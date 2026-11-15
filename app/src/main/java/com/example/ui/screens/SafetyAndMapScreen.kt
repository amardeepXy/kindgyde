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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.example.data.model.CareCircle
import com.example.data.model.MedicationItem
import com.example.data.model.SafePlace
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
    modifier: Modifier = Modifier
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
    var showAddPlaceDialog by remember { mutableStateOf(false) }
    var showScamSheet by remember { mutableStateOf(false) }
    var scamInput by remember { mutableStateOf("") }
    var scamBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedPinId by remember { mutableStateOf("home") }

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
            onSave = { name, dosage, slot ->
                onAddMedication(name, dosage, slot)
                showAddMedDialog = false
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onCheckScam(scamInput, scamBitmap) },
                        enabled = scamInput.isNotBlank() && scamState !is ScamUiState.Loading,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("check_scam_text_button")
                    ) {
                        if (scamState is ScamUiState.Loading) {
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
                        enabled = scamState !is ScamUiState.Loading,
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

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
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

        // 3. Medication Reminders
        item {
            WarmSectionCard(modifier = Modifier.widthIn(max = 640.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Daily Medicines", style = MaterialTheme.typography.titleLarge)
                    OutlinedButton(
                        onClick = { showAddMedDialog = true },
                        modifier = Modifier.testTag("add_medication_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }
                if (medications.isEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    EmptyFriendlyState(
                        icon = Icons.Filled.Medication,
                        title = "No medicines listed yet",
                        tip = "Tap Add above or ask KindGuide to add your daily pills."
                    )
                }
            }
        }

        items(medications, key = { "med_${it.id}" }) { med ->
            WarmSectionCard(
                modifier = Modifier.widthIn(max = 640.dp),
                borderColor = if (med.takenToday) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.outline
                }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${med.scheduleSlot} • ${med.dosage}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(med.name, style = MaterialTheme.typography.titleLarge)
                    }
                    Button(
                        onClick = { onToggleMedication(med.id, !med.takenToday) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (med.takenToday) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        ),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text(if (med.takenToday) "Taken" else "Took It")
                    }
                    IconButton(
                        onClick = { onDeleteMedication(med.id) },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete medicine",
                            tint = MaterialTheme.colorScheme.error
                        )
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
    onSave: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var dosage by remember { mutableStateOf("1 tablet") }
    var slot by remember { mutableStateOf("Morning") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Medicine Reminder", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Medicine Name") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dosage,
                    onValueChange = { dosage = it },
                    label = { Text("Dosage (e.g. 1 pill)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = slot,
                    onValueChange = { slot = it },
                    label = { Text("Time of Day (Morning / Evening / Bedtime)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onSave(name, dosage, slot) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
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
