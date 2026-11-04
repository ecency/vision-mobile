package com.ecency.core.api.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A JavaScript value as `JSON.parse` builds it, for code that must reproduce what the RN app's JS writes
 * byte for byte (`JSON.stringify` of objects it merged). Objects keep JS property order: array-index keys
 * ascending first, then the other keys in creation order; a key set to `undefined` keeps its place but is
 * left out of [stringify], as in JS.
 */
sealed class JsValue {
    data class Str(val value: String) : JsValue()
    data class Num(val value: Double) : JsValue()
    data class Bool(val value: Boolean) : JsValue()
    data object Null : JsValue()
    data object Undefined : JsValue()
    data class Arr(val items: List<JsValue>) : JsValue()

    /** A plain object. Mutable, like a JS object; copy with [copy] before changing a shared one. */
    class Obj(entries: Map<String, JsValue> = emptyMap()) : JsValue() {
        private val map = LinkedHashMap(entries)

        operator fun get(key: String): JsValue? = map[key]

        operator fun set(key: String, value: JsValue) {
            map[key] = value // an existing key keeps its position, as a JS property does
        }

        fun containsKey(key: String) = map.containsKey(key)

        /** Own keys in JS order (OrdinaryOwnPropertyKeys): array indices ascending, then strings by creation. */
        val keys: List<String>
            get() {
                val (indices, strings) = map.keys.partition { isArrayIndex(it) }
                return indices.sortedBy { it.toLong() } + strings
            }

        /** `{ ...this }`: a shallow copy with the same key order. */
        fun copy(): Obj = Obj(map)

        override fun equals(other: Any?): Boolean = other is Obj && keys == other.keys && keys.all { map[it] == other.map[it] }

        override fun hashCode(): Int = keys.hashCode()

        override fun toString(): String = "Obj(${keys.joinToString { "$it=${map[it]}" }})"
    }

    /** JS truthiness. */
    val truthy: Boolean
        get() = when (this) {
            is Str -> value.isNotEmpty()
            is Num -> value != 0.0 && !value.isNaN()
            is Bool -> value
            Null, Undefined -> false
            is Arr, is Obj -> true
        }

