package com.example.ui.components

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ai.AiGuideStep
import com.example.ai.CompletedSessionStep
import com.example.ai.GeminiLiveManager
import com.example.ai.GeminiLiveUiState
import com.example.ai.GuideSessionCoordinator
import com.example.service.ScreenGuideAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Monitors the current Activity window on-demand:
 * - Always keeps `GuideSessionCoordinator` aware that KindGuide is the active foreground package
 *   when the user is inside KindGuide (so it never confuses KindGuide with WhatsApp or Chrome,
 *   and hides the cross-app floating pill while inside KindGuide).
 * - Syncs `showGeminiLiveScreen` with `GuideSessionCoordinator.setGeminiLiveScreenVisible(...)`.
 * - NEVER streams screenshots by default during normal voice conversations.
 * - Only inspects/streams screenshots when a screen-guided task is active (`isScreenTaskActive` or
 *   `activeStep != null`) or when the user explicitly turns on Screen Share.
 */
@Composable
fun LiveInAppScreenMonitorEffect(
    liveState: GeminiLiveUiState,
    activeStep: AiGuideStep?,
    isOverlayVisible: Boolean,
    showGeminiLiveScreen: Boolean
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val isScreenTaskActive by GuideSessionCoordinator.isScreenTaskActive.collectAsStateWithLifecycle()

    LaunchedEffect(showGeminiLiveScreen) {
        GuideSessionCoordinator.setGeminiLiveScreenVisible(showGeminiLiveScreen)
    }

    DisposableEffect(activity) {
        if (activity != null) {
            GuideSessionCoordinator.onInAppWindowResumed(context.packageName)
            GuideSessionCoordinator.registerInAppScreenshotProvider {
                captureDecorViewBitmapSync(activity)
            }
        }
        onDispose {
            GuideSessionCoordinator.registerInAppScreenshotProvider(null)
            GuideSessionCoordinator.setGeminiLiveScreenVisible(false)
        }
    }

    val shouldMonitorScreenTask = isScreenTaskActive ||
        activeStep != null ||
        liveState.isScreenSharing ||
        isOverlayVisible

    LaunchedEffect(activity, liveState.isActive, shouldMonitorScreenTask, showGeminiLiveScreen) {
        if (activity == null) return@LaunchedEffect
        // Always record that KindGuide is the active foreground window when inside MainActivity
        GuideSessionCoordinator.onInAppWindowResumed(context.packageName)

        if (!shouldMonitorScreenTask && !liveState.isActive) return@LaunchedEffect

        var lastFrameSentMs = 0L
        while (isActive) {
            // Never traverse the view hierarchy or capture window bitmaps on the UI thread
            // while Gemini Live is actively speaking, so audio playback stays crystal clear!
            if (liveState.phase == com.example.ai.LivePhase.AI_SPEAKING) {
                delay(650L)
                continue
            }

            if (shouldMonitorScreenTask) {
                val bounds = collectActivityNodeBounds(activity)
                GuideSessionCoordinator.updateInAppScreenNodes(
                    packageName = context.packageName,
                    boundsByLabel = bounds
                )
            } else {
                // Keep foreground package identity updated without scanning or capturing screen
                GuideSessionCoordinator.onInAppWindowResumed(context.packageName)
            }

            val now = System.currentTimeMillis()
            // Strictly on-demand: ONLY stream in-app frames when a screen task is active
            val shouldStreamInAppFrame = liveState.isActive &&
                (isScreenTaskActive || activeStep != null) &&
                !liveState.isScreenSharing &&
                !liveState.isCameraSharing &&
                now - lastFrameSentMs >= 2200L
            if (shouldStreamInAppFrame) {
                lastFrameSentMs = now
                val bmp = captureActivityWindowBitmap(activity)
                if (bmp != null) {
                    GeminiLiveManager.sendScreenFrame(
                        bmp,
                        sourceLabel = "KindGuide App (Internal AI Assistant Screen)"
                    )
                    bmp.recycle()
                }
            }
            delay(900L)
        }
    }
}

/**
 * Host wrapper for the main application content.
 *
 * Per user feedback, there is NO duplicate in-app floating step banner (`Step 1/3 | Tap | Next`)
 * or in-app spotlight canvas inside KindGuide.
 * However, when the AI has completed steps in the current session (or the user opens the
 * 'Task History' drawer), a compact, movable floating 'Task History' pill + scrollable drawer
 * lets the senior review every completed step at any time.
 */
