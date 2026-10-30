package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.ScrollTopPopup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.app.UiCopy
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/** The parity gaps of wave gaps2 on Android, against fakes only (nothing paid or broadcast leaves the test). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Gaps2FlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }
    private val opened = mutableListOf<String>()
    private val copied = mutableListOf<String>()
    private val shared = mutableListOf<String>()
    private val host = object : AppHost {
        override fun openExternal(url: String) { opened += url }
        override fun toast(text: String) {}
        override fun copy(text: String) { copied += text }
        override fun share(text: String) { shared += text }
        override fun saveImage(url: String, done: (String?) -> Unit) {
            saved += url
            done(null)
        }
    }
    private val saved = mutableListOf<String>()

    /** Paid assist requests, as sent. */
    private val assists: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())

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
            when (r.url.substringAfter("/private-api/", "")) {
                "points" -> HttpResult(200, """{"points":"100.000","unclaimed_points":"0"}""")
                "ai-assist-price" -> HttpResult(200, """[{"action":"summarize","cost":3,"free_remaining":0}]""")
                "ai-assist" -> {
                    assists += Json.parseToJsonElement(r.body ?: "{}").jsonObject
                    HttpResult(200, """{"action":"summarize","output":"A short summary of the post.","cost":3}""")
                }
                else -> null
            }
        }
    }

    @After
    fun tearDown() = SingletonImageLoader.reset()

    private fun signIn() {
        val vault = KeyVault(node.store)
        vault.save(VaultAccount("tester", AuthType.POSTING_KEY, postingKey = node.key.toWif(), accessToken = "token-tester"))
        vault.setCurrentUsername("tester")
    }

    private fun launch(fontScale: Float = 1f) = compose.setContent {
        val d = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, fontScale)) {
            EcencyTheme(dark = false) { EcencyApp(env, host) }
        }
    }
    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = compose.await("exists($tag)") { exists(tag) }
    private fun click(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun openPost(fontScale: Float = 1f) {
        launch(fontScale)
        waitFor("open:alice/trending-one")
        compose.onNodeWithTag("open:alice/trending-one").performClick()
        waitFor("postActions")
    }

    private val longText = " " + (1..70).joinToString(" ") { "word$it" }

    @Test
    fun theRecapIsOfferedToASignedInReaderWithItsCostAndShowsTheSummary() {
        node.postBodyExtra = longText
        signIn()
        openPost()
        waitFor("readingBar")
        waitFor("recapCost")
        compose.onNodeWithTag("recapCost").assertTextEquals("3 POINTS")
        assertTrue("nothing paid before the tap", assists.isEmpty())
        click("recap")
        waitFor("recapText")
        compose.onNodeWithTag("recapText").assertTextEquals("A short summary of the post.")
        assertEquals("summarize", assists.single()["action"]!!.jsonPrimitive.content)
        assertTrue(exists("recapTryAgain"))
    }

    @Test
    fun aGuestSeesWordsAndReadingTimeButNoRecap() {
        node.postBodyExtra = longText
        openPost()
        waitFor("readingBar")
        compose.waitForIdle()
        assertTrue(!exists("recap"))
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText(UiCopy.WORDS)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun withShowImagesOffCardImagesWaitForATapOneByOne() {
        node.metadataImage = "https://images.ecency.com/DQm/{permlink}.png"
        env.notifications.setShowImages(false)
        launch()
        waitFor("hiddenImage:alice/trending-one")
        click("hiddenImage:alice/trending-one")
        compose.await("the image replaced its placeholder") { !exists("hiddenImage:alice/trending-one") }
    }

    @Test
    fun withShowImagesOnCardsShowTheirImages() {
        node.metadataImage = "https://images.ecency.com/DQm/{permlink}.png"
        launch()
        waitFor("card:alice/trending-one")
        compose.waitForIdle()
        assertTrue(!exists("hiddenImage:alice/trending-one"))
    }

    @Test
    fun theScrollTopPopupShowsWhenScrollingUpFarDownAndTakesTheListToTheTop() {
        val state = androidx.compose.foundation.lazy.LazyListState()
        compose.setContent {
            EcencyTheme(dark = false) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    androidx.compose.foundation.lazy.LazyColumn(state = state, modifier = androidx.compose.ui.Modifier.fillMaxSize().testTag("list")) {
                        items(300) { i -> androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(100.dp).testTag("row$i")) }
                    }
                    ScrollTopPopup(state)
                }
            }
        }
        compose.onNodeWithTag("list").performScrollToIndex(120)
        compose.waitForIdle()
        assertTrue("scrolling down never shows it", !exists("scrollTop"))
        compose.onNodeWithTag("list").performScrollToIndex(110)
        waitFor("scrollTop")
        click("scrollTop")
        compose.await("back at the top") { state.firstVisibleItemIndex == 0 }
        assertTrue(!exists("scrollTop"))
    }

    private fun newsletterBackend() {
        val base = node.auth.rest
        node.auth.rest = { r ->
            when {
                "/api/newsletter/subscriptions" in r.url -> HttpResult(200, """{"subscriptions":[]}""")
                "/api/newsletter/sender" in r.url -> HttpResult(200, """{"subscribers":{"weekly":2,"monthly":1}}""")
                else -> base?.invoke(r)
            }
        }
    }

    @Test
    fun theEndOfAPostOffersTheAuthorsDigestAndTheSubscribeOpensTheSheet() {
        signIn()
        newsletterBackend()
        openPost()
        compose.scrollListTo("postList", hasTestTag("digestPrompt"))
        waitFor("digestPrompt")
        click("digestPromptSubscribe")
        waitFor("digestSheet")
        assertEquals("creator", env.digest.current.type)
        assertEquals("alice", env.digest.current.target)
    }

    @Test
    fun theDigestPromptDismissalSticks() {
        signIn()
        newsletterBackend()
        openPost()
        compose.scrollListTo("postList", hasTestTag("digestPrompt"))
        click("digestPromptDismiss")
        compose.await("the card is gone") { !exists("digestPrompt") }
        assertTrue(env.prefs.getString(com.ecency.core.app.DigestTarget.dismissKey("tester", com.ecency.core.app.DigestTarget("creator", "alice"))) != null)
    }

    @Test
    fun tappingAPayoutShowsItsBreakdownWithoutSigningIn() {
        launch()
        waitFor("card:alice/trending-one")
        compose.onAllNodes(hasTestTag("payout") and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("card:alice/trending-one")))[0]
            .performSemanticsAction(SemanticsActions.OnClick)
        waitFor("payoutPopover")
        assertTrue(exists("payoutRow:${com.ecency.core.app.PayoutCopy.POTENTIAL}"))
        assertTrue(exists("payoutRow:${com.ecency.core.app.PayoutCopy.PAYOUT_DATE}"))
        // the breakdown joins once the feed price is read (half HBD, half HP at $0.25)
        waitFor("payoutRow:${com.ecency.core.app.PayoutCopy.BREAKDOWN}")
        assertTrue("no login asked", !exists("login"))
    }

    @Test
    fun thePostShowsItsViewCountAndTheStatsModalLeadsToPromote() {
        val base = node.auth.rest
        node.auth.rest = { r ->
            if (r.url.endsWith("/api/stats")) {
                HttpResult(200, """{"results":[{"metrics":[120,1534,1800,95],"dimensions":["Mobile"]}],"query":{"metrics":["visitors","visits","pageviews","visit_duration"]}}""")
            } else base?.invoke(r)
        }
        signIn()
        val navigator = com.ecency.app.ui.Navigator(com.ecency.core.app.NavState().push(com.ecency.core.app.AppScreen.Post("alice", "trending-one")))
        compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host, navigator) } }
        waitFor("postViews")
        compose.await("the count") { compose.onAllNodes(androidx.compose.ui.test.hasText("1.5K")).fetchSemanticsNodes().isNotEmpty() }
        click("postViews")
        waitFor("postStats")
        waitFor("device:Mobile")
        compose.onNodeWithTag("statsPromote").performSemanticsAction(SemanticsActions.OnClick)
        compose.await("the promote screen") { navigator.state.top == com.ecency.core.app.AppScreen.Redeem("promote", "alice/trending-one") }
    }

    @Test
    fun ecencyAndAiBadgesShowOnCardsAndThePostFooter() {
        node.metadataExtra = ""","app":"ecency/4.0.0-mobile","ai_tools":{"writing_edit":true}"""
        launch()
        waitFor("card:alice/trending-one")
        // the card is one TalkBack node: the badges sit in its unmerged tree and its summary names them
        assertTrue(compose.onAllNodes(hasTestTag("ecencySource") and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("card:alice/trending-one")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodes(hasTestTag("aiDisclosed") and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("card:alice/trending-one")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        val summary = compose.onNodeWithTag("card:alice/trending-one").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].first()
        assertTrue(summary, summary.contains(com.ecency.core.app.PostBadges.SOURCE_ECENCY) && summary.contains(com.ecency.core.app.PostBadges.AI_DISCLOSED))
        compose.onNodeWithTag("open:alice/trending-one").performClick()
        waitFor("postActions")
        compose.scrollListTo("postList", hasTestTag("postedBy"))
        assertTrue(exists("postedByEcency"))
        assertTrue(compose.onAllNodes(androidx.compose.ui.test.hasText("Posted by alice via Ecency", substring = true)).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun theSourceBadgeIsTheLabelledLogoMarkWithNoText() {
        compose.setContent { EcencyTheme(dark = true) { com.ecency.app.ui.EcencySourceBadge() } }
        val badge = compose.onNodeWithTag("ecencySource").fetchSemanticsNode()
        assertEquals(listOf(com.ecency.core.app.PostBadges.SOURCE_ECENCY), badge.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription])
        assertEquals(androidx.compose.ui.semantics.Role.Image, badge.config[androidx.compose.ui.semantics.SemanticsProperties.Role])
        assertTrue("no visible text", compose.onAllNodes(androidx.compose.ui.test.hasText("ecency", substring = true, ignoreCase = true)).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("ecencySource").assertWidthIsEqualTo(12.dp)
    }

    @Test
    fun theImageViewerSharesCopiesAndSavesTheShownImage() {
        node.postBodyExtra = """\n\n![one](https://example.com/one.png)\n\n![two](https://example.com/two.png)"""
        openPost()
        val first = hasTestTag("image") and androidx.compose.ui.test.hasContentDescription("one")
        compose.scrollListTo("postList", first)
        compose.onNode(first).performClick()
        waitFor("gallery")
        compose.onNodeWithTag("galleryPosition", useUnmergedTree = true).assertTextEquals("Preview (1/2)")
        click("galleryCopy")
        click("galleryShare")
        click("gallerySave")
        waitFor("galleryAlert")
        compose.onNodeWithTag("galleryAlert", useUnmergedTree = true).assertTextEquals(UiCopy.IMAGE_SAVED)
        val url = copied.single()
        assertTrue(url, url.startsWith("https://"))
        assertEquals(listOf(url), shared)
        assertEquals(listOf(url), saved)
    }

    @Test
    fun savedImagesGetRnsExtension() {
        assertEquals("jpg", ImageSaver.extension("image/jpeg", "https://x/a"))
        assertEquals("svg", ImageSaver.extension("image/svg+xml; charset=utf-8", "https://x/a"))
        assertEquals("webp", ImageSaver.extension("image/webp", "https://x/a.png"))
        assertEquals("gif", ImageSaver.extension("application/octet-stream", "https://x/p/a.GIF?x=1"))
        assertEquals("jpg", ImageSaver.extension(null, "https://x/noext"))
        assertEquals("Pictures/Ecency".substringAfter('/'), ImageSaver.ALBUM)
    }

    @Test
    fun theLinkSheetCopiesOrOpensTheBrowserAndHidesExploreUntilItIsBuilt() {
        val url = "https://example.org/page"
        var copies = 0
        var systems = 0
        var dismissed = 0
        compose.setContent {
            EcencyTheme(dark = false) {
                com.ecency.app.ui.LinkActionSheet(url, onCopy = { copies++ }, onExplore = {}, onSystem = { systems++ }, onDismiss = { dismissed++ })
            }
        }
        waitFor("linkSheet")
        assertEquals(com.ecency.core.app.LinkSheet.exploreScreen(url) != null, exists("linkExplore"))
        click("linkCopy")
        assertEquals(1, copies)
        click("linkSystem")
        assertEquals(1, systems)
        assertEquals(2, dismissed)
    }

    @Test
    fun repliesUnderATopLevelCommentStartCollapsedAndTheChevronOpensThem() {
        node.extra = { method, _ ->
            if (method == "bridge.get_discussion") {
                """{"alice/trending-one":${node.entry("alice", "trending-one", children = 2)},""" +
                    """"carol/re-one":${node.entry("carol", "re-one", parent = "alice" to "trending-one", children = 1).replace("\"replies\":[]", "\"replies\":[\"dave/re-two\"]")},""" +
                    """"dave/re-two":${node.entry("dave", "re-two", parent = "carol" to "re-one").replace("\"depth\":1", "\"depth\":2")}}"""
            } else null
        }
        openPost()
        compose.scrollListTo("postList", hasTestTag("comment:carol/re-one"))
        waitFor("threadToggle")
        assertTrue("collapsed by default", !exists("comment:dave/re-two"))
        click("threadToggle")
        compose.scrollListTo("postList", hasTestTag("comment:dave/re-two"))
        assertTrue(exists("comment:dave/re-two"))
        click("threadToggle")
        compose.await("collapsed again") { !exists("comment:dave/re-two") }
    }

    private fun threadOfTwo() {
        node.extra = { method, _ ->
            if (method == "bridge.get_discussion") {
                """{"alice/trending-one":${node.entry("alice", "trending-one", children = 2)},""" +
                    """"carol/re-one":${node.entry("carol", "re-one", parent = "alice" to "trending-one", children = 1).replace("\"replies\":[]", "\"replies\":[\"dave/re-two\"]")},""" +
                    """"dave/re-two":${node.entry("dave", "re-two", parent = "carol" to "re-one").replace("\"depth\":1", "\"depth\":2")}}"""
            } else null
        }
    }

    private fun inComment(tag: String) = hasTestTag(tag) and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("comment:carol/re-one"))

    private fun commentBounds(tag: String) = compose.onNode(inComment(tag), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** The comment's reply button (its comments icon and count) and the thread toggle share one line. */
    private fun assertReplyAndToggleOnOneLine() {
        val reply = commentBounds("reply")
        val toggle = commentBounds("threadToggle")
        assertTrue("toggle $toggle on the reply's line $reply", toggle.top < reply.bottom && toggle.bottom > reply.top)
    }

    @Test
    fun aCommentsIconWithItsCountIsTheReplyButtonAndTheToggleStaysOnItsLine() {
        signIn()
        threadOfTwo()
        openPost()
        compose.scrollListTo("postList", hasTestTag("comment:carol/re-one"))
        waitFor("threadToggle")
        val comment = compose.onNode(androidx.compose.ui.test.hasText("Reply") and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("comment:carol/re-one")), useUnmergedTree = true)
        comment.assertDoesNotExist()
        val reply = compose.onNode(inComment("reply"), useUnmergedTree = true).fetchSemanticsNode().config
        assertEquals(androidx.compose.ui.semantics.Role.Button, reply[androidx.compose.ui.semantics.SemanticsProperties.Role])
        assertEquals(listOf(com.ecency.core.l10n.L.post.a11yReplyHint), reply[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription])
        assertEquals(com.ecency.core.l10n.L.post.a11yComments(count = 1), reply[androidx.compose.ui.semantics.SemanticsProperties.StateDescription])
        assertReplyAndToggleOnOneLine()
        // TalkBack reads the merged header, so the reply count is in its summary
        val header = compose.onNode(hasTestTag("commentHeader") and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("comment:carol/re-one"))).fetchSemanticsNode()
        val summary = header.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString()
        assertTrue(summary, com.ecency.core.l10n.L.post.a11yComments(count = 1) in summary)
        // the caret still opens and closes the replies
        click("threadToggle")
        compose.scrollListTo("postList", hasTestTag("comment:dave/re-two"))
        assertTrue(exists("comment:dave/re-two"))
        compose.scrollListTo("postList", hasTestTag("comment:carol/re-one"))
        // the icon opens the reply input for this comment
        compose.onNode(inComment("reply"), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        waitFor("replySheet")
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun onANarrowPhoneTheCommentRowFitsOneLineAtDefaultText() {
        signIn()
        threadOfTwo()
        openPost()
        compose.scrollListTo("postList", hasTestTag("comment:carol/re-one"))
        waitFor("threadToggle")
        assertReplyAndToggleOnOneLine()
        // at least 8 dp between the reply, tip and toggle targets
        val gap = 8 * compose.activity.resources.displayMetrics.density - 0.5f
        val reply = commentBounds("reply")
        val tip = commentBounds("tip")
        val toggle = commentBounds("threadToggle")
        assertTrue("reply $reply to tip $tip", tip.left - reply.right >= gap)
        assertTrue("tip $tip to toggle $toggle", toggle.left - tip.right >= gap)
        compose.assertNothingClipped("comment row")
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun atFontScale2OnANarrowPhoneTheCommentRowWrapsWithoutClipping() {
        signIn()
        threadOfTwo()
        openPost(fontScale = 2f)
        compose.scrollListTo("postList", hasTestTag("comment:carol/re-one"))
        waitFor("threadToggle")
        val width = compose.activity.resources.displayMetrics.widthPixels.toFloat()
        for (tag in listOf("reply", "threadToggle", "vote")) {
            val box = commentBounds(tag)
            assertTrue("$tag ($box) on screen", box.left >= -1f && box.right <= width + 1f && box.width > 0f)
        }
        compose.assertNothingClipped("comment row")
    }
}
