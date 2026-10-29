package com.ecency.core.render.transform

import com.ecency.core.render.Entry
import com.ecency.core.render.RenderHelper
import com.ecency.core.render.RenderOptions
import com.ecency.core.render.SeoContext
import com.ecency.core.render.methods.CleanReply
import com.ecency.core.render.methods.Helper
import com.ecency.core.render.methods.ImageState
import com.ecency.core.render.methods.Linkify
import com.ecency.core.render.methods.RenderContext
import kotlin.test.Test
import kotlin.test.fail

/**
 * Byte-for-byte parity with @ecency/render-helper 2.5.42 for the whole post pipeline.
 * Fixtures: native/tools/render/generate-transform-fixtures.mjs (synthetic inputs only).
 */
class TransformGoldenTest {
    private val allowedDeviations = TransformDeviations.security
    private val deviationsSeen = HashSet<String>()

    private val helper = RenderHelper(RenderCases.PROXY_BASE)

    private class Mismatch(val name: String, val input: String, val expected: String, val actual: String)

    private fun diff(expected: String, actual: String): String {
        var i = 0
        while (i < expected.length && i < actual.length && expected[i] == actual[i]) i++
        val from = maxOf(0, i - 60)
        fun window(s: String) = s.substring(minOf(from, s.length), minOf(s.length, i + 80)).replace("\n", "\\n")
        return "  first difference at $i (expected length ${expected.length}, actual ${actual.length})\n" +
            "  expected: ...${window(expected)}...\n" +
            "  actual:   ...${window(actual)}..."
    }

    private fun report(kind: String, total: Int, mismatches: List<Mismatch>) {
        deviationsSeen.addAll(mismatches.map { it.name }.filter { it in allowedDeviations })
        val real = mismatches.filter { it.name !in allowedDeviations }
        val deviations = mismatches.size - real.size
        println("$kind: ${total - mismatches.size}/$total exact" + if (deviations > 0) " + $deviations security deviations" else "")
        if (real.isEmpty()) return
        val details = real.take(10).joinToString("\n\n") { m ->
            "[${m.name}] input=${m.input.take(300).replace("\n", "\\n")}\n${diff(m.expected, m.actual)}"
        }
        fail("$kind: ${real.size} of $total cases differ: ${real.map { it.name }.take(60)}\n\n$details")
    }

    private fun opts(o: TOpts) = RenderOptions(o.embedVideosDirectly, o.inertAuthorAndTagChips, o.externalProfileBase, o.preserveImageDimensions)

    private fun seo(o: TOpts) = if (o.authorReputation == null && o.postPayout == null) null else SeoContext(o.authorReputation, o.postPayout)

    private fun render(md: String, forApp: Boolean, parentDomain: String, o: TOpts): String = try {
        helper.renderPostBody(md, forApp, parentDomain, seo(o), opts(o))
    } catch (e: Throwable) {
        "EXCEPTION $e"
    }

    private fun fnv(s: String): String {
        var h = 0x811c9dc5.toInt()
        for (c in s) {
            h = h xor c.code
            h *= 0x01000193
        }
        return h.toUInt().toString(16)
    }

    @Test
    fun renderPostBodyMatchesRenderHelper() {
        val mismatches = ArrayList<Mismatch>()
        for (c in RenderCases.cases) {
            val actual = render(c.markdown, c.forApp, c.parentDomain, c.opts)
            if (actual != c.html) mismatches.add(Mismatch(c.name, c.markdown, c.html, actual))
        }
        report("render", RenderCases.cases.size, mismatches)
    }

    @Test
    fun seededRandomBodiesMatchRenderHelper() {
        val mismatches = ArrayList<Mismatch>()
        for (c in RandomCases.cases) {
            val actual = render(c.markdown, c.forApp, c.parentDomain, c.opts)
            if (actual.length != c.length || fnv(actual) != c.hash) {
                mismatches.add(Mismatch(c.name, c.markdown, "length ${c.length} hash ${c.hash}", "length ${actual.length} hash ${fnv(actual)}: $actual"))
            }
        }
        report("random", RandomCases.cases.size, mismatches)
    }

    @Test
    fun securityDeviationsStillDiffer() {
        // Runs both golden sets itself (test order is not guaranteed) and checks the list is live.
        runCatching { renderPostBodyMatchesRenderHelper() }
        runCatching { seededRandomBodiesMatchRenderHelper() }
        val stale = allowedDeviations.keys - deviationsSeen
        if (stale.isNotEmpty()) fail("listed as security deviations but now match the oracle: $stale")
    }

    @Test
    fun entryOverloadMatchesRenderHelper() {
        val mismatches = ArrayList<Mismatch>()
        for (c in EntryCases.cases) {
            val actual = helper.renderPostBody(Entry(author = "example-user", body = c.input), true)
            if (actual != c.expected) mismatches.add(Mismatch(c.name, c.input, c.expected, actual))
        }
        report("entry", EntryCases.cases.size, mismatches)
    }

