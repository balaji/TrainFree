package dev.balaji.moonrose.trainfree.db

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory

/**
 * Opens a real v17 database built from `schemas/.../17.json` with destructive
 * fallback OFF so Room validates the migrated v18 schema against `18.json`,
 * verifying `exercises.description` removal, builtin exercise renames/updates,
 * foreign-key preservation, and custom exercise preservation.
 */
class Migration17To18Test {
    private val dbPath = NSTemporaryDirectory() + "migration_17_18_test.db"

    @BeforeTest
    fun setUp() = deleteDb()

    @AfterTest
    fun tearDown() = deleteDb()

    @OptIn(ExperimentalForeignApi::class)
    private fun deleteDb() {
        listOf(dbPath, "$dbPath-wal", "$dbPath-shm").forEach {
            NSFileManager.defaultManager.removeItemAtPath(it, null)
        }
    }

    @Test
    fun dropsDescriptionAndMigratesBuiltinExercisesPreservingForeignKeys() {
        seedV17Database()

        val db = openMigratedDb()
        runBlocking {
            val byId = db.exerciseDao().getAll().associateBy { it.id }

            assertExercise(
                byId.getValue(1L),
                name = "Single-Arm Dumbbell Row",
                type = "strength",
                bodyPart = "back",
                otherBodyParts = listOf("biceps", "shoulders", "core"),
                equipment = "dumbbell",
                builtin = true,
            )
            assertExercise(
                byId.getValue(2L),
                name = "Kneeling Cable Lat Prayer",
                type = "strength",
                bodyPart = "back",
                otherBodyParts = listOf("core"),
                equipment = "cable",
                builtin = true,
            )
            assertExercise(
                byId.getValue(3L),
                name = "Bradford Press",
                type = "strength",
                bodyPart = "shoulders",
                otherBodyParts = listOf("triceps", "back"),
                equipment = "barbell",
                builtin = true,
            )
            assertExercise(
                byId.getValue(4L),
                name = "Dumbbell Biceps Curl",
                type = "strength",
                bodyPart = "biceps",
                otherBodyParts = listOf("forearms"),
                equipment = "dumbbell",
                builtin = true,
            )
            assertExercise(
                byId.getValue(5L),
                name = "Close-Grip Chin-Up",
                type = "strength",
                bodyPart = "biceps",
                otherBodyParts = listOf("back", "forearms"),
                equipment = "body weight",
                builtin = true,
            )
            assertExercise(
                byId.getValue(6L),
                name = "Cyclist Squat",
                type = "strength",
                bodyPart = "thighs",
                otherBodyParts = listOf("glutes"),
                equipment = "dumbbell",
                builtin = true,
            )
            assertExercise(
                byId.getValue(7L),
                name = "Romanian Deadlift",
                type = "strength",
                bodyPart = "hamstrings",
                otherBodyParts = listOf("glutes", "back", "forearms"),
                equipment = "barbell",
                builtin = true,
            )
            assertExercise(
                byId.getValue(8L),
                name = "Barbell Bent-Over Row",
                type = "strength",
                bodyPart = "back",
                otherBodyParts = listOf("biceps", "shoulders", "forearms", "core"),
                equipment = "barbell",
                builtin = true,
            )
            assertExercise(
                byId.getValue(9L),
                name = "Outdoor Run",
                type = "distance_duration",
                bodyPart = "thighs",
                otherBodyParts = listOf("glutes", "hamstrings", "calves"),
                equipment = "body weight",
                builtin = true,
            )
            assertExercise(
                byId.getValue(10L),
                name = "Farmer's Walk",
                type = "duration",
                bodyPart = "forearms",
                otherBodyParts = listOf("back", "core", "calves"),
                equipment = "dumbbell",
                builtin = true,
            )
            assertTrue(byId.getValue(7L).updatedAt > 1L)

            val custom = byId.getValue(99L)
            assertExercise(
                custom,
                name = "My Custom Zercher Carry",
                type = "duration",
                bodyPart = "full_body",
                otherBodyParts = listOf("core", "custom"),
                equipment = "barbell",
                builtin = false,
            )
            assertEquals(1L, custom.updatedAt)

            assertEquals(listOf(1L, 2L, 99L), db.workSetDao().getForRoutine(1L).map { it.exerciseId })
            assertEquals(listOf(1L, 2L, 99L), db.workoutSessionDetailDao().getForSession(1L).map { it.exerciseId })
        }
        db.close()

        val connection = BundledSQLiteDriver().open(dbPath)
        try {
            val columns = connection.prepare("PRAGMA table_info(exercises)").use { stmt ->
                buildList {
                    while (stmt.step()) {
                        add(stmt.getText(1))
                    }
                }
            }
            assertFalse("description" in columns)
        } finally {
            connection.close()
        }
    }

