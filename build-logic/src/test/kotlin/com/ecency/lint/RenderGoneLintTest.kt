package com.ecency.lint

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A web view's renderer can crash or be ended by the system under memory pressure; Android then ends the whole app
 * unless the view's client answers `onRenderProcessGone` (issue #3701). Every `WebViewClient` the Android app creates
 * overrides it (through `RenderGone.handle`).
 */
class RenderGoneLintTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { it.resolve("src/config/locales/en-US.json").isFile }

    /** The body of each `object : WebViewClient() { ... }` in [text], by balanced braces, with its line. */
    internal fun clients(text: String): List<Pair<Int, String>> =
        Regex("""(?:object|class\s+\w+(?:\([^)]*\))?)\s*:\s*WebViewClient(?:Compat)?\(\)\s*\{""").findAll(text).map { m ->
            var depth = 1
            var i = m.range.last + 1
            while (i < text.length && depth > 0) {
                when (text[i]) { '{' -> depth++; '}' -> depth-- }
                i++
            }
            text.substring(0, m.range.first).count { it == '\n' } + 1 to text.substring(m.range.last + 1, i)
        }.toList()

    @Test
    fun everyWebViewClientSurvivesItsRenderer() {
        val bad = root.resolve("androidApp/src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.flatMap { f ->
            clients(f.readText()).filter { (_, body) -> "onRenderProcessGone" !in body || "RenderGone.handle" !in body }.map { (line, _) -> "${f.relativeTo(root)}:$line" }
        }.toList()
        assertEquals(emptyList(), bad, "a WebViewClient without onRenderProcessGone (RenderGone.handle): a renderer crash ends the app")
    }

    /** iOS: every web view delegate answers a web process that ended, so the page never stays blank. */
    @Test
    fun everyIosWebViewDelegateAnswersAnEndedProcess() {
        val delegate = Regex("""(?:class|struct|extension)\s+\w+\s*:[^{]*\bWKNavigationDelegate\b""")
        val bad = root.resolve("iosApp/EcencyApp").walkTopDown().filter { it.isFile && it.extension == "swift" }.sortedBy { it.path }.flatMap { f ->
            val text = f.readText()
            delegate.findAll(text).map { m ->
                var depth = 0
                var i = text.indexOf('{', m.range.last)
                val start = i
                do {
                    when (text[i]) { '{' -> depth++; '}' -> depth-- }
                    i++
                } while (depth > 0 && i < text.length)
                val line = text.substring(0, m.range.first).count { it == '\n' } + 1
                line to text.substring(start, i)
            }.filter { (_, body) -> "webViewWebContentProcessDidTerminate" !in body }.map { (line, _) -> "${f.relativeTo(root)}:$line" }
        }.toList()
        assertEquals(emptyList(), bad, "a WKNavigationDelegate without webViewWebContentProcessDidTerminate: the page stays blank")
    }

    @Test
    fun theScanBites() {
        val text = """
            val a = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean { return true }
            }
            val b = object : WebViewClient() {
                override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean = RenderGone.handle(v) { }
            }
            class Named(x: Int) : WebViewClientCompat() {
            }
        """.trimIndent()
        val found = clients(text)
        assertEquals(3, found.size)
        assertEquals(listOf(1, 7), found.filter { "RenderGone.handle" !in it.second }.map { it.first })
    }
}
