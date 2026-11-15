package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.render.BodySelectionAction
import com.ecency.app.render.PostActions
import com.ecency.app.render.PostBody
import com.ecency.app.render.SelectableBodyText
import com.ecency.app.render.act
import com.ecency.app.render.rememberInlineText
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.FeedxUi
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.render.blocks.Block
import com.ecency.core.render.blocks.Inline
import com.ecency.core.render.blocks.LinkTarget
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #3699: text of a post or comment body selects; Quote, Translate and Share act on the selection; Select text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SelectTextFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }
    private val shared = mutableListOf<String>()
    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
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

    private val paragraph = listOf(Inline.Text("Hi "), Inline.Mention("spike-bob"), Inline.Text(", thanks for reading."))

    @Test
    fun aMenuItemGetsTheSelectedTextWithTheChipAsItsName() {
        val state = SelectionState()
        compose.setContent {
            EcencyTheme(dark = false) {
                val actions = PostActions(onSelection = { _, _ -> })
                val built = rememberInlineText(paragraph, actions)
                SelectableBodyText(actions.onSelection, state) { BasicText(built.text, inlineContent = built.inlineContent) }
            }
        }
        compose.waitForIdle()
        val got = mutableListOf<Pair<BodySelectionAction, String>>()
        compose.runOnIdle {
            state.selectAll()
        }
        compose.runOnIdle {
            state.act(BodySelectionAction.QUOTE) { a, t -> got += a to t }
            assertTrue("the selection goes once taken", state.selectedTexts.all { it.text.isEmpty() })
        }
        assertEquals(1, got.size)
        assertEquals(BodySelectionAction.QUOTE, got.single().first)
        assertTrue(got.single().second, got.single().second.contains("@spike-bob"))
        assertTrue(got.single().second.startsWith("Hi "))
        // nothing selected: nothing handed over
        compose.runOnIdle { state.act(BodySelectionAction.SHARE) { a, t -> got += a to t } }
        assertEquals(1, got.size)
    }

    @Test
    fun inSelectableTextALongPressDoesNotCopyTheParagraphAndLinksStillOpen() {
        val pressed = mutableListOf<String>()
        val taps = mutableListOf<LinkTarget>()
        val link = LinkTarget.External("https://example.com/page")
        val blocks = listOf(
            Block.Paragraph(paragraph),
            Block.Paragraph(listOf(Inline.Text("Read "), Inline.Link(link, listOf(Inline.Text("this page"))), Inline.Text(" now"))),
        )
        compose.setContent {
            EcencyTheme(dark = false) {
                PostBody(blocks, 360.dp, PostActions(onLink = { taps += it }, onParagraphLongPress = { pressed += it }, onSelection = { _, _ -> }))
            }
        }
        // a mouse long press: the old paragraph handler took it as well as a finger's, and it draws no magnifier
        // (Robolectric has no surface for the platform one a touch selection shows)
        compose.onNodeWithText("Hi", substring = true).performMouseInput { longClick() }
        compose.onNodeWithTag("chip:@spike-bob", useUnmergedTree = true).performMouseInput { longClick() }
        compose.waitForIdle()
        assertEquals("the long press selects instead of copying the paragraph", emptyList<String>(), pressed)
        // the link is its own clickable node (chips are the others): a tap still opens it
        val clickable = compose.onAllNodes(hasClickAction(), useUnmergedTree = true)
        val linkIndex = clickable.fetchSemanticsNodes().indexOfFirst {
            it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }?.startsWith("chip:") != true
        }
        assertTrue(linkIndex >= 0)
        clickable[linkIndex].performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf<LinkTarget>(link), taps)
    }

    @Test
    fun theParagraphLongPressHandsTheParagraphOverOnlyWhereTextDoesNotSelect() {
        val pressed = mutableListOf<String>()
        val plain = PostActions(onParagraphLongPress = { pressed += it })
        com.ecency.app.render.paragraphLongPress(plain, paragraph)!!.invoke()
        assertEquals(listOf("Hi @spike-bob, thanks for reading."), pressed)
        val selectable = PostActions(onParagraphLongPress = { pressed += it }, onSelection = { _, _ -> })
        assertEquals(null, com.ecency.app.render.paragraphLongPress(selectable, paragraph))
    }

    @Test
    fun quoteTranslateAndShareActOnTheSelection() {
        val replied = mutableListOf<Pair<PostKey, Post>>()
        var loginAsked = 0
        val ui = FeedxUi(env, host, push = {}, reply = { root, post -> replied += root to post }, login = { loginAsked++ })
        val root = PostKey("alice", "trending-one")
        val comment = Post("carol", "re-one", body = "A comment", depth = 1, parentAuthor = "alice", parentPermlink = "trending-one")
        // signed out: Quote asks to log in and quotes nothing
        compose.runOnIdle { ui.bodySelection(BodySelectionAction.QUOTE, "well said", comment, root) }
        assertEquals(1, loginAsked)
        assertTrue(replied.isEmpty())

        node.signIn()
        compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host) } }
        compose.await("signed in") { env.session.current.username != null }
        compose.runOnIdle { ui.bodySelection(BodySelectionAction.QUOTE, "well said", comment, root) }
        compose.await("the quote in the comment's draft") { env.replyModel(root, comment).current.text == "> well said\n\n" }
        assertEquals(listOf(root to comment), replied)

        compose.runOnIdle { ui.bodySelection(BodySelectionAction.SHARE, " well said ", comment, root) }
        assertEquals(listOf("\"well said\"\n\nhttps://ecency.com/@carol/re-one"), shared)

        compose.runOnIdle { ui.bodySelection(BodySelectionAction.TRANSLATE, "well said", comment, root) }
        assertEquals("well said", ui.translateText)
    }

    @Test
    fun selectTextInThePostMenuShowsTheWholeBody() {
        node.signIn()
        compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host) } }
        compose.await("the card") { compose.onAllNodes(hasTestTag("card:alice/trending-one")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("open:alice/trending-one").performClick()
        compose.await("the post") { compose.onAllNodes(hasTestTag("postTitle")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasTestTag("options") and hasAnyAncestor(hasTestTag("postList")).not())[0].performClick()
        compose.await("the menu") { compose.onAllNodes(hasTestTag("menu:select-text")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("menu:select-text").performSemanticsAction(SemanticsActions.OnClick)
        compose.await("the sheet with the body") {
            compose.onAllNodes(hasTestTag("selectTextBody"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasTestTag("selectTextBody") and hasText("Body of trending-one with bold text", substring = true), useUnmergedTree = true).assertExists()
    }
}
