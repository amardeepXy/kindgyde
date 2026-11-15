package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Elderly
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.components.EmptyFriendlyState
import com.example.ui.components.WarmSectionCard
import com.example.ui.state.CircleDashboardBundle

@Composable
fun CaregiverDashboardScreen(
    bundle: CircleDashboardBundle,
    brainGamesWon: Int,
    onSwitchToSeniorMode: () -> Unit,
    onSignOut: () -> Unit,
    onResolveAlert: (String) -> Unit,
    onPushTask: (title: String, category: String, dueTime: String) -> Unit,
    onPushEvent: (title: String, date: String, time: String, location: String, notes: String) -> Unit,
    onAddMedication: (name: String, dosage: String, slot: String) -> Unit,
    onAddSafePlace: (label: String, category: String, address: String, lat: Double, lng: Double) -> Unit,
    onSendVoiceNote: (senderName: String, transcript: String) -> Unit,
    onUpdateCircleSettings: (
        seniorName: String,
        homeAddress: String,
        homeLat: Double,
        homeLng: Double,
        emergencyName: String,
        emergencyPhone: String,
        newPin: String
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    val circle = bundle.circle
    var showTaskDialog by remember { mutableStateOf(false) }
    var showEventDialog by remember { mutableStateOf(false) }
    var showMedDialog by remember { mutableStateOf(false) }
    var showPlaceDialog by remember { mutableStateOf(false) }
    var showVoiceDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    if (showTaskDialog) {
        CaregiverInputDialog(
            title = "Send Task to ${circle.seniorName}",
            field1Label = "Task Description",
            field2Label = "Category (e.g. Health, Home)",
            field3Label = "Due Time (e.g. 4:00 PM)",
            default2 = "Family",
            default3 = "Today",
            onDismiss = { showTaskDialog = false },
            onSave = { f1, f2, f3 ->
                onPushTask(f1, f2, f3)
                showTaskDialog = false
            }
        )
    }

    if (showEventDialog) {
        CaregiverInputDialog(
            title = "Add Shared Calendar Event",
            field1Label = "Event Title",
            field2Label = "Date (e.g. Friday, Nov 20)",
            field3Label = "Time & Place",
            default2 = "Tomorrow",
            default3 = "10:30 AM",
            onDismiss = { showEventDialog = false },
            onSave = { f1, f2, f3 ->
                onPushEvent(f1, f2, f3, "", "Scheduled by family caregiver")
                showEventDialog = false
            }
        )
    }

    if (showMedDialog) {
        CaregiverInputDialog(
            title = "Add Medication Reminder",
            field1Label = "Medicine Name",
            field2Label = "Dosage",
            field3Label = "Time Slot (Morning / Evening)",
            default2 = "1 tablet",
            default3 = "Morning",
            onDismiss = { showMedDialog = false },
            onSave = { f1, f2, f3 ->
                onAddMedication(f1, f2, f3)
                showMedDialog = false
            }
        )
    }

    if (showPlaceDialog) {
        CaregiverInputDialog(
            title = "Add Trusted Safe Place",
            field1Label = "Place Label (e.g. Daughter's Home)",
            field2Label = "Category (Family / Clinic)",
            field3Label = "Street Address",
            default2 = "Family",
            default3 = circle.homeAddress,
            onDismiss = { showPlaceDialog = false },
            onSave = { f1, f2, f3 ->
                onAddSafePlace(f1, f2, f3, circle.homeLat, circle.homeLng)
                showPlaceDialog = false
            }
        )
    }

    if (showVoiceDialog) {
        CaregiverInputDialog(
            title = "Send Voice Note to ${circle.seniorName}",
            field1Label = "Your Message (will be read aloud)",
            field2Label = "Your Name",
            field3Label = "Occasion (optional)",
            default2 = "Family Caregiver",
            default3 = "Thinking of you",
            onDismiss = { showVoiceDialog = false },
            onSave = { msg, sender, _ ->
                onSendVoiceNote(sender.ifBlank { "Family" }, msg)
                showVoiceDialog = false
            }
        )
    }

    if (showSettingsDialog) {
        CareCircleSettingsDialog(
            circle = circle,
            onDismiss = { showSettingsDialog = false },
            onSave = { sName, addr, lat, lng, eName, ePhone, pin ->
                onUpdateCircleSettings(sName, addr, lat, lng, eName, ePhone, pin)
                showSettingsDialog = false
            }
        )
    }

    val activeAlerts = bundle.alerts.filter { !it.resolved }
    val stuckMessages = bundle.conversations.filter { it.isStuckFlagged }
    val completedTasks = bundle.tasks.count { it.completed }
    val takenMeds = bundle.medications.count { it.takenToday }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 680.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header + Family Invite Code (Clean Ivory card with primary border)
            WarmSectionCard(borderColor = MaterialTheme.colorScheme.primary) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            Icons.Filled.FamilyRestroom,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Caregiver Dashboard", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = "${circle.seniorName} • ${circle.memberUids.size} members",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(
                        onClick = onSignOut,
                        modifier = Modifier.testTag("caregiver_sign_out_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Sign Out")
                    }
                }

                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Family Link Code", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = circle.inviteCode,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onSwitchToSeniorMode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("switch_to_senior_mode_button")
                ) {
                    Icon(Icons.Filled.Elderly, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Switch to ${stringResource(R.string.senior_mode)}", style = MaterialTheme.typography.titleMedium)
                }
            }

            // Live Progress Summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CareMetricCard(
                    label = "Tasks",
                    value = "$completedTasks/${bundle.tasks.size}",
                    modifier = Modifier.weight(1f)
                )
                CareMetricCard(
                    label = "Meds",
                    value = "$takenMeds/${bundle.medications.size}",
                    modifier = Modifier.weight(1f)
                )
                CareMetricCard(
                    label = "Games",
                    value = "$brainGamesWon",
                    modifier = Modifier.weight(1f)
                )
            }

            if (circle.lastCheckInLabel.isNotBlank()) {
                WarmSectionCard(borderColor = MaterialTheme.colorScheme.secondary) {
                    Text(
                        text = "Latest Check-In: ${circle.lastCheckInLabel}",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            // Needs Attention & Stuck-Step Feed (Flattened inside one Clean Ivory card)
            WarmSectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.NotificationsActive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Alerts & Attention (${activeAlerts.size})",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
                Spacer(Modifier.height(10.dp))

                if (activeAlerts.isEmpty() && stuckMessages.isEmpty()) {
                    EmptyFriendlyState(
                        icon = Icons.Filled.CheckCircle,
                        title = "All clear right now",
                        tip = "SOS alerts and scam warnings for ${circle.seniorName} appear here."
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        activeAlerts.forEachIndexed { idx, alert ->
                            if (idx > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = alert.type.uppercase().replace("_", " "),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(text = alert.message, style = MaterialTheme.typography.bodyLarge)
                                Button(
                                    onClick = { onResolveAlert(alert.id) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondary
                                    )
                                ) {
                                    Text("Resolve")
                                }
                            }
                        }

                        stuckMessages.takeLast(2).forEach { msg ->
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "Asked for Simpler Help",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            // Remote Support Controls (Clean 2-Column Equal-Width Grid)
            WarmSectionCard {
                Text("Remote Support Actions", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showTaskDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Icon(Icons.Filled.AddTask, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Push Task", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { showEventDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Add Event", maxLines = 1)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showMedDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Icon(Icons.Filled.Medication, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Add Med", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { showPlaceDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Icon(Icons.Filled.AddLocationAlt, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Safe Place", maxLines = 1)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showVoiceDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Icon(Icons.Filled.RecordVoiceOver, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Voice Note", maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Settings", maxLines = 1)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CareMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    WarmSectionCard(modifier = modifier, containerColor = MaterialTheme.colorScheme.surface) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CaregiverInputDialog(
    title: String,
    field1Label: String,
    field2Label: String,
    field3Label: String,
    default2: String,
    default3: String,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var f1 by remember { mutableStateOf("") }
    var f2 by remember { mutableStateOf(default2) }
    var f3 by remember { mutableStateOf(default3) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text(field1Label) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text(field2Label) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = f3, onValueChange = { f3 = it }, label = { Text(field3Label) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = { if (f1.isNotBlank()) onSave(f1, f2, f3) }, enabled = f1.isNotBlank()) {
                Text("Send")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CareCircleSettingsDialog(
    circle: com.example.data.model.CareCircle,
    onDismiss: () -> Unit,
    onSave: (String, String, Double, Double, String, String, String) -> Unit
) {
    var seniorName by remember { mutableStateOf(circle.seniorName) }
    var homeAddress by remember { mutableStateOf(circle.homeAddress) }
    var emergencyName by remember { mutableStateOf(circle.emergencyContactName) }
    var emergencyPhone by remember { mutableStateOf(circle.emergencyContactPhone) }
    var newPin by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Care Circle Profile & PIN", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = seniorName, onValueChange = { seniorName = it }, label = { Text("Senior Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = homeAddress, onValueChange = { homeAddress = it }, label = { Text("Home Address") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = emergencyName, onValueChange = { emergencyName = it }, label = { Text("Emergency Contact Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = emergencyPhone,
                    onValueChange = { emergencyPhone = it },
                    label = { Text("Emergency Phone") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPin,
                    onValueChange = { if (it.length <= 4 && it.all { ch -> ch.isDigit() }) newPin = it },
                    label = { Text("New 4-Digit PIN (leave blank to keep)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        seniorName,
                        homeAddress,
                        circle.homeLat,
                        circle.homeLng,
                        emergencyName,
                        emergencyPhone,
                        newPin
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
