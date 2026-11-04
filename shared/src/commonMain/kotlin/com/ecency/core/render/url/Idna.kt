package com.ecency.core.render.url

/**
 * Domain to ASCII (UTS 46 with CheckHyphens off, CheckBidi and CheckJoiners on, as the URL
 * standard runs it). A port of Ada's `ada::idna::to_ascii`, the code inside the Node that
 * produces our URL goldens, over Ada's own tables ([IdnaTables]): the full IDNA mapping table,
 * NFC normalization, Punycode, and label validity. Ada's reading of the validity rules is kept on
 * purpose, including where it is looser than RFC 5893 (bidi rules apply per label and only to
 * labels holding an R, AL or AN character), because the point is to agree with the oracle.
 */
internal object Idna {
    /** The ASCII form of [domain], or null where Node's URL parser fails. */
    fun toAscii(domain: String): String? {
        if (domain.all { it.code < 0x80 }) return fromAsciiToAscii(domain)
        val cps = codePoints(domain) ?: return null
        val mapped = map(cps) ?: return null
        val normalized = Normalizer.nfc(mapped)
        // Split on '.' keeping empty labels, as Ada does (a trailing dot stays a trailing dot).
        val labels = ArrayList<List<Int>>()
        var start = 0
        for (i in normalized.indices) {
            if (normalized[i] == '.'.code) {
                labels.add(normalized.subList(start, i))
                start = i + 1
            }
        }
        labels.add(normalized.subList(start, normalized.size))
        val out = StringBuilder()
        for ((index, label) in labels.withIndex()) {
            if (index > 0) out.append('.')
            when {
                label.isEmpty() -> {}
                label.size >= 4 && label[0] == 'x'.code && label[1] == 'n'.code && label[2] == '-'.code && label[3] == '-'.code -> {
                    if (label.any { it >= 0x80 }) return null
                    val ascii = asciiString(label)
                    if (!isValidAceLabel(ascii.substring(4))) return null
                    out.append(ascii)
                }
                label.all { it < 0x80 } -> out.append(asciiString(label))
                else -> {
                    if (!isLabelValid(label)) return null
                    out.append("xn--").append(Punycode.encode(label) ?: return null)
                }
            }
        }
        return out.toString()
    }

    /** Ada `from_ascii_to_ascii`: lower-case, then validate only the `xn--` labels. */
    private fun fromAsciiToAscii(domain: String): String? {
        val mapped = buildString(domain.length) { for (c in domain) append(if (c in 'A'..'Z') c + 32 else c) }
        for (label in mapped.split('.')) {
            if (label.startsWith("xn--") && !isValidAceLabel(label.substring(4))) return null
        }
        return mapped
    }

    /** An `xn--` label: decodes, is unchanged by mapping and NFC, is non-empty and valid. */
    private fun isValidAceLabel(punycode: String): Boolean {
        val decoded = Punycode.decode(punycode) ?: return false
        val mapped = map(decoded) ?: return false
        if (mapped != decoded) return false
        if (Normalizer.nfc(mapped) != mapped) return false
        if (mapped.isEmpty()) return false
        return isLabelValid(mapped)
    }

    private fun asciiString(cps: List<Int>) = buildString(cps.size) { for (c in cps) append(c.toChar()) }

