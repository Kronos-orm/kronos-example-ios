package com.kotlinorm.example.entities

import com.kotlinorm.annotations.PrimaryKey
import com.kotlinorm.annotations.Table
import com.kotlinorm.interfaces.KPojo

/** Model library consumed only through the application's Native source set. */
@Table("platform_person")
data class PlatformPerson(@PrimaryKey(identity = true) var id: Int? = null, var name: String? = null) : KPojo
