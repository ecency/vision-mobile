package com.ecency.core.math

/**
 * A minimal unsigned big integer: just what the resource credit price curve needs (`coeff_a` is about 1e19 and
 * its product with the regeneration rate passes 64 bits, and common Kotlin has no big integer). Immutable;
 * 32-bit limbs, least significant first, without leading zero limbs.
 *
 * Operations: [plus], [times], [shr], [div], [compareTo], reading from a decimal string or a [ULong], and
 * [toDouble] with JavaScript's `Number(bigint)` rounding. Nothing else.
 */
class UBig private constructor(private val limbs: IntArray) : Comparable<UBig> {
    val isZero: Boolean get() = limbs.isEmpty()

    /** The number of bits needed to write the value; 0 for zero. */
    val bitLength: Int get() = if (limbs.isEmpty()) 0 else (limbs.size - 1) * 32 + (32 - limbs.last().countLeadingZeroBits())

    private fun limb(i: Int): Long = if (i < limbs.size) limbs[i].toLong() and MASK else 0L

    private fun bit(i: Int): Boolean = (limb(i / 32) shr (i % 32)) and 1L == 1L

    operator fun plus(other: UBig): UBig {
        val out = IntArray(maxOf(limbs.size, other.limbs.size) + 1)
        var carry = 0L
        for (i in out.indices) {
            val sum = limb(i) + other.limb(i) + carry
            out[i] = sum.toInt()
            carry = sum ushr 32
        }
        return trimmed(out)
    }

    /** `this - other`; [other] must not be larger. */
    private fun minus(other: UBig): UBig {
        val out = IntArray(limbs.size)
        var borrow = 0L
        for (i in out.indices) {
            var d = limb(i) - other.limb(i) - borrow
            borrow = if (d < 0) 1 else 0
            if (d < 0) d += 1L shl 32
            out[i] = d.toInt()
        }
        return trimmed(out)
    }

    operator fun times(other: UBig): UBig {
        if (isZero || other.isZero) return ZERO
        val out = IntArray(limbs.size + other.limbs.size)
        for (i in limbs.indices) {
            var carry = 0L
            val a = limb(i)
            for (j in other.limbs.indices) {
                // a * b fits an unsigned 64-bit value; the two additions are done with their own carries
                val product = a * other.limb(j)
                val low = (product and MASK) + (out[i + j].toLong() and MASK) + (carry and MASK)
                out[i + j] = low.toInt()
                carry = (product ushr 32) + (carry ushr 32) + (low ushr 32)
            }
            var k = i + other.limbs.size
            while (carry != 0L) {
                val sum = (out[k].toLong() and MASK) + (carry and MASK)
                out[k] = sum.toInt()
                carry = (carry ushr 32) + (sum ushr 32)
                k++
            }
        }
        return trimmed(out)
    }

    infix fun shr(bits: Int): UBig {
        require(bits >= 0)
        val words = bits / 32
        val rest = bits % 32
        if (words >= limbs.size) return ZERO
        val out = IntArray(limbs.size - words)
        for (i in out.indices) {
            val low = limb(i + words) ushr rest
            val high = if (rest == 0) 0L else (limb(i + words + 1) shl (32 - rest)) and MASK
            out[i] = (low or high).toInt()
        }
        return trimmed(out)
    }

    private infix fun shl(bits: Int): UBig {
        if (isZero) return this
        val words = bits / 32
        val rest = bits % 32
        val out = IntArray(limbs.size + words + 1)
        for (i in limbs.indices) {
            val v = limb(i) shl rest
            out[i + words] = out[i + words] or v.toInt()
            out[i + words + 1] = (v ushr 32).toInt()
        }
        return trimmed(out)
    }

    /** The quotient, rounded down. A division by zero is an error, as in BigInt. */
    operator fun div(other: UBig): UBig {
        if (other.isZero) throw ArithmeticException("division by zero")
        if (this < other) return ZERO
        // schoolbook division one bit at a time: the values here have at most a few hundred bits
        var remainder = ZERO
        val out = IntArray(limbs.size)
        for (i in bitLength - 1 downTo 0) {
            remainder = remainder shl 1
            if (bit(i)) remainder += ONE
            if (remainder >= other) {
                remainder = remainder.minus(other)
                out[i / 32] = out[i / 32] or (1 shl (i % 32))
            }
        }
        return trimmed(out)
    }

