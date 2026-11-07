package com.ecency.lint

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Handled errors reach Sentry (README "Crash reporting", "Handled errors"). This scan of the sources fails when:
 * - an `AppError.from` / `AppError.fromBroadcast` / `ErrorReports.quiet` / `ErrorReports.handled` call (Kotlin), or a
 *   `Reports.quiet` / `Reports.handled` / `ErrorText.message` / `ErrorText.appError` / `AppError.companion.from` call
 *   (Swift), names no `where`, or names one that is not a short stable `area.action` literal (a pass-through parameter
 *   named `where` or a `...Where(...)` helper is allowed); a where built from data could carry user data;
 * - a Kotlin `catch` of `Exception` or `Throwable` (shared commonMain, the Android app) neither rethrows, maps through
 *   `AppError.from` / `fromBroadcast`, reports through `ErrorReports`, hands the failure to a helper with a where
 *   literal, nor carries a `// not reported: <reason>` line;
 * - a Swift catch-all (`catch {`) in the iOS app does none of these (`Reports.`, `ErrorText.`, `AppError`).
 * The scan reads source text with balanced brackets, no compiler.
 */
class ErrorReportLintTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { it.resolve("src/config/locales/en-US.json").isFile }

    private fun kotlinSources() = listOf("shared/src/commonMain", "shared/src/androidMain", "shared/src/iosMain", "androidApp/src/main").flatMap { dir ->
        root.resolve(dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }.sortedBy { it.path }

    private fun swiftSources() = root.resolve("iosApp/EcencyApp").walkTopDown().filter { it.isFile && it.extension == "swift" }.sortedBy { it.path }.toList()

    @Test
    fun everyReportNamesAStableWhere() {
        val bad = kotlinSources().flatMap { f -> ErrorReportLint.kotlinWheres(f.readText()).map { "${f.relativeTo(root)}:$it" } } +
            swiftSources().flatMap { f -> ErrorReportLint.swiftWheres(f.readText()).map { "${f.relativeTo(root)}:$it" } }
        assertEquals(emptyList(), bad, "a report without a stable `area.action` where (README \"Crash reporting\")")
    }

    @Test
    fun everyCatchReportsRethrowsOrSaysWhyNot() {
        val bad = kotlinSources().flatMap { f -> ErrorReportLint.kotlinCatches(f.readText()).map { "${f.relativeTo(root)}:$it" } } +
            swiftSources().flatMap { f -> ErrorReportLint.swiftCatches(f.readText()).map { "${f.relativeTo(root)}:$it" } }
        assertEquals(emptyList(), bad, "a catch that swallows a failure: report it (ErrorReports.quiet / AppError.from) or add `// not reported: <reason>`")
    }

    @Test
    fun theScanBites() {
        val kotlin = """
            fun a() = try { x() } catch (e: Exception) { null }
            fun b() = try { x() } catch (e: Exception) {
                // not reported: untrusted JSON
                null
            }
            fun c() = try { x() } catch (e: Exception) { ErrorReports.quiet(e, "feed.refresh") }
            fun d() = try { x() } catch (e: Throwable) { throw e }
            fun e() = try { x() } catch (e: Exception) { AppError.from(e) }
            fun f() = try { x() } catch (e: Exception) { AppError.from(e, "post.${'$'}author") }
            fun g() = try { x() } catch (e: Exception) { toast(ChatsModel.message(e, FAIL, "chats.load")) }
            fun h() = try { x() } catch (e: Exception) { if (fatal(e)) throw e; null }
            fun i() = try { x() } catch (e: Exception) { open(url, "ecency.com") }
            fun j() = try { x() } catch (e: java.io.IOException) { null }
            fun k() = try { x() } catch (e: Exception) { cleanup(); throw IllegalStateException("x", e) }
            fun l() = try { x() } catch (e: IllegalArgumentException) { null }
        """.trimIndent()
        assertEquals(listOf(1, 11, 12, 13, 15), ErrorReportLint.kotlinCatches(kotlin))
        assertEquals(listOf(8, 9), ErrorReportLint.kotlinWheres(kotlin))
        val swift = """
            do { try a() } catch { ok = false }
            do { try a() } catch { Reports.quiet(error, where: "listen.renew") }
            do { try a() } catch {
                // not reported: the store reports it
            }
            let m = ErrorText.message(error)
            let n = ErrorText.message(error, where: name)
            do { try a() } catch { let x = AppErrorView(); show(x) }
            do { try a() } catch let failure { log(failure) }
            do { try a() } catch let failure { throw failure }
        """.trimIndent()
        assertEquals(listOf(1, 8, 9), ErrorReportLint.swiftCatches(swift))
        assertEquals(listOf(6, 7), ErrorReportLint.swiftWheres(swift))
        assertTrue(ErrorReportLint.isWhere("\"editor.publish\""))
        assertTrue(!ErrorReportLint.isWhere("\"Editor publish\""))
    }
}

/** The scanner of [ErrorReportLintTest]: each function returns the 1-based lines that break the rule. */
object ErrorReportLint {
    private val WHERE = Regex("^\"[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)+\"$")

    fun isWhere(arg: String): Boolean {
        val a = arg.trim()
        return WHERE.matches(a) || a == "where" || a == "`where`" || Regex("^[a-z]\\w*Where\\(.*\\)$").matches(a)
    }

    private fun lineAt(src: String, at: Int) = src.substring(0, at).count { it == '\n' } + 1

    private fun isComment(src: String, at: Int): Boolean {
        val before = src.substring(src.lastIndexOf('\n', at - 1) + 1, at).trimStart()
        return before.startsWith("//") || before.startsWith("*") || before.startsWith("/*")
    }

