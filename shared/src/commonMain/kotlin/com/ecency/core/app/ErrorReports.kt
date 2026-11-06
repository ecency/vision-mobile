package com.ecency.core.app

import com.ecency.core.auth.AuthException
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException

/**
 * Handled failures to Sentry (README "Crash reporting", "Handled errors"): every failure the UI shows goes through
 * [AppError.from] / [AppError.fromBroadcast] with a short stable `where` ("editor.publish", "post.vote") and lands
 * here as [handled]; a failure the app swallows on purpose (a quiet refresh, a best-effort read) is [quiet].
 *
 * One per process: [AppEnvironment] installs it over its own [AppEnvironment.crashReporter], which is
 * [CrashReporter.None] until the platform sets Sentry (and in tests, until a test sets a recording reporter). Before
 * that nothing is sent.
 *
 * - Never reported: a cancellation ([CancellationException], [ErrorKind.CANCELLED]) and RN's benign errors
 *   ([CrashScrubber.ignores]).
 * - Level "warning" with tag `expected=true` for what is not a bug of the app: no connection, the user's own sign-in
 *   mistakes, the chain's per-user refusals (cooldown, Resource Credits, a paid-out post, a vote already cast) and the
 *   app's own refusals (an [AppException] without a cause). Everything else is "error".
 * - Grouping by an explicit fingerprint (never the stack of this one site, which would make one issue of every
 *   failure): `handled`, where, kind, exception type and the message with its numbers, hex, quoted names and @names
 *   replaced, cut to [MESSAGE_CHARS].
 * - Floods: one event per fingerprint per [THROTTLE_MILLIS], at most [MAX_REPORTS] per run, and the same exception
 *   reported once however often it is mapped.
 * - Privacy: everything goes out as a [CrashReport], so every text passes the platform's `beforeSend` scrubber; the
 *   fingerprint's message is scrubbed here too. `where` names a place in the code, never user data.
 *
 * Total: it never throws into the code that called [AppError.from].
 */
@OptIn(ExperimentalAtomicApi::class)
object ErrorReports {
    const val THROTTLE_MILLIS = 5 * 60_000L
    const val MAX_REPORTS = 50
    const val MESSAGE_CHARS = 120
    const val STACK_LINES = 40

    /** How many recent exceptions are remembered by identity. */
    private const val RECENT = 64

    private class Sink(val reporter: () -> CrashReporter, val nowMillis: () -> Long)

    private class Seen(val count: Int, val lastSent: Map<String, Long>, val recent: List<Throwable>)

    private val sink = AtomicReference<Sink?>(null)
    private val seen = AtomicReference(Seen(0, emptyMap(), emptyList()))

    /** [AppEnvironment] at its creation: the reports go to [reporter], with a fresh count and throttle. */
    internal fun install(reporter: () -> CrashReporter, nowMillis: () -> Long) {
        sink.store(Sink(reporter, nowMillis))
        seen.store(Seen(0, emptyMap(), emptyList()))
    }

    /** A failure the UI shows, as [AppError.from] mapped it at [where]. */
    fun handled(e: Throwable, where: String, mapped: AppError) {
        try {
            val root = rootOf(e)
            if (skips(e, root, mapped.kind)) return
            send(root, where, mapped.kind.name.lowercase(), expected(e, root, mapped.kind), root)
        } catch (t: Throwable) {
            // not reported: reporting must never fail the caller
        }
    }

    /** A failure the app swallows on purpose at [where] (a quiet refresh, a best-effort background read): a warning. */
    fun quiet(e: Throwable, where: String) {
        try {
            val root = rootOf(e)
            val kind = AppError.kindOf(e)
            if (skips(e, root, kind)) return
            send(root, where, kind.name.lowercase(), true, root, quiet = true)
        } catch (t: Throwable) {
            // not reported: reporting must never fail the caller
        }
    }

    /**
     * A failure the iOS app caught that is not a Kotlin exception (a Swift error): [type] is its type name, [stack]
     * whatever the platform has (may be empty). [quiet] as [ErrorReports.quiet], else as [handled] with kind UNKNOWN.
     */
    fun foreign(type: String, message: String, stack: String, where: String, quiet: Boolean) {
        try {
            if (CrashScrubber.shouldDrop(type, message)) return
            send(Foreign(type, message, stack), where, ErrorKind.UNKNOWN.name.lowercase(), quiet, null, quiet)
        } catch (t: Throwable) {
            // not reported: reporting must never fail the caller
        }
    }

    private class Foreign(val type: String, message: String, val stack: String) : Throwable(message)

    private fun rootOf(e: Throwable): Throwable = if (e is AppException) e.cause ?: e else e

    private fun skips(e: Throwable, root: Throwable, kind: ErrorKind): Boolean =
        kind == ErrorKind.CANCELLED || e is CancellationException || root is CancellationException ||
            CrashScrubber.ignores(e) || CrashScrubber.ignores(root)