    @Test
    fun handlesUniqueNameConflictGracefullyWhenCustomExerciseSharesRenamedTarget() {
        seedV17Database(
            extraStatements = listOf(
                "INSERT INTO exercises (id, name, description, exercise_type, body_part, other_body_parts, equipment, builtin, created_at, updated_at) " +
                    "VALUES (50, 'Dumbbell Biceps Curl', 'Custom curl', 'strength', 'biceps', 'custom_part', 'dumbbell', 0, 1, 1)",
            ),
        )

        val db = openMigratedDb()
        runBlocking {
            val byId = db.exerciseDao().getAll().associateBy { it.id }
            assertEquals("Dumbbell Biceps Curl", byId.getValue(50L).name)
            assertEquals(false, byId.getValue(50L).builtin)
            assertEquals(listOf("custom_part"), byId.getValue(50L).otherBodyParts)
            assertEquals("Dumbbell Bicep Curl", byId.getValue(4L).name)
        }
        db.close()
    }

    private fun openMigratedDb(): AppDatabase =
        Room.databaseBuilder<AppDatabase>(name = dbPath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.Default)
            .addMigrations(MIGRATION_17_18)
            // Deliberately NO fallbackToDestructiveMigration: Room validates the
            // migrated schema against 18.json and throws on any mismatch.
            .build()

    private fun seedV17Database(extraStatements: List<String> = emptyList()) {
        val connection = BundledSQLiteDriver().open(dbPath)
        try {
            V17_SCHEMA.forEach { connection.execSQL(it) }
            V17_FIXTURES.forEach { connection.execSQL(it) }
            extraStatements.forEach { connection.execSQL(it) }
        } finally {
            connection.close()
        }
    }

    private fun assertExercise(
        actual: ExerciseEntity,
        name: String,
        type: String,
        bodyPart: String,
        otherBodyParts: List<String>,
        equipment: String,
        builtin: Boolean,
    ) {
        assertEquals(name, actual.name)
        assertEquals(type, actual.exerciseType)
        assertEquals(bodyPart, actual.bodyPart)
        assertEquals(otherBodyParts, actual.otherBodyParts)
        assertEquals(equipment, actual.equipment)
        assertEquals(builtin, actual.builtin)
    }
}

