package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaRecorder
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.ai.GuideSessionCoordinator
import com.example.data.model.FamilyPhotoItem
import com.example.data.model.VoiceNoteItem
import com.example.ui.components.EmptyFriendlyState
import com.example.ui.components.WarmSectionCard
import java.io.File

@Composable
fun PhotosAndVoiceScreen(
    seniorName: String,
    photos: List<FamilyPhotoItem>,
    voiceNotes: List<VoiceNoteItem>,
    isCaregiverMode: Boolean,
    onSharePhoto: (Bitmap, String, String) -> Unit,
    onDeletePhoto: (String) -> Unit,
    onSendVoiceNote: (senderName: String, durationSeconds: Long, transcript: String, isCaregiver: Boolean) -> Unit,
    onDeleteVoiceNote: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var pendingBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var photoCaption by remember { mutableStateOf("") }
    var showTypeNoteDialog by remember { mutableStateOf(false) }
    var voiceMessageText by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    var recordingStartMs by remember { mutableLongStateOf(0L) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var audioPermissionNotice by remember { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri))
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }
                pendingBitmap = bmp
            }
        }
    }

    val startMicRecording = {
        runCatching {
            val outFile = File(context.cacheDir, "kg_voice_${System.currentTimeMillis()}.m4a")
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setOutputFile(outFile.absolutePath)
            mr.prepare()
            mr.start()
            recorder = mr
            recordingStartMs = System.currentTimeMillis()
            isRecording = true
            audioPermissionNotice = null
        }.onFailure {
            audioPermissionNotice = "Microphone unavailable; tap Type Note to send a spoken message."
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startMicRecording()
        } else {
            audioPermissionNotice = "Microphone permission declined; tap Type Note to send a spoken message."
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
        }
    }

    if (pendingBitmap != null) {
        val bmp = pendingBitmap!!
        AlertDialog(
            onDismissRequest = { pendingBitmap = null },
            title = { Text("Share Photo", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Selected family photo",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(MaterialTheme.shapes.medium),
                        contentScale = ContentScale.Crop
                    )
                    OutlinedTextField(
                        value = photoCaption,
                        onValueChange = { photoCaption = it },
                        label = { Text("Add a caption") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val sender = if (isCaregiverMode) "Family Caregiver" else seniorName.ifBlank { "Senior" }
                        onSharePhoto(bmp, photoCaption.ifBlank { "Shared a moment!" }, sender)
                        photoCaption = ""
                        pendingBitmap = null
                    }
                ) { Text("Share") }
            },
            dismissButton = {
                TextButton(onClick = { pendingBitmap = null }) { Text("Cancel") }
            }
        )
    }

    if (showTypeNoteDialog) {
        AlertDialog(
            onDismissRequest = { showTypeNoteDialog = false },
            title = { Text("Send Spoken Note", style = MaterialTheme.typography.headlineSmall) },
            text = {
                OutlinedTextField(
                    value = voiceMessageText,
                    onValueChange = { voiceMessageText = it },
                    label = { Text("Message to read aloud to family") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("voice_note_input"),
                    maxLines = 3
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (voiceMessageText.isNotBlank()) {
                            val sender = if (isCaregiverMode) "Family Caregiver" else seniorName.ifBlank { "Senior" }
                            onSendVoiceNote(sender, 6L, voiceMessageText, isCaregiverMode)
                            voiceMessageText = ""
                            showTypeNoteDialog = false
                        }
                    },
                    enabled = voiceMessageText.isNotBlank(),
                    modifier = Modifier.testTag("send_text_voice_note_button")
                ) {
                    Text("Send")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTypeNoteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Photos (${photos.size})", style = MaterialTheme.typography.titleMedium) },
                    modifier = Modifier.testTag("tab_family_photos")
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Voice Notes (${voiceNotes.size})", style = MaterialTheme.typography.titleMedium) },
                    modifier = Modifier.testTag("tab_voice_notes")
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (selectedTab == 0) {
                    item {
                        Button(
                            onClick = {
                                photoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .testTag("pick_share_photo_button")
                        ) {
                            Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Share Photo", style = MaterialTheme.typography.titleMedium)
                        }
                    }

                    if (photos.isEmpty()) {
                        item {
                            EmptyFriendlyState(
                                icon = Icons.Filled.PhotoLibrary,
                                title = "No shared photos yet",
                                tip = "Tap Share Photo above to add a family memory."
                            )
                        }
                    } else {
                        items(photos, key = { "ph_${it.id}" }) { photo ->
                            val decodedBitmap = remember(photo.thumbnailBase64) {
                                runCatching {
                                    val bytes = Base64.decode(photo.thumbnailBase64, Base64.DEFAULT)
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                                }.getOrNull()
                            }
                            WarmSectionCard {
                                if (decodedBitmap != null) {
                                    Image(
                                        bitmap = decodedBitmap,
                                        contentDescription = photo.caption,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(210.dp)
                                            .clip(MaterialTheme.shapes.medium),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(Modifier.height(10.dp))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(photo.caption, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            text = "Shared by ${photo.sharedBy}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeletePhoto(photo.id) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Delete photo",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (!isRecording) {
                                        val granted = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (granted) {
                                            startMicRecording()
                                        } else {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                        }
                                    } else {
                                        val elapsedSec = ((System.currentTimeMillis() - recordingStartMs) / 1000L)
                                            .coerceAtLeast(1L)
                                        runCatching { recorder?.stop() }
                                        runCatching { recorder?.release() }
                                        recorder = null
                                        isRecording = false
                                        val sender = if (isCaregiverMode) "Family Caregiver" else seniorName.ifBlank { "Senior" }
                                        onSendVoiceNote(
                                            sender,
                                            elapsedSec,
                                            "Recorded a ${elapsedSec}s voice greeting for the family.",
                                            isCaregiverMode
                                        )
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isRecording) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    }
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp)
                                    .testTag("record_voice_note_button")
                            ) {
                                Icon(
                                    imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                                    contentDescription = null
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (isRecording) "Stop & Send" else "Record Voice", maxLines = 1)
                            }

                            OutlinedButton(
                                onClick = { showTypeNoteDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(54.dp)
                                    .testTag("open_type_voice_note_button")
                            ) {
                                Icon(Icons.Filled.EditNote, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Type Note", maxLines = 1)
                            }
                        }

                        if (audioPermissionNotice != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = audioPermissionNotice.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    if (voiceNotes.isEmpty()) {
                        item {
                            EmptyFriendlyState(
                                icon = Icons.Filled.RecordVoiceOver,
                                title = "No voice notes yet",
                                tip = "Tap Record Voice above to leave a message for your family."
                            )
                        }
                    } else {
                        items(voiceNotes, key = { "vn_${it.id}" }) { note ->
                            WarmSectionCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${note.senderName} • ${note.durationSeconds}s",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(text = note.transcript, style = MaterialTheme.typography.bodyLarge)
                                    }
                                    IconButton(
                                        onClick = { GuideSessionCoordinator.speakText("${note.senderName} says: ${note.transcript}") },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.VolumeUp,
                                            contentDescription = "Listen aloud",
                                            tint = MaterialTheme.colorScheme.secondary
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteVoiceNote(note.id) },
                                        modifier = Modifier.minimumInteractiveComponentSize()
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = "Delete voice note",
                                            tint = MaterialTheme.colorScheme.error
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
}
