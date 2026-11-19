package com.ecency.core.render.sanitize

import com.ecency.core.render.dom.EntityDecoder
import com.ecency.core.render.js.Js

/** render-helper `SanitizeHtmlOptions` plus the image proxy base it reads globally (`setProxyBase`). */
data class SanitizeOptions(
    /** Keep a validated pixel width/height pair on `<img>` (`RenderOptions.preserveImageDimensions`). */
    val preserveImageDimensions: Boolean = false,
    /** render-helper's proxy base: `<source srcset>` must point at its `/p/` route, images go through it. */
    val proxyBase: String = DEFAULT_PROXY_BASE,
    /**
     * 2.5.45 (#1919): the HTML never went through the DOM transforms (the sanitize-only fallback): nothing in it is
     * trusted. Images through the proxy, author target/rel dropped, render-helper's markers removed.
     */
    val untransformed: Boolean = false,
    /**
     * 2.5.45: the final pass of a full render. Tags carrying this per-render nonce were emitted by the transformed
     * DOM and keep render-helper's markers and link policy; every other tag gets the untransformed rules.
     */
    val renderNonce: String? = null,
) {
    companion object {
        const val DEFAULT_PROXY_BASE = "https://i.ecency.com"
    }
}

/**
 * Port of @ecency/render-helper 2.5.52 `sanitizeHtml` (`methods/sanitize-html.method.ts`):
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
     * [beforeTransform]: the pre-sanitize of the repair path, ahead of a full DOM transform. Iframes are left to
     * iframe(), which normalises provider page URLs and shows the unsupported placeholder for the rest; the final
     * pass enforces the allow list on what it emits.
     */
    fun sanitize(html: String, options: SanitizeOptions = SanitizeOptions(), beforeTransform: Boolean = false): String {
        val preserve = options.preserveImageDimensions
        val pass = Pass(options, beforeTransform)
        pass.collectDeclaredIds(html)
        val xss = Xss(
            if (preserve) WITH_DIMENSIONS else ALLOWED_ATTRIBUTES,
            listOf("style"),
            onTagSeen = { raw -> pass.trusted = options.renderNonce != null && raw.indexOf(options.renderNonce) >= 0 && isStrictTag(raw) },
        ) { tag, name, value, isWhiteAttr -> pass.onTagAttr(tag, name, value, isWhiteAttr) }
        val cleaned = xss.process(html)
        val withoutBareSource = replaceTags(cleaned, "source") { tag -> if (hasAvifOrWebpType(tag)) tag else "" }
        val withoutBareIframe = if (beforeTransform) withoutBareSource else dropBareIframes(withoutBareSource)
        return if (preserve) enforceImageDimensionPair(withoutBareIframe) else withoutBareIframe
    }

    /** One attribute through the rules of a plain `sanitizeHtml` pass (tests drive xss with other whitelists). */
    internal fun onTagAttr(tag: String, name: String, value: String, isWhiteAttr: Boolean, options: SanitizeOptions): String? =
        Pass(options, false).onTagAttr(tag, name, value, isWhiteAttr)

    /** The attribute rules of one `sanitizeHtml` call (render-helper's `onTagAttr` closure). */
    private class Pass(val options: SanitizeOptions, val beforeTransform: Boolean) {
        val untransformed = options.untransformed
        val enforce = untransformed || options.renderNonce != null
        val proxyBase = trimTrailingSlash(options.proxyBase) + "/"
        private val proxy by lazy { com.ecency.core.render.ImageProxy(options.proxyBase) }

        /** Set by xss's onTag for the tag whose attributes come next. */
        var trusted = false

        /** The ids this input declares, decoded: a `#fragment` link to one follows it to its namespaced id. */
        private val declaredIds = HashSet<String>()

        /** `/\sid\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))/gi` over the input. */
        fun collectDeclaredIds(html: String) {
            var i = 0
            val n = html.length
            while (i < n) {
                if (!Js.isSpace(html[i]) || !Js.regionMatchesLower(html, i + 1, "id")) {
                    i++
                    continue
                }
                var p = i + 3
                while (p < n && Js.isSpace(html[p])) p++
                if (p >= n || html[p] != '=') {
                    i++
                    continue
                }
                p++
                while (p < n && Js.isSpace(html[p])) p++
                if (p >= n) {
                    i++
                    continue
                }
                val c = html[p]
                val value: String
                val end: Int
                if (c == '"' || c == '\'') {
                    val close = html.indexOf(c, p + 1)
                    if (close < 0) {
                        i++
                        continue
                    }
                    value = html.substring(p + 1, close)
                    end = close + 1
                } else {
                    var q = p
                    while (q < n && !Js.isSpace(html[q]) && html[q] != '"' && html[q] != '\'' && html[q] != '>') q++
                    if (q == p) {
                        i++
                        continue
                    }
                    value = html.substring(p, q)
                    end = q
                }
                declaredIds.add(RenderText.decodeAttributeEntities(Js.trim(value)))
                i = end
            }
        }

        private fun declaredFragment(fragment: String): String? {
            val candidates = try {
                listOf(fragment, Js.decodeUriComponent(fragment))
            } catch (e: com.ecency.core.render.js.UriError) {
                listOf(fragment)
            }
            return candidates.firstOrNull { it in declaredIds && isIdWhitelisted(it) }
        }

        fun onTagAttr(tag: String, name: String, value: String, isWhiteAttr: Boolean): String? {
            val decoded = RenderText.decodeAttributeEntities(Js.trim(value))
            val decodedLower = Js.toLowerCase(decoded)

            if (name.startsWith("on")) return ""
            // An href keeps only the schemes a() itself writes, read as a browser reads it (#2099).
            var hrefSchemeAllowed = false
            if (name == "href") {
                val scheme = hrefScheme(decoded)
                if (scheme != null && !isHrefScheme(scheme)) return if (isWhiteAttr) "href" else ""
                // only tel:, mailto: and web+ handlers skip xss's rule (#2107)
                hrefSchemeAllowed = scheme != null && isTelMailtoWeb(scheme)
            }
            if (tag == "img" && name == "src" && !startsWithHttp(decodedLower)) return ""
            if ((tag == "img" || tag == "source") && name == "srcset" && !beforeTransform && !isWellFormedSrcset(decoded)) return ""
            if (tag == "img" && name == "srcset") {
                if (srcsetCandidates(decoded).any { !startsWithHttpIgnoreCase(it[0]) }) return ""
            }
            if (tag == "source" && name == "srcset" && !isProxyPSrcset(decoded)) return ""
            if (tag == "source" && name == "type" && decodedLower != "image/avif" && decodedLower != "image/webp") return ""
            if ((tag == "video" || tag == "audio") && (name == "src" || name == "poster") && !startsWithHttp(decodedLower)) return ""
            if (tag == "audio" && name == "preload" && decodedLower != "metadata" && decodedLower != "none") return ""
            if (tag == "img" && (name == "dynsrc" || name == "lowsrc")) return ""
            if (tag == "img" && (name == "width" || name == "height")) {
                if (!options.preserveImageDimensions || ImageDimensions.parsePixelDimension(decoded) == null) return ""
            }
            if (tag == "span" && name == "class" && Js.trim(Js.toLowerCase(decoded)) == "wr") return ""
            // A class the render did not stamp is the author's: only the classes an author may write survive.
            if (isWhiteAttr && name == "class" && !trusted) {
                val kept = AuthorAttributes.authorClasses(decoded)
                return if (kept.isNotEmpty()) "class=\"${escapeAttr(kept)}\"" else ""
            }
            // The embed allow list holds on every path; a refused src gets iframe()'s spelling one more try (#1919).
            if (!beforeTransform && ((tag == "iframe" && name == "src") || name in EMBED_SRC_DATA_ATTRS) && hasTabOrNewline(decoded)) return ""
            if (tag == "iframe" && name == "src" && !beforeTransform && !EmbedSrc.isAllowedEmbedSrc(decoded)) {
                val canonical = EmbedSrc.canonicalEmbedSrc(decoded)
                return if (EmbedSrc.isAllowedEmbedSrc(canonical)) "src=\"${escapeAttr(canonical)}\"" else ""
            }
            // An author's sandbox keeps only the tokens iframe() itself grants.
            if (tag == "iframe" && name == "sandbox") {
                val tokens = splitWs(decodedLower)
                val kept = tokens.filter { it in AuthorAttributes.IFRAME_SANDBOX_GRANTS }
                if (kept.size != tokens.size) return "sandbox=\"${kept.joinToString(" ")}\""
            }
            // Full-render output: images and posters only ever load through the image proxy.
            if (enforce) {
                if (tag == "img" && name == "src" && !decoded.startsWith(proxyBase)) {
                    val proxied = proxy.proxify(decoded, forceProxy = true)
                    return if (proxied.isNotEmpty()) "src=\"${escapeAttr(proxied)}\"" else ""
                }
                if (tag == "img" && name == "srcset" && srcsetCandidates(decoded).any { !it[0].startsWith(proxyBase) }) return ""
                if (tag == "video" && name == "poster" && !decoded.startsWith(proxyBase)) {
                    val proxied = if (startsWithHttpIgnoreCase(decoded)) proxy.proxify(decoded, forceProxy = true) else ""
                    return if (proxied.isNotEmpty()) "poster=\"${escapeAttr(proxied)}\"" else ""
                }
            }
            if (enforce) {
                // a reference or control character in a class is the author's, hiding a marker until xss decodes it
                if (isWhiteAttr && name == "class" && value.any { it == '&' || it.code <= 0x1f || it.code == 0x7f }) {
                    val kept = splitWs(replaceControls(decoded)).filter { !Js.toLowerCase(it).contains("markdown") }.joinToString(" ")
                    return if (kept.isNotEmpty()) "class=\"${escapeAttr(kept)}\"" else ""
                }
                if (tag == "a" && name == "rel" && collapseWs(decodedLower) !in RENDER_HELPER_RELS) return ""
                if (tag == "a" && name == "target" && decodedLower != "_blank") return ""
            }
            // Tags the render did not stamp were hidden from the transforms: render-helper's markers and the link
            // policy on them are the author's, so they go.
            if (enforce && !trusted) {
                if (tag == "a" && (name == "target" || name == "rel")) return ""
                val navigable = if (tag == "a" && name == "href") navigableHref(decoded) else ""
                if (navigable.isNotEmpty() && isOffSite(navigable)) {
                    return "href=\"${escapeAttr(navigable)}\" rel=\"nofollow ugc noopener\""
                }
                if (name in AuthorAttributes.RENDER_HELPER_DATA_ATTRIBUTES) return ""
                if (isWhiteAttr && name == "class") {
                    val kept = AuthorAttributes.stripMarkdownClasses(decoded)
                    if (kept != decoded) return if (kept.isNotEmpty()) "class=\"${escapeAttr(kept)}\"" else ""
                }
            }
            if (name in EMBED_SRC_DATA_ATTRS && !EmbedSrc.isAllowedEmbedSrc(decoded)) return ""
            if (name == "data-href" && !isSafeNavValue(decoded)) return ""
            if (name == "id") {
                if (!isIdWhitelisted(decoded)) return ""
                // every id is the author's, namespaced away from the page's, exactly once per render
                if (isWhiteAttr && !beforeTransform && !untransformed) return "id=\"${escapeAttr(AuthorAttributes.namespacedId(decoded))}\""
            }
            if (isWhiteAttr && !beforeTransform && !untransformed && tag == "a" && (name == "href" || name == "data-href") && decoded.startsWith("#")) {
                val target = declaredFragment(decoded.substring(1))
                if (target != null) return "$name=\"#${escapeAttr(AuthorAttributes.namespacedId(target))}\""
            }
            // Always emit exactly the string that was validated.
            if (isWhiteAttr && name == "href") {
                return if (hrefSchemeAllowed || Xss.safeAttrValue(name, decoded).isNotEmpty()) "href=\"${escapeAttr(decoded)}\"" else "href"
            }
            if (!beforeTransform && isWhiteAttr && isUrlAttribute(tag, name)) return "$name=\"${escapeAttr(decoded)}\""
            return null
        }

        private fun isProxyPSrcset(srcset: String): Boolean {
            val prefix = trimTrailingSlash(options.proxyBase) + "/p/"
            val candidates = srcsetCandidates(srcset).map { it[0] }
            return candidates.isNotEmpty() && candidates.all { it.startsWith(prefix) }
        }
    }

    // data-* attributes later read as an iframe src by the client video extensions
    private val EMBED_SRC_DATA_ATTRS = setOf("data-embed-src", "data-video-href")
    private val RENDER_HELPER_RELS = setOf("nofollow ugc noopener", "noopener")
    private val HREF_SCHEMES = setOf("http", "https", "mailto", "tel", "hive", "steem", "esteem", "ecency")

    private fun trimTrailingSlash(s: String): String {
        var end = s.length
        while (end > 0 && s[end - 1] == '/') end--
        return s.substring(0, end)
    }

    /** `/^([a-z][a-z0-9+.-]*):/i` after leading `[\s\u0000- �]` and every tab, LF and CR are dropped. */
    private fun hrefScheme(decoded: String): String? {
        var i = 0
        while (i < decoded.length && (Js.isSpace(decoded[i]) || decoded[i].code <= 0x20 || decoded[i] == '�')) i++
        val sb = StringBuilder()
        for (k in i until decoded.length) {
            val c = decoded[k]
            if (c == '\t' || c == '\n' || c == '\r') continue
            sb.append(c)
        }
        val v = sb.toString()
        if (v.isEmpty() || !Js.isAsciiAlpha(v[0])) return null
        var p = 1
        while (p < v.length && (Js.isAsciiAlpha(v[p]) || Js.isDigit(v[p]) || v[p] == '+' || v[p] == '.' || v[p] == '-')) p++
        return if (p < v.length && v[p] == ':') v.substring(0, p) else null
    }

    private fun isWebPlus(lower: String): Boolean =
        lower.startsWith("web+") && lower.length > 4 && lower.substring(4).all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '+' || it == '-' }

    private fun isHrefScheme(scheme: String): Boolean {
        val lower = Js.toLowerCase(scheme)
        return lower in HREF_SCHEMES || isWebPlus(lower)
    }

    private fun isTelMailtoWeb(scheme: String): Boolean {
        val lower = Js.toLowerCase(scheme)
        return lower == "tel" || lower == "mailto" || isWebPlus(lower)
    }

    /** An off-site a() link: `/^(?:https?:)?\/\//i`. */
    private fun isOffSite(v: String): Boolean {
        var i = 0
        if (Js.regionMatchesLower(v, 0, "https:")) i = 6 else if (Js.regionMatchesLower(v, 0, "http:")) i = 5
        return v.startsWith("//", i)
    }

    /** The href as a browser reads it: leading `[\u0000- �]` dropped, tab/LF/CR removed, `\` as `/`. */
    private fun navigableHref(decoded: String): String {
        var i = 0
        while (i < decoded.length && (decoded[i].code <= 0x20 || decoded[i] == '�')) i++
        val sb = StringBuilder(decoded.length - i)
        for (k in i until decoded.length) {
            val c = decoded[k]
            when (c) {
                '\t', '\n', '\r' -> {}
                '\\' -> sb.append('/')
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun hasTabOrNewline(v: String) = v.indexOf('\t') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0

    private fun replaceControls(v: String): String {
        val sb = StringBuilder(v.length)
        for (c in v) sb.append(if (c.code <= 0x1f || c.code == 0x7f) ' ' else c)
        return sb.toString()
    }

    /** `value.split(/\s+/).filter(Boolean)` (JS whitespace). */
    internal fun splitWs(v: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < v.length) {
            while (i < v.length && Js.isSpace(v[i])) i++
            val start = i
            while (i < v.length && !Js.isSpace(v[i])) i++
            if (i > start) out.add(v.substring(start, i))
        }
        return out
    }

    /** `.replace(/\s+/g, ' ')`. */
    private fun collapseWs(v: String): String {
        val sb = StringBuilder(v.length)
        var i = 0
        while (i < v.length) {
            if (Js.isSpace(v[i])) {
                sb.append(' ')
                while (i < v.length && Js.isSpace(v[i])) i++
            } else {
                sb.append(v[i++])
            }
        }
        return sb.toString()
    }

    /** sanitize-html `escapeAttr`. */
    private fun escapeAttr(v: String): String {
        if (v.none { it == '&' || it == '"' || it == '<' || it == '>' }) return v
        val sb = StringBuilder(v.length + 8)
        for (c in v) when (c) {
            '&' -> sb.append("&amp;")
            '"' -> sb.append("&quot;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    private fun isAsciiWs(c: Char) = c == '\t' || c == '\n' || c == '\u000C' || c == '\r' || c == ' '

    /** `srcsetCandidates`: candidates split on commas, URL and descriptors on ASCII whitespace; empty ones dropped. */
    private fun srcsetCandidates(srcset: String): List<List<String>> = srcset.split(',').map { candidate ->
        val parts = ArrayList<String>()
        var i = 0
        while (i < candidate.length) {
            while (i < candidate.length && isAsciiWs(candidate[i])) i++
            val start = i
            while (i < candidate.length && !isAsciiWs(candidate[i])) i++
            if (i > start) parts.add(candidate.substring(start, i))
        }
        parts
    }.filter { it.isNotEmpty() }

    /** Each candidate a URL with at most one `320w` / `1.5x` / `.5x` descriptor. */
    private fun isWellFormedSrcset(srcset: String): Boolean = srcsetCandidates(srcset).all { parts ->
        parts.size == 1 || (parts.size == 2 && isDescriptor(parts[1]))
    }

    /** `/^(?:\d+(?:\.\d+)?|\.\d+)[wx]$/i`. */
    private fun isDescriptor(d: String): Boolean {
        if (d.length < 2) return false
        val last = d[d.length - 1]
        if (last != 'w' && last != 'x' && last != 'W' && last != 'X') return false
        val num = d.substring(0, d.length - 1)
        val dot = num.indexOf('.')
        if (dot < 0) return num.isNotEmpty() && num.all(Js::isDigit)
        val a = num.substring(0, dot)
        val b = num.substring(dot + 1)
        return b.isNotEmpty() && b.all(Js::isDigit) && a.all(Js::isDigit)
    }

    /** Attributes whose value is a URL the checks above validate. */
    private fun isUrlAttribute(tag: String, name: String): Boolean =
        (name == "src" && tag != "a") || name == "srcset" || name == "poster" || name == "data-href" || name in EMBED_SRC_DATA_ATTRS

    /**
     * render-boundary `isStrictTag`: `/^<[A-Za-z][A-Za-z0-9:_.-]*(?:\s+[A-Za-z0-9_:.-]+="[^"]*")*\s*\/?>$/`, the exact
     * shape xmldom serializes. Linear scan.
     */
    internal fun isStrictTag(raw: String): Boolean {
        val n = raw.length
        if (n < 3 || raw[0] != '<' || !Js.isAsciiAlpha(raw[1])) return false
        var i = 2
        fun nameChar(c: Char) = Js.isAsciiAlpha(c) || Js.isDigit(c) || c == ':' || c == '_' || c == '.' || c == '-'
        while (i < n && nameChar(raw[i])) i++
        while (true) {
            val wsStart = i
            while (i < n && Js.isSpace(raw[i])) i++
            if (i < n && nameChar(raw[i]) && i > wsStart) {
                // an attribute: name="value"
                var p = i
                while (p < n && nameChar(raw[p])) p++
                if (p + 1 < n && raw[p] == '=' && raw[p + 1] == '"') {
                    val close = raw.indexOf('"', p + 2)
                    if (close < 0) return false
                    i = close + 1
                    continue
                }
                return false
            }
            break
        }
        if (i < n && raw[i] == '/') i++
        return i == n - 1 && raw[i] == '>'
    }

    /** Drops an iframe start tag without a quoted src (and a close tag right after), repeated until stable. */
    private fun dropBareIframes(html: String): String {
        var current = html
        while (true) {
            val next = replaceTags(current, "iframe", trailingClose = true) { tag ->
                if (quotedAttrs(tag).any { Js.toLowerCase(it.name) == "src" }) tag else ""
            }
            if (next == current) return current
            current = next
        }
    }

    /** `/^https?:\/\//` on an already lower-cased value. */
    private fun startsWithHttp(v: String) = v.startsWith("http://") || v.startsWith("https://")

    private fun startsWithHttpIgnoreCase(v: String) = Js.regionMatchesLower(v, 0, "http://") || Js.regionMatchesLower(v, 0, "https://")

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
    internal fun replaceTags(html: String, name: String, trailingClose: Boolean = false, fn: (String) -> String): String {
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
            if (trailingClose) {
                // `(?:\s*<\/name>)?` after the tag
                var p = end
                while (p < html.length && Js.isSpace(html[p])) p++
                if (Js.regionMatchesLower(html, p, "</$name>")) end = p + name.length + 3
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
    fun decodeEntities(value: String): String = decode(value, EntityDecoder.Mode.LEGACY)

    /**
     * `decodeAttributeEntities` (2.5.45, #2085): an attribute value as a browser decodes it. A legacy named
     * reference without its `;` stays as written when `=` or an alphanumeric follows (`?x=1&timestamp=5` is not
     * `×tamp`). Same numeric guard as [decodeEntities].
     */
    fun decodeAttributeEntities(value: String): String = decode(value, EntityDecoder.Mode.ATTRIBUTE)

    /** entities `decodeHTMLStrict` of one complete reference, guarded as [decodeEntities] (helper `decodeCompleteReferences`). */
    fun decodeStrict(value: String): String = decode(value, EntityDecoder.Mode.STRICT)

    private fun decode(value: String, mode: EntityDecoder.Mode): String {
        if (value.indexOf('&') < 0) return value
        val safe = guardNumericRefs(value)
        return try {
            EntityDecoder.decode(safe, EntityDecoder.V6, mode)
        } catch (e: IllegalArgumentException) {
            // not reported: untrusted entity text
            safe
        }
    }

    /** helper `guardNumericRefs`: leading zeros of numeric references dropped, overlong ones to U+FFFD. */
    fun guardNumericRefs(value: String): String = replaceOverlong(stripLeadingZeros(value))

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
