package com.ecency.core.render.url

import com.ecency.core.render.js.Js

/** WHATWG percent-encode sets, as code-point predicates over ASCII; every non-ASCII code point is encoded. */
internal object PercentEncode {
    val C0_CONTROL: (Int) -> Boolean = { it < 0x20 || it > 0x7E }
    val FRAGMENT: (Int) -> Boolean = { C0_CONTROL(it) || it == ' '.code || it == '"'.code || it == '<'.code || it == '>'.code || it == '`'.code }
    val QUERY: (Int) -> Boolean = { C0_CONTROL(it) || it == ' '.code || it == '"'.code || it == '#'.code || it == '<'.code || it == '>'.code }
    val SPECIAL_QUERY: (Int) -> Boolean = { QUERY(it) || it == '\''.code }
    val PATH: (Int) -> Boolean = { QUERY(it) || it == '?'.code || it == '`'.code || it == '{'.code || it == '}'.code }
    val USERINFO: (Int) -> Boolean = { PATH(it) || it.toChar() in "/:;=@[\\]^|" }
    val COMPONENT: (Int) -> Boolean = { USERINFO(it) || it.toChar() in "$%&+," }
    val FORM: (Int) -> Boolean = { COMPONENT(it) || it.toChar() in "!'()~" }

    private const val HEX = "0123456789ABCDEF"

    fun encode(s: String, set: (Int) -> Boolean, spaceAsPlus: Boolean = false): String {
        var needs = false
        for (c in s) if (set(c.code) || c.isSurrogate()) { needs = true; break }
        if (!needs) return s
        val sb = StringBuilder(s.length + 16)
        for (b in Js.utf8(s)) {
            val v = b.toInt() and 0xFF
            when {
                spaceAsPlus && v == ' '.code -> sb.append('+')
                set(v) -> sb.append('%').append(HEX[v shr 4]).append(HEX[v and 15])
                else -> sb.append(v.toChar())
            }
        }
        return sb.toString()
    }

    /** Percent-decodes to bytes; malformed escapes stay literal, as in the spec. */
    fun decodeToBytes(s: String): ByteArray {
        val bytes = Js.utf8(s)
        val out = ByteArray(bytes.size)
        var n = 0
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i]
            if (b == '%'.code.toByte() && i + 2 < bytes.size) {
                val hi = hexValue(bytes[i + 1])
                val lo = hexValue(bytes[i + 2])
                if (hi >= 0 && lo >= 0) {
                    out[n++] = ((hi shl 4) or lo).toByte()
                    i += 3
                    continue
                }
            }
            out[n++] = b
            i++
        }
        return out.copyOf(n)
    }

    fun decode(s: String): String = if ('%' !in s) s else decodeToBytes(s).decodeToString()

    private fun hexValue(b: Byte): Int = when (val c = b.toInt().toChar()) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}

/** application/x-www-form-urlencoded, as used by `URLSearchParams`. */
internal object FormUrlEncoded {
    fun parse(input: String): List<Pair<String, String>> {
        val q = if (input.startsWith("?")) input.substring(1) else input
        if (q.isEmpty()) return emptyList()
        return q.split('&').filter { it.isNotEmpty() }.map { pair ->
            val eq = pair.indexOf('=')
            val name = if (eq < 0) pair else pair.substring(0, eq)
            val value = if (eq < 0) "" else pair.substring(eq + 1)
            PercentEncode.decode(name.replace('+', ' ')) to PercentEncode.decode(value.replace('+', ' '))
        }
    }

    fun serialize(params: List<Pair<String, String>>): String = params.joinToString("&") { (k, v) ->
        PercentEncode.encode(k, PercentEncode.FORM, spaceAsPlus = true) + "=" + PercentEncode.encode(v, PercentEncode.FORM, spaceAsPlus = true)
    }
}

/** Host parsing: IPv6, IPv4 (including the legacy numeric forms browsers accept), domains and opaque hosts. */
internal object Host {
    private const val FORBIDDEN_HOST = "\u0000\t\n\r #/:<>?@[\\]^|"

    fun parse(input: String, special: Boolean): String? {
        if (input.startsWith("[")) {
            if (!input.endsWith("]")) return null
            return Ipv6.parse(input.substring(1, input.length - 1))?.let { "[$it]" }
        }
        if (!special) {
            if (input.any { it in FORBIDDEN_HOST }) return null
            return PercentEncode.encode(input, PercentEncode.C0_CONTROL)
        }
        val domain = PercentEncode.decodeToBytes(input).decodeToString()
        val ascii = Idna.toAscii(domain) ?: return null
        if (ascii.isEmpty()) return null
        if (ascii.any { it in FORBIDDEN_HOST || it < ' ' || it == '%' || it == '\u007F' }) return null
        if (endsInNumber(ascii)) return Ipv4.parse(ascii)
        return ascii
    }

    private fun endsInNumber(host: String): Boolean {
        val labels = host.split('.').toMutableList()
        if (labels.last().isEmpty()) {
            if (labels.size == 1) return false
            labels.removeAt(labels.lastIndex)
        }
        val last = labels.last()
        if (last.isNotEmpty() && last.all { it in '0'..'9' }) return true
        return Ipv4.parseNumber(last) != null
    }
}

