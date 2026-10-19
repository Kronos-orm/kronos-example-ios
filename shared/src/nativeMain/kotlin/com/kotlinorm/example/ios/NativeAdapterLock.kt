package com.kotlinorm.example.ios

internal expect class NativeAdapterLock() {
    fun lock()
    fun unlock()
}
