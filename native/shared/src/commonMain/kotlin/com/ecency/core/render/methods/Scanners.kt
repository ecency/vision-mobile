package com.ecency.core.render.methods

import com.ecency.core.render.js.Js
/**
 * Hand-written matchers for the render-helper regexes that run on every text node or over
 * whole bodies, where Kotlin/Native's regex engine is slow, or where JS counts UTF-16 code units
 * and the JVM engine would count code points. Each one documents the JS source it reproduces,
 * including where JS backtracking makes the result unique.
 */
internal object Scanners {
    private val IMG_EXTS = arrayOf("tif", "jpg", "jpeg", "gif", "png", "svg", "ico", "heic", "webp", "arw")

    /** Index after "http://" or "https://" (ASCII case-insensitive) at [p], or -1. */
    fun schemeEnd(s: String, p: Int): Int {
        if (!Js.startsWithCi(s, "http", p)) return -1
        var q = p + 4
        if (q < s.length && (s[q] == 's' || s[q] == 'S')) {
            if (s.startsWith("://", q + 1)) return q + 4
        }
        return if (s.startsWith("://", q)) q + 3 else -1
    }

    private fun lineEnd(s: String, from: Int): Int {
        var i = from
        while (i < s.length && !Js.isLineTerminator(s[i])) i++
        return i
    }

    private fun hasImageExt(s: String, from: Int, to: Int): Boolean {
        var d = s.indexOf('.', from)
        while (d in from until to) {
            for (e in IMG_EXTS) if (Js.startsWithCi(s, e, d + 1)) return true
            d = s.indexOf('.', d + 1)
        }
        return false
    }

    /**
     * `IMG_REGEX = /(https?:\/\/.*\.(?:tiff?|jpe?g|gif|png|svg|ico|heic|webp|arw))(.*)/gim` from
     * [from]: the leftmost http(s):// whose line holds an image extension after it. The trailing
     * `(.*)` runs to the end of the line, so the match is always [start, line end). If the first
     * candidate of a line fails, every later one on that line fails too (its tail is a suffix).
     */
    fun imgFind(s: String, from: Int = 0): IntArray? {
        var p = from
        while (true) {
            val h = Js.indexOfCi(s, "http", p)
            if (h < 0) return null
            val q = schemeEnd(s, h)
            if (q < 0) {
                p = h + 1
                continue
            }
            val end = lineEnd(s, q)
            if (hasImageExt(s, q, end)) return intArrayOf(h, end)
            p = end
            if (p >= s.length) return null
        }
    }

    fun imgTest(s: String): Boolean = imgFind(s) != null

    /** `s.replace(IMG_REGEX, fn)`. */
    inline fun imgReplace(s: String, fn: (String) -> String): String {
        var m = imgFind(s, 0) ?: return s
        val sb = StringBuilder(s.length + 64)
        var last = 0
        while (true) {
            sb.append(s, last, m[0])
            sb.append(fn(s.substring(m[0], m[1])))
            last = m[1]
            m = imgFind(s, last) ?: break
        }
        sb.append(s, last, s.length)
        return sb.toString()
    }

    /** `s.match(ENTITY_REGEX)` for `/&([a-z0-9]+|#[0-9]{1,6}|#x[0-9a-fA-F]{1,6});/ig`. */
    fun entityMatches(s: String): List<String> {
        val out = ArrayList<String>()
        var i = s.indexOf('&')
        while (i >= 0) {
            val end = entityEnd(s, i)
            if (end > 0) {
                out.add(s.substring(i, end))
                i = s.indexOf('&', end)
            } else {
                i = s.indexOf('&', i + 1)
            }
        }
        return out
    }

