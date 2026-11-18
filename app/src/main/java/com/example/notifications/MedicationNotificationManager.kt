package com.example.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.MedicationScheduleEntity
import java.util.Calendar

object MedicationNotificationManager {

    const val CHANNEL_ID = "kindguide_medication_reminders"
    private const val CHANNEL_NAME = "Daily Medication Reminders"

    const val ACTION_TRIGGER_MEDICATION_REMINDER = "com.example.action.TRIGGER_MEDICATION_REMINDER"
    const val ACTION_MARK_TAKEN = "com.example.action.MARK_MEDICATION_TAKEN"
    const val EXTRA_MEDICATION_ID = "extra_medication_id"
    const val EXTRA_OPEN_SAFETY_TAB = "extra_open_safety_tab"

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Daily scheduled reminders to take your medicines on time."
                    enableVibration(true)
                    setSound(soundUri, attrs)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Calculates the next epoch timestamp in milliseconds for the daily [hour]:[minute] reminder.
     * If today's occurrence is strictly in the future relative to [nowMillis], schedules for today;
     * otherwise schedules for the same clock time tomorrow.
     */
    fun computeNextTriggerTimeMillis(
        hour: Int,
        minute: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): Long {
        val safeHour = hour.coerceIn(0, 23)
        val safeMinute = minute.coerceIn(0, 59)
        val target = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, safeHour)
            set(Calendar.MINUTE, safeMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= nowMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    fun scheduleDailyReminder(
        context: Context,
        schedule: MedicationScheduleEntity,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        ensureNotificationChannel(context)
        if (!schedule.reminderEnabled) {
            cancelReminder(context, schedule.id)
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAtMs = computeNextTriggerTimeMillis(
            hour = schedule.reminderHour,
            minute = schedule.reminderMinute,
            nowMillis = nowMillis
        )
        val pendingIntent = buildTriggerPendingIntent(context, schedule.id)
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMs,
            pendingIntent
        )
    }

    fun cancelReminder(context: Context, scheduleId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager != null) {
            val pendingIntent = buildTriggerPendingIntent(context, scheduleId)
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationIdFor(scheduleId))
        }
    }

    fun syncDailyReminders(
        context: Context,
        schedules: List<MedicationScheduleEntity>,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        ensureNotificationChannel(context)
        schedules.forEach { schedule ->
            if (schedule.reminderEnabled) {
                scheduleDailyReminder(context, schedule, nowMillis)
            } else {
                cancelReminder(context, schedule.id)
            }
        }
    }

    fun showMedicationReminderNotification(
        context: Context,
        schedule: MedicationScheduleEntity
    ): Boolean {
        ensureNotificationChannel(context)
        if (!hasNotificationPermission(context)) {
            return false
        }

        val notifId = notificationIdFor(schedule.id)
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_SAFETY_TAB, true)
            putExtra(EXTRA_MEDICATION_ID, schedule.id)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val markTakenIntent = Intent(context, MedicationReminderReceiver::class.java).apply {
            action = ACTION_MARK_TAKEN
            putExtra(EXTRA_MEDICATION_ID, schedule.id)
        }
        val markTakenPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId xor 0x4D41524B,
            markTakenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dosageText = schedule.dosage.ifBlank { "Scheduled dose" }
        val bodyText = "$dosageText • ${schedule.scheduleSlot} reminder (${schedule.formattedReminderTime()})"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Time for ${schedule.name}")
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                R.mipmap.ic_launcher,
                "Mark Taken",
                markTakenPendingIntent
            )
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            true
        }.getOrDefault(false)
    }

    fun notificationIdFor(scheduleId: String): Int {
        return 20_000 + (scheduleId.hashCode() and 0x0FFF_FFFF)
    }

    private fun buildTriggerPendingIntent(context: Context, scheduleId: String): PendingIntent {
        val intent = Intent(context, MedicationReminderReceiver::class.java).apply {
            action = ACTION_TRIGGER_MEDICATION_REMINDER
            putExtra(EXTRA_MEDICATION_ID, scheduleId)
        }
        return PendingIntent.getBroadcast(
            context,
            notificationIdFor(scheduleId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