    private fun expected(e: Throwable, root: Throwable, kind: ErrorKind): Boolean = when {
        kind in EXPECTED_KINDS -> true
        e is AppException && e.cause == null -> true
        root is AuthException -> isUserMistake(root)
        else -> false
    }

    private val EXPECTED_KINDS = setOf(
        ErrorKind.NETWORK, ErrorKind.TOO_SOON, ErrorKind.RESOURCE_CREDITS, ErrorKind.PAID_OUT, ErrorKind.DUPLICATE_VOTE,
    )

    /** What the user typed or chose, not something the app did wrong. */
    private fun isUserMistake(e: AuthException): Boolean = when (e) {
        is AuthException.InvalidUsername, is AuthException.UnknownAccount, is AuthException.WrongPassword,
        is AuthException.KeyNotOnAccount, is AuthException.PublicKeyGiven, is AuthException.InvalidKey,
        is AuthException.MemoKeyOnly, is AuthException.KeyBelowThreshold, is AuthException.OwnerKeyOnly,
        is AuthException.KeySharedWithOwner, is AuthException.NotSignedIn, is AuthException.MissingKey,
        is AuthException.InvalidLink, is AuthException.SessionExpired -> true
        is AuthException.HiveAuth -> e.reason == AuthException.HiveAuth.Reason.REJECTED || e.reason == AuthException.HiveAuth.Reason.EXPIRED
        else -> false
    }

    /**
     * The message is catalog copy in the app language (a classified broadcast failure, the app's own refusal): it
     * stays out of the fingerprint, so one bug is one issue in every language.
     */
    private fun isCopy(e: Throwable): Boolean =
        (e is com.ecency.core.api.broadcast.BroadcastException && e !is com.ecency.core.api.broadcast.BroadcastException.Rejected) ||
            e is AppException

    private fun typeOf(e: Throwable): String = (e as? Foreign)?.type ?: e::class.simpleName ?: "Throwable"

    private fun send(e: Throwable, where: String, kind: String, expected: Boolean, identity: Throwable?, quiet: Boolean = false) {
        val s = sink.load() ?: return
        val type = typeOf(e)
        val message = e.message.orEmpty()
        val fingerprint = listOf("handled", where, kind, type, if (isCopy(e)) "" else normalise(message))
        val key = fingerprint.joinToString("\u0000")
        val now = s.nowMillis()
        while (true) {
            val before = seen.load()
            if (before.count >= MAX_REPORTS) return
            if (identity != null && before.recent.any { it === identity }) return
            val last = before.lastSent[key]
            if (last != null && now - last in 0 until THROTTLE_MILLIS) return
            val after = Seen(
                before.count + 1,
                before.lastSent.filterValues { now - it in 0 until THROTTLE_MILLIS } + (key to now),
                if (identity == null) before.recent else (before.recent + identity).takeLast(RECENT),
            )
            if (seen.compareAndSet(before, after)) break
        }
        s.reporter().capture(
            CrashReport(
                type = type,
                message = message,
                tags = buildMap {
                    put("handled", "true")
                    put("where", where)
                    put("error.kind", kind)
                    put("exception", type)
                    if (quiet) put("quiet", "true")
                    if (expected) put("expected", "true")
                },
                fingerprint = fingerprint,
                contextName = "handled",
                context = mapOf("where" to where, "kind" to kind, "exception" to type, "stack" to stackOf(e)),
                level = if (expected) "warning" else "error",
            ),
        )
    }

    /** The exception's own stack trace (its causes included), first [STACK_LINES] lines. */
    internal fun stackOf(e: Throwable): String {
        if (e is Foreign) return e.stack.lineSequence().take(STACK_LINES).joinToString("\n")
        return e.stackTraceToString().lineSequence().take(STACK_LINES).joinToString("\n")
    }

    private val URL = Regex("\\b[a-z][a-z0-9+.-]*://[^\\s,;)]*[^\\s,;):.]",RegexOption.IGNORE_CASE)
    private val QUOTED = Regex("\"[^\"]*\"|'[^']*'|`[^`]*`")
    private val AT_NAME = Regex("@[a-z0-9][a-z0-9.-]{1,15}", RegexOption.IGNORE_CASE)
    private val HEX = Regex("\\b(?:0x)?(?=[0-9a-fA-F]*[0-9])(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{6,}\\b")
    private val NUMBER = Regex("\\d+(\\.\\d+)?")
    private val SPACES = Regex("\\s+")

    /**
     * The message as the fingerprint keeps it: secrets scrubbed, then addresses (the node that answered last),
     * quoted names, @names, hex and numbers replaced (an account, a node, a block number or a trx id must not split one
     * bug into many issues), cut to [MESSAGE_CHARS].
     */
    fun normalise(message: String): String {
        val scrubbed = CrashScrubber.scrub(message) ?: return ""
        return scrubbed
            .replace(URL, "<url>")
            .replace(QUOTED, "<q>")
            .replace(AT_NAME, "@<user>")
            .replace(HEX, "<hex>")
            .replace(NUMBER, "<n>")
            .replace(SPACES, " ")
            .trim()
            .take(MESSAGE_CHARS)
    }
}
