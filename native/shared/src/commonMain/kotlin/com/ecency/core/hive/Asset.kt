package com.ecency.core.hive

/**
 * A Hive asset amount held as an exact integer of the smallest unit.
 *
 * Unlike the JS SDK (which parses through a float and rounds), amounts with more decimals
 * than the asset allows are rejected instead of being silently rounded.
 */
class Asset private constructor(val units: Long, val symbol: String) {
    val precision: Int = precisionOf(symbol)

    /** Symbol as serialized on chain: HIVE and HBD keep their legacy STEEM and SBD names. */
    private val chainSymbol: String = when (symbol) {
        "HIVE" -> "STEEM"
        "HBD" -> "SBD"
        else -> symbol
    }

    internal fun write(writer: HiveWriter) {
        writer.i64(units)
        writer.u8(precision)
        val name = chainSymbol.encodeToByteArray()
        for (i in 0 until 7) writer.u8(if (i < name.size) name[i].toInt() else 0)
    }

    override fun toString(): String {
        val negative = units < 0
        val digits = (if (negative) -units else units).toString().padStart(precision + 1, '0')
        val whole = digits.dropLast(precision)
        val fraction = digits.takeLast(precision)
        return "${if (negative) "-" else ""}$whole.$fraction $symbol"
    }

    override fun equals(other: Any?): Boolean =
        other is Asset && other.units == units && other.symbol == symbol

    override fun hashCode(): Int = units.hashCode() * 31 + symbol.hashCode()

    companion object {
        private val SYMBOLS = setOf("HIVE", "HBD", "VESTS", "STEEM", "SBD", "TESTS", "TBD")
        // ASCII digits only: a localized amount ("١٫٠٠٠", "1,000") is never read as one, on any platform
        private val AMOUNT = Regex("""^(-?)([0-9]+)(?:\.([0-9]+))?$""")

        private fun precisionOf(symbol: String) = if (symbol == "VESTS") 6 else 3

        /** Parses "1.234 HIVE". */
        fun parse(value: String): Asset {
            val parts = value.trim().split(" ")
            require(parts.size == 2) { "invalid asset: $value" }
            val (amount, symbol) = parts
            require(symbol in SYMBOLS) { "invalid asset symbol: $symbol" }
            val precision = precisionOf(symbol)
            val match = requireNotNull(AMOUNT.matchEntire(amount)) { "invalid asset amount: $amount" }
            val (sign, whole, fraction) = match.destructured
            require(fraction.length <= precision) {
                "$symbol has $precision decimals, got ${fraction.length} in $value"
            }
            var units = 0L
            for (c in whole + fraction.padEnd(precision, '0')) {
                require(units <= (Long.MAX_VALUE - 9) / 10) { "asset amount too large: $value" }
                units = units * 10 + (c - '0')
            }
            return Asset(if (sign == "-") -units else units, symbol)
        }
    }
}
