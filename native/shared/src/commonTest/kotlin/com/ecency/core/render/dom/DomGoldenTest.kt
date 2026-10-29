package com.ecency.core.render.dom

import com.ecency.core.render.LinearScaling
import com.ecency.core.render.sanitize.EmbedSrc
import com.ecency.core.render.sanitize.HtmlSanitizer
import com.ecency.core.render.sanitize.RenderText
import com.ecency.core.render.sanitize.SanitizeOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Byte-for-byte parity with @ecency/render-helper 2.5.42 (xmldom, htmlparser2, dom-serializer,
 * xss, entities). Fixtures: native/tools/render/generate-dom-fixtures.mjs.
 */
class DomGoldenTest {
    /**
     * Cases that deliberately differ from the JS oracle, with the reason. Only security fixes
     * belong here (see native/docs/upstream-render-helper-issues.md); every listed case must still
     * differ, so the set cannot go stale. Their output is held to [IframeInvariant] instead.
     */
    private val securityDeviations: Map<String, String> = run {
        val iframeSrc = "iframe src outside the embed allow list is dropped on every path; " +
            "render-helper keeps it whenever its iframe() transform does not run"
        val sandbox = "author sandbox tokens beyond the ones iframe() grants are dropped"
        mapOf(
            "raw_iframe_evil" to iframeSrc,
            "raw_iframe_attack_xmldom" to "$iframeSrc; $sandbox",
            "raw_iframe_attack_repaired" to "$iframeSrc; $sandbox",
            "raw_iframe_attack_sanitized" to "$iframeSrc; $sandbox",
            "raw_iframe_attack_protocol_relative" to iframeSrc,
            "raw_iframe_attack_lookalike" to iframeSrc,
            "raw_iframe_attack_entity_scheme" to iframeSrc,
            "raw_iframe_allowed_author_sandbox" to sandbox,
            "random_22" to iframeSrc,
            "attr_60_iframe_src" to iframeSrc,
        )
    }

    private fun options(preserve: Boolean) =
        SanitizeOptions(preserveImageDimensions = preserve, proxyBase = DomFixtures.PROXY_BASE)

    private class Mismatch(val name: String, val field: String, val expected: String, val actual: String)

    private fun report(kind: String, total: Int, mismatches: List<Mismatch>, deviationsSeen: MutableSet<String>? = null) {
        val real = mismatches.filter { it.name !in securityDeviations }
        deviationsSeen?.addAll(mismatches.map { it.name }.filter { it in securityDeviations })
        if (real.isEmpty()) return
        val details = real.take(12).joinToString("\n\n") { m ->
            "[${m.name}] ${m.field}\n${diff(m.expected, m.actual)}"
        }
        fail("$kind: ${real.map { it.name }.distinct().size} of $total cases differ: ${real.map { it.name }.distinct().take(40)}\n\n$details")
    }

    private fun diff(expected: String, actual: String): String {
        var i = 0
        while (i < expected.length && i < actual.length && expected[i] == actual[i]) i++
        val from = maxOf(0, i - 40)
        fun window(s: String) = s.substring(minOf(from, s.length), minOf(s.length, i + 60)).replace("\n", "\\n")
        return "  first difference at $i (expected length ${expected.length}, actual ${actual.length})\n" +
            "  expected: ...${window(expected)}...\n" +
            "  actual:   ...${window(actual)}..."
    }

    private fun check(mismatches: MutableList<Mismatch>, name: String, field: String, expected: String, actual: String) {
        if (expected != actual) mismatches.add(Mismatch(name, field, expected, actual))
    }

