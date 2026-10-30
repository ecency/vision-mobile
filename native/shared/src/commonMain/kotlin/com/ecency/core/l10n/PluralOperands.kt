package com.ecency.core.l10n

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/**
 * CLDR plural operands (UTS #35) of a number, all as Double so the generated rules compare uniformly: n absolute
 * value, i integer digits, v visible fraction digit count, w the same without trailing zeros, f the visible fraction
 * digits, t the same without trailing zeros, e the compact exponent (always 0 here). A whole number has v = 0 (1.0
 * selects like 1, as ICU and Intl.PluralRules do for numbers); other values use their shortest decimal form, at most
 * 6 fraction digits.
 */
internal class PluralOperands(val n: Double, val i: Double, val v: Double, val w: Double, val f: Double, val t: Double) {
    val e: Double get() = 0.0

    companion object {
        private const val MAX_FRACTION = 6

        fun of(value: Double): PluralOperands {
            val n = abs(value)
            val i = floor(n)
            if (n == i || n.isNaN() || n.isInfinite()) return PluralOperands(n, i, 0.0, 0.0, 0.0, 0.0)
            var digits = 1
            var scale = 10.0
            while (digits < MAX_FRACTION && abs(n * scale - round(n * scale)) > 1e-9 * scale) { digits++; scale *= 10 }
            val f = round((n - i) * scale)
            if (f >= scale) return PluralOperands(i + 1, i + 1, 0.0, 0.0, 0.0, 0.0)
            var t = f
            var w = digits.toDouble()
            while (t > 0 && t % 10.0 == 0.0) { t /= 10; w-- }
            // a fraction rounded away entirely (1.0000001) reads as the whole number
            if (f == 0.0) return PluralOperands(i, i, 0.0, 0.0, 0.0, 0.0)
            return PluralOperands(n, i, digits.toDouble(), w, f, t)
        }
    }
}

/** A plural rule range test: [x] is a whole number within [lo]..[hi] (CLDR: `n = 1..3` is false for 1.5). */
internal fun inR(x: Double, lo: Double, hi: Double): Boolean = x == floor(x) && x >= lo && x <= hi
