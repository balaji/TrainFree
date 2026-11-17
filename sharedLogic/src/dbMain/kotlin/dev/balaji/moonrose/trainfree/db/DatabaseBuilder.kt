package dev.balaji.moonrose.trainfree.db

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal expect fun databaseBuilder(): RoomDatabase.Builder<AppDatabase>

// v18 drops exercises.description and refreshes builtin seed rows in-place
// (preserving row ids and foreign keys in work_sets / workout_session_details).
internal val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `exercises` DROP COLUMN `description`")
        ExerciseSeedCallback.migrateBuiltinExercises(connection)
    }
}

internal val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `journal_entries` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`profile_id` INTEGER NOT NULL, " +
                "`epoch_day` INTEGER NOT NULL, " +
                "`content` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, " +
                "FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_journal_entries_profile_id` ON `journal_entries` (`profile_id`)",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_journal_entries_profile_id_epoch_day` " +
                "ON `journal_entries` (`profile_id`, `epoch_day`)",
        )
    }
}

internal val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `workout_sessions` ADD COLUMN `title` TEXT NOT NULL DEFAULT ''",
        )
        connection.execSQL(
            "UPDATE `workout_sessions` " +
                "SET `title` = COALESCE((SELECT `name` FROM `routines` WHERE `routines`.`id` = `workout_sessions`.`routine_id`), '') " +
                "WHERE `routine_id` IS NOT NULL",
        )
        connection.execSQL(
            "UPDATE `workout_sessions` " +
                "SET `title` = 'Workout on ' || strftime('%Y/%m/%d', `performed_at` / 1000, 'unixepoch', 'localtime') " +
                "WHERE `title` = ''",
        )
    }
}

internal val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `body_metrics` ADD COLUMN `external_id` TEXT",
        )
        connection.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_body_metrics_external_id` " +
                "ON `body_metrics` (`external_id`)",
        )
    }
}

fun createDatabase(): AppDatabase =
    databaseBuilder()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addCallback(ExerciseSeedCallback)
        .addMigrations(MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21)
        // Pre-release safety net for schema history older than v17.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

object AppDb {
    val db: AppDatabase by lazy { createDatabase() }
}

@OptIn(ExperimentalTime::class)
internal fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
