package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.example.data.local.KindGuideDatabase
import com.example.data.local.MedicationScheduleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MedicationReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val appContext = context.applicationContext
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val db = KindGuideDatabase.getInstance(appContext)
                val repo = MedicationScheduleRepository(
                    dao = db.medicationScheduleDao(),
                    historyDao = db.medicationHistoryDao()
                )
                repo.reconcileMissedDoses()
                when (action) {
                    MedicationNotificationManager.ACTION_TRIGGER_MEDICATION_REMINDER -> {
                        val medId = intent.getStringExtra(MedicationNotificationManager.EXTRA_MEDICATION_ID)
                        if (!medId.isNullOrBlank()) {
                            val schedule = repo.getScheduleById(medId)
                            if (schedule != null && schedule.reminderEnabled) {
                                val now = System.currentTimeMillis()
                                if (!schedule.isTakenOnSameLocalDay(now)) {
                                    MedicationNotificationManager.showMedicationReminderNotification(
                                        appContext,
                                        schedule
                                    )
                                }
                                // Schedule next day's reminder even if today's dose was already marked taken
                                val nextBase = now + 60_000L
                                MedicationNotificationManager.scheduleDailyReminder(
                                    context = appContext,
                                    schedule = schedule,
                                    nowMillis = nextBase
                                )
                            }
                        }
                    }

                    MedicationNotificationManager.ACTION_MARK_TAKEN -> {
                        val medId = intent.getStringExtra(MedicationNotificationManager.EXTRA_MEDICATION_ID)
                        if (!medId.isNullOrBlank()) {
                            repo.setTakenToday(medId, taken = true)
                            runCatching {
                                NotificationManagerCompat.from(appContext).cancel(
                                    MedicationNotificationManager.notificationIdFor(medId)
                                )
                            }
                            repo.getScheduleById(medId)?.let { updated ->
                                if (updated.reminderEnabled) {
                                    MedicationNotificationManager.scheduleDailyReminder(
                                        context = appContext,
                                        schedule = updated,
                                        nowMillis = System.currentTimeMillis() + 60_000L
                                    )
                                }
                            }
                        }
                    }

                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED -> {
                        val enabledSchedules = repo.getEnabledSchedules()
                        MedicationNotificationManager.syncDailyReminders(appContext, enabledSchedules)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
