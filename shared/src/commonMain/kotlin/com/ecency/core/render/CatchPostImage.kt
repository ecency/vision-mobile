package com.ecency.core.render

import com.ecency.core.render.js.Js
import com.ecency.core.render.markdown.BlockParser
import com.ecency.core.render.methods.CleanReply
import com.ecency.core.render.methods.Helper
import com.ecency.core.render.methods.Scanners
import com.ecency.core.render.sanitize.RenderText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [CardAutolinkTable] decoded once per process (every lookup builds its own [CatchPostImage]): one flag per UTF-16
 * code unit, never written after it is built.
 */
private val autolinkWordChars: BooleanArray by lazy {
    val set = BooleanArray(0x10000)
    for (range in CardAutolinkTable.WORD_CHARS.split(',')) {
        val dash = range.indexOf('-')
        for (c in range.substring(0, dash).toInt(16)..range.substring(dash + 1).toInt(16)) set[c] = true
    }
    set
}

/**
 * render-helper `catch-post-image.ts`: the card image of a post. Metadata first, then a regex
 * fast path over the raw body (code, comments, hidden HTML and unequal anchors blanked out,
 * offsets preserved), then the full render. Every regex of the JS file is a scanner here; the
 * comments name the pattern each one reproduces. Not cached (render-helper memoizes per entry).
 */
internal class CatchPostImage(private val helper: RenderHelper) {
    private val proxy = helper.proxy

    // ---- metadata --------------------------------------------------------------------------

    private fun parseMeta(json: String?): JsonElement? {
        if (json == null) return null
        return try {
            Json.parseToJsonElement(json)
        } catch (e: Exception) {
            // not reported: untrusted post metadata
            null
        }
    }

    private fun prop(meta: JsonElement?, name: String): JsonElement? = (meta as? JsonObject)?.get(name)