/** DDL copied verbatim from `sharedLogic/schemas/dev.balaji.moonrose.trainfree.db.AppDatabase/17.json`. */
private val V17_SCHEMA = listOf(
    "CREATE TABLE IF NOT EXISTS `profile` (`id` INTEGER NOT NULL, `age` INTEGER, `gender` TEXT, `weight` REAL, `height` REAL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `exercises` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `exercise_type` TEXT, `body_part` TEXT, `other_body_parts` TEXT, `equipment` TEXT, `builtin` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_exercises_name` ON `exercises` (`name`)",
    "CREATE TABLE IF NOT EXISTS `programs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profile_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_programs_profile_id` ON `programs` (`profile_id`)",
    "CREATE TABLE IF NOT EXISTS `routines` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `program_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `position` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`program_id`) REFERENCES `programs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_routines_program_id` ON `routines` (`program_id`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_routines_program_id_position` ON `routines` (`program_id`, `position`)",
    "CREATE TABLE IF NOT EXISTS `work_sets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `routine_id` INTEGER NOT NULL, `exercise_id` INTEGER NOT NULL, `position` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`routine_id`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`exercise_id`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
    "CREATE INDEX IF NOT EXISTS `index_work_sets_routine_id` ON `work_sets` (`routine_id`)",
    "CREATE INDEX IF NOT EXISTS `index_work_sets_exercise_id` ON `work_sets` (`exercise_id`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_work_sets_routine_id_position` ON `work_sets` (`routine_id`, `position`)",
    "CREATE TABLE IF NOT EXISTS `work_set_details` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `work_set_id` INTEGER NOT NULL, `position` INTEGER NOT NULL, `target_weight` REAL, `target_reps` INTEGER, `target_duration` INTEGER, `target_distance` REAL, `set_type` TEXT NOT NULL DEFAULT 'normal', `weight_unit` TEXT NOT NULL DEFAULT 'metric', `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`work_set_id`) REFERENCES `work_sets`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_work_set_details_work_set_id` ON `work_set_details` (`work_set_id`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_work_set_details_work_set_id_position` ON `work_set_details` (`work_set_id`, `position`)",
    "CREATE TABLE IF NOT EXISTS `workout_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profile_id` INTEGER NOT NULL, `routine_id` INTEGER, `performed_at` INTEGER NOT NULL, `completed_at` INTEGER, `test_data` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`routine_id`) REFERENCES `routines`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
    "CREATE INDEX IF NOT EXISTS `index_workout_sessions_routine_id` ON `workout_sessions` (`routine_id`)",
    "CREATE INDEX IF NOT EXISTS `index_workout_sessions_profile_id_performed_at` ON `workout_sessions` (`profile_id`, `performed_at`)",
    "CREATE TABLE IF NOT EXISTS `workout_session_details` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workout_session_id` INTEGER NOT NULL, `work_set_detail_id` INTEGER, `exercise_id` INTEGER NOT NULL, `position` INTEGER NOT NULL, `target_weight` REAL, `target_reps` INTEGER, `target_duration` INTEGER, `target_distance` REAL, `actual_weight` REAL, `actual_reps` INTEGER, `actual_duration` INTEGER, `actual_distance` REAL, `set_type` TEXT NOT NULL DEFAULT 'normal', `weight_unit` TEXT NOT NULL DEFAULT 'metric', `completed` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`workout_session_id`) REFERENCES `workout_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`work_set_detail_id`) REFERENCES `work_set_details`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL , FOREIGN KEY(`exercise_id`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
    "CREATE INDEX IF NOT EXISTS `index_workout_session_details_work_set_detail_id` ON `workout_session_details` (`work_set_detail_id`)",
    "CREATE INDEX IF NOT EXISTS `index_workout_session_details_exercise_id` ON `workout_session_details` (`exercise_id`)",
    "CREATE UNIQUE INDEX IF NOT EXISTS `index_workout_session_details_workout_session_id_position` ON `workout_session_details` (`workout_session_id`, `position`)",
    "CREATE TABLE IF NOT EXISTS `user_settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`key`))",
    "CREATE TABLE IF NOT EXISTS `body_metrics` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `profile_id` INTEGER NOT NULL, `weight` REAL NOT NULL, `weight_unit` TEXT NOT NULL DEFAULT 'metric', `logged_at` INTEGER NOT NULL, `test_data` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    "CREATE INDEX IF NOT EXISTS `index_body_metrics_profile_id_logged_at` ON `body_metrics` (`profile_id`, `logged_at`)",
    "CREATE TABLE IF NOT EXISTS `ai_insight_cache` (`card_id` TEXT NOT NULL, `stats_hash` TEXT NOT NULL, `text` TEXT NOT NULL, `generated_at` INTEGER NOT NULL, PRIMARY KEY(`card_id`))",
    "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
    "INSERT OR REPLACE INTO room_master_table (id, identity_hash) VALUES(42, '4fabd2d68e6ffe8113def4baa4cbbee6')",
    "PRAGMA user_version = 17",
)

