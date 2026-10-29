package com.ecency.core.api.broadcast

/**
 * Amount text for broadcasts, RN `utils/number.ts` (`toFixedNoExp`, `formatTokenQuantity`, `capDecimals`,
 * `getAssetPrecision`), on exact decimal strings: amounts are never rounded up past what the user has (truncated
 * toward zero), and nothing goes through a float, so large Engine balances keep every digit.
 */
object Amounts {
    private val PLAIN = Regex("^-?[0-9]+(\\.[0-9]*)?$")

    /** RN getAssetPrecision: 3 for HIVE, HBD, HP, POINT(S), TESTS, TBD and anything unknown; 6 for VESTS. */
    fun precision(symbol: String): Int = if (symbol.trim().uppercase() == "VESTS") 6 else 3

    /**
     * RN toFixedNoExp: [value] truncated toward zero to exactly [precision] decimals, padded when shorter; "0.000"
     * (for precision 3) when it is not a plain decimal number. A negative zero loses its sign.
     */
    fun fixed(value: String, precision: Int): String = truncate(value, precision) ?: zero(precision)

    /** "1.234 HIVE": [value] fixed to the symbol's precision ([precision]) with the symbol. */
    fun asset(value: String, symbol: String): String = "${fixed(value, precision(symbol))} $symbol"

    /**
     * RN formatTokenQuantity (#3569): truncated to min([precision], 8) decimals, then trailing zeros and a dangling
     * dot dropped ("1.50000000" is "1.5", "2.000" is "2"); "0" when unreadable.
     */
    fun tokenQuantity(value: String, precision: Int = 8): String {
        val cut = truncate(value, precision.coerceIn(0, 8)) ?: return "0"
        return if ('.' !in cut) cut else cut.trimEnd('0').trimEnd('.')
    }

    /** RN capDecimals: typed text cut to at most [maxDecimals] fraction digits; a trailing '.' stays. */
    fun capDecimals(value: String, maxDecimals: Int): String {
        val dot = value.indexOf('.')
        if (dot == -1 || value.length - dot - 1 <= maxDecimals) return value
        return value.substring(0, dot + 1 + maxDecimals)
    }

    /** What a typed amount field keeps: [typedText], then [capDecimals] (an ambiguous text is left whole, uncut). */
    fun typed(value: String, maxDecimals: Int): String = posixTyped(value)?.let { capDecimals(it, maxDecimals) } ?: value

    /**
     * Typed amount text made POSIX, so a keyboard in the app language works: every Unicode decimal digit (Arabic-Indic,
     * Persian, Bengali, Devanagari, any Nd) becomes its ASCII digit, and the Arabic decimal separator "٫" (U+066B) and
     * a comma (RN, in every language) become '.'. A number typed into an amount field has no grouping, so a separator
     * is only ever the decimal point: text that holds two different separators ("1,000.5", "1.000,5") or the Arabic
     * thousands separator "٬" (U+066C) is ambiguous and comes back with them unchanged, which no amount reading
     * accepts (the field keeps its previous text). Input only: [Asset], the dApp bridge and the operation builders
     * still take ASCII alone.
     */
    fun typedText(value: String): String = posixTyped(value) ?: value

    private fun posixTyped(value: String): String? {
        val b = StringBuilder(value.length)
        var separators = 0
        for (c in value) {
            val d = c.digitToIntOrNull()
            when {
                d != null && c !in '0'..'9' -> b.append('0' + d)
                c == '.' || c == ',' || c == '\u066B' -> { b.append('.'); separators = separators or SEP_BITS.getValue(c) }
                else -> b.append(c)
            }
        }
        // mixed separators: likely one of them groups, so nothing is guessed
        if (separators and (separators - 1) != 0) return null
        return b.toString()
    }

    private val SEP_BITS = mapOf('.' to 1, ',' to 2, '\u066B' to 4)

    private fun zero(precision: Int) = if (precision > 0) "0." + "0".repeat(precision) else "0"

