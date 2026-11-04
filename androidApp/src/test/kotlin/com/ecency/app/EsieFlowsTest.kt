package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.EsieMessageBlock
import com.ecency.app.ui.EsieAnimatesKey
import com.ecency.app.ui.EsieMoodKey
import com.ecency.app.ui.LocalMotion
import com.ecency.app.ui.Motion
import com.ecency.core.app.MotionLevel
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasText
import com.ecency.app.ui.EsieView
import com.ecency.app.ui.Navigator
import com.ecency.app.ui.ShellErrorScreen
import com.ecency.core.app.AppScreen
import com.ecency.core.app.NavState
import com.ecency.core.esie.EsieMood
import com.ecency.core.esie.EsiePlacement
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
import org.robolectric.shadows.ShadowLooper

/** Esie at her placements (README "Esie"): resting on empty lists, sad on failures, waving on welcome and login. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EsieFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }
    private var terms = true

    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
        override fun termsAccepted() = terms
        override fun setTermsAccepted() { terms = true }
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
        SingletonImageLoader.reset()
        EcencyGraph.install(null)
    }

    private fun launch(navigator: Navigator = Navigator()) = compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host, navigator) } }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** The moods of every Esie on screen. */
    private fun moods() = compose.onAllNodes(hasTestTag("esie"), useUnmergedTree = true).fetchSemanticsNodes().map { it.config[EsieMoodKey] }

    private fun until(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            compose.waitForIdle()
            if (condition()) return
            if (System.currentTimeMillis() > end) throw AssertionError("condition not met in 10 s")
            ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
    }

    @Test
    fun anEmptyFeedShowsEsieResting() {
        node.extra = { method, _ -> if (method == "bridge.get_ranked_posts") "[]" else null }
        launch()
        until { moods().isNotEmpty() }
        assertEquals(listOf(EsieMood.REST), moods())
        assertTrue(exists("message"))
    }

    @Test
    fun aFeedThatFailsToLoadShowsEsieSad() {
        node.feedDown = true
        launch()
        until { moods().isNotEmpty() }
        assertEquals(listOf(EsieMood.SAD), moods())
        assertTrue(exists("retry"))
    }

    @Test
    fun aLoadedFeedHasNoEsie() {
        launch()
        until { exists("votes") }
        assertTrue(moods().isEmpty())
    }

    @Test
    fun welcomeWaves() {
        terms = false
        launch()
        until { exists("welcome") }
        assertEquals(listOf(EsieMood.WAVE), moods())
        assertFalse("the old mascot image is gone", exists("welcomeMascot"))
    }

    @Test
    fun loginWaves() {
        launch(Navigator(NavState().push(AppScreen.Login)))
        until { exists("login") }
        assertEquals(listOf(EsieMood.WAVE), moods())
    }

    @Test
    fun theErrorScreenIsSadAndEsieIsDecorative() {
        compose.setContent { EcencyTheme(dark = false) { ShellErrorScreen(onRetry = {}) } }
        assertEquals(listOf(EsieMood.SAD), moods())
        val config = compose.onNodeWithTag("esie", useUnmergedTree = true).fetchSemanticsNode().config
        assertTrue("hidden from TalkBack", SemanticsProperties.HideFromAccessibility in config)
        assertFalse(SemanticsProperties.ContentDescription in config)
    }

    @Test
    fun welcomeEsieLeavesTheTitleClearOnANarrowPhone() = narrowWelcome()

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun welcomeEsieShrinksOnA320dpPhone() = narrowWelcome()

    private fun narrowWelcome() {
        terms = false
        launch()
        until { exists("welcome") }
        val esie = compose.onNodeWithTag("esie", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val title = compose.onAllNodes(hasText(com.ecency.core.app.WelcomeCopy.TITLE), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot
        val screen = compose.onNodeWithTag("welcome", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Esie ($esie) clear of the title ($title)", esie.left >= title.right)
        // Robolectric measures text narrow, so also hold the rule itself: at most 40 % of the content width
        assertTrue("Esie ($esie) at most 40 % of $screen", esie.width <= 0.4f * screen.width + 1)
    }

    @Test
    fun welcomeLeavesEsieOutAtLargeTextSizes() {
        terms = false
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale = 2f)) {
                EcencyTheme(dark = false) { EcencyApp(env, host, Navigator()) }
            }
        }
        until { exists("welcome") }
        assertTrue("no Esie at font scale 2", moods().isEmpty())
        assertTrue(com.ecency.app.ui.welcomeShowsEsie(1.3f))
        assertFalse(com.ecency.app.ui.welcomeShowsEsie(1.5f))
    }

    @Test
    fun reducedMotionHoldsAStillPoseAndATalkingMouthStillSwaps() {
        compose.setContent {
            EcencyTheme(dark = false) {
                androidx.compose.foundation.layout.Column {
                    for (level in listOf(MotionLevel.FULL, MotionLevel.REDUCED, MotionLevel.NONE)) {
                        CompositionLocalProvider(LocalMotion provides Motion(level)) { EsieView(EsieMood.REST) }
                    }
                    CompositionLocalProvider(LocalMotion provides Motion(MotionLevel.REDUCED)) { EsieView(EsieMood.SAD, talking = true) }
                }
            }
        }
        val animates = compose.onAllNodes(hasTestTag("esie"), useUnmergedTree = true).fetchSemanticsNodes().map { it.config[EsieAnimatesKey] }
        assertEquals(listOf(true, false, false, true), animates)
    }

    @Test
    fun aLabelMakesEsieAnImage() {
        compose.setContent {
            EcencyTheme(dark = false) {
                androidx.compose.foundation.layout.Column {
                    EsieView(EsiePlacement.SUCCESS, label = "Esie cheering")
                    EsieMessageBlock(EsiePlacement.EMPTY, "Nothing here")
                }
            }
        }
        val nodes = compose.onAllNodes(hasTestTag("esie"), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(listOf(EsieMood.HAPPY, EsieMood.REST), nodes.map { it.config[EsieMoodKey] })
        assertEquals(listOf("Esie cheering"), nodes[0].config[SemanticsProperties.ContentDescription])
        assertTrue(SemanticsProperties.HideFromAccessibility in nodes[1].config)
    }
}