private val V17_FIXTURES = listOf(
    "INSERT INTO profile (id, age, gender, weight, height, created_at, updated_at) VALUES (1, 30, 'other', 80.0, 178.0, 1, 1)",
    "INSERT INTO exercises (id, name, description, exercise_type, body_part, other_body_parts, equipment, builtin, created_at, updated_at) VALUES (1, 'One-Arm Dumbbell Row', 'Pull dumbbell toward hip', 'strength', 'back', 'biceps', 'dumbbell', 1, 1, 1)",
    "INSERT INTO exercises (id, name, description, exercise_type, body_part, other_body_parts, equipment, builtin, created_at, updated_at) VALUES (2, 'Standing Cable Lat Prayer Pushdown', 'Push bar down', 'strength', 'back', 'shoulders', 'cable', 1, 1, 1)",
    "INSERT INTO exercises (id, name, description, exercise_type, body_part, other_body_parts, equipment, builtin, created_at, updated_at) VALUES (3, 'Dumbbell Shrug-Press Combo (Bradford Press)', 'Shrug press', 'strength', 'shoulders', 'traps', 'dumbbell', 1, 1, 1)",
    "INSERT INTO exercises (id, name, description, exercise_type, body_part, other_body_parts, equipment, builtin, created_at, updated_at) VALUES (4, 'Dumbbell Bicep Curl', 'Curl dumbbell', 'strength', 'biceps', 'forearms', 'dumbbell', 1, 1, 1)",
    "INSERT INTO exercises (id, name, description, exercise_type, body_part, other_body_parts, equipment, builtin, created_at, updated_at) VALUES (5, 'Chin-Up (Biceps Focus)', 'Pull chin over bar', 'strength', 'biceps', 'back', 'body weight', 1, 1, 1)",
    "INSERT INTO exercises (id, name, description, exercise_type, body_part, other_body_parts, equipment, builtin, created_at, updated_at) VALUES (6, 'Dumbbell Sissy Squat Hybrid (Cyclist Squat)', 'Squat on elevated heels', 'strength', 'thighs', 'glutes', 'dumbbell', 1, 1, 1)",
    "INSERT INTO exercises (id, name, description, exercise_type, body_part, other_body_parts, equipment, builtin, created_at, updated_at) VALUES (7, 'Romanian Deadlift', 'Hinge at hips', 'strength', 'thighs', 'hamstrings,glutes,back', 'barbell', 1, 1, 1)",
    "INSERT INTO exercises (id, name, description, exercise_type, body_part, other_body_parts, equipment, builtin, created_at, updated_at) VALUES (8, 'Barbell Bent-Over Row', 'Row barbell to torso', 'strength', 'back', 'biceps,traps,forearms', 'barbell', 1, 1, 1)",
    "INSERT INTO exercises (id, name, description, exercise_type, body_part, other_body_parts, equipment, builtin, created_at, updated_at) VALUES (9, 'Outdoor Run', 'Run outdoors', 'distance_duration', 'cardio', 'thighs,calves', 'body weight', 1, 1, 1)",
    "INSERT INTO exercises (id, name, description, exercise_type, body_part, other_body_parts, equipment, builtin, created_at, updated_at) VALUES (10, 'Farmer''s Walk', 'Walk holding dumbbells', 'duration', 'forearms', 'traps,core', 'dumbbell', 1, 1, 1)",
    "INSERT INTO exercises (id, name, description, exercise_type, body_part, other_body_parts, equipment, builtin, created_at, updated_at) VALUES (99, 'My Custom Zercher Carry', 'Custom notes', 'duration', 'full_body', 'core,custom', 'barbell', 0, 1, 1)",
    "INSERT INTO programs (id, profile_id, name, created_at, updated_at) VALUES (1, 1, 'Program', 1, 1)",
    "INSERT INTO routines (id, program_id, name, position, created_at, updated_at) VALUES (1, 1, 'Day A', 1, 1, 1)",
    "INSERT INTO work_sets (id, routine_id, exercise_id, position, created_at, updated_at) VALUES (1, 1, 1, 1, 1, 1)",
    "INSERT INTO work_sets (id, routine_id, exercise_id, position, created_at, updated_at) VALUES (2, 1, 2, 2, 1, 1)",
    "INSERT INTO work_sets (id, routine_id, exercise_id, position, created_at, updated_at) VALUES (3, 1, 99, 3, 1, 1)",
    "INSERT INTO work_set_details (id, work_set_id, position, target_weight, target_reps, set_type, weight_unit, created_at, updated_at) VALUES (1, 1, 1, 30.0, 10, 'normal', 'metric', 1, 1)",
    "INSERT INTO workout_sessions (id, profile_id, routine_id, performed_at, completed_at, created_at, updated_at) VALUES (1, 1, 1, 100, 200, 1, 1)",
    "INSERT INTO workout_session_details (id, workout_session_id, work_set_detail_id, exercise_id, position, target_weight, target_reps, actual_weight, actual_reps, set_type, weight_unit, completed, created_at, updated_at) VALUES (1, 1, 1, 1, 1, 30.0, 10, 30.0, 10, 'normal', 'metric', 1, 1, 1)",
    "INSERT INTO workout_session_details (id, workout_session_id, work_set_detail_id, exercise_id, position, target_weight, target_reps, actual_weight, actual_reps, set_type, weight_unit, completed, created_at, updated_at) VALUES (2, 1, NULL, 2, 2, 25.0, 12, 25.0, 12, 'normal', 'metric', 1, 1, 1)",
    "INSERT INTO workout_session_details (id, workout_session_id, work_set_detail_id, exercise_id, position, target_duration, actual_duration, set_type, weight_unit, completed, created_at, updated_at) VALUES (3, 1, NULL, 99, 3, 60, 60, 'normal', 'metric', 1, 1, 1)",
    "INSERT INTO user_settings (key, value, created_at, updated_at) VALUES ('weight_unit', 'metric', 1, 1)",
    "INSERT INTO body_metrics (id, profile_id, weight, weight_unit, logged_at, created_at, updated_at) VALUES (1, 1, 80.0, 'metric', 150, 1, 1)",
)
