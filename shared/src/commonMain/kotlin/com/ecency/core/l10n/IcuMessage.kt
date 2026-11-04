package com.ecency.core.l10n

/**
 * The ICU MessageFormat subset the locale files use (react-intl compatible): `{x}`, `{x, number}`,
 * `{x, plural, [offset:N] =N {..} one {..} other {..}}` with `#` (in the plural's own sub-messages only, not inside a
 * nested select, as ICU4J and formatjs), `{x, select, a {..} other {..}}`, and apostrophe quoting in ICU's
 * DOUBLE_OPTIONAL mode (`''` is one apostrophe; `'` starts a quote only before `{`, `}`, or `#` inside a plural; any
 * other apostrophe is literal, as in "l'utilisateur").
 *
 * Numbers are written by the caller's formatter (the catalog passes the language's [LocaleFormatter]).
 *
 * Not supported, so rejected as malformed rather than rendered wrong: `selectordinal`, number styles
 * (`{x, number, percent}`), dates and times. The generator fails the build on them in en-US.
 *
 * [parse] throws [IcuSyntaxException] on malformed input; [L10n] catches it and falls back to English.
 */
internal class IcuMessage private constructor(val parts: List<Part>) {
    sealed interface Part
    class Text(val text: String) : Part
    class Arg(val name: String) : Part
    class NumberArg(val name: String) : Part
    object Pound : Part
    class Plural(val name: String, val offset: Int, val options: Map<String, List<Part>>) : Part
    class Select(val name: String, val options: Map<String, List<Part>>) : Part

    /** Every argument name, nested ones included. */
    val argNames: Set<String> by lazy { HashSet<String>().also { collect(parts, it) } }

    /** The message is plain text: [format] would return this. */
    val plain: String? = (parts.singleOrNull() as? Text)?.text ?: if (parts.isEmpty()) "" else null

    private fun collect(ps: List<Part>, into: MutableSet<String>) {
        for (p in ps) when (p) {
            is Arg -> into += p.name
            is NumberArg -> into += p.name
            is Plural -> { into += p.name; p.options.values.forEach { collect(it, into) } }
            is Select -> { into += p.name; p.options.values.forEach { collect(it, into) } }
            else -> Unit
        }
    }

    /**
     * Formats with [args]; [plural] picks the CLDR category of a number, [isolate] wraps substituted text (bidi
     * isolates in RTL languages), [number] writes numbers (`#`, `{x, number}` and a number in `{x}`; the catalog
     * passes the language's [LocaleFormatter], grouped with at most 3 fraction digits as ICU and formatjs).
     * Throws [IcuFormatException] when an argument is missing or of the wrong type.
     */
    fun format(
        args: Map<String, Any>,
        plural: (PluralOperands) -> String,
        isolate: (String) -> String,
        number: (Double) -> String = ::formatNumber,
    ): String {
        plain?.let { return it }
        val b = StringBuilder()
        append(b, parts, args, null, Fmt(plural, isolate, number))
        return b.toString()
    }

    private class Fmt(val plural: (PluralOperands) -> String, val isolate: (String) -> String, val number: (Double) -> String)

    private fun append(b: StringBuilder, ps: List<Part>, args: Map<String, Any>, pound: Double?, f: Fmt) {
        for (p in ps) when (p) {
            is Text -> b.append(p.text)
            is Arg -> b.append(f.isolate(arg(args, p.name).let { if (it is Number) f.number(it.toDouble()) else it.toString() }))
            is NumberArg -> b.append(f.number(number(args, p.name)))
            Pound -> if (pound != null) b.append(f.number(pound)) else b.append('#')
            is Plural -> {
                val value = number(args, p.name)
                val exact = if (value == kotlin.math.floor(value)) p.options["=${value.toLong()}"] else null
                val n = value - p.offset
                val chosen = exact ?: p.options[f.plural(PluralOperands.of(n))] ?: p.options.getValue("other")
                append(b, chosen, args, n, f)
            }
            is Select -> {
                val v = arg(args, p.name).toString()
                append(b, p.options[v] ?: p.options.getValue("other"), args, pound, f)
            }
        }
    }

    private fun arg(args: Map<String, Any>, name: String): Any = args[name] ?: throw IcuFormatException("missing argument '$name'")

    private fun number(args: Map<String, Any>, name: String): Double =
        (arg(args, name) as? Number)?.toDouble() ?: throw IcuFormatException("argument '$name' is not a number")

