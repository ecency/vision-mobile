package com.ecency.app

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
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

/** The launch screen in the running activity (README "Launch screen"): bar style after removal, recreation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LaunchSplashActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val node = TestNode()
    private val env by lazy { node.env() }

    @Before
    fun setUp() {
        acceptTerms()
        EcencyGraph.install(env)
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
        EcencyGraph.install(null)
        SingletonImageLoader.reset()
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun launched(): ActivityScenario<MainActivity> {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.await("exists(\"tabBar\")") { idle(); compose.onAllNodes(hasTestTag("tabBar"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        return scenario
    }

    /** PhoneWindow's decor-fits flag (no public getter before API 35's). */
    private fun decorFits(window: android.view.Window): Boolean {
        var c: Class<*>? = window.javaClass
        while (c != null) {
            runCatching { return c!!.getDeclaredField("mDecorFitsSystemWindows").apply { isAccessible = true }.getBoolean(window) }
            c = c.superclass
        }
        error("no decor-fits field")
    }

    /** What core-splashscreen does to the window: before calling the exit listener and in remove(). */
    private fun clobber(activity: MainActivity) {
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        bars.isAppearanceLightStatusBars = false
        bars.isAppearanceLightNavigationBars = false
        WindowCompat.setDecorFitsSystemWindows(activity.window, true)
    }

    private fun assertAppBars(activity: MainActivity, moment: String) {
        val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        assertTrue("$moment: status bar icons dark on the light page", bars.isAppearanceLightStatusBars)
        assertTrue("$moment: navigation bar icons dark", bars.isAppearanceLightNavigationBars)
        assertFalse("$moment: edge to edge", decorFits(activity.window))
    }

    @Test
    fun theSplashExitKeepsTheAppsBarsThroughTheFadeAndAfterRemoval() {
        val scenario = launched()
        scenario.onActivity { activity ->
            assertAppBars(activity, "launched")
            // the library's entry clobber, then our listener
            clobber(activity)
            var removed = 0
            activity.onSplashExit(android.view.View(activity)) { removed++; clobber(activity) }
            assertAppBars(activity, "during the fade")
            assertEquals(0, removed)
        }
        // the fade ends: remove() clobbers again (Android 12+), finishSplash puts the bars back
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(com.ecency.core.app.MotionTokens.SPLASH_FADE_MS + 100L))
        scenario.onActivity { activity -> assertAppBars(activity, "after removal") }
        scenario.close()
    }

    @Test
    fun androidTwelveAndLaterAnimateTheSplashOutOnARestoreToo() {
        val scenario = launched()
        scenario.onActivity { assertTrue(it.splashAnimated) }
        // a recreation with saved state (as a restore after process death): the system shows its splash, so it fades
        scenario.recreate()
        idle()
        scenario.onActivity { assertTrue(it.splashAnimated) }
        scenario.close()
    }

    @Test
    @Config(sdk = [30])
    fun beforeAndroidTwelveARecreationBuildsNoSplash() {
        val scenario = launched()
        scenario.onActivity { assertTrue(it.splashAnimated) }
        // dark mode, font scale, multi-window: installed, but no splash view built again
        scenario.recreate()
        idle()
        scenario.onActivity { assertFalse(it.splashAnimated) }
        scenario.close()
    }
}
