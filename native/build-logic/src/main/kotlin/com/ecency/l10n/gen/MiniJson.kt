package com.ecency.l10n.gen

/**
 * A small JSON reader for the locale files: objects keep their key order (the generated API follows en-US's order),
 * strings, numbers, booleans and null. Throws [IllegalArgumentException] with the offset on malformed input.
 */
internal object MiniJson {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        require(p.pos == text.length) { "trailing content at ${p.pos}" }
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun ws() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            require(pos < s.length) { "unexpected end" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("unexpected '$c' at $pos")
            }
        }

        fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, pos)) { "bad literal at $pos" }
            pos += word.length
            return v
        }

        fun num(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            return s.substring(start, pos).toDouble()
        }

        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++
            ws()
            if (s[pos] == '}') { pos++; return m }
            while (true) {
                ws()
                require(s[pos] == '"') { "expected key at $pos" }
                val k = str()
                ws()
                require(s[pos] == ':') { "expected ':' at $pos" }
                pos++
                ws()
                require(k !in m) { "duplicate key '$k' at $pos" }
                m[k] = value()
                ws()
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> throw IllegalArgumentException("expected ',' or '}' at ${pos - 1}")
                }
            }
        }

        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            pos++
            ws()
            if (s[pos] == ']') { pos++; return l }
            while (true) {
                ws()
                l += value()
                ws()
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> throw IllegalArgumentException("expected ',' or ']' at ${pos - 1}")
                }
            }
        }

        fun str(): String {
            pos++
            val b = StringBuilder()
            while (true) {
                require(pos < s.length) { "unterminated string" }
                val c = s[pos++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> { b.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4 }
                            else -> throw IllegalArgumentException("bad escape '\\$e' at $pos")
                        }
                    }
                    else -> b.append(c)
                }
            }
        }
    }
}
