package com.ecency.core.render.methods

import com.ecency.core.render.js.Js
import com.ecency.core.render.js.UriError
import com.ecency.core.render.dom.DomParseException
import com.ecency.core.render.dom.Document
import com.ecency.core.render.dom.HtmlStringFixes
import com.ecency.core.render.dom.XmlDomParser
import com.ecency.core.render.sanitize.RenderText
import com.ecency.core.render.url.WebUrl

/** render-helper `helper.ts` (the parts not already ported with the DOM layer). */
internal object Helper {
    fun decodeEntities(value: String): String = RenderText.decodeEntities(value)

    fun decodeAttributeEntities(value: String): String = RenderText.decodeAttributeEntities(value)

    /** Entities, then percent-decoding; a malformed `%` falls back to the entity-decoded string. */
    fun decodeImageSrc(src: String): String = decodeDomImageSrc(decodeEntities(src))

    /**
     * `decodeDomImageSrc` (2.5.46, #2092): a body image URL read from the parsed document, whose entities the
     * parser (or the markdown destination decode) already decoded exactly once: only percent-encoding is left.
     */
    fun decodeDomImageSrc(src: String): String = try {
        Js.trim(Js.decodeUriComponent(src))
    } catch (e: UriError) {
        Js.trim(src)
    }

    private const val TRAILING_PUNCTUATION = ".,;:!)]}*_~"
    private val IMAGE_EXTENSIONS = listOf("tiff", "tif", "jpeg", "jpg", "gif", "png", "svg", "ico", "heic", "webp", "arw")

    /** The length of the `IMAGE_EXT_G` alternative (`tiff?|jpe?g|gif|...`, case-insensitive) at [at], or 0. */
    private fun extensionAt(token: String, at: Int): Int {
        for (e in IMAGE_EXTENSIONS) if (Js.regionMatchesLower(token, at, e)) return e.length
        return 0
    }

    /**
     * `imageUrlEnd` (2.5.46, #2094): where the image URL in a bare URL token ends, or -1 when it is not one. A token
     * holding an image extension is an image taken whole, suffixes and query included; only prose punctuation at the
     * end of a URL without a query or fragment is left out, and with one an unbalanced trailing `)` (2.5.53). An extension a letter or digit runs on from (`.icons`),
     * or that another extension follows (`photo.jpg.html`), does not count. Linear.
     */
    fun imageUrlEnd(token: String): Int {
        var contentEnd = token.length
        if (token.indexOf('?') < 0 && token.indexOf('#') < 0) {
            while (contentEnd > 0 && TRAILING_PUNCTUATION.indexOf(token[contentEnd - 1]) >= 0) contentEnd--
        } else {
            // 2.5.53 (#2155): with a query or fragment only an unbalanced `)` at the end is prose, read in source
            // order (`?a=)&b=(1)` keeps the `)` that closes `(1)`).
            var open = 0
            var unmatchedTail = 0
            for (c in token) {
                when (c) {
                    '(' -> {
                        open++
                        unmatchedTail = 0
                    }
                    ')' -> if (open > 0) {
                        open--
                        unmatchedTail = 0
                    } else {
                        unmatchedTail++
                    }
                    else -> unmatchedTail = 0
                }
            }
            contentEnd -= unmatchedTail
        }
        var i = 0
        while (i < token.length) {
            if (token[i] != '.') {
                i++
                continue
            }
            val len = extensionAt(token, i + 1)
            if (len == 0) {
                i++
                continue
            }
            val at = i + 1 + len
            i = at
            if (at > contentEnd) continue
            if (at < contentEnd) {
                val c = token[at]
                if (Js.isAsciiAlpha(c) || Js.isDigit(c)) continue
                if (c == '.' && at + 1 < token.length && Js.isAsciiAlpha(token[at + 1])) continue
            }
            return contentEnd
        }
        return -1
    }

