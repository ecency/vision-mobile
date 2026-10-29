package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Sent
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.waves.WavePublish
import com.ecency.core.api.waves.WavesApi
import com.ecency.core.api.waves.WavesGoldens
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Waves lists, tabs, the composer and the shorts reels over fake endpoints (synthetic rows only). */
@OptIn(ExperimentalCoroutinesApi::class)
class WavesModelsTest {
    private val alice = TestApp.USER
    private val bob = "spike-bob"

    /** A scriptable waves backend: answers by URL, records requests, can hold an answer back. */
    private class Backend : AuthHttp {
        val sent = mutableListOf<String>()
        var feed: (String) -> String = { "[]" }
        var shorts: (String) -> String = { "[]" }
        var promoted = "[]"
        var bots = "[]"
        var trending = "[]"
        var stream: String? = """{"videoUrl":"https://play.3speak.tv/hls?u=one"}"""
        var failFeed = false

        /** When set, feed requests wait here before answering (with what [feed] says at that moment). */
        var gate: CompletableDeferred<Unit>? = null
        var promotedGate: CompletableDeferred<Unit>? = null

        override suspend fun send(request: AuthHttpRequest): HttpResult {
            sent += request.url
            val url = request.url
            return when {
                url.contains("/private-api/waves/feed") -> {
                    val body = feed(url)
                    gate?.await()
                    if (failFeed) HttpResult(500, "down") else HttpResult(200, body)
                }
                url.contains("/private-api/waves/shorts") -> HttpResult(200, shorts(url))
                url.contains("/private-api/promoted-entries") -> {
                    val body = promoted
                    promotedGate?.await()
                    HttpResult(200, body)
                }
                url.contains("/private-api/public/bots") -> HttpResult(200, bots)
                url.contains("/waves/trending/tags") -> HttpResult(200, trending)
                url.contains("play.3speak.tv/api/embed") -> stream?.let { HttpResult(200, it) } ?: HttpResult(404, "")
                else -> HttpResult(404, "")
            }
        }

        fun feedCalls() = sent.filter { it.contains("/waves/feed") }
    }

    private fun row(author: String, permlink: String, cursor: String? = "c-$permlink", body: String = "Wave $permlink", extra: String = "") =
        """{"id":1,"author":"$author","permlink":"$permlink","title":"","body":${Json.encodeToString(body)},"json_metadata":{"app":"ecency/4.0.0-mobile","tags":["waves"]},
           "timestamp":"2026-10-06T11:00:00+00:00","pending_payout_value":"0.100 HBD","net_votes":2,"children":1,"tip_count":0,"host":"ecency.waves"
           ${cursor?.let { ""","_cursor":"$it"""" }.orEmpty()}$extra}"""

    private fun short(author: String, permlink: String, video: Boolean = true) = row(
        author, permlink, body = "Clip $permlink https://play.3speak.tv/embed?v=$author/v-$permlink",
        extra = if (video) ""","video":{"platform":"3speak","author":"$author","permlink":"v-$permlink","embed_url":"https://play.3speak.tv/embed?v=$author/v-$permlink","thumbnail_url":"https://i.ecency.com/x/thumbnail.jpg","duration_secs":null}""" else "",
    )

    private fun promotedEntry(author: String, permlink: String) =
        """{"author":"$author","permlink":"$permlink","body":"Promoted $permlink","created":"2026-10-06T10:00:00","depth":1,"parent_author":"ecency.waves","parent_permlink":"waves-x",
           "author_reputation":60.5,"active_votes":[{"voter":"x","rshares":10}],"stats":{"gray":false,"hide":false,"total_votes":1},"json_metadata":{"app":"peakd/1"}}"""

    private var containers: Map<String, String> = mapOf("ecency.waves" to """{"author":"ecency.waves","permlink":"waves-20261006","json_metadata":{"tags":["waves-20261006","hive-125125"]}}""")
    private var linkedTitle: String? = "Linked title"
    private var rejectBroadcast = false

    /** Broadcasts are refused for lack of resource credits. */
    private var rcShort = false

    /** What `condenser_api.get_content` says the chain holds for any permlink asked (null: nothing). */
    private var onChainBody: String? = null

    private fun chain(): suspend FakeTransport.(Sent) -> HttpResult = { req ->
        when (req.method) {
            "bridge.get_account_posts" -> {
                val host = req.params.jsonObject.getValue("account").jsonPrimitive.content
                containers[host]?.let { if (it == "fail") HttpResult(500, "down") else FakeTransport.result(req, "[$it]") } ?: FakeTransport.result(req, "[]")
            }
            "bridge.get_post" -> linkedTitle?.let { FakeTransport.result(req, """{"author":"spike-carol","permlink":"my-post","title":${Json.encodeToString(it)}}""") }
                ?: FakeTransport.result(req, "null")
            "condenser_api.broadcast_transaction" -> when {
                rcShort -> FakeTransport.error(req, -32000, "Account: x has 12 RC, needs 3400 RC. Please wait to transact, or power up HIVE.")
                rejectBroadcast -> HttpResult(500, "down")
                else -> FakeTransport.result(req, "null")
            }
            "condenser_api.get_content" -> onChainBody?.let { body ->
                val p = req.params.jsonArray
                FakeTransport.result(req, """{"author":${p[0]},"permlink":${p[1]},"body":${Json.encodeToString(body)}}""")
            } ?: FakeTransport.result(req, """{"author":"","permlink":""}""")
            "condenser_api.get_following" -> FakeTransport.result(req, "[]")
            "condenser_api.get_accounts" -> FakeTransport.result(req, "[]")
            else -> TestApp.chain(req) ?: HttpResult(500, "")
        }
    }

    private fun TestScope.app(backend: Backend): TestApp = TestApp(this, handler = chain()).also {
        it.env.waves.api = WavesApi(backend)
        it.authBackend.rest = { r -> if (r.url.endsWith("/private-api/public/bots")) HttpResult(200, backend.bots) else null }
    }

    /** The points reports (`usr-activity`) types; the account needs its Ecency session for them ([withSession]). */
    private fun TestApp.reportTypes() = authBackend.sent.filter { it.url.endsWith("/private-api/usr-activity") }
        .map { Json.parseToJsonElement(it.body!!).jsonObject.getValue("ty").jsonPrimitive.content.toInt() }

    /** Gives the account its Ecency session (the points reports carry its code) and answers the reports. */
    private fun TestScope.withSession(app: TestApp, user: String = alice) {
        KeyVault(app.store).save(VaultAccount(user, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "access-$user"))
        val rest = app.authBackend.rest
        app.authBackend.rest = { r -> if (r.url.endsWith("/private-api/usr-activity")) HttpResult(200, "{}") else rest?.invoke(r) }
        // the lock's settings read (no PIN): nothing private is sent while they are unread
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
    }

    private fun TestScope.signIn(app: TestApp, user: String = alice) {
        app.signIn(user)
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        assertEquals(user, app.env.session.current.username)
    }

