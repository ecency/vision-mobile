package com.ecency.core.editor

import com.ecency.core.render.js.Js

/**
 * Port of speakingurl's `getSlug(input)` with its defaults (separator "-", English symbols), which RN's
 * generatePermlink uses, and of generatePermlink itself (src/utils/editor.ts). Golden vectors run the JS.
 */
object Slug {
    private fun table(chunks: List<String>): Map<String, String> = buildMap {
        chunks.forEach { chunk ->
            chunk.split('\u001e').forEach { entry ->
                val at = entry.indexOf('\u001f')
                if (at >= 0) put(entry.substring(0, at), entry.substring(at + 1))
            }
        }
    }

    private val charMap by lazy { table(SlugData.CHAR_MAP) }
    private val diatricMap by lazy { table(SlugData.DIATRIC_MAP) }
    private val langEn by lazy { table(SlugData.LANG_EN) }
    private val symbolEn by lazy { table(SlugData.SYMBOL_EN) }

    private fun isAlnum(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    private fun hasAlnum(s: String) = s.any(::isAlnum)

    /** JS `'' + diatricMap[key]`: a missing key reads "undefined", as the JS concatenation does. */
    private fun diatric(key: String) = diatricMap[key] ?: "undefined"

    fun getSlug(raw: String): String {
        val input = Js.trim(raw)
        val result = StringBuilder()
        var diatricString = ""
        var lastCharWasSymbol = false
        var lastCharWasDiatric = false
        val l = input.length
        for (i in 0 until l) {
            var ch = input[i].toString()
            val lang = langEn[ch]
            val symbol = symbolEn[ch]
            if (!lang.isNullOrEmpty()) {
                ch = if (lastCharWasSymbol && hasAlnum(lang)) " $lang" else lang
                lastCharWasSymbol = false
            } else if (charMap.containsKey(ch)) {
                val mapped = charMap.getValue(ch)
                if (i + 1 < l && SlugData.LOOK_AHEAD.indexOf(input[i + 1]) >= 0) {
                    diatricString += ch
                    ch = ""
                } else if (lastCharWasDiatric) {
                    ch = diatric(diatricString) + mapped
                    diatricString = ""
                } else {
                    ch = if (lastCharWasSymbol && hasAlnum(mapped)) " $mapped" else mapped
                }
                lastCharWasSymbol = false
                lastCharWasDiatric = false
            } else if (diatricMap.containsKey(ch)) {
                diatricString += ch
                ch = ""
                // the JS throws on an unknown combination here; nothing is added instead
                if (i == l - 1) ch = diatricMap[diatricString].orEmpty()
                lastCharWasDiatric = true
            } else if (!symbol.isNullOrEmpty()) {
                ch = if (lastCharWasSymbol || (result.isNotEmpty() && isAlnum(result[result.length - 1]))) "-$symbol" else symbol
                if (i + 1 < l && isAlnum(input[i + 1])) ch += "-"
                lastCharWasSymbol = true
            } else {
                if (lastCharWasDiatric) {
                    ch = diatric(diatricString) + ch
                    diatricString = ""
                    lastCharWasDiatric = false
                } else if (lastCharWasSymbol && isAlnum(ch[0])) {
                    ch = " $ch"
                }
                lastCharWasSymbol = false
            }
            // ch.replace(/[^\w\s\-_-]/g, '-')
            for (c in ch) result.append(if (Js.isWordChar(c) || Js.isSpace(c) || c == '-') c else '-')
        }
        // \s+ and runs of the separator collapse to one separator; none at the ends
        val out = StringBuilder()
        var pendingDash = false
        for (c in result) {
            if (Js.isSpace(c) || c == '-') {
                pendingDash = true
            } else {
                if (pendingDash && out.isNotEmpty()) out.append('-')
                pendingDash = false
                out.append(Js.asciiLower(c))
            }
        }
        return out.toString()
    }

    /**
     * RN generatePermlink: the slug's first five words, an optional "-<random>" suffix, at most 255 characters,
     * `[a-z0-9-]` only; an empty result is [randomString] alone. An empty [title] gives "".
     */
    fun generatePermlink(title: String, random: Boolean, randomString: () -> String): String {
        if (title.isEmpty()) return ""
        var perm = getSlug(title)
        val parts = perm.split('-')
        if (parts.size > 5) perm = parts.take(5).joinToString("-")
        if (random) perm = "$perm-${randomString()}"
        if (perm.length > 255) perm = perm.substring(perm.length - 255)
        perm = perm.lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it == '-' }
        return perm.ifEmpty { randomString() }
    }
}
