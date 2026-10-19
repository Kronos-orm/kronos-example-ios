package com.kotlinorm.example.ios

import com.kotlinorm.annotations.PrimaryKey
import com.kotlinorm.interfaces.KPojo
import com.kotlinorm.utils.initializeKronosMetadata

private val ormMetadataInitialization = lazy { initializeKronosMetadata() }

/** Explicit, once-only startup before the first ORM lookup. */
fun initializeOrmExample() { ormMetadataInitialization.value }

enum class NativeStatus { READY, DONE }

data class NativePerson(
    @PrimaryKey(identity=true) var id: Int? = null,
    var name: String? = null,
    var status: NativeStatus? = null,
    var age: Int? = null
) : KPojo

data class NativeCodecRecord(
    @PrimaryKey(identity=true) var id: Int? = null,
    var status: NativeStatus? = null,
    @com.kotlinorm.annotations.ColumnType(com.kotlinorm.enums.KColumnType.INT)
    var ordinalStatus: NativeStatus? = null,
    @com.kotlinorm.annotations.Serialize
    var tags: List<String>? = null,
    var bytes: ByteArray? = null,
    var note: String? = null
) : KPojo

/** Logical Kotlin property and physical SQLite identity column intentionally differ. */
data class NativeRenamedIdentity(
    @PrimaryKey(identity = true)
    @com.kotlinorm.annotations.Column("person_key")
    var id: Int? = null,
    var name: String? = null
) : KPojo