    private fun stringOf(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun truthy(e: JsonElement?): Boolean = when (e) {
        null, JsonNull -> false
        is JsonPrimitive -> if (e.isString) {
            e.content.isNotEmpty()
        } else {
            when (e.content) {
                "true" -> true
                "false" -> false
                else -> e.content.toDoubleOrNull().let { it != null && it != 0.0 && !it.isNaN() }
            }
        }
        else -> true
    }

    /** `value.length` as a truthiness test. */
    private fun lengthTruthy(e: JsonElement?): Boolean = when (e) {
        is JsonArray -> e.isNotEmpty()
        is JsonObject -> truthy(e["length"])
        is JsonPrimitive -> e.isString && e.content.isNotEmpty()
        else -> false
    }

    /** `value[0]`. */
    private fun index0(e: JsonElement?): JsonElement? = when (e) {
        is JsonArray -> e.getOrNull(0)
        is JsonObject -> e["0"]
        is JsonPrimitive -> if (e.isString && e.content.isNotEmpty()) JsonPrimitive(e.content.substring(0, 1)) else null
        else -> null
    }

    private fun firstMetaUrl(value: JsonElement?): String? {
        stringOf(value)?.let { if (Js.trim(it).isNotEmpty()) return it }
        if (value is JsonArray) {
            for (v in value) {
                val s = stringOf(v)
                if (s != null && Js.trim(s).isNotEmpty()) return s
            }
        }
        return null
    }

    /** `onProxy` (2.5.58): a src already on the proxy (`/p/`, `/u/`, a sized route), which the body keeps as written. */
    private fun onProxy(src: String): Boolean {
        val base = proxy.base
        if (src.startsWith("$base/p/") || src.startsWith("$base/u/")) return true
        if (!src.startsWith("$base/")) return false
        // `/^\d+x\d+\//` after the base
        var i = base.length + 1
        val d1 = i
        while (i < src.length && src[i] in '0'..'9') i++
        if (i == d1 || i >= src.length || src[i] != 'x') return false
        i++
        val d2 = i
        while (i < src.length && src[i] in '0'..'9') i++
        return i > d2 && i < src.length && src[i] == '/'
    }

    /**
     * `proxifySource` (2.5.58, #2185, #2191): a src as the body reads it (`bodyImageSources`): one already on the
     * proxy is kept as written (unsized, the card is that src), any other goes through `/p/`, legacy sized hosts
     * included. Empty when the proxy refuses it.
     */
    private fun proxifySource(written: String, w: Int, h: Int): String {
        val source = Js.trim(written)
        if (!onProxy(source)) return proxy.proxify(Helper.decodeDomImageSrc(source), w, h, forceProxy = true)
        if (w == 0 && h == 0) return source
        val proxied = proxy.proxify(Helper.decodeDomImageSrc(source), w, h)
        return if (proxied.startsWith("${proxy.base}/p/")) proxied else proxy.proxify(source, w, h)
    }

    /**
     * `regexTierImage` (2.5.58): the full lookup's regex tier, the first body image when it is a markdown or HTML
     * image the scans read, proxied, or null to leave the body to the render. A bare URL or promoted link first is the
     * render's to read (#2180), and so is an image the proxy refuses (#2182), and one after a video poster (#2185).
     */
    private fun regexTierImage(raw: String, w: Int, h: Int): String? {
        val body = parserInput(raw)
        val prepared = prepareBody(body)
        val found = findFirstImageCandidate(prepared).candidate ?: return null
        if (!(found.kind == Candidate.HTML || found.md)) return null
        val poster = findFirstVideoPoster(prepared)
        if (poster != null && poster.pos < found.pos) return null
        val source = candidateSource(body, found) ?: return null
        return proxifySource(source, w, h).ifEmpty { null }
    }

    private fun getImage(entry: Entry, w: Int, h: Int, fast: Boolean): String? {
        val meta = parseMeta(entry.jsonMetadata)
        val thumbnail = firstMetaUrl(prop(meta, "thumbnails"))
        if (thumbnail != null) {
            val proxied = proxy.proxify(Helper.decodeEntities(thumbnail), w, h)
            if (proxied.isNotEmpty()) return proxied
        }
        val image = prop(meta, "image")
        val imageString = stringOf(image)
        if (imageString != null && imageString.isNotEmpty()) return proxy.proxify(Helper.decodeEntities(imageString), w, h)
        if (truthy(image) && lengthTruthy(image)) {
            val first = index0(image)
            if (truthy(first)) {
                val s = stringOf(first) ?: return ""
                return proxy.proxify(Helper.decodeEntities(s), w, h)
            }
        }
        if (fast) return fastBodyImage(entry.body, w, h)
        regexTierImage(entry.body, w, h)?.let { return it }
        return fromRender(entry.body, w, h)
    }

    private fun fromRender(body: String, w: Int, h: Int): String? {
        val html = helper.renderPostBody(body, true)
        val doc = Helper.createDoc(html) ?: return null
        // 2.5.58 (#2182): the render leaves an image the proxy refuses without a src, so the first image shown is the
        // first one that has a src
        val src = doc.getElementsByTagName("img").firstNotNullOfOrNull { img -> img.getAttribute("src")?.takeIf { it.isNotEmpty() } }
            ?: return null
        // a kept proxy URL the proxy would not take again (over its length limit) is the rendered src as it stands.
        // 2.5.59 (#2195): the DOM already decoded its references, so they are not decoded again
        return proxifySource(src, w, h).ifEmpty { src }
    }

    fun forEntry(entry: Entry, w: Int, h: Int, fast: Boolean): String? = getImage(entry, w, h, fast)

    fun forBody(body: String, w: Int, h: Int, fast: Boolean): String? {
        if (fast) return fastBodyImage(body, w, h)
        regexTierImage(body, w, h)?.let { return it }
        return fromRender(body, w, h)
    }

    /**
     * `bodyImageRawUrl` (2.5.58): the first body image as the render holds it ([candidateSource]), percent-decoded as
     * its `<img>` src is. Null when a video poster comes first, the proxy refuses it, a kept proxy URL would be named
     * decoded, or a destination decodes to a control character.
     */
    fun bodyImageRawUrl(raw: String): String? {
        val body = parserInput(raw)
        val prepared = prepareBody(body)
        val candidate = findFirstImageCandidate(prepared).candidate ?: return null
        val poster = findFirstVideoPoster(prepared)
        if (poster != null && poster.pos < candidate.pos) return null
        val source = candidateSource(body, candidate) ?: return null
        val url = Helper.decodeDomImageSrc(source)
        if (url.length > ImageProxy.MAX_PROXIED_URL_LENGTH) return null
        val trimmed = Js.trim(source)
        if (onProxy(trimmed) && url != trimmed) return null
        return if (candidate.kind == Candidate.DESTINATION && url.any { it < ' ' || it == '\u007F' }) null else url
    }

    /**
     * `candidateSource` (2.5.58, #2183): a candidate's URL as the render's DOM holds it, references decoded by where it
     * came from (an HTML attribute, a markdown destination, HTML block text or markdown text), percent-encoding still
     * as written. Every lookup reads a candidate through this, so they name one URL. Null where the render differs.
     */
    private fun candidateSource(body: String, candidate: Candidate): String? {
        if (candidate.kind == Candidate.HTML) return Helper.decodeAttributeEntities(candidate.url)
        val ampEscapes = !Helper.hasAmpSentinel(body)
        if (candidate.kind == Candidate.DESTINATION) return Helper.rawBodySource(candidate.url, true, ampEscapes)
        if (lineMasks(body).block[candidate.pos]) return htmlTextImageUrl(body, candidate.pos)
        val afterAt = candidate.pos + candidate.url.length
        val after = jsSlice(body, afterAt, afterAt + 4)
        // The token stops before a `;` that may close a reference (`.png&quot;`) or be punctuation after literal text.
        if (after.startsWith(";") && endsWithReference(candidate.url, 0, candidate.url.length)) return null
        // A lone surrogate reads as U+FFFD in the render (2.5.55, #2158), which ends the URL there.
        val loneAt = loneSurrogateAt(candidate.url)
        val token = if (loneAt == -1) candidate.url else candidate.url.substring(0, loneAt)
        fun tokenUrl(): String? {
            val url = withoutTrailingPunctuation(token, if (loneAt == -1) after else "")
            return if (Helper.imageUrlEnd(url) == -1) null else Helper.rawBodySource(url, false, ampEscapes)
        }
        // An autolink is its whole URL; one holding a reference is left to the render.
        if (candidate.pos > 0 && body[candidate.pos - 1] == '<' && after.startsWith(">")) {
            return if (loneAt != -1 || token.contains('&')) null else token
        }
        // A raw anchor's text and a reference definition's destination are not autolinked.
        if (isAnchorClose(after) || definitionDestinationAt(body, candidate.pos)) return tokenUrl()
        // 2.5.55 (#2157): markdown text with a parenthesis in the URL is read as the render's linkify (Autolinker)
        // reads it, only where nothing else can end the text first; everything else as before.
        val raw = markdownTextSpan(body, candidate.pos)
        val span = Helper.withoutLoneSurrogates(Helper.decodeCompleteReferences(raw.replace("\\&", "&amp;")))
        var k = candidate.pos - 1
        while (k >= 0 && (body[k] == ' ' || body[k] == '\t')) k--
        val before = if (k < 0) '\n' else body[k]
        val plain = (if (k == candidate.pos - 1) before == '\n' || before == '(' else before != '@') && !strayHashBefore(body, candidate.pos)
        if ((span.indexOf('(') < 0 && span.indexOf(')') < 0) || hasInlineDelimiter(span) || !plain) return tokenUrl()
        val url = span.substring(0, autolinkUrlEnd(span))
        return if (Helper.imageUrlEnd(url) == -1) null else url
    }

    /** `/^<\/a[\s>]/i` on the four characters after the URL. */
    private fun isAnchorClose(after: String): Boolean =
        after.length >= 4 && after[0] == '<' && after[1] == '/' && (after[2] == 'a' || after[2] == 'A') && (Js.isSpace(after[3]) || after[3] == '>')

    /** `/[*~_|^]|==|\+\+/`: an inline delimiter the parser may take. */
    private fun hasInlineDelimiter(span: String): Boolean {
        for (i in span.indices) {
            val c = span[i]
            if (c == '*' || c == '~' || c == '_' || c == '|' || c == '^') return true
            if ((c == '=' || c == '+') && i + 1 < span.length && span[i + 1] == c) return true
        }
        return false
    }

    /** `markdownTextSpan`: the text a bare URL at [pos] sits in, to whitespace, `<` or `[`. */
    private fun markdownTextSpan(body: String, pos: Int): String {
        var end = pos
        while (end < body.length && !Js.isSpace(body[end]) && body[end] != '<' && body[end] != '[') end++
        return body.substring(pos, end)
    }

    /** `loneSurrogateAt`: the index of the first lone surrogate in [text], or -1. */
    private fun loneSurrogateAt(text: String): Int {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isHighSurrogate()) {
                if (i + 1 < text.length && text[i + 1].isLowSurrogate()) {
                    i += 2
                    continue
                }
                return i
            }
            if (c.isLowSurrogate()) return i
            i++
        }
        return -1
    }

    /** `definitionDestinationAt`: whether [pos] starts a reference definition's destination (`]:`, blanks, at most one line break). */
    private fun definitionDestinationAt(body: String, pos: Int): Boolean {
        var k = pos - 1
        fun skipBlanks() {
            while (k >= 0 && (body[k] == ' ' || body[k] == '\t')) k--
        }
        skipBlanks()
        if (k >= 0 && body[k] == '\n') {
            k--
            skipBlanks()
        }
        return k >= 1 && body[k] == ':' && body[k - 1] == ']'
    }

    /**
     * `strayHashBefore`: whether the line before [pos] holds a `#` that is neither a hashtag nor after an Autolinker
     * word character; Autolinker's hashtag matcher then drops the URL. A heading's own marker is not text.
     */
    private fun strayHashBefore(body: String, pos: Int): Boolean {
        val lineStart = if (pos == 0) 0 else body.lastIndexOf('\n', pos - 1) + 1
        var line = body.substring(lineStart, pos)
        // `/^ {0,3}#{1,6}(?=[ \t])/`
        var sp = 0
        while (sp < 3 && sp < line.length && line[sp] == ' ') sp++
        var h = sp
        while (h < line.length && h - sp < 6 && line[h] == '#') h++
        if (h > sp && h < line.length && (line[h] == ' ' || line[h] == '\t')) line = line.substring(h)
        line = Helper.decodeCompleteReferences(line)
        var i = line.indexOf('#')
        while (i != -1) {
            val prev = if (i > 0) line[i - 1] else null
            val next = if (i + 1 < line.length) line[i + 1] else null
            if (!(prev != null && autolinkWordChar(prev)) && !(next != null && (autolinkWordChar(next) || next == '_'))) return true
            i = line.indexOf('#', i + 1)
        }
        return false
    }

    /** `autolinkWordChar`: a letter, mark or decimal digit, or one of Autolinker's emoji code units. */
    private fun autolinkWordChar(c: Char): Boolean = autolinkWordChars[c.code]

    /**
     * `autolinkUrlEnd` (2.5.55, #2157): where Autolinker 3.16 ends a scheme URL at the start of [text], ported from
     * url-matcher.js as render-helper's card does: the host's labels, a port, a path, query or fragment to its last
     * allowed end character; then a trailing `?` dropped, and either one closing bracket the match does not open
     * dropped, or the match cut where matchHasInvalidCharAfterTld cuts it. Linear.
     */
    private fun autolinkUrlEnd(text: String): Int {
        val hostStart = when {
            Js.startsWithCi(text, "https://") -> 8
            Js.startsWithCi(text, "http://") -> 7
            else -> return 0
        }
        var end = hostStart
        var pos = hostStart
        while (pos < text.length && autolinkWordChar(text[pos])) {
            var k = pos
            var lastWord = pos
            while (k < text.length && (autolinkWordChar(text[k]) || text[k] == '-' || text[k] == '_')) {
                if (autolinkWordChar(text[k])) lastWord = k
                k++
            }
            end = lastWord + 1
            if (end >= text.length || text[end] != '.' || end + 1 >= text.length || !autolinkWordChar(text[end + 1])) break
            pos = end + 1
        }
        if (end == hostStart) return 0
        // `/^:[0-9]+/`
        if (end < text.length && text[end] == ':') {
            var p = end + 1
            while (p < text.length && text[p] in '0'..'9') p++
            if (p > end + 1) end = p
        }
        if (end < text.length && (text[end] == '/' || text[end] == '?' || text[end] == '#')) {
            var last = end + 1
            for (i in end + 1 until text.length) {
                val c = text[i]
                val word = autolinkWordChar(c)
                if (!word && AUTOLINK_PUNCT.indexOf(c) < 0) break
                if (word || AUTOLINK_END.indexOf(c) >= 0) last = i + 1
            }
            end = last
        }
        if (text[end - 1] == '?') end--
        val close = text[end - 1]
        val open = when (close) {
            ')' -> '('
            ']' -> '['
            '}' -> '{'
            else -> null
        }
        if (open != null) {
            var depth = 0
            for (i in 0 until end - 1) {
                if (text[i] == open) depth++ else if (text[i] == close) depth = maxOf(depth - 1, 0)
            }
            if (depth == 0) return end - 1
        }
        // matchHasInvalidCharAfterTld: the longest `[-.w]*[-w]\.[-w]+` after the `//`, and a cut there when what
        // follows is not one of `[-.A-Za-z0-9:/?#]`
        var tld = -1
        var dotOk = false
        for (i in hostStart until end) {
            val c = text[i]
            if (c == '.') {
                dotOk = i > hostStart && text[i - 1] != '.'
            } else if (c == '-' || autolinkWordChar(c)) {
                if (dotOk) tld = i + 1
            } else {
                break
            }
        }
        if (tld != -1 && tld < end) {
            val c = text[tld]
            val allowed = c == '-' || c == '.' || Js.isAsciiAlpha(c) || c in '0'..'9' || c == ':' || c == '/' || c == '?' || c == '#'
            if (!allowed) return tld
        }
        return end
    }

    /** card-image.ts `AUTOLINK_PUNCT` and `AUTOLINK_END`. */
    private val AUTOLINK_PUNCT = "-+&@#/%=~_()|'\$*[]{}?!:,.;^\u2713"
    private val AUTOLINK_END = "-+&@#/%=~_()|'\$*[]{}\u2713"

    /** `codeBlanked`: fences and inline code blanked, nothing else. */
    private fun codeBlanked(body: String): String = blankInlineCode(blankFences(blankFences(body, "```"), "~~~"))

    /** `asciiLower`: only A-Z lowered, so positions stay those of the text. */
    private fun asciiLower(s: String): String {
        for (c in s) if (c in 'A'..'Z') return buildString(s.length) { for (ch in s) append(Js.asciiLower(ch)) }
        return s
    }

    /**
     * `withoutTrailingPunctuation`: `.,;:!?` at the end of a bare URL are the sentence's, unless a `)` or `</a>`
     * follows (the author closed the URL there) or the `;` ends a character reference.
     */
    private fun withoutTrailingPunctuation(url: String, after: String): String {
        if (after.startsWith(")") || (Js.startsWithCi(after, "</a") && after.length > 3 && (Js.isSpace(after[3]) || after[3] == '>'))) return url
        var end = url.length
        while (end > 0) {
            val c = url[end - 1]
            if (c == ';' && endsWithReference(url, maxOf(0, end - 34), end - 1)) break
            if (".,;:!?".indexOf(c) < 0) break
            end--
        }
        return url.substring(0, end)
    }

    /** `/&#?[a-z\d]+$/i` on `s[from, to)`. */
    private fun endsWithReference(s: String, from: Int, to: Int): Boolean {
        var i = to
        while (i > from && (Js.isAsciiAlpha(s[i - 1]) || Js.isDigit(s[i - 1]))) i--
        if (i == to) return false
        if (i > from && s[i - 1] == '#') i--
        return i > from && s[i - 1] == '&'
    }

    /**
     * `htmlTextImageUrl`: a bare URL on an HTML block line is read as HTML text (references decoded, `&lt;`/`&gt;`
     * end it), then cut where [Helper.imageUrlEnd] says.
     */
    private fun htmlTextImageUrl(body: String, pos: Int): String? {
        var end = pos
        while (end < body.length && !Js.isSpace(body[end]) && body[end] != '<') end++
        val text = decodeHtmlText(body.substring(pos, end))
        val q = Scanners.schemeEnd(text, 0)
        if (q < 0) return null
        var e = q
        while (e < text.length) {
            val c = text[e]
            if (Js.isSpace(c) || c == '<' || c == '>' || c == '"' || c == '\'') break
            if (c == '&' && (Js.startsWithCi(text, "lt;", e + 1) || Js.startsWithCi(text, "gt;", e + 1))) break
            e++
        }
        if (e == q) return null
        val token = text.substring(0, e)
        val urlEnd = Helper.imageUrlEnd(token)
        return if (urlEnd == -1) null else token.substring(0, urlEnd)
    }

    /**
     * `decodeHtmlText`: `/&(?:#\d{1,7};?|#[xX][\da-fA-F]{1,6};?|([A-Za-z][A-Za-z\d]{0,31});?)/g`, a numeric reference
     * decoded as HTML text, a named one only when its whole name is a reference (with or without its `;`).
     */
    private fun decodeHtmlText(text: String): String {
        var amp = text.indexOf('&')
        if (amp < 0) return text
        val out = StringBuilder(text.length)
        var last = 0
        while (amp >= 0) {
            var p = amp + 1
            var named = false
            if (p < text.length && text[p] == '#') {
                p++
                val d = run(text, p, 7) { Js.isDigit(it) }
                p = if (d > p) d else if (p < text.length && (text[p] == 'x' || text[p] == 'X')) run(text, p + 1, 6) { Js.isHexDigit(it) }.let { if (it > p + 1) it else -1 } else -1
            } else if (p < text.length && Js.isAsciiAlpha(text[p])) {
                p = run(text, p + 1, 31) { Js.isAsciiAlpha(it) || Js.isDigit(it) }
                named = true
            } else {
                p = -1
            }
            if (p < 0) {
                amp = text.indexOf('&', amp + 1)
                continue
            }
            val nameEnd = p
            if (p < text.length && text[p] == ';') p++
            val ref = text.substring(amp, p)
            out.append(text, last, amp)
            if (named) {
                val strict = "&" + text.substring(amp + 1, nameEnd) + ";"
                val whole = RenderText.decodeStrict(strict)
                out.append(if (whole == strict) ref else whole)
            } else {
                out.append(RenderText.decodeEntities(ref))
            }
            last = p
            amp = text.indexOf('&', p)
        }
        out.append(text, last, text.length)
        return out.toString()
    }

    /** End of a run of at most [max] characters from [from] that satisfy [ok]. */
    private inline fun run(s: String, from: Int, max: Int, ok: (Char) -> Boolean): Int {
        var i = from
        while (i < s.length && i - from < max && ok(s[i])) i++
        return i
    }

    fun entryImageRawUrl(entry: Entry): String? {
        val meta = parseMeta(entry.jsonMetadata)
        val image = prop(meta, "image")
        val imageString = stringOf(image)
        if (imageString != null && imageString.isNotEmpty()) return Helper.decodeImageSrc(imageString)
        if (truthy(image) && lengthTruthy(image)) {
            val first = stringOf(index0(image))
            if (first != null && first.isNotEmpty()) return Helper.decodeImageSrc(first)
        }
        return bodyImageRawUrl(entry.body)
    }

    fun cardImageRawUrl(entry: Entry): String? {
        val thumbnail = firstMetaUrl(prop(parseMeta(entry.jsonMetadata), "thumbnails"))
        if (thumbnail != null) return Helper.decodeImageSrc(thumbnail)
        return entryImageRawUrl(entry)
    }

    // ---- body scans ------------------------------------------------------------------------

    private class Spellings(val text: String, val lower: String)

    /** [inTag]: [markInsideTags] of [text]. */
    private class ScanText(val text: String, val inTag: ByteArray)

    private class Prepared(val source: String, val cleaned: String, val image: ScanText, val video: ScanText) {
        /** `rawTagsOf`: tag marks on the code-stripped text before any anchor is blanked. */
        var rawTags: ByteArray? = null
    }

    private class Candidate(
        val url: String,
        val pos: Int,
        val kind: Int = BARE,
        /** A markdown image (`![](url)`), as opposed to a link the render promotes. */
        val md: Boolean = false,
        /** A video poster whose context the scans could not confirm. */
        val uncertain: Boolean = false,
    ) {
        companion object {
            const val BARE = 0
            const val HTML = 1
            const val DESTINATION = 2
        }
    }

    private class CandidateResult(val candidate: Candidate?, val ambiguous: Boolean)

    /**
     * `fastBodyImage` (2.5.58, #2185): the first `<img>` the render shows in source order, an image, a promoted link
     * or a video poster, without rendering; null where the scans cannot decide.
     */
    private fun fastBodyImage(raw: String, w: Int, h: Int): String? {
        val body = parserInput(raw)
        val prepared = prepareBody(body)
        val found = findFirstImageCandidate(prepared)
        if (found.ambiguous) return null
        val first = found.candidate
        val poster = findFirstVideoPoster(prepared)
        val posterFirst = poster != null && (first == null || poster.pos < first.pos)
        // a poster first whose context is unconfirmed may be link text or a title
        if (posterFirst && poster!!.uncertain) return null
        if (first != null && (first.kind == Candidate.HTML || first.md) && !posterFirst) {
            val source = candidateSource(body, first) ?: return null
            return proxifySource(source, w, h).ifEmpty { null }
        }
        // 2.5.57 (#2179): the render ends a bare URL at a lone surrogate (#2158), which the full lookup reads its own
        // way: give up rather than name another URL, unless a poster comes first and wins on source order anyway.
        if (first != null && first.kind != Candidate.DESTINATION && loneSurrogateAt(first.url) != -1 && !posterFirst) return null
        if (posterFirst) return proxifySource(proxy.proxify(poster!!.url), w, h)
        if (first == null) return null
        val source = candidateSource(body, first) ?: return null
        // a URL the proxy refuses is one the render drops
        return proxifySource(source, w, h).ifEmpty { null }
    }

    /**
     * `parserInput` (2.5.56, #2176): the body as the render's parser reads it, so the card scans the same text. First
     * the render's own input pass: cleanReply (the render drops platform byline lines) and leofinance/inleo links
     * internalised; its lone surrogate pass keeps every offset and the scans read a lone surrogate where it sits
     * (#2158), so it is not repeated here. Then remarkable's block pre-pass: U+00A0 as a space, the line separators as
     * newlines, each tab to its four-column stop. Raw HTML is part of that text too.
     */
    private fun parserInput(raw: String): String {
        val body = Helper.internalizeLeoLinks(CleanReply.cleanReply(raw))
        if (body.none(::isParserRewritten)) return body
        return BlockParser.parserText(body)
    }

    /** `PARSER_REWRITTEN_RE = /[\u00a0\t\r\u2424\u2028\u0085]/` */
    private fun isParserRewritten(c: Char): Boolean = when (c.code) {
        0xA0, 0x09, 0x0D, 0x2424, 0x2028, 0x85 -> true
        else -> false
    }

    private fun prepareBody(body: String): Prepared {
        val cleaned = if (body.isEmpty()) "" else stripCodeRegions(body)
        if (cleaned.isEmpty()) return Prepared(body, cleaned, ScanText("", ByteArray(0)), ScanText("", ByteArray(0)))
        val imageText = blankUnequalAnchors(cleaned, false)
        val videoText = blankUnequalAnchors(cleaned, true)
        return Prepared(body, cleaned, ScanText(imageText, markInsideTags(imageText)), ScanText(videoText, markInsideTags(videoText)))
    }

    private fun isSafeUrl(s: String) = Js.startsWithCi(s, "http://") || Js.startsWithCi(s, "https://")

    private val NONE = CandidateResult(null, false)
    private val AMBIGUOUS = CandidateResult(null, true)

    private fun findFirstImageCandidate(prepared: Prepared): CandidateResult {
        val cleaned = prepared.cleaned
        if (cleaned.isEmpty()) return NONE
        val md = mdImageFind(cleaned)
        val html = matchHtmlImage(cleaned) { rawTagsOf(prepared) }
        // An angle destination is whole whatever it holds, read in its plain spelling (2.5.55, #2156); a plain one
        // with `(` may have been cut short by the capture class. A plain destination's escapes resolve as an angle
        // one's do (`\)` is `)`, 2.5.56, #2176); one without a backslash is unchanged.
        val mdUrl = md?.let {
            if (it.angle || it.raw.indexOf('\\') >= 0) {
                Helper.plainDestination(Helper.withoutLoneSurrogates(Helper.unescapeDestination(it.raw, !Helper.hasAmpSentinel(cleaned))))
            } else {
                it.raw
            }
        }
        if (md != null && mdUrl != null) {
            if (mdUrl.isEmpty() || !isSafeUrl(mdUrl) || (!md.angle && mdUrl.contains('(')) ||
                // 2.5.56 (#2176): a line inside the image that may open a block, which can end the paragraph first
                opensBlockInside(cleaned, md.pos, md.end) ||
                // An escaped `!` before a link whose text holds a URL (2.5.57, #2181 review): only the parser reads it.
                escapedAt(cleaned, md.pos) ||
                // A backslash before whitespace or a control escapes nothing in a plain destination.
                (!md.angle && escapesBlank(md.raw)) ||
                // 2.5.57 (#2179): with a `%` that starts no escape, the render normalises the escaped destination and
                // decodes it once, which only the parser reads exactly.
                (!md.angle && md.raw.indexOf('\\') >= 0 && hasStrayPercent(md.raw))
            ) {
                return AMBIGUOUS
            }
        }
        val prior = if (md != null) cleaned.substring(0, md.pos) else cleaned
        val seen = BooleanArray(1)
        if (hasUndecidedImage(prior, seen)) return AMBIGUOUS

        val candidates = ArrayList<Candidate>()
        if (md != null && !mdUrl.isNullOrEmpty()) candidates.add(Candidate(mdUrl, md.pos, Candidate.DESTINATION, md = true))
        // 2.5.58: `data-src`, `x-src`: which attribute the render takes is its own to say, where it may come first
        // 2.5.59 (#2195): and so is an `<img` inside another tag
        val dashSrc = html != null && (html.dashed || html.undecided)
        if (dashSrc && !(md != null && !mdUrl.isNullOrEmpty() && md.pos < html!!.index)) return AMBIGUOUS
        // the render trims the attribute (#2185)
        if (html != null && !dashSrc && isSafeUrl(Js.trim(html.src))) candidates.add(Candidate(html.src, html.index, Candidate.HTML))
        // 2.5.58 (#2180): a URL after a `](` that closes no label the scans read is text the render linkifies, maybe
        // into an image: the first one defers when it comes before every candidate.
        val scan = prepared.image
        val labels = knownLabelCloses(cleaned)
        val untrusted = labels.untrusted
        var undecidedAt = -1
        var range = 0
        for (m in urlTokens(scan.text)) {
            val idx = m.pos
            if (scan.inTag.marked(idx)) continue
            val url = imageToken(m.url) ?: continue
            while (range < untrusted.size && untrusted[range][1] <= idx) range++
            if (range < untrusted.size && untrusted[range][0] <= idx) {
                undecidedAt = idx
                break
            }
            val close = labelCloseBefore(cleaned, idx)
            if (close != -1) {
                if (close in labels.closes) continue
                undecidedAt = idx
                break
            }
            if (!isStandalone(scan, idx) || !isSafeUrl(url)) continue
            candidates.add(Candidate(url, idx))
            break
        }
        // `[url](url)`: a markdown link the render promotes to an image (a.method); an angle destination is the URL
        // inside the brackets (2.5.58)
        for (m in mdLinks(cleaned)) {
            if (m.start > 0 && cleaned[m.start - 1] == '!') continue
            val href = if (isAngleOnly(m.href)) m.href.substring(1, m.href.length - 1) else m.href
            if (href.isNotEmpty() && isImageHref(href) && promotesToImage(m.label, href)) {
                candidates.add(Candidate(href, m.start, Candidate.DESTINATION))
                break
            }
        }
        if (undecidedAt != -1 && candidates.all { undecidedAt < it.pos }) return AMBIGUOUS
        candidates.sortBy { it.pos } // stable, like Array.sort
        val first = candidates.firstOrNull()
        // Past a dropped image only the plain case is read (#2182): dropped images and whitespace, then a markdown
        // image, read as written.
        if (seen[0] && !(first != null && first.md && onlyDroppedBefore(prepared.source, first.pos))) return AMBIGUOUS
        if (first == null) return NONE
        // What comes before the first image must hold nothing the scans cannot read, and the image must sit where the
        // render shows it (#2185).
        if (unreadBefore(prepared, labels.closes, first.pos)) return AMBIGUOUS
        if (first.md && !mdImageShown(prepared, first.pos)) return AMBIGUOUS
        val bare = !(first.kind == Candidate.HTML || first.md)
        if (bare && !bareShown(prepared, first)) return AMBIGUOUS
        if (bare) {
            // a bare URL in an HTML block with an image on its line: the DOM may nest or reorder the two
            val lineEnd = cleaned.indexOf('\n', first.pos)
            if (lineMasks(prepared.source).block[first.pos] &&
                candidates.any { it !== first && (it.kind == Candidate.HTML || it.md) && (lineEnd == -1 || it.pos < lineEnd) }
            ) {
                return AMBIGUOUS
            }
        }
        return CandidateResult(first, false)
    }

    /** `/^<[^<>]*>$/` */
    private fun isAngleOnly(s: String): Boolean {
        if (s.length < 2 || s[0] != '<' || s[s.length - 1] != '>') return false
        for (i in 1 until s.length - 1) if (s[i] == '<' || s[i] == '>') return false
        return true
    }

    /**
     * `promotesToImage`: a.method compares the href as the DOM reads it with the label's serialized HTML, `&amp;` read
     * as `&` on both sides; both hold their complete references decoded, and the label is serialized again (#2098).
     */
    private fun promotesToImage(label: String, href: String): Boolean {
        val serialized = Helper.decodeCompleteReferences(Js.trim(label)).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return Helper.decodeCompleteReferences(Js.trim(href)).replace("&amp;", "&") == serialized.replace("&amp;", "&")
    }

    private fun isImageHref(href: String): Boolean {
        if (!isSafeUrl(href)) return false
        var d = href.indexOf('.')
        while (d >= 0) {
            // 2.5.46: not `.ico` in `.icons/readme` nor `.jpg` in `photo.jpg.html` (imageUrlEnd)
            if (extLen(href, d) > 0) return Helper.imageUrlEnd(href) != -1
            d = href.indexOf('.', d + 1)
        }
        return false
    }

    private fun findFirstVideoPoster(prepared: Prepared): Candidate? {
        val cleaned = prepared.cleaned
        if (cleaned.isEmpty()) return null
        var best: Candidate? = standaloneMatches(prepared.video, ::youtubeIdOf).firstOrNull()
        for (m in mdLinks(cleaned)) {
            if (m.start > 0 && cleaned[m.start - 1] == '!') continue
            if (best != null && m.start >= best.pos) break
            val href = m.href
            if (href.isNotEmpty() && Js.trim(m.label) == href) {
                val id = youtubeIdOf(href)
                if (id != null) {
                    best = Candidate(id, m.start)
                    break
                }
            }
        }
        val b = best ?: return null
        return Candidate("https://img.youtube.com/vi/${b.url.substringBefore('?')}/hqdefault.jpg", b.pos, uncertain = !posterShown(prepared, b.pos))
    }

    // ---- code and hidden regions ------------------------------------------------------------

    private fun blankChars(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c == '\n') '\n' else ' ')
        return sb.toString()
    }

    /** `/```[\s\S]*?```/g` and the tilde form. */
    private fun blankFences(text: String, fence: String): String {
        var open = text.indexOf(fence)
        if (open < 0) return text
        val sb = StringBuilder(text.length)
        var last = 0
        while (open >= 0) {
            val close = text.indexOf(fence, open + 3)
            if (close < 0) break
            sb.append(text, last, open).append(blankChars(text.substring(open, close + 3)))
            last = close + 3
            open = text.indexOf(fence, last)
        }
        sb.append(text, last, text.length)
        return sb.toString()
    }

    /** `/`[^`\n]*`/g` */
    private fun blankInlineCode(text: String): String {
        val sb = StringBuilder(text.length)
        var last = 0
        var from = 0
        while (true) {
            val p = text.indexOf('`', from)
            if (p < 0) break
            var j = p + 1
            while (j < text.length && text[j] != '`' && text[j] != '\n') j++
            if (j < text.length && text[j] == '`') {
                sb.append(text, last, p).append(blankChars(text.substring(p, j + 1)))
                last = j + 1
                from = j + 1
            } else {
                from = p + 1
            }
        }
        sb.append(text, last, text.length)
        return sb.toString()
    }

    private fun stripCodeRegions(body: String): String {
        var text = blankFences(body, "```")
        text = blankFences(text, "~~~")
        text = blankInlineCode(text)
        return stripHiddenRegions(text)
    }

    private fun isOpenTagNameEnd(c: Char) = c == '\t' || c == '\u000C' || c == '\r' || c == ' ' || c == '/' || c == '>'
    private fun isCloseTagNameEnd(c: Char) = Js.isSpace(c) || c == '>'

    private fun findTag(lower: String, tag: String, from: Int, open: Boolean): Int {
        var at = lower.indexOf(tag, from)
        while (at != -1) {
            val i = at + tag.length
            val whole = i >= lower.length || (if (open) isOpenTagNameEnd(lower[i]) else isCloseTagNameEnd(lower[i]))
            if (whole) return at
            at = lower.indexOf(tag, at + tag.length)
        }
        return -1
    }

    /** Index of the `>` ending an opening tag, -1 when unterminated, [BROKEN] for broken or self-closing. */
    private fun findOpenTagEnd(lower: String, openAt: Int): Int {
        var quote = '\u0000'
        var i = openAt + 1
        while (i < lower.length) {
            val c = lower[i]
            if (c == '\n') return BROKEN
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                return if (lower[i - 1] == '/') BROKEN else i
            }
            i++
        }
        return -1
    }

    private val HTML_BLOCK_TAGS = setOf(
        "article", "aside", "button", "blockquote", "body", "canvas", "caption", "col", "colgroup", "dd", "div",
        "dl", "dt", "embed", "fieldset", "figcaption", "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5",
        "h6", "header", "hgroup", "hr", "iframe", "li", "map", "object", "ol", "output", "p", "pre", "progress",
        "script", "section", "style", "table", "tbody", "td", "textarea", "tfoot", "th", "tr", "thead", "ul", "video",
    )

    /** `^ {0,3}> ?` length, or -1. */
    private fun blockquotePrefix(line: String): Int {
        var i = 0
        while (i < 3 && i < line.length && line[i] == ' ') i++
        if (i >= line.length || line[i] != '>') return -1
        i++
        if (i < line.length && line[i] == ' ') i++
        return i
    }

    /** `^(?:[-*+]|\d{1,9}[.)]) +` length, or -1. */
    private fun listPrefix(line: String): Int {
        var i: Int
        if (line.isNotEmpty() && (line[0] == '-' || line[0] == '*' || line[0] == '+')) {
            i = 1
        } else {
            i = 0
            while (i < line.length && line[i] in '0'..'9') i++
            if (i == 0 || i > 9 || i >= line.length || (line[i] != '.' && line[i] != ')')) return -1
            i++
        }
        val s = i
        while (i < line.length && line[i] == ' ') i++
        return if (i > s) i else -1
    }

    /**
     * `^ {0,3}<(?:[!?]|([a-z]{1,15})[\s/>]|\/([a-z]{1,15})[\s>])`: 0 when no match, 1 for a match
     * without a tag name, 2 for a block tag name, 3 for another tag name.
     */
    private fun htmlBlockLine(line: String): Int {
        var i = 0
        while (i < 3 && i < line.length && line[i] == ' ') i++
        if (i >= line.length || line[i] != '<') return 0
        i++
        if (i < line.length && (line[i] == '!' || line[i] == '?')) return 1
        var closing = false
        if (i < line.length && line[i] == '/') {
            closing = true
        }
        // the opening alternative is tried first; a '/' cannot start [a-z]
        val nameStart = if (closing) i + 1 else i
        var j = nameStart
        while (j < line.length && line[j] in 'a'..'z') j++
        val len = j - nameStart
        if (len < 1 || len > 15 || j >= line.length) return 0
        val c = line[j]
        val ok = if (closing) Js.isSpace(c) || c == '>' else Js.isSpace(c) || c == '/' || c == '>'
        if (!ok) return 0
        return if (line.substring(nameStart, j) in HTML_BLOCK_TAGS) 2 else 3
    }

    private class LineMasks(val block: BooleanArray, val code: BooleanArray)

    private fun markLines(lower: String): LineMasks {
        val block = BooleanArray(lower.length)
        val code = BooleanArray(lower.length)
        var inBlock = false
        var listIndent = 0
        var nestedItem = false
        var lineStart = 0
        while (lineStart <= lower.length) {
            var lineEnd = lower.indexOf('\n', lineStart)
            if (lineEnd == -1) lineEnd = lower.length
            var line = lower.substring(lineStart, lineEnd)
            if (inBlock) {
                if (Js.trim(line).isEmpty()) {
                    inBlock = false
                    listIndent = 0
                    nestedItem = false
                } else {
                    block.fill(true, lineStart, lineEnd)
                }
                lineStart = lineEnd + 1
                continue
            }
            var stripped = 0
            var sawList = false
            var lastWasList = false
            var inlineRemainder = false
            while (true) {
                val bq = blockquotePrefix(line)
                if (bq >= 0) {
                    line = line.substring(bq)
                    stripped += bq
                    lastWasList = false
                    inlineRemainder = false
                    continue
                }
                val lm = listPrefix(line)
                if (lm >= 0) {
                    line = line.substring(lm)
                    stripped += lm
                    if (lastWasList) inlineRemainder = true
                    sawList = true
                    lastWasList = true
                    continue
                }
                break
            }
            if (sawList) {
                listIndent = stripped
                nestedItem = inlineRemainder
            } else if (listIndent > 0 && Js.trim(line).isNotEmpty()) {
                var indent = 0
                while (indent < listIndent && indent < line.length && line[indent] == ' ') indent++
                if (indent >= minOf(listIndent, 2)) {
                    line = line.substring(indent)
                    inlineRemainder = nestedItem
                } else {
                    listIndent = 0
                    nestedItem = false
                }
            }
            val blank = Js.trim(line).isEmpty()
            if (blank) {
                inBlock = false
                listIndent = 0
                nestedItem = false
            } else if (inlineRemainder) {
                inBlock = false
            } else if (!inBlock && (line.startsWith("    ") || line.startsWith("\t"))) {
                code.fill(true, lineStart, lineEnd)
            } else if (!inBlock) {
                when (htmlBlockLine(line)) {
                    1, 2 -> inBlock = true
                    3 -> inBlock = false
                }
            }
            if (inBlock && !blank) block.fill(true, lineStart, lineEnd)
            lineStart = lineEnd + 1
        }
        return LineMasks(block, code)
    }

    private fun blankSpans(input: Spellings, open: String, close: String, tagNames: Boolean, blockMask: BooleanArray?): Spellings {
        val text = input.text
        val lower = input.lower
        fun findOpen(from: Int): Int {
            if (!tagNames) return lower.indexOf(open, from)
            var at = findTag(lower, open, from, true)
            while (at != -1 && (findOpenTagEnd(lower, at) == BROKEN || (blockMask != null && !blockMask[at]))) {
                at = findTag(lower, open, at + open.length, true)
            }
            return at
        }
        fun findClose(from: Int): Int = if (tagNames) findTag(lower, close, from, false) else lower.indexOf(close, from)
        var start = findOpen(0)
        if (start == -1) return input
        val textOut = StringBuilder(text.length)
        val lowerOut = StringBuilder(lower.length)
        var from = 0
        while (start != -1) {
            val end = findClose(start + open.length)
            val to = if (end == -1) {
                text.length
            } else if (tagNames) {
                val gt = lower.indexOf('>', end + close.length)
                if (gt == -1) text.length else gt + 1
            } else {
                end + close.length
            }
            val blanked = blankChars(jsSlice(lower, start, to))
            textOut.append(jsSlice(text, from, start)).append(blanked)
            lowerOut.append(jsSlice(lower, from, start)).append(blanked)
            from = to
            start = if (to >= text.length) -1 else findOpen(to)
        }
        textOut.append(jsSlice(text, from, text.length))
        lowerOut.append(jsSlice(lower, from, lower.length))
        return Spellings(textOut.toString(), lowerOut.toString())
    }

    /** `String.prototype.slice` (clamped, empty when start >= end). */
    private fun jsSlice(s: String, start: Int, end: Int): String {
        val a = start.coerceIn(0, s.length)
        val b = end.coerceIn(0, s.length)
        return if (a >= b) "" else s.substring(a, b)
    }

    private fun blankMasked(input: Spellings, mask: BooleanArray): Spellings {
        val text = StringBuilder()
        val lower = StringBuilder()
        var from = 0
        var i = 0
        while (i < mask.size) {
            if (!mask[i]) {
                i++
                continue
            }
            var j = i
            while (j < mask.size && mask[j]) j++
            text.append(jsSlice(input.text, from, i)).append(blankChars(jsSlice(input.text, i, j)))
            lower.append(jsSlice(input.lower, from, i)).append(blankChars(jsSlice(input.lower, i, j)))
            from = j
            i = j + 1
        }
        if (from == 0) return input
        return Spellings(text.append(jsSlice(input.text, from, input.text.length)).toString(), lower.append(jsSlice(input.lower, from, input.lower.length)).toString())
    }

    private fun stripHiddenRegions(text: String): String {
        var sp = Spellings(text, Js.toLowerCase(text))
        val masks = markLines(sp.lower)
        sp = blankMasked(sp, masks.code)
        sp = blankSpans(sp, "<!--", "-->", false, null)
        sp = blankSpans(sp, "<style", "</style", true, null)
        sp = blankSpans(sp, "<pre", "</pre", true, masks.block)
        sp = blankSpans(sp, "<code", "</code", true, masks.block)
        return sp.text
    }

    // ---- anchors and tags --------------------------------------------------------------------

    /** `HREF_ATTR_RE = /\shref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))/i` on [tag]: the value, or null. */
    private fun hrefAttr(tag: String): String? {
        var p = 0
        while (p < tag.length) {
            if (Js.isSpace(tag[p]) && Js.startsWithCi(tag, "href", p + 1)) {
                var i = p + 5
                while (i < tag.length && Js.isSpace(tag[i])) i++
                if (i < tag.length && tag[i] == '=') {
                    i++
                    while (i < tag.length && Js.isSpace(tag[i])) i++
                    if (i < tag.length) {
                        val c = tag[i]
                        if (c == '"' || c == '\'') {
                            val close = tag.indexOf(c, i + 1)
                            if (close >= 0) return tag.substring(i + 1, close)
                        } else if (c != '>') {
                            var e = i
                            while (e < tag.length && !Js.isSpace(tag[e]) && tag[e] != '"' && tag[e] != '\'' && tag[e] != '>') e++
                            if (e > i) return tag.substring(i, e)
                        }
                    }
                }
            }
            p++
        }
        return null
    }

    private fun hasGluedAttribute(tag: String): Boolean {
        var quote = '\u0000'
        for (i in tag.indices) {
            val c = tag[i]
            if (quote != '\u0000') {
                if (c == quote) {
                    quote = '\u0000'
                    if (i + 1 < tag.length && (tag[i + 1] in 'a'..'z' || tag[i + 1] in 'A'..'Z')) return true
                }
            } else if (c == '"' || c == '\'') {
                quote = c
            }
        }
        return false
    }

    /** `inner.search(/<[A-Za-z/!]/)` */
    private fun firstRealTag(s: String): Int {
        var i = s.indexOf('<')
        while (i >= 0) {
            if (i + 1 < s.length) {
                val c = s[i + 1]
                if (c in 'a'..'z' || c in 'A'..'Z' || c == '/' || c == '!') return i
            }
            i = s.indexOf('<', i + 1)
        }
        return -1
    }

    private fun blankUnequalAnchors(cleaned: String, textContent: Boolean): String {
        val lower = Js.toLowerCase(cleaned)
        val out = StringBuilder()
        var changed = false
        var from = 0
        var at = findTag(lower, "<a", 0, true)
        while (at != -1) {
            val gt = findOpenTagEnd(lower, at)
            if (gt == BROKEN || gt == -1 || hasGluedAttribute(jsSlice(cleaned, at, gt))) {
                at = findTag(lower, "<a", at + 2, true)
                continue
            }
            val closeAt = findTag(lower, "</a", gt + 1, false)
            val innerEnd = if (closeAt == -1) cleaned.length else closeAt
            var spanEnd = cleaned.length
            if (closeAt != -1) {
                val closeGt = lower.indexOf('>', closeAt + 3)
                spanEnd = if (closeGt == -1) cleaned.length else closeGt + 1
            }
            val href = hrefAttr(jsSlice(cleaned, at, gt)) ?: ""
            val inner = jsSlice(cleaned, gt + 1, innerEnd)
            val text = if (textContent) {
                Helper.stripHtmlTags(inner)
            } else {
                val firstTag = firstRealTag(inner)
                if (firstTag == -1) inner else inner.substring(0, firstTag)
            }
            if (href.isEmpty() || Helper.decodeEntities(Js.trim(text)) != Helper.decodeEntities(Js.trim(href))) {
                out.append(jsSlice(cleaned, from, at)).append(blankChars(jsSlice(cleaned, at, spanEnd)))
                from = spanEnd
                changed = true
            }
            at = if (spanEnd >= cleaned.length) -1 else findTag(lower, "<a", spanEnd, true)
        }
        if (!changed) return cleaned
        out.append(jsSlice(cleaned, from, cleaned.length))
        return out.toString()
    }

    /** `/^https?:\/\//i.test(text.slice(idx, idx + 8))` */
    private fun isAutolinkAt(text: String, idx: Int): Boolean = Scanners.schemeEnd(text, idx) >= 0

    /**
     * `markInsideTags`: nonzero ([marked]) for every character of a tag, quoted values included. 2.5.59 (#2195): the
     * `<` that opens a tag is [TAG_START], so a `<` marked [IN_TAG] sits inside a tag already open.
     */
    private fun markInsideTags(text: String): ByteArray {
        val marks = ByteArray(text.length)
        var inTag = false
        var quote = '\u0000'
        for (i in text.indices) {
            val c = text[i]
            if (!inTag) {
                if (c == '<' && i + 1 < text.length) {
                    val n = text[i + 1]
                    if ((n in 'a'..'z' || n in 'A'..'Z' || n == '/' || n == '!' || n == '?') && !isAutolinkAt(text, i + 1)) {
                        inTag = true
                        marks[i] = TAG_START
                    }
                }
                continue
            }
            marks[i] = IN_TAG
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == '>') {
                inTag = false
            }
        }
        return marks
    }

    /** A [markInsideTags] mark: any part of a tag. */
    private fun ByteArray.marked(i: Int) = this[i] != 0.toByte()

    private fun isStandalone(scan: ScanText, idx: Int): Boolean {
        if (scan.inTag.marked(idx)) return false
        if (idx == 0) return true
        val prev = scan.text[idx - 1]
        if (prev in 'a'..'z' || prev in 'A'..'Z' || prev in '0'..'9' || prev == '_' || prev in "/.:%?&=#[-") return false
        if (prev == '(' && idx > 1 && scan.text[idx - 2] == ']') return false
        return true
    }

    private fun isUrlTokenChar(c: Char) = !Js.isSpace(c) && c != '<' && c != '>' && c != '"' && c != '\'' &&
        c != '(' && c != ')' && c != '[' && c != ']'

    /** `URL_TOKEN_RE = /https?:\/\/(?:[^\s<>"'()[\]&]|&(?!lt;|gt;))+/gi` (2.5.51): every match, as (token, position). */
    private fun urlTokens(s: String): Sequence<Candidate> = sequence {
        var p = 0
        while (true) {
            val h = Js.indexOfCi(s, "http", p)
            if (h < 0) break
            val q = Scanners.schemeEnd(s, h)
            if (q < 0) {
                p = h + 1
                continue
            }
            var e = q
            // the regex is `/gi`: its `&(?!lt;|gt;)` refuses `&GT;` too
            while (e < s.length && isUrlTokenChar(s[e]) && !(s[e] == '&' && (Js.startsWithCi(s, "lt;", e + 1) || Js.startsWithCi(s, "gt;", e + 1)))) e++
            if (e == q) {
                p = h + 1
                continue
            }
            yield(Candidate(s.substring(h, e), h))
            p = e
        }
    }

    /** `standaloneMatches`: the standalone URL tokens, classified. */
    private fun standaloneMatches(scan: ScanText, classify: (String) -> String?): Sequence<Candidate> =
        urlTokens(scan.text).mapNotNull { m -> if (isStandalone(scan, m.pos)) classify(m.url)?.let { Candidate(it, m.pos) } else null }

    private val EXTS = arrayOf("tiff", "tif", "jpeg", "jpg", "gif", "png", "svg", "ico", "heic", "webp", "arw")

    /** Length of `\.(?:tiff?|jpe?g|gif|png|svg|ico|heic|webp|arw)` at [d] (dot included), or 0. */
    private fun extLen(s: String, d: Int): Int {
        if (d >= s.length || s[d] != '.') return 0
        for (e in EXTS) if (Js.startsWithCi(s, e, d + 1)) return e.length + 1
        return 0
    }

    /** 2.5.46: the image URL a bare token denotes, read as linkify reads it ([Helper.imageUrlEnd]). */
    private fun imageToken(token: String): String? {
        val end = Helper.imageUrlEnd(token)
        if (end == -1) return null
        val url = token.substring(0, end)
        return if (isSafeUrl(url)) url else null
    }

    private fun isYoutubeIdChar(c: Char) = c != '"' && c != '&' && c != '?' && c != '/' && !Js.isSpace(c)

    private fun youtubeIdOf(url: String): String? {
        val q = Scanners.schemeEnd(url, 0)
        if (q < 0) return null
        var e = q
        while (e < url.length && url[e] != '/' && url[e] != '?' && url[e] != '#') e++
        if (e == q) return null
        val host = Js.toLowerCase(url.substring(q, e))
        val isShort = host == "youtu.be"
        val isFull = host == "youtube.com" || host.endsWith(".youtube.com")
        if (!isShort && !isFull) return null
        val rest = url.substring(e)
        val beforeHash = rest.substringBefore('#')
        val qAt = beforeHash.indexOf('?')
        val path = if (qAt == -1) beforeHash else beforeHash.substring(0, qAt)
        val query = if (qAt == -1) "" else beforeHash.substring(qAt + 1)
        fun candidate(v: String?): String? {
            val id = if (v == null) "" else v.take(11)
            return if (id.length == 11 && id.all(::isYoutubeIdChar)) id else null
        }
        if (isFull && query.isNotEmpty()) {
            for (part in query.split('&')) {
                if (part.startsWith("v=")) candidate(part.substring(2))?.let { return it }
            }
        }
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (isShort) return candidate(segments.getOrNull(0))
        if (segments.size >= 2 && Js.toLowerCase(segments[0]) in setOf("v", "e", "embed", "shorts")) return candidate(segments[1])
        if (segments.size >= 3) return candidate(segments[segments.size - 1])
        return null
    }

    // ---- markdown and html image patterns -------------------------------------------------------

    private fun isWs(c: Char) = Js.isSpace(c)

    /** After `](`: `\s*` + href run + optional title + `\s*` + `)`; returns (hrefStart, hrefEnd, matchEnd) or null. */
    private fun parenTail(s: String, open: Int, isHrefChar: (Char) -> Boolean, maxHref: Int): IntArray? {
        var i = open + 1
        while (i < s.length && isWs(s[i])) i++
        val hs = i
        while (i < s.length && isHrefChar(s[i])) i++
        val he = i
        if (he == hs || he - hs > maxHref) return null
        // optional title
        var j = he
        while (j < s.length && isWs(s[j])) j++
        if (j > he && j < s.length && (s[j] == '"' || s[j] == '\'')) {
            var k = j + 1
            while (k < s.length && s[k] != '"' && s[k] != '\'') k++
            if (k < s.length) {
                k++
                while (k < s.length && isWs(s[k])) k++
                if (k < s.length && s[k] == ')') return intArrayOf(hs, he, k + 1)
            }
        }
        var k = he
        while (k < s.length && isWs(s[k])) k++
        if (k < s.length && s[k] == ')') return intArrayOf(hs, he, k + 1)
        return null
    }

    /** End index of `\[[^[\]]*\]` at [p] (the `]`), or -1. */
    private fun labelEnd(s: String, p: Int): Int {
        if (p >= s.length || s[p] != '[') return -1
        var i = p + 1
        while (i < s.length && s[i] != '[' && s[i] != ']') i++
        return if (i < s.length && s[i] == ']') i else -1
    }

    /** A markdown image match: its destination as written, where the match starts and ends, and its form. */
    private class MdImage(val raw: String, val pos: Int, val end: Int, val angle: Boolean, val labelEnd: Int)

    /** How deep `MD_IMAGE_RE`'s label nests balanced brackets (2.5.57, #2179). */
    private val mdLabelDepth = 2

    /**
     * `MD_IMAGE_RE` (2.5.56, #2176), first match:
     * `!\[(?:[^[\]\n]|\n(?! *\n))*\]\( *(?:\n *)?(?:<((?:[^<>\n\\]|\\[^\n]){1,2048})>|((?:[^)\\ CONTROLS]|\\[\s\S]){1,2048}))`
     * `(?:(?= |\n) *(?:\n *)?(?:"(?:[^"\n]|\n(?! *\n))*"|'(?:[^'\n]|\n(?! *\n))*'))? *(?:\n *)?\)`, where CONTROLS is
     * U+0000 to U+0020 and U+007F. As the parser reads an image: only spaces and newlines around the destination and
     * title, no part across a blank line; an angle destination (group 1) to its first unescaped `>` on the line
     * (2.5.55, #2156), a plain one (group 2) to a space or control, a backslash escaping the character after it.
     * No part can backtrack to another split, so one pass per `![` finds the match. Several `![` can share one
     * destination end, so the tail scans (a title can be long) are kept per start.
     */
    private fun mdImageFind(s: String): MdImage? {
        val tails = HashMap<Int, Int>()
        val urls = SchemeScan(s)
        var p = s.indexOf("![")
        while (p >= 0) {
            val found = mdImageAt(s, p, tails)
            // firstUnescapedImage (2.5.57, #2179): an escaped `\![...](url)` is a link, and the scan resumes past its
            // `![`, unless the text before the match's last `](` holds a URL (the caller defers that one).
            if (found != null && (!escapedAt(s, p) || urls.holdsUrl(p, s.lastIndexOf("](", found.end - 2)))) return found
            p = s.indexOf("![", p + 1)
        }
        return null
    }

    /** `MD_IMAGE_RE` at the `![` at [p], or null. [tails] keeps the tail scans per start (several `![` can share one). */
    private fun mdImageAt(s: String, p: Int, tails: HashMap<Int, Int>): MdImage? {
        val le = mdLabelEnd(s, p + 2)
        if (le < 0 || le + 1 >= s.length || s[le + 1] != '(') return null
        val i = skipSeparator(s, le + 2)
        val close = angleContentEnd(s, i)
        if (close >= 0) {
            val e = tails.getOrPut(close + 1) { mdTailEnd(s, close + 1) }
            if (e >= 0) return MdImage(s.substring(i + 1, close), p, e, true, le)
        }
        val he = plainDestinationEnd(s, i)
        if (he > i) {
            val e = tails.getOrPut(he) { mdTailEnd(s, he) }
            if (e >= 0) return MdImage(s.substring(i, he), p, e, false, le)
        }
        return null
    }

    /** `regexMatches(s, MD_IMAGE_RE_G)`: every match, each search resuming at the previous match's end. */
    private fun mdImages(s: String): Sequence<MdImage> = sequence {
        val tails = HashMap<Int, Int>()
        var p = s.indexOf("![")
        while (p >= 0) {
            val m = mdImageAt(s, p, tails)
            if (m != null) {
                yield(m)
                p = s.indexOf("![", m.end)
            } else {
                p = s.indexOf("![", p + 1)
            }
        }
    }

    /**
     * `escapedLabelHoldsUrl` on the match starting at a rising position: whether `://` sits in `s[from, to)`. The
     * first `://` at or after [from] is kept, so the matches together scan the text once.
     */
    private class SchemeScan(private val s: String) {
        private var at = -2

        fun holdsUrl(from: Int, to: Int): Boolean {
            if (at != -1 && at < from) at = s.indexOf("://", from)
            return at >= 0 && at + 3 <= to
        }
    }

    /** `escapedAt`: the character at [pos] follows an odd run of backslashes. */
    private fun escapedAt(text: String, pos: Int): Boolean {
        var k = pos - 1
        while (k >= 0 && text[k] == '\\') k--
        return (pos - 1 - k) % 2 == 1
    }

    /** `STRAY_PCT_RE = /%(?![\da-f]{2})/i`: a `%` that starts no percent escape. */
    private fun hasStrayPercent(raw: String): Boolean {
        var i = raw.indexOf('%')
        while (i >= 0) {
            if (i + 2 >= raw.length || !isHexDigit(raw[i + 1]) || !isHexDigit(raw[i + 2])) return true
            i = raw.indexOf('%', i + 1)
        }
        return false
    }

    private fun isHexDigit(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** At the newline [nl], `\n(?! *\n)` fails: spaces, then another newline (a blank line). */
    private fun blankLineAt(s: String, nl: Int): Boolean {
        var k = nl + 1
        while (k < s.length && s[k] == ' ') k++
        return k < s.length && s[k] == '\n'
    }

    /**
     * The label of `MD_IMAGE_RE` (2.5.57, #2179) from [from]: the index of its closing `]`, or -1. Each unit is
     * `[^[\]\n\\]`, a backslash escape `\\[^\n]` (one unit, so `\[` and `\]` are no brackets), a backslash before a
     * newline, `\n(?! *\n)`, or a bracketed group holding the same, balanced two levels deep; a third `[` fails the
     * match (the uncaptured-image check defers it). Every unit has one reading, so the scan never backtracks.
     */
    private fun mdLabelEnd(s: String, from: Int): Int {
        var depth = 0
        var i = from
        while (i < s.length) {
            when (s[i]) {
                '\\' -> {
                    if (i + 1 >= s.length) return -1
                    i += if (s[i + 1] == '\n') 1 else 2
                    continue
                }
                '\n' -> if (blankLineAt(s, i)) return -1
                '[' -> if (depth == mdLabelDepth) return -1 else depth++
                ']' -> if (depth == 0) return i else depth--
            }
            i++
        }
        return -1
    }

    /** ` *(?:\n *)?` from [from]: where it ends. */
    private fun skipSeparator(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i] == ' ') i++
        if (i < s.length && s[i] == '\n') {
            i++
            while (i < s.length && s[i] == ' ') i++
        }
        return i
    }

    /** Group 2 at [i]: the end of its 1 to 2048 units, or [i] when it matches nothing there. */
    private fun plainDestinationEnd(s: String, i: Int): Int {
        var j = i
        var units = 0
        while (j < s.length) {
            val c = s[j]
            if (c == '\\') {
                if (j + 1 >= s.length) break
                j += 2
            } else if (c == ')' || c.code <= 0x20 || c.code == 0x7F) {
                break
            } else {
                j++
            }
            // a 2049th unit starts no title, separator or `)`, so nothing matches at this `![`
            if (++units > 2048) return i
        }
        return j
    }

    /** `/\\[\u0000- ]/`: a backslash before a space or a control. */
    private fun escapesBlank(raw: String): Boolean {
        var i = raw.indexOf('\\')
        while (i >= 0) {
            if (i + 1 < raw.length && raw[i + 1].code <= 0x20) return true
            i = raw.indexOf('\\', i + 1)
        }
        return false
    }

    /** `<((?:[^<>\n\\]|\\[^\n]){1,2048})>` at [i]: the index of the closing `>`, or -1. */
    private fun angleContentEnd(s: String, i: Int): Int {
        if (i >= s.length || s[i] != '<') return -1
        var j = i + 1
        var units = 0
        while (j < s.length) {
            val c = s[j]
            if (c == '>') break
            if (c == '<' || c == '\n') return -1
            if (c == '\\') {
                if (j + 1 >= s.length || s[j + 1] == '\n') return -1
                j += 2
            } else {
                j++
            }
            if (++units > 2048) return -1
        }
        return if (j < s.length && units > 0) j else -1
    }

    /**
     * `(?:(?= |\n) *(?:\n *)?(?:"(?:[^"\n]|\n(?! *\n))*"|'(?:[^'\n]|\n(?! *\n))*'))? *(?:\n *)?\)` at [from]: the match
     * end, or -1. A title closes with the quote that opened it.
     */
    private fun mdTailEnd(s: String, from: Int): Int {
        if (from < s.length && (s[from] == ' ' || s[from] == '\n')) {
            val q = skipSeparator(s, from)
            if (q < s.length && (s[q] == '"' || s[q] == '\'')) {
                val quote = s[q]
                var m = q + 1
                while (m < s.length && s[m] != quote && !(s[m] == '\n' && blankLineAt(s, m))) m++
                if (m < s.length && s[m] == quote) {
                    val k = skipSeparator(s, m + 1)
                    if (k < s.length && s[k] == ')') return k + 1
                }
            }
        }
        val k = skipSeparator(s, from)
        return if (k < s.length && s[k] == ')') k + 1 else -1
    }

    /**
     * `opensBlockInside` (2.5.56, #2176) on the image match `s[from, to)`: a line inside it that may open a block as
     * the parser reads one, so the image is left to the render. `BLOCK_START_LINE_RE`, any indent:
     * `\n *(?:#{1,6}(?=[ \n]|$)|>|[-*+](?=[ \n]|$)|\d+[.)](?=[ \n]|$)|`{3,}|~{3,}|<[!?]|(?:[-*_] *){3,}(?=\n|$)|`
     * `=+ *(?=\n|$)|[-:][-:| ]*(?=\n|$)|[:~] |\[\^[^\]\n]*\]:|\|)` (2.5.57: no digit cap, footnote definitions), or `HTML_LINE_TAG_RE` (`\n {0,3}<\/?([A-Za-z][A-Za-z0-9-]*)`) naming
     * a block tag. Each newline is checked on its own line only, so the scan is linear.
     */
    private fun opensBlockInside(s: String, from: Int, to: Int): Boolean {
        var nl = s.indexOf('\n', from)
        while (nl in 0 until to) {
            var q = nl + 1
            while (q < to && s[q] == ' ') q++
            if (blockStartAt(s, q, to) || htmlBlockTagAt(s, nl + 1, to)) return true
            nl = s.indexOf('\n', nl + 1)
        }
        return false
    }

    /** One alternative of `BLOCK_START_LINE_RE` at [q], the text ending at [to] (`$`). */
    private fun blockStartAt(s: String, q: Int, to: Int): Boolean {
        if (q >= to) return false
        fun endsLine(k: Int) = k >= to || s[k] == '\n'
        fun spaceOrEnd(k: Int) = k >= to || s[k] == ' ' || s[k] == '\n'
        val c = s[q]
        when (c) {
            '#' -> {
                val k = runOf(s, q, to, '#')
                if (k - q <= 6 && spaceOrEnd(k)) return true
            }
            '>', '|' -> return true
            '`', '~' -> if (runOf(s, q, to, c) - q >= 3) return true
            '<' -> if (q + 1 < to && (s[q + 1] == '!' || s[q + 1] == '?')) return true
            '=' -> {
                var k = runOf(s, q, to, '=')
                while (k < to && s[k] == ' ') k++
                if (endsLine(k)) return true
            }
            in '0'..'9' -> {
                // `\d+[.)]`: an ordered list marker of any length (2.5.57, #2179)
                var k = q
                while (k < to && s[k] in '0'..'9') k++
                if (k < to && (s[k] == '.' || s[k] == ')') && spaceOrEnd(k + 1)) return true
            }
            '[' -> {
                // `\[\^[^\]\n]*\]:`: a footnote definition (2.5.57, #2179)
                if (q + 1 < to && s[q + 1] == '^') {
                    var k = q + 2
                    while (k < to && s[k] != ']' && s[k] != '\n') k++
                    if (k + 1 < to && s[k] == ']' && s[k + 1] == ':') return true
                }
            }
        }
        if ((c == '-' || c == '*' || c == '+') && spaceOrEnd(q + 1)) return true
        if (c == '-' || c == '*' || c == '_') {
            // (?:[-*_] *){3,}(?=\n|$): only the whole run of marks and spaces can be followed by the line end
            var k = q
            var marks = 0
            while (k < to && (s[k] == '-' || s[k] == '*' || s[k] == '_' || s[k] == ' ')) {
                if (s[k] != ' ') marks++
                k++
            }
            if (marks >= 3 && endsLine(k)) return true
        }
        if (c == '-' || c == ':') {
            var k = q + 1
            while (k < to && (s[k] == '-' || s[k] == ':' || s[k] == '|' || s[k] == ' ')) k++
            if (endsLine(k)) return true
        }
        return (c == ':' || c == '~') && q + 1 < to && s[q + 1] == ' '
    }

    private fun runOf(s: String, from: Int, to: Int, c: Char): Int {
        var k = from
        while (k < to && s[k] == c) k++
        return k
    }

    /** `\n {0,3}<\/?([A-Za-z][A-Za-z0-9-]*)` with the line starting at [start]: the name is a block tag. */
    private fun htmlBlockTagAt(s: String, start: Int, to: Int): Boolean {
        var k = start
        while (k < to && k - start < 3 && s[k] == ' ') k++
        if (k >= to || s[k] != '<') return false
        k++
        if (k < to && s[k] == '/') k++
        val nameStart = k
        if (k >= to || !(s[k] in 'a'..'z' || s[k] in 'A'..'Z')) return false
        while (k < to && (s[k] in 'a'..'z' || s[k] in 'A'..'Z' || s[k] in '0'..'9' || s[k] == '-')) k++
        return asciiLower(s.substring(nameStart, k)) in HTML_BLOCK_TAGS
    }

    /**
     * `MD_IMAGE_PRESENT_RE = /!\[[^[\]]*(?:\]\(\s*[^\s)]|\[)/g` at the `![` at [p]: the match end and where its label
     * stops (the `[` or `]`), or null. Every part has one reading.
     */
    private fun presentAt(s: String, p: Int): IntArray? {
        var i = p + 2
        while (i < s.length && s[i] != '[' && s[i] != ']') i++
        if (i >= s.length) return null
        if (s[i] == '[') return intArrayOf(i + 1, i)
        if (i + 1 >= s.length || s[i + 1] != '(') return null
        var k = i + 2
        while (k < s.length && isWs(s[k])) k++
        return if (k < s.length && s[k] != ')') intArrayOf(k + 1, i) else null
    }

    /**
     * `hasUndecidedImage` (2.5.58, #2182): whether [text] holds a markdown image the scans may not have captured (an
     * escaped `\![` is a link unless its label holds a URL, 2.5.57), skipping one the render drops for its length;
     * `seen[0]` is set when one was skipped.
     */
    private fun hasUndecidedImage(text: String, seen: BooleanArray): Boolean {
        var from = 0
        while (true) {
            var p = text.indexOf("![", from)
            var m: IntArray? = null
            while (p >= 0) {
                m = presentAt(text, p)
                if (m != null) break
                p = text.indexOf("![", p + 1)
            }
            if (p < 0 || m == null) return false
            // escapedLabelHoldsUrl: the match text before its `](` (or all of it)
            if (escapedAt(text, p) && !text.substring(p, m[1]).contains("://")) {
                from = p + 2
                continue
            }
            val end = droppedImageEnd(text, p)
            if (end == -1) return true
            seen[0] = true
            from = end
        }
    }

    /**
     * `DROPPED_IMAGE_RE = /!\[[^[\]\n\\`<>]*\]\((https?:\/\/[^\s()<>[\]\\`&"']+)\)/y` at [pos]: (url start, url end,
     * match end), or null. A plain label and a plain URL with nothing the render would shorten.
     */
    private fun droppedImageMatch(text: String, pos: Int): IntArray? {
        if (!text.startsWith("![", pos)) return null
        var i = pos + 2
        while (i < text.length && text[i] != '[' && text[i] != ']' && text[i] != '\n' && text[i] != '\\' && text[i] != '`' &&
            text[i] != '<' && text[i] != '>'
        ) {
            i++
        }
        if (!text.startsWith("](", i)) return null
        val us = i + 2
        // `https?:\/\/`, case-sensitive
        val q = when {
            text.startsWith("https://", us) -> us + 8
            text.startsWith("http://", us) -> us + 7
            else -> return null
        }
        var e = q
        while (e < text.length) {
            val c = text[e]
            if (Js.isSpace(c) || c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' || c == '\\' || c == '`' ||
                c == '&' || c == '"' || c == '\''
            ) {
                break
            }
            e++
        }
        if (e == q || e >= text.length || text[e] != ')') return null
        return intArrayOf(us, e, e + 1)
    }

    /** `droppedImageEnd` (2.5.58, #2182): the end of the image at [pos] when the render drops it for its length, else -1. */
    private fun droppedImageEnd(text: String, pos: Int): Int {
        val m = droppedImageMatch(text, pos) ?: return -1
        val length = m[1] - m[0]
        // an existing proxy URL is kept as written, whatever its length
        if (length <= ImageProxy.MAX_PROXIED_URL_LENGTH || text.startsWith("${proxy.base}/", m[0])) return -1
        if (text.indexOf('%', m[0]).let { it in m[0] until m[1] }) {
            // dropped for sure only when even every escape decoded is over the limit: each shrinks it by under three
            var escapes = 0
            var k = m[0]
            while (k < m[1]) {
                if (text[k] == '%' && k + 2 < m[1] && isHexDigit(text[k + 1]) && isHexDigit(text[k + 2])) {
                    escapes++
                    k += 3
                } else {
                    k++
                }
            }
            if (length - 3 * escapes <= ImageProxy.MAX_PROXIED_URL_LENGTH) return -1
        }
        return m[2]
    }

    /** `onlyDroppedBefore`: whether [text] before [pos] is only `DROPPED_IMAGE_G` matches and whitespace. */
    private fun onlyDroppedBefore(text: String, pos: Int): Boolean {
        val prefix = text.substring(0, pos)
        var i = 0
        while (i < prefix.length) {
            val m = if (prefix[i] == '!') droppedImageMatch(prefix, i) else null
            if (m != null) {
                i = m[2]
                continue
            }
            if (!Js.isSpace(prefix[i])) return false
            i++
        }
        return true
    }

    /**
     * An `<img` tag `matchHtmlImage` read: where it starts, its src, and whether the name only ends in `src`.
     * [undecided]: an `<img` inside another tag, maybe in a quoted attribute: the render's to read (2.5.59, #2195).
     */
    private class HtmlImage(val index: Int, val src: String, val dashed: Boolean, val undecided: Boolean = false)

    /** `SPACE_CHARS = ' \t\n\r\f'` */
    private fun isTagSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

    private fun skipTagSpaces(text: String, from: Int): Int {
        var i = from
        while (i < text.length && isTagSpace(text[i])) i++
        return i
    }

    /**
     * `matchHtmlImage` (2.5.58, #2185): the first `<img` tag with a quoted `src`, in one forward pass. A tag is read by
     * the HTML tag grammar attribute by attribute, a quoted value whole, so a `src` inside another attribute's value
     * is text. The first `src` decides, empty or not; a name that only ends in `src` (`data-src`) is reported as
     * dashed. At the first `<img` the grammar does not read, or one across a line that ends its paragraph, the scan
     * stops with null (unreadBefore defers on that `<img`).
     */
    private fun matchHtmlImage(text: String, marksOf: () -> ByteArray): HtmlImage? {
        val footnotes = text.contains("[^")
        var tagMarks: ByteArray? = null
        var p = Js.indexOfCi(text, "<img", 0)
        while (p >= 0) {
            // `\b`: no word character after the name
            if (p + 4 < text.length && isWordChar(text[p + 4])) {
                p = Js.indexOfCi(text, "<img", p + 1)
                continue
            }
            val start = p
            // `<div title='<img src="A">'>` shows no image: the attribute is text (2.5.59, #2195)
            val marks = tagMarks ?: marksOf().also { tagMarks = it }
            if (marks[start] == IN_TAG) return HtmlImage(start, "", dashed = false, undecided = true)
            var i = start + 4
            var found: HtmlImage? = null
            var srcSeen = false
            while (true) {
                val spaced = skipTagSpaces(text, i)
                // `/\/?>/y`
                if (spaced < text.length && text[spaced] == '>') {
                    i = spaced + 1
                    break
                }
                if (spaced + 1 < text.length && text[spaced] == '/' && text[spaced + 1] == '>') {
                    i = spaced + 2
                    break
                }
                // `/[A-Za-z_:][\w.:-]*/y`, after whitespace only
                if (spaced == i || spaced >= text.length) return null
                val c0 = text[spaced]
                if (!(Js.isAsciiAlpha(c0) || c0 == '_' || c0 == ':')) return null
                var ne = spaced + 1
                while (ne < text.length && (isWordChar(text[ne]) || text[ne] == '.' || text[ne] == ':' || text[ne] == '-')) ne++
                i = ne
                var value: String? = null
                val eq = skipTagSpaces(text, i)
                if (eq < text.length && text[eq] == '=') {
                    // `/"([^"]*)"|'([^']*)'|[^\s"'=<>`]+/y`: an unquoted value is read but holds no src
                    val v = skipTagSpaces(text, eq + 1)
                    if (v < text.length && (text[v] == '"' || text[v] == '\'')) {
                        val close = text.indexOf(text[v], v + 1)
                        if (close >= 0) {
                            i = close + 1
                            value = text.substring(v + 1, close)
                        } else {
                            var u = v
                            while (u < text.length && isUnquotedValueChar(text[u])) u++
                            if (u > v) i = u
                        }
                    } else {
                        var u = v
                        while (u < text.length && isUnquotedValueChar(text[u])) u++
                        if (u > v) i = u
                    }
                }
                val name = asciiLower(text.substring(spaced, ne))
                if (name == "src") {
                    if (!srcSeen && found == null && !value.isNullOrEmpty()) found = HtmlImage(start, value, false)
                    srcSeen = true
                } else if (found == null && !srcSeen && !value.isNullOrEmpty() && name.endsWith("src") && name.length >= 4 &&
                    !isWordChar(name[name.length - 4])
                ) {
                    found = HtmlImage(start, value, true)
                }
            }
            if (tagBreaksParagraph(text, start, i, footnotes)) return null
            if (found != null) return found
            p = Js.indexOfCi(text, "<img", i)
        }
        return null
    }

    /** `[^\s"'=<>`]` */
    private fun isUnquotedValueChar(c: Char) = !Js.isSpace(c) && c != '"' && c != '\'' && c != '=' && c != '<' && c != '>' && c != '`'

    /**
     * `tagBreaksParagraph` (2.5.58, #2194): whether a line inside `text[from, to)` ends the paragraph the tag is in (a
     * blank line, or one starting what can begin a block), or any line break when the tag's own line is a block of
     * one line (a heading, a table row) or the body has a footnote. A lone `\r` breaks a line too.
     */
    private fun tagBreaksParagraph(text: String, from: Int, to: Int, footnotes: Boolean): Boolean {
        var multiline = false
        var k = from
        while (k < to) {
            val c = text[k]
            if (c != '\n' && !(c == '\r' && (k + 1 >= text.length || text[k + 1] != '\n'))) {
                k++
                continue
            }
            multiline = true
            var j = k + 1
            while (j < to && (text[j] == ' ' || text[j] == '\t')) j++
            if (j >= to || text[j] == '\n' || text[j] == '\r' || BLOCK_START_CHARS.indexOf(text[j]) >= 0) return true
            k = j
        }
        if (!multiline) return false
        if (footnotes) return true
        var lineStart = from
        while (lineStart > 0 && text[lineStart - 1] != '\n' && text[lineStart - 1] != '\r') lineStart--
        for (i in lineStart until from) if (text[i] == '#' || text[i] == '|' || text[i] == '[') return true
        return false
    }

    private val BLOCK_START_CHARS = "-*_=#>+|<`~:[0123456789"

    /** `labelCloseBefore` (2.5.58, #2180): the `]` of a `](` the URL at [idx] follows as a destination would, or -1. */
    private fun labelCloseBefore(text: String, idx: Int): Int {
        var k = idx - 1
        if (k >= 0 && text[k] == '<') k--
        while (k >= 0 && text[k] == ' ') k--
        if (k >= 0 && text[k] == '\n') {
            k--
            while (k >= 0 && text[k] == ' ') k--
        }
        return if (k >= 1 && text[k] == '(' && text[k - 1] == ']') k - 1 else -1
    }

    private class LabelCloses(val closes: HashSet<Int>, val untrusted: List<IntArray>)

    /**
     * `knownLabelCloses` (2.5.58, #2180): the `]` closing the label of every link or image the scans can read, and the
     * destinations of links the parser may read otherwise, in source order.
     */
    private fun knownLabelCloses(cleaned: String): LabelCloses {
        val closes = HashSet<Int>()
        val untrusted = ArrayList<IntArray>()
        for (m in mdImages(cleaned)) {
            // an escaped `!` makes a link, which the loop below reads
            if (escapedAt(cleaned, m.pos)) continue
            closes.add(m.labelEnd)
        }
        for (m in mdLinks(cleaned)) {
            val open = m.start
            val close = open + 1 + m.label.length
            if (escapedAt(cleaned, open) || escapedAt(cleaned, close)) continue
            if (hasBlankLine(cleaned, open, m.end) || (open > 0 && cleaned[open - 1] == ']') ||
                ((m.href.indexOf('<') >= 0 || m.href.indexOf('>') >= 0) && !isAngleOnly(m.href)) ||
                (!m.href.startsWith("<") && m.href.indexOf('(') >= 0)
            ) {
                untrusted.add(intArrayOf(close + 2, m.end))
                continue
            }
            closes.add(close)
        }
        return LabelCloses(closes, untrusted)
    }

    /** `/\n[ \t]*\n/` in `s[from, to)`, read only there (a line of many links must not rescan the rest per link). */
    private fun hasBlankLine(s: String, from: Int, to: Int): Boolean {
        var nl = from
        while (nl < to) {
            if (s[nl] == '\n') {
                var k = nl + 1
                while (k < to && (s[k] == ' ' || s[k] == '\t')) k++
                if (k < to && s[k] == '\n') return true
                nl = k
            } else {
                nl++
            }
        }
        return false
    }

    /** `markLines(asciiLower(codeBlanked(source)))`, kept for the last source read (one lookup reads one body). */
    private var linesSource: String? = null
    private var linesMasks: LineMasks? = null

    private fun lineMasks(source: String): LineMasks {
        if (linesSource !== source) {
            linesMasks = markLines(asciiLower(codeBlanked(source)))
            linesSource = source
        }
        return linesMasks!!
    }

    /** A code fence as it opened: its marker run and the prefix before it. */
    private class Fence(val run: String, val prefix: String)

    /** Past a line that may or may not have closed a fence: from there on nothing is read as known. */
    private val UNKNOWN_FENCE = Fence("", "")

    /**
     * `nextFenceState` (2.5.58, #2185): the fence state after [line] (null outside a fence). `FENCE_LINE_RE =
     * /^[ \t>*+\-\d.)]*(`{3,}|~{3,})/`. A closer is the opener's character, at least as long, with nothing after it
     * but spaces: behind the opener's own prefix it closes; after an opener at the margin, a closer indented up to
     * three spaces closes and one indented four is code; any other prefix is a line the scans cannot place.
     */
    private fun nextFenceState(state: Fence?, line: String): Fence? {
        if (state === UNKNOWN_FENCE) return state
        var p = 0
        while (p < line.length && isFencePrefixChar(line[p])) p++
        if (p >= line.length || (line[p] != '`' && line[p] != '~')) return state
        val marker = line[p]
        var e = p
        while (e < line.length && line[e] == marker) e++
        if (e - p < 3) return state
        val run = line.substring(p, e)
        val prefix = line.substring(0, p)
        val rest = line.substring(e)
        if (state == null) {
            // a backtick in the info string makes it no fence
            if (rest.indexOf('`') >= 0) return UNKNOWN_FENCE
            return Fence(run, prefix)
        }
        if (run[0] != state.run[0] || run.length < state.run.length || Js.trim(rest).isNotEmpty()) return state
        if (prefix == state.prefix) return null
        if (state.prefix.isEmpty() && prefix.all { it == ' ' }) return if (prefix.length <= 3) null else state
        return UNKNOWN_FENCE
    }

    /** `[ \t>*+\-\d.)]` */
    private fun isFencePrefixChar(c: Char) = c == ' ' || c == '\t' || c == '>' || c == '*' || c == '+' || c == '-' || c in '0'..'9' ||
        c == '.' || c == ')'

    /** `fenceOpen`: whether a code fence may be open at [pos], reading the line [pos] is on whole. */
    private fun fenceOpen(source: String, pos: Int): Boolean {
        val eol = source.indexOf('\n', pos)
        var state: Fence? = null
        for (line in source.substring(0, if (eol == -1) source.length else eol).split('\n')) state = nextFenceState(state, line)
        return state != null
    }

    /** `LOOSE_URL_RE = /https?:\/\/[^\s<>"'`]+/gi`: every match, as (token, position). */
    private fun looseUrls(s: String): Sequence<Candidate> = sequence {
        var p = 0
        while (true) {
            val h = Js.indexOfCi(s, "http", p)
            if (h < 0) break
            val q = Scanners.schemeEnd(s, h)
            if (q < 0) {
                p = h + 1
                continue
            }
            var e = q
            while (e < s.length && isLooseUrlChar(s[e])) e++
            if (e == q) {
                p = h + 1
                continue
            }
            yield(Candidate(s.substring(h, e), h))
            p = e
        }
    }

    private fun isLooseUrlChar(c: Char) = !Js.isSpace(c) && c != '<' && c != '>' && c != '"' && c != '\'' && c != '`'

    private val HIDING_TAGS = arrayOf("iframe", "head", "script", "style", "textarea", "title", "noscript", "template", "object", "svg", "select")

    /** `HIDING_TAG_RE = /<(\/?)(iframe|head|...|select)\b/g` on lowered text: how many such elements are still open. */
    private fun openHidingTags(lower: String): Int {
        var open = 0
        var p = lower.indexOf('<')
        while (p >= 0) {
            val closing = p + 1 < lower.length && lower[p + 1] == '/'
            val n = if (closing) p + 2 else p + 1
            var matchedEnd = -1
            for (tag in HIDING_TAGS) {
                if (lower.startsWith(tag, n) && (n + tag.length >= lower.length || !isWordChar(lower[n + tag.length]))) {
                    matchedEnd = n + tag.length
                    break
                }
            }
            if (matchedEnd >= 0) {
                open += if (closing) -1 else 1
                p = lower.indexOf('<', matchedEnd)
            } else {
                p = lower.indexOf('<', p + 1)
            }
        }
        return open
    }

    /** `/<(\/?)a\b/g` positions: whether anchors nest anywhere in [lower] (the scan text blanks the outer one whole). */
    private fun nestedAnchors(lower: String): Boolean {
        var depth = 0
        var p = lower.indexOf('<')
        while (p >= 0) {
            val closing = p + 1 < lower.length && lower[p + 1] == '/'
            val n = if (closing) p + 2 else p + 1
            if (n < lower.length && lower[n] == 'a' && (n + 1 >= lower.length || !isWordChar(lower[n + 1]))) {
                depth = if (closing) maxOf(0, depth - 1) else depth + 1
                if (depth > 1) return true
                p = lower.indexOf('<', n + 1)
            } else {
                p = lower.indexOf('<', p + 1)
            }
        }
        return false
    }

    /**
     * `unreadBefore` (2.5.58, #2185): whether the body before [pos] holds something the scans cannot read, so the first
     * image is the render's to say: an image URL the scans did not take, an element that hides its content still
     * open, a processing instruction, a code fence, or another `<img` tag.
     */
    private fun unreadBefore(prepared: Prepared, closes: Set<Int>, pos: Int): Boolean {
        val source = prepared.source
        val cleaned = prepared.cleaned
        val scan = prepared.image
        val prefix = cleaned.substring(0, pos)
        val lowerPrefix = asciiLower(prefix)
        if (hasImgTag(lowerPrefix)) return true
        if (fenceOpen(source, pos)) return true
        if (openHidingTags(lowerPrefix) > 0) return true
        if (lowerPrefix.lastIndexOf("<?") > lowerPrefix.lastIndexOf("?>")) return true
        val lastAnchorClose = lowerPrefix.lastIndexOf("</a")
        val nested = nestedAnchors(lowerPrefix)
        var tagMarks: ByteArray? = null
        var tagOpen: IntArray? = null
        for (m in looseUrls(source)) {
            val idx = m.pos
            if (idx >= pos) break
            if (Helper.imageUrlEnd(m.url) == -1) continue
            // inside an anchor the render keeps a link that closes before the image
            if (!Js.startsWithCi(scan.text, "http", idx) && Js.startsWithCi(cleaned, "http", idx) && lastAnchorClose >= idx && !nested) continue
            if (tagMarks == null || tagOpen == null) {
                tagMarks = markInsideTags(prefix)
                tagOpen = IntArray(prefix.length)
                var lt = -1
                var nl = -1
                for (i in prefix.indices) {
                    if (prefix[i] == '<') lt = i
                    if (prefix[i] == '\n') nl = i
                    tagOpen[i] = if (lt > nl) lt else -1
                }
            }
            // an attribute value, in a tag that opens on the same line
            if (tagMarks.marked(idx) && tagOpen[idx] != -1) continue
            val close = labelCloseBefore(cleaned, idx)
            if (close != -1 && close in closes) continue
            return true
        }
        return false
    }

    /** `/<img\b/` on lowered text. */
    private fun hasImgTag(lower: String): Boolean {
        var p = lower.indexOf("<img")
        while (p >= 0) {
            if (p + 4 >= lower.length || !isWordChar(lower[p + 4])) return true
            p = lower.indexOf("<img", p + 1)
        }
        return false
    }

    /**
     * `inTextContext` (2.5.58): whether a paragraph's text before an image puts it in a definition (`]:`) or an open
     * link title (`](url "`), or still inside a destination. One pass.
     */
    private fun inTextContext(text: String): Boolean {
        if (text.contains("]:")) return true
        var inDestination = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == ']' && i + 1 < text.length && text[i + 1] == '(') {
                inDestination = true
                i++
            } else if (inDestination) {
                if (c == ')') {
                    inDestination = false
                } else if (Js.isSpace(c) && i + 1 < text.length && (text[i + 1] == '"' || text[i + 1] == '\'' || text[i + 1] == '(')) {
                    return true
                }
            }
            i++
        }
        return inDestination
    }

    /** `mdImageShown` (2.5.58): whether the render shows the markdown image at [pos] where it stands. */
    private fun mdImageShown(prepared: Prepared, pos: Int): Boolean {
        if (prepared.image.inTag.marked(pos)) return false
        val text = prepared.cleaned
        val blank = text.lastIndexOf("\n\n", pos)
        return !inTextContext(text.substring(if (blank == -1) 0 else blank, pos))
    }

    /**
     * `inlineBacktick` (2.5.58): whether the paragraph `source[from, to)` has a backtick outside code fences, the
     * fences read from the start of the body.
     */
    private fun inlineBacktick(source: String, from: Int, to: Int): Boolean {
        var state: Fence? = null
        var at = 0
        for (line in source.substring(0, to).split('\n')) {
            val before = state
            state = nextFenceState(state, line)
            val lineStart = at
            at += line.length + 1
            if (lineStart < from) continue
            if (before != null || state != null) continue
            if (line.indexOf('`') >= 0) return true
        }
        return false
    }

    /** `URL_SYNTAX_RE = /[`|^]|\+\+|==|~~/` */
    private fun hasUrlSyntax(url: String): Boolean {
        for (i in url.indices) {
            val c = url[i]
            if (c == '`' || c == '|' || c == '^') return true
            if ((c == '+' || c == '=' || c == '~') && i + 1 < url.length && url[i + 1] == c) return true
        }
        return false
    }

    /**
     * `markdownBracketAt` (2.5.58, #2185): whether the `[` at [at] starts markdown the parser reads before linkify, in
     * the shapes proven to: an inline link `[x](url)`, or a reference whose definition stands on its own line after a
     * blank one, outside a code fence.
     */
    private fun markdownBracketAt(source: String, at: Int): Boolean {
        val head = jsSlice(source, at, at + 2200)
        if (inlineLinkHead(head)) return true
        // `/^\[([^[\]\n]{1,100})\](?:\[([^[\]\n]{0,100})\])?/`
        val e1 = plainLabelEnd(head, 1)
        if (e1 < 0 || e1 - 1 < 1) return false
        var label = head.substring(1, e1)
        if (e1 + 1 < head.length && head[e1 + 1] == '[') {
            val e2 = plainLabelEnd(head, e1 + 2)
            if (e2 >= 0 && e2 > e1 + 2) label = head.substring(e1 + 2, e2)
        }
        label = Js.trim(label)
        if (label.isEmpty()) return false
        val def = definitionStart(source, label)
        return def >= 0 && !fenceOpen(source, def)
    }

    /** `[^[\]\n]{0,100}` from [from] then `]`: the index of the `]`, or -1. */
    private fun plainLabelEnd(s: String, from: Int): Int {
        var i = from
        while (i < s.length && i - from < 100 && s[i] != '[' && s[i] != ']' && s[i] != '\n') i++
        return if (i < s.length && s[i] == ']') i else -1
    }

    /** `/^\[(?:[^[\]\n]|\[[^[\]\n]{0,100}\]){0,100}\]\([^\s()<>]*\)/` */
    private fun inlineLinkHead(head: String): Boolean {
        if (head.isEmpty() || head[0] != '[') return false
        var i = 1
        var units = 0
        while (i < head.length && units < 100) {
            val c = head[i]
            if (c == '[') {
                val e = plainLabelEnd(head, i + 1)
                if (e < 0) break
                i = e + 1
            } else if (c == ']' || c == '\n') {
                break
            } else {
                i++
            }
            units++
        }
        if (!head.startsWith("](", i)) return false
        var k = i + 2
        while (k < head.length && !Js.isSpace(head[k]) && head[k] != '(' && head[k] != ')' && head[k] != '<' && head[k] != '>') k++
        return k < head.length && head[k] == ')'
    }

    /**
     * `(?:^|\n[ \t]*\n)( {0,3}\[LABEL\]:[ \t]*\S+[ \t]*)(?:\n|$)` with the `i` flag on [source], first match: its start
     * (the `\n` that begins it, or 0), or -1. The label compares as JS case folding without `u` does.
     */
    private fun definitionStart(source: String, label: String): Int {
        if (definitionAt(source, 0, label)) return 0
        var nl = source.indexOf('\n')
        while (nl >= 0) {
            var j = nl + 1
            while (j < source.length && (source[j] == ' ' || source[j] == '\t')) j++
            if (j < source.length && source[j] == '\n' && definitionAt(source, j + 1, label)) return nl
            nl = source.indexOf('\n', nl + 1)
        }
        return -1
    }

    private fun definitionAt(s: String, from: Int, label: String): Boolean {
        var i = from
        while (i < s.length && i - from < 3 && s[i] == ' ') i++
        if (i >= s.length || s[i] != '[') return false
        i++
        if (i + label.length > s.length) return false
        for (k in label.indices) if (jsCaseFold(s[i + k]) != jsCaseFold(label[k])) return false
        i += label.length
        if (!s.startsWith("]:", i)) return false
        i += 2
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        val run = i
        while (i < s.length && !Js.isSpace(s[i])) i++
        if (i == run) return false
        while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
        return i >= s.length || s[i] == '\n'
    }

    /**
     * JS `Canonicalize` without `u`, from the JS-generated table: a character whose full upper case is longer than one
     * (`ᾀ`) stays itself, unlike Kotlin's simple mapping.
     */
    private fun jsCaseFold(c: Char): Char = com.ecency.core.render.markdown.UrlScanner.canon[c.code]

    private fun rawTagsOf(prepared: Prepared): ByteArray =
        prepared.rawTags ?: markInsideTags(prepared.cleaned).also { prepared.rawTags = it }

    /** `/&amp;(?:[a-z][a-z\d]*|#\d+|#x[\da-f]+);/i`: a reference written twice. */
    private fun hasDoubledReference(url: String): Boolean {
        var p = Js.indexOfCi(url, "&amp;", 0)
        while (p >= 0) {
            var k = p + 5
            if (k < url.length && Js.isAsciiAlpha(url[k])) {
                k++
                while (k < url.length && (Js.isAsciiAlpha(url[k]) || Js.isDigit(url[k]))) k++
                if (k < url.length && url[k] == ';') return true
            } else if (k < url.length && url[k] == '#') {
                k++
                val d = k
                while (k < url.length && Js.isDigit(url[k])) k++
                if (k > d && k < url.length && url[k] == ';') return true
                k = d
                if (k < url.length && (url[k] == 'x' || url[k] == 'X')) {
                    k++
                    val h = k
                    while (k < url.length && isHexDigit(url[k])) k++
                    if (k > h && k < url.length && url[k] == ';') return true
                }
            }
            p = Js.indexOfCi(url, "&amp;", p + 1)
        }
        return false
    }

    /** `/^<(\/?)([a-z][a-z\d]*)/i` on `s.slice(at, at + 40)` with the slash given: the lowered name, or null. */
    private fun tagNameAt(s: String, at: Int, closing: Boolean): String? {
        val end = minOf(s.length, at + 40)
        var i = at
        if (i >= end || s[i] != '<') return null
        i++
        if (closing) {
            if (i >= end || s[i] != '/') return null
            i++
        }
        if (i >= end || !Js.isAsciiAlpha(s[i])) return null
        val n = i
        while (i < end && (Js.isAsciiAlpha(s[i]) || Js.isDigit(s[i]))) i++
        return asciiLower(s.substring(n, i))
    }

    /** `/^<a\b/i` at [at]. */
    private fun anchorOpenAt(s: String, at: Int): Boolean =
        at + 1 < s.length && s[at] == '<' && (s[at + 1] == 'a' || s[at + 1] == 'A') && (at + 2 >= s.length || !isWordChar(s[at + 2]))

    /**
     * `bareShown` (2.5.58, #2185): whether the render shows a bare URL or promoted link at the candidate's position as
     * an image rather than as text in a construct the scans cannot see (a link's text or title, a definition or
     * footnote, code, an attribute, an escape, an autolink in an HTML block, an anchor read its own way). Read on its
     * paragraph as written.
     */
    private fun bareShown(prepared: Prepared, candidate: Candidate): Boolean {
        val source = prepared.source
        val cleaned = prepared.cleaned
        val pos = candidate.pos
        val url = candidate.url
        if (prepared.image.inTag.marked(pos) || rawTagsOf(prepared).marked(pos)) return false
        var slashes = 0
        while (pos - 1 - slashes >= 0 && source[pos - 1 - slashes] == '\\') slashes++
        if (slashes % 2 == 1) return false
        val blank = source.lastIndexOf("\n\n", pos)
        val paraStart = if (blank == -1) 0 else blank + 2
        val para = source.substring(paraStart, pos)
        if (inTextContext(para)) return false
        // an open `[`: link text; a `]` in an attribute closes nothing
        var depth = 0
        var i = 0
        while (i < para.length) {
            val c = para[i]
            if (c == '\\') {
                i++
            } else if (c == '[') {
                depth++
            } else if (c == ']' && !prepared.image.inTag.marked(paraStart + i)) {
                depth = maxOf(0, depth - 1)
            }
            i++
        }
        if (depth > 0) return false
        if (inlineBacktick(source, paraStart, pos) || hasUrlSyntax(url)) return false
        // an autolink in an HTML block is text to the parser
        if (pos > 0 && source[pos - 1] == '<' && lineMasks(source).block[pos]) return false
        // an anchor around it opened across lines, or nested, is read its own way
        val lowerPara = asciiLower(cleaned.substring(paraStart, pos))
        if (nestedAnchors(lowerPara)) return false
        val lt = lowerPara.lastIndexOf("<a")
        if (lt != -1 && anchorOpenAt(lowerPara.substring(lt, minOf(lowerPara.length, lt + 3)), 0) && lowerPara.indexOf('\n', lt) >= 0) return false
        val nextAt = pos + url.length
        val next = if (nextAt < source.length) source[nextAt] else null
        if (next == '\'') return false
        // literal brackets the linkify takes into the URL, unlike a link or defined reference the parser takes first
        if (next == '[' && !markdownBracketAt(source, nextAt)) return false
        // a closing tag right after it that does not close the tag right before it
        val closing = tagNameAt(source, nextAt, true)
        if (closing != null) {
            val ltc = cleaned.lastIndexOf('<', pos)
            val opening = if (ltc == -1) null else tagNameAt(cleaned, ltc, false)
            if (opening == null || opening != closing) return false
        }
        if (next != null && (next == '*' || next == '_' || next == '~') && para.indexOf(next) < 0) return false
        if (url.endsWith("\\")) return false
        // a reference written twice, or one without its `;` in an anchor's text
        if (hasDoubledReference(url)) return false
        val tag = cleaned.lastIndexOf('<', pos)
        if (tag != -1 && anchorOpenAt(cleaned.substring(tag, minOf(cleaned.length, tag + 3)), 0) &&
            decodeHtmlText(url) != Helper.decodeCompleteReferences(url)
        ) {
            return false
        }
        return true
    }

    /**
     * `posterShown` (2.5.58, #2185): whether the render shows the video link at [pos] as a poster, read in the contexts
     * a bare image URL is. A link the checks cannot place is uncertain: the caller defers.
     */
    private fun posterShown(prepared: Prepared, pos: Int): Boolean {
        val source = prepared.source
        val cleaned = prepared.cleaned
        // `/^https?:\/\/[^\s<>"'`]+/i` on `source.slice(pos, pos + 2100)`
        val limit = minOf(source.length, pos + 2100)
        val q = Scanners.schemeEnd(source, pos)
        var e = q
        if (q >= 0) while (e < limit && isLooseUrlChar(source[e])) e++
        val token = if (q >= 0 && q <= limit && e > q) source.substring(pos, e) else null
        val candidate = if (token != null) Candidate(token, pos) else Candidate("", pos, Candidate.DESTINATION)
        // a destination (`[]( URL )`) is a link, never a poster
        if (token != null && labelCloseBefore(cleaned, pos) != -1) return false
        // in an HTML block with an image later on its line, the DOM may reorder them
        var lineEnd = source.indexOf('\n', pos)
        if (lineEnd == -1) lineEnd = source.length
        if (lineMasks(source).block[pos] && imageOnLine(source, pos, lineEnd)) return false
        return bareShown(prepared, candidate) && !unreadBefore(prepared, knownLabelCloses(cleaned).closes, pos)
    }

    /** `/!\[|<img\b/i` on `source.slice(pos, lineEnd)`. */
    private fun imageOnLine(source: String, pos: Int, lineEnd: Int): Boolean {
        val line = source.substring(pos, lineEnd)
        return line.contains("![") || hasImgTag(asciiLower(line))
    }

    /** A `MD_LINK_RE` match: where it starts and ends (`m[0]`), its label (`m[1]`) and its href (`m[2]`). */
    private class MdLink(val start: Int, val label: String, val href: String, val end: Int)

    /** `MD_LINK_RE = /\[([^[\]]*)\]\(\s*([^)\s[]+)(?:\s+["'][^"']*["'])?\s*\)/g` */
    private fun mdLinks(s: String): Sequence<MdLink> = sequence {
        var p = s.indexOf('[')
        while (p >= 0) {
            val le = labelEnd(s, p)
            if (le >= 0 && le + 1 < s.length && s[le + 1] == '(') {
                val t = parenTail(s, le + 1, { c -> c != ')' && c != '[' && !isWs(c) }, Int.MAX_VALUE)
                if (t != null) {
                    yield(MdLink(p, s.substring(p + 1, le), s.substring(t[0], t[1]), t[2]))
                    p = s.indexOf('[', t[2])
                    continue
                }
            }
            p = s.indexOf('[', p + 1)
        }
    }

    private fun isWordChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    private companion object {
        const val BROKEN = -2

        /** [markInsideTags]: a character inside a tag, and the `<` that opens one. */
        const val IN_TAG: Byte = 1
        const val TAG_START: Byte = 2
    }
}
