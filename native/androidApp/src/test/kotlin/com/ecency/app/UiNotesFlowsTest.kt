package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.CreateFab
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.ScrollTopPopup
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.l10n.L
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

/** The owner's UI notes: the scroll-to-top button styled as the FAB, the post bar's tip count and comments-as-reply. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiNotesFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }
    private var tipsCount = 1234
    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
    }

    @Before
    fun setUp() {
        SingletonImageLoader.setUnsafe { ctx ->
            ImageLoader.Builder(ctx).components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                        ErrorResult(null, chain.request, IllegalStateException("no network in tests"))
                })
            }.build()
        }
        node.auth.rest = { r ->
            if ("/private-api/post-tips/alice/trending-one" in r.url) HttpResult(200, """{"meta":{"count":$tipsCount,"totals":{}},"list":[]}""") else null
        }
    }

    @After
    fun tearDown() {
        SingletonImageLoader.reset()
        com.ecency.app.ui.DebugLaunch.demoScrollTop = false
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = compose.await("exists($tag)") { exists(tag) }
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun px(dp: Int) = dp * compose.activity.resources.displayMetrics.density

    // ---- 1. scroll to top: the FAB's size, shape and elevation, at the opposite bottom corner ----

    private fun fabsOver(direction: LayoutDirection): LazyListState {
        val state = LazyListState()
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalLayoutDirection provides direction) {
                EcencyTheme(dark = false) {
                    Box(Modifier.fillMaxSize().testTag("screen")) {
                        LazyColumn(state = state, modifier = Modifier.fillMaxSize().testTag("list")) {
                            items(300) { Spacer(Modifier.height(100.dp)) }
                        }
                        ScrollTopPopup(state)
                        CreateFab(onClick = {}, modifier = Modifier.align(Alignment.BottomEnd))
                    }
                }
            }
        }
        compose.onNodeWithTag("list").performScrollToIndex(120)
        compose.onNodeWithTag("list").performScrollToIndex(110)
        waitFor("scrollTop")
        return state
    }

    private fun assertFabTwins(screen: Rect, top: Rect, fab: Rect) {
        assertEquals("the FAB's width", fab.width, top.width, 0.5f)
        assertEquals("the FAB's height", fab.height, top.height, 0.5f)
        assertEquals("56 dp like the FAB", px(56), top.width, 0.5f)
        assertEquals("on the FAB's baseline", fab.bottom, top.bottom, 0.5f)
        assertEquals("16 dp above the bottom", screen.bottom - px(16), top.bottom, 0.5f)
        assertFalse("$top overlaps $fab", top.overlaps(fab))
    }

    @Test
    fun theScrollTopButtonIsTheFabsTwinAtTheBottomStart() {
        val state = fabsOver(LayoutDirection.Ltr)
        val screen = bounds("screen")
        val top = bounds("scrollTop")
        assertFabTwins(screen, top, bounds("createFab"))
        assertEquals("16 dp from the start edge", screen.left + px(16), top.left, 0.5f)
        val config = compose.onNodeWithTag("scrollTop").fetchSemanticsNode().config
        assertEquals(Role.Button, config[SemanticsProperties.Role])
        assertEquals(listOf(com.ecency.core.app.UiCopy.SCROLL_TOP), config[SemanticsProperties.ContentDescription])
        compose.onNodeWithTag("scrollTop").performSemanticsAction(SemanticsActions.OnClick)
        compose.await("back at the top") { state.firstVisibleItemIndex == 0 }
    }

    @Test
    fun inRtlTheScrollTopButtonMirrorsToTheRight() {
        fabsOver(LayoutDirection.Rtl)
        val screen = bounds("screen")
        val top = bounds("scrollTop")
        val fab = bounds("createFab")
        assertFabTwins(screen, top, fab)
        assertEquals("16 dp from the start (right) edge", screen.right - px(16), top.right, 0.5f)
        assertTrue("the FAB on the other side", fab.right < top.left)
    }

    // ---- 2. the post's action bar: tip count, the comments icon replies, no Reply button ----

    private fun signIn() {
        val vault = KeyVault(node.store)
        vault.save(VaultAccount("tester", AuthType.POSTING_KEY, postingKey = node.key.toWif(), accessToken = "token-tester"))
        vault.setCurrentUsername("tester")
    }

    private fun openPost() {
        compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host) } }
        waitFor("open:alice/trending-one")
        compose.onNodeWithTag("open:alice/trending-one").performClick()
        waitFor("postActions")
    }

    private fun inBar(tag: String) = hasTestTag(tag) and hasAnyAncestor(hasTestTag("postActions"))

    @Test
    fun theGiftShowsThePostsTipCountCompactAndSaysIt() {
        openPost()
        compose.await("tip count") { compose.onAllNodes(inBar("tipCount"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val count = compose.onNode(inBar("tipCount"), useUnmergedTree = true).fetchSemanticsNode().config
        assertEquals("1.2K", count[SemanticsProperties.Text].joinToString { it.text })
        val tip = compose.onNode(inBar("tip"), useUnmergedTree = true).fetchSemanticsNode().config
        assertEquals(Role.Button, tip[SemanticsProperties.Role])
        assertEquals(listOf(L.post.a11yTip), tip[SemanticsProperties.ContentDescription])
        assertEquals(L.post.a11yTips(count = 1234), tip[SemanticsProperties.StateDescription])
    }

    @Test
    fun noTipsShowNoNumber() {
        tipsCount = 0
        openPost()
        // the read landed: the gift says "0 tips"
        compose.await("the zero count read") {
            compose.onAllNodes(inBar("tip"), useUnmergedTree = true).fetchSemanticsNodes().any {
                it.config.getOrElseNullable(SemanticsProperties.StateDescription) { null } == L.post.a11yTips(count = 0)
            }
        }
        assertTrue(compose.onAllNodes(inBar("tip"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(inBar("tipCount"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theBarHasNoReplyButtonAndTheCommentsIconTakesTheReaderToTheCommentsReply() {
        // a body longer than the screen: the comments start below the fold
        node.postBodyExtra = " " + (1..600).joinToString(" ") { "word$it" }
        signIn()
        openPost()
        compose.onNode(hasText(com.ecency.core.app.ReadingUiCopy.REPLY) and hasAnyAncestor(hasTestTag("postActions")), useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(inBar("reply"), useUnmergedTree = true).assertDoesNotExist()
        val comments = compose.onNode(inBar("postComments"), useUnmergedTree = true)
        val config = comments.fetchSemanticsNode().config
        assertEquals(Role.Button, config[SemanticsProperties.Role])
        assertEquals(listOf(L.post.a11yComments(count = 1)), config[SemanticsProperties.ContentDescription])
        assertEquals(L.post.a11yCommentsHint, config[SemanticsActions.OnClick].label)
        assertFalse("the comments' reply entry is below the fold at first", isOnScreen("commentsReply"))
        comments.performSemanticsAction(SemanticsActions.OnClick)
        compose.await("the comments section's reply entry in view") { isOnScreen("commentsReply") }
        compose.onNodeWithTag("commentsReply").performSemanticsAction(SemanticsActions.OnClick)
        waitFor("replySheet")
    }

    private fun isOnScreen(tag: String): Boolean {
        val nodes = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()
        if (nodes.isEmpty()) return false
        val b = nodes.first().boundsInRoot
        val bar = bounds("postActions")
        return b.height > 0f && b.top >= 0f && b.bottom <= bar.top + 1f
    }

    // ---- the scroll to top above the system navigation bar on pushed screens (no tab bar there) ----

    private val navBarPx = 126

    /** A gesture or 3-button navigation bar [navBarPx] tall, as the edge to edge window reports it. */
    private fun navigationBar() {
        compose.runOnUiThread {
            val root = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
            val insets = androidx.core.view.WindowInsetsCompat.Builder()
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, navBarPx))
                .setVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), true).build()
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root, insets)
        }
        compose.waitForIdle()
    }

    private fun pushed(screen: com.ecency.core.app.AppScreen) {
        com.ecency.app.ui.DebugLaunch.demoScrollTop = true
        val navigator = com.ecency.app.ui.Navigator(com.ecency.core.app.NavState().push(screen))
        compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host, navigator) } }
        waitFor("feed")
        waitFor("scrollTop")
        navigationBar()
    }

    private fun assertAboveTheNavigationBar() {
        val window = compose.activity.window.decorView.height.toFloat()
        val top = bounds("scrollTop")
        assertEquals("16 dp above the navigation bar ($top in $window)", window - navBarPx - px(16), top.bottom, 1f)
    }

    @Test
    fun onAPushedTagFeedTheScrollTopSitsAboveTheNavigationBar() {
        pushed(com.ecency.core.app.AppScreen.Feed(com.ecency.core.api.repository.FeedSource.Ranked(com.ecency.core.api.RankedSort.TRENDING, "photography"), "#photography"))
        assertAboveTheNavigationBar()
    }

    @Test
    fun onAPushedAccountFeedTheScrollTopSitsAboveTheNavigationBar() {
        pushed(com.ecency.core.app.AppScreen.Feed(com.ecency.core.api.repository.FeedSource.Account("alice", com.ecency.core.api.AccountPostsSort.BLOG), "Blog"))
        assertAboveTheNavigationBar()
    }

    @Test
    fun onTheFeedTabTheScrollTopKeepsTheFabsBaselineWithANavigationBar() {
        com.ecency.app.ui.DebugLaunch.demoScrollTop = true
        compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host) } }
        waitFor("scrollTop")
        navigationBar()
        val top = bounds("scrollTop")
        val fab = bounds("createFab")
        assertEquals("no second inset over the tab bar's", fab.bottom, top.bottom, 0.5f)
        assertTrue("above the tab bar", top.bottom <= bounds("tabBar").top)
    }

    @Test
    fun aVoteFailureSnackbarClearsTheFabRow() {
        signIn()
        node.broadcastDown = true
        compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host) } }
        waitFor("card:alice/trending-one")
        compose.onAllNodes(hasTestTag("vote"))[0].performClick()
        waitFor("voteSheet")
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("upvote").performSemanticsAction(SemanticsActions.OnClick)
        compose.await("the failure snackbar") {
            compose.onAllNodes(hasTestTag("snackbar")).fetchSemanticsNodes().any { it.boundsInRoot.height > 0f }
        }
        val bar = bounds("snackbar")
        val fab = bounds("createFab")
        assertTrue("the snackbar $bar above the FAB $fab", bar.bottom <= fab.top)
    }

    // ---- Waves: the scroll to top and the compose FAB ----

    @Test
    fun onWavesTheScrollTopIsTheComposeFabsTwinAtTheBottomStart() {
        signIn()
        com.ecency.app.ui.DebugLaunch.demoScrollTop = true
        val navigator = com.ecency.app.ui.Navigator(com.ecency.core.app.NavState())
        compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host, navigator) } }
        waitFor("tabBar")
        compose.runOnUiThread { navigator.selectTab(com.ecency.core.app.AppTab.WAVES) }
        waitFor("waves:scrollTop")
        val top = bounds("waves:scrollTop")
        val fab = bounds("createFab")
        assertEquals(fab.width, top.width, 0.5f)
        assertEquals(px(56), top.width, 0.5f)
        assertEquals("on the FAB's baseline", fab.bottom, top.bottom, 0.5f)
        assertEquals("16 dp from the start", px(16), top.left, 0.5f)
        assertFalse(top.overlaps(fab))
    }

    @Test
    fun theGiftIsAFullTouchTarget() {
        openPost()
        val tip = compose.onNode(inBar("tip"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("48 dp wide: $tip", tip.width >= px(48) - 0.5f)
        assertTrue("48 dp tall: $tip", tip.height >= px(48) - 0.5f)
    }

    @Test
    fun onAPushedFeedTheSnackbarClearsTheNavigationBarAndTheScrollTop() {
        signIn()
        node.broadcastDown = true
        pushed(com.ecency.core.app.AppScreen.Feed(com.ecency.core.api.repository.FeedSource.Ranked(com.ecency.core.api.RankedSort.TRENDING, "photography"), "#photography"))
        compose.onAllNodes(hasTestTag("vote"))[0].performClick()
        waitFor("voteSheet")
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("upvote").performSemanticsAction(SemanticsActions.OnClick)
        compose.await("the failure snackbar") {
            compose.onAllNodes(hasTestTag("snackbar")).fetchSemanticsNodes().any { it.boundsInRoot.height > 0f }
        }
        val bar = bounds("snackbar")
        val top = bounds("scrollTop")
        assertTrue("the snackbar $bar above the scroll to top $top", bar.bottom <= top.top)
    }
}

