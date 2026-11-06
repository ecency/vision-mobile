package com.ecency.app

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.LocalMotion
import com.ecency.app.ui.Motion
import com.ecency.app.ui.Navigator
import com.ecency.core.app.LaunchReveal
import com.ecency.core.app.MotionLevel
import com.ecency.core.app.WelcomeCopy
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

/** RN's welcome art (#3636, README "Welcome and terms, register"): the line icons and the launch animation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class WelcomeFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }

    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
        override fun termsAccepted() = false
        override fun setTermsAccepted() {}
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
        Settings.Global.putFloat(compose.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private fun launch(level: MotionLevel? = null) = compose.setContent {
        EcencyTheme(dark = false) {
            if (level == null) EcencyApp(env, host, Navigator())
            else CompositionLocalProvider(LocalMotion provides Motion(level)) { EcencyApp(env, host, Navigator()) }
        }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Frame by frame (the clock is held) until the welcome screen shows: the accounts are read first. */
    private fun untilWelcome() = compose.await("the welcome screen") {
        exists("welcome").also { if (!it) compose.mainClock.advanceTimeByFrame() }
    }

    @Test
    fun eachLineHasRnsIconBesideIt() {
        launch(MotionLevel.NONE)
        compose.await("the welcome screen") { exists("welcome") }
        compose.waitForIdle()
        WelcomeCopy.LINES.forEachIndexed { i, (heading, _) ->
            val icons = compose.onAllNodes(hasTestTag("welcomeIcon:$i"), useUnmergedTree = true).fetchSemanticsNodes()
            assertEquals("one icon beside line $i", 1, icons.size)
            val icon = icons.single().boundsInRoot
            val text = compose.onAllNodes(hasText(heading), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot
            assertEquals("RN's 30 dp icon", 30f * compose.density.density, icon.width, 0.5f)
            assertTrue("icon $i ($icon) sits before its heading ($text)", icon.right <= text.left && icon.top <= text.bottom && icon.bottom >= text.top)
        }
    }

    @Test
    fun theLaunchAnimationCoversTheWelcomeScreenForRnsThreeAndAHalfSeconds() {
        compose.mainClock.autoAdvance = false
        launch()
        untilWelcome()
        assertTrue("the animation covers the welcome screen", exists("welcomeLaunch"))
        assertTrue(exists("welcome"))
        compose.mainClock.advanceTimeBy(LaunchReveal.DURATION_MS - 200)
        assertTrue("still drawing at 3.35 s", exists("welcomeLaunch"))
        compose.mainClock.advanceTimeBy(400)
        assertFalse("gone after 3.55 s", exists("welcomeLaunch"))
        assertTrue(exists("welcome"))
    }

    @Test
    fun removeAnimationsSkipsIt() {
        // Android's reduce motion: "Remove animations" sets the animator duration scale to 0
        Settings.Global.putFloat(compose.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.mainClock.autoAdvance = false
        launch()
        untilWelcome()
        assertTrue(exists("welcome"))
        assertFalse(exists("welcomeLaunch"))
    }

    @Test
    fun reducedMotionSkipsIt() {
        compose.mainClock.autoAdvance = false
        launch(MotionLevel.REDUCED)
        untilWelcome()
        assertTrue(exists("welcome"))
        assertFalse(exists("welcomeLaunch"))
    }
}
