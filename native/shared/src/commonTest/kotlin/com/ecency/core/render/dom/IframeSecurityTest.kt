package com.ecency.core.render.dom

import com.ecency.core.render.sanitize.EmbedSrc
import com.ecency.core.render.sanitize.HtmlSanitizer
import com.ecency.core.render.sanitize.RenderText
import com.ecency.core.render.sanitize.SanitizeOptions
import com.ecency.core.render.sanitize.Xss
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What every rendered iframe must satisfy, whatever path produced it: a `src` (if any) that the
 * embed allow list accepts in its `https:` form, and only the sandbox tokens render-helper's
 * `iframe()` transform grants. Returns a description of the first violation, or null.
 */
internal object IframeInvariant {
    private val SANDBOX_TOKENS = setOf("allow-scripts", "allow-same-origin", "allow-popups")

    fun violation(html: String): String? {
        var from = 0
        while (true) {
            val i = html.indexOf("<iframe", from, ignoreCase = true)
            if (i < 0) return null
            val end = html.indexOf('>', i).let { if (it < 0) html.length else it }
            val tag = html.substring(i, end)
            from = end
            attr(tag, "src")?.let { raw ->
                val src = RenderText.decodeEntities(raw).trim()
                val https = when {
                    src.startsWith("//") -> "https:$src"
                    src.startsWith("http://", ignoreCase = true) -> "https://" + src.substring(7)
                    else -> src
                }
                if (!EmbedSrc.isAllowedEmbedSrc(https)) return "iframe src not on the allow list: $tag"
            }
            attr(tag, "sandbox")?.let { raw ->
                val bad = raw.split(' ').filter { it.isNotEmpty() && it.lowercase() !in SANDBOX_TOKENS }
                if (bad.isNotEmpty()) return "iframe sandbox grants $bad: $tag"
            }
        }
    }

    private fun attr(tag: String, name: String): String? {
        val m = Regex("""\s${name}="([^"]*)"""", RegexOption.IGNORE_CASE).find(tag) ?: return null
        return m.groupValues[1]
    }
}

class IframeSecurityTest {
    private val opts = SanitizeOptions(proxyBase = DomFixtures.PROXY_BASE)

    private class Rendered(val path: RenderBodyParser.Path, val html: String)

    private fun render(html: String): Rendered {
        val parsed = RenderBodyParser.parse(html, opts)
        val serialized = parsed.document?.let { XmlSerializer.serializeToString(it) } ?: parsed.sanitizedHtml!!
        return Rendered(parsed.path, RenderBodyParser.finish(serialized, opts))
    }

    private val wideSandbox = "allow-scripts allow-same-origin allow-top-navigation allow-forms"

    /** One unlisted iframe per parse path: the input shapes are chosen to land on each one. */
    private val attacks = listOf(
        RenderBodyParser.Path.XMLDOM to """<iframe src="https://evil.example.com/x" sandbox="$wideSandbox"></iframe>""",
        RenderBodyParser.Path.REPAIRED to """<p><iframe src="https://evil.example.com/x" sandbox="$wideSandbox"></iframe></b>""",
        RenderBodyParser.Path.SANITIZED to """<iframe src="https://evil.example.com/x" sandbox="$wideSandbox">&lt;b&gt;</iframe><p>""",
    )

    @Test
    fun unlistedIframeBecomesThePlaceholderOnEveryPath() {
        for ((path, html) in attacks) {
            val r = render(html)
            assertEquals(path, r.path, "input should take the $path path: $html")
            assertFalse(r.html.contains("src=\"https://evil.example.com"), "$path kept the src: ${r.html}")
            assertFalse(r.html.contains("sandbox"), "$path kept the author sandbox: ${r.html}")
            assertFalse(r.html.contains("<iframe"), "$path kept the iframe: ${r.html}")
            assertTrue(
                r.html.contains("<div class=\"unsupported-iframe\">(Unsupported https://evil.example.com/x)</div>"),
                "$path should show iframe()'s unsupported placeholder: ${r.html}",
            )
            assertNull(IframeInvariant.violation(r.html))
            // The sanitizer alone (the last step of every path) holds the line too.
            assertNull(IframeInvariant.violation(HtmlSanitizer.sanitize(html, opts)))
        }
    }

