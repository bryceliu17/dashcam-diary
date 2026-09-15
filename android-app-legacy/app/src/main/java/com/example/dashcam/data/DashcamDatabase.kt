package com.example.dashcam.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun fromStatus(value: UploadStatus) = value.name
    @TypeConverter fun toStatus(value: String) = UploadStatus.valueOf(value)
}

@Database(entities = [VideoEntity::class, AudioEntity::class, BatteryTemperatureSample::class, LocationPointEntity::class], version = 6, exportSchema = false)
@TypeConverters(Converters::class)
abstract class DashcamDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun audioDao(): AudioDao
    abstract fun batteryTemperatureDao(): BatteryTemperatureDao
    abstract fun locationPointDao(): LocationPointDao

    companion object {
        @Volatile private var instance: DashcamDatabase? = null
        fun get(context: Context): DashcamDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, DashcamDatabase::class.java, "dashcam.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { instance = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE videos ADD COLUMN playbackRotationDegrees INTEGER DEFAULT NULL")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS audio_recordings (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        filename TEXT NOT NULL,
                        localPath TEXT NOT NULL,
                        startTime INTEGER NOT NULL,
                        endTime INTEGER NOT NULL,
                        durationSeconds INTEGER NOT NULL,
                        fileSizeBytes INTEGER NOT NULL,
                        uploadStatus TEXT NOT NULL,
                        retryCount INTEGER NOT NULL,
                        lastUploadAttemptAt INTEGER,
                        uploadedAt INTEGER,
                        serverAudioId INTEGER,
                        errorMessage TEXT,
                        locked INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS battery_temperature_samples (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        recordedAt INTEGER NOT NULL,
                        temperatureTenthsC INTEGER NOT NULL,
                        batteryLevel INTEGER NOT NULL,
                        isCharging INTEGER NOT NULL,
                        videoRecordingActive INTEGER NOT NULL,
                        audioRecordingActive INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_battery_temperature_samples_recordedAt ON battery_temperature_samples (recordedAt)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE battery_temperature_samples ADD COLUMN voltageMillivolts INTEGER")
                db.execSQL("ALTER TABLE battery_temperature_samples ADD COLUMN currentNowMicroamps INTEGER")
                db.execSQL("ALTER TABLE battery_temperature_samples ADD COLUMN estimatedBatteryPowerMilliwatts INTEGER")
                db.execSQL("ALTER TABLE battery_temperature_samples ADD COLUMN chargingSource TEXT NOT NULL DEFAULT 'Unknown'")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE videos ADD COLUMN recordingUuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE audio_recordings ADD COLUMN recordingUuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS location_points (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        recordingUuid TEXT NOT NULL,
                        mediaType TEXT NOT NULL,
                        recordedAt INTEGER NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        accuracyMeters REAL NOT NULL,
                        speedMetersPerSecond REAL,
                        bearingDegrees REAL,
                        altitudeMeters REAL,
                        provider TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_location_points_recordingUuid ON location_points (recordingUuid)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_location_points_recordedAt ON location_points (recordedAt)")
            }
        }
    }
}
