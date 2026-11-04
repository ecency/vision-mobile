package com.ecency.app

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performFirstLinkClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.CompositionProbe
import com.ecency.app.ui.EMBED_REFERER
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.NavigationViewModel
import com.ecency.app.ui.Navigator
import com.ecency.core.app.AppScreen
import com.ecency.core.app.NavState
import com.ecency.app.ui.loadEmbed
import com.ecency.app.ui.setUpPlayer
import com.ecency.app.ui.playerNavigationAllowed
import com.ecency.core.api.AccountPostsSort
import com.ecency.core.api.RankedSort
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.repository.FeedSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred

/** Review fixes of the slice on Android: sign-in keeps the post, reply sends outlive the sheet, gallery, vote weight, recomposition, player. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SliceFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }
    private val opened = mutableListOf<String>()
    private var weight = 100f
    private val savedWeights = mutableListOf<Float>()

    private val host = object : AppHost {
        override fun openExternal(url: String) { opened += url }
        override fun toast(text: String) {}
        override fun copy(text: String) {}
        override fun voteWeightPercent() = weight
        override fun setVoteWeightPercent(percent: Float) { savedWeights += percent; weight = percent }
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
    }

    @After
    fun tearDown() {
        CompositionProbe.onCard = null
        SingletonImageLoader.reset()
    }

    private fun launch(navigator: Navigator? = null) = compose.setContent {
        EcencyTheme(dark = false) { if (navigator != null) EcencyApp(env, host, navigator) else EcencyApp(env, host) }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = compose.await("a node tagged $tag") { exists(tag) }

    private fun scrollToInPost(target: SemanticsMatcher) = compose.scrollListTo("postList", target)

    private fun settle() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun openPost() {
        waitFor("open:alice/trending-one")
        compose.onNodeWithTag("open:alice/trending-one").performClick()
        waitFor("postActions")
    }

    @Test
    fun signingInToVoteKeepsTheReaderOnThePost() {
        launch()
        openPost()
        compose.onNodeWithTag("postActions").assertExists()
        compose.onAllNodes(hasTestTag("vote") and hasAnyAncestor(hasTestTag("postActions")))[0].performClick()
        waitFor("login")
        compose.onNodeWithTag("loginUser").performTextInput("tester")
        compose.onNodeWithTag("loginKey").performTextInput(node.key.toWif())
        compose.onNodeWithTag("loginSubmit").performClick()
        compose.await("the login sheet closes") { !exists("login") }
        assertTrue("back on the post, not on Home", exists("postTitle"))
        assertFalse(exists("tab:HOT"))
        // the post reloaded in place for the new observer
        compose.await("the post is read again for the new observer") { node.sent.count { "bridge.get_discussion" in it } >= 2 }
    }

    @Test
    fun aReplyBeingSentSurvivesLeavingThePost() {
        node.signIn()
        launch()
        openPost()
        // the bar's comments icon scrolls to the comments, whose header holds the Reply to the post
        compose.onAllNodes(hasTestTag("postComments") and hasAnyAncestor(hasTestTag("postActions")))[0].performClick()
        waitFor("commentsReply")
        compose.onNodeWithTag("commentsReply").performClick()
        waitFor("replySheet")
        settle()
        val gate = CompletableDeferred<Unit>()
        node.broadcastGate = gate
        compose.onNodeWithTag("replyText").performTextInput("Nice post")
        compose.onNodeWithTag("replySend").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.await("the reply reaches the node") { node.broadcasts().isNotEmpty() }

        settle()
        // leaving the post while it sends: the send goes on (the model is app-wide) and its result arrives
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor("tab:HOT") // left the post
        assertTrue("the send is held: its sheet is still up", exists("replySheet"))

        gate.complete(Unit)
        compose.mainClock.autoAdvance = true
        // the sheet stays up while the send runs (checked: with the broadcast held it is still there after the
        // post was left) and closes with the send's result
        compose.await("the reply sheet closes once the send finished") { !exists("replySheet") }
        // back on the post, the reply is there: waited for as well, not read at once
        openPost()
        compose.await("the sent reply shows on the post") {
            runCatching { compose.onNodeWithTag("postList").performScrollToNode(hasTestTag("localComment")) }.isSuccess && exists("localComment")
        }
        assertEquals(1, node.broadcasts().map { it.substringAfter("\"params\":").substringBefore(",\"id\"") }.distinct().size)
    }

    @Test
    fun aDraftIsStillThereAfterLeavingAndReopeningThePost() {
        node.signIn()
        launch()
        openPost()
        // the bar's comments icon scrolls to the comments, whose header holds the Reply to the post
        compose.onAllNodes(hasTestTag("postComments") and hasAnyAncestor(hasTestTag("postActions")))[0].performClick()
        waitFor("commentsReply")
        compose.onNodeWithTag("commentsReply").performClick()
        waitFor("replySheet")
        settle()
        compose.onNodeWithTag("replyText").performTextInput("Half written")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() } // closes the sheet
        settle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() } // leaves the post
        waitFor("open:alice/trending-one")
        openPost()
        // the bar's comments icon scrolls to the comments, whose header holds the Reply to the post
        compose.onAllNodes(hasTestTag("postComments") and hasAnyAncestor(hasTestTag("postActions")))[0].performClick()
        waitFor("commentsReply")
        compose.onNodeWithTag("commentsReply").performClick()
        waitFor("replySheet")
        compose.onNodeWithTag("replyText").assertTextEquals("Half written", includeEditableText = true)
    }

    @Test
    fun anInPostLinkScrollsToItsAnchorAndPushesNoScreen() {
        val filler = (1..40).joinToString("") { "\\n\\nFiller paragraph $it that pushes the target far below the first screen." }
        node.postBodyExtra = "\\n\\n[Jump down](#far-away)$filler\\n\\n<a id=\\\"far-away\\\"></a>\\n\\n## Destination heading"
        val navigator = Navigator(NavState())
        launch(navigator)
        openPost()
        val depth = navigator.state.stack.size
        val link = hasText("Jump down")
        scrollToInPost(link)
        assertTrue(compose.onAllNodes(hasText("Destination heading")).fetchSemanticsNodes().isEmpty())
        compose.onNode(link).performFirstLinkClick()
        compose.await("the destination on screen") { compose.onAllNodes(hasText("Destination heading")).fetchSemanticsNodes().isNotEmpty() }
        // a finger on the link's first letters does the same (back to the link first)
        scrollToInPost(link)
        compose.onNode(link).performTouchInput { click(androidx.compose.ui.geometry.Offset(12f, centerY)) }
        compose.await("the destination on screen") { compose.onAllNodes(hasText("Destination heading")).fetchSemanticsNodes().isNotEmpty() }
        settle()
        compose.onNode(hasText("Destination heading")).assertIsDisplayed()
        assertEquals(depth, navigator.state.stack.size)
        assertTrue(opened.toString(), opened.isEmpty())
    }

    @Test
    fun anAnchorLinkInACommentScrollsBackToTheBodyBlock() {
        val filler = (1..40).joinToString("") { "\\n\\nFiller paragraph $it that keeps the body long." }
        node.postBodyExtra = "\\n\\n<a id=\\\"top-part\\\"></a>\\n\\n## Early heading$filler"
        node.commentBodyExtra = "\\n\\n[Back up](#top-part)"
        val navigator = Navigator(NavState())
        launch(navigator)
        openPost()
        val depth = navigator.state.stack.size
        val link = hasText("Back up")
        scrollToInPost(link)
        // no body row is on screen now: only the comment
        assertTrue(compose.onAllNodes(hasText("Early heading")).fetchSemanticsNodes().isEmpty())
        compose.onNode(link).performFirstLinkClick()
        compose.await("the body heading on screen") { compose.onAllNodes(hasText("Early heading")).fetchSemanticsNodes().isNotEmpty() }
        settle()
        compose.onNode(hasText("Early heading")).assertIsDisplayed()
        assertEquals(depth, navigator.state.stack.size)
    }

    @Test
    fun bodyImagesOpenTheGalleryNotTheBrowser() {
        node.postBodyExtra = """\n\n![one](https://example.com/one.png)\n\n![two](https://example.com/two.png)"""
        launch()
        openPost()
        // the second image (the Listen bar above the body may leave only the first one composed after a scroll to "an image")
        val second = hasTestTag("image") and androidx.compose.ui.test.hasContentDescription("two")
        compose.scrollListTo("postList", second)
        compose.onNode(second).performClick()
        waitFor("gallery")
        compose.onNodeWithTag("galleryPosition", useUnmergedTree = true).assertTextEquals("Preview (2/2)")
        assertTrue(opened.toString(), opened.isEmpty())
    }

    @Test
    fun withShowImagesOffBodyImagesWaitForATapAndTheGalleryShowsOnlyRevealedOnes() {
        node.postBodyExtra = """\n\n![one](https://example.com/one.png)\n\n![two](https://example.com/two.png)"""
        env.notifications.setShowImages(false)
        com.ecency.app.render.RevealedImages.clear()
        launch()
        openPost()
        compose.scrollListTo("postList", hasTestTag("image-hidden"))
        assertTrue(compose.onAllNodes(hasTestTag("image"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        // a tap reveals that one image; the gallery then pages through the revealed one only
        compose.onAllNodes(hasTestTag("image-hidden"))[0].performClick()
        waitFor("image")
        compose.onAllNodes(hasTestTag("image"))[0].performClick()
        waitFor("gallery")
        compose.onNodeWithTag("galleryPosition", useUnmergedTree = true).assertTextEquals("Preview (1/1)")
        com.ecency.app.render.RevealedImages.clear()
    }

    @Test
    fun theVoteSheetStartsAtTheLastWeightAndRemembersTheNewOne() {
        node.signIn()
        weight = 40f
        launch()
        waitFor("card:alice/trending-one")
        compose.onAllNodes(hasTestTag("vote"))[0].performClick()
        waitFor("voteSheet")
        settle()
        compose.onNodeWithTag("votePercent").assertTextEquals("40%")
        compose.onNodeWithTag("voteSubmit").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.await("the vote reaches the node") { node.broadcasts().isNotEmpty() }
        assertTrue(node.broadcasts().single().contains("\"weight\":4000"))
        assertEquals(listOf(40f), savedWeights)
    }

    /** Owner feedback: the compact sheet, a direction toggle, four presets and ONE vote button, no empty space. */
    @Test
    fun theCompactVoteSheetHasADirectionTogglePresetsAndOneVoteButton() {
        node.signIn()
        weight = 12f
        launch()
        waitFor("card:alice/trending-one")
        compose.onAllNodes(hasTestTag("vote"))[0].performClick()
        waitFor("voteSheet")
        settle()
        for (tag in listOf("voteDirUp", "voteDirDown", "votePreset:25", "votePreset:50", "votePreset:75", "votePreset:100", "voteSubmit")) {
            assertTrue("$tag in the sheet", exists(tag))
        }
        assertFalse("no separate upvote button", exists("upvote"))
        assertFalse("no separate downvote button", exists("downvote"))
        compose.onNodeWithTag("votePercent").assertTextEquals("12%")
        compose.onNodeWithTag("voteSubmit").assertTextEquals("Upvote 12%")
        compose.onNodeWithTag("voteDirUp").assertIsSelected()
        compose.onNodeWithTag("voteDirDown").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("voteDirDown").assertIsSelected()
        compose.onNodeWithTag("voteSubmit").assertTextEquals("Downvote 12%")
        compose.onNodeWithTag("votePreset:50").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("votePreset:50").assertIsSelected()
        compose.onNodeWithTag("votePreset:25").assertIsNotSelected()
        compose.onNodeWithTag("voteSubmit").assertTextEquals("Downvote 50%")
        assertTrue("nothing voted yet", node.broadcasts().isEmpty())
        // the sheet ends right under its content: the vote button is near the sheet's bottom
        val sheet = compose.onNodeWithTag("voteSheet").getBoundsInRoot()
        val button = compose.onNodeWithTag("voteSubmit").getBoundsInRoot()
        assertTrue("button bottom ${button.bottom} vs sheet bottom ${sheet.bottom}", sheet.bottom - button.bottom <= 72.dp)
        assertTrue("button at least 50 dp high: ${button.bottom - button.top}", button.bottom - button.top >= 50.dp)
        compose.onNodeWithTag("voteSubmit").performSemanticsAction(SemanticsActions.OnClick)
        compose.await("the vote reaches the node") { node.broadcasts().isNotEmpty() }
        assertTrue(node.broadcasts().single(), node.broadcasts().single().contains("\"weight\":-5000"))
        assertEquals(listOf(50f), savedWeights)
    }

    /** Owner (TestFlight): the payout moves by the vote's estimate at once, as RN; a failed vote puts it back. */
    @Test
    fun aVoteMovesTheCardsPayoutAtOnceAndAFailurePutsItBack() {
        node.signIn()
        // the reader's stake (10000 VESTS, mana long since full) and the reserve rate: a full upvote is worth $0.200 here
        node.extra = { method, _ ->
            when (method) {
                "condenser_api.get_accounts" -> """[{"name":"tester","posting":{"weight_threshold":1,"account_auths":[],"key_auths":[["${node.key.publicKey()}",1]]},""" +
                    """"memo_key":"${node.key.publicKey()}","vesting_shares":"10000.000000 VESTS","voting_manabar":{"current_mana":"0","last_update_time":0}}]"""
                "condenser_api.get_dynamic_global_properties" ->
                    """{"head_block_number":100003,"head_block_id":"000186a3${"11".repeat(16)}","time":"2026-10-06T11:59:00","vote_power_reserve_rate":10}"""
                "condenser_api.get_reward_fund" -> """{"recent_claims":"150000000000000","reward_balance":"800000.000 HIVE"}"""
                else -> null
            }
        }
        val gate = CompletableDeferred<Unit>()
        node.broadcastGate = gate
        node.broadcastDown = true
        launch()
        waitFor("card:alice/trending-one")
        val payout = compose.onAllNodes(hasTestTag("payout") and hasAnyAncestor(hasTestTag("card:alice/trending-one")), useUnmergedTree = true)[0]
        payout.assertTextEquals("$ 1.500")
        compose.onAllNodes(hasTestTag("vote"))[0].performClick()
        waitFor("voteSheet")
        // the estimate sits in a merged group: its tag is in the unmerged tree
        compose.await("the vote estimate") { compose.onAllNodes(hasTestTag("voteValue"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("voteValue", useUnmergedTree = true).assertTextEquals("$0.200")
        compose.onNodeWithTag("votePreset:100").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("voteSubmit").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        // optimistic: the broadcast is still held
        payout.assertTextEquals("$ 1.700")
        gate.complete(Unit)
        compose.await("the vote failed") { env.votes.current.statuses.values.any { it.error != null } }
        compose.waitForIdle()
        payout.assertTextEquals("$ 1.500")
    }

    @Test
    fun aVoteRecomposesOnlyItsOwnCard() {
        node.signIn()
        val gate = CompletableDeferred<Unit>()
        node.broadcastGate = gate
        launch()
        waitFor("card:bob/trending-two")
        compose.waitForIdle()
        val counts = Collections.synchronizedMap(mutableMapOf<PostKey, Int>())
        CompositionProbe.onCard = { counts.merge(it, 1, Int::plus) }
        // the in-flight spinner never settles: drive the clock by hand while the vote is held
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { env.votes.vote("alice", "trending-one", 10000) }
        compose.await("the vote reaches the node") {
            compose.mainClock.advanceTimeByFrame()
            node.broadcasts().isNotEmpty()
        }
        compose.mainClock.advanceTimeBy(500)
        assertTrue(env.votes.current.statuses.values.single().inFlight)
        assertTrue(counts.toString(), (counts[PostKey("alice", "trending-one")] ?: 0) >= 1)
        assertEquals(counts.toString(), null, counts[PostKey("bob", "trending-two")])
        gate.complete(Unit)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun theBackStackSurvivesProcessDeathThroughTheSavedState() {
        val handle = SavedStateHandle()
        val before = NavigationViewModel(handle)
        before.navigator.push(AppScreen.Feed(FeedSource.Ranked(RankedSort.HOT, "hive-100001"), "Test Community"))
        before.navigator.push(AppScreen.Profile("alice"))
        before.navigator.push(AppScreen.Feed(FeedSource.Account("alice", AccountPostsSort.BLOG), "Blog\u001F"))
        before.navigator.push(AppScreen.Post("alice", "trending-one"))
        val model = before.navigator.top.get("post") { env.postModel("alice", "trending-one") }

        // the process dies: only the saved state comes back
        val restored = NavigationViewModel(SavedStateHandle(mapOf(NavigationViewModel.KEY to handle.get<ArrayList<String>>(NavigationViewModel.KEY))))
        assertEquals(
            listOf(
                AppScreen.Feed(FeedSource.Ranked(RankedSort.HOT, "hive-100001"), "Test Community"),
                AppScreen.Profile("alice"),
                AppScreen.Feed(FeedSource.Account("alice", AccountPostsSort.BLOG), "Blog "),
                AppScreen.Post("alice", "trending-one"),
            ),
            restored.navigator.screens,
        )
        assertEquals(null, restored.navigator.top.peek<Any>("post")) // models are created again on demand

        before.onCleared()
        assertTrue("onCleared closes every model", model.watch {}.isClosed)
        assertTrue(NavState.decodeScreen("unknown\u001Fx") == null)
    }

    @Test
    fun playerFramesStayOnTheEmbedHostAndLoadWithAReferer() {
        val embed = "https://www.youtube.com/embed/dQw4w9WgXcQ"
        assertTrue(playerNavigationAllowed("https://www.youtube.com/embed/other", embed))
        assertTrue(playerNavigationAllowed("about:blank", embed))
        assertFalse(playerNavigationAllowed("https://player.vimeo.com/video/1", embed)) // allowlisted, but another host
        assertFalse(playerNavigationAllowed("https://accounts.google.com/", embed))
        assertFalse(playerNavigationAllowed("http://www.youtube.com/embed/x", embed))
        assertFalse(playerNavigationAllowed("intent://x#Intent;end", embed))

        val view = WebView(ApplicationProvider.getApplicationContext())
        view.loadEmbed(embed)
        assertEquals(embed, shadowOf(view).lastLoadedUrl)
        assertEquals(EMBED_REFERER, shadowOf(view).lastAdditionalHttpHeaders["Referer"])
        val blocked = WebView(ApplicationProvider.getApplicationContext())
        blocked.loadEmbed("https://evil.example/embed/x")
        assertEquals(null, shadowOf(blocked).lastLoadedUrl)
    }

    @Test
    fun thePlayerSheetUsesTheSharedNavigationRule() {
        val skate = "https://ipfs.skatehive.app/ipfs/QmVideoHash"
        val youtube = "https://www.youtube.com/embed/dQw4w9WgXcQ"
        val cases = listOf(
            "https://evil.example\\@ipfs.skatehive.app/ipfs/QmVideoHash",
            "https://user@ipfs.skatehive.app/ipfs/QmVideoHash",
            "https://ipfs.skatehive.app/ipfs/QmOtherHash",
            skate,
            "https://evil.example\\@www.youtube.com/embed/x",
            "https://www.youtube.com/embed/other",
        )
        for (embed in listOf(skate, youtube)) for (url in cases) {
            assertEquals(url, com.ecency.core.render.blocks.SafeTarget.playerNavigationAllowed(url, embed), playerNavigationAllowed(url, embed))
        }
        assertFalse(playerNavigationAllowed("https://evil.example\\@ipfs.skatehive.app/ipfs/QmVideoHash", skate))
        // the web view's client blocks what the rule refuses, and a video file plays without page script
        val view = WebView(ApplicationProvider.getApplicationContext())
        view.setUpPlayer(skate)
        assertFalse(view.settings.javaScriptEnabled)
        val client = shadowOf(view).webViewClient!!
        assertTrue(client.shouldOverrideUrlLoading(view, request("https://ipfs.skatehive.app/login")))
        assertFalse(client.shouldOverrideUrlLoading(view, request(skate)))
        val player = WebView(ApplicationProvider.getApplicationContext())
        player.setUpPlayer(youtube)
        assertTrue(player.settings.javaScriptEnabled)
    }

    private fun request(url: String) = object : android.webkit.WebResourceRequest {
        override fun getUrl() = android.net.Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }
}
