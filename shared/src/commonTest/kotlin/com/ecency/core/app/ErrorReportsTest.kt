package com.ecency.core.app

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.broadcast.BroadcastException
import com.ecency.core.api.rpc.AllNodesFailedException
import com.ecency.core.auth.AuthException
import com.ecency.core.l10n.L
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Handled and quiet failures to Sentry (ErrorReports, README "Crash reporting"). */
class ErrorReportsTest {
    private val reporter = RecordingReporter()
    private var now = 1_000_000L

    @BeforeTest
    fun install() = ErrorReports.install({ reporter }, { now })

    @AfterTest
    fun uninstall() = ErrorReports.install({ CrashReporter.None }, { 0L })

    private fun rejected(message: String) = BroadcastException.Rejected(message, RuntimeException(message))

    @Test
    fun aMappedFailureIsOneEventGroupedByWhereKindTypeAndMessage() {
        val error = AppError.from(rejected("Assert Exception: post body too long"), "editor.publish")
        assertEquals(ErrorKind.REJECTED, error.kind)
        val r = reporter.reports.single()
        assertEquals("Rejected", r.type)
        assertEquals("Assert Exception: post body too long", r.message)
        assertEquals(listOf("handled", "editor.publish", "rejected", "Rejected", "Assert Exception: post body too long"), r.fingerprint)
        assertEquals("error", r.level)
        assertEquals("true", r.tags["handled"])
        assertEquals("editor.publish", r.tags["where"])
        assertEquals("rejected", r.tags["error.kind"])
        assertEquals("Rejected", r.tags["exception"])
        assertFalse("expected" in r.tags)
    }

    @Test
    fun anUnexpectedFailureShowsTheCodeItsReportIsTaggedWith() {
        // issue #3660: a tester's screenshot finds the Sentry issue
        val error = AppError.from(IllegalStateException("boom"), "editor.publish")
        val code = error.code
        assertTrue(code != null && Regex("^[0-9A-Z]{6}$").matches(code), "six letters or digits: $code")
        assertEquals(code, reporter.reports.single().tags["error_code"])
        assertEquals(AppError.GENERIC_MESSAGE + "\n" + L.native.appError.errorCode(code = code), error.text, "said once, then the code")
        // the same fingerprint, the same code, even when the report itself is throttled
        assertEquals(code, AppError.from(IllegalStateException("boom"), "editor.publish").code)
        assertNotEquals(code, AppError.from(IllegalStateException("boom"), "post.vote").code)
    }

    @Test
    fun aFailureMappedAgainKeepsTheCodeItsReportWasSentWith() {
        val first = AppError.from(IllegalStateException("boom"), "community.send")
        val again = AppError.from(AppException(first, IllegalStateException("boom")), "communityMembers.setRole")
        assertEquals(first.code, again.code)
    }

    @Test
    fun oneFailureShownAtTwoPlacesShowsTheCodeOfItsOneReport() {
        val e = IllegalStateException("boom")
        val first = AppError.from(e, "community.send")
        val second = AppError.from(e, "communityMembers.setRole")
        assertEquals(1, reporter.reports.size)
        assertEquals(first.code, second.code)
        assertEquals(reporter.reports.single().tags["error_code"], second.code)
    }

    @Test
    fun anExpectedRefusalCarriesNoCode() {
        val error = AppError.fromBroadcast(BroadcastException.InsufficientResourceCredits(RuntimeException("rc")), "post.vote")
        assertEquals(null, error.code)
        assertEquals(error.message, error.text)
        assertFalse("error_code" in reporter.reports.single().tags)
        assertEquals(null, AppError.from(AllNodesFailedException("bridge.get_ranked_posts", emptyList()), "feed.load").code)
    }

    @Test
    fun theSameFailureAtAnotherPlaceOrOfAnotherKindIsAnotherIssue() {
        AppError.from(rejected("refused"), "editor.publish")
        AppError.from(rejected("refused"), "post.vote")
        AppError.fromBroadcast(BroadcastException.InsufficientResourceCredits(RuntimeException("rc")), "post.vote")
        val prints = reporter.reports.map { it.fingerprint }
        assertEquals(3, prints.distinct().size)
        assertEquals(listOf("editor.publish", "post.vote", "post.vote"), prints.map { it[1] })
        assertEquals(listOf("rejected", "rejected", "resource_credits"), prints.map { it[2] })
        // its message is copy in the app language: the fingerprint leaves it out, so one issue holds every language
        assertEquals("", prints[2][4])
    }

