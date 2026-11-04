package com.ecency.core.app

import com.ecency.core.api.model.Post
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RN NewsletterPostPrompt, postDigestTarget and NewsletterSenderInfo over a scripted relay. */
class DigestEntriesTest {
    private val viewer = TestApp.USER

    private inner class World(val scope: TestScope) {
        val requests = mutableListOf<AuthHttpRequest>()
        var subscriptions = """{"subscriptions":[]}"""
        var subscriptionsDown = false
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val app = TestApp(scope) { TestApp.chain(it) ?: HttpResult(500, "") }

        init {
            app.authBackend.rest = { r ->
                if ("/api/newsletter/" in r.url) {
                    requests += r
                    when {
                        "/subscriptions" in r.url -> if (subscriptionsDown) HttpResult(503, "") else HttpResult(200, subscriptions)
                        "/sender?" in r.url -> HttpResult(200, """{"type":"creator","status":"active","subscribers":{"weekly":12,"monthly":3}}""")
                        else -> HttpResult(200, "{}")
                    }
                } else null
            }
            app.authBackend.gate = { r -> if ("/subscriptions" in r.url) gate?.await() }
            KeyVault(app.store).save(VaultAccount("spike-bob", AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-bob"))
            KeyVault(app.store).save(VaultAccount(viewer, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token"))
            KeyVault(app.store).setCurrentUsername(viewer)
            app.env.session.start()
            idle()
        }

        fun idle() = scope.testScheduler.advanceUntilIdle()
    }

    private fun post(author: String = "alice", category: String = "photography", depth: Int = 0, parent: String? = null) =
        Post(author = author, permlink = "p", category = category, depth = depth, parentAuthor = parent)

    @Test
    fun theCardOffersTheAuthorsDigestOrTheCommunityOfTheAuthorsOwnPost() {
        assertEquals(DigestTarget("creator", "alice"), DigestTarget.of(post(), viewer))
        assertNull(DigestTarget.of(post(), null), "signed out")
        assertNull(DigestTarget.of(post(depth = 1, parent = "bob"), viewer), "comments offer nothing")
        assertNull(DigestTarget.of(post(author = viewer), viewer), "own non-community post")
        assertEquals(DigestTarget("community", "hive-125125"), DigestTarget.of(post(author = viewer, category = "hive-125125"), viewer))
        assertNull(DigestTarget.of(post(author = viewer, category = "other-hive-125125"), viewer), "anchored community id")
        assertEquals("New posts by @alice in your inbox.", DigestTarget("creator", "alice").body)
    }

    @Test
    fun theCardShowsOnlyAfterALookupWithoutThatList() = runTest {
        val w = World(this)
        val m = w.app.env.digestPromptModel()
        m.start(post(), viewer)
        assertFalse(m.current.visible, "not before the lookup answered")
        w.idle()
        assertTrue(m.current.visible)
        assertTrue(w.requests.single().headers["X-HS-Token"] != null)
        w.idle()

        w.subscriptions = """{"subscriptions":[{"id":"1","email":"a@b.co","type":"creator","target":"ALICE","cadence":"weekly","status":"active"}]}"""
        w.app.env.digest.invalidateSubscriptions()
        val subscribed = w.app.env.digestPromptModel()
        subscribed.start(post(), viewer)
        w.idle()
        assertFalse(subscribed.current.visible, "already subscribed (target without case)")

        w.subscriptionsDown = true
        w.app.env.digest.invalidateSubscriptions()
        val down = w.app.env.digestPromptModel()
        down.start(post(), viewer)
        w.idle()
        assertFalse(down.current.visible, "a failed lookup shows nothing")
    }

    @Test
    fun aDismissalIsRememberedPerViewerAndListAndSubscribeOpensTheSheet() = runTest {
        val w = World(this)
        val m = w.app.env.digestPromptModel()
        m.start(post(), viewer)
        w.idle()
        m.subscribe()
        w.idle()
        assertTrue(w.app.env.digest.current.open)
        assertEquals("creator", w.app.env.digest.current.type)
        m.dismiss()
        assertFalse(m.current.visible)
        val again = w.app.env.digestPromptModel()
        again.start(post(), viewer)
        w.idle()
        assertFalse(again.current.visible)
        val other = w.app.env.digestPromptModel()
        other.start(post(author = "carol"), viewer)
        w.idle()
        assertTrue(other.current.visible, "another author's card is not hidden")
        assertTrue(DigestTarget.dismissKey(viewer, DigestTarget("creator", "alice")).startsWith(DigestTarget.PROMPT_PREFIX))
        assertTrue(DigestTarget.PROMPT_PREFIX in AccountPrefs.PREFIXES, "a wipe forgets it")
    }

    @Test
    fun theOwnProfileShowsTheSubscriberCounts() = runTest {
        val w = World(this)
        val m = w.app.env.digestSenderModel(viewer)
        m.start()
        w.idle()
        assertEquals("12 weekly • 3 monthly email subscribers", m.current.text)
        assertTrue(w.requests.single().url.endsWith("/api/newsletter/sender?type=creator&target=$viewer"))
        assertEquals("https://ecency.com/@$viewer?subscribe=digest", m.link)
        val other = w.app.env.digestSenderModel("alice")
        other.start()
        w.idle()
        assertFalse(other.current.loaded, "only the list owner asks")
        assertEquals(1, w.requests.size, "nothing is asked for someone else's list")
    }

    @Test
    fun anAnswerForTheViewerBeforeASwitchIsNotShown() = runTest {
        val w = World(this)
        w.gate = kotlinx.coroutines.CompletableDeferred()
        val m = w.app.env.digestPromptModel()
        m.start(post(), viewer)
        repeat(50) { testScheduler.runCurrent() }
        launch { w.app.env.session.switchTo("spike-bob") }
        repeat(50) { testScheduler.runCurrent() }
        w.gate!!.complete(Unit)
        w.idle()
        assertFalse(m.current.visible, "the previous viewer's lookup does not show a card to the next account")
    }

    @Test
    fun theLookupIsCachedPerViewerAndAskedAgainAfterASubscriptionChanged() = runTest {
        val w = World(this)
        val a = w.app.env.digestPromptModel()
        a.start(post(), viewer)
        w.idle()
        val b = w.app.env.digestPromptModel()
        b.start(post(author = "carol"), viewer)
        w.idle()
        assertTrue(a.current.visible && b.current.visible)
        assertEquals(1, w.requests.count { "/subscriptions" in it.url }, "one lookup per viewer for the session")
        // subscribed elsewhere (the sheet, Stop all): the cards ask again and the subscribed list's card goes
        w.subscriptions = """{"subscriptions":[{"id":"1","email":"a@b.co","type":"creator","target":"alice","cadence":"weekly","status":"active"}]}"""
        w.app.env.digest.invalidateSubscriptions()
        w.idle()
        assertEquals(2, w.requests.count { "/subscriptions" in it.url })
        assertFalse(a.current.visible)
        assertTrue(b.current.visible)
    }

    @Test
    fun subscribingFromTheSheetDropsTheCachedLookup() = runTest {
        val w = World(this)
        val before = w.app.env.digest.revision.value
        w.app.env.digest.show("creator", "alice")
        w.idle()
        w.app.env.digest.setEmail("me@example.com")
        w.app.env.digest.subscribe()
        w.idle()
        assertTrue(w.requests.any { it.url.endsWith("/api/newsletter/subscribe") })
        assertTrue(w.app.env.digest.revision.value > before)
    }

    @Test
    fun aSwitchSendsNoLookupForThePreviousViewer() = runTest {
        val w = World(this)
        val m = w.app.env.digestPromptModel()
        m.start(post(), viewer)
        w.idle()
        assertTrue(m.current.visible)
        val asked = w.requests.count { "/subscriptions" in it.url }
        w.app.env.session.switchTo("spike-bob")
        w.idle()
        assertEquals(asked, w.requests.count { "/subscriptions" in it.url }, "no GET for an account that is not signed in")
        assertFalse(m.current.visible)
    }
}
