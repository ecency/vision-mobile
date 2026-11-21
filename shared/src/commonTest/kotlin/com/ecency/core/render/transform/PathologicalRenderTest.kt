package com.ecency.core.render.transform

import com.ecency.core.render.LinearScaling
import com.ecency.core.render.RenderHelper
import com.ecency.core.render.methods.Traverse
import kotlin.test.Test
import kotlin.test.assertEquals
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

    /** render-helper 2.5.45 (#1920): an adjacent run of tags is linked in one pass, at any length (no nesting). */
    @Test
    fun aTagChainPastTheDepthLimitIsLinkedWhole() {
        val count = Traverse.MAX_DEPTH + 200
        val out = helper.renderPostBody("#a".repeat(count), true)
        assertEquals(count, Regex("markdown-tag-link").findAll(out).count())
    }

    /**
     * render-helper 2.5.45 (#1920): past [Traverse.MAX_DEPTH] levels a subtree is reduced to its plain text instead of
     * being walked, so the rest of the body is still transformed and nothing below the cap ships untransformed.
     */
    @Test
    fun nestingPastTheDepthLimitIsFlattenedToText() {
        val depth = Traverse.MAX_DEPTH + 50
        val md = "<div>".repeat(depth) + "deep @spike-alice" + "</div>".repeat(depth) + "\n\nafter @spike-bob"
        val out = helper.renderPostBody(md, true)
        assertTrue("deep @spike-alice" in out, out.takeLast(300))
        assertTrue("data-author=\"spike-alice\"" !in out)
        assertTrue("data-author=\"spike-bob\"" in out, "the rest of the body is still transformed")
    }

    /** time(4n) / time(n) stays near 4 (quadratic would be 16); see [LinearScaling]. */
    private fun assertLinear(name: String, make: (Int) -> String, n: Int) =
        LinearScaling.assertLinear(name, n, make) { helper.renderPostBody(it, true) }

    @Test
    fun tagChainsPastTheLimitStayLinear() {
        // 6,000 and 24,000 tags, each run linked in one pass (render-helper 2.5.45); fixed sizes, so growth shows
        assertLinear("tag chain", { n -> "#a".repeat(n) }, 6_000)
    }

    /** The 2.5.52 port's new scanners (review of #3711): each guarded against going quadratic on author input. */
    @Test
    fun theNewScannersStayLinear() {
        // removeDuplicateAttributes' scan budget: unclosed tags whose only `>` sits inside a quoted value (the
        // render escapes this shape before it gets there, so the scanner is driven directly)
        LinearScaling.assertLinear("unclosed tags, quoted >", 4_000, { n -> "<a b ".repeat(n) + "c=\">\"" }) {
            com.ecency.core.render.dom.HtmlStringFixes.removeDuplicateAttributes(it)
        }
        // collectDeclaredIds: many author ids and links to them
        assertLinear("declared ids", { n -> (1..n).joinToString("") { "<a id=\"i$it\" href=\"#i$it\">x</a> " } }, 1_000)
        // fences inside nested quote and list containers (code entities come back from the code tokens since 2.5.53)
        assertLinear("nested fences", { n -> "> - ```\ncode &amp; x\n```\n".repeat(n) }, 1_000)
        // EntityProtection: URL runs with parentheses and entities
        assertLinear("entity url runs", { n -> "https://x.example/(a&amp;b ".repeat(n) }, 2_000)
    }

    /** The 2.5.53 and 2.5.54 port's new scanners (#2154, #2155): each guarded against going quadratic on author input. */
    @Test
    fun the2554ScannersStayLinear() {
        val untransformed = com.ecency.core.render.sanitize.SanitizeOptions(proxyBase = RenderCases.PROXY_BASE, untransformed = true)
        fun sanitize(html: String) = com.ecency.core.render.sanitize.HtmlSanitizer.sanitize(html, untransformed)
        // dropRefusedIframes: refused iframes with fallback text, unclosed ones, nested kept pairs
        LinearScaling.assertLinear("refused iframes", 1_000, { n -> "<iframe src=\"https://evil.example.com/x\">fb</iframe>".repeat(n) }) { sanitize(it) }
        LinearScaling.assertLinear("unclosed refused iframes", 1_000, { n -> "<iframe src=\"https://evil.example.com/x\">a <b>b</b> ".repeat(n) + "</iframe>" }) { sanitize(it) }
        LinearScaling.assertLinear("nested kept iframes", 1_000, { n ->
            "<iframe src=\"https://www.youtube.com/embed/abcdefghijk\">".repeat(n) + "</iframe>x<iframe src=\"https://evil.example.com/x\">y".repeat(n)
        }) { sanitize(it) }
        // angle destinations and angle reference definitions, read back from the source
        assertLinear("angle links", { n -> "[x](<https://e.example/a b?q=&amp;amp;>) ".repeat(n) }, 1_000)
        // one definition per paragraph: Remarkable re-slices a paragraph's rest after each of its definitions
        // (quadratic in JS too), which is not the angle rule's cost
        assertLinear("angle definitions", { n -> (1..n).joinToString("") { "[r$it]: <https://e.example/a b?q=&amp;amp;>\n\n" } + "[x][r1]" }, 1_000)
        // placeholderDelimiter's two-character fallback: every single delimiter taken, many entities
        val taken = "\u200B\u2060\u2061\u2062\u2063\u2064" + (0xE100..0xF8FF).map { it.toChar() }.joinToString("")
        assertLinear("pair delimiter", { n -> taken + "\n\n" + "x &copy; y\t&amp;\tz ".repeat(n) }, 2_000)
        // expandTabsLikeRemarkable: tabs after entities on many lines
        assertLinear("tabs after entities", { n -> "a&lt;\tb\t&amp;\tc\n".repeat(n) }, 2_000)
    }

    /** The 2.5.55 port's new scanners (#2156 to #2160, #2170): each guarded against going quadratic on author input. */
    @Test
    fun the2555ScannersStayLinear() {
        val untransformed = com.ecency.core.render.sanitize.SanitizeOptions(proxyBase = RenderCases.PROXY_BASE, untransformed = true)
        fun sanitize(html: String) = com.ecency.core.render.sanitize.HtmlSanitizer.sanitize(html, untransformed)
        // wholeIframeElements: deep and many nested bare iframes, other tags between
        LinearScaling.assertLinear("nested bare iframes", 2_000, { n -> "<iframe>a".repeat(n) + "</iframe>b".repeat(n) }) { sanitize(it) }
        LinearScaling.assertLinear("iframes and tags", 1_000, { n -> "<iframe>a<b>x</b><iframe>c</iframe></iframe>".repeat(n) }) { sanitize(it) }
        // withDecodedLinkify: one text token of many URLs and entities on the app render
        assertLinear("linkify over entities", { n -> "see https://e.example/a)&amp;x &copy; www.e.example/q?x=1&y; ".repeat(n) }, 1_000)
        // withAutolinkSource and plainForm: many autolinks with entities and spaces
        assertLinear("autolinks", { n -> "<https://e.example/a?q=&copy;&amp;z=1> <a@b.example> ".repeat(n) }, 1_000)
        // the card's bare URL reading (autolinkUrlEnd, strayHashBefore, markdownTextSpan), as upstream's own spec
        val card = { body: String -> helper.getEntryCardImageRawUrl(com.ecency.core.render.Entry(body = body)) }
        LinearScaling.assertLinear("card emphasis runs", 5_000, { n -> "*".repeat(n) + "https://e.example/x.png" + ("*".repeat(n - 1) + "a").repeat(2) }) { card(it) }
        LinearScaling.assertLinear("card parentheses", 5_000, { n -> "x # y https://e.example/p.png?a=" + "(".repeat(n) + ")".repeat(n + 1) }) { card(it) }
        LinearScaling.assertLinear("card long host", 5_000, { n -> "(https://" + "a-b.".repeat(n) + "example/p.png)" }) { card(it) }
    }

    /** Reference definitions in one paragraph: render-helper 2.5.54 renders them in linear time on both renders. */
    @Test
    fun definitionsInOneParagraphStayLinear() {
        val make = { n: Int -> (0 until n).joinToString("\n") { "[r$it]: <https://e.example/p$it?a=1&amp;b=2>" } + "\n\n[x][r0]" }
        LinearScaling.assertLinear("one-paragraph definitions (app)", 4_000, make) { helper.renderPostBody(it, true) }
        LinearScaling.assertLinear("one-paragraph definitions (web)", 4_000, make) { helper.renderPostBody(it, false) }
        val plain = { n: Int -> (0 until n).joinToString("\n") { "[r$it]: https://e.example/p$it?a=1" } + "\n\n[x][r0]" }
        LinearScaling.assertLinear("one-paragraph plain definitions", 4_000, plain) { helper.renderPostBody(it, true) }
    }

    @Test
    fun mentionChainsStayLinear() {
        assertLinear("mention chain", { n -> "@" + "a.".repeat(n) + "a" }, 20_000)
        assertLinear("mention repeat", { n -> "@a.".repeat(n) }, 10_000)
        // a run whose first mention is refused (17 characters): the run is not collected past it (review of #3714)
        assertLinear("refused mention runs", { n -> "@aaaaaaaaaaaaaaaaa#-".repeat(n) }, 2_000)
    }

    @Test
    fun deepMarkupStaysLinear() {
        assertLinear("nested spans", { n -> "<span>".repeat(n) + "x" + "</span>".repeat(n) }, 4_000)
    }
}
