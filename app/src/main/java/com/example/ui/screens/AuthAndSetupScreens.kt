package com.example.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Elderly
import androidx.compose.material.icons.filled.FamilyRestroom
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.auth.GoogleSignInButton
import com.example.ui.components.WarmSectionCard

@Composable
fun AuthScreen(
    onAuthSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_companion_hero),
                contentDescription = "KindGuide friendly senior smartphone companion",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(MaterialTheme.shapes.extraLarge),
                contentScale = ContentScale.Crop
            )

            Spacer(Modifier.height(24.dp))
            Text(
                text = "Welcome to KindGuide",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Your patient voice-first smartphone coach, daily helper, and family connection space — one gentle step at a time.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
            WarmSectionCard(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "Sign in once with Google to safely sync tasks, shared family calendars, and guidance.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            GoogleSignInButton(
                onAuthSuccess = {
                    errorMessage = null
                    onAuthSuccess()
                },
                onAuthError = { errorMessage = it }
            )

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    val addAccountIntent = Intent(Settings.ACTION_ADD_ACCOUNT).apply {
                        putExtra(Settings.EXTRA_ACCOUNT_TYPES, arrayOf("com.google"))
                    }
                    runCatching { context.startActivity(addAccountIntent) }.onFailure {
                        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("add_google_account_button")
            ) {
                Icon(Icons.Filled.PersonAdd, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "No Google Account Yet? Add One in Settings",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            if (errorMessage != null) {
                Spacer(Modifier.height(16.dp))
                WarmSectionCard(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    borderColor = MaterialTheme.colorScheme.error
                ) {
                    Text(
                        text = errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
fun CareCircleSetupScreen(
    defaultDisplayName: String,
    isBusy: Boolean,
    statusMessage: String?,
    onCreateCircle: (
        seniorName: String,
        pin: String,
        homeAddress: String,
        homeLat: Double,
        homeLng: Double,
        emergencyName: String,
        emergencyPhone: String
    ) -> Unit,
    onJoinCircle: (inviteCode: String, pin: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var setupAsCaregiver by remember { mutableStateOf(false) }

    var seniorName by remember { mutableStateOf(defaultDisplayName.ifBlank { "Grandpa" }) }
    var pin by remember { mutableStateOf("1234") }
    var homeAddress by remember { mutableStateOf("") }
    var emergencyName by remember { mutableStateOf("") }
    var emergencyPhone by remember { mutableStateOf("") }

    var inviteCodeInput by remember { mutableStateOf("") }
    var joinPinInput by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier.widthIn(max = 600.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Set Up Your Family Circle",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Choose whether this phone is the Senior's primary phone or a Family Caregiver's phone joining remotely.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FilterChip(
                    selected = !setupAsCaregiver,
                    onClick = {
                        setupAsCaregiver = false
                        validationError = null
                    },
                    label = { Text("Senior Mode Setup", style = MaterialTheme.typography.labelLarge) },
                    leadingIcon = { Icon(Icons.Filled.Elderly, contentDescription = null) },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("setup_role_senior_chip")
                )
                FilterChip(
                    selected = setupAsCaregiver,
                    onClick = {
                        setupAsCaregiver = true
                        validationError = null
                    },
                    label = { Text("Join as Family", style = MaterialTheme.typography.labelLarge) },
                    leadingIcon = { Icon(Icons.Filled.FamilyRestroom, contentDescription = null) },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("setup_role_caregiver_chip")
                )
            }

            if (!setupAsCaregiver) {
                WarmSectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = seniorName,
                            onValueChange = { seniorName = it },
                            label = { Text("Senior's Name (e.g. Grandpa Raj)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("setup_senior_name_input")
                        )
                        OutlinedTextField(
                            value = pin,
                            onValueChange = {
                                if (it.length <= 4 && it.all { ch -> ch.isDigit() }) pin = it
                            },
                            label = { Text("4-Digit Caregiver PIN (to unlock Family View)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("setup_pin_input")
                        )
                        OutlinedTextField(
                            value = homeAddress,
                            onValueChange = { homeAddress = it },
                            label = { Text("Home Address (for Take Me Home)") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("setup_home_address_input")
                        )
                        OutlinedTextField(
                            value = emergencyName,
                            onValueChange = { emergencyName = it },
                            label = { Text("Primary Family Contact Name (e.g. Son Aarav)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("setup_emergency_name_input")
                        )
                        OutlinedTextField(
                            value = emergencyPhone,
                            onValueChange = { emergencyPhone = it },
                            label = { Text("Family Contact Phone Number") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("setup_emergency_phone_input")
                        )

                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = {
                                when {
                                    seniorName.isBlank() -> validationError = "Please enter a name."
                                    pin.length != 4 -> validationError = "Caregiver PIN must be 4 digits."
                                    else -> {
                                        validationError = null
                                        onCreateCircle(
                                            seniorName,
                                            pin,
                                            homeAddress,
                                            37.7749,
                                            -122.4194,
                                            emergencyName,
                                            emergencyPhone
                                        )
                                    }
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .testTag("create_circle_button")
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Start Senior Mode", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            } else {
                WarmSectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = "Ask your loved one (or check their Caregiver Dashboard) for the 6-character Family Link Code and Caregiver PIN.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        OutlinedTextField(
                            value = inviteCodeInput,
                            onValueChange = { inviteCodeInput = it.uppercase().take(12) },
                            label = { Text("Family Link Code (e.g. KG4829)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("join_invite_code_input")
                        )
                        OutlinedTextField(
                            value = joinPinInput,
                            onValueChange = {
                                if (it.length <= 4 && it.all { ch -> ch.isDigit() }) joinPinInput = it
                            },
                            label = { Text("4-Digit Caregiver PIN") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("join_pin_input")
                        )

                        Button(
                            onClick = {
                                when {
                                    inviteCodeInput.isBlank() -> validationError = "Enter the Family Link Code."
                                    joinPinInput.length != 4 -> validationError = "Enter the 4-digit PIN."
                                    else -> {
                                        validationError = null
                                        onJoinCircle(inviteCodeInput, joinPinInput)
                                    }
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .testTag("join_circle_button")
                        ) {
                            if (isBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Join Care Circle", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }

            val displayError = validationError ?: statusMessage
            if (displayError != null) {
                WarmSectionCard(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    borderColor = MaterialTheme.colorScheme.error
                ) {
                    Text(
                        text = displayError,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