@Composable
fun LiveScreenCoPilotOverlayHost(
    liveState: GeminiLiveUiState,
    @Suppress("UNUSED_PARAMETER") activeStep: AiGuideStep?,
    @Suppress("UNUSED_PARAMETER") stepMonitorBanner: String?,
    @Suppress("UNUSED_PARAMETER") targetHighlightBounds: Rect?,
    @Suppress("UNUSED_PARAMETER") isHighlightEnabled: Boolean,
    @Suppress("UNUSED_PARAMETER") isAccessibilityConnected: Boolean,
    showGeminiLiveScreen: Boolean,
    @Suppress("UNUSED_PARAMETER") onOpenLiveScreen: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onToggleHighlight: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onAdvanceStep: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onDismissGuidance: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val sessionTaskHistory by GuideSessionCoordinator.sessionTaskHistory.collectAsStateWithLifecycle()
    val isTaskHistoryDrawerOpen by GuideSessionCoordinator.isTaskHistoryDrawerOpen.collectAsStateWithLifecycle()

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    Box(modifier = modifier.fillMaxSize()) {
        content()

        val showFloatingTaskHistory = !showGeminiLiveScreen &&
            (sessionTaskHistory.isNotEmpty() || isTaskHistoryDrawerOpen || liveState.isActive)

        AnimatedVisibility(
            visible = showFloatingTaskHistory,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 68.dp, end = 12.dp, start = 12.dp)
                .offset {
                    IntOffset(
                        x = dragOffsetX.roundToInt(),
                        y = dragOffsetY.roundToInt()
                    )
                }
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Draggable floating pill button to toggle the persistent 'Task History' drawer
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = if (isTaskHistoryDrawerOpen) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    shadowElevation = 8.dp,
                    border = BorderStroke(
                        1.dp,
                        if (isTaskHistoryDrawerOpen) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                    ),
                    modifier = Modifier
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                dragOffsetX += dragAmount.x
                                dragOffsetY += dragAmount.y
                            }
                        }
                        .testTag("floating_task_history_pill")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DragIndicator,
                            contentDescription = "Drag floating Task History pill",
                            tint = if (isTaskHistoryDrawerOpen) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                            modifier = Modifier.size(16.dp)
                        )
                        TextButton(
                            onClick = { GuideSessionCoordinator.toggleTaskHistoryDrawer() },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (isTaskHistoryDrawerOpen) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            ),
                            modifier = Modifier.testTag("toggle_task_history_drawer_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Checklist,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (sessionTaskHistory.isNotEmpty()) {
                                    "Task History (${sessionTaskHistory.size})"
                                } else {
                                    "Task History"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = if (isTaskHistoryDrawerOpen) {
                                    Icons.Filled.ExpandLess
                                } else {
                                    Icons.Filled.ExpandMore
                                },
                                contentDescription = if (isTaskHistoryDrawerOpen) "Collapse" else "Expand",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = isTaskHistoryDrawerOpen) {
                    SessionTaskHistoryDrawerCard(
                        steps = sessionTaskHistory,
                        onClearHistory = { GuideSessionCoordinator.clearSessionTaskHistory() },
                        onCloseDrawer = { GuideSessionCoordinator.setTaskHistoryDrawerOpen(false) }
                    )
                }
            }
        }
    }
}

/**
 * Persistent, scrollable 'Task History' drawer displaying all steps the AI has completed
 * in the current session, usable from both the floating overlay and `GeminiLiveScreen`.
 */
