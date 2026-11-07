package com.ecency.core.app

import com.ecency.core.api.ecency.ImageUploadTransport
import com.ecency.core.api.ecency.ImageUploader
import com.ecency.core.api.ecency.UploadFile
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.FakeAuthHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A private API call refused (401) that no renewal fixes: the user is asked to sign in again, once, and the failure
 * reads as an expired session, not as a bug with an error code. The renewal's failure is reported for diagnosis.
 */
class SessionRefusalTest {
    private val alice = "spike-alice"

    private fun AuthHttpRequest.code(): String? =
        body?.let { runCatching { Json.parseToJsonElement(it).jsonObject["code"]?.jsonPrimitive?.content }.getOrNull() }

    private class Rig(val app: TestApp, val reporter: RecordingReporter) {
        /** Exchanges answered so far: the last token issued is `access-<issued>`. */
        var issued = 0

        /** The private API takes a code when this says so: everything while signing in, then what the test sets. */
        var accepts: (String?) -> Boolean = { true }

        /**
         * While set, every exchange fails this way: 0 is no answer at all (the transport throws), anything else that
         * HTTP status. Null: exchanges behave as the rig's refresh status says.
         */
        var exchangeFails: Int? = null

        /** Private API requests sent, in order. */
        val privateCalls get() = app.authBackend.sent.filter { "/private-api/" in it.url }
        val exchanges get() = app.authBackend.sent.filter { it.url.endsWith("/auth-api/hs-token-refresh") }
        val renewReports get() = reporter.reports.filter { it.tags["where"] == "auth.renewToken" }
    }

    /**
     * Every exchange gives fresh tokens (`access-N`, `refresh-N`); a refresh token is refused with [refreshStatus]
     * (null: accepted). The private API takes what [Rig.accepts] takes.
     */
    private fun TestScope.rig(refreshStatus: Int? = null): Rig {
        val app = appWithAccounts(this)
        val reporter = RecordingReporter()
        app.env.crashReporter = reporter
        val rig = Rig(app, reporter)
        app.authBackend.rest = { r ->
            if (r.url.endsWith("/auth-api/hs-token-refresh")) {
                val code = r.code().orEmpty()
                val fails = rig.exchangeFails
                if (fails == 0) throw IllegalStateException("no connection")
                if (fails != null) {
                    HttpResult(fails, "busy")
                } else if (code.startsWith("refresh-") && refreshStatus != null) {
                    HttpResult(refreshStatus, """{"error":"invalid_grant"}""")
                } else {
                    rig.issued++
                    FakeAuthHttp.tokens(alice, access = "access-${rig.issued}", refresh = "refresh-${rig.issued}")
                }
            } else {
                null
            }
        }
        app.authBackend.privateApi = { r -> if (rig.accepts(r.code())) HttpResult(200, "{}") else HttpResult(401, "Unauthorized") }
        app.env.session
        return rig
    }

    private suspend fun TestScope.claim(app: TestApp): Throwable? {
        val e = runCatching { app.env.walletApi.claimPoints(alice) }.exceptionOrNull()
        testScheduler.advanceUntilIdle()
        return e
    }

    @Test
    fun aHiveSignerSessionThatCannotBeRenewedAsksToSignInAgainOnce() = runTest {
        for (status in listOf(400, 401)) {
            val rig = rig(refreshStatus = status)
            val app = rig.app
            app.env.session.loginWithHiveSignerCode("hs-code-$alice")
            testScheduler.advanceUntilIdle()
            rig.accepts = { false }
            val before = rig.privateCalls.size

            val e = assertNotNull(claim(app), "refused")
            assertTrue(rig.privateCalls.size - before <= 2, "the call is sent at most twice")
            assertEquals(alice, app.env.session.current.expiredSession, "the session-expired prompt")

            // the screen says the session expired, with no error code, and it is not reported as a bug
            val shown = AppError.from(e, "wallet.claim")
            assertEquals(ErrorKind.AUTH, shown.kind)
            assertEquals(AppError.SESSION_EXPIRED, shown.message)
            assertNull(shown.code)
            val claimReport = rig.reporter.reports.single { it.tags["where"] == "wallet.claim" }
            assertEquals("warning", claimReport.level)
            assertEquals("true", claimReport.tags["expected"])
            assertNull(claimReport.tags["error_code"])

            // why the renewal failed, as a quiet warning without any token
            val renew = rig.renewReports.single()
            assertEquals("warning", renew.level)
            assertTrue("HTTP $status" in renew.message, renew.message)
            assertTrue("not a key account" in renew.message, renew.message)
            rig.reporter.reports.forEach { r ->
                val text = r.message + r.tags.values.joinToString() + r.context.values.joinToString()
                assertFalse("access-" in text || "refresh-" in text, "no token in a report")
            }

            // dismissed: further refusals of the same session neither ask again nor spend the refresh token again
            app.env.session.dismissExpiredSession()
            val exchanges = rig.exchanges.size
            assertNotNull(claim(app))
            assertNotNull(claim(app))
            assertNull(app.env.session.current.expiredSession, "asked once")
            assertEquals(exchanges, rig.exchanges.size, "no renewal loop")
            assertEquals(1, rig.renewReports.size)
        }
    }

