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

fun createDatabase(): AppDatabase =
    databaseBuilder()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addCallback(ExerciseSeedCallback)
        .addMigrations(MIGRATION_17_18)
        // Pre-release safety net for schema history older than v17.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()

object AppDb {
    val db: AppDatabase by lazy { createDatabase() }
}

@OptIn(ExperimentalTime::class)
internal fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
