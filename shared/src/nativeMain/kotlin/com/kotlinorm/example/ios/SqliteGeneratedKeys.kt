package com.kotlinorm.example.ios

import com.kotlinorm.interfaces.KronosActionExecutionPlan
import com.kotlinorm.interfaces.KronosPhysicalValue
import com.kotlinorm.interfaces.KronosQueryExecutionPlan

/** Validate the requested column against SQLite's actual schema under the caller's connection lock. */
internal fun KronosSqliteDataSource.validateGeneratedKey(plan: KronosActionExecutionPlan) {
    val requested = plan.generatedKey?.takeIf { it.capture } ?: return
    val tokens = statementTokens(plan.sql)
    var offset = 0
    fun keyword(value: String): Boolean = tokens.getOrNull(offset)?.let { !it.quoted && it.value.equals(value, true) } == true
    require(keyword("INSERT")) { "Generated keys require a plain INSERT statement" }
    offset++
    if (keyword("OR")) offset += 2
    require(keyword("INTO")) { "Cannot determine the INSERT table for generated-key capture" }
    offset++
    fun identifier(): String {
        val token = tokens.getOrNull(offset++)
        require(token != null && (token.quoted || token.value.firstOrNull()?.let { it.isLetterOrDigit() || it == '_' } == true)) {
            "Cannot determine the INSERT table for generated-key capture"
        }
        return token.value
    }
    val firstName = identifier()
    val schemaName: String?
    val tableName: String
    if (tokens.getOrNull(offset)?.value == ".") {
        offset++
        schemaName = firstName
        tableName = identifier()
    } else {
        schemaName = null
        tableName = firstName
    }
    require(tokens.drop(offset).none { !it.quoted && it.value.equals("RETURNING", true) }) {
        "Generated-key capture cannot be combined with an explicit RETURNING clause"
    }
    require(tokens.drop(offset).windowed(2).none { pair ->
        pair.all { !it.quoted } && pair[0].value.equals("DO", true) && pair[1].value.equals("UPDATE", true)
    }) { "Generated keys for conflict-update/UPSERT statements are unsupported" }

    data class Table(val schema: String, val withoutRowId: Boolean)
    val tables = toList(KronosQueryExecutionPlan("PRAGMA table_list")) { row ->
        val name = (row.get("name") as KronosPhysicalValue.Text).value
        val schema = (row.get("schema") as KronosPhysicalValue.Text).value
        if (sqliteIdentifierEquals(name, tableName) && (schemaName == null || sqliteIdentifierEquals(schema, schemaName))) {
            Table(schema, (row.get("wr") as KronosPhysicalValue.Integer).value != 0L)
        } else null
    }.filterNotNull()
    val table = tables.firstOrNull { it.schema == "temp" }
        ?: tables.firstOrNull { it.schema == "main" }
        ?: tables.singleOrNull()
    require(table != null && !table.withoutRowId) {
        "Generated keys require an INTEGER PRIMARY KEY rowid alias; WITHOUT ROWID tables are unsupported"
    }
    fun quoted(identifier: String) = "\"${identifier.replace("\"", "\"\"")}\""
    val target = "${quoted(table.schema)}."
    data class Column(val name: String, val type: String, val primaryPosition: Long)
    val primary = toList(KronosQueryExecutionPlan("PRAGMA ${target}table_xinfo(${quoted(tableName)})")) { row ->
        Column((row.get("name") as KronosPhysicalValue.Text).value,
            (row.get("type") as KronosPhysicalValue.Text).value,
            (row.get("pk") as KronosPhysicalValue.Integer).value)
    }.filter { it.primaryPosition > 0 }
    require(primary.size == 1 && sqliteIdentifierEquals(primary.single().name, requested.fieldName) &&
        primary.single().type.trim().equals("INTEGER", true)) {
        "Generated key '${requested.fieldName}' must be the table's single INTEGER PRIMARY KEY rowid alias"
    }
    // INTEGER PRIMARY KEY DESC is SQLite's historical exception: it owns a PK index, not the rowid.
    val hasPrimaryIndex = toList(KronosQueryExecutionPlan("PRAGMA ${target}index_list(${quoted(tableName)})")) { row ->
        (row.get("origin") as KronosPhysicalValue.Text).value == "pk"
    }.any { it }
    require(!hasPrimaryIndex) { "Generated keys require a rowid alias; indexed/DESC primary keys are unsupported" }
}

// SQLite folds only ASCII in identifiers; Unicode ignoreCase can select another table's schema.
private fun sqliteIdentifierEquals(left: String, right: String): Boolean {
    fun asciiLower(char: Char): Char = if (char in 'A'..'Z') char + ('a' - 'A') else char
    return left.length == right.length && left.indices.all { asciiLower(left[it]) == asciiLower(right[it]) }
}

internal fun isSqliteRowMutation(sql: String): Boolean =
    statementTokens(sql).firstOrNull()?.let { token ->
        !token.quoted && token.value.uppercase() in setOf("INSERT", "UPDATE", "DELETE", "REPLACE", "WITH")
    } == true

private data class InsertToken(val value: String, val quoted: Boolean = false)

/** This only recognizes an INSERT target, rejecting unsupported shapes rather than guessing a key. */
private fun statementTokens(sql: String): List<InsertToken> = insertTokens(sql).dropWhile { it.value == ";" || it.value == "\uFEFF" }

private fun insertTokens(sql: String): List<InsertToken> = buildList {
    var cursor = 0
    while (cursor < sql.length) {
        val char = sql[cursor]
        when {
            char.isWhitespace() -> cursor++
            sql.startsWith("--", cursor) -> { cursor = sql.indexOf('\n', cursor + 2).takeIf { it >= 0 } ?: sql.length }
            sql.startsWith("/*", cursor) -> {
                val end = sql.indexOf("*/", cursor + 2)
                require(end >= 0) { "Unterminated SQL comment" }
                cursor = end + 2
            }
            char == '\'' || char == '"' || char == '`' || char == '[' -> {
                val endChar = if (char == '[') ']' else char
                cursor++
                val value = StringBuilder()
                var closed = false
                while (cursor < sql.length) {
                    val next = sql[cursor++]
                    if (next == endChar) {
                        if (char != '[' && sql.getOrNull(cursor) == endChar) { value.append(next); cursor++ }
                        else { closed = true; break }
                    } else value.append(next)
                }
                require(closed) { "Unterminated quoted SQL value" }
                add(InsertToken(value.toString(), true))
            }
            char.isLetterOrDigit() || char == '_' || char == '$' -> {
                val start = cursor++
                while (cursor < sql.length && (sql[cursor].isLetterOrDigit() || sql[cursor] == '_' || sql[cursor] == '$')) cursor++
                add(InsertToken(sql.substring(start, cursor)))
            }
            else -> { add(InsertToken(char.toString())); cursor++ }
        }
    }
}