    @Test
    fun theFingerprintsMessageDropsNumbersHexNamesAndSecrets() {
        assertEquals(
            "block <n> trx <hex> by @<user> for <q> failed",
            ErrorReports.normalise("block 98765432 trx 4f2a9c81bd by @good-karma for \"my-post\" failed"),
        )
        assertEquals("missing <q> on <q>", ErrorReports.normalise("missing 'posting' on `alice`"))
        assertEquals(
            "get_following failed on all <n> nodes: <url>: HTTP <n>",
            ErrorReports.normalise("get_following failed on all 6 nodes: https://node-c.example: HTTP 500"),
        )
        val wif = "5" + "J" + "1".repeat(50)
        assertFalse(ErrorReports.normalise("bad key $wif").contains(wif))
        assertEquals(ErrorReports.MESSAGE_CHARS, ErrorReports.normalise("x ".repeat(200)).length)
        // two accounts, one issue
        AppError.from(rejected("account @alice has 12 votes"), "post.vote")
        now += ErrorReports.THROTTLE_MILLIS
        AppError.from(rejected("account @bob has 3 votes"), "post.vote")
        assertEquals(1, reporter.reports.map { it.fingerprint }.distinct().size)
    }

    @Test
    fun networkAndTheUsersOwnMistakesAreExpectedWarnings() {
        AppError.from(AllNodesFailedException("bridge.get_ranked_posts", emptyList()), "feed.load")
        AppError.from(AuthException.WrongPassword(), "login.password")
        AppError.from(AppException(AppError(ErrorKind.INVALID_INPUT, "blank", false)), "editor.publish")
        AppError.from(IllegalStateException("boom"), "editor.publish")
        assertEquals(listOf("warning", "warning", "warning", "error"), reporter.reports.map { it.level })
        assertEquals(listOf("true", "true", "true", null), reporter.reports.map { it.tags["expected"] })
    }

    @Test
    fun cancellationsAreNeverReported() {
        AppError.from(CancellationException("left the screen"), "editor.publish")
        AppError.from(AuthException.Cancelled(), "post.vote")
        AppError.fromBroadcast(AppException(AppError(ErrorKind.CANCELLED, "closed", false)), "post.vote")
        ErrorReports.quiet(CancellationException("gone"), "feed.refresh")
        assertEquals(emptyList(), reporter.reports)
    }

    @Test
    fun theSameIssueIsSentOncePerFiveMinutes() {
        repeat(3) { AppError.from(IllegalStateException("boom"), "wallet.load") }
        assertEquals(1, reporter.reports.size)
        now += ErrorReports.THROTTLE_MILLIS - 1
        AppError.from(IllegalStateException("boom"), "wallet.load")
        assertEquals(1, reporter.reports.size)
        now += 1
        AppError.from(IllegalStateException("boom"), "wallet.load")
        assertEquals(2, reporter.reports.size)
    }

    @Test
    fun aRunSendsAtMostFiftyReports() {
        repeat(ErrorReports.MAX_REPORTS + 10) { AppError.from(IllegalStateException("boom"), "area.place$it") }
        assertEquals(ErrorReports.MAX_REPORTS, reporter.reports.size)
    }

    @Test
    fun warningsHaveTheirOwnCapSoTheyNeverCrowdOutAnError() {
        repeat(ErrorReports.MAX_WARNINGS + 10) { ErrorReports.quiet(IllegalStateException("boom"), "area.quiet$it") }
        assertEquals(ErrorReports.MAX_WARNINGS, reporter.reports.size)
        AppError.from(IllegalStateException("boom"), "editor.publish")
        assertEquals("error", reporter.reports.last().level)
        assertEquals(ErrorReports.MAX_WARNINGS + 1, reporter.reports.size)
    }

    @Test
    fun aQuietFailureOfTheConnectionIsNotSent() {
        ErrorReports.quiet(AllNodesFailedException("bridge.get_ranked_posts", emptyList()), "feed.refresh")
        ErrorReports.quiet(EcencyHttpException.ofSendFailure(IllegalStateException("offline")), "leaderboard.refresh")
        assertEquals(emptyList(), reporter.reports)
    }

    /** Review of #3659: a failure the screen shows with its own text is handled, so offline it still counts. */
    @Test
    fun aShownFailureOfTheConnectionIsAnExpectedWarning() {
        ErrorReports.handled(EcencyHttpException.ofSendFailure(IllegalStateException("offline")), "emailDigests.stopAll")
        ErrorReports.handled(IllegalStateException("boom"), "digest.leave")
        assertEquals(listOf("warning", "error"), reporter.reports.map { it.level })
        assertEquals(listOf("true", null), reporter.reports.map { it.tags["expected"] })
    }

    @Test
    fun aNetworkFailureGroupsWithoutItsMessage() {
        // the platform's text (Darwin's NSError description) is in the device language
        AppError.from(AllNodesFailedException("m", emptyList()), "feed.load")
        assertEquals(listOf("handled", "feed.load", "network", "AllNodesFailedException", ""), reporter.reports.single().fingerprint)
        // a chat failure's text is the server's or the catalog's fallback in the app language
        AppError.from(com.ecency.core.api.chat.ChatApiException(400, "Anfrage fehlgeschlagen"), "chats.load")
        assertEquals("", reporter.reports.last().fingerprint[4])
    }

    @Test
    fun theMessageSentLosesAddressesUrisAndPaths() {
        AppError.from(IllegalStateException("copy of content://media/external/images/12 to /data/user/0/app/cache/x.jpg failed: https://images.example/upload?sig=1"), "media.copyPicked")
        val m = reporter.reports.single().message
        assertEquals("copy of <url> to <path> failed: <url>", m)
    }

