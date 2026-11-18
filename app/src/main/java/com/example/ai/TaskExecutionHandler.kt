package com.example.ai

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.example.service.ScreenGuideAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

data class TaskExecutionState(
    val isExecutingStep: Boolean = false,
    val isSpeakingConfirmation: Boolean = false,
    val currentSpokenText: String = "",
    val lastSpokenConfirmation: String = "",
    val spokenConfirmationsInCurrentChain: List<String> = emptyList()
)

/**
 * Host callbacks supplied by [GuideSessionCoordinator] so [TaskExecutionHandler] can inspect
 * live screen state, perform accessibility actions, update overlay UI/Task History, and honor
 * conversation intents without creating tight cyclic dependencies.
 */
interface TaskExecutionHost {
    val appContext: Context?
    val isAccessibilityConnected: Boolean
    val activeScreenPackage: String
    val latestNodeBounds: Map<String, android.graphics.Rect>
    val pendingConfirmationAction: AssistiveScreenAction?
    val activeStep: AiGuideStep?
    val lastRequestedContactName: String
    val pendingSubGoal: String
    val pendingMessageText: String
    val pausedTaskDescription: String
    val isTaskAwaitingUserTap: Boolean
    val lastExecutedActionSignature: String
    val lastExecutedActionAtMs: Long
    val screenSnapshotRequester: (suspend () -> OverlayScreenSnapshot?)?
    val screenActionExecutor: (suspend (AssistiveScreenAction) -> Boolean)?

    fun updateScreenTaskActive(active: Boolean)
    fun updateOverlayVisible(visible: Boolean)
    fun updatePendingConfirmation(action: AssistiveScreenAction?)
    fun updateStepMonitorBanner(banner: String?)
    fun updateLastReply(reply: String)
    fun updateSpeakingMessageId(id: String?)
    fun updateLastRequestedContactName(name: String)
    fun updatePendingSubGoal(goal: String)
    fun updatePendingMessageText(text: String)
    fun updatePausedTaskState(awaitingTap: Boolean, description: String)
    fun updateLastExecutedAction(signature: String, atMs: Long, succeeded: Boolean)
    fun updateLatestNodeBounds(bounds: Map<String, android.graphics.Rect>)
    fun onForegroundWindowChanged(newPackageName: String)
    fun setLiveGuideStep(step: AiGuideStep): Boolean
    fun refreshSpotlightBounds()
    fun isChatComposerVisibleOnScreen(): Boolean
    fun getCurrentVisibleNodeSummary(): String
    fun isFuzzyLabelMatch(clickedLabel: String, targetHint: String): Boolean
    fun isPackageMatchingAppName(packageName: String, appName: String): Boolean
    fun recordCompletedSessionStep(
        actionType: String,
        title: String,
        detail: String,
        appName: String,
        statusIcon: String,
        succeeded: Boolean
    )
}

/**
 * Executes verified single- and multi-step screen tasks under [InterruptController],
 * explicitly calling the Android [TextToSpeech] (TTS) engine after each completed step and
 * awaiting utterance completion before proceeding to the next automated operation.
 */
object TaskExecutionHandler {

    private val utteranceSeq = AtomicLong(0L)
    private val pendingUtterances = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val _state = MutableStateFlow(TaskExecutionState())
    val state: StateFlow<TaskExecutionState> = _state.asStateFlow()

    @Volatile
    private var ttsEngine: TextToSpeech? = null

    @Volatile
    private var ttsReadyProvider: () -> Boolean = { false }

    @Volatile
    var voiceReadAloudEnabledProvider: (() -> Boolean)? = null

    /**
     * When true (e.g. a Gemini Live voice session is actively connected), [TaskExecutionHandler]
     * bypasses blocking local Android [TextToSpeech] confirmations so Gemini Live can narrate
     * step progress directly in its own streaming voice without stalling tool responses.
     */
    @Volatile
    var preferLiveNativeVoiceProvider: () -> Boolean = { false }

    /**
     * Optional custom TTS delegate for automated testing or alternative speech sinks.
     * When null (default in production), [TaskExecutionHandler] explicitly calls [TextToSpeech.speak]
     * on [ttsEngine] and suspends until [onUtteranceFinished] fires via UtteranceProgressListener.
     */
    @Volatile
    private var customTtsStepSpeaker: (suspend (utteranceId: String, spokenText: String) -> Boolean)? = null

    private val interruptKeywords = setOf(
        "stop", "cancel", "wait", "leave", "quit", "no", "hold", "abort", "never"
    )

    fun attachTextToSpeechEngine(engine: TextToSpeech?, isReadyProvider: () -> Boolean) {
        ttsEngine = engine
        ttsReadyProvider = isReadyProvider
    }

    fun registerCustomTtsStepSpeaker(
        speaker: (suspend (utteranceId: String, spokenText: String) -> Boolean)?
    ) {
        customTtsStepSpeaker = speaker
    }

    fun isSpeakingConfirmation(): Boolean = _state.value.isSpeakingConfirmation

