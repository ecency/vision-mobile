package com.ecency.a11y

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * TalkBack basics the Android screens must keep (README "Accessibility", Android):
 * - every `clickable` / `combinedClickable` / `toggleable` / `selectable` names its `role` (Button, Tab, Checkbox...), so TalkBack says what a tap does;
 * - a tappable `Box` / `Row` / `Column` / `Surface` / `IconButton` whose content is only an icon (a `Glyph`, `Icon`,
 *   `Image` or `AsyncImage`, no `Text`) carries a label: the icon's `description` / `contentDescription`, or a
 *   `contentDescription` in its own semantics;
 * - a `Glyph` or `Icon` that is itself clickable has a description;
 * - text and icons never take the `blue` / `red` fill tokens (they fall under WCAG AA in dark mode): `blueText` /
 *   `redText` instead; the fill tokens stay for backgrounds, borders and button containers.
 * Glance widgets (RemoteViews) are exempt. The scan reads source text with balanced brackets, no compiler.
 */
class A11yLintTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { it.resolve("src/config/locales/en-US.json").isFile }
    private val app = root.resolve("native/androidApp")
    private val sources = app.resolve("src/main").walkTopDown()
        .filter { it.isFile && it.extension == "kt" && !it.path.contains("/widgets/") }.sortedBy { it.path }.toList()

    private val clickable = Regex("""\.(clickable|combinedClickable|toggleable|selectable|triStateToggleable)\b""")
    private val container = Regex("""(?<![\w.])(Box|Row|Column|Surface|IconButton)\(""")
    private val inked = Regex("""(?<![\w.])(Text|BasicText|\w*Glyph|Icon|CircularProgressIndicator|LinearProgressIndicator|AnimatedCounter)\(""")
    private val fillToken = Regex("""\b(colors|LocalEcencyColors\.current)\.(blue|red)\b(?!Text)""")
    private val fillCalls = setOf("background", "drawRect", "drawCircle", "drawArc", "drawRoundRect", "drawPath", "drawLine", "horizontalGradient", "verticalGradient", "linearGradient")
    /** An animated colour that a `// fill` comment may mark as a fill. */
    private val animatedFill = Regex("""\banimate(Color|Value)AsState\(""")
    private val fillParams = setOf(
        "containerColor", "disabledContainerColor", "selectedContainerColor", "checkedTrackColor", "uncheckedTrackColor", "indicatorColor",
    )
    private val iconCall = Regex("""(?<![\w.])(Glyph|Icon)\(""")
    private val visual = Regex("""(?<![\w.])(Glyph|Icon|Image|AsyncImage|UpvoteGlyph|Avatar)\(""")
    private val labelled = Regex("""(?<![\w.])\w*(Text|Title|Label|AnimatedCounter)\(|\b(description|contentDescription)\s*=\s*+(?!null\b)""")

    private val semanticsLabel = Regex("""\bsemantics\b[^{]*\{[^}]*\bcontentDescription\s*=""")

    /** Each clickable call with its line and its argument text (empty for a bare `.clickable { }`). */
    internal fun clickables(src: String): List<Pair<Int, String>> = clickable.findAll(src).filter { !isImport(src, it.range.first) }.map { m ->
        var j = m.range.last + 1
        while (j < src.length && src[j].isWhitespace()) j++
        val args = if (j < src.length && src[j] == '(') balanced(src, j, '(', ')') else ""
        line(src, m.range.first) to args
    }.toList()

    /** Tappable containers whose content shows only icons and nothing names them: line numbers. */
    internal fun unlabelledIconButtons(src: String): List<Int> = container.findAll(src).mapNotNull { m ->
        val open = m.range.last
        val args = balanced(src, open, '(', ')')
        val tappable = m.groupValues[1] == "IconButton" || clickable.containsMatchIn(args) || args.contains(".toggleable(")
        if (!tappable) return@mapNotNull null
        var j = open + args.length + 2
        while (j < src.length && src[j].isWhitespace()) j++
        val body = if (j < src.length && src[j] == '{') balanced(src, j, '{', '}') else ""
        val bare = argsWithoutNestedLambdas(args)
        val named = labelled.containsMatchIn(body) || labelled.containsMatchIn(bare) || semanticsLabel.containsMatchIn(args)
        if (!visual.containsMatchIn(body) || named) null else line(src, m.range.first)
    }.toList()

    /** `Glyph(...)` / `Icon(...)` calls that are clickable themselves and have no description: line numbers. */
    internal fun clickableIconsWithoutLabel(src: String): List<Int> = iconCall.findAll(src).mapNotNull { m ->
        val args = balanced(src, m.range.last, '(', ')')
        if (clickable.containsMatchIn(args) && !labelled.containsMatchIn(args)) line(src, m.range.first) else null
    }.toList()

    /**
     * Every `colors.blue` / `colors.red` (fill tokens, under WCAG AA as ink on dark backgrounds) that is not a fill:
     * line numbers. A use is a fill when its innermost enclosing call is a fill call ([fillCalls]), the argument it
     * sits in is a fill parameter ([fillParams]) or the light scheme's `primary`; a line with an animated colour
     * (`animateColorAsState`) ending in a `// fill` comment marks an animated fill. Borders are not fills: a 1 dp
     * outline needs 3:1, which the dark fill blue misses.
     * Anything else (a `Text` or `Glyph` colour, a wrapper's colour argument, a `when` branch, a local) is ink.
     */
    internal fun fillTokenInk(src: String): List<Int> = fillToken.findAll(src).mapNotNull { m ->
        val lineStart = src.lastIndexOf('\n', m.range.first) + 1
        val lineEnd = src.indexOf('\n', m.range.first).let { if (it < 0) src.length else it }
        val text = src.substring(lineStart, lineEnd)
        if (text.trimEnd().endsWith("// fill") && animatedFill.containsMatchIn(text)) return@mapNotNull null
        var i = m.range.first - 1
        var depth = 0
        var param: String? = null
        var call: String? = null
        while (i >= 0) {
            val c = src[i]
            when {
                c == ')' || c == ']' || c == '}' -> depth++
                c == '(' || c == '[' || c == '{' -> if (depth == 0) {
                    if (c == '(') {
                        var j = i - 1
                        while (j >= 0 && (src[j].isLetterOrDigit() || src[j] == '_')) j--
                        call = src.substring(j + 1, i)
                    }
                    break
                } else depth--
                depth == 0 && param == null && c == '=' && i > 0 && src[i - 1] !in "=!<>" && src.getOrNull(i + 1) != '=' -> {
                    var j = i - 1
                    while (j >= 0 && src[j] == ' ') j--
                    var k = j
                    while (k >= 0 && (src[k].isLetterOrDigit() || src[k] == '_')) k--
                    param = src.substring(k + 1, j + 1)
                }
                depth == 0 && c == ',' && param == null -> param = ""
            }
            i--
        }
        // the light scheme's primary is a fill that passes on white; the dark scheme's is the readable blue
        val lightPrimary = call == "lightColorScheme" && param == "primary"
        if (call in fillCalls || param in fillParams || lightPrimary) null else line(src, m.range.first)
    }.toList()

    @Test
    fun textAndIconsUseTheTextTokens() {
        val bad = sources.flatMap { f -> fillTokenInk(f.readText()).distinct().map { "${f.relativeTo(app)}:$it" } }
        assertTrue(
            bad.isEmpty(),
            "text or an icon colored with colors.blue / colors.red (under WCAG AA on dark backgrounds): use blueText / " +
                "redText; the fill tokens are for backgrounds, borders and button containers:\n" + bad.joinToString("\n"),
        )
    }

    @Test
    fun everyButtonColorsNamesItsContentColor() {
        val bad = sources.flatMap { f ->
            val src = f.readText()
            Regex("""buttonColors\(""").findAll(src).filter { !balanced(src, it.range.last, '(', ')').contains("contentColor") }
                .map { "${f.relativeTo(app)}:${line(src, it.range.first)}" }.toList()
        }
        assertTrue(bad.isEmpty(), "buttonColors without contentColor takes the scheme's onPrimary (navy in dark mode): name it:\n" + bad.joinToString("\n"))
    }

    @Test
    fun everyClickableNamesARole() {
        val all = sources.flatMap { f -> clickables(f.readText()).map { Triple(f, it.first, it.second) } }
        assertTrue(all.size > 200, "the scan found only ${all.size} clickables; did the pattern break?")
        val bad = all.filter { (_, _, args) -> !Regex("""\brole\s*=""").containsMatchIn(args) }
        assertTrue(
            bad.isEmpty(),
            "clickable without a role (TalkBack then cannot say it is a button, tab, checkbox...): pass role = Role.Button " +
                "or the fitting role:\n" + bad.joinToString("\n") { (f, line, _) -> "${f.relativeTo(app)}:$line" },
        )
    }

    @Test
    fun everyIconOnlyButtonHasALabel() {
        val bad = sources.flatMap { f -> val src = f.readText(); (unlabelledIconButtons(src) + clickableIconsWithoutLabel(src)).map { "${f.relativeTo(app)}:$it" } }
        assertTrue(
            bad.isEmpty(),
            "a tappable icon with no label (TalkBack reads it as an unlabelled button): give the Glyph a description, or the " +
                "container a contentDescription:\n" + bad.joinToString("\n"),
        )
    }

    @Test
    fun theScansSeeWhatTheyCheck() {
        assertTrue(clickables("Modifier.clickable { x() }").single().second.isEmpty())
        assertTrue(clickables("Modifier.clickable(enabled = a(b), role = Role.Tab) { }").single().second.contains("role"))
        assertTrue(clickables("Modifier.combinedClickable(onClick = f)").single().second.contains("onClick"))
        assertTrue(clickables("import androidx.compose.foundation.clickable\n").isEmpty())
        assertTrue(unlabelledIconButtons("Box(Modifier.clickable(role = Role.Button) { go() }.padding(4.dp)) { Glyph(AppIcon.CLOSE, c, 16.dp) }") == listOf(1))
        assertTrue(unlabelledIconButtons("Box(Modifier.clickable(role = Role.Button) { go() }) { Glyph(AppIcon.CLOSE, c, 16.dp, description = UiCopy.CLOSE) }").isEmpty())
        assertTrue(unlabelledIconButtons("Row(Modifier.clickable(role = Role.Button) { go() }) { Glyph(AppIcon.HEART, c); Text(n) }").isEmpty())
        assertTrue(unlabelledIconButtons("Box(Modifier.semantics { contentDescription = l }.clickable(role = Role.Button) { }) { Icon(v, null) }").isEmpty())
        assertTrue(unlabelledIconButtons("IconButton(onClick = f) { Icon(v, contentDescription = null) }") == listOf(1))
        assertTrue(unlabelledIconButtons("Box(Modifier.padding(4.dp)) { Glyph(AppIcon.CLOSE, c) }").isEmpty())
        assertTrue(clickableIconsWithoutLabel("Glyph(AppIcon.X, c, 20.dp, Modifier.clickable(role = Role.Button) { })") == listOf(1))
        assertTrue(clickableIconsWithoutLabel("Glyph(AppIcon.X, c, 20.dp, Modifier.clickable(role = Role.Button) { }, description = d)").isEmpty())
        assertTrue(fillTokenInk("Text(t, color = colors.blue)").isNotEmpty())
        assertTrue(fillTokenInk("Glyph(AppIcon.X, colors.red, 20.dp)").isNotEmpty())
        assertTrue(fillTokenInk("Text(t, color = colors.blueText, modifier = Modifier.background(colors.blue))").isEmpty())
        assertTrue(fillTokenInk("Box(Modifier.background(colors.blue)) { }").isEmpty())
        assertTrue(fillTokenInk("Bubble(icon, colors.blue)").isNotEmpty())
        assertTrue(fillTokenInk("val tint = when (x) { true -> colors.red; else -> colors.text }").isNotEmpty())
        assertTrue(fillTokenInk("val accent = colors.blue").isNotEmpty())
        assertTrue(fillTokenInk("Button(colors = ButtonDefaults.buttonColors(contentColor = White, containerColor = colors.blue))").isEmpty())
        assertTrue(fillTokenInk("val fill by animateColorAsState(colors.blue) // fill").isEmpty())
        assertTrue(fillTokenInk("Text(t, color = colors.blue) // fill").isNotEmpty())
        assertTrue(fillTokenInk("Box(Modifier.border(1.dp, colors.blue, shape))").isNotEmpty())
        assertTrue(fillTokenInk("OutlinedTextFieldDefaults.colors(focusedBorderColor = colors.blue)").isNotEmpty())
        assertTrue(fillTokenInk("lightColorScheme(primary = colors.blue)").isEmpty())
        assertTrue(fillTokenInk("darkColorScheme(primary = colors.blue)").isNotEmpty())
    }

    private fun isImport(src: String, at: Int) = src.substring(src.lastIndexOf('\n', at) + 1, at).trimStart().startsWith("import ")

    private fun line(src: String, at: Int) = src.substring(0, at).count { it == '\n' } + 1

    /** The text between the bracket at [open] and its match (exclusive), skipping string literals. */
    private fun balanced(src: String, open: Int, l: Char, r: Char): String {
        var depth = 0
        var i = open
        var inString = false
        while (i < src.length) {
            val c = src[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                l -> depth++
                r -> { depth--; if (depth == 0) return src.substring(open + 1, i) }
            }
            i++
        }
        return src.substring(open + 1)
    }

    /** [args] without the bodies of lambdas inside it (a click handler that shows a `Text` does not label the button). */
    private fun argsWithoutNestedLambdas(args: String): String {
        val out = StringBuilder()
        var depth = 0
        for (c in args) {
            if (c == '{') depth++
            if (depth == 0) out.append(c)
            if (c == '}') depth--
        }
        return out.toString()
    }
}
