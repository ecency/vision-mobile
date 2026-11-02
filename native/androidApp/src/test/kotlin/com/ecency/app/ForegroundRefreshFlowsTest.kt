package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.FeedActions
import com.ecency.app.ui.FeedList
import com.ecency.app.ui.Navigator
import com.ecency.core.api.RankedSort
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.app.AppTab
import com.ecency.core.app.Freshness
import com.ecency.core.app.NavState
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * README "Foreground refresh" on Android: the activity stops and starts again (the app went to the background and
 * came back); after a long trip the feed on screen and the wallet read again quietly, after a short one nothing does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ForegroundRefreshFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    @Volatile private var monotonic = 0L
    private val env by lazy { node.env { monotonic } }
    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
    }

    /** The newest posts first: [fresh] on top of "old-1".."old-30". */
    @Volatile private var fresh = emptyList<String>()

    @Before
    fun setUp() {
        acceptTerms()
        SingletonImageLoader.setUnsafe { ctx ->
            ImageLoader.Builder(ctx).components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                        ErrorResult(null, chain.request, IllegalStateException("no network in tests"))
                })
            }.build()
        }
        node.extra = { method, req ->
            if (method != "bridge.get_ranked_posts") null
            else if (req["params"].toString().contains("start_author")) "[]"
            else (fresh + (1..30).map { "old-$it" }).joinToString(",", "[", "]") { node.entry("alice", it) }
        }
    }

    @After
    fun tearDown() = SingletonImageLoader.reset()

    private fun idleMain() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = compose.await("exists($tag)") { idleMain(); exists(tag) }
    private fun feedReads() = node.sent.count { "bridge.get_ranked_posts" in it }

    /** The app goes to the background for [millis] and comes back: ON_STOP, then ON_START. */
    private fun away(millis: Long) {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        monotonic += millis
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
    }

    @Test
    fun theHomeFeedReadsAgainAfterALongTripAndNotAfterAShortOne() {
        compose.setContent { EcencyApp(env, host) }
        waitFor("card:alice/old-1")
        val reads = feedReads()

        away(Freshness.STALE_AFTER_MILLIS - 1)
        idleMain()
        assertEquals("a short trip does not refetch", reads, feedReads())

        fresh = listOf("new-1")
        away(Freshness.STALE_AFTER_MILLIS)
        waitFor("card:alice/new-1")
        assertEquals(reads + 1, feedReads())
        assertTrue("the list stayed on screen", exists("card:alice/old-1"))
    }

    @Test
    fun aReaderScrolledDownKeepsTheCardTheyReadAndOneAtTheTopSeesTheNewCards() {
        val feed = env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        val scrolled = LazyListState()
        val actions = FeedActions({}, {}, {}, {})
        compose.setContent { EcencyTheme(dark = false) { FeedList(feed, scrolled, env.votes.state, 0L, actions) } }
        waitFor("card:alice/old-1")
        compose.runOnIdle { kotlinx.coroutines.runBlocking { scrolled.scrollToItem(10) } }
        compose.waitForIdle()
        val reading = scrolled.layoutInfo.visibleItemsInfo.first().key

        fresh = listOf("new-1", "new-2")
        val seen = mutableListOf<com.ecency.core.app.FeedState>()
        val watch = feed.watch { seen += it }
        env.appLock.onBackground()
        away(Freshness.STALE_AFTER_MILLIS)
        env.appLock.onForeground()
        compose.await("the refresh landed") { idleMain(); feed.current.items.first().key.permlink == "new-1" }
        compose.waitForIdle()
        assertEquals("the card being read stays first on screen", reading, scrolled.layoutInfo.visibleItemsInfo.first().key)
        assertFalse("no pull to refresh spinner", seen.any { it.isRefreshing || it.isLoading })
        watch.close()
    }

    @Test
    fun aFeedAtItsTopShowsTheNewCards() {
        val feed = env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        val top = LazyListState()
        compose.setContent { EcencyTheme(dark = false) { FeedList(feed, top, env.votes.state, 0L, FeedActions({}, {}, {}, {})) } }
        waitFor("card:alice/old-1")
        fresh = listOf("new-1", "new-2")
        env.appLock.onBackground()
        away(Freshness.STALE_AFTER_MILLIS)
        env.appLock.onForeground()
        compose.await("the refresh landed") { idleMain(); feed.current.items.first().key.permlink == "new-1" }
        waitFor("card:alice/new-1")
        assertEquals(0, top.firstVisibleItemIndex)
    }

    @Test
    fun theWalletOnScreenReadsThePortfolioAgainAfterALongTrip() {
        val vault = KeyVault(node.store)
        vault.save(VaultAccount("tester", AuthType.POSTING_KEY, postingKey = node.key.toWif(), accessToken = "access-tester"))
        vault.setCurrentUsername("tester")
        node.auth.rest = { r ->
            if (r.url.endsWith("/wallet-api/portfolio-v2")) HttpResult(
                200,
                """{"wallets":[{"name":"Hive","symbol":"HIVE","layer":"hive","balance":12.345,"fiatRate":0.25,"precision":3,"actions":[],"pendingRewards":0,"liquid":12.345}]}""",
            ) else null
        }
        fun reads() = node.auth.requests.count { it.url.endsWith("/wallet-api/portfolio-v2") }
        compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host, Navigator(NavState().selectTab(AppTab.WALLET))) } }
        waitFor("asset:HIVE")
        val before = reads()

        away(Freshness.STALE_AFTER_MILLIS - 1)
        idleMain()
        assertEquals("a short trip does not refetch", before, reads())

        away(Freshness.STALE_AFTER_MILLIS)
        compose.await("the portfolio was read again") { idleMain(); reads() > before }
        assertTrue(exists("asset:HIVE"))
    }
}
