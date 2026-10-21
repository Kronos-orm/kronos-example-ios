package com.kotlinorm.example.ios

import com.kotlinorm.annotations.PrimaryKey
import com.kotlinorm.interfaces.KPojo

data class NativeCodecRecord(
    @PrimaryKey(identity = true) var id: Int? = null,
    var status: NativeStatus? = null,
    @com.kotlinorm.annotations.ColumnType(com.kotlinorm.enums.KColumnType.INT)
    var ordinalStatus: NativeStatus? = null,
    @com.kotlinorm.annotations.Serialize var tags: List<String>? = null,
    var bytes: ByteArray? = null,
    var note: String? = null,
) : KPojo

/** Logical Kotlin property and physical SQLite identity column intentionally differ. */
data class NativeRenamedIdentity(
    @PrimaryKey(identity = true)
    @com.kotlinorm.annotations.Column("person_key")
    var id: Int? = null,
    var name: String? = null,
) : KPojo
