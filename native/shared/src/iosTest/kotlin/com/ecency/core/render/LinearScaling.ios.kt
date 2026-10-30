package com.ecency.core.render

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.CLOCK_THREAD_CPUTIME_ID
import platform.posix.clock_gettime
import platform.posix.timespec
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

@OptIn(NativeRuntimeApi::class)
internal actual fun collectGarbage() = GC.collect()

@OptIn(ExperimentalForeignApi::class)
internal actual fun threadCpuTime(): Duration = memScoped {
    val ts = alloc<timespec>()
    clock_gettime(CLOCK_THREAD_CPUTIME_ID.convert(), ts.ptr)
    (ts.tv_sec * 1_000_000_000L + ts.tv_nsec).nanoseconds
}

/**
 * Kotlin/Native's collector makes allocation-heavy linear work grow like n^1.4 to n^1.5 between these sizes
 * (markdown links and nested spans measure 1.43 to 1.56 here, 1.2 to 1.3 on the JVM); quadratic work is still 2.
 */
internal actual val defaultExponentCap: Double = 1.75
