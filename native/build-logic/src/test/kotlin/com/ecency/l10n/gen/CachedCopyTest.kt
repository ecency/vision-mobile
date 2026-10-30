package com.ecency.l10n.gen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Copy is read from the catalog when it is shown, so a live language switch reaches it. This scans the shared core for
 * the ways a catalog read gets cached and fails on any:
 * - a stored property of an object, a companion or a class initialized from `L.` (instead of `get() =`), the whole
 *   initializer read up to where its brackets close;
 * - `by lazy` over `L.`;
 * - an enum constructor argument or a class constructor default that reads `L.`;
 * - a `MutableStateFlow` whose initial value reads `L.`.
 * A read inside a lambda (`{ L.x }`, a label map of lambdas) runs when called and is fine. It needs no list of copy,
 * so new copy is covered too.
 */
class CachedCopyTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { it.resolve("native/shared/src/commonMain").isDirectory }

    @Test
    fun noCatalogReadIsCached() {
        val found = mutableListOf<String>()
        root.resolve("native/shared/src/commonMain").walkTopDown().filter { it.isFile && it.extension == "kt" && !it.path.contains("/core/l10n/") }.forEach { f ->
            found += scan(f.readText()).map { (line, why) -> "${f.relativeTo(root)}:$line: $why" }
        }
        assertTrue(found.isEmpty(), "cached catalog reads (use `get() =`, a lambda, or read when shown):\n" + found.joinToString("\n"))
    }

    @Test
    fun theScanFindsEachCachedShape() {
        val src = """
            object A {
                val X = L.post.words
                val OK: String get() = L.post.words
                val Y by lazy { L.post.words }
                fun f() = L.post.words
                private val MAP: Map<String, () -> String> = mapOf("a" to { L.post.words })
                val LIST = listOf(
                    "x",
                    L.post.words,
                )
            }
            class B(val title: String = L.alert.fail, val n: Int = 1) {
                companion object {
                    val Z: String = L.alert.fail
                }
                val state = MutableStateFlow(S(text = L.alert.fail))
                val text = "${'$'}{L.alert.fail}!"
                val shown: String get() = L.alert.fail
                fun g() { val local = L.alert.fail }
            }
            enum class E(val label: String, private val t: () -> String) {
                ONE(L.post.words, { L.post.words }),
                TWO("two", { L.post.words }),
                ;
            }
            val s = "L.post.words in a string"
        """.trimIndent()
        val lines = scan(src).map { it.first }.distinct().sorted()
        assertEquals(listOf(2, 4, 9, 12, 14, 16, 17, 22), lines)
    }

    private val read = Regex("""\bL\.[a-z]""")

    /** (1-based line, reason) of every cached catalog read in [text]. */
    internal fun scan(text: String): List<Pair<Int, String>> {
        val code = blank(text)
        val out = sortedSetOf<Pair<Int, String>>(compareBy({ it.first }, { it.second }))
        fun line(at: Int) = code.substring(0, at).count { it == '\n' } + 1
        fun isRead(i: Int) = read.matchesAt(code, i) && (i == 0 || !(code[i - 1].isLetterOrDigit() || code[i - 1] == '.' || code[i - 1] == '_'))
        // every catalog read outside a lambda between [from] and [to]
        fun reads(from: Int, to: Int): List<Int> {
            val hits = mutableListOf<Int>()
            var braces = 0
            for (i in from until to) {
                when (code[i]) {
                    '{' -> braces++
                    '}' -> braces--
                }
                if (braces == 0 && isRead(i)) hits += i
            }
            return hits
        }
        // the end of an expression starting at [from]: its own closing bracket, or the line end outside brackets
        fun exprEnd(from: Int): Int {
            var depth = 0
            var i = from
            while (i < code.length) {
                val c = code[i]
                if (c == '(' || c == '[' || c == '{') depth++
                if (c == ')' || c == ']' || c == '}') { if (depth == 0) return i; depth-- }
                if ((c == '\n' || c == ';') && depth == 0) {
                    val next = code.substring(i + 1).trimStart()
                    if (c == ';' || !(next.startsWith(".") || next.startsWith("?:") || next.startsWith("+") || next.startsWith("?."))) return i
                }
                i++
            }
            return code.length
        }
        val member = Regex("""((private|internal|public|protected|override|open|const|lateinit)\s+)*val\s+\w+(\s*:\s*[^=\n]+?)?\s*(=|by\s+lazy)""")
        val kinds = ArrayDeque<String>()
        var stmtStart = 0
        var parens = 0
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '{' -> {
                    val head = code.substring(stmtStart, i)
                    val kind = when {
                        Regex("""\benum\s+class\b""").containsMatchIn(head) -> "enum"
                        Regex("""\b(object|class|interface)\b""").containsMatchIn(head) && !Regex("""=\s*object\b|\bfun\b""").containsMatchIn(head) -> "type"
                        else -> "code"
                    }
                    if (kind != "code") classDefaults(head, stmtStart, ::exprEnd, ::reads).forEach { out += line(it) to "constructor default reads the catalog once" }
                    if (kind == "enum") {
                        var depth = 0
                        var j = i + 1
                        while (j < code.length) {
                            val ch = code[j]
                            if (ch == '(' || ch == '{') depth++
                            if (ch == ')' || ch == '}') depth--
                            if ((ch == ';' && depth == 0) || depth < 0) break
                            j++
                        }
                        reads(i + 1, j).forEach { out += line(it) to "enum constructor reads the catalog once" }
                    }
                    kinds.addLast(kind)
                    stmtStart = i + 1
                }
                c == '}' -> { kinds.removeLastOrNull(); stmtStart = i + 1 }
                c == '(' || c == '[' -> parens++
                c == ')' || c == ']' -> parens--
                c == ';' -> stmtStart = i + 1
                // a line break ends a statement unless it is inside brackets or a header goes on (`:` or `,`)
                c == '\n' && parens == 0 && !code.substring(stmtStart, i).trimEnd().let { it.endsWith(":") || it.endsWith(",") } &&
                    !code.substring(i + 1).trimStart().startsWith(":") -> stmtStart = i + 1
                kinds.lastOrNull() == "type" && code.substring(stmtStart, i).isBlank() -> {
                    member.matchAt(code, i)?.takeIf { !it.value.contains("get()") }?.let { m ->
                        val init = m.range.last + 1
                        val end = exprEnd(init)
                        if (m.value.endsWith("lazy")) {
                            if (read.containsMatchIn(code.substring(init, end))) out += line(i) to "lazy catalog read"
                        } else {
                            reads(init, end).forEach { out += line(it) to "stored property reads the catalog once" }
                        }
                    }
                }
            }
            i++
        }
        // top level classes without a body still have constructor defaults
        Regex("""\bclass\s+\w+(<[^>]*>)?\s*((private|internal|public|protected)\s+)?(constructor\s*)?\(""").findAll(code).forEach { m ->
            val close = exprEnd(m.range.last + 1)
            if (!code.substring(close + 1).trimStart().startsWith("{")) reads(m.range.last + 1, close).forEach { out += line(it) to "constructor default reads the catalog once" }
        }
        Regex("""by\s+lazy\s*\{""").findAll(code).forEach { m ->
            val end = exprEnd(m.range.last + 1)
            if (read.containsMatchIn(code.substring(m.range.last, end))) out += line(m.range.first) to "lazy catalog read"
        }
        Regex("""MutableStateFlow\s*\(""").findAll(code).forEach { m ->
            reads(m.range.last + 1, exprEnd(m.range.last + 1)).forEach { out += line(it) to "state flow starts with a catalog read" }
        }
        return out.toList()
    }

    /** Reads in the constructor parameter list of a class header [head] that starts at [at] in the code. */
    private fun classDefaults(head: String, at: Int, exprEnd: (Int) -> Int, reads: (Int, Int) -> List<Int>): List<Int> {
        val m = Regex("""\bclass\s+\w+(<[^>]*>)?\s*((private|internal|public|protected)\s+)?(constructor\s*)?\(""").find(head) ?: return emptyList()
        val open = at + m.range.last
        return reads(open + 1, exprEnd(open + 1))
    }

    /** Comments, string text and char literals blanked (line breaks and template code kept). */
    private fun blank(s: String): String {
        val b = StringBuilder(s.length)
        fun keep(c: Char) { b.append(if (c == '\n') '\n' else ' ') }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                s.startsWith("/*", i) -> { val e = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }; for (k in i until e) keep(s[k]); i = e }
                s.startsWith("//", i) -> while (i < s.length && s[i] != '\n') { b.append(' '); i++ }
                c == '"' -> i = string(s, i, b)
                c == '\'' -> {
                    val e = if (i + 1 < s.length && s[i + 1] == '\\') s.indexOf('\'', i + 3) else i + 2
                    if (e in (i + 2) until s.length && s[e] == '\'') { b.append('\''); for (k in i + 1 until e) b.append(' '); b.append('\''); i = e + 1 } else { b.append(c); i++ }
                }
                else -> { b.append(c); i++ }
            }
        }
        return b.toString()
    }

    /** Blanks the string literal at [start] into [b] (template code kept); returns the index after it. */
    private fun string(s: String, start: Int, b: StringBuilder): Int {
        val raw = s.startsWith("\"\"\"", start)
        val q = if (raw) "\"\"\"" else "\""
        b.append(q)
        var i = start + q.length
        while (i < s.length) {
            if (s.startsWith(q, i)) { b.append(q); return i + q.length }
            val c = s[i]
            if (!raw && c == '\\') { b.append("  "); i += 2; continue }
            if (c == '$' && i + 1 < s.length && s[i + 1] == '{') {
                // the template's own braces become parentheses: they are not a lambda
                b.append(" (")
                i += 2
                var depth = 1
                while (i < s.length) {
                    val d = s[i]
                    if (d == '"') { i = string(s, i, b); continue }
                    if (d == '{') depth++
                    if (d == '}') depth--
                    if (depth == 0) { b.append(')'); i++; break }
                    b.append(d); i++
                }
                continue
            }
            b.append(if (c == '\n') '\n' else ' '); i++
        }
        return i
    }
}
