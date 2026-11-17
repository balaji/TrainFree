package dev.balaji.moonrose.trainfree.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Migration19To20Test {
    private class RecordingSQLiteConnection : SQLiteConnection {
        val executedSql = mutableListOf<String>()

        override fun inTransaction(): Boolean = false

        override fun prepare(sql: String): SQLiteStatement {
            executedSql += sql
            return object : SQLiteStatement {
                override fun bindBlob(index: Int, value: ByteArray) = Unit
                override fun bindDouble(index: Int, value: Double) = Unit
                override fun bindLong(index: Int, value: Long) = Unit
                override fun bindText(index: Int, value: String) = Unit
                override fun bindNull(index: Int) = Unit
                override fun getBlob(index: Int): ByteArray = ByteArray(0)
                override fun getDouble(index: Int): Double = 0.0
                override fun getLong(index: Int): Long = 0L
                override fun getText(index: Int): String = ""
                override fun isNull(index: Int): Boolean = true
                override fun getColumnCount(): Int = 0
                override fun getColumnName(index: Int): String = ""
                override fun getColumnType(index: Int): Int = 0
                override fun step(): Boolean = false
                override fun reset() = Unit
                override fun clearBindings() = Unit
                override fun close() = Unit
            }
        }

        override fun close() = Unit
    }

    @Test
    fun migration19To20AddsTitleColumnAndBackfillsRoutineAndEmptyTitles() {
        val connection = RecordingSQLiteConnection()
        assertEquals(19, MIGRATION_19_20.startVersion)
        assertEquals(20, MIGRATION_19_20.endVersion)

        MIGRATION_19_20.migrate(connection)

        assertEquals(3, connection.executedSql.size)
        val alterTable = connection.executedSql[0]
        assertTrue(alterTable.contains("ALTER TABLE `workout_sessions` ADD COLUMN `title` TEXT NOT NULL DEFAULT ''"))
        assertTrue(connection.executedSql[1].contains("SELECT `name` FROM `routines`"))
        assertTrue(connection.executedSql[2].contains("'Workout on ' || strftime('%Y/%m/%d'"))
    }
}