    fun isLikelyTtsEcho(rawInput: String): Boolean {
        val currentTts = _state.value.currentSpokenText.trim()
        if (!_state.value.isSpeakingConfirmation || currentTts.isBlank()) return false
        val cleanInput = rawInput.trim()
        if (cleanInput.length < 3) return true
        val words = cleanInput.lowercase()
            .replace(Regex("[^a-z\\s]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        if (words.any { it in interruptKeywords }) return false
        return currentTts.contains(cleanInput, ignoreCase = true)
    }

    fun onUtteranceStarted(utteranceId: String?) {
        if (utteranceId.isNullOrBlank()) return
        if (pendingUtterances.containsKey(utteranceId)) {
            _state.update { it.copy(isSpeakingConfirmation = true) }
        }
    }

    fun onUtteranceFinished(utteranceId: String?, succeeded: Boolean = true) {
        if (utteranceId.isNullOrBlank()) {
            val ids = pendingUtterances.keys.toList()
            for (id in ids) {
                pendingUtterances.remove(id)?.complete(succeeded)
            }
            _state.update { it.copy(isSpeakingConfirmation = false, currentSpokenText = "") }
            return
        }
        pendingUtterances.remove(utteranceId)?.complete(succeeded)
        if (pendingUtterances.isEmpty()) {
            _state.update { it.copy(isSpeakingConfirmation = false, currentSpokenText = "") }
        }
    }

    fun stopTtsImmediately() {
        val ids = pendingUtterances.keys.toList()
        for (id in ids) {
            pendingUtterances.remove(id)?.complete(false)
        }
        runCatching { ttsEngine?.stop() }
        _state.update { it.copy(isSpeakingConfirmation = false, currentSpokenText = "") }
    }

    fun reset() {
        stopTtsImmediately()
        _state.value = TaskExecutionState()
    }

    /**
     * Explicitly invokes the Text-to-Speech (TTS) engine for [confirmationText] after a completed
     * step and suspends until speech completes (or is interrupted/times out) before the caller
     * moves on to the next automated operation.
     */
    suspend fun speakStepConfirmationAndWait(
        token: TaskExecutionToken,
        confirmationText: String,
        host: TaskExecutionHost? = null,
        slow: Boolean = false
    ): Boolean {
        val cleanText = confirmationText.replace(Regex("\\s+"), " ").trim()
        if (cleanText.isBlank()) return false
        if (InterruptController.isAborted(token)) return false
        if (preferLiveNativeVoiceProvider.invoke()) {
            return false
        }

        _state.update {
            it.copy(
                lastSpokenConfirmation = cleanText,
                spokenConfirmationsInCurrentChain = it.spokenConfirmationsInCurrentChain + cleanText
            )
        }
        host?.updateLastReply(cleanText)

        if (voiceReadAloudEnabledProvider?.invoke() == false) {
            return false
        }

        val utteranceId = "step_tts_${utteranceSeq.incrementAndGet()}"
        val customSpeaker = customTtsStepSpeaker
        if (customSpeaker != null) {
            _state.update { it.copy(isSpeakingConfirmation = true, currentSpokenText = cleanText) }
            host?.updateSpeakingMessageId(utteranceId)
            return try {
                if (InterruptController.isAborted(token)) return false
                customSpeaker.invoke(utteranceId, cleanText)
            } catch (ce: CancellationException) {
                stopTtsImmediately()
                throw ce
            } finally {
                _state.update { it.copy(isSpeakingConfirmation = false, currentSpokenText = "") }
                host?.updateSpeakingMessageId(null)
            }
        }

        val engine = ttsEngine
        if (engine == null || !ttsReadyProvider.invoke()) {
            return false
        }

        val completion = CompletableDeferred<Boolean>()
        pendingUtterances[utteranceId] = completion
        _state.update { it.copy(isSpeakingConfirmation = true, currentSpokenText = cleanText) }
        host?.updateSpeakingMessageId(utteranceId)

        return try {
            if (InterruptController.isAborted(token)) {
                pendingUtterances.remove(utteranceId)?.complete(false)
                return false
            }
            engine.setPitch(1.03f)
            engine.setSpeechRate(if (slow) 0.85f else 0.95f)
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val speakResult = engine.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (speakResult != TextToSpeech.SUCCESS) {
                pendingUtterances.remove(utteranceId)?.complete(false)
                false
            } else {
                val timeoutMs = (2200L + cleanText.length * 85L).coerceIn(2500L, 9000L)
                withTimeoutOrNull(timeoutMs) {
                    completion.await()
                } ?: false
            }
        } catch (ce: CancellationException) {
            pendingUtterances.remove(utteranceId)?.complete(false)
            runCatching { engine.stop() }
            throw ce
        } finally {
            pendingUtterances.remove(utteranceId)
            _state.update { it.copy(isSpeakingConfirmation = false, currentSpokenText = "") }
            host?.updateSpeakingMessageId(null)
        }
    }

    suspend fun executeAndVerifyScreenAction(
        action: AssistiveScreenAction,
        host: TaskExecutionHost
    ): VerifiedActionOutcome {
        host.updateScreenTaskActive(true)
        if (host.isAccessibilityConnected) {
            host.updateOverlayVisible(true)
        }

        val pendingConfirm = host.pendingConfirmationAction
        val effectiveAction = if (action.userConfirmed && pendingConfirm != null &&
            action.targetLabel.isBlank()
        ) {
            pendingConfirm.copy(userConfirmed = true, isFinalSendOrCommit = false)
        } else {
            action
        }

        val isCommitAction = !effectiveAction.userConfirmed && (
            effectiveAction.isFinalSendOrCommit ||
                effectiveAction.targetLabel.equals("Send", ignoreCase = true) ||
                effectiveAction.targetLabel.equals("Delete", ignoreCase = true) ||
                effectiveAction.targetLabel.equals("Pay", ignoreCase = true)
            )

        if (isCommitAction && effectiveAction.actionType.equals("TAP_ELEMENT", ignoreCase = true)) {
            val targetBtn = effectiveAction.targetLabel.ifBlank { "Send" }
            val promptText = effectiveAction.spokenExplanation.ifBlank {
                "Tap '$targetBtn' now?"
            }
            host.updatePendingConfirmation(effectiveAction.copy(targetLabel = targetBtn))
            val confirmStep = AiGuideStep(
                stepTitle = "Confirm Before Sending",
                stepNumber = host.activeStep?.stepNumber ?: 1,
                totalSteps = host.activeStep?.totalSteps ?: 1,
                instruction = promptText,
                targetButtonHint = targetBtn,
                reassuranceNote = "Tap '✅ Send Now' or say 'Yes, send it' to confirm.",
                suggestedAppName = "",
                isCorrection = false,
                guidanceMode = "CONFIRM_SEND",
                requiresConfirmation = true
            )
            host.setLiveGuideStep(confirmStep)

            val ctx = host.appContext
            val pkg = host.activeScreenPackage.ifBlank { ctx?.packageName.orEmpty() }
            val appTitle = ScreenGuideAccessibilityService.resolveHumanReadableAppName(ctx, pkg)
            host.recordCompletedSessionStep(
                actionType = "CONFIRM_PAUSE",
                title = "Ready to tap '$targetBtn' (Awaiting Confirmation)",
                detail = promptText,
                appName = appTitle,
                statusIcon = "✋",
                succeeded = true
            )
            return VerifiedActionOutcome(
                success = true,
                awaitingUserConfirmation = true,
                actionSummary = "Paused before tapping '$targetBtn'. Confirmation buttons ('✅ $targetBtn Now' / '✕ Cancel') are now visible on screen.",
                verifiedAppTitle = appTitle,
                isChatConversationOpen = host.isChatComposerVisibleOnScreen(),
                visibleElementsSummary = host.getCurrentVisibleNodeSummary()
            )
        }

        host.updatePendingConfirmation(null)
        val ctx = host.appContext
        val executor = host.screenActionExecutor
        val isDirectIntentAction = effectiveAction.actionType.equals("OPEN_APP", ignoreCase = true) ||
            effectiveAction.actionType.equals("WEB_SEARCH", ignoreCase = true)

        if (executor == null && (!isDirectIntentAction || ctx == null)) {
            val msg = "Accessibility helper is not enabled yet to tap or type across apps."
            host.updateStepMonitorBanner("⚠️ $msg")
            val pkg = host.activeScreenPackage.ifBlank { ctx?.packageName.orEmpty() }
            return VerifiedActionOutcome(
                success = false,
                actionSummary = msg,
                verifiedAppTitle = ScreenGuideAccessibilityService.resolveHumanReadableAppName(ctx, pkg),
                visibleElementsSummary = host.getCurrentVisibleNodeSummary()
            )
        }

        val requestedTargetClean = effectiveAction.targetLabel.ifBlank { effectiveAction.expectedAppName }.trim()
        val actionSig = "${effectiveAction.actionType.uppercase()}:${requestedTargetClean.lowercase()}:${effectiveAction.textToType.trim().lowercase()}"
        val taskDesc = "${effectiveAction.actionType}:${requestedTargetClean.ifBlank { effectiveAction.textToType.take(24) }}"
        val execJob = Job(coroutineContext[Job])
        val token = InterruptController.beginExecution(taskDesc, execJob)
        _state.update { it.copy(isExecutingStep = true, spokenConfirmationsInCurrentChain = emptyList()) }
        try {
            return withContext(execJob) {
                executeInterruptibleScreenAction(
                    token = token,
                    effectiveAction = effectiveAction,
                    requestedTargetClean = requestedTargetClean,
                    actionSig = actionSig,
                    ctx = ctx,
                    executor = executor,
                    isDirectIntentAction = isDirectIntentAction,
                    host = host
                )
            }
        } catch (ce: CancellationException) {
            stopTtsImmediately()
            if (InterruptController.isAborted(token)) {
                return buildAbortedOutcome(ctx, host)
            }
            throw ce
        } finally {
            execJob.complete()
            _state.update { it.copy(isExecutingStep = false) }
            InterruptController.finishExecution(token)
        }
    }

    private fun buildAbortedOutcome(ctx: Context?, host: TaskExecutionHost): VerifiedActionOutcome {
        val pkg = host.activeScreenPackage.ifBlank { ctx?.packageName.orEmpty() }
        val appTitle = ScreenGuideAccessibilityService.resolveHumanReadableAppName(ctx, pkg)
        return VerifiedActionOutcome(
            success = false,
            wasAborted = true,
            actionSummary = "Aborted previous task due to user interrupt",
            verifiedAppTitle = appTitle,
            isChatConversationOpen = host.isChatComposerVisibleOnScreen(),
            visibleElementsSummary = host.getCurrentVisibleNodeSummary(),
            spokenConfirmations = _state.value.spokenConfirmationsInCurrentChain
        )
    }

    private suspend fun executeInterruptibleScreenAction(
        token: TaskExecutionToken,
        effectiveAction: AssistiveScreenAction,
        requestedTargetClean: String,
        actionSig: String,
        ctx: Context?,
        executor: (suspend (AssistiveScreenAction) -> Boolean)?,
        isDirectIntentAction: Boolean,
        host: TaskExecutionHost
    ): VerifiedActionOutcome {
        if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)

        val nowMs = System.currentTimeMillis()
        val currentActivePkg = host.activeScreenPackage
        val alreadyInRequestedApp = effectiveAction.actionType.equals("OPEN_APP", ignoreCase = true) &&
            host.isPackageMatchingAppName(currentActivePkg, requestedTargetClean)

        if (alreadyInRequestedApp && (
                host.isTaskAwaitingUserTap ||
                    (actionSig == host.lastExecutedActionSignature && nowMs - host.lastExecutedActionAtMs < 5000L)
                )
        ) {
            val snap = if (host.isAccessibilityConnected) {
                runCatching { host.screenSnapshotRequester?.invoke() }.getOrNull()
            } else {
                null
            }
            if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
            if (snap != null) {
                host.updateLatestNodeBounds(snap.nodeBoundsByLabel)
            }
            val appTitle = ScreenGuideAccessibilityService.resolveHumanReadableAppName(ctx, currentActivePkg)
            val awaitingTap = host.isTaskAwaitingUserTap
            val msg = if (awaitingTap && host.lastRequestedContactName.isNotBlank()) {
                "🔍 WhatsApp search is open for '${host.lastRequestedContactName}' — awaiting tap"
            } else {
                "✅ Already in ${requestedTargetClean.ifBlank { appTitle.substringBefore(" (") }}"
            }
            host.updateStepMonitorBanner(msg)
            val spoken = if (awaitingTap && host.lastRequestedContactName.isNotBlank()) {
                "WhatsApp search is already open for ${host.lastRequestedContactName}. Please tap their chat on the screen."
            } else {
                "${requestedTargetClean.ifBlank { appTitle.substringBefore(" (") }} is already open."
            }
            speakStepConfirmationAndWait(token, spoken, host)
            if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
            return VerifiedActionOutcome(
                success = true,
                awaitingUserConfirmation = host.pendingConfirmationAction != null,
                awaitingUserTapOrHelp = awaitingTap,
                actionSummary = msg,
                verifiedAppTitle = appTitle,
                isChatConversationOpen = host.isChatComposerVisibleOnScreen(),
                visibleElementsSummary = host.getCurrentVisibleNodeSummary(),
                spokenConfirmations = _state.value.spokenConfirmationsInCurrentChain
            )
        }

        val progressText = when (effectiveAction.actionType.uppercase()) {
            "OPEN_APP" -> "🟢 Opening ${effectiveAction.targetLabel.ifBlank { effectiveAction.expectedAppName }}..."
            "WEB_SEARCH" -> "🟢 Searching Chrome for '${effectiveAction.textToType.ifBlank { effectiveAction.targetLabel }}'..."
            "TAP_ELEMENT" -> "🟢 Tapping '${effectiveAction.targetLabel}'..."
            "SWIPE_SCREEN" -> "🟢 Swiping ${effectiveAction.swipeDirection.lowercase()}..."
            "TYPE_TEXT" -> "🟢 Typing '${effectiveAction.textToType.take(28)}'..."
            "SYSTEM_NAV" -> "🟢 Pressing ${effectiveAction.systemNavAction.lowercase()}..."
            else -> "🟢 Working on screen..."
        }
        host.updateStepMonitorBanner(progressText)

        var success = runCatching {
            when {
                executor != null -> executor.invoke(effectiveAction)
                ctx != null && effectiveAction.actionType.equals("OPEN_APP", ignoreCase = true) ->
                    ScreenGuideAccessibilityService.launchAppByName(
                        ctx,
                        effectiveAction.targetLabel.ifBlank { effectiveAction.expectedAppName }
                    )
                ctx != null && effectiveAction.actionType.equals("WEB_SEARCH", ignoreCase = true) ->
                    ScreenGuideAccessibilityService.launchWebSearchInBrowser(
                        ctx,
                        effectiveAction.textToType.ifBlank { effectiveAction.targetLabel }
                    )
                else -> false
            }
        }.getOrDefault(false)

        if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)

        host.updateLastExecutedAction(actionSig, System.currentTimeMillis(), success)

        val inMessagingContext = effectiveAction.expectedAppName.contains("whatsapp", ignoreCase = true) ||
            host.activeScreenPackage.contains("whatsapp", ignoreCase = true) ||
            host.activeScreenPackage.contains("messaging", ignoreCase = true)
        if (inMessagingContext && effectiveAction.actionType.equals("TAP_ELEMENT", ignoreCase = true)) {
            val t = effectiveAction.targetLabel.trim()
            if (t.length in 3..34 &&
                !t.equals("Send", ignoreCase = true) &&
                !t.contains("Search", ignoreCase = true) &&
                !t.equals("WhatsApp", ignoreCase = true) &&
                !t.equals("New chat", ignoreCase = true) &&
                !t.equals("Back", ignoreCase = true)
            ) {
                host.updateLastRequestedContactName(t)
            }
        }

        val settleMs = when {
            executor != null -> 20L
            isDirectIntentAction -> 260L
            else -> 120L
        }
        if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
        delay(settleMs)
        if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)

        var liveSnap = if (host.isAccessibilityConnected) {
            runCatching { host.screenSnapshotRequester?.invoke() }.getOrNull()
        } else {
            null
        }
        if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
        if (liveSnap != null) {
            if (liveSnap.packageName.isNotBlank() && liveSnap.packageName != host.activeScreenPackage) {
                host.onForegroundWindowChanged(liveSnap.packageName)
            }
            host.updateLatestNodeBounds(liveSnap.nodeBoundsByLabel)
        }

        // Speak TTS confirmation right after Step 1 completes BEFORE moving to any follow-up step!
        val launchedMessagingApp = requestedTargetClean.contains("whatsapp", ignoreCase = true) ||
            requestedTargetClean.contains("message", ignoreCase = true) ||
            host.activeScreenPackage.contains("whatsapp", ignoreCase = true)
        val willFallbackSearchOnTapMiss = !success &&
            effectiveAction.actionType.equals("TAP_ELEMENT", ignoreCase = true) &&
            host.activeScreenPackage.contains("whatsapp", ignoreCase = true) &&
            executor != null

        val primaryStepAppName = effectiveAction.targetLabel.ifBlank {
            effectiveAction.expectedAppName.ifBlank { "the app" }
        }
        var step1Spoken = false
        val primaryConfirmationPhrase = when (effectiveAction.actionType.uppercase()) {
            "OPEN_APP" -> if (success) "Opened $primaryStepAppName." else "I couldn't open $primaryStepAppName."
            "TAP_ELEMENT" -> when {
                success -> "Tapped ${effectiveAction.targetLabel}."
                willFallbackSearchOnTapMiss -> null
                else -> "I couldn't find ${effectiveAction.targetLabel} on this screen."
            }
            "TYPE_TEXT" -> if (success) {
                if (host.isChatComposerVisibleOnScreen() && !effectiveAction.submitAfterType) {
                    "Typed '${effectiveAction.textToType}'. Tap Send Now or say yes to send."
                } else {
                    "Typed '${effectiveAction.textToType}'."
                }
            } else {
                "Please open the chat box first before typing."
            }
            "SWIPE_SCREEN" -> "Scrolled ${effectiveAction.swipeDirection.lowercase()}."
            "WEB_SEARCH" -> if (success) {
                "Searched Chrome for ${effectiveAction.textToType.ifBlank { effectiveAction.targetLabel }}."
            } else {
                "I couldn't open Chrome search right now."
            }
            "SYSTEM_NAV" -> "Pressed ${effectiveAction.systemNavAction.lowercase()}."
            else -> if (success) "Done." else "That step could not finish."
        }
        if (!primaryConfirmationPhrase.isNullOrBlank()) {
            speakStepConfirmationAndWait(token, primaryConfirmationPhrase, host)
            step1Spoken = true
            if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
        }

        // AUTO-CHAIN STEP 2: When OPEN_APP opens a messaging app and we know the contact,
        // find or search for the contact and tap their chat, speaking TTS confirmation after each sub-step.
        var autoOpenedContactName: String? = null
        var openedSearchAwaitingTapFor: String? = null
        var spokenAutoSearchOrContactConfirmation = false

        if (success &&
            !InterruptController.isAborted(token) &&
            effectiveAction.actionType.equals("OPEN_APP", ignoreCase = true) &&
            launchedMessagingApp &&
            host.lastRequestedContactName.isNotBlank() &&
            executor != null
        ) {
            val contactTarget = host.lastRequestedContactName
            var matchedContactLabel = host.latestNodeBounds.keys.firstOrNull { label ->
                host.isFuzzyLabelMatch(label, contactTarget) &&
                    !label.contains("Search", ignoreCase = true) &&
                    !label.equals("WhatsApp", ignoreCase = true)
            }
            var searchWasTyped = false
            if (matchedContactLabel == null) {
                val searchBarLabel = host.latestNodeBounds.keys.firstOrNull { label ->
                    label.contains("Ask Meta AI or Search", ignoreCase = true) ||
                        label.equals("Search", ignoreCase = true)
                }
                if (searchBarLabel != null) {
                    searchWasTyped = runCatching {
                        executor.invoke(
                            AssistiveScreenAction(
                                actionType = "TYPE_TEXT",
                                targetLabel = searchBarLabel,
                                textToType = contactTarget,
                                submitAfterType = false,
                                expectedAppName = effectiveAction.targetLabel.ifBlank { "WhatsApp" },
                                spokenExplanation = "Searching for $contactTarget"
                            )
                        )
                    }.getOrDefault(false)
                    if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
                    if (searchWasTyped) {
                        speakStepConfirmationAndWait(token, "Searching for $contactTarget.", host)
                        spokenAutoSearchOrContactConfirmation = true
                        if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)

                        for (attempt in 0 until 5) {
                            if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
                            delay(90L)
                            if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
                            liveSnap = runCatching { host.screenSnapshotRequester?.invoke() }.getOrNull()
                            if (liveSnap != null) {
                                host.updateLatestNodeBounds(liveSnap.nodeBoundsByLabel)
                            }
                            matchedContactLabel = host.latestNodeBounds.keys.firstOrNull { label ->
                                host.isFuzzyLabelMatch(label, contactTarget) &&
                                    !label.contains("Search", ignoreCase = true) &&
                                    !label.equals("WhatsApp", ignoreCase = true)
                            }
                            if (matchedContactLabel != null) break
                        }
                    }
                }
            }

            if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
            val labelToTap = matchedContactLabel ?: if (searchWasTyped) contactTarget else null
            var tappedContact = false
            if (labelToTap != null) {
                tappedContact = runCatching {
                    executor.invoke(
                        AssistiveScreenAction(
                            actionType = "TAP_ELEMENT",
                            targetLabel = labelToTap,
                            spokenExplanation = "Opening $labelToTap's chat"
                        )
                    )
                }.getOrDefault(false)
                if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
                if (tappedContact) {
                    autoOpenedContactName = labelToTap
                    host.updatePausedTaskState(awaitingTap = false, description = "")
                    for (attempt in 0 until 3) {
                        if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
                        liveSnap = runCatching { host.screenSnapshotRequester?.invoke() }.getOrNull()
                        if (liveSnap != null) {
                            host.updateLatestNodeBounds(liveSnap.nodeBoundsByLabel)
                        }
                        if (host.isChatComposerVisibleOnScreen()) break
                        delay(65L)
                    }
                    if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
                    speakStepConfirmationAndWait(token, "Opened $labelToTap's chat.", host)
                    spokenAutoSearchOrContactConfirmation = true
                    if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
                }
            }

            if (!InterruptController.isAborted(token) && !tappedContact && !host.isChatComposerVisibleOnScreen()) {
                openedSearchAwaitingTapFor = contactTarget
                host.updatePausedTaskState(awaitingTap = true, description = "Opening WhatsApp chat with $contactTarget")
                speakStepConfirmationAndWait(
                    token,
                    "I opened WhatsApp search for $contactTarget. Please tap their chat on the screen.",
                    host
                )
                spokenAutoSearchOrContactConfirmation = true
                if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
            }
        } else if (!success &&
            !InterruptController.isAborted(token) &&
            effectiveAction.actionType.equals("TAP_ELEMENT", ignoreCase = true) &&
            host.activeScreenPackage.contains("whatsapp", ignoreCase = true) &&
            executor != null
        ) {
            val targetName = effectiveAction.targetLabel.trim()
            val isGenericControl = targetName.isBlank() ||
                targetName.equals("Send", ignoreCase = true) ||
                targetName.contains("Search", ignoreCase = true) ||
                targetName.equals("Back", ignoreCase = true)
            if (!isGenericControl) {
                val searchBarLabel = host.latestNodeBounds.keys.firstOrNull { label ->
                    label.contains("Ask Meta AI or Search", ignoreCase = true) ||
                        label.equals("Search", ignoreCase = true)
                }
                val searchTyped = if (searchBarLabel != null) {
                    runCatching {
                        executor.invoke(
                            AssistiveScreenAction(
                                actionType = "TYPE_TEXT",
                                targetLabel = searchBarLabel,
                                textToType = targetName,
                                submitAfterType = false,
                                expectedAppName = "WhatsApp",
                                spokenExplanation = "Searching for $targetName"
                            )
                        )
                    }.getOrDefault(false)
                } else {
                    false
                }
                if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
                if (searchTyped) {
                    speakStepConfirmationAndWait(token, "Searching for $targetName.", host)
                    spokenAutoSearchOrContactConfirmation = true
                    if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)

                    var foundLabel: String? = null
                    for (attempt in 0 until 5) {
                        if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
                        delay(90L)
                        if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
                        liveSnap = runCatching { host.screenSnapshotRequester?.invoke() }.getOrNull()
                        if (liveSnap != null) {
                            host.updateLatestNodeBounds(liveSnap.nodeBoundsByLabel)
                        }
                        foundLabel = host.latestNodeBounds.keys.firstOrNull { label ->
                            host.isFuzzyLabelMatch(label, targetName) &&
                                !label.contains("Search", ignoreCase = true) &&
                                !label.equals("WhatsApp", ignoreCase = true)
                        }
                        if (foundLabel != null) break
                    }
                    if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
                    val tapOk = runCatching {
                        executor.invoke(
                            AssistiveScreenAction(
                                actionType = "TAP_ELEMENT",
                                targetLabel = foundLabel ?: targetName,
                                spokenExplanation = "Opening ${foundLabel ?: targetName}'s chat"
                            )
                        )
                    }.getOrDefault(false)
                    if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
                    if (tapOk) {
                        success = true
                        host.updatePausedTaskState(awaitingTap = false, description = "")
                        for (attempt in 0 until 3) {
                            if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
                            liveSnap = runCatching { host.screenSnapshotRequester?.invoke() }.getOrNull()
                            if (liveSnap != null) {
                                host.updateLatestNodeBounds(liveSnap.nodeBoundsByLabel)
                            }
                            if (host.isChatComposerVisibleOnScreen()) break
                            delay(65L)
                        }
                        if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
                        speakStepConfirmationAndWait(token, "Opened ${foundLabel ?: targetName}'s chat.", host)
                        spokenAutoSearchOrContactConfirmation = true
                        if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
                    } else {
                        openedSearchAwaitingTapFor = targetName
                        host.updatePausedTaskState(awaitingTap = true, description = "Opening WhatsApp chat with $targetName")
                        speakStepConfirmationAndWait(
                            token,
                            "I opened WhatsApp search for $targetName. Please tap their chat on the screen.",
                            host
                        )
                        spokenAutoSearchOrContactConfirmation = true
                        if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
                    }
                } else {
                    openedSearchAwaitingTapFor = targetName
                    host.updatePausedTaskState(awaitingTap = true, description = "Opening WhatsApp chat with $targetName")
                    if (!step1Spoken) {
                        speakStepConfirmationAndWait(
                            token,
                            "I couldn't find $targetName automatically. Please tap their chat or use search.",
                            host
                        )
                        spokenAutoSearchOrContactConfirmation = true
                        if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
                    }
                }
            } else if (!step1Spoken) {
                speakStepConfirmationAndWait(token, "I couldn't find $targetName on this screen.", host)
                if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
            }
        }

