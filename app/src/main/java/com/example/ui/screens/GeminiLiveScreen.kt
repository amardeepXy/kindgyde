package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.StopScreenShare
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.ai.AiGuideStep
import com.example.ai.GeminiLiveManager
import com.example.ai.GeminiLiveUiState
import com.example.ai.LivePhase
import com.example.ai.LiveTranscriptEntry
import com.example.service.ScreenGuideAccessibilityService
import com.example.ui.components.AccessibilityDisclosureDialog
import java.util.concurrent.Executors

private data class LiveVoiceOption(
    val id: String,
    val label: String
)

private val LIVE_VOICES = listOf(
    LiveVoiceOption("AOEDE", "Warm"),
    LiveVoiceOption("KORE", "Gentle"),
    LiveVoiceOption("PUCK", "Bright"),
    LiveVoiceOption("FENRIR", "Deep")
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GeminiLiveScreen(
    liveState: GeminiLiveUiState,
    activeStep: AiGuideStep?,
    isAccessibilityConnected: Boolean,
    onMinimizeBack: () -> Unit,
    onStartLive: (voiceName: String, shareScreen: Boolean) -> Unit,
    onStopLive: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onToggleCameraShare: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleMute: () -> Unit,
    onManualInterrupt: () -> Unit,
    onSelectVoice: (String) -> Unit,
    onSendNudge: (String) -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var nudgeInput by remember { mutableStateOf("") }
    var showDisclosure by remember { mutableStateOf(false) }
    var showOptionsSheet by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            onToggleCameraShare()
        }
    }

    val itemCount = liveState.transcriptLog.size +
        (if (liveState.liveUserCaption.isNotBlank()) 1 else 0) +
        (if (liveState.liveAiCaption.isNotBlank()) 1 else 0)

    LaunchedEffect(itemCount) {
        if (itemCount > 0) {
            listState.animateScrollToItem((itemCount - 1).coerceAtLeast(0))
        }
    }

    if (showDisclosure) {
        AccessibilityDisclosureDialog(
            onDismiss = { showDisclosure = false },
            onOpenSettings = {
                showDisclosure = false
                ScreenGuideAccessibilityService.openAccessibilitySettings(context)
            }
        )
    }

    if (showOptionsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOptionsSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Live Voice & Tools", style = MaterialTheme.typography.titleLarge)

                Text("Voice Style", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LIVE_VOICES.forEach { voice ->
                        FilterChip(
                            selected = liveState.selectedVoice == voice.id,
                            onClick = { onSelectVoice(voice.id) },
                            label = { Text(voice.label) },
                            modifier = Modifier.testTag("live_voice_${voice.id.lowercase()}")
                        )
                    }
                }

                Text("Quick Prompts", style = MaterialTheme.typography.labelLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "What should I tap on my screen?",
                        "Read what my camera sees",
                        "Explain more simply",
                        "Add a task for today"
                    ).forEach { prompt ->
                        AssistChip(
                            onClick = {
                                onSendNudge(prompt)
                                showOptionsSheet = false
                            },
                            label = { Text(prompt) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = nudgeInput,
                        onValueChange = { nudgeInput = it },
                        placeholder = { Text("Type a note to Gemini Live...") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("live_nudge_input")
                    )
                    FilledIconButton(
                        onClick = {
                            if (nudgeInput.isNotBlank()) {
                                onSendNudge(nudgeInput)
                                nudgeInput = ""
                                showOptionsSheet = false
                            }
                        },
                        enabled = nudgeInput.isNotBlank(),
                        modifier = Modifier
                            .size(50.dp)
                            .testTag("live_nudge_send")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send text to Live")
                    }
                }

                if (!isAccessibilityConnected) {
                    OutlinedButton(
                        onClick = {
                            showOptionsSheet = false
                            showDisclosure = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Layers, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Enable Cross-App Floating Overlay")
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.gemini_live_title),
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1
                        )
                        if (liveState.isActive) {
                            val badgeText = when {
                                liveState.isScreenSharing -> "SCREEN"
                                liveState.isCameraSharing -> "CAMERA"
                                else -> "LIVE"
                            }
                            Surface(
                                color = if (liveState.isScreenSharing || liveState.isCameraSharing) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                },
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    text = badgeText,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (liveState.isScreenSharing || liveState.isCameraSharing) {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onMinimizeBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("live_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Minimize Gemini Live"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showOptionsSheet = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("live_options_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = "Voice and text options"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            GeminiLiveBottomDock(
                liveState = liveState,
                onStartLive = { onStartLive(liveState.selectedVoice, false) },
                onStopLive = onStopLive,
                onToggleScreenShare = onToggleScreenShare,
                onToggleCameraShare = {
                    if (liveState.isCameraSharing) {
                        onToggleCameraShare()
                    } else if (hasCameraPermission) {
                        onToggleCameraShare()
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                onToggleMute = onToggleMute,
                onManualInterrupt = onManualInterrupt
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Visual Stage: Live Camera Preview OR Animated Gemini Live Orb
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(
                        width = 1.dp,
                        color = when (liveState.phase) {
                            LivePhase.AI_SPEAKING -> MaterialTheme.colorScheme.primary
                            LivePhase.INTERRUPTED -> MaterialTheme.colorScheme.tertiary
                            LivePhase.LISTENING -> MaterialTheme.colorScheme.secondary
                            LivePhase.ERROR -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.outline
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (liveState.isCameraSharing && hasCameraPermission) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(MaterialTheme.shapes.medium)
                            ) {
                                LiveCameraPreviewStream(
                                    useFrontCamera = liveState.useFrontCamera,
                                    onFrameCaptured = { bmp ->
                                        GeminiLiveManager.sendScreenFrame(bmp, sourceLabel = "Camera")
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                                FilledTonalIconButton(
                                    onClick = onFlipCamera,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .size(48.dp)
                                        .testTag("live_flip_camera_button")
                                ) {
                                    Icon(
                                        Icons.Filled.Cameraswitch,
                                        contentDescription = "Switch front or back camera"
                                    )
                                }
                            }
                        } else {
                            GeminiLiveOrb(
                                phase = liveState.phase,
                                isMuted = liveState.isMuted,
                                modifier = Modifier.size(104.dp)
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = when (liveState.phase) {
                                LivePhase.CONNECTING -> "Connecting to Gemini Live..."
                                LivePhase.LISTENING -> when {
                                    liveState.isMuted -> "Microphone Muted"
                                    liveState.isCameraSharing -> "Watching Camera & Listening"
                                    liveState.isScreenSharing -> "Watching Screen & Listening"
                                    else -> "Listening — speak naturally"
                                }
                                LivePhase.AI_SPEAKING -> "Speaking — talk anytime to interrupt"
                                LivePhase.INTERRUPTED -> "Interrupted — listening to you"
                                LivePhase.ERROR -> "Live connection paused"
                                LivePhase.IDLE -> "Tap Start below to talk live"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val subStatus = liveState.errorMessage ?: liveState.statusNotice
                        if (!subStatus.isNullOrBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = subStatus,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                color = if (liveState.errorMessage != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        if (liveState.phase == LivePhase.ERROR) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = {
                                        onClearError()
                                        onStartLive(liveState.selectedVoice, false)
                                    },
                                    modifier = Modifier.testTag("live_retry_button")
                                ) {
                                    Text("Try Again")
                                }
                                OutlinedButton(onClick = onClearError) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                }

                // Active Step Banner (if guiding)
                if (activeStep != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Step ${activeStep.stepNumber}/${activeStep.totalSteps} • ${activeStep.stepTitle}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = activeStep.instruction,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (activeStep.targetButtonHint.isNotBlank()) {
                                Text(
                                    text = "Tap: ${activeStep.targetButtonHint}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                } else if (liveState.lastActionBadge != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = liveState.lastActionBadge,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Live Captions & Conversation Log
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    if (itemCount == 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Speak naturally, share your screen for app steps, or tap Camera to show a medicine bottle or letter.",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(liveState.transcriptLog, key = { it.id }) { entry ->
                                TranscriptBubble(entry = entry)
                            }
                            if (liveState.liveUserCaption.isNotBlank()) {
                                item(key = "live_user_caption") {
                                    LiveStreamingBubble(
                                        speakerLabel = "You",
                                        text = liveState.liveUserCaption,
                                        isUser = true
                                    )
                                }
                            }
                            if (liveState.liveAiCaption.isNotBlank()) {
                                item(key = "live_ai_caption") {
                                    LiveStreamingBubble(
                                        speakerLabel = "KindGuide Live",
                                        text = liveState.liveAiCaption,
                                        isUser = false
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

@Composable
private fun LiveCameraPreviewStream(
    useFrontCamera: Boolean,
    onFrameCaptured: (Bitmap) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    var lastAnalyzedMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { analyzerExecutor.shutdown() }
        }
    }

    LaunchedEffect(useFrontCamera, lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val cameraProvider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analyzerExecutor) { imageProxy ->
                        try {
                            val now = System.currentTimeMillis()
                            if (now - lastAnalyzedMs >= 1000L) {
                                lastAnalyzedMs = now
                                val rawBitmap = imageProxy.toBitmap()
                                val rot = imageProxy.imageInfo.rotationDegrees
                                val finalBitmap = if (rot != 0) {
                                    val matrix = Matrix().apply { postRotate(rot.toFloat()) }
                                    Bitmap.createBitmap(
                                        rawBitmap,
                                        0,
                                        0,
                                        rawBitmap.width,
                                        rawBitmap.height,
                                        matrix,
                                        true
                                    ).also {
                                        if (it !== rawBitmap) rawBitmap.recycle()
                                    }
                                } else {
                                    rawBitmap
                                }
                                onFrameCaptured(finalBitmap)
                                finalBitmap.recycle()
                            }
                        } catch (_: Exception) {
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

            val selector = if (useFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

@Composable
private fun GeminiLiveOrb(
    phase: LivePhase,
    isMuted: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "live_orb")
    val pulse by transition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (phase) {
                    LivePhase.AI_SPEAKING -> 580
                    LivePhase.INTERRUPTED -> 420
                    LivePhase.CONNECTING -> 880
                    else -> 1350
                },
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_pulse"
    )

    val coreColor: Color = when {
        isMuted -> MaterialTheme.colorScheme.onSurfaceVariant
        phase == LivePhase.AI_SPEAKING -> MaterialTheme.colorScheme.primary
        phase == LivePhase.INTERRUPTED -> MaterialTheme.colorScheme.tertiary
        phase == LivePhase.LISTENING -> MaterialTheme.colorScheme.secondary
        phase == LivePhase.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.minDimension * 0.28f

            if (phase == LivePhase.AI_SPEAKING || phase == LivePhase.LISTENING || phase == LivePhase.INTERRUPTED) {
                drawCircle(
                    color = coreColor.copy(alpha = 0.16f),
                    radius = baseRadius * (1.42f * pulse),
                    center = center
                )
                drawCircle(
                    color = coreColor.copy(alpha = 0.34f),
                    radius = baseRadius * (1.2f * pulse),
                    center = center,
                    style = Stroke(width = 4f)
                )
            }

            drawCircle(
                color = coreColor,
                radius = baseRadius * (if (phase == LivePhase.IDLE) 0.95f else (0.95f + (pulse - 1f) * 0.35f)),
                center = center
            )
        }
        Icon(
            imageVector = when {
                isMuted -> Icons.Filled.MicOff
                phase == LivePhase.AI_SPEAKING -> Icons.Filled.GraphicEq
                phase == LivePhase.INTERRUPTED -> Icons.Filled.PanTool
                else -> Icons.Filled.Mic
            },
            contentDescription = "Gemini Live voice status",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
private fun TranscriptBubble(entry: LiveTranscriptEntry) {
    val isUser = entry.speaker == "user"
    Surface(
        color = if (isUser) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = buildString {
                    append(if (isUser) "You" else "KindGuide")
                    if (entry.wasInterrupted) append(" • Interrupted")
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (isUser) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun LiveStreamingBubble(
    speakerLabel: String,
    text: String,
    isUser: Boolean
) {
    Surface(
        color = if (isUser) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        } else {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
        },
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = speakerLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(2.dp))
            Text(text = text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun GeminiLiveBottomDock(
    liveState: GeminiLiveUiState,
    onStartLive: () -> Unit,
    onStopLive: () -> Unit,
    onToggleScreenShare: () -> Unit,
    onToggleCameraShare: () -> Unit,
    onToggleMute: () -> Unit,
    onManualInterrupt: () -> Unit
) {
    Surface(
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockActionItem(
                icon = if (liveState.isScreenSharing) {
                    Icons.AutoMirrored.Filled.StopScreenShare
                } else {
                    Icons.AutoMirrored.Filled.ScreenShare
                },
                label = if (liveState.isScreenSharing) "Stop" else "Screen",
                isActive = liveState.isScreenSharing,
                onClick = onToggleScreenShare,
                testTag = "live_share_screen_button"
            )

            DockActionItem(
                icon = if (liveState.isCameraSharing) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
                label = if (liveState.isCameraSharing) "Cam Off" else "Camera",
                isActive = liveState.isCameraSharing,
                onClick = onToggleCameraShare,
                testTag = "live_share_camera_button"
            )

            DockActionItem(
                icon = if (liveState.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                label = if (liveState.isMuted) "Unmute" else "Mic",
                isActive = liveState.isMuted,
                enabled = liveState.isActive,
                onClick = onToggleMute,
                testTag = "live_mute_button"
            )

            DockActionItem(
                icon = Icons.Filled.PanTool,
                label = "Pause",
                isActive = false,
                enabled = liveState.phase == LivePhase.AI_SPEAKING,
                onClick = onManualInterrupt,
                testTag = "live_interrupt_button"
            )

            // Primary Start / End Live circular button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledIconButton(
                    onClick = { if (liveState.isActive) onStopLive() else onStartLive() },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (liveState.isActive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        contentColor = if (liveState.isActive) {
                            MaterialTheme.colorScheme.onError
                        } else {
                            MaterialTheme.colorScheme.onPrimary
                        }
                    ),
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("live_power_button")
                ) {
                    Icon(
                        imageVector = if (liveState.isActive) Icons.Filled.CallEnd else Icons.Filled.GraphicEq,
                        contentDescription = if (liveState.isActive) "End Gemini Live" else "Start Gemini Live",
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (liveState.isActive) "End" else "Start",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun DockActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    testTag: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            colors = IconButtonDefaults.iconButtonColors(
                containerColor = if (isActive) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            modifier = Modifier
                .size(52.dp)
                .testTag(testTag)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}
