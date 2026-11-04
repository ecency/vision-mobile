package com.ecency.core.app

import com.ecency.core.api.model.Post
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RN postReadingMetadata's AI recap: gating, the money rules of the paid assist and the summary's translation. */
class RecapModelTest {
    private val user = TestApp.USER
    private val longBody = (1..60).joinToString(" ") { "word$it" } + ". The end of a long enough post."
    private val shortBody = (1..20).joinToString(" ") { "word$it" }

    /** A device store that refuses the paid records (the intent then lives in memory only). */
    private class RefusingStorage : DraftStorage {
        val inner = InMemoryDraftStorage()
        override fun read(account: String, slot: String): String? = inner.read(account, slot)
        override fun write(account: String, slot: String, value: String?) {
            if (slot.startsWith("paid:") && value != null) throw IllegalStateException("disk full")
            inner.write(account, slot, value)
        }
        override fun clear(account: String) = inner.clear(account)
        override fun clearAll() = inner.clearAll()
    }

    private inner class World(val scope: TestScope, signedIn: Boolean = true, storage: DraftStorage? = null) {
        val paid = mutableListOf<JsonObject>()
        val translated = mutableListOf<AuthHttpRequest>()
        var answer: (() -> HttpResult)? = null
        var free = 0
        var pricesDown = false
        var balance = "100.000"
        var keys = 0
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val app = TestApp(scope) { TestApp.chain(it) ?: HttpResult(500, "") }

        init {
            app.env.idempotencyKey = { "key-${++keys}-abcdef" }
            storage?.let { app.env.draftStorage = it }
            app.authBackend.privateApi = { r ->
                val o = Json.parseToJsonElement(r.body ?: "{}").jsonObject
                when (r.url.substringAfter("/private-api/")) {
                    "points" -> HttpResult(200, """{"points":"${if (o["username"]?.jsonPrimitive?.content == "spike-bob") "7.000" else balance}","unclaimed_points":"0"}""")
                    "ai-assist-price" -> if (pricesDown) HttpResult(500, "") else HttpResult(200, """[{"action":"summarize","cost":3,"free_remaining":$free}]""")
                    "ai-assist" -> {
                        paid += o
                        answer?.invoke() ?: HttpResult(200, """{"action":"summarize","output":"A short summary.","cost":3}""")
                    }
                    else -> HttpResult(200, "{}")
                }
            }
            app.authBackend.gate = { r -> if (r.url.endsWith("/ai-assist")) gate?.await() }
            app.authBackend.rest = { r ->
                if (r.url.endsWith("/translate")) {
                    translated += r
                    HttpResult(200, """{"translatedText":"Una breve sinopsis.","detectedLanguage":{"language":"en"}}""")
                } else null
            }
            if (signedIn) {
                KeyVault(app.store).save(VaultAccount("spike-bob", AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-bob"))
                KeyVault(app.store).save(VaultAccount(user, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token"))
                KeyVault(app.store).setCurrentUsername(user)
            }
            app.env.session.start()
            idle()
        }

        fun idle() = scope.testScheduler.advanceUntilIdle()

        fun recap(body: String = longBody): RecapModel {
            val m = app.env.recapModel("alice", "p", "es-ES")
            m.start(Post(author = "alice", permlink = "p", body = body), app.env.session.current.username)
            idle()
            return m
        }

        /** Runs what is ready without moving the virtual clock (a held paid request would otherwise time out). */
        fun step() = repeat(50) { scope.testScheduler.runCurrent() }

        fun switchTo(name: String, m: RecapModel, body: String = longBody) {
            scope.launch { app.env.session.switchTo(name) }
            step()
            m.start(Post(author = "alice", permlink = "p", body = body), app.env.session.current.username)
            step()
        }
    }

    @Test
    fun onlyASignedInReaderOfALongEnoughPostIsOfferedTheRecap() = runTest {
        assertFalse(World(this, signedIn = false).recap().current.eligible, "signed out")
        val w = World(this)
        assertFalse(w.recap(shortBody).current.eligible, "under 50 words")
        val m = w.recap()
        assertTrue(m.current.eligible)
        assertEquals("3 POINTS", m.current.costText)
        assertEquals(UiCopy.RECAP, m.current.label)
    }

    @Test
    fun aTapSendsTheSummarizeActionOnceWithTheSpeechTextAndShowsTheSummary() = runTest {
        val w = World(this)
        val m = w.recap()
        m.summarize()
        w.idle()
        assertEquals(1, w.paid.size)
        assertEquals("summarize", w.paid[0]["action"]!!.jsonPrimitive.content)
        assertEquals(Speech.plainText(longBody), w.paid[0]["text"]!!.jsonPrimitive.content)
        assertEquals("A short summary.", m.current.summary)
        // a second tap does nothing while the summary shows; "Try again" clears it and the next run has a new key
        m.summarize()
        w.idle()
        assertEquals(1, w.paid.size)
        m.tryAgain()
        assertNull(m.current.summary)
        m.summarize()
        w.idle()
        assertEquals(listOf("key-1-abcdef", "key-2-abcdef"), w.paid.map { it["idempotency_key"]!!.jsonPrimitive.content })
    }

    @Test
    fun aShortBalanceIsRefusedBeforeAnythingIsSent() = runTest {
        val w = World(this)
        w.balance = "1.000"
        val m = w.recap()
        m.summarize()
        w.idle()
        assertTrue(w.paid.isEmpty())
        assertEquals(UiCopy.insufficientPoints("3", "1"), m.current.alert)
        // a free run needs no balance
        w.free = 1
        val free = w.recap()
        assertEquals(UiCopy.FREE, free.current.costText)
        free.summarize()
        w.idle()
        assertEquals(1, w.paid.size)
    }

    @Test
    fun anAnswerThatArrivesAfterASwitchIsKeptForThePayerAndNeverShownToTheOtherAccount() = runTest {
        val w = World(this)
        val m = w.recap()
        w.gate = kotlinx.coroutines.CompletableDeferred()
        m.summarize()
        w.step()
        assertTrue(m.current.running)
        w.switchTo("spike-bob", m)
        assertEquals("spike-bob", m.current.owner)
        w.gate!!.complete(Unit)
        w.step()
        assertNull(m.current.summary, "the other account never sees the payer's summary")
        assertEquals(7.0, m.current.balance, "nor the payer's balance")
        assertFalse(m.current.running)
        // back as the payer: the run is still open, and "Check again" fetches it with the same key
        w.switchTo(user, m)
        assertTrue(m.current.unresolved)
        m.summarize()
        w.idle()
        assertEquals("A short summary.", m.current.summary)
        assertEquals(listOf("key-1-abcdef", "key-1-abcdef"), w.paid.map { it["idempotency_key"]!!.jsonPrimitive.content })
    }

    @Test
    fun anEditDuringARunStillShowsItsSummaryWithoutAnotherPayment() = runTest {
        val w = World(this)
        val m = w.recap()
        w.gate = kotlinx.coroutines.CompletableDeferred()
        m.summarize()
        w.step()
        m.start(Post(author = "alice", permlink = "p", body = longBody + " An edit."), user)
        w.step()
        w.gate!!.complete(Unit)
        w.idle()
        assertEquals("A short summary.", m.current.summary, "a summary of the same post, paid for: shown")
        assertEquals(1, w.paid.size)
        // the edited body is taken after the run: a new run is for it, with its own key
        w.gate = null
        m.tryAgain()
        m.summarize()
        w.idle()
        assertEquals(listOf("key-1-abcdef", "key-2-abcdef"), w.paid.map { it["idempotency_key"]!!.jsonPrimitive.content })
        assertTrue(w.paid.last()["text"]!!.jsonPrimitive.content.contains("An edit"))
    }

    @Test
    fun aRunTheDeviceCouldNotStoreCanStillBeFetchedAfterASwitchAndBack() = runTest {
        val w = World(this, storage = RefusingStorage())
        w.answer = { throw IllegalStateException("connection lost") }
        val m = w.recap()
        m.summarize()
        w.idle()
        assertTrue(m.current.unresolved)
        assertTrue(m.current.notice!!.contains(PaidCopy.NOT_STORED))
        w.switchTo("spike-bob", m)
        assertFalse(m.current.unresolved, "nothing of alice's under bob")
        w.switchTo(user, m)
        assertTrue(m.current.unresolved, "kept in memory for the payer")
        w.answer = null
        m.summarize()
        w.idle()
        assertEquals("A short summary.", m.current.summary)
        assertEquals(listOf("key-1-abcdef", "key-1-abcdef"), w.paid.map { it["idempotency_key"]!!.jsonPrimitive.content })
    }

    @Test
    fun withoutAPriceNothingIsSent() = runTest {
        val w = World(this)
        w.pricesDown = true
        val m = w.recap()
        m.summarize()
        w.idle()
        assertTrue(w.paid.isEmpty())
        assertNull(m.current.costText)
    }

    @Test
    fun aLostAnswerIsCheckedAgainWithTheSameKeyAndNeverResentByItself() = runTest {
        val w = World(this)
        w.answer = { throw IllegalStateException("connection lost") }
        val m = w.recap()
        m.summarize()
        w.idle()
        assertTrue(m.current.unresolved)
        assertEquals(PaidCopy.CHECK_AGAIN, m.current.label)
        assertEquals(1, w.paid.size, "never sent again by itself")
        w.answer = null
        m.summarize()
        w.idle()
        assertEquals(listOf("key-1-abcdef", "key-1-abcdef"), w.paid.map { it["idempotency_key"]!!.jsonPrimitive.content })
        assertEquals("A short summary.", m.current.summary)
        assertFalse(m.current.unresolved)
    }

    @Test
    fun theUnansweredRunIsRestoredWhenThePostIsOpenedAgain() = runTest {
        val w = World(this)
        w.answer = { throw IllegalStateException("connection lost") }
        w.recap().summarize()
        w.idle()
        val again = w.recap()
        assertTrue(again.current.unresolved)
        assertEquals(PaidCopy.RESTORED, again.current.notice)
    }

    @Test
    fun refusalsShowRnAlerts() = runTest {
        val w = World(this)
        w.answer = { HttpResult(429, """{"error":"rate_limited"}""") }
        val m = w.recap()
        m.summarize()
        w.idle()
        assertEquals(UiCopy.ERROR_RATE_LIMIT, m.current.alert)
        assertFalse(m.current.unresolved)
        assertNull(m.current.summary)
    }

    @Test
    fun theSummaryIsTranslatedToTheReadersLanguageOnlyWhenAsked() = runTest {
        val w = World(this)
        val m = w.recap()
        m.summarize()
        w.idle()
        assertTrue(w.translated.isEmpty())
        m.toggleTranslation()
        w.idle()
        assertEquals("es", m.current.target)
        assertEquals("Una breve sinopsis.", m.current.shownText)
        assertTrue(w.translated.single().body!!.contains("\"target\":\"es\""))
        m.toggleTranslation()
        assertEquals("A short summary.", m.current.shownText)
    }
}
