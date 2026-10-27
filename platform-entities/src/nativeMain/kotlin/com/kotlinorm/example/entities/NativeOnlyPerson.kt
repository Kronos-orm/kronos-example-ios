package com.kotlinorm.example.entities

import com.kotlinorm.annotations.PrimaryKey
import com.kotlinorm.annotations.Table
import com.kotlinorm.interfaces.KPojo

/** Native declaration contributed through the common-owned provider skeleton. */
@Table("native_only_person")
data class NativeOnlyPerson(@PrimaryKey(identity = true) var id: Int? = null, var name: String? = null) : KPojo
