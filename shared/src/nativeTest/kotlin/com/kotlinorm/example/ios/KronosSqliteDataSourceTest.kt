package com.kotlinorm.example.ios

import com.kotlinorm.enums.KOperationType
import com.kotlinorm.interfaces.KronosActionExecutionPlan
import com.kotlinorm.interfaces.KronosBatchExecutionPlan
import com.kotlinorm.interfaces.KronosExecutionFirstResult
import com.kotlinorm.interfaces.KronosPhysicalValue
import com.kotlinorm.interfaces.KronosQueryExecutionPlan
import com.kotlinorm.interfaces.KronosTransactionMode
import com.kotlinorm.interfaces.KronosTransactionOptions
import kotlinx.cinterop.toKString
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KronosSqliteDataSourceTest {
    private val sources = mutableListOf<KronosSqliteDataSource>()

    private fun source() = KronosSqliteDataSource().also(sources::add)

    @AfterTest
    fun closeSources() {
        sources.forEach { it.close() }
        sources.clear()
    }

    @Test
    fun `crud row mapping and generated key stay on one connection`() {
        val dataSource = source()
        dataSource.execute(KronosActionExecutionPlan("""
            CREATE TABLE person (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)
        """.trimIndent(), operationType = KOperationType.CREATE))

        val inserted = dataSource.execute(
            KronosActionExecutionPlan(
                sql = "INSERT INTO person(name) VALUES (:name)",
                parameters = mapOf("name" to "Ada"),
                operationType = KOperationType.INSERT,
                generatedKey = com.kotlinorm.interfaces.KronosGeneratedKeyRequest("id")
            )
        )
        assertEquals(1, inserted.affectedRows)
        assertEquals(1L, inserted.lastInsertId)

        val rows = dataSource.toList(
            KronosQueryExecutionPlan("SELECT id, name FROM person ORDER BY id")
        ) { row ->
            val id = (row.get("id") as KronosPhysicalValue.Integer).value
            val name = (row.get("name") as KronosPhysicalValue.Text).value
            id to name
        }
        assertEquals(listOf(1L to "Ada"), rows)
    }

    @Test
    fun `batch and nested savepoint transaction preserve sqlite semantics`() {
        val dataSource = source()
        dataSource.execute(KronosActionExecutionPlan("CREATE TABLE item (id INTEGER PRIMARY KEY, name TEXT)"))

        val batch = dataSource.executeBatch(
            KronosBatchExecutionPlan(
                sql = "INSERT INTO item(id, name) VALUES (:id, :name)",
                entries = listOf(
                    mapOf("id" to 1, "name" to "one"),
                    mapOf("id" to 2, "name" to "two")
                ),
                operationType = KOperationType.INSERT
            )
        )
        assertEquals(listOf(1, 1), batch.map { it.affectedRows })

        dataSource.transact(KronosTransactionOptions(KronosTransactionMode.IMMEDIATE)) {
            val token = savepoint("remove-two")
            dataSource.execute(
                KronosActionExecutionPlan(
                    sql = "INSERT INTO item(id, name) VALUES (3, 'three')",
                    operationType = KOperationType.INSERT
                )
            )
            rollbackToSavepoint(token)
            releaseSavepoint(token)
        }

        val count = when (val result = dataSource.first(
            KronosQueryExecutionPlan("SELECT COUNT(*) AS count FROM item")
        ) { row -> row.get("count") }) {
            KronosExecutionFirstResult.Empty -> error("count query returned no row")
            is KronosExecutionFirstResult.Present -> (result.value as KronosPhysicalValue.Integer).value
        }
        assertEquals(2L, count)
    }

    @Test
    fun `explicit close requires reopen and resets memory database`() {
        val dataSource = source()
        dataSource.execute(KronosActionExecutionPlan("CREATE TABLE value (id INTEGER)"))
        dataSource.close()

        assertFailsWith<IllegalStateException> {
            dataSource.execute(KronosActionExecutionPlan("SELECT 1"))
        }

        dataSource.reopen()
        assertTrue(
            dataSource.first(KronosQueryExecutionPlan("SELECT name FROM sqlite_master WHERE type = 'table'")) {
                it.get(1)
            }
                is KronosExecutionFirstResult.Empty
        )
    }

    @Test
    fun namedMemoryIdentityUsesUriAndReopensAsAnEmptyDatabase() {
        val name="file:kronos_named_${currentThreadIdentity()}?mode=memory&cache=private"
        val dataSource=KronosSqliteDataSource(databaseName=name)
        sources+=dataSource
        assertEquals(name,dataSource.identity.databaseName)
        dataSource.execute(KronosActionExecutionPlan("CREATE TABLE item(id INTEGER)"))
        dataSource.close()
        dataSource.open()
        assertEquals(KronosExecutionFirstResult.Empty,
            dataSource.first(KronosQueryExecutionPlan("SELECT name FROM sqlite_master WHERE type='table'")) { it.get(1) })
    }

    @Test
    fun openAndCloseAreIdempotent() {
        val dataSource = source()
        dataSource.open()
        dataSource.open()
        val row = dataSource.first(KronosQueryExecutionPlan("SELECT 42")) { it.get(1) }
        assertEquals(KronosExecutionFirstResult.Present(KronosPhysicalValue.Integer(42)), row)
        dataSource.close()
        dataSource.close()
    }

    @Test
    fun mapperRowsExpireAndKeepTheirOrdinal() {
        val dataSource = source()
        val retained = mutableListOf<com.kotlinorm.interfaces.KronosPhysicalRow>()
        val rows = dataSource.toList(KronosQueryExecutionPlan("SELECT 1 AS id UNION ALL SELECT 2 AS id")) {
            retained += it
            it.rowNumber to it.get("id")
        }
        assertEquals(listOf(0 to KronosPhysicalValue.Integer(1), 1 to KronosPhysicalValue.Integer(2)), rows)
        retained.forEach { row ->
            assertEquals(false, row.isActive)
            assertFailsWith<IllegalStateException> { row.get(1) }
            assertFailsWith<IllegalStateException> { row.rowNumber }
        }
    }

    @Test
    fun firstPreservesNullableResults() {
        val dataSource = source()
        assertEquals(
            KronosExecutionFirstResult.Present(null),
            dataSource.first(KronosQueryExecutionPlan("SELECT NULL")) { null }
        )
        assertEquals(
            KronosExecutionFirstResult.Empty,
            dataSource.first(KronosQueryExecutionPlan("SELECT 1 WHERE 0")) { it.get(1) }
        )
    }

    @Test
    fun physicalCodecValuesBindThroughTheRealDriver() {
        val dataSource = source()
        val actual = dataSource.first(
            KronosQueryExecutionPlan(
                "SELECT :n, :s, :b, :z",
                mapOf(
                    "n" to KronosPhysicalValue.Integer(7),
                    "s" to KronosPhysicalValue.Text("Kronos"),
                    "b" to KronosPhysicalValue.Blob(byteArrayOf(1, 2)),
                    "z" to KronosPhysicalValue.Null
                )
            )
        ) { row -> (1..row.columnCount).map(row::get) }
        assertEquals(
            KronosExecutionFirstResult.Present(
                listOf(
                    KronosPhysicalValue.Integer(7),
                    KronosPhysicalValue.Text("Kronos"),
                    KronosPhysicalValue.Blob(byteArrayOf(1, 2)),
                    KronosPhysicalValue.Null
                )
            ),
            actual
        )
    }

    @Test
    fun failuresRollbackTheOwnedConnection() {
        val dataSource = source()
        dataSource.execute(KronosActionExecutionPlan("CREATE TABLE rollback_test(id INTEGER)"))
        for (mode in KronosTransactionMode.entries) {
            assertFailsWith<IllegalStateException>(mode.name) {
                dataSource.transact(KronosTransactionOptions(mode)) {
                    dataSource.execute(KronosActionExecutionPlan("INSERT INTO rollback_test VALUES (1)"))
                    error("rollback")
                }
            }
            assertEquals(
                KronosExecutionFirstResult.Present(KronosPhysicalValue.Integer(0)),
                dataSource.first(KronosQueryExecutionPlan("SELECT COUNT(*) FROM rollback_test")) { it.get(1) },
                mode.name
            )
        }
    }

    @Test
    fun ignoredInsertDoesNotReturnAnEarlierGeneratedId() {
        val dataSource=source()
        dataSource.execute(KronosActionExecutionPlan("CREATE TABLE item(id INTEGER PRIMARY KEY,name TEXT UNIQUE)"))
        val plan=KronosActionExecutionPlan(
            "INSERT OR IGNORE INTO item(name) VALUES(:name)",mapOf("name" to "same"),
            operationType=KOperationType.INSERT,
            generatedKey=com.kotlinorm.interfaces.KronosGeneratedKeyRequest("id")
        )
        assertEquals(1L,dataSource.execute(plan).lastInsertId)
        val ignored=dataSource.execute(plan)
        assertEquals(0,ignored.affectedRows)
        assertEquals(emptyList(),ignored.generatedKeys)
        assertEquals(null,ignored.lastInsertId)
        assertFailsWith<IllegalArgumentException> {
            dataSource.execute(plan.copy(operationType=KOperationType.UPSERT))
        }
    }

    @Test
    fun missingAndUnsupportedBindingsFailExplicitly() {
        val dataSource = source()
        assertFailsWith<IllegalArgumentException> {
            dataSource.first(KronosQueryExecutionPlan("SELECT :missing")) { it.get(1) }
        }
        assertFailsWith<IllegalStateException> {
            dataSource.first(KronosQueryExecutionPlan("SELECT :value", mapOf("value" to listOf(1)))) { it.get(1) }
        }
        assertFailsWith<IllegalArgumentException> {
            dataSource.transact(KronosTransactionOptions(timeoutSeconds = 1)) { error("must not enter") }
        }
        assertEquals(emptyList(), dataSource.executeBatch(KronosBatchExecutionPlan("not executed")))
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class,
    kotlin.native.concurrent.ObsoleteWorkersApi::class)
class SqliteLifecycleAndSemanticsTest {
    @Test
    fun transactionScopesAndTokensExpire() {
        val source = KronosSqliteDataSource()
        try {
            var escaped: com.kotlinorm.interfaces.KronosTransactionScope? = null
            var token: com.kotlinorm.interfaces.KronosSavepointToken? = null
            source.transact {
                escaped = this
                token = savepoint("same name")
                val nested = savepoint("same name")
                releaseSavepoint(token!!)
                assertFailsWith<IllegalStateException> { releaseSavepoint(nested) }
            }
            assertFailsWith<IllegalStateException> { escaped!!.savepoint() }
            source.transact {
                assertFailsWith<IllegalStateException> { releaseSavepoint(token!!) }
            }
        } finally { source.close() }
    }

    @Test
    fun escapedNestedScopesCannotAffectTheirOuterTransaction() {
        val source = KronosSqliteDataSource()
        try {
            var escaped: com.kotlinorm.interfaces.KronosTransactionScope? = null
            source.transact {
                source.transact { escaped = this }
                assertFailsWith<IllegalStateException> { escaped!!.savepoint() }
                savepoint().also { releaseSavepoint(it) }
            }
        } finally { source.close() }
    }

    @Test
    fun nestedFailureRollsBackOnlyTheNestedBlock() {
        val source = KronosSqliteDataSource()
        try {
            source.execute(KronosActionExecutionPlan("CREATE TABLE item(id INTEGER PRIMARY KEY)"))
            source.transact {
                source.execute(KronosActionExecutionPlan("INSERT INTO item VALUES(1)"))
                assertFailsWith<IllegalArgumentException> {
                    source.transact {
                        source.execute(KronosActionExecutionPlan("INSERT INTO item VALUES(2)"))
                        throw IllegalArgumentException("nested application failure")
                    }
                }
                source.execute(KronosActionExecutionPlan("INSERT INTO item VALUES(3)"))
            }
            assertEquals(listOf(KronosPhysicalValue.Integer(1), KronosPhysicalValue.Integer(3)), query(source,"SELECT id FROM item ORDER BY id"))
        } finally { source.close() }
    }

    @Test
    fun foreignThreadFailsDuringActiveTransactionWithoutDeadlock() {
        val source = KronosSqliteDataSource()
        val worker = kotlin.native.concurrent.Worker.start()
        try {
            source.transact {
                val result = worker.execute(kotlin.native.concurrent.TransferMode.SAFE, { source }) {
                    try {
                        it.first(KronosQueryExecutionPlan("SELECT 1")) { row -> row.get(1) }
                        "unexpected success"
                    } catch (cause: IllegalStateException) { cause.message }
                }.result
                assertEquals("SQLite transaction belongs to another thread", result)
            }
        } finally {
            worker.requestTermination().result
            source.close()
        }
    }

    @Test
    fun concurrentGeneratedKeysIdentifyTheirOwnInsertedRows() {
        val source = KronosSqliteDataSource()
        val workers = List(3) { kotlin.native.concurrent.Worker.start() }
        try {
            source.execute(KronosActionExecutionPlan("CREATE TABLE item(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)"))
            val futures = workers.mapIndexed { index, worker ->
                worker.execute(kotlin.native.concurrent.TransferMode.SAFE, { source to index }) { (db, owner) ->
                    List(8) { ordinal ->
                        val name = "$owner-$ordinal"
                        val id = db.execute(KronosActionExecutionPlan(
                            "INSERT INTO item(name) VALUES(:name)", mapOf("name" to name),
                            operationType = KOperationType.INSERT,
                            generatedKey = com.kotlinorm.interfaces.KronosGeneratedKeyRequest("id")
                        )).lastInsertId!!
                        id to name
                    }
                }
            }
            val inserted = futures.flatMap { it.result }.sortedBy { it.first }
            val persisted = source.toList(KronosQueryExecutionPlan("SELECT id,name FROM item ORDER BY id")) {
                (it.get(1) as KronosPhysicalValue.Integer).value to (it.get(2) as KronosPhysicalValue.Text).value
            }
            assertEquals(24, inserted.map { it.first }.distinct().size)
            assertEquals(inserted, persisted)
        } finally {
            workers.forEach { it.requestTermination().result }
            source.close()
        }
    }

    @Test
    fun lexerPreservesQuotedIdentifiersEscapesAndComments() {
        val sql = "SELECT ':ignored', 'it''s :ignored', :value AS `:column`, :value AS [a:b] /* :ignored */ -- :ignored\n"
        assertEquals(
            "SELECT ':ignored', 'it''s :ignored', ? AS `:column`, ? AS [a:b] /* :ignored */ -- :ignored\n" to listOf(7,7),
            sql.replaceNamedParameters(mapOf("value" to 7), emptyList())
        )
        val source = KronosSqliteDataSource()
        try {
            assertEquals(
                listOf(KronosPhysicalValue.Integer(7)),
                source.toList(KronosQueryExecutionPlan("SELECT :value /* :ignored */ -- :ignored\n", mapOf("value" to 7))) { it.get(1) }
            )
        } finally { source.close() }
    }

    @Test
    fun duplicateLabelsRequirePositionAndPhysicalValuesRemainExact() {
        val source = KronosSqliteDataSource()
        try {
            val rows = source.toList(KronosQueryExecutionPlan("SELECT NULL AS value, 3.25 AS value, X'' AS blob")) {
                assertFailsWith<IllegalArgumentException> { it.get("value") }
                (1..3).map(it::get)
            }
            assertEquals(listOf(listOf(KronosPhysicalValue.Null,KronosPhysicalValue.Real(3.25),KronosPhysicalValue.Blob(byteArrayOf()))),rows)
            assertEquals(KronosSqliteDataSource.State.OPEN, source.state)
        } finally { source.close() }
    }

    @Test
    fun mapperExceptionsDoNotInvalidateTheConnection() {
        val source = KronosSqliteDataSource()
        try {
            assertFailsWith<IllegalArgumentException> {
                source.first(KronosQueryExecutionPlan("SELECT 1")) { throw IllegalArgumentException("mapper") }
            }
            assertEquals(listOf(KronosPhysicalValue.Integer(2)), query(source,"SELECT 2"))
        } finally { source.close() }
    }

    @Test
    fun driverFailureRequiresExplicitRecovery() {
        val driver = RecordingDriver()
        val source = KronosSqliteDataSource(driver)
        try {
            source.open()
            driver.failNextPrepare = true
            assertFailsWith<SqliteDriverFailure> { query(source, "SELECT 1") }
            assertEquals(KronosSqliteDataSource.State.FAILED, source.state)
            assertFailsWith<IllegalStateException> { query(source,"SELECT 1") }
            assertFailsWith<IllegalStateException> { source.open() }
            source.reopen()
            assertEquals(listOf(KronosPhysicalValue.Integer(1)), query(source,"SELECT 1"))
        } finally { source.close() }
    }

    @Test
    fun statementErrorsPreserveTheConnectionAndExistingMemoryData() {
        val source = KronosSqliteDataSource()
        try {
            source.execute(KronosActionExecutionPlan("CREATE TABLE item(id INTEGER PRIMARY KEY)"))
            source.execute(KronosActionExecutionPlan("INSERT INTO item VALUES(1)"))
            val syntax = assertFailsWith<SqliteDriverFailure> {
                source.execute(KronosActionExecutionPlan("INSERT INTO missing_table VALUES(2)"))
            }
            assertEquals(false, syntax.connectionInvalid)
            val constraint = assertFailsWith<SqliteDriverFailure> {
                source.execute(KronosActionExecutionPlan("INSERT INTO item VALUES(1)"))
            }
            assertEquals(false, constraint.connectionInvalid)
            assertEquals(KronosSqliteDataSource.State.OPEN, source.state)
            assertEquals(listOf(KronosPhysicalValue.Integer(1)), query(source, "SELECT id FROM item"))
            source.execute(KronosActionExecutionPlan("INSERT INTO item VALUES(2)"))
            assertEquals(listOf(KronosPhysicalValue.Integer(1), KronosPhysicalValue.Integer(2)),
                query(source, "SELECT id FROM item ORDER BY id"))
        } finally { source.close() }
    }

    @Test
    fun caughtStatementErrorsCannotCommitOrEscapeTheOwnedTransaction() {
        for (conflict in listOf("", "OR ROLLBACK")) for (nested in listOf(false, true)) {
            val source = KronosSqliteDataSource()
            try {
                source.execute(KronosActionExecutionPlan("CREATE TABLE item(id INTEGER PRIMARY KEY)"))
                source.execute(KronosActionExecutionPlan("INSERT INTO item VALUES(1)"))
                assertFailsWith<IllegalStateException> {
                    source.transact {
                        source.execute(KronosActionExecutionPlan("INSERT INTO item VALUES(2)"))
                        fun failStatement() {
                            assertFailsWith<SqliteDriverFailure> {
                                source.execute(KronosActionExecutionPlan("INSERT $conflict INTO item VALUES(1)"))
                            }
                        }
                        if (nested) {
                            assertFailsWith<IllegalStateException> { source.transact { failStatement() } }
                        } else failStatement()
                        assertFailsWith<IllegalStateException> {
                            source.execute(KronosActionExecutionPlan("INSERT INTO item VALUES(3)"))
                        }
                    }
                }
                assertEquals(KronosSqliteDataSource.State.OPEN, source.state)
                assertEquals(listOf(KronosPhysicalValue.Integer(1)), query(source, "SELECT id FROM item"))
            } finally { source.close() }
        }
    }

    @Test
    fun reopenCannotDestroyAnActiveOperation() {
        val source = KronosSqliteDataSource()
        try {
            source.first(KronosQueryExecutionPlan("SELECT 1")) {
                assertFailsWith<IllegalStateException> { source.reopen() }
                it.get(1)
            }
            source.transact {
                assertFailsWith<IllegalStateException> { source.reopen() }
            }
            assertEquals(KronosSqliteDataSource.State.OPEN, source.state)
        } finally { source.close() }
    }

    @Test
    fun batchesReturnPerRowCountsWithoutGeneratedKeyPromises() {
        val source = KronosSqliteDataSource()
        try {
            source.execute(KronosActionExecutionPlan("CREATE TABLE item(id INTEGER PRIMARY KEY, name TEXT)"))
            val results = source.executeBatch(KronosBatchExecutionPlan(
                "INSERT INTO item(name) VALUES(:name)",
                listOf(mapOf("name" to "one"),mapOf("name" to "two")),
                generatedKey = com.kotlinorm.interfaces.KronosGeneratedKeyRequest("id")
            ))
            assertEquals(listOf(1,1),results.map { it.affectedRows })
            assertEquals(listOf(emptyList(),emptyList()),results.map { it.generatedKeys })
            assertEquals(listOf(null,null),results.map { it.lastInsertId })
            val noCapture = source.execute(KronosActionExecutionPlan(
                "INSERT INTO item(name) VALUES('three')",generatedKey = com.kotlinorm.interfaces.KronosGeneratedKeyRequest("id",capture=false)
            ))
            assertEquals(null,noCapture.lastInsertId)
            assertEquals(emptyList(),noCapture.generatedKeys)
        } finally { source.close() }
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
class SqliteFileAndSchemaTest {
    @Test
    fun fileDatabasePersistsAcrossReopenAndSchemaRebuild() {
        val path = testDatabasePath("kronos-example")
        val source = KronosSqliteDataSource(databaseName=path)
        try {
            source.execute(KronosActionExecutionPlan("PRAGMA journal_mode=WAL"))
            assertEquals(
                KronosExecutionFirstResult.Present(KronosPhysicalValue.Text("wal")),
                source.first(KronosQueryExecutionPlan("PRAGMA journal_mode")) { it.get(1) }
            )
            source.execute(KronosActionExecutionPlan("PRAGMA foreign_keys=ON"))
            source.execute(KronosActionExecutionPlan("CREATE TABLE item(id INTEGER PRIMARY KEY, name TEXT)"))
            source.execute(KronosActionExecutionPlan("CREATE UNIQUE INDEX item_name ON item(name)"))
            source.execute(KronosActionExecutionPlan("INSERT INTO item VALUES(7,'persistent')"))
            source.close()
            source.open()
            assertEquals(
                KronosExecutionFirstResult.Present(KronosPhysicalValue.Text("persistent")),
                source.first(KronosQueryExecutionPlan("SELECT name FROM item WHERE id=7")) { it.get(1) }
            )
            // SQLite's schema-sync strategy rebuilds a table in one transaction.
            source.transact {
                source.execute(KronosActionExecutionPlan("CREATE TABLE item_new(id INTEGER PRIMARY KEY, name TEXT, note TEXT)"))
                source.execute(KronosActionExecutionPlan("INSERT INTO item_new(id,name) SELECT id,name FROM item"))
                source.execute(KronosActionExecutionPlan("DROP TABLE item"))
                source.execute(KronosActionExecutionPlan("ALTER TABLE item_new RENAME TO item"))
                source.execute(KronosActionExecutionPlan("CREATE UNIQUE INDEX item_name ON item(name)"))
            }
            assertEquals(
                KronosExecutionFirstResult.Present(KronosPhysicalValue.Null),
                source.first(KronosQueryExecutionPlan("SELECT note FROM item WHERE id=7")) { it.get(1) }
            )
            assertEquals(
                KronosExecutionFirstResult.Present(KronosPhysicalValue.Text("item_name")),
                source.first(KronosQueryExecutionPlan("SELECT name FROM sqlite_master WHERE type='index' AND name='item_name'")) { it.get(1) }
            )
        } finally {
            source.close()
            listOf(path,"$path-wal","$path-shm","$path-journal").forEach { platform.posix.remove(it) }
        }
    }

    @Test
    fun foreignKeyViolationsAreReportedByTheRealDriver() {
        val source=KronosSqliteDataSource()
        try {
            source.execute(KronosActionExecutionPlan("PRAGMA foreign_keys=ON"))
            source.execute(KronosActionExecutionPlan("CREATE TABLE parent(id INTEGER PRIMARY KEY)"))
            source.execute(KronosActionExecutionPlan("CREATE TABLE child(id INTEGER PRIMARY KEY,parent_id INTEGER REFERENCES parent(id))"))
            source.transact {
                source.execute(KronosActionExecutionPlan("INSERT INTO parent VALUES(1)"))
                source.execute(KronosActionExecutionPlan("INSERT INTO child VALUES(1,1)"))
            }
            assertFailsWith<SqliteDriverFailure> {
                source.execute(KronosActionExecutionPlan("INSERT INTO child VALUES(2,999)"))
            }
            assertEquals(KronosSqliteDataSource.State.OPEN,source.state)
            assertEquals(listOf(KronosPhysicalValue.Integer(1)), query(source, "SELECT id FROM parent"))
            source.execute(KronosActionExecutionPlan("INSERT INTO child VALUES(2,1)"))
        } finally { source.close() }
    }

    @Test
    fun closeDuringTransactionRollsBackAndExpiresItsScope() {
        val path=testDatabasePath("kronos-close")
        val source=KronosSqliteDataSource(databaseName=path)
        try {
            source.execute(KronosActionExecutionPlan("CREATE TABLE item(id INTEGER)"))
            var escaped: com.kotlinorm.interfaces.KronosTransactionScope? = null
            assertFailsWith<IllegalStateException> {
                source.transact {
                    escaped=this
                    source.execute(KronosActionExecutionPlan("INSERT INTO item VALUES(1)"))
                    source.close()
                    assertFailsWith<IllegalStateException> { savepoint() }
                    assertFailsWith<IllegalStateException> { source.open() }
                }
            }
            assertEquals(KronosSqliteDataSource.State.CLOSED,source.state)
            source.open()
            assertEquals(KronosExecutionFirstResult.Present(KronosPhysicalValue.Integer(0)),
                source.first(KronosQueryExecutionPlan("SELECT COUNT(*) FROM item")) { it.get(1) })
            assertFailsWith<IllegalStateException> { escaped!!.savepoint() }
        } finally {
            source.close()
            listOf(path,"$path-journal","$path-wal","$path-shm").forEach { platform.posix.remove(it) }
        }
    }

    @Test
    fun textBindingsPreserveUnicodeAndEmbeddedNul() {
        val source = KronosSqliteDataSource()
        try {
            val text = "Kronos\u0000数据库🚀"
            assertEquals(
                KronosExecutionFirstResult.Present(KronosPhysicalValue.Text(text)),
                source.first(KronosQueryExecutionPlan("SELECT :text",mapOf("text" to text))) { it.get(1) }
            )
        } finally { source.close() }
    }
}

class SqliteDriverOwnershipTest {
    @Test
    fun firstStepsOnceAndClosesStatementEvenWhenMapperThrows() {
        val driver = RecordingDriver()
        val source = KronosSqliteDataSource(driver)
        try {
            source.open()
            source.open()
            assertEquals(1,driver.opens)
            assertEquals(
                KronosExecutionFirstResult.Present(KronosPhysicalValue.Integer(1)),
                source.first(KronosQueryExecutionPlan("SELECT 1 UNION ALL SELECT 2")) { it.get(1) }
            )
            assertEquals(1,driver.steps)
            assertEquals(1,driver.statementCloses)
            assertFailsWith<IllegalArgumentException> {
                source.first(KronosQueryExecutionPlan("SELECT 1")) { throw IllegalArgumentException("mapper") }
            }
            assertEquals(2,driver.statementCloses)
            source.close()
            source.close()
            assertEquals(1,driver.connectionCloses)
        } finally { source.close() }
    }

    @Test
    fun closeDuringToListDoesNotAdvanceTheClosedCursor() {
        val driver=RecordingDriver()
        val source=KronosSqliteDataSource(driver)
        try {
            assertFailsWith<IllegalStateException> {
                source.toList(KronosQueryExecutionPlan("SELECT 1 UNION ALL SELECT 2")) { source.close() }
            }
            assertEquals(1,driver.steps)
            assertEquals(1,driver.statementCloses)
            assertEquals(KronosSqliteDataSource.State.CLOSED,source.state)
        } finally { source.close() }
    }

    @Test
    fun closeDuringMapperExpiresTheRowAndClosesOwnedResourcesOnce() {
        val driver=RecordingDriver()
        val source=KronosSqliteDataSource(driver)
        try {
            assertFailsWith<IllegalStateException> {
                source.first(KronosQueryExecutionPlan("SELECT 1")) { row ->
                    source.close()
                    assertEquals(false,row.isActive)
                    assertFailsWith<IllegalStateException> { row.get(1) }
                    assertFailsWith<IllegalStateException> { source.open() }
                    assertFailsWith<IllegalStateException> { source.reopen() }
                }
            }
            assertEquals(KronosSqliteDataSource.State.CLOSED,source.state)
            assertEquals(1,driver.statementCloses)
            assertEquals(1,driver.connectionCloses)
            source.open()
            assertEquals(KronosExecutionFirstResult.Present(KronosPhysicalValue.Integer(2)),
                source.first(KronosQueryExecutionPlan("SELECT 2")) { it.get(1) })
        } finally { source.close() }
    }

    @Test
    fun initialOpenFailureIsObservableAndCanRecoverExplicitly() {
        val driver = RecordingDriver().also { it.failOpen=true }
        val source = KronosSqliteDataSource(driver)
        try {
            assertFailsWith<SqliteDriverFailure> { source.open() }
            assertEquals(KronosSqliteDataSource.State.FAILED,source.state)
            driver.failOpen=false
            assertFailsWith<IllegalStateException> { source.open() }
            source.reopen()
            assertEquals(
                KronosExecutionFirstResult.Present(KronosPhysicalValue.Integer(42)),
                source.first(KronosQueryExecutionPlan("SELECT 42")) { it.get(1) }
            )
        } finally { source.close() }
    }
}

private fun query(source: KronosSqliteDataSource, sql: String): List<KronosPhysicalValue> =
    source.toList(KronosQueryExecutionPlan(sql)) { it.get(1) }

private class RecordingDriver : SqliteDriver {
    private val real = defaultSqliteDriver()
    override val name = "recording-${real.name}"
    var opens=0
    var connectionCloses=0
    var statementCloses=0
    var steps=0
    var failOpen=false
    var failNextPrepare=false
    override fun open(path: String): SqliteConnection {
        if (failOpen) throw SqliteDriverFailure("injected open failure")
        opens++
        val connection=real.open(path)
        return object : SqliteConnection by connection {
            override fun close() { connectionCloses++; connection.close() }
            override fun prepare(sql: String): SqliteStatement {
                if (failNextPrepare) {
                    failNextPrepare = false
                    throw SqliteDriverFailure("injected connection failure")
                }
                val statement=connection.prepare(sql)
                return object : SqliteStatement by statement {
                    override fun step(): Boolean { steps++; return statement.step() }
                    override fun close() { statementCloses++; statement.close() }
                }
            }
        }
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun testDatabasePath(label: String): String {
    val directory=platform.posix.getenv("TMPDIR")?.toKString()
        ?: platform.posix.getenv("TEMP")?.toKString()
        ?: platform.posix.getenv("TMP")?.toKString()
        ?: "/tmp"
    return "${directory.trimEnd('/', '\\')}/$label-${currentThreadIdentity()}.sqlite"
}
