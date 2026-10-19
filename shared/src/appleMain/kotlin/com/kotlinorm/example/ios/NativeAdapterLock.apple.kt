package com.kotlinorm.example.ios

import platform.Foundation.NSRecursiveLock

internal actual class NativeAdapterLock {
    private val delegate = NSRecursiveLock()

    actual fun lock() = delegate.lock()
    actual fun unlock() = delegate.unlock()
}
