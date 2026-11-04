package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.LabelOrIcon
import com.ecency.app.ui.Navigator
import com.ecency.core.app.AppIcon
import com.ecency.core.app.AppScreen
import com.ecency.core.app.AppTab
import com.ecency.core.app.NavState
import com.ecency.core.app.UiCopy
import com.ecency.core.l10n.L
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * TalkBack, font scale and small screen checks (README "Accessibility", Android), with real font metrics (native
 * graphics). The scans walk every window's semantics tree:
 * - every action TalkBack can reach has a label (text or content description) and a role;
 * - at font scale 2.0 on a 360 dp wide phone no text is cut: a text either fits or ends in an ellipsis on purpose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h740dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class A11yFlowsTest {
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

    private fun launch(state: NavState = NavState(), fontScale: Float = 1f, navigator: Navigator = Navigator(state)) = compose.setContent {
        val d = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
            EcencyTheme(dark = false) { EcencyApp(env, host, navigator) }
        }
    }

    /** Each signed-in tab in turn, in one composition. */
    /** Rows each tab showed (printed: a tab the fake node leaves empty is checked in its own flow test with rows). */
    private val shown = mutableMapOf<AppTab, Int>()

    private fun eachSignedInTab(tabs: List<AppTab>, fontScale: Float, check: (AppTab) -> Unit) {
        node.signIn()
        val navigator = Navigator(NavState())
        launch(fontScale = fontScale, navigator = navigator)
        waitFor("bottomTab:FEED")
        for (tab in tabs) {
            compose.runOnUiThread { navigator.selectTab(tab) }
            compose.await("the $tab tab") { compose.onAllNodes(hasTestTag("bottomTab:${tab.name}")).fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.Selected) == true } }
            // the tab's content, not its skeleton: a row, or the empty / error message when the fake node has none
            val prefix = mapOf(AppTab.WAVES to "wave:", AppTab.NOTIFICATION to "notification:", AppTab.WALLET to "asset:", AppTab.CHATS to "chatRow:")[tab]
            compose.await("the $tab tab's content") {
                val tags = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("tagged") { it.config.contains(SemanticsProperties.TestTag) }, useUnmergedTree = true)
                    .fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
                tags.none { it.startsWith("skeleton") || it == "chatsLoading" } && tags.any { (prefix != null && it.startsWith(prefix)) || it in setOf("message", "chatsEmpty", "walletError") }
            }
            shown[tab] = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("row") { n -> prefix != null && n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }, useUnmergedTree = true)
                .fetchSemanticsNodes().size
            check(tab)
        }
        println("a11y tab rows: $shown")
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = compose.await("a node tagged $tag") { exists(tag) }

    private fun nodes(unmerged: Boolean) = compose.a11yNodes(unmerged)
    private fun unlabelledActions() = compose.unlabelledActions()
    private fun clippedTexts() = compose.clippedTexts()
    private fun assertAccessible(what: String) = compose.assertAccessible(what)
    private fun assertNothingClipped(what: String) = compose.assertNothingClipped(what)
    private fun SemanticsNode.label() = a11yLabel()

    // ---- TalkBack ----

    @Test
    fun aFeedCardIsOneStopWithItsTapsAsCustomActions() {
        launch()
        waitFor("card:alice/trending-one")
        val card = compose.onNodeWithTag("card:alice/trending-one").fetchSemanticsNode()
        val summary = card.config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(summary, summary.contains("Title trending-one") && summary.contains("alice"))
        assertTrue(summary, summary.contains(L.post.a11yVotes(count = 1)) || summary.contains(L.post.a11yVotes(count = 0)) || summary.contains(" vote"))
        assertTrue("a double tap opens it", card.config.contains(SemanticsActions.OnClick))
        assertEquals("one description: the badges inside do not add their own", 1, card.config[SemanticsProperties.ContentDescription].size)
        val labels = card.config[SemanticsActions.CustomActions].map { it.label }
        assertTrue(labels.toString(), labels.containsAll(listOf(UiCopy.UPVOTE, L.post.a11yCommentsHint, UiCopy.profileOf("alice"))))
        // the small taps inside are hidden from TalkBack but still there for touch
        for (tag in listOf("vote", "avatar", "author", "options")) {
            val inner = compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("card:alice/trending-one")), useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("$tag is in the card", inner.isNotEmpty())
            assertTrue("$tag is hidden from TalkBack", inner.all { it.config.contains(SemanticsProperties.HideFromAccessibility) })
        }
        // the custom action does what the tap does: the post opens from "View comments"
        val comments = card.config[SemanticsActions.CustomActions].first { it.label == L.post.a11yCommentsHint }
        compose.runOnUiThread { comments.action() }
        waitFor("postActions")
    }

    @Test
    fun theFeedReachesNoUnlabelledAction() {
        launch()
        waitFor("card:alice/trending-one")
        assertAccessible("feed")
        // bottom tabs are tabs with a selected state
        val feedTab = compose.onNodeWithTag("bottomTab:FEED").fetchSemanticsNode()
        assertEquals(Role.Tab, feedTab.config[SemanticsProperties.Role])
        assertEquals(true, feedTab.config[SemanticsProperties.Selected])
        val hot = compose.onNodeWithTag("tab:HOT").fetchSemanticsNode()
        assertEquals(Role.Tab, hot.config[SemanticsProperties.Role])
    }

    @Test
    fun thePostAndItsCommentsReachNoUnlabelledAction() {
        launch()
        waitFor("open:alice/trending-one")
        compose.onNodeWithTag("open:alice/trending-one").performClick()
        waitFor("postActions")
        compose.await("comments") { compose.onAllNodes(hasTestTag("commentHeader")).fetchSemanticsNodes().isNotEmpty() || exists("message") }
        assertAccessible("post")
        compose.onAllNodes(hasTestTag("commentHeader")).fetchSemanticsNodes().firstOrNull()?.let { header ->
            val labels = header.config[SemanticsActions.CustomActions].map { it.label }
            assertTrue(labels.toString(), labels.containsAll(listOf(UiCopy.UPVOTE, L.post.a11yReplyHint)))
        }
    }

    @Test
    fun settingsReadTheirTitlesAndRoles() {
        launch(NavState().push(AppScreen.Settings))
        waitFor("settings:language")
        assertAccessible("settings")
        val title = compose.onNodeWithTag("screenTitle").fetchSemanticsNode()
        assertTrue("the screen title is a heading", title.config.contains(SemanticsProperties.Heading))
        // the dropdown names its setting and its value, as one stop
        val dropdown = compose.onNodeWithTag("settings:language").fetchSemanticsNode()
        assertEquals(Role.DropdownList, dropdown.config[SemanticsProperties.Role])
        assertTrue(dropdown.config[SemanticsProperties.ContentDescription].isNotEmpty())
        assertTrue(dropdown.config.getOrNull(SemanticsProperties.StateDescription).orEmpty().isNotEmpty())
        assertTrue("it can expand", dropdown.config.contains(SemanticsActions.Expand))
    }

    @Test
    fun theLoginScreenReachesNoUnlabelledAction() {
        launch(NavState().push(AppScreen.Login))
        waitFor("login")
        assertAccessible("login")
    }

    @Test
    fun signedInTabsReachNoUnlabelledAction() =
        eachSignedInTab(listOf(AppTab.WAVES, AppTab.NOTIFICATION, AppTab.WALLET, AppTab.CHATS), 1f) { assertAccessible(it.name) }

    // ---- touch targets do not inflate layouts ----

    @Test
    fun theSearchBarKeepsItsHeightWhenTheClearButtonAppears() {
        launch(NavState().push(AppScreen.Search))
        waitFor("searchBar")
        val before = compose.onNodeWithTag("searchBar").fetchSemanticsNode().size.height
        compose.onNodeWithTag("searchInput").performTextInput("hive")
        waitFor("clearSearch")
        assertEquals("the search bar does not jump when the clear button shows", before, compose.onNodeWithTag("searchBar").fetchSemanticsNode().size.height)
    }

    @Test
    fun aCommentHeaderIsNotInflatedByItsOptionsButton() {
        launch()
        waitFor("open:alice/trending-one")
        compose.onNodeWithTag("open:alice/trending-one").performClick()
        compose.await("a comment") { compose.onAllNodes(hasTestTag("commentHeader")).fetchSemanticsNodes().isNotEmpty() }
        val header = compose.onAllNodes(hasTestTag("commentHeader")).fetchSemanticsNodes().first()
        val heightDp = with(compose.density) { header.size.height.toDp() }
        // name, age and the 36 dp options button: Compose widens the small button's touch area, not the row
        assertTrue("comment header $heightDp tall", heightDp.value <= 40f)
    }

    // ---- the editor header's label comes back (LabelOrIcon) ----

    @Test
    fun anEditorHeaderActionGoesBackToItsLabelWhenRoomComesBack() {
        var width by mutableStateOf(40.dp)
        compose.setContent {
            EcencyTheme(dark = false) {
                Box(Modifier.width(width)) { Row { LabelOrIcon(UiCopy.SAVE, AppIcon.DRAFTS, androidx.compose.ui.graphics.Color.Blue, tag = "save") {} } }
            }
        }
        compose.waitForIdle()
        assertTrue("narrow: the icon", compose.onAllNodes(hasTestTag("save.label"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        width = 300.dp
        compose.waitForIdle()
        assertTrue("wide again: the label", compose.onAllNodes(hasTestTag("save.label"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        width = 40.dp
        compose.waitForIdle()
        assertTrue("narrow again: the icon", compose.onAllNodes(hasTestTag("save.label"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        // either way the button is named
        assertEquals(UiCopy.SAVE, compose.onNodeWithTag("save").fetchSemanticsNode().label())
    }

    // ---- font scale 2.0 on a 360 dp phone ----

    @Test
    fun theFeedAtFontScale2CutsNoText() {
        launch(fontScale = 2f)
        waitFor("card:alice/trending-one")
        assertNothingClipped("feed")
        assertAccessible("feed at font scale 2")
    }

    @Test
    fun thePostAtFontScale2CutsNoText() {
        launch(fontScale = 2f)
        waitFor("open:alice/trending-one")
        compose.onNodeWithTag("open:alice/trending-one").performClick()
        waitFor("postActions")
        compose.mainClock.advanceTimeBy(1_000)
        assertNothingClipped("post")
    }

    @Test
    fun settingsAtFontScale2CutNoText() {
        launch(NavState().push(AppScreen.Settings), fontScale = 2f)
        waitFor("settings:language")
        assertNothingClipped("settings")
    }

    @Test
    fun theLoginScreenAtFontScale2CutsNoText() {
        launch(NavState().push(AppScreen.Login), fontScale = 2f)
        waitFor("login")
        assertNothingClipped("login")
    }

    @Test
    fun signedInTabsAtFontScale2CutNoText() =
        eachSignedInTab(listOf(AppTab.WAVES, AppTab.NOTIFICATION, AppTab.WALLET, AppTab.CHATS), 2f) { assertNothingClipped(it.name) }

    @Test
    fun theScansSeeACutTextAndAnUnlabelledButton() {
        compose.setContent {
            EcencyTheme(dark = false) {
                androidx.compose.foundation.layout.Column {
                    Box(Modifier.width(30.dp).height(20.dp)) {
                        androidx.compose.material3.Text("A sentence far too long for this box", modifier = Modifier.testTag("cut"))
                    }
                    Box(Modifier.width(30.dp).height(30.dp).clickable { }) {}
                    // hiding a node hides that node only: an action below it is still reached
                    Box(Modifier.semantics { hideFromAccessibility() }) { Box(Modifier.width(30.dp).height(30.dp).clickable { }.testTag("underHidden")) {} }
                }
            }
        }
        compose.waitForIdle()
        assertTrue(clippedTexts().toString(), clippedTexts().any { it.contains("A sentence") })
        assertTrue(unlabelledActions().toString(), unlabelledActions().isNotEmpty())
        assertTrue(unlabelledActions().toString(), unlabelledActions().any { "underHidden" in it })
    }
}
