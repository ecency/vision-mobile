package com.ecency.core.render.sanitize

import com.ecency.core.render.dom.EntityDecoder
import com.ecency.core.render.js.Js

/** render-helper `SanitizeHtmlOptions` plus the image proxy base it reads globally (`setProxyBase`). */
data class SanitizeOptions(
    /** Keep a validated pixel width/height pair on `<img>` (`RenderOptions.preserveImageDimensions`). */
    val preserveImageDimensions: Boolean = false,
    /** render-helper's proxy base: `<source srcset>` must point at its `/p/` route. */
    val proxyBase: String = DEFAULT_PROXY_BASE,
) {
    companion object {
        const val DEFAULT_PROXY_BASE = "https://i.ecency.com"
    }
}

/**
 * Port of @ecency/render-helper 2.5.42 `sanitizeHtml` (`methods/sanitize-html.method.ts`):
 * xss with the render-helper whitelist and attribute policy, then the `<source>` and image
 * dimension post-passes. String in, string out, byte for byte the JS result.
 */
object HtmlSanitizer {
    private val ALLOWED_ATTRIBUTES: Map<String, List<String>> = mapOf(
        "a" to listOf(
            "href", "target", "rel", "data-permlink", "data-tag", "data-author", "data-href", "data-community",
            "data-filter", "data-embed-src", "data-youtube", "data-start-time", "data-video-href", "data-proposal",
            "data-is-inline", "class", "title", "data-id", "id",
        ),
        "img" to listOf("src", "srcset", "sizes", "alt", "class", "loading", "fetchpriority", "decoding", "itemprop"),
        "picture" to emptyList(),
        "source" to listOf("type", "srcset", "sizes"),
        "span" to listOf("class", "id", "data-align"),
        "iframe" to listOf("src", "class", "frameborder", "allowfullscreen", "webkitallowfullscreen", "mozallowfullscreen", "sandbox"),
        "video" to listOf("src", "controls", "poster"),
        "audio" to listOf("src", "controls", "preload"),
        "div" to listOf("class", "id", "data-align"),
        "strong" to emptyList(), "b" to emptyList(), "i" to emptyList(), "strike" to emptyList(), "em" to emptyList(),
        "code" to emptyList(), "pre" to emptyList(),
        "blockquote" to listOf("class"),
        "sup" to emptyList(), "sub" to emptyList(),
        "h1" to listOf("dir", "id", "data-align"), "h2" to listOf("dir", "id", "data-align"),
        "h3" to listOf("dir", "id", "data-align"), "h4" to listOf("dir", "id", "data-align"),
        "h5" to listOf("dir", "id", "data-align"), "h6" to listOf("dir", "id", "data-align"),
        "p" to listOf("dir", "id", "data-align"),
        "center" to emptyList(), "ul" to emptyList(), "ol" to emptyList(), "li" to emptyList(),
        "table" to emptyList(), "thead" to emptyList(), "tbody" to emptyList(), "tr" to emptyList(),
        "td" to emptyList(), "th" to emptyList(), "hr" to emptyList(), "br" to emptyList(),
        "del" to emptyList(), "ins" to emptyList(),
    )

    private val WITH_DIMENSIONS: Map<String, List<String>> =
        ALLOWED_ATTRIBUTES + ("img" to ALLOWED_ATTRIBUTES.getValue("img") + listOf("width", "height"))

    /**
     * [beforeTransform]: the pre-sanitize of the repair path, whose output still goes through
     * iframe() and then this sanitizer again. iframe `src` and `sandbox` are left to those two
     * (as in render-helper), so iframe() can still normalize an allowed embed (a protocol-relative
     * or m.youtube.com source) before the allow list judges it.
     */
    fun sanitize(html: String, options: SanitizeOptions = SanitizeOptions(), beforeTransform: Boolean = false): String {
        val preserve = options.preserveImageDimensions
        // Rejected iframe sources are marked in place, then the whole iframe becomes iframe()'s
        // "unsupported" placeholder. The marker uses private-use characters, so it is only used
        // when the input has none of them (otherwise the src is simply dropped).
        val markIframes = !beforeTransform && html.indexOf(MARK_START) < 0 && html.indexOf(MARK_END) < 0
        val xss = Xss(if (preserve) WITH_DIMENSIONS else ALLOWED_ATTRIBUTES, listOf("style")) { tag, name, value ->
            val ret = if (beforeTransform && tag == "iframe" && (name == "src" || name == "sandbox")) {
                null
            } else {
                onTagAttr(tag, name, value, options)
            }
            if (markIframes && ret == "" && tag == "iframe" && name == "src") {
                MARK_START + escapeText(RenderText.decodeEntities(Js.trim(value))) + MARK_END
            } else {
                ret
            }
        }
        val cleaned = if (markIframes) replaceMarkedIframes(xss.process(html)) else xss.process(html)
        val withoutBareSource = replaceTags(cleaned, "source") { tag -> if (hasAvifOrWebpType(tag)) tag else "" }
        return if (preserve) enforceImageDimensionPair(withoutBareSource) else withoutBareSource
    }

