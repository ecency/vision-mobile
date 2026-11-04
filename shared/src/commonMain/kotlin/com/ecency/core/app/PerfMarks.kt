package com.ecency.core.app

import kotlinx.coroutines.flow.MutableStateFlow

/** Event names the perf scripts look for. Scroll metrics are measured outside the app. */
object PerfEvent {
    /**
     * The app's own code starts: the first line of its entry point, before any UI exists (Android
     * `Application.onCreate`, iOS the `@main` App's `init`). Its millis are the time the system took
     * from process start to the app's code. Marked once per process.
     */
    const val APP_START = "app_start"

    /** The first frame of the app's UI was drawn (whatever screen it is: feed, lock, error). Marked once. */
    const val FIRST_FRAME = "first_frame"

    /** First feed card drawn. */
    const val FEED_FIRST_CONTENT = "feed_first_content"

    /** The user tapped a post card. */
    const val POST_OPEN_TAP = "post_open_tap"

    /** The post body's first blocks drawn. */
    const val POST_BODY_DRAWN = "post_body_drawn"

    /** Model side: the first feed page arrived (data, not pixels). */
    const val FEED_FIRST_PAGE = "feed_first_page"

    /** Model side: the post's body blocks are ready (data, not pixels). */
    const val POST_BODY_READY = "post_body_ready"
}

/**
 * Timing marks, logged as `EcencyPerf <event> <millis>` where millis counts from process start.
 * Android: logcat tag `EcencyPerf` (`adb logcat -s EcencyPerf`). iOS: os_log subsystem
 * `app.esteem.mobile.ios`, category `perf`, plus an os_signpost event per mark (Instruments).
 */
object PerfMarks {
    private val once = MutableStateFlow(emptySet<String>())

    /** Test hook: also receives every mark. */
    var listener: ((String, Long) -> Unit)? = null

    /** Logs [event] now and returns its millis since process start. */
    fun mark(event: String): Long {
        val millis = perfProcessMillis()
        perfEmit(event, millis)
        listener?.invoke(event, millis)
        return millis
    }

    /** Like [mark], but only the first time [event] is marked in this process. Returns null after that. */
    fun markOnce(event: String): Long? {
        while (true) {
            val seen = once.value
            if (event in seen) return null
            if (once.compareAndSet(seen, seen + event)) return mark(event)
        }
    }

    /** Forgets [markOnce] history (tests, or a perf run that restarts a flow in the same process). */
    fun resetOnce() {
        once.value = emptySet()
    }
}

/** Milliseconds since the process started. */
internal expect fun perfProcessMillis(): Long

/** Writes one mark to the platform log. Must never throw. */
internal expect fun perfEmit(event: String, millis: Long)
