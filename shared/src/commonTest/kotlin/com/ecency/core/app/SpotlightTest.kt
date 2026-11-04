package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpotlightTest {
    private fun TestScope.app(signedIn: Boolean = true, json: () -> String): TestApp {
        val app = TestApp(this) { TestApp.chain(it) ?: HttpResult(500, "") }
        app.authBackend.rest = { r -> if (r.url.endsWith("/private-api/spotlights")) HttpResult(200, json()) else null }
        if (signedIn) {
            val vault = KeyVault(app.store)
            listOf("alice", "bob").forEach { vault.save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = com.ecency.core.hive.GoldenVectors.WIF)) }
            vault.setCurrentUsername("alice")
        }
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        return app
    }

    private fun row(id: String, extra: String = "") =
        """{"id":"$id","feature":"f","title":"Title $id","description":"About $id","button_text":"Try $id","button_link":"/@alice/post-$id"$extra}"""

    private fun TestScope.shown(app: TestApp): String? {
        app.env.spotlight.start()
        testScheduler.advanceUntilIdle()
        return app.env.spotlight.current.card?.id
    }

    @Test
    fun theHighestWeightForThisPlatformAndAudienceShowsAndTiesGoToTheEarliestStart() = runTest {
        val app = app {
            "[" + listOf(
                row("web", ""","weight":99,"platforms":["web"]"""),
                row("guests", ""","weight":50,"guestsOnly":true"""),
                row("late", ""","weight":10,"start":"2026-10-05T00:00:00Z""""),
                row("early", ""","weight":10,"start":"2026-10-01T00:00:00Z","platforms":["web","mobile"]"""),
                row("light", ""","weight":1"""),
            ).joinToString(",") + "]"
        }
        assertEquals("early", shown(app))
        val card = app.env.spotlight.current.card!!
        assertEquals("Title early", card.title)
        assertEquals("About early", card.description)
        assertEquals("Try early", card.buttonText)
        val request = app.authBackend.sent.single { it.url.endsWith("/private-api/spotlights") }
        assertEquals("GET", request.method)
    }

    @Test
    fun guestsSeeOnlyGuestSpotlights() = runTest {
        val app = app(signedIn = false) { "[${row("members")},${row("guests", ""","guestsOnly":true""")}]" }
        assertEquals("guests", shown(app))
    }

    @Test
    fun dismissIsPerAccountAndTheNextSpotlightTakesItsPlace() = runTest {
        val app = app { "[${row("a", ""","weight":2""")},${row("b", ""","weight":1""")}]" }
        assertEquals("a", shown(app))
        app.env.spotlight.dismiss()
        assertEquals("b", app.env.spotlight.current.card?.id)
        assertEquals("1", app.env.prefs.getString(SpotlightModel.DISMISSED_PREFIX + "a_alice"))
        // another account has not dismissed it
        app.env.session.switchTo("bob")
        testScheduler.advanceUntilIdle()
        assertEquals("a", app.env.spotlight.current.card?.id)
        // and a wipe forgets every dismissal (it names the account)
        AccountPrefs.clear(app.env.prefs)
        assertTrue(app.env.prefs.keys().none { it.startsWith(SpotlightModel.DISMISSED_PREFIX) })
    }

    @Test
    fun theButtonDismissesAndOpensOnlySafeTargets() = runTest {
        var json = "[${row("a")}]"
        val app = app { json }
        assertEquals("a", shown(app))
        val target = app.env.spotlight.act()
        assertTrue(target is AnnouncementTarget.InApp, "an ecency.com post path opens in the app")
        assertNull(app.env.spotlight.current.card)
        // a link that would name another host is no link at all, but the card still goes away
        json = """[${row("b").replace("/@alice/post-b", ".evil.example/x")}]"""
        app.env.prefs.putString(SpotlightModel.DISMISSED_PREFIX + "a_alice", "1")
        app.now += SpotlightModel.STALE_MILLIS
        app.env.spotlight.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals("b", app.env.spotlight.current.card?.id)
        assertNull(app.env.spotlight.act())
        assertNull(app.env.spotlight.current.card)
    }

    @Test
    fun theAppLanguagePicksTheTranslationAndAFailedFetchShowsNothing() = runTest {
        var fail = true
        val app = app { "[]" }
        app.authBackend.rest = { r ->
            if (r.url.endsWith("/private-api/spotlights")) {
                if (fail) HttpResult(500, "") else HttpResult(200, """[${row("a", ""","locales":{"de-DE":{"title":"Titel","description":"","button_text":"Los"}}""")}]""")
            } else null
        }
        assertNull(shown(app))
        fail = false
        app.env.prefs.putString(SettingsModel.KEY_LANGUAGE, "de-DE")
        app.env.spotlight.refresh()
        testScheduler.advanceUntilIdle()
        val card = app.env.spotlight.current.card!!
        assertEquals("Titel", card.title)
        assertEquals("About a", card.description, "a missing field keeps the base text")
        assertEquals("Los", card.buttonText)
    }

    @Test
    fun aRefreshAskedForDuringAFetchRunsOnceItEnds() = runTest {
        val app = app { "[${row("a")}]" }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        app.authBackend.gate = { r -> if (r.url.endsWith("/private-api/spotlights")) gate.await() }
        app.env.spotlight.start()
        testScheduler.runCurrent() // the fetch waits at the gate (virtual time stays put: no request timeout)
        // an account change while the list is still on its way (SessionChanged calls refresh)
        app.env.session.switchTo("bob")
        testScheduler.runCurrent()
        app.now += SpotlightModel.STALE_MILLIS // the answer will be stale by then: the re-pick loads again
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(2, app.authBackend.sent.count { it.url.endsWith("/private-api/spotlights") }, "picked again after the running fetch")
        assertEquals("a", app.env.spotlight.current.card?.id)
    }

    @Test
    fun rowsWithoutIdTitleOrButtonAreDropped() = runTest {
        val app = app { """[{"title":"x","button_text":"y"},{"id":"n","button_text":"y"},{"id":"m","title":"t"},${row("ok")},7]""" }
        assertEquals("ok", shown(app))
    }
}