    private const val MARK_START = "\uE000"
    private const val MARK_END = "\uE001"

    /** XMLSerializer text escaping (`&`, `<`, `>`), as the DOM path writes iframe()'s placeholder text. */
    private fun escapeText(s: String): String {
        if (s.none { it == '&' || it == '<' || it == '>' }) return s
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** iframe()'s replacement for an embed it does not support. */
    fun unsupportedIframeHtml(escapedSrc: String) = "<div class=\"unsupported-iframe\">(Unsupported $escapedSrc)</div>"

    /**
     * SECURITY DEVIATION, placeholder half: an iframe whose `src` the allow list rejects is
     * replaced (with its content and end tag) by the placeholder iframe() emits for unsupported
     * embeds, instead of staying as an empty `<iframe>`.
     */
    private fun replaceMarkedIframes(html: String): String {
        var m = html.indexOf(MARK_START)
        if (m < 0) return html
        val sb = StringBuilder(html.length)
        var last = 0
        while (m >= 0) {
            val me = html.indexOf(MARK_END, m)
            val start = lastIndexOfIgnoreCase(html, "<iframe", m)
            val tagEnd = if (me < 0) -1 else html.indexOf('>', me)
            if (me < 0 || start < last || tagEnd < 0) {
                // Malformed: keep the text without the marker.
                sb.append(html, last, m)
                last = if (me < 0) m + 1 else me + 1
                m = html.indexOf(MARK_START, last)
                continue
            }
            val close = indexOfIgnoreCase(html, "</iframe", tagEnd + 1)
            val end = if (close < 0) tagEnd + 1 else html.indexOf('>', close).let { if (it < 0) html.length else it + 1 }
            sb.append(html, last, start).append(unsupportedIframeHtml(html.substring(m + 1, me)))
            last = end
            m = html.indexOf(MARK_START, last)
        }
        sb.append(html, last, html.length)
        return sb.toString()
    }

    private fun lastIndexOfIgnoreCase(s: String, lit: String, before: Int): Int {
        var i = minOf(before, s.length - lit.length)
        while (i >= 0) {
            if (Js.regionMatchesLower(s, i, lit)) return i
            i--
        }
        return -1
    }

    /** The xss `onTagAttr` hook: null keeps the default handling, "" drops the attribute. */
    internal fun onTagAttr(tag: String, name: String, value: String, options: SanitizeOptions): String? {
        val decoded = RenderText.decodeEntities(Js.trim(value))
        val decodedLower = decoded.lowercase()
        if (name.startsWith("on")) return ""
        if (tag == "img" && name == "src" && !startsWithHttp(decodedLower)) return ""
        if (tag == "img" && name == "srcset") {
            if (decoded.split(',').any { !startsWithHttpIgnoreCase(firstToken(it)) }) return ""
        }
        if (tag == "source" && name == "srcset" && !isProxyPSrcset(decoded, options.proxyBase)) return ""
        if (tag == "source" && name == "type" && decodedLower != "image/avif" && decodedLower != "image/webp") return ""
        if ((tag == "video" || tag == "audio") && (name == "src" || name == "poster") && !startsWithHttp(decodedLower)) return ""
        if (tag == "audio" && name == "preload" && decodedLower != "metadata" && decodedLower != "none") return ""
        if (tag == "img" && (name == "dynsrc" || name == "lowsrc")) return ""
        if (tag == "img" && (name == "width" || name == "height")) {
            if (!options.preserveImageDimensions || ImageDimensions.parsePixelDimension(decoded) == null) return ""
        }
        if (tag == "span" && name == "class" && Js.trim(decoded.lowercase()) == "wr") return ""
        if (tag == "iframe" && name == "src" && !isAllowedIframeSrc(decoded)) return ""
        if (tag == "iframe" && name == "sandbox" && !isTransformSandbox(decoded)) return ""
        if ((name == "data-embed-src" || name == "data-video-href") && !EmbedSrc.isAllowedEmbedSrc(decoded)) return ""
        if (name == "data-href" && !isSafeNavValue(decoded)) return ""
        if (name == "id" && !isIdWhitelisted(decoded)) return ""
        return null
    }

    /**
     * SECURITY DEVIATION from render-helper (see native/docs/upstream-render-helper-issues.md).
     * render-helper's sanitizer keeps any iframe `src` and author `sandbox`; only the DOM
     * `iframe()` transform enforces the embed host allow list, and the sanitize-only fallback
     * (both parses failed) never runs it. Here the sanitizer enforces it on every path: an
     * iframe keeps its `src` only when [EmbedSrc.isAllowedEmbedSrc] accepts it. The transform
     * itself leaves protocol-relative and `http:` sources of allowed hosts in place, so those are
     * judged by their `https:` form and kept as written.
     */
    internal fun isAllowedIframeSrc(src: String): Boolean {
        val t = Js.trim(src)
        val https = when {
            t.startsWith("//") -> "https:$t"
            Js.regionMatchesLower(t, 0, "http://") -> "https://" + t.substring(7)
            else -> t
        }
        return EmbedSrc.isAllowedEmbedSrc(https)
    }

    /** The only sandbox tokens render-helper's `iframe()` transform ever grants. */
    private val TRANSFORM_SANDBOX_TOKENS = setOf("allow-scripts", "allow-same-origin", "allow-popups")

    /**
     * SECURITY DEVIATION (with [isAllowedIframeSrc]): an iframe `sandbox` survives only when
     * every token is one the transform itself grants, so an author can never add permissions
     * such as top navigation or forms. The transform's own values pass unchanged.
     */
    private fun isTransformSandbox(value: String): Boolean {
        val tokens = value.split(' ', '\t', '\n', '\r', '\u000C').filter { it.isNotEmpty() }
        return tokens.all { it.lowercase() in TRANSFORM_SANDBOX_TOKENS }
    }

    /** `/^https?:\/\//` on an already lower-cased value. */
    private fun startsWithHttp(v: String) = v.startsWith("http://") || v.startsWith("https://")

    private fun startsWithHttpIgnoreCase(v: String) = Js.regionMatchesLower(v, 0, "http://") || Js.regionMatchesLower(v, 0, "https://")

    /** `c.trim().split(/\s+/)[0]`. */
    private fun firstToken(c: String): String {
        val t = Js.trim(c)
        for (i in t.indices) if (Js.isSpace(t[i])) return t.substring(0, i)
        return t
    }

    private fun isProxyPSrcset(srcset: String, proxyBase: String): Boolean {
        var end = proxyBase.length
        while (end > 0 && proxyBase[end - 1] == '/') end--
        val prefix = proxyBase.substring(0, end) + "/p/"
        val candidates = srcset.split(',').map { firstToken(it) }.filter { it.isNotEmpty() }
        return candidates.isNotEmpty() && candidates.all { it.startsWith(prefix) }
    }

    /** `^[A-Za-z][-A-Za-z0-9_]*$`. */
    private fun isIdWhitelisted(v: String): Boolean {
        if (v.isEmpty() || !Js.isAsciiAlpha(v[0])) return false
        for (i in 1 until v.length) {
            val c = v[i]
            if (!(Js.isAsciiAlpha(c) || Js.isDigit(c) || c == '-' || c == '_')) return false
        }
        return true
    }

    /** data-href scheme policy (`isSafeNavValue`). */
    private fun isSafeNavValue(value: String): Boolean {
        val t0 = Js.trim(value)
        val sb = StringBuilder(t0.length)
        for (c in t0) if (c != '\t' && c != '\n' && c != '\r' && c != '\u000C' && c != '\u000B' && c != '\u0000') sb.append(c)
        val trimmed = sb.toString().lowercase()
        if (trimmed.isEmpty()) return false
        return isSafeScheme(trimmed) || isRelative(trimmed)
    }

    /** `/^(https?|mailto|hive|tel|web\+[a-z0-9.+-]+):/i` on a lower-cased value. */
    private fun isSafeScheme(t: String): Boolean {
        for (s in arrayOf("http:", "https:", "mailto:", "hive:", "tel:")) if (t.startsWith(s)) return true
        if (t.startsWith("web+")) {
            var i = 4
            while (i < t.length && (t[i] in 'a'..'z' || t[i] in '0'..'9' || t[i] == '.' || t[i] == '+' || t[i] == '-')) i++
            // The class includes ':'-free chars only, so the run must end right at a ':'.
            return i > 4 && i < t.length && t[i] == ':'
        }
        return false
    }

    /** `/^(\/\/|\/[^/]?|#|\?|[a-z0-9._\-]+(\/|$))/i` on a lower-cased value. */
    private fun isRelative(t: String): Boolean {
        val c = t[0]
        if (c == '/' || c == '#' || c == '?') return true
        var i = 0
        while (i < t.length && (t[i] in 'a'..'z' || t[i] in '0'..'9' || t[i] == '.' || t[i] == '_' || t[i] == '-')) i++
        return i > 0 && (i == t.length || t[i] == '/')
    }

    private fun hasAvifOrWebpType(tag: String): Boolean {
        var from = 0
        while (true) {
            val i = indexOfIgnoreCase(tag, "type", from)
            if (i < 0) return false
            from = i + 1
            if (i > 0 && Js.isWordChar(tag[i - 1])) continue
            var p = i + 4
            while (p < tag.length && Js.isSpace(tag[p])) p++
            if (p >= tag.length || tag[p] != '=') continue
            p++
            while (p < tag.length && Js.isSpace(tag[p])) p++
            if (p >= tag.length || (tag[p] != '"' && tag[p] != '\'')) continue
            p++
            val rest = if (Js.regionMatchesLower(tag, p, "image/avif")) 10 else if (Js.regionMatchesLower(tag, p, "image/webp")) 10 else -1
            if (rest < 0) continue
            p += rest
            if (p < tag.length && (tag[p] == '"' || tag[p] == '\'')) return true
        }
    }

    private fun indexOfIgnoreCase(s: String, lowerLit: String, from: Int): Int {
        var i = from
        while (i + lowerLit.length <= s.length) {
            if (Js.regionMatchesLower(s, i, lowerLit)) return i
            i++
        }
        return -1
    }

    /**
     * `html.replace(/<name\b(?:[^>"']|"[^"]*"|'[^']*')*>/gi, fn)`: a start tag of [name] whose
     * quoted spans are consumed whole (a `>` inside quotes does not end it).
     */
    internal fun replaceTags(html: String, name: String, fn: (String) -> String): String {
        var i = indexOfIgnoreCase(html, "<$name", 0)
        if (i < 0) return html
        val sb = StringBuilder(html.length)
        var last = 0
        while (i >= 0) {
            val afterName = i + 1 + name.length
            var end = -1
            if (afterName >= html.length || !Js.isWordChar(html[afterName])) {
                var p = afterName
                while (p < html.length) {
                    val c = html[p]
                    if (c == '>') {
                        end = p + 1
                        break
                    }
                    if (c == '"' || c == '\'') {
                        val close = html.indexOf(c, p + 1)
                        if (close < 0) break
                        p = close + 1
                    } else {
                        p++
                    }
                }
            }
            if (end < 0) {
                i = indexOfIgnoreCase(html, "<$name", i + 1)
                continue
            }
            sb.append(html, last, i)
            sb.append(fn(html.substring(i, end)))
            last = end
            i = indexOfIgnoreCase(html, "<$name", end)
        }
        sb.append(html, last, html.length)
        return sb.toString()
    }

    private class QuotedAttr(val start: Int, val end: Int, val name: String, val value: String)

    /** `/\s([^\s"'>\/=]+)="([^"]*)"/g` matches in [tag]. */
    private fun quotedAttrs(tag: String): List<QuotedAttr> {
        val out = ArrayList<QuotedAttr>()
        var k = 0
        while (k < tag.length) {
            if (Js.isSpace(tag[k])) {
                var p = k + 1
                while (p < tag.length && !isNameStop(tag[p])) p++
                if (p > k + 1 && p + 1 < tag.length && tag[p] == '=' && tag[p + 1] == '"') {
                    val close = tag.indexOf('"', p + 2)
                    if (close >= 0) {
                        out.add(QuotedAttr(k, close + 1, tag.substring(k + 1, p), tag.substring(p + 2, close)))
                        k = close + 1
                        continue
                    }
                }
            }
            k++
        }
        return out
    }

    private fun isNameStop(c: Char) = Js.isSpace(c) || c == '"' || c == '\'' || c == '>' || c == '/' || c == '='

    private fun enforceImageDimensionPair(html: String): String = replaceTags(html, "img") { tag ->
        val attrs = quotedAttrs(tag)
        fun find(n: String) = attrs.firstOrNull { it.name.lowercase() == n }?.let { RenderText.decodeEntities(it.value) }
        val width = find("width")
        val height = find("height")
        val src = Js.trim(find("src") ?: "")
        if (src.isNotEmpty() && ImageDimensions.authorPixelSize(width, height) != null) {
            tag
        } else {
            val sb = StringBuilder(tag.length)
            var last = 0
            for (a in attrs) {
                val n = a.name.lowercase()
                if (n == "width" || n == "height") {
                    sb.append(tag, last, a.start)
                    last = a.end
                }
            }
            sb.append(tag, last, tag.length)
            sb.toString()
        }
    }
}

/** render-helper `methods/image-dimensions.ts`. */
object ImageDimensions {
    const val MAX_IMAGE_DIMENSION_PX = 8192
    const val MAX_IMAGE_DIMENSION_RATIO = 40

