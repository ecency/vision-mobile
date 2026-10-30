package com.ecency.l10n.gen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * In a right to left layout Compose lays out text whose style has no direction right to left, whatever it holds:
 * "@alice" shows "alice@" and an English sentence ".Hello world" (README "Localization", RTL). The app theme sets
 * `TextDirection.Content` on `LocalTextStyle` and the typography, but a `TextStyle(...)` built from scratch does not
 * inherit it, and `BasicTextField` ignores `LocalTextStyle` altogether. So every Compose `TextStyle(...)` constructor
 * in `androidApp/src/main` must name its `textDirection` (Content for text of any language, Ltr for machine text);
 * styles derived with `.copy(...)` from the theme keep the theme's. `TextStyle.Default` has no direction either, so a
 * style built from it must name one in its `.copy(...)`. And since `BasicTextField` falls back to `TextStyle.Default`,
 * every `BasicTextField(` call must pass a `textStyle`. Glance widget styles (RemoteViews) are exempt.
 */
class TextDirectionLintTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { it.resolve("src/config/locales/en-US.json").isFile }
    private val app = root.resolve("native/androidApp")
    private val sources = app.resolve("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
    private val constructor = Regex("""(?<![\w.])(?:androidx\.compose\.ui\.text\.)?TextStyle\(""")
    private val fromDefault = Regex("""(?<![\w.])(?:androidx\.compose\.ui\.text\.)?TextStyle\.Default\b""")
    private val defaultCopy = Regex("""(?<![\w.])(?:androidx\.compose\.ui\.text\.)?TextStyle\.Default\s*\.copy\(""")
    private val textField = Regex("""(?<![\w.])(?:androidx\.compose\.foundation\.text\.)?BasicTextField\(""")

    /** The argument text of every `TextStyle(` call in [src] (balanced parentheses), with its line. */
    internal fun calls(src: String): List<Pair<Int, String>> = argsOf(constructor, src)

    /**
     * Every use of `TextStyle.Default` in [src] with its line, and the arguments of its `.copy(...)` (null when it is
     * used bare, which never names a direction).
     */
    internal fun defaults(src: String): List<Pair<Int, String?>> {
        val copies = argsOf(defaultCopy, src).iterator()
        val copyStarts = defaultCopy.findAll(src).map { it.range.first }.toSet()
        return fromDefault.findAll(src).map { m ->
            val line = src.substring(0, m.range.first).count { it == '\n' } + 1
            line to (if (m.range.first in copyStarts) copies.next().second else null)
        }.toList()
    }

    /** The argument text of every `BasicTextField(` call in [src], with its line. */
    internal fun textFields(src: String): List<Pair<Int, String>> = argsOf(textField, src)

    private fun argsOf(pattern: Regex, src: String): List<Pair<Int, String>> = pattern.findAll(src).map { m ->
        var depth = 1
        var i = m.range.last + 1
        while (i < src.length && depth > 0) {
            when (src[i]) { '(' -> depth++; ')' -> depth-- }
            i++
        }
        src.substring(0, m.range.first).count { it == '\n' } + 1 to src.substring(m.range.last + 1, i - 1)
    }.toList()

    @Test
    fun everyComposeTextStyleNamesItsDirection() {
        val compose = sources.filter { !it.readText().contains("import androidx.glance.text.TextStyle") }
        val all = compose.flatMap { f -> calls(f.readText()).map { Triple(f, it.first, it.second) } }
        assertTrue(all.size > 10, "the scan found only ${all.size} TextStyle calls; did the pattern break?")
        val bad = all.filter { (_, _, args) -> !args.contains("textDirection") }
        assertTrue(
            bad.isEmpty(),
            "TextStyle without a textDirection (RTL layouts force it right to left; use TextDirection.Content, or Ltr for " +
                "machine text, or derive from LocalTextStyle.current):\n" + bad.joinToString("\n") { (f, line, _) -> "${f.relativeTo(app)}:$line" },
        )
    }

    @Test
    fun everyDefaultDerivedStyleNamesItsDirection() {
        val all = sources.flatMap { f -> defaults(f.readText()).map { Triple(f, it.first, it.second) } }
        val bad = all.filter { (_, _, args) -> args == null || !args.contains("textDirection") }
        assertTrue(
            bad.isEmpty(),
            "TextStyle.Default has no textDirection; use TextStyle.Default.copy(textDirection = TextDirection.Content, ...) " +
                "or LocalTextStyle.current:\n" + bad.joinToString("\n") { (f, line, _) -> "${f.relativeTo(app)}:$line" },
        )
    }

    @Test
    fun everyBasicTextFieldPassesATextStyle() {
        val all = sources.flatMap { f -> textFields(f.readText()).map { Triple(f, it.first, it.second) } }
        assertTrue(all.size >= 5, "the scan found only ${all.size} BasicTextField calls; did the pattern break?")
        val bad = all.filter { (_, _, args) -> !Regex("""\btextStyle\s*=""").containsMatchIn(args) }
        assertTrue(
            bad.isEmpty(),
            "BasicTextField without a textStyle falls back to TextStyle.Default, which has no textDirection; pass a " +
                "textStyle that names one:\n" + bad.joinToString("\n") { (f, line, _) -> "${f.relativeTo(app)}:$line" },
        )
    }

    @Test
    fun theDefaultAndTextFieldScansSeeTheirArguments() {
        assertTrue(defaults("val s = TextStyle.Default").single().second == null)
        assertTrue(defaults("val s = TextStyle.Default.copy(fontSize = f(2))").single().second == "fontSize = f(2)")
        assertTrue(defaults("val s = TextStyle.Default.copy(textDirection = TextDirection.Content)").single().second!!.contains("textDirection"))
        assertTrue(defaults("val s = TextStyle.Defaults").isEmpty())
        assertTrue(textFields("BasicTextField(v, { }, singleLine = true)").single().second.contains("singleLine"))
        assertTrue(textFields("BasicTextField(v, { }, textStyle = s.copy(color = c))").single().second.contains("textStyle"))
        assertTrue(textFields("MyBasicTextField(v)").isEmpty())
    }

    @Test
    fun theScanSeesNestedArguments() {
        assertTrue(calls("val s = TextStyle(color = c(1), fontSize = 2.sp)").single().second.contains("fontSize"))
        assertTrue(calls("x.copy(TextStyle())").single().second.isEmpty())
        assertTrue(calls("val t = LocalTextStyle.current.copy(textDirection = d)").isEmpty())
    }
}
