package com.example.ai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.LiveGenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.ActivityDetectionConfig
import com.google.firebase.ai.type.ContextWindowCompressionConfig
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.RealtimeInputConfig
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveAudioConversationConfig
import com.google.firebase.ai.type.liveGenerationConfig
import com.google.firebase.ai.type.realtimeInputConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream

enum class LivePhase {
    IDLE,
    CONNECTING,
    LISTENING,
    AI_SPEAKING,
    INTERRUPTED,
    ERROR
}

data class LiveTranscriptEntry(
    val id: Long,
    val speaker: String, // "user" or "model"
    val text: String,
    val wasInterrupted: Boolean = false
)

data class GeminiLiveUiState(
    val phase: LivePhase = LivePhase.IDLE,
    val isScreenSharing: Boolean = false,
    val isCameraSharing: Boolean = false,
    val useFrontCamera: Boolean = false,
    val isMuted: Boolean = false,
    val selectedVoice: String = "AOEDE",
    val liveUserCaption: String = "",
    val liveAiCaption: String = "",
    val transcriptLog: List<LiveTranscriptEntry> = emptyList(),
    val lastActionBadge: String? = null,
    val statusNotice: String? = null,
    val errorMessage: String? = null
) {
    val isActive: Boolean
        get() = phase == LivePhase.CONNECTING ||
            phase == LivePhase.LISTENING ||
            phase == LivePhase.AI_SPEAKING ||
            phase == LivePhase.INTERRUPTED
}

@OptIn(PublicPreviewAPI::class)
object GeminiLiveManager {

    private const val TAG = "GeminiLiveManager"
    private const val LIVE_MODEL_NAME = "gemini-3.1-flash-live-preview"
    private const val VIDEO_FRAME_INTERVAL_MS = 950L
    private const val MAX_VIDEO_DIMENSION = 768

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionMutex = Mutex()

    private val _state = MutableStateFlow(GeminiLiveUiState())
    val state: StateFlow<GeminiLiveUiState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var liveSession: LiveSession? = null
    private var sessionJob: Job? = null
    private var speakingIdleJob: Job? = null
    private var interruptionResetJob: Job? = null
    private var previousAudioMode: Int? = null
    private var previousSpeakerphone: Boolean? = null
    private var previousMicMute: Boolean? = null

    private val userTurnBuffer = StringBuilder()
    private val aiTurnBuffer = StringBuilder()
    private var entrySeq = 0L
    private var lastVideoSentMs = 0L
    private var lastNodeContextSent = ""
    private var lastNodeContextSentMs = 0L

    private val highlightStepDeclaration = FunctionDeclaration(
        name = "highlight_screen_step",
        description = "Display one gentle step-by-step instruction card and highlight the exact target button on the senior's screen.",
        parameters = mapOf(
            "stepTitle" to Schema.string("Short friendly title of the task, e.g. Send Photo to Son."),
            "stepNumber" to Schema.integer("Current 1-based step number."),
            "totalSteps" to Schema.integer("Estimated total steps."),
            "instruction" to Schema.string("ONE clear, jargon-free action the senior should tap or do right now."),
            "targetButtonHint" to Schema.string("Exact visible button label or icon description to spotlight on screen."),
            "reassuranceNote" to Schema.string("Short warm encouraging tip.")
        ),
        optionalParameters = listOf("targetButtonHint", "reassuranceNote")
    )

    private val manageCareCircleDeclaration = FunctionDeclaration(
        name = "manage_care_circle_item",
        description = "Create, complete, update, or delete a daily task, shared calendar event, medication reminder, or voice note for the senior's Care Circle.",
        parameters = mapOf(
            "operation" to Schema.enumeration(listOf("create", "complete", "update", "delete")),
            "entity" to Schema.enumeration(listOf("task", "calendar_event", "medication", "voice_note")),
            "title" to Schema.string("Task title, event title, medication name, or voice note message."),
            "subtitle" to Schema.string("Due time, date, dosage, or extra note."),
            "extra" to Schema.string("Category, time label, or medication slot (Morning/Afternoon/Evening/Bedtime).")
        ),
        optionalParameters = listOf("subtitle", "extra")
    )

