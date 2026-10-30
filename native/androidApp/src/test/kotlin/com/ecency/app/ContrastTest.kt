package com.ecency.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.ecency.app.render.PostTheme
import com.ecency.app.ui.EcencyColors
import org.junit.Assert.assertTrue
import org.junit.Test

/** The theme's text and icon tokens meet WCAG AA in light and dark (README "Accessibility"). */
class ContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance().toDouble(), b.luminance().toDouble()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun check(failures: MutableList<String>, name: String, fg: Color, bg: Color, min: Double) {
        val r = ratio(fg, bg)
        if (r < min) failures += "$name: ${"%.2f".format(r)} < $min"
    }

    @Test
    fun textAndIconTokensMeetWcagAa() {
        val failures = mutableListOf<String>()
        for ((theme, c) in listOf("light" to EcencyColors.Light, "dark" to EcencyColors.Dark)) {
            for ((bgName, bg) in listOf("background" to c.background, "lightBackground" to c.lightBackground, "grayBackground" to c.grayBackground)) {
                check(failures, "$theme text on $bgName", c.text, bg, 4.5)
                check(failures, "$theme darkGray on $bgName", c.darkGray, bg, 4.5)
                check(failures, "$theme darkText on $bgName", c.darkText, bg, 4.5)
                check(failures, "$theme blueText on $bgName", c.blueText, bg, 4.5)
                check(failures, "$theme redText on $bgName", c.redText, bg, 4.5)
                // icons that carry meaning: 3:1 (WCAG 1.4.11)
                check(failures, "$theme icon on $bgName", c.icon, bg, 3.0)
                // outlines that show state (focused fields, selected chips): 3:1 (WCAG 1.4.11)
                check(failures, "$theme blueText border on $bgName", c.blueText, bg, 3.0)
                check(failures, "$theme redText border on $bgName", c.redText, bg, 3.0)
            }
            // white labels on the blue and red fills (buttons, badges)
            check(failures, "$theme white on blue", Color.White, c.blue, 4.5)
            check(failures, "$theme white on red", Color.White, c.red, 4.5)
        }
        for ((theme, p) in listOf("light" to PostTheme.Light, "dark" to PostTheme.Dark)) {
            check(failures, "$theme post text", p.text, p.background, 4.5)
            check(failures, "$theme post link", p.link, p.background, 4.5)
            check(failures, "$theme post danger", p.danger, p.background, 4.5)
        }
        assertTrue("contrast below WCAG AA:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun theRatioIsWcags() {
        assertTrue(ratio(Color.Black, Color.White) in 20.99..21.01)
        assertTrue(ratio(Color(0xFF777777), Color.White) in 4.47..4.49)
    }
}
