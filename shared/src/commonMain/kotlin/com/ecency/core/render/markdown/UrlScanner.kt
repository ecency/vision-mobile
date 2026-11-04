package com.ecency.core.render.markdown

/**
 * Autolinker's UrlMatcher.matcherRegex (flags gi) as a scanner, with the regex's backtracking
 * order reproduced step by step:
 *
 *     (?:(SCHEME DOMAIN)|((//)?www\.DOMAIN)|((//)?DOMAIN\.TLD(?![-ALNUM])))(?::[0-9]+)?(?:[/?#](?:[S]*[E])?)?
 *
 *     SCHEME = [A-Za-z][-.+A-Za-z0-9]{0,63}:(?![A-Za-z][-.+A-Za-z0-9]{0,63}:\/\/)(?!\d+\/?)(?:\/\/)?
 *     DOMAIN = (?:LABEL(?:\.LABEL){0,126}|(?:[DEC]{1,3}\.){3}[DEC]{1,3})
 *     LABEL  = atomic [ALNUM](?:[ALNUM\-_]{0,61}[ALNUM])?   (written (?=(...))\N in JS)
 *
 * generate-markdown-data.mjs rebuilds that regex from the same parts and fails if Autolinker's
 * differs. A regex engine is not used because Kotlin/Native's is slow on this pattern (tens of
 * microseconds per start position) and mishandles lookaheads inside repeated groups.
 *
 * Case folding: the classes ([AutolinkerData.URL_ALNUM] etc.) are generated as the /i regex sees
 * them, and TLD literals are compared through JS's Canonicalize ([canon]).
 */
internal object UrlScanner {
    class RegexMatch(val start: Int, val end: Int, val schemeEnd: Int, val protocolRelative: Boolean)

    private fun ranges(data: String): BooleanArray {
        val set = BooleanArray(0x10000)
        for (range in data.split(',')) {
            val dash = range.indexOf('-')
            for (c in range.substring(0, dash).toInt(16)..range.substring(dash + 1).toInt(16)) set[c] = true
        }
        return set
    }

    private val alnum by lazy { ranges(AutolinkerData.URL_ALNUM) }
    private val decimal by lazy { ranges(AutolinkerData.URL_DECIMAL) }
    private val suffix by lazy { ranges(AutolinkerData.URL_SUFFIX) }
    private val suffixEnd by lazy { ranges(AutolinkerData.URL_SUFFIX_END) }
    private val canon: CharArray by lazy {
        val table = CharArray(0x10000)
        for (run in AutolinkerData.CANON_TABLE.split(',')) {
            val dash = run.indexOf('-')
            val colon = run.indexOf(':')
            val lo = run.substring(0, dash).toInt(16)
            val hi = run.substring(dash + 1, colon).toInt(16)
            val value = run.substring(colon + 1)
            for (c in lo..hi) table[c] = if (value == "i") c.toChar() else value.toInt(16).toChar()
        }
        table
    }

    /** TLD prefix tree over canonical characters; [index] is the TLD's position in the regex. */
    private class Node {
        val children = HashMap<Char, Node>()
        var index = -1
    }

    private val tldTree: Node by lazy {
        val root = Node()
        AutolinkerData.TLDS.split('|').forEachIndexed { i, tld ->
            var n = root
            for (ch in tld) n = n.children.getOrPut(canon[ch.code]) { Node() }
            if (n.index < 0) n.index = i
        }
        root
    }

    private fun isAlnum(s: String, i: Int) = i < s.length && alnum[s[i].code]
    private fun isDecimal(s: String, i: Int) = i < s.length && decimal[s[i].code]
    private fun isAsciiLetter(s: String, i: Int) = i < s.length && (s[i] in 'A'..'Z' || s[i] in 'a'..'z')
    private fun isAsciiDigit(s: String, i: Int) = i < s.length && s[i] in '0'..'9'
    private fun isSchemeChar(s: String, i: Int) =
        i < s.length && (s[i] in 'A'..'Z' || s[i] in 'a'..'z' || s[i] in '0'..'9' || s[i] == '-' || s[i] == '.' || s[i] == '+')

    /** Leftmost match at or after [from], as `exec` with `lastIndex = from`. */
    fun find(text: String, from: Int): RegexMatch? {
        for (p in from until text.length) matchAt(text, p)?.let { return it }
        return null
    }

    private fun matchAt(s: String, p: Int): RegexMatch? {
        var end: Int
        var schemeEnd = -1
        var relative = false
        val scheme = schemeBranch(s, p)
        if (scheme >= 0) {
            end = scheme
            schemeEnd = scheme
        } else {
            val q = if (s.startsWith("//", p)) p + 2 else p
            val www = wwwBranch(s, q)
            if (www >= 0) {
                end = www
                relative = q != p
            } else {
                val tld = tldBranch(s, q)
                if (tld < 0) return null
                end = tld
                relative = q != p
            }
        }
        // (?::[0-9]+)?
        if (end < s.length && s[end] == ':' && isAsciiDigit(s, end + 1)) {
            end += 2
            while (isAsciiDigit(s, end)) end++
        }
        // (?:[/?#](?:[S]*[E])?)?
        if (end < s.length && (s[end] == '/' || s[end] == '?' || s[end] == '#')) {
            end++
            var i = end
            var lastEnd = -1
            while (i < s.length && suffix[s[i].code]) {
                if (suffixEnd[s[i].code]) lastEnd = i
                i++
            }
            if (lastEnd >= 0) end = lastEnd + 1
        }
        return RegexMatch(p, end, schemeEnd, relative)
    }