    private val flagHelpDeclaration = FunctionDeclaration(
        name = "flag_senior_needs_help",
        description = "Notify the Family Caregiver Dashboard if the senior says they are stuck, lost, or confused.",
        parameters = mapOf(
            "reason" to Schema.string("Brief plain description of what the senior needs help with.")
        )
    )

    fun startLiveSession(
        context: Context,
        voiceName: String = _state.value.selectedVoice,
        initialPrompt: String? = null
    ) {
        appContext = context.applicationContext
        if (_state.value.phase == LivePhase.CONNECTING || _state.value.phase == LivePhase.LISTENING ||
            _state.value.phase == LivePhase.AI_SPEAKING || _state.value.phase == LivePhase.INTERRUPTED
        ) {
            if (!initialPrompt.isNullOrBlank()) {
                sendTextNudge(initialPrompt)
            }
            return
        }

        GuideSessionCoordinator.stopTtsOnly()
        userTurnBuffer.clear()
        aiTurnBuffer.clear()
        lastVideoSentMs = 0L

        _state.update {
            it.copy(
                phase = LivePhase.CONNECTING,
                selectedVoice = voiceName,
                isMuted = false,
                liveUserCaption = "",
                liveAiCaption = "",
                lastActionBadge = null,
                statusNotice = "Connecting to Gemini Live...",
                errorMessage = null
            )
        }

        configureCommunicationAudio(context.applicationContext)

        sessionJob?.cancel()
        sessionJob = scope.launch(Dispatchers.IO) {
            sessionMutex.withLock {
                try {
                    val liveModel = buildLiveModel(voiceName)
                    val session = liveModel.connect()
                    liveSession = session

                    _state.update {
                        it.copy(
                            phase = LivePhase.LISTENING,
                            statusNotice = "Listening — speak naturally or interrupt anytime",
                            errorMessage = null
                        )
                    }

                    if (!initialPrompt.isNullOrBlank()) {
                        launch {
                            delay(250L)
                            runCatching { session.send(initialPrompt, turnComplete = true) }
                        }
                    }

                    @SuppressLint("MissingPermission")
                    val config = liveAudioConversationConfig {
                        enableInterruptions = true
                        functionCallHandler = { call -> handleToolCall(call) }
                        transcriptHandler = { inputT, outputT ->
                            handleTranscriptions(
                                inputText = inputT?.text,
                                outputText = outputT?.text
                            )
                        }
                        goAwayHandler = { goAway ->
                            _state.update {
                                it.copy(statusNotice = "Refreshing Live session soon (${goAway.timeLeft?.inWholeSeconds ?: 0}s left)")
                            }
                        }
                    }

                    session.startAudioConversation(config)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    Log.e(TAG, "Microphone permission missing for Gemini Live", e)
                    _state.update {
                        it.copy(
                            phase = LivePhase.ERROR,
                            statusNotice = null,
                            errorMessage = "Please allow Microphone access so you can talk with Gemini Live."
                        )
                    }
                    cleanupAudioRouting()
                } catch (e: Exception) {
                    Log.e(TAG, "Gemini Live session failed", e)
                    _state.update {
                        it.copy(
                            phase = LivePhase.ERROR,
                            statusNotice = null,
                            errorMessage = e.localizedMessage ?: "Could not connect to Gemini Live. Please try again."
                        )
                    }
                    cleanupAudioRouting()
                }
            }
        }
    }

