@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package com.kotlinorm.example.ios

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlin.native.ref.createCleaner
import platform.posix.*

internal actual class NativeAdapterLock {
    private val mutex = nativeHeap.alloc<pthread_mutex_t>().ptr.also { mutex ->
        memScoped {
            val attributes = alloc<pthread_mutexattr_t>()
            check(pthread_mutexattr_init(attributes.ptr) == 0)
            try {
                check(pthread_mutexattr_settype(attributes.ptr, PTHREAD_MUTEX_RECURSIVE.toInt()) == 0)
                check(pthread_mutex_init(mutex, attributes.ptr) == 0)
            } finally {
                pthread_mutexattr_destroy(attributes.ptr)
            }
        }
    }

    @Suppress("unused")
    private val cleaner = createCleaner(mutex) {
        pthread_mutex_destroy(it)
        nativeHeap.free(it.rawValue)
    }

    actual fun lock() {
        check(pthread_mutex_lock(mutex) == 0)
    }

    actual fun unlock() {
        check(pthread_mutex_unlock(mutex) == 0)
    }
}