internal object Ipv4 {
    fun parse(input: String): String? {
        val parts = input.split('.').toMutableList()
        if (parts.last().isEmpty() && parts.size > 1) parts.removeAt(parts.lastIndex)
        if (parts.size > 4) return null
        val numbers = parts.map { parseNumber(it) ?: return null }
        if (numbers.dropLast(1).any { it > 255 }) return null
        if (numbers.last() >= 1L shl (8 * (5 - numbers.size))) return null
        var ipv4 = numbers.last()
        for ((i, n) in numbers.dropLast(1).withIndex()) ipv4 += n shl (8 * (3 - i))
        return (0..3).joinToString(".") { ((ipv4 shr (8 * (3 - it))) and 0xFF).toString() }
    }

    fun parseNumber(s: String): Long? {
        if (s.isEmpty()) return null
        var input = s
        var radix = 10
        if (input.length >= 2 && (input.startsWith("0x") || input.startsWith("0X"))) {
            input = input.substring(2)
            radix = 16
        } else if (input.length >= 2 && input.startsWith("0")) {
            input = input.substring(1)
            radix = 8
        }
        if (input.isEmpty()) return 0
        val valid = when (radix) {
            16 -> input.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
            8 -> input.all { it in '0'..'7' }
            else -> input.all { it in '0'..'9' }
        }
        if (!valid) return null
        // Anything above 2^32 fails later; clamp so huge inputs do not overflow.
        return if (input.trimStart('0').length > 12) Long.MAX_VALUE / 2 else input.toLong(radix)
    }
}

internal object Ipv6 {
    fun parse(input: String): String? {
        val address = IntArray(8)
        var pieceIndex = 0
        var compress = -1
        var p = 0
        val c = { i: Int -> if (i < input.length) input[i] else '\u0000' }
        if (c(p) == ':') {
            if (c(p + 1) != ':') return null
            p += 2
            pieceIndex++
            compress = pieceIndex
        }
        while (p < input.length) {
            if (pieceIndex == 8) return null
            if (c(p) == ':') {
                if (compress != -1) return null
                p++
                pieceIndex++
                compress = pieceIndex
                continue
            }
            var value = 0
            var length = 0
            while (length < 4 && p < input.length && hex(c(p)) >= 0) {
                value = value * 16 + hex(c(p))
                p++
                length++
            }
            if (c(p) == '.') {
                if (length == 0) return null
                p -= length
                if (pieceIndex > 6) return null
                var numbersSeen = 0
                while (p < input.length) {
                    var ipv4Piece = -1
                    if (numbersSeen > 0) {
                        if (c(p) == '.' && numbersSeen < 4) p++ else return null
                    }
                    if (c(p) !in '0'..'9') return null
                    while (c(p) in '0'..'9') {
                        val number = c(p) - '0'
                        ipv4Piece = when (ipv4Piece) {
                            -1 -> number
                            0 -> return null
                            else -> ipv4Piece * 10 + number
                        }
                        if (ipv4Piece > 255) return null
                        p++
                    }
                    address[pieceIndex] = address[pieceIndex] * 0x100 + ipv4Piece
                    numbersSeen++
                    if (numbersSeen == 2 || numbersSeen == 4) pieceIndex++
                }
                if (numbersSeen != 4) return null
                break
            } else if (c(p) == ':') {
                p++
                if (p >= input.length) return null
            } else if (p < input.length) {
                return null
            }
            address[pieceIndex] = value
            pieceIndex++
        }
        if (compress != -1) {
            var swaps = pieceIndex - compress
            pieceIndex = 7
            while (pieceIndex != 0 && swaps > 0) {
                val tmp = address[compress + swaps - 1]
                address[compress + swaps - 1] = address[pieceIndex]
                address[pieceIndex] = tmp
                pieceIndex--
                swaps--
            }
        } else if (pieceIndex != 8) {
            return null
        }
        return serialize(address)
    }

    private fun serialize(a: IntArray): String {
        // Compress the first longest run (length > 1) of zero pieces.
        var bestStart = -1
        var bestLen = 1
        var i = 0
        while (i < 8) {
            if (a[i] == 0) {
                var j = i
                while (j < 8 && a[j] == 0) j++
                if (j - i > bestLen) { bestStart = i; bestLen = j - i }
                i = j
            } else {
                i++
            }
        }
        val sb = StringBuilder()
        var ignore0 = false
        for (k in 0 until 8) {
            if (ignore0 && a[k] == 0) continue
            ignore0 = false
            if (bestStart == k) {
                sb.append(if (k == 0) "::" else ":")
                ignore0 = true
                continue
            }
            sb.append(a[k].toString(16))
            if (k != 7) sb.append(':')
        }
        return sb.toString()
    }

    private fun hex(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'a'..'f' -> ch - 'a' + 10
        in 'A'..'F' -> ch - 'A' + 10
        else -> -1
    }
}