    @Test
    fun parseAndSerializeMatchXmldom() {
        val mismatches = ArrayList<Mismatch>()
        for (c in DomFixtures.cases) {
            val opts = options(c.preserveImageDimensions)
            val parsed = try {
                RenderBodyParser.parse(c.html, opts)
            } catch (e: Throwable) {
                mismatches.add(Mismatch(c.name, "exception", c.serialized, e.toString()))
                continue
            }
            check(mismatches, c.name, "path", c.path, parsed.path.name.lowercase())
            if (c.path == "repaired") {
                check(mismatches, c.name, "repaired", c.repaired, HtmlRepair.repair(HtmlSanitizer.sanitize(c.html, opts)))
            }
            val serialized = parsed.document?.let { XmlSerializer.serializeToString(it) } ?: parsed.sanitizedHtml ?: ""
            check(mismatches, c.name, "serialized", c.serialized, serialized)
            val final = RenderBodyParser.finish(serialized, opts)
            check(mismatches, c.name, "final", c.final, final)
            IframeInvariant.violation(final)?.let { mismatches.add(Mismatch(c.name, "iframe invariant", "", it)) }
        }
        println("parse+serialize: ${DomFixtures.cases.size} cases, ${DomFixtures.cases.groupingBy { it.path }.eachCount()}")
        val invariant = mismatches.filter { it.field == "iframe invariant" }
        if (invariant.isNotEmpty()) fail("iframe invariant broken: " + invariant.joinToString { "${it.name}: ${it.actual}" })
        report("parse+serialize", DomFixtures.cases.size, mismatches, deviationsSeen)
    }

    @Test
    fun sanitizeMatchesRenderHelper() {
        val mismatches = ArrayList<Mismatch>()
        for (c in SanitizeFixtures.cases) {
            val actual = try {
                HtmlSanitizer.sanitize(c.html, options(c.preserveImageDimensions))
            } catch (e: Throwable) {
                e.toString()
            }
            check(mismatches, c.name, "sanitized", c.expected, actual)
            IframeInvariant.violation(actual)?.let { fail("iframe invariant broken in ${c.name}: $it") }
        }
        println("sanitizeHtml: ${SanitizeFixtures.cases.size} cases")
        report("sanitizeHtml", SanitizeFixtures.cases.size, mismatches, deviationsSeen)
    }

    private val deviationsSeen = HashSet<String>()

    @Test
    fun securityDeviationsStillDiffer() {
        // Runs both golden sets itself (test order is not guaranteed) and checks the list is live.
        runCatching { parseAndSerializeMatchXmldom() }
        runCatching { sanitizeMatchesRenderHelper() }
        val stale = securityDeviations.keys - deviationsSeen
        if (stale.isNotEmpty()) fail("listed as security deviations but now match the oracle: $stale")
    }

    @Test
    fun decodeEntitiesMatchesRenderHelper() {
        val mismatches = ArrayList<Mismatch>()
        for (c in DecodeFixtures.cases) check(mismatches, c.name, "decoded", c.expected, RenderText.decodeEntities(c.input))
        report("decodeEntities", DecodeFixtures.cases.size, mismatches)
    }

    @Test
    fun embedAllowListMatchesRenderHelper() {
        val mismatches = ArrayList<Mismatch>()
        for (c in EmbedFixtures.cases) {
            check(mismatches, c.name, "allowed(${c.url})", c.allowed.toString(), EmbedSrc.isAllowedEmbedSrc(c.url).toString())
        }
        report("isAllowedEmbedSrc", EmbedFixtures.cases.size, mismatches)
    }

    @Test
    fun blockLevelFixMatchesRenderHelper() {
        val mismatches = ArrayList<Mismatch>()
        for (c in BlockFixFixtures.cases) {
            check(mismatches, c.name, "fixed", c.expected, HtmlStringFixes.fixBlockLevelTagsInParagraphs(c.input))
        }
        report("fixBlockLevelTagsInParagraphs", BlockFixFixtures.cases.size, mismatches)
    }

    @Test
    fun largeBodiesAreFastAndExact() {
        for (l in DomFixtures.large) {
            val input = buildLarge(l.broken)
            assertTrue(input.length >= 200_000)
            val opts = options(false)
            val parsed = RenderBodyParser.parse(input, opts)
            val serialized = parsed.document?.let { XmlSerializer.serializeToString(it) } ?: parsed.sanitizedHtml!!
            val final = RenderBodyParser.finish(serialized, opts)
            val actual = listOf(parsed.path.name.lowercase(), serialized.length, fnv(serialized), final.length, fnv(final))
            val expected = listOf(l.path, l.serializedLength, l.serializedHash, l.finalLength, l.finalHash)
            if (actual != expected) fail("large(broken=${l.broken}): expected $expected, got $actual")
            // Fast means linear in the body size: checked by growth from 50 KB to 200 KB, not by an
            // absolute bound, which failed whenever parallel builds loaded the machine.
            assertEquals(parsed.path, RenderBodyParser.parse(buildLarge(l.broken, 50_000), opts).path, "both sizes take one path")
            LinearScaling.assertLinear("large(broken=${l.broken})", 50_000, { buildLarge(l.broken, it) }) {
                RenderBodyParser.roundTrip(it, opts)
            }
        }
    }