    @Test
    fun postBodySummaryMatchesRenderHelper() {
        val mismatches = ArrayList<Mismatch>()
        for (c in SummaryCases.cases) {
            val platform = when (c.platform) {
                "ios" -> RenderHelper.Platform.IOS
                "android" -> RenderHelper.Platform.ANDROID
                else -> RenderHelper.Platform.WEB
            }
            val actual = try {
                helper.postBodySummary(c.body, c.length, platform)
            } catch (e: Throwable) {
                "EXCEPTION $e"
            }
            if (actual != c.expected) mismatches.add(Mismatch(c.name, c.body, c.expected, actual))
        }
        report("summary", SummaryCases.cases.size, mismatches)
    }

    @Test
    fun catchPostImageMatchesRenderHelper() {
        val mismatches = ArrayList<Mismatch>()
        for (c in ImageCases.cases) {
            val entry = Entry(author = "example-user", permlink = "p", lastUpdate = "2026-10-06T00:00:00", body = c.body, jsonMetadata = c.meta)
            val actual = try {
                when (c.kind) {
                    "entry" -> helper.catchPostImage(entry, c.width, c.height, c.fast)
                    "raw" -> helper.getEntryImageRawUrl(entry)
                    "card" -> helper.getEntryCardImageRawUrl(entry)
                    "string" -> helper.catchPostImage(c.body, c.width, c.height, c.fast)
                    "rawstring" -> helper.getEntryImageRawUrl(c.body)
                    else -> error("kind ${c.kind}")
                }
            } catch (e: Throwable) {
                "EXCEPTION $e"
            }
            if (actual != c.expected) mismatches.add(Mismatch(c.name, "${c.meta} | ${c.body}", c.expected.toString(), actual.toString()))
        }
        report("image", ImageCases.cases.size, mismatches)
    }

    @Test
    fun simpleMarkdownMatchesRenderHelper() {
        val mismatches = ArrayList<Mismatch>()
        for (c in SimpleCases.cases) {
            val actual = try {
                helper.simpleMarkdownToHTML(c.input)
            } catch (e: Throwable) {
                "EXCEPTION $e"
            }
            if (actual != c.expected) mismatches.add(Mismatch(c.name, c.input, c.expected, actual))
        }
        report("simple", SimpleCases.cases.size, mismatches)
    }

    @Test
    fun helpersMatchRenderHelper() {
        val mismatches = ArrayList<Mismatch>()
        for (c in HelperCases.cases) {
            val actual = when (c.fn) {
                "extractYtStartTime" -> Helper.extractYtStartTime(c.input)
                "isValidUsername" -> Helper.isValidUsername(c.input).toString()
                "sanitizePermlink" -> Helper.sanitizePermlink(c.input)
                "isValidPermlink" -> Helper.isValidPermlink(c.input).toString()
                "decodeImageSrc" -> Helper.decodeImageSrc(c.input)
                "stripHtmlTags" -> Helper.stripHtmlTags(c.input)
                "youtubeVideoLinkClass" -> Helper.youtubeVideoLinkClass(c.input)
                "cleanReply" -> CleanReply.cleanReply(c.input)
                else -> error("fn ${c.fn}")
            }
            if (actual != c.expected) mismatches.add(Mismatch("${c.fn}(${c.input.take(40)})", c.input, c.expected, actual))
        }
        report("helpers", HelperCases.cases.size, mismatches)
    }

    @Test
    fun linkifyMatchesRenderHelper() {
        val mismatches = ArrayList<Mismatch>()
        for (c in LinkifyCases.cases) {
            val ctx = RenderContext(c.forApp, "ecency.com", null, RenderOptions(inertAuthorAndTagChips = c.inert, externalProfileBase = c.externalProfileBase), helper.proxy)
            val actual = try {
                Linkify.linkify(c.input, ctx)
            } catch (e: Throwable) {
                "EXCEPTION $e"
            }
            if (actual != c.expected) mismatches.add(Mismatch(c.name, c.input, c.expected, actual))
        }
        report("linkify", LinkifyCases.cases.size, mismatches)
    }
}

/**
 * Cases that differ from render-helper only through the deliberate iframe security deviation
 * (README "Deliberate deviations from the oracle", DomGoldenTest.securityDeviations): the native
 * sanitizer drops an iframe src outside the embed allow list on every path. Verified by turning
 * the deviation off, which makes every one of them match the oracle exactly.
 */
internal object TransformDeviations {
    private const val SHORTS =
        "iframe() keeps youtube.com/shorts/<id> when the id is not 11 characters; that src is " +
            "outside the embed allow list, so the native port shows iframe()'s Unsupported placeholder instead"
    private const val FALLBACK =
        "a markdownToHTML fallback path keeps an off-allow-list iframe src in render-helper; " +
            "the native sanitizer replaces that iframe with iframe()'s Unsupported placeholder"

    val security: Map<String, String> = mapOf(
        "iframe_youtube_shorts_bad_app" to SHORTS,
        "iframe_youtube_shorts_bad_web" to SHORTS,
        "iframe_youtube_shorts_bad_seolow_web" to SHORTS,
    ) + listOf("random_40", "random_203", "random_214", "random_230", "random_267", "random_334", "random_377")
        .associateWith { "$SHORTS; and/or $FALLBACK" }
}
