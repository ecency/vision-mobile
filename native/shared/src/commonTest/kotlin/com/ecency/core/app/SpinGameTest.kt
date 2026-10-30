package com.ecency.core.app

import com.ecency.core.api.ecency.GameStatus
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The spin game (RN SpinGameContainer after ab9a588a82, 184d50d2c8, 764e5a24d9): the status always from the
 * server and numbered, one claim at a time and only on a press, superseded answers dropped before claiming, and
 * no status check from a claim that outlived its screen.
 */
class SpinGameTest {
    private val alice = "spike-alice"
    private val bob = "spike-bob"

    /** The answers of `get-game`, in order; the last one repeats. */
    private val statuses = ArrayDeque<String>()
    private var lastStatus = """{"status":1,"key":"day-key","remaining":1,"next_date":"2026-10-07T12:00:00Z","wait_secs":0}"""
    private var claimAnswer: () -> HttpResult = { HttpResult(200, """{"score":25}""", mapOf("content-type" to "application/json")) }

    /** Holds an answer back: set per request url suffix. */
    private val holds = mutableMapOf<Int, CompletableDeferred<Unit>>()
    private var statusCount = 0

    private fun TestScope.app(): TestApp {
        val app = appWithAccounts(this)
        val vault = KeyVault(app.store)
        listOf(bob, alice).forEach { vault.save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(it, com.ecency.core.hive.KeyRole.POSTING), accessToken = "access-$it")) }
        vault.setCurrentUsername(alice)
        app.authBackend.gate = { r ->
            if (r.url.endsWith("/get-game")) {
                val n = ++statusCount
                holds[n]?.await()
            }
        }
        app.authBackend.rest = { r ->
            when {
                r.url.endsWith("/private-api/get-game") -> HttpResult(200, statuses.removeFirstOrNull()?.also { lastStatus = it } ?: lastStatus)
                r.url.endsWith("/private-api/post-game") -> claimAnswer()
                r.url.endsWith("/private-api/quests") -> HttpResult(200, "{}")
                else -> null
            }
        }
        app.env.session.start()
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        return app
    }

    private fun TestScope.started(app: TestApp): SpinGameModel = app.env.spinGameModel().also {
        it.start()
        testScheduler.runCurrent()
    }

    private fun TestApp.calls(path: String) = authBackend.sent.filter { it.url.endsWith(path) }

    private fun AuthHttpRequest.field(name: String) = Json.parseToJsonElement(body!!).jsonObject.getValue(name).jsonPrimitive.content

    @Test
    fun theStatusComesFromTheServerWithTheSdksRequest() = runTest {
        val app = app()
        val model = app.env.spinGameModel()
        assertTrue(model.current.loading)
        model.start()
        testScheduler.runCurrent()
        val sent = app.calls("/get-game").single()
        assertEquals("https://ecency.com/private-api/get-game", sent.url)
        assertEquals(Json.parseToJsonElement("""{"game_type":"spin","code":"access-spike-alice"}"""), Json.parseToJsonElement(sent.body!!))
        val s = model.current
        assertFalse(s.loading)
        assertEquals(1, s.gameRight)
        assertTrue(s.showButton && s.showTags && s.showCounters)
        assertFalse(s.showProducts || s.showScore)
        // a session change that keeps the account asks nothing again
        app.env.session.requestLogin("someone")
        testScheduler.runCurrent()
        assertEquals(1, app.calls("/get-game").size)
        // a reopened screen asks the server again: never a cached answer
        model.close()
        val again = started(app)
        assertEquals(2, app.calls("/get-game").size)
        again.close()
    }

    @Test
    fun aPressAsksAFreshStatusThenClaimsWithItsKeyOnce() = runTest {
        val app = app()
        val model = started(app)
        statuses += """{"status":1,"key":"fresh-key","remaining":1}"""
        statuses += """{"status":18,"remaining":0,"next_date":"2026-10-07T12:00:00Z"}"""
        model.spin()
        model.spin() // a second press while the first one runs sends nothing
        assertTrue(model.current.spinning)
        testScheduler.runCurrent()
        val claim = app.calls("/post-game").single()
        assertEquals("https://ecency.com/private-api/post-game", claim.url)
        assertEquals(Json.parseToJsonElement("""{"game_type":"spin","key":"fresh-key","code":"access-spike-alice"}"""), Json.parseToJsonElement(claim.body!!))
        val s = model.current
        assertEquals(25, s.score)
        assertEquals(0, s.gameRight)
        assertEquals("2026-10-07T12:00:00Z", s.nextDate)
        assertEquals(3, app.calls("/get-game").size, "on open, before the claim, after it")
        assertFalse(s.showScore, "the score shows once the rings stop")
        testScheduler.advanceTimeBy(SpinCopy.SPIN_MILLIS + 1)
        assertTrue(model.current.showScore && model.current.showProducts)
        assertEquals("25 Points", SpinCopy.score(model.current.score))
        model.close()
    }

    @Test
    fun aPressWhileASlowClaimRunsSendsNothingEvenAfterTheRingsStopped() = runTest {
        val app = app()
        val model = started(app)
        val claimHold = CompletableDeferred<Unit>()
        app.authBackend.gate = { r -> if (r.url.endsWith("/post-game")) claimHold.await() }
        model.spin()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(SpinCopy.SPIN_MILLIS + 1) // the button is back, the claim is not answered yet
        assertTrue(model.current.showButton && model.current.claiming)
        model.spin()
        testScheduler.runCurrent()
        assertEquals(2, app.calls("/get-game").size, "the second press asked nothing")
        claimHold.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(1, app.calls("/post-game").size)
        model.close()
    }

    @Test
    fun theSameDailyKeyClaimsAgainOnTheNextPress() = runTest {
        val app = app()
        val model = started(app)
        model.spin()
        testScheduler.advanceUntilIdle()
        // the free spin key is the same every day: a cleared claim lets the next press send it again
        model.spin()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("day-key", "day-key"), app.calls("/post-game").map { it.field("key") })
        model.close()
    }

    @Test
    fun aPackOfSpinsShowsFiveUntilTheStatusSaysOtherwise() = runTest {
        val app = app()
        val model = started(app)
        val hold = CompletableDeferred<Unit>()
        holds[3] = hold
        statuses += """{"status":3,"key":"pack-key","remaining":5}"""
        model.spin()
        testScheduler.runCurrent()
        assertEquals(5, model.current.gameRight, "RN: status 3 shows 5 at once")
        statuses += """{"status":3,"key":"pack-key","remaining":4}"""
        hold.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(4, model.current.gameRight)
        model.close()
    }

    @Test
    fun noSpinLeftClaimsNothingAndShowsTheTimer() = runTest {
        val app = app()
        val model = started(app)
        statuses += """{"status":18,"remaining":0,"next_date":"2026-10-06T15:04:05Z"}"""
        model.spin()
        testScheduler.advanceUntilIdle()
        assertTrue(app.calls("/post-game").isEmpty())
        assertEquals(0, model.current.gameRight)
        assertTrue(model.current.showProducts)
        assertEquals("3:4:05", SpinCopy.timeLeft(model.current.nextDate, app.now))
        assertEquals("Resets daily at 00:00 UTC", model.current.resetNote, "under the countdown")
        model.close()
    }

    @Test
    fun aStatusWithoutAKeySaysSo() = runTest {
        val app = app()
        val model = started(app)
        statuses += """{"status":1,"remaining":1}"""
        model.spin()
        testScheduler.runCurrent()
        assertEquals("Game key missing", model.current.alert)
        assertTrue(app.calls("/post-game").isEmpty())
        model.dismissAlert()
        assertNull(model.current.alert)
        model.close()
    }

    @Test
    fun aSupersededStatusNeverReachesAClaim() = runTest {
        val app = app()
        val model = started(app)
        // the press's status request (the 2nd) is held; a newer one starts meanwhile (a reopened screen)
        val hold = CompletableDeferred<Unit>()
        holds[2] = hold
        model.spin()
        testScheduler.runCurrent()
        val other = started(app)
        hold.complete(Unit)
        testScheduler.runCurrent()
        assertTrue(app.calls("/post-game").isEmpty(), "its key may belong to a spin the newer request saw used")
        assertFalse(model.current.claiming)
        // the guard is released: the next press claims (once the rings stopped and the button is back)
        testScheduler.advanceTimeBy(SpinCopy.SPIN_MILLIS + 1)
        model.spin()
        testScheduler.runCurrent()
        assertEquals(1, app.calls("/post-game").size)
        model.close()
        other.close()
    }

    @Test
    fun onlyTheNewestStatusSetsTheCountersAndOnlyItsFailureAlerts() = runTest {
        val app = app()
        val first = CompletableDeferred<Unit>()
        holds[1] = first
        // answers go out in arrival order: request 2 answers first ("none left"), the held request 1 later
        statuses += """{"status":18,"remaining":0,"next_date":"2026-10-07T12:00:00Z"}"""
        statuses += """{"status":1,"key":"k","remaining":7}"""
        val stale = started(app) // request 1 held: it read spins left before one was used
        val fresh = started(app) // request 2: "none left"
        assertEquals(0, fresh.current.gameRight)
        first.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(1, stale.current.gameRight, "the superseded answer leaves the counters alone (the default 1, not 7)")
        assertFalse(stale.current.loading)
        stale.close()
        fresh.close()

        // a superseded failure says nothing; the newest one alerts
        val app2 = app()
        val held = CompletableDeferred<Unit>()
        statusCount = 0
        holds.clear()
        holds[1] = held
        app2.authBackend.rest = { r -> if (r.url.endsWith("/get-game")) HttpResult(500, """{"message":"spin service down"}""") else null }
        val a = started(app2)
        val b = started(app2)
        assertEquals("spin service down", b.current.alert)
        held.complete(Unit)
        testScheduler.runCurrent()
        assertNull(a.current.alert)
        a.close()
        b.close()
    }

    @Test
    fun aClaimThatOutlivesItsScreenStartsNoStatusCheck() = runTest {
        val app = app()
        val model = started(app)
        val claimHold = CompletableDeferred<Unit>()
        app.authBackend.gate = { r -> if (r.url.endsWith("/post-game")) claimHold.await() }
        model.spin()
        testScheduler.runCurrent()
        model.close()
        val reopened = started(app)
        val before = app.calls("/get-game").size
        claimHold.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.calls("/post-game").size, "the claim was not cut off")
        assertEquals(before, app.calls("/get-game").size, "and the closed screen asked nothing after it")
        reopened.close()
    }

    @Test
    fun aFailedClaimAlertsAndReleasesTheGuard() = runTest {
        val app = app()
        val model = started(app)
        claimAnswer = { HttpResult(502, "<html>bad gateway</html>") }
        model.spin()
        testScheduler.runCurrent()
        assertEquals("An error occurred", model.current.alert)
        assertFalse(model.current.claiming)
        claimAnswer = { HttpResult(200, """{"score":5}""") }
        model.dismissAlert()
        testScheduler.advanceTimeBy(SpinCopy.SPIN_MILLIS + 1) // the button is back once the rings stop
        model.spin()
        testScheduler.runCurrent()
        assertEquals(5, model.current.score)
        model.close()
    }

    @Test
    fun aStatusThatAnswersAfterAnAccountSwitchClaimsNothing() = runTest {
        val app = app()
        val model = started(app)
        // the press's status request: the account switches before its answer comes back
        val gate = app.authBackend.gate
        app.authBackend.gate = { r ->
            if (r.url.endsWith("/get-game") && model.current.claiming) {
                app.authBackend.gate = gate
                app.env.session.switchTo(bob)
            }
        }
        model.spin()
        testScheduler.runCurrent()
        assertTrue(app.calls("/post-game").isEmpty(), "never claimed with the account that is gone")
        model.close()
    }

    @Test
    fun anAccountChangeAsksForTheNewAccountsStatus() = runTest {
        val app = app()
        val model = started(app)
        app.env.session.switchTo(bob)
        testScheduler.runCurrent()
        assertEquals(bob, model.current.account)
        assertEquals("access-spike-bob", app.calls("/get-game").last().field("code"))
        model.close()
    }

    @Test
    fun theCountdownIsMomentsUtcTimeOfDay() {
        // node: moment.utc(moment(next).diff(now)).format('H:m:ss'), now = 2026-10-20T10:00:00Z
        val now = 1_792_490_400_000L
        val golden = listOf(
            "2026-10-20T10:00:00Z" to "0:0:00",
            "2026-10-20T10:00:01Z" to "0:0:01",
            "2026-10-20T11:05:09Z" to "1:5:09",
            "2026-10-21T09:59:59Z" to "23:59:59",
            "2026-10-21T10:00:00Z" to "0:0:00",
            "2026-10-22T13:07:03Z" to "3:7:03",
            "2026-10-20T09:59:59Z" to "23:59:59",
            "2026-10-19T09:00:00Z" to "23:0:00",
            "2026-10-20T10:00:00.999Z" to "0:0:00",
            "2026-10-20T10:00:00.001Z" to "0:0:00",
            "2026-10-20T12:30:45+02:00" to "0:30:45",
        )
        golden.forEach { (next, want) -> assertEquals(want, SpinCopy.timeLeft(next, now), next) }
        assertEquals("", SpinCopy.timeLeft(null, now))
        assertEquals("", SpinCopy.timeLeft("soon", now))
        assertEquals("1:0:00", SpinCopy.timeLeft("2026-10-20T11:00:00", now), "no zone: read as UTC")
    }

    @Test
    fun theStatusIsReadAsUntrusted() {
        assertNull(GameStatus.parse("<html>"))
        val s = GameStatus.parse("""{"status":"1","key":"a b","remaining":-3,"next_date":"${"x".repeat(80)}"}""")!!
        assertNull(s.key, "a key with white space is no key")
        assertEquals(0, s.remaining)
        assertNull(s.nextDate)
        assertTrue(GameStatus.parse("""{"status":18}""")!!.noSpinLeft)
    }
}
