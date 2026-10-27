package com.kotlinorm.example.benchmark

import com.kotlinorm.Kronos
import com.kotlinorm.example.entities.NativeOnlyPerson
import com.kotlinorm.example.entities.PlatformPerson
import com.kotlinorm.example.ios.NativePerson
import com.kotlinorm.interfaces.KPojo
import com.kotlinorm.utils.initializeKronosMetadata
import kotlin.reflect.typeOf

data class BenchmarkPerson(var id: Int = 17) : KPojo

/** Runs as a separate process, so initialization must contribute the complete associated graph. */
fun main() {
    initializeKronosMetadata()
    val own = Kronos.createKPojo(typeOf<BenchmarkPerson>()) as BenchmarkPerson
    check(own.id == 17)
    check(own !== Kronos.createKPojo(typeOf<BenchmarkPerson>()))
    check(Kronos.createKPojo(typeOf<NativePerson>()) is NativePerson)
    check(Kronos.createKPojo(typeOf<PlatformPerson>()) is PlatformPerson)
    check(Kronos.createKPojo(typeOf<NativeOnlyPerson>()) is NativeOnlyPerson)
    println("Native associated compilation metadata verified")
}
