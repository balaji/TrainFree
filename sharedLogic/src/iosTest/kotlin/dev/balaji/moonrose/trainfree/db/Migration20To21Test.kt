package dev.balaji.moonrose.trainfree.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import dev.balaji.moonrose.trainfree.model.WeightUnit
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
 * Reconstructs a real v20 database from the exported schema SQL, runs
 * [MIGRATION_20_21] with destructive fallback disabled, and verifies Room's
 * schema validation and `body_metrics.external_id` read/write behavior.
 */
class Migration20To21Test {
    private val dbPath = NSTemporaryDirectory() + "migration-20-21-${NSUUID().UUIDString}.db"

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
    fun migratesV20DatabaseToV21PreservesMetricsAndAddsExternalId() {
        seedV20Database()

        val db = Room.databaseBuilder<AppDatabase>(name = dbPath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .addMigrations(MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21)
            .build()

        try {
            runBlocking {
                val existing = db.bodyMetricDao().getAll()
                assertEquals(1, existing.size)
                assertEquals(82.5, existing[0].weight)
                assertNull(existing[0].externalId)

                db.bodyMetricDao().insert(
                    BodyMetricEntity(
                        profileId = PROFILE_ID,
                        weight = 81.9,
                        weightUnit = WeightUnit.METRIC,
                        loggedAt = 2_000L,
                        externalId = "hc-weight-1",
                        createdAt = 2_000L,
                        updatedAt = 2_000L,
                    ),
                )
                val imported = db.bodyMetricDao().findByExternalId("hc-weight-1")
                assertEquals(81.9, imported?.weight)
            }
        } finally {
            db.close()
        }
    }

    private fun seedV20Database() {
        val connection = BundledSQLiteDriver().open(dbPath)
        try {
            listOf(
                "CREATE TABLE IF NOT EXISTS `profile` (`id` INTEGER NOT NULL, `age` INTEGER, `gender` TEXT, `weight` REAL, `height` REAL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `exercises` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `exercise_type` TEXT, `body_part` TEXT, `other_body_parts` TEXT, `equipment` TEXT, `builtin` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)",
                "CREATE TABLE IF NOT EXISTS `programs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profile_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `routines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `program_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `position` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`program_id`) REFERENCES `programs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `work_sets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `routine_id` INTEGER NOT NULL, `exercise_id` INTEGER NOT NULL, `position` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`routine_id`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`exercise_id`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
                "CREATE TABLE IF NOT EXISTS `work_set_details` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `work_set_id` INTEGER NOT NULL, `position` INTEGER NOT NULL, `target_weight` REAL, `target_reps` INTEGER, `target_duration` INTEGER, `target_distance` REAL, `set_type` TEXT NOT NULL DEFAULT 'normal', `weight_unit` TEXT NOT NULL DEFAULT 'metric', `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`work_set_id`) REFERENCES `work_sets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                "CREATE TABLE IF NOT EXISTS `workout_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profile_id` INTEGER NOT NULL, `routine_id` INTEGER, `performed_at` INTEGER NOT NULL, `completed_at` INTEGER, `title` TEXT NOT NULL DEFAULT '', `test_data` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`routine_id`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
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
                "INSERT INTO `profile` (`id`, `age`, `gender`, `weight`, `height`, `created_at`, `updated_at`) VALUES (1, 30, 'male', 82.5, 178.0, 1000, 1000)",
                "INSERT INTO `body_metrics` (`id`, `profile_id`, `weight`, `weight_unit`, `logged_at`, `test_data`, `created_at`, `updated_at`) VALUES (1, 1, 82.5, 'metric', 1000, 0, 1000, 1000)",
                "PRAGMA user_version = 20",
            ).forEach { connection.execSQL(it) }
        } finally {
            connection.close()
        }
    }
}