    private suspend fun TestScope.switchTo(app: TestApp, user: String) {
        KeyVault(app.store).save(VaultAccount(user, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF))
        app.env.session.switchTo(user)
    }

    private fun WavesFeedModel.permlinks() = current.items.map { it.key.permlink }

    // ---- the list ----

    @Test
    fun theFeedPagesByCursorAndEndsOnAShortPage() = runTest {
        val backend = Backend()
        backend.feed = { url ->
            when {
                "cursor=c-w20" in url -> (21..23).joinToString(",", "[", "]") { row("a$it", "w$it") }
                "cursor=" in url -> error("unexpected cursor $url")
                else -> (1..20).joinToString(",", "[", "]") { row("a$it", "w$it") }
            }
        }
        val app = app(backend)
        val feed = WavesFeedModel(app.env, WavesFeedKind.ForYou)
        feed.start()
        assertTrue(feed.current.isLoading)
        testScheduler.advanceUntilIdle()
        assertEquals(20, feed.current.items.size)
        assertFalse(feed.current.endReached)
        assertEquals(1, feed.current.pages)
        feed.loadMore()
        testScheduler.advanceUntilIdle()
        assertEquals(23, feed.current.items.size)
        assertTrue(feed.current.endReached)
        feed.loadMore()
        testScheduler.advanceUntilIdle()
        assertEquals(
            listOf("https://ecency.com/private-api/waves/feed?limit=20", "https://ecency.com/private-api/waves/feed?limit=20&cursor=c-w20"),
            backend.feedCalls(),
        )
        // the card: feed rows carry a vote count, no reputation; the body was rendered off the main thread
        val first = feed.current.items.first()
        assertEquals(2, first.card.votes)
        assertEquals("$0.100", first.card.payoutText)
        assertNull(first.reputation)
        assertTrue(first.isFromEcency)
        assertEquals("ecency.waves", first.host)
        assertTrue(first.blocks.isNotEmpty())
        assertEquals(emptyList(), app.renderer.offRenderCalls)
        feed.close()
    }

    @Test
    fun eachKindAsksForItsOwnSliceAsTheSignedInObserver() = runTest {
        val backend = Backend()
        val app = app(backend)
        signIn(app)
        listOf(
            WavesFeedKind.ForYou, WavesFeedKind.Following, WavesFeedKind.Tag("art"), WavesFeedKind.Container("peak.snaps"), WavesFeedKind.Author(bob),
        ).forEach {
            val m = WavesFeedModel(app.env, it)
            m.start()
            testScheduler.advanceUntilIdle()
            m.close()
        }
        val base = "https://ecency.com/private-api/waves/feed?limit=20"
        assertEquals(
            listOf("$base&observer=$alice", "$base&following=$alice&observer=$alice", "$base&tag=art&observer=$alice", "$base&container=peak.snaps&observer=$alice", "$base&author=$bob"),
            backend.feedCalls(),
        )
        // promoted waves are asked for on the unfiltered feeds only
        assertEquals(2, backend.sent.count { it.contains("promoted-entries") })
    }