@Composable
fun SessionTaskHistoryDrawerCard(
    steps: List<CompletedSessionStep>,
    onClearHistory: () -> Unit,
    onCloseDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    LaunchedEffect(steps.size) {
        if (steps.isNotEmpty()) {
            listState.animateScrollToItem((steps.size - 1).coerceAtLeast(0))
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .widthIn(max = 360.dp)
            .fillMaxWidth()
            .testTag("task_history_drawer_card")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Checklist,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Task History (${steps.size} ${if (steps.size == 1) "step" else "steps"})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (steps.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onClearHistory,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.testTag("clear_task_history_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = "Clear Task History",
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Clear", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    FilledTonalButton(
                        onClick = onCloseDrawer,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("close_task_history_drawer_button")
                    ) {
                        Text("Hide ▲", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (steps.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No steps completed in this session yet.\nAsk KindGuide to open an app, send a message, or search the web!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .testTag("task_history_scrollable_list"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(steps, key = { it.id }) { step ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (step.succeeded) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                            } else {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
                            },
                            border = BorderStroke(
                                1.dp,
                                if (step.succeeded) {
                                    MaterialTheme.colorScheme.outlineVariant
                                } else {
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("task_history_item_${step.stepIndex}")
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "${step.statusIcon} Step ${step.stepIndex} • ${step.appName} • ${step.timestampLabel}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (step.succeeded) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                )
                                Text(
                                    text = step.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (step.detail.isNotBlank()) {
                                    Text(
                                        text = step.detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun collectActivityNodeBounds(activity: Activity): Map<String, Rect> =
    withContext(Dispatchers.Main) {
        val bounds = linkedMapOf<String, Rect>()
        val labels = mutableListOf<String>()
        val decorView = activity.window?.decorView ?: return@withContext emptyMap()
        val maxW = decorView.width.coerceAtLeast(360)
        val maxH = decorView.height.coerceAtLeast(640)

        // 1. Inspect Compose + View AccessibilityNodeInfo tree directly from decorView
        runCatching {
            val rootNode = decorView.createAccessibilityNodeInfo()
            if (rootNode != null) {
                ScreenGuideAccessibilityService.collectInteractiveNodes(
                    node = rootNode,
                    labels = labels,
                    boundsByLabel = bounds,
                    depth = 0,
                    clickableParentBounds = null,
                    maxScreenWidth = maxW,
                    maxScreenHeight = maxH,
                    expectedPackage = activity.packageName
                )
            }
        }

        // 2. Also walk standard Android View hierarchy as a secondary pass
        runCatching {
            collectAndroidViewBounds(decorView, bounds, null)
        }

        bounds
    }

private fun collectAndroidViewBounds(
    view: View,
    boundsByLabel: MutableMap<String, Rect>,
    clickableParentRect: Rect?
) {
    if (view.visibility != View.VISIBLE || !view.isShown) return
    val loc = IntArray(2)
    view.getLocationOnScreen(loc)
    val selfRect = Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
    val effectiveRect = if (view.isClickable && !selfRect.isEmpty) {
        selfRect
    } else {
        clickableParentRect
    }

    if (effectiveRect != null && !effectiveRect.isEmpty) {
        val desc = view.contentDescription?.toString()?.trim().orEmpty()
        if (desc.isNotBlank() && desc.length <= 42) {
            boundsByLabel.putIfAbsent(desc, Rect(effectiveRect))
        }
        if (view is TextView) {
            val text = view.text?.toString()?.trim().orEmpty()
            if (text.isNotBlank() && text.length <= 42) {
                boundsByLabel.putIfAbsent(text, Rect(effectiveRect))
            }
        }
    }
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            collectAndroidViewBounds(view.getChildAt(i), boundsByLabel, effectiveRect)
        }
    }
}

private suspend fun captureActivityWindowBitmap(activity: Activity): Bitmap? =
    withContext(Dispatchers.Main) {
        val window = activity.window ?: return@withContext null
        val decorView = window.decorView
        val width = decorView.width
        val height = decorView.height
        if (width <= 0 || height <= 0) return@withContext null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val copied = suspendCancellableCoroutine<Boolean> { cont ->
                try {
                    PixelCopy.request(
                        window,
                        bitmap,
                        { copyResult ->
                            if (cont.isActive) {
                                cont.resume(copyResult == PixelCopy.SUCCESS)
                            }
                        },
                        Handler(Looper.getMainLooper())
                    )
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            if (copied) return@withContext bitmap
            bitmap.recycle()
        }

        runCatching {
            val fallback = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = AndroidCanvas(fallback)
            decorView.draw(canvas)
            fallback
        }.getOrNull()
    }

private fun captureDecorViewBitmapSync(activity: Activity): Bitmap? {
    val decorView = activity.window?.decorView ?: return null
    val width = decorView.width
    val height = decorView.height
    if (width <= 0 || height <= 0) return null
    return runCatching {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bmp)
        decorView.draw(canvas)
        bmp
    }.getOrNull()
}
