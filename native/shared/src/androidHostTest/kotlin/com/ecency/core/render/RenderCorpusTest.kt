package com.ecency.core.render

import com.ecency.core.render.sanitize.EmbedSrc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Gate 1 corpus: 500 public Hive posts rendered by the Kotlin port and by @ecency/render-helper.
 * The data is other people's content, so it is never committed: it lives in the gitignored
 * `native/tools/render/corpus/` (`node corpus.mjs fetch` then `node corpus.mjs render`), and this
 * test is skipped when the files are absent. JVM only (it reads local files).
 */
class RenderCorpusTest {
    private val dir = File(System.getenv("RENDER_CORPUS_DIR") ?: "../tools/render/corpus")

    private fun lines(name: String) = File(dir, name).readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content ?: ""

    private fun classify(expected: String, actual: String): String =
        if (expected.filterNot { it.isWhitespace() } == actual.filterNot { it.isWhitespace() }) "cosmetic (whitespace only)" else "semantic"

    /** The sanitizer's iframe check: allowed hosts, judged by the `https:` form of `//` and `http:` sources. */
    private fun allowedEmbed(src: String): Boolean {
        val t = src.trim()
        val https = when {
            t.startsWith("//") -> "https:$t"
            t.startsWith("http://", ignoreCase = true) -> "https://" + t.substring(7)
            else -> t
        }
        return EmbedSrc.isAllowedEmbedSrc(https)
    }

    private val unsupportedIframe = Regex("""<div class="unsupported-iframe">\(Unsupported ([^)<]*)\)</div>""")
    private val iframeSrc = Regex("""(<iframe\b[^>]*?) src="([^"]*)"""")

    /**
     * The deliberate iframe security deviation (README "Deliberate deviations from the oracle"):
     * render-helper keeps an off-allow-list iframe src when its iframe() transform does not run,
     * so iframe() later turns it into an "Unsupported" placeholder, or the src survives. The
     * native sanitizer drops that src on every path, so the iframe is removed or left without a
     * src. True when removing exactly those from the oracle output gives the native output.
     */
    private fun onlySecurityDeviation(expected: String, actual: String): Boolean {
        val stripped = unsupportedIframe.replace(expected) { m ->
            if (allowedEmbed(m.groupValues[1].replace("&amp;", "&"))) m.value else ""
        }.let { html ->
            iframeSrc.replace(html) { m -> if (allowedEmbed(m.groupValues[2].replace("&amp;", "&"))) m.value else m.groupValues[1] }
        }
        return stripped != expected && stripped == actual
    }

    private fun pct(sorted: List<Double>, p: Double) = sorted[((sorted.size - 1) * p).toInt()]

    @Test
    fun corpusMatchesRenderHelper() {
        val postsFile = File(dir, "posts.jsonl")
        val expectedFile = File(dir, "expected.jsonl")
        if (!postsFile.exists() || !expectedFile.exists()) {
            println("render corpus: skipped (no ${postsFile.path})")
            return
        }
        val posts = lines("posts.jsonl")
        val expected = lines("expected.jsonl").associateBy { it.str("key") }
        val helper = RenderHelper("https://images.ecency.com")

        // Warm up class loading and regex compilation so the timings are steady-state.
        repeat(2) { helper.renderPostBody(posts[it % posts.size].str("body"), true) }

        val renderMs = ArrayList<Double>()
        val mismatches = ArrayList<String>()
        val securityDeviations = ArrayList<String>()
        var htmlExact = 0
        var summaryExact = 0
        var imageExact = 0
        val report = StringBuilder()
        for (p in posts) {
            val key = "${p.str("author")}/${p.str("permlink")}"
            val exp = expected[key] ?: continue
            val body = p.str("body")
            val t0 = System.nanoTime()
            val html = try {
                helper.renderPostBody(body, true)
            } catch (e: Throwable) {
                "EXCEPTION $e"
            }
            renderMs.add((System.nanoTime() - t0) / 1e6)
            val summary = helper.postBodySummary(body, 200)
            val entry = Entry(p.str("author"), p.str("permlink"), p.str("last_update"), body, p.str("json_metadata"))
            val image = helper.catchPostImage(entry) ?: ""
            fun check(field: String, e: String, a: String): Boolean {
                if (e == a) return true
                if (field == "html" && onlySecurityDeviation(e, a)) {
                    securityDeviations.add(key)
                    return true
                }
                val cls = classify(e, a)
                mismatches.add("$key $field: $cls")
                var i = 0
                while (i < e.length && i < a.length && e[i] == a[i]) i++
                report.append("== $key $field $cls at $i\n  expected: ${e.substring(maxOf(0, i - 80), minOf(e.length, i + 120))}\n" +
                    "  actual:   ${a.substring(maxOf(0, i - 80), minOf(a.length, i + 120))}\n")
                return false
            }
            if (check("html", exp.str("html"), html)) htmlExact++
            if (check("summary", exp.str("summary"), summary)) summaryExact++
            if (check("image", exp.str("image"), image)) imageExact++
        }
        val sorted = renderMs.sorted()
        println(
            "render corpus: html ${htmlExact - securityDeviations.size}/${posts.size} exact + ${securityDeviations.size} iframe security deviation, summary $summaryExact/${posts.size}, image $imageExact/${posts.size} exact; " +
                "renderPostBody ms p50=%.2f p95=%.2f max=%.2f".format(pct(sorted, 0.5), pct(sorted, 0.95), sorted.last()),
        )
        File("build/render-corpus-report.txt").writeText(report.toString())
        if (mismatches.isNotEmpty()) {
            println("mismatches (details in build/render-corpus-report.txt):\n" + mismatches.joinToString("\n"))
            fail("${mismatches.size} corpus mismatches")
        }
    }
}