    companion object {
        /** `JSON.parse`; null when [text] is not valid JSON (or nested past SafeJson's limit). */
        fun parse(text: String): JsValue? = try {
            // SafeJson: metadata nested deeper than any real payload is refused, not parsed (stack overflow)
            com.ecency.core.api.json.SafeJson.parseOrNull(text)?.let(::from)
        } catch (_: Exception) {
            null
        }

        fun from(element: JsonElement): JsValue = when (element) {
            is JsonNull -> Null
            is JsonPrimitive -> when {
                element.isString -> Str(element.content)
                element.content == "true" -> Bool(true)
                element.content == "false" -> Bool(false)
                else -> Num(element.content.toDouble())
            }
            is JsonArray -> Arr(element.map(::from))
            is JsonObject -> Obj().also { o -> element.forEach { (k, v) -> o[k] = from(v) } }
        }

        /** An array index property name: the canonical decimal form of an integer in 0 .. 2^32 - 2. */
        internal fun isArrayIndex(key: String): Boolean {
            if (key.isEmpty() || key.length > 10 || key.any { it !in '0'..'9' }) return false
            if (key.length > 1 && key[0] == '0') return false
            return key.toLong() <= 4_294_967_294L
        }

        /** `JSON.stringify(value)`; null for undefined (JS returns undefined). */
        fun stringify(value: JsValue): String? {
            if (value is Undefined) return null
            val sb = StringBuilder()
            write(sb, value)
            return sb.toString()
        }

        private fun write(sb: StringBuilder, value: JsValue) {
            when (value) {
                is Str -> quote(sb, value.value)
                is Num -> sb.append(if (value.value.isFinite()) numberToString(value.value) else "null")
                is Bool -> sb.append(value.value)
                Null -> sb.append("null")
                Undefined -> sb.append("null") // only reachable inside arrays, where JS writes null
                is Arr -> {
                    sb.append('[')
                    value.items.forEachIndexed { i, item ->
                        if (i > 0) sb.append(',')
                        write(sb, item)
                    }
                    sb.append(']')
                }
                is Obj -> {
                    sb.append('{')
                    var first = true
                    for (key in value.keys) {
                        val v = value[key] ?: continue
                        if (v is Undefined) continue
                        if (!first) sb.append(',')
                        first = false
                        quote(sb, key)
                        sb.append(':')
                        write(sb, v)
                    }
                    sb.append('}')
                }
            }
        }

        /** JSON.stringify's QuoteJSONString (ES2019 well-formed: lone surrogates escaped). */
        private fun quote(sb: StringBuilder, s: String) {
            sb.append('"')
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\b' -> sb.append("\\b")
                    c == '\u000C' -> sb.append("\\f")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c.code < 0x20 -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                        sb.append(c).append(s[i + 1])
                        i++
                    }
                    c.isSurrogate() -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> sb.append(c)
                }
                i++
            }
            sb.append('"')
        }

        /** `Number.prototype.toString()` for a finite double: shortest round-trip digits, JS layout. */
        fun numberToString(value: Double): String {
            if (value == 0.0) return "0" // also -0
            if (value < 0) return "-" + numberToString(-value)
            val (digits, exponent) = shortestDigits(value) // value = 0.digits * 10^exponent
            val k = digits.length
            val n = exponent
            return when {
                n in k..21 -> digits + "0".repeat(n - k)
                n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
                n in -5..0 -> "0." + "0".repeat(-n) + digits
                else -> {
                    val e = n - 1
                    val mant = if (k == 1) digits else digits[0] + "." + digits.substring(1)
                    mant + "e" + (if (e >= 0) "+" else "-") + kotlin.math.abs(e)
                }
            }
        }

        /**
         * The shortest digit string d (no leading or trailing zeros) and exponent n with d * 10^(n - len(d))
         * reading back as [value], choosing the candidate closest to the exact value (ties to even) when two
         * of the same length round-trip, as ECMAScript Number::toString requires.
         */
        private fun shortestDigits(value: Double): Pair<String, Int> {
            val (exact, pointPos) = exactDecimal(value) // value = 0.exact * 10^pointPos, exact without leading zeros
            for (len in 1..exact.length) {
                val floor = exact.substring(0, len)
                val rest = exact.substring(len)
                val candidates = mutableListOf<Pair<String, Int>>()
                candidates += floor to pointPos
                if (rest.any { it != '0' }) {
                    val up = increment(floor)
                    candidates += if (up.length > floor.length) up.substring(0, len) to pointPos + 1 else up to pointPos
                }
                val ok = candidates.filter { (d, p) -> readBack(d, p) == value }
                if (ok.isEmpty()) continue
                val chosen = if (ok.size == 1) ok[0] else {
                    val half = "5" + "0".repeat(maxOf(0, rest.length - 1))
                    val cmp = compareDigits(rest, half)
                    when {
                        cmp < 0 -> ok[0]
                        cmp > 0 -> ok[1]
                        (ok[0].first.last() - '0') % 2 == 0 -> ok[0]
                        else -> ok[1]
                    }
                }
                return chosen.first.trimEnd('0').ifEmpty { "0" } to chosen.second
            }
            return exact.trimEnd('0') to pointPos
        }

        private fun compareDigits(a: String, b: String): Int {
            val len = maxOf(a.length, b.length)
            return a.padEnd(len, '0').compareTo(b.padEnd(len, '0'))
        }

        private fun readBack(digits: String, pointPos: Int): Double = "0.${digits}e$pointPos".toDouble()

        private fun increment(s: String): String {
            val chars = s.toCharArray()
            var i = chars.size - 1
            while (i >= 0) {
                if (chars[i] == '9') {
                    chars[i] = '0'
                    i--
                } else {
                    chars[i] = chars[i] + 1
                    return chars.concatToString()
                }
            }
            return "1" + chars.concatToString()
        }

        /** Exact decimal expansion of a positive finite double: digits (no leading zeros) and the decimal point position. */
        private fun exactDecimal(value: Double): Pair<String, Int> {
            val bits = value.toRawBits()
            val biased = ((bits ushr 52) and 0x7FF).toInt()
            var mantissa = bits and 0xFFFFFFFFFFFFFL
            var exp: Int
            if (biased == 0) {
                exp = -1074
            } else {
                mantissa = mantissa or 0x10000000000000L
                exp = biased - 1075
            }
            // value = mantissa * 2^exp
            var digits = mantissa.toString().reversed().map { it - '0' }.toMutableList() // little endian
            var pointPos: Int
            if (exp >= 0) {
                repeat(exp) { digits = mul(digits, 2) }
                pointPos = digits.size
            } else {
                // mantissa * 2^exp = mantissa * 5^-exp / 10^-exp
                repeat(-exp) { digits = mul(digits, 5) }
                pointPos = digits.size + exp
            }
            val big = digits.reversed().joinToString("") { it.toString() }
            val stripped = big.trimStart('0')
            pointPos -= big.length - stripped.length
            return stripped to pointPos
        }

        private fun mul(le: MutableList<Int>, f: Int): MutableList<Int> {
            var carry = 0
            for (i in le.indices) {
                val v = le[i] * f + carry
                le[i] = v % 10
                carry = v / 10
            }
            while (carry > 0) {
                le.add(carry % 10)
                carry /= 10
            }
            return le
        }
    }
}
