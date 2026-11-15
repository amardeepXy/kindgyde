package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity
import com.example.ai.AiGuideStep
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
 * Play-Policy-compliant AccessibilityService that powers KindGuide's real-time screen inspection
 * and cross-app floating overlay (`WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY`).
 *
 * Capabilities:
 * 1. Screen Inspection: Traverses active application windows and dialogs (including Android Share
 *    sheets) to extract visible labels and clickable target bounds while strictly ignoring
 *    password fields (`node.isPassword`).
 * 2. Floating Overlay Permission via `TYPE_ACCESSIBILITY_OVERLAY`: Renders a draggable top/bottom
 *    floating coach bar and a non-blocking `SpotlightOverlayView` callout ring over target buttons
 *    without requiring `SYSTEM_ALERT_WINDOW`.
 * 3. On-Demand & Live Fallbacks: Captures Android 11+ (`takeScreenshot`) snapshots when requested
 *    by the senior or when Gemini Live is guiding across apps without MediaProjection active.
 */
class ScreenGuideAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    private var coachBarView: View? = null
    private var coachBarLayoutParams: WindowManager.LayoutParams? = null
    private var spotlightView: SpotlightOverlayView? = null

    private var titleView: TextView? = null
    private var stepTextView: TextView? = null
    private var nextButton: Button? = null
    private var repeatButton: Button? = null
    private var simplerButton: Button? = null
    private var toggleCollapseButton: Button? = null
    private var actionRow: LinearLayout? = null
    private var isCollapsed = false
    private var liveOverlayDismissedByUser = false
    private var wasLiveActive = false
    private var lastScreenshotAtMs = 0L
    private var liveScreenshotFallbackJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Ensure interactive windows and view IDs are enabled for thorough screen inspection
        serviceInfo = serviceInfo?.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }

        GuideSessionCoordinator.setAccessibilityConnected(true)
        GuideSessionCoordinator.registerScreenSnapshotRequester { captureCurrentScreenSnapshot() }

        serviceScope.launch {
            combine(
                GuideSessionCoordinator.isOverlayVisible,
                GuideSessionCoordinator.activeStep,
                GuideSessionCoordinator.isBusy,
                GuideSessionCoordinator.lastReply,
                GuideSessionCoordinator.targetHighlightBounds,
                GeminiLiveManager.state
            ) { values ->
                val visible = values[0] as Boolean
                val step = values[1] as AiGuideStep?
                val busy = values[2] as Boolean
                val reply = values[3] as String
                val bounds = values[4] as Rect?
                val liveState = values[5] as GeminiLiveUiState
                OverlayRenderState(visible, step, busy, reply, bounds, liveState)
            }.collect { state ->
                if (state.liveState.isActive && !wasLiveActive) {
                    liveOverlayDismissedByUser = false
                }
                wasLiveActive = state.liveState.isActive
                manageLiveScreenshotFallback(state.liveState)
                renderOverlay(state)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val inspection = inspectActiveScreen() ?: return
        GuideSessionCoordinator.updateLiveScreenNodes(
            packageName = inspection.packageName,
            boundsByLabel = inspection.nodeBoundsByLabel
        )
    }

    override fun onInterrupt() {
        liveScreenshotFallbackJob?.cancel()
        removeOverlays()
    }

    override fun onDestroy() {
        super.onDestroy()
        liveScreenshotFallbackJob?.cancel()
        GuideSessionCoordinator.registerScreenSnapshotRequester(null)
        GuideSessionCoordinator.setAccessibilityConnected(false)
        removeOverlays()
        serviceScope.cancel()
        screenshotExecutor.shutdown()
    }

    private fun manageLiveScreenshotFallback(liveState: GeminiLiveUiState) {
        val shouldRunFallback = liveState.isActive &&
            !liveState.isScreenSharing &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        if (shouldRunFallback && liveScreenshotFallbackJob?.isActive != true) {
            liveScreenshotFallbackJob = serviceScope.launch {
                while (isActive) {
                    delay(2600L)
                    val cur = GeminiLiveManager.state.value
                    if (cur.isActive && !cur.isScreenSharing) {
                        val pkg = GuideSessionCoordinator.activeScreenPackage.value
                        if (pkg.isNotBlank() && pkg != packageName) {
                            val bmp = takeAccessibilityScreenshot()
                            if (bmp != null) {
                                GeminiLiveManager.sendScreenFrame(bmp, sourceLabel = "Accessibility")
                                bmp.recycle()
                            }
                        }
                    }
                }
            }
        } else if (!shouldRunFallback) {
            liveScreenshotFallbackJob?.cancel()
            liveScreenshotFallbackJob = null
        }
    }

    private fun renderOverlay(state: OverlayRenderState) {
        val liveState = state.liveState
        val showForLive = liveState.isActive && !liveOverlayDismissedByUser
        val shouldShow = state.visible || showForLive

        if (!shouldShow) {
            removeOverlays()
            return
        }
        val step = state.step
        ensureSpotlightOverlay(
            bounds = state.highlightBounds,
            calloutLabel = step?.targetButtonHint?.ifBlank { "Tap Here" } ?: "Tap Here"
        )
        ensureCoachBar()

        if (liveState.isActive) {
            val phaseLabel = when (liveState.phase) {
                LivePhase.CONNECTING -> "Connecting Live..."
                LivePhase.LISTENING -> if (liveState.isScreenSharing) "Live • Screen Shared" else "Live • Listening"
                LivePhase.AI_SPEAKING -> "Gemini Speaking (Interrupt anytime)"
                LivePhase.INTERRUPTED -> "Interrupted — Listening"
                else -> "Gemini Live"
            }
            titleView?.text = if (step != null) {
                "$phaseLabel • Step ${step.stepNumber}/${step.totalSteps}"
            } else {
                "KindGuide • $phaseLabel"
            }
            stepTextView?.text = when {
                step != null -> step.instruction
                liveState.liveAiCaption.isNotBlank() -> liveState.liveAiCaption
                !liveState.statusNotice.isNullOrBlank() -> liveState.statusNotice
                else -> "Speak naturally — I can guide you across any app."
            }
            nextButton?.isEnabled = true
            nextButton?.text = if (liveState.isMuted) "Unmute Mic" else "Mute Mic"
            repeatButton?.isEnabled = true
            repeatButton?.text = "Open Live"
            simplerButton?.isEnabled = true
            simplerButton?.text = "End Live"
        } else {
            titleView?.text = if (step != null) {
                "KindGuide • Step ${step.stepNumber} of ${step.totalSteps}: ${step.stepTitle}"
            } else {
                "KindGuide • Live Screen Coach (Drag to move)"
            }
            stepTextView?.text = when {
                state.busy -> "Looking at your screen carefully..."
                step != null -> step.instruction
                state.reply.isNotBlank() -> state.reply
                else -> "Open any app and tap Check Screen for your next step."
            }
            nextButton?.isEnabled = !state.busy
            nextButton?.text = if (state.busy) "Checking..." else "Next / Check Screen"
            repeatButton?.isEnabled = !state.busy
            repeatButton?.text = "Repeat"
            simplerButton?.isEnabled = !state.busy
            simplerButton?.text = "Simpler"
        }
    }

    private fun ensureSpotlightOverlay(bounds: Rect?, calloutLabel: String) {
        if (bounds == null) {
            spotlightView?.let {
                runCatching { windowManager.removeView(it) }
                spotlightView = null
            }
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
        spotlightView?.updateHighlightRect(bounds, calloutLabel)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureCoachBar() {
        if (coachBarView != null) return

        val dp = { v: Int ->
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                v.toFloat(),
                resources.displayMetrics
            ).toInt()
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1917"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(2), Color.parseColor("#EA580C"))
            }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            setTextColor(Color.parseColor("#FDBA74"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleView = title

        val collapseBtn = Button(this).apply {
            text = "Hide"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#44403C"))
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener {
                isCollapsed = !isCollapsed
                actionRow?.visibility = if (isCollapsed) View.GONE else View.VISIBLE
                stepTextView?.maxLines = if (isCollapsed) 1 else 5
                text = if (isCollapsed) "Show" else "Hide"
            }
        }
        toggleCollapseButton = collapseBtn

        val closeBtn = Button(this).apply {
            text = "Close"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#991B1B"))
                cornerRadius = dp(12).toFloat()
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
            layoutParams = lp
            setOnClickListener {
                if (GeminiLiveManager.state.value.isActive) {
                    liveOverlayDismissedByUser = true
                }
                GuideSessionCoordinator.setOverlayVisible(false)
            }
        }

        headerRow.addView(title)
        headerRow.addView(collapseBtn)
        headerRow.addView(closeBtn)

        val stepTv = TextView(this).apply {
            setTextColor(Color.parseColor("#FFFBF5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(0, dp(8), 0, dp(10))
            maxLines = 5
        }
        stepTextView = stepTv

        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actionRow = buttonsRow

        val nextBtn = Button(this).apply {
            text = "Next / Check Screen"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#15803D"))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
            setOnClickListener {
                if (GeminiLiveManager.state.value.isActive) {
                    val muted = GeminiLiveManager.toggleMute()
                    text = if (muted) "Unmute Mic" else "Mute Mic"
                } else {
                    GuideSessionCoordinator.advanceStepWithLiveScreen()
                }
            }
        }
        nextButton = nextBtn

        val repeatBtn = Button(this).apply {
            text = "Repeat"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#C2410C"))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f).apply {
                marginStart = dp(8)
            }
            setOnClickListener {
                if (GeminiLiveManager.state.value.isActive) {
                    val openIntent = Intent(this@ScreenGuideAccessibilityService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                        putExtra(GeminiLiveForegroundService.EXTRA_OPEN_LIVE, true)
                    }
                    runCatching { startActivity(openIntent) }
                } else {
                    GuideSessionCoordinator.repeatCurrentStepSlowly()
                }
            }
        }
        repeatButton = repeatBtn

        val simplerBtn = Button(this).apply {
            text = "Simpler"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B45309"))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.9f).apply {
                marginStart = dp(8)
            }
            setOnClickListener {
                if (GeminiLiveManager.state.value.isActive) {
                    GeminiLiveForegroundService.stopAll(this@ScreenGuideAccessibilityService)
                    GuideSessionCoordinator.setOverlayVisible(false)
                } else {
                    GuideSessionCoordinator.explainCurrentStepDifferently()
                }
            }
        }
        simplerButton = simplerBtn

        buttonsRow.addView(nextBtn)
        buttonsRow.addView(repeatBtn)
        buttonsRow.addView(simplerBtn)

        container.addView(headerRow)
        container.addView(stepTv)
        container.addView(buttonsRow)

        val wrapper = LinearLayout(this).apply {
            setPadding(dp(12), dp(36), dp(12), dp(8))
            addView(
                container,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 0
        }
        coachBarLayoutParams = params

        // Allow vertical dragging of the floating coach bar so seniors can move it if it covers a button
        var initialY = 0
        var touchStartY = 0f
        var dragging = false
        val dragListener = View.OnTouchListener { _, event ->
            val lp = coachBarLayoutParams ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = lp.y
                    touchStartY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - touchStartY).toInt()
                    if (abs(dy) > dp(6)) {
                        dragging = true
                        val maxOffset = (resources.displayMetrics.heightPixels - dp(180)).coerceAtLeast(0)
                        lp.y = (initialY + dy).coerceIn(0, maxOffset)
                        coachBarView?.let { v ->
                            runCatching { windowManager.updateViewLayout(v, lp) }
                        }
                    }
                    dragging
                }
                else -> dragging
            }
        }
        headerRow.setOnTouchListener(dragListener)
        title.setOnTouchListener(dragListener)

        runCatching {
            windowManager.addView(wrapper, params)
            coachBarView = wrapper
        }.onFailure {
            Log.e(TAG, "Failed to attach KindGuide floating overlay", it)
        }
    }

    private fun removeOverlays() {
        coachBarView?.let {
            runCatching { windowManager.removeView(it) }
            coachBarView = null
        }
        coachBarLayoutParams = null
        spotlightView?.let {
            runCatching { windowManager.removeView(it) }
            spotlightView = null
        }
    }

    private fun inspectActiveScreen(): ScreenInspectionResult? {
        val labels = mutableListOf<String>()
        val bounds = linkedMapOf<String, Rect>()
        var detectedPackage = ""

        // Inspect interactive windows (application + share sheets/system dialogs)
        val interactiveWindows = runCatching { windows }.getOrNull().orEmpty()
        for (window in interactiveWindows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                window.type != AccessibilityWindowInfo.TYPE_SYSTEM
            ) {
                continue
            }
            val root = window.root ?: continue
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg.isBlank() || pkg == packageName || pkg == "com.android.systemui") continue
            if (detectedPackage.isBlank()) {
                detectedPackage = pkg
            }
            collectInteractiveNodes(root, labels, bounds, 0)
        }

        // Fallback to rootInActiveWindow if windows list was empty
        if (labels.isEmpty()) {
            val activeRoot = rootInActiveWindow ?: return null
            val pkg = activeRoot.packageName?.toString().orEmpty()
            if (pkg.isBlank() || pkg == packageName || pkg == "com.android.systemui") return null
            detectedPackage = pkg
            collectInteractiveNodes(activeRoot, labels, bounds, 0)
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
        val screenshotBitmap = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            now - lastScreenshotAtMs >= 1100L
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
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val hwBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        val argbBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        hwBitmap?.recycle()
                        buffer.close()
                        if (cont.isActive) cont.resume(argbBitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot failed with errorCode=$errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        }
    }

    private class SpotlightOverlayView(context: Context) : View(context) {
        private var targetRect: Rect? = null
        private var calloutText: String = "Tap Here"

        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EA580C")
            style = Paint.Style.STROKE
            strokeWidth = 10f
        }
        private val outerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#55FDBA74")
            style = Paint.Style.STROKE
            strokeWidth = 18f
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#26EA580C")
            style = Paint.Style.FILL
        }
        private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EA580C")
            style = Paint.Style.FILL
        }
        private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                14f,
                resources.displayMetrics
            )
            typeface = Typeface.DEFAULT_BOLD
        }

        fun updateHighlightRect(rect: Rect?, label: String) {
            targetRect = rect
            calloutText = if (label.isNotBlank()) "Tap: ${label.take(28)}" else "Tap Here"
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

            // Draw high-contrast callout pill above (or below if near top of screen)
            val textWidth = badgeTextPaint.measureText(calloutText)
            val pillH = 64f
            val pillW = textWidth + 44f
            val pillLeft = left.coerceIn(16f, (width - pillW - 16f).coerceAtLeast(16f))
            val pillTop = if (top - pillH - 14f > 100f) {
                top - pillH - 12f
            } else {
                (bottom + 12f).coerceAtMost(height - pillH - 16f)
            }
            val pillRect = RectF(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH)
            canvas.drawRoundRect(pillRect, 32f, 32f, badgeBgPaint)
            val textY = pillRect.centerY() - (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
            canvas.drawText(calloutText, pillRect.left + 22f, textY, badgeTextPaint)
        }
    }

    data class ScreenInspectionResult(
        val packageName: String,
        val visibleLabels: List<String>,
        val nodeBoundsByLabel: Map<String, Rect>
    )

    private data class OverlayRenderState(
        val visible: Boolean,
        val step: AiGuideStep?,
        val busy: Boolean,
        val reply: String,
        val highlightBounds: Rect?,
        val liveState: GeminiLiveUiState
    )

    companion object {
        private const val TAG = "ScreenGuideService"

        /**
         * Traverses an AccessibilityNodeInfo hierarchy, collecting visible text/contentDescriptions
         * and clickable/focusable screen bounds while strictly filtering out password nodes.
         */
        fun collectInteractiveNodes(
            node: AccessibilityNodeInfo?,
            labels: MutableList<String>,
            boundsByLabel: MutableMap<String, Rect>,
            depth: Int = 0
        ) {
            if (node == null || depth > 14 || labels.size >= 80) return
            // Privacy guard: never inspect password inputs
            if (node.isPassword) return

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
            if (label.isNotBlank() && label.length <= 60) {
                labels.add(label)
                if (node.isClickable || node.isFocusable || node.isEditable || node.isCheckable) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (!rect.isEmpty) {
                        boundsByLabel.putIfAbsent(label, rect)
                    }
                }
            }
            for (i in 0 until node.childCount) {
                collectInteractiveNodes(node.getChild(i), labels, boundsByLabel, depth + 1)
            }
        }

        /**
         * Checks whether `ScreenGuideAccessibilityService` is enabled in system Accessibility settings,
         * which simultaneously grants screen inspection (`canRetrieveWindowContent` / `canTakeScreenshot`)
         * and floating overlay (`TYPE_ACCESSIBILITY_OVERLAY`) permissions.
         */
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

        /**
         * Launches the Android system Accessibility settings screen so the user or caregiver
         * can toggle on "KindGuide On-Screen Coach".
         */
        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }
}
