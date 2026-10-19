package com.kotlinorm.example.ios

import com.kotlinorm.enums.ValueCodecDirection
import com.kotlinorm.enums.ValueCodecOrigin
import com.kotlinorm.enums.ValueStorage
import com.kotlinorm.interfaces.*
import com.kotlinorm.utils.StaticTypeInfo
import com.kotlinorm.utils.StaticTypeRef
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class SqliteCodecIntegrationTest {
    private enum class Status { READY, DONE }

    @Test
    fun nullableEnumAndSerializedJsonRoundTripThroughRealSQLite() {
        val source = KronosSqliteDataSource()
        val enumType = StaticTypeRef("example.Status",nullable=false)
        val jsonType = StaticTypeRef("kotlinx.serialization.json.JsonObject",nullable=false)
        val stringType = StaticTypeRef("kotlin.String",nullable=true)
        val enumRegistration = KronosValueCodecs.registerEnum(object : StaticEnumValueCodec {
            override val type = enumType
            override val entryNames = Status.entries.map { it.name }
            override fun decode(name: String): Enum<*>? = Status.entries.firstOrNull { it.name == name }
        })
        val jsonRegistration = KronosValueCodecs.register(serializedTextValueCodec(
            encode={ value,_ -> (value as JsonObject).toString() },
            decode={ text,_ -> Json.parseToJsonElement(text).jsonObject }
        ))
        fun context(type: StaticTypeRef, direction: ValueCodecDirection, serialized: Boolean=false) =
            StaticValueCodecContext(
                direction=direction,
                origin=if (direction==ValueCodecDirection.ENCODE) ValueCodecOrigin.PARAMETER else ValueCodecOrigin.DATABASE,
                targetType=type,
                targetTypeInfo=if (type==enumType) StaticTypeInfo(enumType,enumKey=enumType.key) else null,
                storage=if (serialized) ValueStorage.SERIALIZED else ValueStorage.NONE
            )
        try {
            val payload = buildJsonObject { put("name","数据库"); put("count",7); put("missing",JsonNull) }
            val params = mapOf(
                "status" to KronosValueCodecs.encode(Status.READY,context(enumType,ValueCodecDirection.ENCODE)),
                "payload" to KronosValueCodecs.encode(payload,context(jsonType,ValueCodecDirection.ENCODE,true)),
                "note" to KronosValueCodecs.encode(null,context(stringType,ValueCodecDirection.ENCODE))
            )
            source.execute(KronosActionExecutionPlan("CREATE TABLE item(status TEXT, payload TEXT, note TEXT)"))
            source.execute(KronosActionExecutionPlan("INSERT INTO item VALUES(:status,:payload,:note)",params))
            val restored = source.first(KronosQueryExecutionPlan("SELECT status,payload,note FROM item")) {
                Triple(
                    KronosValueCodecs.decode(it.get(1),context(enumType,ValueCodecDirection.DECODE)),
                    KronosValueCodecs.decode(it.get(2),context(jsonType,ValueCodecDirection.DECODE,true)),
                    KronosValueCodecs.decode(it.get(3),context(stringType,ValueCodecDirection.DECODE))
                )
            }
            assertEquals(KronosExecutionFirstResult.Present(Triple(Status.READY,payload,null)),restored)
        } finally {
            jsonRegistration.close()
            enumRegistration.close()
            source.close()
        }
    }
}
