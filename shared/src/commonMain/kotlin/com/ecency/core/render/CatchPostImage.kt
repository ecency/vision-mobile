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

    /**
     * `proxifyFound` (2.5.55, #2169): percent-decoded as the render decodes its `<img>` src before proxying, so the
     * card names the image the body shows. A legacy passthrough (not a `/p/` hash on the proxy) keeps it as written.
     */
    private fun proxifyFound(src: String, w: Int, h: Int): String {
        val entityDecoded = Helper.decodeEntities(src)
        val proxied = proxy.proxify(Helper.decodeDomImageSrc(entityDecoded), w, h)
        return if (proxied.startsWith("${proxy.base}/p/")) proxied else proxy.proxify(entityDecoded, w, h)
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
        findFirstImageUrl(entry.body, false)?.let { return proxifyFound(it, w, h) }
        return fromRender(entry.body, w, h)
    }

    private fun fromRender(body: String, w: Int, h: Int): String? {
        val html = helper.renderPostBody(body, true)
        val doc = Helper.createDoc(html) ?: return null
        // 2.5.58 (#2182): the render leaves an image the proxy refuses without a src, so the first image shown is the
        // first one that has a src
        val src = doc.getElementsByTagName("img").firstNotNullOfOrNull { img -> img.getAttribute("src")?.takeIf { it.isNotEmpty() } }
            ?: return null
        return proxifyFound(src, w, h)
    }

    fun forEntry(entry: Entry, w: Int, h: Int, fast: Boolean): String? = getImage(entry, w, h, fast)

    fun forBody(body: String, w: Int, h: Int, fast: Boolean): String? {
        if (fast) return fastBodyImage(body, w, h)
        findFirstImageUrl(body, false)?.let { return proxifyFound(it, w, h) }
        return fromRender(body, w, h)
    }

    /**
     * `bodyImageRawUrl` (2.5.52): the first image candidate, decoded the way the render reads that kind of candidate.
     * An `<img src>` is an attribute value; a markdown destination keeps its escapes' meaning and is refused with a
     * control character; a bare URL on an HTML block line is HTML text; any other bare URL loses trailing sentence
     * punctuation first.
     */
    fun bodyImageRawUrl(raw: String): String? {
        val body = parserInput(raw)
        val candidate = findFirstImageCandidate(prepareBody(body), true).candidate ?: return null
        if (candidate.kind == Candidate.HTML) return Helper.decodeDomImageSrc(Helper.decodeAttributeEntities(candidate.url))
        val ampEscapes = !Helper.hasAmpSentinel(body)
        if (candidate.kind == Candidate.DESTINATION) {
            val url = Helper.decodeRawBodyImageSrc(candidate.url, true, ampEscapes)
            return if (url.any { it < ' ' || it == '\u007F' }) null else url
        }
        if (markLines(asciiLower(codeBlanked(body))).block[candidate.pos]) return htmlTextImageUrl(body, candidate.pos)
        val afterAt = candidate.pos + candidate.url.length
        val after = jsSlice(body, afterAt, afterAt + 4)
        // A lone surrogate reads as U+FFFD in the render (2.5.55, #2158), which ends the URL there.
        val loneAt = loneSurrogateAt(candidate.url)
        val token = if (loneAt == -1) candidate.url else candidate.url.substring(0, loneAt)
        fun tokenUrl(): String? {
            val url = withoutTrailingPunctuation(token, if (loneAt == -1) after else "")
            return if (Helper.imageUrlEnd(url) == -1) null else Helper.decodeRawBodyImageSrc(url, false, ampEscapes)
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
        return if (Helper.imageUrlEnd(url) == -1) null else Helper.decodeDomImageSrc(url)
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
        return if (urlEnd == -1) null else Helper.decodeDomImageSrc(token.substring(0, urlEnd))
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

    private class ScanText(val text: String, val inTag: BooleanArray)

    private class Prepared(val cleaned: String, val image: ScanText, val video: ScanText)

    private class Candidate(val url: String, val pos: Int, val kind: Int = BARE) {
        companion object {
            const val BARE = 0
            const val HTML = 1
            const val DESTINATION = 2
        }
    }

    private class CandidateResult(val candidate: Candidate?, val ambiguous: Boolean)

    private fun findFirstImageUrl(body: String, includeBare: Boolean): String? =
        findFirstImageCandidate(prepareBody(parserInput(body)), includeBare).candidate?.url

    private fun fastBodyImage(raw: String, w: Int, h: Int): String? {
        val prepared = prepareBody(parserInput(raw))
        val strict = findFirstImageCandidate(prepared, false)
        strict.candidate?.let { return proxifyFound(it.url, w, h) }
        if (strict.ambiguous) return null
        val bare = findFirstImageCandidate(prepared, true).candidate
        val poster = findFirstVideoPoster(prepared)
        // 2.5.57 (#2179): the render ends a bare URL at a lone surrogate (#2158), which the full lookup reads its own
        // way: give up rather than name another URL, unless a poster comes first and wins on source order anyway.
        if (bare != null && bare.kind == Candidate.BARE && loneSurrogateAt(bare.url) != -1 && !(poster != null && poster.pos < bare.pos)) {
            return null
        }
        if (poster != null && (bare == null || poster.pos < bare.pos)) {
            return proxifyFound(proxy.proxify(poster.url), w, h)
        }
        return bare?.let { proxifyFound(it.url, w, h) }
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
        if (cleaned.isEmpty()) return Prepared(cleaned, ScanText("", BooleanArray(0)), ScanText("", BooleanArray(0)))
        val imageText = blankUnequalAnchors(cleaned, false)
        val videoText = blankUnequalAnchors(cleaned, true)
        return Prepared(cleaned, ScanText(imageText, markInsideTags(imageText)), ScanText(videoText, markInsideTags(videoText)))
    }

    private fun isSafeUrl(s: String) = Js.startsWithCi(s, "http://") || Js.startsWithCi(s, "https://")

    private fun findFirstImageCandidate(prepared: Prepared, includeBare: Boolean): CandidateResult {
        val cleaned = prepared.cleaned
        if (cleaned.isEmpty()) return CandidateResult(null, false)
        val md = mdImageFind(cleaned)
        val html = htmlImageFind(cleaned)
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
                return CandidateResult(null, true)
            }
        }
        val prior = if (md != null) cleaned.substring(0, md.pos) else cleaned
        if (mdImagePresent(prior)) return CandidateResult(null, true)

        val candidates = ArrayList<Candidate>()
        if (md != null && !mdUrl.isNullOrEmpty()) candidates.add(Candidate(mdUrl, md.pos, Candidate.DESTINATION))
        if (html != null && html.url.isNotEmpty() && isSafeUrl(html.url)) candidates.add(html)
        if (includeBare) {
            val bare = standaloneMatches(prepared.image, ::imageToken).firstOrNull()
            if (bare != null && isSafeUrl(bare.url)) candidates.add(bare)
            for (m in mdLinks(cleaned)) {
                if (m.start > 0 && cleaned[m.start - 1] == '!') continue
                val href = m.href
                if (href.isNotEmpty() && isImageHref(href) && deAmp(m.label) == deAmp(href)) {
                    candidates.add(Candidate(href, m.start, Candidate.DESTINATION))
                    break
                }
            }
        }
        if (candidates.isEmpty()) return CandidateResult(null, false)
        return CandidateResult(candidates.sortedBy { it.pos }[0], false) // sortedBy is stable, like Array.sort
    }

    private fun deAmp(s: String) = Js.trim(s).replace("&amp;", "&")

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
        return Candidate("https://img.youtube.com/vi/${b.url.substringBefore('?')}/hqdefault.jpg", b.pos)
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

    private fun markInsideTags(text: String): BooleanArray {
        val marks = BooleanArray(text.length)
        var inTag = false
        var quote = '\u0000'
        for (i in text.indices) {
            val c = text[i]
            if (!inTag) {
                if (c == '<' && i + 1 < text.length) {
                    val n = text[i + 1]
                    if ((n in 'a'..'z' || n in 'A'..'Z' || n == '/' || n == '!' || n == '?') && !isAutolinkAt(text, i + 1)) {
                        inTag = true
                        marks[i] = true
                    }
                }
                continue
            }
            marks[i] = true
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

    private fun isStandalone(scan: ScanText, idx: Int): Boolean {
        if (scan.inTag[idx]) return false
        if (idx == 0) return true
        val prev = scan.text[idx - 1]
        if (prev in 'a'..'z' || prev in 'A'..'Z' || prev in '0'..'9' || prev == '_' || prev in "/.:%?&=#[-") return false
        if (prev == '(' && idx > 1 && scan.text[idx - 2] == ']') return false
        return true
    }

    private fun isUrlTokenChar(c: Char) = !Js.isSpace(c) && c != '<' && c != '>' && c != '"' && c != '\'' &&
        c != '(' && c != ')' && c != '[' && c != ']'

    /** `URL_TOKEN_RE = /https?:\/\/(?:[^\s<>"'()[\]&]|&(?!lt;|gt;))+/gi` (2.5.51), standalone tokens only, classified. */
    private fun standaloneMatches(scan: ScanText, classify: (String) -> String?): Sequence<Candidate> = sequence {
        val s = scan.text
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
            if (isStandalone(scan, h)) {
                val v = classify(s.substring(h, e))
                if (v != null) yield(Candidate(v, h))
            }
            p = e
        }
    }

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
    private class MdImage(val raw: String, val pos: Int, val end: Int, val angle: Boolean)

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
        fun tail(from: Int): Int = tails.getOrPut(from) { mdTailEnd(s, from) }
        val urls = SchemeScan(s)
        var p = s.indexOf("![")
        while (p >= 0) {
            val le = mdLabelEnd(s, p + 2)
            if (le >= 0 && le + 1 < s.length && s[le + 1] == '(') {
                val i = skipSeparator(s, le + 2)
                var found: MdImage? = null
                val close = angleContentEnd(s, i)
                if (close >= 0) {
                    val e = tail(close + 1)
                    if (e >= 0) found = MdImage(s.substring(i + 1, close), p, e, true)
                }
                if (found == null) {
                    val he = plainDestinationEnd(s, i)
                    if (he > i) {
                        val e = tail(he)
                        if (e >= 0) found = MdImage(s.substring(i, he), p, e, false)
                    }
                }
                // firstUnescapedImage (2.5.57, #2179): an escaped `\![...](url)` is a link, and the scan resumes past its
                // `![`, unless the text before the match's last `](` holds a URL (the caller defers that one).
                if (found != null && (!escapedAt(s, p) || urls.holdsUrl(p, s.lastIndexOf("](", found.end - 2)))) return found
            }
            p = s.indexOf("![", p + 1)
        }
        return null
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
     * `firstUnescapedImage(s, MD_IMAGE_PRESENT_RE)` (2.5.57, #2179), `MD_IMAGE_PRESENT_RE = /!\[[^[\]]*(?:\]\(\s*[^\s)]|\[)/g`:
     * an image the pattern may not have captured, a label opening a nested `[` included. An escaped `\![` is a link
     * and does not count, unless its label holds a URL (the render may turn that link into an image).
     */
    private fun mdImagePresent(s: String): Boolean {
        var p = s.indexOf("![")
        while (p >= 0) {
            var i = p + 2
            while (i < s.length && s[i] != '[' && s[i] != ']') i++
            var matched = i < s.length && s[i] == '['
            if (!matched && i + 1 < s.length && s[i + 1] == '(') {
                var k = i + 2
                while (k < s.length && isWs(s[k])) k++
                matched = k < s.length && s[k] != ')'
            }
            // the match text before its `](` (or all of it) is the label, scanned once per start
            if (matched && (!escapedAt(s, p) || s.substring(p, i).contains("://"))) return true
            p = s.indexOf("![", p + 1)
        }
        return false
    }

    private class MdLink(val start: Int, val label: String, val href: String)

    /** `MD_LINK_RE = /\[([^[\]]*)\]\(\s*([^)\s[]+)(?:\s+["'][^"']*["'])?\s*\)/g` */
    private fun mdLinks(s: String): Sequence<MdLink> = sequence {
        var p = s.indexOf('[')
        while (p >= 0) {
            val le = labelEnd(s, p)
            if (le >= 0 && le + 1 < s.length && s[le + 1] == '(') {
                val t = parenTail(s, le + 1, { c -> c != ')' && c != '[' && !isWs(c) }, Int.MAX_VALUE)
                if (t != null) {
                    yield(MdLink(p, s.substring(p + 1, le), s.substring(t[0], t[1])))
                    p = s.indexOf('[', t[2])
                    continue
                }
            }
            p = s.indexOf('[', p + 1)
        }
    }

    /** `HTML_IMAGE_RE = /<img\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']/i`, first match. */
    private fun htmlImageFind(s: String): Candidate? {
        var p = Js.indexOfCi(s, "<img", 0)
        while (p >= 0) {
            val a = p + 4
            if (a >= s.length || !isWordChar(s[a])) {
                var q = a
                while (true) {
                    if (q < s.length && Js.startsWithCi(s, "src", q) && !isWordChar(s[q - 1])) {
                        var i = q + 3
                        while (i < s.length && isWs(s[i])) i++
                        if (i < s.length && s[i] == '=') {
                            i++
                            while (i < s.length && isWs(s[i])) i++
                            if (i < s.length && (s[i] == '"' || s[i] == '\'')) {
                                var e = i + 1
                                while (e < s.length && s[e] != '"' && s[e] != '\'') e++
                                if (e > i + 1 && e < s.length) return Candidate(s.substring(i + 1, e), p, Candidate.HTML)
                            }
                        }
                    }
                    if (q >= s.length || s[q] == '>') break
                    q++
                }
            }
            p = Js.indexOfCi(s, "<img", p + 1)
        }
        return null
    }

    private fun isWordChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    private companion object {
        const val BROKEN = -2
    }
}
