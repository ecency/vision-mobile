package com.ecency.l10n.gen

/** How an ICU argument is used in a message; decides the parameter type of the generated accessor. */
enum class ArgKind { TEXT, NUMBER, PLURAL, SELECT }

data class MessageArg(val name: String, val kind: ArgKind)

/**
 * Reads the arguments of an ICU message (the subset the app supports: `{x}`, `{x, number}`,
 * `{x, plural, [offset:N] sel {..} ..}`, `{x, select, sel {..} ..}`, apostrophe quoting as ICU's DOUBLE_OPTIONAL
 * mode, which react-intl follows). The runtime parser in the shared core is the full one; this one only finds names
 * and kinds, in order of first use, and throws [IllegalArgumentException] on malformed input, and on syntax the
 * runtime does not render (`selectordinal`, number styles, dates), so such an en-US message fails the build.
 */
object MessageArgs {
    fun of(message: String): List<MessageArg> {
        val found = LinkedHashMap<String, ArgKind>()
        val s = Scanner(message, found)
        s.message(inPlural = false, nested = false)
        require(s.pos == message.length) { "unbalanced '}' at ${s.pos} in: $message" }
        return found.map { (n, k) -> MessageArg(n, k) }
    }

    private class Scanner(val s: String, val found: MutableMap<String, ArgKind>) {
        var pos = 0

        fun message(inPlural: Boolean, nested: Boolean) {
            while (pos < s.length) {
                val c = s[pos]
                when {
                    c == '\'' -> quote(inPlural)
                    c == '{' -> { pos++; argument() }
                    c == '}' -> { require(nested) { "unexpected '}' at $pos" }; return }
                    else -> pos++
                }
            }
            require(!nested) { "unterminated sub-message" }
        }

        /** ICU DOUBLE_OPTIONAL: `''` is one apostrophe; `'` before `{`, `}` (or `#` in a plural) quotes. */
        fun quote(inPlural: Boolean) {
            val next = s.getOrNull(pos + 1)
            if (next == '\'') { pos += 2; return }
            if (next == '{' || next == '}' || (inPlural && next == '#')) {
                pos++
                while (pos < s.length) {
                    if (s[pos] == '\'') {
                        if (s.getOrNull(pos + 1) == '\'') { pos += 2; continue }
                        pos++
                        return
                    }
                    pos++
                }
                return // an unclosed quote runs to the end, as in ICU
            }
            pos++
        }

        fun ws() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

        fun ident(): String {
            ws()
            val start = pos
            while (pos < s.length && !s[pos].isWhitespace() && s[pos] !in ",{}") pos++
            require(pos > start) { "expected a name at $start in: $s" }
            return s.substring(start, pos)
        }

        fun expect(c: Char) {
            ws()
            require(pos < s.length && s[pos] == c) { "expected '$c' at $pos in: $s" }
            pos++
        }

        fun argument() {
            val name = ident()
            ws()
            require(pos < s.length) { "unterminated argument in: $s" }
            if (s[pos] == '}') { pos++; add(name, ArgKind.TEXT); return }
            expect(',')
            when (val type = ident()) {
                "number" -> {
                    add(name, ArgKind.NUMBER)
                    ws()
                    require(pos < s.length && s[pos] != ',') { "number styles are not supported (the formatter has no locale data) in: $s" }
                    expect('}')
                }
                "plural", "select" -> {
                    add(name, if (type == "select") ArgKind.SELECT else ArgKind.PLURAL)
                    expect(',')
                    val selectors = HashSet<String>()
                    while (true) {
                        ws()
                        require(pos < s.length) { "unterminated $type in: $s" }
                        if (s[pos] == '}') { pos++; break }
                        val sel = ident()
                        if (type != "select" && sel.startsWith("offset:")) {
                            if (sel == "offset:") ident() // "offset: 1"
                            continue
                        }
                        require(selectors.add(sel)) { "duplicate selector '$sel' in: $s" }
                        expect('{')
                        message(inPlural = type != "select", nested = true)
                        expect('}')
                    }
                    require("other" in selectors) { "$type without 'other' in: $s" }
                }
                // selectordinal would need CLDR ordinal rules; the runtime has none, so it is refused here
                else -> throw IllegalArgumentException("unsupported argument type '$type' in: $s")
            }
        }

        fun add(name: String, kind: ArgKind) {
            val old = found[name]
            found[name] = when {
                old == null || old == kind -> kind
                old == ArgKind.SELECT || kind == ArgKind.SELECT -> throw IllegalArgumentException("'$name' used as select and as ${if (old == ArgKind.SELECT) kind else old} in: $s")
                old == ArgKind.PLURAL || kind == ArgKind.PLURAL -> ArgKind.PLURAL
                else -> ArgKind.NUMBER
            }
        }
    }
}
