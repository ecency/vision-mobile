package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyColors
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.Navigator
import com.ecency.core.app.AppScreen
import com.ecency.core.app.RouteName
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Empty states sit on the theme background (no block of another colour around them), dark and light. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemedStatesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }
    private val navigator = Navigator()
    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
    }

    private fun idleMain() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun searchPeopleEmpty(dark: Boolean) {
        acceptTerms()
        // the app picks its theme from the settings, over any EcencyTheme around it
        env.settings.setTheme(if (dark) com.ecency.core.app.ThemeMode.DARK else com.ecency.core.app.ThemeMode.LIGHT)
        compose.setContent { EcencyTheme(dark = dark) { EcencyApp(env, host, navigator) } }
        compose.runOnUiThread { navigator.push(AppScreen.of(RouteName.SEARCH_RESULT)) }
        compose.await("search tabs") { idleMain(); compose.onAllNodes(hasTestTag("searchTab:PEOPLE")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("searchTab:PEOPLE").performClick()
        compose.await("Nothing here") { idleMain(); compose.onAllNodes(hasText("Nothing here"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        val want = (if (dark) EcencyColors.Dark else EcencyColors.Light).background.toArgb()
        val message = compose.onNode(hasText("Nothing here"), useUnmergedTree = true).getUnclippedBoundsInRoot()
        // the content view drawn into a bitmap (captureToImage waits for a frame the paused looper never draws)
        val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
        val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(image)) }
        val density = compose.activity.resources.displayMetrics.density
        fun at(xDp: Float, yDp: Float) = image.getPixel((xDp * density).toInt().coerceIn(0, image.width - 1), (yDp * density).toInt().coerceIn(0, image.height - 1))
        val side = "dark=$dark"
        assertEquals("beside the empty state ($side)", Integer.toHexString(want), Integer.toHexString(at(4f, message.top.value + 4)))
        assertEquals("below the empty state ($side)", Integer.toHexString(want), Integer.toHexString(at(image.width / density / 2, image.height / density - 80)))
        // no divider under the state
        var y = message.bottom.value + 1
        while (y < message.bottom.value + 40) {
            assertEquals("no line under the empty state at $y ($side)", Integer.toHexString(want), Integer.toHexString(at(image.width / density - 30, y)))
            y += 0.5f
        }
    }

    @Test
    fun searchPeopleEmptyOnTheDarkTheme() = searchPeopleEmpty(dark = true)

    @Test
    fun searchPeopleEmptyOnTheLightTheme() = searchPeopleEmpty(dark = false)
}