    fun stopLiveSession() {
        sessionJob?.cancel()
        sessionJob = null
        speakingIdleJob?.cancel()
        interruptionResetJob?.cancel()

        val sessionToClose = liveSession
        liveSession = null

        flushTranscriptTurns()
        cleanupAudioRouting()

        _state.update {
            it.copy(
                phase = LivePhase.IDLE,
                isScreenSharing = false,
                isCameraSharing = false,
                isMuted = false,
                liveUserCaption = "",
                liveAiCaption = "",
                statusNotice = null
            )
        }

        if (sessionToClose != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { sessionToClose.stopAudioConversation() }
                runCatching { sessionToClose.close() }
            }
        }
    }

    fun setScreenSharingActive(active: Boolean) {
        _state.update {
            it.copy(
                isScreenSharing = active,
                isCameraSharing = if (active) false else it.isCameraSharing,
                statusNotice = when {
                    active && it.isActive -> "Sharing your screen live — switch to any app and talk!"
                    !active && it.isActive -> "Screen share stopped. Still listening to your voice."
                    else -> it.statusNotice
                }
            )
        }
        if (active && liveSession != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    liveSession?.sendTextRealtime(
                        "The senior just turned on live screen sharing. Look closely at the incoming screen frames and guide them one gentle step at a time."
                    )
                }
            }
        }
    }

    fun setCameraSharingActive(active: Boolean) {
        _state.update {
            it.copy(
                isCameraSharing = active,
                isScreenSharing = if (active) false else it.isScreenSharing,
                statusNotice = when {
                    active && it.isActive -> "Camera is live — point at any object, letter, or medicine bottle and ask!"
                    !active && it.isActive -> "Camera turned off. Still listening to your voice."
                    else -> it.statusNotice
                }
            )
        }
        if (active && liveSession != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    liveSession?.sendTextRealtime(
                        "The senior just turned on their live camera feed. Look closely at what they show on camera (medicine bottle, mail, appliance, or another screen) and guide them one clear step at a time."
                    )
                }
            }
        }
    }

    fun toggleCameraLens() {
        _state.update {
            it.copy(
                useFrontCamera = !it.useFrontCamera,
                statusNotice = if (!it.useFrontCamera) "Switched to front camera" else "Switched to back camera"
            )
        }
    }

    fun sendScreenFrame(bitmap: Bitmap, sourceLabel: String = "screen") {
        val session = liveSession ?: return
        if (!_state.value.isActive || session.isClosed()) return

        val now = System.currentTimeMillis()
        if (now - lastVideoSentMs < VIDEO_FRAME_INTERVAL_MS) return
        lastVideoSentMs = now

        scope.launch(Dispatchers.IO) {
            runCatching {
                val jpegBytes = encodeJpegFrame(bitmap)
                session.sendVideoRealtime(InlineData(jpegBytes, "image/jpeg"))

                val nodeSummary = GuideSessionCoordinator.getCurrentVisibleNodeSummary()
                if (nodeSummary.isNotBlank() &&
                    (nodeSummary != lastNodeContextSent || now - lastNodeContextSentMs > 4500L)
                ) {
                    lastNodeContextSent = nodeSummary
                    lastNodeContextSentMs = now
                    session.sendTextRealtime("[Live $sourceLabel context] $nodeSummary")
                }
            }.onFailure {
                Log.w(TAG, "Failed sending live screen frame", it)
            }
        }
    }

    fun toggleMute(): Boolean {
        val nextMuted = !_state.value.isMuted
        val ctx = appContext
        if (ctx != null) {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (previousMicMute == null) {
                previousMicMute = am.isMicrophoneMute
            }
            am.isMicrophoneMute = nextMuted
        }
        _state.update {
            it.copy(
                isMuted = nextMuted,
                statusNotice = if (nextMuted) "Microphone muted" else "Microphone unmuted — listening"
            )
        }
        return nextMuted
    }

    fun sendTextNudge(text: String) {
        val clean = text.trim()
        val session = liveSession ?: return
        if (clean.isBlank() || session.isClosed()) return
        appendTranscriptEntry(speaker = "user", text = clean)
        GuideSessionCoordinator.recordLiveTranscriptTurn("user", clean)
        scope.launch(Dispatchers.IO) {
            runCatching {
                session.send(clean, turnComplete = true)
            }.onFailure {
                Log.w(TAG, "sendTextNudge failed", it)
            }
        }
    }

    fun manualInterruptPrompt() {
        val session = liveSession ?: return
        if (session.isClosed()) return
        _state.update {
            it.copy(
                phase = LivePhase.INTERRUPTED,
                statusNotice = "Paused — go ahead, I'm listening..."
            )
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                session.sendStartActivityRealtime()
                delay(120L)
                session.sendStopActivityRealtime()
            }
        }
    }

    fun updateSelectedVoice(voiceName: String) {
        _state.update { it.copy(selectedVoice = voiceName) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null, phase = if (it.phase == LivePhase.ERROR) LivePhase.IDLE else it.phase) }
    }

    private fun buildLiveModel(voiceName: String): LiveGenerativeModel {
        val contextSummary = GuideSessionCoordinator.buildLiveSystemContext()
        val liveConfig = liveGenerationConfig {
            responseModality = ResponseModality.AUDIO
            speechConfig = SpeechConfig(voice = Voice(voiceName))
            inputAudioTranscription = AudioTranscriptionConfig()
            outputAudioTranscription = AudioTranscriptionConfig()
            contextWindowCompression = ContextWindowCompressionConfig()
            realtimeInputConfig = realtimeInputConfig {
                activityHandling = RealtimeInputConfig.ActivityHandling.INTERRUPT
                automaticActivityDetection = ActivityDetectionConfig.builder()
                    .setStartSensitivity(ActivityDetectionConfig.Sensitivity.HIGH)
                    .setEndSensitivity(ActivityDetectionConfig.Sensitivity.LOW)
                    .setSilenceDurationMs(700)
                    .build()
            }
        }

        val systemText = buildString {
            appendLine(
                """
                You are KindGuide Live, a warm, patient, natural voice, screen-sharing, and camera-seeing AI companion for senior citizens.
                HOW TO TALK & GUIDE:
                1. Speak warmly, clearly, and at a comfortable pace in short, friendly sentences. Avoid technical jargon like "UI", "URL", "hamburger menu", or "widget".
                2. NEVER list multiple steps at once! When helping Grandpa or Grandma do anything on their phone or with a physical object/document, explain ONLY ONE small, easy step right now, then pause and wait for them to do it or tell you.
                3. When Screen Sharing is active, inspect their live screen frames and visible button labels, and call `highlight_screen_step` so the exact button lights up and the floating step card updates.
                4. When Camera Sharing is active, read what they are pointing the camera at (such as a medicine bottle, paper mail/bill, TV remote, or appliance), answer clearly, and call `highlight_screen_step` if a step-by-step instruction card is helpful.
                5. If the senior interrupts you while you are speaking, stop immediately and answer their new question cheerfully.
                6. If the senior asks to add, complete, change, or delete a daily task, calendar event, medication reminder, or voice note, call `manage_care_circle_item` right away and confirm warmly.
                7. If the senior sounds lost or asks for extra help, also call `flag_senior_needs_help` so their family caregiver knows.
                """.trimIndent()
            )
            if (contextSummary.isNotBlank()) {
                appendLine()
                appendLine("SENIOR CARE CIRCLE & MEMORY CONTEXT:")
                appendLine(contextSummary)
            }
        }

        return Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = LIVE_MODEL_NAME,
            generationConfig = liveConfig,
            tools = listOf(
                Tool.functionDeclarations(
                    listOf(
                        highlightStepDeclaration,
                        manageCareCircleDeclaration,
                        flagHelpDeclaration
                    )
                )
            ),
            systemInstruction = content { text(systemText) }
        )
    }

    private fun handleTranscriptions(inputText: String?, outputText: String?) {
        if (!inputText.isNullOrBlank()) {
            val wasSpeaking = _state.value.phase == LivePhase.AI_SPEAKING
            if (wasSpeaking) {
                if (aiTurnBuffer.isNotBlank()) {
                    appendTranscriptEntry(
                        speaker = "model",
                        text = aiTurnBuffer.toString().trim(),
                        wasInterrupted = true
                    )
                    GuideSessionCoordinator.recordLiveTranscriptTurn("model", aiTurnBuffer.toString().trim())
                    aiTurnBuffer.clear()
                }
                _state.update {
                    it.copy(
                        phase = LivePhase.INTERRUPTED,
                        liveAiCaption = "",
                        statusNotice = "Interrupted — listening to you..."
                    )
                }
                interruptionResetJob?.cancel()
                interruptionResetJob = scope.launch {
                    delay(750L)
                    if (_state.value.phase == LivePhase.INTERRUPTED) {
                        _state.update {
                            it.copy(
                                phase = LivePhase.LISTENING,
                                statusNotice = if (it.isScreenSharing) {
                                    "Listening • Screen shared live"
                                } else {
                                    "Listening — speak naturally"
                                }
                            )
                        }
                    }
                }
            } else if (_state.value.phase != LivePhase.LISTENING) {
                _state.update { it.copy(phase = LivePhase.LISTENING) }
            }

            if (aiTurnBuffer.isNotBlank() && !wasSpeaking) {
                val finishedAi = aiTurnBuffer.toString().trim()
                appendTranscriptEntry(speaker = "model", text = finishedAi)
                GuideSessionCoordinator.recordLiveTranscriptTurn("model", finishedAi)
                aiTurnBuffer.clear()
            }

            appendSpacingSafe(userTurnBuffer, inputText)
            _state.update { it.copy(liveUserCaption = userTurnBuffer.toString().trim()) }
        }

        if (!outputText.isNullOrBlank()) {
            if (userTurnBuffer.isNotBlank()) {
                val finishedUser = userTurnBuffer.toString().trim()
                appendTranscriptEntry(speaker = "user", text = finishedUser)
                GuideSessionCoordinator.recordLiveTranscriptTurn("user", finishedUser)
                userTurnBuffer.clear()
            }

            appendSpacingSafe(aiTurnBuffer, outputText)
            _state.update {
                it.copy(
                    phase = LivePhase.AI_SPEAKING,
                    liveUserCaption = "",
                    liveAiCaption = aiTurnBuffer.toString().trim(),
                    statusNotice = "Gemini is speaking — interrupt anytime"
                )
            }

            speakingIdleJob?.cancel()
            speakingIdleJob = scope.launch {
                delay(1600L)
                if (_state.value.phase == LivePhase.AI_SPEAKING) {
                    if (aiTurnBuffer.isNotBlank()) {
                        val finishedAi = aiTurnBuffer.toString().trim()
                        appendTranscriptEntry(speaker = "model", text = finishedAi)
                        GuideSessionCoordinator.recordLiveTranscriptTurn("model", finishedAi)
                        aiTurnBuffer.clear()
                    }
                    _state.update {
                        it.copy(
                            phase = LivePhase.LISTENING,
                            statusNotice = if (it.isScreenSharing) {
                                "Listening • Screen shared live"
                            } else {
                                "Listening — speak naturally"
                            }
                        )
                    }
                }
            }
        }
    }

    private fun handleToolCall(call: FunctionCallPart): FunctionResponsePart {
        return when (call.name) {
            "highlight_screen_step" -> {
                val stepTitle = call.args["stepTitle"]?.jsonPrimitive?.contentOrNull ?: "On-Screen Step"
                val stepNumber = call.args["stepNumber"]?.jsonPrimitive?.intOrNull ?: 1
                val totalSteps = call.args["totalSteps"]?.jsonPrimitive?.intOrNull ?: 1
                val instruction = call.args["instruction"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val hint = call.args["targetButtonHint"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val note = call.args["reassuranceNote"]?.jsonPrimitive?.contentOrNull.orEmpty()

                val step = AiGuideStep(
                    stepTitle = stepTitle,
                    stepNumber = stepNumber.coerceAtLeast(1),
                    totalSteps = totalSteps.coerceAtLeast(1),
                    instruction = instruction,
                    targetButtonHint = hint,
                    reassuranceNote = note,
                    suggestedAppName = ""
                )
                GuideSessionCoordinator.setLiveGuideStep(step)
                _state.update { it.copy(lastActionBadge = "Step $stepNumber: $stepTitle") }
                FunctionResponsePart(
                    name = call.name,
                    response = JsonObject(
                        mapOf(
                            "status" to JsonPrimitive("ok"),
                            "highlighted" to JsonPrimitive(hint)
                        )
                    )
                )
            }

            "manage_care_circle_item" -> {
                val op = call.args["operation"]?.jsonPrimitive?.contentOrNull ?: "create"
                val entity = call.args["entity"]?.jsonPrimitive?.contentOrNull ?: "task"
                val title = call.args["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val subtitle = call.args["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val extra = call.args["extra"]?.jsonPrimitive?.contentOrNull.orEmpty()

                GuideSessionCoordinator.executeLiveCrud(
                    AiCrudCommand(
                        operation = op,
                        entity = entity,
                        title = title,
                        subtitle = subtitle,
                        extra = extra
                    )
                )
                _state.update {
                    it.copy(lastActionBadge = "${op.replaceFirstChar { c -> c.uppercase() }}d $entity: $title")
                }
                FunctionResponsePart(
                    name = call.name,
                    response = JsonObject(
                        mapOf(
                            "status" to JsonPrimitive("ok"),
                            "operation" to JsonPrimitive(op),
                            "entity" to JsonPrimitive(entity),
                            "title" to JsonPrimitive(title)
                        )
                    )
                )
            }

            "flag_senior_needs_help" -> {
                val reason = call.args["reason"]?.jsonPrimitive?.contentOrNull ?: "Senior asked for extra help"
                GuideSessionCoordinator.flagLiveStuckHelp(reason)
                _state.update { it.copy(lastActionBadge = "Family notified to check in") }
                FunctionResponsePart(
                    name = call.name,
                    response = JsonObject(
                        mapOf("status" to JsonPrimitive("ok"))
                    )
                )
            }

            else -> FunctionResponsePart(
                name = call.name,
                response = JsonObject(
                    mapOf("error" to JsonPrimitive("Unknown tool ${call.name}"))
                )
            )
        }
    }

    private fun appendTranscriptEntry(speaker: String, text: String, wasInterrupted: Boolean = false) {
        if (text.isBlank()) return
        entrySeq += 1L
        val entry = LiveTranscriptEntry(
            id = entrySeq,
            speaker = speaker,
            text = text,
            wasInterrupted = wasInterrupted
        )
        _state.update { current ->
            current.copy(transcriptLog = (current.transcriptLog + entry).takeLast(30))
        }
    }

    private fun flushTranscriptTurns() {
        if (userTurnBuffer.isNotBlank()) {
            val u = userTurnBuffer.toString().trim()
            appendTranscriptEntry("user", u)
            GuideSessionCoordinator.recordLiveTranscriptTurn("user", u)
            userTurnBuffer.clear()
        }
        if (aiTurnBuffer.isNotBlank()) {
            val a = aiTurnBuffer.toString().trim()
            appendTranscriptEntry("model", a)
            GuideSessionCoordinator.recordLiveTranscriptTurn("model", a)
            aiTurnBuffer.clear()
        }
    }

    private fun appendSpacingSafe(sb: StringBuilder, piece: String) {
        val clean = piece
        if (sb.isNotEmpty() &&
            !sb.last().isWhitespace() &&
            !clean.first().isWhitespace() &&
            clean.first() !in listOf('.', ',', '!', '?', ':', ';', '\'')
        ) {
            // Only insert space when streaming words if needed; transcription chunks often include leading spaces.
            if (sb.last().isLetterOrDigit() && clean.first().isLetterOrDigit() && clean.length > 2) {
                sb.append(' ')
            }
        }
        sb.append(clean)
    }

    private fun encodeJpegFrame(bitmap: Bitmap): ByteArray {
        val safeBitmap = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        val width = safeBitmap.width
        val height = safeBitmap.height
        val scaled = if (width > MAX_VIDEO_DIMENSION || height > MAX_VIDEO_DIMENSION) {
            val ratio = MAX_VIDEO_DIMENSION.toFloat() / maxOf(width, height).toFloat()
            Bitmap.createScaledBitmap(
                safeBitmap,
                (width * ratio).toInt().coerceAtLeast(1),
                (height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            safeBitmap
        }
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 65, stream)
        if (scaled !== safeBitmap) scaled.recycle()
        if (safeBitmap !== bitmap) safeBitmap.recycle()
        return stream.toByteArray()
    }

    private fun configureCommunicationAudio(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            previousAudioMode = am.mode
            previousSpeakerphone = @Suppress("DEPRECATION") am.isSpeakerphoneOn
            previousMicMute = am.isMicrophoneMute

            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isMicrophoneMute = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speaker != null) {
                    am.setCommunicationDevice(speaker)
                }
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
        }.onFailure {
            Log.w(TAG, "Unable to configure speakerphone communication audio", it)
        }
    }

    private fun cleanupAudioRouting() {
        val ctx = appContext ?: return
        runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            previousMicMute?.let { am.isMicrophoneMute = it }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                previousSpeakerphone?.let { am.isSpeakerphoneOn = it }
            }
            previousAudioMode?.let { am.mode = it }
        }
        previousMicMute = null
        previousAudioMode = null
        previousSpeakerphone = null
    }
}