    @Test
    fun unlistedSrcVariantsBecomeThePlaceholder() {
        for (src in listOf(
            "//evil.example.com/x", "https://www.youtube.com.evil.example.com/embed/x", "https://evil.example.com/?u=https://www.youtube.com/embed/x",
            "https://www.youtube.com@evil.example.com/embed/x", "javascript&colon;alert(1)", "data:text/html,x", "/relative/path",
            "https://www.youtube.com/watch?v=x", " https://evil.example.com/x", "HTTPS://EVIL.EXAMPLE.COM/x", "http://evil.example.com/x",
        )) {
            val out = HtmlSanitizer.sanitize("""<iframe src="$src"></iframe>""", opts)
            val shown = RenderText.decodeEntities(src.trim()).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            assertEquals("<div class=\"unsupported-iframe\">(Unsupported $shown)</div>", out, "src $src")
            assertNull(IframeInvariant.violation(out))
        }
    }

    @Test
    fun allowListedSourcesAndTransformSandboxesPassUnchanged() {
        for (html in listOf(
            """<iframe src="https://www.youtube.com/embed/x" frameborder="0" allowfullscreen></iframe>""",
            """<iframe src="//www.youtube.com/embed/x"></iframe>""",
            """<iframe src="http://player.twitch.tv/?channel=x&amp;parent=ecency.com&amp;autoplay=false"></iframe>""",
            """<iframe src="https://open.spotify.com/embed/track/x" sandbox="allow-scripts allow-same-origin allow-popups" frameborder="0"></iframe>""",
            """<iframe src="https://emb.d.tube/#!/a/b" sandbox="allow-scripts allow-same-origin" frameborder="0" allowfullscreen="true"></iframe>""",
            """<iframe src="https://player.eu.dapplr.in/file/dapplr-videos/x.mp4" sandbox="allow-scripts allow-same-origin"></iframe>""",
        )) {
            val out = HtmlSanitizer.sanitize(html, opts)
            assertEquals(html, out)
            assertNull(IframeInvariant.violation(out))
        }
        // An empty sandbox (the most restrictive one) stays; xss writes it without a value.
        assertEquals(
            """<iframe src="https://www.youtube.com/embed/x" sandbox></iframe>""",
            HtmlSanitizer.sanitize("""<iframe src="https://www.youtube.com/embed/x" sandbox=""></iframe>""", opts),
        )
    }

    @Test
    fun authorSandboxTokensBeyondTheTransformAreDropped() {
        for (sandbox in listOf("allow-top-navigation", "allow-scripts allow-forms", "allow-same-origin allow-modals", "allow-popups-to-escape-sandbox")) {
            val out = HtmlSanitizer.sanitize("""<iframe src="https://www.youtube.com/embed/x" sandbox="$sandbox"></iframe>""", opts)
            assertEquals("""<iframe src="https://www.youtube.com/embed/x"></iframe>""", out, sandbox)
        }
    }

    /**
     * The `on*` guard in the sanitizer's attribute hook is unreachable through render-helper's
     * whitelist (no whitelisted attribute starts with "on"), so it is tested by running the xss
     * port with a whitelist that does allow one.
     */
    @Test
    fun eventHandlerGuardDropsWhitelistedOnAttributes() {
        val xss = Xss(mapOf("a" to listOf("href", "onclick"), "img" to listOf("src", "onerror")), listOf("style")) { tag, name, value ->
            HtmlSanitizer.onTagAttr(tag, name, value, opts)
        }
        assertEquals("""<a href="https://a.example/">x</a>""", xss.process("""<a href="https://a.example/" onclick="alert(1)">x</a>"""))
        assertEquals("""<img src="https://a.example/i.png">""", xss.process("""<img src="https://a.example/i.png" onerror="alert(1)">"""))
    }
}
