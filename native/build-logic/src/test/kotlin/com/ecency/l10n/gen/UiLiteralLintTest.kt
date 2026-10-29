package com.ecency.l10n.gen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Android app shows no English of its own: user-visible text comes from the catalog (`L.*` or a shared copy
 * object). This scan fails on a string literal in `androidApp/src/main` that reads like copy (words, a sentence, a
 * capitalised label), wherever it sits: a `Text("..")`, a `contentDescription`, a toast, a custom composable's label.
 * Literals that are not copy (MIME types, crypto names, brand names, test ids built from words, debug screens) are
 * listed in `native/androidApp/l10n-literals-allowed.txt`, one per line as `File.kt<TAB>literal<TAB>why`, matched by
 * file name and the literal's source text. A stale entry fails too, so the list stays the real set.
 *
 * Also: windows (dialogs, sheets, menus, popups) come from `ui/Windows.kt`, which makes them follow a live language
 * switch; an explicit import of the Material or platform window bypasses that and fails here.
 */
class UiLiteralLintTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { it.resolve("src/config/locales/en-US.json").isFile }
    private val app = root.resolve("native/androidApp")
    private val sources = app.resolve("src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    private data class Allowed(val file: String, val literal: String)

    private val allowed: Set<Allowed> = app.resolve("l10n-literals-allowed.txt").readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line -> line.split('\t').let { require(it.size == 3 && it[2].isNotBlank()) { "allowlist line needs file, literal and a reason: $line" }; Allowed(it[0], it[1]) } }
        .toSet()

    @Test
    fun noUserVisibleLiteralOutsideTheAllowlist() {
        val found = sources.flatMap { f ->
            val src = f.readText()
            literals(src).filter { !inCodeContext(src, it.third) && (looksLikeCopy(it.second) || inTextSlot(src, it.third, it.second)) }.map { Triple(f, it.first, it.second) }
        }
        assertTrue(found.size > 20, "the scan found only ${found.size} literals; did the tokenizer break?")
        val bad = found.filter { (f, _, lit) -> Allowed(f.name, lit) !in allowed }
        assertTrue(
            bad.isEmpty(),
            "User-visible English in the Android app; read it from the catalog (README Localization) or, if it is not copy, " +
                "add it to androidApp/l10n-literals-allowed.txt with a reason:\n" +
                bad.joinToString("\n") { (f, line, lit) -> "${f.relativeTo(app)}:$line \"$lit\"" },
        )
        val seen = found.map { (f, _, lit) -> Allowed(f.name, lit) }.toSet()
        val stale = allowed - seen
        assertTrue(stale.isEmpty(), "allowlist entries no longer in the code (remove them):\n" + stale.joinToString("\n") { "${it.file}\t${it.literal}" })
    }

    @Test
    fun windowsComeFromTheWrappers() {
        val bad = sources.filter { it.name != "Windows.kt" }.flatMap { f ->
            val text = f.readText()
            windowImports.findAll(text).map { "${f.relativeTo(app)}: ${it.value.trim()}" } + platformCalls.findAll(text).map { "${f.relativeTo(app)}: ${it.value}" }
        }
        assertTrue(bad.isEmpty(), "use the ui/Windows.kt windows (they follow a live language switch):\n" + bad.joinToString("\n"))
        // outside the ui package a plain name does not reach the wrapper: it must be imported by name
        val outside = sources.filter { !it.path.contains("/com/ecency/app/ui/") }.flatMap { f ->
            val text = f.readText()
            WINDOWS.filter { w -> Regex("""(?<![\w.])$w\(""").containsMatchIn(text) && !text.contains("import com.ecency.app.ui.$w\n") }.map { "${f.relativeTo(app)}: $it" }
        }
        assertTrue(outside.isEmpty(), "a window outside the ui package must import the ui/Windows.kt one:\n" + outside.joinToString("\n"))
    }

    @Test
    fun theWindowRuleCatchesAliasesAndStars() {
        for (line in listOf("import androidx.compose.material3.AlertDialog as Box", "import androidx.compose.ui.window.Dialog", "import androidx.compose.ui.window.Popup as P",
            "import androidx.compose.material3.ModalBottomSheet", "import androidx.compose.material3.BasicAlertDialog", "import androidx.compose.ui.window.*"))
            assertTrue(windowImports.containsMatchIn(line + "\n"), line)
        for (line in listOf("import androidx.compose.material3.AlertDialogDefaults", "import androidx.compose.ui.window.DialogProperties", "import androidx.compose.material3.DropdownMenuItem"))
            assertTrue(!windowImports.containsMatchIn(line + "\n"), line)
        assertTrue(platformCalls.containsMatchIn("androidx.compose.ui.window.Popup(a)"))
    }

    @Test
    fun theHeuristicTellsCopyFromIds() {
        for (copy in listOf("Cancel", "Move up", "No more posts.", "{}  •  {} votes", "Reply to @{}", "OK", "{}, selected")) assertTrue(looksLikeCopy(copy), copy)
        for (id in listOf("draftsTab:", "settings:imageServer", "image/*", "AES/GCM/NoPadding", "postOptions", "SHA-256", "https://ecency.com", "chip:{}", "EDIT_TEXT", "a_b", "{}", "%"))
            assertTrue(!looksLikeCopy(id), id)
        // a lower case word counts in a text slot, named or not, but not as an animation's tooling label
        for (src in listOf("Text(\"or\")", "Text(text = \"or\")", "supportingText = { Text(\"optional\") }", "TextField(v, label = { Text(text = \"amount\") })",
            "contentDescription = \"close\"", "Chip(text = \"more\")", "Button(onClick = {}) { Text(\"ok\") }"))
            assertTrue(inTextSlot(src, src.indexOf('"'), src.substringAfter('"').substringBefore('"')), src)
        for (src in listOf("animateColorAsState(c, label = \"pillText\")", "Glyph(icon, tag = \"menu\")"))
            assertTrue(!inTextSlot(src, src.indexOf('"'), src.substringAfter('"').substringBefore('"')), src)
    }

    companion object {
        private val WINDOWS = listOf("AlertDialog", "BasicAlertDialog", "ModalBottomSheet", "DropdownMenu", "Dialog", "Popup")

        /** Any import of a Material or platform window, aliased or not, and the window package's star import. */
        private val windowImports = Regex(
            """(?m)^import androidx\.compose\.(material3\.(AlertDialog|BasicAlertDialog|ModalBottomSheet|DropdownMenu)|ui\.window\.(Dialog|Popup|\*))(\s+as\s+\w+)?\s*$""",
        )
        private val platformCalls = Regex("""(?<![\w.])androidx\.compose\.(material3\.(AlertDialog|BasicAlertDialog|ModalBottomSheet|DropdownMenu)|ui\.window\.(Dialog|Popup))\(""")

        private val codeContexts = listOf(
            Regex("""@\w+\(\s*("[^"]*"\s*,\s*)*$"""), // annotation arguments
            Regex("""(==|!=)\s*$"""), // a comparison with an id
        )

        /** Calls whose string arguments are ids, keys, patterns or log lines, never shown text. */
        private val codeCalls = listOf("testTag(", "Log.d(", "Log.i(", "Log.w(", "Log.e(", "SemanticsPropertyKey<", "Regex(", "getInstance(", "getTimeZone(", "SimpleDateFormat(",
            // animation labels (Compose tooling names)
            "AsState(", "Transition(", "Crossfade(", "AnimatedContent(")

        private val textSlot = Regex(
            """(\bText\(\s*(text\s*=\s*)?|\b(contentDescription|description|stateDescription|text|label|title|message|placeholder|supportingText|""" +
                """onClickLabel|onLongClickLabel|actionLabel|confirmLabel|dismissLabel)\s*=\s*(\{\s*Text\(\s*(text\s*=\s*)?)?)$""",
        )

        /** A literal that is the text of a `Text` or an accessibility label is copy even as one lower case word ("or"). */
        fun inTextSlot(src: String, offset: Int, literal: String): Boolean {
            if (literal.replace(Regex("""\\u[0-9a-fA-F]{4}|\\."""), " ").replace("{}", "").count { it.isLetter() } < 2) return false
            val before = src.substring(src.lastIndexOf('\n', offset - 1) + 1, offset)
            // `label = "pillText"`: a Compose animation's tooling name, one lower camel case word
            if (Regex("""\blabel\s*=\s*$""").containsMatchIn(before) && Regex("""[a-z][A-Za-z0-9{}]*""").matches(literal)) return false
            return textSlot.containsMatchIn(before)
        }

        /** True when the literal starting at [offset] is an argument nobody reads as text (see [codeContexts], [codeCalls]). */
        fun inCodeContext(src: String, offset: Int): Boolean {
            val before = src.substring(src.lastIndexOf('\n', offset - 1) + 1, offset)
            if (codeContexts.any { it.containsMatchIn(before) }) return true
            return codeCalls.any { call ->
                val at = before.lastIndexOf(call)
                at >= 0 && before.substring(at + call.length).let { rest -> rest.count { it == '(' } >= rest.count { it == ')' } }
            }
        }

        /** Text with template parts as `{}`; true for words a reader would see. */
        fun looksLikeCopy(literal: String): Boolean {
            val text = literal.replace(Regex("""\\u[0-9a-fA-F]{4}|\\."""), " ") // escapes are not letters
            val t = text.replace("{}", " ").trim()
            if (t.count { it.isLetter() } < 2) return false
            if (Regex("""^(https?:|ecency:|hive:|mailto:|/|#|\.)""").containsMatchIn(text.trim())) return false
            if (' ' !in text.trim()) {
                if ("{}" in text) return false // a name or id built from values ("capture_{}.jpg", "{}Notice")
                if (Regex("""[a-z0-9_.\-/:*+]+""").matches(t)) return false // keys, MIME types, paths
                if (Regex("""[A-Za-z0-9]+(\.[A-Za-z0-9_]+)+""").matches(t)) return false // packages, keys
                if (Regex("""[a-z]+([A-Z][a-z0-9]*)+:?""").matches(t)) return false // camelCase ids
                if (Regex("""[A-Za-z][A-Za-z0-9]*:\S*""").matches(t)) return false // test ids with a value
                if (Regex("""[A-Z0-9]+([_/\-][A-Z0-9]+)+""").matches(t)) return false // CONSTANT_NAMES
                if (Regex("""[A-Za-z0-9]+[/\-][A-Za-z0-9/\-]+""").matches(t)) return false // AES/GCM/NoPadding, SHA-256
            }
            return true
        }

        /** Each string literal of a Kotlin source: its line, its text with every `${..}` / `$x` template as `{}`, its offset. */
        fun literals(src: String): List<Triple<Int, String, Int>> {
            val out = mutableListOf<Triple<Int, String, Int>>()
            var i = 0
            var line = 1
            fun readString(start: Int, triple: Boolean, collect: Boolean): Int {
                var j = start
                val buf = StringBuilder()
                val first = line
                while (j < src.length) {
                    if (triple && src.startsWith("\"\"\"", j)) { j += 3; break }
                    val c = src[j]
                    if (!triple && c == '"') { j++; break }
                    if (c == '\n') line++
                    if (c == '\\' && !triple) { buf.append(src, j, minOf(j + 2, src.length)); j += 2; continue }
                    if (c == '$' && j + 1 < src.length && src[j + 1] == '{') {
                        var depth = 1
                        j += 2
                        while (j < src.length && depth > 0) {
                            when {
                                src[j] == '"' -> { val triple2 = src.startsWith("\"\"\"", j); j = readString(j + if (triple2) 3 else 1, triple2, true); continue }
                                src[j] == '{' -> depth++
                                src[j] == '}' -> depth--
                                src[j] == '\n' -> line++
                            }
                            j++
                        }
                        buf.append("{}")
                        continue
                    }
                    if (c == '$' && j + 1 < src.length && (src[j + 1].isLetter() || src[j + 1] == '_')) {
                        j++
                        while (j < src.length && (src[j].isLetterOrDigit() || src[j] == '_')) j++
                        buf.append("{}")
                        continue
                    }
                    buf.append(c)
                    j++
                }
                if (collect) out += Triple(first, buf.toString(), start - 1)
                return j
            }
            while (i < src.length) {
                val c = src[i]
                when {
                    c == '\n' -> { line++; i++ }
                    src.startsWith("//", i) -> i = src.indexOf('\n', i).let { if (it < 0) src.length else it }
                    src.startsWith("/*", i) -> { val end = src.indexOf("*/", i + 2).let { if (it < 0) src.length else it + 2 }; line += src.substring(i, end).count { it == '\n' }; i = end }
                    c == '\'' -> { var j = i + 1; while (j < src.length && src[j] != '\'') j += if (src[j] == '\\') 2 else 1; i = j + 1 }
                    c == '"' -> { val triple = src.startsWith("\"\"\"", i); i = readString(i + if (triple) 3 else 1, triple, true) }
                    else -> i++
                }
            }
            return out
        }
    }
}
