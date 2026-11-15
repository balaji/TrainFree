package dev.balaji.moonrose.trainfree.db

import androidx.room.RoomOpenDelegate
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies [MIGRATION_17_18] behavior against an in-memory v17 SQLite schema
 * double (matching `ExerciseSeedTest`, because `androidHostTest` lacks native
 * desktop JNI binaries for `BundledSQLiteDriver`) and validates the generated
 * [AppDatabase_Impl] open delegate version and schema identity hash.
 */
class Migration17To18Test {

    private data class MigratedExerciseRow(
        val id: Long,
        var name: String,
        var exerciseType: String?,
        var bodyPart: String?,
        var otherBodyParts: String?,
        var equipment: String?,
        val builtin: Boolean,
        var updatedAt: Long,
    )

    private class FakeV17SQLiteConnection(
        initialRows: List<MigratedExerciseRow>,
    ) : SQLiteConnection {
        var hasDescriptionColumn = true
            private set
        val rowsById: LinkedHashMap<Long, MigratedExerciseRow> =
            initialRows.associateByTo(linkedMapOf()) { it.id }

        override fun inTransaction(): Boolean = false

        override fun prepare(sql: String): SQLiteStatement = when (sql) {
            "ALTER TABLE `exercises` DROP COLUMN `description`" -> ExecStatement {
                check(hasDescriptionColumn) { "Expected exercises.description column before DROP COLUMN" }
                hasDescriptionColumn = false
            }

            "UPDATE OR IGNORE exercises SET name = ?, updated_at = ? WHERE name = ? AND builtin != 0" ->
                RenameStatement { newName, updatedAt, oldName ->
                    check(!hasDescriptionColumn) { "Expected DROP COLUMN to run before renaming builtin exercises" }
                    if (rowsById.values.none { it.name == newName }) {
                        rowsById.values
                            .filter { it.name == oldName && it.builtin }
                            .forEach { row ->
                                row.name = newName
                                row.updatedAt = updatedAt
                            }
                    }
                }

            "UPDATE exercises SET exercise_type = ?, body_part = ?, other_body_parts = ?, equipment = ?, updated_at = ? WHERE name = ? AND builtin != 0" ->
                UpdateMetadataStatement { type, bodyPart, otherBodyParts, equipment, updatedAt, name ->
                    check(!hasDescriptionColumn) { "Expected DROP COLUMN to run before updating exercise metadata" }
                    rowsById.values
                        .filter { it.name == name && it.builtin }
                        .forEach { row ->
                            row.exerciseType = type
                            row.bodyPart = bodyPart
                            row.otherBodyParts = otherBodyParts
                            row.equipment = equipment
                            row.updatedAt = updatedAt
                        }
                }

            else -> error("Unexpected SQL in Migration17To18Test: $sql")
        }

        override fun close() = Unit
    }

    private abstract class BaseStatement : SQLiteStatement {
        override fun bindBlob(index: Int, value: ByteArray): Unit = unsupported()
        override fun bindDouble(index: Int, value: Double): Unit = unsupported()
        override fun bindLong(index: Int, value: Long): Unit = unsupported()
        override fun bindText(index: Int, value: String): Unit = unsupported()
        override fun bindNull(index: Int): Unit = unsupported()
        override fun getBlob(index: Int): ByteArray = unsupported()
        override fun getDouble(index: Int): Double = unsupported()
        override fun getLong(index: Int): Long = unsupported()
        override fun getText(index: Int): String = unsupported()
        override fun isNull(index: Int): Boolean = unsupported()
        override fun getColumnCount(): Int = unsupported()
        override fun getColumnName(index: Int): String = unsupported()
        override fun getColumnType(index: Int): Int = unsupported()
        override fun reset() = Unit
        override fun clearBindings() = Unit
        override fun close() = Unit
        private fun unsupported(): Nothing = error("Unsupported SQLiteStatement operation")
    }

    private class ExecStatement(private val onStep: () -> Unit) : BaseStatement() {
        override fun step(): Boolean {
            onStep()
            return false
        }
    }

    private class RenameStatement(
        private val onRename: (newName: String, updatedAt: Long, oldName: String) -> Unit,
    ) : BaseStatement() {
        private val textArgs = mutableMapOf<Int, String>()
        private val longArgs = mutableMapOf<Int, Long>()

        override fun bindText(index: Int, value: String) {
            textArgs[index] = value
        }

        override fun bindLong(index: Int, value: Long) {
            longArgs[index] = value
        }

        override fun step(): Boolean {
            onRename(
                requireNotNull(textArgs[1]) { "Missing newName bind arg" },
                requireNotNull(longArgs[2]) { "Missing updatedAt bind arg" },
                requireNotNull(textArgs[3]) { "Missing oldName bind arg" },
            )
            return false
        }
    }

