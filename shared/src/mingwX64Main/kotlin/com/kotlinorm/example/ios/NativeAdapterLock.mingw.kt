@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)
package com.kotlinorm.example.ios

import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlin.native.ref.createCleaner
import platform.windows.*

internal actual class NativeAdapterLock {
    private val section = nativeHeap.alloc<CRITICAL_SECTION>().ptr.also { InitializeCriticalSection(it) }
    @Suppress("unused")
    private val cleaner = createCleaner(section) {
        DeleteCriticalSection(it)
        nativeHeap.free(it.rawValue)
    }
    actual fun lock() = EnterCriticalSection(section)
    actual fun unlock() = LeaveCriticalSection(section)
}
internal actual fun currentThreadIdentity(): Long = GetCurrentThreadId().toLong()
