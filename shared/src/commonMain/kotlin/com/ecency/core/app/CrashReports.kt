package com.ecency.core.app

import kotlin.coroutines.cancellation.CancellationException

/**
 * Where the shared code sends what RN sends with `captureException` / `captureMessage` / `addBreadcrumb`
 * (utils/sentryUtils.ts). Each app sets its Sentry adapter as [AppEnvironment.crashReporter] at start; without a DSN in
 * the build the adapter is [CrashReporter.None]. Every text goes through [CrashScrubber] on the platform's
 * `beforeSend` and `beforeBreadcrumb`, so the shared code may hand over raw messages.
 */
interface CrashReporter {
    fun capture(report: CrashReport)

    fun breadcrumb(category: String, message: String, data: Map<String, String>)

    object None : CrashReporter {
        override fun capture(report: CrashReport) {}
        override fun breadcrumb(category: String, message: String, data: Map<String, String>) {}
    }
}

/**
 * The Sentry DSN a build reports to (README "Crash reporting"). A DSN only lets the app send events, so RN keeps it in
 * App.tsx; the native app reuses RN's project. Build setting (`ecency.sentryDsn`, CI secret `NATIVE_SENTRY_DSN`):
 * empty is [DEFAULT] in release builds and nothing in debug builds, `off` (or `none`) turns reporting off everywhere,
 * anything else must be an https Sentry DSN with a key and a project id or reporting stays off.
 */
object CrashDsn {
    const val DEFAULT = "https://a7b0c5a49bdeae965767e2967411b7b0@o4507985141956608.ingest.de.sentry.io/4509786252116048"

    private val SHAPE = Regex("^https://[0-9a-f]{16,64}@[a-z0-9.-]+/[0-9]+$")

    fun of(setting: String?, debug: Boolean): String {
        val s = setting?.trim().orEmpty()
        if (s.isEmpty()) return if (debug) "" else DEFAULT
        if (s.equals("off", ignoreCase = true) || s.equals("none", ignoreCase = true)) return ""
        return if (SHAPE.matches(s)) s else ""
    }
}

/**
 * One event: the exception's [type] and [message] (or a message event with [isMessage]), Sentry tags,
 * a [fingerprint] when the issue must group by something other than the stack (RN `scope.setFingerprint`) and one
 * named context ([contextName], RN `scope.setContext`).
 */
data class CrashReport(
    val type: String,
    val message: String,
    val tags: Map<String, String> = emptyMap(),
    val fingerprint: List<String> = emptyList(),
    val contextName: String = "",
    val context: Map<String, String> = emptyMap(),
    val isMessage: Boolean = false,
    val level: String = "error",
) {
    companion object {
        /** RN `captureException(error, scope => scope.setTag('context', ...))` for a Kotlin exception. */
        fun of(e: Throwable, context: String): CrashReport =
            CrashReport(e::class.simpleName ?: "Error", e.message.orEmpty(), tags = mapOf("context" to context))
    }
}

/**
 * RN App.tsx's `beforeSend` / `beforeBreadcrumb` and its ignore lists, plus what this app adds (memos, PINs, codes,
 * e-mail addresses). Pure and total: [scrub] and [shouldDrop] never throw (a throw inside Sentry's `beforeSend` drops
 * the event it was processing, and a Kotlin exception crossing into Swift aborts the app), so a scrubbing failure
 * returns [FAILED] instead of the text.
 */
object CrashScrubber {
    /** What a text becomes when scrubbing it failed: nothing of the original leaves the device. */
    const val FAILED = "<scrub-failed>"

    /** Longer texts are cut before scrubbing; Sentry truncates long values anyway. */
    const val MAX_TEXT = 16_384

    /** Nested breadcrumb data deeper than this is dropped (RN `redactDeep` depth guard). */
    const val MAX_DEPTH = 6

    // RN HIVE_WIF_KEY and BEARER_TOKEN
    private val WIF = Regex("\\b5[HJK][1-9A-HJ-NP-Za-km-z]{48,51}\\b")
    private val BEARER = Regex("\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]{12,}", RegexOption.IGNORE_CASE)

