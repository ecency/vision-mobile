package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Refer & Earn: the SDK's requests, RN's point maths, paging, and the list's account binding. */
class ReferModelTest {
    private val alice = "spike-alice"
    private val bob = "spike-bob"

    private fun row(id: Int, user: String, rewarded: Int = 0) =
        """{"id":$id,"referral":"x","username":"$user","rewarded":$rewarded,"created":"2026-09-0${id % 9 + 1}T10:00:00+00:00"}"""

    private fun TestScope.app(): TestApp {
        val app = appWithAccounts(this)
        val vault = KeyVault(app.store)
        listOf(bob, alice).forEach { vault.save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(it, com.ecency.core.hive.KeyRole.POSTING), accessToken = "access-$it")) }
        vault.setCurrentUsername(alice)
        app.authBackend.rest = { r ->
            when {
                r.url.endsWith("/referrals/$alice/stats") -> HttpResult(200, """{"total":5,"rewarded":2}""")
                r.url.endsWith("/referrals/$alice") -> HttpResult(200, "[${row(9, "ref-nine", 1)},${row(8, "ref-eight")}]")
                r.url.endsWith("/referrals/$alice?max_id=8") -> HttpResult(200, "[${row(7, "ref-seven")}]")
                r.url.endsWith("/referrals/$alice?max_id=7") -> HttpResult(200, "[]")
                r.url.endsWith("/referrals/$bob/stats") -> HttpResult(200, """{"total":1,"rewarded":1}""")
                r.url.endsWith("/referrals/$bob") -> HttpResult(200, "[${row(3, "bobs-friend", 1)}]")
                else -> null
            }
        }
        app.env.session.start()
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        return app
    }

    private fun TestScope.started(app: TestApp): ReferModel = app.env.referModel().also {
        it.start()
        testScheduler.advanceUntilIdle()
    }

    private fun TestApp.referralCalls() = authBackend.sent.filter { "/private-api/referrals/" in it.url }

    @Test
    fun loadsTheListAndRnsPointsWithTheSdksRequests() = runTest {
        val app = app()
        val model = started(app)
        val s = model.current
        assertEquals(alice, s.account)
        assertEquals(200, s.earnedPoints)
        assertEquals(300, s.pendingPoints)
        assertEquals(listOf("ref-nine", "ref-eight"), s.rows.map { it.username })
        assertEquals("Referral points rewarded", s.rows[0].rewardHint)
        assertEquals("Referral reward pending", s.rows[1].rewardHint)
        assertEquals(1_788_256_800_000L, s.rows[0].createdMillis) // 2026-09-01T10:00:00Z
        assertEquals("https://ecency.com/signup?referral=spike-alice", s.shareUrl)
        val calls = app.referralCalls()
        assertEquals(setOf("https://ecency.com/private-api/referrals/spike-alice", "https://ecency.com/private-api/referrals/spike-alice/stats"), calls.map { it.url }.toSet())
        assertTrue(calls.all { it.method == "GET" && it.body == null }, "public GETs: no account token is sent")
    }

    @Test
    fun pagesByTheLastRowsIdUntilAnEmptyPage() = runTest {
        val app = app()
        val model = started(app)
        assertTrue(model.current.hasMore)
        model.loadMore()
        model.loadMore() // while the first is on its way
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("ref-nine", "ref-eight", "ref-seven"), model.current.rows.map { it.username })
        model.loadMore()
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.hasMore)
        model.loadMore()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("", "/stats", "?max_id=8", "?max_id=7").size, app.referralCalls().size)
    }

    @Test
    fun aRepeatedPageEndsTheList() = runTest {
        val app = app()
        val base = app.authBackend.rest!!
        app.authBackend.rest = { r -> if (r.url.endsWith("?max_id=8")) HttpResult(200, "[${row(8, "ref-eight")}]") else base(r) }
        val model = started(app)
        model.loadMore()
        testScheduler.advanceUntilIdle()
        assertEquals(2, model.current.rows.size)
        assertFalse(model.current.hasMore)
    }

    @Test
    fun withoutStatsThePointsReadZeroAndTheListStillShows() = runTest {
        val app = app()
        val base = app.authBackend.rest!!
        app.authBackend.rest = { r -> if (r.url.endsWith("/stats")) HttpResult(500, "") else base(r) }
        val model = started(app)
        assertEquals(0, model.current.earnedPoints)
        assertEquals(0, model.current.pendingPoints)
        assertEquals(2, model.current.rows.size)
        assertNull(model.current.error)
    }

    @Test
    fun aFailedListShowsAnErrorAndRetryLoads() = runTest {
        val app = app()
        val base = app.authBackend.rest!!
        var down = true
        app.authBackend.rest = { r -> if (down && !r.url.endsWith("/stats")) HttpResult(503, "") else base(r) }
        val model = started(app)
        assertEquals(ErrorKind.NETWORK, model.current.error?.kind)
        assertFalse(model.current.isEmpty, "an error is not RN's empty hint")
        down = false
        model.retry()
        testScheduler.advanceUntilIdle()
        assertNull(model.current.error)
        assertEquals(2, model.current.rows.size)
    }

    @Test
    fun anEmptyListShowsRnsHint() = runTest {
        val app = app()
        app.authBackend.rest = { r -> if (r.url.endsWith("/stats")) HttpResult(200, """{"total":0,"rewarded":0}""") else if ("/referrals/" in r.url) HttpResult(200, "[]") else null }
        val model = started(app)
        assertTrue(model.current.isEmpty)
        assertEquals("Refer your loved ones today and earn free points", ReferCopy.EMPTY)
    }

    @Test
    fun anAccountChangeEmptiesTheListAndLoadsTheNewAccounts() = runTest {
        val app = app()
        val model = started(app)
        app.env.session.switchTo(bob)
        testScheduler.advanceUntilIdle()
        assertEquals(bob, model.current.account)
        assertEquals(listOf("bobs-friend"), model.current.rows.map { it.username })
        assertEquals(100, model.current.earnedPoints)
        assertEquals("https://ecency.com/signup?referral=spike-bob", model.current.shareUrl)
    }

    @Test
    fun aLateAnswerAfterSigningOutNeverShows() = runTest {
        val app = app()
        val model = started(app)
        val held = CompletableDeferred<Unit>()
        app.authBackend.gate = { r -> if (r.url.endsWith("?max_id=8")) held.await() }
        model.loadMore()
        testScheduler.runCurrent()
        app.env.session.logoutAll()
        testScheduler.runCurrent()
        held.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertNull(model.current.account)
        assertTrue(model.current.rows.isEmpty(), "no row of the account that left")
    }

    @Test
    fun signingOutEmptiesTheList() = runTest {
        val app = app()
        val model = started(app)
        assertEquals(2, model.current.rows.size)
        app.env.session.logoutAll()
        testScheduler.advanceUntilIdle()
        assertNull(model.current.account)
        assertTrue(model.current.rows.isEmpty())
        assertEquals(0, model.current.earnedPoints)
        assertNull(model.current.shareUrl)
    }

    @Test
    fun aLateAnswerForThePreviousAccountNeverShows() = runTest {
        val app = app()
        val model = started(app)
        val held = CompletableDeferred<Unit>()
        app.authBackend.gate = { r -> if (r.url.endsWith("?max_id=8")) held.await() }
        model.loadMore()
        testScheduler.advanceUntilIdle()
        app.env.session.switchTo(bob)
        testScheduler.advanceUntilIdle()
        held.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(bob, model.current.account)
        assertEquals(listOf("bobs-friend"), model.current.rows.map { it.username })
    }

    @Test
    fun signedOutThereIsNothing() = runTest {
        val app = appWithAccounts(this)
        app.env.session.start()
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        val model = started(app)
        assertNull(model.current.account)
        assertNull(model.current.shareUrl)
        assertTrue(app.authBackend.sent.isEmpty())
    }

    @Test
    fun malformedRowsAndDatesDoNotBreakTheList() = runTest {
        val app = app()
        app.authBackend.rest = { r ->
            if (r.url.endsWith("/stats")) HttpResult(200, """{"total":"x"}""")
            else if ("/referrals/" in r.url) HttpResult(200, """[{"id":"abc","username":"odd-row","rewarded":"yes","created":"not a date"},7,null,{"id":2,"username":"plain","created":"2026-09-01 10:00:00"}]""")
            else null
        }
        val model = started(app)
        assertEquals(listOf("odd-row", "plain"), model.current.rows.map { it.username })
        assertNull(model.current.rows[0].createdMillis)
        assertTrue(model.current.rows[0].isRewarded)
        assertEquals(1_788_256_800_000L, model.current.rows[1].createdMillis)
        assertEquals(0, model.current.earnedPoints)
    }

    @Test
    fun theReferScreenNeedsAnAccountAndIsSaved() {
        assertEquals(AppScreen.Refer, AppScreen.of(RouteName.REFER))
        assertTrue(RouteName.REFER.requiresLogin)
        assertEquals(AppScreen.Login, NavState().pushGated(AppScreen.Refer, loggedIn = false).top)
        assertEquals(AppScreen.Refer, NavState.decodeScreen(NavState.encodeScreen(AppScreen.Refer)))
        assertTrue(NavState().push(AppScreen.Refer).afterAccountChange().stack.isEmpty())
    }
}