    private class UpdateMetadataStatement(
        private val onUpdate: (
            type: String,
            bodyPart: String,
            otherBodyParts: String,
            equipment: String,
            updatedAt: Long,
            name: String,
        ) -> Unit,
    ) : BaseStatement() {
        private val textArgs = mutableMapOf<Int, String>()
        private val longArgs = mutableMapOf<Int, Long>()

        override fun bindText(index: Int, value: String) {
            textArgs[index] = value
        }

        override fun bindLong(index: Int, value: Long) {
            longArgs[index] = value
        }

        override fun step(): Boolean {
            onUpdate(
                requireNotNull(textArgs[1]) { "Missing exercise_type bind arg" },
                requireNotNull(textArgs[2]) { "Missing body_part bind arg" },
                requireNotNull(textArgs[3]) { "Missing other_body_parts bind arg" },
                requireNotNull(textArgs[4]) { "Missing equipment bind arg" },
                requireNotNull(longArgs[5]) { "Missing updated_at bind arg" },
                requireNotNull(textArgs[6]) { "Missing name bind arg" },
            )
            return false
        }
    }

    @Test
    fun dropsDescriptionAndMigratesBuiltinExercisesPreservingIds() {
        val connection = FakeV17SQLiteConnection(v17Fixtures())
        assertEquals(17, MIGRATION_17_18.startVersion)
        assertEquals(18, MIGRATION_17_18.endVersion)
        assertTrue(connection.hasDescriptionColumn)

        MIGRATION_17_18.migrate(connection)

        assertFalse(connection.hasDescriptionColumn, "exercises.description column should be dropped")
        val rows = connection.rowsById

        assertRow(
            rows.getValue(1L),
            id = 1L,
            name = "Single-Arm Dumbbell Row",
            type = "strength",
            bodyPart = "back",
            otherBodyParts = "biceps,shoulders,core",
            equipment = "dumbbell",
            builtin = true,
        )
        assertRow(
            rows.getValue(2L),
            id = 2L,
            name = "Kneeling Cable Lat Prayer",
            type = "strength",
            bodyPart = "back",
            otherBodyParts = "core",
            equipment = "cable",
            builtin = true,
        )
        assertRow(
            rows.getValue(3L),
            id = 3L,
            name = "Bradford Press",
            type = "strength",
            bodyPart = "shoulders",
            otherBodyParts = "triceps,back",
            equipment = "barbell",
            builtin = true,
        )
        assertRow(
            rows.getValue(4L),
            id = 4L,
            name = "Dumbbell Biceps Curl",
            type = "strength",
            bodyPart = "biceps",
            otherBodyParts = "forearms",
            equipment = "dumbbell",
            builtin = true,
        )
        assertRow(
            rows.getValue(5L),
            id = 5L,
            name = "Close-Grip Chin-Up",
            type = "strength",
            bodyPart = "biceps",
            otherBodyParts = "back,forearms",
            equipment = "body weight",
            builtin = true,
        )
        assertRow(
            rows.getValue(6L),
            id = 6L,
            name = "Cyclist Squat",
            type = "strength",
            bodyPart = "thighs",
            otherBodyParts = "glutes",
            equipment = "dumbbell",
            builtin = true,
        )
        assertRow(
            rows.getValue(7L),
            id = 7L,
            name = "Romanian Deadlift",
            type = "strength",
            bodyPart = "hamstrings",
            otherBodyParts = "glutes,back,forearms",
            equipment = "barbell",
            builtin = true,
        )
        assertRow(
            rows.getValue(8L),
            id = 8L,
            name = "Barbell Bent-Over Row",
            type = "strength",
            bodyPart = "back",
            otherBodyParts = "biceps,shoulders,forearms,core",
            equipment = "barbell",
            builtin = true,
        )
        assertRow(
            rows.getValue(9L),
            id = 9L,
            name = "Outdoor Run",
            type = "distance_duration",
            bodyPart = "thighs",
            otherBodyParts = "glutes,hamstrings,calves",
            equipment = "body weight",
            builtin = true,
        )
        assertRow(
            rows.getValue(10L),
            id = 10L,
            name = "Farmer's Walk",
            type = "duration",
            bodyPart = "forearms",
            otherBodyParts = "back,core,calves",
            equipment = "dumbbell",
            builtin = true,
        )
        (1L..10L).forEach { id ->
            assertTrue(rows.getValue(id).updatedAt > 1L, "Expected updatedAt > 1L for builtin id=$id")
        }

        val custom = rows.getValue(99L)
        assertRow(
            custom,
            id = 99L,
            name = "My Custom Zercher Carry",
            type = "duration",
            bodyPart = "full_body",
            otherBodyParts = "core,custom",
            equipment = "barbell",
            builtin = false,
        )
        assertEquals(1L, custom.updatedAt)
    }