    // added here: JWT-shaped tokens (Ecency access and refresh tokens, HiveSigner codes), long hex (private keys,
    // signatures), query or JSON fields that name a secret, and e-mail addresses (personal data)
    private val JWT = Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(\\.[A-Za-z0-9_-]+)?")
    private val LONG_HEX = Regex("\\b[0-9a-fA-F]{64,}\\b")
    private const val SECRET_NAMES = "code|access_token|accessToken|refresh_token|refreshToken|token|key|wif|memo|pin|password|" +
        "signature|sig|secret|receipt|purchaseToken|private_key|privateKey|email"
    private val QUERY_SECRET = Regex("(?i)([?&;](?:$SECRET_NAMES)=)[^&#\\s]*")
    private val JSON_SECRET = Regex("(?i)(\"(?:$SECRET_NAMES)\"\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*\"")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}")

    /** Keys whose value is dropped whole in breadcrumb data and contexts. */
    private val SECRET_KEYS: Set<String> = SECRET_NAMES.split('|').map { it.lowercase() }.toSet() + setOf("authorization", "cookie", "x-mm-token", "x-hs-token")

    /** Every secret pattern in [text] replaced. Never throws. */
    fun scrub(text: String?): String? {
        if (text == null) return null
        return try {
            val t = if (text.length > MAX_TEXT) text.substring(0, MAX_TEXT) else text
            t.replace(WIF, "<redacted-key>")
                .replace(BEARER) { "${it.groupValues[1]} <redacted>" }
                .replace(JWT, "<redacted-token>")
                .replace(LONG_HEX, "<redacted-hex>")
                .replace(QUERY_SECRET) { "${it.groupValues[1]}<redacted>" }
                .replace(JSON_SECRET) { "${it.groupValues[1]}\"<redacted>\"" }
                .replace(EMAIL, "<redacted-email>")
        } catch (e: Throwable) {
            FAILED
        }
    }

    /** A value of breadcrumb data or a context under [key]: dropped whole when the key names a secret. Never throws. */
    fun scrubValue(key: String, value: String?): String? = try {
        if (key.lowercase().replace("-", "_") in SECRET_KEYS || key.lowercase() in SECRET_KEYS) "<redacted>" else scrub(value)
    } catch (e: Throwable) {
        FAILED
    }

    /** RN `redactDeep` for a flat map. Never throws. */
    fun scrubMap(data: Map<String, String>): Map<String, String> = try {
        data.mapValues { (k, v) -> scrubValue(k, v) ?: "" }
    } catch (e: Throwable) {
        emptyMap()
    }

    // RN IGNORED_ERROR_VALUES, IGNORED_ERROR_PATTERNS, IGNORED_ERROR_TYPES and ignoreErrors /CancelledError/
    private val IGNORED_TYPES = setOf("CancelledError", "SigningCancelledError", "CancellationException", "JobCancellationException")
    private val IGNORED_VALUES = setOf("auth.invalid_credentials", "auth.invalid_username")
    private val IGNORED_PATTERNS = listOf(Regex("check your PIN", RegexOption.IGNORE_CASE), Regex("HiveSigner signing cancelled", RegexOption.IGNORE_CASE), Regex("CancelledError"))

    /**
     * RN `isIgnoredError` and `ignoreErrors`: benign, expected errors (a wrong password or PIN, a cancelled signing,
     * a cancelled coroutine) are not reported. [type] is the exception's simple or qualified class name. Never throws.
     */
    fun shouldDrop(type: String?, message: String?): Boolean = try {
        val simple = type?.substringAfterLast('.')?.substringAfterLast('$').orEmpty()
        val value = message.orEmpty()
        simple in IGNORED_TYPES || value in IGNORED_VALUES || IGNORED_PATTERNS.any { it.containsMatchIn(value) }
    } catch (e: Throwable) {
        false
    }

    /** The shared code's own exceptions that never become events. */
    fun ignores(e: Throwable): Boolean = e is CancellationException || shouldDrop(e::class.simpleName, e.message)
}

/** RN providers/iap/errors.ts `IapStage`. */
enum class IapStage(val tag: String) { INIT("init"), PRODUCTS("products"), REQUEST("request"), PURCHASE("purchase"), FINISH("finish"), RECOVER("recover"), ORDER("order") }

/** RN `IapReport`: what [IapErrors.report] did, so the caller knows whether the user still has to hear about it. */
enum class IapReport { REPORTED, CANCELLED, DUPLICATE }

/**
 * RN providers/iap/errors.ts: store errors to Sentry. A cancellation is a breadcrumb only; a store code is its own
 * issue (fingerprint `iap` + code, tags `iap.stage`, `iap.code`, `iap.product`, `iap.platform`); a second sighting of
 * the same code and product within [DUPLICATE_WINDOW_MILLIS] is a duplicate delivery (the sheet's error can arrive
 * through the listener and the request's own failure) and is neither reported nor breadcrumbed again.
 */
class IapErrors internal constructor(private val reporter: () -> CrashReporter, private val platform: () -> String, private val nowMillis: () -> Long) {
    private val recent = mutableMapOf<String, Long>()

    fun report(error: StoreError, stage: IapStage, sku: String? = null): IapReport {
        val productId = error.productId?.takeIf { it.isNotEmpty() } ?: sku
        val now = nowMillis()
        recent.entries.removeAll { now - it.value > DUPLICATE_WINDOW_MILLIS }
        val key = "${error.code}|${productId.orEmpty()}"
        if (key in recent) return IapReport.DUPLICATE
        recent[key] = now
        if (error.isUserCancelled) {
            reporter().breadcrumb("iap", "user cancelled purchase", mapOf("stage" to stage.tag, "productId" to productId.orEmpty()))
            return IapReport.CANCELLED
        }
        reporter().capture(
            CrashReport(
                type = "IapError",
                message = "IAP ${error.code}: ${error.message.ifEmpty { "store error" }}",
                tags = buildMap {
                    put("iap.stage", stage.tag)
                    put("iap.code", error.code)
                    productId?.let { put("iap.product", it) }
                    put("iap.platform", platform())
                },
                fingerprint = listOf("iap", error.code),
                contextName = "iap",
                context = buildMap {
                    put("code", error.code)
                    put("message", error.message)
                    error.debugMessage?.let { put("debugMessage", it) }
                    error.responseCode?.let { put("responseCode", it.toString()) }
                    productId?.let { put("productId", it) }
                },
            ),
        )
        return IapReport.REPORTED
    }

    /** A failure without a store code (the order request, a thrown error): the stage tag only. */
    fun reportThrown(e: Throwable, stage: IapStage, sku: String? = null) {
        if (CrashScrubber.ignores(e)) return
        reporter().capture(
            CrashReport(
                type = e::class.simpleName ?: "IapError",
                message = "IAP ${stage.tag} failed: ${e.message.orEmpty()}",
                tags = buildMap { put("iap.stage", stage.tag); sku?.let { put("iap.product", it) }; put("iap.platform", platform()) },
            ),
        )
    }

    /** Test hook (RN resetIapErrorDedup). */
    internal fun reset() = recent.clear()

    companion object {
        const val DUPLICATE_WINDOW_MILLIS = 5_000L
    }
}
