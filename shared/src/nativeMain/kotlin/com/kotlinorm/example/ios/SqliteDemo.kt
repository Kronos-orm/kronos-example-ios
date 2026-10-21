package com.kotlinorm.example.ios

import com.kotlinorm.Kronos
import com.kotlinorm.interfaces.KronosTransactionMode
import com.kotlinorm.interfaces.KronosTransactionOptions
import com.kotlinorm.orm.ddl.table
import com.kotlinorm.orm.insert.insert
import com.kotlinorm.orm.select.select
import com.kotlinorm.wrappers.KronosExecutionWrapper

/** Entry shared by SwiftUI and the desktop executable. */
object SqliteDemo {
    fun insertAndRead(name: String): String {
        initializeOrmExample()
        val database = KronosSqliteDataSource()
        val previous = Kronos.dataSource
        val wrapper = KronosExecutionWrapper(database)
        Kronos.dataSource = { wrapper }
        try {
            wrapper.table.createTable(NativePerson())
            val inserted =
                database.transact(KronosTransactionOptions(KronosTransactionMode.IMMEDIATE)) {
                    NativePerson(name = name, status = NativeStatus.READY)
                        .insert()
                        .withId()
                        .execute()
                }
            check(inserted.affectedRows == 1 && inserted.lastInsertId == 1L)
            val insertedId = checkNotNull(inserted.lastInsertId).toInt()
            val person = NativePerson().select().where { it.id == insertedId }.toList().single()
            check(person.id == 1 && person.name == name && person.status == NativeStatus.READY)
            return "SQLite connected: id=${person.id}, name=${person.name}"
        } finally {
            Kronos.dataSource = previous
            database.close()
        }
    }
}
