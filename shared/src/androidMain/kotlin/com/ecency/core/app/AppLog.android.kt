package com.ecency.core.app

import android.util.Log

internal actual fun appLogWarning(message: String) {
    try {
        Log.w("Ecency", message)
    } catch (e: RuntimeException) {
        // JVM host tests: android.jar stubs throw
        println("Ecency W $message")
    }
}
