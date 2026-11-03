package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Fixtures.array
import com.ecency.core.api.Fixtures.entry
import com.ecency.core.api.RankedSort
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Back in front after a while (README "Foreground refresh"): the staleness rule of [Freshness], and the screens'
 * models refreshing quietly when shown after a long trip away, never after a short one.
 */
class ForegroundRefreshTest {
    private val long = Freshness.STALE_AFTER_MILLIS

    /** The app leaves the foreground for [millis] and comes back (both apps report it to the app lock). */
    private fun TestApp.away(millis: Long) {
        env.appLock.onBackground()
        monotonic += millis
        env.appLock.onForeground()
    }

    // ---- the rule ----

    @Test
    fun onlyATripOfAtLeastTheThresholdMakesDataStale() = runTest {
        val app = TestApp(this) { HttpResult(500, "") }
        val freshness = app.env.freshness
        app.monotonic = 5_000
        val loaded = freshness.stamp()
        app.away(long - 1)
        assertFalse(freshness.isStale(loaded), "a short trip keeps the data")
        app.away(long - 1)
        assertFalse(freshness.isStale(loaded), "short trips do not add up")
        app.env.appLock.onBackground()
        app.monotonic += long
        assertTrue(freshness.isStale(loaded), "a screen told of the return before the lock already sees it")
        app.env.appLock.onForeground()
        assertTrue(freshness.isStale(loaded))
        assertEquals(10 * 60_000L, long)
        val reloaded = freshness.stamp()
        assertFalse(freshness.isStale(reloaded), "data loaded after the return is fresh")
        app.away(long - 1)
        assertFalse(freshness.isStale(reloaded))
    }

    // ---- feeds ----

    /** A ranked feed whose pages are "[round]-0..9" then "[round]-10..19", then nothing. */
    private class Feed(scope: TestScope) {
        var round = "a"
        var down = false
        var calls = 0
        val app = TestApp(scope) { req ->
            if (req.method != "bridge.get_ranked_posts") return@TestApp HttpResult(500, "no fixture")
            calls++
            if (down) return@TestApp HttpResult(503, "down")
            val cursor = req.params.jsonObject["start_permlink"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val range = when {
                cursor.isEmpty() -> 0 until 10
                cursor.endsWith("-9") -> 10 until 20
                else -> IntRange.EMPTY
            }
            FakeTransport.result(req, array(*range.map { entry("author$it", "$round-$it") }.toTypedArray()))
        }
    }

    @Test
    fun aFeedShownAfterALongTripRefreshesQuietlyAndKeepsItsPages() = runTest {
        val f = Feed(this)
        val feed = f.app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        feed.start()
        testScheduler.advanceUntilIdle()
        feed.loadMore()
        testScheduler.advanceUntilIdle()
        assertEquals(20, feed.current.items.size)
        assertEquals(2, f.calls)

        f.app.away(long - 1)
        feed.start()
        testScheduler.advanceUntilIdle()
        assertEquals(2, f.calls, "a short trip does not refetch")

        f.round = "b"
        f.app.away(long)
        val seen = mutableListOf<FeedState>()
        val watch = feed.watch { seen += it }
        feed.start()
        testScheduler.advanceUntilIdle()
        assertEquals(4, f.calls, "both loaded pages are read again (RN refetches every page of the infinite query)")
        assertEquals((0 until 20).map { "b-$it" }, feed.current.items.map { it.key.permlink })
        assertEquals(2, feed.current.pages)
        assertTrue(seen.none { it.isRefreshing || it.isLoading || it.items.isEmpty() }, "no spinner, no skeleton: $seen")
        watch.close()

        feed.start()
        testScheduler.advanceUntilIdle()
        assertEquals(4, f.calls, "refreshed once per return")
        feed.close()
    }

    @Test
    fun aFeedNotShownStaysStaleUntilItIsShown() = runTest {
        val f = Feed(this)
        val shown = f.app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        val hidden = f.app.env.feedModel(FeedSource.Ranked(RankedSort.HOT))
        shown.start()
        hidden.start()
        testScheduler.advanceUntilIdle()
        assertEquals(2, f.calls)
        f.round = "b"
        f.app.away(long)
        shown.start()
        testScheduler.advanceUntilIdle()
        assertEquals(3, f.calls, "only the feed on screen refreshes")
        assertEquals("a-0", hidden.current.items.first().key.permlink)
        hidden.start()
        testScheduler.advanceUntilIdle()
        assertEquals(4, f.calls)
        assertEquals("b-0", hidden.current.items.first().key.permlink)
        shown.close()
        hidden.close()
    }

