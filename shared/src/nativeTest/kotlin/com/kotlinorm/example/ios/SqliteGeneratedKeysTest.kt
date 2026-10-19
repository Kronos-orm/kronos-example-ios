package com.kotlinorm.example.ios

import com.kotlinorm.enums.KOperationType
import com.kotlinorm.interfaces.KronosActionExecutionPlan
import com.kotlinorm.interfaces.KronosGeneratedKeyRequest
import com.kotlinorm.interfaces.KronosPhysicalValue
import com.kotlinorm.interfaces.KronosQueryExecutionPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqliteGeneratedKeysTest {
    private fun KronosSqliteDataSource.action(sql: String) = execute(KronosActionExecutionPlan(sql))
    private fun insert(sql: String, field: String = "id") = KronosActionExecutionPlan(
        sql, operationType = KOperationType.INSERT, generatedKey = KronosGeneratedKeyRequest(field)
    )
    private fun KronosSqliteDataSource.integers(sql: String) = toList(KronosQueryExecutionPlan(sql)) {
        (it.get(1) as KronosPhysicalValue.Integer).value
    }

    @Test
    fun triggerInsertReturnsTheOuterRowIdentity() {
        val source = KronosSqliteDataSource()
        try {
            source.action("CREATE TABLE person(id INTEGER PRIMARY KEY, name TEXT)")
            source.action("CREATE TABLE audit(id INTEGER PRIMARY KEY, person_id INTEGER)")
            source.action("INSERT INTO audit(id) VALUES(100)")
            source.action("CREATE TRIGGER audit_person AFTER INSERT ON person BEGIN INSERT INTO audit(person_id) VALUES(new.id); END")
            val result = source.execute(insert("INSERT INTO person(name) VALUES('outer')"))
            assertEquals(1, result.affectedRows)
            assertEquals(1L, result.lastInsertId)
            assertEquals(listOf(1L), result.generatedKeys)
            assertEquals(listOf(100L, 101L), source.integers("SELECT id FROM audit ORDER BY id"))
            assertEquals(listOf(1L), source.integers("SELECT person_id FROM audit WHERE person_id IS NOT NULL"))
        } finally { source.close() }
    }

    @Test
    fun withoutRowidCompoundNonIntegerAndDescendingKeysRejectCaptureBeforeInsert() {
        val source = KronosSqliteDataSource()
        try {
            source.action("CREATE TABLE earlier(id INTEGER PRIMARY KEY)")
            assertEquals(99L, source.execute(insert("INSERT INTO earlier(id) VALUES(99)")).lastInsertId)
            val cases = listOf(
                "without_rowid" to "id INTEGER PRIMARY KEY) WITHOUT ROWID",
                "compound" to "id INTEGER, other INTEGER, PRIMARY KEY(id,other))",
                "not_integer" to "id INT PRIMARY KEY)",
                "descending" to "id INTEGER PRIMARY KEY DESC)"
            )
            for ((table, definition) in cases) {
                source.action("CREATE TABLE $table($definition")
                val values = if (table == "compound") "7,8" else "7"
                assertFailsWith<IllegalArgumentException>(table) {
                    source.execute(insert("INSERT INTO $table VALUES($values)"))
                }
                assertEquals(listOf(0L), source.integers("SELECT COUNT(*) FROM $table"), table)
                val unrequested = source.execute(insert("INSERT INTO $table VALUES($values)").copy(generatedKey = null))
                assertEquals(1, unrequested.affectedRows)
                assertEquals(emptyList(), unrequested.generatedKeys)
                assertEquals(null, unrequested.lastInsertId)
            }
            assertEquals(KronosSqliteDataSource.State.OPEN, source.state)
        } finally { source.close() }
    }

    @Test
    fun quotedSchemaAndIntegerPrimaryKeyTableConstraintCaptureTheRequestedAlias() {
        val source = KronosSqliteDataSource()
        try {
            source.action("CREATE TABLE \"odd table\"(\"odd id\" INTEGER, name TEXT, PRIMARY KEY(\"odd id\" DESC))")
            val result = source.execute(insert("/* INSERT ignored */ INSERT INTO main.\"odd table\"(name) VALUES('DO UPDATE RETURNING')", "odd id"))
            assertEquals(1L, result.lastInsertId)
            assertFailsWith<IllegalArgumentException> {
                source.execute(insert("INSERT INTO main.\"odd table\"(name) VALUES('wrong column')", "name"))
            }
            source.action("CREATE TEMP TABLE \"odd table\"(\"odd id\" INT PRIMARY KEY, name TEXT)")
            assertFailsWith<IllegalArgumentException> {
                source.execute(insert("INSERT INTO \"odd table\" VALUES(8,'temp')", "odd id"))
            }
            assertEquals(listOf(1L), source.integers("SELECT \"odd id\" FROM main.\"odd table\""))
            source.action("CREATE TABLE \"Ä\"(id INTEGER PRIMARY KEY)")
            source.action("CREATE TABLE \"ä\"(id INTEGER PRIMARY KEY) WITHOUT ROWID")
            assertFailsWith<IllegalArgumentException> {
                source.execute(insert("INSERT INTO \"ä\" VALUES(7)"))
            }
            assertEquals(7L, source.execute(insert("INSERT INTO \"Ä\" VALUES(7)")).lastInsertId)
        } finally { source.close() }
    }

    @Test
    fun conflictUpdateCannotMasqueradeAsAnInsertGeneratedKey() {
        val source = KronosSqliteDataSource()
        try {
            source.action("CREATE TABLE item(id INTEGER PRIMARY KEY,name TEXT UNIQUE)")
            assertEquals(1L, source.execute(insert("INSERT INTO item(name) VALUES('same')")).lastInsertId)
            assertFailsWith<IllegalArgumentException> {
                source.execute(insert("INSERT INTO item(name) VALUES('same') ON CONFLICT(name) DO UPDATE SET name=excluded.name"))
            }
            assertFailsWith<IllegalArgumentException> {
                source.execute(insert("INSERT INTO item(name) VALUES('other') RETURNING id"))
            }
            assertEquals(listOf(1L), source.integers("SELECT id FROM item"))
        } finally { source.close() }
    }
}
