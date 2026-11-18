package com.example.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Calendar
import java.util.Locale

@Entity(tableName = "medication_schedules")
data class MedicationScheduleEntity(
    @PrimaryKey val id: String,
    val circleId: String = "",
    val name: String,
    @ColumnInfo(name = "dosage") val dosage: String = "",
    val scheduleSlot: String = "Morning",
    val reminderHour: Int = 8,
    val reminderMinute: Int = 0,
    val reminderEnabled: Boolean = true,
    val takenToday: Boolean = false,
    val lastTakenEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    fun formattedDosage(): String = dosage.trim().ifBlank { "As prescribed" }

    fun formattedReminderTime(): String {
        val safeHour = reminderHour.coerceIn(0, 23)
        val safeMinute = reminderMinute.coerceIn(0, 59)
        val amPm = if (safeHour < 12) "AM" else "PM"
        val hour12 = when {
            safeHour == 0 -> 12
            safeHour > 12 -> safeHour - 12
            else -> safeHour
        }
        return String.format(Locale.US, "%d:%02d %s", hour12, safeMinute, amPm)
    }

    fun isTakenOnSameLocalDay(nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!takenToday || lastTakenEpochMs <= 0L) return false
        val takenCal = Calendar.getInstance().apply { timeInMillis = lastTakenEpochMs }
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        return takenCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
            takenCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
    }

    companion object {
        fun normalizeSlot(slot: String): String = when (slot.trim().lowercase(Locale.US)) {
            "afternoon" -> "Afternoon"
            "evening" -> "Evening"
            "bedtime" -> "Bedtime"
            else -> "Morning"
        }

        fun defaultTimeForSlot(slot: String): Pair<Int, Int> = when (normalizeSlot(slot)) {
            "Afternoon" -> 13 to 0
            "Evening" -> 18 to 0
            "Bedtime" -> 21 to 0
            else -> 8 to 0
        }

        fun formatTime(hour: Int, minute: Int): String {
            val safeHour = hour.coerceIn(0, 23)
            val safeMinute = minute.coerceIn(0, 59)
            val amPm = if (safeHour < 12) "AM" else "PM"
            val hour12 = when {
                safeHour == 0 -> 12
                safeHour > 12 -> safeHour - 12
                else -> safeHour
            }
            return String.format(Locale.US, "%d:%02d %s", hour12, safeMinute, amPm)
        }
    }
}
