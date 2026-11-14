package dev.balaji.moonrose.trainfree.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExerciseSeedTest {

    private data class ExerciseRow(
        val name: String,
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

            sql.startsWith("INSERT OR IGNORE INTO exercises") -> InsertStatement { name, builtin ->
                insertAttempts++
                rowsByName.putIfAbsent(name, ExerciseRow(name = name, builtin = builtin))
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
        private val onInsert: (name: String, builtin: Long) -> Unit,
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
            onInsert(
                requireNotNull(textArgs[1]) { "Missing name parameter" },
                requireNotNull(longArgs[7]) { "Missing builtin parameter" },
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
}
