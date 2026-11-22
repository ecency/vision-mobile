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

    private fun shortfalls() = reporter.reports.filter { it.message == RcShortfall.MESSAGE }

    private fun outOfRc() = BroadcastException.InsufficientResourceCredits(RuntimeException("Account: spike has 100 RC, needs 2000 RC. Please wait to transact, or power up HIVE."))

    @Test
    fun aPublishRejectedForResourceCreditsIsOneShortfallReport() {
        AppError.fromBroadcast(outOfRc(), "editor.publish")
        val r = shortfalls().single()
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
            "editor.publish" to "post", "crossPost.publish" to "post",
            "composeReply.send" to "comment", "waveCompose.send" to "comment", "community.send" to "comment",
            "post.vote" to "vote", "poll.vote" to "vote", "postActions.reblog" to "reblog",
            "wallet.send" to "transfer", "wallet.claim" to "transfer", "transfer.confirm" to "transfer",
            "chat.send" to "other", "" to "other",
        )
        expected.forEach { (where, op) -> assertEquals(op, RcShortfall.opOf(where), where) }
    }

    @Test
    fun thePrecheckIsReportedOncePerAccountPerRun() {
        repeat(3) { RcShortfall.precheck("alice", RcShortfall.OP_POST) }
        RcShortfall.precheck("bob", RcShortfall.OP_COMMENT)
        val sent = shortfalls()
        assertEquals(2, sent.size)
        assertEquals(listOf("precheck", "precheck"), sent.map { it.tags["rc.stage"] })
        assertEquals(listOf("post", "comment"), sent.map { it.tags["rc.op"] })
        assertTrue(sent.none { r -> r.tags.values.any { it == "alice" || it == "bob" } && r.message.contains("alice") }, "no account in the report")
    }

    @Test
    fun aFailingReporterNeverReachesTheBroadcastFlow() {
        ErrorReports.install({ object : CrashReporter {
            override fun capture(report: CrashReport) = throw IllegalStateException("sentry down")
            override fun breadcrumb(category: String, message: String, data: Map<String, String>) {}
        } }, { now })
        val error = AppError.fromBroadcast(outOfRc(), "editor.publish")
        assertEquals(ErrorKind.RESOURCE_CREDITS, error.kind)
        RcShortfall.precheck("alice", RcShortfall.OP_POST)
    }
}
