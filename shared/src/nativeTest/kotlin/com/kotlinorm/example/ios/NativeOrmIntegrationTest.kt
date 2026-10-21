package com.kotlinorm.example.ios

import com.kotlinorm.orm.ddl.table
import com.kotlinorm.functions.bundled.exts.PolymerizationFunctions.avgDouble
import com.kotlinorm.Kronos
import com.kotlinorm.orm.insert.insert
import com.kotlinorm.orm.select.select
import com.kotlinorm.orm.update.update
import com.kotlinorm.orm.delete.delete
import com.kotlinorm.orm.upsert.upsert
import com.kotlinorm.interfaces.KronosActionExecutionPlan
import com.kotlinorm.interfaces.KronosQueryExecutionPlan
import com.kotlinorm.interfaces.KronosPhysicalValue
import kotlin.test.assertTrue
import com.kotlinorm.utils.initializeKronosMetadata
import com.kotlinorm.wrappers.KronosExecutionWrapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertContentEquals
import com.kotlinorm.interfaces.serializedValueCodec
import kotlinx.serialization.json.*
import com.kotlinorm.annotations.PrimaryKey
import com.kotlinorm.annotations.Table
import com.kotlinorm.interfaces.KPojo
import com.kotlinorm.types.ToInsertSelect
import com.kotlinorm.types.ToReference
import com.kotlinorm.types.ToSelect
import com.kotlinorm.types.ToSort
import kotlin.reflect.KMutableProperty0

private val ormTestMetadataInitialization = lazy { initializeKronosMetadata() }

/** Test-owned generated projections join the main graph before its first freeze. */
private fun initializeOrmTest() {
    ormTestMetadataInitialization.value
    initializeOrmExample()
}

@Table("native_typed_dsl_record")
internal data class NativeTypedDslRecord(
    @PrimaryKey(identity = true) var key: Int? = null,
    var number: Int = 7,
    var text: String = "ready",
    var optionalNumber: Int? = 9,
    var optionalText: String? = "nullable"
) : KPojo

@Table("native_typed_dsl_target")
internal data class NativeTypedDslTarget(@PrimaryKey var text: String = "") : KPojo

private class NativeTypedDslCapture {
    var reads = 0
    private val value: String get() { reads++; return "copied" }
    fun read(): String = value
}

class NativeOrmIntegrationTest {
    @Test
    fun explicitDslReturnTypesKeepTheirNativeCallingConvention() {
        initializeOrmTest()
        val source = KronosSqliteDataSource()
        val wrapper = KronosExecutionWrapper(source)
        val previous = Kronos.dataSource
        Kronos.dataSource = { wrapper }
        try {
            val record = NativeTypedDslRecord()
            wrapper.table.createTable(record)
            wrapper.table.createTable(NativeTypedDslTarget())
            assertEquals(1, record.insert().execute().affectedRows)

            val nullableInt: ToSelect<NativeTypedDslRecord, Int?> = { it.optionalNumber }
            val nullableString: ToSelect<NativeTypedDslRecord, String?> = { it.optionalText }
            val nonNullInt: ToSelect<NativeTypedDslRecord, Int> = { it.number }
            val nonNullString: ToSelect<NativeTypedDslRecord, String> = { it.text }
            val nonNullList: ToSelect<NativeTypedDslRecord, List<Any>> = { listOf(it.number, it.text) }
            val nullableAny: ToSelect<NativeTypedDslRecord, Any?> = { it.number }
            val unitSelect: ToSelect<NativeTypedDslRecord, Unit> = { [it.number, it.text] }

            assertEquals(listOf(record.optionalNumber), record.select(nullableInt).toList<Int?>())
            assertEquals(listOf("nullable"), record.select(nullableString).toList<String?>())
            assertEquals(listOf(record.number), record.select(nonNullInt).toList<Int>())
            assertEquals(listOf("ready"), record.select(nonNullString).toList<String>())
            assertEquals(listOf(mapOf("number" to record.number, "text" to "ready")),
                record.select(nonNullList).toList<Map<String, Any?>>())
            assertEquals(listOf(record.number), record.select(nullableAny).toList<Int>())
            val unitSort: ToSort<NativeTypedDslRecord, Unit> = { [it.number, it.text.desc()] }
            assertEquals(listOf(mapOf("number" to record.number, "text" to "ready")),
                record.select(unitSelect).orderBy(unitSort).toList<Map<String, Any?>>())

            val sort: ToSort<NativeTypedDslRecord, Int> = { it.number }
            assertEquals(listOf("ready"), record.select(nonNullString).orderBy(sort).toList<String>())
            val reference: ToReference<NativeTypedDslRecord, KMutableProperty0<String>> = { it::text }
            assertEquals(listOf("ready"), record.select(nonNullString).cascade(reference).toList<String>())

            val captured = NativeTypedDslCapture()
            val insert: ToInsertSelect<NativeTypedDslRecord, String> = { captured.read() }
            assertEquals(1, record.select().insert<NativeTypedDslTarget>(insert).execute().affectedRows)
            assertEquals(1, captured.reads)
            assertEquals(listOf(NativeTypedDslTarget("copied")), NativeTypedDslTarget().select().toList())
            assertEquals(1, captured.reads)
        } finally {
            Kronos.dataSource = previous
            source.close()
        }
    }