    fun parsePixelDimension(value: String?): Int? {
        if (value == null) return null
        val t = Js.trim(value)
        if (t.isEmpty() || t.length > 4 || !t.all(Js::isDigit)) return null
        val n = t.toInt()
        return if (n < 1 || n > MAX_IMAGE_DIMENSION_PX) null else n
    }

    fun authorPixelSize(width: String?, height: String?): Pair<String, String>? {
        val w = parsePixelDimension(width) ?: return null
        val h = parsePixelDimension(height) ?: return null
        val larger = maxOf(w, h)
        val smaller = minOf(w, h)
        if (larger.toDouble() / smaller > MAX_IMAGE_DIMENSION_RATIO) return null
        return w.toString() to h.toString()
    }
}

/** render-helper `helper.ts` text utilities. */
object RenderText {
    /**
     * `decodeEntities`: entities 6 `decodeHTML` (legacy mode) after dropping leading zeros of
     * numeric references and turning overlong ones into U+FFFD. Never throws.
     */
    fun decodeEntities(value: String): String {
        if (value.indexOf('&') < 0) return value
        val safe = replaceOverlong(stripLeadingZeros(value))
        return try {
            EntityDecoder.decode(safe, EntityDecoder.V6)
        } catch (e: IllegalArgumentException) {
            safe
        }
    }

