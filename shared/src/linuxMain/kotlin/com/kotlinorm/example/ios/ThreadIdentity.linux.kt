@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.kotlinorm.example.ios
import platform.posix.pthread_self
internal actual fun currentThreadIdentity(): Long = pthread_self().toLong()
