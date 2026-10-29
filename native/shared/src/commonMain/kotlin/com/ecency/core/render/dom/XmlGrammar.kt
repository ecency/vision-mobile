package com.ecency.core.render.dom

import com.ecency.core.render.js.Js
/**
 * Hand-written matchers for the productions in xmldom's `grammar.js` (built there as
 * unicode-mode regular expressions). Scanning by hand keeps them linear and independent of
 * the platform regex engine.
 */
internal object XmlGrammar {
    private fun isNameStartCp(cp: Int): Boolean =
        cp == ':'.code || cp == '_'.code || cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code ||
            cp in 0xC0..0xD6 || cp in 0xD8..0xF6 || cp in 0xF8..0x2FF || cp in 0x370..0x1FFF ||
            cp in 0x200C..0x200D || cp in 0x2070..0x218F || cp in 0x2C00..0x2FEF || cp in 0x3001..0xD7FF ||
            cp in 0xF900..0xFDCF || cp in 0xFDF0..0xFFFD || cp in 0x10000..0x10FFFF

    private fun isNameCp(cp: Int): Boolean =
        isNameStartCp(cp) || cp == '-'.code || cp == '.'.code || cp in '0'.code..'9'.code || cp == 0xB7 ||
            cp in 0x300..0x36F || cp in 0x203F..0x2040

    /** XML `Char` (the `u`-mode class, so supplementary code points count). */
    fun isCharCp(cp: Int): Boolean =
        cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF

    fun isS(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r' || c == '\n'

    private fun cpLen(cp: Int) = if (cp > 0xFFFF) 2 else 1

    /** Length of the NCName starting at [from] (0 if none). */
    private fun ncName(s: String, from: Int): Int {
        if (from >= s.length) return 0
        val first = Js.codePointAt(s, from)
        if (first == ':'.code || !isNameStartCp(first)) return 0
        var i = from + cpLen(first)
        while (i < s.length) {
            val cp = Js.codePointAt(s, i)
            if (cp == ':'.code || !isNameCp(cp)) break
            i += cpLen(cp)
        }
        return i - from
    }

    /** Length of the XML `Name` starting at [from] (0 if none). */
    fun name(s: String, from: Int): Int {
        if (from >= s.length) return 0
        val first = Js.codePointAt(s, from)
        if (!isNameStartCp(first)) return 0
        var i = from + cpLen(first)
        while (i < s.length) {
            val cp = Js.codePointAt(s, i)
            if (!isNameCp(cp)) break
            i += cpLen(cp)
        }
        return i - from
    }

    /** Length of the greedy `QName` prefix at [from] (0 if none). */
    fun qNamePrefix(s: String, from: Int): Int {
        val a = ncName(s, from)
        if (a == 0) return 0
        val colon = from + a
        if (colon < s.length && s[colon] == ':') {
            val b = ncName(s, colon + 1)
            if (b > 0) return a + 1 + b
        }
        return a
    }

    fun isQName(s: String): Boolean = s.isNotEmpty() && qNamePrefix(s, 0) == s.length

    /** End (exclusive) of a well-formed `<!--...-->` starting at [i], or -1. */
    fun matchComment(s: String, i: Int): Int {
        if (!s.startsWith("<!--", i)) return -1
        var p = i + 4
        while (p < s.length) {
            if (s[p] == '-') {
                if (p + 1 < s.length && s[p + 1] == '-') {
                    return if (p + 2 < s.length && s[p + 2] == '>') p + 3 else -1
                }
                if (p + 1 >= s.length) return -1
                val cp = Js.codePointAt(s, p + 1)
                if (!isCharCp(cp)) return -1
                p += 1 + cpLen(cp)
            } else {
                val cp = Js.codePointAt(s, p)
                if (!isCharCp(cp)) return -1
                p += cpLen(cp)
            }
        }
        return -1
    }

    /** End (exclusive) of the lazy run of `Char` from [from] up to and including [terminator], or -1. */
    fun lazyCharsUntil(s: String, from: Int, terminator: String): Int {
        var p = from
        while (p <= s.length) {
            if (s.startsWith(terminator, p)) return p + terminator.length
            if (p >= s.length) return -1
            val cp = Js.codePointAt(s, p)
            if (!isCharCp(cp)) return -1
            p += cpLen(cp)
        }
        return -1
    }

    /** `<![CDATA[...]]>` starting at [i]: end (exclusive) or -1. */
    fun matchCData(s: String, i: Int): Int =
        if (!s.startsWith("<![CDATA[", i)) -1 else lazyCharsUntil(s, i + 9, "]]>")

    class Pi(val target: String, val data: String?, val end: Int)

    /** Processing instruction `<?Name (S data)??>` starting at [i]. */
    fun matchPi(s: String, i: Int): Pi? {
        if (!s.startsWith("<?", i)) return null
        val n = name(s, i + 2)
        if (n == 0) return null
        val j = i + 2 + n
        val target = s.substring(i + 2, j)
        if (j < s.length && isS(s[j])) {
            var k = j
            while (k < s.length && isS(s[k])) k++
            val end = lazyCharsUntil(s, k, "?>")
            return if (end < 0) null else Pi(target, s.substring(k, end - 2), end)
        }
        return if (s.startsWith("?>", j)) Pi(target, null, j + 2) else null
    }
}
