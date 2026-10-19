package com.kotlinorm.example.ios

/** Run a real SQLite create/insert/query from a desktop Native executable. */
fun main() {
    val result = SqliteDemo.insertAndRead("Kronos Native")
    check(result == "SQLite connected: id=1, name=Kronos Native")
    println("Kronos SQLite connection verified: id=1, name=Kronos Native")
}
