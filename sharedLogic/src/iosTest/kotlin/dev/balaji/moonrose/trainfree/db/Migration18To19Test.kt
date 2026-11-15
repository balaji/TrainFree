package dev.balaji.moonrose.trainfree.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Reconstructs a real v18 database from the exported schema SQL, runs
 * [MIGRATION_18_19] with destructive fallback disabled, and verifies Room's
 * schema validation and journal read/write behavior.
 */
class Migration18To19Test {
    private val dbPath = NSTemporaryDirectory() + "migration-18-19-${NSUUID().UUIDString}.db"

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
    fun migratesV18DatabaseToV19AndPersistsJournalEntries() {
        seedV18Database()

        val db = Room.databaseBuilder<AppDatabase>(name = dbPath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .addMigrations(MIGRATION_17_18, MIGRATION_18_19)
            .build()

        try {
            runBlocking {
                assertNull(db.journalEntryDao().findForDay(PROFILE_ID, 20_000L))
                db.saveJournal(20_000L, "# Leg day\nFelt strong", now = 1234L)
                val saved = db.journalEntryDao().findForDay(PROFILE_ID, 20_000L)
                assertEquals("# Leg day\nFelt strong", saved?.content)

                db.saveJournal(20_000L, "   ", now = 1235L)
                assertNull(db.journalEntryDao().findForDay(PROFILE_ID, 20_000L))
            }
        } finally {
            db.close()
        }
    }

    private fun seedV18Database() {
        val connection = BundledSQLiteDriver().open(dbPath)
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
                "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '607fd7e94aeda46e9c80c141af004a9d')",
                "PRAGMA user_version = 18",
                "INSERT INTO profile (id, age, gender, weight, height, created_at, updated_at) VALUES (1, 30, 'male', 75.0, 175.0, 1, 1)",
            ).forEach(connection::execSQL)
        } finally {
            connection.close()
        }
    }
}