    @Test
    fun pathologicalShapesMatchAndStayLinear() {
        val opts = options(false)
        val failures = ArrayList<String>()
        for (c in DomFixtures.pathological) {
            val input = c.build()
            val parsed = RenderBodyParser.parse(input, opts)
            val serialized = parsed.document?.let { XmlSerializer.serializeToString(it) } ?: parsed.sanitizedHtml!!
            val final = RenderBodyParser.finish(serialized, opts)
            val actual = listOf(parsed.path.name.lowercase(), serialized.length, fnv(serialized), final.length, fnv(final))
            val expected = listOf(c.path, c.serializedLength, c.serializedHash, c.finalLength, c.finalHash)
            if (actual != expected) failures.add("${c.name}: expected $expected, got $actual")
            // Same shape scaled to at least 200 KB must stay linear (several of these are quadratic
            // in the JS packages themselves, which is why the golden copy is small). Checked by
            // growth from a quarter of that size, so load on the machine does not fail it.
            // The golden count itself can be special (deep_closed's 5,000 opening tags exactly match its closing ones
            // and take the faster XMLDOM path), so the small size is one unit past it and both sizes must take the
            // same path: a ratio between two different paths says nothing about growth.
            val scale = maxOf(4, (200_000 + input.length - 1) / input.length)
            val n = c.count * scale / 4 + 1
            assertEquals(RenderBodyParser.parse(c.build(n), opts).path, RenderBodyParser.parse(c.build(4 * n), opts).path, "${c.name}: both sizes take one path")
            try {
                LinearScaling.assertLinear("pathological ${c.name}", n, { c.build(it) }) {
                    RenderBodyParser.roundTrip(it, opts)
                }
            } catch (e: AssertionError) {
                failures.add(e.message ?: "${c.name}: not linear")
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    /**
     * Allowed deviation (robustness, not security): nesting past what the JS stack allows. In
     * Node, dom-serializer overflows the stack in the repair step (around 2,500 to 5,000 levels)
     * and render-helper falls back to sanitize-only output, recorded here as [DeepNestingCase.jsPath].
     * The Kotlin repair is iterative, so the body takes the repaired path with every tag closed.
     */
    @Test
    fun deepNestingPastTheJsStackIsRepaired() {
        val opts = options(false)
        for (c in DomFixtures.deepNesting) {
            assertEquals("sanitized", c.jsPath, "${c.name}: the JS oracle is expected to give up on this depth")
            val input = c.build()
            val parsed = RenderBodyParser.parse(input, opts)
            assertEquals(RenderBodyParser.Path.REPAIRED, parsed.path, c.name)
            val serialized = XmlSerializer.serializeToString(parsed.document!!)
            val final = RenderBodyParser.finish(serialized, opts)
            val tag = c.unit.removePrefix("<").removeSuffix(">")
            assertEquals(c.count, count(final, c.unit), "${c.name}: open tags")
            assertEquals(c.count, count(final, "</$tag>"), "${c.name}: every tag closed")
            assertTrue(final.endsWith("</$tag>"), c.name)
        }
    }

    private fun count(s: String, needle: String): Int {
        var n = 0
        var i = s.indexOf(needle)
        while (i >= 0) {
            n++
            i = s.indexOf(needle, i + needle.length)
        }
        return n
    }

    private fun largeBlock(i: Int) =
        "<p>Paragraph $i with <strong>bold</strong>, <em>em</em> &amp; a <a href=\"https://example.com/$i\">link</a>" +
            " and text &nbsp; café 🐝.</p>\n<div class=\"pull-left\"><img src=\"https://images.example.com/$i.png\" alt=\"x\"></div>\n" +
            "<table><tr><td>$i</td><td>v</td></tr></table>\n"

    private fun buildLarge(broken: Boolean, size: Int = 200_000): String {
        val sb = StringBuilder()
        var i = 0
        while (sb.length < size) sb.append(largeBlock(i++))
        if (broken) sb.append("<b>unclosed</p>")
        return sb.toString()
    }

    private fun fnv(s: String): String {
        var h = 0x811c9dc5.toInt()
        for (c in s) {
            h = h xor c.code
            h *= 0x01000193
        }
        return h.toUInt().toString(16)
    }
}