    @Test
    fun aFailedQuietRefreshKeepsTheListWithoutAnErrorAndTriesAgainOnTheNextShow() = runTest {
        val f = Feed(this)
        val feed = f.app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        feed.start()
        testScheduler.advanceUntilIdle()
        f.round = "b"
        f.down = true
        f.app.away(long)
        feed.start()
        testScheduler.advanceUntilIdle()
        assertEquals("a-0", feed.current.items.first().key.permlink)
        assertNull(feed.current.error, "a background refresh that failed says nothing")
        assertTrue(feed.current.loaded)
        f.down = false
        feed.start()
        testScheduler.advanceUntilIdle()
        assertEquals("b-0", feed.current.items.first().key.permlink)
        feed.close()
    }

    /**
     * A ranked feed whose first page is all by a muted author ("[round]-s0..9"), then "[round]-0..9", then
     * "[round]-10..19", then nothing; with [allMuted] every page is by the muted author, for 100 pages.
     */
    private class FilteredFeed(scope: TestScope) {
        var round = "a"
        var allMuted = false
        var calls = 0
        val app = TestApp(scope) { req ->
            if (req.method != "bridge.get_ranked_posts") return@TestApp HttpResult(500, "no fixture")
            calls++
            val cursor = req.params.jsonObject["start_permlink"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val posts = when {
                allMuted -> if (calls % 100 == 0) emptyList() else (0 until 10).map { entry("spammer", "$round-m$calls-$it") }
                cursor.isEmpty() -> (0 until 10).map { entry("spammer", "$round-s$it") }
                cursor.endsWith("-s9") -> (0 until 10).map { entry("author$it", "$round-$it") }
                cursor == "$round-9" -> (10 until 20).map { entry("author$it", "$round-$it") }
                else -> emptyList()
            }
            FakeTransport.result(req, array(*posts.toTypedArray()))
        }.also { it.env.setAuthorMuted("spammer", true) }
    }

    /** Review of #3617: the pages the first load skipped count, so the quiet refresh reads them all again. */
    @Test
    fun aQuietRefreshAfterSkippedPagesKeepsEveryVisibleCard() = runTest {
        val f = FilteredFeed(this)
        val feed = f.app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        feed.start()
        testScheduler.advanceUntilIdle()
        feed.loadMore()
        testScheduler.advanceUntilIdle()
        assertEquals(20, feed.current.items.size)
        assertEquals(3, f.calls)
        assertEquals(3, feed.current.pages, "the skipped page counts")

        f.round = "b"
        f.app.away(long)
        feed.start()
        testScheduler.advanceUntilIdle()
        assertEquals(6, f.calls, "all three pages are read again")
        assertEquals((0 until 20).map { "b-$it" }, feed.current.items.map { it.key.permlink })
        feed.close()
    }

    /** Review of #3617: a feed with nothing to show reads at most the first load's cap again, not its whole history. */
    @Test
    fun aQuietRefreshOfAFeedFilteredAwayIsBounded() = runTest {
        val f = FilteredFeed(this)
        f.allMuted = true
        val feed = f.app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        feed.start()
        testScheduler.advanceUntilIdle()
        assertEquals(6, f.calls, "the first load stops after the page asked for and 5 more")
        assertTrue(feed.current.loaded && feed.current.items.isEmpty() && !feed.current.endReached)

        f.round = "b"
        f.app.away(long)
        feed.start()
        testScheduler.advanceUntilIdle()
        assertEquals(12, f.calls, "the refresh reads as many pages as were loaded, not the whole history")
        assertTrue(feed.current.loaded && feed.current.items.isEmpty() && !feed.current.endReached)
        assertEquals(6, feed.current.pages)
        feed.close()
    }

    // ---- wallet ----

    private fun WalletWorld.portfolioReads() = app.authBackend.sent.count { it.url.endsWith("portfolio-v2") }

