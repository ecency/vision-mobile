package com.ecency.core.api.rpc

/** A plain mutual-exclusion lock for short, non-suspending sections (ReentrantLock on Android, NSLock on iOS). */
internal expect class PlatformLock() {
    fun <T> withLock(block: () -> T): T
}
