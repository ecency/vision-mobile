package com.ecency.core.app

import com.ecency.core.api.broadcast.BroadcastException
import com.ecency.core.api.broadcast.ChainRefusal
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
 * - Floods: one event per fingerprint per [THROTTLE_MILLIS], at most [MAX_REPORTS] errors and [MAX_WARNINGS] warnings per run (counted apart), and the same exception
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
    const val MAX_WARNINGS = 30
    const val MESSAGE_CHARS = 120
    const val STACK_LINES = 40

    /**
     * How many recent exceptions are remembered by identity. Common Kotlin has no identity hash on every target, so the
     * objects themselves are held: a short bounded list (an exception is mapped again right where it was first mapped).
     */
    private const val RECENT = 16

    private class Sink(val reporter: () -> CrashReporter, val nowMillis: () -> Long)

    /** [recent]: the exceptions already reported, each with the code it was shown with (null for an expected one). */
    private class Seen(val errors: Int, val warnings: Int, val lastSent: Map<String, Long>, val recent: List<Pair<Throwable, String?>>)

    private val NONE = Seen(0, 0, emptyMap(), emptyList())
    private val sink = AtomicReference<Sink?>(null)
    private val seen = AtomicReference(NONE)

    /** [AppEnvironment] at its creation: the reports go to [reporter], with a fresh count and throttle. */
    internal fun install(reporter: () -> CrashReporter, nowMillis: () -> Long) {
        sink.store(Sink(reporter, nowMillis))
        seen.store(NONE)
    }

    /**
     * A failure the UI shows with its own text (a toast, an alert, an error line) at [where]: reported as handled, so a
     * lost connection still counts (as an expected one), unlike [quiet].
     */
    fun handled(e: Throwable, where: String) {
        AppError.from(e, where)
    }

    /**
     * A failure the UI shows, as [AppError.from] mapped it at [where]. Returns the code the screen shows with it (issue
     * #3660), the report's `error_code` tag: for a failure that is not expected (see the class comment), else null. The
     * code depends only on the fingerprint, so it is the same when this report is throttled or capped.
     */
    fun handled(e: Throwable, where: String, mapped: AppError): String? = try {
        val root = rootOf(e)
        if (skips(e, root, mapped.kind)) {
            null
        } else {
            val expected = expected(e, root, mapped.kind)
            send(root, where, mapped.kind.name.lowercase(), expected, root)
        }
    } catch (t: Throwable) {
        // not reported: reporting must never fail the caller
        null
    }

    /**
     * A failure the app swallows on purpose at [where] (a quiet refresh, a best-effort background read): a warning. A
     * lost connection is not sent at all: offline, every quiet refresh would fail the same way.
     */
    fun quiet(e: Throwable, where: String) {
        try {
            val root = rootOf(e)
            val kind = AppError.kindOf(e)
            if (kind == ErrorKind.NETWORK || skips(e, root, kind)) return
            send(root, where, kind.name.lowercase(), true, root, quiet = true)
        } catch (t: Throwable) {
            // not reported: reporting must never fail the caller
        }
    }

    /**
     * A failure the iOS app caught that is not a Kotlin exception (a Swift error): [type] is its type name, [code] its
     * domain and code (`NSURLErrorDomain -1009`), which groups it, [message] its description (in the device language, so
     * it stays out of the fingerprint), [stack] whatever the platform has (may be empty). [network]: a lost connection,
     * a warning when shown and nothing when [quiet]. Otherwise [quiet] as [ErrorReports.quiet], else kind UNKNOWN.
     */
    fun foreign(type: String, code: String, message: String, stack: String, where: String, quiet: Boolean, network: Boolean) {
        try {
            if (network && quiet) return
            if (CrashScrubber.shouldDrop(type, message)) return
            val kind = if (network) ErrorKind.NETWORK else ErrorKind.UNKNOWN
            send(Foreign(type, code, message, stack), where, kind.name.lowercase(), quiet || network, null, quiet)
        } catch (t: Throwable) {
            // not reported: reporting must never fail the caller
        }
    }

    private class Foreign(val type: String, val code: String, message: String, val stack: String) : Throwable(message)

    private fun rootOf(e: Throwable): Throwable = if (e is AppException) e.cause ?: e else e

    private fun skips(e: Throwable, root: Throwable, kind: ErrorKind): Boolean =
        kind == ErrorKind.CANCELLED || e is CancellationException || root is CancellationException ||
            CrashScrubber.ignores(e) || CrashScrubber.ignores(root)

    private fun expected(e: Throwable, root: Throwable, kind: ErrorKind): Boolean = when {
        kind in EXPECTED_KINDS -> true
        ChainRefusal.of(e)?.isUsersOwn == true -> true
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
        (e is BroadcastException && e !is BroadcastException.Rejected) ||
            e is AppException || e is com.ecency.core.api.chat.ChatApiException

    /**
     * What the fingerprint keeps of the message: a Swift error's domain and code; nothing for catalog copy and for a
     * lost connection (the platform's own text, Darwin's NSError description, is in the device language); else the
     * message normalised.
     */
    private fun fingerprintMessage(e: Throwable, kind: String): String = when {
        e is Foreign -> e.code
        // each refusal its own issue, whatever language its copy is in
        e is BroadcastException.Refused -> e.refusal.name
        e is BroadcastException.MissingAuthority -> e.refusal.name
        e is BroadcastException.CommentTooSoon -> e.refusal.name
        kind == "network" || isCopy(e) -> ""
        else -> normalise(e.message.orEmpty())
    }

    private fun typeOf(e: Throwable): String = (e as? Foreign)?.type ?: e::class.simpleName ?: "Throwable"

    /** Sends the report unless throttled or capped; returns the code of an unexpected failure (see [handled]). */
    private fun send(e: Throwable, where: String, kind: String, expected: Boolean, identity: Throwable?, quiet: Boolean = false): String? {
        val type = typeOf(e)
        val fingerprint = listOf("handled", where, kind, type, fingerprintMessage(e, kind))
        val key = fingerprint.joinToString("\u0000")
        val code = if (expected || quiet) null else codeOf(key)
        val s = sink.load() ?: return code
        // addresses, URIs and file paths never leave as they are (an upload URL, a picked file's content:// URI)
        val message = withoutPlaces(e.message.orEmpty())
        val now = s.nowMillis()
        while (true) {
            val before = seen.load()
            // separate caps: offline warnings never use up the room a later error needs
            if (if (expected) before.warnings >= MAX_WARNINGS else before.errors >= MAX_REPORTS) return code
            // reported once, at its first place: shown again elsewhere, it keeps that report's code
            if (identity != null) before.recent.firstOrNull { it.first === identity }?.let { return it.second }
            val last = before.lastSent[key]
            if (last != null && now - last in 0 until THROTTLE_MILLIS) return code
            val after = Seen(
                if (expected) before.errors else before.errors + 1,
                if (expected) before.warnings + 1 else before.warnings,
                before.lastSent.filterValues { now - it in 0 until THROTTLE_MILLIS } + (key to now),
                if (identity == null) before.recent else (before.recent + (identity to code)).takeLast(RECENT),
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
                    if (code != null) put("error_code", code)
                },
                fingerprint = fingerprint,
                contextName = "handled",
                context = mapOf("where" to where, "kind" to kind, "exception" to type, "stack" to stackOf(e)),
                level = if (expected) "warning" else "error",
            ),
        )
        return code
    }

    /**
     * Six letters and digits from the fingerprint (FNV-1a, 30 bits in base 32 without I, L, O, U): what a screenshot
     * shows and Sentry's `error_code` tag finds. The fingerprint holds no user data, so neither does the code.
     */
    internal fun codeOf(key: String): String {
        var h = 0x811c9dc5.toInt()
        for (b in key.encodeToByteArray()) {
            h = h xor (b.toInt() and 0xff)
            h *= 16777619
        }
        var v = h ushr 2
        return buildString { repeat(6) { append(CODE_ALPHABET[v and 31]); v = v ushr 5 } }
    }

    private const val CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /**
     * The exception's own stack trace, first [STACK_LINES] lines, as frames and type names only: the first line and
     * every `Caused by:` / `Suppressed:` keep the exception's type, never its message (a parser's message quotes the
     * text it read, which can be vault JSON), and any other line that is not a frame is dropped.
     */
    internal fun stackOf(e: Throwable): String {
        if (e is Foreign) return e.stack.lineSequence().take(STACK_LINES).joinToString("\n")
        val out = mutableListOf(e::class.simpleName ?: "Throwable")
        for (line in e.stackTraceToString().lineSequence().drop(1)) {
            if (out.size >= STACK_LINES) break
            val t = line.trimStart()
            val indent = line.substring(0, line.length - t.length)
            when {
                line.firstOrNull()?.isWhitespace() == true && FRAME.matches(t) -> out += line
                t.startsWith("... ") -> out += line
                t.startsWith(CAUSED) -> out += indent + CAUSED + typeName(t.removePrefix(CAUSED))
                t.startsWith(SUPPRESSED) -> out += indent + SUPPRESSED + typeName(t.removePrefix(SUPPRESSED))
            }
        }
        return out.joinToString("\n")
    }

    private const val CAUSED = "Caused by: "
    private const val SUPPRESSED = "Suppressed: "

    /** A frame line: `at` and a symbol (JVM `at a.b.C.f(File.kt:1)`, Kotlin/Native `at 0 Module 0x... kfun:...`). */
    private val FRAME = Regex("^at \\S.*")

    /** `com.x.FooException: message` to `FooException`. */
    private fun typeName(header: String): String =
        header.substringBefore(':').trim().substringAfterLast('.').substringAfterLast('$').ifEmpty { "Throwable" }

    private val PATH = Regex("(?<![\\w.:/<>-])/(?:[\\w.@%+~-]+/)+[\\w.@%+~-]*")

    /** URLs and URIs (any scheme, content:// and file:// included) and absolute file paths replaced by placeholders. */
    fun withoutPlaces(text: String): String = text.replace(URL, "<url>").replace(PATH, "<path>")

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
            .let(::withoutPlaces)
            .replace(QUOTED, "<q>")
            .replace(AT_NAME, "@<user>")
            .replace(HEX, "<hex>")
            .replace(NUMBER, "<n>")
            .replace(SPACES, " ")
            .trim()
            .take(MESSAGE_CHARS)
    }
}
