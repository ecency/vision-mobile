package com.ecency.core.render

import com.ecency.core.render.js.Js
import com.ecency.core.render.methods.Helper
import com.ecency.core.render.methods.Scanners
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

    private fun proxifyFound(src: String, w: Int, h: Int): String = proxy.proxify(Helper.decodeEntities(src), w, h)

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
        val imgs = doc.getElementsByTagName("img")
        if (imgs.isEmpty()) return null
        val src = imgs[0].getAttribute("src")
        if (src.isNullOrEmpty()) return null
        return proxifyFound(src, w, h)
    }

    fun forEntry(entry: Entry, w: Int, h: Int, fast: Boolean): String? = getImage(entry, w, h, fast)

    fun forBody(body: String, w: Int, h: Int, fast: Boolean): String? {
        if (fast) return fastBodyImage(body, w, h)
        findFirstImageUrl(body, false)?.let { return proxifyFound(it, w, h) }
        return fromRender(body, w, h)
    }

    fun bodyImageRawUrl(body: String): String? = findFirstImageUrl(body, true)?.let { Helper.decodeImageSrc(it) }

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

    private class Candidate(val url: String, val pos: Int)

    private class CandidateResult(val candidate: Candidate?, val ambiguous: Boolean)

    private fun findFirstImageUrl(body: String, includeBare: Boolean): String? =
        findFirstImageCandidate(prepareBody(body), includeBare).candidate?.url

    private fun fastBodyImage(body: String, w: Int, h: Int): String? {
        val prepared = prepareBody(body)
        val strict = findFirstImageCandidate(prepared, false)
        strict.candidate?.let { return proxifyFound(it.url, w, h) }
        if (strict.ambiguous) return null
        val bare = findFirstImageCandidate(prepared, true).candidate
        val poster = findFirstVideoPoster(prepared)
        if (poster != null && (bare == null || poster.pos < bare.pos)) {
            return proxifyFound(proxy.proxify(poster.url), w, h)
        }
        return bare?.let { proxifyFound(it.url, w, h) }
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
        if (md != null) {
            val url = md.url
            if (url.isEmpty() || !isSafeUrl(url) || url.contains('(')) return CandidateResult(null, true)
        }
        val prior = if (md != null) cleaned.substring(0, md.pos) else cleaned
        if (mdImagePresent(prior)) return CandidateResult(null, true)

        val candidates = ArrayList<Candidate>()
        if (md != null) candidates.add(md)
        if (html != null && html.url.isNotEmpty() && isSafeUrl(html.url)) candidates.add(html)
        if (includeBare) {
            val bare = standaloneMatches(prepared.image, ::imageToken).firstOrNull()
            if (bare != null && isSafeUrl(bare.url)) candidates.add(bare)
            for (m in mdLinks(cleaned)) {
                if (m.start > 0 && cleaned[m.start - 1] == '!') continue
                val href = m.href
                if (href.isNotEmpty() && isImageHref(href) && deAmp(m.label) == deAmp(href)) {
                    candidates.add(Candidate(href, m.start))
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
            if (extLen(href, d) > 0) return true
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

    /** `URL_TOKEN_RE = /https?:\/\/[^\s<>"'()[\]]+/gi`, standalone tokens only, classified. */
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
            while (e < s.length && isUrlTokenChar(s[e])) e++
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

    private fun imageToken(token: String): String? {
        var end = -1
        var i = 0
        while (i < token.length) {
            val l = extLen(token, i)
            if (l > 0) {
                end = i + l
                i += l
            } else {
                i++
            }
        }
        if (end == -1) return null
        if (end < token.length && (token[end] == '?' || token[end] == '#')) end = token.length
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

    /** `MD_IMAGE_RE = /!\[[^[\]]*\]\(\s*([^)\s]{1,2048})(?:\s+["'][^"']*["'])?\s*\)/`, first match. */
    private fun mdImageFind(s: String): Candidate? {
        var p = s.indexOf("![")
        while (p >= 0) {
            val le = labelEnd(s, p + 1)
            if (le >= 0 && le + 1 < s.length && s[le + 1] == '(') {
                val t = parenTail(s, le + 1, { c -> c != ')' && !isWs(c) }, 2048)
                if (t != null) return Candidate(s.substring(t[0], t[1]), p)
            }
            p = s.indexOf("![", p + 1)
        }
        return null
    }

    /** `MD_IMAGE_PRESENT_RE = /!\[[^[\]]*\]\(\s*[^\s)]/` */
    private fun mdImagePresent(s: String): Boolean {
        var p = s.indexOf("![")
        while (p >= 0) {
            val le = labelEnd(s, p + 1)
            if (le >= 0 && le + 1 < s.length && s[le + 1] == '(') {
                var i = le + 2
                while (i < s.length && isWs(s[i])) i++
                if (i < s.length && s[i] != ')') return true
            }
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
                                if (e > i + 1 && e < s.length) return Candidate(s.substring(i + 1, e), p)
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
