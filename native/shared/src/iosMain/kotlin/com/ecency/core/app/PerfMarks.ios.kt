package com.ecency.core.app

import com.ecency.core.app.perf.ecency_perf_emit
import com.ecency.core.app.perf.ecency_perf_process_millis
import kotlinx.cinterop.ExperimentalForeignApi

@OptIn(ExperimentalForeignApi::class)
internal actual fun perfProcessMillis(): Long = ecency_perf_process_millis()

@OptIn(ExperimentalForeignApi::class)
internal actual fun perfEmit(event: String, millis: Long) = ecency_perf_emit(event, millis)
