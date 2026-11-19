package com.ecency.core.render.dom

import com.ecency.core.render.js.Js
import com.ecency.core.render.sanitize.RenderText
/**
 * String passes render-helper runs on the markdown output before parsing it
 * (`helper.ts` and `fixBlockLevelTagsInParagraphs` in `markdown-to-html.method.ts`).
 * All are single linear scans.
 */
object HtmlStringFixes {
    private fun isSpaceChar(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'
    private fun isTagNameChar(c: Char) = Js.isAsciiAlpha(c) || Js.isDigit(c)
    private fun isAttrNameChar(c: Char) = Js.isAsciiAlpha(c) || Js.isDigit(c) || c == '-' || c == '_' || c == ':' || c == '.'

    /**
     * `removeDuplicateAttributes`: keeps the first of each attribute name (case-insensitive) in
     * every well-formed start tag, normalising the separators to single spaces.
     */
    fun removeDuplicateAttributes(html: String): String {
        val n = html.length
        val out = StringBuilder(n)
        // A start tag needs a '>' after it; past the last one nothing can match (keeps runs of
        // unterminated tags linear).
        val lastGt = html.lastIndexOf('>')
        // 2.5.45: characters scanned by tags that never closed. A '>' only ever inside a quoted value defeats the
        // guard above; past this budget the rest is copied as written, so the work stays linear.
        val scanBudget = 4L * n + 4096
        var wasted = 0L
        var i = 0
        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0 || lt > lastGt) {
                out.append(html, i, n)
                break
            }
            out.append(html, i, lt)
            if (lt + 1 >= n || !Js.isAsciiAlpha(html[lt + 1])) {
                out.append('<')
                i = lt + 1
                continue
            }
            var p = lt + 1
            while (p < n && isTagNameChar(html[p])) p++
            val tagName = html.substring(lt + 1, p)
            if (p >= n || !isSpaceChar(html[p])) {
                out.append('<')
                i = lt + 1
                continue
            }
            val attrs = ArrayList<String>()
            val seen = HashSet<String>()
            var q = p
            while (q < n) {
                while (q < n && isSpaceChar(html[q])) q++
                if (q >= n) break
                val ch = html[q]
                if (ch == '>') break
                if (ch == '/' && q + 1 < n && html[q + 1] == '>') break
                val nameStart = q
                while (q < n && isAttrNameChar(html[q])) q++
                if (q == nameStart) {
                    q++
                    continue
                }
                val attrName = html.substring(nameStart, q)
                var r = q
                while (r < n && isSpaceChar(html[r])) r++
                var valueEnd = q
                // the value without its quotes when complete; null keeps the attribute's bytes as written
                var rawValue: String? = null
                if (r < n && html[r] == '=') {
                    r++
                    while (r < n && isSpaceChar(html[r])) r++
                    if (r < n) {
                        val v = html[r]
                        if (v == '"' || v == '\'') {
                            val end = html.indexOf(v, r + 1)
                            valueEnd = if (end < 0) {
                                val gt = html.indexOf('>', r + 1)
                                if (gt < 0) n else gt
                            } else {
                                rawValue = html.substring(r + 1, end)
                                end + 1
                            }
                        } else {
                            var s = r
                            while (s < n) {
                                val k = html[s]
                                if (isSpaceChar(k) || k == '>') break
                                s++
                            }
                            valueEnd = s
                            rawValue = html.substring(r, s)
                        }
                    } else {
                        valueEnd = r
                    }
                }
                val fullAttr = html.substring(nameStart, valueEnd)
                q = valueEnd
                // JS toLowerCase on [A-Za-z0-9-_:.] is plain ASCII lower-casing.
                val key = attrName.lowercase()
                if (seen.add(key)) {
                    // 2.5.45 (#1919, #2085): names lower-cased on the way out; a value is handed on decoded as a
                    // browser decodes an attribute and escaped again, unless that leaves it as written. A literal
                    // tab, line feed or carriage return is attribute whitespace: a space.
                    val value = rawValue?.let { escapeAttrValue(RenderText.decodeAttributeEntities(attrWhitespace(it))) }
                    attrs.add(if (value == null || value == rawValue) key + fullAttr.substring(attrName.length) else "$key=\"$value\"")
                }
            }
            var selfClose = false
            if (q < n && html[q] == '/') {
                selfClose = true
                q++
            }
            if (q >= n || html[q] != '>') {
                wasted += q - lt
                if (wasted > scanBudget) {
                    out.append(html, lt, n)
                    break
                }
                out.append('<')
                i = lt + 1
                continue
            }
            q++
            out.append('<').append(tagName)
            if (attrs.isNotEmpty()) {
                out.append(' ')
                attrs.forEachIndexed { k, a ->
                    if (k > 0) out.append(' ')
                    out.append(a)
                }
            }
            if (selfClose) out.append(" /")
            out.append('>')
            i = q
        }
        return out.toString()
    }

    /** `\r\n?|[\n\t]` to a space (helper `removeDuplicateAttributes`). */
    private fun attrWhitespace(v: String): String {
        if (v.indexOf('\r') < 0 && v.indexOf('\n') < 0 && v.indexOf('\t') < 0) return v
        val sb = StringBuilder(v.length)
        var i = 0
        while (i < v.length) {
            val c = v[i]
            when {
                c == '\r' -> {
                    sb.append(' ')
                    if (i + 1 < v.length && v[i + 1] == '\n') i++
                }
                c == '\n' || c == '\t' -> sb.append(' ')
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** helper `escapeAttrValue`: for a double-quoted attribute; tab, LF and CR stay references, as xmldom keeps them. */
    private fun escapeAttrValue(v: String): String {
        val sb = StringBuilder(v.length + 8)
        for (c in v) when (c) {
            '&' -> sb.append("&amp;")
            '"' -> sb.append("&quot;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '\t' -> sb.append("&#9;")
            '\n' -> sb.append("&#10;")
            '\r' -> sb.append("&#13;")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    private val BLOCK_TAGS = listOf("center", "div", "table", "figure", "section", "article", "aside", "header", "footer", "nav", "main")
    private val BLOCK_TAG_SET = BLOCK_TAGS.toHashSet()

    /** Name of the block tag at [at] (case-insensitive), or null. */
    private fun blockTagAt(s: String, at: Int): String? {
        for (t in BLOCK_TAGS) if (Js.regionMatchesLower(s, at, t)) return t
        return null
    }

    /** `<(?:tags)(?:\s[^>]*)?>` at [at]; returns the end index or -1. */
    private fun blockOpenTag(s: String, at: Int): Int {
        if (at >= s.length || s[at] != '<') return -1
        val t = blockTagAt(s, at + 1) ?: return -1
        var p = at + 1 + t.length
        if (p >= s.length) return -1
        if (s[p] == '>') return p + 1
        if (!Js.isSpace(s[p])) return -1
        val gt = s.indexOf('>', p + 1)
        return if (gt < 0) -1 else gt + 1
    }

    /** `<\/(?:tags)>` at [at]; returns the end index or -1. */
    private fun blockCloseTag(s: String, at: Int): Int {
        if (!s.startsWith("</", at)) return -1
        val t = blockTagAt(s, at + 2) ?: return -1
        val p = at + 2 + t.length
        return if (p < s.length && s[p] == '>') p + 1 else -1
    }

    /** Global replace driven by a matcher that returns (end, replacement) or null at an index. */
    private inline fun replaceAt(s: String, anchor: String, ignoreCase: Boolean, match: (Int) -> Pair<Int, String>?): String {
        val sb = StringBuilder(s.length)
        var last = 0
        var i = 0
        var changed = false
        while (true) {
            i = if (ignoreCase) indexOfIgnoreCase(s, anchor, i) else s.indexOf(anchor, i)
            if (i < 0) break
            val m = match(i)
            if (m == null) {
                i++
                continue
            }
            sb.append(s, last, i).append(m.second)
            last = m.first
            i = m.first
            changed = true
        }
        if (!changed) return s
        sb.append(s, last, s.length)
        return sb.toString()
    }

    private fun indexOfIgnoreCase(s: String, lowerLit: String, from: Int): Int {
        var i = from
        val first = lowerLit[0]
        while (i + lowerLit.length <= s.length) {
            if (Js.asciiLower(s[i]) == first && Js.regionMatchesLower(s, i, lowerLit)) return i
            i++
        }
        return -1
    }

    /** `fixBlockLevelTagsInParagraphs`: unwraps block tags remarkable put inside `<p>`. */
    fun fixBlockLevelTagsInParagraphs(input: String): String {
        var html = input
        // <p><tag ...></p> -> <tag ...>
        html = replaceAt(html, "<p>", true) { i ->
            val e = blockOpenTag(html, i + 3)
            if (e >= 0 && Js.regionMatchesLower(html, e, "</p>")) (e + 4) to html.substring(i + 3, e) else null
        }
        // <p></tag></p> -> </tag>
        html = replaceAt(html, "<p>", true) { i ->
            val e = blockCloseTag(html, i + 3)
            if (e >= 0 && Js.regionMatchesLower(html, e, "</p>")) (e + 4) to html.substring(i + 3, e) else null
        }
        // <p><tag ...>(<br>)?\s* -> <tag ...><p>
        html = replaceAt(html, "<p>", true) { i ->
            val e = blockOpenTag(html, i + 3)
            if (e < 0) {
                null
            } else {
                var p = e
                if (Js.regionMatchesLower(html, p, "<br>")) p += 4
                while (p < html.length && Js.isSpace(html[p])) p++
                p to (html.substring(i + 3, e) + "<p>")
            }
        }
        html = moveBlockClosingTagOutOfParagraph(html, BLOCK_TAG_SET)
        // <p>\s*</p> and <p><br>\s*</p> (case-sensitive)
        html = replaceAt(html, "<p>", false) { i ->
            var p = i + 3
            while (p < html.length && Js.isSpace(html[p])) p++
            if (html.startsWith("</p>", p)) (p + 4) to "" else null
        }
        html = replaceAt(html, "<p><br>", false) { i ->
            var p = i + 7
            while (p < html.length && Js.isSpace(html[p])) p++
            if (html.startsWith("</p>", p)) (p + 4) to "" else null
        }
        return html
    }

    private fun isHtmlWhitespace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

    /** `moveBlockClosingTagOutOfParagraph`: `(\s*<br>)?\s*</tag></p>` becomes `</p></tag>`. */
    fun moveBlockClosingTagOutOfParagraph(html: String, blockTags: Set<String>): String {
        val n = html.length
        val out = StringBuilder(n)
        var i = 0
        while (i < n) {
            val pStart = html.indexOf("</p>", i)
            if (pStart < 0) {
                out.append(html, i, n)
                break
            }
            if (pStart == i || html[pStart - 1] != '>') {
                out.append(html, i, pStart + 4)
                i = pStart + 4
                continue
            }
            val closingStart = if (pStart - 2 < 0) -1 else html.lastIndexOf("</", pStart - 2)
            if (closingStart < i) {
                out.append(html, i, pStart + 4)
                i = pStart + 4
                continue
            }
            val tagName = html.substring(closingStart + 2, pStart - 1).lowercase()
            if (tagName !in blockTags) {
                out.append(html, i, pStart + 4)
                i = pStart + 4
                continue
            }
            var k = closingStart
            while (k > i && isHtmlWhitespace(html[k - 1])) k--
            if (k - 4 >= i && html.substring(k - 4, k).lowercase() == "<br>") {
                k -= 4
                while (k > i && isHtmlWhitespace(html[k - 1])) k--
            }
            out.append(html, i, k).append("</p>").append(html, closingStart, pStart)
            i = pStart + 4
        }
        return out.toString()
    }
}
