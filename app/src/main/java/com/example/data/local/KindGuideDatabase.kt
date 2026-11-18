package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MedicationScheduleEntity::class,
        MedicationHistoryEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class KindGuideDatabase : RoomDatabase() {

    abstract fun medicationScheduleDao(): MedicationScheduleDao
    abstract fun medicationHistoryDao(): MedicationHistoryDao

    companion object {
        private const val DATABASE_NAME = "kindguide_local.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `medication_history` (
                        `id` TEXT NOT NULL,
                        `scheduleId` TEXT NOT NULL,
                        `circleId` TEXT NOT NULL,
                        `medicationName` TEXT NOT NULL,
                        `dosage` TEXT NOT NULL,
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
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!hasColumn(db, "medication_schedules", "dosage")) {
                    db.execSQL(
                        "ALTER TABLE `medication_schedules` ADD COLUMN `dosage` TEXT NOT NULL DEFAULT ''"
                    )
                }
                if (!hasColumn(db, "medication_history", "dosage")) {
                    db.execSQL(
                        "ALTER TABLE `medication_history` ADD COLUMN `dosage` TEXT NOT NULL DEFAULT ''"
                    )
                }
            }

            private fun hasColumn(
                db: SupportSQLiteDatabase,
                tableName: String,
                columnName: String
            ): Boolean {
                db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (nameIndex >= 0 && cursor.getString(nameIndex) == columnName) {
                            return true
                        }
                    }
                }
                return false
            }
        }

        @Volatile
        private var instance: KindGuideDatabase? = null

        fun getInstance(context: Context): KindGuideDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    KindGuideDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