    /**
     * `decodeCompleteReferences` (2.5.46, #2092): only complete references (`&...;`) decode, strictly, as CommonMark
     * reads a link or image destination: `?a=1&copy` stays, `?a=1&copy;` is `?a=1©`, `&region;` is not `®ion;`.
     */
    fun decodeCompleteReferences(value: String): String {
        var amp = value.indexOf('&')
        if (amp < 0) return value
        val out = StringBuilder(value.length)
        var from = 0
        while (amp >= 0) {
            val end = completeReferenceEnd(value, amp)
            if (end < 0) {
                amp = value.indexOf('&', amp + 1)
                continue
            }
            out.append(value, from, amp)
            val reference = value.substring(amp, end)
            out.append(RenderText.decodeStrict(RenderText.guardNumericRefs(reference)))
            from = end
            amp = value.indexOf('&', end)
        }
        out.append(value, from, value.length)
        return out.toString()
    }

    /** `/&(?:#\d{1,7}|#x[\da-f]{1,6}|[a-z][a-z\d]{1,31});/i` at [at]: the end after `;`, or -1. */
    private fun completeReferenceEnd(s: String, at: Int): Int {
        var p = at + 1
        if (p >= s.length) return -1
        fun run(start: Int, max: Int, ok: (Char) -> Boolean): Int {
            var q = start
            while (q < s.length && ok(s[q])) q++
            val count = q - start
            return if (count in 1..max && q < s.length && s[q] == ';') q + 1 else -1
        }
        if (s[p] == '#') {
            p++
            if (p >= s.length) return -1
            val dec = run(p, 7) { Js.isDigit(it) }
            if (dec >= 0) return dec
            if (s[p] == 'x' || s[p] == 'X') return run(p + 1, 6) { Js.isHexDigit(it) }
            return -1
        }
        if (!Js.isAsciiAlpha(s[p])) return -1
        return run(p + 1, 31) { Js.isAsciiAlpha(it) || Js.isDigit(it) }
    }

    private val AMP_SENTINEL = JsRegex.compile("[\uE000\uE001]|%EE%80%8[01]|&#x0*e00[01];|&#0*5734[45];", ignoreCase = true)

    /** `hasAmpSentinel`: the body already holds the private-use pair the render swaps an escaped `&` for. */
    fun hasAmpSentinel(body: String): Boolean = AMP_SENTINEL.containsMatchIn(body)

    /** Remarkable's backslash escapes (`unescapeMd`). */
    private const val MD_ESCAPABLE = "\\!\"#$%&'()*+,./:;<=>?@[]^_`{|}~-"

