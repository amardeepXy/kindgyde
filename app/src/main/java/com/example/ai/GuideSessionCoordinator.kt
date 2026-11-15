package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.data.model.CalendarEvent
import com.example.data.model.CareCircle
import com.example.data.model.CareTask
import com.example.data.model.ConversationMessage
import com.example.data.model.MedicationItem
import com.example.data.repository.KindGuideRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin

data class OverlayScreenSnapshot(
    val packageName: String,
    val visibleLabels: List<String>,
    val nodeBoundsByLabel: Map<String, Rect>,
    val screenshot: Bitmap?
)

/**
 * Process-level coordinator shared between in-app ViewModels and the
 * ScreenGuideAccessibilityService floating overlay so step-by-step coaching,
 * natural voice reading with live voice meter, and Firestore CRUD keep working
 * even when KindGuide is backgrounded.
 */
object GuideSessionCoordinator {

    private const val TAG = "GuideCoordinator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var appContext: Context? = null
    private var repository: KindGuideRepository? = null
    private val aiService = KindGuideAiService()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voiceMeterJob: Job? = null

    var currentCircle: CareCircle? = null
    var currentTasks: List<CareTask> = emptyList()
    var currentEvents: List<CalendarEvent> = emptyList()
    var currentMedications: List<MedicationItem> = emptyList()
    var currentHistory: List<ConversationMessage> = emptyList()

    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    private val _isOverlayVisible = MutableStateFlow(false)
    val isOverlayVisible: StateFlow<Boolean> = _isOverlayVisible.asStateFlow()

    private val _activeStep = MutableStateFlow<AiGuideStep?>(null)
    val activeStep: StateFlow<AiGuideStep?> = _activeStep.asStateFlow()

    private val _lastReply = MutableStateFlow("")
    val lastReply: StateFlow<String> = _lastReply.asStateFlow()

    private val _streamingReply = MutableStateFlow("")
    val streamingReply: StateFlow<String> = _streamingReply.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _errorText = MutableStateFlow<String?>(null)
    val errorText: StateFlow<String?> = _errorText.asStateFlow()

    private val _voiceReadAloudEnabled = MutableStateFlow(true)
    val voiceReadAloudEnabled: StateFlow<Boolean> = _voiceReadAloudEnabled.asStateFlow()

    private val _speakingMessageId = MutableStateFlow<String?>(null)
    val speakingMessageId: StateFlow<String?> = _speakingMessageId.asStateFlow()

    private val _voiceMeterLevel = MutableStateFlow(0f)
    val voiceMeterLevel: StateFlow<Float> = _voiceMeterLevel.asStateFlow()

    private val _targetHighlightBounds = MutableStateFlow<Rect?>(null)
    val targetHighlightBounds: StateFlow<Rect?> = _targetHighlightBounds.asStateFlow()

    private val _activeScreenPackage = MutableStateFlow("")
    val activeScreenPackage: StateFlow<String> = _activeScreenPackage.asStateFlow()

    private val _crudFeedbackBanner = MutableStateFlow<String?>(null)
    val crudFeedbackBanner: StateFlow<String?> = _crudFeedbackBanner.asStateFlow()

    private var screenSnapshotRequester: (suspend () -> OverlayScreenSnapshot?)? = null
    private var latestNodeBounds: Map<String, Rect> = emptyMap()

    fun initialize(context: Context, repo: KindGuideRepository) {
        if (appContext == null) {
            appContext = context.applicationContext
            initTts(context.applicationContext)
        }
        repository = repo
    }