    @Test
    fun theWalletOnScreenRefreshesQuietlyOnALongReturnOnly() = runTest {
        val w = WalletWorld(this)
        val wallet = w.env.wallet
        // the wallet polls while shown: closed whatever happens, or the test never goes idle
        try {
        wallet.start()
        wallet.visible(true)
        testScheduler.runCurrent()
        assertTrue(wallet.current.loaded)
        val reads = w.portfolioReads()
        val rows = wallet.current.rows.size
        assertTrue(rows > 0)

        w.app.away(long - 1)
        testScheduler.runCurrent()
        assertEquals(reads, w.portfolioReads(), "a short trip does not refetch")

        w.app.now += long
        val seen = mutableListOf<WalletState>()
        val watch = wallet.watch { seen += it }
        w.app.away(long)
        testScheduler.runCurrent()
        assertEquals(reads + 1, w.portfolioReads())
        assertEquals(w.app.now, wallet.current.updatedAtMillis)
        assertTrue(seen.none { it.isRefreshing || it.isLoading }, "no spinner: $seen")
        assertEquals(rows, wallet.current.rows.size, "the rows stayed")
        watch.close()
        } finally {
            wallet.close()
        }
    }

    @Test
    fun theWalletOffScreenRefreshesWhenItIsShownAgain() = runTest {
        val w = WalletWorld(this)
        val wallet = w.env.wallet
        try {
        wallet.start()
        testScheduler.runCurrent()
        val reads = w.portfolioReads()
        w.app.away(long)
        testScheduler.runCurrent()
        assertEquals(reads, w.portfolioReads(), "nothing shows the wallet: nothing is read")
        wallet.visible(true)
        testScheduler.runCurrent()
        assertEquals(reads + 1, w.portfolioReads())
        assertFalse(wallet.current.isRefreshing)
        wallet.visible(false)
        wallet.visible(true)
        testScheduler.runCurrent()
        assertEquals(reads + 1, w.portfolioReads(), "shown again without a trip away: no read")
        } finally {
            wallet.close()
        }
    }

    // ---- notifications ----

    @Test
    fun loadedNotificationsReloadQuietlyOnALongReturnKeepingTheirPages() = runTest {
        val f = NotificationFixture(this)
        val alice = NotificationFixture.ALICE
        f.backend.rows[alice] = (1..7).map { FakeNotificationsBackend.vote("n$it") }
        f.start()
        f.model.show()
        f.idle()
        f.model.loadMore()
        f.idle()
        assertEquals(6, f.model.current.items.size)
        val lists = f.backend.of("notifications").size

        f.app.away(long - 1)
        f.model.onForeground()
        f.idle()
        assertEquals(lists, f.backend.of("notifications").size, "a short trip only reads the count")

        f.backend.rows[alice] = (0..7).map { FakeNotificationsBackend.vote("n$it") }
        val seen = mutableListOf<NotificationsState>()
        val watch = f.model.watch { seen += it }
        f.app.away(long)
        f.model.onForeground()
        f.idle()
        assertEquals(listOf("n0", "n1", "n2", "n3", "n4", "n5"), f.model.current.items.map { it.id })
        assertEquals(2, f.model.current.pages)
        assertTrue(seen.none { it.isRefreshing || it.isLoading }, "no spinner: $seen")
        watch.close()
    }

    // ---- profile ----

    @Test
    fun aProfileShownAfterALongTripReadsTheAccountAgainWithoutASpinner() = runTest {
        var followers = 1234
        val app = TestApp(this, handler = ProfileFixtures.chain(rpc = { req ->
            if (req.method == "condenser_api.get_follow_count") FakeTransport.result(req, """{"account":"alice","follower_count":$followers,"following_count":56}""") else null
        }))
        app.authBackend.privateApi = ProfileFixtures.ecency()
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        val profile = app.env.profileModel("alice", "")
        profile.start()
        testScheduler.advanceUntilIdle()
        assertEquals(1234, assertNotNull(profile.current.header).followers)
        val reads = app.transport.sent.count { it.method == "condenser_api.get_follow_count" }

        followers = 1300
        app.away(long - 1)
        profile.start()
        testScheduler.advanceUntilIdle()
        assertEquals(reads, app.transport.sent.count { it.method == "condenser_api.get_follow_count" })

        val seen = mutableListOf<ProfileState>()
        val watch = profile.watch { seen += it }
        app.away(long)
        profile.start()
        testScheduler.advanceUntilIdle()
        assertEquals(1300, assertNotNull(profile.current.header).followers)
        assertTrue(seen.none { it.isLoading || it.header == null }, "the header stays: $seen")
        watch.close()
        profile.close()
    }
}
