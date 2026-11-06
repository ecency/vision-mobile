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

    private fun kotlinSources() = listOf("shared/src/commonMain", "androidApp/src/main").flatMap { dir ->
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
        """.trimIndent()
        assertEquals(listOf(1), ErrorReportLint.kotlinCatches(kotlin))
        assertEquals(listOf(8, 9), ErrorReportLint.kotlinWheres(kotlin))
        val swift = """
            do { try a() } catch { ok = false }
            do { try a() } catch { Reports.quiet(error, where: "listen.renew") }
            do { try a() } catch {
                // not reported: the store reports it
            }
            let m = ErrorText.message(error)
            let n = ErrorText.message(error, where: name)
        """.trimIndent()
        assertEquals(listOf(1), ErrorReportLint.swiftCatches(swift))
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

    private val KOTLIN_CATCH = Regex("""catch\s*\(\s*\w+\s*:\s*(?:kotlin\.)?(?:Exception|Throwable)\s*\)\s*\{""")
    private val HANDED_TO_HELPER = Regex(""",\s*"[a-z][a-zA-Z0-9]*(\.[a-z][a-zA-Z0-9]*)+"\s*\)""")
    private val MARKER = Regex("""//\s*not reported:\s*\S""")

    private fun handles(body: String) = Regex("""\bthrow\b""").containsMatchIn(body) || "AppError.from" in body ||
        "ErrorReports." in body || HANDED_TO_HELPER.containsMatchIn(body) || MARKER.containsMatchIn(body)

    fun kotlinCatches(src: String): List<Int> = KOTLIN_CATCH.findAll(src).filter { !isComment(src, it.range.first) }.mapNotNull { m ->
        if (handles(balanced(src, m.range.last, '{', '}'))) null else lineAt(src, m.range.first)
    }.toList()

    private val SWIFT_CATCH = Regex("""\bcatch\s*\{""")

    fun swiftCatches(src: String): List<Int> = SWIFT_CATCH.findAll(src).filter { !isComment(src, it.range.first) }.mapNotNull { m ->
        val body = balanced(src, m.range.last, '{', '}')
        val ok = handles(body) || "Reports." in body || "ErrorText." in body || "AppError" in body
        if (ok) null else lineAt(src, m.range.first)
    }.toList()
}
