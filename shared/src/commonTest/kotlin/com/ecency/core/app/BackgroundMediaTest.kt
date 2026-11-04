package com.ecency.core.app

import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostMetadata
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackgroundMediaTest {
    private val proxy = "https://i.ecency.com"
    private val image = "https://i.ecency.com/p/cover.jpg"

    private fun post(title: String = "A walk in the hills", tags: List<String> = listOf("travel")) =
        Post(author = "alice", permlink = "walk", title = title, metadata = PostMetadata(tags = tags))

    @Test
    fun theCardShowsTitleAuthorAndThePostImage() {
        val card = NowPlaying.card(post(), "alice", lockEnabled = false, image = image, proxyBase = proxy)
        assertEquals(NowPlayingCard("A walk in the hills", "@alice", "Ecency", image), card)
    }

    @Test
    fun withThePinLockOnTheCardShowsOnlyTitleAndAuthor() {
        val card = NowPlaying.card(post(), "alice", lockEnabled = true, image = image, proxyBase = proxy)
        assertEquals("A walk in the hills", card.title)
        assertEquals("@alice", card.artist)
        assertNull(card.artworkUrl, "no image from the post on a locked phone")
    }

    @Test
    fun anNsfwPostOrOneWithoutAnImageShowsTheAuthorsAvatar() {
        val nsfw = NowPlaying.card(post(tags = listOf("nsfw")), "alice", lockEnabled = false, image = image, proxyBase = proxy)
        assertEquals("$proxy/u/alice/avatar/256", nsfw.artworkUrl)
        val none = NowPlaying.card(post(), "alice", lockEnabled = false, image = null, proxyBase = proxy)
        assertEquals("$proxy/u/alice/avatar/256", none.artworkUrl)
        val plain = NowPlaying.card(post(), "alice", lockEnabled = false, image = "http://example.com/a.jpg", proxyBase = proxy)
        assertEquals("$proxy/u/alice/avatar/256", plain.artworkUrl, "only https artwork")
    }

    @Test
    fun theTitleIsOneCleanCappedLine() {
        assertEquals("Line one line two", NowPlaying.clean("Line one\n\tline‮ two\u0007"))
        val long = NowPlaying.clean("x".repeat(500))
        assertEquals(NowPlaying.TITLE_MAX, long.length)
        assertTrue(long.endsWith("…"))
        val untitled = NowPlaying.card(post(title = " \n "), "alice", lockEnabled = false, image = null, proxyBase = proxy)
        assertEquals("Post by @alice", untitled.title)
        assertEquals("@bob", NowPlaying.card(null, "bob", lockEnabled = false, image = null, proxyBase = proxy).artist)
    }

    @Test
    fun skipsStayInsideTheTrack() {
        assertEquals(25.0, NowPlaying.skip(10.0, NowPlaying.SKIP_SECONDS, 100.0))
        assertEquals(0.0, NowPlaying.skip(10.0, -NowPlaying.SKIP_SECONDS, 100.0))
        assertEquals(100.0, NowPlaying.skip(95.0, NowPlaying.SKIP_SECONDS, 100.0))
        assertEquals(110.0, NowPlaying.skip(95.0, NowPlaying.SKIP_SECONDS, Double.NaN), "length unknown: no upper bound")
        assertEquals(15.0, NowPlaying.skip(Double.NaN, NowPlaying.SKIP_SECONDS, null))
    }

    @Test
    fun stopAllRunsEverySessionOnceEvenWhenOneThrows() {
        val sessions = BackgroundSessions()
        val stopped = mutableListOf<String>()
        sessions.register { stopped += "a"; error("boom") }
        val b = sessions.register { stopped += "b" }
        sessions.register { stopped += "c" }
        b.close()
        assertEquals(2, sessions.count)
        sessions.stopAll()
        assertEquals(listOf("a", "c"), stopped)
        sessions.stopAll()
        assertEquals(listOf("a", "c"), stopped, "each runs once")
        assertEquals(0, sessions.count)
    }

    @Test
    fun logoutAndWipeStopTheBackgroundSessionsAnotherAccountLeavingDoesNot() = runTest {
        val app = TestApp(this) { TestApp.chain(it) ?: HttpResult(500, "") }
        listOf("spike-alice", "spike-bob").forEach { name ->
            KeyVault(app.store).save(VaultAccount(name, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-$name"))
        }
        KeyVault(app.store).setCurrentUsername("spike-alice")
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        val stopped = mutableListOf<String>()
        app.env.backgroundSessions.register { stopped += "listen" }
        app.env.session.logout("spike-bob")
        testScheduler.advanceUntilIdle()
        assertTrue(stopped.isEmpty(), "the current reader's audio goes on")
        app.env.session.logout("spike-alice")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("listen"), stopped)
        app.env.backgroundSessions.register { stopped += "again" }
        app.env.resetAfterWipe()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("listen", "again"), stopped)
    }

    @Test
    fun theEnvironmentCardFollowsTheLockSetting() = runTest {
        val app = TestApp(this) { TestApp.chain(it) ?: HttpResult(500, "") }
        val model = app.env.listenModel("alice", "walk")
        val card = app.env.nowPlaying(model)
        assertEquals("@alice", card.artist)
        assertEquals("Post by @alice", card.title, "before the post is in")
        model.close()
    }
}
