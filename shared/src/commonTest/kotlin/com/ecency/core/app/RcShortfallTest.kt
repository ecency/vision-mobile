package com.ecency.core.app

import com.ecency.core.api.broadcast.BroadcastException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Issue #3727: every out-of-RC moment is one grouped `rc-shortfall` info event (the web app sends the same, vision-web#2196). */
class RcShortfallTest {
    private val reporter = RecordingReporter()
    private var now = 1_000_000L

    @BeforeTest
    fun install() = ErrorReports.install({ reporter }, { now })

    @AfterTest
    fun uninstall() = ErrorReports.install({ CrashReporter.None }, { 0L })

    private fun shortfalls() = reporter.reports.filter { it.fingerprint == listOf(RcShortfall.MESSAGE) }

    private fun outOfRc() = BroadcastException.InsufficientResourceCredits(RuntimeException("Account: spike has 100 RC, needs 2000 RC. Please wait to transact, or power up HIVE."))

    @Test
    fun aPublishRejectedForResourceCreditsIsOneShortfallReport() {
        AppError.fromBroadcast(outOfRc(), "editor.publish")
        val r = shortfalls().single()
        assertEquals("rc-shortfall: rejected/post", r.message, "the web app's message (vision-web#2199)")
        assertEquals("info", r.level)
        assertEquals(listOf("rc-shortfall"), r.fingerprint)
        assertTrue(r.isMessage, "a message, not an exception")
        assertEquals("rejected", r.tags["rc.stage"])
        assertEquals("post", r.tags["rc.op"])
    }

    @Test
    fun oneFailureHandledTwiceIsCountedOnce() {
        val e = outOfRc()
        AppError.fromBroadcast(e, "composeReply.send")
        AppError.fromBroadcast(e, "composeReply.send")
        assertEquals(1, shortfalls().size)
        // another failure is another moment, however soon (the count is the point, no throttle)
        AppError.fromBroadcast(outOfRc(), "composeReply.send")
        assertEquals(2, shortfalls().size)
        assertEquals("comment", shortfalls().last().tags["rc.op"])
    }

    @Test
    fun anotherRefusalIsNoShortfall() {
        AppError.fromBroadcast(BroadcastException.Rejected("missing required posting authority", RuntimeException("x")), "editor.publish")
        AppError.from(IllegalStateException("boom"), "post.vote")
        assertEquals(emptyList(), shortfalls())
    }

    @Test
    fun theOperationIsNamedFromWhereItFailed() {
        val expected = mapOf(
            "editor.publish" to "post", "crossPost.publish" to "post", "editor.update" to "post",
            // the full editor's replies and reply edits (PR #3730 review)
            "editor.reply" to "comment", "editor.updateReply" to "comment",
            "composeReply.send" to "comment", "waveCompose.send" to "comment", "community.send" to "comment",
            "post.vote" to "vote", "poll.vote" to "vote", "postActions.reblog" to "reblog",
            // the community reblog after a publish is a reblog (the web app's rule too)
            "editor.reblog" to "reblog",
            // every wallet operation is a transfer, as on the web
            "wallet.send" to "transfer", "wallet.claim" to "transfer", "transfer.confirm" to "transfer",
            "tip.confirm" to "transfer", "trade.swap" to "transfer", "redeem.confirm" to "transfer", "powerDown.route" to "transfer",
            "chat.send" to "other", "" to "other",
        )
        expected.forEach { (where, op) -> assertEquals(op, RcShortfall.opOf(where), where) }
    }

    @Test
    fun thePrecheckIsReportedOncePerAccountAndOpPerUtcDay() {
        // as the web app: precheck events approximate unique users a day (vision-web#2199)
        val prefs = InMemoryPrefs()
        val day = 86_400_000L
        val noon = 20_000 * day + day / 2
        repeat(3) { RcShortfall.precheck(prefs, noon, "alice", RcShortfall.OP_POST) }
        RcShortfall.precheck(prefs, noon, "alice", RcShortfall.OP_COMMENT)
        RcShortfall.precheck(prefs, noon, "bob", RcShortfall.OP_COMMENT)
        // the app restarted the same day: still counted
        ErrorReports.install({ reporter }, { now })
        RcShortfall.precheck(prefs, noon + day / 4, "alice", RcShortfall.OP_POST)
        // the next UTC day counts again
        RcShortfall.precheck(prefs, noon + day, "alice", RcShortfall.OP_POST)
        val sent = shortfalls()
        assertEquals(
            listOf("rc-shortfall: precheck/post", "rc-shortfall: precheck/comment", "rc-shortfall: precheck/comment", "rc-shortfall: precheck/post"),
            sent.map { it.message },
        )
        assertTrue(sent.all { it.tags["rc.stage"] == "precheck" })
        assertTrue(sent.none { r -> "alice" in r.message || "bob" in r.message || r.tags.values.any { it == "alice" || it == "bob" } }, "no account in the report")
    }

    @Test
    fun aFailingReporterNeverReachesTheBroadcastFlow() {
        ErrorReports.install({ object : CrashReporter {
            override fun capture(report: CrashReport) = throw IllegalStateException("sentry down")
            override fun breadcrumb(category: String, message: String, data: Map<String, String>) {}
        } }, { now })
        val error = AppError.fromBroadcast(outOfRc(), "editor.publish")
        assertEquals(ErrorKind.RESOURCE_CREDITS, error.kind)
        RcShortfall.precheck(InMemoryPrefs(), now, "alice", RcShortfall.OP_POST)
    }
}
