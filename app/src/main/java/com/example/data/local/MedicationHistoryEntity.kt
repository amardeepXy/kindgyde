package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Entity(tableName = "medication_history")
data class MedicationHistoryEntity(
    @PrimaryKey val id: String,
    val scheduleId: String,
    val circleId: String = "",
    val medicationName: String,
    val dosage: String,
    val scheduleSlot: String = "Morning",
    val status: String = STATUS_TAKEN, // "TAKEN" or "MISSED"
    val eventTimestampMs: Long = System.currentTimeMillis(),
    val dateKey: String = formatDateKey(eventTimestampMs),
    val reminderHour: Int = 8,
    val reminderMinute: Int = 0
) {
    val isTaken: Boolean
        get() = status == STATUS_TAKEN

    val isMissed: Boolean
        get() = status == STATUS_MISSED

    fun formattedEventTime(): String {
        val formatter = SimpleDateFormat("h:mm a", Locale.US)
        return formatter.format(Date(eventTimestampMs))
    }

    fun formattedScheduledTime(): String =
        MedicationScheduleEntity.formatTime(reminderHour, reminderMinute)

    fun formattedDayHeader(nowMillis: Long = System.currentTimeMillis()): String {
        val todayKey = formatDateKey(nowMillis)
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val yesterdayKey = formatDateKey(cal.timeInMillis)
        val niceDate = SimpleDateFormat("EEE, MMM d", Locale.US).format(Date(eventTimestampMs))
        return when (dateKey) {
            todayKey -> "Today • $niceDate"
            yesterdayKey -> "Yesterday • $niceDate"
            else -> niceDate
        }
    }

    companion object {
        const val STATUS_TAKEN = "TAKEN"
        const val STATUS_MISSED = "MISSED"

        fun entryIdFor(scheduleId: String, dateKey: String): String = "${scheduleId}_${dateKey}"

        fun formatDateKey(timestampMs: Long): String {
            val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            return formatter.format(Date(timestampMs))
        }
    }
}
