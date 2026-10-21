package com.kotlinorm.example.ios

import com.kotlinorm.annotations.PrimaryKey
import com.kotlinorm.interfaces.KPojo
import com.kotlinorm.utils.initializeKronosMetadata

private val ormMetadataInitialization = lazy { initializeKronosMetadata() }

/** Explicit, once-only startup before the first ORM lookup. */
fun initializeOrmExample() {
    ormMetadataInitialization.value
}

enum class NativeStatus {
    READY,
    DONE,
}

data class NativePerson(
    @PrimaryKey(identity = true) var id: Int? = null,
    var name: String? = null,
    var status: NativeStatus? = null,
    var age: Int? = null,
) : KPojo
