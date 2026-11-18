package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioAttributes
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
import com.example.service.ScreenGuideAccessibilityService
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

data class OverlayScreenSnapshot(
    val packageName: String,
    val visibleLabels: List<String>,
    val nodeBoundsByLabel: Map<String, Rect>,
    val screenshot: Bitmap?
)

/**
 * Represents a single step completed (or verified) by KindGuide AI in the current session,
 * displayed in the persistent, scrollable 'Task History' drawer on the floating overlay.
 */
data class CompletedSessionStep(
    val id: String = UUID.randomUUID().toString(),
    val stepIndex: Int,
    val actionType: String,
    val title: String,
    val detail: String,
    val appName: String,
    val statusIcon: String,
    val succeeded: Boolean,
    val timestampLabel: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Process-level coordinator shared between in-app ViewModels, GeminiLiveManager,
 * and ScreenGuideAccessibilityService so step-by-step on-screen coaching,
 * verified spotlight highlighting, real-time wrong-click monitoring, confirmation
 * cards, and Firestore CRUD work end-to-end across both KindGuide and external apps.
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
    private var monitorActionJob: Job? = null
    private var lastMonitoredClickAtMs = 0L
    private val ttsQueueLock = Any()
    private val activeTtsUtterances = mutableSetOf<String>()
    internal var customSpeechSink: ((messageId: String, spokenText: String, queueAdd: Boolean) -> Unit)? = null

    const val LEGACY_THREAD_ID = "legacy_chat"

    var currentCircle: CareCircle? = null
    var currentTasks: List<CareTask> = emptyList()
    var currentEvents: List<CalendarEvent> = emptyList()
    var currentMedications: List<MedicationItem> = emptyList()
    var allConversations: List<ConversationMessage> = emptyList()
    var currentHistory: List<ConversationMessage> = emptyList()

    private val _activeConversationId = MutableStateFlow(UUID.randomUUID().toString())
    val activeConversationId: StateFlow<String> = _activeConversationId.asStateFlow()

    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    private val _isOverlayVisible = MutableStateFlow(false)
    val isOverlayVisible: StateFlow<Boolean> = _isOverlayVisible.asStateFlow()

    private val _activeStep = MutableStateFlow<AiGuideStep?>(null)
    val activeStep: StateFlow<AiGuideStep?> = _activeStep.asStateFlow()

    private val _stepMonitorBanner = MutableStateFlow<String?>(null)
    val stepMonitorBanner: StateFlow<String?> = _stepMonitorBanner.asStateFlow()

    private val _pendingConfirmationAction = MutableStateFlow<AssistiveScreenAction?>(null)
    val pendingConfirmationAction: StateFlow<AssistiveScreenAction?> = _pendingConfirmationAction.asStateFlow()

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

    private val _isHighlightEnabled = MutableStateFlow(true)
    val isHighlightEnabled: StateFlow<Boolean> = _isHighlightEnabled.asStateFlow()

    private val _targetHighlightBounds = MutableStateFlow<Rect?>(null)
    val targetHighlightBounds: StateFlow<Rect?> = _targetHighlightBounds.asStateFlow()

    private val _activeScreenPackage = MutableStateFlow("")
    val activeScreenPackage: StateFlow<String> = _activeScreenPackage.asStateFlow()

    private val _isScreenTaskActive = MutableStateFlow(false)
    val isScreenTaskActive: StateFlow<Boolean> = _isScreenTaskActive.asStateFlow()

    private val _isGeminiLiveScreenVisible = MutableStateFlow(false)
    val isGeminiLiveScreenVisible: StateFlow<Boolean> = _isGeminiLiveScreenVisible.asStateFlow()

    private val _isKindGuideActivityResumed = MutableStateFlow(true)
    val isKindGuideActivityResumed: StateFlow<Boolean> = _isKindGuideActivityResumed.asStateFlow()

    private val _crudFeedbackBanner = MutableStateFlow<String?>(null)
    val crudFeedbackBanner: StateFlow<String?> = _crudFeedbackBanner.asStateFlow()

    private val _sessionTaskHistory = MutableStateFlow<List<CompletedSessionStep>>(emptyList())
    val sessionTaskHistory: StateFlow<List<CompletedSessionStep>> = _sessionTaskHistory.asStateFlow()

    private val _isTaskHistoryDrawerOpen = MutableStateFlow(false)
    val isTaskHistoryDrawerOpen: StateFlow<Boolean> = _isTaskHistoryDrawerOpen.asStateFlow()

    private const val PREFS_TASK_HISTORY = "kindguide_session_task_history_prefs"
    private const val KEY_ACTIVE_SESSION_ID = "active_session_id"

    private var screenSnapshotRequester: (suspend () -> OverlayScreenSnapshot?)? = null
    private var screenActionExecutor: (suspend (AssistiveScreenAction) -> Boolean)? = null
    private var inAppScreenshotProvider: (() -> Bitmap?)? = null
    private var quickSosTrigger: (() -> Unit)? = null
    private var latestNodeBounds: Map<String, Rect> = emptyMap()
    private var inAppNodeBounds: Map<String, Rect> = emptyMap()

    // Tracks the target contact or sub-goal across multi-step voice requests (e.g., "Amardeep Sharma")
    var lastRequestedContactName: String = ""
        private set
    var pendingSubGoal: String = ""
        private set
    var pendingMessageText: String = ""
        private set
    var pausedTaskDescription: String = ""
        private set
    var isTaskAwaitingUserTap: Boolean = false
        private set
    private var lastAutoFulfilledSignature: String = ""
    private var lastAutoFulfilledAtMs: Long = 0L
    private var lastExecutedActionSignature: String = ""
    private var lastExecutedActionAtMs: Long = 0L
    private var lastExecutedActionSuccess: Boolean = false

    // Anti-jitter tracking for spotlight bounds
    private var lastLockedHighlightPkg: String = ""
    private var lastLockedHighlightLabel: String = ""
    private var lastLockedHighlightRect: Rect? = null

    private val ignoredControlLabels = setOf(
        "highlight on",
        "highlight off",
        "tap for me",
        "confirm & tap",
        "✅ send now",
        "✕ cancel",
        "📋 history",
        "history",
        "task history",
        "hide ▲",
        "clear",
        "live",
        "open",
        "expand",
        "expand live",
        "minimize",
        "minimize & highlight",
        "show on screen",
        "pause",
        "interrupt",
        "end",
        "end live",
        "close",
        "dismiss",
        "✕"
    )

    private val taskExecutionHost = object : TaskExecutionHost {
        override val appContext: Context? get() = GuideSessionCoordinator.appContext
        override val isAccessibilityConnected: Boolean get() = _isAccessibilityConnected.value
        override val activeScreenPackage: String get() = _activeScreenPackage.value
        override val latestNodeBounds: Map<String, Rect> get() = GuideSessionCoordinator.latestNodeBounds
        override val pendingConfirmationAction: AssistiveScreenAction? get() = _pendingConfirmationAction.value
        override val activeStep: AiGuideStep? get() = _activeStep.value
        override val lastRequestedContactName: String get() = GuideSessionCoordinator.lastRequestedContactName
        override val pendingSubGoal: String get() = GuideSessionCoordinator.pendingSubGoal
        override val pendingMessageText: String get() = GuideSessionCoordinator.pendingMessageText
        override val pausedTaskDescription: String get() = GuideSessionCoordinator.pausedTaskDescription
        override val isTaskAwaitingUserTap: Boolean get() = GuideSessionCoordinator.isTaskAwaitingUserTap
        override val lastExecutedActionSignature: String get() = GuideSessionCoordinator.lastExecutedActionSignature
        override val lastExecutedActionAtMs: Long get() = GuideSessionCoordinator.lastExecutedActionAtMs
        override val screenSnapshotRequester: (suspend () -> OverlayScreenSnapshot?)?
            get() = GuideSessionCoordinator.screenSnapshotRequester
        override val screenActionExecutor: (suspend (AssistiveScreenAction) -> Boolean)?
            get() = GuideSessionCoordinator.screenActionExecutor

        override fun updateScreenTaskActive(active: Boolean) {
            _isScreenTaskActive.value = active
        }

        override fun updateOverlayVisible(visible: Boolean) {
            _isOverlayVisible.value = visible && _isAccessibilityConnected.value
        }

        override fun updatePendingConfirmation(action: AssistiveScreenAction?) {
            _pendingConfirmationAction.value = action
        }

        override fun updateStepMonitorBanner(banner: String?) {
            _stepMonitorBanner.value = banner
        }

        override fun updateLastReply(reply: String) {
            _lastReply.value = reply
        }

        override fun updateSpeakingMessageId(id: String?) {
            _speakingMessageId.value = id
        }

        override fun updateLastRequestedContactName(name: String) {
            GuideSessionCoordinator.lastRequestedContactName = name
        }

        override fun updatePendingSubGoal(goal: String) {
            GuideSessionCoordinator.pendingSubGoal = goal
        }

        override fun updatePendingMessageText(text: String) {
            GuideSessionCoordinator.pendingMessageText = text
        }

        override fun updatePausedTaskState(awaitingTap: Boolean, description: String) {
            GuideSessionCoordinator.isTaskAwaitingUserTap = awaitingTap
            GuideSessionCoordinator.pausedTaskDescription = description
        }

        override fun updateLastExecutedAction(signature: String, atMs: Long, succeeded: Boolean) {
            GuideSessionCoordinator.lastExecutedActionSignature = signature
            GuideSessionCoordinator.lastExecutedActionAtMs = atMs
            GuideSessionCoordinator.lastExecutedActionSuccess = succeeded
        }

        override fun updateLatestNodeBounds(bounds: Map<String, Rect>) {
            GuideSessionCoordinator.latestNodeBounds = bounds
        }

        override fun onForegroundWindowChanged(newPackageName: String) {
            GuideSessionCoordinator.onForegroundWindowChanged(newPackageName)
        }

        override fun setLiveGuideStep(step: AiGuideStep): Boolean =
            GuideSessionCoordinator.setLiveGuideStep(step)

        override fun refreshSpotlightBounds() {
            GuideSessionCoordinator.refreshSpotlightBounds()
        }

        override fun isChatComposerVisibleOnScreen(): Boolean =
            GuideSessionCoordinator.isChatComposerVisibleOnScreen()

        override fun getCurrentVisibleNodeSummary(): String =
            GuideSessionCoordinator.getCurrentVisibleNodeSummary()

        override fun isFuzzyLabelMatch(clickedLabel: String, targetHint: String): Boolean =
            GuideSessionCoordinator.isFuzzyLabelMatch(clickedLabel, targetHint)

        override fun isPackageMatchingAppName(packageName: String, appName: String): Boolean =
            GuideSessionCoordinator.isPackageMatchingAppName(packageName, appName)

        override fun recordCompletedSessionStep(
            actionType: String,
            title: String,
            detail: String,
            appName: String,
            statusIcon: String,
            succeeded: Boolean
        ) {
            GuideSessionCoordinator.recordCompletedSessionStep(
                actionType = actionType,
                title = title,
                detail = detail,
                appName = appName,
                statusIcon = statusIcon,
                succeeded = succeeded
            )
        }
    }

    init {
        TaskExecutionHandler.voiceReadAloudEnabledProvider = { _voiceReadAloudEnabled.value }
        TaskExecutionHandler.preferLiveNativeVoiceProvider = { GeminiLiveManager.state.value.isActive }
        InterruptController.isGuidedFlowActiveProvider = {
            _isScreenTaskActive.value ||
                _pendingConfirmationAction.value != null ||
                isTaskAwaitingUserTap
        }
        InterruptController.setOnAbortListener { reason, pivotCommand, hadActiveExecutions ->
            onTaskInterruptedByController(reason, pivotCommand, hadActiveExecutions)
        }
    }

    private fun onTaskInterruptedByController(
        reason: String,
        pivotCommand: String?,
        hadActiveExecutions: Boolean
    ) {
        TaskExecutionHandler.stopTtsImmediately()
        monitorActionJob?.cancel()
        monitorActionJob = null
        if (pivotCommand == null || hadActiveExecutions) {
            lastRequestedContactName = ""
            pendingSubGoal = ""
            pendingMessageText = ""
            pausedTaskDescription = ""
            isTaskAwaitingUserTap = false
            lastAutoFulfilledSignature = ""
            lastAutoFulfilledAtMs = 0L
            lastExecutedActionSignature = ""
            lastExecutedActionAtMs = 0L
            lastExecutedActionSuccess = false
        } else {
            // Switching from a paused failed search without active sub-tasks: keep pausedTaskDescription
            // for ask-once reminder while clearing stale contact/step intent immediately.
            lastRequestedContactName = ""
            pendingSubGoal = ""
            pendingMessageText = ""
            isTaskAwaitingUserTap = false
        }
        _pendingConfirmationAction.value = null
        _activeStep.value = null
        _targetHighlightBounds.value = null
        _isScreenTaskActive.value = false
        _stepMonitorBanner.value = if (pivotCommand != null) {
            "🔄 Pivoting to new request..."
        } else {
            "✕ Stopped previous task"
        }
        if (!pivotCommand.isNullOrBlank()) {
            noteUserTaskIntent(pivotCommand)
        }
    }

    fun initialize(context: Context, repo: KindGuideRepository) {
        if (appContext == null) {
            appContext = context.applicationContext
            initTts(context.applicationContext)
            restorePersistedSessionHistory()
        }
        repository = repo
    }

    fun toggleTaskHistoryDrawer(): Boolean {
        val next = !_isTaskHistoryDrawerOpen.value
        _isTaskHistoryDrawerOpen.value = next
        return next
    }

    fun setTaskHistoryDrawerOpen(open: Boolean) {
        _isTaskHistoryDrawerOpen.value = open
    }

    fun clearSessionTaskHistory() {
        _sessionTaskHistory.value = emptyList()
        persistSessionHistory(_activeConversationId.value, emptyList())
    }

    private fun recordCompletedSessionStep(
        actionType: String,
        title: String,
        detail: String,
        appName: String,
        statusIcon: String = "✅",
        succeeded: Boolean = true
    ) {
        val cleanTitle = title.trim()
        if (cleanTitle.isBlank()) return
        val currentList = _sessionTaskHistory.value
        // Avoid recording identical consecutive step entries within 1.5 seconds
        val lastEntry = currentList.lastOrNull()
        val now = System.currentTimeMillis()
        if (lastEntry != null &&
            lastEntry.title.equals(cleanTitle, ignoreCase = true) &&
            lastEntry.actionType == actionType &&
            now - lastEntry.timestampMs < 1500L
        ) {
            return
        }

        val timeFormat = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
        val stepEntry = CompletedSessionStep(
            stepIndex = currentList.size + 1,
            actionType = actionType,
            title = cleanTitle,
            detail = detail.trim(),
            appName = appName.substringBefore(" (").trim().ifBlank { "Phone Screen" },
            statusIcon = statusIcon,
            succeeded = succeeded,
            timestampLabel = timeFormat.format(Date(now)),
            timestampMs = now
        )
        val updated = (currentList + stepEntry).takeLast(60)
            .mapIndexed { idx, item -> item.copy(stepIndex = idx + 1) }
        _sessionTaskHistory.value = updated
        persistSessionHistory(_activeConversationId.value, updated)
    }

    private fun persistSessionHistory(sessionId: String, steps: List<CompletedSessionStep>) {
        val ctx = appContext ?: return
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS_TASK_HISTORY, Context.MODE_PRIVATE)
            val arr = JSONArray()
            for (step in steps) {
                val obj = JSONObject().apply {
                    put("id", step.id)
                    put("stepIndex", step.stepIndex)
                    put("actionType", step.actionType)
                    put("title", step.title)
                    put("detail", step.detail)
                    put("appName", step.appName)
                    put("statusIcon", step.statusIcon)
                    put("succeeded", step.succeeded)
                    put("timestampLabel", step.timestampLabel)
                    put("timestampMs", step.timestampMs)
                }
                arr.put(obj)
            }
            prefs.edit()
                .putString(KEY_ACTIVE_SESSION_ID, sessionId)
                .putString("steps_$sessionId", arr.toString())
                .apply()
        }
    }

    private fun loadPersistedHistoryForSession(sessionId: String): List<CompletedSessionStep> {
        val ctx = appContext ?: return emptyList()
        return runCatching {
            val prefs = ctx.getSharedPreferences(PREFS_TASK_HISTORY, Context.MODE_PRIVATE)
            val raw = prefs.getString("steps_$sessionId", null) ?: return emptyList()
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        CompletedSessionStep(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            stepIndex = obj.optInt("stepIndex", i + 1),
                            actionType = obj.optString("actionType", "STEP"),
                            title = obj.optString("title", ""),
                            detail = obj.optString("detail", ""),
                            appName = obj.optString("appName", "Phone Screen"),
                            statusIcon = obj.optString("statusIcon", "✅"),
                            succeeded = obj.optBoolean("succeeded", true),
                            timestampLabel = obj.optString("timestampLabel", ""),
                            timestampMs = obj.optLong("timestampMs", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun restorePersistedSessionHistory() {
        val ctx = appContext ?: return
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS_TASK_HISTORY, Context.MODE_PRIVATE)
            val savedSessionId = prefs.getString(KEY_ACTIVE_SESSION_ID, null)
            if (!savedSessionId.isNullOrBlank()) {
                val restored = loadPersistedHistoryForSession(savedSessionId)
                if (restored.isNotEmpty()) {
                    _activeConversationId.value = savedSessionId
                    _sessionTaskHistory.value = restored
                }
            }
        }
    }

    fun registerInAppScreenshotProvider(provider: (() -> Bitmap?)?) {
        inAppScreenshotProvider = provider
    }

    fun registerScreenActionExecutor(executor: (suspend (AssistiveScreenAction) -> Boolean)?) {
        screenActionExecutor = executor
    }

    fun registerQuickSosTrigger(trigger: (() -> Unit)?) {
        quickSosTrigger = trigger
    }

    fun triggerQuickSosNow(reason: String = "Voice Quick SOS request"): Boolean {
        val trigger = quickSosTrigger
        val circle = currentCircle
        return if (trigger != null) {
            scope.launch {
                trigger.invoke()
                recordCompletedSessionStep(
                    actionType = "QUICK_SOS",
                    title = "Triggered Quick SOS Alert",
                    detail = reason,
                    appName = "KindGuide",
                    statusIcon = "🚨",
                    succeeded = true
                )
            }
            true
        } else if (circle != null && repository != null) {
            scope.launch {
                repository?.createAlert(
                    circleId = circle.id,
                    memberUids = circle.memberUids,
                    type = "sos",
                    message = "Urgent SOS from ${circle.seniorName}: $reason",
                    latitude = circle.homeLat,
                    longitude = circle.homeLng
                )
                recordCompletedSessionStep(
                    actionType = "QUICK_SOS",
                    title = "Sent SOS Alert to Family",
                    detail = reason,
                    appName = "KindGuide",
                    statusIcon = "🚨",
                    succeeded = true
                )
            }
            true
        } else {
            false
        }
    }

    fun setKindGuideActivityResumed(resumed: Boolean) {
        _isKindGuideActivityResumed.value = resumed
        if (resumed) {
            val ownPkg = appContext?.packageName.orEmpty()
            if (ownPkg.isNotBlank()) {
                _activeScreenPackage.value = ownPkg
            }
        }
    }

    fun setGeminiLiveScreenVisible(visible: Boolean) {
        _isGeminiLiveScreenVisible.value = visible
    }

    fun clearTaskIntentAndRecoveryState(clearOverlayStep: Boolean = true) {
        lastRequestedContactName = ""
        pendingSubGoal = ""
        pendingMessageText = ""
        pausedTaskDescription = ""
        isTaskAwaitingUserTap = false
        lastAutoFulfilledSignature = ""
        lastAutoFulfilledAtMs = 0L
        lastExecutedActionSignature = ""
        lastExecutedActionAtMs = 0L
        lastExecutedActionSuccess = false
        if (clearOverlayStep) {
            _pendingConfirmationAction.value = null
            _activeStep.value = null
            _targetHighlightBounds.value = null
            _isScreenTaskActive.value = false
        }
    }

    /**
     * Extracts and remembers the target contact name (e.g., "Amardeep Sharma"), sub-goal
     * ("voice_call", "video_call", "send_message"), and optional message body from user speech.
     * Handles explicit cancel/leave/switch requests immediately and pauses failed tasks cleanly
     * without leaking stale contact intent into unrelated requests.
     */
    fun noteUserTaskIntent(rawUtterance: String) {
        val clean = rawUtterance.trim()
        if (clean.isBlank()) return
        val lower = clean.lowercase()

        val cancelOrLeaveRegex = Regex(
            "\\b(cancel|leave that|leave it|leave the task|leave this task|leave whatsapp|forget it|forget that|never mind|nevermind|quit|stop that|stop it|stop the task|drop it|skip it|don't do it|do not do it|abandon)\\b",
            RegexOption.IGNORE_CASE
        )
        val confirmSwitchRegex = Regex(
            "\\b(yes leave|yes cancel|yes switch|switch to|do that instead|leave and open)\\b",
            RegexOption.IGNORE_CASE
        )
        val explicitCancelMatch = cancelOrLeaveRegex.find(clean) ?: confirmSwitchRegex.find(clean)
        var workingUtterance = clean
        if (explicitCancelMatch != null) {
            clearTaskIntentAndRecoveryState(clearOverlayStep = true)
            _stepMonitorBanner.value = "✕ Cancelled previous task"
            val tail = clean.substring(explicitCancelMatch.range.last + 1)
                .replace(Regex("^\\s*(and|then|,|\\.)\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
            if (tail.length < 4) {
                return
            }
            workingUtterance = tail
        }

        val workingLower = workingUtterance.lowercase()
        val mentionsMessagingOrExistingContact =
            workingLower.contains("whatsapp") ||
                workingLower.contains("call") ||
                workingLower.contains("message") ||
                workingLower.contains("text") ||
                workingLower.contains("chat") ||
                (lastRequestedContactName.isNotBlank() &&
                    workingLower.contains(lastRequestedContactName.lowercase()))

        val unrelatedTaskKeywords = listOf(
            "youtube", "chrome", "google", "maps", "settings", "gmail",
            "calculator", "calendar", "camera", "photos", "clock", "play store",
            "weather", "news", "music", "song", "video", "story", "game",
            "medicine", "medication", "pill", "reminder", "alarm", "sos"
        )
        val isSwitchingToUnrelatedTask = !mentionsMessagingOrExistingContact &&
            unrelatedTaskKeywords.any { kw -> Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(workingLower) }

        if (isSwitchingToUnrelatedTask) {
            if (isTaskAwaitingUserTap && pausedTaskDescription.isBlank() && lastRequestedContactName.isNotBlank()) {
                pausedTaskDescription = "Opening WhatsApp chat with $lastRequestedContactName"
            }
            // Never drag stale WhatsApp contact/message state into an unrelated request!
            lastRequestedContactName = ""
            pendingSubGoal = ""
            pendingMessageText = ""
            isTaskAwaitingUserTap = false
            return
        }

        when {
            workingLower.contains("video call") -> pendingSubGoal = "video_call"
            workingLower.contains("call ") || workingLower.contains("phone ") || workingLower.contains("voice call") ->
                pendingSubGoal = "voice_call"
            workingLower.contains("send ") || workingLower.contains("message") || workingLower.contains("text ") || workingLower.contains("write ") || workingLower.contains("ask ") ->
                pendingSubGoal = "send_message"
        }

        // Extract optional message body if the user stated what to send/ask (e.g. "asking have you taken food")
        val msgPatterns = listOf(
            Regex("(?:saying|asking\\s+him|asking\\s+her|asking\\s+them|asking|that\\s+says|with\\s+message)\\s+[\"']?([^\"'.!]+(?:[?!])?)[\"']?", RegexOption.IGNORE_CASE),
            Regex("(?:send|write|type)\\s+[\"']([^\"']{2,120})[\"']", RegexOption.IGNORE_CASE)
        )
        for (mRegex in msgPatterns) {
            val mMatch = mRegex.find(workingUtterance) ?: continue
            val extractedMsg = mMatch.groupValues.getOrNull(1)?.trim().orEmpty()
            if (extractedMsg.length in 2..120 &&
                !extractedMsg.equals("a message", ignoreCase = true) &&
                !extractedMsg.startsWith("to ", ignoreCase = true)
            ) {
                pendingMessageText = extractedMsg.replaceFirstChar { it.uppercase() }
                pendingSubGoal = "send_message"
                break
            }
        }

        // Match against known visible contact names on screen first
        val visibleContact = getCombinedNodeBounds().keys.firstOrNull { label ->
            label.length in 3..36 &&
                !label.equals("WhatsApp", ignoreCase = true) &&
                !label.equals("Chats", ignoreCase = true) &&
                !label.equals("Calls", ignoreCase = true) &&
                !label.equals("Updates", ignoreCase = true) &&
                !label.equals("Communities", ignoreCase = true) &&
                !label.contains("Search", ignoreCase = true) &&
                workingLower.contains(label.lowercase())
        }
        if (!visibleContact.isNullOrBlank()) {
            lastRequestedContactName = visibleContact
            isTaskAwaitingUserTap = false
            pausedTaskDescription = ""
            return
        }

        // Regex extraction for patterns like "call to Amardeep Sharma", "message to Amardeep Sharma"
        val patterns = listOf(
            Regex("(?:call\\s+to|call|message\\s+to|message|text\\s+to|text|chat\\s+with|talk\\s+to|to)\\s+([A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+)?)"),
            Regex("(?:call\\s+to|call|message\\s+to|message|text\\s+to|text|chat\\s+with|talk\\s+to|to)\\s+([a-zA-Z]{3,18}(?:\\s+[a-zA-Z]{3,18})?)", RegexOption.IGNORE_CASE)
        )
        val stopWords = setOf(
            "whatsapp", "chrome", "youtube", "my", "the", "on", "in", "for", "me",
            "now", "please", "someone", "him", "her", "them", "home", "screen", "app", "our",
            "send", "message", "call", "voice", "video", "asking", "saying"
        )
        for (regex in patterns) {
            val match = regex.find(workingUtterance) ?: continue
            val candidate = match.groupValues.getOrNull(1)?.trim()
                ?.replace(Regex("\\b(on|in|via|using|please|now|and|to|asking|saying|that)\\b.*", RegexOption.IGNORE_CASE), "")
                ?.trim()
                .orEmpty()
            val words = candidate.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isNotEmpty() && words.size <= 3 && words.none { it.lowercase() in stopWords }) {
                lastRequestedContactName = words.joinToString(" ") { w ->
                    w.replaceFirstChar { c -> c.uppercase() }
                }
                isTaskAwaitingUserTap = false
                pausedTaskDescription = ""
                break
            }
        }
    }

    private fun initTts(context: Context) {
        if (tts != null) return
        val engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureNaturalNeuralVoice()
                ttsReady = true
                TaskExecutionHandler.attachTextToSpeechEngine(tts) { ttsReady }
            }
        }
        tts = engine
        TaskExecutionHandler.attachTextToSpeechEngine(engine) { ttsReady }
    }

    private fun configureNaturalNeuralVoice() {
        val engine = tts ?: return
        runCatching {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
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

            engine.setPitch(1.03f)
            engine.setSpeechRate(0.95f)

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    TaskExecutionHandler.onUtteranceStarted(utteranceId)
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
                    TaskExecutionHandler.onUtteranceFinished(utteranceId, succeeded = true)
                    handleLocalUtteranceCompleted(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    TaskExecutionHandler.onUtteranceFinished(utteranceId, succeeded = false)
                    handleLocalUtteranceCompleted(utteranceId)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    TaskExecutionHandler.onUtteranceFinished(utteranceId, succeeded = false)
                    handleLocalUtteranceCompleted(utteranceId)
                }
            })
        }
    }

    private fun handleLocalUtteranceCompleted(utteranceId: String?) {
        val stillQueued = synchronized(ttsQueueLock) {
            if (utteranceId != null) {
                activeTtsUtterances.remove(utteranceId)
            } else {
                activeTtsUtterances.clear()
            }
            activeTtsUtterances.isNotEmpty()
        }
        if (!stillQueued) {
            stopVoiceMeterAnimation()
        }
    }

    fun isLocalTtsSpeaking(): Boolean {
        val hasQueued = synchronized(ttsQueueLock) { activeTtsUtterances.isNotEmpty() }
        return hasQueued || _speakingMessageId.value != null
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
        }
        refreshSpotlightBounds()
    }

    fun registerScreenSnapshotRequester(requester: (suspend () -> OverlayScreenSnapshot?)?) {
        screenSnapshotRequester = requester
    }

    /**
     * Immediately clears stale node bounds and spotlight boxes when switching windows/apps
     * so an old highlight box from a previous screen NEVER stays drawn on the new screen!
     */
    fun onForegroundWindowChanged(newPackageName: String) {
        if (newPackageName.isNotBlank() && newPackageName != _activeScreenPackage.value) {
            _activeScreenPackage.value = newPackageName
            latestNodeBounds = emptyMap()
            lastLockedHighlightPkg = ""
            lastLockedHighlightLabel = ""
            lastLockedHighlightRect = null
            _targetHighlightBounds.value = null
        }
    }

    fun updateLiveScreenNodes(packageName: String, boundsByLabel: Map<String, Rect>) {
        if (packageName.isNotBlank()) {
            if (packageName != _activeScreenPackage.value) {
                onForegroundWindowChanged(packageName)
            }
        }
        latestNodeBounds = boundsByLabel
        refreshSpotlightBounds()
    }

    fun onInAppWindowResumed(packageName: String) {
        if (packageName.isNotBlank() && packageName != _activeScreenPackage.value) {
            onForegroundWindowChanged(packageName)
        }
    }

    fun updateInAppScreenNodes(packageName: String, boundsByLabel: Map<String, Rect>) {
        if (packageName.isNotBlank() && packageName != _activeScreenPackage.value) {
            onForegroundWindowChanged(packageName)
        }
        inAppNodeBounds = boundsByLabel
        refreshSpotlightBounds()
    }

    fun setScreenTaskActive(active: Boolean) {
        _isScreenTaskActive.value = active
        if (!active) {
            _targetHighlightBounds.value = null
            _pendingConfirmationAction.value = null
        }
    }

    /**
     * Returns ONLY the nodes belonging to the current active foreground screen.
     * Never merges stale nodes from KindGuide when in an external app, and never merges
     * external app nodes when inside KindGuide!
     */
    fun getCombinedNodeBounds(): Map<String, Rect> {
        val activePkg = _activeScreenPackage.value
        val ownPkg = appContext?.packageName.orEmpty()
        if (activePkg.isNotBlank() && ownPkg.isNotBlank() && activePkg != ownPkg) {
            return latestNodeBounds
        }
        return if (inAppNodeBounds.isNotEmpty()) inAppNodeBounds else latestNodeBounds
    }

    fun latestNodeBoundsSnapshotLabels(): Set<String> = getCombinedNodeBounds().keys.toSet()

    fun setOverlayVisible(visible: Boolean) {
        _isOverlayVisible.value = visible && _isAccessibilityConnected.value
        if (!visible) {
            _pendingConfirmationAction.value = null
            _targetHighlightBounds.value = null
        } else {
            refreshSpotlightBounds()
        }
    }

    fun setHighlightEnabled(enabled: Boolean) {
        _isHighlightEnabled.value = enabled
        if (!enabled) {
            _targetHighlightBounds.value = null
        } else {
            refreshSpotlightBounds()
        }
    }

    fun toggleHighlightEnabled(): Boolean {
        val next = !_isHighlightEnabled.value
        setHighlightEnabled(next)
        return next
    }

    fun toggleVoiceReadAloud() {
        val next = !_voiceReadAloudEnabled.value
        _voiceReadAloudEnabled.value = next
        if (!next) {
            stopTtsOnly()
        }
    }

    fun toggleSpeakMessage(messageId: String, rawMarkdownText: String) {
        if (_speakingMessageId.value == messageId) {
            stopTtsOnly()
            return
        }
        val cleanText = cleanMarkdownForSpeech(rawMarkdownText)
        if (GeminiLiveManager.state.value.isActive) {
            GeminiLiveManager.sendTextNudge("Please read this message aloud warmly: $cleanText")
            return
        }
        speakText(rawMarkdownText, slow = false, messageId = messageId)
    }

    fun speakText(text: String, slow: Boolean = false, messageId: String = "manual_tts") {
        speakChunkInternal(
            text = text,
            slow = slow,
            messageId = messageId,
            queueAdd = false,
            allowLiveRelay = true
        )
    }

    /**
     * Speaks directly through local Android TTS even if Gemini Live is in an active/connecting state
     * (used for text-only Live turns or Live connection/runtime error announcements).
     */
    fun speakLocalDirectly(text: String, slow: Boolean = false, messageId: String = "live_fallback_tts") {
        speakChunkInternal(
            text = text,
            slow = slow,
            messageId = messageId,
            queueAdd = false,
            allowLiveRelay = false,
            bypassLiveCheck = true
        )
    }

    /**
     * Speaks a completed sentence or tail chunk from a streaming AI response so audio starts
     * immediately as tokens arrive and continues seamlessly across queued sentences.
     */
    fun speakStreamedChunk(
        text: String,
        messageId: String,
        isFirstChunk: Boolean,
        slow: Boolean = false
    ) {
        if (!_voiceReadAloudEnabled.value) return
        speakChunkInternal(
            text = text,
            slow = slow,
            messageId = messageId,
            queueAdd = !isFirstChunk,
            allowLiveRelay = false
        )
    }

    private fun speakChunkInternal(
        text: String,
        slow: Boolean,
        messageId: String,
        queueAdd: Boolean,
        allowLiveRelay: Boolean,
        bypassLiveCheck: Boolean = false
    ) {
        if (text.isBlank()) return
        val cleanSpeech = cleanMarkdownForSpeech(text)
        if (cleanSpeech.isBlank()) return

        if (!bypassLiveCheck && GeminiLiveManager.state.value.isActive) {
            if (allowLiveRelay) {
                GeminiLiveManager.sendTextNudge(
                    if (slow) "Please say this slowly and clearly: $cleanSpeech" else "Please say this warmly: $cleanSpeech"
                )
            }
            return
        }

        val sink = customSpeechSink
        if (sink != null) {
            _speakingMessageId.value = messageId
            sink.invoke(messageId, cleanSpeech, queueAdd)
            return
        }

        if (!ttsReady) return

        val utteranceId = "${messageId}_${UUID.randomUUID().toString().take(8)}"
        synchronized(ttsQueueLock) {
            if (!queueAdd) {
                activeTtsUtterances.clear()
            }
            activeTtsUtterances.add(utteranceId)
        }
        _speakingMessageId.value = messageId
        startVoiceMeterAnimation(messageId)

        tts?.setPitch(1.03f)
        tts?.setSpeechRate(if (slow) 0.82f else 0.95f)
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        val queueMode = if (queueAdd) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH
        tts?.speak(cleanSpeech, queueMode, params, utteranceId)
    }

    internal fun extractReadySpeechSentences(
        fullText: String,
        spokenUpToIndex: Int,
        flushRemaining: Boolean
    ): Pair<List<String>, Int> {
        if (spokenUpToIndex >= fullText.length) return emptyList<String>() to spokenUpToIndex
        val remaining = fullText.substring(spokenUpToIndex)
        if (remaining.isBlank()) return emptyList<String>() to fullText.length

        if (flushRemaining) {
            val cleaned = cleanMarkdownForSpeech(remaining)
            return (if (cleaned.isNotBlank()) listOf(cleaned) else emptyList()) to fullText.length
        }

        val boundaryRegex = Regex("[.!?](?:\\s+|$)|\\n+")
        val sentences = mutableListOf<String>()
        var localStart = 0
        for (match in boundaryRegex.findAll(remaining)) {
            val endPos = match.range.last + 1
            val candidateRaw = remaining.substring(localStart, endPos)
            val rawTrimmed = candidateRaw.trim()
            if (rawTrimmed.matches(Regex("^\\d+\\.$"))) {
                continue
            }
            val candidateClean = cleanMarkdownForSpeech(candidateRaw)
            if (candidateClean.length >= 4 && candidateClean.any { it.isLetter() }) {
                sentences.add(candidateClean)
                localStart = endPos
            }
        }
        return sentences to (spokenUpToIndex + localStart)
    }

    internal fun cleanMarkdownForSpeech(markdown: String): String {
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
            if (GeminiLiveManager.state.value.isActive) {
                GeminiLiveManager.sendTextNudge(
                    "Please repeat the current step slowly and clearly for me: $textToSpeak"
                )
            } else {
                speakText(textToSpeak, slow = true, messageId = "step_repeat")
            }
        }
    }

    fun clearGuideSession() {
        TaskExecutionHandler.reset()
        InterruptController.reset()
        monitorActionJob?.cancel()
        monitorActionJob = null
        clearTaskIntentAndRecoveryState(clearOverlayStep = true)
        _stepMonitorBanner.value = null
        _isOverlayVisible.value = false
        lastLockedHighlightPkg = ""
        lastLockedHighlightLabel = ""
        lastLockedHighlightRect = null
        stopTtsOnly()
    }

    fun stopTtsOnly() {
        TaskExecutionHandler.stopTtsImmediately()
        synchronized(ttsQueueLock) {
            activeTtsUtterances.clear()
        }
        tts?.stop()
        stopVoiceMeterAnimation()
    }

    /**
     * Sets a live step ONLY if its target button is verified on the current screen (for HIGHLIGHT_BUTTON)
     * or if it is a SWIPE_HINT / SEARCH_HINT / CONFIRM_SEND step.
     * Never shows irrelevant instructions for buttons that do not exist on the current screen!
     */
    fun setLiveGuideStep(step: AiGuideStep): Boolean {
        _isScreenTaskActive.value = true
        val normalizedHint = step.targetButtonHint.trim()
        val combined = getCombinedNodeBounds()
        val verifiedRect = if (normalizedHint.isNotBlank()) {
            findMatchingNodeBoundsInMap(normalizedHint, combined)
        } else {
            null
        }

        // If Gemini asked to HIGHLIGHT_BUTTON for a button that is NOT on the current screen,
        // reject drawing a bogus highlight or irrelevant instruction!
        if (step.guidanceMode == "HIGHLIGHT_BUTTON" && normalizedHint.isNotBlank() && verifiedRect == null) {
            _targetHighlightBounds.value = null
            return false
        }

        val effectiveMode = when {
            step.guidanceMode == "SWIPE_HINT" || step.guidanceMode == "SEARCH_HINT" -> step.guidanceMode
            step.requiresConfirmation || step.guidanceMode == "CONFIRM_SEND" -> "CONFIRM_SEND"
            normalizedHint.isBlank() -> "SWIPE_HINT"
            else -> "HIGHLIGHT_BUTTON"
        }

        val syncedStep = step.copy(guidanceMode = effectiveMode)
        _activeStep.value = syncedStep
        _lastReply.value = syncedStep.instruction
        _stepMonitorBanner.value = when {
            syncedStep.requiresConfirmation -> "✋ Confirm: ${syncedStep.instruction}"
            syncedStep.guidanceMode == "SWIPE_HINT" -> "↔️ ${syncedStep.instruction}"
            syncedStep.guidanceMode == "SEARCH_HINT" -> "🔍 ${syncedStep.instruction}"
            syncedStep.isCorrection -> "⚠️ ${syncedStep.instruction}"
            else -> "Step ${syncedStep.stepNumber}: ${syncedStep.instruction}"
        }
        if (_isAccessibilityConnected.value) {
            _isOverlayVisible.value = true
        }
        val pkg = _activeScreenPackage.value.ifBlank { appContext?.packageName.orEmpty() }
        val appTitle = ScreenGuideAccessibilityService.resolveHumanReadableAppName(appContext, pkg)
        if (!syncedStep.requiresConfirmation) {
            recordCompletedSessionStep(
                actionType = "GUIDE_STEP",
                title = syncedStep.stepTitle.ifBlank { "Guided Step ${syncedStep.stepNumber}" },
                detail = syncedStep.instruction,
                appName = appTitle,
                statusIcon = if (syncedStep.isCorrection) "⚠️" else "👆",
                succeeded = true
            )
        }
        refreshSpotlightBounds()
        return true
    }

    fun completeLiveGuidedTask(congratsMessage: String) {
        val pkg = _activeScreenPackage.value.ifBlank { appContext?.packageName.orEmpty() }
        val appTitle = ScreenGuideAccessibilityService.resolveHumanReadableAppName(appContext, pkg)
        recordCompletedSessionStep(
            actionType = "TASK_COMPLETE",
            title = congratsMessage.ifBlank { "Completed guided task" },
            detail = "All steps verified and finished",
            appName = appTitle,
            statusIcon = "✅",
            succeeded = true
        )
        clearTaskIntentAndRecoveryState(clearOverlayStep = true)
        _stepMonitorBanner.value = "✅ $congratsMessage"
        scope.launch {
            delay(3800L)
            if (_activeStep.value == null && _pendingConfirmationAction.value == null) {
                _stepMonitorBanner.value = null
            }
        }
    }

    /**
     * Synchronously executes a single or multi-step assistive screen action via [TaskExecutionHandler],
     * calling the Text-to-Speech (TTS) engine after each completed step before moving to the next operation.
     */
    suspend fun executeAndVerifyScreenActionSuspend(
        action: AssistiveScreenAction
    ): VerifiedActionOutcome {
        return TaskExecutionHandler.executeAndVerifyScreenAction(action, taskExecutionHost)
    }

    private fun isPackageMatchingAppName(packageName: String, appName: String): Boolean {
        if (packageName.isBlank() || appName.isBlank()) return false
        val pkgLow = packageName.lowercase()
        val appLow = appName.trim().lowercase()
        return when {
            appLow.contains("whatsapp") -> pkgLow.contains("whatsapp")
            appLow.contains("chrome") -> pkgLow.contains("chrome")
            appLow.contains("youtube") -> pkgLow.contains("youtube")
            appLow.contains("map") -> pkgLow.contains("maps")
            appLow.contains("setting") -> pkgLow.contains("settings")
            appLow.contains("gmail") -> pkgLow.contains("gm")
            appLow.contains("phone") -> pkgLow.contains("dialer")
            appLow.contains("message") -> pkgLow.contains("messaging") || pkgLow.contains("mms")
            else -> pkgLow.contains(appLow.replace(" ", ""))
        }
    }

    /**
     * Strictly checks whether an individual chat conversation screen is currently open
     * (has a bottom message input or chat controls AND is NOT on the main WhatsApp Chats list
     * or Select Contact screen).
     */
    fun isChatComposerVisibleOnScreen(): Boolean {
        val keys = getCombinedNodeBounds().keys
        val isOnChatsListOrSelectContact = keys.any {
            it.equals("Select contact", ignoreCase = true) ||
                it.contains("Contacts on WhatsApp", ignoreCase = true) ||
                it.equals("New group", ignoreCase = true) ||
                it.equals("New chat", ignoreCase = true) ||
                it.equals("Updates", ignoreCase = true) ||
                it.equals("Communities", ignoreCase = true) ||
                it.contains("Ask Meta AI or Search", ignoreCase = true)
        }
        if (isOnChatsListOrSelectContact) return false

        val hasMessageInput = keys.any {
            it.equals("Message", ignoreCase = true) ||
                it.contains("Type a message", ignoreCase = true) ||
                it.contains("Text message", ignoreCase = true) ||
                it.contains("Write a message", ignoreCase = true)
        }
        val hasChatControls = keys.any {
            it.equals("Send", ignoreCase = true) ||
                it.contains("Voice message", ignoreCase = true) ||
                it.contains("Attach", ignoreCase = true) ||
                it.contains("Emoji", ignoreCase = true)
        }
        return hasMessageInput || hasChatControls
    }

    /**
     * When Gemini Live speaks a commitment aloud (e.g., "I'm opening your WhatsApp now",
     * "I'm sending a Quick SOS alert now") without emitting a tool call for an UNCONSUMED user turn,
     * executes the promised action safely without looping over failed, paused, or already-executed turns.
     */
    fun fulfillSpokenCommitmentIfNoToolCalled(userText: String, aiText: String) {
        val aiLower = aiText.lowercase()
        val userLower = userText.lowercase()
        if (userText.isBlank() && aiText.isBlank()) return

        // Never parse AI's own narration/apologies into task intent! Only parse real user utterances.
        if (userText.isNotBlank()) {
            noteUserTaskIntent(userText)
        }

        // 1. Check if the user verbally confirmed or cancelled a pending Send/Call action
        if (_pendingConfirmationAction.value != null) {
            if (userLower.matches(Regex(".*\\b(yes|yeah|yep|send|send it|call|start call|start the call|go ahead|sure|okay|ok|do it)\\b.*"))) {
                confirmPendingAction()
                return
            } else if (userLower.matches(Regex(".*\\b(no|cancel|don't|do not|stop)\\b.*"))) {
                cancelPendingAction()
                return
            }
        }

        val now = System.currentTimeMillis()
        // Never double-trigger fallback right after a real tool or action just executed!
        if (now - lastExecutedActionAtMs < 3500L) {
            return
        }

        // If AI is reporting failure, asking a question, asking the user to tap, or recounting past action,
        // NEVER auto-retrigger OPEN_APP or TAP_ELEMENT!
        val aiIsReportingFailureQuestionOrDone = aiLower.contains("?") ||
            aiLower.contains("couldn't") ||
            aiLower.contains("could not") ||
            aiLower.contains("cannot") ||
            aiLower.contains("can't") ||
            aiLower.contains("not found") ||
            aiLower.contains("please tap") ||
            aiLower.contains("go ahead and tap") ||
            aiLower.contains("would you like") ||
            aiLower.contains("i've opened") ||
            aiLower.contains("i have opened") ||
            aiLower.contains("i opened") ||
            aiLower.contains("tried opening")

        // 2. Check for Quick SOS commitment ("Send Quick SOS" / "sending a Quick SOS alert now")
        if (userLower.contains("quick sos") ||
            userLower.contains("send sos") ||
            aiLower.contains("sending a quick sos") ||
            aiLower.contains("triggering quick sos") ||
            aiLower.contains("sending an sos")
        ) {
            val sig = "QUICK_SOS"
            if (sig != lastAutoFulfilledSignature || now - lastAutoFulfilledAtMs > 5000L) {
                lastAutoFulfilledSignature = sig
                lastAutoFulfilledAtMs = now
                triggerQuickSosNow("Triggered via KindGuide Live voice command")
            }
            return
        }

        // If a task is already paused awaiting user tap or recovery confirmation, do NOT auto-execute app launches
        if (isTaskAwaitingUserTap || aiIsReportingFailureQuestionOrDone) {
            return
        }

        // 3. Check for Home Screen or returning to KindGuide
        if (userLower.contains("go to home screen") ||
            aiLower.contains("going to your home screen") ||
            aiLower.contains("taking you to the home screen")
        ) {
            val sig = "SYSTEM_HOME"
            if (sig != lastAutoFulfilledSignature || now - lastAutoFulfilledAtMs > 3000L) {
                lastAutoFulfilledSignature = sig
                lastAutoFulfilledAtMs = now
                InterruptController.launchInterruptibleTask(scope, "fallback:HOME") { token ->
                    val outcome = executeAndVerifyScreenActionSuspend(
                        AssistiveScreenAction(
                            actionType = "SYSTEM_NAV",
                            systemNavAction = "HOME",
                            spokenExplanation = "Going to your home screen"
                        )
                    )
                    if (!outcome.wasAborted && !InterruptController.isAborted(token) && GeminiLiveManager.state.value.isActive) {
                        GeminiLiveManager.notifyUserScreenAction(
                            "[ACTION COMPLETED] Pressed Home. Verified screen: ${outcome.visibleElementsSummary}"
                        )
                    }
                }
            }
            return
        }

        if ((userLower.contains("open our app") ||
                userLower.contains("open kindguide") ||
                userLower.contains("back to our app") ||
                aiLower.contains("opening kindguide")) &&
            !_isKindGuideActivityResumed.value
        ) {
            val ctx = appContext ?: return
            val sig = "OPEN_KINDGUIDE"
            if (sig != lastAutoFulfilledSignature || now - lastAutoFulfilledAtMs > 3000L) {
                lastAutoFulfilledSignature = sig
                lastAutoFulfilledAtMs = now
                val launchIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                if (launchIntent != null) {
                    runCatching { ctx.startActivity(launchIntent) }
                }
            }
            return
        }

        // 4. Check for Web Search in Chrome / Google ("Search on Chrome for...", "I'm searching Chrome for...")
        val webSearchRegex = Regex(
            "(?:search\\s+(?:on\\s+|in\\s+)?(?:chrome|google|the\\s+web|browser)\\s+for|searching\\s+(?:on\\s+|in\\s+)?(?:chrome|google|the\\s+web)\\s+for|google\\s+search\\s+for)\\s+[\"']?([^\"'.!?]{2,80})[\"']?",
            RegexOption.IGNORE_CASE
        )
        val searchMatch = webSearchRegex.find(userText) ?: webSearchRegex.find(aiText)
        val searchQuery = searchMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (searchQuery.isNotBlank()) {
            val sig = "WEB_SEARCH:${searchQuery.lowercase()}"
            if (sig != lastAutoFulfilledSignature || now - lastAutoFulfilledAtMs > 4000L) {
                lastAutoFulfilledSignature = sig
                lastAutoFulfilledAtMs = now
                InterruptController.launchInterruptibleTask(scope, "fallback:WEB_SEARCH:$searchQuery") { token ->
                    val outcome = executeAndVerifyScreenActionSuspend(
                        AssistiveScreenAction(
                            actionType = "WEB_SEARCH",
                            targetLabel = searchQuery,
                            textToType = searchQuery,
                            expectedAppName = "Chrome",
                            spokenExplanation = "Searching Chrome for '$searchQuery'"
                        )
                    )
                    if (!outcome.wasAborted && !InterruptController.isAborted(token) && GeminiLiveManager.state.value.isActive) {
                        GeminiLiveManager.notifyUserScreenAction(
                            "[STEP EXECUTED] KindGuide searched Chrome for '$searchQuery' (${outcome.actionSummary}). Current screen: ${outcome.visibleElementsSummary}."
                        )
                    }
                }
            }
            return
        }

        // 5. Only launch an external app from explicit unfulfilled present-tense commitment in this fresh turn
        val appCandidates = listOf(
            "WhatsApp", "Chrome", "YouTube", "Google Maps", "Maps", "Settings", "Gmail",
            "Phone", "Contacts", "Messages", "Camera", "Photos", "Gallery", "Play Store",
            "Calculator", "Calendar", "Clock", "Instagram", "Facebook", "Google Pay", "PhonePe", "Paytm"
        )
        var promisedApp = appCandidates.firstOrNull { app ->
            val appLow = app.lowercase()
            (aiLower.contains("i'm opening $appLow") ||
                aiLower.contains("i am opening $appLow") ||
                aiLower.contains("opening $appLow for you") ||
                aiLower.contains("opening your $appLow") ||
                userLower.contains("open $appLow") ||
                userLower.contains("open my $appLow"))
        }
        if (promisedApp == null && userText.isNotBlank()) {
            val genericAppRegex = Regex(
                "\\bopen\\s+(?:my\\s+|the\\s+)?([a-zA-Z0-9]{3,16})(?:\\s+app|\\s+for\\s+you|\\s+now|[.,!?]|$)",
                RegexOption.IGNORE_CASE
            )
            val candidateApp = genericAppRegex.find(userText)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val nonApps = setOf("chat", "message", "conversation", "screen", "kindguide", "our", "this", "that", "it", "up")
            if (candidateApp.isNotBlank() && candidateApp.lowercase() !in nonApps) {
                promisedApp = candidateApp
            }
        }
        val activePkg = _activeScreenPackage.value
        val alreadyInPromisedApp = promisedApp != null && isPackageMatchingAppName(activePkg, promisedApp)

        if (promisedApp != null && !alreadyInPromisedApp) {
            val appToLaunch = promisedApp
            val sig = "OPEN_APP:${appToLaunch.lowercase()}"
            if (sig != lastAutoFulfilledSignature || now - lastAutoFulfilledAtMs > 5000L) {
                lastAutoFulfilledSignature = sig
                lastAutoFulfilledAtMs = now
                InterruptController.launchInterruptibleTask(scope, "fallback:OPEN_APP:$appToLaunch") { token ->
                    val outcome = executeAndVerifyScreenActionSuspend(
                        AssistiveScreenAction(
                            actionType = "OPEN_APP",
                            targetLabel = appToLaunch,
                            expectedAppName = appToLaunch,
                            spokenExplanation = "Opening $appToLaunch"
                        )
                    )
                    if (!outcome.wasAborted && !InterruptController.isAborted(token) && GeminiLiveManager.state.value.isActive) {
                        GeminiLiveManager.notifyUserScreenAction(
                            "[STEP EXECUTED] ${outcome.actionSummary}. Current screen: ${outcome.visibleElementsSummary}. Speak a brief update!"
                        )
                    }
                }
            }
            return
        }

        // 6. If a chat conversation is open and the AI narrated typing a message ("I've typed '...'")
        //    without calling `perform_screen_action(TYPE_TEXT)`, type the message and show Send confirmation!
        if (isChatComposerVisibleOnScreen() && _pendingConfirmationAction.value == null) {
            val quotedAiMsg = Regex("['\"]([^'\"]{2,120})['\"]").find(aiText)?.groupValues?.getOrNull(1)?.trim()
            val aiNarratedTyping = aiLower.contains("i've typed") ||
                aiLower.contains("i have typed") ||
                aiLower.contains("typing ") ||
                aiLower.contains("typed your message") ||
                aiLower.contains("ready to send")
            val messageToEnter = when {
                aiNarratedTyping && !quotedAiMsg.isNullOrBlank() -> quotedAiMsg
                pendingMessageText.isNotBlank() && aiNarratedTyping -> pendingMessageText
                else -> null
            }
            if (!messageToEnter.isNullOrBlank()) {
                val sig = "TYPE_MSG:${messageToEnter.lowercase()}"
                if (sig != lastAutoFulfilledSignature || now - lastAutoFulfilledAtMs > 3500L) {
                    lastAutoFulfilledSignature = sig
                    lastAutoFulfilledAtMs = now
                    pendingMessageText = ""
                    InterruptController.launchInterruptibleTask(scope, "fallback:TYPE_MSG") {
                        executeAndVerifyScreenActionSuspend(
                            AssistiveScreenAction(
                                actionType = "TYPE_TEXT",
                                targetLabel = "Message",
                                textToType = messageToEnter,
                                submitAfterType = false,
                                expectedAppName = "WhatsApp",
                                spokenExplanation = "Typing '$messageToEnter'"
                            )
                        )
                    }
                }
                return
            }
        }
    }

    /**
     * Triggered when the senior taps "✅ Send Now" on the cross-app floating confirmation pill.
     */
    fun confirmPendingAction() {
        val pending = _pendingConfirmationAction.value
        val targetHint = pending?.targetLabel
            ?: _activeStep.value?.targetButtonHint
            ?: "Send"
        _pendingConfirmationAction.value = null
        _activeStep.value = null
        _targetHighlightBounds.value = null

        InterruptController.launchInterruptibleTask(scope, "confirm:$targetHint") { token ->
            _stepMonitorBanner.value = "🟢 Sending now..."
            val outcome = executeAndVerifyScreenActionSuspend(
                AssistiveScreenAction(
                    actionType = "TAP_ELEMENT",
                    targetLabel = targetHint,
                    isFinalSendOrCommit = false,
                    userConfirmed = true
                )
            )
            if (outcome.wasAborted || InterruptController.isAborted(token)) return@launchInterruptibleTask
            completeLiveGuidedTask("Done! Tapped $targetHint.")
            if (GeminiLiveManager.state.value.isActive) {
                GeminiLiveManager.notifyUserScreenAction(
                    actionMessage = "[USER CONFIRMED & SENT] The senior tapped '✅ $targetHint Now' and KindGuide clicked '$targetHint' (success=${outcome.success}). Screen now shows: ${outcome.visibleElementsSummary}. Confirm warmly in 1 short sentence that it has been sent!"
                )
            }
        }
    }

    /**
     * Triggered when the senior taps "✕ Cancel" on the cross-app floating confirmation pill.
     */
    fun cancelPendingAction() {
        val cancelledTarget = _pendingConfirmationAction.value?.targetLabel
            ?: _activeStep.value?.targetButtonHint
            ?: "action"
        InterruptController.abortActiveTask("User cancelled confirmation via overlay")
        clearTaskIntentAndRecoveryState(clearOverlayStep = true)
        _stepMonitorBanner.value = "✕ Cancelled — tell KindGuide what you'd like to change"

        if (GeminiLiveManager.state.value.isActive) {
            GeminiLiveManager.notifyUserScreenAction(
                actionMessage = "[USER CANCELLED CONFIRMATION] The senior tapped '✕ Cancel' and decided NOT to tap '$cancelledTarget' yet. Ask warmly what they would like to change or do instead."
            )
        }
    }

    fun performActiveStepActionOnScreen() {
        if (_pendingConfirmationAction.value != null || _activeStep.value?.requiresConfirmation == true) {
            confirmPendingAction()
            return
        }
        val step = _activeStep.value ?: return
        val hint = step.targetButtonHint.trim()
        if (hint.isBlank()) return

        InterruptController.launchInterruptibleTask(scope, "assistTap:$hint") { token ->
            val outcome = executeAndVerifyScreenActionSuspend(
                AssistiveScreenAction(
                    actionType = "TAP_ELEMENT",
                    targetLabel = hint,
                    isFinalSendOrCommit = false,
                    userConfirmed = true
                )
            )
            if (outcome.wasAborted || InterruptController.isAborted(token)) return@launchInterruptibleTask
            if (GeminiLiveManager.state.value.isActive) {
                GeminiLiveManager.notifyUserScreenAction(
                    actionMessage = "[ASSISTIVE CLICK COMPLETED] KindGuide clicked '$hint' (success=${outcome.success}). Updated screen: ${outcome.visibleElementsSummary}. Speak a brief update and continue to the next step."
                )
            }
        }
    }

    /**
     * Real-time monitor invoked whenever the senior clicks a named UI element while a guide step is active.
     */
    fun onUserClickedElement(
        packageName: String,
        clickedLabel: String,
        clickedBounds: Rect?
    ) {
        val step = _activeStep.value ?: return
        val normalizedClicked = clickedLabel.trim()
        if (normalizedClicked.isBlank()) return
        if (normalizedClicked.lowercase() in ignoredControlLabels) return
        if (normalizedClicked.startsWith("Step ", ignoreCase = true)) return

        val now = System.currentTimeMillis()
        if (now - lastMonitoredClickAtMs < 750L) return
        lastMonitoredClickAtMs = now

        val targetHint = step.targetButtonHint.trim()
        val currentSpotlight = _targetHighlightBounds.value

        val labelMatchesTarget = targetHint.isNotBlank() && isFuzzyLabelMatch(normalizedClicked, targetHint)
        val boundsMatchesTarget = if (clickedBounds != null && currentSpotlight != null) {
            val expanded = Rect(
                currentSpotlight.left - 44,
                currentSpotlight.top - 44,
                currentSpotlight.right + 44,
                currentSpotlight.bottom + 44
            )
            Rect.intersects(expanded, clickedBounds) ||
                expanded.contains(clickedBounds.centerX(), clickedBounds.centerY())
        } else {
            false
        }
        val isBackRecoveryTap = step.isCorrection &&
            (isFuzzyLabelMatch(normalizedClicked, "Back") ||
                isFuzzyLabelMatch(normalizedClicked, "Navigate up"))
        val isSearchResultContactTap = isTaskAwaitingUserTap &&
            step.guidanceMode == "SEARCH_HINT" &&
            !normalizedClicked.contains("Search", ignoreCase = true) &&
            !normalizedClicked.equals("Back", ignoreCase = true) &&
            !normalizedClicked.equals("Navigate up", ignoreCase = true) &&
            !normalizedClicked.equals("Clear", ignoreCase = true)

        val isCorrectAction = labelMatchesTarget || boundsMatchesTarget || isBackRecoveryTap || isSearchResultContactTap

        monitorActionJob?.cancel()
        if (isCorrectAction) {
            isTaskAwaitingUserTap = false
            pausedTaskDescription = ""
            val tappedDisplay = normalizedClicked.ifBlank { targetHint.ifBlank { "button" } }
            val appTitle = ScreenGuideAccessibilityService.resolveHumanReadableAppName(
                appContext,
                packageName.ifBlank { _activeScreenPackage.value }
            )
            recordCompletedSessionStep(
                actionType = "USER_TAP",
                title = "Tapped '$tappedDisplay'",
                detail = "Completed Step ${step.stepNumber}: ${step.instruction}",
                appName = appTitle,
                statusIcon = "✅",
                succeeded = true
            )
            _targetHighlightBounds.value = null
            _pendingConfirmationAction.value = null
            _stepMonitorBanner.value = "✅ Tapped '$tappedDisplay' — checking next step..."

            monitorActionJob = InterruptController.launchInterruptibleTask(scope, "monitor:$tappedDisplay") { token ->
                delay(220L)
                if (InterruptController.isAborted(token)) return@launchInterruptibleTask
                val liveSnap = if (_isAccessibilityConnected.value) {
                    runCatching { screenSnapshotRequester?.invoke() }.getOrNull()
                } else {
                    null
                }
                if (InterruptController.isAborted(token)) return@launchInterruptibleTask
                val frame = liveSnap?.screenshot ?: inAppScreenshotProvider?.invoke()
                if (liveSnap != null) {
                    latestNodeBounds = liveSnap.nodeBoundsByLabel
                }
                val updatedSummary = getCurrentVisibleNodeSummary()

                if (!InterruptController.isAborted(token) && GeminiLiveManager.state.value.isActive) {
                    val nextNum = if (step.isCorrection) step.stepNumber else step.stepNumber + 1
                    val prompt = "[LIVE MONITOR - STEP COMPLETED] The senior tapped '$tappedDisplay' for Step ${step.stepNumber}. Current screen: $updatedSummary. Speak a brief update to the senior and proceed to Step $nextNum (or call `complete_guided_task` if finished)."
                    GeminiLiveManager.notifyUserScreenAction(prompt, frame)
                }
            }
        } else if (targetHint.isNotBlank() && currentSpotlight != null) {
            monitorActionJob = InterruptController.launchInterruptibleTask(scope, "monitorWrongTap:$normalizedClicked") { token ->
                delay(200L)
                if (InterruptController.isAborted(token)) return@launchInterruptibleTask
                val liveSnap = if (_isAccessibilityConnected.value) {
                    runCatching { screenSnapshotRequester?.invoke() }.getOrNull()
                } else {
                    null
                }
                if (InterruptController.isAborted(token)) return@launchInterruptibleTask
                val frame = liveSnap?.screenshot ?: inAppScreenshotProvider?.invoke()
                if (liveSnap != null) {
                    latestNodeBounds = liveSnap.nodeBoundsByLabel
                }
                refreshSpotlightBounds()
                val updatedSummary = getCurrentVisibleNodeSummary()

                if (!InterruptController.isAborted(token) && GeminiLiveManager.state.value.isActive) {
                    val wrongPrompt = "[LIVE MONITOR - USER TAPPED DIFFERENT ITEM] The senior was guided to tap '$targetHint', but tapped '$normalizedClicked' instead. Current screen: $updatedSummary. Gently guide them from the current screen."
                    GeminiLiveManager.notifyUserScreenAction(wrongPrompt, frame)
                }
            }
        }
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
        val activeThread = _activeConversationId.value
        scope.launch {
            repository?.addConversationMessage(
                circleId = circle.id,
                memberUids = circle.memberUids,
                role = role,
                text = clean,
                guideStepTitle = _activeStep.value?.stepTitle.orEmpty(),
                stepNumber = (_activeStep.value?.stepNumber ?: 0).toLong(),
                totalSteps = (_activeStep.value?.totalSteps ?: 0).toLong(),
                isStuckFlagged = false,
                threadId = activeThread
            )
        }
    }

    fun buildLiveSystemContext(): String {
        val circleSummary = buildCircleSummary(currentCircle)
        val recentTurns = currentHistory.takeLast(8).joinToString("\n") { msg ->
            "${if (msg.role == "model") "KindGuide" else "Senior"}: ${msg.text.take(260)}"
        }
        val visibleLabels = getCombinedNodeBounds().keys.take(35).joinToString(" | ")
        return buildString {
            appendLine(circleSummary)
            appendLine()
            appendLine(
                """
                KINDGUIDE APP SCREENS & BUTTON LABELS FOR STEP-BY-STEP GUIDANCE:
                - Home Dashboard: "Today's Tasks", "All Tasks", "Done", "Daily Affirmation", "AI Assistant", "Quick SOS", "Family View".
                - Bottom Navigation Tabs: "Home", "Tasks", "Family", "Safety", "Joy".
                - Tasks Tab: "Add Task", "Add Event", task checkboxes.
                - Family Tab: "Share Photo", "Record Voice Note", family photo cards.
                - Safety Tab: "Took It" (medication button), "Take Me Home", "Check Scam", "Quick SOS".
                - Joy Tab: "Listen to Story", "Memory Match" brain game.
                """.trimIndent()
            )
            if (recentTurns.isNotBlank()) {
                appendLine()
                appendLine("RECENT CONVERSATION MEMORY:")
                appendLine(recentTurns)
            }
            if (visibleLabels.isNotBlank()) {
                appendLine()
                appendLine("CURRENT VISIBLE SCREEN BUTTONS (${_activeScreenPackage.value}): $visibleLabels")
            }
        }
    }

    fun getCurrentVisibleNodeSummary(): String {
        val pkg = _activeScreenPackage.value.ifBlank { appContext?.packageName.orEmpty() }
        val appTitle = ScreenGuideAccessibilityService.resolveHumanReadableAppName(appContext, pkg)
        val labels = getCombinedNodeBounds().keys.take(32)
        val chatOpenNote = if (isChatComposerVisibleOnScreen()) " (Active Chat Message Box Open)" else ""
        return if (labels.isEmpty()) {
            "Verified Active Foreground App: $appTitle$chatOpenNote"
        } else {
            "Verified Active Foreground App: $appTitle$chatOpenNote | Visible Clickable UI Elements: ${labels.joinToString(", ")}"
        }
    }

    fun dismissCrudBanner() {
        _crudFeedbackBanner.value = null
    }

    fun dismissStepMonitorBanner() {
        _stepMonitorBanner.value = null
    }

    fun clearError() {
        _errorText.value = null
    }

    /**
     * Starts a fresh conversation thread AND resets Gemini Live's transcript history
     * so opening KindGuide Live after tapping "New Chat" starts completely fresh!
     */
    fun startNewConversation(): String {
        TaskExecutionHandler.reset()
        InterruptController.reset()
        monitorActionJob?.cancel()
        monitorActionJob = null
        val freshId = UUID.randomUUID().toString()
        _activeConversationId.value = freshId
        clearTaskIntentAndRecoveryState(clearOverlayStep = true)
        _stepMonitorBanner.value = null
        _lastReply.value = ""
        _streamingReply.value = ""
        _errorText.value = null
        _isTaskHistoryDrawerOpen.value = false
        _sessionTaskHistory.value = emptyList()
        persistSessionHistory(freshId, emptyList())
        lastLockedHighlightPkg = ""
        lastLockedHighlightLabel = ""
        lastLockedHighlightRect = null
        currentHistory = filterMessagesForThread(allConversations, freshId)
        GeminiLiveManager.clearTranscriptForNewChat()
        return freshId
    }

    fun selectConversation(threadId: String) {
        val resolvedId = threadId.ifBlank { LEGACY_THREAD_ID }
        _activeConversationId.value = resolvedId
        _activeStep.value = null
        _stepMonitorBanner.value = null
        _pendingConfirmationAction.value = null
        _lastReply.value = ""
        _streamingReply.value = ""
        _errorText.value = null
        _targetHighlightBounds.value = null
        _sessionTaskHistory.value = loadPersistedHistoryForSession(resolvedId)
        currentHistory = filterMessagesForThread(allConversations, resolvedId)
    }

    fun filterMessagesForThread(
        messages: List<ConversationMessage>,
        threadId: String
    ): List<ConversationMessage> {
        return if (threadId == LEGACY_THREAD_ID) {
            messages.filter { it.threadId.isBlank() || it.threadId == LEGACY_THREAD_ID }
        } else {
            messages.filter { it.threadId == threadId }
        }
    }

    fun syncBundle(
        circle: CareCircle,
        tasks: List<CareTask>,
        events: List<CalendarEvent>,
        medications: List<MedicationItem>,
        allConversations: List<ConversationMessage>
    ) {
        currentCircle = circle
        currentTasks = tasks
        currentEvents = events
        currentMedications = medications
        this.allConversations = allConversations
        currentHistory = filterMessagesForThread(allConversations, _activeConversationId.value)
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

        stopTtsOnly()

        scope.launch {
            _isBusy.value = true
            _streamingReply.value = ""
            _errorText.value = null
            val circle = currentCircle
            val activeThread = _activeConversationId.value
            try {
                val historySnapshot = filterMessagesForThread(allConversations, activeThread)
                if (circle != null) {
                    repository?.addConversationMessage(
                        circleId = circle.id,
                        memberUids = circle.memberUids,
                        role = "user",
                        text = bubbleText,
                        isStuckFlagged = markStuckAlert,
                        threadId = activeThread
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
                val effectiveBitmap = attachedScreenshot ?: liveSnap?.screenshot ?: inAppScreenshotProvider?.invoke()
                val screenSummary = buildString {
                    if (extraScreenContext.isNotBlank()) appendLine(extraScreenContext)
                    if (liveSnap != null) {
                        appendLine("Active app package: ${liveSnap.packageName}")
                        if (liveSnap.visibleLabels.isNotEmpty()) {
                            appendLine("Visible on-screen elements: ${liveSnap.visibleLabels.take(45).joinToString(" | ")}")
                        }
                        latestNodeBounds = liveSnap.nodeBoundsByLabel
                    } else if (inAppNodeBounds.isNotEmpty()) {
                        appendLine("Visible KindGuide buttons: ${inAppNodeBounds.keys.take(35).joinToString(" | ")}")
                    }
                }

                val circleSummary = buildCircleSummary(circle)
                var spokenUpToIndex = 0
                var hasQueuedAnySpeech = false
                val streamSpeechMsgId = "streaming_live"

                val result = aiService.streamCompanionTurn(
                    userMessage = cleanQuery,
                    historyMessages = historySnapshot,
                    screenContextSummary = screenSummary,
                    screenshot = effectiveBitmap,
                    circleContextSummary = circleSummary,
                    onPartialReply = { partial ->
                        _streamingReply.value = partial
                        if (_voiceReadAloudEnabled.value && !GeminiLiveManager.state.value.isActive) {
                            val (readySentences, newIdx) = extractReadySpeechSentences(
                                fullText = partial,
                                spokenUpToIndex = spokenUpToIndex,
                                flushRemaining = false
                            )
                            spokenUpToIndex = newIdx
                            readySentences.forEach { sentence ->
                                speakStreamedChunk(
                                    text = sentence,
                                    messageId = streamSpeechMsgId,
                                    isFirstChunk = !hasQueuedAnySpeech
                                )
                                hasQueuedAnySpeech = true
                            }
                        }
                    }
                )

                val replyText = result.reply.ifBlank {
                    result.guideStep?.instruction ?: "I'm right here with you."
                }
                _lastReply.value = replyText

                if (_voiceReadAloudEnabled.value) {
                    if (GeminiLiveManager.state.value.isActive) {
                        GeminiLiveManager.sendTextNudge("Please read this reply warmly: ${cleanMarkdownForSpeech(replyText)}")
                    } else {
                        val (tailSentences, newIdx) = extractReadySpeechSentences(
                            fullText = replyText,
                            spokenUpToIndex = spokenUpToIndex,
                            flushRemaining = true
                        )
                        spokenUpToIndex = newIdx
                        tailSentences.forEach { chunk ->
                            speakStreamedChunk(
                                text = chunk,
                                messageId = streamSpeechMsgId,
                                isFirstChunk = !hasQueuedAnySpeech
                            )
                            hasQueuedAnySpeech = true
                        }
                    }
                }

                if (result.guideStep != null) {
                    setLiveGuideStep(result.guideStep)
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
                        isStuckFlagged = false,
                        threadId = activeThread
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "askCompanion failed", e)
                val fallbackMsg = "I'm right here to help with your phone, tasks, and medicines. Could you say that once more?"
                _lastReply.value = fallbackMsg
                _errorText.value = e.localizedMessage ?: "Couldn't reach AI assistant right now."
                if (_voiceReadAloudEnabled.value) {
                    speakText(fallbackMsg, messageId = "last_reply")
                }
                if (circle != null) {
                    repository?.addConversationMessage(
                        circleId = circle.id,
                        memberUids = circle.memberUids,
                        role = "model",
                        text = fallbackMsg,
                        isStuckFlagged = false,
                        threadId = activeThread
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
        if (GeminiLiveManager.state.value.isActive) {
            val prompt = if (step != null) {
                "[USER REQUESTED NEXT STEP] I finished Step ${step.stepNumber} ('${step.instruction}'). Inspect my current screen (${getCurrentVisibleNodeSummary()}) and guide me to the next step."
            } else {
                "Inspect my current screen (${getCurrentVisibleNodeSummary()}) and guide me to the next single step."
            }
            GeminiLiveManager.notifyUserScreenAction(prompt, inAppScreenshotProvider?.invoke())
            return
        }

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
        if (GeminiLiveManager.state.value.isActive) {
            val prompt = if (step != null) {
                "[USER STUCK ON STEP] I am confused on Step ${step.stepNumber} ('${step.instruction}'). Please explain this step more simply in your warm Gemini voice."
            } else {
                "Please explain your last instruction more simply, one small step at a time."
            }
            flagLiveStuckHelp(step?.instruction ?: "Asked for simpler explanation")
            GeminiLiveManager.notifyUserScreenAction(prompt, inAppScreenshotProvider?.invoke())
            return
        }

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
        val feedback = _crudFeedbackBanner.value
        if (!feedback.isNullOrBlank()) {
            recordCompletedSessionStep(
                actionType = "CARE_CIRCLE_${entity.uppercase()}",
                title = feedback,
                detail = listOf(cmd.subtitle, cmd.extra).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { "Saved to Care Circle" },
                appName = "KindGuide",
                statusIcon = "✅",
                succeeded = true
            )
        }
    }

    /**
     * Strictly verifies whether `targetButtonHint` exists on the current screen's live UI node map,
     * and stabilizes coordinates so the highlight ring never jitters or jumps around!
     */
    fun refreshSpotlightBounds() {
        val pendingConfirm = _pendingConfirmationAction.value
        val step = _activeStep.value
        if (!_isHighlightEnabled.value || (step == null && pendingConfirm == null)) {
            _targetHighlightBounds.value = null
            return
        }

        if (step != null && (step.guidanceMode == "SWIPE_HINT" || step.guidanceMode == "SEARCH_HINT")) {
            _targetHighlightBounds.value = null
            return
        }

        val hint = pendingConfirm?.targetLabel?.trim()?.ifBlank { "Send" }
            ?: step?.targetButtonHint?.trim().orEmpty()
        if (hint.isBlank()) {
            _targetHighlightBounds.value = null
            return
        }

        val combined = getCombinedNodeBounds()
        val matched = findMatchingNodeBoundsInMap(hint, combined)
        if (matched == null) {
            _targetHighlightBounds.value = null
            return
        }

        val curPkg = _activeScreenPackage.value
        val prevRect = lastLockedHighlightRect
        if (prevRect != null &&
            curPkg == lastLockedHighlightPkg &&
            hint.equals(lastLockedHighlightLabel, ignoreCase = true)
        ) {
            val dist = hypot(
                (matched.centerX() - prevRect.centerX()).toDouble(),
                (matched.centerY() - prevRect.centerY()).toDouble()
            )
            // Ignore sub-24px layout jitter so the spotlight box stays rock-solid
            if (dist < 24.0) {
                _targetHighlightBounds.value = prevRect
                return
            }
        }

        lastLockedHighlightPkg = curPkg
        lastLockedHighlightLabel = hint
        lastLockedHighlightRect = Rect(matched)
        _targetHighlightBounds.value = matched
    }

    fun findVerifiedBoundsForLabel(hint: String): Rect? {
        return findMatchingNodeBoundsInMap(hint.trim(), getCombinedNodeBounds())
    }

    /**
     * Strictly matches ONLY real, concise button/contact labels (`<= 42` chars) on the current screen.
     * Never matches long sentences or vague descriptions when the actual icon is not present!
     */
    private fun findMatchingNodeBoundsInMap(hint: String, boundsMap: Map<String, Rect>): Rect? {
        if (hint.isBlank() || boundsMap.isEmpty()) return null
        val cleanHint = cleanTargetLabel(hint)
        if (cleanHint.isBlank() || cleanHint.split(" ").size > 5) return null

        // 1. Exact case-insensitive label match (e.g. "WhatsApp", "Amardeep Sharma", "Send")
        boundsMap.entries.firstOrNull { (label, _) ->
            label.length <= 42 && (
                label.trim().equals(hint, ignoreCase = true) ||
                    cleanTargetLabel(label).equals(cleanHint, ignoreCase = true)
                )
        }?.let { return it.value }

        // 2. Clean contact/button title match (only when label is concise and shares exact words)
        boundsMap.entries.firstOrNull { (label, _) ->
            val cleanedLabel = cleanTargetLabel(label)
            label.length <= 38 &&
                cleanedLabel.isNotBlank() &&
                cleanedLabel.length >= 3 &&
                cleanHint.length >= 3 &&
                (cleanedLabel.startsWith(cleanHint, ignoreCase = true) ||
                    cleanHint.startsWith(cleanedLabel, ignoreCase = true))
        }?.let { return it.value }

        return null
    }

    private fun isFuzzyLabelMatch(clickedLabel: String, targetHint: String): Boolean {
        if (clickedLabel.isBlank() || targetHint.isBlank()) return false
        val c1 = cleanTargetLabel(clickedLabel).lowercase()
        val c2 = cleanTargetLabel(targetHint).lowercase()
        if (c1.isBlank() || c2.isBlank()) return false
        if (c1 == c2 || c1.contains(c2) || c2.contains(c1)) return true

        val t1 = c1.split(" ").filter { it.length >= 3 }.toSet()
        val t2 = c2.split(" ").filter { it.length >= 3 }.toSet()
        return t1.isNotEmpty() && t2.isNotEmpty() && t1.intersect(t2).isNotEmpty()
    }

    private fun cleanTargetLabel(raw: String): String {
        return raw.lowercase()
            .replace(Regex("\\b(button|tab|icon|card|the|tap|click|on|at|bottom|top|left|right|green|blue|red)\\b"), " ")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
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