    companion object {
        /** A message that is [text] verbatim (no syntax). */
        fun literal(text: String): IcuMessage = IcuMessage(listOf(Text(text)))

        fun parse(message: String): IcuMessage {
            val p = Parser(message)
            val parts = p.message(inPlural = false, nested = false)
            return IcuMessage(parts)
        }

        /** Plain ASCII digits, no grouping, up to 6 decimals: the default when no language formatter is given. */
        fun formatNumber(v: Double): String {
            if (v.isNaN() || v.isInfinite()) return v.toString()
            if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) return v.toLong().toString()
            val ops = PluralOperands.of(v)
            val sign = if (v < 0) "-" else ""
            if (ops.v == 0.0) return sign + ops.i.toLong()
            return sign + ops.i.toLong() + "." + ops.f.toLong().toString().padStart(ops.v.toInt(), '0')
        }
    }

    private class Parser(val s: String) {
        var pos = 0

        fun fail(what: String): Nothing = throw IcuSyntaxException("$what at $pos")

        fun message(inPlural: Boolean, nested: Boolean): List<Part> {
            val parts = ArrayList<Part>()
            val text = StringBuilder()
            fun flush() { if (text.isNotEmpty()) { parts += Text(text.toString()); text.setLength(0) } }
            while (pos < s.length) {
                val c = s[pos]
                when {
                    c == '\'' -> quote(text, inPlural)
                    c == '{' -> { flush(); pos++; parts += argument() }
                    c == '}' -> { if (!nested) fail("unexpected '}'"); flush(); return parts }
                    c == '#' && inPlural -> { flush(); parts += Pound; pos++ }
                    else -> { text.append(c); pos++ }
                }
            }
            if (nested) fail("unterminated sub-message")
            flush()
            return parts
        }

        fun quote(text: StringBuilder, inPlural: Boolean) {
            val next = s.getOrNull(pos + 1)
            if (next == '\'') { text.append('\''); pos += 2; return }
            if (next == '{' || next == '}' || (inPlural && next == '#')) {
                pos++
                while (pos < s.length) {
                    val c = s[pos]
                    if (c == '\'') {
                        if (s.getOrNull(pos + 1) == '\'') { text.append('\''); pos += 2; continue }
                        pos++
                        return
                    }
                    text.append(c)
                    pos++
                }
                return
            }
            text.append('\'')
            pos++
        }

        fun ws() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

        fun ident(): String {
            ws()
            val start = pos
            while (pos < s.length && !s[pos].isWhitespace() && s[pos] != ',' && s[pos] != '{' && s[pos] != '}') pos++
            if (pos == start) fail("expected a name")
            return s.substring(start, pos)
        }

        fun expect(c: Char) {
            ws()
            if (pos >= s.length || s[pos] != c) fail("expected '$c'")
            pos++
        }

        fun argument(): Part {
            val name = ident()
            ws()
            if (pos >= s.length) fail("unterminated argument")
            if (s[pos] == '}') { pos++; return Arg(name) }
            expect(',')
            return when (val type = ident()) {
                "number" -> {
                    // a style (percent, currency, skeletons) would need locale data this formatter lacks
                    expect('}')
                    NumberArg(name)
                }
                "plural", "select" -> {
                    val isSelect = type == "select"
                    expect(',')
                    var offset = 0
                    val options = LinkedHashMap<String, List<Part>>()
                    while (true) {
                        ws()
                        if (pos >= s.length) fail("unterminated $type")
                        if (s[pos] == '}') { pos++; break }
                        val sel = ident()
                        if (!isSelect && sel.startsWith("offset:")) {
                            if (options.isNotEmpty()) fail("offset after a selector")
                            // "offset:1" or "offset: 1"
                            val value = sel.removePrefix("offset:").ifEmpty { ident() }
                            offset = value.toIntOrNull() ?: fail("bad offset")
                            continue
                        }
                        if (!isSelect && sel.startsWith("=") && sel.drop(1).toLongOrNull() == null) fail("bad exact selector '$sel'")
                        if (sel in options) fail("duplicate selector '$sel'")
                        expect('{')
                        options[sel] = message(inPlural = !isSelect, nested = true)
                        expect('}')
                    }
                    if ("other" !in options) fail("$type without 'other'")
                    if (isSelect) Select(name, options) else Plural(name, offset, options)
                }
                else -> fail("unsupported argument type '$type'")
            }
        }
    }
}

internal class IcuSyntaxException(message: String) : IllegalArgumentException(message)

internal class IcuFormatException(message: String) : IllegalArgumentException(message)
