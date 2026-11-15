package com.example.viewmodel

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.DailyEngagementContent
import com.example.ai.GuideSessionCoordinator
import com.example.ai.KindGuideAiService
import com.example.data.model.CareCircle
import com.example.data.model.sha256Hex
import com.example.data.repository.KindGuideRepository
import com.example.ui.state.CircleDashboardBundle
import com.example.ui.state.DataUiState
import com.example.ui.state.EngagementUiState
import com.example.ui.state.RoleMode
import com.example.ui.state.ScamUiState
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class KindGuideViewModel(
    private val repository: KindGuideRepository,
    private val aiService: KindGuideAiService,
    private val currentUserId: String,
    private val userDisplayName: String
) : ViewModel() {

    private val _roleMode = MutableStateFlow(RoleMode.SENIOR)
    val roleMode: StateFlow<RoleMode> = _roleMode.asStateFlow()

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    private val _isActionBusy = MutableStateFlow(false)
    val isActionBusy: StateFlow<Boolean> = _isActionBusy.asStateFlow()

    private val _scamState = MutableStateFlow<ScamUiState>(ScamUiState.Idle)
    val scamState: StateFlow<ScamUiState> = _scamState.asStateFlow()

    private val _engagementState = MutableStateFlow<EngagementUiState>(EngagementUiState.Idle)
    val engagementState: StateFlow<EngagementUiState> = _engagementState.asStateFlow()

    private val _currentLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val currentLocation: StateFlow<Pair<Double, Double>?> = _currentLocation.asStateFlow()

    private val _brainGamesWon = MutableStateFlow(0)
    val brainGamesWon: StateFlow<Int> = _brainGamesWon.asStateFlow()

    private val _selectedCircleIndex = MutableStateFlow(0)
    private var lastSyncedMemberHash: String = ""

    val circlesState: StateFlow<DataUiState<List<CareCircle>>> = repository.observeCareCircles(currentUserId)
        .map<List<CareCircle>, DataUiState<List<CareCircle>>> { DataUiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing CareCircles", rootError)
            emit(DataUiState.Error(rootError.message ?: "Could not load your Care Circle."))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = DataUiState.Loading
        )

    val bundleState: StateFlow<DataUiState<CircleDashboardBundle?>> = circlesState
        .flatMapLatest { state ->
            when (state) {
                is DataUiState.Loading -> flowOf(DataUiState.Loading)
                is DataUiState.Error -> flowOf(DataUiState.Error(state.message))
                is DataUiState.Success -> {
                    val circles = state.data
                    if (circles.isEmpty()) {
                        GuideSessionCoordinator.currentCircle = null
                        flowOf(DataUiState.Success(null))
                    } else {
                        val circle = circles.getOrElse(_selectedCircleIndex.value) { circles.first() }
                        GuideSessionCoordinator.currentCircle = circle
                        val memberKey = "${circle.id}:${circle.memberUids.sorted().joinToString(",")}"
                        if (circle.ownerUid == currentUserId &&
                            circle.memberUids.size > 1 &&
                            memberKey != lastSyncedMemberHash
                        ) {
                            lastSyncedMemberHash = memberKey
                            viewModelScope.launch {
                                repository.syncSubcollectionMembership(circle)
                            }
                        }

                        // Auto-default remote caregivers (non-owner) into Caregiver Dashboard on first load
                        if (circle.ownerUid != currentUserId &&
                            _roleMode.value == RoleMode.SENIOR &&
                            lastSyncedMemberHash.isEmpty()
                        ) {
                            lastSyncedMemberHash = memberKey
                            _roleMode.value = RoleMode.CAREGIVER
                        }

                        val baseFlows = combine(
                            repository.observeTasks(circle.id, currentUserId),
                            repository.observeEvents(circle.id, currentUserId),
                            repository.observeMedications(circle.id, currentUserId),
                            repository.observePhotos(circle.id, currentUserId),
                            repository.observeVoiceNotes(circle.id, currentUserId)
                        ) { tasks, events, meds, photos, notes ->
                            PartialBundleA(tasks, events, meds, photos, notes)
                        }

                        val extraFlows = combine(
                            repository.observeSafePlaces(circle.id, currentUserId),
                            repository.observeAlerts(circle.id, currentUserId),
                            repository.observeConversations(circle.id, currentUserId)
                        ) { places, alerts, convs ->
                            PartialBundleB(places, alerts, convs)
                        }

                        combine(baseFlows, extraFlows) { a, b ->
                            GuideSessionCoordinator.currentTasks = a.tasks
                            GuideSessionCoordinator.currentEvents = a.events
                            GuideSessionCoordinator.currentMedications = a.medications
                            GuideSessionCoordinator.currentHistory = b.conversations

                            CircleDashboardBundle(
                                circle = circle,
                                tasks = a.tasks,
                                events = a.events,
                                medications = a.medications,
                                photos = a.photos,
                                voiceNotes = a.notes,
                                safePlaces = b.places,
                                alerts = b.alerts,
                                conversations = b.conversations
                            )
                        }.map<CircleDashboardBundle, DataUiState<CircleDashboardBundle?>> {
                            DataUiState.Success(it)
                        }.catch { error ->
                            val firestoreError = generateSequence(error) { it.cause }
                                .filterIsInstance<FirebaseFirestoreException>()
                                .firstOrNull()
                            if (firestoreError == null && error is CancellationException) throw error
                            val rootError = firestoreError ?: error
                            Log.w(TAG, "Error observing circle subcollections", rootError)
                            emit(DataUiState.Error(rootError.message ?: "Failed to sync care data."))
                        }
                    }
                }
            }
        }
        .onEach { }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = DataUiState.Loading
        )

    fun clearActionMessage() {
        _actionMessage.value = null
    }

    fun updateCurrentLocation(lat: Double, lng: Double) {
        _currentLocation.value = lat to lng
    }

    fun createCareCircle(
        seniorName: String,
        pin: String,
        homeAddress: String,
        homeLat: Double,
        homeLng: Double,
        emergencyName: String,
        emergencyPhone: String
    ) {
        viewModelScope.launch {
            _isActionBusy.value = true
            val result = repository.createCareCircle(
                seniorName = seniorName.ifBlank { userDisplayName.ifBlank { "Grandpa" } },
                pin = pin,
                homeAddress = homeAddress,
                homeLat = homeLat,
                homeLng = homeLng,
                emergencyContactName = emergencyName,
                emergencyContactPhone = emergencyPhone
            )
            _isActionBusy.value = false
            result.onSuccess {
                _roleMode.value = RoleMode.SENIOR
                _actionMessage.value = "Care Circle ready! Family Code: ${it.inviteCode}"
            }.onFailure {
                _actionMessage.value = it.localizedMessage ?: "Could not create Care Circle."
            }
        }
    }

    fun joinCareCircleByCode(inviteCode: String, pin: String) {
        viewModelScope.launch {
            _isActionBusy.value = true
            val result = repository.joinCareCircleByCode(inviteCode, pin)
            _isActionBusy.value = false
            result.onSuccess {
                _roleMode.value = RoleMode.CAREGIVER
                _actionMessage.value = "Joined Care Circle! You are now in Family Caregiver view."
            }.onFailure {
                _actionMessage.value = it.localizedMessage ?: "Could not join Care Circle."
            }
        }
    }

    fun unlockCaregiverMode(enteredPin: String, circle: CareCircle): Boolean {
        return if (sha256Hex(enteredPin.trim()) == circle.caregiverPinHash) {
            _roleMode.value = RoleMode.CAREGIVER
            true
        } else {
            false
        }
    }

    fun switchToSeniorMode() {
        _roleMode.value = RoleMode.SENIOR
    }

    fun updateCircleSettings(
        circle: CareCircle,
        seniorName: String,
        homeAddress: String,
        homeLat: Double,
        homeLng: Double,
        emergencyName: String,
        emergencyPhone: String,
        newPin: String
    ) {
        viewModelScope.launch {
            val updates = mutableMapOf<String, Any>(
                "seniorName" to seniorName.trim().take(60),
                "homeAddress" to homeAddress.trim().take(200),
                "homeLat" to homeLat,
                "homeLng" to homeLng,
                "emergencyContactName" to emergencyName.trim().take(60),
                "emergencyContactPhone" to emergencyPhone.trim().take(30)
            )
            if (newPin.length == 4 && newPin.all { it.isDigit() }) {
                updates["caregiverPinHash"] = sha256Hex(newPin)
            }
            repository.updateCareCircleProfile(circle.id, updates)
                .onSuccess { _actionMessage.value = "Care Circle details saved." }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to update details." }
        }
    }

    fun addTask(circle: CareCircle, title: String, category: String, dueTimeLabel: String, createdBy: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            repository.createTask(circle.id, circle.memberUids, title, category, dueTimeLabel, createdBy)
                .onSuccess { _actionMessage.value = "Task added: $title" }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to add task." }
        }
    }

    fun toggleTask(circle: CareCircle, taskId: String, completed: Boolean) {
        viewModelScope.launch {
            repository.updateTask(circle.id, taskId, mapOf("completed" to completed))
        }
    }

    fun deleteTask(circle: CareCircle, taskId: String) {
        viewModelScope.launch {
            repository.deleteTask(circle.id, taskId)
        }
    }

    fun addEvent(
        circle: CareCircle,
        title: String,
        dateLabel: String,
        timeLabel: String,
        location: String,
        notes: String,
        createdBy: String
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            repository.createEvent(circle.id, circle.memberUids, title, dateLabel, timeLabel, location, notes, createdBy)
                .onSuccess { _actionMessage.value = "Calendar event saved: $title" }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to save event." }
        }
    }

    fun deleteEvent(circle: CareCircle, eventId: String) {
        viewModelScope.launch {
            repository.deleteEvent(circle.id, eventId)
        }
    }

    fun addMedication(circle: CareCircle, name: String, dosage: String, slot: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createMedication(circle.id, circle.memberUids, name, dosage, slot)
                .onSuccess { _actionMessage.value = "Medication reminder added." }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Failed to add medication." }
        }
    }

    fun toggleMedicationTaken(circle: CareCircle, medId: String, taken: Boolean) {
        viewModelScope.launch {
            val label = if (taken) "Taken today" else "Not taken yet today"
            repository.updateMedication(
                circle.id,
                medId,
                mapOf("takenToday" to taken, "lastTakenLabel" to label)
            )
        }
    }

    fun deleteMedication(circle: CareCircle, medId: String) {
        viewModelScope.launch {
            repository.deleteMedication(circle.id, medId)
        }
    }

    fun sharePhotoFromBitmap(circle: CareCircle, bitmap: Bitmap, caption: String, sharedBy: String) {
        viewModelScope.launch {
            _isActionBusy.value = true
            val encoded = encodeThumbnailBase64(bitmap)
            val result = repository.sharePhoto(
                circleId = circle.id,
                memberUids = circle.memberUids,
                caption = caption,
                sharedBy = sharedBy,
                thumbnailBase64 = encoded
            )
            _isActionBusy.value = false
            result.onSuccess { _actionMessage.value = "Photo shared with your Family Circle!" }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Could not share photo." }
        }
    }

    fun deletePhoto(circle: CareCircle, photoId: String) {
        viewModelScope.launch {
            repository.deletePhoto(circle.id, photoId)
        }
    }

    fun addVoiceNote(
        circle: CareCircle,
        senderName: String,
        durationSeconds: Long,
        transcript: String,
        isFromCaregiver: Boolean
    ) {
        if (transcript.isBlank()) return
        viewModelScope.launch {
            repository.addVoiceNote(
                circleId = circle.id,
                memberUids = circle.memberUids,
                senderName = senderName,
                durationSeconds = durationSeconds,
                transcript = transcript,
                isFromCaregiver = isFromCaregiver
            ).onSuccess {
                _actionMessage.value = "Voice message sent!"
            }.onFailure {
                _actionMessage.value = it.localizedMessage ?: "Could not send voice message."
            }
        }
    }

    fun deleteVoiceNote(circle: CareCircle, noteId: String) {
        viewModelScope.launch {
            repository.deleteVoiceNote(circle.id, noteId)
        }
    }

    fun addSafePlace(circle: CareCircle, label: String, category: String, address: String, lat: Double, lng: Double) {
        if (label.isBlank()) return
        viewModelScope.launch {
            repository.addSafePlace(circle.id, circle.memberUids, label, category, address, lat, lng)
                .onSuccess { _actionMessage.value = "Safe place added: $label" }
                .onFailure { _actionMessage.value = it.localizedMessage ?: "Could not add safe place." }
        }
    }

    fun deleteSafePlace(circle: CareCircle, placeId: String) {
        viewModelScope.launch {
            repository.deleteSafePlace(circle.id, placeId)
        }
    }

    fun sendCheckIn(circle: CareCircle, mood: String) {
        val loc = _currentLocation.value
        val lat = loc?.first ?: circle.homeLat
        val lng = loc?.second ?: circle.homeLng
        viewModelScope.launch {
            repository.updateCareCircleProfile(
                circle.id,
                mapOf(
                    "lastMood" to mood,
                    "lastCheckInLabel" to "Feeling $mood • Checked in safely"
                )
            )
            repository.createAlert(
                circleId = circle.id,
                memberUids = circle.memberUids,
                type = "check_in",
                message = "${circle.seniorName} checked in: feeling $mood.",
                latitude = lat,
                longitude = lng
            )
            _actionMessage.value = "Family notified that you are feeling $mood!"
        }
    }

    fun triggerSosAlert(circle: CareCircle) {
        val loc = _currentLocation.value
        val lat = loc?.first ?: circle.homeLat
        val lng = loc?.second ?: circle.homeLng
        viewModelScope.launch {
            repository.createAlert(
                circleId = circle.id,
                memberUids = circle.memberUids,
                type = "sos",
                message = "SOS Alert from ${circle.seniorName}! Please call ${circle.emergencyContactName} or check location.",
                latitude = lat,
                longitude = lng
            ).onSuccess {
                _actionMessage.value = "SOS alert sent to your Family Caregiver Dashboard!"
            }.onFailure {
                _actionMessage.value = it.localizedMessage ?: "Could not send SOS alert."
            }
        }
    }

    fun resolveAlert(circle: CareCircle, alertId: String) {
        viewModelScope.launch {
            repository.resolveAlert(circle.id, alertId, true)
        }
    }

    fun analyzeScam(circle: CareCircle?, messageText: String, screenshot: Bitmap?) {
        if (messageText.isBlank() && screenshot == null) return
        viewModelScope.launch {
            _scamState.value = ScamUiState.Loading
            try {
                val result = aiService.checkForScam(messageText, screenshot)
                _scamState.value = ScamUiState.Result(result)
                GuideSessionCoordinator.speakText("${result.verdict}. ${result.headline}. ${result.recommendedAction}")
                if (circle != null && result.verdict != "Safe") {
                    repository.createAlert(
                        circleId = circle.id,
                        memberUids = circle.memberUids,
                        type = "scam_warning",
                        message = "Scam Check (${result.verdict}): ${result.headline}"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Scam check error", e)
                _scamState.value = ScamUiState.Error(e.localizedMessage ?: "Could not check message.")
            }
        }
    }

    fun resetScamCheck() {
        _scamState.value = ScamUiState.Idle
    }

    fun generateDailyEngagement(themeHint: String = "family gardens, music, and friendly neighborhood memories") {
        viewModelScope.launch {
            _engagementState.value = EngagementUiState.Loading
            try {
                val content = aiService.generateDailyEngagement(themeHint)
                _engagementState.value = EngagementUiState.Ready(content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Engagement error", e)
                _engagementState.value = EngagementUiState.Error(
                    e.localizedMessage ?: "Could not load today's story."
                )
            }
        }
    }

    fun recordBrainGameWin(circle: CareCircle?) {
        _brainGamesWon.value += 1
        if (circle != null) {
            viewModelScope.launch {
                repository.createAlert(
                    circleId = circle.id,
                    memberUids = circle.memberUids,
                    type = "check_in",
                    message = "${circle.seniorName} completed a daily Memory Match brain game!"
                )
            }
        }
    }

    private fun encodeThumbnailBase64(bitmap: Bitmap): String {
        val maxDim = 540
        val ratio = minOf(1f, maxDim.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat())
        val scaled = if (ratio < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 72, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private data class PartialBundleA(
        val tasks: List<com.example.data.model.CareTask>,
        val events: List<com.example.data.model.CalendarEvent>,
        val medications: List<com.example.data.model.MedicationItem>,
        val photos: List<com.example.data.model.FamilyPhotoItem>,
        val notes: List<com.example.data.model.VoiceNoteItem>
    )

    private data class PartialBundleB(
        val places: List<com.example.data.model.SafePlace>,
        val alerts: List<com.example.data.model.SafetyAlert>,
        val conversations: List<com.example.data.model.ConversationMessage>
    )

    private companion object {
        const val TAG = "KindGuideVM"
    }
}
