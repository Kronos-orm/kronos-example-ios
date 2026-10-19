package com.kotlinorm.example.ios

import com.kotlinorm.enums.KOperationType
import com.kotlinorm.enums.DBType
import com.kotlinorm.interfaces.KronosActionExecutionPlan
import com.kotlinorm.interfaces.KronosBatchExecutionPlan
import com.kotlinorm.interfaces.KronosDatabaseIdentity
import com.kotlinorm.interfaces.KronosExecutionDataSource
import com.kotlinorm.interfaces.KronosExecutionFirstResult
import com.kotlinorm.interfaces.KronosExecutionResult
import com.kotlinorm.interfaces.KronosGeneratedKeyRequest
import com.kotlinorm.interfaces.KronosPhysicalRow
import com.kotlinorm.interfaces.KronosPhysicalValue
import com.kotlinorm.interfaces.KronosQueryExecutionPlan
import com.kotlinorm.interfaces.KronosSavepointToken
import com.kotlinorm.interfaces.KronosTransactionMode
import com.kotlinorm.interfaces.KronosTransactionOptions
import com.kotlinorm.interfaces.KronosTransactionScope
import kotlin.time.Instant
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Example-owned SQLite adapter for the common Kronos execution boundary.
 * AndroidX SQLite remains an application dependency and is not published by the
 * main Kronos repository.
 */
