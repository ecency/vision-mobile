package com.ecency.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.TypedValue
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.ecency.core.app.MotionTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** The launch screen (README "Launch screen"): the activity's starting theme and the fade out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LaunchSplashTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** [attr] of the launcher activity's own theme, resolved in the current configuration. */
    private fun themeValue(attr: Int): TypedValue {
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), PackageManager.GET_META_DATA)
        val theme = context.resources.newTheme().apply { applyStyle(info.theme, true) }
        return TypedValue().also { assertTrue("theme has the attribute", theme.resolveAttribute(attr, it, true)) }
    }

    @Test
    fun theLauncherStartsOnTheLogoOverThePageColour() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(R.style.Theme_Ecency_Starting, info.theme)
        assertEquals(R.drawable.splash_logo, themeValue(androidx.core.splashscreen.R.attr.windowSplashScreenAnimatedIcon).resourceId)
        assertEquals(0xFFFFFFFF.toInt(), themeValue(androidx.core.splashscreen.R.attr.windowSplashScreenBackground).data)
        // after the splash: the app's window theme, on the page colour (README "Themed backgrounds")
        assertEquals(R.style.Theme_Ecency, themeValue(androidx.core.splashscreen.R.attr.postSplashScreenTheme).resourceId)
        assertEquals(0xFFFFFFFF.toInt(), windowBackground())
    }

    /** The window background of the app's window theme, resolved in the current configuration. */
    private fun windowBackground(): Int {
        val theme = context.resources.newTheme().apply { applyStyle(R.style.Theme_Ecency, true) }
        val value = TypedValue().also { assertTrue("theme has a window background", theme.resolveAttribute(android.R.attr.windowBackground, it, true)) }
        return value.data
    }

    @Test
    @Config(qualifiers = "night")
    fun inDarkModeTheBackgroundIsTheDarkPageColour() {
        assertEquals(0xFF1E2835.toInt(), themeValue(androidx.core.splashscreen.R.attr.windowSplashScreenBackground).data)
        assertEquals(0xFF1E2835.toInt(), windowBackground())
    }

    @Test
    fun theSplashFadesOutLikeRnsBootsplash() {
        assertEquals(MotionTokens.SPLASH_FADE_MS, LaunchSplash.fadeMillis(1f))
        val view = View(context)
        var removed = 0
        LaunchSplash.fadeOut(view, 1f) { removed++ }
        assertEquals("still fading", 0, removed)
        ShadowLooper.idleMainLooper(MotionTokens.SPLASH_FADE_MS.toLong() + 50, TimeUnit.MILLISECONDS)
        assertEquals(1, removed)
        assertEquals(0f, view.alpha)
    }

    @Test
    fun animationsOffRemovesItAtOnce() {
        assertEquals(0, LaunchSplash.fadeMillis(0f))
        var removed = 0
        LaunchSplash.fadeOut(View(context), 0f) { removed++ }
        assertEquals(1, removed)
    }
}
