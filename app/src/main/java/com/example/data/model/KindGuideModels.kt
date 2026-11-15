package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import java.security.MessageDigest

fun sha256Hex(input: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

@IgnoreExtraProperties
data class CareCircle(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val caregiverUids: List<String> = emptyList(),
    val memberUids: List<String> = emptyList(),
    val inviteCode: String = "",
    val caregiverPinHash: String = "",
    val seniorName: String = "",
    val homeAddress: String = "",
    val homeLat: Double = 37.7749,
    val homeLng: Double = -122.4194,
    val emergencyContactName: String = "",
    val emergencyContactPhone: String = "",
    val lastCheckInLabel: String = "",
    val lastMood: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CareTask(
    @DocumentId val id: String = "",
    val memberUids: List<String> = emptyList(),
    val authorUid: String = "",
    val title: String = "",
    val category: String = "Daily",
    val dueTimeLabel: String = "",
    val completed: Boolean = false,
    val createdBy: String = "senior",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CalendarEvent(
    @DocumentId val id: String = "",
    val memberUids: List<String> = emptyList(),
    val authorUid: String = "",
    val title: String = "",
    val dateLabel: String = "",
    val timeLabel: String = "",
    val location: String = "",
    val notes: String = "",
    val createdBy: String = "senior",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class MedicationItem(
    @DocumentId val id: String = "",
    val memberUids: List<String> = emptyList(),
    val authorUid: String = "",
    val name: String = "",
    val dosage: String = "",
    val scheduleSlot: String = "Morning",
    val takenToday: Boolean = false,
    val lastTakenLabel: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class FamilyPhotoItem(
    @DocumentId val id: String = "",
    val memberUids: List<String> = emptyList(),
    val authorUid: String = "",
    val caption: String = "",
    val sharedBy: String = "",
    val thumbnailBase64: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class VoiceNoteItem(
    @DocumentId val id: String = "",
    val memberUids: List<String> = emptyList(),
    val authorUid: String = "",
    val senderName: String = "",
    val durationSeconds: Long = 0L,
    val transcript: String = "",
    val isFromCaregiver: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SafePlace(
    @DocumentId val id: String = "",
    val memberUids: List<String> = emptyList(),
    val authorUid: String = "",
    val label: String = "",
    val category: String = "Family",
    val address: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@IgnoreExtraProperties
data class SafetyAlert(
    @DocumentId val id: String = "",
    val memberUids: List<String> = emptyList(),
    val authorUid: String = "",
    val type: String = "check_in",
    val message: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val resolved: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class ConversationMessage(
    @DocumentId val id: String = "",
    val memberUids: List<String> = emptyList(),
    val authorUid: String = "",
    val role: String = "user",
    val text: String = "",
    val guideStepTitle: String = "",
    val stepNumber: Long = 0L,
    val totalSteps: Long = 0L,
    val isStuckFlagged: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null
)
