package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ScreenShare
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ai.AiGuideStep
import com.example.ai.GeminiLiveUiState
import com.example.ai.GuideSessionCoordinator
import com.example.ai.LivePhase
import com.example.data.model.ConversationMessage
import com.example.ui.components.AccessibilityDisclosureDialog
import com.example.ui.components.ActiveGuideStepCard
import com.example.ui.components.WarmSectionCard
import com.example.ui.state.CircleDashboardBundle
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SeniorHomeAndCoachScreen(
    bundle: CircleDashboardBundle,
    liveState: GeminiLiveUiState,
    activeStep: AiGuideStep?,
    lastReply: String,
    streamingReply: String = "",
    isAiBusy: Boolean,
    aiError: String?,
    crudBanner: String?,
    isReadAloudEnabled: Boolean,
    isAccessibilityConnected: Boolean,
    isOverlayVisible: Boolean,
    onOpenGeminiLive: (shareScreen: Boolean) -> Unit,
    onOpenGeminiLiveCamera: () -> Unit = { onOpenGeminiLive(false) },
    onStopGeminiLive: () -> Unit,
    onNavigateTab: (String) -> Unit,
    onToggleMedication: (String, Boolean) -> Unit,
    onSendCheckIn: (String) -> Unit,
    onTriggerSos: () -> Unit,
    onLaunchTakeMeHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var queryText by remember { mutableStateOf("") }
    var attachedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showDisclosure by remember { mutableStateOf(false) }
    var showToolsSheet by remember { mutableStateOf(false) }
    var showPracticeSimulator by remember { mutableStateOf(false) }
    var voiceNotice by remember { mutableStateOf<String?>(null) }

    val speakingMessageId by GuideSessionCoordinator.speakingMessageId.collectAsStateWithLifecycle()
    val voiceMeterLevel by GuideSessionCoordinator.voiceMeterLevel.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()

    // Ensure GuideSessionCoordinator always has live Tasks, Medications, Events, and Conversations
    LaunchedEffect(
        bundle.circle,
        bundle.tasks,
        bundle.events,
        bundle.medications,
        bundle.conversations
    ) {
        GuideSessionCoordinator.syncBundle(
            circle = bundle.circle,
            tasks = bundle.tasks,
            events = bundle.events,
            medications = bundle.medications,
            history = bundle.conversations
        )
    }

    LaunchedEffect(bundle.conversations.size, activeStep, isAiBusy, streamingReply.length) {
        val totalCount = listState.layoutInfo.totalItemsCount
        if (totalCount > 0) {
            runCatching { listState.scrollToItem(totalCount - 1) }
        }
    }

    val sendCurrentQuery: () -> Unit = {
        val text = queryText.trim()
        if (text.isNotBlank()) {
            queryText = ""
            GuideSessionCoordinator.askCompanion(
                query = text,
                attachedScreenshot = attachedBitmap,
                activateOverlayIfConnected = isAccessibilityConnected
            )
            attachedBitmap = null
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (spoken.isNotBlank()) {
                GuideSessionCoordinator.askCompanion(
                    query = spoken,
                    attachedScreenshot = attachedBitmap,
                    activateOverlayIfConnected = isAccessibilityConnected
                )
                attachedBitmap = null
                queryText = ""
            }
        }
    }

    val screenshotPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val src = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                        decoder.isMutableRequired = false
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
                attachedBitmap = bmp
                val userPrompt = queryText.ifBlank { "Please look at this photo and guide me step by step." }
                GuideSessionCoordinator.askCompanion(
                    query = userPrompt,
                    attachedScreenshot = bmp,
                    activateOverlayIfConnected = isAccessibilityConnected,
                    displayTextInChat = queryText.ifBlank { "Shared a photo — please guide me step by step." }
                )
                attachedBitmap = null
                queryText = ""
            }
        }
    }

    if (showDisclosure) {
        AccessibilityDisclosureDialog(
            onDismiss = { showDisclosure = false },
            onOpenSettings = {
                showDisclosure = false
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )
    }

    val pendingMed = bundle.medications.firstOrNull { !it.takenToday }
    val openTasksCount = bundle.tasks.count { !it.completed }

    if (showToolsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showToolsSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Quick Tools & Shortcuts",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                // Quick Safety Row: Take Me Home + SOS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            showToolsSheet = false
                            onLaunchTakeMeHome()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .testTag("home_take_me_home_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Icon(Icons.Filled.Home, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.take_me_home), maxLines = 1)
                    }
                    Button(
                        onClick = {
                            showToolsSheet = false
                            onTriggerSos()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .testTag("home_sos_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Filled.Emergency, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("SOS Alert", maxLines = 1)
                    }
                }

                // 2x2 Quick Navigation Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ModuleQuickTile(
                        icon = Icons.Filled.CalendarMonth,
                        title = "Tasks",
                        subtitle = "$openTasksCount open",
                        accentColor = MaterialTheme.colorScheme.primary,
                        onClick = {
                            showToolsSheet = false
                            onNavigateTab("tasks")
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tile_tasks_calendar")
                    )
                    ModuleQuickTile(
                        icon = Icons.Filled.PhotoLibrary,
                        title = "Family",
                        subtitle = "${bundle.photos.size} photos",
                        accentColor = MaterialTheme.colorScheme.secondary,
                        onClick = {
                            showToolsSheet = false
                            onNavigateTab("family")
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tile_photos_voice")
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ModuleQuickTile(
                        icon = Icons.Filled.Shield,
                        title = "Safety & Map",
                        subtitle = "Meds & GPS",
                        accentColor = MaterialTheme.colorScheme.tertiary,
                        onClick = {
                            showToolsSheet = false
                            onNavigateTab("safety")
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tile_safety_map")
                    )
                    ModuleQuickTile(
                        icon = Icons.Filled.Psychology,
                        title = "Stories & Game",
                        subtitle = "Brain match",
                        accentColor = MaterialTheme.colorScheme.primary,
                        onClick = {
                            showToolsSheet = false
                            onNavigateTab("joy")
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tile_daily_joy")
                    )
                }

                // Floating Coach & Practice Phone
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (isAccessibilityConnected) {
                                GuideSessionCoordinator.setOverlayVisible(!isOverlayVisible)
                            } else {
                                showToolsSheet = false
                                showDisclosure = true
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("toggle_floating_coach_button")
                    ) {
                        Icon(Icons.Filled.Layers, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when {
                                !isAccessibilityConnected -> "Enable Overlay"
                                isOverlayVisible -> "Hide Overlay"
                                else -> "Show Overlay"
                            },
                            maxLines = 1
                        )
                    }
                    OutlinedButton(
                        onClick = { showPracticeSimulator = !showPracticeSimulator },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("toggle_practice_mode_button")
                    ) {
                        Icon(Icons.Filled.School, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Practice Phone", maxLines = 1)
                    }
                }

                if (showPracticeSimulator) {
                    SmartphonePracticeSimulatorCard(
                        onAskCoach = { msg ->
                            showToolsSheet = false
                            GuideSessionCoordinator.askCompanion(query = msg)
                        }
                    )
                }

                // Daily Mood Check-In
                Text(
                    text = "Daily Family Check-In (${bundle.circle.lastMood.ifBlank { "Calm" }})",
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Happy", "Calm", "Tired", "Call Me").forEach { mood ->
                        OutlinedButton(
                            onClick = {
                                onSendCheckIn(mood)
                                showToolsSheet = false
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(mood, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Compact Top Greeting + Utility Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Hi, ${bundle.circle.seniorName.ifBlank { "Friend" }}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = buildString {
                            append("$openTasksCount open task${if (openTasksCount == 1) "" else "s"}")
                            if (pendingMed != null) {
                                append(" • ${pendingMed.name} due")
                            } else {
                                append(" • All meds taken")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (isAccessibilityConnected) {
                                GuideSessionCoordinator.setOverlayVisible(!isOverlayVisible)
                            } else {
                                showDisclosure = true
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.height(42.dp)
                    ) {
                        Icon(
                            Icons.Filled.Layers,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (isOverlayVisible) "Overlay On" else "Overlay")
                    }

                    OutlinedButton(
                        onClick = { showToolsSheet = true },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier
                            .height(42.dp)
                            .testTag("open_more_tools_button")
                    ) {
                        Icon(
                            Icons.Filled.MoreHoriz,
                            contentDescription = "More tools",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Tools")
                    }
                }
            }

            // Active Gemini Live Banner
            if (liveState.isActive) {
                WarmSectionCard(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    borderColor = MaterialTheme.colorScheme.primary
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    liveState.isScreenSharing -> "Live Screen Share Active"
                                    liveState.isCameraSharing -> "Live Camera Share Active"
                                    liveState.phase == LivePhase.AI_SPEAKING -> "Gemini Live Speaking"
                                    else -> "Gemini Live Listening"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = liveState.statusNotice ?: "Tap Open to view Live Assistant",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { onOpenGeminiLive(false) },
                                modifier = Modifier.testTag("banner_open_live_button"),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Text("Open")
                            }
                            OutlinedButton(
                                onClick = onStopGeminiLive,
                                modifier = Modifier.testTag("banner_stop_live_button"),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text("End")
                            }
                        }
                    }
                }
            }

            // Pending Medicine Quick Pill (if any due today)
            if (pendingMed != null) {
                WarmSectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Medicine Due • ${pendingMed.scheduleSlot}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${pendingMed.name} (${pendingMed.dosage})",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1
                            )
                        }
                        Button(
                            onClick = { onToggleMedication(pendingMed.id, true) },
                            modifier = Modifier
                                .height(44.dp)
                                .testTag("quick_take_med_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Text("Took It")
                        }
                    }
                }
            }

            // Real-time AI CRUD Confirmation Banner
            if (crudBanner != null) {
                WarmSectionCard(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    borderColor = MaterialTheme.colorScheme.secondary
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = crudBanner,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        TextButton(onClick = { GuideSessionCoordinator.dismissCrudBanner() }) {
                            Text("OK")
                        }
                    }
                }
            }

            // Main Scrollable WhatsApp-Style Chat Thread
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("assistant_chat_list"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 6.dp)
            ) {
                // Starter Card: Open Live Voice / Screen / Camera + Quick Prompts
                item(key = "welcome_starter_card") {
                    WarmSectionCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Assistant,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "KindGuide Assistant",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Ask about your tasks, medicines, wellness, or phone—or start Live Assistant to share your screen or camera.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onOpenGeminiLive(false) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(
                                    Icons.Filled.GraphicEq,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Live Voice", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }
                            FilledTonalButton(
                                onClick = { onOpenGeminiLive(true) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("hero_live_share_screen_button"),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ScreenShare,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Screen", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }
                            FilledTonalButton(
                                onClick = onOpenGeminiLiveCamera,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("hero_live_share_camera_button"),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(
                                    Icons.Filled.PhotoCamera,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Camera", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val quickPrompts = listOf(
                                "Morning exercises to stay healthy",
                                "What tasks & medicines do I have?",
                                "How do I send a photo?"
                            )
                            quickPrompts.forEach { prompt ->
                                AssistChip(
                                    onClick = {
                                        GuideSessionCoordinator.askCompanion(
                                            query = prompt,
                                            activateOverlayIfConnected = isAccessibilityConnected
                                        )
                                    },
                                    label = {
                                        Text(prompt, style = MaterialTheme.typography.bodySmall)
                                    }
                                )
                            }
                        }
                    }
                }

                // Natural WhatsApp-Style Chat Bubbles with Rich Markdown & Voice Meter
                itemsIndexed(
                    items = bundle.conversations.takeLast(30),
                    key = { index, msg -> "msg_${index}_${msg.id}" }
                ) { index, msg ->
                    val msgId = msg.id.ifBlank { "msg_$index" }
                    WhatsAppStyleChatBubble(
                        message = msg,
                        isStreaming = false,
                        isSpeaking = speakingMessageId == msgId,
                        voiceMeterLevel = if (speakingMessageId == msgId) voiceMeterLevel else 0f,
                        onToggleSpeak = {
                            GuideSessionCoordinator.toggleSpeakMessage(msgId, msg.text)
                        }
                    )
                }

                // Live Streaming AI Reply Bubble (word-by-word as tokens arrive)
                val lastSavedMsg = bundle.conversations.lastOrNull()
                val isAlreadySaved = lastSavedMsg?.role == "model" && lastSavedMsg.text == streamingReply
                if (streamingReply.isNotBlank() && !isAlreadySaved) {
                    item(key = "live_streaming_reply_bubble") {
                        WhatsAppStyleChatBubble(
                            message = ConversationMessage(
                                id = "streaming_live",
                                role = "model",
                                text = streamingReply
                            ),
                            isStreaming = true,
                            isSpeaking = speakingMessageId == "streaming_live",
                            voiceMeterLevel = if (speakingMessageId == "streaming_live") voiceMeterLevel else 0f,
                            onToggleSpeak = {
                                GuideSessionCoordinator.toggleSpeakMessage("streaming_live", streamingReply)
                            }
                        )
                    }
                } else if (isAiBusy) {
                    // Brief thinking indicator before the first token arrives
                    item(key = "typing_indicator_bubble") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                tonalElevation = 1.dp,
                                shadowElevation = 1.dp,
                                shape = RoundedCornerShape(
                                    topStart = 4.dp,
                                    topEnd = 18.dp,
                                    bottomStart = 18.dp,
                                    bottomEnd = 18.dp
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "KindGuide is typing...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Active Step-by-Step Guidance Card (when guiding through a phone task)
                if (activeStep != null) {
                    item(key = "active_guide_step_card") {
                        ActiveGuideStepCard(
                            step = activeStep,
                            isBusy = isAiBusy,
                            isReadAloudEnabled = isReadAloudEnabled,
                            isAccessibilityConnected = isAccessibilityConnected,
                            onNextStep = { GuideSessionCoordinator.advanceStepWithLiveScreen() },
                            onRepeatSlowly = { GuideSessionCoordinator.repeatCurrentStepSlowly() },
                            onExplainDifferently = { GuideSessionCoordinator.explainCurrentStepDifferently() },
                            onToggleReadAloud = { GuideSessionCoordinator.toggleVoiceReadAloud() },
                            onStartOverlay = {
                                if (isAccessibilityConnected) {
                                    GuideSessionCoordinator.setOverlayVisible(true)
                                } else {
                                    showDisclosure = true
                                }
                            },
                            onCloseGuide = { GuideSessionCoordinator.clearGuideSession() }
                        )
                    }
                } else if (bundle.conversations.isEmpty() && lastReply.isNotBlank()) {
                    item(key = "fallback_last_reply") {
                        WhatsAppStyleChatBubble(
                            message = ConversationMessage(
                                id = "last_reply",
                                role = "model",
                                text = lastReply
                            ),
                            isStreaming = false,
                            isSpeaking = speakingMessageId == "last_reply",
                            voiceMeterLevel = if (speakingMessageId == "last_reply") voiceMeterLevel else 0f,
                            onToggleSpeak = {
                                GuideSessionCoordinator.toggleSpeakMessage("last_reply", lastReply)
                            }
                        )
                    }
                }

                if (aiError != null) {
                    item(key = "ai_error_banner") {
                        WarmSectionCard(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            borderColor = MaterialTheme.colorScheme.error
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = aiError,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { GuideSessionCoordinator.clearError() }) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                }
            }

            if (voiceNotice != null) {
                Text(
                    text = voiceNotice.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            // Bottom Composer: Full-Width Text Box on Top + Tools Row Below It
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                shadowElevation = 2.dp,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Row 1: Full-width input text box
                    OutlinedTextField(
                        value = queryText,
                        onValueChange = { queryText = it },
                        placeholder = { Text("Ask KindGuide anything...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("companion_text_input"),
                        maxLines = 3,
                        shape = RoundedCornerShape(16.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendCurrentQuery() })
                    )

                    // Row 2: Tools below the input box (Photo, Voice, Live on left; circular Send button on right)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    screenshotPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .height(44.dp)
                                    .testTag("attach_screenshot_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Image,
                                    contentDescription = "Attach screenshot or photo",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text("Photo", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }

                            FilledTonalButton(
                                onClick = {
                                    voiceNotice = null
                                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(
                                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                        )
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                                        putExtra(
                                            RecognizerIntent.EXTRA_PROMPT,
                                            "Speak your question for KindGuide"
                                        )
                                    }
                                    runCatching { speechLauncher.launch(intent) }
                                        .onFailure {
                                            voiceNotice = "Voice input unavailable; tap Live to talk or type above."
                                        }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .height(44.dp)
                                    .testTag("hero_voice_ask_button")
                            ) {
                                Icon(
                                    Icons.Filled.Mic,
                                    contentDescription = "Voice dictation",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text("Voice", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }

                            Button(
                                onClick = { onOpenGeminiLive(false) },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .height(44.dp)
                                    .testTag("hero_gemini_live_button")
                            ) {
                                Icon(
                                    Icons.Filled.GraphicEq,
                                    contentDescription = "Open Gemini Live Assistant",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text("Live", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }
                        }

                        Button(
                            onClick = sendCurrentQuery,
                            enabled = queryText.isNotBlank() && !isAiBusy,
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            ),
                            modifier = Modifier
                                .size(46.dp)
                                .testTag("companion_send_button")
                        ) {
                            if (isAiBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSecondary
                                )
                            } else {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send message",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WhatsAppStyleChatBubble(
    message: ConversationMessage,
    isStreaming: Boolean,
    isSpeaking: Boolean,
    voiceMeterLevel: Float,
    onToggleSpeak: () -> Unit
) {
    val isAssistant = message.role == "model"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        horizontalArrangement = if (isAssistant) Arrangement.Start else Arrangement.End
    ) {
        Surface(
            color = if (isAssistant) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            shape = RoundedCornerShape(
                topStart = if (isAssistant) 4.dp else 18.dp,
                topEnd = if (isAssistant) 18.dp else 4.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp
            ),
            modifier = Modifier.widthIn(
                min = 72.dp,
                max = if (isAssistant) 340.dp else 300.dp
            )
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = 14.dp,
                    vertical = if (isAssistant) 12.dp else 10.dp
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isAssistant) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Text(
                                text = "KindGuide",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        VoiceMeterPill(
                            isSpeaking = isSpeaking,
                            voiceMeterLevel = voiceMeterLevel,
                            onClick = onToggleSpeak
                        )
                    }
                }

                RichMarkdownMessageContent(
                    markdown = message.text,
                    isAssistant = isAssistant,
                    isStreaming = isStreaming
                )
            }
        }
    }
}

/**
 * Interactive Voice Meter Pill that replaces the plain speaker button:
 * - Idle: shows a clean "Listen" pill with a VolumeUp icon.
 * - Speaking: displays a 5-bar animated Equalizer / Voice Meter driven by live speech amplitude
 *   along with a Stop button so tapping stops speech immediately.
 */
@Composable
private fun VoiceMeterPill(
    isSpeaking: Boolean,
    voiceMeterLevel: Float,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (isSpeaking) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        },
        border = if (isSpeaking) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.secondary)
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isSpeaking) {
                // 5-bar live animated voice meter
                val multipliers = listOf(0.55f, 0.9f, 1.0f, 0.8f, 0.6f)
                Row(
                    modifier = Modifier.height(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.5.dp)
                ) {
                    multipliers.forEachIndexed { idx, factor ->
                        val targetFraction = (0.22f + voiceMeterLevel * factor + (idx % 2) * 0.1f)
                            .coerceIn(0.22f, 1.0f)
                        val animatedFraction by animateFloatAsState(
                            targetValue = targetFraction,
                            animationSpec = tween(durationMillis = 80),
                            label = "voice_bar_$idx"
                        )
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight(animatedFraction)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.secondary)
                        )
                    }
                }
                Text(
                    text = "Stop",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = "Stop reading aloud",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Read response aloud",
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "Listen",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class NumberedItem(val number: String, val content: String) : MarkdownBlock
    data class BulletItem(val content: String) : MarkdownBlock
    data class CalloutQuote(val content: String) : MarkdownBlock
    data class Paragraph(val content: String) : MarkdownBlock
}

private fun parseMarkdownBlocks(rawText: String): List<MarkdownBlock> {
    val lines = rawText.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    val numberedRegex = Regex("^(\\d+)[.)]\\s+(.*)")
    val bulletRegex = Regex("^[-*•]\\s+(.*)")
    val headingRegex = Regex("^(#{1,4})\\s+(.*)")

    for (rawLine in lines) {
        val line = rawLine.trim()
        if (line.isBlank()) continue

        val headingMatch = headingRegex.find(line)
        val numberedMatch = numberedRegex.find(line)
        val bulletMatch = bulletRegex.find(line)

        when {
            headingMatch != null -> {
                val level = headingMatch.groupValues[1].length
                val title = headingMatch.groupValues[2].trim()
                blocks.add(MarkdownBlock.Heading(level, title))
            }
            line.startsWith(">") -> {
                val quoteText = line.removePrefix(">").trim()
                if (quoteText.isNotBlank()) {
                    blocks.add(MarkdownBlock.CalloutQuote(quoteText))
                }
            }
            numberedMatch != null -> {
                val num = numberedMatch.groupValues[1]
                val itemContent = numberedMatch.groupValues[2].trim()
                blocks.add(MarkdownBlock.NumberedItem(num, itemContent))
            }
            bulletMatch != null -> {
                val itemContent = bulletMatch.groupValues[1].trim()
                blocks.add(MarkdownBlock.BulletItem(itemContent))
            }
            else -> {
                blocks.add(MarkdownBlock.Paragraph(line))
            }
        }
    }

    if (blocks.isEmpty() && rawText.isNotBlank()) {
        blocks.add(MarkdownBlock.Paragraph(rawText.trim()))
    }
    return blocks
}

/**
 * Parses inline Markdown (**bold**, *italic*, `code`) into an AnnotatedString.
 * If [autoBoldLeadIn] is true and there are no explicit `**` markers, automatically
 * bolds the lead-in title before the first `:` or `—` (so items like
 * `Neck Tilts: Gently tilt your head...` also render with a bold title).
 */
private fun parseInlineMarkdown(
    text: String,
    highlightColor: Color,
    autoBoldLeadIn: Boolean = false
): AnnotatedString {
    val normalized = if (autoBoldLeadIn && !text.contains("**")) {
        val colonIdx = text.indexOf(':')
        val dashIdx = text.indexOf(" — ")
        val splitIdx = when {
            colonIdx in 1..42 -> colonIdx + 1
            dashIdx in 1..42 -> dashIdx
            else -> -1
        }
        if (splitIdx > 0) {
            "**${text.substring(0, splitIdx)}**${text.substring(splitIdx)}"
        } else {
            text
        }
    } else {
        text
    }

    return buildAnnotatedString {
        var i = 0
        val n = normalized.length
        while (i < n) {
            when {
                normalized.startsWith("**", i) -> {
                    val end = normalized.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(normalized.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append("**")
                        i += 2
                    }
                }
                normalized.startsWith("`", i) -> {
                    val end = normalized.indexOf("`", i + 1)
                    if (end != -1) {
                        withStyle(
                            SpanStyle(
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                background = highlightColor
                            )
                        ) {
                            append(" ${normalized.substring(i + 1, end)} ")
                        }
                        i = end + 1
                    } else {
                        append("`")
                        i += 1
                    }
                }
                normalized.startsWith("*", i) && (i + 1 < n && normalized[i + 1] != ' ') -> {
                    val end = normalized.indexOf("*", i + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(normalized.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append("*")
                        i += 1
                    }
                }
                else -> {
                    append(normalized[i])
                    i += 1
                }
            }
        }
    }
}

@Composable
private fun RichMarkdownMessageContent(
    markdown: String,
    isAssistant: Boolean,
    isStreaming: Boolean
) {
    val textColor = if (isAssistant) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    val codeBg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)

    if (!isAssistant) {
        Text(
            text = markdown,
            style = MaterialTheme.typography.bodyLarge,
            color = textColor
        )
        return
    }

    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { index, block ->
            val isLastBlock = index == blocks.lastIndex
            val cursorSuffix = if (isStreaming && isLastBlock) " ▍" else ""

            when (block) {
                is MarkdownBlock.Heading -> {
                    Text(
                        text = parseInlineMarkdown(
                            text = block.text + cursorSuffix,
                            highlightColor = codeBg
                        ),
                        style = if (block.level <= 2) {
                            MaterialTheme.typography.titleLarge
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = if (index > 0) 4.dp else 0.dp)
                    )
                }

                is MarkdownBlock.NumberedItem -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                                modifier = Modifier.size(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = block.number,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                            Text(
                                text = parseInlineMarkdown(
                                    text = block.content + cursorSuffix,
                                    highlightColor = codeBg,
                                    autoBoldLeadIn = true
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                color = textColor,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                is MarkdownBlock.BulletItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(top = 9.dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondary)
                        )
                        Text(
                            text = parseInlineMarkdown(
                                text = block.content + cursorSuffix,
                                highlightColor = codeBg,
                                autoBoldLeadIn = true
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = textColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MarkdownBlock.CalloutQuote -> {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.secondary)
                            )
                            Text(
                                text = parseInlineMarkdown(
                                    text = block.content + cursorSuffix,
                                    highlightColor = codeBg
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                            )
                        }
                    }
                }

                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = parseInlineMarkdown(
                            text = block.content + cursorSuffix,
                            highlightColor = codeBg
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor
                    )
                }
            }
        }
    }
}

@Composable
private fun ModuleQuickTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(104.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(26.dp)
            )
            Column {
                Text(text = title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SmartphonePracticeSimulatorCard(
    onAskCoach: (String) -> Unit
) {
    var practiceStep by remember { mutableIntStateOf(0) }
    val steps = listOf(
        "Step 1: Tap \"Open Gallery\" below to pick a photo.",
        "Step 2: Tap \"Choose Aarav\" to pick who gets the photo.",
        "Step 3: Tap \"Send Photo\" to finish!"
    )
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Practice Mode: Send a Photo",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (practiceStep < steps.size) steps[practiceStep] else "Great job! You practiced sending a photo.",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { if (practiceStep == 0) practiceStep = 1 },
                    enabled = practiceStep == 0,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                ) {
                    Text("Gallery", maxLines = 1)
                }
                Button(
                    onClick = { if (practiceStep == 1) practiceStep = 2 },
                    enabled = practiceStep == 1,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                ) {
                    Text("Aarav", maxLines = 1)
                }
                Button(
                    onClick = { if (practiceStep == 2) practiceStep = 3 },
                    enabled = practiceStep == 2,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                ) {
                    Text("Send", maxLines = 1)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { practiceStep = 0 }) {
                    Text("Restart")
                }
                OutlinedButton(
                    onClick = {
                        onAskCoach("I am practicing sending a photo on step ${practiceStep + 1}. Give me one helpful tip.")
                    }
                ) {
                    Text("Ask Tip")
                }
            }
        }
    }
}