    /**
     * The one reading of typed amount text every amount path shares (with [Dec.of]): digits with at most one dot,
     * a leading dot read as "0." (".5" is 0.5, as a number field shows it); "", "." and anything else are not amounts.
     * Returns the text with the leading "0" restored, or null.
     */
    fun normalize(value: String): String? {
        val text = value.trim()
        val negative = text.startsWith('-')
        val body = if (negative) text.drop(1) else text
        if (body.isEmpty() || body == ".") return null
        val fixed = if (body.startsWith('.')) "0$body" else body
        if (!Regex("^[0-9]+(\\.[0-9]*)?$").matches(fixed)) return null
        return (if (negative) "-" else "") + fixed
    }

    private fun truncate(value: String, precision: Int): String? {
        val text = normalize(value) ?: return null
        if (!PLAIN.matches(text)) return null
        val negative = text.startsWith('-')
        var digits = (if (negative) text.drop(1) else text)
        digits = digits.replaceFirst(Regex("^0+(?=[0-9])"), "")
        val dot = digits.indexOf('.')
        val cut = if (dot == -1) {
            if (precision > 0) "$digits.${"0".repeat(precision)}" else digits
        } else {
            val frac = (digits.substring(dot + 1) + "0".repeat(precision)).take(precision)
            if (precision > 0) "${digits.substring(0, dot)}.$frac" else digits.substring(0, dot)
        }
        val isZero = cut.all { it == '0' || it == '.' }
        return (if (negative && !isZero) "-" else "") + cut
    }
}

/**
 * An exact non-negative decimal for balance checks (typed amount against balance, amount times recipients). Parsed
 * from plain decimal text only; null for anything else.
 */
class Dec private constructor(private val digits: String, private val scale: Int) : Comparable<Dec> {
    /** Unscaled digits with leading zeros dropped. */
    private val unscaled: String get() = digits.trimStart('0').ifEmpty { "0" }

    val isZero: Boolean get() = unscaled == "0"

    private fun rescaled(to: Int): String = unscaled + "0".repeat(to - scale)

    override fun compareTo(other: Dec): Int {
        val s = maxOf(scale, other.scale)
        val a = rescaled(s).trimStart('0').ifEmpty { "0" }
        val b = other.rescaled(s).trimStart('0').ifEmpty { "0" }
        return if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
    }

    override fun equals(other: Any?): Boolean = other is Dec && compareTo(other) == 0

    override fun hashCode(): Int = toString().hashCode()

    /** This times a small whole [n] (recipients). */
    fun times(n: Int): Dec {
        require(n >= 0)
        var carry = 0L
        val out = StringBuilder()
        for (i in digits.indices.reversed()) {
            val v = (digits[i] - '0') * n.toLong() + carry
            out.append((v % 10).toInt())
            carry = v / 10
        }
        while (carry > 0) {
            out.append((carry % 10).toInt())
            carry /= 10
        }
        return Dec(out.reverse().toString(), scale)
    }

    override fun toString(): String {
        val u = unscaled.padStart(scale + 1, '0')
        return if (scale == 0) u else "${u.dropLast(scale)}.${u.takeLast(scale)}"
    }

    companion object {
        private val PLAIN = Regex("^[0-9]+(\\.[0-9]*)?$|^\\.[0-9]+$")

        fun of(text: String): Dec? {
            // the same reading as the broadcast amounts (Amounts.normalize), so a value checked here is the value signed
            val t = Amounts.normalize(text)?.takeIf { !it.startsWith('-') } ?: return null
            if (!PLAIN.matches(t)) return null
            val whole = t.substringBefore('.').ifEmpty { "0" }
            val frac = if ('.' in t) t.substringAfter('.') else ""
            return Dec(whole + frac, frac.length)
        }

        /** The leading number of an asset string or plain number, "1.234 HIVE" is 1.234; null when unreadable. */
        fun ofAsset(text: String): Dec? = of(text.trim().substringBefore(' ').replace(",", ""))

        val ZERO: Dec = Dec("0", 0)
    }
}