@OptIn(ExperimentalAtomicApi::class)
class KronosSqliteDataSource(
    private val driver: SqliteDriver = defaultSqliteDriver(),
    private val databaseName: String = ":memory:"
) : KronosExecutionDataSource {
    override val identity = KronosDatabaseIdentity(
        dbType = DBType.SQLite,
        databaseName = databaseName,
        driverName = driver.name
    )

    enum class State { NEW, OPEN, CLOSED, FAILED }

    private val lock = NativeAdapterLock()
    private val transactionOwner = AtomicLong(0L)
    private var connection: SqliteConnection? = null
    private var currentTransaction: TransactionContext? = null
    private var operationDepth = 0
    private var savepointSequence = 0L
    private var lifecycle = State.NEW
    private val statements = mutableSetOf<OwnedStatement>()
    private val rows = mutableSetOf<Row>()
    val state: State get() = withLock(ensureOpen = false) { lifecycle }

    fun open() = withLock(ensureOpen = false) {
        check(lifecycle != State.FAILED) { "SQLite data source failed; call reopen() to recover" }
        if (connection == null) {
            check(operationDepth == 0) { "Cannot open SQLite while an earlier operation is active" }
            openConnection()
        }
    }

    fun close() = withLock(ensureOpen = false) {
        var failure: Throwable? = null
        fun release(block: () -> Unit) {
            try { block() } catch (cause: Throwable) {
                val first = failure
                if (first == null) failure = cause else first.addSuppressed(cause)
            }
        }
        rows.forEach { it.isActive = false }
        rows.clear()
        statements.toList().forEach { statement -> release { statement.close() } }
        val transaction = currentTransaction
        if (transaction?.active == true) {
            release { transaction.connection.exec("ROLLBACK") }
            transaction.active = false
            transaction.tokens.forEach { it.active = false }
        }
        val previous = connection
        connection = null
        lifecycle = State.CLOSED
        if (previous != null) release { previous.close() }
        failure?.let { throw it }
    }

    fun reopen() = withLock(ensureOpen = false) {
        check(operationDepth == 0) { "Cannot reopen SQLite while an operation or transaction is active" }
        runCatching { connection?.close() }
        connection = null
        openConnection()
    }

    private fun openConnection() {
        try {
            connection = driver.open(databaseName)
            lifecycle = State.OPEN
        } catch (cause: SqliteDriverFailure) {
            lifecycle = State.FAILED
            throw cause
        }
    }

    override fun <T> toList(
        plan: KronosQueryExecutionPlan,
        mapper: (KronosPhysicalRow) -> T
    ): List<T> = withLock {
        val statement = prepare(plan.sql, plan.parameters, plan.parameterOrder)
        try {
            val results = mutableListOf<T>()
            while (statement.step()) {
                results += statement.mapRow(results.size, mapper)
            }
            results
        } finally {
            statement.close()
        }
    }

    override fun <T> first(
        plan: KronosQueryExecutionPlan,
        mapper: (KronosPhysicalRow) -> T
    ): KronosExecutionFirstResult<T> = withLock {
        val statement = prepare(plan.sql, plan.parameters, plan.parameterOrder)
        try {
            if (!statement.step()) {
                KronosExecutionFirstResult.Empty
            } else {
                KronosExecutionFirstResult.Present(statement.mapRow(0, mapper))
            }
        } finally {
            statement.close()
        }
    }

    override fun execute(plan: KronosActionExecutionPlan): KronosExecutionResult = withLock {
        require(plan.generatedKey?.capture != true || plan.operationType == KOperationType.INSERT) {
            "Generated keys require a plain INSERT plan; conflict-update/UPSERT keys are unsupported"
        }
        validateGeneratedKey(plan)
        val statement = prepare(plan.sql, plan.parameters, plan.parameterOrder)
        try {
            val affectedRows = executeToCompletion(statement, plan.sql)
            val generatedKeys = plan.generatedKey?.takeIf { it.capture && affectedRows > 0 }?.let { listOf(lastInsertRowId()) }.orEmpty()
            KronosExecutionResult(
                affectedRows = affectedRows,
                generatedKeys = generatedKeys,
                lastInsertId = generatedKeys.firstOrNull().asLongOrNull()
            )
        } finally {
            statement.close()
        }
    }

    override fun executeBatch(plan: KronosBatchExecutionPlan): List<KronosExecutionResult> = withLock {
        if (plan.entries.isEmpty()) return@withLock emptyList()
        val first = plan.sql.replaceNamedParameters(plan.entries.first(), plan.parameterOrder)
        val statement = createStatement(first.first)
        try {
            plan.entries.map { entry ->
                statement.reset()
                statement.clearBindings()
                val materialized = plan.sql.replaceNamedParameters(entry, plan.parameterOrder)
                require(materialized.first == first.first) { "Batch entries must produce one stable SQL shape" }
                materialized.second.forEachIndexed { index, value -> bind(statement, index + 1, value) }
                KronosExecutionResult(affectedRows = executeToCompletion(statement, plan.sql))
            }
        } finally {
            statement.close()
        }
    }

    override fun <T> transact(
        options: KronosTransactionOptions,
        block: KronosTransactionScope.() -> T
    ): T = withLock {
        require(options.timeoutSeconds == null) { "This SQLite driver does not support transaction timeouts" }
        val active = currentTransaction
        if (active != null) {
            val scope = TransactionScope(active)
            val token = scope.createSavepoint()
            return@withLock try {
                scope.block().also { scope.releaseSavepoint(token) }
            } catch (cause: Throwable) {
                runCatching {
                    scope.rollbackToSavepoint(token)
                    scope.releaseSavepoint(token)
                }.exceptionOrNull()?.let(cause::addSuppressed)
                throw cause
            } finally {
                scope.active = false
            }
        }

        val context = TransactionContext(requireConnection(), currentThreadIdentity())
        val scope = TransactionScope(context)
        context.connection.exec("BEGIN ${options.mode.sqlName()}")
        currentTransaction = context
        transactionOwner.store(context.owner)
        try {
            scope.block().also {
                check(lifecycle == State.OPEN) { "SQLite transaction connection failed" }
                context.connection.exec("COMMIT")
            }
        } catch (cause: Throwable) {
            if (context.active && connection === context.connection) {
                runCatching { context.connection.exec("ROLLBACK") }.exceptionOrNull()?.let(cause::addSuppressed)
            }
            throw cause
        } finally {
            scope.active = false
            context.active = false
            context.tokens.forEach { it.active = false }
            currentTransaction = null
            transactionOwner.store(0L)
        }
    }

    private fun prepare(
        sql: String,
        parameters: Map<String, Any?>,
        parameterOrder: List<String>
    ): SqliteStatement {
        val materialized = sql.replaceNamedParameters(parameters, parameterOrder)
        val statement = createStatement(materialized.first)
        try {
            materialized.second.forEachIndexed { index, value -> bind(statement, index + 1, value) }
            return statement
        } catch (cause: Throwable) {
            statement.close()
            throw cause
        }
    }

    private fun bind(statement: SqliteStatement, index: Int, value: Any?) {
        when (value) {
            null -> statement.bindNull(index)
            KronosPhysicalValue.Null -> statement.bindNull(index)
            is KronosPhysicalValue.Integer -> statement.bindLong(index, value.value)
            is KronosPhysicalValue.Real -> statement.bindDouble(index, value.value)
            is KronosPhysicalValue.Text -> statement.bindText(index, value.value)
            is KronosPhysicalValue.Blob -> statement.bindBlob(index, value.bytes)
            is Boolean -> statement.bindLong(index, if (value) 1 else 0)
            is Byte -> statement.bindLong(index, value.toLong())
            is Short -> statement.bindLong(index, value.toLong())
            is Int -> statement.bindLong(index, value.toLong())
            is Long -> statement.bindLong(index, value)
            is Float -> statement.bindDouble(index, value.toDouble())
            is Double -> statement.bindDouble(index, value)
            is ByteArray -> statement.bindBlob(index, value)
            is Instant -> statement.bindText(index, value.toString())
            is Enum<*> -> statement.bindText(index, value.name)
            is String -> statement.bindText(index, value)
            else -> error("Unsupported SQLite parameter at index $index: ${value::class}")
        }
    }

    private fun executeToCompletion(statement: SqliteStatement, sql: String): Int {
        val previousTotal = singleLong("SELECT total_changes()")
        while (statement.step()) { /* Finish DML RETURNING and PRAGMA result rows. */ }
        val currentTotal = singleLong("SELECT total_changes()")
        // Schema/PRAGMA statements can leave changes() unchanged; DROP with FK cascades can even
        // increase total_changes(). Classify the actual SQL rather than relying on plan defaults.
        return if (!isSqliteRowMutation(sql) || currentTotal == previousTotal) 0 else changes()
    }

    private fun changes(): Int = singleLong("SELECT changes()").toInt()

    private fun lastInsertRowId(): Long = singleLong("SELECT last_insert_rowid()")

    private fun singleLong(sql: String): Long {
        val statement = createStatement(sql)
        return try {
            check(statement.step()) { "Expected one row for $sql" }
            statement.getLong(0)
        } finally {
            statement.close()
        }
    }

    private fun requireConnection(): SqliteConnection = connection ?: error(
        "SQLite data source is closed; call open() or reopen() before executing operations"
    )

    private fun checkTransactionOwner() {
        val owner = transactionOwner.load()
        check(owner == 0L || owner == currentThreadIdentity()) {
            "SQLite transaction belongs to another thread"
        }
    }

    private fun <T> withLock(ensureOpen: Boolean = true, block: () -> T): T {
        // Check before locking: a foreign thread must fail instead of waiting for its
        // transaction's callback (which might itself be waiting for that thread).
        checkTransactionOwner()
        lock.lock()
        return try {
            checkTransactionOwner()
            if (ensureOpen) {
                when (lifecycle) {
                    State.NEW -> openConnection()
                    State.OPEN -> Unit
                    State.CLOSED -> error("SQLite data source is closed; call open() or reopen()")
                    State.FAILED -> error("SQLite data source failed; call reopen() to recover")
                }
                operationDepth++
            }
            try {
                block().also {
                    if (ensureOpen) check(lifecycle == State.OPEN) {
                        "SQLite operation was aborted because its connection was closed or failed"
                    }
                }
            } catch (cause: SqliteDriverFailure) {
                lifecycle = State.FAILED
                throw cause
            } finally {
                if (ensureOpen) operationDepth--
                if (operationDepth == 0 && lifecycle == State.FAILED) {
                    runCatching { connection?.close() }
                    connection = null
                }
            }
        } finally {
            lock.unlock()
        }
    }

    private fun createStatement(sql: String): SqliteStatement =
        OwnedStatement(requireConnection().prepare(sql)).also(statements::add)

    private inner class OwnedStatement(private val delegate: SqliteStatement) : SqliteStatement by delegate {
        private var closed = false
        override fun step(): Boolean {
            check(!closed) { "SQLite statement was closed during its operation" }
            return delegate.step()
        }
        override fun close() {
            if (closed) return
            closed = true
            try { delegate.close() } finally { statements.remove(this) }
        }
    }

    private class Row(
        private val statement: SqliteStatement,
        private val ordinal: Int = 0
    ) : KronosPhysicalRow {
        private val owner = currentThreadIdentity()
        override val rowNumber: Int get() { requireActive(); return ordinal }
        override fun requireActive() {
            check(isActive) { "KronosPhysicalRow is only valid while its mapper is running" }
            check(owner == currentThreadIdentity()) { "SQLite row belongs to another thread" }
        }
        override val columnCount: Int
            get() {
                requireActive()
                return statement.getColumnCount()
            }
        override var isActive: Boolean = true

        override fun columnLabel(position: Int): String {
            requireColumnPosition(position)
            return statement.getColumnName(position - 1)
        }

        override fun get(position: Int): KronosPhysicalValue {
            requireColumnPosition(position)
            val index = position - 1
            if (statement.isNull(index)) return KronosPhysicalValue.Null
            return when (statement.getColumnType(index)) {
                SQLITE_INTEGER -> KronosPhysicalValue.Integer(statement.getLong(index))
                SQLITE_FLOAT -> KronosPhysicalValue.Real(statement.getDouble(index))
                SQLITE_BLOB -> KronosPhysicalValue.Blob(statement.getBlob(index))
                SQLITE_TEXT -> KronosPhysicalValue.Text(statement.getText(index))
                else -> error("Unsupported SQLite storage type at column $position")
            }
        }
    }

    private fun <T> SqliteStatement.mapRow(rowNumber: Int, mapper: (KronosPhysicalRow) -> T): T {
        val row = Row(this, rowNumber)
        rows += row
        return try {
            mapper(row)
        } finally {
            row.isActive = false
            rows.remove(row)
        }
    }

    private class TransactionContext(val connection: SqliteConnection, val owner: Long) {
        var active = true
        val tokens = mutableListOf<SavepointToken>()
    }

    private inner class TransactionScope(private val context: TransactionContext) : KronosTransactionScope {
        var active = true

        private fun validate() {
            checkTransactionOwner()
            check(active && context.active && currentTransaction === context && lifecycle == State.OPEN) {
                "SQLite transaction scope is no longer active"
            }
        }

        fun createSavepoint(): SavepointToken {
            validate()
            // User labels are descriptive only; SQL names are unique and opaque.
            val token = SavepointToken("kronos_savepoint_${++savepointSequence}", context, this)
            context.connection.exec("SAVEPOINT ${token.name.quotedIdentifier()}")
            context.tokens += token
            return token
        }

        override fun savepoint(name: String?): KronosSavepointToken = withLock { createSavepoint() }

        override fun rollbackToSavepoint(savepoint: KronosSavepointToken) = withLock {
            val token = requireToken(savepoint)
            context.connection.exec("ROLLBACK TO SAVEPOINT ${token.name.quotedIdentifier()}")
            invalidateTokensAfter(token, includeToken = false)
        }

        override fun releaseSavepoint(savepoint: KronosSavepointToken) = withLock {
            val token = requireToken(savepoint)
            context.connection.exec("RELEASE SAVEPOINT ${token.name.quotedIdentifier()}")
            invalidateTokensAfter(token, includeToken = true)
        }

        private fun invalidateTokensAfter(token: SavepointToken, includeToken: Boolean) {
            val first = context.tokens.indexOf(token) + if (includeToken) 0 else 1
            context.tokens.drop(first).forEach { it.active = false }
            while (context.tokens.size > first) context.tokens.removeAt(context.tokens.lastIndex)
        }

        private fun requireToken(savepoint: KronosSavepointToken): SavepointToken {
            validate()
            val token = savepoint as? SavepointToken
                ?: error("Savepoint belongs to another SQLite transaction")
            check(token.context === context && token.scope === this && token.active) {
                "Savepoint belongs to another scope or has already been released"
            }
            return token
        }
    }

    private class SavepointToken(
        val name: String,
        val context: TransactionContext,
        val scope: KronosTransactionScope
    ) : KronosSavepointToken {
        var active = true
    }

    private companion object {
        const val SQLITE_INTEGER = 1
        const val SQLITE_FLOAT = 2
        const val SQLITE_TEXT = 3
        const val SQLITE_BLOB = 4
    }
}