    /** Code points of [s]; null on a lone surrogate (invalid UTF-8 never reaches here intact). */
    private fun codePoints(s: String): List<Int>? {
        val out = ArrayList<Int>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                out.add(((c.code - 0xD800) shl 10) + (s[i + 1].code - 0xDC00) + 0x10000)
                i += 2
            } else {
                if (c.isSurrogate()) return null
                out.add(c.code)
                i++
            }
        }
        return out
    }

    private val mappingKeys by lazy { IdnaData.unpack(IdnaTables.MAPPING_KEYS) }
    private val mappingDescriptors by lazy { IdnaData.unpack(IdnaTables.MAPPING_DESCRIPTORS) }
    private val mappings by lazy { IdnaData.unpack(IdnaTables.MAPPINGS) }

    /** UTS 46 mapping step; null when a code point is disallowed. */
    fun map(input: List<Int>): List<Int>? {
        val out = ArrayList<Int>(input.size)
        for (x in input) {
            val descriptor = mappingDescriptors[rangeIndex(x)]
            when (descriptor and 0xFF) {
                0 -> {}
                1 -> out.add(x)
                2 -> return null
                else -> {
                    val count = descriptor ushr 24
                    val index = (descriptor ushr 8) and 0xFFFF
                    for (k in index until index + count) out.add(mappings[k])
                }
            }
        }
        return out
    }

    private fun rangeIndex(key: Int): Int {
        var low = 0
        var high = mappingKeys.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val v = mappingKeys[mid]
            when {
                v < key -> low = mid + 1
                v > key -> high = mid - 1
                else -> return mid
            }
        }
        return if (low == 0) 0 else low - 1
    }

    // ---- label validity (Ada is_label_valid) ----------------------------------------------

    private class Ranges(encoded: String, withClass: Boolean) {
        val starts: IntArray
        val ends: IntArray
        val classes: CharArray

        init {
            val items = encoded.split(',')
            starts = IntArray(items.size)
            ends = IntArray(items.size)
            classes = CharArray(items.size)
            for ((i, raw) in items.withIndex()) {
                val item = if (withClass) raw.dropLast(1) else raw
                if (withClass) classes[i] = raw.last()
                val dash = item.indexOf('-')
                starts[i] = (if (dash < 0) item else item.substring(0, dash)).toInt(16)
                ends[i] = (if (dash < 0) item else item.substring(dash + 1)).toInt(16)
            }
        }

        fun find(cp: Int): Int {
            var lo = 0
            var hi = ends.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (ends[mid] < cp) lo = mid + 1 else hi = mid - 1
            }
            return if (lo < ends.size && cp >= starts[lo]) lo else -1
        }
    }

    private val bidi by lazy { Ranges(IdnaTables.BIDI, withClass = true) }
    private val leadingCombining by lazy { Ranges(IdnaTables.LEADING_COMBINING, withClass = false) }
    private val viramas by lazy { IdnaTables.VIRAMA.sortedArray() }
    private val joinR by lazy { IdnaTables.JOINING_R.sortedArray() }
    private val joinL by lazy { IdnaTables.JOINING_L.sortedArray() }
    private val joinD by lazy { IdnaTables.JOINING_D.sortedArray() }

    /** Bidi class letter (see [IdnaTables.BIDI]); 'x' for classes the rules never accept. */
    private fun direction(cp: Int): Char {
        val i = bidi.find(cp)
        return if (i < 0) 'x' else bidi.classes[i]
    }

    /** Binary search in a sorted array (common code has no IntArray.binarySearch). */
    private fun IntArray.has(cp: Int): Boolean {
        var lo = 0
        var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = this[mid]
            when {
                v < cp -> lo = mid + 1
                v > cp -> hi = mid - 1
                else -> return true
            }
        }
        return false
    }

    private const val LTR_ALLOWED = "LESCTOBM"
    private const val RTL_ALLOWED = "RANESCTOBM"
    private const val RTL_END = "RANE"

    fun isLabelValid(label: List<Int>): Boolean {
        if (label.isEmpty()) return true
        if (leadingCombining.find(label[0]) >= 0) return false

        // CheckJoiners. As in Ada, the first joiner decides for the whole label.
        for (i in label.indices) {
            val c = label[i]
            if (c == 0x200C) {
                if (i > 0 && viramas.has(label[i - 1])) return true
                if (i == 0 || i + 1 >= label.size) return false
                val before = (0 until i).any { joinL.has(label[it]) || joinD.has(label[it]) }
                val after = (i + 1 until label.size).any { joinR.has(label[it]) || joinD.has(label[it]) }
                return before && after
            } else if (c == 0x200D) {
                return i > 0 && viramas.has(label[i - 1])
            }
        }

        // CheckBidi, for labels holding an R, AL or AN character.
        var last = label.size - 1
        while (last >= 0 && direction(label[last]) == 'M') last--
        if (last < 0) return false
        if (label.none { val d = direction(it); d == 'R' || d == 'A' || d == 'N' }) return true
        if (direction(label[0]) == 'L') {
            // Ada stops one short of the last character, so an LTR label's ending is not checked.
            for (i in 0 until last) if (direction(label[i]) !in LTR_ALLOWED) return false
            return true
        }
        var hasEn = false
        var hasAn = false
        for (i in 0..last) {
            val d = direction(label[i])
            if (d == 'E') hasEn = true
            if (d == 'N') hasAn = true
            if (hasEn && hasAn) return false
            if (d !in RTL_ALLOWED) return false
            if (i == last && d !in RTL_END) return false
        }
        return true
    }
}

/** NFC as Ada computes it: one-level canonical decomposition, mark reordering, composition. */
internal object Normalizer {
    private const val S_BASE = 0xAC00
    private const val T_BASE = 0x11A7
    private const val V_BASE = 0x1161
    private const val L_BASE = 0x1100
    private const val L_COUNT = 19
    private const val V_COUNT = 21
    private const val T_COUNT = 28
    private const val N_COUNT = V_COUNT * T_COUNT
    private const val S_COUNT = L_COUNT * N_COUNT

