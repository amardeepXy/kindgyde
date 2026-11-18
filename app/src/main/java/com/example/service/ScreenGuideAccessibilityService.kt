package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.example.MainActivity
import com.example.ai.AiGuideStep
import com.example.ai.AssistiveScreenAction
import com.example.ai.CompletedSessionStep
import com.example.ai.GeminiLiveManager
import com.example.ai.GeminiLiveUiState
import com.example.ai.GuideSessionCoordinator
import com.example.ai.LivePhase
import com.example.ai.OverlayScreenSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Play-Policy-compliant AccessibilityService that powers:
 * 1. Single Unified Cross-App Movable Floating Overlay (hidden when inside KindGuide so there are
 *    never duplicate overlays; shown over external apps like WhatsApp, Chrome, YouTube, Home Screen).
 * 2. Clear Confirmation Card (`✅ Send Now` / `✕ Cancel`) before final send/commit actions.
 * 3. Step-verified cross-app actions (opening apps, tapping contacts directly, verifying chat screen
 *    is open before typing messages so messages are never typed into a Contact Search Bar).
 * 4. Strict, non-jittering Spotlight Highlighting ONLY for verified clickable buttons on the active screen.
 */
class ScreenGuideAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    private var floatingPillWrapper: View? = null
    private var floatingPillParams: WindowManager.LayoutParams? = null
    private var spotlightView: SpotlightOverlayView? = null

    private var statusDotView: View? = null
    private var highlightTogglePill: TextView? = null
    private var taskHistoryTogglePill: TextView? = null
    private var openLivePill: TextView? = null
    private var stepHintChip: TextView? = null
    private var confirmActionRow: LinearLayout? = null
    private var confirmSendBtn: TextView? = null
    private var cancelSendBtn: TextView? = null
    private var taskHistoryDrawerCard: LinearLayout? = null
    private var taskHistoryHeaderTitle: TextView? = null
    private var taskHistoryClearBtn: TextView? = null
    private var taskHistoryScrollView: ScrollView? = null
    private var taskHistoryListContainer: LinearLayout? = null
    private var lastRenderedHistorySignature: String = ""
    private var lastRenderedDotColor: Int = 0
    private var lastRenderedHighlightKey: String = ""
    private var lastRenderedHistoryBtnText: String = ""
    private var lastRenderedOpenLiveText: String = ""
    private var lastRenderedHintKey: String = ""
    private var lastRenderedConfirmKey: String = ""

    private var liveOverlayDismissedByUser = false
    private var wasLiveActive = false
    private var lastScreenshotAtMs = 0L
    private var lastContentInspectionAtMs = 0L
    private var liveScreenshotFallbackJob: Job? = null
    private var lastInspectedPackage = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }

        GuideSessionCoordinator.setAccessibilityConnected(true)
        GuideSessionCoordinator.registerScreenSnapshotRequester { captureCurrentScreenSnapshot() }
        GuideSessionCoordinator.registerScreenActionExecutor { action ->
            executeAssistiveAction(action)
        }

        serviceScope.launch {
            combine(
                GuideSessionCoordinator.isOverlayVisible,
                GuideSessionCoordinator.isHighlightEnabled,
                GuideSessionCoordinator.activeStep,
                GuideSessionCoordinator.stepMonitorBanner,
                GuideSessionCoordinator.targetHighlightBounds,
                GeminiLiveManager.state,
                GuideSessionCoordinator.isScreenTaskActive,
                GuideSessionCoordinator.isGeminiLiveScreenVisible,
                GuideSessionCoordinator.pendingConfirmationAction,
                GuideSessionCoordinator.activeScreenPackage,
                GuideSessionCoordinator.sessionTaskHistory,
                GuideSessionCoordinator.isTaskHistoryDrawerOpen,
                GuideSessionCoordinator.isKindGuideActivityResumed
            ) { values ->
                val visible = values[0] as Boolean
                val highlightEnabled = values[1] as Boolean
                val step = values[2] as AiGuideStep?
                val monitorBanner = values[3] as String?
                val bounds = values[4] as Rect?
                val liveState = values[5] as GeminiLiveUiState
                val screenTaskActive = values[6] as Boolean
                val liveScreenVisible = values[7] as Boolean
                val pendingConfirm = values[8] as AssistiveScreenAction?
                val activePkg = values[9] as String
                @Suppress("UNCHECKED_CAST")
                val taskHistory = values[10] as List<CompletedSessionStep>
                val historyDrawerOpen = values[11] as Boolean
                val isActivityResumed = values[12] as Boolean
                OverlayRenderState(
                    visible = visible,
                    highlightEnabled = highlightEnabled,
                    step = step,
                    stepMonitorBanner = monitorBanner,
                    highlightBounds = bounds,
                    liveState = liveState,
                    isScreenTaskActive = screenTaskActive,
                    isGeminiLiveScreenVisible = liveScreenVisible,
                    pendingConfirmationAction = pendingConfirm,
                    activeScreenPackage = activePkg,
                    sessionTaskHistory = taskHistory,
                    isTaskHistoryDrawerOpen = historyDrawerOpen,
                    isKindGuideActivityResumed = isActivityResumed
                )
            }.collect { state ->
                if (state.liveState.isActive && !wasLiveActive) {
                    liveOverlayDismissedByUser = false
                }
                wasLiveActive = state.liveState.isActive
                manageOnDemandScreenStreaming(state.liveState, state.isScreenTaskActive)
                renderOverlay(state)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventType = event?.eventType ?: return
        val eventPkg = event.packageName?.toString().orEmpty()

        // Ignore self-overlay layout/content events when KindGuide is in the background
        // so updating the floating pill NEVER triggers a feedback loop or flickering!
        if (eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            (eventPkg == packageName || eventPkg == "com.android.systemui") &&
            !GuideSessionCoordinator.isKindGuideActivityResumed.value
        ) {
            return
        }

        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickedLabel = extractClickedLabel(event)
            val clickedRect = extractClickedBounds(event)

            val isOverlayControl = clickedLabel in OVERLAY_PILL_LABELS ||
                clickedLabel.startsWith("📋") ||
                clickedLabel.startsWith("✅")
            if (!isOverlayControl && (clickedLabel.isNotBlank() || clickedRect != null)) {
                GuideSessionCoordinator.onUserClickedElement(
                    packageName = eventPkg,
                    clickedLabel = clickedLabel,
                    clickedBounds = clickedRect
                )
            }
        }

        val now = System.currentTimeMillis()
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Do not flood the main thread with Accessibility node tree IPC while AI audio is playing.
            if (GeminiLiveManager.state.value.phase == LivePhase.AI_SPEAKING) {
                return
            }
            val minIntervalMs = if (GuideSessionCoordinator.isScreenTaskActive.value) 280L else 650L
            if (now - lastContentInspectionAtMs < minIntervalMs) {
                return
            }
        }
        lastContentInspectionAtMs = now

        val inspection = inspectActiveScreen() ?: return
        if (inspection.packageName.isNotBlank() && inspection.packageName != lastInspectedPackage) {
            lastInspectedPackage = inspection.packageName
            GuideSessionCoordinator.onForegroundWindowChanged(inspection.packageName)
        }
        GuideSessionCoordinator.updateLiveScreenNodes(
            packageName = inspection.packageName,
            boundsByLabel = inspection.nodeBoundsByLabel
        )
    }

    private fun extractClickedLabel(event: AccessibilityEvent): String {
        val directText = event.text
            ?.mapNotNull { it?.toString()?.trim() }
            ?.firstOrNull { it.isNotBlank() }
            .orEmpty()
        if (directText.isNotBlank()) return directText

        val directDesc = event.contentDescription?.toString()?.trim().orEmpty()
        if (directDesc.isNotBlank()) return directDesc

        val src = runCatching { event.source }.getOrNull() ?: return ""
        return extractLabelFromNodeOrChildren(src, 0)
    }

    private fun extractLabelFromNodeOrChildren(node: AccessibilityNodeInfo?, depth: Int): String {
        if (node == null || depth > 4) return ""
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotBlank()) return text
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (desc.isNotBlank()) return desc
        for (i in 0 until node.childCount) {
            val childLabel = extractLabelFromNodeOrChildren(node.getChild(i), depth + 1)
            if (childLabel.isNotBlank()) return childLabel
        }
        return ""
    }

    private fun extractClickedBounds(event: AccessibilityEvent): Rect? {
        val src = runCatching { event.source }.getOrNull() ?: return null
        val rect = Rect()
        src.getBoundsInScreen(rect)
        return if (!rect.isEmpty) rect else null
    }

    override fun onInterrupt() {
        liveScreenshotFallbackJob?.cancel()
        removeOverlays()
    }

    override fun onDestroy() {
        super.onDestroy()
        liveScreenshotFallbackJob?.cancel()
        GuideSessionCoordinator.registerScreenSnapshotRequester(null)
        GuideSessionCoordinator.registerScreenActionExecutor(null)
        GuideSessionCoordinator.setAccessibilityConnected(false)
        removeOverlays()
        serviceScope.cancel()
        screenshotExecutor.shutdown()
    }

    /**
     * Universal assistive action executor across any installed Android app:
     * - OPEN_APP: Launches any app by name (Chrome, WhatsApp, YouTube, Maps, Settings, etc.).
     * - WEB_SEARCH: Opens Chrome/browser to search a query directly.
     * - TAP_ELEMENT: Clicks a verified button, icon, link, or contact row by label.
     * - SWIPE_SCREEN: Swipes LEFT, RIGHT, UP, or DOWN to navigate pages or scroll results.
     * - TYPE_TEXT: Verifies the correct target field (distinguishing top contact search bars from
     *   bottom chat message boxes!), taps visible contact rows first if needed, and enters text.
     * - SYSTEM_NAV: Presses system BACK or HOME.
     */
    private suspend fun executeAssistiveAction(action: AssistiveScreenAction): Boolean {
        return when (action.actionType.uppercase()) {
            "OPEN_APP" -> {
                val appToOpen = action.targetLabel.ifBlank { action.expectedAppName }
                val launched = launchAppByName(this, appToOpen)
                waitForExternalAppReady()
                launched
            }

            "WEB_SEARCH" -> {
                val query = action.textToType.ifBlank { action.targetLabel }
                val searched = launchWebSearchInBrowser(this, query)
                waitForExternalAppReady()
                searched
            }

            "SYSTEM_NAV" -> {
                val nav = action.systemNavAction.uppercase()
                val globalAction = if (nav == "HOME") {
                    GLOBAL_ACTION_HOME
                } else {
                    GLOBAL_ACTION_BACK
                }
                val prevLabels = GuideSessionCoordinator.latestNodeBoundsSnapshotLabels()
                val ok = performGlobalAction(globalAction)
                waitForUiChangeAndRefresh(previousLabels = prevLabels, initialDelayMs = 90L)
                ok
            }

            "SWIPE_SCREEN" -> {
                val prevLabels = GuideSessionCoordinator.latestNodeBoundsSnapshotLabels()
                val ok = performDirectionalSwipe(action.swipeDirection)
                waitForUiChangeAndRefresh(previousLabels = prevLabels, initialDelayMs = 110L)
                ok
            }

            "TYPE_TEXT" -> {
                val ok = performTypeTextIntoField(
                    targetLabel = action.targetLabel,
                    textToType = action.textToType,
                    submitAfterType = action.submitAfterType,
                    expectedAppName = action.expectedAppName
                )
                delay(90L)
                refreshNodesAfterAction()
                ok
            }

            "TAP_ELEMENT" -> {
                val prevLabels = GuideSessionCoordinator.latestNodeBoundsSnapshotLabels()
                val ok = performTapElementByLabel(action.targetLabel)
                waitForUiChangeAndRefresh(previousLabels = prevLabels, initialDelayMs = 90L)
                ok
            }

            else -> false
        }
    }

    /**
     * Waits briefly for app launch and polls every 85ms until an external window with
     * interactive UI nodes is visible (returns in ~120ms for warm apps).
     */
    private suspend fun waitForExternalAppReady() {
        delay(120L)
        for (attempt in 0 until 6) {
            val inspection = inspectActiveScreen()
            if (inspection != null &&
                inspection.packageName.isNotBlank() &&
                inspection.packageName != packageName &&
                inspection.nodeBoundsByLabel.isNotEmpty()
            ) {
                if (inspection.packageName != lastInspectedPackage) {
                    lastInspectedPackage = inspection.packageName
                    GuideSessionCoordinator.onForegroundWindowChanged(inspection.packageName)
                }
                GuideSessionCoordinator.updateLiveScreenNodes(
                    packageName = inspection.packageName,
                    boundsByLabel = inspection.nodeBoundsByLabel
                )
                return
            }
            delay(85L)
        }
        refreshNodesAfterAction()
    }

    private suspend fun waitForUiChangeAndRefresh(
        previousLabels: Set<String>,
        initialDelayMs: Long = 90L
    ) {
        delay(initialDelayMs)
        for (attempt in 0 until 2) {
            val inspection = inspectActiveScreen()
            if (inspection != null) {
                if (inspection.packageName.isNotBlank() && inspection.packageName != lastInspectedPackage) {
                    lastInspectedPackage = inspection.packageName
                    GuideSessionCoordinator.onForegroundWindowChanged(inspection.packageName)
                }
                GuideSessionCoordinator.updateLiveScreenNodes(
                    packageName = inspection.packageName,
                    boundsByLabel = inspection.nodeBoundsByLabel
                )
                if (inspection.nodeBoundsByLabel.keys != previousLabels &&
                    inspection.nodeBoundsByLabel.isNotEmpty()
                ) {
                    return
                }
            }
            delay(70L)
        }
        refreshNodesAfterAction()
    }

    private fun refreshNodesAfterAction() {
        val inspection = inspectActiveScreen() ?: return
        if (inspection.packageName.isNotBlank()) {
            if (inspection.packageName != lastInspectedPackage) {
                lastInspectedPackage = inspection.packageName
                GuideSessionCoordinator.onForegroundWindowChanged(inspection.packageName)
            }
        }
        GuideSessionCoordinator.updateLiveScreenNodes(
            packageName = inspection.packageName,
            boundsByLabel = inspection.nodeBoundsByLabel
        )
    }

    private suspend fun performTapElementByLabel(targetLabel: String): Boolean {
        val cleanTarget = targetLabel.trim()
        if (cleanTarget.isBlank()) return false

        val matchedNodeAndRect = findMatchingAccessibilityNode(cleanTarget)
        if (matchedNodeAndRect != null) {
            val (node, rect) = matchedNodeAndRect
            val clickableNode = findClickableNodeOrAncestor(node)
            if (clickableNode != null && clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            if (!rect.isEmpty) {
                return dispatchTapGesture(rect.exactCenterX(), rect.exactCenterY())
            }
        }

        val verifiedRect = GuideSessionCoordinator.findVerifiedBoundsForLabel(cleanTarget)
        if (verifiedRect != null && !verifiedRect.isEmpty) {
            return dispatchTapGesture(verifiedRect.exactCenterX(), verifiedRect.exactCenterY())
        }

        // If the element is slightly below the fold in an external app list (e.g. WhatsApp chats,
        // Settings, or Chrome results), scroll down once and check again!
        val activePkg = GuideSessionCoordinator.activeScreenPackage.value
        if (activePkg.isNotBlank() && activePkg != packageName &&
            !cleanTarget.equals("Send", ignoreCase = true)
        ) {
            val scrolled = performDirectionalSwipe("UP")
            if (scrolled) {
                delay(320L)
                refreshNodesAfterAction()
                val retryMatch = findMatchingAccessibilityNode(cleanTarget)
                if (retryMatch != null) {
                    val (node, rect) = retryMatch
                    val clickableNode = findClickableNodeOrAncestor(node)
                    if (clickableNode != null && clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                    if (!rect.isEmpty) {
                        return dispatchTapGesture(rect.exactCenterX(), rect.exactCenterY())
                    }
                }
            }
        }

        return false
    }

    /**
     * Enters text into a verified search bar, address bar, or chat input box.
     * STRICT GUARDRAILS:
     * 1. Never types into KindGuide's own chat input box ("Ask KindGuide anything...").
     * 2. Never types a chat message (e.g., "Have you taken food?") into a top Contact Search Bar!
     *    If the screen is still on a Contact Search / Select Contact screen and a matching contact
     *    row is visible below the search bar, it automatically taps the contact result row first,
     *    waits for the chat conversation to open, and then types into the bottom chat message box!
     */
    private suspend fun performTypeTextIntoField(
        targetLabel: String,
        textToType: String,
        submitAfterType: Boolean,
        expectedAppName: String
    ): Boolean {
        val cleanText = textToType.trim()
        if (cleanText.isBlank()) return false

        // 1. Verify we are not inside KindGuide when trying to type into an external app
        var currentInspection = inspectActiveScreen()
        val currentPkg = currentInspection?.packageName.orEmpty()
        if (currentPkg == packageName && expectedAppName.isNotBlank() &&
            !expectedAppName.contains("KindGuide", ignoreCase = true)
        ) {
            val opened = launchAppByName(this, expectedAppName)
            if (!opened) return false
            waitForExternalAppReady()
            currentInspection = inspectActiveScreen()
        }

        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(800)
        val isExplicitSearchRequest = submitAfterType ||
            targetLabel.contains("Search", ignoreCase = true) ||
            targetLabel.contains("Address", ignoreCase = true) ||
            targetLabel.contains("URL", ignoreCase = true)
        val looksLikeChatMessage = !isExplicitSearchRequest && (
            targetLabel.contains("Message", ignoreCase = true) ||
                targetLabel.contains("Chat", ignoreCase = true) ||
                cleanText.contains("?") ||
                cleanText.contains("!") ||
                cleanText.split(Regex("\\s+")).size >= 3
            )

        // 2. If we are trying to type a CHAT MESSAGE (like "Have you taken food?"), verify that
        //    we are actually inside an open chat conversation (bottom message composer) and NOT
        //    on the main WhatsApp Chats list or "Select contact" screen!
        if (looksLikeChatMessage) {
            refreshNodesAfterAction()
            if (!GuideSessionCoordinator.isChatComposerVisibleOnScreen()) {
                val targetContact = GuideSessionCoordinator.lastRequestedContactName.trim()
                val tappedKnownContact = if (targetContact.isNotBlank()) {
                    performTapElementByLabel(targetContact)
                } else {
                    false
                }
                val tappedRow = if (tappedKnownContact) {
                    true
                } else {
                    val contactCandidate = findFirstContactRowInSearchResults(screenH)
                    if (contactCandidate != null) {
                        val (contactNode, contactRect) = contactCandidate
                        val clickable = findClickableNodeOrAncestor(contactNode)
                        if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            true
                        } else {
                            dispatchTapGesture(contactRect.exactCenterX(), contactRect.exactCenterY())
                        }
                    } else {
                        false
                    }
                }
                if (tappedRow) {
                    for (attempt in 0 until 5) {
                        delay(85L)
                        refreshNodesAfterAction()
                        if (GuideSessionCoordinator.isChatComposerVisibleOnScreen()) break
                    }
                }
            }
        }

        // 3. Locate the appropriate editable field (prefer bottom composer for chat messages, top bar for searches)
        var editNode = findEditableNode(
            targetLabel = targetLabel,
            preferBottomChatComposer = looksLikeChatMessage,
            screenHeight = screenH
        )
        if (editNode == null && targetLabel.isNotBlank() && !looksLikeChatMessage) {
            // For search bars (like WhatsApp "Ask Meta AI or Search" or magnifying glass "Search"),
            // tap the search trigger first so the EditText opens
            val tappedSearchTrigger = performTapElementByLabel(targetLabel) ||
                performTapElementByLabel("Search") ||
                performTapElementByLabel("Ask Meta AI or Search")
            if (tappedSearchTrigger) {
                for (attempt in 0 until 5) {
                    delay(75L)
                    editNode = findEditableNode(
                        targetLabel = targetLabel,
                        preferBottomChatComposer = false,
                        screenHeight = screenH
                    )
                    if (editNode != null) break
                }
            }
        }

        if (editNode == null) return false

        val rect = Rect()
        editNode.getBoundsInScreen(rect)
        val nodeText = editNode.text?.toString().orEmpty()
        val nodeHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) editNode.hintText?.toString().orEmpty() else ""
        val nodeDesc = editNode.contentDescription?.toString().orEmpty()
        val combinedFieldLabel = "$nodeText $nodeHint $nodeDesc"

        // Final strict safety check: NEVER type a chat message into a top-of-screen search bar
        // or "Ask Meta AI or Search" on the WhatsApp main Chats list!
        val isTopSearchOrMetaBar = rect.centerY() < (screenH * 0.45f).toInt() ||
            combinedFieldLabel.contains("Search", ignoreCase = true) ||
            combinedFieldLabel.contains("Meta AI", ignoreCase = true)
        if (looksLikeChatMessage && isTopSearchOrMetaBar) {
            val targetContact = GuideSessionCoordinator.lastRequestedContactName.trim()
            val openedChat = if (targetContact.isNotBlank() && performTapElementByLabel(targetContact)) {
                true
            } else {
                val contactCandidate = findFirstContactRowInSearchResults(screenH)
                if (contactCandidate != null) {
                    val (contactNode, contactRect) = contactCandidate
                    val clickable = findClickableNodeOrAncestor(contactNode)
                    if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        true
                    } else {
                        dispatchTapGesture(contactRect.exactCenterX(), contactRect.exactCenterY())
                    }
                } else {
                    false
                }
            }
            if (openedChat) {
                delay(800L)
                refreshNodesAfterAction()
                editNode = findEditableNode(
                    targetLabel = "Message",
                    preferBottomChatComposer = true,
                    screenHeight = screenH
                ) ?: return false
                editNode.getBoundsInScreen(rect)
                if (rect.centerY() < (screenH * 0.45f).toInt()) {
                    return false
                }
            } else {
                return false
            }
        }

        editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        editNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                cleanText
            )
        }
        var setOk = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!setOk && !rect.isEmpty) {
            dispatchTapGesture(rect.exactCenterX(), rect.exactCenterY())
            delay(280L)
            val retryNode = findEditableNode(
                targetLabel = targetLabel,
                preferBottomChatComposer = looksLikeChatMessage,
                screenHeight = screenH
            )
            if (retryNode != null) {
                editNode = retryNode
                setOk = retryNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }

        if (setOk && submitAfterType && editNode != null) {
            delay(200L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                editNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }
        return setOk
    }

    /**
     * Finds the first actual contact row in WhatsApp / Contacts search results below the search bar
     * (ignoring headers like "Contacts on WhatsApp", "New group", "New contact", "Invite from Instagram").
     */
    private fun findFirstContactRowInSearchResults(screenHeight: Int): Pair<AccessibilityNodeInfo, Rect>? {
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        val ignoredHeaders = setOf(
            "select contact",
            "new group",
            "new contact",
            "new community",
            "new event",
            "contacts on whatsapp",
            "more",
            "share invite link",
            "invite from instagram",
            "contacts help",
            "navigate up",
            "back",
            "search",
            "ask meta ai",
            "ask meta ai or search",
            "meta ai",
            "chats",
            "unread",
            "favorites",
            "groups"
        )
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            collectContactResultRows(activeRoot, ignoredHeaders, screenHeight, candidates, 0)
        }
        if (candidates.isEmpty()) {
            val interactiveWindows = runCatching { windows }.getOrNull().orEmpty()
            for (window in interactiveWindows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = window.root ?: continue
                collectContactResultRows(root, ignoredHeaders, screenHeight, candidates, 0)
            }
        }
        return candidates.minByOrNull { it.second.top }
    }

    private fun collectContactResultRows(
        node: AccessibilityNodeInfo?,
        ignoredHeaders: Set<String>,
        screenHeight: Int,
        out: MutableList<Pair<AccessibilityNodeInfo, Rect>>,
        depth: Int
    ) {
        if (node == null || depth > 14 || !node.isVisibleToUser || node.isEditable) return
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotBlank() &&
            text.length in 3..42 &&
            text.lowercase() !in ignoredHeaders &&
            !text.contains("Tell a friend", ignoreCase = true) &&
            !text.contains("Anyone you invite", ignoreCase = true) &&
            !text.contains("Hello. I'm using WhatsApp", ignoreCase = true) &&
            !text.contains("Message yourself", ignoreCase = true)
        ) {
            val clickable = findClickableNodeOrAncestor(node)
            if (clickable != null) {
                val rect = Rect()
                clickable.getBoundsInScreen(rect)
                if (!rect.isEmpty && rect.top > (screenHeight * 0.14f).toInt() && rect.bottom < (screenHeight * 0.85f).toInt()) {
                    out.add(clickable to rect)
                }
            }
        }
        for (i in 0 until node.childCount) {
            collectContactResultRows(node.getChild(i), ignoredHeaders, screenHeight, out, depth + 1)
        }
    }

    private suspend fun performDirectionalSwipe(directionRaw: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat().coerceAtLeast(360f)
        val h = dm.heightPixels.toFloat().coerceAtLeast(640f)

        val (startX, startY, endX, endY) = when (directionRaw.uppercase()) {
            "LEFT" -> listOf(w * 0.82f, h * 0.50f, w * 0.18f, h * 0.50f)
            "RIGHT" -> listOf(w * 0.18f, h * 0.50f, w * 0.82f, h * 0.50f)
            "DOWN" -> listOf(w * 0.50f, h * 0.28f, w * 0.50f, h * 0.76f)
            else -> listOf(w * 0.50f, h * 0.76f, w * 0.50f, h * 0.26f)
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 320L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSuspend(gesture)
    }

    private suspend fun dispatchTapGesture(x: Float, y: Float): Boolean {
        val dm = resources.displayMetrics
        val safeX = x.coerceIn(8f, (dm.widthPixels - 8).toFloat().coerceAtLeast(16f))
        val safeY = y.coerceIn(24f, (dm.heightPixels - 16).toFloat().coerceAtLeast(48f))

        val path = Path().apply {
            moveTo(safeX, safeY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 75L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureSuspend(gesture)
    }

    private suspend fun dispatchGestureSuspend(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }

    private fun findMatchingAccessibilityNode(targetLabel: String): Pair<AccessibilityNodeInfo, Rect>? {
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        val activeRoot = rootInActiveWindow
        if (activeRoot != null && activeRoot.packageName?.toString() != "com.android.systemui") {
            collectMatchingNodes(activeRoot, targetLabel, candidates, 0)
        }
        if (candidates.isEmpty()) {
            val interactiveWindows = runCatching { windows }.getOrNull().orEmpty()
            for (window in interactiveWindows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = window.root ?: continue
                if (root.packageName?.toString() == "com.android.systemui") continue
                collectMatchingNodes(root, targetLabel, candidates, 0)
            }
        }
        // Sort exact matches first, and avoid matching an EditText search bar when clicking a contact row
        return candidates.sortedWith(
            compareByDescending<Pair<AccessibilityNodeInfo, Rect>> { (node, _) ->
                val t = node.text?.toString()?.trim().orEmpty()
                val d = node.contentDescription?.toString()?.trim().orEmpty()
                t.equals(targetLabel, ignoreCase = true) || d.equals(targetLabel, ignoreCase = true)
            }.thenBy { (node, _) ->
                if (node.isEditable && !targetLabel.contains("Search", ignoreCase = true) && !targetLabel.contains("Message", ignoreCase = true)) 1 else 0
            }
        ).firstOrNull()
    }

    private fun collectMatchingNodes(
        node: AccessibilityNodeInfo?,
        targetLabel: String,
        results: MutableList<Pair<AccessibilityNodeInfo, Rect>>,
        depth: Int
    ) {
        if (node == null || depth > 16 || !node.isVisibleToUser) return
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim().orEmpty()
        } else {
            ""
        }
        val label = listOf(text, desc, hint).firstOrNull { it.isNotBlank() }.orEmpty()
        if (label.isNotBlank() && label.length <= 120 && label !in OVERLAY_PILL_LABELS) {
            if (label.equals(targetLabel, ignoreCase = true) ||
                label.contains(targetLabel, ignoreCase = true) ||
                (targetLabel.length >= 4 && targetLabel.contains(label, ignoreCase = true))
            ) {
                val clickable = findClickableNodeOrAncestor(node) ?: node
                val rect = Rect()
                clickable.getBoundsInScreen(rect)
                if (rect.isEmpty) node.getBoundsInScreen(rect)
                if (!rect.isEmpty) {
                    results.add(node to rect)
                }
            }
        }
        for (i in 0 until node.childCount) {
            collectMatchingNodes(node.getChild(i), targetLabel, results, depth + 1)
        }
    }

    private fun findEditableNode(
        targetLabel: String,
        preferBottomChatComposer: Boolean,
        screenHeight: Int
    ): AccessibilityNodeInfo? {
        val editables = mutableListOf<AccessibilityNodeInfo>()
        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            collectEditableNodes(activeRoot, editables, 0)
        }
        if (editables.isEmpty()) {
            val interactiveWindows = runCatching { windows }.getOrNull().orEmpty()
            for (window in interactiveWindows) {
                if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                val root = window.root ?: continue
                collectEditableNodes(root, editables, 0)
            }
        }

        // Never type external queries or contact names into KindGuide's own internal input fields
        val safeEditables = editables.filterNot { node ->
            val nodePkg = node.packageName?.toString().orEmpty()
            val t = node.text?.toString().orEmpty()
            val d = node.contentDescription?.toString().orEmpty()
            val h = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else ""
            val combinedText = "$t $d $h"
            nodePkg == packageName && (
                combinedText.contains("Ask KindGuide", ignoreCase = true) ||
                    combinedText.contains("Type a message to Gemini Live", ignoreCase = true) ||
                    targetLabel.isBlank() ||
                    !targetLabel.contains("KindGuide", ignoreCase = true)
                )
        }

        if (preferBottomChatComposer) {
            // Strictly require an editable field in the lower half of the screen that is NOT a search bar
            return safeEditables.firstOrNull { n ->
                val r = Rect()
                n.getBoundsInScreen(r)
                val h = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.hintText?.toString().orEmpty() else ""
                val t = n.text?.toString().orEmpty()
                val d = n.contentDescription?.toString().orEmpty()
                val combined = "$t $h $d"
                val notSearchBar = !combined.contains("Search", ignoreCase = true) &&
                    !combined.contains("Meta AI", ignoreCase = true)
                notSearchBar && (
                    r.centerY() > (screenHeight * 0.45f).toInt() ||
                        h.contains("Message", ignoreCase = true) ||
                        t.equals("Message", ignoreCase = true)
                    )
            }
        }

        if (targetLabel.isNotBlank()) {
            safeEditables.firstOrNull { n ->
                val t = n.text?.toString().orEmpty()
                val d = n.contentDescription?.toString().orEmpty()
                val h = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) n.hintText?.toString().orEmpty() else ""
                t.contains(targetLabel, ignoreCase = true) ||
                    d.contains(targetLabel, ignoreCase = true) ||
                    h.contains(targetLabel, ignoreCase = true)
            }?.let { return it }
        }
        return safeEditables.firstOrNull { it.isFocused } ?: safeEditables.firstOrNull()
    }

    private fun collectEditableNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (node == null || depth > 16 || !node.isVisibleToUser) return
        if (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            collectEditableNodes(node.getChild(i), out, depth + 1)
        }
    }

    private fun findClickableNodeOrAncestor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var cur = node
        var hops = 0
        while (cur != null && hops < 5) {
            if (cur.isClickable) return cur
            cur = cur.parent
            hops++
        }
        return null
    }

    /**
     * Strictly on-demand screen capture: ONLY runs when the user explicitly shared their screen
     * OR when a screen-guided task (`isScreenTaskActive`) is active in an external app.
     */
    private fun manageOnDemandScreenStreaming(
        liveState: GeminiLiveUiState,
        isScreenTaskActive: Boolean
    ) {
        val shouldRunOnDemandCapture = liveState.isActive &&
            !liveState.isScreenSharing &&
            !liveState.isCameraSharing &&
            isScreenTaskActive &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        if (shouldRunOnDemandCapture && liveScreenshotFallbackJob?.isActive != true) {
            liveScreenshotFallbackJob = serviceScope.launch {
                while (isActive) {
                    delay(2800L)
                    val cur = GeminiLiveManager.state.value
                    val taskActive = GuideSessionCoordinator.isScreenTaskActive.value
                    // Never capture hardware screenshots while AI audio is actively speaking
                    // so audio playback is never interrupted!
                    if (cur.isActive &&
                        cur.phase != LivePhase.AI_SPEAKING &&
                        !cur.isScreenSharing &&
                        !cur.isCameraSharing &&
                        taskActive
                    ) {
                        val bmp = takeAccessibilityScreenshot()
                        if (bmp != null) {
                            GeminiLiveManager.sendScreenFrame(bmp, sourceLabel = "Accessibility")
                            bmp.recycle()
                        }
                    }
                }
            }
        } else if (!shouldRunOnDemandCapture) {
            liveScreenshotFallbackJob?.cancel()
            liveScreenshotFallbackJob = null
        }
    }

    private fun renderOverlay(state: OverlayRenderState) {
        val liveState = state.liveState
        // Use MainActivity's resumed lifecycle state so self-overlay accessibility events
        // NEVER flip `inExternalApp` to false and cause window add/remove flickering!
        val inExternalApp = !state.isKindGuideActivityResumed

        val shouldShow = inExternalApp &&
            !liveOverlayDismissedByUser &&
            (liveState.isActive ||
                state.visible ||
                state.isScreenTaskActive ||
                state.step != null ||
                state.pendingConfirmationAction != null ||
                state.isTaskHistoryDrawerOpen ||
                state.sessionTaskHistory.isNotEmpty())

        if (!shouldShow) {
            floatingPillWrapper?.visibility = View.GONE
            spotlightView?.visibility = View.GONE
            return
        }

        val step = state.step
        val pendingConfirm = state.pendingConfirmationAction
        val shouldDrawSpotlightBox = state.highlightEnabled &&
            state.highlightBounds != null &&
            ((step != null && (step.guidanceMode == "HIGHLIGHT_BUTTON" || step.guidanceMode == "CONFIRM_SEND")) ||
                pendingConfirm != null)

        val activeBounds = if (shouldDrawSpotlightBox) state.highlightBounds else null
        val calloutLabel = pendingConfirm?.targetLabel?.ifBlank { "Send" }
            ?: step?.targetButtonHint?.ifBlank { "Tap Here" }
            ?: "Tap Here"

        ensureSpotlightOverlay(
            bounds = activeBounds,
            calloutLabel = calloutLabel,
            isCorrection = step?.isCorrection == true,
            isConfirmSend = pendingConfirm != null || step?.requiresConfirmation == true
        )

        ensureMinimalFloatingPill()
        if (floatingPillWrapper?.visibility != View.VISIBLE) {
            floatingPillWrapper?.visibility = View.VISIBLE
        }

        val dotColor = when {
            step?.isCorrection == true -> Color.parseColor("#DC2626")
            pendingConfirm != null -> Color.parseColor("#15803D")
            !liveState.isActive -> Color.parseColor("#C2410C")
            liveState.isMuted -> Color.parseColor("#78716C")
            liveState.phase == LivePhase.AI_SPEAKING -> Color.parseColor("#EA580C")
            liveState.phase == LivePhase.INTERRUPTED -> Color.parseColor("#D97706")
            else -> Color.parseColor("#15803D")
        }
        if (dotColor != lastRenderedDotColor) {
            lastRenderedDotColor = dotColor
            (statusDotView?.background as? GradientDrawable)?.setColor(dotColor)
        }

        val dp = { v: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                v.toFloat(),
                resources.displayMetrics
            ).toInt()
        }

        val highlightKey = "${state.highlightEnabled}:${step?.isCorrection == true}"
        if (highlightKey != lastRenderedHighlightKey) {
            lastRenderedHighlightKey = highlightKey
            highlightTogglePill?.apply {
                text = if (state.highlightEnabled) "Highlight ON" else "Highlight OFF"
                setTextColor(
                    if (state.highlightEnabled) Color.WHITE else Color.parseColor("#44403C")
                )
                background = GradientDrawable().apply {
                    setColor(
                        if (state.highlightEnabled) {
                            if (step?.isCorrection == true) Color.parseColor("#DC2626")
                            else Color.parseColor("#C2410C")
                        } else {
                            Color.parseColor("#F3ECE4")
                        }
                    )
                    cornerRadius = dp(18).toFloat()
                }
            }
        }

        val historyCount = state.sessionTaskHistory.size
        val historyBtnText = when {
            state.isTaskHistoryDrawerOpen -> "📋 History ▲ ($historyCount)"
            historyCount > 0 -> "📋 History ($historyCount)"
            else -> "📋 History"
        }
        if (historyBtnText != lastRenderedHistoryBtnText) {
            lastRenderedHistoryBtnText = historyBtnText
            taskHistoryTogglePill?.apply {
                text = historyBtnText
                setTextColor(
                    if (state.isTaskHistoryDrawerOpen) Color.WHITE else Color.parseColor("#292524")
                )
                background = GradientDrawable().apply {
                    setColor(
                        if (state.isTaskHistoryDrawerOpen) Color.parseColor("#9A3412")
                        else Color.parseColor("#EFE6DC")
                    )
                    cornerRadius = dp(18).toFloat()
                }
            }
        }

        val openLiveLabel = if (liveState.isActive) "Live" else "Open"
        if (openLiveLabel != lastRenderedOpenLiveText) {
            lastRenderedOpenLiveText = openLiveLabel
            openLivePill?.text = openLiveLabel
        }

        // Live Step Status Banner: only updates on step/action state changes (NOT on streaming audio tokens)
        val hintText = when {
            pendingConfirm != null -> {
                val explain = pendingConfirm.spokenExplanation.ifBlank {
                    "Ready to tap '${pendingConfirm.targetLabel.ifBlank { "Send" }}'"
                }
                "✋ Confirm: $explain"
            }
            !state.stepMonitorBanner.isNullOrBlank() -> state.stepMonitorBanner
            step != null && step.guidanceMode == "SWIPE_HINT" -> "↔️ ${step.instruction}"
            step != null && step.guidanceMode == "SEARCH_HINT" -> "🔍 ${step.instruction}"
            step != null && step.requiresConfirmation -> "✋ Confirm: ${step.instruction}"
            step != null && step.isCorrection -> "⚠️ ${step.instruction}"
            step != null -> "Step ${step.stepNumber}: ${step.instruction}"
            else -> ""
        }
        val hintKey = "$hintText:${step?.isCorrection == true}:${pendingConfirm != null || step?.requiresConfirmation == true}"
        if (hintKey != lastRenderedHintKey) {
            lastRenderedHintKey = hintKey
            stepHintChip?.apply {
                if (hintText.isNotBlank()) {
                    visibility = View.VISIBLE
                    text = hintText
                    background = GradientDrawable().apply {
                        setColor(
                            when {
                                step?.isCorrection == true -> Color.parseColor("#F2991B1B")
                                pendingConfirm != null || step?.requiresConfirmation == true -> Color.parseColor("#F214532D")
                                else -> Color.parseColor("#EB1C1917")
                            }
                        )
                        cornerRadius = dp(14).toFloat()
                    }
                } else {
                    visibility = View.GONE
                }
            }
        }

        // Clear Confirmation Row: "✅ Send Now" and "✕ Cancel"
        val showConfirmationButtons = pendingConfirm != null || step?.requiresConfirmation == true
        val actionVerb = pendingConfirm?.targetLabel?.ifBlank { "Send" }
            ?: step?.targetButtonHint?.ifBlank { "Confirm" }
            ?: "Send"
        val confirmKey = "$showConfirmationButtons:$actionVerb"
        if (confirmKey != lastRenderedConfirmKey) {
            lastRenderedConfirmKey = confirmKey
            confirmActionRow?.visibility = if (showConfirmationButtons) View.VISIBLE else View.GONE
            if (showConfirmationButtons) {
                confirmSendBtn?.text = "✅ $actionVerb Now"
            }
        }

        // Persistent, Scrollable 'Task History' Drawer
        val desiredDrawerVis = if (state.isTaskHistoryDrawerOpen) View.VISIBLE else View.GONE
        if (taskHistoryDrawerCard?.visibility != desiredDrawerVis) {
            taskHistoryDrawerCard?.visibility = desiredDrawerVis
        }
        if (state.isTaskHistoryDrawerOpen) {
            populateTaskHistoryDrawer(state.sessionTaskHistory, dp)
        }
    }

    private fun ensureSpotlightOverlay(
        bounds: Rect?,
        calloutLabel: String,
        isCorrection: Boolean,
        isConfirmSend: Boolean
    ) {
        if (bounds == null || bounds.isEmpty) {
            spotlightView?.visibility = View.GONE
            return
        }
        if (spotlightView == null) {
            val view = SpotlightOverlayView(this)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            runCatching {
                windowManager.addView(view, params)
                spotlightView = view
            }.onFailure {
                Log.w(TAG, "Unable to attach spotlight overlay", it)
            }
        }
        if (spotlightView?.visibility != View.VISIBLE) {
            spotlightView?.visibility = View.VISIBLE
        }
        spotlightView?.updateHighlightRect(bounds, calloutLabel, isCorrection, isConfirmSend)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureMinimalFloatingPill() {
        if (floatingPillWrapper != null) return

        val dp = { v: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                v.toFloat(),
                resources.displayMetrics
            ).toInt()
        }

        val rootColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }

        val pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(8), dp(6))
            elevation = dp(6).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFFBF5"))
                cornerRadius = dp(28).toFloat()
                setStroke(dp(1), Color.parseColor("#E7DDD3"))
            }
        }

        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#15803D"))
            }
        }
        statusDotView = dot

        val highlightBtn = TextView(this).apply {
            text = "Highlight ON"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener {
                GuideSessionCoordinator.toggleHighlightEnabled()
            }
        }
        highlightTogglePill = highlightBtn

        val historyBtn = TextView(this).apply {
            text = "📋 History"
            setTextColor(Color.parseColor("#292524"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EFE6DC"))
                cornerRadius = dp(18).toFloat()
            }
            setOnClickListener {
                GuideSessionCoordinator.toggleTaskHistoryDrawer()
            }
        }
        taskHistoryTogglePill = historyBtn

        val liveBtn = TextView(this).apply {
            text = "Live"
            setTextColor(Color.parseColor("#292524"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E7DDD3"))
                cornerRadius = dp(18).toFloat()
            }
            setOnClickListener {
                val openIntent = Intent(this@ScreenGuideAccessibilityService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(GeminiLiveForegroundService.EXTRA_OPEN_LIVE, true)
                }
                runCatching { startActivity(openIntent) }
            }
        }
        openLivePill = liveBtn

        val closeIcon = TextView(this).apply {
            text = "✕"
            setTextColor(Color.parseColor("#78716C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(4), dp(6), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(2) }
            setOnClickListener {
                if (GeminiLiveManager.state.value.isActive) {
                    liveOverlayDismissedByUser = true
                }
                GuideSessionCoordinator.setTaskHistoryDrawerOpen(false)
                GuideSessionCoordinator.setOverlayVisible(false)
            }
        }

        pillRow.addView(dot)
        pillRow.addView(highlightBtn)
        pillRow.addView(historyBtn)
        pillRow.addView(liveBtn)
        pillRow.addView(closeIcon)

        val stepHint = TextView(this).apply {
            setTextColor(Color.parseColor("#FFFBF5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EB1C1917"))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
            maxWidth = dp(310)
            visibility = View.GONE
        }
        stepHintChip = stepHint

        // Clear Confirmation Buttons Row: "✅ Send Now" and "✕ Cancel"
        val confirmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
            }
            visibility = View.GONE
        }

        val sendNowButton = TextView(this).apply {
            text = "✅ Send Now"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#15803D"))
                cornerRadius = dp(18).toFloat()
            }
            setOnClickListener {
                GuideSessionCoordinator.confirmPendingAction()
            }
        }
        confirmSendBtn = sendNowButton

        val cancelButton = TextView(this).apply {
            text = "✕ Cancel"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B91C1C"))
                cornerRadius = dp(18).toFloat()
            }
            setOnClickListener {
                GuideSessionCoordinator.cancelPendingAction()
            }
        }
        cancelSendBtn = cancelButton

        confirmRow.addView(sendNowButton)
        confirmRow.addView(cancelButton)
        confirmActionRow = confirmRow

        // Persistent, Scrollable 'Task History' Drawer Card attached to the floating overlay
        val historyDrawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            elevation = dp(10).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FAFFFBF5"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#D6C7B8"))
            }
            layoutParams = LinearLayout.LayoutParams(
                dp(310),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
            visibility = View.GONE
        }

        val drawerHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            }
        }

        val drawerTitleView = TextView(this).apply {
            text = "📋 Task History"
            setTextColor(Color.parseColor("#1C1917"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        taskHistoryHeaderTitle = drawerTitleView

        val clearHistoryBtn = TextView(this).apply {
            text = "Clear"
            setTextColor(Color.parseColor("#9A3412"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FDE6D2"))
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener {
                GuideSessionCoordinator.clearSessionTaskHistory()
            }
        }
        taskHistoryClearBtn = clearHistoryBtn

        val collapseDrawerBtn = TextView(this).apply {
            text = "Hide ▲"
            setTextColor(Color.parseColor("#44403C"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(6)
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E7DDD3"))
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener {
                GuideSessionCoordinator.setTaskHistoryDrawerOpen(false)
            }
        }

        drawerHeaderRow.addView(drawerTitleView)
        drawerHeaderRow.addView(clearHistoryBtn)
        drawerHeaderRow.addView(collapseDrawerBtn)

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        taskHistoryListContainer = listContainer

        val historyScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            isFillViewport = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(listContainer)
            setOnTouchListener { v, ev ->
                // Allow vertical scrolling inside the Task History drawer without triggering window drag
                v.parent?.requestDisallowInterceptTouchEvent(true)
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
        taskHistoryScrollView = historyScroll

        historyDrawer.addView(drawerHeaderRow)
        historyDrawer.addView(historyScroll)
        taskHistoryDrawerCard = historyDrawer

        rootColumn.addView(pillRow)
        rootColumn.addView(stepHint)
        rootColumn.addView(confirmRow)
        rootColumn.addView(historyDrawer)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(116)
        }
        floatingPillParams = params

        var initialX = 0
        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var dragging = false
        val dragListener = View.OnTouchListener { _, event ->
            val lp = floatingPillParams ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (touchStartX - event.rawX).toInt()
                    val dy = (event.rawY - touchStartY).toInt()
                    if (abs(dx) > dp(5) || abs(dy) > dp(5)) {
                        dragging = true
                        val maxW = (resources.displayMetrics.widthPixels - dp(120)).coerceAtLeast(0)
                        val maxH = (resources.displayMetrics.heightPixels - dp(80)).coerceAtLeast(0)
                        lp.x = (initialX + dx).coerceIn(0, maxW)
                        lp.y = (initialY + dy).coerceIn(dp(20), maxH)
                        floatingPillWrapper?.let { v ->
                            runCatching { windowManager.updateViewLayout(v, lp) }
                        }
                    }
                    dragging
                }
                else -> dragging
            }
        }
        rootColumn.setOnTouchListener(dragListener)
        pillRow.setOnTouchListener(dragListener)
        dot.setOnTouchListener(dragListener)
        stepHint.setOnTouchListener(dragListener)
        drawerHeaderRow.setOnTouchListener(dragListener)

        runCatching {
            windowManager.addView(rootColumn, params)
            floatingPillWrapper = rootColumn
        }.onFailure {
            Log.e(TAG, "Failed to attach KindGuide minimal floating pill", it)
        }
    }

    private fun populateTaskHistoryDrawer(
        steps: List<CompletedSessionStep>,
        dp: (Int) -> Int
    ) {
        val container = taskHistoryListContainer ?: return
        val scroll = taskHistoryScrollView ?: return

        taskHistoryHeaderTitle?.text = if (steps.isEmpty()) {
            "📋 Task History (0 steps)"
        } else {
            "📋 Task History (${steps.size} ${if (steps.size == 1) "step" else "steps"})"
        }
        taskHistoryClearBtn?.visibility = if (steps.isNotEmpty()) View.VISIBLE else View.GONE

        val signature = steps.joinToString("|") { "${it.id}:${it.stepIndex}:${it.title}" }
        if (signature == lastRenderedHistorySignature && container.childCount > 0) {
            return
        }
        lastRenderedHistorySignature = signature
        container.removeAllViews()

        // Cap ScrollView height when there are 3+ steps so it scrolls smoothly within the floating overlay
        val targetScrollHeight = if (steps.size >= 3) {
            dp(220)
        } else {
            LinearLayout.LayoutParams.WRAP_CONTENT
        }
        scroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            targetScrollHeight
        )

        if (steps.isEmpty()) {
            val emptyView = TextView(this).apply {
                text = "No steps completed in this session yet.\nAsk KindGuide to open an app or help with any task!"
                setTextColor(Color.parseColor("#57534E"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#F5EFE8"))
                    cornerRadius = dp(12).toFloat()
                }
            }
            container.addView(emptyView)
            return
        }

        for (step in steps) {
            val itemCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    setColor(
                        if (step.succeeded) Color.parseColor("#F4EDE4")
                        else Color.parseColor("#FEF2F2")
                    )
                    cornerRadius = dp(12).toFloat()
                    setStroke(
                        dp(1),
                        if (step.succeeded) Color.parseColor("#E5D8C8")
                        else Color.parseColor("#FECACA")
                    )
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(6)
                }
            }

            val metaLine = TextView(this).apply {
                text = "${step.statusIcon} Step ${step.stepIndex} • ${step.appName} • ${step.timestampLabel}"
                setTextColor(
                    if (step.succeeded) Color.parseColor("#9A3412")
                    else Color.parseColor("#B91C1C")
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.DEFAULT_BOLD
            }

            val titleLine = TextView(this).apply {
                text = step.title
                setTextColor(Color.parseColor("#1C1917"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(2), 0, 0)
            }

            itemCard.addView(metaLine)
            itemCard.addView(titleLine)

            if (step.detail.isNotBlank()) {
                val detailLine = TextView(this).apply {
                    text = step.detail
                    setTextColor(Color.parseColor("#57534E"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(2), 0, 0)
                }
                itemCard.addView(detailLine)
            }

            container.addView(itemCard)
        }

        scroll.post {
            scroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun removeOverlays() {
        floatingPillWrapper?.let {
            runCatching { windowManager.removeView(it) }
            floatingPillWrapper = null
        }
        floatingPillParams = null
        statusDotView = null
        highlightTogglePill = null
        taskHistoryTogglePill = null
        openLivePill = null
        stepHintChip = null
        confirmActionRow = null
        confirmSendBtn = null
        cancelSendBtn = null
        taskHistoryDrawerCard = null
        taskHistoryHeaderTitle = null
        taskHistoryClearBtn = null
        taskHistoryScrollView = null
        taskHistoryListContainer = null
        lastRenderedHistorySignature = ""
        spotlightView?.let {
            runCatching { windowManager.removeView(it) }
            spotlightView = null
        }
    }

    /**
     * Inspects the TRUE active foreground window first (`rootInActiveWindow`) so KindGuide
     * never confuses its own chat screen with WhatsApp or another background app.
     */
    private fun inspectActiveScreen(): ScreenInspectionResult? {
        val labels = mutableListOf<String>()
        val bounds = linkedMapOf<String, Rect>()
        var detectedPackage = ""
        val dm = resources.displayMetrics

        val isKindGuideResumed = GuideSessionCoordinator.isKindGuideActivityResumed.value

        // 1. Prioritize rootInActiveWindow so the true foreground app is always identified accurately!
        //    When KindGuide's Activity is paused/in background (`!isKindGuideResumed`), ignore our own
        //    accessibility overlay package (`packageName`) so it never mistakes the overlay for KindGuide!
        val activeRoot = runCatching { rootInActiveWindow }.getOrNull()
        val activeRootPkg = activeRoot?.packageName?.toString().orEmpty()
        if (activeRoot != null &&
            activeRootPkg.isNotBlank() &&
            activeRootPkg != "com.android.systemui" &&
            (isKindGuideResumed || activeRootPkg != packageName)
        ) {
            detectedPackage = activeRootPkg
            collectInteractiveNodes(
                node = activeRoot,
                expectedPackage = activeRootPkg,
                labels = labels,
                boundsByLabel = bounds,
                depth = 0,
                clickableParentBounds = null,
                maxScreenWidth = dm.widthPixels,
                maxScreenHeight = dm.heightPixels
            )
        }

        // 2. Fallback to focused/active TYPE_APPLICATION windows if rootInActiveWindow was empty
        if (labels.isEmpty()) {
            val interactiveWindows = runCatching { windows }.getOrNull().orEmpty()
            val orderedWindows = interactiveWindows.sortedByDescending { w ->
                (if (w.isFocused) 2 else 0) + (if (w.isActive) 1 else 0)
            }
            for (window in orderedWindows) {
                if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                    window.type != AccessibilityWindowInfo.TYPE_SYSTEM
                ) {
                    continue
                }
                val root = window.root ?: continue
                val pkg = root.packageName?.toString().orEmpty()
                if (pkg.isBlank() || pkg == "com.android.systemui") continue
                if (!isKindGuideResumed && pkg == packageName) continue
                if (detectedPackage.isBlank()) {
                    detectedPackage = pkg
                }
                collectInteractiveNodes(
                    node = root,
                    expectedPackage = pkg,
                    labels = labels,
                    boundsByLabel = bounds,
                    depth = 0,
                    clickableParentBounds = null,
                    maxScreenWidth = dm.widthPixels,
                    maxScreenHeight = dm.heightPixels
                )
                if (labels.isNotEmpty()) break
            }
        }

        if (detectedPackage.isBlank()) return null
        return ScreenInspectionResult(
            packageName = detectedPackage,
            visibleLabels = labels.distinct(),
            nodeBoundsByLabel = bounds
        )
    }

    private suspend fun captureCurrentScreenSnapshot(): OverlayScreenSnapshot? {
        val inspection = inspectActiveScreen()
        val pkg = inspection?.packageName.orEmpty()
        val labels = inspection?.visibleLabels.orEmpty()
        val bounds = inspection?.nodeBoundsByLabel.orEmpty()

        val now = System.currentTimeMillis()
        val liveState = GeminiLiveManager.state.value
        // Skip synchronous hardware screenshot when Gemini Live is active (`manageOnDemandScreenStreaming`
        // handles throttled visual streaming when the AI is not speaking) so tool verification returns
        // immediately without blocking or jittering live audio playback.
        val screenshotBitmap = if (
            !liveState.isActive &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            now - lastScreenshotAtMs >= 1000L
        ) {
            lastScreenshotAtMs = now
            takeAccessibilityScreenshot()
        } else {
            null
        }

        return OverlayScreenSnapshot(
            packageName = pkg,
            visibleLabels = labels,
            nodeBoundsByLabel = bounds,
            screenshot = screenshotBitmap
        )
    }

    private suspend fun takeAccessibilityScreenshot(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val bmp = runCatching {
                                val hwBuffer = screenshot.hardwareBuffer
                                val colorSpace = screenshot.colorSpace
                                val wrapped = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                                val soft = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                                wrapped?.recycle()
                                hwBuffer.close()
                                soft
                            }.getOrNull()
                            if (cont.isActive) cont.resume(bmp)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private inner class SpotlightOverlayView(context: Context) : View(context) {
        private var targetRect: RectF? = null
        private var calloutText: String = "Tap Here"

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10f
            color = Color.parseColor("#EA580C")
        }
        private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 22f
            color = Color.parseColor("#66FDBA74")
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#26EA580C")
        }
        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#EA580C")
        }
        private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.DEFAULT_BOLD
        }

        fun updateHighlightRect(
            rect: Rect,
            label: String,
            isCorrection: Boolean,
            isConfirmSend: Boolean
        ) {
            targetRect = RectF(rect)
            val primaryColor = when {
                isCorrection -> Color.parseColor("#DC2626")
                isConfirmSend -> Color.parseColor("#15803D")
                else -> Color.parseColor("#EA580C")
            }
            val glowColor = when {
                isCorrection -> Color.parseColor("#77FCA5A5")
                isConfirmSend -> Color.parseColor("#7786EFAC")
                else -> Color.parseColor("#66FDBA74")
            }
            val fillColor = when {
                isCorrection -> Color.parseColor("#2EDC2626")
                isConfirmSend -> Color.parseColor("#2615803D")
                else -> Color.parseColor("#26EA580C")
            }

            strokePaint.color = primaryColor
            outerGlowPaint.color = glowColor
            fillPaint.color = fillColor
            badgeBgPaint.color = primaryColor

            val trimmed = label.take(28).ifBlank { "Here" }
            calloutText = when {
                isCorrection -> "⚠️ Tap: $trimmed"
                isConfirmSend -> "✋ Ready: Tap $trimmed"
                else -> "👆 Tap: $trimmed"
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val r = targetRect ?: return
            val pad = 12f
            val left = r.left - pad
            val top = r.top - pad
            val right = r.right + pad
            val bottom = r.bottom + pad

            canvas.drawRoundRect(left, top, right, bottom, 28f, 28f, fillPaint)
            canvas.drawRoundRect(left, top, right, bottom, 28f, 28f, outerGlowPaint)
            canvas.drawRoundRect(left, top, right, bottom, 28f, 28f, strokePaint)

            val textWidth = badgeTextPaint.measureText(calloutText)
            val pillH = 68f
            val pillW = textWidth + 48f
            val pillLeft = left.coerceIn(16f, (width - pillW - 16f).coerceAtLeast(16f))
            val pillTop = if (top - pillH - 14f > 100f) {
                top - pillH - 12f
            } else {
                (bottom + 12f).coerceAtMost(height - pillH - 16f)
            }
            val pillRect = RectF(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH)
            canvas.drawRoundRect(pillRect, 34f, 34f, badgeBgPaint)
            val textY = pillRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
            canvas.drawText(calloutText, pillRect.left + 24f, textY, badgeTextPaint)
        }
    }

    data class ScreenInspectionResult(
        val packageName: String,
        val visibleLabels: List<String>,
        val nodeBoundsByLabel: Map<String, Rect>
    )

    private data class OverlayRenderState(
        val visible: Boolean,
        val highlightEnabled: Boolean,
        val step: AiGuideStep?,
        val stepMonitorBanner: String?,
        val highlightBounds: Rect?,
        val liveState: GeminiLiveUiState,
        val isScreenTaskActive: Boolean,
        val isGeminiLiveScreenVisible: Boolean,
        val pendingConfirmationAction: AssistiveScreenAction?,
        val activeScreenPackage: String,
        val sessionTaskHistory: List<CompletedSessionStep>,
        val isTaskHistoryDrawerOpen: Boolean,
        val isKindGuideActivityResumed: Boolean
    )

    companion object {
        private const val TAG = "ScreenGuideService"
        private val OVERLAY_PILL_LABELS = setOf(
            "Highlight ON",
            "Highlight OFF",
            "Tap For Me",
            "Confirm & Tap",
            "✅ Send Now",
            "✕ Cancel",
            "📋 History",
            "Clear",
            "Hide ▲",
            "Live",
            "Open",
            "✕"
        )

        private val KNOWN_APP_PACKAGES = mapOf(
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "wa business" to listOf("com.whatsapp.w4b"),
            "chrome" to listOf("com.android.chrome", "com.chrome.beta", "org.chromium.chrome"),
            "browser" to listOf("com.android.chrome", "com.sec.android.app.sbrowser"),
            "google" to listOf("com.google.android.googlequicksearchbox", "com.android.chrome"),
            "youtube" to listOf("com.google.android.youtube"),
            "maps" to listOf("com.google.android.apps.maps"),
            "google maps" to listOf("com.google.android.apps.maps"),
            "gmail" to listOf("com.google.android.gm"),
            "settings" to listOf("com.android.settings"),
            "phone" to listOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer"),
            "contacts" to listOf("com.google.android.contacts", "com.android.contacts"),
            "messages" to listOf("com.google.android.apps.messaging", "com.android.mms"),
            "camera" to listOf("com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera"),
            "photos" to listOf("com.google.android.apps.photos", "com.sec.android.gallery3d"),
            "gallery" to listOf("com.sec.android.gallery3d", "com.google.android.apps.photos"),
            "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
            "instagram" to listOf("com.instagram.android"),
            "gpay" to listOf("com.google.android.apps.nbu.paisa.user"),
            "google pay" to listOf("com.google.android.apps.nbu.paisa.user"),
            "phonepe" to listOf("com.phonepe.app"),
            "paytm" to listOf("net.one97.paytm"),
            "play store" to listOf("com.android.vending")
        )

        fun launchAppByName(context: Context, rawAppName: String): Boolean {
            val query = rawAppName.trim().lowercase()
            if (query.isBlank()) return false
            val pm = context.packageManager

            for ((alias, pkgCandidates) in KNOWN_APP_PACKAGES) {
                if (query == alias || query.contains(alias) || alias.contains(query)) {
                    for (pkg in pkgCandidates) {
                        val launchIntent = runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            if (runCatching { context.startActivity(launchIntent) }.isSuccess) {
                                return true
                            }
                        }
                    }
                }
            }

            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = runCatching { pm.queryIntentActivities(mainIntent, 0) }.getOrNull().orEmpty()
            val matchedInfo = resolveInfos.firstOrNull { info ->
                val label = info.loadLabel(pm)?.toString()?.trim()?.lowercase().orEmpty()
                val pkg = info.activityInfo?.packageName?.lowercase().orEmpty()
                label == query || label.contains(query) || query.contains(label) || pkg.contains(query)
            }
            if (matchedInfo != null) {
                val pkg = matchedInfo.activityInfo.packageName
                val launchIntent = pm.getLaunchIntentForPackage(pkg) ?: Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    component = ComponentName(pkg, matchedInfo.activityInfo.name)
                }
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (runCatching { context.startActivity(launchIntent) }.isSuccess) {
                    return true
                }
            }

            if (query.contains("setting")) {
                val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (runCatching { context.startActivity(settingsIntent) }.isSuccess) {
                    return true
                }
            }

            return false
        }

        fun launchWebSearchInBrowser(context: Context, searchQuery: String): Boolean {
            val cleanQuery = searchQuery.trim()
            if (cleanQuery.isBlank()) {
                return launchAppByName(context, "Chrome")
            }
            val encoded = Uri.encode(cleanQuery)
            val searchUri = Uri.parse("https://www.google.com/search?q=$encoded")

            val chromeIntent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(chromeIntent) }.isSuccess) {
                return true
            }

            val webSearchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, cleanQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (runCatching { context.startActivity(webSearchIntent) }.isSuccess) {
                return true
            }

            val browserIntent = Intent(Intent.ACTION_VIEW, searchUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return runCatching { context.startActivity(browserIntent) }.isSuccess
        }

        fun resolveHumanReadableAppName(context: Context?, packageName: String): String {
            if (packageName.isBlank()) return "Unknown Screen"
            val ownPkg = context?.packageName.orEmpty()
            if (packageName == ownPkg) {
                return "KindGuide App (Internal AI Assistant Screen — NOT WhatsApp or Chrome)"
            }
            if (packageName.contains("launcher", ignoreCase = true) ||
                packageName.contains("trebuchet", ignoreCase = true) ||
                packageName.contains("nexuslauncher", ignoreCase = true) ||
                packageName.contains("miui.home", ignoreCase = true)
            ) {
                return "Android Home Screen Launcher ($packageName)"
            }
            if (packageName == "com.whatsapp") return "WhatsApp ($packageName)"
            if (packageName == "com.whatsapp.w4b") return "WhatsApp Business ($packageName)"
            if (packageName.contains("chrome", ignoreCase = true)) return "Google Chrome Browser ($packageName)"
            if (packageName.contains("youtube", ignoreCase = true)) return "YouTube ($packageName)"

            if (context != null) {
                val pm = context.packageManager
                val appLabel = runCatching {
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    pm.getApplicationLabel(appInfo).toString()
                }.getOrNull()
                if (!appLabel.isNullOrBlank()) {
                    return "$appLabel ($packageName)"
                }
            }
            return packageName
        }

        /**
         * Strictly collects ONLY interactive (`isClickable` or `isEditable` or inside a clickable row)
         * UI controls belonging to `expectedPackage` with concise labels (`<= 42` chars).
         * Never collects long chat paragraphs or non-interactive text views!
         */
        fun collectInteractiveNodes(
            node: AccessibilityNodeInfo?,
            expectedPackage: String = "",
            labels: MutableList<String>,
            boundsByLabel: MutableMap<String, Rect>,
            depth: Int = 0,
            clickableParentBounds: Rect? = null,
            maxScreenWidth: Int = 1440,
            maxScreenHeight: Int = 3200
        ) {
            if (node == null || depth > 16 || labels.size >= 110) return
            if (node.isPassword || !node.isVisibleToUser) return
            val nodePkg = node.packageName?.toString().orEmpty()
            if (expectedPackage.isNotBlank() && nodePkg.isNotBlank() && nodePkg != expectedPackage) {
                return
            }

            val selfBounds = Rect()
            node.getBoundsInScreen(selfBounds)

            val isReasonableButtonSize = { r: Rect ->
                !r.isEmpty &&
                    r.width() in 18..maxScreenWidth &&
                    r.height() in 18..(maxScreenHeight * 0.25f).toInt()
            }

            val isInteractiveSelf =
                node.isClickable || node.isEditable || node.isCheckable
            val inheritedClickableBounds = when {
                isInteractiveSelf && isReasonableButtonSize(selfBounds) -> Rect(selfBounds)
                clickableParentBounds != null && isReasonableButtonSize(clickableParentBounds) ->
                    clickableParentBounds
                else -> null
            }

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.trim().orEmpty()
            } else {
                ""
            }
            val label = when {
                text.isNotBlank() -> text
                desc.isNotBlank() -> desc
                hintText.isNotBlank() -> hintText
                else -> ""
            }

            // Only register highlightable bounds if this element (or its row container) is genuinely clickable/editable
            // and has a concise button/contact label (<= 42 chars, never a multi-sentence chat bubble!)
            if (label.isNotBlank() &&
                label.length <= 42 &&
                label !in OVERLAY_PILL_LABELS &&
                !label.startsWith("✋") &&
                !label.startsWith("✅") &&
                !label.startsWith("📋") &&
                inheritedClickableBounds != null
            ) {
                labels.add(label)
                boundsByLabel.putIfAbsent(label, Rect(inheritedClickableBounds))
            }

            for (i in 0 until node.childCount) {
                collectInteractiveNodes(
                    node = node.getChild(i),
                    expectedPackage = expectedPackage,
                    labels = labels,
                    boundsByLabel = boundsByLabel,
                    depth = depth + 1,
                    clickableParentBounds = inheritedClickableBounds,
                    maxScreenWidth = maxScreenWidth,
                    maxScreenHeight = maxScreenHeight
                )
            }
        }

        fun isEnabled(context: Context): Boolean {
            val expectedComponent = ComponentName(context, ScreenGuideAccessibilityService::class.java)
            val expectedFlat = expectedComponent.flattenToString()
            val expectedShort = expectedComponent.flattenToShortString()

            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()

            if (enabledServices.split(':').any {
                    it.equals(expectedFlat, ignoreCase = true) ||
                        it.equals(expectedShort, ignoreCase = true)
                }
            ) {
                return true
            }

            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            val running = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .orEmpty()
            return running.any { info ->
                val svc = info.resolveInfo?.serviceInfo
                svc?.packageName == context.packageName &&
                    svc.name == ScreenGuideAccessibilityService::class.java.name
            }
        }

        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}
