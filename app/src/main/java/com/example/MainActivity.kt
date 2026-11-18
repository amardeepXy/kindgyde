package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ai.GeminiLiveManager
import com.example.ai.GuideSessionCoordinator
import com.example.ai.KindGuideAiService
import com.example.auth.attemptAutoSignIn
import com.example.auth.authStateFlow
import com.example.auth.signOut
import com.example.data.firebase.configureAppCheck
import com.example.data.local.KindGuideDatabase
import com.example.data.local.MedicationScheduleRepository
import com.example.data.repository.KindGuideRepository
import com.example.notifications.MedicationNotificationManager
import com.example.safety.SosNotificationHelper
import com.example.service.GeminiLiveForegroundService
import com.example.service.ScreenGuideAccessibilityService
import com.example.ui.components.CaregiverPinDialog
import com.example.ui.components.LiveInAppScreenMonitorEffect
import com.example.ui.components.LiveScreenCoPilotOverlayHost
import com.example.ui.components.WarmSectionCard
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CareCircleSetupScreen
import com.example.ui.screens.CaregiverDashboardScreen
import com.example.ui.screens.DailyJoyScreen
import com.example.ui.screens.GeminiLiveScreen
import com.example.ui.screens.MedicationHistoryScreen
import com.example.ui.screens.PhotosAndVoiceScreen
import com.example.ui.screens.SafetyAndMapScreen
import com.example.ui.screens.SeniorHomeAndCoachScreen
import com.example.ui.screens.SeniorHomeDashboardScreen
import com.example.ui.screens.TasksAndCalendarScreen
import com.example.ui.state.CircleDashboardBundle
import com.example.ui.state.DataUiState
import com.example.ui.state.RoleMode
import com.example.ui.theme.KindGuideTheme
import com.example.viewmodel.KindGuideViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch

private data class SeniorTabItem(
    val id: String,
    val label: String,
    val icon: ImageVector
)

class MainActivity : ComponentActivity() {
    private val openLiveSignal = mutableIntStateOf(0)
    private val openSafetyTabSignal = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureAppCheck(this, intent)
        SosNotificationHelper.ensureChannel(this)
        MedicationNotificationManager.ensureNotificationChannel(this)

        if (intent?.getBooleanExtra(GeminiLiveForegroundService.EXTRA_OPEN_LIVE, false) == true) {
            openLiveSignal.intValue += 1
        }
        if (intent?.getBooleanExtra(MedicationNotificationManager.EXTRA_OPEN_SAFETY_TAB, false) == true) {
            openSafetyTabSignal.intValue += 1
        }

        val repository = KindGuideRepository(applicationContext)
        GuideSessionCoordinator.initialize(applicationContext, repository)

        setContent {
            KindGuideTheme {
                KindGuideRootApp(
                    repository = repository,
                    openLiveSignal = openLiveSignal.intValue,
                    openSafetyTabSignal = openSafetyTabSignal.intValue
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(GeminiLiveForegroundService.EXTRA_OPEN_LIVE, false)) {
            openLiveSignal.intValue += 1
        }
        if (intent.getBooleanExtra(MedicationNotificationManager.EXTRA_OPEN_SAFETY_TAB, false)) {
            openSafetyTabSignal.intValue += 1
        }
    }
}

@Composable
private fun KindGuideRootApp(
    repository: KindGuideRepository,
    openLiveSignal: Int,
    openSafetyTabSignal: Int
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val currentUser by remember { Firebase.auth.authStateFlow() }
        .collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)

    var autoSignInFinished by remember { mutableStateOf(Firebase.auth.currentUser != null) }

