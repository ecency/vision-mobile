package com.ecency.a11y

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A bottom sheet's content scrolls (README "Android app", Windows): with the keyboard up or a large font a column
 * that does not scroll pushes its last controls off screen, out of reach (#3715, #3720). Every `ModalBottomSheet`
 * of the Android app holds a scroll container in its content (`verticalScroll`, `LazyColumn`, `LazyVerticalGrid`),
 * directly or in a composable of the app the content calls (two levels deep). Sheets of a fixed height by design are
 * listed with a reason in `androidApp/sheet-scroll-allowed.txt`; an entry nothing matches fails too.
 * The scan reads source text with balanced brackets, no compiler.
 */
class SheetScrollLintTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { it.resolve("src/config/locales/en-US.json").isFile }
    private val sources = root.resolve("androidApp/src/main/kotlin/com/ecency/app").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
    private val allowFile = root.resolve("androidApp/sheet-scroll-allowed.txt")

    /** File name and sheet id (its first test tag, or its function) of each sheet allowed to stay fixed. */
    private val allowed: List<Pair<String, String>> = allowFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
        val parts = line.split('\t')
        require(parts.size == 3 && parts[2].isNotBlank()) { "sheet-scroll-allowed.txt: want File.kt<TAB>sheet<TAB>reason, got: $line" }
        parts[0] to parts[1]
    }

    private val scrollers = Regex("""(?<!\w)(verticalScroll|LazyColumn|LazyVerticalGrid|LazyVerticalStaggeredGrid)\s*\(""")

    private fun lineAt(src: String, at: Int) = src.substring(0, at).count { it == '\n' } + 1

    private fun isImportOrComment(src: String, at: Int): Boolean {
        val before = src.substring(src.lastIndexOf('\n', at) + 1, at).trimStart()
        return before.startsWith("import ") || before.startsWith("//") || before.startsWith("*") || before.startsWith("/*")
    }

    /** The index just past the bracket closing the one at [open] (strings and char literals skipped). */
    private fun closing(src: String, open: Int): Int {
        val o = src[open]
        val c = if (o == '(') ')' else '}'
        var depth = 0
        var i = open
        var inString = false
        while (i < src.length) {
            val ch = src[i]
            if (inString) {
                if (ch == '\\') i++ else if (ch == '"') inString = false
            } else when (ch) {
                '"' -> inString = true
                '\'' -> i += if (src.getOrNull(i + 1) == '\\') 3 else 2
                o -> depth++
                c -> { depth--; if (depth == 0) return i + 1 }
            }
            i++
        }
        return src.length
    }

    /** The trailing lambda after the call whose arguments open at [open], or "" when there is none. */
    private fun trailingLambda(src: String, open: Int): String {
        var i = closing(src, open)
        while (i < src.length && src[i].isWhitespace()) i++
        return if (i < src.length && src[i] == '{') src.substring(i, closing(src, i)) else ""
    }

    /** Every function of the app by name: its whole text (overloads joined). */
    private val functions: Map<String, String> by lazy {
        val decl = Regex("""(?<!\w)fun\s+(?:[\w.<>?]+\.)?([A-Za-z]\w*)\s*\(""")
        val out = mutableMapOf<String, String>()
        sources.forEach { f ->
            val src = f.readText()
            decl.findAll(src).filter { !isImportOrComment(src, it.range.first) }.forEach { m ->
                val paramsEnd = closing(src, m.range.last)
                var i = paramsEnd
                // skip a return type, then take the body (a block, or an expression body to the end of its line)
                while (i < src.length && src[i] != '{' && src[i] != '=' && src[i] != '\n') i++
                val body = when {
                    i < src.length && src[i] == '{' -> src.substring(i, closing(src, i))
                    i < src.length && src[i] == '=' -> src.substring(i, src.indexOf('\n', i).let { if (it < 0) src.length else it })
                    else -> ""
                }
                out[m.groupValues[1]] = (out[m.groupValues[1]] ?: "") + "\n" + body
            }
        }
        out
    }

    /** [content] with the bodies of the app composables it calls, [depth] levels deep. */
    private fun expanded(content: String, depth: Int): String {
        if (depth == 0) return content
        val called = Regex("""(?<![\w.])([A-Z]\w*)\s*[({]""").findAll(content).map { it.groupValues[1] }.toSet() - "ModalBottomSheet"
        return content + called.mapNotNull { functions[it] }.joinToString("\n") { expanded(it, depth - 1) }
    }

    data class Sheet(val file: String, val line: Int, val id: String, val content: String)

    /** Each `ModalBottomSheet(...) { content }` call of [src] (the wrapper in ui/Windows.kt is the definition, skipped). */
    private fun sheets(name: String, src: String): List<Sheet> =
        Regex("""(?<![\w.])ModalBottomSheet\s*\(""").findAll(src).filter { m ->
            !isImportOrComment(src, m.range.first) && !src.substring(maxOf(0, m.range.first - 4), m.range.first).endsWith("fun ")
        }.map { m ->
            val content = trailingLambda(src, m.range.last)
            val tag = Regex("""testTag\("([^"]+)"\)""").find(content)?.groupValues?.get(1)
            val fn = Regex("""(?<!\w)fun\s+(?:[\w.<>?]+\.)?(\w+)\s*\(""").findAll(src.substring(0, m.range.first)).lastOrNull()?.groupValues?.get(1)
            Sheet(name, lineAt(src, m.range.first), tag ?: fn ?: "?", content)
        }.toList()

    private val all by lazy { sources.flatMap { f -> sheets(f.name, f.readText()) } }

    private fun scrolls(s: Sheet) = scrollers.containsMatchIn(expanded(s.content, 2))

    @Test
    fun theScanFindsEverySheetAndItsScroll() {
        val src = """
            import androidx.compose.material3.ModalBottomSheet
            // ModalBottomSheet( in a comment
            @Composable fun A() { ModalBottomSheet(onDismissRequest = {}) { Column(Modifier.testTag("a")) { Text("x") } } }
            @Composable fun B() {
                ModalBottomSheet(onDismissRequest = { close(")") }, containerColor = c) {
                    Column(Modifier.verticalScroll(rememberScrollState()).testTag("b")) { Text("y") }
                }
            }
            @Composable fun C() = ModalBottomSheet(onDismissRequest = {}) { Body() }
        """.trimIndent()
        val found = sheets("T.kt", src)
        assertEquals(listOf("a" to 3, "b" to 5, "C" to 9), found.map { it.id to it.line })
        assertEquals(listOf(false, true, false), found.map { scrollers.containsMatchIn(it.content) })
    }

    @Test
    fun everySheetScrolls() {
        assertTrue(all.size >= 40, "the scan found the app's sheets (${all.size})")
        val bad = all.filter { s -> !scrolls(s) && allowed.none { (f, id) -> f == s.file && id == s.id } }
            .map { "${it.file}:${it.line} ${it.id}" }
        assertEquals(
            emptyList(), bad,
            "a sheet's content scrolls (verticalScroll on its column, a bounded list inside), or it is listed in " +
                "androidApp/sheet-scroll-allowed.txt with the reason it is fixed height by design",
        )
    }

    @Test
    fun everyAllowlistEntryNamesAFixedSheet() {
        val stale = allowed.filter { (f, id) -> all.none { it.file == f && it.id == id && !scrolls(it) } }
        assertEquals(emptyList(), stale, "sheet-scroll-allowed.txt entries that match no fixed sheet: remove them")
    }
}
