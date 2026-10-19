package com.kotlinorm.example.ios


data class BundledSqliteProbeResult(
    val id: Long,
    val name: String,
)

class BundledSqliteProbe(
    private val driver: SqliteDriver = defaultSqliteDriver(),
) {
    fun insertAndRead(name: String): BundledSqliteProbeResult {
        val connection = driver.open(":memory:")
        try {
            connection.execute("CREATE TABLE probe (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)")

            val insert = connection.prepare("INSERT INTO probe(name) VALUES (?)")
            try {
                insert.bindText(1, name)
                insert.step()
            } finally {
                insert.close()
            }

            val id = connection.singleLong("SELECT last_insert_rowid()")
            val query = connection.prepare("SELECT name FROM probe WHERE id = ?")
            try {
                query.bindLong(1, id)
                check(query.step()) { "Inserted row was not readable" }
                return BundledSqliteProbeResult(id = id, name = query.getText(0))
            } finally {
                query.close()
            }
        } finally {
            connection.close()
        }
    }
}

private fun SqliteConnection.execute(sql: String) {
    val statement = prepare(sql)
    try {
        statement.step()
    } finally {
        statement.close()
    }
}

private fun SqliteConnection.singleLong(sql: String): Long {
    val statement: SqliteStatement = prepare(sql)
    try {
        check(statement.step()) { "Expected one row for $sql" }
        return statement.getLong(0)
    } finally {
        statement.close()
    }
}
