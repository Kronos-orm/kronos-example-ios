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

/** A statement error can leave its connection usable; unknown transport failures cannot. */
class SqliteDriverFailure(
    message: String,
    cause: Throwable? = null,
    val connectionInvalid: Boolean = true
) : IllegalStateException(message, cause)

/** Both drivers classify extended SQLite codes by their primary result code. */
internal fun sqliteFailure(code: Int?, message: String, cause: Throwable? = null): SqliteDriverFailure =
    SqliteDriverFailure(message, cause, connectionInvalid = code == null || (code and 0xff) !in statementErrorCodes)

// ERROR, ABORT, BUSY, LOCKED, READONLY, INTERRUPT, SCHEMA, TOOBIG, CONSTRAINT,
// MISMATCH, AUTH and RANGE do not make the connection handle unusable.
private val statementErrorCodes = setOf(1, 4, 5, 6, 8, 9, 17, 18, 19, 20, 23, 25)

internal expect fun defaultSqliteDriver(): SqliteDriver
internal expect fun currentThreadIdentity(): Long
