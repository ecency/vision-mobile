package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ecency.app.ui.horizontalEdgeFade
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A sideways strip fades out at an edge with more to scroll to, so a cut off pill reads as "scroll for more"
 * (a tester read the Waves tag tabs cut at the "+" as tags with incomplete names).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EdgeFadeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var scroll: ScrollState

    /** A 200 dp strip over white, its content a red bar five times as wide. */
    private fun strip(direction: LayoutDirection) {
        compose.setContent {
            scroll = androidx.compose.foundation.rememberScrollState()
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(Modifier.background(Color.White)) {
                    Row(Modifier.width(200.dp).horizontalEdgeFade(scroll).horizontalScroll(scroll)) {
                        Box(Modifier.size(1000.dp, 40.dp).background(Color.Red))
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** How red the pixel is at [xDp] in the strip (255 drawn in full, 0 faded to the white behind). */
    private fun redness(xDp: Float): Int {
        val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
        val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(image)) }
        val density = compose.activity.resources.displayMetrics.density
        val pixel = image.getPixel((xDp * density).toInt(), (20 * density).toInt())
        // red keeps green at 0, white has it at 255
        return 255 - android.graphics.Color.green(pixel)
    }

    private fun scrollToEnd() {
        compose.runOnUiThread { runBlocking { scroll.scrollTo(scroll.maxValue) } }
        compose.waitForIdle()
    }

    private fun assertFaded(where: String, xDp: Float) = redness(xDp).let { assertTrue("$where faded ($it)", it < 60) }
    private fun assertDrawn(where: String, xDp: Float) = redness(xDp).let { assertTrue("$where drawn in full ($it)", it > 240) }

    @Test
    fun atTheStartOnlyTheEndFades() {
        strip(LayoutDirection.Ltr)
        assertDrawn("the start", 1f)
        assertDrawn("the middle", 100f)
        assertFaded("the end", 199f)
    }

    @Test
    fun atTheEndOnlyTheStartFades() {
        strip(LayoutDirection.Ltr)
        scrollToEnd()
        assertFaded("the start", 1f)
        assertDrawn("the middle", 100f)
        assertDrawn("the end", 199f)
    }

    @Test
    fun rightToLeftTheFadeFollowsTheScrollDirection() {
        strip(LayoutDirection.Rtl)
        // the start is on the right: more to scroll toward the left
        assertDrawn("the start (right)", 199f)
        assertFaded("the end (left)", 1f)
        scrollToEnd()
        assertFaded("the start (right)", 199f)
        assertDrawn("the end (left)", 1f)
    }

    @Test
    fun aStripThatFitsDoesNotFade() {
        compose.setContent {
            scroll = androidx.compose.foundation.rememberScrollState()
            Box(Modifier.background(Color.White)) {
                Row(Modifier.width(200.dp).horizontalEdgeFade(scroll).horizontalScroll(scroll)) {
                    Box(Modifier.size(200.dp, 40.dp).background(Color.Red))
                }
            }
        }
        compose.waitForIdle()
        assertDrawn("the start", 1f)
        assertDrawn("the end", 199f)
    }
}
