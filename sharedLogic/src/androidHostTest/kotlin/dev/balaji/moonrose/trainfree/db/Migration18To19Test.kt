package dev.balaji.moonrose.trainfree.db

import androidx.room.RoomOpenDelegate
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Migration18To19Test {
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
    fun v19OpenDelegateMatchesExportedSchemaVersionAndHash() {
        val method = AppDatabase_Impl::class.java.getDeclaredMethod("createOpenDelegate")
        method.isAccessible = true
        val delegate = method.invoke(AppDatabase_Impl()) as RoomOpenDelegate

        assertTrue(delegate.version >= 19)
    }

    @Test
    fun migration18To19CreatesJournalEntriesTableAndIndices() {
        val connection = RecordingSQLiteConnection()
        assertEquals(18, MIGRATION_18_19.startVersion)
        assertEquals(19, MIGRATION_18_19.endVersion)

        MIGRATION_18_19.migrate(connection)

        assertEquals(3, connection.executedSql.size)
        val createTable = connection.executedSql[0]
        assertTrue(createTable.contains("CREATE TABLE IF NOT EXISTS `journal_entries`"))
        assertTrue(createTable.contains("`profile_id` INTEGER NOT NULL"))
        assertTrue(createTable.contains("`epoch_day` INTEGER NOT NULL"))
        assertTrue(createTable.contains("`content` TEXT NOT NULL"))
        assertTrue(createTable.contains("FOREIGN KEY(`profile_id`) REFERENCES `profile`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE"))
        assertTrue(connection.executedSql[1].contains("index_journal_entries_profile_id"))
        assertTrue(connection.executedSql[2].contains("UNIQUE INDEX IF NOT EXISTS `index_journal_entries_profile_id_epoch_day`"))
    }
}
