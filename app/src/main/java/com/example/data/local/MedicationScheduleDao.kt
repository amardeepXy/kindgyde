package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicationScheduleDao {

    @Query("SELECT * FROM medication_schedules ORDER BY reminderHour ASC, reminderMinute ASC, name ASC")
    fun observeAllSchedules(): Flow<List<MedicationScheduleEntity>>

    @Query("SELECT * FROM medication_schedules ORDER BY reminderHour ASC, reminderMinute ASC, name ASC")
    suspend fun getAllSchedulesOnce(): List<MedicationScheduleEntity>

    @Query("SELECT * FROM medication_schedules WHERE reminderEnabled = 1 ORDER BY reminderHour ASC, reminderMinute ASC")
    suspend fun getEnabledSchedules(): List<MedicationScheduleEntity>

    @Query("SELECT * FROM medication_schedules WHERE id = :id LIMIT 1")
    suspend fun getScheduleById(id: String): MedicationScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSchedule(schedule: MedicationScheduleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSchedules(schedules: List<MedicationScheduleEntity>)

    @Query(
        "UPDATE medication_schedules SET takenToday = :taken, lastTakenEpochMs = :takenAtMs, updatedAtEpochMs = :updatedAtMs WHERE id = :id"
    )
    suspend fun updateTakenStatus(
        id: String,
        taken: Boolean,
        takenAtMs: Long,
        updatedAtMs: Long = System.currentTimeMillis()
    )

    @Query(
        "UPDATE medication_schedules SET reminderEnabled = :enabled, reminderHour = :hour, reminderMinute = :minute, updatedAtEpochMs = :updatedAtMs WHERE id = :id"
    )
    suspend fun updateReminderSettings(
        id: String,
        enabled: Boolean,
        hour: Int,
        minute: Int,
        updatedAtMs: Long = System.currentTimeMillis()
    )

    @Query(
        "UPDATE medication_schedules SET dosage = :dosage, reminderEnabled = :enabled, reminderHour = :hour, reminderMinute = :minute, updatedAtEpochMs = :updatedAtMs WHERE id = :id"
    )
    suspend fun updateScheduleReminderAndDosage(
        id: String,
        dosage: String,
        enabled: Boolean,
        hour: Int,
        minute: Int,
        updatedAtMs: Long = System.currentTimeMillis()
    )

    @Query("DELETE FROM medication_schedules WHERE id = :id")
    suspend fun deleteScheduleById(id: String)

    @Query("DELETE FROM medication_schedules WHERE circleId = :circleId")
    suspend fun deleteAllForCircle(circleId: String)

    @Query("DELETE FROM medication_schedules WHERE circleId = :circleId AND id NOT IN (:activeIds)")
    suspend fun pruneRemovedCircleSchedules(circleId: String, activeIds: List<String>)
}
