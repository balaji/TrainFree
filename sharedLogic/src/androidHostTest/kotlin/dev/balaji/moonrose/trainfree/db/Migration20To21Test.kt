package dev.balaji.moonrose.trainfree.db

import androidx.room.RoomOpenDelegate
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Migration20To21Test {
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
    fun v21OpenDelegateMatchesExportedSchemaVersionAndHash() {
        val method = AppDatabase_Impl::class.java.getDeclaredMethod("createOpenDelegate")
        method.isAccessible = true
        val delegate = method.invoke(AppDatabase_Impl()) as RoomOpenDelegate

        assertEquals(21, delegate.version)
        assertEquals("c14f23fa046c759baa4fd5fb1824a770", delegate.identityHash)
    }

    @Test
    fun migration20To21AddsExternalIdColumnAndUniqueIndex() {
        val connection = RecordingSQLiteConnection()
        assertEquals(20, MIGRATION_20_21.startVersion)
        assertEquals(21, MIGRATION_20_21.endVersion)

        MIGRATION_20_21.migrate(connection)

        assertEquals(2, connection.executedSql.size)
        assertTrue(connection.executedSql[0].contains("ALTER TABLE `body_metrics` ADD COLUMN `external_id` TEXT"))
        assertTrue(connection.executedSql[1].contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_body_metrics_external_id`"))
    }
}
