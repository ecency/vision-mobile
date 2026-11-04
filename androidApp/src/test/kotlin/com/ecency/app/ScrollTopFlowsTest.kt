package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.Navigator
import com.ecency.app.ui.ScrollTopPopup
import com.ecency.core.api.RankedSort
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.app.AppScreen
import com.ecency.core.app.NavState
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

/** Back to the top of a list: the scroll to top button while the list still flings, and a tap on the selected tab (RN onTabPress). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScrollTopFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }
    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
    }

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
        // every ranked feed: 40 posts on its first page, nothing after
        node.extra = { method, req ->
            if (method != "bridge.get_ranked_posts") null
            else if (req["params"].toString().contains("start_author")) "[]"
            else (1..40).joinToString(",", "[", "]") { node.entry("alice", "long-$it") }
        }
    }

    @After
    fun tearDown() = SingletonImageLoader.reset()

    private fun idleMain() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = compose.await("exists($tag)") { idleMain(); exists(tag) }

    /** The feed's list scrolled far down: its first card is gone. */
    private fun scrollFeedDown() {
        waitFor("card:alice/long-1")
        compose.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("feed"))).performScrollToIndex(30)
        compose.await("the first card scrolled away") { idleMain(); !exists("card:alice/long-1") }
    }

    @Test
    fun theScrollTopButtonStopsAFlingAndGoesToTheTop() {
        com.ecency.app.ui.DebugLaunch.demoScrollTop = true
        try {
            val state = LazyListState(150)
            compose.setContent {
                EcencyTheme(dark = false) {
                    Box(Modifier.fillMaxSize()) {
                        LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("list")) { items(400) { Spacer(Modifier.height(100.dp)) } }
                        ScrollTopPopup(state)
                    }
                }
            }
            waitFor("scrollTop")
            compose.mainClock.autoAdvance = false
            // a fast swipe: the list keeps flinging after the finger lifts
            compose.onNodeWithTag("list").performTouchInput { swipeUp(startY = bottom - 10f, endY = top + 10f, durationMillis = 80) }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(100)
            assertTrue("still flinging", state.isScrollInProgress)
            compose.onNodeWithTag("scrollTop").performClick()
            compose.mainClock.advanceTimeBy(3_000)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertEquals("the fling did not carry it away from the top", 0, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
            assertFalse(state.isScrollInProgress)
        } finally {
            com.ecency.app.ui.DebugLaunch.demoScrollTop = false
        }
    }

    @Test
    fun aTapOnTheSelectedHomeFilterScrollsItsFeedToTheTop() {
        compose.setContent { EcencyApp(env, host) }
        scrollFeedDown()
        compose.onNodeWithTag("tab:TRENDING").performClick()
        waitFor("card:alice/long-1")
        // it stays on the same filter (no other feed loaded)
        assertTrue(exists("card:alice/long-2"))
    }

    @Test
    fun aTapOnTheSelectedSortOfATagFeedScrollsItToTheTop() {
        val navigator = Navigator(NavState().push(AppScreen.Feed(FeedSource.Ranked(RankedSort.TRENDING, "photography"), "#photography")))
        compose.setContent { EcencyApp(env, host, navigator) }
        scrollFeedDown()
        compose.onNodeWithTag("tab:TOP").performClick()
        waitFor("card:alice/long-1")
    }
}
