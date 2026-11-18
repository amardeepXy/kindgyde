package com.example.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.Firebase
import com.google.firebase.ai.LiveGenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.ActivityDetectionConfig
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.ContextWindowCompressionConfig
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.InlineData
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.LiveServerContent
import com.google.firebase.ai.type.LiveServerGoAway
import com.google.firebase.ai.type.LiveServerToolCall
import com.google.firebase.ai.type.LiveServerToolCallCancellation
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.RealtimeInputConfig
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.SlidingWindow
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.TextPart
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class LivePhase {
    IDLE,
    CONNECTING,
    LISTENING,
    AI_SPEAKING,
    INTERRUPTED,
    ERROR
}

enum class LiveTonePreset(
    val id: String,
    val label: String,
    val directive: String
) {
    WARM_PATIENT(
        id = "warm_patient",
        label = "Warm & Patient",
        directive = "Speak warmly, gently, and reassuringly at a relaxed pace with kind encouragement."
    ),
    CONCISE_DIRECT(
        id = "concise_direct",
        label = "Concise",
        directive = "Keep answers crisp, brief, and straight to the point in 1 to 2 short clear sentences."
    ),
    CHEERFUL_UPBEAT(
        id = "cheerful_upbeat",
        label = "Cheerful",
        directive = "Sound joyful, lively, bright, and uplifting like an energetic helpful friend."
    ),
    SLOW_TEACHER(
        id = "slow_teacher",
        label = "Slow Teacher",
        directive = "Speak extra slowly and distinctly, breaking ideas down gently like a patient one-on-one teacher."
    )
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
    val selectedTone: LiveTonePreset = LiveTonePreset.WARM_PATIENT,
    val isSwitchingVoice: Boolean = false,
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

private data class LiveAudioChunk(
    val epoch: Long,
    val pcmBytes: ByteArray
)

@OptIn(PublicPreviewAPI::class)
object GeminiLiveManager {

    private const val TAG = "GeminiLiveManager"
    private const val PREFS_LIVE_SESSION = "kindguide_live_session_prefs"
    private const val KEY_LAST_WORKING_MODEL = "last_working_live_model"
    private val LIVE_MODEL_CANDIDATES = listOf(
        "gemini-2.5-flash-native-audio-preview-12-2025",
        "gemini-3.1-flash-live-preview",
        "gemini-2.5-flash-native-audio-preview-09-2025"
    )
    private const val CONNECT_TIMEOUT_MS = 5_000L
    private const val VIDEO_FRAME_INTERVAL_MS = 1200L
    private const val FAST_SPEECH_COMMIT_MS = 700L
    private const val SILENT_STALL_WATCHDOG_MS = 5_000L
    private const val SPEECH_HANGOVER_MS = 560L
    private const val MAX_VIDEO_DIMENSION = 768

    private const val INPUT_SAMPLE_RATE_HZ = 16_000
    private const val OUTPUT_SAMPLE_RATE_HZ = 24_000
    private const val INPUT_FRAME_BYTES = 2560 // 80ms @ 16kHz 16-bit mono PCM (12.5 JSON frames/s)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionMutex = Mutex()
    private val audioTrackLock = Any()

    private val priorityOutboundQueue = ConcurrentLinkedQueue<suspend (LiveSession) -> Unit>()
    private val audioOutboundQueue = ArrayBlockingQueue<ByteArray>(64)
    private val pendingVideoFrame = AtomicReference<ByteArray?>(null)
    private var outboundSignal = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow(GeminiLiveUiState())
    val state: StateFlow<GeminiLiveUiState> = _state.asStateFlow()

    private var appContext: Context? = null
    @Volatile
    private var liveSession: LiveSession? = null
    private var sessionJob: Job? = null
    private var speakingIdleJob: Job? = null
    private var interruptionResetJob: Job? = null
    private var userSpeechSettleJob: Job? = null
    @Volatile
    private var userRequestedStop = false

    private var previousAudioMode: Int? = null
    private var previousSpeakerphone: Boolean? = null
    private var previousMicMute: Boolean? = null

    private var audioRecord: AudioRecord? = null
    private var playbackAudioTrack: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var audioPlaybackChannel = Channel<LiveAudioChunk>(Channel.UNLIMITED)
    private val playbackEpoch = AtomicLong(0L)
    private val queuedAudioBytes = AtomicInteger(0)
    @Volatile
    private var isWritingAudioTrack = false

    private val inFlightToolJobs = ConcurrentHashMap<String, Job>()
    private val activeToolCount = AtomicInteger(0)

    private val userTurnBuffer = StringBuilder()
    private val aiTurnBuffer = StringBuilder()
    private val turnTextPartBuffer = StringBuilder()
    @Volatile
    private var turnReceivedAudioBytes = false

    private var entrySeq = 0L
    private var lastVideoSentMs = 0L
    private var lastInterruptedAtMs = 0L
    private var lastUserSpeechChunkAtMs = 0L
    private var lastAiOutputChunkAtMs = 0L
    private var lastToolCallAtMs = 0L
    private var lastToolCallFinishedAtMs = 0L
    private var currentTurnId = 0L
    private var toolCalledForTurnId = -1L
    private var handledFallbackForTurnId = -1L
    private var lastActionNotificationText = ""
    private var lastActionNotificationAtMs = 0L
    private var lastCommittedAiTurn = ""
    private var lastWatchdogSentUserText = ""
    private var lastCompletedUserTurnText = ""
    @Volatile
    private var adaptiveNoiseFloorRms = 85.0
    @Volatile
    private var lastMicVoiceActiveAtMs = 0L

    private val inspectScreenDeclaration = FunctionDeclaration(
        name = "inspect_current_screen",
        description = "On-demand screen inspection: Call this when the user asks about what is on their screen or asks for help performing a task on their phone. Returns the verified foreground app name and visible clickable UI elements.",
        parameters = mapOf(
            "taskGoal" to Schema.string("Brief description of why the screen is being inspected.")
        ),
        optionalParameters = listOf("taskGoal")
    )

    private val highlightStepDeclaration = FunctionDeclaration(
        name = "highlight_screen_step",
        description = "Update the on-screen step guidance. Only use guidanceMode='HIGHLIGHT_BUTTON' when the exact target button is verified in the current screen's visible UI elements; use 'SWIPE_HINT' or 'SEARCH_HINT' when swiping or searching is needed.",
        parameters = mapOf(
            "stepTitle" to Schema.string("Short friendly title of the task, e.g. Search on Chrome or Send Message on WhatsApp."),
            "stepNumber" to Schema.integer("Current 1-based step number."),
            "totalSteps" to Schema.integer("Estimated total steps."),
            "instruction" to Schema.string("ONE clear, screen-accurate instruction matching the current screen."),
            "guidanceMode" to Schema.enumeration(
                listOf("HIGHLIGHT_BUTTON", "SWIPE_HINT", "SEARCH_HINT", "CONFIRM_SEND"),
                "Use HIGHLIGHT_BUTTON only when a specific visible button on the current screen should be highlighted."
            ),
            "targetButtonHint" to Schema.string("Exact visible button text or contact label on the current screen (leave empty for SWIPE_HINT or SEARCH_HINT)."),
            "reassuranceNote" to Schema.string("Short warm tip or encouragement."),
            "isCorrection" to Schema.boolean("True only if the user tapped a wrong item and needs to recover.")
        ),
        optionalParameters = listOf(
            "guidanceMode",
            "targetButtonHint",
            "reassuranceNote",
            "isCorrection"
        )
    )

    private val performScreenActionDeclaration = FunctionDeclaration(
        name = "perform_screen_action",
        description = "Universal verified smartphone action tool: Executes ONE single step at a time and verifies that the step succeeded on screen before returning. Supports opening any installed app (OPEN_APP: Chrome, WhatsApp, YouTube, Maps, Settings, Gmail, Phone, Messages, etc.), searching the web (WEB_SEARCH), tapping a visible contact/button/result (TAP_ELEMENT), swiping/scrolling (SWIPE_SCREEN), typing text into a verified search bar or chat message box (TYPE_TEXT), or pressing system Back/Home (SYSTEM_NAV).",
        parameters = mapOf(
            "actionType" to Schema.enumeration(
                listOf("OPEN_APP", "WEB_SEARCH", "TAP_ELEMENT", "SWIPE_SCREEN", "TYPE_TEXT", "SYSTEM_NAV"),
                "Type of assistive screen action to perform."
            ),
            "targetLabel" to Schema.string("For OPEN_APP: app name (e.g. 'WhatsApp', 'Chrome'). For TAP_ELEMENT: exact visible contact name or button (e.g. 'Amardeep Sharma', 'Ask Meta AI or Search', 'Send'). For TYPE_TEXT: 'Message' when typing a chat message at the bottom of an open chat, or 'Search' when typing a contact name/search query."),
            "swipeDirection" to Schema.enumeration(
                listOf("LEFT", "RIGHT", "UP", "DOWN"),
                "Direction to swipe when actionType is SWIPE_SCREEN."
            ),
            "textToType" to Schema.string("Text to enter when actionType is TYPE_TEXT or WEB_SEARCH."),
            "submitAfterType" to Schema.boolean("Set to true ONLY when typing a search query into a search bar (in Chrome, YouTube, WhatsApp Search) to press Search/Enter. Set to false when typing a chat message."),
            "expectedAppName" to Schema.string("Name of the app where this action takes place (e.g. 'WhatsApp', 'Chrome', 'YouTube')."),
            "systemNavAction" to Schema.enumeration(
                listOf("BACK", "HOME"),
                "System navigation action when actionType is SYSTEM_NAV."
            ),
            "spokenExplanation" to Schema.string("Short spoken progress update for the senior, e.g. 'Opening Amardeep Sharma chat' or 'Ready to send Have you taken food?'."),
            "isFinalSendOrCommit" to Schema.boolean("Set to true when tapping Send/Pay/Delete for the first time so KindGuide pauses and shows the '✅ Send Now / ✕ Cancel' confirmation card."),
            "userConfirmed" to Schema.boolean("Set to true ONLY after the senior verbally says 'Yes', 'Send it', or 'Go ahead' to confirm sending.")
        ),
        optionalParameters = listOf(
            "targetLabel",
            "swipeDirection",
            "textToType",
            "submitAfterType",
            "expectedAppName",
            "systemNavAction",
            "spokenExplanation",
            "isFinalSendOrCommit",
            "userConfirmed"
        )
    )

    private val completeGuidedTaskDeclaration = FunctionDeclaration(
        name = "complete_guided_task",
        description = "Mark the current on-screen guided task as completed and turn off on-demand screen inspection when finished.",
        parameters = mapOf(
            "taskTitle" to Schema.string("Title of the guided task that was completed."),
            "congratulationMessage" to Schema.string("Warm congratulatory message for finishing the task.")
        )
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

    private val triggerQuickSosDeclaration = FunctionDeclaration(
        name = "trigger_quick_sos",
        description = "Immediately trigger an urgent Quick SOS alert with safety photos and location to the Family Caregiver Dashboard when the senior asks to send Quick SOS or says they have an emergency.",
        parameters = mapOf(
            "reason" to Schema.string("Brief description of why Quick SOS was triggered.")
        ),
        optionalParameters = listOf("reason")
    )

    fun clearTranscriptForNewChat() {
        userTurnBuffer.clear()
        aiTurnBuffer.clear()
        turnTextPartBuffer.clear()
        turnReceivedAudioBytes = false
        lastCommittedAiTurn = ""
        lastWatchdogSentUserText = ""
        lastCompletedUserTurnText = ""
        currentTurnId = 0L
        toolCalledForTurnId = -1L
        handledFallbackForTurnId = -1L
        InterruptController.reset()
        _state.update {
            it.copy(
                transcriptLog = emptyList(),
                liveUserCaption = "",
                liveAiCaption = "",
                lastActionBadge = null,
                errorMessage = null
            )
        }
    }

    fun markConnectingPending(voiceName: String = _state.value.selectedVoice) {
        if (sessionJob?.isActive == true || _state.value.isActive) return
        _state.update {
            it.copy(
                phase = LivePhase.CONNECTING,
                selectedVoice = voiceName,
                statusNotice = "Connecting to Google Gemini Live Voice...",
                errorMessage = null
            )
        }
    }

    fun startLiveSession(
        context: Context,
        voiceName: String = _state.value.selectedVoice,
        initialPrompt: String? = null,
        preserveActiveShares: Boolean = false
    ) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (sessionJob?.isActive == true && _state.value.isActive && !preserveActiveShares) {
            if (!initialPrompt.isNullOrBlank()) {
                sendTextNudge(initialPrompt)
            }
            return
        }

        userRequestedStop = false
        GuideSessionCoordinator.stopTtsOnly()
        if (!preserveActiveShares) {
            GuideSessionCoordinator.setScreenTaskActive(false)
        }
        resetSessionRuntimeState()
        InterruptController.reset()

        _state.update {
            it.copy(
                phase = LivePhase.CONNECTING,
                selectedVoice = voiceName,
                isMuted = if (preserveActiveShares) it.isMuted else false,
                isSwitchingVoice = preserveActiveShares,
                liveUserCaption = "",
                liveAiCaption = "",
                lastActionBadge = null,
                statusNotice = if (preserveActiveShares) {
                    "Switching Gemini voice smoothly..."
                } else {
                    "Connecting to Google Gemini Live Voice..."
                },
                errorMessage = null
            )
        }

        configureCommunicationAudio(appCtx)

        sessionJob?.cancel()
        sessionJob = scope.launch(Dispatchers.IO) {
            var activeSession: LiveSession? = null
            try {
                if (ContextCompat.checkSelfPermission(appCtx, Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    throw SecurityException("Microphone permission not granted")
                }

                activeSession = sessionMutex.withLock {
                    connectWithModelFallback(
                        voiceName = voiceName,
                        tonePreset = _state.value.selectedTone
                    ).also { liveSession = it }
                }

                prepareAudioHardware(appCtx)

                val curState = _state.value
                _state.update {
                    it.copy(
                        phase = LivePhase.LISTENING,
                        isSwitchingVoice = false,
                        statusNotice = when {
                            curState.isScreenSharing -> "Watching screen & listening • Speak anytime"
                            curState.isCameraSharing -> "Camera live • Point and ask anything"
                            else -> "Gemini Voice ready — speak naturally or interrupt anytime"
                        },
                        errorMessage = null
                    )
                }

                coroutineScope {
                    val outboundJob = launch(Dispatchers.IO) {
                        runOutboundLoop(activeSession)
                    }
                    val playbackJob = launch(Dispatchers.IO) {
                        runAudioPlaybackLoop()
                    }
                    val receiveJob = launch(Dispatchers.IO) {
                        runReceiveLoop(activeSession)
                    }

                    // Send opening clientContent turn BEFORE mic capture starts streaming realtimeInput,
                    // preventing clientContent + realtimeInput collision and ambient noise pre-turn interference.
                    try {
                        if (!initialPrompt.isNullOrBlank()) {
                            sendPromptDirect(activeSession, initialPrompt)
                        } else if (preserveActiveShares) {
                            val recentSnippet = _state.value.transcriptLog.takeLast(4).joinToString(" | ") {
                                "${if (it.speaker == "model") "KindGuide" else "Senior"}: ${it.text}"
                            }
                            if (recentSnippet.isNotBlank() && !activeSession.isClosed()) {
                                activeSession.send(
                                    "[Voice persona switched — continue listening or speaking smoothly without repeating your previous answer. Recent context: $recentSnippet]",
                                    turnComplete = true
                                )
                            }
                        } else if (!activeSession.isClosed()) {
                            val seniorName = GuideSessionCoordinator.currentCircle?.seniorName
                                ?.trim()
                                .orEmpty()
                                .ifBlank { "friend" }
                            activeSession.send(
                                "[KINDGUIDE LIVE OPENED — SPEAK FIRST NOW]: Greet $seniorName warmly by name in 1 short, friendly sentence (for example: 'Hello $seniorName! I'm right here with you—how can I help you today?') and listen.",
                                turnComplete = true
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Opening Live turn failed; continuing with voice capture", e)
                    }

                    val micJob = launch(Dispatchers.IO) {
                        runMicCaptureLoop(activeSession)
                    }

                    receiveJob.join()
                    micJob.cancel()
                    outboundJob.cancel()
                    playbackJob.cancel()
                }

                // If receive() completed naturally without user requesting stop, inform & speak error clearly
                if (!userRequestedStop && _state.value.phase != LivePhase.ERROR) {
                    val closedMsg = "Live voice connection ended. Tap Try Again to reconnect."
                    handleLiveFailure(closedMsg)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                Log.e(TAG, "Microphone permission missing for Gemini Live", e)
                val msg = "Please allow Microphone access so you can talk with Gemini Live."
                handleLiveFailure(msg)
            } catch (e: Exception) {
                Log.e(TAG, "Gemini Live session failed", e)
                val msg = e.localizedMessage?.takeIf { it.isNotBlank() }
                    ?: "Could not connect to Gemini Live. Please check your connection and try again."
                handleLiveFailure(msg)
            } finally {
                cancelInFlightTools()
                releaseAudioResources()
                cleanupAudioRouting()
                val sessionToClose = activeSession
                if (liveSession === sessionToClose) {
                    liveSession = null
                }
                if (sessionToClose != null) {
                    runCatching { sessionToClose.stopReceiving() }
                    runCatching { sessionToClose.close() }
                }
            }
        }
    }

    private fun handleLiveFailure(message: String) {
        flushTranscriptTurns()
        _state.update {
            it.copy(
                phase = LivePhase.ERROR,
                isSwitchingVoice = false,
                liveUserCaption = "",
                liveAiCaption = "",
                statusNotice = null,
                errorMessage = message
            )
        }
        if (GuideSessionCoordinator.voiceReadAloudEnabled.value) {
            GuideSessionCoordinator.speakLocalDirectly(
                text = message,
                messageId = "live_error"
            )
        }
    }

    private fun resetSessionRuntimeState() {
        userTurnBuffer.clear()
        aiTurnBuffer.clear()
        turnTextPartBuffer.clear()
        turnReceivedAudioBytes = false
        lastVideoSentMs = 0L
        lastInterruptedAtMs = 0L
        lastUserSpeechChunkAtMs = 0L
        lastAiOutputChunkAtMs = 0L
        lastToolCallAtMs = 0L
        lastToolCallFinishedAtMs = 0L
        activeToolCount.set(0)
        currentTurnId = 0L
        toolCalledForTurnId = -1L
        handledFallbackForTurnId = -1L
        lastActionNotificationText = ""
        lastActionNotificationAtMs = 0L
        lastWatchdogSentUserText = ""
        lastCompletedUserTurnText = ""
        adaptiveNoiseFloorRms = 85.0
        lastMicVoiceActiveAtMs = 0L
        queuedAudioBytes.set(0)
        playbackEpoch.incrementAndGet()
        drainAudioChannel()
        clearOutboundQueues()
        runCatching { outboundSignal.close() }
        outboundSignal = Channel(Channel.CONFLATED)
    }

    private fun orderedLiveModelCandidates(): List<String> {
        val cached = runCatching {
            appContext?.getSharedPreferences(PREFS_LIVE_SESSION, Context.MODE_PRIVATE)
                ?.getString(KEY_LAST_WORKING_MODEL, null)
        }.getOrNull()
        return if (!cached.isNullOrBlank() && cached in LIVE_MODEL_CANDIDATES) {
            listOf(cached) + LIVE_MODEL_CANDIDATES.filterNot { it == cached }
        } else {
            LIVE_MODEL_CANDIDATES
        }
    }

    private suspend fun connectWithModelFallback(
        voiceName: String,
        tonePreset: LiveTonePreset
    ): LiveSession {
        var lastError: Exception? = null
        for (modelName in orderedLiveModelCandidates()) {
            try {
                Log.d(TAG, "Connecting to Gemini Live model: $modelName (voice=$voiceName)")
                val session = withTimeout(CONNECT_TIMEOUT_MS) {
                    buildLiveModel(modelName, voiceName, tonePreset).connect()
                }
                runCatching {
                    appContext?.getSharedPreferences(PREFS_LIVE_SESSION, Context.MODE_PRIVATE)
                        ?.edit()
                        ?.putString(KEY_LAST_WORKING_MODEL, modelName)
                        ?.apply()
                }
                return session
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Live model $modelName failed to connect (${e.message}), trying next candidate")
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("Unable to connect to Gemini Live.")
    }

    @SuppressLint("MissingPermission")
    private fun prepareAudioHardware(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Microphone permission required")
        }

        playbackAudioTrack?.let {
            runCatching {
                it.pause()
                it.flush()
                it.release()
            }
        }
        playbackAudioTrack = null

        val minTrackBuf = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)
        val trackBufferSize = maxOf(minTrackBuf * 2, 8_192)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUTPUT_SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(trackBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("Failed to initialize voice playback audio track.")
        }
        track.play()
        playbackAudioTrack = track

        audioRecord?.let {
            runCatching {
                it.stop()
                it.release()
            }
        }
        audioRecord = null
        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null

        val minRecBuf = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(INPUT_FRAME_BYTES)
        val recBufferSize = maxOf(minRecBuf * 2, INPUT_FRAME_BYTES * 2)

        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(INPUT_SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(recBufferSize)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("Failed to initialize microphone recording.")
        }

        val sessionId = record.audioSessionId
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
        }
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        }

        record.startRecording()
        audioRecord = record

        audioPlaybackChannel.close()
        audioPlaybackChannel = Channel(Channel.UNLIMITED)
    }

    private fun clearOutboundQueues() {
        priorityOutboundQueue.clear()
        audioOutboundQueue.clear()
        pendingVideoFrame.set(null)
    }

    private fun enqueuePriorityOutbound(block: suspend (LiveSession) -> Unit) {
        priorityOutboundQueue.add(block)
        outboundSignal.trySend(Unit)
    }

    private fun enqueueAudioFrame(pcmBytes: ByteArray) {
        while (!audioOutboundQueue.offer(pcmBytes)) {
            audioOutboundQueue.poll()
        }
        outboundSignal.trySend(Unit)
    }

    private fun enqueueVideoFrame(jpegBytes: ByteArray) {
        pendingVideoFrame.set(jpegBytes)
        outboundSignal.trySend(Unit)
    }

    private suspend fun runOutboundLoop(session: LiveSession) = withContext(Dispatchers.IO) {
        val signal = outboundSignal
        for (@Suppress("UNUSED_VARIABLE") wake in signal) {
            while (isActive && !session.isClosed()) {
                val priorityAction = priorityOutboundQueue.poll()
                if (priorityAction != null) {
                    runCatching { priorityAction(session) }
                        .onFailure { Log.w(TAG, "Priority outbound send failed", it) }
                    continue
                }

                val audioBytes = audioOutboundQueue.poll()
                if (audioBytes != null) {
                    runCatching {
                        session.sendAudioRealtime(InlineData(audioBytes, "audio/pcm"))
                    }.onFailure { err ->
                        Log.w(TAG, "sendAudioRealtime failed", err)
                    }
                    continue
                }

                val videoBytes = pendingVideoFrame.getAndSet(null)
                if (videoBytes != null) {
                    runCatching {
                        session.sendVideoRealtime(InlineData(videoBytes, "image/jpeg"))
                    }.onFailure { err ->
                        Log.w(TAG, "sendVideoRealtime failed", err)
                    }
                    continue
                }

                break
            }
        }
    }

    private suspend fun runAudioPlaybackLoop() = withContext(Dispatchers.IO) {
        val channel = audioPlaybackChannel
        for (chunk in channel) {
            if (!isActive) break
            if (chunk.epoch != playbackEpoch.get()) {
                queuedAudioBytes.addAndGet(-chunk.pcmBytes.size).coerceAtLeast(0)
                continue
            }
            isWritingAudioTrack = true
            try {
                var offset = 0
                val data = chunk.pcmBytes
                while (offset < data.size && isActive && chunk.epoch == playbackEpoch.get()) {
                    val sliceLen = minOf(data.size - offset, 1920) // 40ms slice @ 24kHz 16-bit mono
                    val written = synchronized(audioTrackLock) {
                        val activeTrack = playbackAudioTrack
                        if (activeTrack == null ||
                            activeTrack.state != AudioTrack.STATE_INITIALIZED ||
                            chunk.epoch != playbackEpoch.get()
                        ) {
                            -1
                        } else {
                            runCatching {
                                activeTrack.write(data, offset, sliceLen, AudioTrack.WRITE_NON_BLOCKING)
                            }.getOrDefault(-1)
                        }
                    }
                    if (written < 0) break
                    if (written == 0) {
                        delay(8L)
                    } else {
                        offset += written
                    }
                }
            } finally {
                isWritingAudioTrack = false
                val remaining = queuedAudioBytes.addAndGet(-chunk.pcmBytes.size)
                if (remaining < 0) queuedAudioBytes.set(0)
            }
        }
    }

    internal fun resetMicNoiseGateForTest() {
        adaptiveNoiseFloorRms = 85.0
        lastMicVoiceActiveAtMs = 0L
    }

    internal fun computePcm16Rms(pcmBytes: ByteArray, length: Int = pcmBytes.size): Double {
        val samplePairs = minOf(length, pcmBytes.size) / 2
        if (samplePairs <= 0) return 0.0
        var sumSquares = 0.0
        var i = 0
        val byteLimit = samplePairs * 2
        while (i < byteLimit) {
            val lo = pcmBytes[i].toInt() and 0xFF
            val hi = pcmBytes[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toDouble()
            sumSquares += sample * sample
            i += 2
        }
        return kotlin.math.sqrt(sumSquares / samplePairs)
    }

    /**
     * Soft adaptive noise & speaker-echo gate that preserves continuous 16kHz PCM framing:
     * real voice and a 560ms trailing hangover window pass untouched, while steady room static
     * or speaker bleed below threshold becomes clean digital silence so Gemini Live's server
     * VAD (`silenceDurationMs`) closes user turns immediately instead of hanging open.
     */
    internal fun processMicFrameForLive(
        pcmBytes: ByteArray,
        isAiSpeaking: Boolean,
        hasHardwareAec: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): ByteArray {
        if (pcmBytes.isEmpty()) return pcmBytes
        val rms = computePcm16Rms(pcmBytes)
        if (!isAiSpeaking || hasHardwareAec) {
            adaptiveNoiseFloorRms = if (rms < adaptiveNoiseFloorRms) {
                adaptiveNoiseFloorRms * 0.85 + rms * 0.15
            } else if (rms < 200.0) {
                adaptiveNoiseFloorRms * 0.98 + rms * 0.02
            } else {
                adaptiveNoiseFloorRms
            }
        }
        val gateThreshold = if (isAiSpeaking && !hasHardwareAec) {
            maxOf(adaptiveNoiseFloorRms * 2.5, 650.0)
        } else {
            (adaptiveNoiseFloorRms * 1.9).coerceIn(110.0, 260.0)
        }
        if (rms >= gateThreshold) {
            lastMicVoiceActiveAtMs = nowMs
            return pcmBytes
        }
        val withinHangover = (!isAiSpeaking || hasHardwareAec) &&
            lastMicVoiceActiveAtMs > 0L &&
            (nowMs - lastMicVoiceActiveAtMs) in 0L..SPEECH_HANGOVER_MS
        return if (withinHangover) pcmBytes else ByteArray(pcmBytes.size)
    }

    private suspend fun runMicCaptureLoop(session: LiveSession) = withContext(Dispatchers.IO) {
        val frameBuffer = ByteArray(INPUT_FRAME_BYTES)
        while (isActive && !session.isClosed()) {
            val record = audioRecord ?: break
            var offset = 0
            var readError = false
            while (offset < frameBuffer.size && isActive && !session.isClosed()) {
                val readCount = record.read(
                    frameBuffer,
                    offset,
                    frameBuffer.size - offset,
                    AudioRecord.READ_BLOCKING
                )
                if (readCount > 0) {
                    offset += readCount
                } else if (readCount < 0) {
                    readError = true
                    break
                } else {
                    break
                }
            }
            if (offset > 0) {
                val shouldPauseUpload = _state.value.isMuted || _state.value.isSwitchingVoice
                if (!shouldPauseUpload) {
                    val rawFrame = if (offset == frameBuffer.size) {
                        frameBuffer.copyOf()
                    } else {
                        frameBuffer.copyOf(offset)
                    }
                    val gatedFrame = processMicFrameForLive(
                        pcmBytes = rawFrame,
                        isAiSpeaking = _state.value.phase == LivePhase.AI_SPEAKING,
                        hasHardwareAec = echoCanceler != null
                    )
                    enqueueAudioFrame(gatedFrame)
                }
            }
            if (readError) {
                delay(15L)
            }
        }
    }

    private suspend fun runReceiveLoop(session: LiveSession) = withContext(Dispatchers.IO) {
        session.receive().collect { message ->
            when (message) {
                is LiveServerContent -> handleServerContent(message)

                is LiveServerToolCall -> {
                    val calls = message.functionCalls
                    if (calls.isNotEmpty()) {
                        // Launch tool execution in child coroutines so receive() stays 100% unblocked
                        // and incoming voice interruptions / transcriptions arrive immediately.
                        val responsesDeferred = calls.map { call ->
                            val jobKey = call.id?.takeIf { it.isNotBlank() }
                                ?: "${call.name}_${System.nanoTime()}"
                            val deferred = scope.async(Dispatchers.IO) {
                                activeToolCount.incrementAndGet()
                                lastToolCallAtMs = System.currentTimeMillis()
                                toolCalledForTurnId = currentTurnId
                                handledFallbackForTurnId = currentTurnId
                                lastCompletedUserTurnText = ""
                                try {
                                    handleToolCall(call)
                                } finally {
                                    inFlightToolJobs.remove(jobKey)
                                    activeToolCount.decrementAndGet().coerceAtLeast(0)
                                    lastToolCallFinishedAtMs = System.currentTimeMillis()
                                }
                            }
                            inFlightToolJobs[jobKey] = deferred
                            deferred
                        }
                        scope.launch(Dispatchers.IO) {
                            try {
                                val responses = responsesDeferred.awaitAll()
                                if (!session.isClosed()) {
                                    enqueuePriorityOutbound { active ->
                                        if (!active.isClosed()) {
                                            active.sendFunctionResponse(responses)
                                        }
                                    }
                                }
                            } catch (_: CancellationException) {
                                // Cancelled via LiveServerToolCallCancellation or InterruptController
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed sending function response(s)", e)
                            }
                        }
                    }
                }

                is LiveServerToolCallCancellation -> {
                    message.functionIds.forEach { id ->
                        inFlightToolJobs.remove(id)?.cancel()
                    }
                    InterruptController.abortActiveTask("Gemini Live tool call cancelled")
                    _state.update {
                        it.copy(
                            statusNotice = "Cancelled action — listening to you"
                        )
                    }
                }

                is LiveServerGoAway -> {
                    val secsLeft = message.timeLeft?.inWholeSeconds ?: 0L
                    _state.update {
                        it.copy(
                            statusNotice = "Refreshing Live session soon (${secsLeft}s left)"
                        )
                    }
                }
            }
        }
    }

    private fun handleServerContent(msg: LiveServerContent) {
        if (msg.interrupted) {
            onServerInterrupted()
        }

        val inputText = msg.inputTranscription?.text
        val outputText = msg.outputTranscription?.text
        if (!inputText.isNullOrBlank() || !outputText.isNullOrBlank()) {
            handleTranscriptions(inputText = inputText, outputText = outputText)
        }

        val parts = msg.content?.parts.orEmpty()
        for (part in parts) {
            when (part) {
                is InlineDataPart -> {
                    if (part.mimeType.startsWith("audio/", ignoreCase = true) && part.inlineData.isNotEmpty()) {
                        turnReceivedAudioBytes = true
                        lastAiOutputChunkAtMs = System.currentTimeMillis()
                        userSpeechSettleJob?.cancel()
                        finalizePendingUserBufferOnAiStart()
                        speakingIdleJob?.cancel()

                        val bytes = part.inlineData
                        queuedAudioBytes.addAndGet(bytes.size)
                        audioPlaybackChannel.trySend(LiveAudioChunk(playbackEpoch.get(), bytes))

                        if (_state.value.phase != LivePhase.AI_SPEAKING) {
                            _state.update {
                                it.copy(
                                    phase = LivePhase.AI_SPEAKING,
                                    liveUserCaption = "",
                                    statusNotice = "Speaking — interrupt anytime"
                                )
                            }
                        }
                    }
                }

                is TextPart -> {
                    if (!part.isThought && part.text.isNotBlank()) {
                        val txt = part.text
                        turnTextPartBuffer.append(txt)
                        lastAiOutputChunkAtMs = System.currentTimeMillis()
                        if (aiTurnBuffer.isEmpty() || outputText.isNullOrBlank()) {
                            appendDeduplicatedAiText(aiTurnBuffer, txt)
                            _state.update {
                                it.copy(
                                    phase = LivePhase.AI_SPEAKING,
                                    liveUserCaption = "",
                                    liveAiCaption = aiTurnBuffer.toString().trim(),
                                    statusNotice = "Speaking — interrupt anytime"
                                )
                            }
                        }
                    }
                }

                else -> Unit
            }
        }

        if (msg.turnComplete || msg.generationComplete) {
            scheduleTurnCompletionFinalize()
        }
    }

    private fun finalizePendingUserBufferOnAiStart() {
        if (userTurnBuffer.isNotBlank()) {
            val finishedUser = userTurnBuffer.toString().trim()
            currentTurnId += 1L
            lastWatchdogSentUserText = finishedUser
            lastCompletedUserTurnText = finishedUser
            GuideSessionCoordinator.noteUserTaskIntent(finishedUser)
            appendTranscriptEntry(speaker = "user", text = finishedUser)
            GuideSessionCoordinator.recordLiveTranscriptTurn("user", finishedUser)
            userTurnBuffer.clear()
        }
    }

    private fun onServerInterrupted() {
        val now = System.currentTimeMillis()
        lastInterruptedAtMs = now
        speakingIdleJob?.cancel()
        flushAudioPlaybackImmediate()

        val partialAi = aiTurnBuffer.toString().trim()
            .ifBlank { turnTextPartBuffer.toString().trim() }
        if (partialAi.isNotBlank()) {
            appendTranscriptEntry(
                speaker = "model",
                text = partialAi,
                wasInterrupted = true
            )
            GuideSessionCoordinator.recordLiveTranscriptTurn("model", partialAi)
        }
        aiTurnBuffer.clear()
        turnTextPartBuffer.clear()
        turnReceivedAudioBytes = false

        _state.update {
            it.copy(
                phase = LivePhase.LISTENING,
                liveAiCaption = "",
                statusNotice = "Interrupted — listening to you..."
            )
        }
    }

    private fun scheduleTurnCompletionFinalize() {
        speakingIdleJob?.cancel()
        val epochAtEnd = playbackEpoch.get()
        val epochAtInterrupt = InterruptController.currentEpochValue()
        val hadAudioBytes = turnReceivedAudioBytes
        val textOnlyFallback = if (!hadAudioBytes) turnTextPartBuffer.toString().trim() else ""

        speakingIdleJob = scope.launch {
            if (!hadAudioBytes && textOnlyFallback.isNotBlank() &&
                GuideSessionCoordinator.voiceReadAloudEnabled.value
            ) {
                GuideSessionCoordinator.speakLocalDirectly(
                    text = textOnlyFallback,
                    messageId = "live_text_turn_${currentTurnId}"
                )
            }

            // Wait accurately until queued audio frames finish draining into AudioTrack + DAC tail
            var waitedMs = 0L
            while (waitedMs < 12_000L &&
                playbackEpoch.get() == epochAtEnd &&
                (queuedAudioBytes.get() > 0 || isWritingAudioTrack || GuideSessionCoordinator.isLocalTtsSpeaking())
            ) {
                delay(25L)
                waitedMs += 25L
            }
            if (hadAudioBytes) {
                delay(35L)
            }
            if (playbackEpoch.get() != epochAtEnd) return@launch

            val committedAiText = aiTurnBuffer.toString().trim()
                .ifBlank { turnTextPartBuffer.toString().trim() }
            if (committedAiText.isNotBlank()) {
                commitModelTurnIfUnique(committedAiText)
            }
            aiTurnBuffer.clear()
            turnTextPartBuffer.clear()
            turnReceivedAudioBytes = false

            if (_state.value.phase == LivePhase.AI_SPEAKING || _state.value.phase == LivePhase.INTERRUPTED) {
                _state.update {
                    it.copy(
                        phase = LivePhase.LISTENING,
                        liveAiCaption = "",
                        statusNotice = when {
                            it.isScreenSharing -> "Listening • Screen shared live"
                            it.isCameraSharing -> "Listening • Camera live"
                            else -> "Listening — speak naturally"
                        }
                    )
                }
            }

            val nowDone = System.currentTimeMillis()
            val shouldCheckFallback = activeToolCount.get() == 0 &&
                InterruptController.currentEpochValue() == epochAtInterrupt &&
                toolCalledForTurnId != currentTurnId &&
                handledFallbackForTurnId != currentTurnId &&
                (nowDone - lastToolCallFinishedAtMs > 2500L)
            if (shouldCheckFallback && (lastCompletedUserTurnText.isNotBlank() || committedAiText.isNotBlank())) {
                val userSnapshotForTurn = lastCompletedUserTurnText
                handledFallbackForTurnId = currentTurnId
                lastCompletedUserTurnText = ""
                GuideSessionCoordinator.fulfillSpokenCommitmentIfNoToolCalled(
                    userText = userSnapshotForTurn,
                    aiText = committedAiText
                )
            }
        }
    }

    private fun flushAudioPlaybackImmediate() {
        playbackEpoch.incrementAndGet()
        queuedAudioBytes.set(0)
        drainAudioChannel()
        synchronized(audioTrackLock) {
            playbackAudioTrack?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    runCatching {
                        track.pause()
                        track.flush()
                        track.play()
                    }
                }
            }
        }
    }

    private fun drainAudioChannel() {
        while (true) {
            val drained = audioPlaybackChannel.tryReceive()
            if (drained.isFailure) break
        }
    }

    private fun cancelInFlightTools() {
        val jobs = inFlightToolJobs.values.toList()
        inFlightToolJobs.clear()
        jobs.forEach { it.cancel() }
        activeToolCount.set(0)
    }

    private fun releaseAudioResources() {
        flushAudioPlaybackImmediate()
        clearOutboundQueues()
        runCatching { audioPlaybackChannel.close() }
        runCatching { outboundSignal.close() }
        audioRecord?.let { rec ->
            runCatching {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
                rec.release()
            }
        }
        audioRecord = null

        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null

        synchronized(audioTrackLock) {
            playbackAudioTrack?.let { track ->
                runCatching {
                    track.pause()
                    track.flush()
                    track.stop()
                    track.release()
                }
            }
            playbackAudioTrack = null
        }
    }

    fun stopLiveSession() {
        userRequestedStop = true
        sessionJob?.cancel()
        sessionJob = null
        speakingIdleJob?.cancel()
        interruptionResetJob?.cancel()
        userSpeechSettleJob?.cancel()
        cancelInFlightTools()

        val sessionToClose = liveSession
        liveSession = null

        GuideSessionCoordinator.setScreenTaskActive(false)
        InterruptController.reset()
        flushTranscriptTurns()
        releaseAudioResources()
        cleanupAudioRouting()

        _state.update {
            it.copy(
                phase = LivePhase.IDLE,
                isScreenSharing = false,
                isCameraSharing = false,
                isMuted = false,
                isSwitchingVoice = false,
                liveUserCaption = "",
                liveAiCaption = "",
                statusNotice = null
            )
        }

        if (sessionToClose != null) {
            scope.launch(Dispatchers.IO) {
                runCatching { sessionToClose.stopReceiving() }
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
                    active && it.isActive -> "Watching your screen live — ask what to do or ask me to click!"
                    !active && it.isActive -> "Screen share stopped. Still listening to your voice."
                    else -> it.statusNotice
                }
            )
        }
    }

    fun setCameraSharingActive(active: Boolean) {
        _state.update {
            it.copy(
                isCameraSharing = active,
                isScreenSharing = if (active) false else it.isScreenSharing,
                statusNotice = when {
                    active && it.isActive -> "Camera is live — point at any bottle, letter, or screen and ask!"
                    !active && it.isActive -> "Camera turned off. Still listening to your voice."
                    else -> it.statusNotice
                }
            )
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

    /**
     * Streams visual JPEG frames ONLY when Screen Sharing, Camera Sharing, or an on-demand
     * Screen Task (`isScreenTaskActive`) is active, dropping frames cleanly when voice audio is busy.
     */
    fun sendScreenFrame(bitmap: Bitmap, sourceLabel: String = "screen", forceSend: Boolean = false) {
        val session = liveSession ?: return
        val cur = _state.value
        val screenAllowed = cur.isScreenSharing || cur.isCameraSharing || GuideSessionCoordinator.isScreenTaskActive.value
        if (!cur.isActive || (!screenAllowed && !forceSend) || session.isClosed() || bitmap.isRecycled) return

        val now = System.currentTimeMillis()
        if (!forceSend) {
            if (cur.phase == LivePhase.AI_SPEAKING || now - lastAiOutputChunkAtMs < 450L) return
            if (now - lastUserSpeechChunkAtMs < 350L) return
            if (now - lastVideoSentMs < VIDEO_FRAME_INTERVAL_MS) return
        }
        lastVideoSentMs = now

        val snapshotCopy = runCatching {
            val safe = if (bitmap.config == Bitmap.Config.HARDWARE) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
            }
            safe
        }.getOrNull() ?: return

        scope.launch(Dispatchers.Default) {
            val jpegBytes = runCatching {
                try {
                    encodeJpegFrame(snapshotCopy)
                } finally {
                    if (!snapshotCopy.isRecycled) snapshotCopy.recycle()
                }
            }.getOrNull() ?: return@launch
            if (!session.isClosed()) {
                enqueueVideoFrame(jpegBytes)
            }
        }
    }

    /**
     * Sends post-action or step-completion updates to Gemini Live using client content
     * (`session.send(..., turnComplete = true)`) with minimal wait so Live narrates smoothly.
     */
    fun notifyUserScreenAction(actionMessage: String, latestFrame: Bitmap? = null) {
        val session = liveSession ?: return
        if (!_state.value.isActive || session.isClosed()) return

        val now = System.currentTimeMillis()
        if (actionMessage == lastActionNotificationText && now - lastActionNotificationAtMs < 1800L) {
            return
        }
        lastActionNotificationText = actionMessage
        lastActionNotificationAtMs = now

        val frameCopy = if (latestFrame != null && !latestFrame.isRecycled) {
            runCatching { latestFrame.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
        } else {
            null
        }

        scope.launch(Dispatchers.Default) {
            val frameBytes = if (frameCopy != null) {
                runCatching {
                    try {
                        encodeJpegFrame(frameCopy)
                    } finally {
                        if (!frameCopy.isRecycled) frameCopy.recycle()
                    }
                }.getOrNull()
            } else {
                null
            }

            var waitCycles = 0
            while (waitCycles < 3 &&
                (activeToolCount.get() > 0 ||
                    _state.value.phase == LivePhase.AI_SPEAKING ||
                    queuedAudioBytes.get() > 0 ||
                    System.currentTimeMillis() - lastAiOutputChunkAtMs < 400L)
            ) {
                delay(120L)
                waitCycles += 1
            }
            if (!_state.value.isActive || session.isClosed()) return@launch
            val spokenContext = userTurnBuffer.toString().trim()
            val fullPayload = if (spokenContext.isNotBlank()) {
                "[User also said: \"$spokenContext\"]\n$actionMessage"
            } else {
                actionMessage
            }
            enqueuePriorityOutbound { active ->
                if (!active.isClosed()) {
                    if (frameBytes != null) {
                        active.sendVideoRealtime(InlineData(frameBytes, "image/jpeg"))
                    }
                    active.send(fullPayload, turnComplete = true)
                }
            }
        }
    }

    private fun safeSendClientContent(session: LiveSession, text: String) {
        if (session.isClosed() || text.isBlank()) return
        enqueuePriorityOutbound { active ->
            if (!active.isClosed()) {
                active.send(text, turnComplete = true)
            }
        }
    }

    fun ensureMicrophoneActiveAfterCameraOrSos() {
        if (_state.value.isMuted) return
        val ctx = appContext ?: return
        runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.isMicrophoneMute = false
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
        currentTurnId += 1L
        lastCompletedUserTurnText = clean
        InterruptController.onUserInputObserved(
            rawInput = clean,
            isFinal = true,
            hasPendingConfirmation = GuideSessionCoordinator.pendingConfirmationAction.value != null
        )
        GuideSessionCoordinator.noteUserTaskIntent(clean)
        appendTranscriptEntry(speaker = "user", text = clean)
        GuideSessionCoordinator.recordLiveTranscriptTurn("user", clean)
        scope.launch(Dispatchers.IO) {
            sendPromptWithContext(session, clean)
        }
    }

    private fun buildPromptWithContext(userPrompt: String): String {
        val screenContextAllowed = _state.value.isScreenSharing || GuideSessionCoordinator.isScreenTaskActive.value
        val nodeSummary = if (screenContextAllowed) {
            GuideSessionCoordinator.getCurrentVisibleNodeSummary()
        } else {
            val pkg = GuideSessionCoordinator.activeScreenPackage.value.ifBlank { appContext?.packageName.orEmpty() }
            "Current App: ${com.example.service.ScreenGuideAccessibilityService.resolveHumanReadableAppName(appContext, pkg)}"
        }
        return if (nodeSummary.isNotBlank()) {
            "$userPrompt\n[$nodeSummary]"
        } else {
            userPrompt
        }
    }

    private suspend fun sendPromptDirect(session: LiveSession, userPrompt: String) {
        val payload = buildPromptWithContext(userPrompt)
        if (!session.isClosed() && payload.isNotBlank()) {
            session.send(payload, turnComplete = true)
        }
    }

    private suspend fun sendPromptWithContext(session: LiveSession, userPrompt: String) {
        safeSendClientContent(session, buildPromptWithContext(userPrompt))
    }

    fun manualInterruptPrompt() {
        val session = liveSession ?: return
        if (session.isClosed()) return

        lastInterruptedAtMs = System.currentTimeMillis()
        speakingIdleJob?.cancel()
        flushAudioPlaybackImmediate()
        cancelInFlightTools()
        InterruptController.triggerManualInterrupt("Senior tapped Interrupt button")

        val partialAi = aiTurnBuffer.toString().trim()
            .ifBlank { turnTextPartBuffer.toString().trim() }
        if (partialAi.isNotBlank()) {
            appendTranscriptEntry(
                speaker = "model",
                text = partialAi,
                wasInterrupted = true
            )
            GuideSessionCoordinator.recordLiveTranscriptTurn("model", partialAi)
        }
        aiTurnBuffer.clear()
        turnTextPartBuffer.clear()
        turnReceivedAudioBytes = false

        enqueuePriorityOutbound { active ->
            if (!active.isClosed()) {
                active.sendStartActivityRealtime()
            }
        }

        _state.update {
            it.copy(
                phase = LivePhase.INTERRUPTED,
                liveAiCaption = "",
                statusNotice = "Paused — listening carefully to everything you say..."
            )
        }

        interruptionResetJob?.cancel()
        interruptionResetJob = scope.launch {
            delay(220L)
            if (_state.value.phase == LivePhase.INTERRUPTED) {
                _state.update {
                    it.copy(
                        phase = LivePhase.LISTENING,
                        statusNotice = if (it.isScreenSharing) {
                            "Listening • Screen shared live"
                        } else if (it.isCameraSharing) {
                            "Listening • Camera live"
                        } else {
                            "Listening — go ahead"
                        }
                    )
                }
            }
        }
    }

    fun updateTonePreset(tone: LiveTonePreset) {
        if (_state.value.selectedTone == tone) return
        _state.update {
            it.copy(
                selectedTone = tone,
                statusNotice = "Tone changed to ${tone.label}"
            )
        }
        val session = liveSession
        if (session != null && _state.value.isActive && !session.isClosed()) {
            scope.launch(Dispatchers.IO) {
                safeSendClientContent(
                    session,
                    "[TONE PREFERENCE UPDATED] For upcoming responses, adopt the '${tone.label}' tone (${tone.directive}). Acknowledge briefly in 1 short sentence in this new tone or wait if user is speaking."
                )
            }
        }
    }

    fun updateSelectedVoice(voiceName: String) {
        val current = _state.value
        if (current.selectedVoice == voiceName) return
        _state.update { it.copy(selectedVoice = voiceName) }

        val ctx = appContext
        if (current.isActive && ctx != null) {
            scope.launch {
                hotSwapVoiceSession(ctx, voiceName)
            }
        } else {
            _state.update {
                it.copy(statusNotice = "Voice set to ${voiceDisplayName(voiceName)}")
            }
        }
    }

    private suspend fun hotSwapVoiceSession(context: Context, newVoiceName: String) {
        val oldSession = liveSession
        liveSession = null
        userRequestedStop = true
        sessionJob?.cancel()
        speakingIdleJob?.cancel()
        interruptionResetJob?.cancel()
        userSpeechSettleJob?.cancel()
        cancelInFlightTools()
        flushTranscriptTurns()
        releaseAudioResources()

        _state.update {
            it.copy(
                isSwitchingVoice = true,
                statusNotice = "Switching voice to ${voiceDisplayName(newVoiceName)}..."
            )
        }

        if (oldSession != null) {
            withContext(Dispatchers.IO) {
                runCatching { oldSession.stopReceiving() }
                runCatching { oldSession.close() }
            }
        }
        delay(60L)
        startLiveSession(
            context = context,
            voiceName = newVoiceName,
            initialPrompt = null,
            preserveActiveShares = true
        )
    }

    fun clearError() {
        _state.update {
            it.copy(
                errorMessage = null,
                phase = if (it.phase == LivePhase.ERROR) LivePhase.IDLE else it.phase
            )
        }
    }

    private fun buildLiveModel(
        modelName: String,
        voiceName: String,
        tonePreset: LiveTonePreset
    ): LiveGenerativeModel {
        val contextSummary = GuideSessionCoordinator.buildLiveSystemContext()
        val liveConfig = liveGenerationConfig {
            responseModality = ResponseModality.AUDIO
            speechConfig = SpeechConfig(voice = Voice(voiceName))
            inputAudioTranscription = AudioTranscriptionConfig()
            outputAudioTranscription = AudioTranscriptionConfig()
            contextWindowCompression = ContextWindowCompressionConfig(
                triggerTokens = 24_000,
                slidingWindow = SlidingWindow(targetTokens = 16_000)
            )
            realtimeInputConfig = RealtimeInputConfig.builder()
                .setActivityHandling(RealtimeInputConfig.ActivityHandling.INTERRUPT)
                .setAutomaticActivityDetection(
                    ActivityDetectionConfig.builder()
                        .setEndSensitivity(ActivityDetectionConfig.Sensitivity.HIGH)
                        .setPrefixPaddingMs(120)
                        .setSilenceDurationMs(300)
                        .build()
                )
                .build()
        }

        val systemText = buildString {
            appendLine(
                """
                You are KindGuide Live, a warm, intelligent real-time voice companion and Universal Smartphone Task Assistant powered by Google Gemini for seniors.

                CURRENT TONE PRESET: ${tonePreset.label}
                TONE STYLE RULE: ${tonePreset.directive}

                1. NATURAL CONVERSATIONAL VOICE AT EVERY STEP (CRITICAL):
                   - NEVER go silent when asked to perform a task! Always keep a warm, natural conversation flowing with the senior (1 to 2 short clear sentences max per reply).
                   - After EVERY verified step (`perform_screen_action`), ALWAYS speak aloud in 1 short sentence telling the senior what you just verified and what comes next.
                   - NEVER emit multiple consecutive silent tool calls without speaking to the senior! Speak your verified update first.
                   - If the senior has NOT told you what message they want to send to a contact, open the contact's chat first and then ask aloud: "I've opened their chat! What message would you like me to send?"

                2. STRICT STEP-BY-STEP VERIFICATION & NO RETRY LOOPS (`perform_screen_action`):
                   - Execute ONLY ONE step per turn and inspect the verified screen result returned by `perform_screen_action`.
                   - NEVER repeat an `OPEN_APP` or `TAP_ELEMENT` call for the same target if the screen has already opened or if a search step is already waiting for the user's tap!
                   - If `perform_screen_action` returns `status="awaiting_user_tap_in_search"` (meaning WhatsApp search results are open with the contact typed, waiting for the user to tap), STOP calling tools and speak warmly asking the senior to go ahead and tap the contact's name on the screen.
                   - NATURAL TASK SWITCHING & PAUSED TASK HANDLING:
                     * Listen actively to everything the senior says, even mid-task.
                     * If the senior explicitly asks to leave, cancel, stop, or switch away from the current task, drop the previous task immediately, confirm warmly by voice, and carry out their new request right away.
                     * If a previous task paused/failed (such as waiting for a contact tap) and the senior asks a different question or requests a new task without mentioning the old one, assist them naturally with their new query AND ask once aloud whether they want to finish or leave the earlier task.
                   - WHATSAPP MESSAGING WORKFLOW:
                     * Step 1 (Open WhatsApp): If not already in WhatsApp, call `perform_screen_action(actionType="OPEN_APP", targetLabel="WhatsApp", spokenExplanation="Opening WhatsApp now")`. Note: KindGuide automatically locates visible contacts or opens WhatsApp Search if needed.
                     * Step 2 (Check Result):
                       - If `isChatConversationOpen` is true and no message was entered yet -> speak aloud asking what message they want to send.
                       - If `status="awaiting_user_confirmation"` -> speak aloud telling them what is typed and ask them to say "Yes, send it" or tap the green Send Now button.
                       - If `status="awaiting_user_tap_in_search"` -> speak aloud asking them to tap the contact's name in WhatsApp search results, and wait for them to tap or speak.

                3. ACCURATE SCREEN-GROUNDED HIGHLIGHTING (`highlight_screen_step`):
                   - NEVER invent vague instructions like "Tap the green phone icon at the bottom left"!
                   - Only use `targetButtonHint` values that are literally present in the current screen's `visibleElements`.
                4. EMERGENCY & QUICK SOS (`trigger_quick_sos`):
                   - If the senior says "Send Quick SOS", "Trigger SOS", or asks for emergency help, ALWAYS call `trigger_quick_sos` immediately and reassure them aloud!
                """.trimIndent()
            )
            if (contextSummary.isNotBlank()) {
                appendLine()
                appendLine("SENIOR CARE CIRCLE CONTEXT:")
                appendLine(contextSummary)
            }
        }

        return Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = modelName,
            generationConfig = liveConfig,
            tools = listOf(
                Tool.functionDeclarations(
                    listOf(
                        inspectScreenDeclaration,
                        highlightStepDeclaration,
                        performScreenActionDeclaration,
                        completeGuidedTaskDeclaration,
                        manageCareCircleDeclaration,
                        flagHelpDeclaration,
                        triggerQuickSosDeclaration
                    )
                )
            ),
            systemInstruction = content { text(systemText) }
        )
    }

    private val echoNoiseShortFragments = setOf(
        "the", "and", "for", "you", "now", "are", "can", "your", "that", "this",
        "with", "have", "from", "uh", "um", "ah", "oh", "hm", "hmm", "mm", "eh", "ha"
    )

    private val instantBargeInKeywords = setOf(
        "stop", "wait", "no", "yes", "ok", "okay", "hold", "pause",
        "cancel", "back", "home", "open", "call", "send", "what", "who", "help"
    )

    internal fun isGenuineUserInterruption(rawInput: String, currentAiSpeech: String): Boolean {
        val cleaned = rawInput.trim()
        val letterCount = cleaned.count { it.isLetter() }
        if (letterCount < 2) return false

        val normalizedWords = cleaned.lowercase()
            .replace(Regex("[^a-z\\s]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (normalizedWords.isEmpty()) return false

        if (normalizedWords.size == 1) {
            val single = normalizedWords[0]
            if (single in echoNoiseShortFragments) return false
            if (single.length <= 3 && single !in instantBargeInKeywords) return false
        }

        if (currentAiSpeech.isNotBlank() &&
            cleaned.length >= 3 &&
            currentAiSpeech.contains(cleaned, ignoreCase = true)
        ) {
            return false
        }
        return true
    }

    private fun handleTranscriptions(inputText: String?, outputText: String?) {
        if (!inputText.isNullOrBlank()) {
            val now = System.currentTimeMillis()
            val wasSpeaking = _state.value.phase == LivePhase.AI_SPEAKING
            val currentAiSpeech = aiTurnBuffer.toString().trim()

            if (wasSpeaking && userTurnBuffer.isEmpty() && !isGenuineUserInterruption(inputText, currentAiSpeech)) {
                return
            }
            if (TaskExecutionHandler.isLikelyTtsEcho(inputText)) {
                return
            }

            lastUserSpeechChunkAtMs = now
            if (wasSpeaking) {
                lastInterruptedAtMs = now
                speakingIdleJob?.cancel()
                flushAudioPlaybackImmediate()
                if (aiTurnBuffer.isNotBlank()) {
                    val interruptedText = aiTurnBuffer.toString().trim()
                    appendTranscriptEntry(
                        speaker = "model",
                        text = interruptedText,
                        wasInterrupted = true
                    )
                    GuideSessionCoordinator.recordLiveTranscriptTurn("model", interruptedText)
                    aiTurnBuffer.clear()
                }
                turnTextPartBuffer.clear()
                turnReceivedAudioBytes = false
                _state.update {
                    it.copy(
                        phase = LivePhase.LISTENING,
                        liveAiCaption = "",
                        statusNotice = "Interrupted — listening to you..."
                    )
                }
            } else if (_state.value.phase != LivePhase.LISTENING) {
                _state.update { it.copy(phase = LivePhase.LISTENING) }
            }

            if (aiTurnBuffer.isNotBlank() && !wasSpeaking) {
                val finishedAi = aiTurnBuffer.toString().trim()
                commitModelTurnIfUnique(finishedAi)
                aiTurnBuffer.clear()
                turnTextPartBuffer.clear()
                turnReceivedAudioBytes = false
                _state.update { it.copy(liveAiCaption = "") }
            }

            appendTranscriptionPiece(userTurnBuffer, inputText)
            val fullUserTextSoFar = userTurnBuffer.toString().trim()
            val liveInterruptDecision = InterruptController.onUserInputObserved(
                rawInput = fullUserTextSoFar,
                isFinal = false,
                hasPendingConfirmation = GuideSessionCoordinator.pendingConfirmationAction.value != null
            )
            if (liveInterruptDecision != InterruptDecision.NONE) {
                speakingIdleJob?.cancel()
                flushAudioPlaybackImmediate()
                aiTurnBuffer.clear()
                turnTextPartBuffer.clear()
                lastCompletedUserTurnText = ""
                _state.update {
                    it.copy(
                        liveAiCaption = "",
                        statusNotice = if (liveInterruptDecision == InterruptDecision.ABORT_AND_PIVOT) {
                            "Interrupted — pivoting to your new command..."
                        } else {
                            "Stopped previous task — listening..."
                        }
                    )
                }
            }
            GuideSessionCoordinator.noteUserTaskIntent(fullUserTextSoFar)
            _state.update { it.copy(liveUserCaption = fullUserTextSoFar) }

            userSpeechSettleJob?.cancel()
            userSpeechSettleJob = scope.launch(Dispatchers.IO) {
                delay(FAST_SPEECH_COMMIT_MS)
                val settledSnapshot = userTurnBuffer.toString().trim()
                if (settledSnapshot.isBlank() || _state.value.phase == LivePhase.AI_SPEAKING) return@launch

                currentTurnId += 1L
                val committedTurnId = currentTurnId
                lastCompletedUserTurnText = settledSnapshot
                val finalInterruptDecision = InterruptController.onUserInputObserved(
                    rawInput = settledSnapshot,
                    isFinal = true,
                    hasPendingConfirmation = GuideSessionCoordinator.pendingConfirmationAction.value != null
                )
                if (finalInterruptDecision != InterruptDecision.NONE) {
                    speakingIdleJob?.cancel()
                    handledFallbackForTurnId = committedTurnId
                }
                GuideSessionCoordinator.noteUserTaskIntent(settledSnapshot)
                appendTranscriptEntry(speaker = "user", text = settledSnapshot)
                GuideSessionCoordinator.recordLiveTranscriptTurn("user", settledSnapshot)
                userTurnBuffer.clear()
                _state.update {
                    it.copy(
                        liveUserCaption = "",
                        statusNotice = if (finalInterruptDecision == InterruptDecision.ABORT_AND_PIVOT) {
                            "Pivoting to your new request..."
                        } else {
                            "Thinking..."
                        }
                    )
                }

                val lowerSnap = settledSnapshot.lowercase()
                if (GuideSessionCoordinator.pendingConfirmationAction.value != null) {
                    if (lowerSnap.matches(Regex(".*\\b(yes|yeah|yep|send|send it|call|start call|start the call|go ahead|sure|okay|ok|do it)\\b.*"))) {
                        handledFallbackForTurnId = committedTurnId
                        lastCompletedUserTurnText = ""
                        GuideSessionCoordinator.confirmPendingAction()
                        return@launch
                    } else if (lowerSnap.matches(Regex(".*\\b(no|cancel|don't|do not|stop)\\b.*"))) {
                        handledFallbackForTurnId = committedTurnId
                        lastCompletedUserTurnText = ""
                        GuideSessionCoordinator.cancelPendingAction()
                        return@launch
                    }
                }
                if (lowerSnap.contains("quick sos") || lowerSnap.contains("send sos")) {
                    handledFallbackForTurnId = committedTurnId
                    lastCompletedUserTurnText = ""
                    GuideSessionCoordinator.triggerQuickSosNow("Triggered via KindGuide Live voice command")
                    return@launch
                }

                // Last-resort stall watchdog (5s): only nudge via clientContent if server VAD stalled silently
                // and the microphone has gone quiet.
                delay(SILENT_STALL_WATCHDOG_MS)
                val session = liveSession
                val nowAfterWait = System.currentTimeMillis()
                val aiStartedOrCalledTool = _state.value.phase == LivePhase.AI_SPEAKING ||
                    activeToolCount.get() > 0 ||
                    toolCalledForTurnId == committedTurnId ||
                    aiTurnBuffer.isNotBlank() ||
                    queuedAudioBytes.get() > 0 ||
                    (nowAfterWait - lastAiOutputChunkAtMs) < 3500L ||
                    (nowAfterWait - lastToolCallAtMs) < 3500L ||
                    (nowAfterWait - lastToolCallFinishedAtMs) < 3500L ||
                    (nowAfterWait - lastMicVoiceActiveAtMs) < 900L ||
                    (nowAfterWait - lastUserSpeechChunkAtMs) < 1200L ||
                    userTurnBuffer.isNotBlank()

                if (session != null &&
                    !session.isClosed() &&
                    !aiStartedOrCalledTool &&
                    !settledSnapshot.equals(lastWatchdogSentUserText, ignoreCase = true)
                ) {
                    lastWatchdogSentUserText = settledSnapshot
                    val appSummary = GuideSessionCoordinator.getCurrentVisibleNodeSummary()
                    val pendingConfirm = GuideSessionCoordinator.pendingConfirmationAction.value
                    val pausedTask = GuideSessionCoordinator.pausedTaskDescription
                    val confirmContext = buildString {
                        if (pendingConfirm != null) {
                            append("\n[PENDING CONFIRMATION]: Waiting for user to confirm tapping '${pendingConfirm.targetLabel}'. If the user said Yes/Send/Okay, call `perform_screen_action` with `userConfirmed=true`!")
                        }
                        if (pausedTask.isNotBlank()) {
                            append("\n[PAUSED PREVIOUS TASK]: '$pausedTask'. Assist the user naturally with what they just said AND ask once whether to finish or leave this previous task (unless they already asked to leave/cancel it).")
                        }
                    }
                    safeSendClientContent(
                        session,
                        "[USER SAID]: \"$settledSnapshot\"\n[$appSummary]$confirmContext\nRespond warmly aloud in 1 short sentence and call the appropriate tool only if needed."
                    )
                }
            }
        }

        if (!outputText.isNullOrBlank()) {
            lastAiOutputChunkAtMs = System.currentTimeMillis()
            userSpeechSettleJob?.cancel()
            finalizePendingUserBufferOnAiStart()

            appendDeduplicatedAiText(aiTurnBuffer, outputText)
            _state.update {
                it.copy(
                    phase = LivePhase.AI_SPEAKING,
                    liveUserCaption = "",
                    liveAiCaption = aiTurnBuffer.toString().trim(),
                    statusNotice = "Speaking — interrupt anytime"
                )
            }

            // Fallback safety timer if server omits turnComplete frame on some network drops
            speakingIdleJob?.cancel()
            speakingIdleJob = scope.launch {
                delay(2800L)
                if (queuedAudioBytes.get() == 0 && !isWritingAudioTrack && !GuideSessionCoordinator.isLocalTtsSpeaking()) {
                    scheduleTurnCompletionFinalize()
                }
            }
        }
    }

    private fun commitModelTurnIfUnique(finishedAi: String) {
        if (finishedAi.isBlank()) return
        if (finishedAi.equals(lastCommittedAiTurn, ignoreCase = true)) {
            return
        }
        lastCommittedAiTurn = finishedAi
        appendTranscriptEntry(speaker = "model", text = finishedAi)
        GuideSessionCoordinator.recordLiveTranscriptTurn("model", finishedAi)
    }

    private suspend fun handleToolCall(call: FunctionCallPart): FunctionResponsePart {
        return when (call.name) {
            "inspect_current_screen" -> {
                val summary = GuideSessionCoordinator.getCurrentVisibleNodeSummary()
                val pkg = GuideSessionCoordinator.activeScreenPackage.value.ifBlank {
                    appContext?.packageName.orEmpty()
                }
                val appLabel = com.example.service.ScreenGuideAccessibilityService
                    .resolveHumanReadableAppName(appContext, pkg)
                _state.update {
                    it.copy(lastActionBadge = "Verified Screen: ${appLabel.substringBefore(" (")}")
                }
                FunctionResponsePart(
                    name = call.name,
                    response = JsonObject(
                        mapOf(
                            "status" to JsonPrimitive("ok"),
                            "verifiedForegroundApp" to JsonPrimitive(appLabel),
                            "screenSummary" to JsonPrimitive(summary)
                        )
                    ),
                    id = call.id
                )
            }

            "highlight_screen_step" -> {
                val stepTitle = call.args["stepTitle"]?.jsonPrimitive?.contentOrNull ?: "Phone Step"
                val stepNumber = (call.args["stepNumber"]?.jsonPrimitive?.intOrNull ?: 1).coerceAtLeast(1)
                val totalSteps = (call.args["totalSteps"]?.jsonPrimitive?.intOrNull ?: 3).coerceAtLeast(stepNumber)
                val instruction = call.args["instruction"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val rawMode = call.args["guidanceMode"]?.jsonPrimitive?.contentOrNull ?: "HIGHLIGHT_BUTTON"
                val hint = call.args["targetButtonHint"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val note = call.args["reassuranceNote"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val isCorrection = call.args["isCorrection"]?.jsonPrimitive?.booleanOrNull ?: false

                val verifiedBounds = if (hint.isNotBlank()) {
                    GuideSessionCoordinator.findVerifiedBoundsForLabel(hint)
                } else {
                    null
                }
                val effectiveMode = when {
                    rawMode == "SWIPE_HINT" || rawMode == "SEARCH_HINT" || rawMode == "CONFIRM_SEND" -> rawMode
                    verifiedBounds != null -> "HIGHLIGHT_BUTTON"
                    else -> "HIGHLIGHT_BUTTON"
                }

                val step = AiGuideStep(
                    stepTitle = stepTitle,
                    stepNumber = stepNumber,
                    totalSteps = totalSteps,
                    instruction = instruction,
                    targetButtonHint = hint,
                    reassuranceNote = note,
                    suggestedAppName = "",
                    isCorrection = isCorrection,
                    guidanceMode = effectiveMode,
                    requiresConfirmation = effectiveMode == "CONFIRM_SEND"
                )
                val accepted = GuideSessionCoordinator.setLiveGuideStep(step)
                if (accepted) {
                    _state.update {
                        it.copy(
                            lastActionBadge = when (effectiveMode) {
                                "SWIPE_HINT" -> "Swipe: $stepTitle"
                                "SEARCH_HINT" -> "Search: $stepTitle"
                                "CONFIRM_SEND" -> "Confirm: Tap $hint"
                                else -> "Step $stepNumber: Tap ${hint.ifBlank { stepTitle }}"
                            }
                        )
                    }
                }
                FunctionResponsePart(
                    name = call.name,
                    response = JsonObject(
                        mapOf(
                            "status" to JsonPrimitive(if (accepted) "ok" else "button_not_on_current_screen"),
                            "buttonVerifiedOnScreen" to JsonPrimitive(verifiedBounds != null),
                            "visibleElements" to JsonPrimitive(GuideSessionCoordinator.getCurrentVisibleNodeSummary()),
                            "instructionRule" to JsonPrimitive(
                                if (accepted) {
                                    "Speak this step instruction warmly to the senior."
                                } else {
                                    "Target '$hint' is NOT on the current screen. Check visibleElements and either open the right app or use a button label that actually exists on this screen."
                                }
                            )
                        )
                    ),
                    id = call.id
                )
            }

            "perform_screen_action" -> {
                val actionType = call.args["actionType"]?.jsonPrimitive?.contentOrNull ?: "TAP_ELEMENT"
                val targetLabel = call.args["targetLabel"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val swipeDirection = call.args["swipeDirection"]?.jsonPrimitive?.contentOrNull ?: "LEFT"
                val textToType = call.args["textToType"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val submitAfterType = call.args["submitAfterType"]?.jsonPrimitive?.booleanOrNull ?: false
                val expectedAppName = call.args["expectedAppName"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val systemNavAction = call.args["systemNavAction"]?.jsonPrimitive?.contentOrNull ?: "BACK"
                val spokenExplanation = call.args["spokenExplanation"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val isFinalSend = call.args["isFinalSendOrCommit"]?.jsonPrimitive?.booleanOrNull ?: false
                val userConfirmed = call.args["userConfirmed"]?.jsonPrimitive?.booleanOrNull ?: false

                val action = AssistiveScreenAction(
                    actionType = actionType,
                    targetLabel = targetLabel,
                    swipeDirection = swipeDirection,
                    textToType = textToType,
                    submitAfterType = submitAfterType,
                    expectedAppName = expectedAppName,
                    systemNavAction = systemNavAction,
                    spokenExplanation = spokenExplanation,
                    isFinalSendOrCommit = isFinalSend,
                    userConfirmed = userConfirmed
                )

                val toolToken = InterruptController.beginExecution(
                    taskDescription = "tool:$actionType:$targetLabel"
                )
                val outcome = try {
                    coroutineScope {
                        val deferred = async {
                            withTimeoutOrNull(18_000L) {
                                GuideSessionCoordinator.executeAndVerifyScreenActionSuspend(action)
                            }
                        }
                        InterruptController.attachJob(toolToken, deferred)
                        try {
                            deferred.await()
                        } catch (_: CancellationException) {
                            VerifiedActionOutcome(
                                success = false,
                                wasAborted = true,
                                actionSummary = "Aborted previous task by user interrupt",
                                verifiedAppTitle = "Interrupted",
                                visibleElementsSummary = GuideSessionCoordinator.getCurrentVisibleNodeSummary()
                            )
                        }
                    } ?: VerifiedActionOutcome(
                        success = false,
                        actionSummary = "Action timed out",
                        verifiedAppTitle = "Unknown",
                        visibleElementsSummary = GuideSessionCoordinator.getCurrentVisibleNodeSummary()
                    )
                } finally {
                    InterruptController.finishExecution(toolToken)
                }

                val wasAbortedByInterrupt = outcome.wasAborted || InterruptController.isAborted(toolToken)
                val pivotCmd = if (wasAbortedByInterrupt) {
                    InterruptController.state.value.pivotCommand.orEmpty().also {
                        InterruptController.clearPivotCommand()
                    }
                } else {
                    ""
                }

                _state.update {
                    it.copy(
                        lastActionBadge = if (wasAbortedByInterrupt) {
                            "Interrupted — switching"
                        } else {
                            outcome.actionSummary
                        }
                    )
                }

                val ttsSummary = outcome.spokenConfirmations.joinToString(" ")
                val ttsDeliveredNote = if (ttsSummary.isNotBlank()) {
                    " Note: KindGuide TTS already spoke aloud: \"$ttsSummary\". Do not repeat it word-for-word;"
                } else {
                    ""
                }

                val nextInstruction = when {
                    wasAbortedByInterrupt && pivotCmd.isNotBlank() ->
                        "PREVIOUS TASK ABORTED because the senior interrupted with a new command: \"$pivotCmd\". Do NOT run any remaining steps of the old task. Speak warmly in 1 short sentence acknowledging the switch and execute \"$pivotCmd\" now!"
                    wasAbortedByInterrupt ->
                        "PREVIOUS TASK ABORTED because the senior interrupted or cancelled. Do NOT run any remaining steps of the old task. Speak warmly in 1 short sentence confirming you stopped and ask how you can help next."
                    outcome.awaitingUserConfirmation ->
                        "Paused for confirmation before tapping '${targetLabel.ifBlank { "Send" }}'.$ttsDeliveredNote Ask briefly if they'd like to tap Send Now or say yes to continue, and wait."
                    outcome.awaitingUserTapOrHelp ->
                        "WhatsApp search results are now open on screen with the contact typed.$ttsDeliveredNote STOP calling tools and wait for the senior to tap the contact or speak."
                    outcome.success && outcome.isChatConversationOpen && GuideSessionCoordinator.pendingSubGoal == "send_message" && GuideSessionCoordinator.pendingMessageText.isBlank() ->
                        "Chat conversation is now open!$ttsDeliveredNote Ask warmly what message they would like to send and wait for their answer."
                    outcome.success ->
                        "Step verified (${outcome.actionSummary}).$ttsDeliveredNote Continue naturally in 1 brief sentence or listen for their next request."
                    else ->
                        "Could not find '${targetLabel}' on the current screen.$ttsDeliveredNote STOP retrying the same action and ask warmly how the senior would like to proceed."
                }

                FunctionResponsePart(
                    name = call.name,
                    response = JsonObject(
                        mapOf(
                            "status" to JsonPrimitive(
                                when {
                                    wasAbortedByInterrupt -> "task_aborted_by_user_interrupt"
                                    outcome.awaitingUserConfirmation -> "awaiting_user_confirmation"
                                    outcome.awaitingUserTapOrHelp -> "awaiting_user_tap_in_search"
                                    outcome.success -> "step_verified_success"
                                    else -> "step_target_not_found"
                                }
                            ),
                            "pivotCommand" to JsonPrimitive(pivotCmd),
                            "actionExecuted" to JsonPrimitive(actionType),
                            "targetLabel" to JsonPrimitive(targetLabel),
                            "actionSummary" to JsonPrimitive(outcome.actionSummary),
                            "spokenConfirmationsDelivered" to JsonPrimitive(ttsSummary),
                            "verifiedForegroundApp" to JsonPrimitive(outcome.verifiedAppTitle),
                            "isChatConversationOpen" to JsonPrimitive(outcome.isChatConversationOpen),
                            "visibleElements" to JsonPrimitive(outcome.visibleElementsSummary),
                            "mandatorySpokenProgressRule" to JsonPrimitive(nextInstruction)
                        )
                    ),
                    id = call.id
                )
            }

            "complete_guided_task" -> {
                val title = call.args["taskTitle"]?.jsonPrimitive?.contentOrNull ?: "Guided Task"
                val congrats = call.args["congratulationMessage"]?.jsonPrimitive?.contentOrNull
                    ?: "Great job finishing $title!"
                GuideSessionCoordinator.completeLiveGuidedTask(congrats)
                _state.update {
                    it.copy(
                        lastActionBadge = "Completed: $title",
                        statusNotice = congrats
                    )
                }
                FunctionResponsePart(
                    name = call.name,
                    response = JsonObject(
                        mapOf(
                            "status" to JsonPrimitive("completed"),
                            "taskTitle" to JsonPrimitive(title)
                        )
                    ),
                    id = call.id
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
                    ),
                    id = call.id
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
                    ),
                    id = call.id
                )
            }

            "trigger_quick_sos" -> {
                val reason = call.args["reason"]?.jsonPrimitive?.contentOrNull ?: "Urgent Quick SOS requested by voice"
                val triggered = GuideSessionCoordinator.triggerQuickSosNow(reason)
                _state.update { it.copy(lastActionBadge = "🚨 Quick SOS Sent to Family") }
                FunctionResponsePart(
                    name = call.name,
                    response = JsonObject(
                        mapOf(
                            "status" to JsonPrimitive(if (triggered) "sos_triggered" else "error"),
                            "message" to JsonPrimitive("Quick SOS alert and evidence capture started for Family Caregiver Dashboard.")
                        )
                    ),
                    id = call.id
                )
            }

            else -> FunctionResponsePart(
                name = call.name,
                response = JsonObject(
                    mapOf("error" to JsonPrimitive("Unknown tool ${call.name}"))
                ),
                id = call.id
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
            commitModelTurnIfUnique(a)
            aiTurnBuffer.clear()
        }
        turnTextPartBuffer.clear()
    }

    /**
     * Appends streamed transcription tokens while preserving sub-word token boundaries
     * (never inserting stray spaces inside words like "Hel" + "lo" or "Amar" + "deep"),
     * and adding a space after sentence/clause punctuation when two sentences touch.
     */
    internal fun appendTranscriptionPiece(sb: StringBuilder, piece: String) {
        if (piece.isEmpty()) return
        if (sb.isEmpty()) {
            sb.append(piece.trimStart())
            return
        }
        val prevLast = sb.last()
        val nextFirst = piece.first()
        if (!prevLast.isWhitespace() &&
            !nextFirst.isWhitespace() &&
            prevLast in charArrayOf('.', '!', '?', ',', ':', ';') &&
            (nextFirst.isLetterOrDigit() || nextFirst == '"' || nextFirst == '\'')
        ) {
            sb.append(' ')
        }
        sb.append(piece)
    }

    private fun appendDeduplicatedAiText(sb: StringBuilder, piece: String) {
        appendTranscriptionPiece(sb, piece)
        val current = sb.toString()
        val sentences = current.split(Regex("(?<=[.!?])\\s+"))
        if (sentences.size >= 2) {
            val deduped = mutableListOf<String>()
            for (s in sentences) {
                val trimmed = s.trim()
                if (trimmed.isNotBlank() &&
                    (deduped.isEmpty() || !deduped.last().equals(trimmed, ignoreCase = true))
                ) {
                    deduped.add(s)
                }
            }
            if (deduped.size < sentences.size) {
                sb.clear()
                sb.append(deduped.joinToString(" "))
            }
        }
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
        scaled.compress(Bitmap.CompressFormat.JPEG, 68, stream)
        if (scaled !== safeBitmap) scaled.recycle()
        if (safeBitmap !== bitmap) safeBitmap.recycle()
        return stream.toByteArray()
    }

    private fun configureCommunicationAudio(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (previousAudioMode == null) previousAudioMode = am.mode
            if (previousSpeakerphone == null) previousSpeakerphone = @Suppress("DEPRECATION") am.isSpeakerphoneOn
            if (previousMicMute == null) previousMicMute = am.isMicrophoneMute

            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isMicrophoneMute = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = am.availableCommunicationDevices
                val preferredDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                } ?: devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (preferredDevice != null && am.communicationDevice?.id != preferredDevice.id) {
                    am.setCommunicationDevice(preferredDevice)
                }
            } else {
                @Suppress("DEPRECATION")
                if (!am.isSpeakerphoneOn) {
                    am.isSpeakerphoneOn = true
                }
            }
        }.onFailure {
            Log.w(TAG, "Unable to configure audio", it)
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

    private fun voiceDisplayName(id: String): String = when (id) {
        "AOEDE" -> "Warm (Aoede)"
        "KORE" -> "Gentle (Kore)"
        "PUCK" -> "Bright (Puck)"
        "FENRIR" -> "Deep (Fenrir)"
        "CHARON" -> "Calm (Charon)"
        else -> id
    }
}
