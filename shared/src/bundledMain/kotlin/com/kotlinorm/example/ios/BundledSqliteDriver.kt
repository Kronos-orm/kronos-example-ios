package com.kotlinorm.example.ios

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_FULLMUTEX
import androidx.sqlite.driver.bundled.SQLITE_OPEN_URI

internal actual fun defaultSqliteDriver(): SqliteDriver = AndroidxBundledDriver()

class AndroidxBundledDriver : SqliteDriver {
    private val delegate = BundledSQLiteDriver()
    override val name = "androidx-bundled-2.6.2"
    override fun open(path: String): SqliteConnection = driverCall { Connection(delegate.open(path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_URI)) }

    private class Connection(private val delegate: SQLiteConnection) : SqliteConnection {
        override fun prepare(sql: String): SqliteStatement = driverCall { Statement(delegate.prepare(sql)) }
        override fun close() = driverCall { delegate.close() }
    }

    private class Statement(private val delegate: SQLiteStatement) : SqliteStatement {
        override fun bindNull(index: Int) = driverCall { delegate.bindNull(index) }
        override fun bindLong(index: Int, value: Long) = driverCall { delegate.bindLong(index, value) }
        override fun bindDouble(index: Int, value: Double) = driverCall { delegate.bindDouble(index, value) }
        override fun bindText(index: Int, value: String) = driverCall { delegate.bindText(index, value) }
        override fun bindBlob(index: Int, value: ByteArray) = driverCall { delegate.bindBlob(index, value) }
        override fun step(): Boolean = driverCall { delegate.step() }
        override fun reset() = driverCall { delegate.reset() }
        override fun clearBindings() = driverCall { delegate.clearBindings() }
        override fun getColumnCount(): Int = driverCall { delegate.getColumnCount() }
        override fun getColumnName(index: Int): String = driverCall { delegate.getColumnName(index) }
        override fun getColumnType(index: Int): Int = driverCall { delegate.getColumnType(index) }
        override fun isNull(index: Int): Boolean = driverCall { delegate.isNull(index) }
        override fun getLong(index: Int): Long = driverCall { delegate.getLong(index) }
        override fun getDouble(index: Int): Double = driverCall { delegate.getDouble(index) }
        // AndroidX 2.6.2 getText uses a zero-terminated UTF-16 read and truncates embedded NUL.
        // SQLite's BLOB accessor exposes a TEXT cell's complete UTF-8 bytes plus explicit length.
        override fun getText(index: Int): String = driverCall { delegate.getBlob(index).decodeToString() }
        override fun getBlob(index: Int): ByteArray = driverCall { delegate.getBlob(index) }
        override fun close() = driverCall { delegate.close() }
    }
}

private inline fun <T> driverCall(block: () -> T): T = try {
    block()
} catch (cause: Exception) {
    // AndroidX 2.6.2 exposes SQLite's result code in throwSQLiteException's message.
    val code = if (cause is SQLiteException) {
        cause.message?.removePrefix("Error code: ")?.substringBefore(',')?.toIntOrNull()
    } else null
    throw sqliteFailure(code, cause.message ?: "SQLite driver failure", cause)
}