    LaunchedEffect(Unit) {
        if (Firebase.auth.currentUser == null) {
            attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = { autoSignInFinished = true },
                onUnauthenticated = { autoSignInFinished = true },
                scope = scope
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    GuideSessionCoordinator.setKindGuideActivityResumed(true)
                    GuideSessionCoordinator.onInAppWindowResumed(context.packageName)
                    GuideSessionCoordinator.setAccessibilityConnected(
                        ScreenGuideAccessibilityService.isEnabled(context)
                    )
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    GuideSessionCoordinator.setKindGuideActivityResumed(false)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        GuideSessionCoordinator.setKindGuideActivityResumed(true)
        GuideSessionCoordinator.onInAppWindowResumed(context.packageName)
        GuideSessionCoordinator.setAccessibilityConnected(
            ScreenGuideAccessibilityService.isEnabled(context)
        )
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val user = currentUser
    if (user == null) {
        if (!autoSignInFinished) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
                AuthScreen(
                    onAuthSuccess = { autoSignInFinished = true },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    } else {
        key(user.uid) {
            AuthenticatedKindGuideApp(
                user = user,
                repository = repository,
                openLiveSignal = openLiveSignal,
                openSafetyTabSignal = openSafetyTabSignal,
                onSignOut = {
                    GeminiLiveForegroundService.stopAll(context)
                    GuideSessionCoordinator.clearGuideSession()
                    signOut(credentialManager, scope)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthenticatedKindGuideApp(
    user: FirebaseUser,
    repository: KindGuideRepository,
    openLiveSignal: Int,
    openSafetyTabSignal: Int,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val aiService = remember { KindGuideAiService() }
    val medScheduleRepo = remember(context) {
        val db = KindGuideDatabase.getInstance(context.applicationContext)
        MedicationScheduleRepository(
            dao = db.medicationScheduleDao(),
            historyDao = db.medicationHistoryDao()
        )
    }
    val viewModel: KindGuideViewModel = viewModel(
        key = user.uid,
        factory = viewModelFactory {
            initializer {
                KindGuideViewModel(
                    repository = repository,
                    aiService = aiService,
                    currentUserId = user.uid,
                    userDisplayName = user.displayName.orEmpty(),
                    medicationScheduleRepository = medScheduleRepo,
                    appContext = context.applicationContext
                )
            }
        }
    )

    val bundleState by viewModel.bundleState.collectAsStateWithLifecycle()
    val roleMode by viewModel.roleMode.collectAsStateWithLifecycle()
    val actionMessage by viewModel.actionMessage.collectAsStateWithLifecycle()
    val isActionBusy by viewModel.isActionBusy.collectAsStateWithLifecycle()
    val scamState by viewModel.scamState.collectAsStateWithLifecycle()
    val engagementState by viewModel.engagementState.collectAsStateWithLifecycle()
    val currentLocation by viewModel.currentLocation.collectAsStateWithLifecycle()
    val brainGamesWon by viewModel.brainGamesWon.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(actionMessage) {
        val msg = actionMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearActionMessage()
        }
    }

    when (val state = bundleState) {
        is DataUiState.Loading -> {
            Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Loading your KindGuide Circle...", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        is DataUiState.Error -> {
            Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    WarmSectionCard {
                        Text("Could not connect to your Care Circle", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(state.message, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onSignOut) {
                            Text("Switch Account / Sign Out")
                        }
                    }
                }
            }
        }

        is DataUiState.Success -> {
            val bundle = state.data
            if (bundle == null) {
                Scaffold(
                    contentWindowInsets = WindowInsets.safeDrawing,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.app_name)) },
                            actions = {
                                IconButton(onClick = onSignOut) {
                                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign out")
                                }
                            }
                        )
                    }
                ) { padding ->
                    CareCircleSetupScreen(
                        defaultDisplayName = user.displayName.orEmpty(),
                        isBusy = isActionBusy,
                        statusMessage = actionMessage,
                        onCreateCircle = { sName, pin, addr, lat, lng, eName, ePhone ->
                            viewModel.createCareCircle(sName, pin, addr, lat, lng, eName, ePhone)
                        },
                        onJoinCircle = { code, pin ->
                            viewModel.joinCareCircleByCode(code, pin)
                        },
                        modifier = Modifier.padding(padding)
                    )
                }
            } else {
                CareCircleLoadedShell(
                    bundle = bundle,
                    roleMode = roleMode,
                    viewModel = viewModel,
                    scamState = scamState,
                    engagementState = engagementState,
                    currentLocation = currentLocation,
                    brainGamesWon = brainGamesWon,
                    openLiveSignal = openLiveSignal,
                    openSafetyTabSignal = openSafetyTabSignal,
                    snackbarHostState = snackbarHostState,
                    onSignOut = onSignOut
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CareCircleLoadedShell(
    bundle: CircleDashboardBundle,
    roleMode: RoleMode,
    viewModel: KindGuideViewModel,
    scamState: com.example.ui.state.ScamUiState,
    engagementState: com.example.ui.state.EngagementUiState,
    currentLocation: Pair<Double, Double>?,
    brainGamesWon: Int,
    openLiveSignal: Int,
    openSafetyTabSignal: Int,
    snackbarHostState: SnackbarHostState,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var currentTab by rememberSaveable { mutableStateOf("home") }
    var showAiAssistantScreen by rememberSaveable { mutableStateOf(false) }
    var showMedicationHistoryScreen by rememberSaveable { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showGeminiLiveScreen by rememberSaveable { mutableStateOf(false) }
    var pendingShareScreen by remember { mutableStateOf(false) }
    var pendingVoiceName by remember { mutableStateOf("AOEDE") }

    val sosProgress by viewModel.sosProgress.collectAsStateWithLifecycle()
    val localMedicationSchedules by viewModel.localMedicationSchedules.collectAsStateWithLifecycle()
    val medicationHistory by viewModel.medicationHistory.collectAsStateWithLifecycle()
    val weeklyMedicationHistory by viewModel.weeklyMedicationHistory.collectAsStateWithLifecycle()
    val historyDateRange by viewModel.historyDateRange.collectAsStateWithLifecycle()
    val liveState by GeminiLiveManager.state.collectAsStateWithLifecycle()
    val activeStep by GuideSessionCoordinator.activeStep.collectAsStateWithLifecycle()
    val lastReply by GuideSessionCoordinator.lastReply.collectAsStateWithLifecycle()
    val streamingReply by GuideSessionCoordinator.streamingReply.collectAsStateWithLifecycle()
    val isAiBusy by GuideSessionCoordinator.isBusy.collectAsStateWithLifecycle()
    val aiError by GuideSessionCoordinator.errorText.collectAsStateWithLifecycle()
    val crudBanner by GuideSessionCoordinator.crudFeedbackBanner.collectAsStateWithLifecycle()
    val isReadAloud by GuideSessionCoordinator.voiceReadAloudEnabled.collectAsStateWithLifecycle()
    val isAccessibilityConnected by GuideSessionCoordinator.isAccessibilityConnected.collectAsStateWithLifecycle()
    val isOverlayVisible by GuideSessionCoordinator.isOverlayVisible.collectAsStateWithLifecycle()
    val isHighlightEnabled by GuideSessionCoordinator.isHighlightEnabled.collectAsStateWithLifecycle()
    val targetHighlightBounds by GuideSessionCoordinator.targetHighlightBounds.collectAsStateWithLifecycle()
    val stepMonitorBanner by GuideSessionCoordinator.stepMonitorBanner.collectAsStateWithLifecycle()
    val activeConversationId by GuideSessionCoordinator.activeConversationId.collectAsStateWithLifecycle()

    LiveInAppScreenMonitorEffect(
        liveState = liveState,
        activeStep = activeStep,
        isOverlayVisible = isOverlayVisible,
        showGeminiLiveScreen = showGeminiLiveScreen
    )

    LaunchedEffect(bundle.circle.id) {
        GuideSessionCoordinator.startNewConversation()
    }

    // Notify Caregiver devices immediately with high-priority sound + notification when a new SOS arrives
    var alertsSeeded by remember(bundle.circle.id) { mutableStateOf(false) }
    val seenSosAlertIds = remember(bundle.circle.id) { mutableSetOf<String>() }
    LaunchedEffect(bundle.circle.id, bundle.alerts, roleMode) {
        val currentAlerts = bundle.alerts
        if (!alertsSeeded) {
            currentAlerts.forEach { seenSosAlertIds.add(it.id) }
            alertsSeeded = true
        } else {
            val newSosAlerts = currentAlerts.filter { alert ->
                !alert.resolved && alert.type == "sos" && seenSosAlertIds.add(alert.id)
            }
            if (roleMode == RoleMode.CAREGIVER && newSosAlerts.isNotEmpty()) {
                newSosAlerts.forEach { alert ->
                    SosNotificationHelper.notifyFamilySosAlert(
                        context = context,
                        seniorName = bundle.circle.seniorName,
                        message = alert.message,
                        alertId = alert.id
                    )
                }
            }
        }
    }

    var previousRoleMode by remember { mutableStateOf(roleMode) }
    LaunchedEffect(roleMode) {
        if (previousRoleMode == RoleMode.CAREGIVER && roleMode == RoleMode.SENIOR) {
            showAiAssistantScreen = false
            GuideSessionCoordinator.startNewConversation()
        }
        previousRoleMode = roleMode
    }

    LaunchedEffect(openLiveSignal) {
        if (openLiveSignal > 0) {
            showGeminiLiveScreen = true
        }
    }

    LaunchedEffect(openSafetyTabSignal) {
        if (openSafetyTabSignal > 0) {
            showAiAssistantScreen = false
            currentTab = "safety"
        }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            GeminiLiveManager.markConnectingPending(pendingVoiceName)
            GeminiLiveForegroundService.startWithScreenShare(
                context = context,
                resultCode = result.resultCode,
                dataIntent = result.data!!,
                voiceName = pendingVoiceName
            )
            showGeminiLiveScreen = true
        } else {
            // Fall back cleanly to voice-only Live if user cancels system screen-share prompt
            if (!liveState.isActive) {
                GeminiLiveManager.markConnectingPending(pendingVoiceName)
                GeminiLiveForegroundService.startVoiceOnly(context, pendingVoiceName)
            }
            showGeminiLiveScreen = true
            scope.launch {
                snackbarHostState.showSnackbar("Screen sharing cancelled — Gemini Live is ready with voice.")
            }
        }
    }

    val launchProjectionPrompt: () -> Unit = {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    val livePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            if (pendingShareScreen) {
                launchProjectionPrompt()
            } else {
                GeminiLiveManager.markConnectingPending(pendingVoiceName)
                GeminiLiveForegroundService.startVoiceOnly(context, pendingVoiceName)
                showGeminiLiveScreen = true
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Microphone permission is needed for Gemini Live voice.")
            }
        }
    }

    fun requestStartGeminiLive(voiceName: String, shareScreen: Boolean) {
        pendingVoiceName = voiceName
        pendingShareScreen = shareScreen

        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val needsNotification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED

        if (!hasMic || needsNotification) {
            val perms = buildList {
                if (!hasMic) add(Manifest.permission.RECORD_AUDIO)
                if (needsNotification) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray()
            livePermissionLauncher.launch(perms)
        } else if (shareScreen) {
            launchProjectionPrompt()
        } else {
            GeminiLiveManager.markConnectingPending(voiceName)
            GeminiLiveForegroundService.startVoiceOnly(context, voiceName)
            showGeminiLiveScreen = true
        }
    }

    if (showPinDialog) {
        CaregiverPinDialog(
            onDismiss = { showPinDialog = false },
            onVerifyPin = { enteredPin ->
                viewModel.unlockCaregiverMode(enteredPin, bundle.circle)
            }
        )
    }

    if (roleMode == RoleMode.CAREGIVER) {
        BackHandler {
            viewModel.switchToSeniorMode()
        }
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            CaregiverDashboardScreen(
                bundle = bundle,
                brainGamesWon = brainGamesWon,
                onSwitchToSeniorMode = { viewModel.switchToSeniorMode() },
                onSignOut = onSignOut,
                onResolveAlert = { viewModel.resolveAlert(bundle.circle, it) },
                onPushTask = { title, cat, due ->
                    viewModel.addTask(bundle.circle, title, cat, due, createdBy = "caregiver")
                },
                onPushEvent = { title, date, time, loc, notes ->
                    viewModel.addEvent(bundle.circle, title, date, time, loc, notes, createdBy = "caregiver")
                },
                onAddMedication = { name, dosage, slot ->
                    viewModel.addMedication(bundle.circle, name, dosage, slot)
                },
                onAddSafePlace = { label, cat, addr, lat, lng ->
                    viewModel.addSafePlace(bundle.circle, label, cat, addr, lat, lng)
                },
                onSendVoiceNote = { sender, transcript ->
                    viewModel.addVoiceNote(bundle.circle, sender, 6L, transcript, isFromCaregiver = true)
                },
                onUpdateCircleSettings = { sName, addr, lat, lng, eName, ePhone, newPin ->
                    viewModel.updateCircleSettings(bundle.circle, sName, addr, lat, lng, eName, ePhone, newPin)
                },
                observeAlertCaptures = { circleId, alertId ->
                    viewModel.observeAlertCaptures(circleId, alertId)
                },
                modifier = Modifier.padding(padding)
            )
        }
        return
    }

    val triggerQuickSosWithEvidence: () -> Unit = {
        if (liveState.isCameraSharing) {
            GeminiLiveManager.setCameraSharingActive(false)
        }
        viewModel.triggerSosWithCaptures(
            circle = bundle.circle,
            context = context,
            activity = context as? Activity,
            lifecycleOwner = lifecycleOwner
        )
    }

    DisposableEffect(bundle.circle.id) {
        GuideSessionCoordinator.registerQuickSosTrigger(triggerQuickSosWithEvidence)
        onDispose {
            GuideSessionCoordinator.registerQuickSosTrigger(null)
        }
    }

    if (showGeminiLiveScreen) {
        LaunchedEffect(showGeminiLiveScreen) {
            if (!liveState.isActive && liveState.phase != com.example.ai.LivePhase.CONNECTING) {
                requestStartGeminiLive(liveState.selectedVoice, shareScreen = false)
            }
        }
        BackHandler {
            showGeminiLiveScreen = false
        }
        GeminiLiveScreen(
            liveState = liveState,
            activeStep = activeStep,
            isAccessibilityConnected = isAccessibilityConnected,
            isHighlightEnabled = isHighlightEnabled,
            onToggleHighlight = { enabled ->
                GuideSessionCoordinator.setHighlightEnabled(enabled)
            },
            onMinimizeBack = { showGeminiLiveScreen = false },
            onStartLive = { voice, share ->
                requestStartGeminiLive(voice, share)
            },
            onStopLive = {
                GeminiLiveForegroundService.stopAll(context)
                showGeminiLiveScreen = false
            },
            onToggleScreenShare = {
                if (liveState.isScreenSharing) {
                    GeminiLiveForegroundService.stopScreenShare(context)
                } else {
                    GeminiLiveManager.setCameraSharingActive(false)
                    requestStartGeminiLive(liveState.selectedVoice, shareScreen = true)
                }
            },
            onToggleCameraShare = {
                if (liveState.isCameraSharing) {
                    GeminiLiveManager.setCameraSharingActive(false)
                } else {
                    if (liveState.isScreenSharing) {
                        GeminiLiveForegroundService.stopScreenShare(context)
                    }
                    if (!liveState.isActive) {
                        requestStartGeminiLive(liveState.selectedVoice, shareScreen = false)
                    }
                    GeminiLiveManager.setCameraSharingActive(true)
                }
            },
            onFlipCamera = { GeminiLiveManager.toggleCameraLens() },
            onToggleMute = { GeminiLiveManager.toggleMute() },
            onManualInterrupt = { GeminiLiveManager.manualInterruptPrompt() },
            onSelectVoice = { voice ->
                GeminiLiveManager.updateSelectedVoice(voice)
            },
            onSelectTone = { tone ->
                GeminiLiveManager.updateTonePreset(tone)
            },
            onSendNudge = { text -> GeminiLiveManager.sendTextNudge(text) },
            onClearError = { GeminiLiveManager.clearError() }
        )
        return
    }

    BackHandler(enabled = showMedicationHistoryScreen) {
        showMedicationHistoryScreen = false
    }

    BackHandler(enabled = !showMedicationHistoryScreen && showAiAssistantScreen) {
        showAiAssistantScreen = false
    }

    BackHandler(enabled = !showMedicationHistoryScreen && !showAiAssistantScreen && currentTab != "home") {
        showAiAssistantScreen = false
        currentTab = "home"
    }

    val tabs = remember {
        listOf(
            SeniorTabItem("home", "Home", Icons.Filled.Home),
            SeniorTabItem("tasks", "Tasks", Icons.Filled.TaskAlt),
            SeniorTabItem("family", "Family", Icons.Filled.PhotoLibrary),
            SeniorTabItem("safety", "Safety", Icons.Filled.Shield),
            SeniorTabItem("joy", "Joy", Icons.Filled.AutoStories)
        )
    }

    val isFullScreenAiChat = currentTab == "home" && showAiAssistantScreen

    LiveScreenCoPilotOverlayHost(
        liveState = liveState,
        activeStep = activeStep,
        stepMonitorBanner = stepMonitorBanner,
        targetHighlightBounds = targetHighlightBounds,
        isHighlightEnabled = isHighlightEnabled,
        isAccessibilityConnected = isAccessibilityConnected,
        showGeminiLiveScreen = showGeminiLiveScreen,
        onOpenLiveScreen = { showGeminiLiveScreen = true },
        onToggleHighlight = { GuideSessionCoordinator.toggleHighlightEnabled() },
        onAdvanceStep = { GuideSessionCoordinator.advanceStepWithLiveScreen() },
        onDismissGuidance = { GuideSessionCoordinator.clearGuideSession() }
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (!isFullScreenAiChat) {
                    TopAppBar(
                        title = {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                        actions = {
                            OutlinedButton(
                                onClick = { showPinDialog = true },
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .testTag("open_caregiver_pin_button")
                            ) {
                                Icon(Icons.Filled.Lock, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.family_view))
                            }
                        }
                    )
                }
            },
            bottomBar = {
                if (!useNavRail && !isFullScreenAiChat) {
                    NavigationBar {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = currentTab == tab.id,
                                onClick = {
                                    showAiAssistantScreen = false
                                    showMedicationHistoryScreen = false
                                    currentTab = tab.id
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag("nav_tab_${tab.id}")
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (useNavRail && !isFullScreenAiChat) {
                    NavigationRail {
                        tabs.forEach { tab ->
                            NavigationRailItem(
                                selected = currentTab == tab.id,
                                onClick = {
                                    showAiAssistantScreen = false
                                    showMedicationHistoryScreen = false
                                    currentTab = tab.id
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag("rail_tab_${tab.id}")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (currentTab) {
                        "home" -> if (!showAiAssistantScreen) {
                            SeniorHomeDashboardScreen(
                                bundle = bundle,
                                sosProgress = sosProgress,
                                onOpenAiAssistant = {
                                    GuideSessionCoordinator.startNewConversation()
                                    showAiAssistantScreen = true
                                },
                                onCompleteTask = { taskId ->
                                    viewModel.toggleTask(bundle.circle, taskId, true)
                                },
                                onOpenTasksTab = {
                                    showAiAssistantScreen = false
                                    currentTab = "tasks"
                                },
                                onTriggerQuickSos = triggerQuickSosWithEvidence,
                                onDismissSosStatus = { viewModel.dismissSosStatus() }
                            )
                        } else {
                            SeniorHomeAndCoachScreen(
                                bundle = bundle,
                                activeConversationId = activeConversationId,
                                liveState = liveState,
                                activeStep = activeStep,
                                lastReply = lastReply,
                                streamingReply = streamingReply,
                                isAiBusy = isAiBusy,
                                aiError = aiError,
                                crudBanner = crudBanner,
                                isReadAloudEnabled = isReadAloud,
                                isAccessibilityConnected = isAccessibilityConnected,
                                isOverlayVisible = isOverlayVisible,
                                onBackToHome = { showAiAssistantScreen = false },
                                onStartNewConversation = { GuideSessionCoordinator.startNewConversation() },
                                onSelectConversation = { GuideSessionCoordinator.selectConversation(it) },
                                onOpenGeminiLive = { shareScreen ->
                                    if (liveState.isActive && !shareScreen) {
                                        showGeminiLiveScreen = true
                                    } else {
                                        requestStartGeminiLive(liveState.selectedVoice, shareScreen)
                                    }
                                },
                                onOpenGeminiLiveCamera = {
                                    if (liveState.isScreenSharing) {
                                        GeminiLiveForegroundService.stopScreenShare(context)
                                    }
                                    if (!liveState.isActive) {
                                        requestStartGeminiLive(liveState.selectedVoice, shareScreen = false)
                                    } else {
                                        showGeminiLiveScreen = true
                                    }
                                    GeminiLiveManager.setCameraSharingActive(true)
                                },
                                onStopGeminiLive = {
                                    GeminiLiveForegroundService.stopAll(context)
                                },
                                onNavigateTab = { targetTab ->
                                    showAiAssistantScreen = false
                                    currentTab = targetTab
                                },
                                onToggleMedication = { medId, taken ->
                                    viewModel.toggleMedicationTaken(bundle.circle, medId, taken)
                                },
                                onSendCheckIn = { mood ->
                                    viewModel.sendCheckIn(bundle.circle, mood)
                                },
                                onTriggerSos = triggerQuickSosWithEvidence,
                                onLaunchTakeMeHome = {
                                    val navUri = Uri.parse(
                                        "google.navigation:q=${bundle.circle.homeLat},${bundle.circle.homeLng}&mode=w"
                                    )
                                    val navIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
                                        setPackage("com.google.android.apps.maps")
                                    }
                                    runCatching { context.startActivity(navIntent) }.onFailure {
                                        showAiAssistantScreen = false
                                        currentTab = "safety"
                                    }
                                }
                            )
                        }

                        "tasks" -> TasksAndCalendarScreen(
                            tasks = bundle.tasks,
                            events = bundle.events,
                            onToggleTask = { id, done -> viewModel.toggleTask(bundle.circle, id, done) },
                            onAddTask = { title, cat, due ->
                                viewModel.addTask(bundle.circle, title, cat, due, createdBy = "senior")
                            },
                            onDeleteTask = { viewModel.deleteTask(bundle.circle, it) },
                            onAddEvent = { title, date, time, loc, notes ->
                                viewModel.addEvent(bundle.circle, title, date, time, loc, notes, createdBy = "senior")
                            },
                            onDeleteEvent = { viewModel.deleteEvent(bundle.circle, it) }
                        )

                        "family" -> PhotosAndVoiceScreen(
                            seniorName = bundle.circle.seniorName,
                            photos = bundle.photos,
                            voiceNotes = bundle.voiceNotes,
                            isCaregiverMode = false,
                            onSharePhoto = { bmp, caption, sender ->
                                viewModel.sharePhotoFromBitmap(bundle.circle, bmp, caption, sender)
                            },
                            onDeletePhoto = { viewModel.deletePhoto(bundle.circle, it) },
                            onSendVoiceNote = { sender, secs, transcript, fromCaregiver ->
                                viewModel.addVoiceNote(bundle.circle, sender, secs, transcript, fromCaregiver)
                            },
                            onDeleteVoiceNote = { viewModel.deleteVoiceNote(bundle.circle, it) }
                        )

                        "safety" -> if (showMedicationHistoryScreen) {
                            MedicationHistoryScreen(
                                historyEntries = medicationHistory,
                                dateRange = historyDateRange,
                                onSelectDateRange = { viewModel.updateHistoryDateRange(it) },
                                onMarkEntryTaken = { entryId ->
                                    viewModel.markHistoryEntryTaken(bundle.circle, entryId)
                                },
                                onRefreshMissedCheck = { viewModel.refreshMedicationHistory() },
                                onBack = { showMedicationHistoryScreen = false },
                                weeklyHistoryEntries = weeklyMedicationHistory
                            )
                        } else {
                            SafetyAndMapScreen(
                                circle = bundle.circle,
                                medications = bundle.medications,
                                safePlaces = bundle.safePlaces,
                                scamState = scamState,
                                currentLocation = currentLocation,
                                onLocationUpdated = { lat, lng -> viewModel.updateCurrentLocation(lat, lng) },
                                onToggleMedication = { medId, taken ->
                                    viewModel.toggleMedicationTaken(bundle.circle, medId, taken)
                                },
                                onAddMedication = { name, dosage, slot ->
                                    viewModel.addMedication(bundle.circle, name, dosage, slot)
                                },
                                onDeleteMedication = { viewModel.deleteMedication(bundle.circle, it) },
                                onAddSafePlace = { label, cat, addr, lat, lng ->
                                    viewModel.addSafePlace(bundle.circle, label, cat, addr, lat, lng)
                                },
                                onDeleteSafePlace = { viewModel.deleteSafePlace(bundle.circle, it) },
                                onCheckScam = { text, bmp -> viewModel.analyzeScam(bundle.circle, text, bmp) },
                                onResetScam = { viewModel.resetScamCheck() },
                                onSendCheckIn = { mood -> viewModel.sendCheckIn(bundle.circle, mood) },
                                onTriggerSos = triggerQuickSosWithEvidence,
                                localSchedules = localMedicationSchedules,
                                onAddMedicationWithSchedule = { name, dosage, slot, hour, minute, enabled ->
                                    viewModel.addMedication(
                                        circle = bundle.circle,
                                        name = name,
                                        dosage = dosage,
                                        slot = slot,
                                        reminderHour = hour,
                                        reminderMinute = minute,
                                        reminderEnabled = enabled
                                    )
                                },
                                onUpdateMedicationReminder = { id, enabled, hour, minute ->
                                    viewModel.updateMedicationReminder(id, enabled, hour, minute)
                                },
                                onUpdateMedicationSchedule = { id, dosage, enabled, hour, minute ->
                                    viewModel.updateMedicationSchedule(
                                        circle = bundle.circle,
                                        scheduleId = id,
                                        dosage = dosage,
                                        enabled = enabled,
                                        hour = hour,
                                        minute = minute
                                    )
                                },
                                onTestMedicationReminder = { schedule ->
                                    viewModel.triggerMedicationReminderNow(schedule)
                                },
                                onOpenMedicationHistory = {
                                    viewModel.refreshMedicationHistory()
                                    showMedicationHistoryScreen = true
                                }
                            )
                        }

                        "joy" -> DailyJoyScreen(
                            engagementState = engagementState,
                            brainGamesWon = brainGamesWon,
                            onGenerateStory = { theme -> viewModel.generateDailyEngagement(theme) },
                            onShareMemoryPrompt = { promptText ->
                                viewModel.addVoiceNote(
                                    bundle.circle,
                                    bundle.circle.seniorName.ifBlank { "Senior" },
                                    8L,
                                    promptText,
                                    isFromCaregiver = false
                                )
                            },
                            onBrainGameWin = { viewModel.recordBrainGameWin(bundle.circle) }
                        )
                    }
                }
            }
        }
    }
    }
}
