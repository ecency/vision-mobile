package com.ecency.l10n.gen

/**
 * An ICU message parsed the way the shared runtime's `IcuMessage` parses it (the same subset and apostrophe rules,
 * see [MessageArgs]), for the generated OS strings: a message without arguments becomes its plain text, and a
 * translation is accepted under the runtime's rule ([SystemStrings.resolve]). Throws [IllegalArgumentException] on
 * malformed input.
 */
internal sealed interface IcuPart {
    data class Text(val text: String) : IcuPart
    data class Arg(val name: String) : IcuPart
    data class NumberArg(val name: String) : IcuPart
    data object Pound : IcuPart
    data class Plural(val name: String, val offset: Int, val options: Map<String, List<IcuPart>>) : IcuPart
    data class Select(val name: String, val options: Map<String, List<IcuPart>>) : IcuPart

    companion object {
        fun parse(message: String): List<IcuPart> = Parser(message).message(inPlural = false, nested = false)

        /** The text a message shows when it has no arguments, or null when it has any. */
        fun plain(parts: List<IcuPart>): String? = when {
            parts.isEmpty() -> ""
            else -> (parts.singleOrNull() as? Text)?.text
        }

        /** Every argument name, nested ones included. */
        fun names(parts: List<IcuPart>): Set<String> = HashSet<String>().also { collect(parts, it) }

        private fun collect(ps: List<IcuPart>, into: MutableSet<String>) {
            for (p in ps) when (p) {
                is Arg -> into += p.name
                is NumberArg -> into += p.name
                is Plural -> { into += p.name; p.options.values.forEach { collect(it, into) } }
                is Select -> { into += p.name; p.options.values.forEach { collect(it, into) } }
                else -> Unit
            }
        }
    }

    private class Parser(val s: String) {
        var pos = 0

        fun fail(what: String): Nothing = throw IllegalArgumentException("$what at $pos in: $s")

        fun message(inPlural: Boolean, nested: Boolean): List<IcuPart> {
            val parts = ArrayList<IcuPart>()
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

        /** ICU DOUBLE_OPTIONAL: `''` is one apostrophe; `'` before `{`, `}` (or `#` in a plural) quotes. */
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

        fun argument(): IcuPart {
            val name = ident()
            ws()
            if (pos >= s.length) fail("unterminated argument")
            if (s[pos] == '}') { pos++; return Arg(name) }
            expect(',')
            return when (val type = ident()) {
                "number" -> { expect('}'); NumberArg(name) }
                "plural", "select" -> {
                    val isSelect = type == "select"
                    expect(',')
                    var offset = 0
                    val options = LinkedHashMap<String, List<IcuPart>>()
                    while (true) {
                        ws()
                        if (pos >= s.length) fail("unterminated $type")
                        if (s[pos] == '}') { pos++; break }
                        val sel = ident()
                        if (!isSelect && sel.startsWith("offset:")) {
                            if (options.isNotEmpty()) fail("offset after a selector")
                            offset = sel.removePrefix("offset:").ifEmpty { ident() }.toIntOrNull() ?: fail("bad offset")
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