    @Test
    fun compilerGeneratedPojoRunsCrudAgainstRealNativeSQLite() {
        initializeOrmTest()
        val source = KronosSqliteDataSource()
        val wrapper = KronosExecutionWrapper(source)
        val previous = Kronos.dataSource
        Kronos.dataSource = { wrapper }
        try {
            wrapper.table.createTable(NativePerson())
            val inserted=NativePerson(name="Native ORM",status=NativeStatus.READY,age=20).insert().withId().execute()
            assertEquals(1L,inserted.lastInsertId)
            assertEquals(1,inserted.affectedRows)
            assertEquals(listOf(NativePerson(1,"Native ORM",NativeStatus.READY,20)),NativePerson().select().toList())
            val projected=NativePerson().select { [it.id,it.name] }.toList()
            assertEquals(listOf(1 to "Native ORM"),projected.map { it.id to it.name })
            assertEquals(listOf("Native ORM"),NativePerson().select { it.name }.toList<String>())
            NativePerson(id=1,name="Updated").update { it.name }.by { it.id }.execute()
            assertEquals(listOf(NativePerson(1,"Updated",NativeStatus.READY,20)),NativePerson().select().where { it.id==1 }.toList())
            val unrequested = NativePerson(name="Second",status=NativeStatus.DONE,age=30).insert().execute()
            assertEquals(1, unrequested.affectedRows)
            assertNull(unrequested.lastInsertId)
            val average = NativePerson().select { f.avgDouble(it.age).alias("average") }.first<Double>()
            assertEquals(25.0,average)
            NativePerson(id=2).delete().by { it.id }.execute()
            NativePerson(id=1).delete().by { it.id }.execute()
            assertEquals(emptyList(),NativePerson().select().toList())
        } finally {
            Kronos.dataSource=previous
            source.close()
        }
    }
    @Test
    fun generatedMetadataMapsEnumOrdinalBlobNullableAndSerializedValuesOnce() {
        initializeOrmTest()
        val source=KronosSqliteDataSource()
        val wrapper=KronosExecutionWrapper(source)
        val previous=Kronos.dataSource
        Kronos.dataSource={ wrapper }
        var decodeCount=0
        val registration=Kronos.registerValueCodec(serializedValueCodec(
            encode={ value,_ -> JsonArray((value as List<*>).map { JsonPrimitive(it as String) }).toString() },
            decode={ text,_ -> decodeCount++; Json.parseToJsonElement(text).jsonArray.map { it.jsonPrimitive.content } }
        ))
        try {
            wrapper.table.createTable(NativeCodecRecord())
            NativeCodecRecord(status=NativeStatus.READY,ordinalStatus=NativeStatus.DONE,
                tags=listOf("数据库","Kronos"),bytes=byteArrayOf(0,1,-1),note=null).insert().execute()
            val restored=NativeCodecRecord().select().toList().single()
            assertEquals(1,restored.id)
            assertEquals(NativeStatus.READY,restored.status)
            assertEquals(NativeStatus.DONE,restored.ordinalStatus)
            assertEquals(listOf("数据库","Kronos"),restored.tags)
            assertContentEquals(byteArrayOf(0,1,-1),restored.bytes)
            assertEquals(null,restored.note)
            assertEquals(1,decodeCount)
        } finally {
            registration.close()
            Kronos.dataSource=previous
            source.close()
        }
    }

    @Test
    fun schemaSyncUpsertAndTotalPaginationUseTheNativeOrmRuntime() {
        initializeOrmTest()
        val source = KronosSqliteDataSource()
        val previous = Kronos.dataSource
        val wrapper = KronosExecutionWrapper(source)
        Kronos.dataSource = { wrapper }
        try {
            val table = NativePerson().__tableName.replace("\"", "\"\"")
            source.execute(KronosActionExecutionPlan("CREATE TABLE \"$table\"(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT)"))
            assertTrue(wrapper.table.syncTable(NativePerson()))
            val columns = source.toList(KronosQueryExecutionPlan("PRAGMA table_info(\"$table\")")) {
                (it.get("name") as KronosPhysicalValue.Text).value
            }
            assertEquals(setOf("id", "name", "status", "age"), columns.toSet())
            NativePerson(name = "First", status = NativeStatus.READY, age = 10).insert().execute()
            NativePerson(name = "Second", status = NativeStatus.DONE, age = 20).insert().execute()
            NativePerson(name = "Third", status = NativeStatus.READY, age = 30).insert().execute()
            NativePerson(id = 2, name = "Second updated").upsert { it.name }.on { it.id }.onConflict().execute()
            assertEquals(listOf(NativePerson(2, "Second updated", NativeStatus.DONE, 20)),
                NativePerson().select().where { it.id == 2 }.toList())
            val page = NativePerson().select().orderBy { it.id }.page(2, 1).withTotal().toList()
            assertEquals(3, page.total)
            assertEquals(3, page.totalPages)
            assertEquals(2, page.pageIndex)
            assertEquals(1, page.pageSize)
            assertEquals(listOf(NativePerson(2, "Second updated", NativeStatus.DONE, 20)), page.records)
            assertEquals(3, NativePerson().select().toList().size)
        } finally {
            Kronos.dataSource = previous
            source.close()
        }
    }
    @Test
    fun renamedIdentityColumnCapturesThePhysicalSqliteKey() {
        initializeOrmTest()
        val source = KronosSqliteDataSource()
        val previous = Kronos.dataSource
        val wrapper = KronosExecutionWrapper(source)
        Kronos.dataSource = { wrapper }
        try {
            wrapper.table.createTable(NativeRenamedIdentity())
            val inserted = NativeRenamedIdentity(name = "Physical identity").insert().withId().execute()
            assertEquals(1, inserted.affectedRows)
            assertEquals(1L, inserted.lastInsertId)
            assertEquals(listOf(NativeRenamedIdentity(1, "Physical identity")),
                NativeRenamedIdentity().select().toList())
        } finally {
            Kronos.dataSource = previous
            source.close()
        }
    }
}
