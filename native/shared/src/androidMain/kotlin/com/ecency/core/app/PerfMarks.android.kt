package com.ecency.core.app

import android.os.Process
import android.os.SystemClock
import android.util.Log

private val jvmStartNanos = System.nanoTime()

internal actual fun perfProcessMillis(): Long = try {
    SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
} catch (e: RuntimeException) {
    // JVM host tests: android.jar stubs throw; count from class load instead
    (System.nanoTime() - jvmStartNanos) / 1_000_000
}

internal actual fun perfEmit(event: String, millis: Long) {
    try {
        Log.i("EcencyPerf", "$event $millis")
    } catch (e: RuntimeException) {
        println("EcencyPerf $event $millis")
    }
}
