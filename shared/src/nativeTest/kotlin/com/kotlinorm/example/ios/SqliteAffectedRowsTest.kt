package com.kotlinorm.example.ios

import com.kotlinorm.interfaces.KronosActionExecutionPlan
import com.kotlinorm.interfaces.KronosBatchExecutionPlan
import com.kotlinorm.interfaces.KronosPhysicalValue
import com.kotlinorm.interfaces.KronosQueryExecutionPlan
import kotlin.test.Test
import kotlin.test.assertEquals

class SqliteAffectedRowsTest {
    @Test
    fun schemaAndPragmaActionsNeverReturnAnEarlierMutationCount() {
        val source = KronosSqliteDataSource()
        fun action(sql: String) = source.execute(KronosActionExecutionPlan(sql)).affectedRows
        try {
            assertEquals(0, action("CREATE TABLE item(id INTEGER PRIMARY KEY, name TEXT)"))
            assertEquals(2, action("INSERT INTO item(name) VALUES('one'),('two')"))
            assertEquals(0, action("/* schema after DML */ CREATE TABLE another(id INTEGER)"))
            assertEquals(0, action("ALTER TABLE another ADD COLUMN name TEXT"))
            assertEquals(0, action("PRAGMA user_version=7"))
            assertEquals(0, action("PRAGMA user_version"))
            assertEquals(0, action("CREATE INDEX item_name ON item(name)"))
            assertEquals(0, action("UPDATE item SET name='missing' WHERE id=99"))
            assertEquals(1, action("WITH chosen AS (SELECT 1 AS id) UPDATE item SET name='changed' WHERE id IN (SELECT id FROM chosen)"))
            assertEquals(1, action("DELETE FROM item WHERE id=2"))
            assertEquals(0, action("DROP INDEX item_name"))
            assertEquals(0, action("DROP TABLE another"))

            assertEquals(0, action("PRAGMA foreign_keys=ON"))
            action("CREATE TABLE parent(id INTEGER PRIMARY KEY)")
            action("CREATE TABLE child(parent_id REFERENCES parent(id) ON DELETE CASCADE)")
            assertEquals(1, action("INSERT INTO parent VALUES(1)"))
            assertEquals(2, action("INSERT INTO child VALUES(1),(1)"))
            assertEquals(0, action("DROP TABLE parent"))
            assertEquals(listOf(KronosPhysicalValue.Integer(0)), source.toList(
                KronosQueryExecutionPlan("SELECT COUNT(*) FROM child")) { it.get(1) })
        } finally { source.close() }
    }

    @Test
    fun returningAndTriggerActionsFinishBeforeCountingOnlyOuterRows() {
        val source = KronosSqliteDataSource()
        fun action(sql: String) = source.execute(KronosActionExecutionPlan(sql)).affectedRows
        try {
            action("CREATE TABLE item(id INTEGER PRIMARY KEY, name TEXT)")
            action("CREATE TABLE audit(item_id INTEGER)")
            action("CREATE TRIGGER audit_item AFTER INSERT ON item BEGIN INSERT INTO audit VALUES(new.id); INSERT INTO audit VALUES(new.id); END")
            assertEquals(2, action("INSERT INTO item(name) VALUES('one'),('two') RETURNING id"))
            assertEquals(listOf(KronosPhysicalValue.Integer(4)), source.toList(
                KronosQueryExecutionPlan("SELECT COUNT(*) FROM audit")) { it.get(1) })
            val batched = source.executeBatch(KronosBatchExecutionPlan(
                "INSERT INTO item(name) VALUES(:name) RETURNING id",
                listOf(mapOf("name" to "three"), mapOf("name" to "four"))
            ))
            assertEquals(listOf(1, 1), batched.map { it.affectedRows })
            assertEquals(listOf(KronosPhysicalValue.Integer(4)), source.toList(
                KronosQueryExecutionPlan("SELECT COUNT(*) FROM item")) { it.get(1) })
            assertEquals(0, action("SELECT * FROM item"))
        } finally { source.close() }
    }
}
