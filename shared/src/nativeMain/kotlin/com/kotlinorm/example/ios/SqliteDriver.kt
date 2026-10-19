package com.kotlinorm.example.ios

/** Example-owned driver seam. No driver type escapes into Kronos's published API. */
interface SqliteDriver {
    val name: String
    fun open(path: String): SqliteConnection
}

interface SqliteConnection {
    fun prepare(sql: String): SqliteStatement
    fun close()
}

interface SqliteStatement {
    fun bindNull(index: Int)
    fun bindLong(index: Int, value: Long)
    fun bindDouble(index: Int, value: Double)
    fun bindText(index: Int, value: String)
    fun bindBlob(index: Int, value: ByteArray)
    fun step(): Boolean
    fun reset()
    fun clearBindings()
    fun getColumnCount(): Int
    fun getColumnName(index: Int): String
    fun getColumnType(index: Int): Int
    fun isNull(index: Int): Boolean
    fun getLong(index: Int): Long
    fun getDouble(index: Int): Double
    fun getText(index: Int): String
    fun getBlob(index: Int): ByteArray
    fun close()
}

/** Distinguishes driver failures from exceptions thrown by an application's mapper. */
class SqliteDriverFailure(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal expect fun defaultSqliteDriver(): SqliteDriver
internal expect fun currentThreadIdentity(): Long