    @Test
    fun aRenewalThatGotNoAnswerOrAServerErrorDoesNotAskToSignInAndTheNextCallRenews() = runTest {
        // the exchange answered nothing, a 5xx or a 429: the session is not known to be refused, so nothing is asked
        // and nothing is recorded, and once the exchange works again the next refused call renews and goes through
        for (key in listOf(false, true)) {
            for (status in listOf(0, 503, 429)) {
                val rig = rig()
                val app = rig.app
                if (key) app.env.session.loginWithKey(alice, AppTestKeys.PASSWORD) else app.env.session.loginWithHiveSignerCode("hs-code-$alice")
                testScheduler.advanceUntilIdle()
                val stored = "access-${rig.issued}"
                rig.accepts = { it != stored }
                rig.exchangeFails = status

                assertNotNull(claim(app), "key=$key status=$status")
                assertNull(app.env.session.current.expiredSession, "no prompt: key=$key status=$status")

                rig.exchangeFails = null
                val before = rig.privateCalls.size
                assertNull(claim(app), "renewed: key=$key status=$status")
                assertEquals(listOf(stored, "access-${rig.issued}"), rig.privateCalls.drop(before).map { it.code() })
                assertNull(app.env.session.current.expiredSession)
            }
        }
    }

    @Test
    fun only401sThePrivateApiGaveUpOnReadAsAnExpiredSession() = runTest {
        // an image upload refused with 401 has no renewal path behind it: a bug report with its code, not "session expired"
        val reporter = RecordingReporter()
        appWithAccounts(this).env.crashReporter = reporter
        val refused = ImageUploadTransport { _, _, _, _ -> HttpResult(401, "Unauthorized") }
        val upload = assertNotNull(
            runCatching { ImageUploader(refused, "https://images.example").upload(UploadFile(ByteArray(10), "a.jpg", "image/jpeg"), "token") }.exceptionOrNull(),
        )
        val shown = AppError.from(upload, "editor.upload")
        assertTrue(shown.message != AppError.SESSION_EXPIRED, shown.message)
        assertNotNull(shown.code, "reported with its code")
        assertTrue(reporter.reports.single { it.tags["where"] == "editor.upload" }.tags["expected"] != "true", "not an expected failure")

        // the private API's own final refusal still reads as one
        val rig = rig(refreshStatus = 400)
        rig.app.env.session.loginWithHiveSignerCode("hs-code-$alice")
        testScheduler.advanceUntilIdle()
        rig.accepts = { false }
        assertEquals(AppError.SESSION_EXPIRED, AppError.from(assertNotNull(claim(rig.app)), "wallet.claim").message)
    }

    @Test
    fun anAiServerErrorIsReported() = runTest {
        // the paid AI endpoints had a double-charge history: their 5xx stay visible, unlike a lost connection elsewhere
        val rig = rig()
        val app = rig.app
        app.env.session.loginWithHiveSignerCode("hs-code-$alice")
        testScheduler.advanceUntilIdle()
        app.authBackend.privateApi = { HttpResult(502, "Bad Gateway") }
        val e = assertNotNull(runCatching { app.env.aiApi.assistPrices(alice) }.exceptionOrNull())
        ErrorReports.quiet(e, "recap.price")
        assertTrue(rig.reporter.reports.any { it.tags["where"] == "recap.price" }, rig.reporter.reports.toString())
    }

    @Test
    fun aRenewedTokenRefusedAgainAsksToSignInAgain() = runTest {
        // the refresh works, but the server refuses the new token too
        val rig = rig()
        val app = rig.app
        app.env.session.loginWithHiveSignerCode("hs-code-$alice")
        testScheduler.advanceUntilIdle()
        rig.accepts = { false }
        val before = rig.privateCalls.size
        val e = assertNotNull(claim(app))
        assertEquals(2, rig.privateCalls.size - before)
        assertEquals(alice, app.env.session.current.expiredSession)
        assertEquals(AppError.SESSION_EXPIRED, AppError.from(e, "wallet.claim").message)
        assertTrue("refused again" in rig.renewReports.single().message)
    }

    @Test
    fun aRefusedSpinOrStreakFreezeReadsAsAnExpiredSession() = runTest {
        val rig = rig(refreshStatus = 401)
        val app = rig.app
        app.env.session.loginWithHiveSignerCode("hs-code-$alice")
        testScheduler.advanceUntilIdle()
        rig.accepts = { false }
        val perks = app.env.perksApi
        val calls = listOf<suspend () -> Unit>(
            { perks.gameStatus(alice) },
            { perks.claimSpin(alice, "key") },
            { perks.buyStreakFreeze(alice, "idem") },
        )
        for (call in calls) {
            val e = assertNotNull(runCatching { call() }.exceptionOrNull())
            val shown = AppError.from(e, "perks.spin")
            assertEquals(AppError.SESSION_EXPIRED, shown.message)
            assertNull(shown.code)
        }
        // the spin screen's own alert says the session expired, not "An error occurred"
        val spin = app.env.spinGameModel()
        spin.start()
        testScheduler.advanceUntilIdle()
        assertEquals(AppError.SESSION_EXPIRED, spin.current.alert)
        spin.close()
    }

