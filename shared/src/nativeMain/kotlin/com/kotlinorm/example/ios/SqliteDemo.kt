package com.kotlinorm.example.ios

/** Entry shared by SwiftUI and the desktop executable. */
object SqliteDemo {
    fun insertAndRead(name: String): String = runSqliteDemo(name)
}
