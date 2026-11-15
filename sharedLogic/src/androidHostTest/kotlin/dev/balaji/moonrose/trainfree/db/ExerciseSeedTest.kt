package dev.balaji.moonrose.trainfree.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import dev.balaji.moonrose.trainfree.model.ExerciseType
import dev.balaji.moonrose.trainfree.model.SamplePrograms
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExerciseSeedTest {

    private data class ExerciseRow(
        val name: String,
        val type: String,
        val bodyPart: String,
        val otherBodyParts: List<String>,
        val equipment: String,
        val builtin: Long,
    )

    private class FakeSQLiteConnection : SQLiteConnection {
        val rowsByName = linkedMapOf<String, ExerciseRow>()
        var inTx = false
            private set
        var beginCount = 0
            private set
        var endCount = 0
            private set
        var rollbackCount = 0
            private set
        var insertAttempts = 0
            private set

        override fun inTransaction(): Boolean = inTx

        override fun prepare(sql: String): SQLiteStatement = when {
            sql == "BEGIN EXCLUSIVE TRANSACTION" -> ExecStatement {
                check(!inTx) { "Nested transaction started" }
                inTx = true
                beginCount++
            }

            sql == "END TRANSACTION" -> ExecStatement {
                check(inTx) { "END TRANSACTION without active transaction" }
                inTx = false
                endCount++
            }

            sql == "ROLLBACK TRANSACTION" -> ExecStatement {
                check(inTx) { "ROLLBACK TRANSACTION without active transaction" }
                inTx = false
                rollbackCount++
            }

            sql.startsWith("SELECT EXISTS(") -> ExistsStatement {
                if (rowsByName.values.any { it.builtin != 0L }) 1L else 0L
            }

            sql.startsWith("INSERT OR IGNORE INTO exercises") -> InsertStatement { row ->
                insertAttempts++
                rowsByName.putIfAbsent(row.name, row)
            }

            else -> error("Unexpected SQL in ExerciseSeedTest: $sql")
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

    private class ExistsStatement(private val query: () -> Long) : BaseStatement() {
        private var result = 0L

        override fun step(): Boolean {
            result = query()
            return true
        }

        override fun getLong(index: Int): Long {
            check(index == 0)
            return result
        }
    }

    private class InsertStatement(
        private val onInsert: (ExerciseRow) -> Unit,
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
            val otherRaw = requireNotNull(textArgs[4]) { "Missing other_body_parts parameter" }
            val otherList = if (otherRaw.isEmpty()) emptyList() else otherRaw.split(",")
            onInsert(
                ExerciseRow(
                    name = requireNotNull(textArgs[1]) { "Missing name parameter" },
                    type = requireNotNull(textArgs[2]) { "Missing exercise_type parameter" },
                    bodyPart = requireNotNull(textArgs[3]) { "Missing body_part parameter" },
                    otherBodyParts = otherList,
                    equipment = requireNotNull(textArgs[5]) { "Missing equipment parameter" },
                    builtin = requireNotNull(longArgs[6]) { "Missing builtin parameter" },
                ),
            )
            return false
        }
    }

    @Test
    fun onOpenSeedsEmptyExercisesTableAndIsIdempotent() {
        val connection = FakeSQLiteConnection()
        assertEquals(0, connection.rowsByName.size)

        ExerciseSeedCallback.onOpen(connection)
        val seededCount = connection.rowsByName.size
        assertTrue(seededCount > 0)
        assertTrue(connection.rowsByName.values.all { it.builtin == 1L })
        assertEquals(1, connection.beginCount)
        assertEquals(1, connection.endCount)
        assertEquals(0, connection.rollbackCount)
        assertFalse(connection.inTransaction())

        val attemptsAfterFirstOpen = connection.insertAttempts
        ExerciseSeedCallback.onOpen(connection)
        assertEquals(seededCount, connection.rowsByName.size)
        assertEquals(1, connection.beginCount)
        assertEquals(1, connection.endCount)
        assertEquals(attemptsAfterFirstOpen, connection.insertAttempts)
    }

    @Test
    fun onCreateFollowedByOnOpenDoesNotDuplicateSeed() {
        val connection = FakeSQLiteConnection()

        ExerciseSeedCallback.onCreate(connection)
        val seededCount = connection.rowsByName.size
        assertTrue(seededCount > 0)
        val attemptsAfterCreate = connection.insertAttempts

        ExerciseSeedCallback.onOpen(connection)
        assertEquals(seededCount, connection.rowsByName.size)
        assertEquals(0, connection.beginCount)
        assertEquals(0, connection.endCount)
        assertEquals(attemptsAfterCreate, connection.insertAttempts)
    }

    @Test
    fun seedCatalogMetadataIsValidAndMatchesSamplePrograms() {
        val canonicalBodyParts = setOf(
            "back", "biceps", "calves", "chest", "core", "forearms",
            "full body", "glutes", "hamstrings", "shoulders", "thighs", "triceps",
        )
        val canonicalEquipment = setOf(
            "machine", "body weight", "cable", "barbell", "smith machine",
            "dumbbell", "kettlebell", "other", "band",
        )

        val connection = FakeSQLiteConnection()
        ExerciseSeedCallback.onCreate(connection)

        assertEquals(
            connection.insertAttempts,
            connection.rowsByName.size,
            "Duplicate exercise name found in SEED_EXERCISES",
        )

        for (row in connection.rowsByName.values) {
            assertTrue(row.name.isNotBlank(), "Exercise name must not be blank")
            assertNotNull(
                ExerciseType.fromValue(row.type),
                "Invalid exercise type '${row.type}' for ${row.name}",
            )
            assertTrue(
                row.bodyPart in canonicalBodyParts,
                "Invalid primary bodyPart '${row.bodyPart}' for ${row.name}",
            )
            assertTrue(
                row.equipment in canonicalEquipment,
                "Invalid equipment '${row.equipment}' for ${row.name}",
            )
            assertTrue(
                row.otherBodyParts.all { it in canonicalBodyParts && it != "full body" },
                "Non-canonical entry in otherBodyParts for ${row.name}: ${row.otherBodyParts}",
            )
            assertFalse(
                row.bodyPart in row.otherBodyParts,
                "otherBodyParts contains primary bodyPart '${row.bodyPart}' for ${row.name}",
            )
            assertEquals(
                row.otherBodyParts.distinct().size,
                row.otherBodyParts.size,
                "Duplicate entries in otherBodyParts for ${row.name}: ${row.otherBodyParts}",
            )
        }

        val sampleExercises = SamplePrograms.all
            .flatMap { it.routines }
            .flatMap { it.workSets }
            .map { it.exerciseName }
            .distinct()
        for (name in sampleExercises) {
            assertTrue(
                name in connection.rowsByName,
                "SampleProgram references unknown seed exercise '$name'",
            )
        }
    }
}
