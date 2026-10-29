package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.core.app.FeedTabs
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

/** Feed, posts and reading on Android: the post menu, voters, reblogs, customise tabs, downvote mode, quick profile. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedxFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }
    private val copied = mutableListOf<String>()
    private val shared = mutableListOf<String>()

    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) { copied += text }
        override fun share(text: String) { shared += text }
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
    fun tearDown() = SingletonImageLoader.reset()

    private fun launch() = compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host) } }
    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = compose.await("exists(tag)") { exists(tag) }
    /** Spinners never settle while a broadcast runs: drive the main looper and the clock by hand (as SliceFlowsTest). */
    private fun pump(condition: () -> Boolean) {
        compose.mainClock.autoAdvance = false
        try {
            compose.await("the condition (main clock moved by hand)") {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                compose.mainClock.advanceTimeByFrame()
                condition()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    private fun hasTextNode(text: String) = compose.onAllNodes(androidx.compose.ui.test.hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun settle() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun openCardMenu() {
        waitFor("card:alice/trending-one")
        compose.onAllNodes(hasTestTag("options") and hasAnyAncestor(hasTestTag("card:alice/trending-one")))[0].performClick()
        waitFor("menu:copy")
        settle()
    }

    @Test
    fun theCardMenuCopiesAndSharesTheCanonicalLink() {
        launch()
        openCardMenu()
        // signed out: no account-only items, reblog is offered (it asks to sign in)
        assertTrue(exists("menu:reblog") && exists("menu:report") && !exists("menu:delete-post"))
        compose.onNodeWithTag("menu:copy").performSemanticsAction(SemanticsActions.OnClick)
        compose.await("copied.isNotEmpty()") { copied.isNotEmpty() }
        assertEquals("https://ecency.com/@alice/trending-one", copied.single())
        openCardMenu()
        compose.onNodeWithTag("menu:share").performSemanticsAction(SemanticsActions.OnClick)
        compose.await("shared.isNotEmpty()") { shared.isNotEmpty() }
        assertEquals("Title trending-one https://ecency.com/@alice/trending-one", shared.single())
    }

    @Test
    fun aModeratorMutesAPostWithANote() {
        node.signIn()
        launch()
        openCardMenu()
        waitFor("menu:mute-post")
        compose.onNodeWithTag("menu:mute-post").performSemanticsAction(SemanticsActions.OnClick)
        waitFor("modNotes")
        settle()
        assertFalse("no broadcast before the note", node.broadcasts().isNotEmpty())
        // the set role sheet's layout: title, the post it acts on, the note, the action row, Cancel
        assertTrue(hasTextNode("@alice/trending-one") && exists("modNotesCancel"))
        compose.onNodeWithTag("modNotesConfirm").assertIsNotEnabled()
        compose.onNodeWithTag("modNotesInput").performTextInput("   ")
        compose.onNodeWithTag("modNotesConfirm").assertIsNotEnabled()
        compose.onNodeWithTag("modNotesInput").performTextClearance()
        compose.onNodeWithTag("modNotesInput").performTextInput("  off topic ")
        compose.onNodeWithTag("modNotesConfirm").assertIsEnabled()
        compose.onNodeWithTag("modNotesConfirm").performSemanticsAction(SemanticsActions.OnClick)
        pump { node.broadcasts().isNotEmpty() }
        val tx = node.broadcasts().single()
        // through CommunityOps, the note trimmed as hivemind wants it
        assertTrue(tx, "mutePost" in tx && "hive-100001" in tx && "notes\\\":\\\"off topic\\\"" in tx)
    }

    @Test
    fun aCancelledNoteBroadcastsNothing() {
        node.signIn()
        launch()
        openCardMenu()
        waitFor("menu:mute-post")
        compose.onNodeWithTag("menu:mute-post").performSemanticsAction(SemanticsActions.OnClick)
        waitFor("modNotes")
        settle()
        compose.onNodeWithTag("modNotesInput").performTextInput("off topic")
        compose.onNodeWithTag("modNotesCancel").performSemanticsAction(SemanticsActions.OnClick)
        compose.await("!exists(\"modNotes\")") { !exists("modNotes") }
        settle()
        assertTrue(node.broadcasts().isEmpty())
    }

    @Test
    fun votesOpenTheVotersScreenWithValues() {
        launch()
        waitFor("card:alice/trending-one")
        compose.onAllNodes(hasTestTag("voters") and hasAnyAncestor(hasTestTag("card:alice/trending-one")))[0].performClick()
        waitFor("votersScreen")
        compose.await("exists(\"voter:x\") && exists(\"voter:y\")") { exists("voter:x") && exists("voter:y") }
        // $1.500 shared by rshares 1000 and -200 over the sum 800
        assertTrue(compose.onAllNodes(hasText("$ 1.875")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(hasText("Voters Info (2)")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun reblogsListShowsWhoReblogged() {
        launch()
        waitFor("card:alice/trending-one")
        compose.onAllNodes(hasTestTag("reblogs") and hasAnyAncestor(hasTestTag("card:alice/trending-one")))[0].performClick()
        waitFor("reblogsScreen")
        pump { exists("reblogger:carol") }
        assertTrue(compose.onAllNodes(hasText("Reblog Post")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun customisingNeedsThreeFilters() {
        node.signIn()
        launch()
        waitFor("customise")
        compose.onNodeWithTag("customise").performClick()
        waitFor("customiseSheet")
        settle()
        // the test environment saved trending, hot, created, friends, communities: drop three
        listOf("friends", "communities", "created").forEach {
            compose.onAllNodes(androidx.compose.ui.test.isToggleable() and hasAnyAncestor(hasTestTag("filter:$it")), useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.OnClick)
        }
        compose.onAllNodes(androidx.compose.ui.test.isToggleable() and hasAnyAncestor(hasTestTag("filter:hot")), useUnmergedTree = true)[0].performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("applyFilters").performSemanticsAction(SemanticsActions.OnClick)
        compose.await("compose.onAllNodes(hasText(\"Please select 3 filters\")).fetchSemanticsNodes().isNotEmpty()") { compose.onAllNodes(hasText("Please select 3 filters")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("unchanged", 5, FeedTabs.saved(env.prefs).size)
    }

    @Test
    fun downvoteSwitchesModeBeforeVoting() {
        node.signIn()
        launch()
        waitFor("card:alice/trending-one")
        compose.onAllNodes(hasTestTag("vote"))[0].performClick()
        waitFor("voteSheet")
        settle()
        compose.onNodeWithTag("downvote").performSemanticsAction(SemanticsActions.OnClick)
        settle()
        assertTrue("first tap only switches mode", node.broadcasts().isEmpty())
        compose.onNodeWithTag("downvote").performSemanticsAction(SemanticsActions.OnClick)
        pump { node.broadcasts().isNotEmpty() }
        assertTrue(node.broadcasts().single(), "\"weight\":-10000" in node.broadcasts().single())
    }

    @Test
    fun aMutedCardSaysTapToRevealUntilTapped() {
        node.secondMuted = true
        launch()
        waitFor("card:bob/trending-two")
        compose.onNodeWithTag("feed").performScrollToNode(hasTestTag("card:bob/trending-two"))
        compose.await("exists(\"mutedHint\")") { exists("mutedHint") }
        assertTrue(compose.onAllNodes(hasText("Content muted for community guidelines violation Tap to reveal")).fetchSemanticsNodes().isNotEmpty())
        // only the muted card carries the hint
        assertEquals(1, compose.onAllNodes(hasTestTag("mutedHint")).fetchSemanticsNodes().size)
        compose.onNodeWithTag("mutedHint").performSemanticsAction(SemanticsActions.OnClick)
        compose.await("!exists(\"mutedHint\")") { !exists("mutedHint") }
    }

    @Test
    fun theAuthorOpensTheQuickProfile() {
        launch()
        waitFor("card:alice/trending-one")
        compose.onAllNodes(hasTestTag("author") and hasAnyAncestor(hasTestTag("card:alice/trending-one")))[0].performClick()
        waitFor("quickProfile")
        compose.onNodeWithTag("qpName").assertExists()
        assertTrue(compose.onAllNodes(hasText("@alice")).fetchSemanticsNodes().isNotEmpty())
    }
}
