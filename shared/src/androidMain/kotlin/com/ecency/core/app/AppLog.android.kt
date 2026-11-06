package com.ecency.core.app

import android.util.Log

internal actual fun appLogWarning(message: String) {
    try {
        Log.w("Ecency", message)
    } catch (e: RuntimeException) {
        // not reported: JVM host tests only: android.jar stubs throw
        // JVM host tests: android.jar stubs throw
        println("Ecency W $message")
    }
}
