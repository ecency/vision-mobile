package com.ecency.core.app

import com.ecency.core.api.ecency.ListenStatus
import com.ecency.core.api.ecency.ListenTrack
import com.ecency.core.api.model.Post
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.EcencyTokenApi
import com.ecency.core.auth.TokenSession
import com.ecency.core.auth.VaultAccountStore
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RN Listen (760b9070be and its follow-ups): the helpers against RN, the bar's status and unlock, renewals, the player. */
class ListenTest {
    private val user = TestApp.USER
    private val other = "spike-other"
    private val longBody = "Reading this post aloud takes a little while, so there are more than ten words here for the bar."

    private inner class World(val scope: TestScope, signedIn: Boolean = true, session: Boolean = true) {
        val app = TestApp(scope) { TestApp.chain(it) ?: HttpResult(500, "") }

        /** Every listen-status and listen request, in order. */
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var status: (JsonObject) -> HttpResult = { HttpResult(200, LOCKED) }
        var unlock: ArrayDeque<(JsonObject) -> HttpResult> = ArrayDeque()
        var refresh: () -> HttpResult? = { null }
        var refreshGate: CompletableDeferred<Unit>? = null
        var unlockGate: CompletableDeferred<Unit>? = null
        var statusGate: CompletableDeferred<Unit>? = null
        var keys = 0

        init {
            app.env.idempotencyKey = { "key-${++keys}-abcdef" }
            app.authBackend.rest = { r -> answer(r) }
            app.authBackend.gate = { r ->
                if (r.url.endsWith(EcencyTokenApi.PATH)) refreshGate?.await()
                if (r.url.endsWith("/private-api/listen")) unlockGate?.await()
                if (r.url.endsWith("/private-api/listen-status")) statusGate?.await()
            }
            val vault = KeyVault(app.store)
            if (signedIn) {
                listOf(user, other).forEach { name -> vault.save(VaultAccount(name, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-$name")) }
                vault.setCurrentUsername(user)
            }
            app.env.session.start()
            idle()
            // the refresh tokens arrive after the app start's own renewal, so the screens start with "token-<user>"
            if (signedIn && session) {
                val store = VaultAccountStore(vault, app.store)
                listOf(user, other).forEach { store.saveSession(TokenSession(it, "token-$it", "refresh-$it", TestApp.NOW + 86_400_000)) }
            }
        }

        private fun answer(r: AuthHttpRequest): HttpResult? {
            if (r.url.endsWith(EcencyTokenApi.PATH)) return refresh()
            val path = r.url.substringAfter("/private-api/", "")
            if (path != "listen-status" && path != "listen") return null
            val o = Json.parseToJsonElement(r.body ?: "{}").jsonObject
            calls += path to o
            return if (path == "listen") (unlock.removeFirstOrNull() ?: { HttpResult(200, PREPARING_QUEUED) })(o) else status(o)
        }

        fun idle() = scope.testScheduler.advanceUntilIdle()
        fun runCurrent() = scope.testScheduler.runCurrent()
        fun advance(millis: Long) {
            scope.testScheduler.advanceTimeBy(millis)
            scope.testScheduler.runCurrent()
        }

        fun statusCalls() = calls.filter { it.first == "listen-status" }.map { it.second }
        fun unlockCalls() = calls.filter { it.first == "listen" }.map { it.second }
        fun exchanges() = app.authBackend.sent.count { it.url.endsWith(EcencyTokenApi.PATH) }

        /** Renewals started since the world was made (the app start's own one is not counted). */
        private val renewalsAtStart = app.env.sessionRenewals.started
        fun renewals() = app.env.sessionRenewals.started - renewalsAtStart

        fun model(post: Post = post()): ListenModel = app.env.listenModel(post.author, post.permlink).also {
            it.start(post)
            idle()
        }

        fun post(body: String = longBody, parent: String? = null, permlink: String = "listen-post") =
            Post(author = "spike-author", permlink = permlink, body = body, updated = "2026-10-19T10:00:00", parentAuthor = parent, depth = if (parent != null) 1 else 0)
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content

    // ---- helpers, against RN's own listen.ts

    @Test
    fun helpersMatchRn() {
        ListenGoldens.formatTime.forEach { (t, want) -> assertEquals(want, Listen.formatTime(t), "formatTime($t)") }
        ListenGoldens.nextSpeed.forEach { (s, want) -> assertEquals(want, Listen.nextSpeed(s), "nextSpeed($s)") }
        ListenGoldens.pickTrack.forEach { (langs, ui, want) ->
            assertEquals(want, Listen.pickTrack(langs.map { ListenTrack(it, 1.0, "https://a/$it") }, ui)?.lang, "pickTrack($langs, $ui)")
        }
        ListenGoldens.preparingMinutes.forEach { (s, want) -> assertEquals(want, Listen.preparingMinutes(s), "preparingMinutes($s)") }
        ListenGoldens.listenMinutes.forEach { (s, w, want) -> assertEquals(want, Listen.listenMinutes(s, w), "listenMinutes($s, $w)") }
        ListenGoldens.unreadable.forEach { (body, want) -> assertEquals(want, Listen.isUnreadableScript(body), "isUnreadableScript($body)") }
        assertEquals(listOf("1x", "1.25x", "1.5x", "1.75x", "2x", "2.5x"), Listen.SPEEDS.map(Listen::speedLabel))
    }

    @Test
    fun theStatusIsReadAsUntrusted() {
        // the live shape (checked against the public status of a post)
        val live = ListenStatus.parse("""{"cost":50,"unlocked":false,"status":"none","languages":[{"lang":"en","share":1,"seconds":168}]}""")!!
        assertEquals(ListenStatus(50.0, false, "none", listOf(com.ecency.core.api.ecency.ListenLanguage("en", 1.0, 168.0))), live)
        val hostile = ListenStatus.parse(
            """{"cost":-5,"unlocked":"true","status":"ready","languages":[{"share":1},{"lang":"de","seconds":"x"}],""" +
                """"tracks":[{"lang":"en","seconds":60,"url":"http://plain.example/a.mp3"},{"lang":"en","seconds":60,"url":"javascript:alert(1)"},""" +
                """{"lang":"de","seconds":60,"url":"https://cdn.example/de.mp3"},{"seconds":1,"url":"https://cdn.example/x.mp3"}],"progress":7,"queued_ahead":-1}""",
        )!!
        assertEquals(0.0, hostile.cost)
        assertFalse(hostile.unlocked, "only a JSON true unlocks")
        assertEquals(listOf("de"), hostile.languages.map { it.lang })
        assertEquals(0.0, hostile.languages.single().seconds)
        assertEquals(listOf("https://cdn.example/de.mp3"), hostile.tracks.map { it.url }, "only https tracks with a language")
        assertEquals(1.0, hostile.progress)
        assertNull(hostile.queuedAhead)
        assertNull(ListenStatus.parse("not json"))
    }

    // ---- the bar

    @Test
    fun aVisitorGetsTheAnonymousStatusAndIsAskedToLogIn() = runTest {
        val w = World(this, signedIn = false)
        val m = w.model()
        val sent = w.statusCalls().single()
        assertNull(sent["code"], "no token for a visitor")
        assertEquals("spike-author", sent.text("author"))
        assertEquals("2026-10-19T10:00:00", sent.text("updated"))
        assertFalse(m.current.hidden)
        assertEquals(ListenButton.LOGIN, m.current.button)
        assertEquals("English · ~3 min", m.current.subtitle)
        m.requestUnlock()
        assertNull(m.current.confirmCost, "a visitor cannot unlock")
        m.close()
    }

    @Test
    fun commentsEmptyPostsAndUnreadableScriptsShowNothing() = runTest {
        val w = World(this)
        val comment = w.model(w.post(parent = "spike-parent"))
        assertTrue(comment.current.hidden)
        assertTrue(w.statusCalls().isEmpty(), "RN asks nothing for a comment")
        val empty = w.model(w.post(body = "", permlink = "empty"))
        assertTrue(empty.current.hidden)
        w.status = { HttpResult(200, """{"cost":50,"unlocked":false,"status":"unsupported","languages":[]}""") }
        assertTrue(w.model(w.post(permlink = "unsupported")).current.hidden)
        // Thai: hidden until the server finds a voice for it
        w.status = { HttpResult(200, """{"cost":50,"unlocked":false,"status":"none","languages":[]}""") }
        val thai = w.model(w.post(body = "สวัสดีครับ ทุกคน วันนี้อากาศดีมาก", permlink = "thai"))
        assertTrue(thai.current.hidden)
        w.status = { HttpResult(200, """{"cost":50,"unlocked":false,"status":"none","languages":[{"lang":"th","share":1,"seconds":60}]}""") }
        val analysed = w.model(w.post(body = "สวัสดีครับ ทุกคน วันนี้อากาศดีมาก", permlink = "thai-2"))
        assertFalse(analysed.current.hidden, "the server's analysis overrides the guess")
        // a short post plays when it was unlocked (no minimum length, b4e1245dca)
        w.status = { HttpResult(200, READY) }
        val short = w.model(w.post(body = "Hi.", permlink = "short"))
        assertTrue(short.current.showsPlayer)
        listOf(comment, empty, thai, analysed, short).forEach { it.close() }
    }

    @Test
    fun theReaderSendsTheTokenAndThePlaceInLineThenProgressThenTheTracks() = runTest {
        val w = World(this)
        val answers = ArrayDeque(listOf(PREPARING_QUEUED, """{"cost":50,"unlocked":true,"status":"preparing","languages":[{"lang":"en","share":1,"seconds":168}],"queued_ahead":0}""", PREPARING_PROGRESS, READY))
        w.status = { HttpResult(200, answers.removeFirstOrNull() ?: READY) }
        val m = w.app.env.listenModel("spike-author", "listen-post")
        m.start(w.post())
        w.runCurrent()
        assertEquals("token-$user", w.statusCalls().single().text("code"))
        assertTrue(m.current.preparing)
        assertEquals("Waiting in line, 2 ahead…", m.current.preparingText)
        assertNull(m.current.preparingPercent)
        assertEquals(ListenButton.NONE, m.current.button)
        w.advance(ListenModel.POLL_MILLIS)
        assertEquals("Next in line…", m.current.preparingText)
        w.advance(ListenModel.POLL_MILLIS)
        assertEquals("Preparing audio · 42%", m.current.preparingText)
        assertEquals(42, m.current.preparingPercent)
        w.advance(ListenModel.POLL_MILLIS)
        assertTrue(m.current.showsPlayer)
        assertEquals(listOf("en", "de"), m.current.tracks.map { it.lang })
        w.advance(ListenModel.POLL_MILLIS * 3)
        assertEquals(4, w.statusCalls().size, "polling stops once the audio is ready")
        m.close()
    }

    @Test
    fun preparingWithoutAPlaceOrProgressShowsTheEstimate() {
        val s = ListenBarState(hidden = false, status = ListenStatus(50.0, true, "analyzing", listOf(com.ecency.core.api.ecency.ListenLanguage("en", 1.0, 400.0))), signedIn = true)
        assertEquals("Preparing audio, about 2 min…", s.preparingText)
        assertEquals("Preparing audio…", s.copy(status = s.status!!.copy(languages = emptyList())).preparingText)
        assertEquals(99, s.copy(status = s.status!!.copy(progress = 0.999)).preparingPercent, "never 100 before it is ready")
        val refunded = ListenBarState(hidden = false, status = ListenStatus(50.0, false, "failed", emptyList()), words = 300, signedIn = true)
        assertEquals("~2 min · Audio could not be made, your Points were refunded", refunded.subtitle)
        assertEquals(ListenButton.UNLOCK, refunded.button)
    }

    @Test
    fun anUnlockIsNeverSentWithoutTheConfirmation() = runTest {
        val w = World(this)
        val m = w.model()
        assertEquals(ListenButton.UNLOCK, m.current.button)
        assertEquals("Unlock · 50 Points", m.current.unlockLabel)
        m.confirmUnlock()
        w.idle()
        assertTrue(w.unlockCalls().isEmpty(), "no confirmation was showing")
        m.requestUnlock()
        assertEquals(50.0, m.current.confirmCost)
        assertEquals("Spend 50 Points to listen to this post? The audio is yours to replay any time.", m.current.confirmText)
        m.cancelUnlock()
        m.confirmUnlock()
        w.idle()
        assertTrue(w.unlockCalls().isEmpty(), "cancelled")
        w.status = { HttpResult(200, PREPARING_QUEUED) }
        val before = w.statusCalls().size
        m.requestUnlock()
        m.confirmUnlock()
        m.confirmUnlock()
        w.runCurrent()
        val sent = w.unlockCalls().single()
        assertEquals("token-$user", sent.text("code"))
        assertEquals("spike-author", sent.text("author"))
        assertEquals("listen-post", sent.text("permlink"))
        assertEquals("2026-10-19T10:00:00", sent.text("updated"))
        assertEquals("key-1-abcdef", sent.text("idempotency_key"))
        // the answer is the new status at once, then the status polls while the audio is made
        assertTrue(m.current.preparing)
        assertFalse(m.current.busy)
        assertNull(m.idempotencyKey, "a delivered unlock is over")
        assertEquals(before, w.statusCalls().size, "the answer is the status: not asked again at once")
        w.advance(ListenModel.POLL_MILLIS)
        assertEquals(before + 1, w.statusCalls().size, "polled after the SDK's interval")
        m.close()
    }

    @Test
    fun aReaderShortOfPointsIsSentToGetThem() = runTest {
        val w = World(this)
        val m = w.model()
        w.unlock.add { HttpResult(402, """{"error":"insufficient_points","required":50,"available":12.5}""") }
        m.requestUnlock()
        m.confirmUnlock()
        w.idle()
        val alert = m.current.alert as ListenAlert.NotEnoughPoints
        assertEquals("Listening needs 50 Points and you have 12.5.", alert.message)
        assertNull(m.idempotencyKey, "a definite answer: the next unlock is a new request")
        m.dismissAlert()
        assertNull(m.current.alert)
        // the SDK asks for the real status after a failed unlock
        assertEquals(2, w.statusCalls().size)
        m.close()
    }

    @Test
    fun aLostAnswerIsResentWithTheSameKeyAndKeptForTheNextTap() = runTest {
        val w = World(this)
        val m = w.model()
        repeat(3) { w.unlock.add { HttpResult(502, "bad gateway") } }
        m.requestUnlock()
        m.confirmUnlock()
        w.idle()
        assertEquals(3, w.unlockCalls().size, "the SDK's two retries for a gateway status")
        assertEquals(setOf("key-1-abcdef"), w.unlockCalls().map { it.text("idempotency_key") }.toSet())
        assertEquals(ListenAlert.UnlockFailed, m.current.alert)
        // the outcome is unknown: the next tap replays the same key, never a second charge
        m.dismissAlert()
        m.requestUnlock()
        m.confirmUnlock()
        w.idle()
        assertEquals(4, w.unlockCalls().size, "the retry left the device")
        assertEquals("key-1-abcdef", w.unlockCalls().last().text("idempotency_key"))
        assertEquals(1, w.keys)
        m.close()
    }

    @Test
    fun anExpiredTokenIsRenewedAndTheSameUnlockResentOnce() = runTest {
        val w = World(this)
        val m = w.model()
        w.unlock.add { HttpResult(401, "{}") }
        w.unlock.add { HttpResult(401, "{}") }
        m.requestUnlock()
        m.confirmUnlock()
        w.idle()
        val sent = w.unlockCalls()
        assertEquals(2, sent.size, "renewed once, then a definite answer")
        assertEquals(listOf("token-$user", "access-$user"), sent.map { it.text("code") })
        assertEquals(listOf("key-1-abcdef", "key-1-abcdef"), sent.map { it.text("idempotency_key") })
        assertEquals(1, w.exchanges())
        assertEquals(ListenAlert.UnlockFailed, m.current.alert)
        assertNull(m.idempotencyKey)
        // a renewal that brings nothing new ends it without a second send
        w.unlock.add { HttpResult(401, "{}") }
        m.dismissAlert()
        m.requestUnlock()
        m.confirmUnlock()
        w.idle()
        assertEquals(3, w.unlockCalls().size, "the stored token is already the newest: nothing to resend with")
        m.close()
    }

    @Test
    fun aRefusedStatusRenewsTheTokenOnceAndAgainOnlyAfterAFailedRenewal() = runTest {
        val w = World(this)
        w.status = { o -> if (o.text("code") == "token-$user") HttpResult(401, "{}") else HttpResult(200, READY) }
        // the first renewal fails (offline): the stored token is kept and the bar is allowed to renew again
        w.refresh = { HttpResult(500, "") }
        val m = w.model()
        assertEquals(1, w.renewals())
        assertNull(m.current.status)
        assertEquals(ListenButton.LOADING, m.current.button)
        w.refresh = { null }
        m.onForeground()
        w.idle()
        assertEquals(2, w.renewals(), "renewed again on the next refusal")
        assertTrue(m.current.showsPlayer)
        assertEquals("access-$user", w.statusCalls().last().text("code"))
        m.onForeground()
        w.idle()
        assertEquals(2, w.renewals(), "the status loaded: nothing more to renew")
        // a renewal that worked is not repeated by this bar (RN keeps its guard after a success)
        w.status = { HttpResult(401, "{}") }
        m.start(w.post().copy(updated = "2026-10-19T11:00:00"))
        w.idle()
        assertEquals(2, w.renewals())
        m.close()
    }

    @Test
    fun aRenewalCutShortByAReloadIsJoinedAndTheBarCanRenewAgain() = runTest {
        // review probe: 401, a renewal held on the network and failing, the foreground during it, then online again
        val w = World(this)
        w.status = { o -> if (o.text("code") == "token-$user") HttpResult(401, "{}") else HttpResult(200, READY) }
        w.refreshGate = CompletableDeferred()
        w.refresh = { HttpResult(500, "") }
        val m = w.model()
        assertEquals(1, w.renewals())
        m.onForeground()
        m.start(w.post().copy(updated = "2026-10-19T12:00:00"))
        w.idle()
        assertEquals(1, w.renewals(), "the reloads wait for the renewal on its way")
        w.refreshGate!!.complete(Unit)
        w.idle()
        assertEquals(2, w.renewals(), "the failed renewal is over: the waiting reload is refused again and renews again")
        assertNull(m.current.status)
        w.refresh = { null }
        m.onForeground()
        w.idle()
        assertEquals(3, w.renewals(), "renews again on the foreground")
        assertTrue(m.current.showsPlayer)
        m.close()
    }

    @Test
    fun pollingPausesInTheBackgroundAndGoesOnInTheForeground() = runTest {
        val w = World(this)
        w.status = { HttpResult(200, PREPARING_QUEUED) }
        val m = w.app.env.listenModel("spike-author", "listen-post")
        m.start(w.post())
        w.runCurrent()
        assertEquals(1, w.statusCalls().size)
        m.onBackground()
        w.advance(ListenModel.POLL_MILLIS * 4)
        assertEquals(1, w.statusCalls().size, "no polling in the background")
        m.onForeground()
        w.runCurrent()
        assertEquals(2, w.statusCalls().size, "asked again at once on the foreground")
        w.advance(ListenModel.POLL_MILLIS)
        assertEquals(3, w.statusCalls().size)
        m.close()
    }

    @Test
    fun theBarStaysHiddenUntilTheScriptGuessIsIn() = runTest {
        val w = World(this)
        val m = w.app.env.listenModel("spike-author", "listen-post")
        m.start(w.post())
        assertTrue(m.current.hidden, "a Thai post must not flash the bar before the guess")
        w.idle()
        assertFalse(m.current.hidden)
        m.close()
    }

    @Test
    fun aLostAnswerKeepsItsKeyWhetherNoAnswerOrAnUnreadableOne() = runTest {
        val w = World(this)
        val m = w.model()
        repeat(3) { w.unlock.add { throw IllegalStateException("connection reset") } }
        m.requestUnlock()
        m.confirmUnlock()
        w.idle()
        assertEquals(3, w.unlockCalls().size, "a transport failure is sent again with the same key")
        assertEquals("key-1-abcdef", m.idempotencyKey)
        m.dismissAlert()
        w.unlock.add { HttpResult(200, "<html>not json</html>") }
        m.requestUnlock()
        m.confirmUnlock()
        w.idle()
        assertEquals("key-1-abcdef", w.unlockCalls().last().text("idempotency_key"))
        assertEquals("key-1-abcdef", m.idempotencyKey, "an unreadable 200 says nothing about the charge")
        assertEquals(ListenAlert.UnlockFailed, m.current.alert)
        m.close()
    }

    @Test
    fun anUnlockOnItsWayFinishesWhenTheReaderChangesOrTheScreenCloses() = runTest {
        // the clock never moves while the request is held (the transport's own 30 s bound would end it)
        val w = World(this)
        val m = w.model()
        w.unlockGate = CompletableDeferred()
        m.requestUnlock()
        m.confirmUnlock()
        w.runCurrent()
        val switched = launch { w.app.env.session.switchTo(other) }
        w.runCurrent()
        assertTrue(switched.isCompleted)
        assertEquals("token-$other", w.statusCalls().last().text("code"))
        w.unlockGate!!.complete(Unit)
        w.runCurrent()
        assertEquals(1, w.unlockCalls().size, "the request was not cut off")
        assertEquals("key-1-abcdef", w.unlockCalls().single().text("idempotency_key"))
        assertFalse(m.current.view.unlocked, "the answer belonged to the previous reader")
        assertFalse(m.current.busy)
        // the screen closing does not cut a request off either
        val n = w.app.env.listenModel("spike-author", "second").also { it.start(w.post(permlink = "second")) }
        w.runCurrent()
        w.unlockGate = CompletableDeferred()
        n.requestUnlock()
        n.confirmUnlock()
        w.runCurrent()
        n.close()
        w.unlockGate!!.complete(Unit)
        w.runCurrent()
        assertEquals(2, w.unlockCalls().size)
        m.close()
    }

    @Test
    fun aReopenedScreenJoinsTheUnlockOnItsWayInsteadOfPayingAgain() = runTest {
        // review probe: a held unlock, the screen closed, the same post opened again, confirm
        val w = World(this)
        val m = w.model()
        w.unlockGate = CompletableDeferred()
        w.unlock.add { HttpResult(200, PREPARING_QUEUED) }
        m.requestUnlock()
        m.confirmUnlock()
        w.runCurrent()
        m.close()
        val again = w.app.env.listenModel("spike-author", "listen-post").also { it.start(w.post()) }
        w.runCurrent()
        assertTrue(again.current.busy, "the reopened bar shows the unlock on its way")
        again.requestUnlock()
        again.confirmUnlock()
        w.runCurrent()
        w.unlockGate!!.complete(Unit)
        w.runCurrent()
        assertEquals(listOf("key-1-abcdef"), w.unlockCalls().map { it.text("idempotency_key") }, "one request, one key")
        assertFalse(again.current.busy)
        assertTrue(again.current.preparing, "the answer is the reopened bar's status")
        assertNull(again.idempotencyKey, "a delivered unlock is over")
        again.close()
    }

    @Test
    fun twoOpenScreensOfOnePostShareOneUnlock() = runTest {
        val w = World(this)
        val a = w.model()
        val b = w.model()
        w.unlockGate = CompletableDeferred()
        a.requestUnlock()
        a.confirmUnlock()
        w.runCurrent()
        b.requestUnlock()
        b.confirmUnlock()
        w.runCurrent()
        w.unlockGate!!.complete(Unit)
        w.runCurrent()
        assertEquals(listOf("key-1-abcdef"), w.unlockCalls().map { it.text("idempotency_key") }, "the second screen joined the first request")
        assertTrue(a.current.preparing && b.current.preparing)
        a.close()
        b.close()
    }

    @Test
    fun anUnlockDeliveredToOneOpenScreenShowsOnTheOtherToo() = runTest {
        val w = World(this)
        val a = w.model()
        val b = w.model()
        a.requestUnlock()
        a.confirmUnlock()
        w.runCurrent()
        assertEquals(1, w.unlockCalls().size)
        assertTrue(a.current.preparing)
        assertTrue(b.current.preparing, "the other open bar of this post shows the unlock too")
        assertEquals(ListenButton.NONE, b.current.button, "so it cannot be unlocked (paid) a second time")
        a.close()
        b.close()
    }

    @Test
    fun aLostAnswerLeavesItsKeyForTheNextScreenToo() = runTest {
        val w = World(this)
        val m = w.model()
        repeat(3) { w.unlock.add { HttpResult(504, "") } }
        m.requestUnlock()
        m.confirmUnlock()
        w.idle()
        m.close()
        val again = w.model()
        again.requestUnlock()
        again.confirmUnlock()
        w.idle()
        assertEquals(setOf("key-1-abcdef"), w.unlockCalls().map { it.text("idempotency_key") }.toSet())
        again.close()
    }

    @Test
    fun theAppGoingToTheBackgroundWhileTheStatusIsAskedForStopsThePoll() = runTest {
        val w = World(this)
        w.status = { HttpResult(200, PREPARING_QUEUED) }
        w.statusGate = CompletableDeferred()
        val m = w.app.env.listenModel("spike-author", "listen-post")
        m.start(w.post())
        w.runCurrent()
        m.onBackground()
        w.statusGate!!.complete(Unit)
        w.runCurrent()
        assertTrue(m.current.preparing)
        w.statusGate = null
        w.advance(ListenModel.POLL_MILLIS * 3)
        val asked = w.statusCalls().size
        // closed before asserting: a poll left running would keep the test scheduler busy forever
        m.close()
        assertEquals(1, asked, "the answer that came in the background schedules no poll")
    }

    @Test
    fun otherErrorsAndVisitorsRenewNothing() = runTest {
        val w = World(this)
        w.status = { HttpResult(500, "") }
        val m = w.model()
        assertEquals(4, w.statusCalls().size, "three retries as React Query")
        assertEquals(0, w.exchanges())
        val visitor = World(this, signedIn = false)
        visitor.status = { HttpResult(401, "{}") }
        visitor.model()
        assertEquals(0, visitor.exchanges())
        m.close()
    }

    @Test
    fun oneRenewalPerAccountAtATimeAcrossScreens() = runTest {
        val w = World(this)
        w.status = { o -> if (o.text("code") == "token-$user") HttpResult(401, "{}") else HttpResult(200, READY) }
        w.refreshGate = CompletableDeferred()
        val a = w.app.env.listenModel("spike-author", "listen-post").also { it.start(w.post()) }
        val b = w.app.env.listenModel("spike-author", "other-post").also { it.start(w.post(permlink = "other-post")) }
        w.idle()
        assertEquals(1, w.renewals(), "the second screen waits for the first renewal")
        w.refreshGate!!.complete(Unit)
        w.idle()
        assertEquals(1, w.exchanges())
        assertTrue(a.current.showsPlayer && b.current.showsPlayer, "both use the one renewed token")
        a.close()
        b.close()
    }

    @Test
    fun aTokenRenewedAfterTheReaderLoggedOutIsNeitherStoredNorUsed() = runTest {
        val w = World(this)
        val m = w.model()
        w.unlock.add { HttpResult(401, "{}") }
        w.refreshGate = CompletableDeferred()
        m.requestUnlock()
        m.confirmUnlock()
        w.idle()
        assertEquals(1, w.renewals())
        // the reader signs out while the renewal is on the network
        val out = launch { w.app.env.session.logout(user) }
        w.idle()
        w.refreshGate!!.complete(Unit)
        w.idle()
        out.join()
        assertNull(KeyVault(w.app.store).account(user), "the account stays gone")
        assertNull(VaultAccountStore(KeyVault(w.app.store), w.app.store).session(user), "no session written back")
        assertEquals(1, w.unlockCalls().size, "the renewed token was never sent")
        assertFalse(m.current.busy)
        m.close()
    }

    @Test
    fun aRenewalIsHandedOutOnlyWhileItsAccountIsCurrent() = runTest {
        val w = World(this)
        w.refreshGate = CompletableDeferred()
        var switched: String? = "unset"
        val first = launch { switched = w.app.env.sessionRenewals.renew(user) }
        w.idle()
        w.app.env.session.switchTo(other)
        w.refreshGate!!.complete(Unit)
        w.idle()
        first.join()
        assertNull(switched, "renewed for an account that is no longer the reader")
        // the account is still on the device, so its renewed session is kept (AccountManager decides that)
        assertEquals("access-$user", KeyVault(w.app.store).account(user)?.accessToken)
    }

    @Test
    fun aRenewalAnsweredAfterALogoutWritesNothing() = runTest {
        val w = World(this)
        w.refreshGate = CompletableDeferred()
        var got: String? = "unset"
        val renewal = launch { got = w.app.env.sessionRenewals.renew(user) }
        w.idle()
        w.app.env.session.logout(user)
        w.refreshGate!!.complete(Unit)
        w.idle()
        renewal.join()
        assertNull(got)
        assertNull(KeyVault(w.app.store).account(user))
        assertNull(VaultAccountStore(KeyVault(w.app.store), w.app.store).session(user))
    }

    @Test
    fun anotherAccountGetsItsOwnStatusAndNothingOfThePreviousUnlock() = runTest {
        val w = World(this)
        val m = w.model()
        w.unlock.add { HttpResult(503, "") }
        m.requestUnlock()
        m.confirmUnlock()
        w.idle()
        assertTrue(m.idempotencyKey != null)
        w.app.env.session.switchTo(other)
        w.idle()
        assertNull(m.idempotencyKey, "the key belonged to the previous reader")
        assertNull(m.current.alert)
        assertEquals("token-$other", w.statusCalls().last().text("code"))
        m.close()
    }

    @Test
    fun freshUrlsComeFromTheStatusOrWithARenewedToken() = runTest {
        val w = World(this)
        w.status = { HttpResult(200, READY) }
        val m = w.model()
        val before = w.statusCalls().size
        m.renewUrls()
        assertEquals(before + 1, w.statusCalls().size)
        assertEquals(0, w.exchanges())
        // the URL and the token both expired: renewed, asked again with the new token
        w.status = { o -> if (o.text("code") == "token-$user") HttpResult(401, "{}") else HttpResult(200, READY) }
        m.renewUrls()
        assertEquals(1, w.exchanges())
        assertEquals("access-$user", w.statusCalls().last().text("code"))
        // nothing new to renew with, and no audio: an error the player shows
        w.status = { HttpResult(200, LOCKED) }
        assertFailsWith<IllegalStateException> { m.renewUrls() }
        m.close()
    }

    // ---- the player

    private val tracks = listOf(ListenTrack("en", 120.0, "https://cdn.example/en.mp3"), ListenTrack("de", 90.0, "https://cdn.example/de.mp3"))

    @Test
    fun thePlayerStartsInTheReadersLanguageAndCyclesSpeeds() {
        var s = ListenPlayerState.initial(tracks, "de-DE")
        assertEquals("de", s.lang)
        assertEquals("1:30", s.durationLabel)
        assertFalse(s.started)
        s = s.toggle()
        assertTrue(s.started && !s.paused)
        val speeds = generateSequence(s) { it.nextSpeed() }.take(7).map { it.speedLabel }.toList()
        assertEquals(listOf("1x", "1.25x", "1.5x", "1.75x", "2x", "2.5x", "1x"), speeds)
        assertNull(s.switchTrack("de", s.track(tracks)), "the same language changes nothing")
        val en = s.switchTrack("en", s.track(tracks))!!
        assertEquals(0.0, en.resume)
        assertEquals("en", en.track(tracks)?.lang)
        assertEquals(0.0, en.loaded(121.0).seekTo)
        assertEquals(121.0, en.loaded(121.0).state.duration)
    }

    @Test
    fun theLockScreenIsFollowedButNotAStopReportedWhileLoading() {
        val s = ListenPlayerState.initial(tracks, "en").toggle().loadStarted()
        assertFalse(s.playbackChanged(isPlaying = false).paused, "loading: the player says not playing before it starts")
        val loaded = s.loaded(120.0).state
        assertTrue(loaded.playbackChanged(isPlaying = false).paused, "a pause from the lock screen")
        assertFalse(loaded.playbackChanged(isPlaying = false, isSeeking = true).paused)
        assertFalse(loaded.playbackChanged(false).playbackChanged(true).paused)
        val ended = loaded.progressed(119.0).ended()
        assertEquals(0.0, ended.seekTo)
        assertTrue(ended.state.paused)
        assertEquals(0.0, ended.state.time)
    }

    @Test
    fun anExpiredUrlIsRenewedAFewTimesThenTheErrorIsShown() {
        var s = ListenPlayerState.initial(tracks, "en").toggle().loaded(120.0).state.progressed(40.0)
        repeat(ListenPlayerState.MAX_URL_REFRESHES) {
            val (next, renew) = s.error()
            assertTrue(renew)
            assertEquals(40.0, next.resume, "picks up where it failed")
            s = next.loaded(120.0).state.progressed(40.0)
        }
        val (stopped, renew) = s.error()
        assertFalse(renew, "a file that keeps failing at the same spot stops")
        assertTrue(stopped.failed && stopped.paused)
        // "Try again" starts the count over
        val retried = stopped.retry()
        assertFalse(retried.failed)
        assertEquals(0, retried.refreshes)
        assertEquals(40.0, retried.resume)
    }

    @Test
    fun playingWellPastTheFailureStartsTheCountOver() {
        var s = ListenPlayerState.initial(tracks, "en").toggle().loaded(3600.0).state.progressed(100.0)
        repeat(3) {
            s = s.error().first.loaded(3600.0).state
            assertEquals(it + 1, s.refreshes)
        }
        assertEquals(3, s.progressed(130.0).refreshes, "exactly 30 s past is not yet proof")
        s = s.progressed(130.5)
        assertEquals(0, s.refreshes, "a renewal that keeps playing proved itself")
        assertTrue(s.error().second)
    }

    @Test
    fun aRenewalThatWorksClearsTheFailure() {
        val failed = ListenPlayerState.initial(tracks, "en").toggle().renewed(ok = false)
        assertTrue(failed.failed && failed.paused)
        assertFalse(failed.renewed(ok = true).failed)
        assertFalse(failed.loaded(10.0).state.failed, "a later load works")
        val dragged = failed.drag(33.0)
        assertEquals("0:33", dragged.timeLabel)
        val let = dragged.dragEnded(33.0)
        assertEquals(33.0, let.seekTo)
        assertNull(let.state.seeking)
        assertEquals(1.0, ListenPlayerState.initial(emptyList(), "en").sliderMax)
    }

    companion object {
        const val LOCKED = """{"cost":50,"unlocked":false,"status":"none","languages":[{"lang":"en","share":1,"seconds":168}]}"""
        const val PREPARING_QUEUED = """{"cost":50,"unlocked":true,"status":"preparing","languages":[{"lang":"en","share":1,"seconds":168}],"queued_ahead":2}"""
        const val PREPARING_PROGRESS = """{"cost":50,"unlocked":true,"status":"preparing","languages":[{"lang":"en","share":1,"seconds":168}],"progress":0.42}"""
        const val READY = """{"cost":50,"unlocked":true,"status":"ready","languages":[{"lang":"en","share":0.7,"seconds":168},{"lang":"de","share":0.3,"seconds":70}],""" +
            """"tracks":[{"lang":"en","seconds":168,"url":"https://audio.example/en.mp3?sig=1"},{"lang":"de","seconds":70,"url":"https://audio.example/de.mp3?sig=1"}]}"""
    }
}
