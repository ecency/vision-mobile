package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.Navigator
import com.ecency.core.app.AppScreen
import com.ecency.core.app.NavState
import com.ecency.core.app.RcErrors
import com.ecency.core.app.RouteName
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

/** RN parity gaps (wave gaps3) on Android, against fakes only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Gaps3FlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }
    private val opened = mutableListOf<String>()

    private val host = object : AppHost {
        override fun openExternal(url: String) { opened += url }
        override fun toast(text: String) {}
        override fun copy(text: String) {}
        override fun share(text: String) {}
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

    private fun signIn(vararg names: String = arrayOf("tester")) {
        val vault = KeyVault(node.store)
        names.forEach { vault.save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = node.key.toWif(), accessToken = "access-$it")) }
        vault.setCurrentUsername(names.last())
    }

    private fun launch(navigator: Navigator = Navigator()) = compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host, navigator) } }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun until(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            compose.waitForIdle()
            if (condition()) return
            if (System.currentTimeMillis() > end) throw AssertionError("condition not met in 10 s")
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
    }

    private fun waitFor(tag: String) = until { exists(tag) }

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun top(navigator: Navigator) = navigator.state.stack.lastOrNull()?.screen

    // ---- app-wide RC offer ----

    @Test
    fun theRcOfferShowsOverAnyScreenAndTopUpOpensThePointsTopUp() {
        signIn()
        val navigator = Navigator(NavState().push(AppScreen.of(RouteName.SETTINGS)))
        launch(navigator)
        compose.runOnIdle { env.rcOffer.raise() }
        waitFor("rcOffer")
        assertEquals(RcErrors.OFFER_BODY, text("rcOffer"))
        compose.onNodeWithTag("rcTopUp").performClick()
        until { !exists("rcOffer") }
        until { top(navigator) == AppScreen.of(RouteName.REDEEM, RcErrors.TOP_UP_REDEEM_TYPE) }
        assertFalse(env.rcOffer.current.open)
    }

    @Test
    fun boostOpensTheBoostAndCancelOpensNothing() {
        signIn()
        val navigator = Navigator()
        launch(navigator)
        compose.runOnIdle { env.rcOffer.raise() }
        waitFor("rcBoost")
        compose.onNodeWithTag("rcBoost").performClick()
        until { top(navigator) == AppScreen.of(RouteName.ACCOUNT_BOOST) }
        val depth = navigator.state.stack.size
        compose.runOnIdle { env.rcOffer.raise() }
        waitFor("rcCancel")
        compose.onNodeWithTag("rcCancel").performClick()
        until { !exists("rcOffer") }
        assertEquals(depth, navigator.state.stack.size)
    }

    // ---- feature spotlight ----

    private fun spotlights(link: String) {
        node.auth.rest = { r ->
            if (r.url.endsWith("/private-api/spotlights")) {
                com.ecency.core.api.rpc.HttpResult(200, """[{"id":"s1","feature":"f","title":"Try Waves","description":"Short posts","button_text":"Open","button_link":"$link","platforms":["mobile"]}]""")
            } else {
                null
            }
        }
    }

    @Test
    fun theSpotlightHeadsTheFirstFeedTabAndDismissIsRemembered() {
        signIn()
        spotlights("/@alice/post")
        launch()
        waitFor("spotlight:s1")
        assertEquals("Try Waves", text("spotlightTitle"))
        compose.onNodeWithTag("spotlightDismiss").performClick()
        until { !exists("spotlight:s1") }
        assertEquals("1", env.prefs.getString(com.ecency.core.app.SpotlightModel.DISMISSED_PREFIX + "s1_tester"))
    }

    @Test
    fun theSpotlightButtonOpensItsLinkAndGoesAway() {
        signIn()
        spotlights("https://example.com/feature")
        launch()
        waitFor("spotlightAction")
        compose.onNodeWithTag("spotlightAction").performClick()
        until { opened == listOf("https://example.com/feature") }
        until { !exists("spotlight:s1") }
    }

    // ---- walkthrough marker ----

    @Test
    fun theCustomiseButtonCarriesARedDotUntilItsFirstUse() {
        signIn()
        launch()
        waitFor("walkthroughMarker:customise")
        // the dot sits on the "+" glyph, inside the pill bar (it hung off the button's corner before)
        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val dot = bounds("walkthroughMarker:customise")
        val glyph = bounds("customiseGlyph")
        val bar = bounds("filterBar")
        assertTrue("on the glyph: $dot in $glyph", dot.left >= glyph.left && dot.right <= glyph.right + 0.5f && dot.top >= glyph.top - 0.5f && dot.bottom <= glyph.bottom)
        assertTrue("inside the bar: $dot in $bar", dot.top >= bar.top && dot.bottom <= bar.bottom)
        compose.onNodeWithTag("customise").performClick()
        until { !exists("walkthroughMarker:customise") }
        assertEquals("2", env.prefs.getString(com.ecency.core.app.WalkthroughModel.KEY))
    }

    @Test
    fun signedOutTheCustomiseButtonHasNoDot() {
        launch()
        waitFor("customise")
        assertFalse(exists("walkthroughMarker:customise"))
    }

    // ---- reply editor account tile ----

    @Test
    fun theReplyEditorsAccountTileOpensTheAccountsSheet() {
        signIn("other", "tester")
        val args = com.ecency.core.app.EditorArgs(com.ecency.core.app.EditorKind.REPLY, "alice", "trending-one")
        launch(Navigator(NavState().push(AppScreen.Editor(args))))
        waitFor("replyAccountTile")
        compose.onNodeWithTag("replyAccountTile").performClick()
        waitFor("accountSwitcher")
        until { !env.replyHandoff.current.askAccounts }
    }

    // ---- uploads gallery ----

    @Test
    fun theUploadsGalleryOffersAddingWithoutInserting() {
        signIn()
        launch(Navigator(NavState().push(AppScreen.Editor())))
        waitFor("toolImage")
        compose.onNodeWithTag("toolImage").performClick()
        waitFor("uploadsSheet")
        waitFor("addToGallery")
    }
}