        if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)

        // AUTO-CHAIN STEP 3: Type pre-provided message if chat composer is open and speak TTS confirmation
        var autoTypedMessage: String? = null
        if (success &&
            !InterruptController.isAborted(token) &&
            (effectiveAction.actionType.equals("OPEN_APP", ignoreCase = true) ||
                effectiveAction.actionType.equals("TAP_ELEMENT", ignoreCase = true)) &&
            host.pendingSubGoal == "send_message" &&
            host.pendingMessageText.isNotBlank() &&
            executor != null &&
            host.isChatComposerVisibleOnScreen()
        ) {
            val msgToType = host.pendingMessageText
            host.updatePendingMessageText("")
            val typedOk = runCatching {
                executor.invoke(
                    AssistiveScreenAction(
                        actionType = "TYPE_TEXT",
                        targetLabel = "Message",
                        textToType = msgToType,
                        submitAfterType = false,
                        expectedAppName = "WhatsApp",
                        spokenExplanation = "Typing '$msgToType'"
                    )
                )
            }.getOrDefault(false)
            if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
            if (typedOk) {
                autoTypedMessage = msgToType
                delay(50L)
                if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
                liveSnap = runCatching { host.screenSnapshotRequester?.invoke() }.getOrNull()
                if (liveSnap != null) {
                    host.updateLatestNodeBounds(liveSnap.nodeBoundsByLabel)
                }
            }
        }

        if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
        host.refreshSpotlightBounds()

        val verifiedPkg = host.activeScreenPackage.ifBlank { ctx?.packageName.orEmpty() }
        val verifiedAppTitle = ScreenGuideAccessibilityService.resolveHumanReadableAppName(ctx, verifiedPkg)
        val chatOpen = host.isChatComposerVisibleOnScreen()
        if (chatOpen) {
            host.updatePausedTaskState(awaitingTap = false, description = "")
        }
        val updatedSummary = host.getCurrentVisibleNodeSummary()

        val typedChatText = autoTypedMessage ?: if (
            success &&
            effectiveAction.actionType.equals("TYPE_TEXT", ignoreCase = true) &&
            !effectiveAction.submitAfterType &&
            chatOpen
        ) {
            effectiveAction.textToType
        } else {
            null
        }

        if (!typedChatText.isNullOrBlank()) {
            host.updatePendingSubGoal("")
            val sendAct = AssistiveScreenAction(
                actionType = "TAP_ELEMENT",
                targetLabel = "Send",
                spokenExplanation = "Send '${typedChatText.take(36)}'?",
                isFinalSendOrCommit = true,
                userConfirmed = false
            )
            host.updatePendingConfirmation(sendAct)
            host.setLiveGuideStep(
                AiGuideStep(
                    stepTitle = "Confirm Before Sending",
                    stepNumber = (host.activeStep?.stepNumber ?: 2),
                    totalSteps = (host.activeStep?.totalSteps ?: 3),
                    instruction = "Tap '✅ Send Now' or say 'Yes, send it' to send '${typedChatText.take(32)}'.",
                    targetButtonHint = "Send",
                    reassuranceNote = "Your message is typed and ready to send.",
                    suggestedAppName = verifiedAppTitle.substringBefore(" ("),
                    isCorrection = false,
                    guidanceMode = "CONFIRM_SEND",
                    requiresConfirmation = true
                )
            )
            if (autoTypedMessage != null) {
                speakStepConfirmationAndWait(
                    token,
                    "I typed '$autoTypedMessage'. Tap Send Now or say yes to send.",
                    host
                )
                if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
            }
        } else if (success &&
            chatOpen &&
            (host.pendingSubGoal == "voice_call" || host.pendingSubGoal == "video_call")
        ) {
            val desiredCallLabel = if (host.pendingSubGoal == "video_call") {
                host.latestNodeBounds.keys.firstOrNull { it.contains("Video call", ignoreCase = true) }
            } else {
                host.latestNodeBounds.keys.firstOrNull {
                    it.contains("Voice call", ignoreCase = true) ||
                        it.contains("Audio call", ignoreCase = true) ||
                        it.equals("Call", ignoreCase = true)
                }
            }
            if (desiredCallLabel != null) {
                host.updatePendingSubGoal("")
                val contactDisplay = host.lastRequestedContactName.ifBlank { "this contact" }
                val callAct = AssistiveScreenAction(
                    actionType = "TAP_ELEMENT",
                    targetLabel = desiredCallLabel,
                    spokenExplanation = "Start $desiredCallLabel with $contactDisplay?",
                    isFinalSendOrCommit = true,
                    userConfirmed = false
                )
                host.updatePendingConfirmation(callAct)
                host.setLiveGuideStep(
                    AiGuideStep(
                        stepTitle = "Confirm Before Calling",
                        stepNumber = 2,
                        totalSteps = 2,
                        instruction = "Tap '✅ $desiredCallLabel Now' or say 'Yes, call' to call $contactDisplay.",
                        targetButtonHint = desiredCallLabel,
                        reassuranceNote = "Ready to start your call.",
                        suggestedAppName = verifiedAppTitle.substringBefore(" ("),
                        isCorrection = false,
                        guidanceMode = "CONFIRM_SEND",
                        requiresConfirmation = true
                    )
                )
                if (!spokenAutoSearchOrContactConfirmation || autoOpenedContactName != null) {
                    speakStepConfirmationAndWait(
                        token,
                        "Ready to start $desiredCallLabel with $contactDisplay. Tap $desiredCallLabel Now or say yes to call.",
                        host
                    )
                    if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)
                }
            }
        } else if (openedSearchAwaitingTapFor != null) {
            host.setLiveGuideStep(
                AiGuideStep(
                    stepTitle = "Tap $openedSearchAwaitingTapFor",
                    stepNumber = 2,
                    totalSteps = 3,
                    instruction = "Tap '$openedSearchAwaitingTapFor' in the WhatsApp search results.",
                    targetButtonHint = "",
                    reassuranceNote = "WhatsApp search results are ready.",
                    suggestedAppName = "WhatsApp",
                    isCorrection = false,
                    guidanceMode = "SEARCH_HINT",
                    requiresConfirmation = false
                )
            )
        }

        if (InterruptController.isAborted(token)) return buildAbortedOutcome(ctx, host)

        val resultBanner = when (effectiveAction.actionType.uppercase()) {
            "OPEN_APP" -> if (success) {
                when {
                    autoTypedMessage != null ->
                        "✅ Opened '$autoOpenedContactName' & typed '${autoTypedMessage.take(22)}'"
                    autoOpenedContactName != null ->
                        "✅ Opened ${effectiveAction.targetLabel.ifBlank { "App" }} & opened '$autoOpenedContactName'"
                    openedSearchAwaitingTapFor != null ->
                        "🔍 Opened WhatsApp search for '$openedSearchAwaitingTapFor' — please tap their chat"
                    else ->
                        "✅ Opened ${effectiveAction.targetLabel.ifBlank { verifiedAppTitle.substringBefore(" (") }}"
                }
            } else {
                "⚠️ Could not open ${effectiveAction.targetLabel}"
            }
            "TAP_ELEMENT" -> when {
                success -> "✅ Opened '${effectiveAction.targetLabel}'"
                openedSearchAwaitingTapFor != null ->
                    "🔍 Opened WhatsApp search for '$openedSearchAwaitingTapFor' — please tap their chat"
                else -> "⚠️ '${effectiveAction.targetLabel}' not found on this screen"
            }
            "TYPE_TEXT" -> if (success) "✅ Typed '${effectiveAction.textToType.take(26)}'" else "⚠️ Open the contact's chat first before typing the message"
            "SWIPE_SCREEN" -> "✅ Scrolled ${effectiveAction.swipeDirection.lowercase()}"
            "WEB_SEARCH" -> "✅ Opened web search in Chrome"
            else -> if (success) "✅ Step completed" else "⚠️ Step could not complete"
        }
        host.updateStepMonitorBanner(resultBanner)

        val historyStepTitle = when (effectiveAction.actionType.uppercase()) {
            "OPEN_APP" -> if (success) {
                when {
                    autoOpenedContactName != null ->
                        "Opened ${effectiveAction.targetLabel.ifBlank { "App" }} & tapped '$autoOpenedContactName'"
                    openedSearchAwaitingTapFor != null ->
                        "Opened WhatsApp search for '$openedSearchAwaitingTapFor'"
                    else ->
                        "Opened ${effectiveAction.targetLabel.ifBlank { verifiedAppTitle.substringBefore(" (") }}"
                }
            } else {
                "Tried opening ${effectiveAction.targetLabel}"
            }
            "TAP_ELEMENT" -> when {
                success -> "Tapped '${effectiveAction.targetLabel}'"
                openedSearchAwaitingTapFor != null ->
                    "Opened WhatsApp search for '$openedSearchAwaitingTapFor'"
                else -> "Could not find '${effectiveAction.targetLabel}' on screen"
            }
            "TYPE_TEXT" -> if (success) {
                "Typed \"${effectiveAction.textToType.take(36)}\""
            } else {
                "Waiting for chat conversation to open before typing"
            }
            "SWIPE_SCREEN" -> "Scrolled ${effectiveAction.swipeDirection.lowercase()} on screen"
            "WEB_SEARCH" -> "Searched Chrome for \"${effectiveAction.textToType.ifBlank { effectiveAction.targetLabel }.take(34)}\""
            "SYSTEM_NAV" -> "Pressed system ${effectiveAction.systemNavAction.lowercase()} button"
            else -> resultBanner.removePrefix("✅ ").removePrefix("⚠️ ")
        }
        val historyDetail = buildString {
            if (effectiveAction.spokenExplanation.isNotBlank()) {
                append(effectiveAction.spokenExplanation.trim())
                append(" • ")
            }
            append("Verified on ${verifiedAppTitle.substringBefore(" (")}")
            if (chatOpen) {
                append(" (Chat Open)")
            }
        }
        host.recordCompletedSessionStep(
            actionType = effectiveAction.actionType.uppercase(),
            title = historyStepTitle,
            detail = historyDetail,
            appName = verifiedAppTitle,
            statusIcon = when {
                openedSearchAwaitingTapFor != null -> "🔍"
                success -> "✅"
                else -> "⚠️"
            },
            succeeded = success || openedSearchAwaitingTapFor != null
        )

        return VerifiedActionOutcome(
            success = success || openedSearchAwaitingTapFor != null,
            awaitingUserConfirmation = host.pendingConfirmationAction != null,
            awaitingUserTapOrHelp = openedSearchAwaitingTapFor != null,
            actionSummary = resultBanner,
            verifiedAppTitle = verifiedAppTitle,
            isChatConversationOpen = chatOpen,
            visibleElementsSummary = updatedSummary,
            spokenConfirmations = _state.value.spokenConfirmationsInCurrentChain
        )
    }
}