    /** `[A-Za-z][-.+A-Za-z0-9]{0,63}:` at [i]: the index after ':', or -1. */
    private fun schemePrefix(s: String, i: Int): Int {
        if (!isAsciiLetter(s, i)) return -1
        var j = i + 1
        // Stop one past the 63 allowed: a longer run cannot be followed by ':' (scanning the whole
        // run made every position O(n), quadratic on long dotted words).
        while (j - (i + 1) <= 63 && isSchemeChar(s, j)) j++
        if (j - (i + 1) > 63 || j >= s.length || s[j] != ':') return -1
        return j + 1
    }

    private fun schemeBranch(s: String, p: Int): Int {
        val afterColon = schemePrefix(s, p)
        if (afterColon < 0) return -1
        // (?![A-Za-z][-.+A-Za-z0-9]{0,63}:\/\/)
        val next = schemePrefix(s, afterColon)
        if (next >= 0 && s.startsWith("//", next)) return -1
        // (?!\d+\/?)
        if (isAsciiDigit(s, afterColon)) return -1
        // (?:\/\/)? then DOMAIN; without the slashes DOMAIN would have to start at '/', which it cannot.
        val domainStart = if (s.startsWith("//", afterColon)) afterColon + 2 else afterColon
        return domainGreedy(s, domainStart)
    }

    private fun wwwBranch(s: String, q: Int): Int {
        if (q + 4 > s.length) return -1
        for (k in 0 until 3) if (canon[s[q + k].code] != 'W') return -1
        if (s[q + 3] != '.') return -1
        return domainGreedy(s, q + 4)
    }

    /** End of the atomic LABEL at [q], or -1. */
    private fun labelEnd(s: String, q: Int): Int {
        if (!isAlnum(s, q)) return -1
        var j = 0
        var best = 0
        var i = q + 1
        while (i < s.length && j < 62 && (alnum[s[i].code] || s[i] == '-' || s[i] == '_')) {
            j++
            if (alnum[s[i].code]) best = j
            i++
        }
        return q + 1 + best
    }

    /**
     * Ends of LABEL(\.LABEL){0,126} after 1, 2, ... labels (greedy order is the last first), written
     * into [out]; returns how many. A primitive buffer: boxing every end was the main cost of this
     * scan on Kotlin/Native for long dotted runs.
     */
    private fun labelEnds(s: String, q: Int, out: IntArray): Int {
        val first = labelEnd(s, q)
        if (first < 0) return 0
        var n = 0
        out[n++] = first
        var e = first
        while (n < 127 && e < s.length && s[e] == '.') {
            val next = labelEnd(s, e + 1)
            if (next < 0) break
            out[n++] = next
            e = next
        }
        return n
    }

    /** End of the greedy LABEL(\.LABEL){0,126} at [q], or -1. */
    private fun lastLabelEnd(s: String, q: Int): Int {
        var e = labelEnd(s, q)
        if (e < 0) return -1
        var n = 1
        while (n < 127 && e < s.length && s[e] == '.') {
            val next = labelEnd(s, e + 1)
            if (next < 0) break
            e = next
            n++
        }
        return e
    }

    /** `(?:[DEC]{1,3}\.){3}[DEC]{1,3}` greedy end, or -1. */
    private fun ipEnd(s: String, q: Int): Int {
        var i = q
        for (part in 0 until 4) {
            val start = i
            while (isDecimal(s, i)) i++
            val n = i - start
            if (part < 3) {
                if (n < 1 || n > 3 || i >= s.length || s[i] != '.') return -1
                i++
            } else {
                if (n < 1) return -1
                i = start + minOf(n, 3)
            }
        }
        return i
    }

    /** DOMAIN where nothing required follows: the greedy (first) alternative wins. */
    private fun domainGreedy(s: String, q: Int): Int {
        val last = lastLabelEnd(s, q)
        return if (last >= 0) last else ipEnd(s, q)
    }

    private fun tldBranch(s: String, q: Int): Int {
        val ends = IntArray(127)
        val count = labelEnds(s, q, ends)
        for (c in count - 1 downTo 0) {
            val e = ends[c]
            if (e < s.length && s[e] == '.') {
                val t = tldAt(s, e + 1)
                if (t >= 0) return t
            }
        }
        val ip = ipEnd(s, q)
        // a longer last IP part than 3 digits leaves a digit where "\." is needed
        if (ip >= 0 && ip < s.length && s[ip] == '.') return tldAt(s, ip + 1)
        return -1
    }

    /** `(?:tld1|tld2|...)(?![-ALNUM])` at [pos]: the first TLD in regex order that fits, or -1. */
    private fun tldAt(s: String, pos: Int): Int {
        var node = tldTree
        var i = pos
        var bestIndex = Int.MAX_VALUE
        var bestEnd = -1
        while (i < s.length) {
            node = node.children[canon[s[i].code]] ?: break
            i++
            if (node.index >= 0 && node.index < bestIndex) {
                val ok = !(i < s.length && (s[i] == '-' || alnum[s[i].code]))
                if (ok) {
                    bestIndex = node.index
                    bestEnd = i
                }
            }
        }
        return bestEnd
    }
}
