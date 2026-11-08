package com.ecency.a11y

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * With gesture navigation a drag that starts at the screen's left or right edge is the system back gesture. A Slider
 * whose thumb rests at 0% or 100% sits there, so the drag closed the screen (the vote sheet at 1% or 100%) and moved
 * nothing. Every Slider of the Android app names `.edgeSwipeSafe()` (ui/EdgeGestures.kt, `systemGestureExclusion`).
 * The scan reads source text with balanced brackets, no compiler.
 */
class SliderGestureLintTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { it.resolve("src/config/locales/en-US.json").isFile }
    private val sources = root.resolve("androidApp/src/main/kotlin/com/ecency/app").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    private fun balanced(src: String, open: Int): String {
        var depth = 0
        var i = open
        var inString = false
        while (i < src.length) {
            val c = src[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return src.substring(open + 1, i) }
            }
            i++
        }
        return src.substring(open + 1)
    }

    private fun sliderCalls(src: String): List<Pair<Int, String>> =
        // any call form: plain, qualified (material3.Slider), spaced, and the range slider
        Regex("""(?<!\w)(?:Range)?Slider\s*\(""").findAll(src).filter { m ->
            val before = src.substring(src.lastIndexOf('\n', m.range.first) + 1, m.range.first).trimStart()
            !before.startsWith("import ") && !before.startsWith("//") && !before.startsWith("*")
        }.map { src.substring(0, it.range.first).count { c -> c == '\n' } + 1 to balanced(src, it.range.last) }.toList()

    @Test
    fun theScanFindsEveryCallForm() {
        val src = """
            import androidx.compose.material3.Slider
            // Slider( in a comment
            Slider(value = 1f, onValueChange = {})
            androidx.compose.material3.Slider (value = 1f, onValueChange = {})
            RangeSlider(value = 0f..1f, onValueChange = {}, modifier = Modifier.edgeSwipeSafe())
            SliderDefaults.colors()
        """.trimIndent()
        assertEquals(listOf(3, 4, 5), sliderCalls(src).map { it.first })
    }

    @Test
    fun everySliderIsExcludedFromTheBackGesture() {
        val all = sources.flatMap { f -> sliderCalls(f.readText()).map { (l, args) -> Triple(f.name, l, args) } }
        assertTrue(all.size >= 4, "the scan found the app's sliders")
        val bad = all.filter { (_, _, args) -> ".edgeSwipeSafe()" !in args && "Modifier.edgeSwipeSafe()" !in args }.map { (n, l, _) -> "$n:$l" }
        assertEquals(emptyList(), bad, "a Slider names Modifier.edgeSwipeSafe(): a drag from the screen edge is the back gesture")
    }
}
