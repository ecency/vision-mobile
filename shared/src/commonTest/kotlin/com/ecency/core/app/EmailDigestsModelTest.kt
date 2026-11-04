package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.hive.GoldenVectors
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

/** RN emailDigestsScreen over a scripted newsletter relay: nothing is subscribed or stopped anywhere. */
class EmailDigestsModelTest {
    private val user = "spike-alice"
    private val other = "spike-other"

    private inner class World(val scope: TestScope) {
        val relay = mutableListOf<AuthHttpRequest>()
        var rows = """[
            {"id":"1","email":"a@example.com","type":"own","target":"$user","cadence":"weekly","status":"active"},
            {"id":"2","email":"a@example.com","type":"creator","target":"bob","cadence":"monthly","status":"pending_confirmation"},
            {"id":"3","email":"b@example.com","type":"community","target":"hive-1","cadence":"weekly","status":"suppressed"}
        ]"""
        var listStatus = 200
        var stopStatus = 200
        var gate: CompletableDeferred<Unit>? = null
        val app = TestApp(scope) { _ -> HttpResult(500, "") }

        init {
            app.authBackend.rest = { r ->
                if ("/api/newsletter/" in r.url) {
                    relay += r
                    when {
                        r.method == "GET" -> HttpResult(listStatus, if (listStatus == 200) """{"subscriptions":$rows}""" else """{"error":"x"}""")
                        r.url.endsWith("/unsubscribe-all") -> HttpResult(stopStatus, "{}")
                        else -> HttpResult(200, "{}")
                    }
                } else {
                    null
                }
            }
            app.authBackend.gate = { r -> if (r.url.endsWith("/unsubscribe-all")) gate?.await() }
            listOf(user, other).forEach { name ->
                KeyVault(app.store).save(VaultAccount(name, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-$name"))
            }
            KeyVault(app.store).setCurrentUsername(user)
            app.env.session.start()
            idle()
        }

        fun idle() = scope.testScheduler.advanceUntilIdle()
        fun step() = scope.testScheduler.runCurrent()

        fun toasts() = generateSequence { app.env.ui.current.toast?.also { app.env.ui.dismiss(it.id) }?.text }.toList()

        val model = app.env.emailDigestsModel()
    }

    @Test
    fun rowsAreGroupedByAddressWithRnsLabels() = runTest {
        val w = World(this)
        w.model.start()
        assertTrue(w.model.current.loading)
        w.idle()
        val s = w.model.current
        assertEquals(user, s.account)
        assertEquals(listOf("a@example.com", "b@example.com"), s.addresses.map { it.email })
        val rows = s.addresses.flatMap { it.rows }
        assertEquals(listOf("My notifications digest", "@bob", "hive-1"), rows.map(EmailDigestsCopy::rowLabel))
        assertEquals(listOf("Weekly • active", "Monthly • pending", "Weekly • stopped"), rows.map(EmailDigestsCopy::meta))
        assertFalse(s.showsOwnOffer, "own is there")
        assertTrue(s.showsSiteOffer)
        assertTrue(s.showsDiscover)
        val get = w.relay.single()
        assertEquals("GET", get.method)
        assertEquals("token-$user", get.headers["X-HS-Token"])
    }

    @Test
    fun emptyAndErrorStates() = runTest {
        val w = World(this)
        w.rows = "[]"
        w.model.start()
        w.idle()
        assertTrue(w.model.current.isEmpty)
        assertTrue(w.model.current.showsOwnOffer && w.model.current.showsSiteOffer)

        w.listStatus = 503
        w.model.retry()
        w.idle()
        assertEquals("Email digests are not available right now.", w.model.current.error)
        assertFalse(w.model.current.showsDiscover)
        w.listStatus = 500
        w.model.retry()
        w.idle()
        assertEquals("Something went wrong, please try again.", w.model.current.error)
    }

    @Test
    fun aRowOpensTheDigestSheetAndTheListReloadsWhenItCloses() = runTest {
        val w = World(this)
        w.model.start()
        w.idle()
        w.model.open("site", "ecency")
        w.idle()
        assertTrue(w.app.env.digest.current.open)
        assertEquals("site", w.app.env.digest.current.type)
        val reads = w.relay.count { it.method == "GET" }
        w.rows = "[]"
        w.app.env.digest.dismiss()
        w.idle()
        assertEquals(reads + 1, w.relay.count { it.method == "GET" })
        assertTrue(w.model.current.subscriptions.isEmpty())
    }

    @Test
    fun stopAllSendsTheSdkRequestOnce() = runTest {
        val w = World(this)
        w.model.start()
        w.idle()
        w.gate = CompletableDeferred()
        w.model.stopAll("a@example.com")
        w.model.stopAll("a@example.com")
        w.model.stopAll("b@example.com")
        w.step()
        assertEquals("a@example.com", w.model.current.stopping)
        w.gate!!.complete(Unit)
        w.idle()
        val stops = w.relay.filter { it.url.endsWith("/unsubscribe-all") }
        assertEquals(1, stops.size, "one request while one runs")
        val body = Json.parseToJsonElement(stops.single().body!!).jsonObject
        assertEquals("a@example.com", body["email"]!!.jsonPrimitive.content)
        assertEquals("token-$user", body["code"]!!.jsonPrimitive.content)
        assertTrue(stops.single().url.endsWith("/api/newsletter/unsubscribe-all"))
        assertEquals(listOf("Address unsubscribed from all mail"), w.toasts())
        assertNull(w.model.current.stopping)

        w.model.stopAll("nobody@example.com")
        w.idle()
        assertEquals(1, w.relay.count { it.url.endsWith("/unsubscribe-all") }, "only an address of the list")

        w.stopStatus = 500
        w.model.stopAll("b@example.com")
        w.idle()
        assertEquals("Something went wrong, please try again.", w.model.current.alert)
    }

    @Test
    fun anAccountChangeEmptiesAndLoadsTheNewAccounts() = runTest {
        val w = World(this)
        w.model.start()
        w.idle()
        w.app.env.session.switchTo(other)
        w.idle()
        assertEquals(other, w.model.current.account)
        assertEquals("token-$other", w.relay.last().headers["X-HS-Token"])
        w.app.env.session.logout(other)
        w.app.env.session.logout(user)
        w.idle()
        assertNull(w.model.current.account)
        assertTrue(w.model.current.subscriptions.isEmpty())
    }

    @Test
    fun aRowOfThePreviousAccountOpensNothing() = runTest {
        val w = World(this)
        w.model.start()
        w.idle()
        w.app.env.session.switchTo(other)
        // the list still shows the previous account's rows until the model hears of the change
        if (w.model.current.account != user) error("the account change arrived first; the guard is not exercised")
        w.model.open("own", user)
        assertFalse(w.app.env.digest.current.open, "no sheet for the new account from the old account's row")
    }

    @Test
    fun aFailedStopAllOfThePreviousAccountShowsNothing() = runTest {
        val w = World(this)
        w.model.start()
        w.idle()
        w.stopStatus = 500
        w.gate = CompletableDeferred()
        w.model.stopAll("a@example.com")
        w.step()
        w.app.env.session.switchTo(other)
        w.step()
        w.gate!!.complete(Unit)
        w.idle()
        assertEquals(other, w.model.current.account)
        assertNull(w.model.current.alert, "the old account's failure is not shown to the new one")
        assertNull(w.model.current.stopping)
    }
}
