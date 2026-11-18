package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicationHistoryDao {

    @Query(
        "SELECT * FROM medication_history " +
            "WHERE eventTimestampMs BETWEEN :startMillis AND :endMillis " +
            "ORDER BY eventTimestampMs DESC"
    )
    fun observeHistoryInRange(startMillis: Long, endMillis: Long): Flow<List<MedicationHistoryEntity>>

    @Query("SELECT * FROM medication_history ORDER BY eventTimestampMs DESC")
    fun observeAllHistory(): Flow<List<MedicationHistoryEntity>>

    @Query("SELECT * FROM medication_history WHERE id = :id LIMIT 1")
    suspend fun getEntryById(id: String): MedicationHistoryEntity?

    @Query("SELECT * FROM medication_history WHERE scheduleId = :scheduleId AND dateKey = :dateKey LIMIT 1")
    suspend fun getEntryForScheduleAndDate(scheduleId: String, dateKey: String): MedicationHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistoryEntry(entry: MedicationHistoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistoryEntries(entries: List<MedicationHistoryEntity>)

    @Query("DELETE FROM medication_history WHERE id = :id")
    suspend fun deleteEntryById(id: String)

    @Query("DELETE FROM medication_history WHERE scheduleId = :scheduleId AND dateKey = :dateKey")
    suspend fun deleteEntryForScheduleAndDate(scheduleId: String, dateKey: String)
}
