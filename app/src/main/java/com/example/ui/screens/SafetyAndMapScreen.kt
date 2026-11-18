package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.ai.GuideSessionCoordinator
import com.example.data.local.MedicationScheduleEntity
import com.example.data.model.CareCircle
import com.example.data.model.MedicationItem
import com.example.data.model.SafePlace
import com.example.notifications.MedicationNotificationManager
import com.example.ui.components.EmptyFriendlyState
import com.example.ui.components.WarmSectionCard
import com.example.ui.state.ScamUiState
import com.google.android.gms.location.LocationServices
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max

private data class MapPinNode(
    val id: String,
    val label: String,
    val subtitle: String,
    val lat: Double,
    val lng: Double,
    val isHome: Boolean = false
)

private data class DisplayedMedicationEntry(
    val id: String,
    val name: String,
    val dosage: String,
    val scheduleSlot: String,
    val takenToday: Boolean,
    val schedule: MedicationScheduleEntity
)

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun SafetyAndMapScreen(
    circle: CareCircle,
    medications: List<MedicationItem>,
    safePlaces: List<SafePlace>,
    scamState: ScamUiState,
    currentLocation: Pair<Double, Double>?,
    onLocationUpdated: (Double, Double) -> Unit,
    onToggleMedication: (String, Boolean) -> Unit,
    onAddMedication: (name: String, dosage: String, slot: String) -> Unit,
    onDeleteMedication: (String) -> Unit,
    onAddSafePlace: (label: String, category: String, address: String, lat: Double, lng: Double) -> Unit,
    onDeleteSafePlace: (String) -> Unit,
    onCheckScam: (messageText: String, screenshot: Bitmap?) -> Unit,
    onResetScam: () -> Unit,
    onSendCheckIn: (String) -> Unit,
    onTriggerSos: () -> Unit,
    modifier: Modifier = Modifier,
    localSchedules: List<MedicationScheduleEntity> = emptyList(),
    onAddMedicationWithSchedule: ((name: String, dosage: String, slot: String, hour: Int, minute: Int, reminderEnabled: Boolean) -> Unit)? = null,
    onUpdateMedicationReminder: (id: String, enabled: Boolean, hour: Int, minute: Int) -> Unit = { _, _, _, _ -> },
    onUpdateMedicationSchedule: ((id: String, dosage: String, enabled: Boolean, hour: Int, minute: Int) -> Unit)? = null,
    onTestMedicationReminder: (MedicationScheduleEntity) -> Unit = {},
    onOpenMedicationHistory: () -> Unit = {}
) {
    val context = LocalContext.current
    val fusedLocationClient = remember(context) {
        LocationServices.getFusedLocationProviderClient(context)
    }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    var locationNotice by remember { mutableStateOf<String?>(null) }

    val fetchLastLocation = {
        runCatching {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    onLocationUpdated(loc.latitude, loc.longitude)
                }
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasLocationPermission = granted
        if (granted) {
            locationNotice = null
            fetchLastLocation()
        } else {
            locationNotice = "Location permission declined. Showing your saved Home & Safe Places."
        }
    }

    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            fetchLastLocation()
        }
    }

    var showAddMedDialog by remember { mutableStateOf(false) }
    var editingReminderSchedule by remember { mutableStateOf<MedicationScheduleEntity?>(null) }
    var pendingNotificationSchedule by remember { mutableStateOf<MedicationScheduleEntity?>(null) }
    var showAddPlaceDialog by remember { mutableStateOf(false) }
    var showScamSheet by remember { mutableStateOf(false) }
    var scamInput by remember { mutableStateOf("") }
    var scamBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedPinId by remember { mutableStateOf("home") }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingNotificationSchedule
        pendingNotificationSchedule = null
        if (granted && pending != null) {
            onTestMedicationReminder(pending)
        }
    }

    val ensureNotificationsThen: (MedicationScheduleEntity?, () -> Unit) -> Unit = { testTarget, action ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !MedicationNotificationManager.hasNotificationPermission(context)
        ) {
            pendingNotificationSchedule = testTarget
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    val scamScreenshotPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val src = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(src) { decoder, _, _ -> decoder.isMutableRequired = false }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
                scamBitmap = bmp
                showScamSheet = true
                onCheckScam(scamInput, bmp)
            }
        }
    }

    if (showAddMedDialog) {
        AddMedicationDialog(
            onDismiss = { showAddMedDialog = false },
            onSave = { name, dosage, slot, hour, minute, enabled ->
                if (enabled) {
                    ensureNotificationsThen(null) {}
                }
                if (onAddMedicationWithSchedule != null) {
                    onAddMedicationWithSchedule(name, dosage, slot, hour, minute, enabled)
                } else {
                    onAddMedication(name, dosage, slot)
                }
                showAddMedDialog = false
            }
        )
    }

    editingReminderSchedule?.let { schedule ->
        EditMedicationReminderDialog(
            schedule = schedule,
            onDismiss = { editingReminderSchedule = null },
            onSave = { dosage, enabled, hour, minute ->
                if (enabled) {
                    ensureNotificationsThen(null) {}
                }
                if (onUpdateMedicationSchedule != null) {
                    onUpdateMedicationSchedule(schedule.id, dosage, enabled, hour, minute)
                } else {
                    onUpdateMedicationReminder(schedule.id, enabled, hour, minute)
                }
                editingReminderSchedule = null
            }
        )
    }

    if (showAddPlaceDialog) {
        AddSafePlaceDialog(
            defaultLat = currentLocation?.first ?: circle.homeLat,
            defaultLng = currentLocation?.second ?: circle.homeLng,
            onDismiss = { showAddPlaceDialog = false },
            onSave = { label, cat, addr, lat, lng ->
                onAddSafePlace(label, cat, addr, lat, lng)
                showAddPlaceDialog = false
            }
        )
    }

    if (showScamSheet) {
        val scamSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showScamSheet = false },
            sheetState = scamSheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Check Suspicious Message",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
                OutlinedTextField(
                    value = scamInput,
                    onValueChange = { scamInput = it },
                    label = { Text("Paste suspicious SMS or email") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("scam_text_input"),
                    maxLines = 4
                )
                val isCheckingScam = scamState is ScamUiState.Loading || scamState is ScamUiState.Streaming
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onCheckScam(scamInput, scamBitmap) },
                        enabled = scamInput.isNotBlank() && !isCheckingScam,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("check_scam_text_button")
                    ) {
                        if (isCheckingScam) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Filled.Security, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Check Text")
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            scamScreenshotPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        enabled = !isCheckingScam,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("check_scam_screenshot_button")
                    ) {
                        Icon(Icons.Filled.Image, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Screenshot")
                    }
                }

                when (scamState) {
                    is ScamUiState.Loading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(vertical = 6.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                            Text("Checking message safely...", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    is ScamUiState.Streaming -> {
                        WarmSectionCard {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text(
                                    text = "Analyzing...",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            if (scamState.partialText.isNotBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = scamState.partialText,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                    is ScamUiState.Result -> {
                        val res = scamState.data
                        val borderCol = when (res.verdict) {
                            "Safe" -> MaterialTheme.colorScheme.secondary
                            "Likely Scam" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.tertiary
                        }
                        WarmSectionCard(borderColor = borderCol) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (res.verdict == "Safe") Icons.Filled.CheckCircle else Icons.Filled.Warning,
                                        contentDescription = null,
                                        tint = borderCol
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = res.verdict,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        GuideSessionCoordinator.speakText("${res.verdict}. ${res.explanation}. ${res.recommendedAction}")
                                    }
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Read verdict aloud")
                                }
                            }
                            Text(res.headline, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(res.explanation, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Action: ${res.recommendedAction}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(6.dp))
                            TextButton(onClick = {
                                scamInput = ""
                                scamBitmap = null
                                onResetScam()
                            }) {
                                Text("Clear")
                            }
                        }
                    }
                    is ScamUiState.Error -> {
                        Text(
                            text = scamState.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    else -> Unit
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    val allPins = remember(circle, safePlaces) {
        buildList {
            add(
                MapPinNode(
                    id = "home",
                    label = "Home",
                    subtitle = circle.homeAddress.ifBlank { "My Home" },
                    lat = circle.homeLat,
                    lng = circle.homeLng,
                    isHome = true
                )
            )
            safePlaces.forEach { sp ->
                add(
                    MapPinNode(
                        id = sp.id,
                        label = sp.label,
                        subtitle = "${sp.category} • ${sp.address}",
                        lat = sp.latitude,
                        lng = sp.longitude,
                        isHome = false
                    )
                )
            }
        }
    }
    val activePin = allPins.firstOrNull { it.id == selectedPinId } ?: allPins.first()
    val schedulesById = remember(localSchedules) { localSchedules.associateBy { it.id } }
    val displayedMedications = remember(medications, localSchedules, schedulesById, circle.id) {
        val fromCircle = medications.map { med ->
            val localSchedule = schedulesById[med.id] ?: run {
                val (defH, defM) = MedicationScheduleEntity.defaultTimeForSlot(med.scheduleSlot)
                MedicationScheduleEntity(
                    id = med.id,
                    circleId = circle.id,
                    name = med.name,
                    dosage = med.dosage,
                    scheduleSlot = med.scheduleSlot,
                    reminderHour = defH,
                    reminderMinute = defM,
                    reminderEnabled = true,
                    takenToday = med.takenToday
                )
            }
            val effectiveDosage = localSchedule.dosage.trim()
                .ifBlank { med.dosage.trim() }
                .ifBlank { "As prescribed" }
            DisplayedMedicationEntry(
                id = med.id,
                name = med.name.ifBlank { localSchedule.name },
                dosage = effectiveDosage,
                scheduleSlot = med.scheduleSlot.ifBlank { localSchedule.scheduleSlot },
                takenToday = med.takenToday || localSchedule.isTakenOnSameLocalDay(),
                schedule = localSchedule.copy(dosage = effectiveDosage)
            )
        }
        val knownIds = medications.map { it.id }.toHashSet()
        val extraRoomOnly = localSchedules
            .filter { it.id !in knownIds }
            .map { sched ->
                DisplayedMedicationEntry(
                    id = sched.id,
                    name = sched.name,
                    dosage = sched.formattedDosage(),
                    scheduleSlot = sched.scheduleSlot,
                    takenToday = sched.isTakenOnSameLocalDay(),
                    schedule = sched
                )
            }
        fromCircle + extraRoomOnly
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("safety_and_map_lazy_column"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Safe Places & Take Me Home Map
        item {
            Column(
                modifier = Modifier.widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                WarmSectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Take Me Home & Safe Map", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = circle.homeAddress.ifBlank { "Home location saved" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = {
                                if (hasLocationPermission) {
                                    fetchLastLocation()
                                } else {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("locate_me_button")
                        ) {
                            Icon(
                                Icons.Filled.MyLocation,
                                contentDescription = "Refresh my GPS location",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Interactive Safe-Place GPS Radar & Neighborhood Map Canvas
                    SafePlacesMapCanvas(
                        pins = allPins,
                        selectedPin = activePin,
                        currentLocation = currentLocation,
                        onSelectPin = { selectedPinId = it.id },
                        onNavigatePin = { pin ->
                            launchDirections(context, pin.lat, pin.lng, pin.label)
                        }
                    )

                    if (locationNotice != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = locationNotice.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                launchDirections(context, circle.homeLat, circle.homeLng, "Home")
                            },
                            modifier = Modifier
                                .weight(1.2f)
                                .height(60.dp)
                                .testTag("safety_take_me_home_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary
                            )
                        ) {
                            Icon(Icons.Filled.Home, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.take_me_home), style = MaterialTheme.typography.titleMedium)
                        }

                        OutlinedButton(
                            onClick = { onSendCheckIn("Safe & Doing Well") },
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp)
                                .testTag("gps_check_in_button")
                        ) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Check In")
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { showAddPlaceDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("add_safe_place_button")
                    ) {
                        Icon(Icons.Filled.AddLocationAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add Trusted Safe Place")
                    }
                }
            }
        }

        items(safePlaces, key = { "sp_${it.id}" }) { place ->
            WarmSectionCard(modifier = Modifier.widthIn(max = 640.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Place,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(place.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${place.category} • ${place.address}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = {
                            launchDirections(context, place.latitude, place.longitude, place.label)
                        },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            Icons.Filled.Directions,
                            contentDescription = "Navigate to ${place.label}",
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                    IconButton(
                        onClick = { onDeleteSafePlace(place.id) },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Remove safe place",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        // 2. Large-Print Speed Dial & SOS Family Alert
        item {
            WarmSectionCard(
                modifier = Modifier.widthIn(max = 640.dp),
                borderColor = MaterialTheme.colorScheme.error
            ) {
                Text("Emergency & Family Call", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            val phone = circle.emergencyContactPhone.trim()
                            if (phone.isNotBlank()) {
                                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                                runCatching { context.startActivity(dialIntent) }
                            }
                        },
                        enabled = circle.emergencyContactPhone.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .testTag("speed_dial_family_button")
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (circle.emergencyContactName.isNotBlank()) {
                                "Call ${circle.emergencyContactName.substringBefore(" ")}"
                            } else {
                                "Call Family"
                            },
                            maxLines = 1
                        )
                    }
                    Button(
                        onClick = onTriggerSos,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .testTag("safety_sos_alert_button")
                    ) {
                        Icon(Icons.Filled.Emergency, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("SOS Alert", maxLines = 1)
                    }
                }
            }
        }

        // 3. Medication Reminders (backed by local Room `medication_schedules` + daily NotificationManager)
        item {
            WarmSectionCard(modifier = Modifier.widthIn(max = 640.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Daily Medicines", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Saved locally on device with daily reminder alerts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onOpenMedicationHistory,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("open_medication_history_button")
                        ) {
                            Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("History")
                        }
                        OutlinedButton(
                            onClick = { showAddMedDialog = true },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("add_medication_button")
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add")
                        }
                    }
                }
                if (displayedMedications.isEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    EmptyFriendlyState(
                        icon = Icons.Filled.Medication,
                        title = "No medicines listed yet",
                        tip = "Tap Add above or ask KindGuide to add your daily pills."
                    )
                }
            }
        }

        items(displayedMedications, key = { "med_${it.id}" }) { entry ->
            val localSchedule = entry.schedule
            val isTaken = entry.takenToday

            WarmSectionCard(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .testTag("medication_card_${entry.id}"),
                borderColor = if (isTaken) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.outline
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = entry.scheduleSlot,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("med_dosage_${entry.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Medication,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Dosage: ${entry.dosage}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                    Button(
                        onClick = { onToggleMedication(entry.id, !isTaken) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isTaken) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        ),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text(if (isTaken) "Taken" else "Took It")
                    }
                    IconButton(
                        onClick = { onDeleteMedication(entry.id) },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete medicine",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = localSchedule.reminderEnabled,
                        onClick = { editingReminderSchedule = localSchedule },
                        leadingIcon = {
                            Icon(
                                imageVector = if (localSchedule.reminderEnabled) {
                                    Icons.Filled.NotificationsActive
                                } else {
                                    Icons.Filled.NotificationsOff
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        label = {
                            Text(
                                text = if (localSchedule.reminderEnabled) {
                                    "Daily ${localSchedule.formattedReminderTime()}"
                                } else {
                                    "Reminder Off (${localSchedule.formattedReminderTime()})"
                                }
                            )
                        },
                        modifier = Modifier.testTag("edit_med_reminder_chip_${entry.id}")
                    )
                    OutlinedButton(
                        onClick = {
                            ensureNotificationsThen(localSchedule) {
                                onTestMedicationReminder(localSchedule)
                            }
                        },
                        modifier = Modifier
                            .height(40.dp)
                            .testTag("test_med_reminder_button_${entry.id}")
                    ) {
                        Icon(
                            Icons.Filled.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Remind Now")
                    }
                }
            }
        }

        // 4. AI Scam Message Detector Trigger Card (opens ModalBottomSheet)
        item {
            WarmSectionCard(modifier = Modifier.widthIn(max = 640.dp)) {
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
                            Icons.Filled.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Scam Message Check", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = "Check suspicious texts or screenshots safely",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { showScamSheet = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("open_scam_checker_button")
                ) {
                    Icon(Icons.Filled.Security, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Check Suspicious Message")
                }
            }
        }
    }
}

@Composable
private fun SafePlacesMapCanvas(
    pins: List<MapPinNode>,
    selectedPin: MapPinNode,
    currentLocation: Pair<Double, Double>?,
    onSelectPin: (MapPinNode) -> Unit,
    onNavigatePin: (MapPinNode) -> Unit
) {
    val homePin = pins.first()
    val refLat = currentLocation?.first ?: homePin.lat
    val refLng = currentLocation?.second ?: homePin.lng

    val bgSurface = MaterialTheme.colorScheme.surfaceVariant
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val routeColor = MaterialTheme.colorScheme.secondary
    val homeColor = MaterialTheme.colorScheme.secondary
    val placeColor = MaterialTheme.colorScheme.primary
    val userGpsColor = MaterialTheme.colorScheme.tertiary

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Pin selector chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            pins.forEach { pin ->
                FilterChip(
                    selected = pin.id == selectedPin.id,
                    onClick = { onSelectPin(pin) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (pin.isHome) Icons.Filled.Home else Icons.Filled.Place,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    label = { Text(pin.label) },
                    modifier = Modifier.testTag("map_pin_chip_${pin.id}")
                )
            }
        }

        // Radar & Coordinate Grid Map
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(MaterialTheme.shapes.medium)
                .testTag("safe_places_radar_map"),
            color = bgSurface,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val center = Offset(w / 2f, h / 2f)
                    val maxRadius = minOf(w, h) * 0.38f

                    // Subtle street grid lines
                    val stepX = w / 6f
                    val stepY = h / 4f
                    for (i in 1..5) {
                        drawLine(
                            color = gridColor,
                            start = Offset(stepX * i, 0f),
                            end = Offset(stepX * i, h),
                            strokeWidth = 1.5f
                        )
                    }
                    for (j in 1..3) {
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, stepY * j),
                            end = Offset(w, stepY * j),
                            strokeWidth = 1.5f
                        )
                    }

                    // Radar distance rings
                    drawCircle(
                        color = gridColor,
                        radius = maxRadius * 0.5f,
                        center = center,
                        style = Stroke(width = 2f)
                    )
                    drawCircle(
                        color = gridColor,
                        radius = maxRadius,
                        center = center,
                        style = Stroke(width = 2f)
                    )

                    // Compute coordinate scale around reference center
                    val cosLat = cos(Math.toRadians(refLat)).coerceAtLeast(0.2)
                    var maxDegSpan = 0.008 // ~800m minimum span
                    pins.forEach { p ->
                        val dLat = kotlin.math.abs(p.lat - refLat)
                        val dLng = kotlin.math.abs((p.lng - refLng) * cosLat)
                        maxDegSpan = max(maxDegSpan, max(dLat, dLng) * 1.35)
                    }

                    fun project(lat: Double, lng: Double): Offset {
                        val dx = ((lng - refLng) * cosLat / maxDegSpan).toFloat().coerceIn(-1f, 1f)
                        val dy = ((refLat - lat) / maxDegSpan).toFloat().coerceIn(-1f, 1f)
                        return Offset(
                            x = center.x + dx * maxRadius,
                            y = center.y + dy * maxRadius
                        )
                    }

                    // Draw dashed guide path from center (current GPS / Home) to selected pin
                    val selectedOffset = project(selectedPin.lat, selectedPin.lng)
                    drawLine(
                        color = routeColor,
                        start = center,
                        end = selectedOffset,
                        strokeWidth = 5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                    )

                    // Draw pins
                    pins.forEach { p ->
                        val pos = project(p.lat, p.lng)
                        val isSelected = p.id == selectedPin.id
                        val pinColor = if (p.isHome) homeColor else placeColor
                        if (isSelected) {
                            drawCircle(
                                color = pinColor.copy(alpha = 0.25f),
                                radius = 28f,
                                center = pos
                            )
                        }
                        drawCircle(
                            color = pinColor,
                            radius = if (isSelected) 15f else 11f,
                            center = pos
                        )
                    }

                    // Draw current GPS location dot if available
                    if (currentLocation != null) {
                        drawCircle(
                            color = userGpsColor.copy(alpha = 0.28f),
                            radius = 22f,
                            center = center
                        )
                        drawCircle(
                            color = userGpsColor,
                            radius = 9f,
                            center = center
                        )
                    }
                }

                // Selected Pin Details Overlay Bar at bottom of map
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${selectedPin.label} • ${formatDistanceLabel(refLat, refLng, selectedPin.lat, selectedPin.lng)}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = selectedPin.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalIconButtonOnMap(
                            label = selectedPin.label,
                            onClick = { onNavigatePin(selectedPin) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilledTonalIconButtonOnMap(
    label: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .height(48.dp)
            .testTag("map_navigate_selected_button"),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Icon(
            Icons.Filled.Directions,
            contentDescription = "Directions to $label",
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text("Go")
    }
}

private fun formatDistanceLabel(
    fromLat: Double,
    fromLng: Double,
    toLat: Double,
    toLng: Double
): String {
    val results = FloatArray(1)
    runCatching {
        Location.distanceBetween(fromLat, fromLng, toLat, toLng, results)
    }
    val meters = results[0]
    return when {
        meters < 35f -> "Right here"
        meters < 1000f -> "${meters.toInt()} m away"
        else -> String.format(Locale.US, "%.1f km away", meters / 1000f)
    }
}

private fun launchDirections(context: android.content.Context, lat: Double, lng: Double, label: String) {
    val navUri = Uri.parse("google.navigation:q=$lat,$lng&mode=w")
    val navIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
        setPackage("com.google.android.apps.maps")
    }
    val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})"))
    runCatching {
        context.startActivity(navIntent)
    }.onFailure {
        runCatching { context.startActivity(fallbackIntent) }
    }
}

@Composable
private fun AddMedicationDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, String, Int, Int, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var dosage by remember { mutableStateOf("1 tablet") }
    var slot by remember { mutableStateOf("Morning") }
    var reminderHour by remember { mutableIntStateOf(8) }
    var reminderMinute by remember { mutableIntStateOf(0) }
    var reminderEnabled by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Medicine Reminder", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Medicine Name") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_med_name_input")
                )
                OutlinedTextField(
                    value = dosage,
                    onValueChange = { dosage = it },
                    label = { Text("Dosage (e.g. 1 pill)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_med_dosage_input")
                )
                Text("Time of Day", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Morning", "Afternoon", "Evening", "Bedtime").forEach { option ->
                        FilterChip(
                            selected = slot == option,
                            onClick = {
                                slot = option
                                val (h, m) = MedicationScheduleEntity.defaultTimeForSlot(option)
                                reminderHour = h
                                reminderMinute = m
                            },
                            label = { Text(option) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Daily Notification", style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = reminderEnabled,
                        onCheckedChange = { reminderEnabled = it }
                    )
                }
                if (reminderEnabled) {
                    ReminderTimeStepperRow(
                        hour = reminderHour,
                        minute = reminderMinute,
                        onTimeChanged = { newH, newM ->
                            reminderHour = newH
                            reminderMinute = newM
                        }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(name, dosage, slot, reminderHour, reminderMinute, reminderEnabled)
                    }
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("save_med_button")
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun EditMedicationReminderDialog(
    schedule: MedicationScheduleEntity,
    onDismiss: () -> Unit,
    onSave: (dosage: String, enabled: Boolean, hour: Int, minute: Int) -> Unit
) {
    var dosage by remember(schedule.id) { mutableStateOf(schedule.dosage) }
    var enabled by remember(schedule.id) { mutableStateOf(schedule.reminderEnabled) }
    var hour by remember(schedule.id) { mutableIntStateOf(schedule.reminderHour) }
    var minute by remember(schedule.id) { mutableIntStateOf(schedule.reminderMinute) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Reminder for ${schedule.name}",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = dosage,
                    onValueChange = { dosage = it },
                    label = { Text("Dosage (e.g. 500mg tablet)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_med_dosage_input")
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Daily Reminder Active", style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = enabled,
                        onCheckedChange = { enabled = it }
                    )
                }
                ReminderTimeStepperRow(
                    hour = hour,
                    minute = minute,
                    onTimeChanged = { newH, newM ->
                        hour = newH
                        minute = newM
                    }
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(dosage, enabled, hour, minute) }) {
                Text("Update")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ReminderTimeStepperRow(
    hour: Int,
    minute: Int,
    onTimeChanged: (Int, Int) -> Unit
) {
    fun shiftMinutes(delta: Int) {
        val total = ((hour * 60 + minute + delta) % 1440 + 1440) % 1440
        onTimeChanged(total / 60, total % 60)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        OutlinedButton(
            onClick = { shiftMinutes(-15) },
            modifier = Modifier.height(48.dp)
        ) { Text("-15m") }
        Text(
            text = MedicationScheduleEntity.formatTime(hour, minute),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        OutlinedButton(
            onClick = { shiftMinutes(15) },
            modifier = Modifier.height(48.dp)
        ) { Text("+15m") }
    }
}

@Composable
private fun AddSafePlaceDialog(
    defaultLat: Double,
    defaultLng: Double,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Double, Double) -> Unit
) {
    var label by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Family") }
    var address by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Safe Place", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Place Name (e.g. Son's House, Clinic)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category (Family / Doctor / Park / Pharmacy)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Address or Neighborhood") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (label.isNotBlank()) onSave(label, category, address, defaultLat, defaultLng) },
                enabled = label.isNotBlank()
            ) { Text("Save Place") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
