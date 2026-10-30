package com.ecency.app.crash

import android.content.Context
import com.ecency.core.app.CrashReport
import com.ecency.core.app.CrashReporter
import com.ecency.core.app.CrashScrubber
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.protocol.SentryTransaction
import io.sentry.protocol.SentrySpan
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException

/**
 * Sentry as RN App.tsx sets it up, with the DSN [com.ecency.core.app.CrashDsn] picks (RN's project unless the build
 * names another): no default PII, no tracing, no session replay, errors at Sentry's default sample rate (all), the
 * release `<applicationId>@<version>+<build>` with the build number as dist (RN's CI naming). Every event and
 * breadcrumb is scrubbed ([CrashScrubber]); RN's benign errors are dropped. Without a DSN nothing starts.
 */
object SentryReporting {
    @Volatile var started = false
        private set

    /** Application.onCreate, before anything else can crash. */
    fun start(context: Context, dsn: String, applicationId: String, version: String, build: Int, debug: Boolean, mappingId: String) {
        if (dsn.isBlank() || started) return
        SentryAndroid.init(context) { o -> configure(o, dsn, applicationId, version, build, debug, mappingId) }
        started = true
    }

    /** The options [start] gives Sentry. [mappingId]: CI's id for this build's R8 mapping, empty when there is none. */
    fun configure(o: SentryAndroidOptions, dsn: String, applicationId: String, version: String, build: Int, debug: Boolean, mappingId: String) {
        o.dsn = dsn
        o.release = release(applicationId, version, build)
        o.dist = build.toString()
        o.environment = if (debug) "debug" else "production"
        // RN sendDefaultPii: false; nothing of the user, no IP from the device
        o.isSendDefaultPii = false
        // performance data at a low sample rate (issue #3571: app starts, slow and frozen frames, screen loads);
        // replays only in development builds, as RN
        o.tracesSampleRate = TRACES_SAMPLE_RATE
        o.sessionReplay.sessionSampleRate = 0.0
        o.sessionReplay.onErrorSampleRate = 0.0
        o.isAttachScreenshot = false
        o.isAttachViewHierarchy = false
        // the id CI uploaded mapping.txt under, so Sentry can map the R8-obfuscated stack traces back
        mappingId.trim().lowercase().takeIf { MAPPING_ID.matches(it) }?.let { o.proguardUuid = it }
        o.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> beforeSend(event) }
        o.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { crumb, _ -> scrub(crumb) }
        o.beforeSendTransaction = SentryOptions.BeforeSendTransactionCallback { tx, _ -> beforeSendTransaction(tx) }
    }

    const val TRACES_SAMPLE_RATE = 0.05

    /** Span kinds whose descriptions are URLs, file paths or queries: never sent. */
    private val PRIVATE_SPANS = listOf("http", "file", "db")

    /**
     * Transactions skip [beforeSend]: no network, file or database spans, every other text scrubbed, no user.
     * Never throws: when scrubbing fails the transaction is dropped, a lost sample costs nothing.
     */
    fun beforeSendTransaction(tx: SentryTransaction): SentryTransaction? = try {
        val kept = tx.spans.filter { s -> PRIVATE_SPANS.none { s.op.startsWith(it) } }.map { s ->
            SentrySpan(
                s.startTimestamp, s.timestamp, s.traceId, s.spanId, s.parentSpanId, s.op,
                CrashScrubber.scrub(s.description), s.status, s.origin, s.tags.orEmpty(), s.measurements, null,
            )
        }
        tx.spans.clear()
        tx.spans.addAll(kept)
        tx.breadcrumbs?.forEach { scrub(it) }
        tx.extras = null
        tx.request = null
        tx.user = null
        tx
    } catch (t: Throwable) {
        null
    }

    private val MAPPING_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun release(applicationId: String, version: String, build: Int) = "$applicationId@$version+$build"

    /**
     * RN `beforeSend`: benign errors dropped, then every text scrubbed. Never throws: when scrubbing itself fails the
     * event goes with its texts replaced, so neither the event nor a secret is lost.
     */
    fun beforeSend(event: SentryEvent): SentryEvent? {
        return try {
            if (event.exceptions.orEmpty().any { CrashScrubber.shouldDrop(it.type, it.value) }) return null
            if (event.throwable?.let(CrashScrubber::ignores) == true) return null
            scrubEvent(event)
            event
        } catch (t: Throwable) {
            runCatching {
                event.message = Message().apply { message = CrashScrubber.FAILED }
                event.exceptions?.forEach { it.value = CrashScrubber.FAILED }
                event.breadcrumbs = null
                event.extras = null
                event.request = null
                event.contexts.entrySet().toList().forEach { (k, v) -> if (v is Map<*, *> || v is String) event.contexts.remove(k) }
            }
            event
        }
    }

    private fun scrubEvent(event: SentryEvent) {
        event.message?.let { m ->
            m.message = CrashScrubber.scrub(m.message)
            m.formatted = CrashScrubber.scrub(m.formatted)
            m.params = m.params?.map { CrashScrubber.scrub(it) ?: "" }
        }
        event.exceptions?.forEach { it.value = CrashScrubber.scrub(it.value) }
        event.breadcrumbs?.forEach { scrub(it) }
        event.extras?.let { extras -> event.extras = extras.mapValues { (k, v) -> deep(k, v, 0) } }
        // named contexts (the iap context carries the store's own messages); the SDK's typed ones (device, os, app)
        // hold no free text and stay as they are
        event.contexts.entrySet().toList().forEach { (k, v) ->
            when (v) {
                is Map<*, *> -> event.contexts[k] = deep(k, v, 0) ?: emptyMap<String, Any>()
                is String -> event.contexts[k] = CrashScrubber.scrubValue(k, v) ?: ""
            }
        }
        event.tags?.let { tags -> event.tags = tags.mapValues { (k, v) -> CrashScrubber.scrubValue(k, v) ?: "" } }
        event.request?.let { r ->
            r.url = CrashScrubber.scrub(r.url)
            r.queryString = CrashScrubber.scrub(r.queryString)
            r.headers = null
            r.cookies = null
            r.data = null
        }
        event.user = null
    }

    /** RN `redactBreadcrumb`. Never throws. */
    fun scrub(crumb: Breadcrumb): Breadcrumb = try {
        crumb.message = CrashScrubber.scrub(crumb.message)
        val data = crumb.data.toMap()
        data.forEach { (k, v) -> crumb.setData(k, deep(k, v, 0) ?: "") }
        crumb
    } catch (t: Throwable) {
        crumb.message = CrashScrubber.FAILED
        runCatching { crumb.data.keys.toList().forEach { crumb.removeData(it) } }
        crumb
    }

    /** RN `redactDeep` (depth 6). */
    private fun deep(key: String, value: Any?, depth: Int): Any? = when {
        value == null -> null
        depth > CrashScrubber.MAX_DEPTH -> null
        value is String -> CrashScrubber.scrubValue(key, value)
        value is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to deep(k.toString(), v, depth + 1) }
        value is List<*> -> value.map { deep(key, it, depth + 1) }
        value is Number || value is Boolean -> value
        else -> CrashScrubber.scrubValue(key, value.toString())
    }

    /** What the shared code reports, as Sentry events (RN captureException / captureMessage / addBreadcrumb). */
    object Reporter : CrashReporter {
        override fun capture(report: CrashReport) {
            if (!started) return
            runCatching { Sentry.captureEvent(event(report)) }
        }

        override fun breadcrumb(category: String, message: String, data: Map<String, String>) {
            if (!started) return
            runCatching {
                Sentry.addBreadcrumb(
                    Breadcrumb().apply {
                        this.category = category
                        this.message = message
                        level = SentryLevel.INFO
                        data.forEach { (k, v) -> setData(k, v) }
                    },
                )
            }
        }

        fun event(report: CrashReport): SentryEvent = SentryEvent().apply {
            level = if (report.level == "warning") SentryLevel.WARNING else SentryLevel.ERROR
            if (report.isMessage) {
                message = Message().apply { message = report.message }
            } else {
                exceptions = listOf(SentryException().apply { type = report.type; value = report.message; module = "com.ecency.core" })
            }
            if (report.fingerprint.isNotEmpty()) fingerprints = report.fingerprint
            report.tags.forEach { (k, v) -> setTag(k, v) }
            if (report.contextName.isNotEmpty()) contexts[report.contextName] = report.context
        }
    }
}
