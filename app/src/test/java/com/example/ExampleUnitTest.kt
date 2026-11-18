package com.example

import android.content.Context
import android.graphics.Rect
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AssistiveScreenAction
import com.example.ai.GeminiLiveManager
import com.example.ai.GuideSessionCoordinator
import com.example.ai.InterruptController
import com.example.ai.InterruptDecision
import com.example.ai.KindGuideAiService
import com.example.ai.LivePhase
import com.example.ai.OverlayScreenSnapshot
import com.example.ai.TaskExecutionHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {

    @Before
    fun setUp() {
        TaskExecutionHandler.registerCustomTtsStepSpeaker(null)
        TaskExecutionHandler.attachTextToSpeechEngine(null) { false }
        TaskExecutionHandler.reset()
        InterruptController.reset()
        GuideSessionCoordinator.startNewConversation()
        GuideSessionCoordinator.setAccessibilityConnected(true)
        GuideSessionCoordinator.setKindGuideActivityResumed(false)
    }

    @After
    fun tearDown() {
        GuideSessionCoordinator.customSpeechSink = null
        TaskExecutionHandler.preferLiveNativeVoiceProvider = { GeminiLiveManager.state.value.isActive }
        TaskExecutionHandler.registerCustomTtsStepSpeaker(null)
        TaskExecutionHandler.attachTextToSpeechEngine(null) { false }
        TaskExecutionHandler.reset()
        InterruptController.reset()
    }

    @Test
    fun chatComposerDetection_rejectsWhatsAppMainChatsList_andAcceptsOpenChat() {
        // 1. Simulate WhatsApp main Chats list ("Ask Meta AI or Search", "Amardeep Sharma", "Chats", "Updates", "Calls")
        GuideSessionCoordinator.updateLiveScreenNodes(
            packageName = "com.whatsapp",
            boundsByLabel = mapOf(
                "Ask Meta AI or Search" to Rect(40, 140, 1000, 240),
                "Amardeep Sharma" to Rect(40, 320, 1000, 480),
                "New chat" to Rect(860, 1900, 1020, 2060),
                "Chats" to Rect(50, 2100, 250, 2240),
                "Updates" to Rect(300, 2100, 500, 2240),
                "Communities" to Rect(550, 2100, 800, 2240),
                "Calls" to Rect(820, 2100, 1020, 2240)
            )
        )
        assertFalse(
            "Main WhatsApp Chats list must NOT be treated as an open chat conversation",
            GuideSessionCoordinator.isChatComposerVisibleOnScreen()
        )

        // 2. Simulate opened chat conversation with Amardeep Sharma ("Message", "Voice message", "Attach")
        GuideSessionCoordinator.updateLiveScreenNodes(
            packageName = "com.whatsapp",
            boundsByLabel = mapOf(
                "Amardeep Sharma" to Rect(140, 80, 620, 160),
                "Video call" to Rect(760, 80, 860, 160),
                "Voice call" to Rect(880, 80, 980, 160),
                "Message" to Rect(120, 2040, 860, 2160),
                "Attach" to Rect(740, 2040, 840, 2160),
                "Voice message" to Rect(900, 2040, 1020, 2160)
            )
        )
        assertTrue(
            "Open WhatsApp conversation with bottom Message input must be detected as open chat",
            GuideSessionCoordinator.isChatComposerVisibleOnScreen()
        )
    }

    @Test
    fun multiStepWhatsAppFlow_autoChainsOpenAppToContact_andRecordsTaskHistory() = runBlocking {
        val executedActions = mutableListOf<AssistiveScreenAction>()
        var currentPackage = "com.aistudio.kindguide.kgrxzm"
        var currentNodes = mapOf<String, Rect>()

        GuideSessionCoordinator.registerScreenActionExecutor { action ->
            executedActions.add(action)
            when (action.actionType) {
                "OPEN_APP" -> {
                    currentPackage = "com.whatsapp"
                    currentNodes = mapOf(
                        "Ask Meta AI or Search" to Rect(40, 140, 1000, 240),
                        "Amardeep Sharma" to Rect(40, 320, 1000, 480),
                        "Chats" to Rect(50, 2100, 250, 2240),
                        "Updates" to Rect(300, 2100, 500, 2240)
                    )
                    true
                }
                "TAP_ELEMENT" -> {
                    if (action.targetLabel.contains("Amardeep", ignoreCase = true)) {
                        currentPackage = "com.whatsapp"
                        currentNodes = mapOf(
                            "Amardeep Sharma" to Rect(140, 80, 620, 160),
                            "Voice call" to Rect(880, 80, 980, 160),
                            "Message" to Rect(120, 2040, 860, 2160),
                            "Send" to Rect(900, 2040, 1020, 2160)
                        )
                        true
                    } else {
                        true
                    }
                }
                "TYPE_TEXT" -> true
                else -> true
            }
        }
        GuideSessionCoordinator.registerScreenSnapshotRequester {
            OverlayScreenSnapshot(
                packageName = currentPackage,
                visibleLabels = currentNodes.keys.toList(),
                nodeBoundsByLabel = currentNodes,
                screenshot = null
            )
        }

        // User asks: "Open WhatsApp and call to Amardeep Sharma"
        GuideSessionCoordinator.noteUserTaskIntent("Open WhatsApp and call to Amardeep Sharma")
        assertEquals("Amardeep Sharma", GuideSessionCoordinator.lastRequestedContactName)

        // Step 1: AI calls OPEN_APP("WhatsApp") -> Coordinator auto-chains TAP_ELEMENT("Amardeep Sharma")
        val openOutcome = GuideSessionCoordinator.executeAndVerifyScreenActionSuspend(
            AssistiveScreenAction(
                actionType = "OPEN_APP",
                targetLabel = "WhatsApp",
                expectedAppName = "WhatsApp",
                spokenExplanation = "Opening WhatsApp"
            )
        )
        assertTrue(openOutcome.success)
        assertTrue("Chat should now be open after auto-chaining contact tap", openOutcome.isChatConversationOpen)
        assertEquals(2, executedActions.size)
        assertEquals("OPEN_APP", executedActions[0].actionType)
        assertEquals("TAP_ELEMENT", executedActions[1].actionType)
        assertEquals("Amardeep Sharma", executedActions[1].targetLabel)

        // Step 2: AI types "Have you taken food?" -> Coordinator automatically sets up Send Confirmation
        val typeOutcome = GuideSessionCoordinator.executeAndVerifyScreenActionSuspend(
            AssistiveScreenAction(
                actionType = "TYPE_TEXT",
                targetLabel = "Message",
                textToType = "Have you taken food?",
                submitAfterType = false,
                expectedAppName = "WhatsApp",
                spokenExplanation = "Typing your message"
            )
        )
        assertTrue(typeOutcome.success)
        assertTrue("Must pause for Send confirmation after typing chat message", typeOutcome.awaitingUserConfirmation)
        assertNotNull(GuideSessionCoordinator.pendingConfirmationAction.value)

        // Verify Task History recorded the steps
        val history = GuideSessionCoordinator.sessionTaskHistory.value
        assertTrue("Task History should contain completed steps", history.size >= 2)
    }

    @Test
    fun spokenCommitmentFallback_triggersQuickSosWhenNarrated() {
        var sosTriggeredCount = 0
        GuideSessionCoordinator.registerQuickSosTrigger {
            sosTriggeredCount += 1
        }

        GuideSessionCoordinator.fulfillSpokenCommitmentIfNoToolCalled(
            userText = "Send Quick SOS",
            aiText = "I'm sending a Quick SOS alert now."
        )
        assertEquals(1, sosTriggeredCount)
    }

    @Test
    fun voiceCallIntent_autoChainsToVoiceCallConfirmation() = runBlocking {
        val executedActions = mutableListOf<AssistiveScreenAction>()
        var currentPackage = "com.aistudio.kindguide.kgrxzm"
        var currentNodes = mapOf<String, Rect>()

        GuideSessionCoordinator.registerScreenActionExecutor { action ->
            executedActions.add(action)
            when (action.actionType) {
                "OPEN_APP" -> {
                    currentPackage = "com.whatsapp"
                    currentNodes = mapOf(
                        "Ask Meta AI or Search" to Rect(40, 140, 1000, 240),
                        "Amardeep Sharma" to Rect(40, 320, 1000, 480),
                        "Chats" to Rect(50, 2100, 250, 2240)
                    )
                    true
                }
                "TAP_ELEMENT" -> {
                    currentPackage = "com.whatsapp"
                    currentNodes = mapOf(
                        "Amardeep Sharma" to Rect(140, 80, 620, 160),
                        "Voice call" to Rect(880, 80, 980, 160),
                        "Video call" to Rect(760, 80, 860, 160),
                        "Message" to Rect(120, 2040, 860, 2160)
                    )
                    true
                }
                else -> true
            }
        }
        GuideSessionCoordinator.registerScreenSnapshotRequester {
            OverlayScreenSnapshot(
                packageName = currentPackage,
                visibleLabels = currentNodes.keys.toList(),
                nodeBoundsByLabel = currentNodes,
                screenshot = null
            )
        }

        GuideSessionCoordinator.noteUserTaskIntent("Open WhatsApp and call to Amardeep Sharma")
        assertEquals("Amardeep Sharma", GuideSessionCoordinator.lastRequestedContactName)
        assertEquals("voice_call", GuideSessionCoordinator.pendingSubGoal)

        val outcome = GuideSessionCoordinator.executeAndVerifyScreenActionSuspend(
            AssistiveScreenAction(
                actionType = "OPEN_APP",
                targetLabel = "WhatsApp",
                expectedAppName = "WhatsApp"
            )
        )
        assertTrue(outcome.success)
        assertTrue("Voice call should pause for confirmation on the Voice call button", outcome.awaitingUserConfirmation)
        assertEquals("Voice call", GuideSessionCoordinator.pendingConfirmationAction.value?.targetLabel)
    }

    @Test
    fun preProvidedMessageIntent_autoChainsOpenTapAndType_toSendConfirmation() = runBlocking {
        val executedActions = mutableListOf<AssistiveScreenAction>()
        var currentPackage = "com.aistudio.kindguide.kgrxzm"
        var currentNodes = mapOf<String, Rect>()

        GuideSessionCoordinator.registerScreenActionExecutor { action ->
            executedActions.add(action)
            when (action.actionType) {
                "OPEN_APP" -> {
                    currentPackage = "com.whatsapp"
                    currentNodes = mapOf(
                        "Ask Meta AI or Search" to Rect(40, 140, 1000, 240),
                        "Amardeep Sharma" to Rect(40, 320, 1000, 480),
                        "Chats" to Rect(50, 2100, 250, 2240)
                    )
                    true
                }
                "TAP_ELEMENT" -> {
                    currentPackage = "com.whatsapp"
                    currentNodes = mapOf(
                        "Amardeep Sharma" to Rect(140, 80, 620, 160),
                        "Message" to Rect(120, 2040, 860, 2160),
                        "Send" to Rect(900, 2040, 1020, 2160)
                    )
                    true
                }
                "TYPE_TEXT" -> true
                else -> true
            }
        }
        GuideSessionCoordinator.registerScreenSnapshotRequester {
            OverlayScreenSnapshot(
                packageName = currentPackage,
                visibleLabels = currentNodes.keys.toList(),
                nodeBoundsByLabel = currentNodes,
                screenshot = null
            )
        }

        GuideSessionCoordinator.noteUserTaskIntent(
            "Open WhatsApp and send a message to Amardeep Sharma asking have you taken food?"
        )
        assertEquals("Amardeep Sharma", GuideSessionCoordinator.lastRequestedContactName)
        assertEquals("Have you taken food?", GuideSessionCoordinator.pendingMessageText)

        val outcome = GuideSessionCoordinator.executeAndVerifyScreenActionSuspend(
            AssistiveScreenAction(
                actionType = "OPEN_APP",
                targetLabel = "WhatsApp",
                expectedAppName = "WhatsApp"
            )
        )
        assertTrue(outcome.success)
        assertEquals(3, executedActions.size)
        assertEquals("OPEN_APP", executedActions[0].actionType)
        assertEquals("TAP_ELEMENT", executedActions[1].actionType)
        assertEquals("TYPE_TEXT", executedActions[2].actionType)
        assertEquals("Have you taken food?", executedActions[2].textToType)
        assertTrue(outcome.awaitingUserConfirmation)
        assertEquals("Send", GuideSessionCoordinator.pendingConfirmationAction.value?.targetLabel)
    }

    @Test
    fun missingContactInWhatsApp_opensSearchAwaitingUserTap_andNeverLoopsOnSubsequentTurns() = runBlocking {
        val executedActions = mutableListOf<AssistiveScreenAction>()
        var currentPackage = "com.aistudio.kindguide.kgrxzm"
        var currentNodes = mapOf<String, Rect>()

        GuideSessionCoordinator.registerScreenActionExecutor { action ->
            executedActions.add(action)
            when (action.actionType) {
                "OPEN_APP" -> {
                    currentPackage = "com.whatsapp"
                    currentNodes = mapOf(
                        "Ask Meta AI or Search" to Rect(40, 140, 1000, 240),
                        "Other Contact" to Rect(40, 320, 1000, 480),
                        "Chats" to Rect(50, 2100, 250, 2240)
                    )
                    true
                }
                "TYPE_TEXT" -> {
                    // Simulate search opened, but Amardeep Sharma does not match automatically
                    currentNodes = mapOf(
                        "Search" to Rect(40, 140, 1000, 240),
                        "No results yet" to Rect(40, 320, 1000, 420)
                    )
                    true
                }
                "TAP_ELEMENT" -> false // Cannot auto-tap contact
                else -> false
            }
        }
        GuideSessionCoordinator.registerScreenSnapshotRequester {
            OverlayScreenSnapshot(
                packageName = currentPackage,
                visibleLabels = currentNodes.keys.toList(),
                nodeBoundsByLabel = currentNodes,
                screenshot = null
            )
        }

        GuideSessionCoordinator.noteUserTaskIntent("Send a message to Amardeep Sharma on WhatsApp")
        assertEquals("Amardeep Sharma", GuideSessionCoordinator.lastRequestedContactName)

        val firstOutcome = GuideSessionCoordinator.executeAndVerifyScreenActionSuspend(
            AssistiveScreenAction(
                actionType = "OPEN_APP",
                targetLabel = "WhatsApp",
                expectedAppName = "WhatsApp"
            )
        )
        assertTrue(firstOutcome.awaitingUserTapOrHelp)
        assertTrue(GuideSessionCoordinator.isTaskAwaitingUserTap)
        assertTrue(GuideSessionCoordinator.pausedTaskDescription.contains("Amardeep Sharma"))
        val countAfterFirst = executedActions.size
        val historyCountAfterFirst = GuideSessionCoordinator.sessionTaskHistory.value.size

        // Subsequent fallback calls with AI failure/ask narration must NEVER re-launch WhatsApp or add steps
        GuideSessionCoordinator.fulfillSpokenCommitmentIfNoToolCalled(
            userText = "Hello",
            aiText = "I couldn't find Amardeep Sharma automatically. Please tap their chat on the screen."
        )
        assertEquals(countAfterFirst, executedActions.size)
        assertEquals(historyCountAfterFirst, GuideSessionCoordinator.sessionTaskHistory.value.size)

        // Repeated OPEN_APP("WhatsApp") while already in WhatsApp awaiting user tap must be a safe no-op
        val secondOutcome = GuideSessionCoordinator.executeAndVerifyScreenActionSuspend(
            AssistiveScreenAction(
                actionType = "OPEN_APP",
                targetLabel = "WhatsApp",
                expectedAppName = "WhatsApp"
            )
        )
        assertTrue(secondOutcome.awaitingUserTapOrHelp)
        assertEquals(countAfterFirst, executedActions.size)
        assertEquals(historyCountAfterFirst, GuideSessionCoordinator.sessionTaskHistory.value.size)
    }

    @Test
    fun taskSwitchAndExplicitCancel_clearStaleContactIntentProperly() {
        GuideSessionCoordinator.noteUserTaskIntent("Open WhatsApp and message Amardeep Sharma")
        assertEquals("Amardeep Sharma", GuideSessionCoordinator.lastRequestedContactName)

        // Switching to an unrelated app clears stale contact/message intent while avoiding loop
        GuideSessionCoordinator.noteUserTaskIntent("Open Chrome and check weather")
        assertEquals("", GuideSessionCoordinator.lastRequestedContactName)
        assertEquals("", GuideSessionCoordinator.pendingSubGoal)
        assertEquals("", GuideSessionCoordinator.pendingMessageText)

        // Explicit cancel resets everything including paused task description
        GuideSessionCoordinator.noteUserTaskIntent("Send a message to Amardeep Sharma on WhatsApp")
        assertEquals("Amardeep Sharma", GuideSessionCoordinator.lastRequestedContactName)
        GuideSessionCoordinator.noteUserTaskIntent("Leave that task and open YouTube")
        assertEquals("", GuideSessionCoordinator.lastRequestedContactName)
        assertEquals("", GuideSessionCoordinator.pausedTaskDescription)
        assertFalse(GuideSessionCoordinator.isTaskAwaitingUserTap)
    }

    @Test
    fun interruptController_abortsMidTaskPivot_andPreventsAbandonedSubSteps() = runBlocking {
        val executedActions = mutableListOf<AssistiveScreenAction>()
        val openAppStarted = CompletableDeferred<Unit>()
        val allowOpenAppToReturn = CompletableDeferred<Unit>()
        var currentPackage = "com.aistudio.kindguide.kgrxzm"
        var currentNodes = mapOf<String, Rect>()

        GuideSessionCoordinator.registerScreenActionExecutor { action ->
            executedActions.add(action)
            when (action.actionType) {
                "OPEN_APP" -> {
                    currentPackage = "com.whatsapp"
                    currentNodes = mapOf(
                        "Ask Meta AI or Search" to Rect(40, 140, 1000, 240),
                        "Amardeep Sharma" to Rect(40, 320, 1000, 480),
                        "Chats" to Rect(50, 2100, 250, 2240)
                    )
                    openAppStarted.complete(Unit)
                    allowOpenAppToReturn.await()
                    true
                }
                else -> true
            }
        }
        GuideSessionCoordinator.registerScreenSnapshotRequester {
            OverlayScreenSnapshot(
                packageName = currentPackage,
                visibleLabels = currentNodes.keys.toList(),
                nodeBoundsByLabel = currentNodes,
                screenshot = null
            )
        }

        GuideSessionCoordinator.noteUserTaskIntent(
            "Open WhatsApp and send a message to Amardeep Sharma asking have you taken food?"
        )
        val initialHistorySize = GuideSessionCoordinator.sessionTaskHistory.value.size

        // Start Step 1 (OPEN_APP WhatsApp) which would normally auto-chain TAP_ELEMENT + TYPE_TEXT
        val deferredOutcome = async {
            GuideSessionCoordinator.executeAndVerifyScreenActionSuspend(
                AssistiveScreenAction(
                    actionType = "OPEN_APP",
                    targetLabel = "WhatsApp",
                    expectedAppName = "WhatsApp"
                )
            )
        }

        openAppStarted.await()
        assertTrue(InterruptController.hasActiveTask())

        // User interrupts mid-execution with a new command!
        val decision = InterruptController.onUserInputObserved(
            rawInput = "Leave that and open YouTube instead",
            isFinal = true,
            hasPendingConfirmation = false
        )
        assertEquals(InterruptDecision.ABORT_AND_PIVOT, decision)
        allowOpenAppToReturn.complete(Unit)

        val outcome = deferredOutcome.await()
        assertTrue("Interrupted task must return wasAborted = true", outcome.wasAborted)
        assertFalse(outcome.success)
        assertEquals(
            "Only OPEN_APP started before interrupt; abandoned TAP_ELEMENT and TYPE_TEXT must never execute",
            1,
            executedActions.size
        )
        assertNull(GuideSessionCoordinator.pendingConfirmationAction.value)
        assertEquals("", GuideSessionCoordinator.lastRequestedContactName)
        assertEquals("", GuideSessionCoordinator.pendingMessageText)
        assertEquals(
            "Aborted action must not pollute Task History",
            initialHistorySize,
            GuideSessionCoordinator.sessionTaskHistory.value.size
        )
        assertEquals("open YouTube instead", InterruptController.state.value.pivotCommand)
    }

    @Test
    fun interruptController_respectsPendingConfirmation_andIgnoresFillerNoise() {
        val token = InterruptController.beginExecution("confirm:Send")
        try {
            // Single filler noise word must NOT abort running task
            assertEquals(
                InterruptDecision.NONE,
                InterruptController.onUserInputObserved("um", isFinal = false, hasPendingConfirmation = false)
            )
            assertFalse(InterruptController.isAborted(token))

            // Saying "Yes, send it" while a Send confirmation is pending must NOT abort
            assertEquals(
                InterruptDecision.NONE,
                InterruptController.onUserInputObserved("Yes, send it", isFinal = true, hasPendingConfirmation = true)
            )
            assertFalse(InterruptController.isAborted(token))

            // Explicit stop command immediately aborts
            assertEquals(
                InterruptDecision.ABORT_ONLY,
                InterruptController.onUserInputObserved("Stop that", isFinal = true, hasPendingConfirmation = true)
            )
            assertTrue(InterruptController.isAborted(token))
        } finally {
            InterruptController.finishExecution(token)
        }
    }

    @Test
    fun interruptController_triggerManualInterrupt_cancelsInterruptibleCoroutineJobImmediately() = runBlocking {
        val jobStarted = CompletableDeferred<Unit>()
        var reachedPostDelayStep = false

        val job = InterruptController.launchInterruptibleTask(this, "long:multi-step") {
            jobStarted.complete(Unit)
            delay(5_000L)
            reachedPostDelayStep = true
        }

        jobStarted.await()
        assertTrue(InterruptController.state.value.isTaskExecuting)

        InterruptController.triggerManualInterrupt("Senior tapped Stop")
        job.join()

        assertTrue(job.isCancelled)
        assertFalse("Post-interrupt sub-step must never execute", reachedPostDelayStep)
        assertFalse(InterruptController.state.value.isTaskExecuting)
        assertEquals("long:multi-step", InterruptController.state.value.lastInterruptedTaskLabel)
    }

    @Test
    fun taskExecutionHandler_speaksTtsConfirmationAfterEachCompletedStep_beforeNextAutomatedOperation() = runBlocking {
        val timeline = mutableListOf<String>()
        var currentPackage = "com.aistudio.kindguide.kgrxzm"
        var currentNodes = mapOf<String, Rect>()

        TaskExecutionHandler.registerCustomTtsStepSpeaker { _, spokenText ->
            timeline.add("TTS_START:$spokenText")
            delay(15L)
            timeline.add("TTS_DONE:$spokenText")
            true
        }

        GuideSessionCoordinator.registerScreenActionExecutor { action ->
            timeline.add("ACTION:${action.actionType}:${action.targetLabel}")
            when (action.actionType) {
                "OPEN_APP" -> {
                    currentPackage = "com.whatsapp"
                    currentNodes = mapOf(
                        "Ask Meta AI or Search" to Rect(40, 140, 1000, 240),
                        "Amardeep Sharma" to Rect(40, 320, 1000, 480),
                        "Chats" to Rect(50, 2100, 250, 2240)
                    )
                    true
                }
                "TAP_ELEMENT" -> {
                    currentPackage = "com.whatsapp"
                    currentNodes = mapOf(
                        "Amardeep Sharma" to Rect(140, 80, 620, 160),
                        "Message" to Rect(120, 2040, 860, 2160),
                        "Send" to Rect(900, 2040, 1020, 2160)
                    )
                    true
                }
                "TYPE_TEXT" -> true
                else -> true
            }
        }
        GuideSessionCoordinator.registerScreenSnapshotRequester {
            OverlayScreenSnapshot(
                packageName = currentPackage,
                visibleLabels = currentNodes.keys.toList(),
                nodeBoundsByLabel = currentNodes,
                screenshot = null
            )
        }

        GuideSessionCoordinator.noteUserTaskIntent(
            "Open WhatsApp and send a message to Amardeep Sharma asking have you taken food?"
        )

        val outcome = GuideSessionCoordinator.executeAndVerifyScreenActionSuspend(
            AssistiveScreenAction(
                actionType = "OPEN_APP",
                targetLabel = "WhatsApp",
                expectedAppName = "WhatsApp"
            )
        )

        assertTrue(outcome.success)
        assertEquals(3, outcome.spokenConfirmations.size)
        assertEquals(
            listOf(
                "ACTION:OPEN_APP:WhatsApp",
                "TTS_START:Opened WhatsApp.",
                "TTS_DONE:Opened WhatsApp.",
                "ACTION:TAP_ELEMENT:Amardeep Sharma",
                "TTS_START:Opened Amardeep Sharma's chat.",
                "TTS_DONE:Opened Amardeep Sharma's chat.",
                "ACTION:TYPE_TEXT:Message",
                "TTS_START:I typed 'Have you taken food?'. Tap Send Now or say yes to send.",
                "TTS_DONE:I typed 'Have you taken food?'. Tap Send Now or say yes to send."
            ),
            timeline
        )
    }

    @Test
    fun taskExecutionHandler_explicitlyInvokesAndroidTextToSpeechEngine_andWaitsForUtteranceDone() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val realTts = TextToSpeech(context) {}
        TaskExecutionHandler.attachTextToSpeechEngine(realTts) { true }

        GuideSessionCoordinator.registerScreenActionExecutor { true }
        GuideSessionCoordinator.registerScreenSnapshotRequester {
            OverlayScreenSnapshot(
                packageName = "com.whatsapp",
                visibleLabels = listOf("Chats"),
                nodeBoundsByLabel = mapOf("Chats" to Rect(0, 0, 100, 100)),
                screenshot = null
            )
        }

        val deferredOutcome = async {
            GuideSessionCoordinator.executeAndVerifyScreenActionSuspend(
                AssistiveScreenAction(
                    actionType = "SWIPE_SCREEN",
                    swipeDirection = "DOWN"
                )
            )
        }

        // Wait briefly until TaskExecutionHandler invokes Android TextToSpeech.speak(...)
        val shadowTts = Shadows.shadowOf(realTts)
        var attempts = 0
        while (shadowTts.lastSpokenText == null && attempts < 40) {
            delay(20L)
            attempts += 1
        }
        assertEquals("Scrolled down.", shadowTts.lastSpokenText)
        assertFalse(
            "TaskExecutionHandler must stay suspended until TTS utterance finishes",
            deferredOutcome.isCompleted
        )
        assertTrue(TaskExecutionHandler.isSpeakingConfirmation())

        // Simulate UtteranceProgressListener.onDone(...) firing from the Android TTS engine
        TaskExecutionHandler.onUtteranceFinished(null, succeeded = true)

        val outcome = deferredOutcome.await()
        assertTrue(outcome.success)
        assertFalse(TaskExecutionHandler.isSpeakingConfirmation())
        assertEquals(listOf("Scrolled down."), outcome.spokenConfirmations)
        realTts.shutdown()
    }

    @Test
    fun taskExecutionHandler_abortDuringStepTtsConfirmation_preventsNextAutomatedOperation() = runBlocking {
        val executedActions = mutableListOf<AssistiveScreenAction>()
        val ttsStep1Started = CompletableDeferred<Unit>()
        val releaseTtsStep1 = CompletableDeferred<Unit>()
        var currentPackage = "com.aistudio.kindguide.kgrxzm"
        var currentNodes = mapOf<String, Rect>()

        TaskExecutionHandler.registerCustomTtsStepSpeaker { _, _ ->
            ttsStep1Started.complete(Unit)
            releaseTtsStep1.await()
            true
        }

        GuideSessionCoordinator.registerScreenActionExecutor { action ->
            executedActions.add(action)
            if (action.actionType == "OPEN_APP") {
                currentPackage = "com.whatsapp"
                currentNodes = mapOf(
                    "Ask Meta AI or Search" to Rect(40, 140, 1000, 240),
                    "Amardeep Sharma" to Rect(40, 320, 1000, 480)
                )
            }
            true
        }
        GuideSessionCoordinator.registerScreenSnapshotRequester {
            OverlayScreenSnapshot(
                packageName = currentPackage,
                visibleLabels = currentNodes.keys.toList(),
                nodeBoundsByLabel = currentNodes,
                screenshot = null
            )
        }

        GuideSessionCoordinator.noteUserTaskIntent("Open WhatsApp and call Amardeep Sharma")
        val deferredOutcome = async {
            GuideSessionCoordinator.executeAndVerifyScreenActionSuspend(
                AssistiveScreenAction(
                    actionType = "OPEN_APP",
                    targetLabel = "WhatsApp",
                    expectedAppName = "WhatsApp"
                )
            )
        }

        // Wait until Step 1 completes and TTS confirmation is actively speaking
        ttsStep1Started.await()
        val decision = InterruptController.onUserInputObserved("Stop that", isFinal = true)
        assertEquals(InterruptDecision.ABORT_ONLY, decision)

        val outcome = deferredOutcome.await()
        assertTrue("Task aborted during TTS confirmation must return wasAborted = true", outcome.wasAborted)
        assertEquals(
            "Step 2 (TAP_ELEMENT Amardeep Sharma) must NOT execute when aborted during Step 1 TTS confirmation",
            1,
            executedActions.size
        )
    }

    @Test
    fun geminiLiveManager_appendTranscriptionPiece_preservesSubwordTokens_andAddsPunctuationSpacing() {
        val sb1 = StringBuilder()
        listOf("Hel", "lo", " Amar", "deep", " Shar", "ma!").forEach {
            GeminiLiveManager.appendTranscriptionPiece(sb1, it)
        }
        assertEquals("Hello Amardeep Sharma!", sb1.toString())

        val sb2 = StringBuilder()
        listOf("I opened WhatsApp.", "What should we write?").forEach {
            GeminiLiveManager.appendTranscriptionPiece(sb2, it)
        }
        assertEquals("I opened WhatsApp. What should we write?", sb2.toString())
    }

    @Test
    fun guideSessionCoordinator_streamedSpeechChunks_speaksProgressively_andFlushesFinalTail() {
        val spokenEvents = mutableListOf<Triple<String, String, Boolean>>()
        GuideSessionCoordinator.customSpeechSink = { msgId, text, queueAdd ->
            spokenEvents.add(Triple(msgId, text, queueAdd))
        }

        val streamSteps = listOf(
            "Good morning! ",
            "Good morning! Let's take a ",
            "Good morning! Let's take a nice deep breath together. ",
            "Good morning! Let's take a nice deep breath together. Then drink some water"
        )
        var spokenIdx = 0
        var queuedAny = false
        for (partial in streamSteps) {
            val (ready, nextIdx) = GuideSessionCoordinator.extractReadySpeechSentences(
                fullText = partial,
                spokenUpToIndex = spokenIdx,
                flushRemaining = false
            )
            spokenIdx = nextIdx
            ready.forEach { sentence ->
                GuideSessionCoordinator.speakStreamedChunk(
                    text = sentence,
                    messageId = "companion_stream",
                    isFirstChunk = !queuedAny
                )
                queuedAny = true
            }
        }
        assertEquals(2, spokenEvents.size)
        assertEquals("Let's take a nice deep breath together.", spokenEvents[1].second)
        assertFalse("First sentence should flush queue", spokenEvents[0].third)
        assertTrue("Second sentence should append to queue", spokenEvents[1].third)

        // Flush remaining tail at end of response
        val (tail, _) = GuideSessionCoordinator.extractReadySpeechSentences(
            fullText = streamSteps.last(),
            spokenUpToIndex = spokenIdx,
            flushRemaining = true
        )
        tail.forEach { chunk ->
            GuideSessionCoordinator.speakStreamedChunk(
                text = chunk,
                messageId = "companion_stream",
                isFirstChunk = !queuedAny
            )
            queuedAny = true
        }
        assertEquals(3, spokenEvents.size)
        assertEquals("Then drink some water", spokenEvents[2].second)
        assertTrue(spokenEvents[2].third)

        // Verify direct local TTS (used for Live errors and text-only turns) works cleanly
        GuideSessionCoordinator.speakLocalDirectly("Live voice connection ended.", messageId = "live_error")
        assertEquals(4, spokenEvents.size)
        assertEquals("live_error", spokenEvents[3].first)
        assertEquals("Live voice connection ended.", spokenEvents[3].second)
    }

    @Test
    fun kindGuideAiService_streamingJsonParsers_extractPartialScamAndStoryPreviews() {
        val partialScamJson = "{\"verdict\":\"Likely Scam\",\"headline\":\"Fake bank urgent link\",\"explanation\":\"Real banks never ask for PINs"
        val scamPreview = KindGuideAiService.buildScamStreamPreview(partialScamJson)
        assertTrue(scamPreview.contains("Likely Scam"))
        assertTrue(scamPreview.contains("Fake bank urgent link"))
        assertTrue(scamPreview.contains("Real banks never ask for PINs"))

        val partialStoryJson = "{\"storyTitle\":\"Sunday Porch Tea\",\"storyBody\":\"Warm sunlight spilled across\\nwooden chairs"
        assertEquals("Sunday Porch Tea", KindGuideAiService.extractPartialJsonStringField(partialStoryJson, "storyTitle"))
        assertEquals("Warm sunlight spilled across\nwooden chairs", KindGuideAiService.extractPartialJsonStringField(partialStoryJson, "storyBody"))
    }

    @Test
    fun taskExecutionHandler_bypassesLocalTtsWhenLiveNativeVoiceActive_withoutBlockingActions() = runBlocking {
        var localTtsCalls = 0
        var currentPackage = "com.aistudio.kindguide.kgrxzm"
        var currentNodes = mapOf<String, Rect>()

        TaskExecutionHandler.registerCustomTtsStepSpeaker { _, _ ->
            localTtsCalls += 1
            delay(500L)
            true
        }
        val prevProvider = TaskExecutionHandler.preferLiveNativeVoiceProvider
        TaskExecutionHandler.preferLiveNativeVoiceProvider = { true }

        try {
            GuideSessionCoordinator.registerScreenActionExecutor { action ->
                when (action.actionType) {
                    "OPEN_APP" -> {
                        currentPackage = "com.whatsapp"
                        currentNodes = mapOf(
                            "Ask Meta AI or Search" to Rect(40, 140, 1000, 240),
                            "Amardeep Sharma" to Rect(40, 320, 1000, 480),
                            "Chats" to Rect(50, 2100, 250, 2240)
                        )
                        true
                    }
                    "TAP_ELEMENT" -> {
                        currentPackage = "com.whatsapp"
                        currentNodes = mapOf(
                            "Amardeep Sharma" to Rect(140, 80, 620, 160),
                            "Message" to Rect(120, 2040, 860, 2160),
                            "Send" to Rect(900, 2040, 1020, 2160)
                        )
                        true
                    }
                    else -> true
                }
            }
            GuideSessionCoordinator.registerScreenSnapshotRequester {
                OverlayScreenSnapshot(
                    packageName = currentPackage,
                    visibleLabels = currentNodes.keys.toList(),
                    nodeBoundsByLabel = currentNodes,
                    screenshot = null
                )
            }

            GuideSessionCoordinator.noteUserTaskIntent(
                "Open WhatsApp and send a message to Amardeep Sharma asking have you taken food?"
            )
            val outcome = GuideSessionCoordinator.executeAndVerifyScreenActionSuspend(
                AssistiveScreenAction(
                    actionType = "OPEN_APP",
                    targetLabel = "WhatsApp",
                    expectedAppName = "WhatsApp"
                )
            )

            assertTrue(outcome.success)
            assertTrue(outcome.awaitingUserConfirmation)
            assertEquals(
                "Live mode must skip blocking local TTS so Gemini Live speaks natively",
                0,
                localTtsCalls
            )
            assertTrue(outcome.spokenConfirmations.isEmpty())
            assertFalse(TaskExecutionHandler.isSpeakingConfirmation())
            assertTrue(GuideSessionCoordinator.sessionTaskHistory.value.isNotEmpty())
        } finally {
            TaskExecutionHandler.preferLiveNativeVoiceProvider = prevProvider
        }
    }

    @Test
    fun geminiLiveManager_bargeInClassifier_acceptsInstantInterruptKeywords_andFiltersEcho() {
        val currentAi = "I've opened Amardeep Sharma's chat on WhatsApp now."
        assertTrue(GeminiLiveManager.isGenuineUserInterruption("stop", currentAi))
        assertTrue(GeminiLiveManager.isGenuineUserInterruption("wait", currentAi))
        assertTrue(GeminiLiveManager.isGenuineUserInterruption("no cancel", currentAi))
        assertTrue(GeminiLiveManager.isGenuineUserInterruption("open YouTube", currentAi))

        assertFalse(GeminiLiveManager.isGenuineUserInterruption("uh", currentAi))
        assertFalse(GeminiLiveManager.isGenuineUserInterruption("the", currentAi))
        assertFalse(GeminiLiveManager.isGenuineUserInterruption("WhatsApp now", currentAi))
    }

    private fun pcm16Frame(amplitude: Short, sampleCount: Int = 1280): ByteArray {
        val bytes = ByteArray(sampleCount * 2)
        val lo = (amplitude.toInt() and 0xFF).toByte()
        val hi = ((amplitude.toInt() ushr 8) and 0xFF).toByte()
        var i = 0
        while (i < bytes.size) {
            bytes[i] = lo
            bytes[i + 1] = hi
            i += 2
        }
        return bytes
    }

    @Test
    fun geminiLiveManager_noiseGate_passesVoiceAndHangover_thenEmitsDigitalSilence() {
        GeminiLiveManager.resetMicNoiseGateForTest()

        val roomNoise = pcm16Frame(amplitude = 45)
        val voice = pcm16Frame(amplitude = 1400)
        val moderateEcho = pcm16Frame(amplitude = 350)

        // Initial low room static should become clean digital silence so VAD doesn't stick open
        val gatedInitialNoise = GeminiLiveManager.processMicFrameForLive(
            pcmBytes = roomNoise,
            isAiSpeaking = false,
            hasHardwareAec = false,
            nowMs = 1_000L
        )
        assertTrue(gatedInitialNoise.all { it == 0.toByte() })

        // Active human speech must pass completely untouched
        val passedVoice = GeminiLiveManager.processMicFrameForLive(
            pcmBytes = voice,
            isAiSpeaking = false,
            hasHardwareAec = false,
            nowMs = 1_100L
        )
        assertTrue(passedVoice.contentEquals(voice))

        // Within 560ms hangover window, quiet tail still passes cleanly
        val passedHangover = GeminiLiveManager.processMicFrameForLive(
            pcmBytes = roomNoise,
            isAiSpeaking = false,
            hasHardwareAec = false,
            nowMs = 1_450L
        )
        assertTrue(passedHangover.contentEquals(roomNoise))

        // After 560ms hangover expires, low noise returns clean digital silence so server VAD closes turn fast
        val gatedAfterHangover = GeminiLiveManager.processMicFrameForLive(
            pcmBytes = roomNoise,
            isAiSpeaking = false,
            hasHardwareAec = false,
            nowMs = 1_750L
        )
        assertTrue(gatedAfterHangover.all { it == 0.toByte() })

        // While AI is speaking without hardware AEC, moderate speaker bleed is muted to avoid false interrupts
        val gatedEcho = GeminiLiveManager.processMicFrameForLive(
            pcmBytes = moderateEcho,
            isAiSpeaking = true,
            hasHardwareAec = false,
            nowMs = 2_000L
        )
        assertTrue(gatedEcho.all { it == 0.toByte() })

        // Loud direct user barge-in while AI is speaking still passes immediately
        val passedBargeIn = GeminiLiveManager.processMicFrameForLive(
            pcmBytes = voice,
            isAiSpeaking = true,
            hasHardwareAec = false,
            nowMs = 2_100L
        )
        assertTrue(passedBargeIn.contentEquals(voice))
    }

    @Test
    fun geminiLiveManager_markConnectingPending_setsConnectingWithoutBlockingSessionStart() {
        try {
            GeminiLiveManager.stopLiveSession()
            assertEquals(LivePhase.IDLE, GeminiLiveManager.state.value.phase)

            GeminiLiveManager.markConnectingPending("KORE")
            assertEquals(LivePhase.CONNECTING, GeminiLiveManager.state.value.phase)
            assertEquals("KORE", GeminiLiveManager.state.value.selectedVoice)
        } finally {
            GeminiLiveManager.stopLiveSession()
        }
    }
}
