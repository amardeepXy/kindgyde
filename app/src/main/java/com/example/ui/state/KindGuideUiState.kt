package com.example.ui.state

import com.example.ai.DailyEngagementContent
import com.example.ai.ScamCheckResult
import com.example.data.model.CalendarEvent
import com.example.data.model.CareCircle
import com.example.data.model.CareTask
import com.example.data.model.ConversationMessage
import com.example.data.model.FamilyPhotoItem
import com.example.data.model.MedicationItem
import com.example.data.model.SafePlace
import com.example.data.model.SafetyAlert
import com.example.data.model.VoiceNoteItem

enum class RoleMode {
    SENIOR,
    CAREGIVER
}

sealed interface DataUiState<out T> {
    data object Loading : DataUiState<Nothing>
    data class Success<T>(val data: T) : DataUiState<T>
    data class Error(val message: String) : DataUiState<Nothing>
}

sealed interface ScamUiState {
    data object Idle : ScamUiState
    data object Loading : ScamUiState
    data class Streaming(val partialText: String) : ScamUiState
    data class Result(val data: ScamCheckResult) : ScamUiState
    data class Error(val message: String) : ScamUiState
}

sealed interface EngagementUiState {
    data object Idle : EngagementUiState
    data object Loading : EngagementUiState
    data class Streaming(val partialTitle: String, val partialStory: String) : EngagementUiState
    data class Ready(val content: DailyEngagementContent) : EngagementUiState
    data class Error(val message: String) : EngagementUiState
}

data class CircleDashboardBundle(
    val circle: CareCircle,
    val tasks: List<CareTask> = emptyList(),
    val events: List<CalendarEvent> = emptyList(),
    val medications: List<MedicationItem> = emptyList(),
    val photos: List<FamilyPhotoItem> = emptyList(),
    val voiceNotes: List<VoiceNoteItem> = emptyList(),
    val safePlaces: List<SafePlace> = emptyList(),
    val alerts: List<SafetyAlert> = emptyList(),
    val conversations: List<ConversationMessage> = emptyList()
)
