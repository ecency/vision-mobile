package com.ecency.core.render

import java.lang.management.ManagementFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds

internal actual fun collectGarbage() = System.gc()

private val threads = ManagementFactory.getThreadMXBean()

internal actual fun threadCpuTime(): Duration =
    if (threads.isCurrentThreadCpuTimeSupported) {
        threads.currentThreadCpuTime.nanoseconds
    } else {
        System.nanoTime().nanoseconds
    }

internal actual val defaultExponentCap: Double = 1.6
