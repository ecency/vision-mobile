package com.ecency.core.app

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Out-of-RC counts (issue #3727, README "Crash reporting"): each time an account runs out of Resource Credits is one
 * Sentry event at level info, message `rc-shortfall: <stage>/<op>` with the one fingerprint `["rc-shortfall"]`, so all
 * of them group into one issue that never alerts as an error. The stage and op are in the message as well as the tags
 * because Sentry's dedupe drops an event whose message and fingerprint equal the previous one, tags aside: a shared
 * message would keep a pre-check and drop the rejection right after it. The web app sends the same
 * (vision-web#2196), so one query covers both.
 *
 * - `rc.stage`: [STAGE_REJECTED] when the chain refused a broadcast ([ErrorReports.handled] with
 *   [ErrorKind.RESOURCE_CREDITS]); [STAGE_PRECHECK] when the editor's estimate warned, once per account and op per
 *   UTC day on this device (kept in the preferences, as the web app keeps it per browser), so precheck events
 *   approximate the users a day.
 * - `rc.op`: what was being done, from the failure's `where` ([opOf]).
 * - No account: the adapters clear the Sentry user, and per-user numbers come from the chain.
 * - Counted, not throttled: a failure handled twice (model and screen) is one event, every other one is its own, up to
 *   [MAX_EVENTS] per run. No Sentry exemption is needed, unlike the web app's: neither native SDK drops repeated
 *   message events (Android's duplicate filter only drops the same exception object, iOS has none).
 *
 * Total: it never throws into the caller.
 */
@OptIn(ExperimentalAtomicApi::class)
object RcShortfall {
    const val MESSAGE = "rc-shortfall"
    const val STAGE_REJECTED = "rejected"
    const val STAGE_PRECHECK = "precheck"
    const val OP_POST = "post"
    const val OP_COMMENT = "comment"
    const val MAX_EVENTS = 200

    /** How many reported failures are remembered by identity (one failure is handled again right where it was). */
    private const val RECENT = 16

    private const val DAY_MILLIS = 86_400_000L

    /** Wallet operations outside the `wallet.` and `transfer.` sites: a tip, a swap, a points redeem, a power down route. */
    private val WALLET_SITES = setOf("tip.confirm", "trade.swap", "redeem.confirm", "powerDown.route")

    private class State(val sent: Int, val recent: List<Throwable>)

    private val EMPTY = State(0, emptyList())
    private val state = AtomicReference(EMPTY)

    /** With [ErrorReports.install]: a fresh run. */
    internal fun reset() = state.store(EMPTY)

    /** The operation a `where` names (the sites of [AppError.from] that broadcast). */
    fun opOf(where: String): String = when {
        where == "editor.publish" || where == "crossPost.publish" || where == "editor.update" -> OP_POST
        where == "editor.reply" || where == "editor.updateReply" ||
            where == "composeReply.send" || where == "waveCompose.send" || where == "community.send" -> OP_COMMENT
        where == "post.vote" || where == "poll.vote" -> "vote"
        // the community reblog after a publish is a reblog, as on the web
        where == "postActions.reblog" || where == "editor.reblog" -> "reblog"
        // every wallet operation is a transfer, as on the web
        where.startsWith("wallet.") || where.startsWith("transfer.") || where in WALLET_SITES -> "transfer"
        else -> "other"
    }

    /** The chain refused [failure] at [where] for Resource Credits. */
    internal fun rejected(failure: Throwable, where: String) = record(STAGE_REJECTED, opOf(where)) { s ->
        if (s.recent.any { it === failure }) null else State(s.sent + 1, (s.recent + failure).takeLast(RECENT))
    }

    /**
     * The editor's estimate says [account]'s credits are too low for what is written ([op]: [OP_POST] or
     * [OP_COMMENT]): sent once per account and op per UTC day, remembered in [prefs] (the account stays on the device).
     */
    fun precheck(prefs: Prefs, nowMillis: Long, account: String, op: String) {
        try {
            val key = "rcShortfall.precheck.$account.$op"
            val day = (nowMillis / DAY_MILLIS).toString()
            if (prefs.getString(key) == day) return
            prefs.putString(key, day)
            record(STAGE_PRECHECK, op) { s -> State(s.sent + 1, s.recent) }
        } catch (t: Throwable) {
            // not reported: counting must never fail the caller
        }
    }

    private fun record(stage: String, op: String, next: (State) -> State?) {
        try {
            while (true) {
                val before = state.load()
                if (before.sent >= MAX_EVENTS) return
                val after = next(before) ?: return
                if (state.compareAndSet(before, after)) break
            }
            ErrorReports.emit(
                CrashReport(
                    type = MESSAGE,
                    message = "$MESSAGE: $stage/$op",
                    tags = mapOf("rc.stage" to stage, "rc.op" to op),
                    fingerprint = listOf(MESSAGE),
                    isMessage = true,
                    level = "info",
                ),
            )
        } catch (t: Throwable) {
            // not reported: counting must never fail the caller
        }
    }
}
