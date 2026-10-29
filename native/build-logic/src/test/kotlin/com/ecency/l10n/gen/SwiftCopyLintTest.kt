package com.ecency.l10n.gen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The iOS app's copy comes from the catalog (README "Localization", platform literals): the shared copy objects
 * (`UiCopy.shared.CANCEL`) or the generated Swift `L` (`L.Native.Ios.Vote.failed`). This lint fails on an English
 * literal handed to SwiftUI as text: the first argument of `Text`, `Label`, `Button` and the other views that show a
 * title, of the modifiers that show or speak one (`navigationTitle`, `alert`, `accessibilityLabel`, ...), and the
 * value of a copy argument (`title:`, `message:`, `label:`, ...). A literal counts when it has a letter outside its
 * interpolations, so `Text("@\(name)")` and `Text("\(a) / \(b)")` pass. What stays a literal on purpose (brand names,
 * debug screens, glyphs) is listed with a reason in `native/iosApp/l10n-literals-allowed.txt`; an entry nothing matches fails
 * too, so the list never goes stale.
 *
 * It also keeps copy from being cached, as build-logic `CachedCopyTest` does for Kotlin: a stored `static`
 * or `lazy` property (or a file level one) whose value reads the catalog would keep the first language, while the root rebuilds with
 * `.id(language)` on a switch.
 */
class SwiftCopyLintTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { it.resolve("src/config/locales/en-US.json").isFile }
    private val ios = root.resolve("native/iosApp")
    private val allowFile = ios.resolve("l10n-literals-allowed.txt")

    private val sources: List<File> = listOf("EcencyApp", "Widgets", "ShareExtension", "NotificationService")
        .map(ios::resolve)
        .flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "swift" && "/Generated/" !in it.path }.toList() }
        .sortedBy { it.path }

    /** (file name, literal with interpolations written `{}`) to its reason; tab separated as the Android list. */
    private val allowed: Map<Pair<String, String>, String> = allowFile.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .associate { line ->
            val parts = line.split('\t')
            require(parts.size == 3 && parts[2].isNotBlank()) { "l10n-literals-allowed.txt: want File.swift<TAB>literal<TAB>reason, got: $line" }
            (parts[0] to parts[1].replace("\\n", "\n")) to parts[2]
        }

    @Test
    fun noEnglishLiteralReachesSwiftUiText() {
        assertTrue(sources.size > 60, "found only ${sources.size} Swift files")
        val used = HashSet<Pair<String, String>>()
        val found = ArrayList<String>()
        for (f in sources) {
            for (hit in copyLiterals(f.readText())) {
                val id = f.name to hit.text
                if (id in allowed) used += id else found += "${f.relativeTo(ios)}:${hit.line}: \"${hit.text.replace("\n", "\\n")}\" (${hit.site})"
            }
        }
        assertTrue(found.isEmpty(), "English literals shown as UI text; use the shared copy or L.Native.Ios.* (or list them with a reason in l10n-literals-allowed.txt):\n" + found.joinToString("\n"))
        val stale = allowed.keys - used
        assertTrue(stale.isEmpty(), "l10n-literals-allowed.txt entries that match no literal any more:\n" + stale.joinToString("\n") { "${it.first} | ${it.second}" })
    }

    @Test
    fun noStaticLetCachesCopy() {
        // a stored let or var with an initializer (`=` before any `{`); computed vars have no `=` and are read live
        val cached = Regex("""(?m)^\s*(?:(?:private|fileprivate|public|internal)\s+)?((?:static\s+|lazy\s+)?(?:let|var))\s+\w+[^=\n{]*=\s*(.*)$""")
        val readsCopy = Regex("""\bL\.[A-Z]\w*\.|\b\w+Copy\.shared\.[A-Z_]{2,}\b|\b\w+Copy\.shared\.[a-z]\w*\(""")
        val found = ArrayList<String>()
        for (f in sources) {
            val masked = Lexer(f.readText()).masked
            for (m in cached.findAll(masked)) {
                val line = m.value.trimStart()
                // file level, static or lazy: kept across a language switch; a local or a view's stored property is
                // made again when the body runs
                val fileLevel = m.value.takeWhile { it == ' ' }.isEmpty() && !m.value.startsWith("\n")
                val kept = m.groupValues[1].startsWith("static") || m.groupValues[1].startsWith("lazy")
                if (!kept && !fileLevel) continue
                if (readsCopy.containsMatchIn(m.groupValues[2])) found += "${f.relativeTo(ios)}: ${line.take(140)}"
            }
        }
        assertTrue(found.isEmpty(), "a static, lazy or file level stored property holds catalog text (make it a computed var):\n" + found.joinToString("\n"))
    }

    @Test
    fun theLintSeesTheSitesItIsFor() {
        val sample = """
            Text("Hello")
            Text(verbatim: "Plain")
            Text("@\(name)")
            Text(flag ? "Yes please" : copy.NO)
            Button("Tap") { go() }.accessibilityIdentifier("tap-button")
            Label(title ?? "Fallback", systemImage: "star")
            .navigationTitle("Title")
            .accessibilityLabel(open ? "Opened" : "Closed")
            .accessibilityAction(named: "Act") { go() }
            AppAlert(title: "Oops", message: L.Native.Ios.Vote.failed)
            Image(systemName: "star.fill")
            // Text("in a comment")
            let url = "https://ecency.com" // Text("after a url")
            Text(L.Native.Ios.Vote.failed)
        """.trimIndent()
        val texts = copyLiterals(sample).map { it.text }
        assertTrue(texts == listOf("Hello", "Plain", "Yes please", "Tap", "Fallback", "Title", "Opened", "Closed", "Act", "Oops"), "$texts")
    }

    private class Hit(val text: String, val line: Int, val site: String)

    private fun copyLiterals(src: String): List<Hit> {
        val lex = Lexer(src)
        val m = lex.masked
        val out = ArrayList<Hit>()
        fun scan(from: Int, site: String) {
            // the expression up to the first comma or close at its own depth: every literal in it is shown
            var i = from
            var depth = 0
            while (i < m.length) {
                val c = m[i]
                if (c == '(' || c == '[' || c == '{') depth++
                else if (c == ')' || c == ']' || c == '}') { if (depth == 0) break; depth-- }
                else if (c == ',' && depth == 0) break
                else if (c == '"' || c == '#') {
                    val lit = lex.literalAt(i)
                    if (lit != null) {
                        // a literal that is a labelled argument of a nested call (`Image(systemName: "x")`) is not this text
                        val before = m.substring(maxOf(0, i - 40), i)
                        val nestedLabel = depth > 0 && Regex("""\w+:\s*$""").containsMatchIn(before)
                        if (!nestedLabel && lit.text.replace("{}", "").any { it.isLetter() }) out += Hit(lit.text, lex.lineOf(i), site)
                        i = lit.end
                        continue
                    }
                }
                i++
            }
        }
        for (call in CALL.findAll(m)) scan(call.range.last + 1, call.value.trim().removeSuffix("("))
        for (arg in ARG.findAll(m)) scan(arg.range.last + 1, arg.groupValues[1] + ":")
        return out.distinctBy { it.line to it.text }.sortedBy { it.line }
    }

    /** Swift source with comments blanked and string literal contents blanked (quotes kept), same offsets. */
    private class Lexer(private val src: String) {
        private val literals = HashMap<Int, Literal>()
        val masked: String

        class Literal(val text: String, val end: Int)

        init {
            val b = StringBuilder(src)
            var i = 0
            while (i < src.length) {
                when {
                    src.startsWith("//", i) -> {
                        val e = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                        for (k in i until e) b[k] = ' '
                        i = e
                    }
                    src.startsWith("/*", i) -> {
                        val e = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }
                        for (k in i until e) if (b[k] != '\n') b[k] = ' '
                        i = e
                    }
                    src[i] == '"' || (src[i] == '#' && src.startsWith("#\"", i)) -> {
                        val lit = read(i)
                        literals[i] = lit
                        for (k in i until lit.end) if (src[k] != '"' && src[k] != '#' && b[k] != '\n') b[k] = ' '
                        i = lit.end
                    }
                    else -> i++
                }
            }
            masked = b.toString()
        }

        fun literalAt(i: Int): Literal? = literals[i]

        fun lineOf(i: Int): Int = src.substring(0, i).count { it == '\n' } + 1

        /** One literal from [start]: its text with each `\(...)` as `{}`, escapes kept as written. */
        private fun read(start: Int): Literal {
            var i = start
            var hashes = 0
            while (src[i] == '#') { hashes++; i++ }
            val multi = src.startsWith("\"\"\"", i)
            val quote = if (multi) "\"\"\"" else "\""
            i += quote.length
            val close = quote + "#".repeat(hashes)
            val esc = "\\" + "#".repeat(hashes)
            val text = StringBuilder()
            while (i < src.length) {
                if (src.startsWith(close, i)) { i += close.length; break }
                if (src.startsWith(esc, i)) {
                    val k = i + esc.length
                    if (k < src.length && src[k] == '(') {
                        var depth = 1
                        var j = k + 1
                        while (j < src.length && depth > 0) {
                            when (src[j]) {
                                '"' -> { j++; while (j < src.length && src[j] != '"') { if (src[j] == '\\') j++; j++ } }
                                '(' -> depth++
                                ')' -> depth--
                            }
                            j++
                        }
                        text.append("{}")
                        i = j
                        continue
                    }
                    if (k < src.length && src[k] == 'n') { text.append('\n'); i = k + 1; continue }
                    if (src.startsWith("u{", k)) {
                        val e = src.indexOf('}', k)
                        text.appendCodePoint(src.substring(k + 2, e).toInt(16))
                        i = e + 1
                        continue
                    }
                    text.append(src, i, minOf(k + 1, src.length))
                    i = k + 1
                    continue
                }
                if (!multi && src[i] == '\n') break
                text.append(src[i])
                i++
            }
            return Literal(text.toString(), i)
        }
    }

    private companion object {
        /** Views whose first argument is shown text, and modifiers whose first argument is shown or spoken. */
        val CALL = Regex(
            """(?<![\w.])(Text|Label|Button|Toggle|Picker|TextField|SecureField|Section|Menu|Link|Stepper|DatePicker|LabeledContent|ProgressView|ShareLink)\(\s*(?:verbatim:\s*)?""" +
                """|\.(navigationTitle|accessibilityLabel|accessibilityHint|accessibilityValue|alert|confirmationDialog|help|badge)\(\s*""" +
                """|\.(accessibilityAction)\(\s*named:\s*""",
        )

        /** Arguments that carry copy in this app's own views and models (`AppAlert(title:message:)`, `toast(text:)`, ...). */
        val ARG = Regex("""(?<![\w.])(title|message|label|placeholder|prompt|text|hint|question|action|cancel|approve|subtitle|caption|localizedReason)\s*:\s*(?=["#])""")
    }
}