    private fun isAsciiAlnum(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    private fun entityEnd(s: String, amp: Int): Int {
        var j = amp + 1
        val n = s.length
        // [a-z0-9]+ ; (greedy; a shorter run would be followed by an alnum, not ';')
        while (j < n && isAsciiAlnum(s[j])) j++
        if (j > amp + 1) return if (j < n && s[j] == ';') j + 1 else -1
        if (j >= n || s[j] != '#') return -1
        // #[0-9]{1,6};
        var k = j + 1
        while (k < n && k - (j + 1) < 6 && s[k] in '0'..'9') k++
        if (k > j + 1 && k < n && s[k] == ';') return k + 1
        // #x[0-9a-fA-F]{1,6};
        if (j + 1 < n && (s[j + 1] == 'x' || s[j + 1] == 'X')) {
            k = j + 2
            while (k < n && k - (j + 2) < 6 && isHex(s[k])) k++
            if (k > j + 2 && k < n && s[k] == ';') return k + 1
        }
        return -1
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    class YtMatch(val start: Int, val end: Int, val id: String, val matched: String)

    private fun isIdChar(c: Char) = c != '"' && c != '&' && c != '?' && c != '/' && !Js.isSpace(c)

    /** 11 id code units at [at], or false. */
    private fun idAt(s: String, at: Int): Boolean {
        if (at < 0 || at + 11 > s.length) return false
        for (k in at until at + 11) if (!isIdChar(s[k])) return false
        return true
    }

    /**
     * `YOUTUBE_REGEX = /(?:youtube\.com\/(?:[^\/]+\/.+\/|(?:v|e(?:mbed)?)\/|shorts\/|.*[?&]v=)|youtu\.be\/)([^"&?\/\s]{11})/i`,
     * first match. Alternatives are tried in the JS order and greedy runs from the right, so the
     * captured id is the one V8 reports.
     */
    fun youtubeFind(s: String): YtMatch? {
        val n = s.length
        var p = Js.indexOfCi(s, "youtu", 0)
        while (p >= 0) {
            if (Js.startsWithCi(s, "youtube.com/", p)) {
                val q = p + 12
                val id = youtubeComId(s, q)
                if (id >= 0) return YtMatch(p, id + 11, s.substring(id, id + 11), s.substring(p, id + 11))
            } else if (Js.startsWithCi(s, "youtu.be/", p) && idAt(s, p + 9)) {
                return YtMatch(p, p + 20, s.substring(p + 9, p + 20), s.substring(p, p + 20))
            }
            p = Js.indexOfCi(s, "youtu", p + 1)
            if (p > n) break
        }
        return null
    }

    /** Start of the id for the `youtube\.com\/` branch with the path starting at [q], or -1. */
    private fun youtubeComId(s: String, q: Int): Int {
        val n = s.length
        val lineEndQ = lineEnd(s, q)
        // [^\/]+\/.+\/  (the [^/] run must be maximal: it has to be followed by '/')
        var r = q
        while (r < n && s[r] != '/') r++
        if (r > q && r < n) {
            val le = lineEnd(s, r + 1)
            var e = le - 1
            while (e >= r + 2) {
                if (s[e] == '/' && idAt(s, e + 1)) return e + 1
                e--
            }
        }
        // (?:v|e(?:mbed)?)\/
        if (Js.startsWithCi(s, "v/", q) && idAt(s, q + 2)) return q + 2
        if (Js.startsWithCi(s, "embed/", q) && idAt(s, q + 6)) return q + 6
        if (Js.startsWithCi(s, "e/", q) && idAt(s, q + 2)) return q + 2
        // shorts\/
        if (Js.startsWithCi(s, "shorts/", q) && idAt(s, q + 7)) return q + 7
        // .*[?&]v=
        var e = lineEndQ - 1
        while (e >= q) {
            val c = s[e]
            if ((c == '?' || c == '&') && e + 2 < n && (s[e + 1] == 'v' || s[e + 1] == 'V') && s[e + 2] == '=' && idAt(s, e + 3)) {
                return e + 3
            }
            e--
        }
        return -1
    }

    /** `IPFS_REGEX = /^https?:\/\/[^/]+\/(ip[fn]s)\/([^/?#]+)/gim` used as a test (`^` also after line terminators). */
    fun ipfsTest(s: String): Boolean {
        var ls = 0
        while (ls <= s.length) {
            if (ipfsAt(s, ls)) return true
            var k = ls
            while (k < s.length && !Js.isLineTerminator(s[k])) k++
            if (k >= s.length) return false
            ls = k + 1
        }
        return false
    }

    private fun ipfsAt(s: String, at: Int): Boolean {
        val q = schemeEnd(s, at)
        if (q < 0) return false
        var r = q
        while (r < s.length && s[r] != '/') r++
        if (r == q || r >= s.length) return false
        val t = r + 1
        if (!Js.startsWithCi(s, "ip", t) || t + 4 >= s.length) return false
        val f = Js.asciiLower(s[t + 2])
        if ((f != 'f' && f != 'n') || Js.asciiLower(s[t + 3]) != 's' || s[t + 4] != '/') return false
        val c = if (t + 5 < s.length) s[t + 5] else return false
        return c != '/' && c != '?' && c != '#'
    }
}
