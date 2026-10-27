package com.kotlinorm.example.ios

import com.kotlinorm.Kronos
import com.kotlinorm.annotations.Column
import com.kotlinorm.annotations.PrimaryKey
import com.kotlinorm.annotations.Table
import com.kotlinorm.interfaces.KPojo
import com.kotlinorm.interfaces.KronosActionExecutionPlan
import com.kotlinorm.interfaces.KronosPhysicalValue
import com.kotlinorm.interfaces.KronosQueryExecutionPlan
import com.kotlinorm.orm.select.select
import com.kotlinorm.wrappers.KronosExecutionWrapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

@Table("native_clock")
internal data class NativeClock(
    @PrimaryKey var id: Int? = null,
    @Column("created_at") var createdAt: Instant? = null
) : KPojo

class NativeTemporalIntegrationTest {
    @Test
    fun sqliteCurrentTimestampMapsToTheSharedInstantModel() {
        initializeOrmTest()
        val source = KronosSqliteDataSource()
        val previousSource = Kronos.dataSource
        val previousZone = Kronos.timeZone
        val previousFormat = Kronos.defaultDateFormat
        Kronos.dataSource = { KronosExecutionWrapper(source) }
        Kronos.timeZone = "UTC"
        Kronos.defaultDateFormat = "yyyy-MM-dd HH:mm:ss"
        try {
            source.execute(KronosActionExecutionPlan(
                "CREATE TABLE native_clock(id INTEGER PRIMARY KEY, created_at TEXT DEFAULT CURRENT_TIMESTAMP)"
            ))
            source.execute(KronosActionExecutionPlan("INSERT INTO native_clock(id) VALUES(1)"))
            val stored = source.toList(KronosQueryExecutionPlan("SELECT created_at FROM native_clock")) {
                (it.get(1) as KronosPhysicalValue.Text).value
            }.single()
            val loaded = NativeClock().select().first()
            assertEquals(1, loaded.id)
            assertEquals(Instant.parse(stored.replace(' ', 'T') + "Z"), loaded.createdAt)
        } finally {
            Kronos.dataSource = previousSource
            Kronos.timeZone = previousZone
            Kronos.defaultDateFormat = previousFormat
            source.close()
        }
    }
}
