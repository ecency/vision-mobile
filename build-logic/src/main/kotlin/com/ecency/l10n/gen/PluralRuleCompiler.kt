package com.ecency.l10n.gen

/**
 * Compiles a CLDR plural rule condition (UTS #35 syntax, as ICU prints it: `v = 0 and i % 10 = 2..4 or n = 1`) to
 * a Kotlin boolean expression over a `PluralOperands` value `o` (fields n, i, v, w, f, t, e as Double) and the
 * runtime helper `inR(x, lo, hi)` (x is a whole number within lo..hi). `and` binds tighter than `or`, as in Kotlin.
 * With [integer] the literals are whole numbers (`o.i % 10`, `inR(x, 2, 4)`) for operands held as integers: the iOS
 * widget extension's Swift rules, which only ever see counts.
 */
object PluralRuleCompiler {
    private val OPERANDS = setOf("n", "i", "v", "w", "f", "t", "e", "c")

    fun compile(rule: String, integer: Boolean = false): String {
        val text = rule.trim()
        require(text.isNotEmpty()) { "empty rule" }
        return text.split(" or ").joinToString(" || ") { and ->
            val parts = and.split(" and ").map { relation(it.trim(), rule, integer) }
            if (parts.size == 1) parts[0] else parts.joinToString(" && ", "(", ")")
        }
    }

    private fun relation(rel: String, rule: String, integer: Boolean): String {
        val m = Regex("""^([a-z])\s*(?:%\s*(\d+))?\s*(!=|=)\s*([\d.,\s]+)$""").matchEntire(rel)
            ?: throw IllegalArgumentException("unsupported relation '$rel' in rule '$rule'")
        val (operand, mod, op, list) = m.destructured
        require(operand in OPERANDS) { "unknown operand '$operand' in rule '$rule'" }
        // c is CLDR's newer name for e (compact exponent); both are 0 for every number the app formats.
        val field = if (operand == "c") "e" else operand
        val dot = if (integer) "" else ".0"
        val x = if (mod.isEmpty()) "o.$field" else "(o.$field % $mod$dot)"
        val ranges = list.split(',').map { it.trim() }.map { r ->
            val bounds = r.split("..")
            require(bounds.size in 1..2 && bounds.all { b -> b.isNotEmpty() && b.all(Char::isDigit) }) { "bad range '$r' in rule '$rule'" }
            "inR($x, ${bounds.first()}$dot, ${bounds.last()}$dot)"
        }
        val any = if (ranges.size == 1) ranges[0] else ranges.joinToString(" || ", "(", ")")
        // a multi-range list is already parenthesised; a single inR(..) call needs no parentheses either
        return if (op == "=") any else "!$any"
    }
}