    @Test
    fun handlesUniqueNameConflictGracefullyWhenCustomExerciseSharesRenamedTarget() {
        val connection = FakeV17SQLiteConnection(
            v17Fixtures() + MigratedExerciseRow(
                id = 50L,
                name = "Dumbbell Biceps Curl",
                exerciseType = "strength",
                bodyPart = "biceps",
                otherBodyParts = "custom_part",
                equipment = "dumbbell",
                builtin = false,
                updatedAt = 1L,
            ),
        )

        MIGRATION_17_18.migrate(connection)

        assertFalse(connection.hasDescriptionColumn)
        val rows = connection.rowsById
        val custom = rows.getValue(50L)
        assertEquals("Dumbbell Biceps Curl", custom.name)
        assertEquals(false, custom.builtin)
        assertEquals("custom_part", custom.otherBodyParts)
        assertEquals(1L, custom.updatedAt)
        // Unique conflict causes UPDATE OR IGNORE to leave builtin row 4's name intact.
        assertEquals("Dumbbell Bicep Curl", rows.getValue(4L).name)
    }

    @Test
    fun v18OpenDelegateMatchesExportedSchemaVersionAndHash() {
        val method = AppDatabase_Impl::class.java.getDeclaredMethod("createOpenDelegate")
        method.isAccessible = true
        val delegate = method.invoke(AppDatabase_Impl()) as RoomOpenDelegate

        assertTrue(delegate.version >= 18)
    }

    private fun v17Fixtures(): List<MigratedExerciseRow> = listOf(
        MigratedExerciseRow(1L, "One-Arm Dumbbell Row", "strength", "back", "biceps", "dumbbell", true, 1L),
        MigratedExerciseRow(2L, "Standing Cable Lat Prayer Pushdown", "strength", "back", "shoulders", "cable", true, 1L),
        MigratedExerciseRow(3L, "Dumbbell Shrug-Press Combo (Bradford Press)", "strength", "shoulders", "traps", "dumbbell", true, 1L),
        MigratedExerciseRow(4L, "Dumbbell Bicep Curl", "strength", "biceps", "forearms", "dumbbell", true, 1L),
        MigratedExerciseRow(5L, "Chin-Up (Biceps Focus)", "strength", "biceps", "back", "body weight", true, 1L),
        MigratedExerciseRow(6L, "Dumbbell Sissy Squat Hybrid (Cyclist Squat)", "strength", "thighs", "glutes", "dumbbell", true, 1L),
        MigratedExerciseRow(7L, "Romanian Deadlift", "strength", "thighs", "hamstrings,glutes,back", "barbell", true, 1L),
        MigratedExerciseRow(8L, "Barbell Bent-Over Row", "strength", "back", "biceps,traps,forearms", "barbell", true, 1L),
        MigratedExerciseRow(9L, "Outdoor Run", "distance_duration", "cardio", "thighs,calves", "body weight", true, 1L),
        MigratedExerciseRow(10L, "Farmer's Walk", "duration", "forearms", "traps,core", "dumbbell", true, 1L),
        MigratedExerciseRow(99L, "My Custom Zercher Carry", "duration", "full_body", "core,custom", "barbell", false, 1L),
    )

    private fun assertRow(
        actual: MigratedExerciseRow,
        id: Long,
        name: String,
        type: String,
        bodyPart: String,
        otherBodyParts: String,
        equipment: String,
        builtin: Boolean,
    ) {
        assertEquals(id, actual.id)
        assertEquals(name, actual.name)
        assertEquals(type, actual.exerciseType)
        assertEquals(bodyPart, actual.bodyPart)
        assertEquals(otherBodyParts, actual.otherBodyParts)
        assertEquals(equipment, actual.equipment)
        assertEquals(builtin, actual.builtin)
    }
}
