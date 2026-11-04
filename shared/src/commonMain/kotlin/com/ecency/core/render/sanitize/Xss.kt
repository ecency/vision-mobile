package com.ecency.core.render.sanitize

import com.ecency.core.render.js.Js

/**
 * Port of `xss` 1.0.15 (`FilterXSS.process`) for the option set render-helper uses:
 * a custom whitelist, `stripIgnoreTag: true`, `stripIgnoreTagBody` with a tag list,
 * `css: false`, comments stripped, and an `onTagAttr` hook. The tokenizer is ported as is,
 * quirks included, because its output is what the app has always rendered.
 *
 * [onTagAttr] mirrors the JS hook: return null for "no opinion" (JS `undefined`), or a
 * replacement string ("" drops the attribute).
 */
internal class Xss(
    whiteList: Map<String, List<String>>,
    private val stripIgnoreTagBody: List<String>,
    private val onTagAttr: (tag: String, name: String, value: String) -> String?,
) {
    private val whiteList: Map<String, List<String>> =
        whiteList.entries.associate { (k, v) -> Js.lower(k) to v.map { Js.lower(it) } }

    fun process(input: String): String {
        if (input.isEmpty()) return ""
        val html = stripCommentTag(input)
        val strip = StripTagBody(stripIgnoreTagBody)
        val ret = parseTag(html) { _, position, tag, tagHtml, isClosing ->
            onTag(tag, tagHtml, isClosing, position, strip)
        }
        return strip.remove(ret)
    }

    private fun onTag(tag: String, html: String, isClosing: Boolean, position: Int, strip: StripTagBody): String {
        val attrList = whiteList[tag]
        if (attrList == null) {
            // onIgnoreTag: stripIgnoreTagBody wrapping onIgnoreTagStripAll.
            return strip.onIgnoreTag(tag, isClosing, position) ?: ""
        }
        if (isClosing) return "</$tag>"
        val attrs = getAttrs(html)
        val attrsHtml = parseAttr(attrs.html) { name, value ->
            val isWhiteAttr = name in attrList
            val ret = onTagAttr(tag, name, value)
            when {
                ret != null -> ret
                isWhiteAttr -> {
                    val v = safeAttrValue(name, value)
                    if (v.isNotEmpty()) "$name=\"$v\"" else name
                }
                else -> null
            }
        }
        val sb = StringBuilder("<").append(tag)
        if (attrsHtml.isNotEmpty()) sb.append(' ').append(attrsHtml)
        if (attrs.closing) sb.append(" /")
        sb.append('>')
        return sb.toString()
    }

    private class StripTagBody(private val tags: List<String>) {
        private val removeList = ArrayList<IntArray>()
        private var posStart = -1 // -1 is JS `false`

        fun onIgnoreTag(tag: String, isClosing: Boolean, position: Int): String? {
            if (tag !in tags) return null
            return if (isClosing) {
                val ret = "[/removed]"
                val end = position + ret.length
                removeList.add(intArrayOf(if (posStart != -1) posStart else position, end))
                posStart = -1
                ret
            } else {
                // `if (!posStart)`: a recorded start of 0 is falsy too.
                if (posStart <= 0) posStart = position
                "[removed]"
            }
        }

        fun remove(html: String): String {
            if (removeList.isEmpty()) return html
            val sb = StringBuilder()
            var lastPos = 0
            for (pos in removeList) {
                sb.append(jsSlice(html, lastPos, pos[0]))
                lastPos = pos[1]
            }
            sb.append(jsSlice(html, lastPos, html.length))
            return sb.toString()
        }
    }

    private class Attrs(val html: String, val closing: Boolean)

    companion object {
        /** `String.prototype.slice` with non-negative bounds. */
        internal fun jsSlice(s: String, start: Int, end: Int): String {
            val a = start.coerceIn(0, s.length)
            val b = end.coerceIn(0, s.length)
            return if (a >= b) "" else s.substring(a, b)
        }

        private fun spaceIndex(s: String): Int {
            for (i in s.indices) if (Js.isSpace(s[i])) return i
            return -1
        }

        private fun getTagName(html: String): String {
            val i = spaceIndex(html)
            var tagName = if (i == -1) jsSlice(html, 1, html.length - 1) else jsSlice(html, 1, i + 1)
            tagName = Js.lower(Js.trim(tagName))
            if (tagName.startsWith("/")) tagName = tagName.substring(1)
            if (tagName.endsWith("/")) tagName = tagName.substring(0, tagName.length - 1)
            return tagName
        }

        private fun getAttrs(html: String): Attrs {
            val i = spaceIndex(html)
            if (i == -1) return Attrs("", html.length >= 2 && html[html.length - 2] == '/')
            var h = Js.trim(jsSlice(html, i + 1, html.length - 1))
            val closing = h.isNotEmpty() && h[h.length - 1] == '/'
            if (closing) h = Js.trim(h.substring(0, h.length - 1))
            return Attrs(h, closing)
        }

        /** Removes `<!-- ... -->`; an unterminated comment drops the rest of the input. */
        fun stripCommentTag(html: String): String {
            if (html.indexOf("<!--") < 0) return html
            val sb = StringBuilder()
            var lastPos = 0
            while (lastPos < html.length) {
                val i = html.indexOf("<!--", lastPos)
                if (i == -1) {
                    sb.append(html, lastPos, html.length)
                    break
                }
                sb.append(html, lastPos, i)
                val j = html.indexOf("-->", i)
                if (j == -1) break
                lastPos = j + 3
            }
            return sb.toString()
        }

        fun escapeHtml(s: String): String {
            if (s.indexOf('<') < 0 && s.indexOf('>') < 0) return s
            val sb = StringBuilder(s.length + 8)
            for (c in s) {
                when (c) {
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        /** xss `parseTag`. */
        private fun parseTag(html: String, onTag: (Int, Int, String, String, Boolean) -> String): String {
            val ret = StringBuilder(html.length + 16)
            var lastPos = 0
            var tagStart = -1
            var quoteStart = '\u0000'
            val len = html.length
            var currentPos = 0
            while (currentPos < len) {
                val c = html[currentPos]
                if (tagStart == -1) {
                    if (c == '<') tagStart = currentPos
                } else if (quoteStart == '\u0000') {
                    if (c == '<') {
                        ret.append(escapeHtml(jsSlice(html, lastPos, currentPos)))
                        tagStart = currentPos
                        lastPos = currentPos
                    } else if (c == '>' || currentPos == len - 1) {
                        ret.append(escapeHtml(jsSlice(html, lastPos, tagStart)))
                        val currentHtml = jsSlice(html, tagStart, currentPos + 1)
                        val tagName = getTagName(currentHtml)
                        ret.append(onTag(tagStart, ret.length, tagName, currentHtml, currentHtml.startsWith("</")))
                        lastPos = currentPos + 1
                        tagStart = -1
                    } else if (c == '"' || c == '\'') {
                        var i = 1
                        var ic = charAtOrEmpty(html, currentPos - i)
                        while (ic == null || Js.isSpace(ic) || ic == '=') {
                            if (ic == '=') {
                                quoteStart = c
                                break
                            }
                            i++
                            ic = charAtOrEmpty(html, currentPos - i)
                        }
                    }
                } else if (c == quoteStart) {
                    quoteStart = '\u0000'
                }
                currentPos++
            }
            if (lastPos < len) ret.append(escapeHtml(html.substring(lastPos)))
            return ret.toString()
        }

        /** `charAt` that returns null for the empty string an out-of-range index gives in JS. */
        private fun charAtOrEmpty(s: String, i: Int): Char? = if (i < 0 || i >= s.length) null else s[i]

        private fun isLegalAttrNameChar(c: Char): Boolean =
            Js.isAsciiAlpha(c) || Js.isDigit(c) || c == '\\' || c == '_' || c == ':' || c == '.' || c == '-'

        /** xss `parseAttr`; [onAttr] returns null to drop the attribute. */
        internal fun parseAttr(input: String, onAttr: (String, String) -> String?): String {
            var html = input
            var spacesNormalized = false
            var lastPos = 0
            var lastMarkPos = -2 // -2 stands for JS `undefined`
            val retAttrs = ArrayList<String>()
            var tmpName: String? = null
            val len = html.length

            fun addAttr(name0: String, value: String) {
                val trimmed = Js.trim(name0)
                val sb = StringBuilder(trimmed.length)
                for (ch in trimmed) if (isLegalAttrNameChar(ch)) sb.append(ch)
                val name = sb.toString().lowercase()
                if (name.isEmpty()) return
                val ret = onAttr(name, value)
                if (!ret.isNullOrEmpty()) retAttrs.add(ret)
            }

            var nextNonSpace = IntArray(0)
            var prevNonSpace = IntArray(0)
            var i = 0
            loop@ while (i < len) {
                val c = html[i]
                if (tmpName == null && c == '=') {
                    tmpName = jsSlice(html, lastPos, i)
                    lastPos = i + 1
                    val at = charAtOrEmpty(html, lastPos)
                    lastMarkPos = if (at == '"' || at == '\'') lastPos else findNextQuotationMark(html, i + 1)
                    i++
                    continue
                }
                if (tmpName != null && i == lastMarkPos) {
                    val j = html.indexOf(c, i + 1)
                    if (j == -1) break@loop
                    val v = Js.trim(jsSlice(html, lastMarkPos + 1, j))
                    addAttr(tmpName, v)
                    tmpName = null
                    i = j
                    lastPos = i + 1
                    i++
                    continue
                }
                if (Js.isSpace(c)) {
                    if (!spacesNormalized) {
                        spacesNormalized = true
                        val sb = StringBuilder(html.length)
                        for (ch in html) sb.append(if (Js.isSpace(ch)) ' ' else ch)
                        html = sb.toString()
                        // findNextEqual/findBeforeEqual skip runs of spaces; precomputing the
                        // neighbours keeps long space runs linear instead of quadratic.
                        nextNonSpace = IntArray(len + 1).also { a ->
                            a[len] = len
                            for (k in len - 1 downTo 0) a[k] = if (html[k] == ' ') a[k + 1] else k
                        }
                        prevNonSpace = IntArray(len).also { a ->
                            for (k in 0 until len) a[k] = if (html[k] != ' ') k else if (k > 0) a[k - 1] else -1
                        }
                    }
                    if (tmpName == null) {
                        val k = nextNonSpace[i]
                        val j = if (k >= len) -2 else if (html[k] == '=') k else -1
                        if (j == -1) {
                            val v = Js.trim(jsSlice(html, lastPos, i))
                            addAttr(v, "")
                            tmpName = null
                            lastPos = i + 1
                            i++
                            continue
                        } else if (j == -2) {
                            break@loop // `i = undefined - 1` is NaN: the loop ends
                        } else {
                            i = j - 1
                            i++
                            continue
                        }
                    } else {
                        val k = if (i - 1 >= 1) prevNonSpace[i - 1] else 0
                        val j = if (k < 1) -2 else if (html[k] == '=') k else -1
                        if (j == -1) {
                            var v = Js.trim(jsSlice(html, lastPos, i))
                            v = stripQuoteWrap(v)
                            addAttr(tmpName, v)
                            tmpName = null
                            lastPos = i + 1
                        }
                        i++
                        continue
                    }
                }
                i++
            }
            if (lastPos < html.length) {
                if (tmpName == null) {
                    addAttr(html.substring(lastPos), "")
                } else {
                    addAttr(tmpName, stripQuoteWrap(Js.trim(html.substring(lastPos))))
                }
            }
            return Js.trim(retAttrs.joinToString(" "))
        }

        private fun findNextQuotationMark(str: String, from: Int): Int {
            var i = from
            while (i < str.length) {
                val c = str[i]
                if (c == ' ') {
                    i++
                    continue
                }
                return if (c == '\'' || c == '"') i else -1
            }
            return -2
        }

        private fun stripQuoteWrap(text: String): String {
            if (text.isEmpty()) return text
            val a = text[0]
            val b = text[text.length - 1]
            return if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) jsSubstr(text, 1, text.length - 2) else text
        }

        private fun jsSubstr(s: String, start: Int, length: Int): String =
            if (length <= 0) "" else s.substring(start, minOf(s.length, start + length))

        // ---- default.js helpers ----

        private fun safeAttrValue(name: String, value0: String): String {
            var value = friendlyAttrValue(value0)
            if (name == "href" || name == "src") {
                value = Js.trim(value)
                if (value == "#") return "#"
                val ok = value.startsWith("http://") || value.startsWith("https://") || value.startsWith("mailto:") ||
                    value.startsWith("tel:") || value.startsWith("data:image/") || value.startsWith("ftp://") ||
                    value.startsWith("./") || value.startsWith("../") || value.startsWith("#") || value.startsWith("/")
                if (!ok) return ""
            } else if (name == "background") {
                if (containsScriptProtocol(value)) return ""
            } else if (name == "style") {
                if (matchesSpacedWordThenParen(value, "expression")) return ""
                if (matchesSpacedWordThenParen(value, "url") && containsScriptProtocol(value)) return ""
            }
            return escapeAttrValue(value)
        }

        private fun escapeAttrValue(s: String): String {
            if (s.indexOf('"') < 0 && s.indexOf('<') < 0 && s.indexOf('>') < 0) return s
            val sb = StringBuilder(s.length + 8)
            for (c in s) {
                when (c) {
                    '"' -> sb.append("&quot;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        internal fun friendlyAttrValue(s0: String): String {
            var s = s0.replace("&quot;", "\"")
            s = escapeHtmlEntities(s)
            s = replaceIgnoreCase(s, "&colon", ":")
            s = replaceIgnoreCase(s, "&newline", " ")
            val sb = StringBuilder(s.length)
            for (c in s) sb.append(if (c.code < 32) ' ' else c)
            return Js.trim(sb.toString())
        }

        /** `/&#([a-zA-Z0-9]*);?/gim` to `String.fromCharCode(parseInt(...))`. */
        private fun escapeHtmlEntities(s: String): String {
            var idx = s.indexOf("&#")
            if (idx < 0) return s
            val sb = StringBuilder(s.length)
            var last = 0
            while (idx >= 0) {
                var p = idx + 2
                while (p < s.length && (Js.isAsciiAlpha(s[p]) || Js.isDigit(s[p]))) p++
                val code = s.substring(idx + 2, p)
                if (p < s.length && s[p] == ';') p++
                sb.append(s, last, idx)
                val value = if (code.isNotEmpty() && (code[0] == 'x' || code[0] == 'X')) {
                    parseIntPrefix(code.substring(1), 16)
                } else {
                    parseIntPrefix(code, 10)
                }
                sb.append(Js.toUint16(value))
                last = p
                idx = s.indexOf("&#", p)
            }
            sb.append(s, last, s.length)
            return sb.toString()
        }

        /** `parseInt(s, radix)` for strings that hold only ASCII letters and digits. */
        private fun parseIntPrefix(s0: String, radix: Int): Double {
            // parseInt with radix 16 also skips a "0x" prefix.
            val s = if (radix == 16 && s0.length >= 2 && s0[0] == '0' && (s0[1] == 'x' || s0[1] == 'X')) s0.substring(2) else s0
            var end = 0
            while (end < s.length && (if (radix == 10) Js.isDigit(s[end]) else Js.isHexDigit(s[end]))) end++
            if (end == 0) return Double.NaN
            return Js.digitsToDouble(s.substring(0, end), radix)
        }

        /** `str.replace(/&lit;?/gim, rep)` for an ASCII literal. */
        private fun replaceIgnoreCase(s: String, lit: String, rep: String): String {
            if (s.indexOf('&') < 0) return s
            val sb = StringBuilder(s.length)
            var i = 0
            var last = 0
            while (i < s.length) {
                if (s[i] == '&' && Js.regionMatchesLower(s, i, lit)) {
                    sb.append(s, last, i).append(rep)
                    i += lit.length
                    if (i < s.length && s[i] == ';') i++
                    last = i
                } else {
                    i++
                }
            }
            if (last == 0) return s
            sb.append(s, last, s.length)
            return sb.toString()
        }

        /** `/((j\s*a\s*v\s*a|v\s*b|l\s*i\s*v\s*e)\s*s\s*c\s*r\s*i\s*p\s*t\s*|m\s*o\s*c\s*h\s*a):/gi`. */
        private fun containsScriptProtocol(v: String): Boolean {
            for (start in v.indices) {
                for (prefix in arrayOf("java", "vb", "live")) {
                    val afterPrefix = spacedWord(v, start, prefix, trailingSpaces = true)
                    if (afterPrefix >= 0) {
                        val end = spacedWord(v, afterPrefix, "script", trailingSpaces = true)
                        if (end >= 0 && end < v.length && v[end] == ':') return true
                    }
                }
                val m = spacedWord(v, start, "mocha", trailingSpaces = false)
                if (m >= 0 && m < v.length && v[m] == ':') return true
            }
            return false
        }

        /** `/word-letters-with-\s*-between\s*\(.* /i` anywhere in [v]. */
        private fun matchesSpacedWordThenParen(v: String, word: String): Boolean {
            for (start in v.indices) {
                val end = spacedWord(v, start, word, trailingSpaces = true)
                if (end >= 0 && end < v.length && v[end] == '(') return true
            }
            return false
        }

        /**
         * Matches the letters of [word] at [start] with optional whitespace between them (and
         * after the last one when [trailingSpaces]); returns the end index or -1.
         */
        private fun spacedWord(v: String, start: Int, word: String, trailingSpaces: Boolean): Int {
            var p = start
            for (k in word.indices) {
                if (k > 0) while (p < v.length && Js.isSpace(v[p])) p++
                if (p >= v.length || Js.asciiLower(v[p]) != word[k]) return -1
                p++
            }
            if (trailingSpaces) while (p < v.length && Js.isSpace(v[p])) p++
            return p
        }
    }
}