    /** `/&#0+(?=[0-9])/g` to `&#`, then `/&#x0+(?=[0-9a-f])/gi` to the first three chars. */
    private fun stripLeadingZeros(s: String): String {
        if (s.indexOf("&#") < 0) return s
        return stripZeros(stripZeros(s, hex = false), hex = true)
    }

    /**
     * One of the two leading-zero passes. `0+` backtracks: when the zero run is not followed by
     * a digit, all zeros but the last are still removed (the last one satisfies the lookahead).
     */
    private fun stripZeros(s: String, hex: Boolean): String {
        val prefix = if (hex) 3 else 2
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val isRef = s.startsWith("&#", i) && (!hex || (i + 2 < s.length && (s[i + 2] == 'x' || s[i + 2] == 'X')))
            if (isRef && i + prefix < s.length && s[i + prefix] == '0') {
                var j = i + prefix
                while (j < s.length && s[j] == '0') j++
                val next = j < s.length && (if (hex) Js.isHexDigit(s[j]) else Js.isDigit(s[j]))
                val keepFrom = if (next) j else if (j - (i + prefix) >= 2) j - 1 else -1
                if (keepFrom >= 0) {
                    sb.append(s, i, i + prefix)
                    i = keepFrom
                    continue
                }
            }
            sb.append(s[i])
            i++
        }
        return sb.toString()
    }

    /** `/&#(?:x[0-9a-f]{256,}|[0-9]{309,});?/gi` to U+FFFD. */
    private fun replaceOverlong(s: String): String {
        if (s.indexOf("&#") < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s.startsWith("&#", i)) {
                val hex = i + 2 < s.length && (s[i + 2] == 'x' || s[i + 2] == 'X')
                var j = if (hex) i + 3 else i + 2
                val digitsStart = j
                while (j < s.length && (if (hex) Js.isHexDigit(s[j]) else Js.isDigit(s[j]))) j++
                val count = j - digitsStart
                if (count >= (if (hex) 256 else 309)) {
                    if (j < s.length && s[j] == ';') j++
                    sb.append('\uFFFD')
                    i = j
                    continue
                }
            }
            sb.append(s[i])
            i++
        }
        return sb.toString()
    }
}