    private val decompositionIndex by lazy { IdnaData.unpack(IdnaTables.DECOMPOSITION_INDEX) }
    private val decompositionBlock by lazy { IdnaData.unpack(IdnaTables.DECOMPOSITION_BLOCK) }
    private val decompositionData by lazy { IdnaData.unpack(IdnaTables.DECOMPOSITION_DATA) }
    private val cccIndex by lazy { IdnaData.unpack(IdnaTables.CCC_INDEX) }
    private val cccBlock by lazy { IdnaData.unpack(IdnaTables.CCC_BLOCK) }
    private val compositionIndex by lazy { IdnaData.unpack(IdnaTables.COMPOSITION_INDEX) }
    private val compositionBlock by lazy { IdnaData.unpack(IdnaTables.COMPOSITION_BLOCK) }
    private val compositionData by lazy { IdnaData.unpack(IdnaTables.COMPOSITION_DATA) }

    fun nfc(input: List<Int>): List<Int> {
        val decomposed = decompose(input)
        sortMarks(decomposed)
        return compose(decomposed)
    }

    private fun ccc(c: Int): Int = if (c in 0 until 0x110000) cccBlock[cccIndex[c shr 8] * 256 + (c and 0xFF)] else 0

    private fun decompose(input: List<Int>): IntArray {
        val out = ArrayList<Int>(input.size + 4)
        for (c in input) {
            if (c >= S_BASE && c < S_BASE + S_COUNT) {
                val s = c - S_BASE
                out.add(L_BASE + s / N_COUNT)
                out.add(V_BASE + (s % N_COUNT) / T_COUNT)
                if (s % T_COUNT != 0) out.add(T_BASE + s % T_COUNT)
            } else if (c in 0 until 0x110000) {
                val at = decompositionIndex[c shr 8] * 257 + (c and 0xFF)
                val d0 = decompositionBlock[at]
                var length = (decompositionBlock[at + 1] shr 2) - (d0 shr 2)
                if (length > 0 && (d0 and 1) != 0) length = 0
                if (length > 0) {
                    for (k in 0 until length) out.add(decompositionData[(d0 shr 2) + k])
                } else {
                    out.add(c)
                }
            } else {
                out.add(c)
            }
        }
        return out.toIntArray()
    }

    private fun sortMarks(input: IntArray) {
        for (idx in 1 until input.size) {
            val c = ccc(input[idx])
            if (c == 0) continue
            val current = input[idx]
            var back = idx
            while (back != 0 && ccc(input[back - 1]) > c) {
                input[back] = input[back - 1]
                back--
            }
            input[back] = current
        }
    }

    private fun compose(input: IntArray): List<Int> {
        val size = input.size
        var inputCount = 0
        var compositionCount = 0
        while (inputCount < size) {
            input[compositionCount] = input[inputCount]
            val c = input[inputCount]
            if (c >= L_BASE && c < L_BASE + L_COUNT) {
                if (inputCount + 1 < size && input[inputCount + 1] >= V_BASE && input[inputCount + 1] < V_BASE + V_COUNT) {
                    input[compositionCount] = S_BASE + ((c - L_BASE) * V_COUNT + input[inputCount + 1] - V_BASE) * T_COUNT
                    inputCount++
                    if (inputCount + 1 < size && input[inputCount + 1] > T_BASE && input[inputCount + 1] < T_BASE + T_COUNT) {
                        input[compositionCount] += input[++inputCount] - T_BASE
                    }
                }
            } else if (c >= S_BASE && c < S_BASE + S_COUNT) {
                if ((c - S_BASE) % T_COUNT != 0 && inputCount + 1 < size &&
                    input[inputCount + 1] > T_BASE && input[inputCount + 1] < T_BASE + T_COUNT
                ) {
                    input[compositionCount] += input[++inputCount] - T_BASE
                }
            } else if (c in 0 until 0x110000) {
                var at = compositionIndex[c shr 8] * 257 + (c and 0xFF)
                val initial = compositionCount
                var previousCcc = -1
                while (inputCount + 1 < size) {
                    val next = input[inputCount + 1]
                    val nextCcc = ccc(next)
                    if (compositionBlock[at + 1] != compositionBlock[at] && previousCcc < nextCcc) {
                        var left = compositionBlock[at]
                        var right = compositionBlock[at + 1]
                        while (left + 2 < right) {
                            val middle = left + (((right - left) shr 1) and 1.inv())
                            if (compositionData[middle] <= next) left = middle
                            if (compositionData[middle] >= next) right = middle
                        }
                        if (compositionData[left] == next) {
                            val composed = compositionData[left + 1]
                            input[initial] = composed
                            at = compositionIndex[composed shr 8] * 257 + (composed and 0xFF)
                            inputCount++
                            continue
                        }
                    }
                    if (nextCcc == 0) break
                    previousCcc = nextCcc
                    input[++compositionCount] = next
                    inputCount++
                }
            }
            inputCount++
            compositionCount++
        }
        return input.copyOf(minOf(compositionCount, size)).asList()
    }
}

