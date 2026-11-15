package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.firebase.OperationType
import com.example.data.firebase.handleFirestoreError
import com.example.data.model.CalendarEvent
import com.example.data.model.CareCircle
import com.example.data.model.CareTask
import com.example.data.model.ConversationMessage
import com.example.data.model.FamilyPhotoItem
import com.example.data.model.MedicationItem
import com.example.data.model.SafePlace
import com.example.data.model.SafetyAlert
import com.example.data.model.VoiceNoteItem
import com.example.data.model.sha256Hex
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class KindGuideRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        Firebase.auth
    )

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing KindGuide data.")

    private fun <T> Flow<T>.logFirestoreErrors(
        uid: String,
        op: OperationType,
        path: String
    ): Flow<T> = catch { error ->
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val exceptionToLog = firestoreError ?: (error as? Exception)
        if (exceptionToLog != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(exceptionToLog, op, path)
        }
        throw error
    }

    fun observeCareCircles(uid: String): Flow<List<CareCircle>> = flow {
        val path = "careCircles"
        emitAll(
            db.collection(path)
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        CareCircle::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
                }
                .logFirestoreErrors(uid, OperationType.LIST, path)
        )
    }

    suspend fun createCareCircle(
        seniorName: String,
        pin: String,
        homeAddress: String,
        homeLat: Double,
        homeLng: Double,
        emergencyContactName: String,
        emergencyContactPhone: String
    ): Result<CareCircle> = runCatching {
        val uid = requireUserId()
        val circleRef = db.collection("careCircles").document()
        val inviteCode = generateInviteCode()
        val pinHash = sha256Hex(pin)
        val payload = mapOf(
            "ownerUid" to uid,
            "caregiverUids" to emptyList<String>(),
            "memberUids" to listOf(uid),
            "inviteCode" to inviteCode,
            "caregiverPinHash" to pinHash,
            "seniorName" to seniorName.trim().take(60),
            "homeAddress" to homeAddress.trim().take(200),
            "homeLat" to homeLat,
            "homeLng" to homeLng,
            "emergencyContactName" to emergencyContactName.trim().take(60),
            "emergencyContactPhone" to emergencyContactPhone.trim().take(30),
            "lastCheckInLabel" to "Circle created",
            "lastMood" to "Calm",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            circleRef.set(payload).await()
            val inviteRef = db.collection("inviteCodes").document(inviteCode)
            inviteRef.set(
                mapOf(
                    "circleId" to circleRef.id,
                    "ownerUid" to uid,
                    "caregiverPinHash" to pinHash,
                    "createdAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, circleRef.path)
            throw e
        }

        // Seed helpful initial Home SafePlace if address provided
        val memberUids = listOf(uid)
        if (homeAddress.isNotBlank()) {
            addSafePlace(
                circleId = circleRef.id,
                memberUids = memberUids,
                label = "Home",
                category = "Home",
                address = homeAddress,
                latitude = homeLat,
                longitude = homeLng
            )
        }

        CareCircle(
            id = circleRef.id,
            ownerUid = uid,
            caregiverUids = emptyList(),
            memberUids = memberUids,
            inviteCode = inviteCode,
            caregiverPinHash = pinHash,
            seniorName = seniorName.trim().take(60),
            homeAddress = homeAddress.trim().take(200),
            homeLat = homeLat,
            homeLng = homeLng,
            emergencyContactName = emergencyContactName.trim().take(60),
            emergencyContactPhone = emergencyContactPhone.trim().take(30),
            lastCheckInLabel = "Circle created",
            lastMood = "Calm"
        )
    }

    suspend fun joinCareCircleByCode(
        inviteCode: String,
        pin: String
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val cleanCode = inviteCode.trim().uppercase()
        val inviteRef = db.collection("inviteCodes").document(cleanCode)
        val inviteSnap = try {
            inviteRef.get().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, inviteRef.path)
            throw e
        }
        if (!inviteSnap.exists()) {
            throw IllegalArgumentException("Family Link Code \"$cleanCode\" was not found.")
        }
        val expectedHash = inviteSnap.getString("caregiverPinHash").orEmpty()
        if (expectedHash != sha256Hex(pin.trim())) {
            throw IllegalArgumentException("Caregiver PIN does not match this Family Link Code.")
        }
        val circleId = inviteSnap.getString("circleId")
            ?: throw IllegalStateException("Invite record is missing Care Circle ID.")

        val circleRef = db.collection("careCircles").document(circleId)
        try {
            circleRef.update(
                mapOf(
                    "caregiverUids" to FieldValue.arrayUnion(uid),
                    "memberUids" to FieldValue.arrayUnion(uid),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, circleRef.path)
            throw e
        }
        circleId
    }

    suspend fun syncSubcollectionMembership(circle: CareCircle) {
        val uid = auth.currentUser?.uid ?: return
        if (uid !in circle.memberUids || circle.memberUids.size <= 1) return
        val circleRef = db.collection("careCircles").document(circle.id)
        val withUpdatedAt = listOf("tasks", "events", "medications", "alerts")
        val withoutUpdatedAt = listOf("photos", "voiceNotes", "safePlaces", "conversations")

        for (sub in withUpdatedAt) {
            try {
                val docs = circleRef.collection(sub)
                    .whereArrayContains("memberUids", uid)
                    .get()
                    .await()
                for (doc in docs.documents) {
                    val existingMembers = (doc.get("memberUids") as? List<*>)
                        ?.filterIsInstance<String>()
                        .orEmpty()
                    if (!existingMembers.containsAll(circle.memberUids)) {
                        doc.reference.update(
                            mapOf(
                                "memberUids" to circle.memberUids,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, "${circleRef.path}/$sub")
            }
        }

        for (sub in withoutUpdatedAt) {
            try {
                val docs = circleRef.collection(sub)
                    .whereArrayContains("memberUids", uid)
                    .get()
                    .await()
                for (doc in docs.documents) {
                    val existingMembers = (doc.get("memberUids") as? List<*>)
                        ?.filterIsInstance<String>()
                        .orEmpty()
                    if (!existingMembers.containsAll(circle.memberUids)) {
                        doc.reference.update(mapOf("memberUids" to circle.memberUids)).await()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, "${circleRef.path}/$sub")
            }
        }
    }

    suspend fun updateCareCircleProfile(
        circleId: String,
        updates: Map<String, Any>
    ): Result<Unit> = runCatching {
        requireUserId()
        val circleRef = db.collection("careCircles").document(circleId)
        val payload = updates.toMutableMap().apply {
            this["updatedAt"] = FieldValue.serverTimestamp()
        }
        try {
            circleRef.update(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, circleRef.path)
            throw e
        }
    }

    // --- Tasks ---

    fun observeTasks(circleId: String, uid: String): Flow<List<CareTask>> = flow {
        val path = "careCircles/$circleId/tasks"
        emitAll(
            db.collection("careCircles").document(circleId).collection("tasks")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        CareTask::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedWith(
                        compareBy<CareTask> { it.completed }
                            .thenByDescending { it.createdAt ?: Timestamp(0, 0) }
                    )
                }
                .logFirestoreErrors(uid, OperationType.LIST, path)
        )
    }

    suspend fun createTask(
        circleId: String,
        memberUids: List<String>,
        title: String,
        category: String = "Daily",
        dueTimeLabel: String = "Today",
        createdBy: String = "senior"
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("careCircles").document(circleId)
            .collection("tasks").document()
        val payload = mapOf(
            "memberUids" to memberUids,
            "authorUid" to uid,
            "title" to title.trim().take(160),
            "category" to category.trim().take(40).ifBlank { "Daily" },
            "dueTimeLabel" to dueTimeLabel.trim().take(40),
            "completed" to false,
            "createdBy" to createdBy,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
        docRef.id
    }

    suspend fun updateTask(
        circleId: String,
        taskId: String,
        updates: Map<String, Any>
    ): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection("careCircles").document(circleId)
            .collection("tasks").document(taskId)
        val payload = updates.toMutableMap().apply {
            this["updatedAt"] = FieldValue.serverTimestamp()
        }
        try {
            docRef.update(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    suspend fun deleteTask(circleId: String, taskId: String): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection("careCircles").document(circleId)
            .collection("tasks").document(taskId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    // --- Calendar Events ---

    fun observeEvents(circleId: String, uid: String): Flow<List<CalendarEvent>> = flow {
        val path = "careCircles/$circleId/events"
        emitAll(
            db.collection("careCircles").document(circleId).collection("events")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        CalendarEvent::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .logFirestoreErrors(uid, OperationType.LIST, path)
        )
    }

    suspend fun createEvent(
        circleId: String,
        memberUids: List<String>,
        title: String,
        dateLabel: String,
        timeLabel: String,
        location: String = "",
        notes: String = "",
        createdBy: String = "senior"
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("careCircles").document(circleId)
            .collection("events").document()
        val payload = mapOf(
            "memberUids" to memberUids,
            "authorUid" to uid,
            "title" to title.trim().take(160),
            "dateLabel" to dateLabel.trim().take(40).ifBlank { "Upcoming" },
            "timeLabel" to timeLabel.trim().take(40),
            "location" to location.trim().take(120),
            "notes" to notes.trim().take(300),
            "createdBy" to createdBy,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
        docRef.id
    }

    suspend fun updateEvent(
        circleId: String,
        eventId: String,
        updates: Map<String, Any>
    ): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection("careCircles").document(circleId)
            .collection("events").document(eventId)
        val payload = updates.toMutableMap().apply {
            this["updatedAt"] = FieldValue.serverTimestamp()
        }
        try {
            docRef.update(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    suspend fun deleteEvent(circleId: String, eventId: String): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection("careCircles").document(circleId)
            .collection("events").document(eventId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    // --- Medications ---

    fun observeMedications(circleId: String, uid: String): Flow<List<MedicationItem>> = flow {
        val path = "careCircles/$circleId/medications"
        emitAll(
            db.collection("careCircles").document(circleId).collection("medications")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        MedicationItem::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.takenToday }
                }
                .logFirestoreErrors(uid, OperationType.LIST, path)
        )
    }

    suspend fun createMedication(
        circleId: String,
        memberUids: List<String>,
        name: String,
        dosage: String,
        scheduleSlot: String
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val normalizedSlot = when (scheduleSlot.trim().lowercase()) {
            "afternoon" -> "Afternoon"
            "evening" -> "Evening"
            "bedtime" -> "Bedtime"
            else -> "Morning"
        }
        val docRef = db.collection("careCircles").document(circleId)
            .collection("medications").document()
        val payload = mapOf(
            "memberUids" to memberUids,
            "authorUid" to uid,
            "name" to name.trim().take(100),
            "dosage" to dosage.trim().take(60),
            "scheduleSlot" to normalizedSlot,
            "takenToday" to false,
            "lastTakenLabel" to "Not taken yet today",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
        docRef.id
    }

    suspend fun updateMedication(
        circleId: String,
        medId: String,
        updates: Map<String, Any>
    ): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection("careCircles").document(circleId)
            .collection("medications").document(medId)
        val payload = updates.toMutableMap().apply {
            this["updatedAt"] = FieldValue.serverTimestamp()
        }
        try {
            docRef.update(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    suspend fun deleteMedication(circleId: String, medId: String): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection("careCircles").document(circleId)
            .collection("medications").document(medId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    // --- Photos ---

    fun observePhotos(circleId: String, uid: String): Flow<List<FamilyPhotoItem>> = flow {
        val path = "careCircles/$circleId/photos"
        emitAll(
            db.collection("careCircles").document(circleId).collection("photos")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        FamilyPhotoItem::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .logFirestoreErrors(uid, OperationType.LIST, path)
        )
    }

    suspend fun sharePhoto(
        circleId: String,
        memberUids: List<String>,
        caption: String,
        sharedBy: String,
        thumbnailBase64: String
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("careCircles").document(circleId)
            .collection("photos").document()
        val payload = mapOf(
            "memberUids" to memberUids,
            "authorUid" to uid,
            "caption" to caption.trim().take(200).ifBlank { "Shared family photo" },
            "sharedBy" to sharedBy.trim().take(60).ifBlank { "Family" },
            "thumbnailBase64" to thumbnailBase64.take(350_000),
            "createdAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
        docRef.id
    }

    suspend fun deletePhoto(circleId: String, photoId: String): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection("careCircles").document(circleId)
            .collection("photos").document(photoId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    // --- Voice Notes ---

    fun observeVoiceNotes(circleId: String, uid: String): Flow<List<VoiceNoteItem>> = flow {
        val path = "careCircles/$circleId/voiceNotes"
        emitAll(
            db.collection("careCircles").document(circleId).collection("voiceNotes")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        VoiceNoteItem::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .logFirestoreErrors(uid, OperationType.LIST, path)
        )
    }

    suspend fun addVoiceNote(
        circleId: String,
        memberUids: List<String>,
        senderName: String,
        durationSeconds: Long,
        transcript: String,
        isFromCaregiver: Boolean
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("careCircles").document(circleId)
            .collection("voiceNotes").document()
        val payload = mapOf(
            "memberUids" to memberUids,
            "authorUid" to uid,
            "senderName" to senderName.trim().take(60).ifBlank { "Family" },
            "durationSeconds" to durationSeconds.coerceIn(0L, 600L),
            "transcript" to transcript.trim().take(1000),
            "isFromCaregiver" to isFromCaregiver,
            "createdAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
        docRef.id
    }

    suspend fun deleteVoiceNote(circleId: String, noteId: String): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection("careCircles").document(circleId)
            .collection("voiceNotes").document(noteId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    // --- Safe Places ---

    fun observeSafePlaces(circleId: String, uid: String): Flow<List<SafePlace>> = flow {
        val path = "careCircles/$circleId/safePlaces"
        emitAll(
            db.collection("careCircles").document(circleId).collection("safePlaces")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        SafePlace::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .logFirestoreErrors(uid, OperationType.LIST, path)
        )
    }

    suspend fun addSafePlace(
        circleId: String,
        memberUids: List<String>,
        label: String,
        category: String,
        address: String,
        latitude: Double,
        longitude: Double
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("careCircles").document(circleId)
            .collection("safePlaces").document()
        val payload = mapOf(
            "memberUids" to memberUids,
            "authorUid" to uid,
            "label" to label.trim().take(80),
            "category" to category.trim().take(40).ifBlank { "Place" },
            "address" to address.trim().take(200),
            "latitude" to latitude.coerceIn(-90.0, 90.0),
            "longitude" to longitude.coerceIn(-180.0, 180.0),
            "createdAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
        docRef.id
    }

    suspend fun deleteSafePlace(circleId: String, placeId: String): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection("careCircles").document(circleId)
            .collection("safePlaces").document(placeId)
        try {
            docRef.delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            throw e
        }
    }

    // --- Safety Alerts ---

    fun observeAlerts(circleId: String, uid: String): Flow<List<SafetyAlert>> = flow {
        val path = "careCircles/$circleId/alerts"
        emitAll(
            db.collection("careCircles").document(circleId).collection("alerts")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        SafetyAlert::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                }
                .logFirestoreErrors(uid, OperationType.LIST, path)
        )
    }

    suspend fun createAlert(
        circleId: String,
        memberUids: List<String>,
        type: String,
        message: String,
        latitude: Double = 0.0,
        longitude: Double = 0.0
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val validType = if (type in listOf("sos", "scam_warning", "stuck_help", "check_in")) type else "check_in"
        val docRef = db.collection("careCircles").document(circleId)
            .collection("alerts").document()
        val payload = mapOf(
            "memberUids" to memberUids,
            "authorUid" to uid,
            "type" to validType,
            "message" to message.trim().take(400),
            "latitude" to latitude.coerceIn(-90.0, 90.0),
            "longitude" to longitude.coerceIn(-180.0, 180.0),
            "resolved" to false,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
        docRef.id
    }

    suspend fun resolveAlert(circleId: String, alertId: String, resolved: Boolean = true): Result<Unit> =
        runCatching {
            requireUserId()
            val docRef = db.collection("careCircles").document(circleId)
                .collection("alerts").document(alertId)
            try {
                docRef.update(
                    mapOf(
                        "resolved" to resolved,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, docRef.path)
                throw e
            }
        }

    // --- Conversations ---

    fun observeConversations(circleId: String, uid: String): Flow<List<ConversationMessage>> = flow {
        val path = "careCircles/$circleId/conversations"
        emitAll(
            db.collection("careCircles").document(circleId).collection("conversations")
                .whereArrayContains("memberUids", uid)
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        ConversationMessage::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.createdAt ?: Timestamp(0, 0) }
                }
                .logFirestoreErrors(uid, OperationType.LIST, path)
        )
    }

    suspend fun addConversationMessage(
        circleId: String,
        memberUids: List<String>,
        role: String,
        text: String,
        guideStepTitle: String = "",
        stepNumber: Long = 0L,
        totalSteps: Long = 0L,
        isStuckFlagged: Boolean = false
    ): Result<String> = runCatching {
        val uid = requireUserId()
        val docRef = db.collection("careCircles").document(circleId)
            .collection("conversations").document()
        val payload = mapOf(
            "memberUids" to memberUids,
            "authorUid" to uid,
            "role" to if (role == "model") "model" else "user",
            "text" to text.trim().take(4000).ifBlank { "..." },
            "guideStepTitle" to guideStepTitle.trim().take(200),
            "stepNumber" to stepNumber.coerceIn(0L, 30L),
            "totalSteps" to totalSteps.coerceIn(0L, 30L),
            "isStuckFlagged" to isStuckFlagged,
            "createdAt" to FieldValue.serverTimestamp()
        )
        try {
            docRef.set(payload).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            throw e
        }
        docRef.id
    }

    suspend fun flagMessageStuck(circleId: String, msgId: String): Result<Unit> = runCatching {
        requireUserId()
        val docRef = db.collection("careCircles").document(circleId)
            .collection("conversations").document(msgId)
        try {
            docRef.update(mapOf("isStuckFlagged" to true)).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            throw e
        }
    }

    private fun generateInviteCode(): String {
        val suffix = UUID.randomUUID().toString()
            .replace("-", "")
            .uppercase()
            .filter { it in 'A'..'Z' || it in '2'..'9' }
            .take(4)
            .padEnd(4, '7')
        return "KG$suffix"
    }
}
