package com.ecency.app

import android.graphics.drawable.VectorDrawable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.ecency.app.ui.Glyph
import com.ecency.app.ui.drawableRes
import com.ecency.app.ui.filledDrawableRes
import com.ecency.core.app.AppIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File

/** Every [AppIcon] resolves to a 24 dp Material Symbols drawable (or the Waves mark), as scripts/icons/icons.tsv lists. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppIconTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** name -> (material, material filled) from the shared list. */
    private val list: Map<String, Pair<String, String>> = File("../scripts/icons/icons.tsv").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }.associate { it[0] to (it[1] to it[2]) }

    private fun viewport(res: Int): Pair<Float, Float> {
        val xml = context.resources.getXml(res)
        while (xml.next() != XmlPullParser.START_TAG) Unit
        assertEquals("vector", xml.name)
        fun attr(name: String) = (0 until xml.attributeCount).first { xml.getAttributeName(it) == name }.let { xml.getAttributeValue(it) }
        return attr("viewportWidth").toFloat() to attr("viewportHeight").toFloat()
    }

    private fun checkDrawable(icon: AppIcon, res: Int) {
        val d = ContextCompat.getDrawable(context, res)
        assertTrue("$icon is a vector", d is VectorDrawable)
        val px = 24 * context.resources.displayMetrics.density
        assertEquals("$icon is 24 dp wide", px, d!!.intrinsicWidth.toFloat(), 1f)
        assertEquals("$icon is 24 dp tall", px, d.intrinsicHeight.toFloat(), 1f)
        val (w, h) = viewport(res)
        assertEquals("$icon has a square viewport", w, h)
        assertTrue("$icon keeps the source's viewport (960, or 24 for the older symbols)", w == 960f || w == 24f)
        assertEquals("$icon mirrors in RTL only when directional", icon.isDirectional, d.isAutoMirrored)
    }

    @Test
    fun everyIconIsMappedAndListed() {
        assertEquals(AppIcon.entries.map { it.name }.toSet(), list.keys)
        AppIcon.entries.forEach { icon ->
            val (material, filled) = list.getValue(icon.name)
            if (material == "custom") {
                assertNull(icon.drawableRes())
            } else {
                checkDrawable(icon, checkNotNull(icon.drawableRes()) { "$icon is mapped" })
            }
            val fill = icon.filledDrawableRes()
            assertEquals("$icon has a filled drawable exactly when the list names one", filled != "-", fill != null)
            fill?.let { checkDrawable(icon, it) }
            assertEquals("$icon: drawable names follow the entry", "ic_${icon.name.lowercase()}", icon.drawableRes()?.let(context.resources::getResourceEntryName) ?: "ic_${icon.name.lowercase()}")
        }
    }

    @Test
    fun everyIconDrawsOutlinedAndFilledWithItsDescription() {
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                AppIcon.entries.forEach { Glyph(it, Color.Black, description = it.name) }
                Glyph(AppIcon.HEART, Color.Red, description = "filled heart", filled = true)
                Glyph(AppIcon.WAVES, Color.Blue, description = "filled waves", filled = true)
            }
        }
        compose.onNodeWithContentDescription("WAVES").assertExists()
        compose.onNodeWithContentDescription("HOME").assertExists()
        compose.onNodeWithContentDescription("filled heart").assertExists()
        compose.onNodeWithContentDescription("filled waves").assertExists()
    }
}