/** Ada's Punycode (RFC 3492) with its overflow and range checks. */
internal object Punycode {
    private const val BASE = 36
    private const val TMIN = 1
    private const val TMAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 128
    private const val MAX = 0x7fffffffL

    private fun adapt(deltaIn: Long, numPoints: Int, first: Boolean): Int {
        var delta = if (first) deltaIn / DAMP else deltaIn / 2
        delta += delta / numPoints
        var k = 0
        while (delta > ((BASE - TMIN) * TMAX) / 2) {
            delta /= BASE - TMIN
            k += BASE
        }
        return (k + (BASE - TMIN + 1) * delta / (delta + SKEW)).toInt()
    }

    private fun digitValue(c: Char): Int = when (c) {
        in 'a'..'z' -> c - 'a'
        in '0'..'9' -> c - '0' + 26
        else -> -1
    }

    private fun digitChar(d: Int): Char = if (d < 26) 'a' + d else '0' + (d - 26)

    /** Ada `punycode_to_utf32`; null on malformed input. */
    fun decode(input: String): List<Int>? {
        val out = ArrayList<Int>(input.length)
        var n = INITIAL_N.toLong()
        var i = 0L
        var bias = INITIAL_BIAS
        var p = 0
        val endOfAscii = input.lastIndexOf('-')
        if (endOfAscii >= 0) {
            for (k in 0 until endOfAscii) {
                if (input[k].code >= 0x80) return null
                out.add(input[k].code)
            }
            p = endOfAscii + 1
        }
        while (p < input.length) {
            val oldi = i
            var w = 1L
            var k = BASE
            while (true) {
                if (p >= input.length) return null
                val digit = digitValue(input[p++])
                if (digit < 0) return null
                if (digit > (MAX - i) / w) return null
                i += digit * w
                val t = if (k <= bias) TMIN else if (k >= bias + TMAX) TMAX else k - bias
                if (digit < t) break
                if (w > MAX / (BASE - t)) return null
                w *= BASE - t
                k += BASE
            }
            val written = out.size + 1
            bias = adapt(i - oldi, written, oldi == 0L)
            if (i / written > MAX - n) return null
            n += i / written
            i %= written
            if (n < 0x80) return null
            out.add(i.toInt(), n.toInt())
            i++
        }
        return out
    }

    /** Ada `utf32_to_punycode`; null when a code point cannot be encoded. */
    fun encode(input: List<Int>): String? {
        val out = StringBuilder()
        var n = INITIAL_N.toLong()
        var d = 0L
        var bias = INITIAL_BIAS
        var h = 0
        for (c in input) {
            if (c < 0x80) {
                h++
                out.append(c.toChar())
            }
            if (c > 0x10FFFF || (c in 0xD880 until 0xE000)) return null
        }
        val b = h
        if (b > 0) out.append('-')
        while (h < input.size) {
            var m = 0x10FFFFL
            for (c in input) if (c >= n && c < m) m = c.toLong()
            if ((m - n) > (MAX - d) / (h + 1)) return null
            d += (m - n) * (h + 1)
            n = m
            for (c in input) {
                if (c < n) {
                    if (d == MAX) return null
                    d++
                }
                if (c.toLong() == n) {
                    var q = d
                    var k = BASE
                    while (true) {
                        val t = if (k <= bias) TMIN else if (k >= bias + TMAX) TMAX else k - bias
                        if (q < t) break
                        out.append(digitChar((t + (q - t) % (BASE - t)).toInt()))
                        q = (q - t) / (BASE - t)
                        k += BASE
                    }
                    out.append(digitChar(q.toInt()))
                    bias = adapt(d, h + 1, h == b)
                    d = 0
                    h++
                }
            }
            d++
            n++
        }
        return out.toString()
    }
}

/** Decoder for the packed tables in [IdnaTables]: base-36 deltas, `delta*run` for repeats. */
internal object IdnaData {
    fun unpack(parts: Array<String>): IntArray {
        val values = ArrayList<Int>()
        var prev = 0
        for (token in parts.joinToString("").split(',')) {
            val star = token.indexOf('*')
            val delta = (if (star < 0) token else token.substring(0, star)).toInt(36)
            val run = if (star < 0) 1 else token.substring(star + 1).toInt(36)
            repeat(run) {
                prev += delta
                values.add(prev)
            }
        }
        return values.toIntArray()
    }
}
