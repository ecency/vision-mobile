package com.ecency.core

/** Name and OS version of the platform the shared core is running on. */
expect fun platformName(): String

object EcencyCore {
    const val VERSION = "4.0.0"

    fun greeting(): String = "Ecency core $VERSION on ${platformName()}"
}