    /** The text between the bracket at [open] and its match, skipping strings. */
    private fun balanced(src: String, open: Int, o: Char, c: Char): String {
        var depth = 0
        var i = open
        var inString = false
        while (i < src.length) {
            val ch = src[i]
            if (inString) {
                if (ch == '\\') i++ else if (ch == '"') inString = false
            } else when (ch) {
                '"' -> inString = true
                o -> depth++
                c -> { depth--; if (depth == 0) return src.substring(open + 1, i) }
            }
            i++
        }
        return src.substring(open + 1)
    }

    /** Top-level arguments of a call's argument text. */
    private fun args(text: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var inString = false
        var start = 0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                if (ch == '\\') i++ else if (ch == '"') inString = false
            } else when (ch) {
                '"' -> inString = true
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) { out += text.substring(start, i); start = i + 1 }
            }
            i++
        }
        if (text.isNotBlank()) out += text.substring(start)
        return out
    }

    private val KOTLIN_CALLS = Regex("""(?<![\w.])(AppError\.(?:from|fromBroadcast)|ErrorReports\.(?:quiet|handled))\(""")

    fun kotlinWheres(src: String): List<Int> = KOTLIN_CALLS.findAll(src).filter { !isComment(src, it.range.first) }.mapNotNull { m ->
        val a = args(balanced(src, m.range.last, '(', ')'))
        if (a.size >= 2 && isWhere(a[1])) null else lineAt(src, m.range.first)
    }.toList()

    private val SWIFT_CALLS = Regex("""(?<![\w])(Reports\.(?:quiet|handled)|ErrorText\.(?:message|appError)|AppError\.companion\.(?:from|fromBroadcast)|ErrorReports\.shared\.(?:quiet|handled))\(""")

    fun swiftWheres(src: String): List<Int> = SWIFT_CALLS.findAll(src).filter { !isComment(src, it.range.first) }.mapNotNull { m ->
        val where = args(balanced(src, m.range.last, '(', ')')).map { it.trim() }.firstOrNull { it.startsWith("where:") }?.removePrefix("where:")
        if (where != null && isWhere(where)) null else lineAt(src, m.range.first)
    }.toList()

    /** Broad catches: Exception, Throwable, Error and the platform's general exceptions, qualified or not. */
    private val KOTLIN_CATCH = Regex(
        """catch\s*\(\s*\w+\s*:\s*(?:[\w.]+\.)?(?:Exception|Throwable|Error|RuntimeException|IOException|IllegalStateException|IllegalArgumentException)\s*\)\s*\{""",
    )
    private val MARKER = Regex("""//\s*not reported:\s*\S""")
    private const val WHERE_LITERAL = """"[a-z][a-zA-Z0-9]*(\.[a-z][a-zA-Z0-9]*)+""""

    /** The reporting calls a catch may use; a helper counts only when it is one of the known ones given a where literal. */
    private val KOTLIN_REPORTS = Regex(
        """\b(?:AppError\.(?:from|fromBroadcast)\(|ErrorReports\.(?:quiet|handled)\()|""" +
            """(?<![\w])(?:ChatsModel\.)?message\([^()]*(?:\([^()]*\)[^()]*)*,\s*$WHERE_LITERAL\s*\)""",
    )
    private val SWIFT_REPORTS = Regex("""(?<![\w])(?:Reports\.(?:quiet|handled)\(|ErrorText\.(?:message|appError)\(|AppError\.companion\.(?:from|fromBroadcast)\()""")

    /** Comments and strings dropped, so a reporting call or a `throw` inside them does not count. */
    private fun code(body: String): String =
        body.replace(Regex("""//[^\n]*"""), "").replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")

    /** The body's last top-level statement (split on new lines and `;` outside brackets). */
    private fun lastStatement(body: String): String {
        val text = code(body)
        var depth = 0
        var inString = false
        var start = 0
        var last = ""
        var i = 0
        fun cut(end: Int) { text.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let { last = it } }
        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                if (ch == '\\') i++ else if (ch == '"') inString = false
            } else when (ch) {
                '"' -> inString = true
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                '\n', ';' -> if (depth == 0) { cut(i); start = i + 1 }
            }
            i++
        }
        cut(text.length)
        return last
    }

    /** Rethrows unconditionally: its last top-level statement is a `throw`. A conditional throw must also report or say why not. */
    private fun rethrows(body: String) = lastStatement(body).startsWith("throw ")

    fun kotlinCatches(src: String): List<Int> = KOTLIN_CATCH.findAll(src).filter { !isComment(src, it.range.first) }.mapNotNull { m ->
        val body = balanced(src, m.range.last, '{', '}')
        val ok = rethrows(body) || MARKER.containsMatchIn(body) || KOTLIN_REPORTS.containsMatchIn(code(body))
        if (ok) null else lineAt(src, m.range.first)
    }.toList()

    /** Catch-alls: `catch {` and `catch let name {` (a typed `catch let x as T` or a pattern is a deliberate case). */
    private val SWIFT_CATCH = Regex("""\bcatch(?:\s+let\s+\w+)?\s*\{""")

    fun swiftCatches(src: String): List<Int> = SWIFT_CATCH.findAll(src).filter { !isComment(src, it.range.first) }.mapNotNull { m ->
        val body = balanced(src, m.range.last, '{', '}')
        val ok = rethrows(body) || MARKER.containsMatchIn(body) || SWIFT_REPORTS.containsMatchIn(code(body))
        if (ok) null else lineAt(src, m.range.first)
    }.toList()
}
