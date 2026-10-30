package com.ecency.core.app

import platform.Foundation.NSLog

internal actual fun appLogWarning(message: String) {
    // the message is the format itself, with no arguments: passing a Kotlin String through NSLog's varargs crashes
    NSLog("Ecency: ${message.replace("%", "%%")}")
}