private fun String.quotedIdentifier(): String = "\"${replace("\"", "\"\"")}\""

private fun KronosTransactionMode.sqlName(): String = when (this) {
    KronosTransactionMode.DEFAULT,
    KronosTransactionMode.DEFERRED -> "DEFERRED"
    KronosTransactionMode.IMMEDIATE -> "IMMEDIATE"
    KronosTransactionMode.EXCLUSIVE -> "EXCLUSIVE"
}

private fun SqliteConnection.exec(sql: String) {
    val statement = prepare(sql)
    try {
        statement.step()
    } finally {
        statement.close()
    }
}

private fun Any?.asLongOrNull(): Long? = when (this) {
    is Byte -> toLong()
    is Short -> toLong()
    is Int -> toLong()
    is Long -> this
    is Number -> toLong()
    else -> toString().toLongOrNull()
}

/** SQLite-aware lexer: quoted strings/identifiers and comments never contain bind markers. */
internal fun String.replaceNamedParameters(
    parameters: Map<String, Any?>,
    parameterOrder: List<String>
): Pair<String, List<Any?>> {
    fun values(names: List<String>) = names.map {
        require(parameters.containsKey(it)) { "Missing SQLite parameter '$it'" }
        parameters[it]
    }
    if (parameterOrder.isNotEmpty()) return this to values(parameterOrder)
    val names = mutableListOf<String>()
    val output = StringBuilder(length)
    var index = 0
    var quote: Char? = null
    var lineComment = false
    var blockComment = false
    while (index < length) {
        val char = this[index]
        val next = getOrNull(index + 1)
        when {
            lineComment -> {
                output.append(char)
                if (char == '\n' || char == '\r') lineComment = false
                index++
            }
            blockComment -> {
                output.append(char)
                index++
                if (char == '*' && next == '/') {
                    output.append('/')
                    index++
                    blockComment = false
                }
            }
            quote != null -> {
                output.append(char)
                index++
                if (char == quote) {
                    if (next == quote && quote != ']') {
                        output.append(next)
                        index++
                    } else quote = null
                }
            }
            char == '-' && next == '-' -> {
                output.append("--")
                index += 2
                lineComment = true
            }
            char == '/' && next == '*' -> {
                output.append("/*")
                index += 2
                blockComment = true
            }
            char == '\'' || char == '"' || char == '`' || char == '[' -> {
                quote = if (char == '[') ']' else char
                output.append(char)
                index++
            }
            char == ':' && next != ':' && (index == 0 || this[index - 1] != ':') &&
                next != null && (next.isLetter() || next == '_') -> {
                var end = index + 2
                while (end < length && (this[end].isLetterOrDigit() || this[end] == '_')) end++
                names += substring(index + 1, end)
                output.append('?')
                index = end
            }
            else -> {
                output.append(char)
                index++
            }
        }
    }
    return output.toString() to values(names)
}