    override fun compareTo(other: UBig): Int {
        if (limbs.size != other.limbs.size) return limbs.size.compareTo(other.limbs.size)
        for (i in limbs.indices.reversed()) {
            val c = limb(i).compareTo(other.limb(i))
            if (c != 0) return c
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is UBig && limbs.contentEquals(other.limbs)

    override fun hashCode(): Int = limbs.contentHashCode()

    /** JavaScript's `Number(bigint)`: the nearest double, ties to even; Infinity past the range. */
    fun toDouble(): Double {
        val bits = bitLength
        if (bits <= 53) {
            var v = 0L
            for (i in limbs.indices.reversed()) v = (v shl 32) or limb(i)
            return v.toDouble()
        }
        if (bits > 1024) return Double.POSITIVE_INFINITY
        // the top 53 bits, then round on the next bit and whether anything is set below it
        val shift = bits - 53
        var mantissa = 0L
        for (i in bits - 1 downTo shift) mantissa = (mantissa shl 1) or (if (bit(i)) 1L else 0L)
        val half = bit(shift - 1)
        var sticky = false
        for (i in 0 until shift - 1) if (bit(i)) { sticky = true; break }
        if (half && (sticky || mantissa and 1L == 1L)) mantissa++
        var result = mantissa.toDouble()
        repeat(shift) { result *= 2.0 }
        return result
    }

    /** The decimal digits. */
    override fun toString(): String {
        if (isZero) return "0"
        val digits = StringBuilder()
        var rest = limbs.copyOf()
        var size = rest.size
        while (size > 0) {
            var remainder = 0L
            for (i in size - 1 downTo 0) {
                val v = (remainder shl 32) or (rest[i].toLong() and MASK)
                rest[i] = (v / 1_000_000_000L).toInt()
                remainder = v % 1_000_000_000L
            }
            while (size > 0 && rest[size - 1] == 0) size--
            digits.insert(0, if (size > 0) remainder.toString().padStart(9, '0') else remainder.toString())
        }
        return digits.toString()
    }

    companion object {
        private const val MASK = 0xFFFFFFFFL

        val ZERO = UBig(IntArray(0))
        val ONE = of(1uL)

        private fun trimmed(limbs: IntArray): UBig {
            var size = limbs.size
            while (size > 0 && limbs[size - 1] == 0) size--
            return UBig(if (size == limbs.size) limbs else limbs.copyOf(size))
        }

        fun of(value: ULong): UBig = trimmed(intArrayOf(value.toInt(), (value shr 32).toInt()))

        /** Decimal digits only (no sign, no spaces); null for anything else. */
        fun parse(text: String): UBig? {
            if (text.isEmpty() || text.length > 400 || text.any { it !in '0'..'9' }) return null
            var out = ZERO
            val billion = of(1_000_000_000uL)
            var i = 0
            while (i < text.length) {
                val end = minOf(text.length, i + 9)
                var scale = 1uL
                repeat(end - i) { scale *= 10uL }
                out = out * (if (end - i == 9) billion else of(scale)) + of(text.substring(i, end).toULong())
                i = end
            }
            return out
        }

        /** JavaScript's `BigInt(Math.trunc(v))` for a number that is not negative; null for a negative, NaN or infinite one. */
        fun ofDouble(value: Double): UBig? {
            if (value.isNaN() || value.isInfinite() || value <= -1.0) return null
            val v = kotlin.math.floor(kotlin.math.abs(value))
            if (v < 9.007199254740992E15) return of(v.toLong().toULong())
            // above 2^53 a double is an integer: mantissa times a power of two
            val raw = v.toRawBits()
            val exponent = ((raw ushr 52) and 0x7FF).toInt() - 1075
            val mantissa = (raw and 0xFFFFFFFFFFFFFL) or (1L shl 52)
            return of(mantissa.toULong()) shl exponent
        }
    }
}