    @Test
    fun aCorruptedVaultEntryNeverSendsItsText() = runTest {
        val app = TestApp(this, handler = { TestApp.chain(it) ?: com.ecency.core.api.rpc.HttpResult(500, "") })
        app.signIn()
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        val reporter = RecordingReporter()
        app.env.crashReporter = reporter
        val wif = "5JdeC9P7Pbd1uGdFVEsJ41EkEnADbbHGq6p1BwFxm6txNBsQnsw"
        // a vault entry cut off mid-way: the JSON parser quotes the text around the error in its message
        app.store.putString("account.victim", """{"username":"victim","postingKey":"$wif","memo":"VAULTTEXT""")
        runCatching { app.env.session.switchTo("victim") }
        testScheduler.advanceUntilIdle()
        val r = reporter.reports.single { it.tags["where"] == "session.switchTo" }
        val everything = listOf(r.type, r.message) + r.tags.values + r.fingerprint + r.context.values
        assertTrue(everything.none { "VAULTTEXT" in it || wif in it || "postingKey" in it }, everything.toString())
        // the failure itself keeps the parser's type, never its text (a shorter stack would carry its "Caused by")
        val corrupted = assertFailsWith<com.ecency.core.secure.SecureStoreException.Corrupted> {
            com.ecency.core.secure.KeyVault(app.store).account("victim")
        }
        val trace = corrupted.stackTraceToString()
        assertFalse("VAULTTEXT" in trace || wif in trace, trace.lines().filter { "VAULTTEXT" in it }.toString())
    }

    @Test
    fun oneExceptionMappedTwiceIsOneReport() {
        val e = IllegalStateException("boom")
        AppError.from(e, "community.subscribe")
        // the app layer wraps it and a screen maps the wrapper again
        AppError.from(AppException(AppError.kindOf(e).let { AppError(it, "x", true) }, e), "community.screen")
        AppError.fromBroadcast(e, "community.other")
        assertEquals(1, reporter.reports.size)
    }

    @Test
    fun kindOfOnlyClassifies() {
        assertEquals(ErrorKind.CANCELLED, AppError.kindOf(AuthException.Cancelled()))
        assertEquals(ErrorKind.UNKNOWN, AppError.kindOf(IllegalStateException("boom")))
        assertEquals(emptyList(), reporter.reports)
    }

    @Test
    fun theExceptionsOwnStackGoesAlong() {
        AppError.from(IllegalStateException("boom", RuntimeException("root cause")), "editor.publish")
        val r = reporter.reports.single()
        assertEquals("handled", r.contextName)
        val stack = r.context.getValue("stack")
        // frames and type names only: an exception's or a cause's message never goes in the stack
        assertTrue(stack.contains("IllegalStateException"), stack)
        assertTrue(stack.lines().any { it.trimStart().startsWith("at ") }, stack)
        assertFalse(stack.contains("boom") || stack.contains("root cause"), stack)
        assertTrue(stack.lines().size <= ErrorReports.STACK_LINES)
        assertEquals("editor.publish", r.context["where"])
    }

    @Test
    fun aQuietFailureIsAWarning() {
        ErrorReports.quiet(IllegalStateException("server error"), "notifications.refresh")
        val r = reporter.reports.single()
        assertEquals("warning", r.level)
        assertEquals("true", r.tags["quiet"])
        assertEquals("notifications.refresh", r.tags["where"])
        assertEquals("handled", r.fingerprint.first())
    }

    @Test
    fun aSwiftErrorIsReportedByItsTypeName() {
        ErrorReports.foreign("CocoaError", "NSCocoaErrorDomain 4", "Datei nicht gefunden", "", "image.save", quiet = false, network = false)
        val r = reporter.reports.single()
        assertEquals("CocoaError", r.type)
        // grouped by domain and code: the localized description stays out of the fingerprint
        assertEquals(listOf("handled", "image.save", "unknown", "CocoaError", "NSCocoaErrorDomain 4"), r.fingerprint)
        assertNotEquals("warning", r.level)
    }

    @Test
    fun aSwiftNetworkErrorIsAWarningWhenShownAndNothingWhenQuiet() {
        ErrorReports.foreign("URLError", "NSURLErrorDomain -1009", "offline", "", "image.save", quiet = true, network = true)
        assertEquals(emptyList(), reporter.reports)
        ErrorReports.foreign("URLError", "NSURLErrorDomain -1009", "offline", "", "image.save", quiet = false, network = true)
        val r = reporter.reports.single()
        assertEquals("warning", r.level)
        assertEquals("network", r.tags["error.kind"])
        assertEquals("true", r.tags["expected"])
    }

    @Test
    fun nothingGoesOutBeforeAnEnvironmentInstalledTheReporter() {
        ErrorReports.install({ CrashReporter.None }, { now })
        AppError.from(IllegalStateException("boom"), "editor.publish")
        assertEquals(emptyList(), reporter.reports)
    }
}
