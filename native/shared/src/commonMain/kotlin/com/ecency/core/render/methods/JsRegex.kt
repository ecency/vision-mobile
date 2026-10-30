package com.ecency.core.render.methods

import com.ecency.core.render.js.Js

/**
 * Compiles a JavaScript regex source (non-unicode, no `m`/`s` flags) into a Kotlin [Regex] that
 * matches the same strings, for the short anchored patterns render-helper runs once per element.
 * Hot or whole-body patterns are hand-written scanners instead.
 *
 * What the translation pins down, because the JVM and Kotlin/Native engines differ from JS here:
 * - `.` excludes exactly `\n`, `\r`, U+2028 and U+2029 (Java also excludes U+0085).
 * - `$` is the end of input (Java's `$` also matches before a final line terminator).
 * - `\s`/`\S` are the JS whitespace set (Java's are ASCII only); `\d` and `\w` are spelled out.
 * - `i` is ASCII-only, as JS without `u` behaves for ASCII patterns: letters are expanded to
 *   `[xX]` instead of using IGNORE_CASE, which on the JVM also folds U+212A, U+017F, U+0130
 *   and U+0131 onto ASCII letters.
 * - `[` and `&` inside a class are escaped (Java reads `[` as a nested class and `&&` as
 *   intersection).
 * `\b`, backreferences, lookbehind and the `m`/`s`/`u`/`y` flags are rejected.
 *
 * Known remaining difference: Java matches a surrogate pair as one character, JS as two, which
 * only matters for counted quantifiers over non-ASCII classes. Patterns that count non-ASCII
 * characters are hand-written (see YouTube).
 */
internal object JsRegex {
    private const val WS = "\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"
    private const val DOT = "[^\\n\\r\\u2028\\u2029]"

    fun compile(source: String, ignoreCase: Boolean = false): Regex = Regex(translate(source, ignoreCase))

    fun translate(src: String, ignoreCase: Boolean): String {
        val out = StringBuilder(src.length * 2)
        var inClass = false
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '\\') {
                require(i + 1 < src.length) { "dangling escape" }
                val e = src[i + 1]
                i += 2
                if (inClass) {
                    when (e) {
                        's' -> out.append(WS)
                        'd' -> out.append("0-9")
                        'w' -> out.append("a-zA-Z0-9_")
                        'S', 'D', 'W', 'b', 'B' -> error("unsupported class escape \\$e")
                        '/' -> out.append('/')
                        else -> i = verbatimEscape(src, e, i, out)
                    }
                } else {
                    when (e) {
                        's' -> out.append("[").append(WS).append("]")
                        'S' -> out.append("[^").append(WS).append("]")
                        'd' -> out.append("[0-9]")
                        'D' -> out.append("[^0-9]")
                        'w' -> out.append("[a-zA-Z0-9_]")
                        'W' -> out.append("[^a-zA-Z0-9_]")
                        '/' -> out.append('/')
                        'b', 'B' -> error("\\b is not translated")
                        in '1'..'9' -> error("backreferences are not translated")
                        else -> i = verbatimEscape(src, e, i, out)
                    }
                }
                continue
            }
            if (inClass) {
                when {
                    c == ']' -> {
                        inClass = false
                        out.append(']')
                    }
                    c == '[' -> out.append("\\[")
                    c == '&' -> out.append("\\&")
                    ignoreCase && isAsciiLetter(c) -> {
                        // a range a-z keeps its partner range; single letters get the other case
                        if (i + 2 < src.length && src[i + 1] == '-' && isAsciiLetter(src[i + 2]) && src[i + 2] != ']') {
                            val end = src[i + 2]
                            out.append(c).append('-').append(end)
                            out.append(swapCase(c)).append('-').append(swapCase(end))
                            i += 3
                            continue
                        }
                        out.append(c).append(swapCase(c))
                    }
                    else -> out.append(c)
                }
                i++
                continue
            }
            when {
                c == '[' -> {
                    inClass = true
                    out.append('[')
                    if (i + 1 < src.length && src[i + 1] == '^') {
                        out.append('^')
                        i++
                    }
                    // A leading ']' is a literal in Java but an empty class in JS; none of the patterns use it.
                    require(i + 1 >= src.length || src[i + 1] != ']') { "empty class" }
                }
                c == '.' -> out.append(DOT)
                c == '$' -> out.append("\\z")
                c == '(' && i + 2 < src.length && src[i + 1] == '?' && src[i + 2] == '<' -> error("lookbehind or named group")
                ignoreCase && isAsciiLetter(c) -> out.append('[').append(c).append(swapCase(c)).append(']')
                else -> out.append(c)
            }
            i++
        }
        require(!inClass) { "unterminated class" }
        return out.toString()
    }

    /**
     * Writes the escape `\e` and, for `\uXXXX`, `\xXX` and `\p{..}` / `\P{..}`, its payload as it stands, so
     * [translate]'s ignoreCase never rewrites the hex digits or the property name as if they were letters to match.
     * Returns the index after what was consumed ([i] is the index after `e`).
     */
    private fun verbatimEscape(src: String, e: Char, i: Int, out: StringBuilder): Int {
        out.append('\\').append(e)
        val length = when (e) {
            'u' -> if (i + 4 <= src.length && (0 until 4).all { Js.isHexDigit(src[i + it]) }) 4 else 0
            'x' -> if (i + 2 <= src.length && (0 until 2).all { Js.isHexDigit(src[i + it]) }) 2 else 0
            'p', 'P' -> if (i < src.length && src[i] == '{') (src.indexOf('}', i).takeIf { it > 0 }?.let { it - i + 1 } ?: 0) else 0
            else -> 0
        }
        out.append(src, i, i + length)
        return i + length
    }

    private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    private fun swapCase(c: Char): Char = if (c in 'a'..'z') c - 32 else if (c in 'A'..'Z') c + 32 else c
}

/** `match[n]` for a Kotlin match: null where JS has `undefined`. */
internal fun MatchResult.g(n: Int): String? = groups[n]?.value
