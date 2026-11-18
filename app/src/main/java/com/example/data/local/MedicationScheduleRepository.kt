package com.example.data.local

import com.example.data.model.MedicationItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.Calendar
import java.util.UUID

class MedicationScheduleRepository(
    private val dao: MedicationScheduleDao,
    private val historyDao: MedicationHistoryDao? = null
) {
    val allSchedules: Flow<List<MedicationScheduleEntity>> = dao.observeAllSchedules()

    val allHistory: Flow<List<MedicationHistoryEntity>>
        get() = historyDao?.observeAllHistory() ?: flowOf(emptyList())

    fun observeHistoryInRange(startMillis: Long, endMillis: Long): Flow<List<MedicationHistoryEntity>> {
        return historyDao?.observeHistoryInRange(startMillis, endMillis) ?: flowOf(emptyList())
    }

    suspend fun getAllOnce(): List<MedicationScheduleEntity> = dao.getAllSchedulesOnce()

    suspend fun getEnabledSchedules(): List<MedicationScheduleEntity> = dao.getEnabledSchedules()

    suspend fun getScheduleById(id: String): MedicationScheduleEntity? = dao.getScheduleById(id)

    suspend fun saveSchedule(schedule: MedicationScheduleEntity) {
        dao.upsertSchedule(schedule)
    }

    suspend fun createOrUpdateSchedule(
        id: String = UUID.randomUUID().toString(),
        circleId: String = "",
        name: String,
        dosage: String,
        scheduleSlot: String,
        reminderHour: Int? = null,
        reminderMinute: Int? = null,
        reminderEnabled: Boolean = true,
        takenToday: Boolean = false
    ): MedicationScheduleEntity {
        val existing = dao.getScheduleById(id)
        val normalizedSlot = MedicationScheduleEntity.normalizeSlot(scheduleSlot)
        val (defaultHour, defaultMinute) = MedicationScheduleEntity.defaultTimeForSlot(normalizedSlot)
        val now = System.currentTimeMillis()
        val entity = MedicationScheduleEntity(
            id = id,
            circleId = circleId.ifBlank { existing?.circleId.orEmpty() },
            name = name.trim().take(100),
            dosage = dosage.trim().take(60),
            scheduleSlot = normalizedSlot,
            reminderHour = (reminderHour ?: existing?.reminderHour ?: defaultHour).coerceIn(0, 23),
            reminderMinute = (reminderMinute ?: existing?.reminderMinute ?: defaultMinute).coerceIn(0, 59),
            reminderEnabled = reminderEnabled,
            takenToday = takenToday,
            lastTakenEpochMs = when {
                takenToday && (existing?.lastTakenEpochMs ?: 0L) > 0L -> existing?.lastTakenEpochMs ?: now
                takenToday -> now
                else -> 0L
            },
            updatedAtEpochMs = now
        )
        dao.upsertSchedule(entity)
        if (takenToday) {
            recordHistoryForSchedule(
                schedule = entity,
                status = MedicationHistoryEntity.STATUS_TAKEN,
                eventTimestampMs = now
            )
        }
        return entity
    }

    /**
     * Synchronizes remote Care Circle medications into the local Room `medication_schedules` table
     * while preserving each medication's locally customized reminder hour, minute, and enabled toggle.
     */
    suspend fun syncFromCareCircleMedications(
        circleId: String,
        medications: List<MedicationItem>
    ): List<MedicationScheduleEntity> {
        val existingById = dao.getAllSchedulesOnce().associateBy { it.id }
        val now = System.currentTimeMillis()

        val synced = medications.map { med ->
            val existing = existingById[med.id]
            val normalizedSlot = MedicationScheduleEntity.normalizeSlot(med.scheduleSlot)
            val (defaultHour, defaultMinute) = MedicationScheduleEntity.defaultTimeForSlot(normalizedSlot)
            val slotChanged = existing != null && existing.scheduleSlot != normalizedSlot
            val hour = if (existing == null || slotChanged) defaultHour else existing.reminderHour
            val minute = if (existing == null || slotChanged) defaultMinute else existing.reminderMinute
            val takenFromLocalNotificationToday = existing?.isTakenOnSameLocalDay(now) == true
            val effectiveTakenToday = med.takenToday || takenFromLocalNotificationToday
            val lastTakenMs = when {
                effectiveTakenToday && (existing?.lastTakenEpochMs ?: 0L) > 0L -> existing!!.lastTakenEpochMs
                effectiveTakenToday -> now
                else -> 0L
            }
            MedicationScheduleEntity(
                id = med.id,
                circleId = circleId,
                name = med.name.trim().take(100),
                dosage = med.dosage.trim().ifBlank { existing?.dosage.orEmpty() }.take(60),
                scheduleSlot = normalizedSlot,
                reminderHour = hour.coerceIn(0, 23),
                reminderMinute = minute.coerceIn(0, 59),
                reminderEnabled = existing?.reminderEnabled ?: true,
                takenToday = effectiveTakenToday,
                lastTakenEpochMs = lastTakenMs,
                updatedAtEpochMs = now
            )
        }

        if (synced.isNotEmpty()) {
            dao.upsertSchedules(synced)
            dao.pruneRemovedCircleSchedules(circleId, synced.map { it.id })
            synced.filter { it.takenToday }.forEach { med ->
                val dateKey = MedicationHistoryEntity.formatDateKey(med.lastTakenEpochMs.takeIf { it > 0L } ?: now)
                if (historyDao?.getEntryForScheduleAndDate(med.id, dateKey) == null) {
                    recordHistoryForSchedule(
                        schedule = med,
                        status = MedicationHistoryEntity.STATUS_TAKEN,
                        eventTimestampMs = med.lastTakenEpochMs.takeIf { it > 0L } ?: now
                    )
                }
            }
        } else {
            dao.deleteAllForCircle(circleId)
        }
        reconcileMissedDoses(nowMillis = now)
        return dao.getAllSchedulesOnce()
    }

    suspend fun setTakenToday(
        id: String,
        taken: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        dao.updateTakenStatus(
            id = id,
            taken = taken,
            takenAtMs = if (taken) nowMillis else 0L,
            updatedAtMs = nowMillis
        )
        val schedule = dao.getScheduleById(id) ?: return
        val dateKey = MedicationHistoryEntity.formatDateKey(nowMillis)
        if (taken) {
            recordHistoryForSchedule(
                schedule = schedule,
                status = MedicationHistoryEntity.STATUS_TAKEN,
                eventTimestampMs = nowMillis
            )
        } else {
            val scheduledTodayMs = scheduledEpochForDay(schedule, nowMillis)
            if (nowMillis - scheduledTodayMs >= DEFAULT_MISSED_GRACE_MS) {
                recordHistoryForSchedule(
                    schedule = schedule,
                    status = MedicationHistoryEntity.STATUS_MISSED,
                    eventTimestampMs = scheduledTodayMs
                )
            } else {
                historyDao?.deleteEntryForScheduleAndDate(id, dateKey)
            }
        }
    }

    suspend fun markHistoryEntryTaken(
        entryId: String,
        nowMillis: Long = System.currentTimeMillis()
    ): MedicationHistoryEntity? {
        val hDao = historyDao ?: return null
        val existing = hDao.getEntryById(entryId) ?: return null
        val updated = existing.copy(
            status = MedicationHistoryEntity.STATUS_TAKEN,
            eventTimestampMs = nowMillis
        )
        hDao.upsertHistoryEntry(updated)
        if (existing.dateKey == MedicationHistoryEntity.formatDateKey(nowMillis)) {
            dao.updateTakenStatus(
                id = existing.scheduleId,
                taken = true,
                takenAtMs = nowMillis,
                updatedAtMs = nowMillis
            )
        }
        return updated
    }

    suspend fun logHistoryEntry(entry: MedicationHistoryEntity) {
        historyDao?.upsertHistoryEntry(entry)
    }

    /**
     * Checks enabled schedules and logs `"MISSED"` rows in `medication_history` for any past
     * scheduled dose today (after [gracePeriodMs]) or recent days where no `"TAKEN"` record exists.
     */
    suspend fun reconcileMissedDoses(
        nowMillis: Long = System.currentTimeMillis(),
        gracePeriodMs: Long = DEFAULT_MISSED_GRACE_MS,
        lookbackDays: Int = 7
    ) {
        val hDao = historyDao ?: return
        val schedules = dao.getAllSchedulesOnce()
        if (schedules.isEmpty()) return

        for (schedule in schedules) {
            if (!schedule.reminderEnabled) continue
            for (dayOffset in 0..lookbackDays) {
                val dayCal = Calendar.getInstance().apply {
                    timeInMillis = nowMillis
                    add(Calendar.DAY_OF_YEAR, -dayOffset)
                }
                val scheduledMs = scheduledEpochForDay(schedule, dayCal.timeInMillis)
                if (scheduledMs < schedule.updatedAtEpochMs - DAY_IN_MS) {
                    // Schedule did not exist yet on this earlier day
                    continue
                }
                if (dayOffset == 0 && (nowMillis - scheduledMs) < gracePeriodMs) {
                    // Today's reminder time + grace period has not elapsed yet
                    continue
                }
                if (dayOffset == 0 && schedule.isTakenOnSameLocalDay(nowMillis)) {
                    continue
                }
                val dateKey = MedicationHistoryEntity.formatDateKey(scheduledMs)
                val existingEntry = hDao.getEntryForScheduleAndDate(schedule.id, dateKey)
                if (existingEntry == null) {
                    hDao.upsertHistoryEntry(
                        MedicationHistoryEntity(
                            id = MedicationHistoryEntity.entryIdFor(schedule.id, dateKey),
                            scheduleId = schedule.id,
                            circleId = schedule.circleId,
                            medicationName = schedule.name,
                            dosage = schedule.dosage,
                            scheduleSlot = schedule.scheduleSlot,
                            status = MedicationHistoryEntity.STATUS_MISSED,
                            eventTimestampMs = scheduledMs,
                            dateKey = dateKey,
                            reminderHour = schedule.reminderHour,
                            reminderMinute = schedule.reminderMinute
                        )
                    )
                }
            }
        }
    }

    suspend fun updateReminderSettings(
        id: String,
        enabled: Boolean,
        hour: Int,
        minute: Int
    ): MedicationScheduleEntity? {
        val now = System.currentTimeMillis()
        dao.updateReminderSettings(
            id = id,
            enabled = enabled,
            hour = hour.coerceIn(0, 23),
            minute = minute.coerceIn(0, 59),
            updatedAtMs = now
        )
        return dao.getScheduleById(id)
    }

    suspend fun updateScheduleDetails(
        id: String,
        dosage: String,
        enabled: Boolean,
        hour: Int,
        minute: Int
    ): MedicationScheduleEntity? {
        val now = System.currentTimeMillis()
        dao.updateScheduleReminderAndDosage(
            id = id,
            dosage = dosage.trim().take(60),
            enabled = enabled,
            hour = hour.coerceIn(0, 23),
            minute = minute.coerceIn(0, 59),
            updatedAtMs = now
        )
        return dao.getScheduleById(id)
    }

    suspend fun deleteSchedule(id: String) {
        dao.deleteScheduleById(id)
    }

    private suspend fun recordHistoryForSchedule(
        schedule: MedicationScheduleEntity,
        status: String,
        eventTimestampMs: Long
    ) {
        val hDao = historyDao ?: return
        val dateKey = MedicationHistoryEntity.formatDateKey(eventTimestampMs)
        hDao.upsertHistoryEntry(
            MedicationHistoryEntity(
                id = MedicationHistoryEntity.entryIdFor(schedule.id, dateKey),
                scheduleId = schedule.id,
                circleId = schedule.circleId,
                medicationName = schedule.name,
                dosage = schedule.dosage,
                scheduleSlot = schedule.scheduleSlot,
                status = status,
                eventTimestampMs = eventTimestampMs,
                dateKey = dateKey,
                reminderHour = schedule.reminderHour,
                reminderMinute = schedule.reminderMinute
            )
        )
    }

    private fun scheduledEpochForDay(schedule: MedicationScheduleEntity, dayMillis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = dayMillis
            set(Calendar.HOUR_OF_DAY, schedule.reminderHour.coerceIn(0, 23))
            set(Calendar.MINUTE, schedule.reminderMinute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    companion object {
        const val DEFAULT_MISSED_GRACE_MS = 30 * 60_000L
        private const val DAY_IN_MS = 24 * 60 * 60_000L
    }
}
