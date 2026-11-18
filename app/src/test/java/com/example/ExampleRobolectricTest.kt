package com.example

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.KindGuideDatabase
import com.example.data.local.MedicationHistoryEntity
import com.example.data.local.MedicationScheduleEntity
import com.example.data.local.MedicationScheduleRepository
import com.example.data.model.CareCircle
import com.example.data.model.MedicationItem
import com.example.notifications.MedicationNotificationManager
import com.example.service.ScreenGuideAccessibilityService
import com.example.ui.components.buildWeeklyAdherencePoints
import com.example.ui.screens.MedicationDateRangePreset
import com.example.ui.screens.MedicationDateRangeSelection
import com.example.ui.screens.MedicationHistoryScreen
import com.example.ui.screens.SafetyAndMapScreen
import com.example.ui.state.ScamUiState
import com.example.ui.theme.KindGuideTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("KindGuide", appName)
  }

  @Test
  fun `gemini live manager updates selected voice`() {
    com.example.ai.GeminiLiveManager.updateSelectedVoice("KORE")
    assertEquals("KORE", com.example.ai.GeminiLiveManager.state.value.selectedVoice)
    com.example.ai.GeminiLiveManager.updateSelectedVoice("AOEDE")
  }

  @Test
  fun `accessibility service inspects clickable nodes and skips passwords`() {
    val clickableNode = AccessibilityNodeInfo().apply {
      text = "Send Photo"
      isClickable = true
      isVisibleToUser = true
      setBoundsInScreen(Rect(20, 40, 220, 120))
    }
    val passwordNode = AccessibilityNodeInfo().apply {
      text = "Secret1234"
      isPassword = true
      isClickable = true
      isVisibleToUser = true
      setBoundsInScreen(Rect(20, 140, 220, 200))
    }

    val labels = mutableListOf<String>()
    val bounds = linkedMapOf<String, Rect>()

    ScreenGuideAccessibilityService.collectInteractiveNodes(
      node = clickableNode,
      labels = labels,
      boundsByLabel = bounds
    )
    ScreenGuideAccessibilityService.collectInteractiveNodes(
      node = passwordNode,
      labels = labels,
      boundsByLabel = bounds
    )

    assertTrue(labels.contains("Send Photo"))
    assertEquals(Rect(20, 40, 220, 120), bounds["Send Photo"])
    assertFalse(labels.contains("Secret1234"))
    assertFalse(bounds.containsKey("Secret1234"))
  }

  @Test
  fun `accessibility service enabled check defaults to false when not in secure settings`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertFalse(ScreenGuideAccessibilityService.isEnabled(context))
  }

  @Test
  fun `accessibility service collects full-width clickable chat row child contact label`() {
    val rowContainer = AccessibilityNodeInfo().apply {
      isClickable = true
      isVisibleToUser = true
      setBoundsInScreen(Rect(0, 300, 1440, 480))
    }
    val childContactText = AccessibilityNodeInfo().apply {
      text = "Amardeep Sharma"
      isClickable = false
      isVisibleToUser = true
      setBoundsInScreen(Rect(180, 320, 680, 390))
    }
    val rowShadow = org.robolectric.Shadows.shadowOf(rowContainer)
    rowShadow.addChild(childContactText)

    val labels = mutableListOf<String>()
    val bounds = linkedMapOf<String, Rect>()

    ScreenGuideAccessibilityService.collectInteractiveNodes(
      node = rowContainer,
      labels = labels,
      boundsByLabel = bounds,
      maxScreenWidth = 1440,
      maxScreenHeight = 3200
    )

    assertTrue("Full-width clickable WhatsApp chat row child label must be collected", labels.contains("Amardeep Sharma"))
    assertEquals(Rect(0, 300, 1440, 480), bounds["Amardeep Sharma"])
  }

  @Test
  fun `room medication_schedules stores custom reminder times and preserves them during sync`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, KindGuideDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = MedicationScheduleRepository(db.medicationScheduleDao())
      val created = repo.createOrUpdateSchedule(
        id = "med_1",
        circleId = "circle_1",
        name = "Metformin",
        dosage = "500mg tablet",
        scheduleSlot = "Morning",
        reminderHour = 8,
        reminderMinute = 30,
        reminderEnabled = true
      )
      assertEquals("8:30 AM", created.formattedReminderTime())

      // Sync from Firestore Care Circle with takenToday = false should preserve custom 8:30 AM time
      val synced = repo.syncFromCareCircleMedications(
        circleId = "circle_1",
        medications = listOf(
          MedicationItem(
            id = "med_1",
            name = "Metformin",
            dosage = "500mg tablet",
            scheduleSlot = "Morning",
            takenToday = false
          )
        )
      )
      assertEquals(1, synced.size)
      assertEquals(8, synced[0].reminderHour)
      assertEquals(30, synced[0].reminderMinute)
      assertTrue(synced[0].reminderEnabled)

      repo.setTakenToday("med_1", true)
      val observed = repo.allSchedules.first()
      assertTrue(observed.first().takenToday)
      assertTrue(observed.first().isTakenOnSameLocalDay())

      repo.updateReminderSettings("med_1", enabled = false, hour = 9, minute = 15)
      val updated = repo.getScheduleById("med_1")
      assertNotNull(updated)
      assertFalse(updated!!.reminderEnabled)
      assertEquals(9, updated.reminderHour)
      assertEquals(15, updated.reminderMinute)

      repo.deleteSchedule("med_1")
      assertNull(repo.getScheduleById("med_1"))
    } finally {
      db.close()
    }
  }

  @Test
  fun `medication notification manager schedules daily alarms and triggers notifications`() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    Shadows.shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    val morningNow = Calendar.getInstance().apply {
      set(2026, Calendar.NOVEMBER, 16, 7, 15, 0)
      set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // Same-day future time -> today at 8:30 AM
    val sameDayTrigger = MedicationNotificationManager.computeNextTriggerTimeMillis(
      hour = 8,
      minute = 30,
      nowMillis = morningNow
    )
    val sameDayCal = Calendar.getInstance().apply { timeInMillis = sameDayTrigger }
    assertEquals(16, sameDayCal.get(Calendar.DAY_OF_MONTH))
    assertEquals(8, sameDayCal.get(Calendar.HOUR_OF_DAY))
    assertEquals(30, sameDayCal.get(Calendar.MINUTE))

    // Already passed today -> tomorrow at 6:45 AM
    val nextDayTrigger = MedicationNotificationManager.computeNextTriggerTimeMillis(
      hour = 6,
      minute = 45,
      nowMillis = morningNow
    )
    val nextDayCal = Calendar.getInstance().apply { timeInMillis = nextDayTrigger }
    assertEquals(17, nextDayCal.get(Calendar.DAY_OF_MONTH))
    assertEquals(6, nextDayCal.get(Calendar.HOUR_OF_DAY))
    assertEquals(45, nextDayCal.get(Calendar.MINUTE))

    val schedule = MedicationScheduleEntity(
      id = "med_aspirin",
      circleId = "circle_1",
      name = "Aspirin",
      dosage = "1 pill",
      scheduleSlot = "Morning",
      reminderHour = 8,
      reminderMinute = 30,
      reminderEnabled = true
    )

    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val shadowAlarmManager = Shadows.shadowOf(alarmManager)

    MedicationNotificationManager.scheduleDailyReminder(context, schedule, nowMillis = morningNow)
    assertFalse(shadowAlarmManager.scheduledAlarms.isEmpty())

    val posted = MedicationNotificationManager.showMedicationReminderNotification(context, schedule)
    assertTrue(posted)

    val notificationManager = context.getSystemService(NotificationManager::class.java)
    val shadowNotificationManager = Shadows.shadowOf(notificationManager)
    val active = shadowNotificationManager.allNotifications
    assertFalse(active.isEmpty())
    val medNotif = active.firstOrNull {
      it.extras.getString(android.app.Notification.EXTRA_TITLE) == "Time for Aspirin"
    }
    assertNotNull(medNotif)
    assertEquals(1, medNotif!!.actions.size)
    assertEquals("Mark Taken", medNotif.actions[0].title.toString())

    MedicationNotificationManager.cancelReminder(context, schedule.id)
    assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
  }

  @Test
  fun `room medication_history logs taken and missed doses and filters by date range`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = Room.inMemoryDatabaseBuilder(context, KindGuideDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = MedicationScheduleRepository(
        dao = db.medicationScheduleDao(),
        historyDao = db.medicationHistoryDao()
      )

      val nowCal = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 16, 10, 0, 0)
        set(Calendar.MILLISECOND, 0)
      }
      val nowMs = nowCal.timeInMillis
      val fourDaysAgoMs = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -4) }.timeInMillis
      val fifteenDaysAgoMs = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -15) }.timeInMillis

      // Create an 8:00 AM schedule updated 5 days ago; at 10:00 AM today its 8:00 AM dose is past 30m grace
      repo.saveSchedule(
        MedicationScheduleEntity(
          id = "med_lisinopril",
          circleId = "c1",
          name = "Lisinopril",
          dosage = "10mg",
          scheduleSlot = "Morning",
          reminderHour = 8,
          reminderMinute = 0,
          reminderEnabled = true,
          takenToday = false,
          updatedAtEpochMs = fourDaysAgoMs
        )
      )

      repo.reconcileMissedDoses(nowMillis = nowMs, lookbackDays = 5)
      val todayKey = MedicationHistoryEntity.formatDateKey(nowMs)
      val todayEntry = db.medicationHistoryDao().getEntryForScheduleAndDate("med_lisinopril", todayKey)
      assertNotNull(todayEntry)
      assertEquals(MedicationHistoryEntity.STATUS_MISSED, todayEntry!!.status)

      // Add a 15-day-old taken dose to verify date-range SQL filtering
      repo.logHistoryEntry(
        MedicationHistoryEntity(
          id = "med_vitd_${MedicationHistoryEntity.formatDateKey(fifteenDaysAgoMs)}",
          scheduleId = "med_vitd",
          circleId = "c1",
          medicationName = "Vitamin D",
          dosage = "1000 IU",
          scheduleSlot = "Morning",
          status = MedicationHistoryEntity.STATUS_TAKEN,
          eventTimestampMs = fifteenDaysAgoMs,
          dateKey = MedicationHistoryEntity.formatDateKey(fifteenDaysAgoMs)
        )
      )

      val last7 = MedicationDateRangeSelection.fromPreset(
        MedicationDateRangePreset.LAST_7_DAYS,
        nowMillis = nowMs
      )
      val last30 = MedicationDateRangeSelection.fromPreset(
        MedicationDateRangePreset.LAST_30_DAYS,
        nowMillis = nowMs
      )

      val results7Days = repo.observeHistoryInRange(last7.startMillis, last7.endMillis).first()
      assertTrue(results7Days.any { it.medicationName == "Lisinopril" && it.isMissed })
      assertFalse(results7Days.any { it.medicationName == "Vitamin D" })

      val results30Days = repo.observeHistoryInRange(last30.startMillis, last30.endMillis).first()
      assertTrue(results30Days.any { it.medicationName == "Vitamin D" && it.isTaken })

      // Marking missed entry taken updates both history and today's schedule
      repo.markHistoryEntryTaken(todayEntry.id, nowMillis = nowMs)
      val updatedEntry = db.medicationHistoryDao().getEntryById(todayEntry.id)
      assertNotNull(updatedEntry)
      assertEquals(MedicationHistoryEntity.STATUS_TAKEN, updatedEntry!!.status)
      assertTrue(repo.getScheduleById("med_lisinopril")!!.takenToday)
    } finally {
      db.close()
    }
  }

  @Test
  fun `medication history screen renders lazy column and filters taken and missed entries`() {
    val nowMs = System.currentTimeMillis()
    val entries = listOf(
      MedicationHistoryEntity(
        id = "h1",
        scheduleId = "s1",
        medicationName = "Metformin",
        dosage = "500mg",
        scheduleSlot = "Morning",
        status = MedicationHistoryEntity.STATUS_TAKEN,
        eventTimestampMs = nowMs
      ),
      MedicationHistoryEntity(
        id = "h2",
        scheduleId = "s2",
        medicationName = "Atorvastatin",
        dosage = "20mg",
        scheduleSlot = "Evening",
        status = MedicationHistoryEntity.STATUS_MISSED,
        eventTimestampMs = nowMs - 3_600_000L
      )
    )
    var selectedRange by mutableStateOf(
      MedicationDateRangeSelection.fromPreset(MedicationDateRangePreset.LAST_7_DAYS, nowMillis = nowMs)
    )
    var markedId: String? = null

    composeTestRule.setContent {
      KindGuideTheme {
        MedicationHistoryScreen(
          historyEntries = entries,
          dateRange = selectedRange,
          onSelectDateRange = { selectedRange = it },
          onMarkEntryTaken = { markedId = it },
          onRefreshMissedCheck = {},
          onBack = {}
        )
      }
    }

    composeTestRule.onNodeWithTag("medication_history_lazy_column").assertIsDisplayed()
    composeTestRule.onNodeWithTag("medication_history_lazy_column")
      .performScrollToNode(hasText("Metformin"))
    composeTestRule.onNodeWithText("Metformin").assertIsDisplayed()

    composeTestRule.onNodeWithTag("medication_history_lazy_column")
      .performScrollToNode(hasText("Atorvastatin"))
    composeTestRule.onNodeWithText("Atorvastatin").assertIsDisplayed()

    // Scroll back up to filter chips and filter to Missed only
    composeTestRule.onNodeWithTag("medication_history_lazy_column")
      .performScrollToNode(hasTestTag("status_filter_chip_missed"))
    composeTestRule.onNodeWithTag("status_filter_chip_missed").performClick()

    composeTestRule.onNodeWithTag("medication_history_lazy_column")
      .performScrollToNode(hasText("Atorvastatin"))
    composeTestRule.onNodeWithText("Atorvastatin").assertIsDisplayed()
    composeTestRule.onNodeWithText("Metformin").assertDoesNotExist()

    // Mark missed dose as taken
    composeTestRule.onNodeWithTag("medication_history_lazy_column")
      .performScrollToNode(hasTestTag("history_mark_taken_h2"))
    composeTestRule.onNodeWithTag("history_mark_taken_h2").performClick()
    assertEquals("h2", markedId)

    // Switch date-range preset to 30 Days
    composeTestRule.onNodeWithTag("medication_history_lazy_column")
      .performScrollToNode(hasTestTag("date_filter_chip_30_days"))
    composeTestRule.onNodeWithTag("date_filter_chip_30_days").performClick()
    assertEquals(MedicationDateRangePreset.LAST_30_DAYS, selectedRange.preset)
  }

  @Test
  fun `room migration 2 to 3 adds missing dosage column and repository updates dosage`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val dbName = "migration_dosage_test.db"
    context.deleteDatabase(dbName)

    // Simulate a legacy table missing the dosage column at version 2
    val rawHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
      androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
        .name(dbName)
        .callback(
          object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
              db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `medication_schedules` (
                    `id` TEXT NOT NULL,
                    `circleId` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `scheduleSlot` TEXT NOT NULL,
                    `reminderHour` INTEGER NOT NULL,
                    `reminderMinute` INTEGER NOT NULL,
                    `reminderEnabled` INTEGER NOT NULL,
                    `takenToday` INTEGER NOT NULL,
                    `lastTakenEpochMs` INTEGER NOT NULL,
                    `updatedAtEpochMs` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
              )
              db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `medication_history` (
                    `id` TEXT NOT NULL,
                    `scheduleId` TEXT NOT NULL,
                    `circleId` TEXT NOT NULL,
                    `medicationName` TEXT NOT NULL,
                    `scheduleSlot` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `eventTimestampMs` INTEGER NOT NULL,
                    `dateKey` TEXT NOT NULL,
                    `reminderHour` INTEGER NOT NULL,
                    `reminderMinute` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
              )
              db.execSQL(
                "INSERT INTO `medication_schedules` VALUES ('m1','c1','Aspirin','Morning',8,0,1,0,0,1000)"
              )
            }

            override fun onUpgrade(
              db: androidx.sqlite.db.SupportSQLiteDatabase,
              oldVersion: Int,
              newVersion: Int
            ) = Unit
          }
        )
        .build()
    )
    rawHelper.writableDatabase.close()
    rawHelper.close()

    val migratedDb = Room.databaseBuilder(context, KindGuideDatabase::class.java, dbName)
      .addMigrations(KindGuideDatabase.MIGRATION_1_2, KindGuideDatabase.MIGRATION_2_3)
      .allowMainThreadQueries()
      .build()
    try {
      val repo = MedicationScheduleRepository(migratedDb.medicationScheduleDao())
      val existing = repo.getScheduleById("m1")
      assertNotNull(existing)
      assertEquals("", existing!!.dosage)

      val updated = repo.updateScheduleDetails(
        id = "m1",
        dosage = "81mg chewable",
        enabled = true,
        hour = 8,
        minute = 15
      )
      assertNotNull(updated)
      assertEquals("81mg chewable", updated!!.dosage)
    } finally {
      migratedDb.close()
      context.deleteDatabase(dbName)
    }
  }

  @Test
  fun `safety screen displays medication dosage from room schedules in medication list`() {
    val schedule = MedicationScheduleEntity(
      id = "med_rx1",
      circleId = "c1",
      name = "Metformin",
      dosage = "500mg twice daily",
      scheduleSlot = "Morning",
      reminderHour = 8,
      reminderMinute = 30,
      reminderEnabled = true
    )

    composeTestRule.setContent {
      KindGuideTheme {
        SafetyAndMapScreen(
          circle = CareCircle(
            id = "c1",
            seniorName = "Eleanor",
            homeAddress = "123 Maple St"
          ),
          medications = emptyList(),
          safePlaces = emptyList(),
          scamState = ScamUiState.Idle,
          currentLocation = null,
          onLocationUpdated = { _, _ -> },
          onToggleMedication = { _, _ -> },
          onAddMedication = { _, _, _ -> },
          onDeleteMedication = {},
          onAddSafePlace = { _, _, _, _, _ -> },
          onDeleteSafePlace = {},
          onCheckScam = { _, _ -> },
          onResetScam = {},
          onSendCheckIn = {},
          onTriggerSos = {},
          localSchedules = listOf(schedule)
        )
      }
    }

    composeTestRule.onNodeWithTag("safety_and_map_lazy_column")
      .performScrollToNode(hasTestTag("medication_card_med_rx1"))
    composeTestRule.onNodeWithTag("medication_card_med_rx1").assertIsDisplayed()
    composeTestRule.onNodeWithText("Metformin").assertIsDisplayed()
    composeTestRule.onNodeWithTag("med_dosage_med_rx1", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("Dosage: 500mg twice daily", useUnmergedTree = true).assertIsDisplayed()
  }

  @Test
  fun `medication history screen displays 7-day adherence trend chart and updates selected day`() {
    val nowMs = System.currentTimeMillis()
    val twoDaysAgoMs = nowMs - 2 * 24 * 60 * 60_000L
    val twoDaysAgoKey = MedicationHistoryEntity.formatDateKey(twoDaysAgoMs)
    val todayKey = MedicationHistoryEntity.formatDateKey(nowMs)

    val entries = listOf(
      MedicationHistoryEntity(
        id = "h_today_taken",
        scheduleId = "s1",
        medicationName = "Metformin",
        dosage = "500mg",
        scheduleSlot = "Morning",
        status = MedicationHistoryEntity.STATUS_TAKEN,
        eventTimestampMs = nowMs,
        dateKey = todayKey
      ),
      MedicationHistoryEntity(
        id = "h_2d_taken",
        scheduleId = "s1",
        medicationName = "Metformin",
        dosage = "500mg",
        scheduleSlot = "Morning",
        status = MedicationHistoryEntity.STATUS_TAKEN,
        eventTimestampMs = twoDaysAgoMs,
        dateKey = twoDaysAgoKey
      ),
      MedicationHistoryEntity(
        id = "h_2d_missed",
        scheduleId = "s2",
        medicationName = "Atorvastatin",
        dosage = "20mg",
        scheduleSlot = "Evening",
        status = MedicationHistoryEntity.STATUS_MISSED,
        eventTimestampMs = twoDaysAgoMs,
        dateKey = twoDaysAgoKey
      )
    )

    val weeklyPoints = buildWeeklyAdherencePoints(entries = entries, nowMillis = nowMs)
    assertEquals(7, weeklyPoints.size)
    assertEquals(todayKey, weeklyPoints.last().dateKey)
    val twoDaysPoint = weeklyPoints.first { it.dateKey == twoDaysAgoKey }
    assertEquals(1, twoDaysPoint.takenCount)
    assertEquals(1, twoDaysPoint.missedCount)
    assertEquals(50f, twoDaysPoint.adherencePercent!!, 0.1f)

    composeTestRule.setContent {
      KindGuideTheme {
        MedicationHistoryScreen(
          historyEntries = entries.take(1),
          weeklyHistoryEntries = entries,
          dateRange = MedicationDateRangeSelection.fromPreset(
            MedicationDateRangePreset.TODAY,
            nowMillis = nowMs
          ),
          onSelectDateRange = {},
          onMarkEntryTaken = {},
          onRefreshMissedCheck = {},
          onBack = {}
        )
      }
    }

    composeTestRule.onNodeWithTag("medication_history_lazy_column")
      .performScrollToNode(hasTestTag("weekly_adherence_chart_card"))
    composeTestRule.onNodeWithTag("weekly_adherence_chart_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("weekly_adherence_canvas").assertIsDisplayed()
    composeTestRule.onNodeWithText("67% 7d Avg").assertIsDisplayed()

    // Select two days ago on the X-axis to inspect its breakdown
    composeTestRule.onNodeWithTag("trend_day_$twoDaysAgoKey").performClick()
    composeTestRule.onNodeWithText("1 taken • 1 missed (50%)").assertIsDisplayed()
  }
}
