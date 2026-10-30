package com.ecency.core.render.markdown

import com.ecency.core.render.js.Js
/**
 * remarkable's inline HTML_TAG_RE as a hand-written matcher:
 *
 *     ^(?:open_tag|close_tag|comment|processing|declaration|cdata)
 *
 * A regex engine would do, but JVM regexes recurse once per repetition of a group such as
 * `(?:-?[^-])*` and overflow the stack on long comments; this scanner is linear and has no
 * recursion. Each branch below explains why greedy scanning gives the same match as the JS regex.
 */
internal object HtmlTag {
    /** Returns the end of the tag starting at [pos] (`src[pos] == '<'`), or -1. */
    fun match(src: String, pos: Int): Int {
        return when (Js.cc(src, pos + 1)) {
            0x2F -> closeTag(src, pos)
            0x21 -> comment(src, pos).let { if (it >= 0) it else declaration(src, pos).let { d -> if (d >= 0) d else cdata(src, pos) } }
            0x3F -> processing(src, pos)
            else -> openTag(src, pos)
        }
    }

    private fun isAlpha(c: Int) = (c in 0x41..0x5A) || (c in 0x61..0x7A)
    private fun isAlnum(c: Int) = isAlpha(c) || c in 0x30..0x39
    private fun isAttrNameStart(c: Int) = isAlpha(c) || c == 0x5F || c == 0x3A
    private fun isAttrNameChar(c: Int) = isAlnum(c) || c == 0x3A || c == 0x2E || c == 0x5F || c == 0x2D

    // unquoted = /[^"'=<>`\x00-\x20]+/
    private fun isUnquoted(c: Int) = c > 0x20 && c != 0x22 && c != 0x27 && c != 0x3D && c != 0x3C && c != 0x3E && c != 0x60

    private fun skipSpace(src: String, from: Int): Int {
        var i = from
        while (i < src.length && Js.isSpace(src[i].code)) i++
        return i
    }

    /**
     * `<[A-Za-z][A-Za-z0-9]*(?:\s+attr_name(?:\s*=\s*attr_value)?)*\s*\/?>`. Every quantifier is
     * followed by something its own characters cannot start, so the greedy parse is the only parse.
     */
    private fun openTag(src: String, pos: Int): Int {
        var i = pos + 1
        if (!isAlpha(Js.cc(src, i))) return -1
        i++
        while (isAlnum(Js.cc(src, i))) i++
        while (true) {
            val afterSpace = skipSpace(src, i)
            if (afterSpace == i || !isAttrNameStart(Js.cc(src, afterSpace))) break
            var j = afterSpace + 1
            while (isAttrNameChar(Js.cc(src, j))) j++
            i = j
            // optional value
            var k = skipSpace(src, j)
            if (Js.cc(src, k) != 0x3D) continue
            k = skipSpace(src, k + 1)
            val c = Js.cc(src, k)
            val valueEnd = when {
                c == 0x22 || c == 0x27 -> src.indexOf(c.toChar(), k + 1).let { if (it < 0) -1 else it + 1 }
                isUnquoted(c) -> {
                    var e = k
                    while (isUnquoted(Js.cc(src, e))) e++
                    e
                }
                else -> -1
            }
            if (valueEnd >= 0) i = valueEnd
        }
        i = skipSpace(src, i)
        if (Js.cc(src, i) == 0x2F) i++
        return if (Js.cc(src, i) == 0x3E) i + 1 else -1
    }

    /** `<\/[A-Za-z][A-Za-z0-9]*\s*>`. */
    private fun closeTag(src: String, pos: Int): Int {
        var i = pos + 2
        if (!isAlpha(Js.cc(src, i))) return -1
        i++
        while (isAlnum(Js.cc(src, i))) i++
        i = skipSpace(src, i)
        return if (Js.cc(src, i) == 0x3E) i + 1 else -1
    }

    /**
     * `<!---->|<!--(?:-?[^>-])(?:-?[^-])*-->`. The body units never contain "--", so the greedy
     * run of units ends at the only place a closing "-->" can start.
     */
    private fun comment(src: String, pos: Int): Int {
        if (!src.startsWith("<!--", pos)) return -1
        if (src.startsWith("<!---->", pos)) return pos + 7
        var i = pos + 4
        // first unit: -?[^>-]
        if (Js.cc(src, i) == 0x2D) i++
        val first = Js.cc(src, i)
        if (first < 0 || first == 0x3E || first == 0x2D) return -1
        i++
        // (?:-?[^-])*
        while (true) {
            val c = Js.cc(src, i)
            if (c < 0) break
            if (c != 0x2D) {
                i++
                continue
            }
            val n = Js.cc(src, i + 1)
            if (n < 0 || n == 0x2D) break
            i += 2
        }
        return if (src.startsWith("-->", i)) i + 3 else -1
    }

    /** `<[?].*?[?]>`: the first "?>" before any line terminator. */
    private fun processing(src: String, pos: Int): Int {
        var i = pos + 2
        while (i < src.length) {
            val c = src[i]
            if (c == '?' && Js.cc(src, i + 1) == 0x3E) return i + 2
            if (c == '\n' || c == '\r' || c == ' ' || c == ' ') return -1
            i++
        }
        return -1
    }

    /** `<![A-Z]+\s+[^>]*>`. */
    private fun declaration(src: String, pos: Int): Int {
        var i = pos + 2
        val start = i
        while (Js.cc(src, i) in 0x41..0x5A) i++
        if (i == start) return -1
        val afterSpace = skipSpace(src, i)
        if (afterSpace == i) return -1
        val gt = src.indexOf('>', afterSpace)
        return if (gt < 0) -1 else gt + 1
    }

    /** `<!\[CDATA\[[\s\S]*?\]\]>`. */
    private fun cdata(src: String, pos: Int): Int {
        if (!src.startsWith("<![CDATA[", pos)) return -1
        val end = src.indexOf("]]>", pos + 9)
        return if (end < 0) -1 else end + 3
    }
}
