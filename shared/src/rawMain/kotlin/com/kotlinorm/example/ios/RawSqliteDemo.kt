package com.kotlinorm.example.ios

import com.kotlinorm.enums.KOperationType
import com.kotlinorm.interfaces.*

/** Entry used by the SwiftUI app and desktop binary; always opens a real SQLite connection. */
internal fun runSqliteDemo(name: String): String {
    val database = KronosSqliteDataSource()
    try {
        database.execute(KronosActionExecutionPlan(
            "CREATE TABLE person(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)"
        ))
        val inserted = database.transact(KronosTransactionOptions(KronosTransactionMode.IMMEDIATE)) {
            database.execute(KronosActionExecutionPlan(
                sql = "INSERT INTO person(name) VALUES (:name)",
                parameters = mapOf("name" to name),
                operationType = KOperationType.INSERT,
                generatedKey = KronosGeneratedKeyRequest("id")
            ))
        }
        val row = database.first(KronosQueryExecutionPlan(
            "SELECT name FROM person WHERE id = :id", mapOf("id" to inserted.lastInsertId)
        )) { (it.get("name") as KronosPhysicalValue.Text).value }
        val persisted = (row as KronosExecutionFirstResult.Present).value
        check(persisted == name)
        return "SQLite connected: id=${inserted.lastInsertId}, name=$persisted"
    } finally { database.close() }
}
