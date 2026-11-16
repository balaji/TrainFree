package dev.balaji.moonrose.trainfree.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Reconstructs a real v19 database from the exported schema SQL, runs
 * [MIGRATION_19_20] with destructive fallback disabled, and verifies Room's
 * schema validation, title backfill, and read/write behavior.
 */
class Migration19To20Test {
    private val dbPath = NSTemporaryDirectory() + "migration-19-20-${NSUUID().UUIDString}.db"

    @AfterTest
    fun tearDown() {
        val fm = NSFileManager.defaultManager
        listOf(dbPath, "$dbPath-wal", "$dbPath-shm").forEach { path ->
            if (fm.fileExistsAtPath(path)) {
                fm.removeItemAtPath(path, null)
            }
        }
    }

    @Test
    fun migratesV19DatabaseToV20BackfillsTitlesAndSupportsRenaming() {
        seedV19Database()

        val db = Room.databaseBuilder<AppDatabase>(name = dbPath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .addMigrations(MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20)
            .build()

        try {
            runBlocking {
                val routineSession = db.workoutSessionDao().findById(100L)
                val emptySession = db.workoutSessionDao().findById(101L)
                val deletedRoutineSession = db.workoutSessionDao().findById(102L)
                val emptyPerformedAt = emptySession!!.performedAt
                val expectedEmptyTitle = defaultWorkoutTitleForMillis(emptyPerformedAt)

                assertEquals("Push Day", routineSession?.title)
                assertEquals(expectedEmptyTitle, emptySession.title)
                assertEquals(expectedEmptyTitle, deletedRoutineSession?.title)

                db.workoutSessionDao().updateTitle(101L, "Evening Pump", now = 5000L)
                assertEquals("Evening Pump", db.workoutSessionDao().findById(101L)?.title)

                val completed = db.workoutSessionDao().observeCompletedWithRoutineName(PROFILE_ID).first()
                assertEquals(listOf("Evening Pump", "Push Day", expectedEmptyTitle), completed.map { it.title })
            }
        } finally {
            db.close()
        }
    }

    private fun seedV19Database() {
        val connection = BundledSQLiteDriver().open(dbPath)
        val sampleMillis = 1_794_744_000_000L // 2026-11-15T12:00:00Z
        try {
            listOf(
                "CREATE TABLE IF NOT EXISTS `profile` (`id` INTEGER NOT NULL, `age` INTEGER, `gender` TEXT, `weight` REAL, `height` REAL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `exercises` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `exercise_type` TEXT, `body_part` TEXT, `other_body_parts` TEXT, `equipment` TEXT, `builtin` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS `programs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profile_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `routines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `program_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `position` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`program_id`) REFERENCES `programs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `work_sets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `routine_id` INTEGER NOT NULL, `exercise_id` INTEGER NOT NULL, `position` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`routine_id`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`exercise_id`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
                "CREATE TABLE IF NOT EXISTS `work_set_details` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `work_set_id` INTEGER NOT NULL, `position` INTEGER NOT NULL, `target_weight` REAL, `target_reps` INTEGER, `target_duration` INTEGER, `target_distance` REAL, `set_type` TEXT NOT NULL DEFAULT 'normal', `weight_unit` TEXT NOT NULL DEFAULT 'metric', `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`work_set_id`) REFERENCES `work_sets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `workout_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profile_id` INTEGER NOT NULL, `routine_id` INTEGER, `performed_at` INTEGER NOT NULL, `completed_at` INTEGER, `test_data` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`routine_id`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
                "CREATE TABLE IF NOT EXISTS `workout_session_details` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workout_session_id` INTEGER NOT NULL, `work_set_detail_id` INTEGER, `exercise_id` INTEGER NOT NULL, `position` INTEGER NOT NULL, `target_weight` REAL, `target_reps` INTEGER, `target_duration` INTEGER, `target_distance` REAL, `actual_weight` REAL, `actual_reps` INTEGER, `actual_duration` INTEGER, `actual_distance` REAL, `set_type` TEXT NOT NULL DEFAULT 'normal', `weight_unit` TEXT NOT NULL DEFAULT 'metric', `completed` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`workout_session_id`) REFERENCES `workout_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`work_set_detail_id`) REFERENCES `work_set_details`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`exercise_id`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
                "CREATE TABLE IF NOT EXISTS `user_settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`key`))",
                "CREATE TABLE IF NOT EXISTS `body_metrics` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profile_id` INTEGER NOT NULL, `weight` REAL NOT NULL, `weight_unit` TEXT NOT NULL DEFAULT 'metric', `logged_at` INTEGER NOT NULL, `test_data` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `ai_insight_cache` (`card_id` TEXT NOT NULL, `stats_hash` TEXT NOT NULL, `text` TEXT NOT NULL, `generated_at` INTEGER NOT NULL, PRIMARY KEY(`card_id`))",
                "CREATE TABLE IF NOT EXISTS `journal_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profile_id` INTEGER NOT NULL, `epoch_day` INTEGER NOT NULL, `content` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_exercises_name` ON `exercises` (`name`)",
                "CREATE INDEX IF NOT EXISTS `index_programs_profile_id` ON `programs` (`profile_id`)",
                "CREATE INDEX IF NOT EXISTS `index_routines_program_id` ON `routines` (`program_id`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_routines_program_id_position` ON `routines` (`program_id`, `position`)",
                "CREATE INDEX IF NOT EXISTS `index_work_sets_routine_id` ON `work_sets` (`routine_id`)",
                "CREATE INDEX IF NOT EXISTS `index_work_sets_exercise_id` ON `work_sets` (`exercise_id`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_work_sets_routine_id_position` ON `work_sets` (`routine_id`, `position`)",
                "CREATE INDEX IF NOT EXISTS `index_work_set_details_work_set_id` ON `work_set_details` (`work_set_id`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_work_set_details_work_set_id_position` ON `work_set_details` (`work_set_id`, `position`)",
                "CREATE INDEX IF NOT EXISTS `index_workout_sessions_routine_id` ON `workout_sessions` (`routine_id`)",
                "CREATE INDEX IF NOT EXISTS `index_workout_sessions_profile_id_performed_at` ON `workout_sessions` (`profile_id`, `performed_at`)",
                "CREATE INDEX IF NOT EXISTS `index_workout_session_details_work_set_detail_id` ON `workout_session_details` (`work_set_detail_id`)",
                "CREATE INDEX IF NOT EXISTS `index_workout_session_details_exercise_id` ON `workout_session_details` (`exercise_id`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_workout_session_details_workout_session_id_position` ON `workout_session_details` (`workout_session_id`, `position`)",
                "CREATE INDEX IF NOT EXISTS `index_body_metrics_profile_id_logged_at` ON `body_metrics` (`profile_id`, `logged_at`)",
                "CREATE INDEX IF NOT EXISTS `index_journal_entries_profile_id` ON `journal_entries` (`profile_id`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_journal_entries_profile_id_epoch_day` ON `journal_entries` (`profile_id`, `epoch_day`)",
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '6da1f3101b8b5c44537b5071e6e1d1b5')",
                "PRAGMA user_version = 19",
                "INSERT INTO profile (id, age, gender, weight, height, created_at, updated_at) VALUES (1, 30, 'male', 75.0, 175.0, 1, 1)",
                "INSERT INTO programs (id, profile_id, name, created_at, updated_at) VALUES (10, 1, 'PPL', 1, 1)",
                "INSERT INTO routines (id, program_id, name, position, created_at, updated_at) VALUES (20, 10, 'Push Day', 1, 1, 1)",
                "INSERT INTO workout_sessions (id, profile_id, routine_id, performed_at, completed_at, test_data, created_at, updated_at) VALUES (100, 1, 20, ${sampleMillis - 1000L}, ${sampleMillis - 500L}, 0, 1, 1)",
                "INSERT INTO workout_sessions (id, profile_id, routine_id, performed_at, completed_at, test_data, created_at, updated_at) VALUES (101, 1, NULL, $sampleMillis, ${sampleMillis + 500L}, 0, 1, 1)",
                "INSERT INTO workout_sessions (id, profile_id, routine_id, performed_at, completed_at, test_data, created_at, updated_at) VALUES (102, 1, NULL, ${sampleMillis - 2000L}, ${sampleMillis - 1500L}, 0, 1, 1)",
            ).forEach(connection::execSQL)
        } finally {
            connection.close()
        }
    }
}