    private fun initTts(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureNaturalNeuralVoice()
                ttsReady = true
            }
        }
    }

    /**
     * Selects the highest-quality neural voice installed on the device rather than
     * the robotic default voice, and attaches an UtteranceProgressListener to drive
     * the live Voice Meter animation.
     */
    private fun configureNaturalNeuralVoice() {
        val engine = tts ?: return
        runCatching {
            val defaultLocale = Locale.getDefault()
            val targetLang = defaultLocale.language.ifBlank { "en" }
            val availableVoices = engine.voices?.toList().orEmpty()

            val bestVoice: Voice? = availableVoices
                .filter { v ->
                    v.locale.language.equals(targetLang, ignoreCase = true) &&
                        !v.features.orEmpty().contains("legacySet")
                }
                .sortedWith(
                    compareByDescending<Voice> { it.quality }
                        .thenBy { it.latency }
                        .thenByDescending { v ->
                            val name = v.name.lowercase()
                            name.contains("neural") ||
                                name.contains("wavenet") ||
                                name.contains("iob") ||
                                name.contains("tpf") ||
                                name.contains("sfg") ||
                                name.contains("end")
                        }
                        .thenBy { it.isNetworkConnectionRequired }
                )
                .firstOrNull()

            if (bestVoice != null) {
                engine.voice = bestVoice
            } else {
                engine.language = defaultLocale
            }

            // Warm, natural human prosody instead of flat default synthesis
            engine.setPitch(1.03f)
            engine.setSpeechRate(0.95f)

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    startVoiceMeterAnimation(utteranceId)
                }

                override fun onRangeStart(
                    utteranceId: String?,
                    start: Int,
                    end: Int,
                    frame: Int
                ) {
                    val wordLen = (end - start).coerceIn(1, 14)
                    val pulse = (0.35f + (wordLen / 14f) * 0.65f).coerceIn(0.3f, 1.0f)
                    _voiceMeterLevel.value = pulse
                }

                override fun onDone(utteranceId: String?) {
                    stopVoiceMeterAnimation()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    stopVoiceMeterAnimation()
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    stopVoiceMeterAnimation()
                }
            })
        }
    }

    private fun startVoiceMeterAnimation(utteranceId: String?) {
        voiceMeterJob?.cancel()
        voiceMeterJob = scope.launch {
            var tick = 0
            while (isActive && _speakingMessageId.value != null) {
                tick += 1
                val wave1 = abs(sin(tick * 0.34f))
                val wave2 = abs(sin(tick * 0.19f + 1.1f))
                val envelope = (0.24f + 0.48f * wave1 + 0.28f * wave2).coerceIn(0.2f, 1.0f)
                _voiceMeterLevel.value = envelope
                delay(55L)
            }
            _voiceMeterLevel.value = 0f
        }
    }

    private fun stopVoiceMeterAnimation() {
        voiceMeterJob?.cancel()
        voiceMeterJob = null
        _speakingMessageId.value = null
        _voiceMeterLevel.value = 0f
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _isAccessibilityConnected.value = connected
        if (!connected) {
            _isOverlayVisible.value = false
            _targetHighlightBounds.value = null
        }
    }

    fun registerScreenSnapshotRequester(requester: (suspend () -> OverlayScreenSnapshot?)?) {
        screenSnapshotRequester = requester
    }

    fun updateLiveScreenNodes(packageName: String, boundsByLabel: Map<String, Rect>) {
        _activeScreenPackage.value = packageName
        latestNodeBounds = boundsByLabel
        refreshSpotlightBounds()
    }

    fun setOverlayVisible(visible: Boolean) {
        _isOverlayVisible.value = visible && _isAccessibilityConnected.value
        if (!_isOverlayVisible.value) {
            _targetHighlightBounds.value = null
        } else {
            refreshSpotlightBounds()
        }
    }

    fun toggleVoiceReadAloud() {
        val next = !_voiceReadAloudEnabled.value
        _voiceReadAloudEnabled.value = next
        if (!next) {
            stopTtsOnly()
        }
    }

    /**
     * Toggles natural voice playback for a specific chat message bubble and drives
     * the animated Voice Meter in the UI.
     */
    fun toggleSpeakMessage(messageId: String, rawMarkdownText: String) {
        if (_speakingMessageId.value == messageId) {
            stopTtsOnly()
            return
        }
        speakText(rawMarkdownText, slow = false, messageId = messageId)
    }

    fun speakText(text: String, slow: Boolean = false, messageId: String = "manual_tts") {
        if (!ttsReady || text.isBlank()) return
        val cleanSpeech = cleanMarkdownForSpeech(text)
        if (cleanSpeech.isBlank()) return

        _speakingMessageId.value = messageId
        startVoiceMeterAnimation(messageId)

        tts?.setPitch(1.03f)
        tts?.setSpeechRate(if (slow) 0.82f else 0.95f)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(cleanSpeech, TextToSpeech.QUEUE_FLUSH, params, messageId)
    }

    /**
     * Strips Markdown syntax (headings, bold/italic asterisks, blockquotes, code ticks)
     * so the voice reader speaks smooth, natural sentences without reading symbols aloud.
     */
    private fun cleanMarkdownForSpeech(markdown: String): String {
        return markdown
            .lines()
            .map { line ->
                line.trim()
                    .replace(Regex("^#{1,6}\\s*"), "")
                    .replace(Regex("^>\\s*"), "")
                    .replace(Regex("^[-*•]\\s+"), "")
                    .replace(Regex("^(\\d+)\\.\\s+"), "Step $1: ")
                    .replace("**", "")
                    .replace("__", "")
                    .replace("`", "")
                    .replace(Regex("(?<!\\w)\\*(?!\\s)"), "")
            }
            .filter { it.isNotBlank() }
            .joinToString(". ")
            .replace(Regex("\\.\\s*\\.+"), ".")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun repeatCurrentStepSlowly() {
        val step = _activeStep.value
        val textToSpeak = step?.instruction ?: _lastReply.value
        if (textToSpeak.isNotBlank()) {
            speakText(textToSpeak, slow = true, messageId = "step_repeat")
        }
    }

    fun clearGuideSession() {
        _activeStep.value = null
        _targetHighlightBounds.value = null
        _isOverlayVisible.value = false
        stopTtsOnly()
    }

    fun stopTtsOnly() {
        tts?.stop()
        stopVoiceMeterAnimation()
    }

    fun setLiveGuideStep(step: AiGuideStep) {
        _activeStep.value = step
        _lastReply.value = step.instruction
        if (_isAccessibilityConnected.value) {
            _isOverlayVisible.value = true
        }
        refreshSpotlightBounds()
    }

    fun executeLiveCrud(cmd: AiCrudCommand) {
        val circle = currentCircle ?: return
        scope.launch {
            executeCrudCommand(circle, cmd)
        }
    }

    fun flagLiveStuckHelp(reason: String) {
        val circle = currentCircle ?: return
        scope.launch {
            repository?.createAlert(
                circleId = circle.id,
                memberUids = circle.memberUids,
                type = "stuck_help",
                message = "${circle.seniorName.ifBlank { "Senior" }} needed help during Gemini Live: ${reason.take(180)}"
            )
        }
    }

    fun recordLiveTranscriptTurn(role: String, text: String) {
        val clean = text.trim()
        val circle = currentCircle ?: return
        if (clean.isBlank()) return
        scope.launch {
            repository?.addConversationMessage(
                circleId = circle.id,
                memberUids = circle.memberUids,
                role = role,
                text = clean,
                guideStepTitle = _activeStep.value?.stepTitle.orEmpty(),
                stepNumber = (_activeStep.value?.stepNumber ?: 0).toLong(),
                totalSteps = (_activeStep.value?.totalSteps ?: 0).toLong(),
                isStuckFlagged = false
            )
        }
    }

    fun buildLiveSystemContext(): String {
        val circleSummary = buildCircleSummary(currentCircle)
        val recentTurns = currentHistory.takeLast(6).joinToString("\n") { msg ->
            "${if (msg.role == "model") "KindGuide" else "Senior"}: ${msg.text}"
        }
        val visibleLabels = latestNodeBounds.keys.take(30).joinToString(" | ")
        return buildString {
            appendLine(circleSummary)
            if (recentTurns.isNotBlank()) {
                appendLine()
                appendLine("RECENT CONVERSATION MEMORY:")
                appendLine(recentTurns)
            }
            if (visibleLabels.isNotBlank()) {
                appendLine()
                appendLine("CURRENT SCREEN ELEMENTS (${_activeScreenPackage.value}): $visibleLabels")
            }
        }
    }

    fun getCurrentVisibleNodeSummary(): String {
        val pkg = _activeScreenPackage.value
        val labels = latestNodeBounds.keys.take(25)
        return if (pkg.isBlank() && labels.isEmpty()) {
            ""
        } else {
            "Active app: $pkg | Visible buttons: ${labels.joinToString(", ")}"
        }
    }

    fun dismissCrudBanner() {
        _crudFeedbackBanner.value = null
    }

    fun clearError() {
        _errorText.value = null
    }

    fun syncBundle(
        circle: CareCircle,
        tasks: List<CareTask>,
        events: List<CalendarEvent>,
        medications: List<MedicationItem>,
        history: List<ConversationMessage>
    ) {
        currentCircle = circle
        currentTasks = tasks
        currentEvents = events
        currentMedications = medications
        currentHistory = history
    }

    fun askCompanion(
        query: String,
        attachedScreenshot: Bitmap? = null,
        extraScreenContext: String = "",
        activateOverlayIfConnected: Boolean = false,
        markStuckAlert: Boolean = false,
        displayTextInChat: String = query
    ) {
        val cleanQuery = query.trim()
        val bubbleText = displayTextInChat.trim().ifBlank { cleanQuery }
        if (cleanQuery.isBlank()) return

        // Stop any previous speech when a new prompt is sent
        stopTtsOnly()

        scope.launch {
            _isBusy.value = true
            _streamingReply.value = ""
            _errorText.value = null
            val circle = currentCircle
            try {
                val historySnapshot = currentHistory.toList()
                if (circle != null) {
                    repository?.addConversationMessage(
                        circleId = circle.id,
                        memberUids = circle.memberUids,
                        role = "user",
                        text = bubbleText,
                        isStuckFlagged = markStuckAlert
                    )
                    if (markStuckAlert) {
                        val stepDesc = _activeStep.value?.let {
                            "Step ${it.stepNumber}/${it.totalSteps} (${it.stepTitle}): ${it.instruction}"
                        } ?: bubbleText
                        repository?.createAlert(
                            circleId = circle.id,
                            memberUids = circle.memberUids,
                            type = "stuck_help",
                            message = "${circle.seniorName.ifBlank { "Senior" }} asked for simpler help on: $stepDesc"
                        )
                    }
                }

                val liveSnap = if (attachedScreenshot == null && _isAccessibilityConnected.value) {
                    screenSnapshotRequester?.invoke()
                } else {
                    null
                }
                val effectiveBitmap = attachedScreenshot ?: liveSnap?.screenshot
                val screenSummary = buildString {
                    if (extraScreenContext.isNotBlank()) appendLine(extraScreenContext)
                    if (liveSnap != null) {
                        appendLine("Active app package: ${liveSnap.packageName}")
                        if (liveSnap.visibleLabels.isNotEmpty()) {
                            appendLine("Visible on-screen elements: ${liveSnap.visibleLabels.take(45).joinToString(" | ")}")
                        }
                        latestNodeBounds = liveSnap.nodeBoundsByLabel
                    }
                }

                val circleSummary = buildCircleSummary(circle)

                val result = aiService.streamCompanionTurn(
                    userMessage = cleanQuery,
                    historyMessages = historySnapshot,
                    screenContextSummary = screenSummary,
                    screenshot = effectiveBitmap,
                    circleContextSummary = circleSummary,
                    onPartialReply = { partial ->
                        _streamingReply.value = partial
                    }
                )

                val replyText = result.reply.ifBlank {
                    result.guideStep?.instruction ?: "I'm right here with you."
                }
                _lastReply.value = replyText

                // Do NOT automatically speak the response on arrival;
                // user can tap the speaker/voice meter button on any message to listen.
                if (result.guideStep != null) {
                    _activeStep.value = result.guideStep
                    if (activateOverlayIfConnected && _isAccessibilityConnected.value) {
                        _isOverlayVisible.value = true
                    }
                    refreshSpotlightBounds()
                } else {
                    _activeStep.value = null
                }

                if (circle != null) {
                    result.crudCommand?.let { executeCrudCommand(circle, it) }
                    repository?.addConversationMessage(
                        circleId = circle.id,
                        memberUids = circle.memberUids,
                        role = "model",
                        text = replyText,
                        guideStepTitle = result.guideStep?.stepTitle.orEmpty(),
                        stepNumber = (result.guideStep?.stepNumber ?: 0).toLong(),
                        totalSteps = (result.guideStep?.totalSteps ?: 0).toLong(),
                        isStuckFlagged = false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "askCompanion failed", e)
                val fallbackMsg = "I'm right here to help with your phone, tasks, and medicines. Could you say that once more?"
                _lastReply.value = fallbackMsg
                if (circle != null) {
                    repository?.addConversationMessage(
                        circleId = circle.id,
                        memberUids = circle.memberUids,
                        role = "model",
                        text = fallbackMsg,
                        isStuckFlagged = false
                    )
                }
            } finally {
                _streamingReply.value = ""
                _isBusy.value = false
            }
        }
    }

    fun advanceStepWithLiveScreen() {
        val step = _activeStep.value
        val prompt = if (step != null) {
            "I finished Step ${step.stepNumber} (${step.instruction}). Read my current screen and give me ONLY the next single step, or congratulate me if the task is done."
        } else {
            "Read my current screen and tell me the single best next step to do here."
        }
        val shortBubble = if (step != null) {
            "Finished Step ${step.stepNumber} — what is the next step?"
        } else {
            "What is the next step?"
        }
        askCompanion(
            query = prompt,
            activateOverlayIfConnected = true,
            displayTextInChat = shortBubble
        )
    }

    fun explainCurrentStepDifferently() {
        val step = _activeStep.value
        val prompt = if (step != null) {
            "I am stuck on Step ${step.stepNumber}: \"${step.instruction}\". Please repeat or explain this same step in simpler everyday words using what is visible on screen."
        } else {
            "Please explain your last instruction more simply, one small step at a time."
        }
        askCompanion(
            query = prompt,
            activateOverlayIfConnected = true,
            markStuckAlert = true,
            displayTextInChat = "Please explain this step more simply."
        )
    }

    private suspend fun executeCrudCommand(circle: CareCircle, cmd: AiCrudCommand) {
        val repo = repository ?: return
        val op = cmd.operation.lowercase()
        val entity = cmd.entity.lowercase()
        val title = cmd.title.trim()

        when (entity) {
            "task" -> when (op) {
                "create" -> if (title.isNotBlank()) {
                    repo.createTask(
                        circleId = circle.id,
                        memberUids = circle.memberUids,
                        title = title,
                        category = cmd.extra.ifBlank { "AI Added" },
                        dueTimeLabel = cmd.subtitle.ifBlank { "Today" },
                        createdBy = "ai"
                    )
                    _crudFeedbackBanner.value = "Added task: $title"
                }
                "complete" -> {
                    val match = currentTasks.firstOrNull {
                        !it.completed && it.title.contains(title, ignoreCase = true)
                    } ?: currentTasks.firstOrNull { !it.completed }
                    if (match != null) {
                        repo.updateTask(circle.id, match.id, mapOf("completed" to true))
                        _crudFeedbackBanner.value = "Completed task: ${match.title}"
                    }
                }
                "update" -> {
                    val match = currentTasks.firstOrNull {
                        it.title.contains(title, ignoreCase = true)
                    }
                    if (match != null) {
                        val updates = mutableMapOf<String, Any>()
                        if (cmd.subtitle.isNotBlank()) updates["dueTimeLabel"] = cmd.subtitle
                        if (cmd.extra.isNotBlank()) updates["category"] = cmd.extra
                        if (updates.isNotEmpty()) {
                            repo.updateTask(circle.id, match.id, updates)
                            _crudFeedbackBanner.value = "Updated task: ${match.title}"
                        }
                    }
                }
                "delete" -> {
                    val match = currentTasks.firstOrNull {
                        it.title.contains(title, ignoreCase = true)
                    }
                    if (match != null) {
                        repo.deleteTask(circle.id, match.id)
                        _crudFeedbackBanner.value = "Removed task: ${match.title}"
                    }
                }
            }

            "calendar_event" -> when (op) {
                "create" -> if (title.isNotBlank()) {
                    repo.createEvent(
                        circleId = circle.id,
                        memberUids = circle.memberUids,
                        title = title,
                        dateLabel = cmd.subtitle.ifBlank { "Upcoming" },
                        timeLabel = cmd.extra.ifBlank { "10:00 AM" },
                        location = "",
                        notes = "Added by KindGuide",
                        createdBy = "ai"
                    )
                    _crudFeedbackBanner.value = "Added event: $title"
                }
                "update" -> {
                    val match = currentEvents.firstOrNull {
                        it.title.contains(title, ignoreCase = true)
                    }
                    if (match != null) {
                        val updates = mutableMapOf<String, Any>()
                        if (cmd.subtitle.isNotBlank()) updates["dateLabel"] = cmd.subtitle
                        if (cmd.extra.isNotBlank()) updates["timeLabel"] = cmd.extra
                        if (updates.isNotEmpty()) {
                            repo.updateEvent(circle.id, match.id, updates)
                            _crudFeedbackBanner.value = "Updated event: ${match.title}"
                        }
                    }
                }
                "delete" -> {
                    val match = currentEvents.firstOrNull {
                        it.title.contains(title, ignoreCase = true)
                    }
                    if (match != null) {
                        repo.deleteEvent(circle.id, match.id)
                        _crudFeedbackBanner.value = "Removed event: ${match.title}"
                    }
                }
            }

            "medication" -> when (op) {
                "create" -> if (title.isNotBlank()) {
                    repo.createMedication(
                        circleId = circle.id,
                        memberUids = circle.memberUids,
                        name = title,
                        dosage = cmd.subtitle.ifBlank { "1 tablet" },
                        scheduleSlot = cmd.extra.ifBlank { "Morning" }
                    )
                    _crudFeedbackBanner.value = "Added medicine: $title"
                }
                "complete" -> {
                    val match = currentMedications.firstOrNull {
                        !it.takenToday && it.name.contains(title, ignoreCase = true)
                    } ?: currentMedications.firstOrNull { !it.takenToday }
                    if (match != null) {
                        repo.updateMedication(
                            circle.id,
                            match.id,
                            mapOf("takenToday" to true, "lastTakenLabel" to "Marked by KindGuide")
                        )
                        _crudFeedbackBanner.value = "Logged medicine taken: ${match.name}"
                    }
                }
                "delete" -> {
                    val match = currentMedications.firstOrNull {
                        it.name.contains(title, ignoreCase = true)
                    }
                    if (match != null) {
                        repo.deleteMedication(circle.id, match.id)
                        _crudFeedbackBanner.value = "Removed medicine: ${match.name}"
                    }
                }
            }

            "voice_note" -> if (op == "create" && title.isNotBlank()) {
                repo.addVoiceNote(
                    circleId = circle.id,
                    memberUids = circle.memberUids,
                    senderName = circle.seniorName.ifBlank { "Senior" },
                    durationSeconds = 5L,
                    transcript = "$title ${cmd.subtitle}".trim(),
                    isFromCaregiver = false
                )
                _crudFeedbackBanner.value = "Saved voice note: $title"
            }
        }
    }

    private fun refreshSpotlightBounds() {
        val hint = _activeStep.value?.targetButtonHint?.trim().orEmpty()
        if (hint.isBlank() || !_isOverlayVisible.value) {
            _targetHighlightBounds.value = null
            return
        }
        val match = latestNodeBounds.entries.firstOrNull { (label, _) ->
            label.equals(hint, ignoreCase = true) ||
                label.contains(hint, ignoreCase = true) ||
                hint.contains(label, ignoreCase = true)
        }
        _targetHighlightBounds.value = match?.value
    }

    private fun buildCircleSummary(circle: CareCircle?): String {
        if (circle == null) return "No Care Circle loaded yet."
        val openTasks = currentTasks.filter { !it.completed }.take(10).joinToString("; ") {
            "${it.title} (${it.dueTimeLabel}, ${it.category})"
        }
        val completedTasks = currentTasks.filter { it.completed }.take(6).joinToString("; ") {
            it.title
        }
        val upcomingEvents = currentEvents.take(8).joinToString("; ") {
            "${it.title} on ${it.dateLabel} at ${it.timeLabel}"
        }
        val pendingMeds = currentMedications.filter { !it.takenToday }.take(8).joinToString("; ") {
            "${it.name} (${it.dosage}, ${it.scheduleSlot} - NOT taken yet)"
        }
        val takenMeds = currentMedications.filter { it.takenToday }.take(8).joinToString("; ") {
            "${it.name} (${it.dosage}, ${it.scheduleSlot} - Taken today)"
        }
        return """
            Senior Name: ${circle.seniorName}
            Emergency Contact: ${circle.emergencyContactName} (${circle.emergencyContactPhone})
            Home Address: ${circle.homeAddress}
            Open Tasks: ${openTasks.ifBlank { "None" }}
            Completed Tasks Today: ${completedTasks.ifBlank { "None" }}
            Medications Due Today: ${pendingMeds.ifBlank { "All taken / None due" }}
            Medications Already Taken: ${takenMeds.ifBlank { "None yet" }}
            Calendar Events: ${upcomingEvents.ifBlank { "None" }}
        """.trimIndent()
    }
}
