package com.ecency.a11y

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A bottom sheet's content scrolls (README "Android app", Sheets scroll): with the keyboard up or a large font a column
 * that does not scroll pushes its last controls off screen, out of reach (#3715, #3720). Every `ModalBottomSheet`
 * of the Android app holds a scroll container in its content (`verticalScroll`, `LazyColumn`, `LazyVerticalGrid`),
 * directly or in a composable of the app the content calls (two levels deep). Sheets of a fixed height by design are
 * listed with a reason in `androidApp/sheet-scroll-allowed.txt`; an entry nothing matches fails too.
 *
 * The scan reads source text, no compiler, and fails closed: comments and the text inside literals are blanked before
 * anything is read, brackets that never balance fail the test, a sheet opened inside the content (or inside a
 * composable it calls) is not the content's scroll, a called name defined in more than one file counts only when the
 * calling file defines it, and a name with overloads counts only when every overload scrolls.
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

    data class Sheet(val file: String, val line: Int, val id: String, val scrolls: Boolean)

    /** The scan over [files] (file name to source). */
    class Scan(files: Map<String, String>) {
        private val code: Map<String, String> = files.mapValues { (name, src) -> blankComments(name, src) }

        /** [code] with the inside of every string blanked too: text a sheet shows is not its code. */
        private val bare: Map<String, String> = code.mapValues { (name, src) -> blankLiterals(name, src) }

        private val scrollers = Regex("""(?<!\w)(verticalScroll|LazyColumn|LazyVerticalGrid|LazyVerticalStaggeredGrid)\s*[({]""")
        private val sheetCall = Regex("""(?<![\w.])ModalBottomSheet\s*\(""")
        private val decl = Regex("""(?<!\w)fun\s+(?:[\w.<>?]+\.)?([A-Za-z]\w*)\s*\(""")

        /** Each function by name: the files that define it and, in each, the text of every overload. */
        private val functions: Map<String, Map<String, List<String>>> = buildMap<String, MutableMap<String, MutableList<String>>> {
            bare.forEach { (file, src) ->
                decl.findAll(src).forEach { m ->
                    var i = closing(file, src, m.range.last)
                    // skip a return type, then take the body (a block, or an expression body)
                    while (i < src.length && src[i] != '{' && src[i] != '=' && src[i] != '\n') i++
                    val body = when {
                        i < src.length && src[i] == '{' -> src.substring(i, closing(file, src, i))
                        i < src.length && src[i] == '=' -> src.substring(i, expressionEnd(file, src, i))
                        else -> ""
                    }
                    getOrPut(m.groupValues[1]) { mutableMapOf() }.getOrPut(file) { mutableListOf() } += body
                }
            }
        }

        /** [text] with the sheets opened in it (their call and content) blanked in place: a sheet opened there scrolls for itself. */
        private fun withoutSheets(file: String, text: String): String {
            val out = StringBuilder(text)
            var at = 0
            while (true) {
                val m = sheetCall.find(text, at) ?: break
                at = lambdaEnd(file, text, m.range.last)
                for (k in m.range.first until at) if (out[k] != '\n') out[k] = ' '
            }
            return out.toString()
        }

        /**
         * The overloads of [name] as [file] calls it: its own definitions first; a name only other files define counts
         * when one file does. Which overload a call picks is not read, so the content scrolls only when all of them do.
         */
        private fun resolve(file: String, name: String): Pair<String, List<String>>? {
            val defs = functions[name] ?: return null
            return defs[file]?.let { file to it } ?: defs.entries.singleOrNull()?.toPair()
        }

        /** Whether [text] in [file], or an app function it calls ([depth] levels deep), holds a scroller outside a nested sheet. */
        private fun scrolls(file: String, text: String, depth: Int): Boolean {
            val own = withoutSheets(file, text)
            if (scrollers.containsMatchIn(own)) return true
            if (depth == 0) return false
            val called = Regex("""(?<![\w.])([A-Z]\w*)\s*[({]""").findAll(own).map { it.groupValues[1] }.toSet() - "ModalBottomSheet"
            return called.any { name -> resolve(file, name)?.let { (where, bodies) -> bodies.all { scrolls(where, it, depth - 1) } } == true }
        }

        /** Each `ModalBottomSheet(...) { content }` call (the wrapper in ui/Windows.kt is the definition, skipped). */
        val sheets: List<Sheet> = bare.flatMap { (file, src) ->
            sheetCall.findAll(src).filter { m -> !src.substring(maxOf(0, m.range.first - 4), m.range.first).endsWith("fun ") }.map { m ->
                val end = lambdaEnd(file, src, m.range.last)
                val argsEnd = closing(file, src, m.range.last)
                val content = src.substring(argsEnd, end)
                // the tag is read where literals keep their text ([code] and [bare] match index for index), nested sheets left out
                val kept = withoutSheets(file, content)
                val shown = code.getValue(file).substring(argsEnd, end).mapIndexed { k, c -> if (kept[k] == content[k]) c else ' ' }.joinToString("")
                val tag = Regex("""testTag\("([^"]+)"\)""").find(shown)?.groupValues?.get(1)
                val fn = decl.findAll(src.substring(0, m.range.first)).lastOrNull()?.groupValues?.get(1)
                Sheet(file, src.substring(0, m.range.first).count { it == '\n' } + 1, tag ?: fn ?: "?", scrolls(file, content, 2))
            }.toList()
        }

        companion object {
            /** The end of the trailing lambda after the call whose arguments open at [open] (or the arguments' end). */
            fun lambdaEnd(file: String, src: String, open: Int): Int {
                var i = closing(file, src, open)
                while (i < src.length && src[i].isWhitespace()) i++
                return if (i < src.length && src[i] == '{') closing(file, src, i) else i
            }

            /** The index just past the bracket closing the one at [open]; string and char literals skipped. Fails when it never closes. */
            fun closing(file: String, src: String, open: Int): Int {
                val o = src[open]
                val c = if (o == '(') ')' else '}'
                var depth = 0
                var i = open
                while (i < src.length) {
                    val literalEnd = literal(file, src, i)
                    if (literalEnd != null) { i = literalEnd; continue }
                    when (src[i]) {
                        o -> depth++
                        c -> { depth--; if (depth == 0) return i + 1 }
                    }
                    i++
                }
                throw AssertionError("$file:${line(src, open)}: '$o' never closes")
            }

            private fun line(src: String, at: Int) = src.substring(0, at).count { it == '\n' } + 1

            /** The end of the string or char literal that starts at [i], or null when none starts there. */
            private fun literal(file: String, src: String, i: Int): Int? = when {
                src.startsWith("\"\"\"", i) -> rawString(file, src, i + 3)
                src[i] == '"' -> string(file, src, i + 1)
                src[i] == '\'' -> {
                    val end = when {
                        src.getOrNull(i + 1) != '\\' -> i + 2
                        src.getOrNull(i + 2) == 'u' -> i + 7
                        else -> i + 3
                    }
                    if (end < 0 || src.getOrNull(end) != '\'') throw AssertionError("$file:${line(src, i)}: char literal never closes")
                    end + 1
                }
                else -> null
            }

            private fun string(file: String, src: String, from: Int): Int {
                var i = from
                while (i < src.length) {
                    when {
                        src[i] == '\\' -> i += 2
                        src[i] == '"' -> return i + 1
                        src.startsWith("\${", i) -> i = closing(file, src, i + 1)
                        src[i] == '\n' -> throw AssertionError("$file:${line(src, from)}: string never closes")
                        else -> i++
                    }
                }
                throw AssertionError("$file:${line(src, from)}: string never closes")
            }

            private fun rawString(file: String, src: String, from: Int): Int {
                var i = from
                while (i < src.length) {
                    when {
                        src.startsWith("\"\"\"", i) -> { i += 3; while (src.getOrNull(i) == '"') i++; return i }
                        src.startsWith("\${", i) -> i = closing(file, src, i + 1)
                        else -> i++
                    }
                }
                throw AssertionError("$file:${line(src, from)}: raw string never closes")
            }

            /** The end of the expression body whose `=` is at [eq]: past its brackets, on through lines that go on with `.`, `?.` or `?:`, up to a `;`. */
            fun expressionEnd(file: String, src: String, eq: Int): Int {
                var i = eq + 1
                while (i < src.length && src[i].isWhitespace()) i++
                while (i < src.length) {
                    val literalEnd = literal(file, src, i)
                    if (literalEnd != null) { i = literalEnd; continue }
                    when (src[i]) {
                        '(', '{' -> i = closing(file, src, i)
                        ')', '}', ';' -> return i
                        '\n' -> {
                            var k = i
                            while (k < src.length && src[k].isWhitespace()) k++
                            if (src.startsWith(".", k) || src.startsWith("?.", k) || src.startsWith("?:", k)) i = k else return i
                        }
                        else -> i++
                    }
                }
                return i
            }

            /** [src] with the inside of every string blanked (a char holds no call); quotes and newlines stay, so it still scans as code. */
            fun blankLiterals(file: String, src: String): String {
                val out = StringBuilder(src)
                var i = 0
                while (i < src.length) {
                    val end = literal(file, src, i)
                    if (end == null) { i++; continue }
                    if (src[i] == '\'') { i = end; continue }
                    val quote = if (src.startsWith("\"\"\"", i)) 3 else 1
                    for (k in i + quote until end - quote) if (out[k] != '\n') out[k] = ' '
                    i = end
                }
                return out.toString()
            }

            /** [src] with every comment's characters blanked (newlines kept, so lines still count). */
            fun blankComments(file: String, src: String): String {
                val out = StringBuilder(src)
                fun blank(from: Int, to: Int) { for (k in from until to) if (out[k] != '\n') out[k] = ' ' }
                var i = 0
                while (i < src.length) {
                    when {
                        src.startsWith("//", i) -> { val end = src.indexOf('\n', i).let { if (it < 0) src.length else it }; blank(i, end); i = end }
                        src.startsWith("/*", i) -> {
                            var depth = 0
                            var k = i
                            while (true) {
                                if (k >= src.length) throw AssertionError("$file:${line(src, i)}: comment never closes")
                                if (src.startsWith("/*", k)) { depth++; k += 2 } else if (src.startsWith("*/", k)) { depth--; k += 2; if (depth == 0) break } else k++
                            }
                            blank(i, k); i = k
                        }
                        else -> i = literal(file, src, i) ?: (i + 1)
                    }
                }
                return out.toString()
            }
        }
    }

    private val all by lazy { Scan(sources.associate { it.name to it.readText() }.also { check(it.size == sources.size) { "two app files share a name" } }).sheets }

    private fun scan(vararg files: Pair<String, String>) = Scan(mapOf(*files)).sheets.associate { it.id to it.scrolls }

    @Test
    fun theScanFindsEverySheetAndItsScroll() {
        val src = """
            import androidx.compose.material3.ModalBottomSheet
            // ModalBottomSheet( in a comment
            @Composable fun A() { ModalBottomSheet(onDismissRequest = {}) { Column(Modifier.testTag("a")) { Text("x") } } }
            @Composable fun B() {
                ModalBottomSheet(onDismissRequest = { close(")") }, containerColor = c) {
                    Column(Modifier.verticalScroll(rememberScrollState()).testTag("b")) { Text("y${'$'}{f("}")}") }
                }
            }
            @Composable fun C() = ModalBottomSheet(onDismissRequest = {}) { Body() }
            @Composable fun Body() { LazyColumn(Modifier.heightIn(max = 300.dp)) {} }
        """.trimIndent()
        val found = Scan(mapOf("T.kt" to src)).sheets
        assertEquals(listOf("a" to 3, "b" to 5, "C" to 9), found.map { it.id to it.line })
        assertEquals(listOf(false, true, true), found.map { it.scrolls })
    }

    @Test
    fun aQuoteInACommentDoesNotRunTheContentIntoTheRestOfTheFile() {
        val src = """
            @Composable fun A() {
                ModalBottomSheet(onDismissRequest = {}) {
                    // the "close button
                    Column(Modifier.testTag("a")) { Text("x") }
                }
            }
            @Composable fun B() { Column(Modifier.verticalScroll(rememberScrollState())) {} }
        """.trimIndent()
        assertEquals(mapOf("a" to false), scan("T.kt" to src))
    }

    @Test
    fun aScrollerInACommentIsNoScroll() {
        val src = """
            @Composable fun A() { ModalBottomSheet(onDismissRequest = {}) { Column(Modifier.testTag("a")) { /* verticalScroll( later */ Text("x") } } }
        """.trimIndent()
        assertEquals(mapOf("a" to false), scan("T.kt" to src))
    }

    @Test
    fun bracketsThatNeverCloseFailTheScan() {
        assertFailsWith<AssertionError> { scan("T.kt" to """@Composable fun A() { ModalBottomSheet(onDismissRequest = {}) { Column { Text("x") } }""") }
        assertFailsWith<AssertionError> { scan("T.kt" to """@Composable fun A() { ModalBottomSheet(onDismissRequest = {}) { Text("x) } }""") }
    }

    @Test
    fun aSheetOpenedInsideTheContentIsNotItsScroll() {
        val src = """
            @Composable fun A() {
                ModalBottomSheet(onDismissRequest = {}) {
                    Column(Modifier.testTag("outer")) {
                        Text("x")
                        if (more) ModalBottomSheet(onDismissRequest = {}) { Column(Modifier.verticalScroll(s).testTag("inner")) {} }
                    }
                }
            }
        """.trimIndent()
        assertEquals(mapOf("outer" to false, "inner" to true), scan("T.kt" to src))
    }

    @Test
    fun aComposableThatOnlyOpensAScrollingSheetIsNotTheContentsScroll() {
        val src = """
            @Composable fun A() { ModalBottomSheet(onDismissRequest = {}) { Column(Modifier.testTag("a")) { Opener() } } }
            @Composable fun Opener() {
                var open by remember { mutableStateOf(false) }
                Text("open", Modifier.clickable { open = true })
                if (open) ModalBottomSheet(onDismissRequest = {}) { Column(Modifier.verticalScroll(s).testTag("opened")) {} }
            }
        """.trimIndent()
        assertEquals(mapOf("a" to false, "opened" to true), scan("T.kt" to src))
    }

    @Test
    fun aNameDefinedInSeveralFilesCountsOnlyAsTheCallingFileDefinesIt() {
        val scrolling = """@Composable fun Body() { Column(Modifier.verticalScroll(s)) {} }"""
        val fixed = """@Composable fun Body() { Column {} }"""
        val sheet = """@Composable fun A() { ModalBottomSheet(onDismissRequest = {}) { Column(Modifier.testTag("a")) { Body() } } }"""
        // another file's Body scrolls, but the sheet's file has its own that does not
        assertEquals(mapOf("a" to false), scan("A.kt" to sheet + "\n" + fixed, "B.kt" to scrolling))
        // two other files define Body: no evidence which one is called
        assertEquals(mapOf("a" to false), scan("A.kt" to sheet, "B.kt" to scrolling, "C.kt" to fixed))
        // the sheet's own file defines the scrolling one, or it is the only one
        assertEquals(mapOf("a" to true), scan("A.kt" to sheet + "\n" + scrolling, "C.kt" to fixed))
        assertEquals(mapOf("a" to true), scan("A.kt" to sheet, "B.kt" to scrolling))
    }

    @Test
    fun aHelperWhoseExpressionBodyStartsOnTheNextLineIsRead() {
        val src = """
            @Composable fun A() { ModalBottomSheet(onDismissRequest = {}) { Column(Modifier.testTag("a")) { Body() } } }
            @Composable fun Body() =
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    item { Text("x") }
                }
            @Composable fun Fixed() =
                Column {}
                    .let { it }
            @Composable fun Other() { LazyColumn {} }
            @Composable fun B() { ModalBottomSheet(onDismissRequest = {}) { Column(Modifier.testTag("b")) { Fixed() } } }
            @Composable fun Chained() =
                Wrapper()
                    .verticalScroll(s)
            @Composable fun C() { ModalBottomSheet(onDismissRequest = {}) { Column(Modifier.testTag("c")) { Chained() } } }
        """.trimIndent()
        assertEquals(mapOf("a" to true, "b" to false, "c" to true), scan("T.kt" to src))
    }

    @Test
    fun anExpressionBodyEndsAtASemicolon() {
        val src = """
            @Composable fun A() { ModalBottomSheet(onDismissRequest = {}) { Column(Modifier.testTag("a")) { Fixed() } } }
            @Composable fun Fixed() = Text("fixed"); @Composable fun Unused() { LazyColumn {} }
        """.trimIndent()
        assertEquals(mapOf("a" to false), scan("T.kt" to src))
    }

    @Test
    fun anOverloadThatScrollsIsNoEvidenceForOneThatDoesNot() {
        val sheet = """@Composable fun A() { ModalBottomSheet(onDismissRequest = {}) { Column(Modifier.testTag("a")) { Body() } } }"""
        val fixed = """@Composable fun Body() { Column {} }"""
        val scrolling = """@Composable fun Body(items: List<String>) { LazyColumn {} }"""
        assertEquals(mapOf("a" to false), scan("A.kt" to listOf(sheet, fixed, scrolling).joinToString("\n")))
        // every overload scrolls: whichever is called, the content scrolls
        val alsoScrolling = """@Composable fun Body() { Column(Modifier.verticalScroll(s)) {} }"""
        assertEquals(mapOf("a" to true), scan("A.kt" to listOf(sheet, alsoScrolling, scrolling).joinToString("\n")))
    }

    @Test
    fun aScrollerNamedInAStringIsNoScroll() {
        val src = """
            @Composable fun A() { ModalBottomSheet(onDismissRequest = {}) { Column(Modifier.testTag("a")) { Text("Use LazyColumn(") ; Text(""${'"'}"verticalScroll(""${'"'}") ; Text("Body(") } } }
            @Composable fun B() { ModalBottomSheet(onDismissRequest = {}) { LazyColumn(Modifier.testTag("b")) {} } }
            @Composable fun Body() { LazyColumn {} }
            @Composable fun C() { ModalBottomSheet(onDismissRequest = {}) { Column(Modifier.testTag("c")) { Hint() } } }
            @Composable fun Hint() { Text("scroll with verticalScroll(") }
        """.trimIndent()
        assertEquals(mapOf("a" to false, "b" to true, "c" to false), scan("T.kt" to src))
    }

    @Test
    fun everySheetScrolls() {
        assertTrue(all.size >= 40, "the scan found the app's sheets (${all.size})")
        val bad = all.filter { s -> !s.scrolls && allowed.none { (f, id) -> f == s.file && id == s.id } }
            .map { "${it.file}:${it.line} ${it.id}" }
        assertEquals(
            emptyList(), bad,
            "a sheet's content scrolls (verticalScroll on its column, a bounded list inside), or it is listed in " +
                "androidApp/sheet-scroll-allowed.txt with the reason it is fixed height by design",
        )
    }

    @Test
    fun everyAllowlistEntryNamesAFixedSheet() {
        val stale = allowed.filter { (f, id) -> all.none { it.file == f && it.id == id && !it.scrolls } }.map { (f, id) -> "$f $id" }
        assertEquals(emptyList(), stale, "sheet-scroll-allowed.txt entries that match no fixed sheet: remove them")
    }
}
