@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kotlinorm.example.ios

import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import com.kotlinorm.example.sqlite.c.*
import kotlinx.cinterop.*

internal actual fun defaultSqliteDriver(): SqliteDriver = CapiSqliteDriver()

/** Application-owned fallback for Native targets without AndroidX bundled artifacts. */
class CapiSqliteDriver : SqliteDriver {
    override val name = "sqlite-c-api-3.54.1"

    override fun open(path: String): SqliteConnection = memScoped {
        val result = alloc<CPointerVar<sqlite3>>()
        val code = sqlite3_open_v2(path, result.ptr,
            SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE or SQLITE_OPEN_FULLMUTEX or SQLITE_OPEN_URI, null)
        val pointer = result.value
        if (code != SQLITE_OK || pointer == null) {
            val message = pointer?.let { sqlite3_errmsg(it)?.toKString() } ?: "Cannot allocate SQLite connection"
            pointer?.let { sqlite3_close_v2(it) }
            throw SqliteDriverFailure("SQLite open failed ($code): $message")
        }
        Connection(pointer)
    }

    private class Connection(private var pointer: CPointer<sqlite3>?) : SqliteConnection {
        fun requirePointer(): CPointer<sqlite3> = pointer ?: throw SqliteDriverFailure("SQLite connection is closed")

        fun check(code: Int) {
            if (code != SQLITE_OK) throw sqliteFailure(
                code, "SQLite error ($code): ${sqlite3_errmsg(requirePointer())?.toKString()}"
            )
        }

        override fun prepare(sql: String): SqliteStatement = memScoped {
            val result = alloc<CPointerVar<sqlite3_stmt>>()
            val tail = alloc<CPointerVar<ByteVar>>()
            val encoded = sql.cstr.getPointer(this)
            check(sqlite3_prepare_v2(requirePointer(), encoded, -1, result.ptr, tail.ptr))
            val statement = result.value ?: throw SqliteDriverFailure("SQLite SQL does not contain a statement")
            // One plan executes exactly one statement; never silently discard trailing SQL.
            if (!tail.value?.toKString().orEmpty().isBlank()) {
                sqlite3_finalize(statement)
                throw SqliteDriverFailure("SQLite execution plans must contain exactly one statement")
            }
            Statement(this@Connection, statement)
        }

        override fun close() {
            val current = pointer ?: return
            check(sqlite3_close_v2(current))
            pointer = null
        }
    }

    private class Statement(
        private val connection: Connection,
        private var pointer: CPointer<sqlite3_stmt>?
    ) : SqliteStatement {
        private fun pointer(): CPointer<sqlite3_stmt> = pointer ?: throw SqliteDriverFailure("SQLite statement is closed")
        override fun bindNull(index: Int) = connection.check(sqlite3_bind_null(pointer(), index))
        override fun bindLong(index: Int, value: Long) = connection.check(sqlite3_bind_int64(pointer(), index, value))
        override fun bindDouble(index: Int, value: Double) = connection.check(sqlite3_bind_double(pointer(), index, value))
        override fun bindText(index: Int, value: String) = connection.check(
            kronos_sqlite_bind_text(pointer(), index, value, value.encodeToByteArray().size)
        )
        override fun bindBlob(index: Int, value: ByteArray) {
            if (value.isEmpty()) connection.check(sqlite3_bind_zeroblob(pointer(), index, 0))
            else value.usePinned { connection.check(kronos_sqlite_bind_blob(pointer(), index, it.addressOf(0), value.size)) }
        }
        override fun step(): Boolean = when (val code = sqlite3_step(pointer())) {
            SQLITE_ROW -> true
            SQLITE_DONE -> false
            else -> {
                connection.check(code)
                false
            }
        }
        override fun reset() = connection.check(sqlite3_reset(pointer()))
        override fun clearBindings() = connection.check(sqlite3_clear_bindings(pointer()))
        override fun getColumnCount(): Int = sqlite3_column_count(pointer())
        override fun getColumnName(index: Int): String = sqlite3_column_name(pointer(), index)?.toKString()
            ?: throw SqliteDriverFailure("SQLite column $index has no label")
        override fun getColumnType(index: Int): Int = sqlite3_column_type(pointer(), index)
        override fun isNull(index: Int): Boolean = getColumnType(index) == SQLITE_NULL
        override fun getLong(index: Int): Long = sqlite3_column_int64(pointer(), index)
        override fun getDouble(index: Int): Double = sqlite3_column_double(pointer(), index)
        override fun getText(index: Int): String = sqlite3_column_text(pointer(), index)
            ?.reinterpret<ByteVar>()?.readBytes(sqlite3_column_bytes(pointer(), index))?.decodeToString()
            ?: throw SqliteDriverFailure("SQLite text column $index is null")
        override fun getBlob(index: Int): ByteArray {
            val size = sqlite3_column_bytes(pointer(), index)
            if (size == 0) return byteArrayOf()
            return sqlite3_column_blob(pointer(), index)?.reinterpret<ByteVar>()?.readBytes(size)
                ?: throw SqliteDriverFailure("SQLite blob column $index is null")
        }
        override fun close() {
            val current = pointer ?: return
            pointer = null
            // finalize repeats a previous step error; preserve that original exception.
            sqlite3_finalize(current)
        }
    }
}
