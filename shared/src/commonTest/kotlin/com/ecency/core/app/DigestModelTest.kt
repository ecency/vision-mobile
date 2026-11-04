package com.ecency.core.app

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.FakeTransport
import com.ecency.core.api.ecency.DigestSubscribeStatus
import com.ecency.core.api.ecency.DigestSubscription
import com.ecency.core.api.ecency.NewsletterApi
import com.ecency.core.api.ecency.NewsletterGoldens
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.EcencyPrivateApi
import com.ecency.core.editor.TextSelection
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RN newsletterDigestSheet and firstPublishDigest over a scripted relay and node: the requests are the SDK's
 * (golden), the form is RN's (golden), and nothing is subscribed anywhere.
 */
class DigestModelTest {
    private val user = "spike-alice"
    private val other = "spike-other"

    private inner class World(val scope: TestScope) {
        val relay = mutableListOf<AuthHttpRequest>()
        var subscriptions = "[]"
        var subscribeAnswer: () -> HttpResult = { HttpResult(200, """{"status":"active"}""") }
        var listAnswer: (() -> HttpResult)? = null
        var gate: CompletableDeferred<Unit>? = null
        var postCount: Int? = 0
        var accountsGate: CompletableDeferred<Unit>? = null
        var accountReads = 0
        var accountsFail = false
        val app = TestApp(scope) { req ->
            when (req.method) {
                "condenser_api.get_accounts" -> {
                    accountReads++
                    accountsGate?.await()
                    if (accountsFail) return@TestApp HttpResult(500, "")
                    FakeTransport.result(req, """[{"name":"$user"${postCount?.let { ""","post_count":$it""" }.orEmpty()}}]""")
                }
                "condenser_api.broadcast_transaction" -> FakeTransport.result(req, "{}")
                "bridge.get_post" -> FakeTransport.result(req, "null")
                else -> TestApp.chain(req) ?: HttpResult(500, "")
            }
        }

        init {
            app.authBackend.rest = { r -> if ("/api/newsletter/" in r.url) answer(r) else null }
            app.authBackend.gate = { r -> if ("/api/newsletter/" in r.url) gate?.await() }
            app.authBackend.privateApi = { HttpResult(200, "{}") }
            app.env.randomString = { "rnd1" }
            listOf(user, other).forEach { name ->
                KeyVault(app.store).save(VaultAccount(name, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-$name"))
            }
            KeyVault(app.store).setCurrentUsername(user)
            app.env.session.start()
            idle()
        }

        private fun answer(r: AuthHttpRequest): HttpResult {
            relay += r
            return when {
                r.method == "GET" -> listAnswer?.invoke() ?: HttpResult(200, """{"subscriptions":$subscriptions}""")
                r.method == "POST" -> subscribeAnswer()
                else -> HttpResult(200, "{}")
            }
        }

        fun idle() = scope.testScheduler.advanceUntilIdle()

        val digest get() = app.env.digest

        fun toasts() = generateSequence { app.env.ui.current.toast?.also { app.env.ui.dismiss(it.id) }?.text }.toList()

        fun publish(title: String = "My first post"): EditorModel {
            val m = app.env.editorModel(EditorArgs(EditorKind.NEW_POST))
            m.start()
            idle()
            m.setTitle(title)
            m.setBody("Hello world", TextSelection(11))
            m.setTagInput("ecency test ")
            idle()
            m.submit()
            return m
        }
    }

    private fun row(id: String, type: String, target: String, email: String = "alice@example.com", cadence: String = "weekly", status: String = "active") =
        """{"id":"$id","email":"$email","account":"$user","type":"$type","target":"$target","cadence":"$cadence","status":"$status","created_at":"2026-08-25T00:00:00Z"}"""

    // ---- golden: the SDK's requests and RN's form ----

    @Test
    fun theRelayRequestsAreTheSdks() = runTest {
        val sent = mutableListOf<AuthHttpRequest>()
        val api = NewsletterApi(
            EcencyPrivateApi(
                { request ->
                    sent += request
                    HttpResult(200, """{"status":"active","subscriptions":[]}""")
                },
                { NewsletterGoldens.CODE },
            ),
        )
        api.subscriptions(user)
        api.subscribe(user, "alice@example.com", "own", "spike-alice", "weekly")
        api.subscribe(user, "a\"b\\c@example.com", "community", "hive-125125", "monthly")
        api.leave(user, "sub-123")
        api.leave(user, "a/b c?d#e&f=g+h%i:é🐝")
        assertEquals(NewsletterGoldens.requests.size, sent.size)
        NewsletterGoldens.requests.zip(sent).forEach { (golden, request) ->
            assertEquals(golden.method, request.method, golden.name)
            assertEquals(golden.url, request.url, golden.name)
            assertEquals(golden.headers, request.headers, golden.name)
            assertEquals(golden.body, request.body, golden.name)
        }
    }

    @Test
    fun answersAreReadAsTheSdkReadsThem() = runTest {
        NewsletterGoldens.answers.forEach { c ->
            val api = NewsletterApi(EcencyPrivateApi({ HttpResult(c.status, c.body) }, { "code" }))
            val result = try {
                "ok:${api.subscriptions(user).size}"
            } catch (e: EcencyHttpException) {
                "error:${e.status}"
            }
            assertEquals(c.result, result, c.name)
        }
        // a subscribe answers one of three states; anything unknown counts as active, as RN's sheet treats it
        suspend fun status(body: String) = NewsletterApi(EcencyPrivateApi({ HttpResult(200, body) }, { "code" })).subscribe(user, "a@b.c", "own", user, "weekly")
        assertEquals(DigestSubscribeStatus.REFUSED, status("""{"status":"refused","reason":"suppressed"}"""))
        assertEquals(DigestSubscribeStatus.PENDING_CONFIRMATION, status("""{"status":"pending_confirmation"}"""))
        assertEquals(DigestSubscribeStatus.ACTIVE, status("""{"status":"active"}"""))
    }

    @Test
    fun theFormIsRns() {
        NewsletterGoldens.forms.forEach { c ->
            val state = DigestState(
                open = true, owner = user, type = c.type, target = c.target, email = c.email, cadence = c.cadence, subscribing = c.pending,
                subscriptions = c.subscriptions.map { DigestSubscription(it.id, it.email, it.type, it.target, it.cadence, it.status) },
            )
            assertEquals(c.subscriptionId, state.subscription?.id, c.name)
            assertEquals(c.knownAddress, state.knownAddress, c.name)
            assertEquals(c.effectiveCadence, state.effectiveCadence, c.name)
            assertEquals(c.needsEmailInput, state.needsEmailInput, c.name)
            val label = mapOf("newsletter.resend" to DigestCopy.RESEND, "newsletter.update" to DigestCopy.UPDATE, "newsletter.subscribe" to DigestCopy.SUBSCRIBE)
            assertEquals(label[c.primaryLabelId], state.primaryLabel, c.name)
            assertEquals(!c.disabled, state.canSubmit, c.name)
            assertEquals(c.showsLeave, state.showsLeave, c.name)
            assertEquals(c.address, state.address, c.name)
        }
        NewsletterGoldens.emails.forEach { (input, valid) -> assertEquals(valid, NewsletterApi.looksLikeEmail(input), "'$input'") }
        NewsletterGoldens.offers.forEach { assertEquals(it.offers, DigestModel.shouldOffer(it.postCount, it.alreadyOffered), "$it") }
    }

    // ---- the sheet ----

    @Test
    fun aNewAddressIsAskedForSubscribedAndSentToItsInbox() = runTest {
        val w = World(this)
        w.gate = CompletableDeferred()
        w.digest.show("own", user)
        w.scope.testScheduler.runCurrent()
        assertTrue(w.digest.current.open)
        assertTrue(w.digest.current.loading, "RN: no form before the lookup answered")
        w.digest.subscribe()
        assertFalse(w.digest.current.subscribing)
        w.gate!!.complete(Unit)
        w.gate = null
        w.idle()
        val form = w.digest.current
        assertFalse(form.loading)
        assertTrue(form.needsEmailInput)
        assertFalse(form.canSubmit)
        assertEquals(DigestCopy.LIST_OWN, form.title)
        assertEquals("X-HS-Token", w.relay.single().headers.keys.single())
        assertEquals("token-$user", w.relay.single().headers["X-HS-Token"])
        w.digest.setEmail(" alice@example.com ")
        w.digest.setCadence("monthly")
        w.digest.setCadence("hourly")
        assertEquals("monthly", w.digest.current.effectiveCadence)
        assertTrue(w.digest.current.canSubmit)
        w.subscribeAnswer = { HttpResult(200, """{"status":"pending_confirmation"}""") }
        w.digest.subscribe()
        assertTrue(w.digest.current.subscribing)
        w.idle()
        assertEquals(
            """{"email":"alice@example.com","type":"own","target":"$user","cadence":"monthly","source":"mobile-app","code":"token-$user"}""",
            w.relay.last().body,
        )
        assertEquals("alice@example.com", w.digest.current.checkInboxEmail)
        assertTrue(w.digest.current.open)
        assertEquals("We sent a confirmation link to alice@example.com. The subscription starts once you confirm.", DigestCopy.checkInboxBody("alice@example.com"))
        w.digest.dismiss()
        assertFalse(w.digest.current.open)
    }

    @Test
    fun aKnownAddressSubscribesInOneTapAndAnExistingSubscriptionCanBeChangedOrLeft() = runTest {
        val w = World(this)
        w.subscriptions = "[${row("c1", "creator", "spike-bob")}]"
        w.digest.show("own", user)
        w.idle()
        assertFalse(w.digest.current.needsEmailInput, "the service already holds an address for the account")
        assertTrue(w.digest.current.canSubmit)
        w.digest.subscribe()
        w.idle()
        assertTrue("\"email\":\"alice@example.com\"" in w.relay.last().body!!)
        assertEquals(listOf(DigestCopy.SUBSCRIBED), w.toasts())
        assertFalse(w.digest.current.open, "RN closes on success")

        w.subscriptions = "[${row("o1", "own", user)}]"
        w.digest.show("own", user)
        w.idle()
        assertEquals(DigestCopy.STATUS_ACTIVE, w.digest.current.status)
        assertEquals(DigestCopy.UPDATE, w.digest.current.primaryLabel)
        assertFalse(w.digest.current.canSubmit, "nothing to update while the cadence is the same")
        assertTrue(w.digest.current.showsLeave)
        w.digest.leave()
        w.idle()
        assertEquals("DELETE", w.relay.last().method)
        assertTrue(w.relay.last().url.endsWith("/api/newsletter/subscriptions/o1"))
        assertEquals(listOf(DigestCopy.LEFT), w.toasts())
        assertFalse(w.digest.current.open)
    }

    @Test
    fun refusalsAndFailuresAreSaidAndTheSheetStays() = runTest {
        val w = World(this)
        w.digest.show("own", user)
        w.idle()
        w.digest.setEmail("alice@example.com")
        w.subscribeAnswer = { HttpResult(200, """{"status":"refused","reason":"suppressed"}""") }
        w.digest.subscribe()
        w.idle()
        assertEquals(DigestCopy.REFUSED, w.digest.current.alert)
        w.digest.clearAlert()
        w.subscribeAnswer = { HttpResult(429, """{"error":"rate_limited"}""") }
        w.digest.subscribe()
        w.idle()
        assertEquals(DigestCopy.TOO_MANY, w.digest.current.alert)
        w.subscribeAnswer = { HttpResult(500, "{}") }
        w.digest.subscribe()
        w.idle()
        assertEquals(DigestCopy.FAIL, w.digest.current.alert)
        assertTrue(w.digest.current.open)
        assertFalse(w.digest.current.subscribing)
        assertTrue(w.toasts().isEmpty())
        // the lookup failing is "not available right now", with only a way out
        w.listAnswer = { HttpResult(503, "{}") }
        w.digest.show("own", user)
        w.idle()
        assertTrue(w.digest.current.unavailable)
        val before = w.relay.size
        w.digest.subscribe()
        w.idle()
        assertEquals(before, w.relay.size)
    }

    @Test
    fun theSheetBelongsToItsAccountAndClosesWhenTheAccountChanges() = runTest {
        val w = World(this)
        w.subscriptions = "[${row("o1", "own", user)}]"
        w.digest.show("own", user)
        w.idle()
        assertEquals(user, w.digest.current.owner)
        // a subscribe is on its way when the account changes: the sheet closes and the answer lands nowhere
        w.digest.setCadence("monthly")
        w.gate = CompletableDeferred()
        w.digest.subscribe()
        w.scope.testScheduler.runCurrent()
        w.app.env.session.switchTo(other)
        w.idle()
        assertFalse(w.digest.current.open, "sheets close on an account change")
        assertNull(w.digest.current.owner)
        w.gate!!.complete(Unit)
        w.gate = null
        w.idle()
        assertFalse(w.digest.current.open)
        assertTrue(w.toasts().none { it == DigestCopy.SUBSCRIBED })
        assertTrue(w.digest.current.subscriptions.isEmpty(), "nothing of the previous account's stays in the state")
        // the next account's sheet asks with its own code
        w.subscriptions = "[]"
        w.digest.show("own", other)
        w.idle()
        assertEquals("token-$other", w.relay.last().headers["X-HS-Token"])
        // signed out, nothing opens
        w.app.env.session.signOut()
        w.idle()
        w.app.env.session.signOut()
        w.idle()
        w.digest.show("own", user)
        assertFalse(w.digest.current.open)
    }

    // ---- the offer after the first post ----

    @Test
    fun theFirstPostOffersTheDigestOnceASecondAfterThePublish() = runTest {
        val w = World(this)
        w.postCount = 0
        val m = w.publish()
        w.scope.testScheduler.runCurrent()
        assertFalse(w.digest.current.open, "not before the publish is done and a second has passed")
        w.idle()
        assertNotNull(m.current.done)
        assertTrue(w.digest.current.open)
        assertTrue(w.digest.current.firstPublish)
        assertEquals(DigestCopy.FIRST_PUBLISH_TITLE, w.digest.current.title)
        assertEquals(DigestCopy.FIRST_PUBLISH_BODY, w.digest.current.body)
        assertEquals("own", w.digest.current.type)
        assertEquals(user, w.digest.current.target)
        assertTrue(w.digest.wasOffered(user))
        w.digest.dismiss()
        // never again, also when the node still says zero posts
        w.publish("Second post")
        w.idle()
        assertFalse(w.digest.current.open)
        assertEquals(1, w.accountReads, "an account that was offered is not asked about again")
    }

    @Test
    fun anAccountWithPostsOrAnUnknownCountIsNeverOffered() = runTest {
        val w = World(this)
        w.postCount = 12
        w.publish()
        w.idle()
        assertFalse(w.digest.current.open)
        assertTrue(w.digest.wasOffered(user), "remembered: it can never be a first post again")
        val w2 = World(this)
        w2.postCount = null
        val m = w2.publish()
        w2.idle()
        assertNotNull(m.current.done)
        assertFalse(w2.digest.current.open, "RN: a missing post_count is don't know, and the answer is no")
        assertFalse(w2.digest.wasOffered(user), "not spent: a later first post may still be offered")
    }

    @Test
    fun theOfferNeverHoldsUpOrFailsThePublish() = runTest {
        val w = World(this)
        // the node never answers the post count
        w.accountsGate = CompletableDeferred()
        val m = w.publish()
        w.idle()
        assertNotNull(m.current.done, "published after the bounded wait")
        assertEquals(1, w.app.broadcasts().size)
        assertFalse(w.digest.current.open)
        // the relay is down when the offer opens: the post is published all the same, the sheet says unavailable
        val w2 = World(this)
        w2.listAnswer = { throw IllegalStateException("offline") }
        val m2 = w2.publish()
        w2.idle()
        assertNotNull(m2.current.done)
        assertNull(m2.current.error)
        assertEquals(1, w2.app.broadcasts().size)
        assertTrue(w2.digest.current.unavailable)
    }

    @Test
    fun aNodeThatNeverAnswersThePostCountHoldsThePublishForThreeSecondsAndNoLonger() = runTest {
        val w = World(this)
        // the transport itself never answers and has no timeout of its own that would end the read sooner
        w.accountsGate = CompletableDeferred()
        assertEquals(3000L, DigestModel.COUNT_TIMEOUT_MILLIS)
        assertTrue(DigestModel.COUNT_TIMEOUT_MILLIS < w.app.env.api.client.readTimeoutMillis, "the bound under test is the digest's, not the client's")
        val m = w.publish()
        testScheduler.advanceTimeBy(DigestModel.COUNT_TIMEOUT_MILLIS - 1)
        testScheduler.runCurrent()
        assertEquals(1, w.accountReads)
        assertEquals(0, w.app.broadcasts().size, "still inside the bounded wait")
        testScheduler.advanceTimeBy(1)
        testScheduler.runCurrent()
        // nothing but the bound was waited for: the post goes out at 3 s, long before the client's own timeout
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        assertEquals(1, w.app.broadcasts().size, "published once the bound passed")
        w.idle()
        assertNotNull(m.current.done)
        assertFalse(w.digest.current.open)
    }

    @Test
    fun anUnreadablePostCountIsWaitedForOncePerAccountAndAppRun() = runTest {
        // timed out once
        val w = World(this)
        w.accountsGate = CompletableDeferred()
        w.publish()
        w.idle()
        assertEquals(1, w.app.broadcasts().size)
        assertEquals(1, w.accountReads)
        val m = w.publish("Second post")
        // no bounded wait this time: the post is out long before 3 s
        testScheduler.advanceTimeBy(500)
        testScheduler.runCurrent()
        assertEquals(2, w.app.broadcasts().size, "the second publish did not wait for the count")
        assertEquals(1, w.accountReads, "and did not ask again")
        w.idle()
        assertNotNull(m.current.done)
        assertFalse(w.digest.wasOffered(user), "not spent: the next app run may still offer")
        // per account: another account's count is still read
        w.accountsGate = null
        w.digest.postCountBeforePublish(other)
        assertEquals(2, w.accountReads)

        // failed once (every node refuses)
        val failing = World(this)
        failing.accountsFail = true
        assertNull(failing.digest.postCountBeforePublish(user))
        val reads = failing.accountReads
        assertTrue(reads > 0)
        assertNull(failing.digest.postCountBeforePublish(user))
        assertEquals(reads, failing.accountReads, "a failed read is not repeated in this run")

        // an answer without a count is an answer: nothing is remembered and the next publish asks again
        val unknown = World(this)
        unknown.postCount = null
        assertNull(unknown.digest.postCountBeforePublish(user))
        unknown.postCount = 0
        assertEquals(0, unknown.digest.postCountBeforePublish(user))
        assertEquals(2, unknown.accountReads)
    }

    @Test
    fun anAccountChangeInTheSecondAfterThePublishSkipsTheOfferWithoutSpendingIt() = runTest {
        val w = World(this)
        w.postCount = 0
        val m = w.publish()
        // run the publish, but not the second after it
        w.scope.testScheduler.advanceTimeBy(DigestModel.OFFER_DELAY_MILLIS - 100)
        w.scope.testScheduler.runCurrent()
        assertNotNull(m.current.done)
        w.app.env.session.switchTo(other)
        w.idle()
        assertFalse(w.digest.current.open, "never opened for the previous account's name")
        assertFalse(w.digest.wasOffered(user), "RN: skipped without writing the flag")
        assertTrue(w.relay.isEmpty())
    }
}