    /**
     * `decodeRawBodyImageSrc`: a body image URL from the raw post body (the card image scan), decoded the way the
     * render reads it. A markdown [destination] is unescaped as Remarkable does (an escaped `&` stays literal unless
     * [ampEscapes] is off), complete references decoded once, then percent-encoding.
     */
    fun decodeRawBodyImageSrc(src: String, destination: Boolean = false, ampEscapes: Boolean = true): String {
        val unescaped = if (destination) {
            val sb = StringBuilder(src.length)
            var i = 0
            while (i < src.length) {
                val c = src[i]
                if (c == '\\' && i + 1 < src.length && MD_ESCAPABLE.indexOf(src[i + 1]) >= 0) {
                    val e = src[i + 1]
                    sb.append(if (e == '&' && ampEscapes) "&amp;" else e.toString())
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            sb.toString()
        } else {
            src.replace("\\&", "&amp;")
        }
        return decodeDomImageSrc(decodeCompleteReferences(unescaped))
    }

    /** `createDoc`: `<body>` wrapped xmldom parse, null for blank input or a parse error. */
    fun createDoc(html: String): Document? {
        if (Js.trim(html).isEmpty()) return null
        return try {
            XmlDomParser.parseFromString("<body>" + HtmlStringFixes.removeDuplicateAttributes(html) + "</body>")
        } catch (e: DomParseException) {
            null
        }
    }

    /** Removes every `<...>` with at least one character inside; a bare `<` or `<>` stays. */
    fun stripHtmlTags(s: String): String {
        val n = s.length
        val out = StringBuilder(n)
        var i = 0
        while (i < n) {
            val lt = s.indexOf('<', i)
            if (lt < 0) {
                out.append(s, i, n)
                break
            }
            out.append(s, i, lt)
            val gt = s.indexOf('>', lt + 1)
            if (gt < 0) {
                out.append(s, lt, n)
                break
            }
            if (gt == lt + 1) {
                out.append(s, lt, gt + 1)
                i = gt + 1
                continue
            }
            i = gt + 1
        }
        return out.toString()
    }

    fun trimTrailingSlash(s: String): String {
        var end = s.length
        while (end > 0 && s[end - 1] == '/') end--
        return s.substring(0, end)
    }

    fun stripQueryString(s: String): String {
        val q = s.indexOf('?')
        return if (q >= 0 && q < s.length - 1) s.substring(0, q) else s
    }

    private val YT_T = JsRegex.compile("^(?:([0-9]{1,7})h)?(?:([0-9]{1,7})m)?(?:([0-9]{1,7})s?)?$", ignoreCase = true)
    private const val YT_MAX_SECONDS = 9_999_999L

    /**
     * 2.5.44 (#2090): `t` as seconds (`90`, `90s`) or YouTube's `XhYmZs` form (`1m30s`), each part and the total
     * bounded to 7 digits; an invalid `t` falls back to a valid `start` (digits only, leading zeros dropped); else "".
     */
    fun extractYtStartTime(url: String): String {
        val u = WebUrl.parse(url) ?: return ""
        val params = u.searchParams
        val t = params.firstOrNull { it.first == "t" }?.second.orEmpty()
        val m = YT_T.matchEntire(t)
        if (m != null) {
            val (h, min, sec) = m.destructured
            if (h.isNotEmpty() || min.isNotEmpty() || sec.isNotEmpty()) {
                val seconds = (h.toLongOrNull() ?: 0) * 3600 + (min.toLongOrNull() ?: 0) * 60 + (sec.toLongOrNull() ?: 0)
                if (seconds <= YT_MAX_SECONDS) return seconds.toString()
            }
        }
        val start = params.firstOrNull { it.first == "start" }?.second.orEmpty()
        return if (start.length in 1..7 && start.all { Js.isDigit(it) }) start.toLong().toString() else ""
    }

    fun youtubeVideoLinkClass(matchedUrl: String): String {
        val base = "markdown-video-link markdown-video-link-youtube"
        return if (Js.indexOfCi(matchedUrl, "youtube.com/shorts/") >= 0) "$base markdown-video-link-youtube-portrait" else base
    }

    fun sanitizePermlink(permlink: String?): String {
        if (permlink.isNullOrEmpty()) return ""
        return permlink.substringBefore('?').substringBefore('#')
    }

    /** Hive permlink shape `^[a-z0-9-]+$` (which already rules out image extensions). */
    fun isValidPermlink(permlink: String?): Boolean {
        val s = sanitizePermlink(permlink)
        if (s.isEmpty()) return false
        for (c in s) if (!(c in 'a'..'z' || c in '0'..'9' || c == '-')) return false
        return true
    }

    fun isValidUsername(username: String?): Boolean {
        if (username.isNullOrEmpty()) return false
        if (username.length > 16) return false
        for (label in username.split('.')) {
            if (label.length < 3 || label.length > 16) return false
            if (label[0] !in 'a'..'z') return false
            for (c in label) if (!(c in 'a'..'z' || c in '0'..'9' || c == '-')) return false
            val last = label[label.length - 1]
            if (!(last in 'a'..'z' || last in '0'..'9')) return false
        }
        return true
    }
}