    @Test
    fun mutedGrayBotAndHiddenNsfwWavesAreDropped() = runTest {
        val backend = Backend()
        backend.bots = """["spike-bot"]"""
        backend.feed = {
            listOf(
                row("fine", "w1"), row("ignored", "w2"), row("spike-bot", "w3"),
                row("grayed", "w4", extra = ""","stats":{"gray":true}"""),
                row("adult", "w5", extra = "").replace("""["waves"]""", """["waves","nsfw"]"""),
                row("fine", "w6"),
            ).joinToString(",", "[", "]")
        }
        val app = app(backend)
        app.env.mutedAuthors += "ignored"
        app.env.prefs.putString(NsfwMode.PREFS_KEY, NsfwMode.HIDE.value)
        val feed = WavesFeedModel(app.env, WavesFeedKind.ForYou)
        feed.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("w1", "w6"), feed.permlinks())
        // the profile's list keeps a muted author's waves (you opened that profile on purpose)
        val author = WavesFeedModel(app.env, WavesFeedKind.Author("ignored"))
        author.start()
        testScheduler.advanceUntilIdle()
        assertTrue("w2" in author.permlinks())
        // a mute made now drops the author at once
        app.env.setAuthorMuted("fine", true)
        testScheduler.advanceUntilIdle()
        assertEquals(emptyList(), feed.permlinks())
        feed.close(); author.close()
    }

    @Test
    fun nsfwWavesAreMarkedUnderAlwaysWarn() = runTest {
        val backend = Backend()
        backend.feed = { "[" + row("adult", "w5").replace("""["waves"]""", """["waves","nsfw"]""") + "," + row("fine", "w1") + "]" }
        val app = app(backend)
        val feed = WavesFeedModel(app.env, WavesFeedKind.ForYou)
        feed.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(true, false), feed.current.items.map { it.card.isNsfw })
        feed.close()
    }

    @Test
    fun wavesTheApiFlagsAsExplicitShowOnlyUnderAlwaysShowOrToTheirAuthor() = runTest {
        val backend = Backend()
        // the per-author feed is where flagged waves still arrive (RN d9f64de0bc)
        backend.feed = {
            listOf(
                row(bob, "flagged", extra = ""","nsfw":true"""),
                row(bob, "odd-flag", extra = ""","nsfw":"true""""),
                row(bob, "clean", extra = ""","nsfw":false"""),
                row(bob, "unflagged"),
            ).joinToString(",", "[", "]")
        }
        backend.promoted = "[" + promotedEntry("spike-promo", "p-flagged").dropLast(1) + ""","nsfw":true}]"""
        val app = app(backend)
        suspend fun shown(kind: WavesFeedKind): List<String> {
            val feed = WavesFeedModel(app.env, kind)
            feed.start()
            testScheduler.advanceUntilIdle()
            return feed.permlinks().also { feed.close() }
        }
        // logged out, setting never stored ("always warn"): kept with its media covered (RN #3586), promoted too
        assertEquals(listOf("flagged", "odd-flag", "clean", "unflagged"), shown(WavesFeedKind.Author(bob)))
        assertTrue("p-flagged" in shown(WavesFeedKind.ForYou))
        run {
            val feed = WavesFeedModel(app.env, WavesFeedKind.Author(bob))
            feed.start()
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(true, false, false, false), feed.current.items.map { it.card.isNsfw }, "only the flagged wave is covered")
            feed.close()
        }
        // review L8: its thread screen keeps the cover, until revealed; nothing else is covered there
        val flaggedKey = com.ecency.core.api.model.PostKey(bob, "flagged")
        assertTrue(app.env.mutedReveals.coversWave(flaggedKey, NsfwMode.WARN))
        assertFalse(app.env.mutedReveals.coversWave(flaggedKey, NsfwMode.SHOW))
        assertFalse(app.env.mutedReveals.coversWave(com.ecency.core.api.model.PostKey(bob, "clean"), NsfwMode.WARN))
        app.env.mutedReveals.reveal(flaggedKey)
        assertFalse(app.env.mutedReveals.coversWave(flaggedKey, NsfwMode.WARN))
        app.env.prefs.putString(NsfwMode.PREFS_KEY, NsfwMode.HIDE.value)
        assertFalse("flagged" in shown(WavesFeedKind.Author(bob)))
        app.env.prefs.putString(NsfwMode.PREFS_KEY, NsfwMode.SHOW.value)
        assertEquals(listOf("flagged", "odd-flag", "clean", "unflagged"), shown(WavesFeedKind.Author(bob)))
        assertTrue("p-flagged" in shown(WavesFeedKind.ForYou))
        run {
            val feed = WavesFeedModel(app.env, WavesFeedKind.Author(bob))
            feed.start()
            testScheduler.advanceUntilIdle()
            assertTrue(feed.current.items.none { it.card.isNsfw }, "always show: no cover")
            feed.close()
        }
        // the author always sees their own wave
        app.env.prefs.putString(NsfwMode.PREFS_KEY, NsfwMode.HIDE.value)
        signIn(app, bob)
        assertTrue("flagged" in shown(WavesFeedKind.Author(bob)))
    }

    @Test
    fun waveNsfwRuleMatchesRn() {
        fun entry(author: String, flag: String?) = com.ecency.core.api.waves.WaveEntry.fromJson(
            Json.parseToJsonElement(row(author, "w", extra = flag?.let { ""","nsfw":$it""" }.orEmpty())),
        )!!
        val flagged = entry(bob, "true")
        // only a JSON true is a verdict
        for (odd in listOf(null, "false", "\"true\"", "1", "{}", "null")) {
            for (mode in NsfwMode.entries) assertFalse(WaveNsfw.isHidden(entry(bob, odd), mode, alice), "flag=$odd mode=$mode")
        }
        assertFalse(WaveNsfw.isHidden(flagged, NsfwMode.SHOW, alice))
        // "always warn" covers instead of hiding (RN #3586; RN hid it)
        assertFalse(WaveNsfw.isHidden(flagged, NsfwMode.WARN, alice))
        assertTrue(WaveNsfw.isHidden(flagged, NsfwMode.HIDE, alice))
        assertTrue(WaveNsfw.isHidden(flagged, NsfwMode.HIDE, null), "logged out")
        assertFalse(WaveNsfw.isHidden(flagged, NsfwMode.WARN, null), "logged out")
        assertFalse(WaveNsfw.isHidden(flagged, NsfwMode.HIDE, bob), "the viewer's own wave")
        assertFalse(WaveNsfw.isHidden(flagged, NsfwMode.WARN, bob))
    }

    @Test
    fun promotedWavesAreInterleavedAtRnsCadence() = runTest {
        assertEquals(
            listOf("o1", "o2", "p1", "o3", "o4", "o5", "o6", "p2", "o7"),
            WaveItems.interleave((1..7).map { PostKey("a", "o$it") }, listOf(PostKey("a", "p1"), PostKey("a", "p2"), PostKey("a", "p3")), { it }).map { it.permlink },
        )
        // an organic copy of a promoted wave is dropped, the promoted one kept
        assertEquals(
            listOf("o1", "o3", "o2"),
            WaveItems.interleave(listOf("o1", "o2", "o3").map { PostKey("a", it) }, listOf(PostKey("a", "o2")), { it }).map { it.permlink },
        )
        val backend = Backend()
        backend.feed = { (1..6).joinToString(",", "[", "]") { row("a$it", "w$it") } }
        backend.promoted = "[${promotedEntry("ad", "p1")},${promotedEntry("ignored", "p2")}]"
        val app = app(backend)
        app.env.mutedAuthors += "ignored"
        val feed = WavesFeedModel(app.env, WavesFeedKind.ForYou)
        feed.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("w1", "w2", "p1", "w3", "w4", "w5", "w6"), feed.permlinks())
        val ad = feed.current.items[2]
        assertTrue(ad.isPromoted && ad.card.isPromoted)
        assertEquals(60, ad.reputation)
        assertFalse(ad.isFromEcency)
        assertEquals(WavesGoldens.PROMOTED_URL, backend.sent.single { it.contains("promoted") })
        feed.close()
    }

    @Test
    fun aFailedFirstPageShowsAnErrorAndRetryLoads() = runTest {
        val backend = Backend()
        backend.failFeed = true
        val app = app(backend)
        val feed = WavesFeedModel(app.env, WavesFeedKind.ForYou)
        feed.start()
        testScheduler.advanceUntilIdle()
        assertNotNull(feed.current.error)
        assertFalse(feed.current.loaded)
        backend.failFeed = false
        backend.feed = { "[${row("a", "w1")}]" }
        feed.retry()
        testScheduler.advanceUntilIdle()
        assertNull(feed.current.error)
        assertEquals(listOf("w1"), feed.permlinks())
        feed.close()
    }

    @Test
    fun theListRefetchesEveryMinuteOnlyWhileActive() = runTest {
        val backend = Backend()
        backend.feed = { "[${row("a", "w1")}]" }
        val app = app(backend)
        val feed = WavesFeedModel(app.env, WavesFeedKind.ForYou)
        feed.start()
        testScheduler.advanceUntilIdle()
        testScheduler.advanceTimeBy(5 * WavesFeedModel.REFETCH_MILLIS)
        testScheduler.runCurrent()
        assertEquals(1, backend.feedCalls().size, "nothing polls while the list is off screen")
        feed.setActive(true)
        backend.feed = { "[${row("b", "w2")},${row("a", "w1").replace("\"net_votes\":2", "\"net_votes\":9")}]" }
        testScheduler.advanceTimeBy(WavesFeedModel.REFETCH_MILLIS + 1)
        testScheduler.runCurrent()
        assertEquals(listOf("w2", "w1"), feed.permlinks())
        assertEquals(9, feed.current.items[1].card.votes)
        feed.setActive(false)
        val calls = backend.feedCalls().size
        testScheduler.advanceTimeBy(3 * WavesFeedModel.REFETCH_MILLIS)
        testScheduler.runCurrent()
        assertEquals(calls, backend.feedCalls().size)
        feed.close()
    }

    @Test
    fun aVoteShowsOnTheWaveAtOnce() = runTest {
        val backend = Backend()
        backend.feed = { "[${row("a", "w1")},${row("b", "w2")}]" }
        val app = app(backend)
        signIn(app)
        val feed = WavesFeedModel(app.env, WavesFeedKind.ForYou)
        feed.start()
        testScheduler.advanceUntilIdle()
        val other = feed.current.items[1]
        app.env.votes.vote("a", "w1", 10000)
        testScheduler.advanceUntilIdle()
        assertTrue(feed.current.items[0].card.votedByMe)
        assertEquals(3, feed.current.items[0].card.votes)
        assertSame(other, feed.current.items[1], "only the voted wave is rebuilt")
        assertEquals(1, app.broadcasts().size)
        feed.close()
    }

    @Test
    fun wavesRememberTheirOwnVoteWeight() = runTest {
        val backend = Backend()
        backend.feed = { "[${row("a", "w1")}]" }
        val app = app(backend)
        assertEquals(100.0, app.env.waves.voteWeightPercent)
        assertFalse(app.env.waves.isWave(PostKey("a", "w1")))
        val feed = WavesFeedModel(app.env, WavesFeedKind.ForYou)
        feed.start()
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.waves.isWave(PostKey("a", "w1")))
        assertFalse(app.env.waves.isWave(PostKey("a", "a-blog-post")))
        app.env.waves.voteWeightPercent = 35.0
        assertEquals("35.0", app.env.prefs.getString(WavesService.VOTE_WEIGHT_KEY))
        app.env.waves.voteWeightPercent = 0.0
        app.env.waves.voteWeightPercent = 250.0
        assertEquals(35.0, app.env.waves.voteWeightPercent, "out of range weights are not kept")
        // the memory is bounded
        app.env.waves.markWaves((1..WavesService.KNOWN_WAVES + 5).map { PostKey("x", "p$it") })
        assertFalse(app.env.waves.isWave(PostKey("a", "w1")))
        assertTrue(app.env.waves.isWave(PostKey("x", "p${WavesService.KNOWN_WAVES + 5}")))
        feed.close()
    }

    @Test
    fun theProfileOwnsItsWavesList() = runTest {
        val backend = Backend()
        backend.feed = { "[${row(bob, "w1")}]" }
        val app = app(backend)
        val profile = app.env.profileModel(bob, "")
        val waves = profile.waves()
        assertSame(waves, profile.waves())
        assertEquals(WavesFeedKind.Author(bob), waves.kind)
        waves.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("w1"), waves.permlinks())
        assertEquals(0, backend.sent.count { it.contains("promoted") })
        profile.close()
        // closed with the profile: nothing it started keeps running
        backend.feed = { "[${row(bob, "w2")}]" }
        waves.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("w1"), waves.permlinks())
    }

    // ---- account binding ----

    @Test
    fun anAccountSwitchEmptiesTheListAndALateAnswerForThePreviousAccountNeverShows() = runTest {
        val backend = Backend()
        backend.feed = { url -> if ("following=$alice" in url) "[${row("alices-friend", "private-1")}]" else "[${row("bobs-friend", "b-1")}]" }
        val app = app(backend)
        signIn(app)
        val feed = WavesFeedModel(app.env, WavesFeedKind.Following)
        feed.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("private-1"), feed.permlinks())

        // a refresh for alice is still on its way when the account changes
        val gate = CompletableDeferred<Unit>()
        backend.gate = gate
        feed.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("private-1"), feed.permlinks())
        switchTo(app, bob)
        testScheduler.runCurrent()
        assertEquals(bob, app.env.session.current.username)
        assertEquals(emptyList(), feed.permlinks(), "nothing of the previous account stays on screen")
        assertTrue(feed.current.isLoading)
        gate.complete(Unit)
        backend.gate = null
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("b-1"), feed.permlinks())
        assertTrue(backend.feedCalls().last().endsWith("following=$bob&observer=$bob"))

        // signed out (both accounts): the following list needs an account and shows nothing
        app.env.session.signOut()
        app.env.session.signOut()
        testScheduler.advanceUntilIdle()
        assertNull(app.env.session.current.username)
        assertEquals(emptyList(), feed.permlinks())
        assertTrue(feed.current.needsLogin)
        feed.close()
    }

    @Test
    fun promotedWavesBuiltForThePreviousAccountNeverLandInTheNextAccountsList() = runTest {
        val backend = Backend()
        backend.feed = { (1..3).joinToString(",", "[", "]") { row("a$it", "w$it") } }
        backend.promoted = "[${promotedEntry("ad", "p-for-alice")}]"
        val held = CompletableDeferred<Unit>()
        backend.promotedGate = held
        val app = app(backend)
        signIn(app)
        val feed = WavesFeedModel(app.env, WavesFeedKind.ForYou)
        feed.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("w1", "w2", "w3"), feed.permlinks())
        // alice's promoted answer is still on its way; bob's own answer has nothing promoted
        backend.promoted = "[]"
        backend.promotedGate = null
        switchTo(app, bob)
        testScheduler.advanceUntilIdle()
        held.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("w1", "w2", "w3"), feed.permlinks())
        feed.close()
    }

    @Test
    fun aComposerBelongsToTheAccountThatOpenedIt() = runTest {
        val backend = Backend()
        val app = app(backend)
        signIn(app)
        val composer = app.env.waves.composer()
        composer.setText("Written by alice, must never go out as bob")
        assertSame(composer, app.env.waves.composer())
        switchTo(app, bob)
        testScheduler.advanceUntilIdle()
        // a sheet left open still holds the old model: it refuses
        composer.send()
        testScheduler.advanceUntilIdle()
        assertEquals(0, app.broadcasts().size)
        // and the new account gets an empty composer of its own
        val next = app.env.waves.composer()
        assertEquals(bob, next.owner)
        assertEquals("", next.current.text)
    }

    @Test
    fun aComposerWhoseOwnerIsNoLongerSignedInRefusesEvenWithoutASessionEvent() = runTest {
        val backend = Backend()
        val app = app(backend)
        signIn(app)
        // the model as a stale sheet would hold it, with the session moved on underneath
        val composer = WaveComposeModel(app.env, "someone-else")
        composer.setText("Hello from a stale sheet, long enough")
        composer.send()
        testScheduler.advanceUntilIdle()
        assertEquals(WaveComposeModel.ACCOUNT_CHANGED, composer.current.error?.message)
        assertEquals(0, app.broadcasts().size)
        val guest = WaveComposeModel(app.env, null)
        guest.setText("Hello")
        guest.send()
        testScheduler.advanceUntilIdle()
        assertEquals("Please sign in first.", guest.current.error?.message)
        assertEquals(0, app.broadcasts().size)
    }

    // ---- publishing ----

    private fun broadcastOps(app: TestApp) = app.broadcasts().last().params.jsonArray[0].jsonObject.getValue("operations").jsonArray

    @Test
    fun theClipboardChipPastesOnceAndStaysClosedForTheComposer() = runTest {
        val app = app(Backend())
        signIn(app)
        val composer = app.env.waves.composer()
        val sources = mutableListOf<WaveImageSource>()
        app.env.waves.attacher = object : WaveImageAttacher {
            override suspend fun attach(username: String, source: WaveImageSource): String? {
                sources += source
                return "https://images.ecency.com/DQmPaste/p.png"
            }
        }
        assertFalse(composer.current.clipboardChip)
        composer.setClipboardHasImage(true)
        assertTrue(composer.current.clipboardChip)
        composer.pasteClipboardImage()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(WaveImageSource.CLIPBOARD), sources, "the clipboard's image, through the editor's upload")
        assertEquals("https://images.ecency.com/DQmPaste/p.png", composer.current.imageUrl)
        assertFalse(composer.current.clipboardChip)
        // RN dismissedClipboardRef: once used or closed, a new check does not bring it back
        composer.setClipboardHasImage(true)
        assertFalse(composer.current.clipboardChip)
        val other = WaveComposeModel(app.env, alice)
        other.setClipboardHasImage(true)
        other.dismissClipboardChip()
        other.setClipboardHasImage(true)
        assertFalse(other.current.clipboardChip)
    }

    @Test
    fun aWaveOutOfResourceCreditsRaisesTheAppWideOfferAndKeepsTheText() = runTest {
        val app = app(Backend())
        signIn(app)
        rcShort = true
        val composer = app.env.waves.composer()
        composer.setText("Hello waves")
        composer.send()
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.rcOffer.current.open)
        assertNull(composer.current.error)
        assertFalse(composer.current.isSending)
        assertEquals("Hello waves", composer.current.text)
    }

    @Test
    fun publishingPostsUnderTheNewestContainerAndShowsTheWaveOnTop() = runTest {
        val backend = Backend()
        backend.feed = { "[${row("a", "w1")}]" }
        val app = app(backend)
        signIn(app)
        val feed = WavesFeedModel(app.env, WavesFeedKind.ForYou)
        val tagFeed = WavesFeedModel(app.env, WavesFeedKind.Tag("x"))
        feed.start(); tagFeed.start()
        testScheduler.advanceUntilIdle()
        val composer = app.env.waves.composer()
        val text = "Read https://ecency.com/hive-125125/@spike-carol/my-post #hello"
        withSession(app)
        composer.setText(text)
        composer.send()
        assertTrue(composer.current.isSending)
        testScheduler.advanceUntilIdle()

        // hive.flow has no container: the wave goes under ecency.waves' newest post
        assertEquals(listOf("hive.flow", "ecency.waves"), app.transport.sent.filter { it.method == "bridge.get_account_posts" }.map { it.params.jsonObject.getValue("account").jsonPrimitive.content })
        val op = broadcastOps(app).single().jsonArray
        assertEquals("comment", op[0].jsonPrimitive.content)
        assertEquals(listOf(110), app.reportTypes(), "a new wave is a reply: it reports a comment")
        val fields = op[1].jsonObject
        val permlink = WavePublish.permlink(alice, "ecency.waves", "waves-20261006", text)
        assertEquals("ecency.waves", fields.getValue("parent_author").jsonPrimitive.content)
        assertEquals("waves-20261006", fields.getValue("parent_permlink").jsonPrimitive.content)
        assertEquals(permlink, fields.getValue("permlink").jsonPrimitive.content)
        assertEquals(text, fields.getValue("body").jsonPrimitive.content)
        assertEquals(
            WavePublish.metadata(text, listOf("waves-20261006", "hive-125125", "hello"), mapOf("https://ecency.com/hive-125125/@spike-carol/my-post" to "Linked title")),
            fields.getValue("json_metadata").jsonPrimitive.content,
        )
        // the composer is done, the toast shows, the wave is on top of For you only
        assertEquals(PostKey(alice, permlink), composer.current.sent)
        assertEquals("", composer.current.text)
        assertEquals("Success!", app.env.ui.state.value.toast?.text)
        assertEquals(listOf(permlink, "w1"), feed.permlinks())
        assertTrue(feed.current.items[0].isLocal)
        tagFeed.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(emptyList(), tagFeed.permlinks().filter { it == permlink }, "a filtered list shows only what the feed returned")
        // once the feed returns it, the local copy is gone
        backend.feed = { "[${row(alice, permlink)},${row("a", "w1")}]" }
        feed.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(permlink, "w1"), feed.permlinks())
        assertFalse(feed.current.items[0].isLocal)
        // the next composer is a fresh one
        assertEquals("", app.env.waves.composer().current.text)
        feed.close(); tagFeed.close()
    }

    @Test
    fun theFirstHostWithAContainerWinsAndAFailingHostIsSkipped() = runTest {
        val app = app(Backend())
        signIn(app)
        containers = mapOf("hive.flow" to """{"author":"hive.flow","permlink":"flow-1","json_metadata":{}}""", "ecency.waves" to "fail")
        assertEquals("hive.flow", app.env.waves.latestContainer().author)
        containers = mapOf("hive.flow" to "fail", "ecency.waves" to """{"author":"ecency.waves","permlink":"waves-1","json_metadata":{}}""")
        assertEquals("ecency.waves", app.env.waves.latestContainer().author)
    }

    @Test
    fun onlyTheHostsOwnRootPostIsAContainer() = runTest {
        val app = app(Backend())
        signIn(app)
        val good = """{"author":"ecency.waves","permlink":"waves-1","json_metadata":{}}"""
        // a reblog row or a wrong node answer: someone else's post under the host's account
        containers = mapOf("hive.flow" to """{"author":"spike-mallory","permlink":"bait","json_metadata":{}}""", "ecency.waves" to good)
        assertEquals("ecency.waves", app.env.waves.latestContainer().author)
        // a comment is not a container
        containers = mapOf("hive.flow" to """{"author":"hive.flow","permlink":"re-x","depth":1,"parent_author":"x","json_metadata":{}}""", "ecency.waves" to good)
        assertEquals("ecency.waves", app.env.waves.latestContainer().author)
        // a permlink no chain would accept
        containers = mapOf("hive.flow" to """{"author":"hive.flow","permlink":"Bad Permlink/../x","json_metadata":{}}""", "ecency.waves" to good)
        assertEquals("ecency.waves", app.env.waves.latestContainer().author)
        // nothing qualifies: nothing is signed, the reason is shown, the draft stays
        containers = mapOf(
            "hive.flow" to """{"author":"spike-mallory","permlink":"bait","json_metadata":{}}""",
            "ecency.waves" to """{"author":"ecency.waves","permlink":"re-y","depth":1,"parent_author":"y","json_metadata":{}}""",
        )
        val composer = app.env.waves.composer()
        composer.setText("This must not become a reply to someone's post")
        composer.send()
        testScheduler.advanceUntilIdle()
        assertEquals(0, app.broadcasts().size)
        assertEquals(WavesService.NO_CONTAINER, composer.current.error?.message)
        assertEquals("Fail", composer.current.errorTitle)
        assertEquals("This must not become a reply to someone's post", composer.current.text)
    }

    @Test
    fun containerTagsAreReadAsRnDoes() = runTest {
        assertEquals(listOf("a", "b"), WavePublish.containerTags("""{"tags":["a","b"]}"""))
        assertEquals(emptyList(), WavePublish.containerTags("""{"tags":[]}"""), "an empty array is truthy in JS: no fallback")
        assertNull(WavePublish.containerTags("{}"))
        assertNull(WavePublish.containerTags("""{"tags":null}"""))
        assertNull(WavePublish.containerTags("""{"tags":""}"""))
        assertNull(WavePublish.containerTags("not json"))
        assertEquals(listOf("a", "b"), WavePublish.containerTags("""{"tags":"ab"}"""))
        assertEquals(listOf("ecency", "x"), WavePublish.tags(null, "#x"))
        assertEquals(listOf("x"), WavePublish.tags(emptyList(), "#x"))
        // through the composer: a container with `tags: []` gives a wave without "ecency"
        val app = app(Backend())
        signIn(app)
        containers = mapOf("ecency.waves" to """{"author":"ecency.waves","permlink":"waves-1","json_metadata":{"tags":[]}}""")
        val composer = app.env.waves.composer()
        composer.setText("No container tags here, only my own #solo tag")
        composer.send()
        testScheduler.advanceUntilIdle()
        val meta = broadcastOps(app).single().jsonArray[1].jsonObject.getValue("json_metadata").jsonPrimitive.content
        assertTrue(""""tags":["solo"]""" in meta, meta)
    }

    @Test
    fun aRetryAsksTheChainFirstAndNeverResendsCommentOptions() = runTest {
        val app = app(Backend())
        signIn(app)
        val text = "Clip of the day, long enough\n\nhttps://play.3speak.tv/embed?v=spike-voter/abcd1234"
        val composer = app.env.waves.composer()
        composer.setText(text)
        // the first broadcast "fails" for the app (the answer is lost) although the chain took it
        rejectBroadcast = true
        composer.send()
        testScheduler.advanceUntilIdle()
        assertNotNull(composer.current.error)
        assertEquals(listOf("comment", "comment_options"), broadcastOps(app).map { it.jsonArray[0].jsonPrimitive.content })
        assertEquals(0, app.transport.sent.count { it.method == "condenser_api.get_content" }, "a first send does not ask")
        val sentBefore = app.broadcasts().size
        // retry: the chain has this wave with this body, so it is published; nothing is sent again
        rejectBroadcast = false
        onChainBody = text
        composer.clearError()
        composer.send()
        testScheduler.advanceUntilIdle()
        assertEquals(sentBefore, app.broadcasts().size)
        assertNotNull(composer.current.sent)
        assertEquals("Success!", app.env.ui.state.value.toast?.text)
    }

    @Test
    fun aRetryOverADifferentBodyOnChainGoesOutWithoutCommentOptions() = runTest {
        val app = app(Backend())
        signIn(app)
        val composer = app.env.waves.composer()
        composer.setText("Clip, long enough to count\n\nhttps://play.3speak.tv/embed?v=spike-voter/abcd1234")
        rejectBroadcast = true
        composer.send()
        testScheduler.advanceUntilIdle()
        rejectBroadcast = false
        withSession(app)
        onChainBody = "something else already under this permlink"
        composer.clearError()
        composer.send()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("comment"), broadcastOps(app).map { it.jsonArray[0].jsonPrimitive.content })
        assertNotNull(composer.current.sent)
        assertTrue(app.reportTypes().isEmpty(), "an edit of the wave that is out earns nothing")
    }

    @Test
    fun deletingAJustPublishedWaveRemovesItsLocalCopyToo() = runTest {
        val backend = Backend()
        backend.feed = { "[${row("a", "w1")}]" }
        val app = app(backend)
        signIn(app)
        val feed = WavesFeedModel(app.env, WavesFeedKind.ForYou)
        feed.start()
        testScheduler.advanceUntilIdle()
        val composer = app.env.waves.composer()
        composer.setText("Published and then deleted right away")
        composer.send()
        testScheduler.advanceUntilIdle()
        val key = composer.current.sent!!
        assertEquals(listOf(key.permlink, "w1"), feed.permlinks())
        app.env.emit(AppEvent.PostDeleted(key))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("w1"), feed.permlinks())
        // and it does not come back with the next redisplay or refresh (the feed never indexed it)
        feed.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("w1"), feed.permlinks())
        assertEquals(emptyList(), app.env.waves.localItems(alice))
        feed.close()
    }

    @Test
    fun aShareTextNeverOverwritesADraftAndWaitsThroughLogin() = runTest {
        val app = app(Backend())
        // signed out: the text stays until someone is signed in
        app.env.waves.openLink("https://ecency.com/waves?text=Shared%20text")
        assertNull(app.env.waves.takeComposeText())
        assertEquals("Shared text", app.env.waves.pendingComposeText)
        signIn(app)
        assertEquals("Shared text", app.env.waves.pendingComposeText, "the login did not drop it")
        val composer = app.env.waves.composer()
        assertTrue(composer.prefill(app.env.waves.takeComposeText()!!))
        assertEquals("Shared text", composer.current.text)
        assertEquals(1, composer.current.prefillSeq)
        assertNull(app.env.waves.takeComposeText(), "once")
        // a second link while a draft is there: the draft wins, the editor is not told to change
        assertFalse(composer.prefill("Another shared text"))
        assertEquals("Shared text", composer.current.text)
        assertEquals(1, composer.current.prefillSeq)
        composer.setText("   ")
        assertTrue(composer.prefill("Now it fits"))
        assertEquals(2, composer.current.prefillSeq)
    }

    @Test
    fun withoutAContainerNothingIsBroadcastAndTheDraftStays() = runTest {
        val app = app(Backend())
        signIn(app)
        containers = mapOf("hive.flow" to "fail", "ecency.waves" to "fail")
        val composer = app.env.waves.composer()
        composer.setText("A wave that has nowhere to go yet")
        composer.send()
        testScheduler.advanceUntilIdle()
        assertEquals(0, app.broadcasts().size)
        assertEquals("Fail", composer.current.errorTitle)
        assertNotNull(composer.current.error)
        assertEquals("A wave that has nowhere to go yet", composer.current.text)
        assertFalse(composer.current.isSending)
    }

    @Test
    fun aFailedPublishKeepsTheDraftAndARetrySendsTheSameTransaction() = runTest {
        val backend = Backend()
        val app = app(backend)
        signIn(app)
        rejectBroadcast = true
        val composer = app.env.waves.composer()
        composer.setText("This one fails first, then goes through")
        composer.send()
        testScheduler.advanceUntilIdle()
        assertEquals("Something went wrong", composer.current.errorTitle)
        assertNotNull(composer.current.error)
        assertEquals("This one fails first, then goes through", composer.current.text)
        assertNull(composer.current.sent)
        assertNull(app.env.ui.state.value.toast)
        val first = broadcastOps(app).toString()
        // the container moved on meanwhile: the retry still sends the wave it built the first time
        containers = mapOf("ecency.waves" to """{"author":"ecency.waves","permlink":"waves-next-day","json_metadata":{}}""")
        rejectBroadcast = false
        composer.clearError()
        composer.send()
        testScheduler.advanceUntilIdle()
        assertEquals(first, broadcastOps(app).toString())
        assertNotNull(composer.current.sent)
    }

    @Test
    fun theComposerValidatesLikeRn() = runTest {
        val app = app(Backend())
        signIn(app)
        val composer = app.env.waves.composer()
        assertEquals("0/250", composer.current.counter)
        assertFalse(composer.current.showsShortHint, "an untouched composer does not nag")
        // an empty composer: RN's rule lets a tap through (and refuses it), the button shows it is off
        assertTrue(composer.current.canPublish)
        assertFalse(composer.current.publishEnabled, "nothing to publish: PUBLISH is off")
        composer.setText("   ")
        assertFalse(composer.current.publishEnabled, "only spaces is nothing")
        assertTrue(composer.current.copy(text = "", imageUrl = "https://images.example/a.png").publishEnabled, "an image alone")
        assertTrue(composer.current.copy(text = "", videoEmbedUrl = "https://play.3speak.tv/embed?v=a/b").publishEnabled, "a video alone")
        assertFalse(composer.current.copy(text = "", poll = com.ecency.core.editor.PollDraft()).publishEnabled, "a poll alone has no body: send would refuse it")
        assertFalse(composer.current.copy(text = "Hello", isSending = true).publishEnabled, "and never while RN's rule says no")
        composer.setText("Thank you")
        assertTrue(composer.current.publishEnabled)
        assertTrue(composer.current.showsShortHint)
        composer.setText("x".repeat(250))
        assertTrue(composer.current.canPublish)
        assertFalse(composer.current.showsShortHint)
        composer.setText("x".repeat(251))
        assertEquals("251/250", composer.current.counter)
        assertTrue(composer.current.lengthExceeded)
        assertFalse(composer.current.canPublish)
        composer.send()
        testScheduler.advanceUntilIdle()
        assertEquals(0, app.broadcasts().size)
        // nothing but spaces is not a wave
        composer.setText("   \n ")
        composer.send()
        testScheduler.advanceUntilIdle()
        assertEquals(0, app.broadcasts().size)
        assertEquals("Write something first.", composer.current.error?.message)
    }

    @Test
    fun anImageIsAttachedThroughThePlatformSeam() = runTest {
        val app = app(Backend())
        signIn(app)
        val composer = app.env.waves.composer()
        // no uploader in this build: a notice, nothing attached
        composer.attach(WaveImageSource.GALLERY)
        assertEquals(WaveComposeModel.NO_UPLOAD, composer.current.error?.message)
        composer.clearError()
        val asked = mutableListOf<Pair<String, WaveImageSource>>()
        val upload = CompletableDeferred<String?>()
        app.env.waves.attacher = object : WaveImageAttacher {
            override suspend fun attach(username: String, source: WaveImageSource): String? {
                asked += username to source
                return upload.await()
            }
        }
        app.env.waves.imageRatios = WaveImageRatios { 1.5 }
        composer.attach(WaveImageSource.CAMERA)
        testScheduler.advanceUntilIdle()
        assertTrue(composer.current.isAttaching)
        assertFalse(composer.current.canPublish, "PUBLISH waits for the upload")
        upload.complete("https://images.ecency.com/DQmTest/photo.jpg")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(alice to WaveImageSource.CAMERA), asked)
        assertEquals("https://images.ecency.com/DQmTest/photo.jpg", composer.current.imageUrl)
        composer.setText("Look at this")
        composer.send()
        testScheduler.advanceUntilIdle()
        val fields = broadcastOps(app).single().jsonArray[1].jsonObject
        assertEquals("Look at this\n\n ![](https://images.ecency.com/DQmTest/photo.jpg)", fields.getValue("body").jsonPrimitive.content)
        assertTrue(""""image_ratios":[1.5]""" in fields.getValue("json_metadata").jsonPrimitive.content)
        // removing the image
        val again = app.env.waves.composer()
        app.env.waves.attacher = object : WaveImageAttacher {
            override suspend fun attach(username: String, source: WaveImageSource): String? = "https://images.ecency.com/x.png"
        }
        again.attach(WaveImageSource.GALLERY)
        testScheduler.advanceUntilIdle()
        again.removeImage()
        assertNull(again.current.imageUrl)
    }

    @Test
    fun aShareLinksTextWaitsForTheComposerOnce() = runTest {
        val app = app(Backend())
        val events = mutableListOf<AppEvent>()
        val watch = app.env.watchEvents { events += it }
        testScheduler.advanceUntilIdle()
        app.env.waves.requestCompose("Shared text")
        testScheduler.advanceUntilIdle()
        assertTrue(AppEvent.WaveComposeRequested in events)
        signIn(app)
        assertEquals("Shared text", app.env.waves.takeComposeText())
        assertNull(app.env.waves.takeComposeText())
        watch.close()
    }

    @Test
    fun wavesLinksOpenTheTabTheThreadOrTheComposer() = runTest {
        val app = app(Backend())
        assertEquals(NavEntry.tab(AppTab.WAVES), ContentLinks.entry("https://ecency.com/waves"))
        assertEquals(NavEntry.tab(AppTab.WAVES), ContentLinks.entry("https://ecency.com/waves?text=Hello"))
        assertEquals(NavEntry.screen(AppScreen.Post("spike-alice", "wave-1")), ContentLinks.entry("https://ecency.com/waves/spike-alice/wave-1"))
        assertNull(ContentLinks.entry("https://ecency.com/wavesx"))
        assertNull(ContentLinks.entry("https://ecency.com/waves/only-one-part"))
        // only a link with text asks for the composer
        app.env.waves.openLink("https://ecency.com/waves")
        assertNull(app.env.waves.pendingComposeText)
        app.env.waves.openLink("https://ecency.com/@spike-alice/a-post?text=nope")
        assertNull(app.env.waves.pendingComposeText)
        app.env.waves.openLink("https://ecency.com/waves?text=Hello%20there")
        assertEquals("Hello there", app.env.waves.pendingComposeText)
        // a wave is recognised by any container host, hive.flow included (no parent box on its thread)
        assertTrue(com.ecency.core.api.waves.Waves.isWavesHost("hive.flow") && com.ecency.core.api.waves.Waves.isWavesHost("peak.snaps"))
    }

    // ---- tabs ----

    @Test
    fun tabsFollowThePinsAndThePinsPersist() = runTest {
        val backend = Backend()
        backend.trending = """[{"tag":"Hive","posts":9},{"tag":"art","posts":3},{"tag":"#!","posts":1}]"""
        val app = app(backend)
        val tabs = app.env.wavesTabsModel()
        assertEquals(listOf("for-you", "following"), tabs.current.tabs.map { it.key })
        assertEquals(WavesTabTap.NEEDS_LOGIN, tabs.select(WavesTab.FOLLOWING))
        assertEquals(WavesTab.FOR_YOU, tabs.current.selected)
        assertEquals(WavesTabTap.SCROLL_TO_TOP, tabs.select(WavesTab.FOR_YOU))

        tabs.addTag("  #Art! ")
        tabs.addTag("art")
        tabs.addTag("###")
        tabs.toggleContainer("leothreads")
        tabs.toggleContainer("shorts")
        tabs.toggleContainer("not-a-source")
        assertEquals(listOf("For you", "Following", "Threads", "Shorts", "#art"), tabs.current.tabs.map { it.title })
        assertEquals("art", app.env.prefs.getString(WavesTabsModel.TAGS_KEY))
        assertEquals("leothreads,shorts", app.env.prefs.getString(WavesTabsModel.CONTAINERS_KEY))
        tabs.loadTrending()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("hive"), tabs.current.trendingTags)
        assertEquals(WavesGoldens.TRENDING_TAGS_URL, backend.sent.single { it.contains("trending") })

        assertEquals(WavesTabTap.SELECTED, tabs.select("tag:art"))
        assertEquals(WavesFeedKind.Tag("art"), tabs.feed(tabs.current.feedKey)?.kind)
        assertEquals(WavesFeedKind.Container("leothreads"), tabs.feed("container:leothreads")?.kind)
        assertNull(tabs.feed(WavesTab.SHORTS_KEY))
        assertNull(tabs.feed("container:evil.host"))
        tabs.select(WavesTab.SHORTS_KEY)
        assertTrue(tabs.current.showsShorts)
        assertFalse(tabs.current.showsCompose)
        // unpinning the active tab falls back to For you
        tabs.toggleContainer("shorts")
        assertEquals(WavesTab.FOR_YOU, tabs.current.selected)
        tabs.close()
        // a new screen reads the pins back
        assertEquals(listOf("For you", "Following", "Threads", "#art"), app.env.wavesTabsModel().current.tabs.map { it.title })
    }

    @Test
    fun aTappedHashtagFiltersForYouUntilCleared() = runTest {
        val backend = Backend()
        val app = app(backend)
        signIn(app)
        val tabs = app.env.wavesTabsModel()
        tabs.addTag("art")
        tabs.select(WavesTab.FOLLOWING)
        tabs.filterByTag("#hive")
        assertEquals(WavesTab.FOR_YOU, tabs.current.selected)
        assertEquals("hive", tabs.current.activeTag)
        assertEquals("tag", tabs.current.feedKey)
        assertEquals(WavesFeedKind.Tag("hive"), tabs.feed("tag")?.kind)
        // tapping For you again keeps the filter (RN), a real tab clears it
        assertEquals(WavesTabTap.SELECTED, tabs.select(WavesTab.FOR_YOU))
        assertEquals("hive", tabs.current.activeTag)
        tabs.select("tag:art")
        assertNull(tabs.current.activeTag)
        tabs.filterByTag("hive")
        tabs.clearTag()
        assertEquals(WavesTab.FOR_YOU, tabs.current.feedKey)
        // signing out leaves the Following tab
        tabs.select(WavesTab.FOLLOWING)
        assertEquals(WavesTab.FOLLOWING, tabs.current.selected)
        app.env.session.signOut()
        testScheduler.advanceUntilIdle()
        assertEquals(WavesTab.FOR_YOU, tabs.current.selected)
        tabs.close()
    }

    // ---- shorts ----

    @Test
    fun captionsMatchRn() {
        WavesGoldens.captions.forEach { assertEquals(it.caption, ShortCaption.of(it.title, it.body), it.body.take(40)) }
    }

    @Test
    fun onlyTheReelInViewPlaysAndOnlyWhileFocused() = runTest {
        val backend = Backend()
        backend.shorts = { "[${short("a", "s1")},${short("b", "s2")},${short("c", "novideo", video = false)},${short("ignored", "s3")}]" }
        val app = app(backend)
        app.env.mutedAuthors += "ignored"
        val shorts = ShortsModel(app.env)
        shorts.start()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("s1", "s2"), shorts.current.items.map { it.key.permlink })
        assertEquals("Clip s1", shorts.current.items[0].caption)
        assertEquals("https://i.ecency.com/x/thumbnail.jpg", shorts.current.items[0].thumbnailUrl)
        assertEquals(WavesGoldens.shortsUrls[0].url, backend.sent.single { it.contains("/waves/shorts") })

        val s1 = PostKey("a", "s1")
        val s2 = PostKey("b", "s2")
        shorts.setActive(s1)
        testScheduler.advanceUntilIdle()
        assertNull(shorts.current.playingKey, "nothing plays until the tab is on screen")
        assertEquals(0, backend.sent.count { it.contains("api/embed") }, "and nothing is resolved either")
        shorts.setFocused(true)
        testScheduler.runCurrent()
        assertEquals(s1, shorts.current.playingKey)
        assertEquals(ShortPlayback.Stream("https://play.3speak.tv/hls?u=one"), shorts.current.playbackOf(s1))
        assertEquals(ShortPlayback.Loading, shorts.current.playbackOf(s2))
        assertEquals("https://play.3speak.tv/api/embed?v=a/v-s1", backend.sent.single { it.contains("api/embed") })

        // the next reel: the stream cannot be resolved, RN falls back to the web player
        backend.stream = null
        shorts.setActive(s2)
        testScheduler.runCurrent()
        assertEquals(s2, shorts.current.playingKey)
        assertEquals(ShortPlayback.Web("https://play.3speak.tv/watch?v=b/v-s2&mode=iframe&autoplay=true"), shorts.current.playbackOf(s2))
        // a stream that fails in the player falls back too
        shorts.playbackFailed(s1)
        assertTrue(shorts.current.playbackOf(s1) is ShortPlayback.Web)

        // another tab, the background or the lock: nothing plays
        shorts.setFocused(false)
        assertNull(shorts.current.playingKey)
        shorts.toggleMute()
        assertTrue(shorts.current.muted)
        // an unknown key is never active
        shorts.setActive(PostKey("x", "y"))
        assertEquals(s2, shorts.current.activeKey)
        shorts.close()
    }

    @Test
    fun anUnsafeStreamIsNeverHandedToThePlayer() = runTest {
        val backend = Backend()
        backend.shorts = { "[${short("a", "s1")}]" }
        backend.stream = """{"videoUrl":"http://insecure.example/x.m3u8","videoUrlFallback1":"https://play.3speak.tv/hls?u=two"}"""
        val app = app(backend)
        val shorts = ShortsModel(app.env)
        shorts.start()
        shorts.setFocused(true)
        testScheduler.runCurrent()
        shorts.setActive(PostKey("a", "s1"))
        testScheduler.runCurrent()
        // RN takes the first non-empty URL; an http one is refused here and the web player is used
        assertTrue(shorts.current.playbackOf(PostKey("a", "s1")) is ShortPlayback.Web)
        shorts.setFocused(false)
        shorts.close()
    }

    @Test
    fun theReelsStartOverForAnotherAccount() = runTest {
        val backend = Backend()
        backend.shorts = { url -> if ("observer=$alice" in url) "[${short("a", "s1")}]" else "[${short("b", "s2")}]" }
        val app = app(backend)
        signIn(app)
        val shorts = ShortsModel(app.env)
        shorts.start()
        testScheduler.advanceUntilIdle()
        shorts.setActive(PostKey("a", "s1"))
        val seen = mutableListOf<List<String>>()
        val watch = shorts.watch { s -> seen += s.items.map { it.key.permlink } }
        switchTo(app, bob)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("s2"), shorts.current.items.map { it.key.permlink })
        assertNull(shorts.current.activeKey)
        // alice's reel left the screen before bob's arrived, and never came back
        assertEquals(listOf(listOf("s1"), emptyList(), listOf("s2")), seen.distinct())
        watch.close()
        shorts.close()
    }
}