    @Test
    fun aKeyAccountSignsAFreshCodeAndTheResendWorks() = runTest {
        // the refresh token is refused; the key signs a new code, whose token the server takes
        val rig = rig(refreshStatus = 400)
        val app = rig.app
        app.env.session.loginWithKey(alice, AppTestKeys.PASSWORD)
        testScheduler.advanceUntilIdle()
        val stored = "access-${rig.issued}"
        val next = "access-${rig.issued + 1}"
        rig.accepts = { it == next }
        val before = rig.privateCalls.size
        assertNull(claim(app))
        assertEquals(listOf(stored, next), rig.privateCalls.drop(before).map { it.code() })
        assertNull(app.env.session.current.expiredSession)
        assertTrue(rig.renewReports.isEmpty())
    }

    @Test
    fun noPromptForASessionThatSignedOutWhileTheCallWasOnItsWay() = runTest {
        for (signInAgain in listOf(false, true)) {
            val rig = rig(refreshStatus = 401)
            val app = rig.app
            app.env.session.loginWithHiveSignerCode("hs-code-$alice")
            testScheduler.advanceUntilIdle()
            rig.accepts = { false }
            val held = CompletableDeferred<Unit>()
            app.authBackend.gate = { r -> if ("/private-api/" in r.url) held.await() }
            val call = async { runCatching { app.env.walletApi.claimPoints(alice) } }
            testScheduler.runCurrent()
            app.env.session.logout(alice)
            if (signInAgain) app.env.session.loginWithHiveSignerCode("hs-code-$alice")
            app.authBackend.gate = null
            held.complete(Unit)
            call.await()
            testScheduler.advanceUntilIdle()
            assertNull(app.env.session.current.expiredSession, "signInAgain=$signInAgain")
            assertTrue(rig.renewReports.isEmpty(), "signInAgain=$signInAgain")
        }
    }

    @Test
    fun noPromptWhenTheUserSignedInAgainBeforeTheRenewedTokenWasRefused() = runTest {
        val rig = rig()
        val app = rig.app
        app.env.session.loginWithHiveSignerCode("hs-code-$alice")
        testScheduler.advanceUntilIdle()
        val signedIn = rig.issued
        // the first token is refused, so the call renews and is sent again with the renewed one
        rig.accepts = { it != "access-$signedIn" }
        val held = CompletableDeferred<Unit>()
        app.authBackend.gate = { r -> if ("/private-api/" in r.url && r.code() == "access-${signedIn + 1}") held.await() }
        val call = async { runCatching { app.env.walletApi.claimPoints(alice) } }
        testScheduler.runCurrent()
        // signed in again (no sign-out) while the resend is on its way; the resend is then refused
        app.authBackend.gate = null
        rig.accepts = { it != "access-$signedIn" && it != "access-${signedIn + 1}" }
        app.env.session.loginWithHiveSignerCode("hs-code-$alice")
        // no time passes: the held resend must not time out
        testScheduler.runCurrent()
        assertEquals("access-${signedIn + 2}", app.env.accounts.code(alice), "a new session was stored")
        held.complete(Unit)
        val failure = assertNotNull(call.await().exceptionOrNull())
        // the stale 401 is no expired session either: the call's own failure, the new session stands
        assertNotEquals(AppError.SESSION_EXPIRED, AppError.from(failure, "wallet.claim").message)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("access-$signedIn", "access-${signedIn + 1}"), rig.privateCalls.map { it.code() }, "refused, renewed, refused again")
        assertNull(app.env.session.current.expiredSession, "the session the call went out with is no longer the stored one")
    }

    @Test
    fun aBackgroundReadRefused401IsNoErrorReport() = runTest {
        val rig = rig(refreshStatus = 401)
        val app = rig.app
        app.env.session.loginWithHiveSignerCode("hs-code-$alice")
        testScheduler.advanceUntilIdle()
        rig.accepts = { false }
        val e = assertNotNull(runCatching { app.env.aiApi.assistPrices(alice) }.exceptionOrNull())
        ErrorReports.quiet(e, "recap.price")
        val u = assertNotNull(runCatching { app.env.notificationsApi.unreadCount(alice) }.exceptionOrNull())
        ErrorReports.quiet(u, "notifications.unreadCount")
        testScheduler.advanceUntilIdle()
        assertTrue(rig.reporter.reports.none { it.tags["where"] == "recap.price" || it.tags["where"] == "notifications.unreadCount" })
        assertTrue(rig.reporter.reports.none { it.level == "error" }, rig.reporter.reports.toString())
    }
}
