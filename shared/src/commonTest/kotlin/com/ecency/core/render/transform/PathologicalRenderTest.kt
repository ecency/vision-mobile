package com.ecency.core.render.transform

import com.ecency.core.render.LinearScaling
import com.ecency.core.render.RenderHelper
import com.ecency.core.render.dom.RenderBodyParser
import com.ecency.core.render.markdown.Markdown
import com.ecency.core.render.methods.Traverse
import com.ecency.core.render.sanitize.HtmlSanitizer
import com.ecency.core.render.sanitize.SanitizeOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Bodies that nest without bound (`#a#a#a...` wraps one span per tag; deep raw markup) and long
 * mention-like runs. Parity with render-helper where Node renders them, the documented depth
 * limit where Node overflows, and scaling checks so none of them turns quadratic again.
 */
class PathologicalRenderTest {
    private val helper = RenderHelper(RenderCases.PROXY_BASE)

    private fun fnv(s: String): String {
        var h = 0x811c9dc5.toInt()
        for (c in s) {
            h = h xor c.code
            h *= 0x01000193
        }
        return h.toUInt().toString(16)
    }

    private fun body(prefix: String, unit: String, count: Int, suffix: String) =
        StringBuilder(prefix.length + unit.length * count + suffix.length).apply {
            append(prefix)
            repeat(count) { append(unit) }
            append(suffix)
        }.toString()

    @Test
    fun pathologicalShapesMatchRenderHelper() {
        for (c in PathologicalCases.cases) {
            val out = helper.renderPostBody(body(c.prefix, c.unit, c.count, c.suffix), true)
            assertEquals(c.length to c.hash, out.length to fnv(out), c.name)
        }
    }

    /** What markdownToHTML returns when both DOM passes fail: the sanitized markdown output. */
    private fun sanitizeOnly(md: String): String {
        val opts = SanitizeOptions(proxyBase = RenderCases.PROXY_BASE)
        return RenderBodyParser.finish(HtmlSanitizer.sanitize(Markdown.render(md), opts), opts)
    }

    /**
     * ROBUSTNESS DEVIATION: a tag chain nesting deeper than [Traverse.MAX_DEPTH] but less deep than
     * V8's stack (about 5,450 tags in Node) renders sanitize-only here, linkified in Node.
     */
    @Test
    fun nestingPastTheDepthLimitFallsBackToSanitizeOnly() {
        val md = "#a".repeat(Traverse.MAX_DEPTH + 200)
        val out = helper.renderPostBody(md, true)
        assertEquals(sanitizeOnly(md), out)
        assertTrue(!out.contains("markdown-tag-link"))
        // Below the limit the chain is linkified as in render-helper.
        assertNotEquals(sanitizeOnly("#a".repeat(1000)), helper.renderPostBody("#a".repeat(1000), true))
    }

    /** time(4n) / time(n) stays near 4 (quadratic would be 16); see [LinearScaling]. */
    private fun assertLinear(name: String, make: (Int) -> String, n: Int) =
        LinearScaling.assertLinear(name, n, make) { helper.renderPostBody(it, true) }

    @Test
    fun tagChainsPastTheLimitStayLinear() {
        // 6,000 and 24,000 tags: both past the depth limit (5,000), so both take the sanitize-only path; fixed sizes,
        // so a raised or removed limit shows up as growth instead of moving the sizes with it
        assertLinear("tag chain", { n -> "#a".repeat(n) }, 6_000)
    }

    @Test
    fun mentionChainsStayLinear() {
        assertLinear("mention chain", { n -> "@" + "a.".repeat(n) + "a" }, 20_000)
        assertLinear("mention repeat", { n -> "@a.".repeat(n) }, 10_000)
    }

    @Test
    fun deepMarkupStaysLinear() {
        assertLinear("nested spans", { n -> "<span>".repeat(n) + "x" + "</span>".repeat(n) }, 4_000)
    }
}
