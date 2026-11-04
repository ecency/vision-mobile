package com.ecency.core.render.js

import kotlin.math.pow

/**
 * JavaScript string and number semantics shared by every part of the render port (markdown,
 * DOM, sanitizer, URL, transforms). Kotlin and JS strings are both UTF-16, so indices carry
 * over; these cover the places where the standard libraries disagree in ways that change
 * output bytes: the `\s` set and `trim`, `toLowerCase`, `charCodeAt`/`slice`/`substr`,
 * `encodeURI`/`decodeURI(Component)` and their errors, `parseInt`, Number to string, UTF-8 of
 * lone surrogates.
 */
internal object Js {

    /** JS RegExp `\s` (WhiteSpace and LineTerminator). Also what `String.prototype.trim` strips. */
    fun isSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u00A0', '\u1680', '\u2028', '\u2029',
        '\u202F', '\u205F', '\u3000', '\uFEFF' -> true
        else -> c in '\u2000'..'\u200A'
    }

    fun trim(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && isSpace(s[start])) start++
        while (end > start && isSpace(s[end - 1])) end--
        return if (start == 0 && end == s.length) s else s.substring(start, end)
    }

    fun isBlank(s: String): Boolean {
        for (c in s) if (!isSpace(c)) return false
        return true
    }

    /** JS RegExp `\w` without the `u` flag. */
    fun isWordChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

    fun isAsciiAlpha(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    fun isDigit(c: Char): Boolean = c in '0'..'9'

    fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    fun asciiLower(c: Char): Char = if (c in 'A'..'Z') c + 32 else c

    /** `String.prototype.toLowerCase`. Only used where the result is compared with ASCII. */
    fun lower(s: String): String {
        for (c in s) {
            if (c in 'A'..'Z' || c >= '\u0080') return s.lowercase()
        }
        return s
    }

    /** Case-insensitive ASCII comparison of [s] at [index] with the lower-case literal [lit]. */
    fun regionMatchesLower(s: String, index: Int, lit: String): Boolean {
        if (index < 0 || index + lit.length > s.length) return false
        for (k in lit.indices) if (asciiLower(s[index + k]) != lit[k]) return false
        return true
    }

    /** ECMAScript ToUint16, as applied by `String.fromCharCode`. */
    fun toUint16(d: Double): Char {
        if (d.isNaN() || d.isInfinite()) return '\u0000'
        val t = truncate(d)
        var m = t % 65536.0
        if (m < 0) m += 65536.0
        return m.toInt().toChar()
    }

    /** ECMAScript ToInt32, as applied by the bitwise operators. */
    fun toInt32(d: Double): Int {
        if (d.isNaN() || d.isInfinite()) return 0
        val t = truncate(d)
        var m = t % 4294967296.0
        if (m < 0) m += 4294967296.0
        return m.toLong().toInt()
    }

    private fun truncate(d: Double): Double = if (d < 0) kotlin.math.ceil(d) else kotlin.math.floor(d)

    /**
     * Value of a run of digits in [radix] (10 or 16) as the nearest double, which is what
     * `parseInt` returns for both radixes in V8.
     */
    fun digitsToDouble(digits: String, radix: Int): Double {
        var i = 0
        while (i < digits.length - 1 && digits[i] == '0') i++
        val s = if (i == 0) digits else digits.substring(i)
        if (s.isEmpty()) return Double.NaN
        if (radix == 10) {
            if (s.length > 400) return Double.POSITIVE_INFINITY // at least 1e400
            return if (s.length <= 15) s.toLong().toDouble() else s.toDouble()
        }
        if (s.length <= 15) return s.toLong(16).toDouble()
        // Keep 60 significant bits plus a sticky bit, then scale by an exact power of two.
        var top = s.substring(0, 15).toLong(16)
        for (k in 15 until s.length) {
            if (s[k] != '0') {
                top = top or 1L
                break
            }
        }
        return top.toDouble() * 2.0.pow(4 * (s.length - 15))
    }

    /**
     * UTF-8 bytes the way JS `TextEncoder` and the WHATWG URL parser produce them: a lone
     * surrogate becomes U+FFFD (EF BF BD). Kotlin/JVM's `encodeToByteArray` writes `?` (0x3F)
     * for it instead, so image proxy hashes and percent-encoding would differ on Android.
     */
    fun utf8(s: String): ByteArray {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                i += 2
            } else if (c.isSurrogate()) {
                break
            } else {
                i++
            }
        }
        if (i == s.length) return s.encodeToByteArray()
        val sb = StringBuilder(s.length)
        sb.append(s, 0, i)
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                sb.append(c).append(s[i + 1])
                i += 2
            } else {
                sb.append(if (c.isSurrogate()) '�' else c)
                i++
            }
        }
        return sb.toString().encodeToByteArray()
    }

    /** `String.fromCodePoint` for a valid code point. */
    fun appendCodePoint(sb: StringBuilder, cp: Int) {
        if (cp > 0xFFFF) {
            val v = cp - 0x10000
            sb.append((0xD800 + (v shr 10)).toChar())
            sb.append((0xDC00 + (v and 0x3FF)).toChar())
        } else {
            sb.append(cp.toChar())
        }
    }

    /** Code point at [i] the way `String.prototype.codePointAt` reads it. */
    fun codePointAt(s: String, i: Int): Int {
        val c = s[i]
        if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
            return 0x10000 + ((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
        }
        return c.code
    }

    // ---- from the markdown port ----

    /** `String.prototype.charCodeAt`, with -1 standing in for NaN past either end. */
    fun cc(s: CharSequence, i: Int): Int = if (i >= 0 && i < s.length) s[i].code else -1

    /** The `\s` / `String.prototype.trim` white space set. */
    fun isSpace(c: Int): Boolean = when (c) {
        0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF -> true
        else -> c in 0x2000..0x200A
    }


    /** `String.prototype.slice`: negative indices count from the end, bounds are clamped. */
    fun slice(s: String, start: Int, end: Int = s.length): String {
        val len = s.length
        val from = if (start < 0) maxOf(len + start, 0) else minOf(start, len)
        val to = if (end < 0) maxOf(len + end, 0) else minOf(end, len)
        return if (from >= to) "" else s.substring(from, to)
    }

    /** `String.prototype.substr`. */
    fun substr(s: String, start: Int, length: Int = Int.MAX_VALUE): String {
        val len = s.length
        val from = if (start < 0) maxOf(len + start, 0) else minOf(start, len)
        val count = minOf(maxOf(length, 0), len - from)
        return if (count <= 0) "" else s.substring(from, from + count)
    }

    /** Collapses runs of `\s` into one space, as `str.replace(/\s+/g, ' ')`. */
    fun collapseSpaces(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (isSpace(s[i].code)) {
                while (i < s.length && isSpace(s[i].code)) i++
                sb.append(' ')
            } else {
                sb.append(s[i++])
            }
        }
        return sb.toString()
    }

    /** Splits on runs of `\s`, as `str.split(/\s+/g)`. */
    fun splitSpaces(s: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < s.length) {
            if (isSpace(s[i].code)) {
                out.add(s.substring(start, i))
                while (i < s.length && isSpace(s[i].code)) i++
                start = i
            } else {
                i++
            }
        }
        out.add(s.substring(start))
        return out
    }

    /**
     * `parseInt(str, radix)` for the digit strings the port feeds it (no sign or leading space):
     * parses the longest valid prefix. Values past [CAP] are clamped there, which keeps every
     * comparison against code point limits correct without overflowing.
     */
    fun parseIntPrefix(s: String, radix: Int): Long? {
        var value = 0L
        var digits = 0
        for (ch in s) {
            val d = ch.digitToIntOrNull(radix) ?: break
            if (ch.code > 0x7F) break
            value = minOf(value * radix + d, CAP)
            digits++
        }
        return if (digits == 0) null else value
    }

    private const val CAP = 0x7FFFFFFFFFFFL

    /** `String.fromCodePoint` for valid code points (remarkable's fromCodePoint helper). */
    fun fromCodePoint(c: Int): String = if (c > 0xFFFF) {
        val v = c - 0x10000
        charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
    } else {
        c.toChar().toString()
    }

    private const val HEX = "0123456789ABCDEF"
    private const val URI_UNESCAPED = "-_.!~*'();/?:@&=+$,#"
    private const val URI_RESERVED = ";/?:@&=+$,#"

    /** `encodeURI`. Throws [UriError] on lone surrogates, like JS. */
    fun encodeUri(s: String): String {
        var sb: StringBuilder? = null
        var k = 0
        while (k < s.length) {
            val c = s[k].code
            if ((c in 0x61..0x7A) || (c in 0x41..0x5A) || (c in 0x30..0x39) || URI_UNESCAPED.indexOf(c.toChar()) >= 0) {
                sb?.append(c.toChar())
                k++
                continue
            }
            if (sb == null) sb = StringBuilder(s.length + 16).append(s, 0, k)
            val cp: Int
            if (c in 0xDC00..0xDFFF) throw UriError()
            if (c in 0xD800..0xDBFF) {
                val next = cc(s, k + 1)
                if (next !in 0xDC00..0xDFFF) throw UriError()
                cp = ((c - 0xD800) shl 10) + (next - 0xDC00) + 0x10000
                k += 2
            } else {
                cp = c
                k++
            }
            utf8(cp) { b -> sb.append('%').append(HEX[b shr 4]).append(HEX[b and 0xF]) }
        }
        return sb?.toString() ?: s
    }

    private inline fun utf8(cp: Int, out: (Int) -> Unit) {
        when {
            cp < 0x80 -> out(cp)
            cp < 0x800 -> { out(0xC0 or (cp shr 6)); out(0x80 or (cp and 0x3F)) }
            cp < 0x10000 -> { out(0xE0 or (cp shr 12)); out(0x80 or ((cp shr 6) and 0x3F)); out(0x80 or (cp and 0x3F)) }
            else -> {
                out(0xF0 or (cp shr 18)); out(0x80 or ((cp shr 12) and 0x3F))
                out(0x80 or ((cp shr 6) and 0x3F)); out(0x80 or (cp and 0x3F))
            }
        }
    }

    /** `decodeURI`. Throws [UriError] on malformed escapes, like JS. */
    fun decodeUri(s: String): String = decode(s, URI_RESERVED)

    /** `decodeURIComponent`. Throws [UriError] on malformed escapes, like JS. */
    fun decodeUriComponent(s: String): String = decode(s, "")

    private fun hexByte(s: String, k: Int): Int {
        val hi = s[k + 1].digitToIntOrNull(16)
        val lo = s[k + 2].digitToIntOrNull(16)
        if (hi == null || lo == null || s[k + 1].code > 0x7F || s[k + 2].code > 0x7F) throw UriError()
        return (hi shl 4) or lo
    }

    private fun decode(s: String, reserved: String): String {
        if (s.indexOf('%') < 0) return s
        val sb = StringBuilder(s.length)
        var k = 0
        val len = s.length
        while (k < len) {
            val c = s[k]
            if (c != '%') {
                sb.append(c)
                k++
                continue
            }
            val start = k
            if (k + 2 >= len) throw UriError()
            val b = hexByte(s, k)
            k += 2
            if (b and 0x80 == 0) {
                if (reserved.indexOf(b.toChar()) >= 0) sb.append(s, start, k + 1) else sb.append(b.toChar())
            } else {
                val n = when {
                    b and 0xE0 == 0xC0 -> 2
                    b and 0xF0 == 0xE0 -> 3
                    b and 0xF8 == 0xF0 -> 4
                    else -> throw UriError()
                }
                var cp = b and (0xFF shr (n + 1))
                if (k + 3 * (n - 1) >= len) throw UriError()
                for (j in 1 until n) {
                    k++
                    if (s[k] != '%') throw UriError()
                    val cb = hexByte(s, k)
                    if (cb and 0xC0 != 0x80) throw UriError()
                    k += 2
                    cp = (cp shl 6) or (cb and 0x3F)
                }
                val min = when (n) { 2 -> 0x80; 3 -> 0x800; else -> 0x10000 }
                if (cp < min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) throw UriError()
                sb.append(fromCodePoint(cp))
            }
            k++
        }
        return sb.toString()
    }

    /**
     * `Number(digits).toString()` for a non-empty string of ASCII digits (list start numbers):
     * the shortest digit string that round-trips to the double (Number::toString), plain below
     * 1e21 and in exponent form above.
     */
    fun numberToString(digits: String): String {
        val d = digits.trimStart('0').ifEmpty { "0" }
        if (d.length <= 15) return d
        val value = d.toDouble()
        if (value.isInfinite()) return "Infinity"
        val exact = exactIntegerDigits(value)
        val n = exact.length
        for (k in 1..n) {
            // the two k-digit candidates around the exact value
            val floor = exact.substring(0, k)
            val tail = exact.substring(k)
            val ceil = if (tail.all { it == '0' }) floor else incrementDecimal(floor)
            val floorFull = floor + "0".repeat(n - k)
            val ceilFull = ceil + "0".repeat(n - k)
            val floorOk = floorFull.toDouble() == value
            val ceilOk = ceilFull.toDouble() == value
            if (!floorOk && !ceilOk) continue
            val chosen = when {
                floorOk && ceilOk && floor != ceil -> {
                    // closer to the exact value; on a tie the even one
                    val half = "5" + "0".repeat(tail.length - 1)
                    val cmp = tail.compareTo(half)
                    if (cmp < 0) floorFull else if (cmp > 0) ceilFull else if ((floor.last() - '0') % 2 == 0) floorFull else ceilFull
                }
                floorOk -> floorFull
                else -> ceilFull
            }
            if (value < 1e21) return chosen
            val mant = chosen.trimEnd('0').ifEmpty { "0" }
            return (if (mant.length == 1) mant else mant[0] + "." + mant.substring(1)) + "e+" + (chosen.length - 1)
        }
        return exact
    }

    /** Exact decimal digits of a non-negative integral double. */
    fun exactIntegerDigits(value: Double): String {
        val bits = value.toRawBits()
        val exp = ((bits ushr 52) and 0x7FF).toInt() - 1075
        val mantissa = (bits and 0xFFFFFFFFFFFFFL) or 0x10000000000000L
        if (exp <= 0) return (mantissa shr -exp).toString()
        // mantissa * 2^exp in base 10, little-endian digits
        val digitsLe = mantissa.toString().reversed().map { it - '0' }.toMutableList()
        repeat(exp) {
            var carry = 0
            for (i in digitsLe.indices) {
                val v = digitsLe[i] * 2 + carry
                digitsLe[i] = v % 10
                carry = v / 10
            }
            if (carry > 0) digitsLe.add(carry)
        }
        return digitsLe.reversed().joinToString("")
    }

    private fun incrementDecimal(s: String): String {
        val chars = s.toCharArray()
        var i = chars.size - 1
        while (i >= 0) {
            if (chars[i] == '9') { chars[i] = '0'; i-- } else { chars[i] = chars[i] + 1; return chars.concatToString() }
        }
        return "1" + chars.concatToString()
    }

    // ---- from the transforms ----


    /** Characters the JS regex `.` does not match. */
    fun isLineTerminator(c: Char): Boolean = c == '\n' || c == '\r' || c == ' ' || c == ' '


    /**
     * `String.prototype.toLowerCase`. Only U+0130 changes length (it becomes "i" + U+0307); every
     * other code unit maps one to one. Final sigma is not special-cased: callers only compare the
     * result with ASCII or use its length.
     */
    fun toLowerCase(s: String): String {
        var plain = true
        for (c in s) {
            if (c in 'A'..'Z' || c >= '\u0080') {
                plain = false
                break
            }
        }
        if (plain) return s
        val sb = StringBuilder(s.length + 4)
        for (c in s) {
            when {
                c in 'A'..'Z' -> sb.append(c + 32)
                c < '\u0080' -> sb.append(c)
                c == 'İ' -> sb.append("i̇")
                c.isSurrogate() -> sb.append(c)
                else -> sb.append(c.lowercaseChar())
            }
        }
        return lowerAstral(sb.toString())
    }

    /** Lower-cases astral letters (Deseret and friends) the way JS does, keeping the length. */
    private fun lowerAstral(s: String): String {
        var i = s.indexOfFirst { it.isHighSurrogate() }
        if (i < 0) return s
        val sb = StringBuilder(s.length)
        sb.append(s, 0, i)
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                val pair = s.substring(i, i + 2)
                val low = pair.lowercase()
                sb.append(if (low.length == 2) low else pair)
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }


    /** ASCII case-insensitive `startsWith` of a lower-case literal (what a JS `/i` ASCII pattern does). */
    fun startsWithCi(s: String, lit: String, at: Int = 0): Boolean = regionMatchesLower(s, at, lit)

    /** ASCII case-insensitive `indexOf` of a lower-case literal. */
    fun indexOfCi(s: String, lit: String, from: Int = 0): Int {
        if (lit.isEmpty()) return from
        val first = lit[0]
        var i = maxOf(from, 0)
        val last = s.length - lit.length
        while (i <= last) {
            if (asciiLower(s[i]) == first && regionMatchesLower(s, i, lit)) return i
            i++
        }
        return -1
    }

    /** `s.replace(lit, rep)` with a string pattern: first occurrence only. */
    fun replaceFirst(s: String, lit: String, rep: String): String {
        val i = s.indexOf(lit)
        return if (i < 0) s else s.substring(0, i) + rep + s.substring(i + lit.length)
    }

    /**
     * `s.replace(lit, rep)` with a string pattern and a string replacement: first occurrence, and
     * `rep` goes through GetSubstitution (`$$`, `$&`, `` $` `` and `$'` are expanded; there are no
     * capture groups, so `$1` and `$<` stay literal).
     */
    fun replaceFirstSubstituted(s: String, lit: String, rep: String): String {
        val i = s.indexOf(lit)
        if (i < 0) return s
        if (rep.indexOf('$') < 0) return s.substring(0, i) + rep + s.substring(i + lit.length)
        val out = StringBuilder(s.length + rep.length)
        out.append(s, 0, i)
        var k = 0
        while (k < rep.length) {
            val c = rep[k]
            if (c == '$' && k + 1 < rep.length) {
                when (rep[k + 1]) {
                    '$' -> { out.append('$'); k += 2; continue }
                    '&' -> { out.append(lit); k += 2; continue }
                    '`' -> { out.append(s, 0, i); k += 2; continue }
                    '\'' -> { out.append(s, i + lit.length, s.length); k += 2; continue }
                }
            }
            out.append(c)
            k++
        }
        out.append(s, i + lit.length, s.length)
        return out.toString()
    }

    /** `s.split(a).join(b)`. */
    fun replaceAll(s: String, lit: String, rep: String): String {
        if (lit.isEmpty()) {
            // JS: "abc".split("") gives code units; join puts rep between them.
            if (s.isEmpty()) return s
            val sb = StringBuilder()
            for (k in s.indices) {
                if (k > 0) sb.append(rep)
                sb.append(s[k])
            }
            return sb.toString()
        }
        return s.replace(lit, rep)
    }

    /** `'' + parseInt(s)` (radix inferred: a "0x" prefix means hex). */
    fun parseIntToString(s: String): String {
        var i = 0
        while (i < s.length && isSpace(s[i])) i++
        var neg = false
        if (i < s.length && (s[i] == '-' || s[i] == '+')) {
            neg = s[i] == '-'
            i++
        }
        var radix = 10
        if (i + 1 < s.length && s[i] == '0' && (s[i + 1] == 'x' || s[i + 1] == 'X')) {
            radix = 16
            i += 2
        }
        val start = i
        while (i < s.length && (if (radix == 10) isDigit(s[i]) else isHexDigit(s[i]))) i++
        if (i == start) return "NaN"
        val digits = s.substring(start, i)
        val text = if (radix == 10) {
            numberToString(digits)
        } else {
            val v = digitsToDouble(digits, 16)
            if (v.isInfinite()) "Infinity" else numberToString(exactIntegerDigits(v))
        }
        return if (neg && text != "0") "-$text" else text
    }
}

/** JS `URIError`. */
internal class UriError : RuntimeException("URI malformed")
