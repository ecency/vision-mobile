package com.ecency.l10n.gen

/**
 * Writes `L10nFormatData.kt` from the vendored CLDR formatting data (`shared/l10n/cldr-formats.json`, printed from
 * ICU4J by the shared host test `CldrFormatsExtract`): one minified JSON text per app language, which the runtime
 * (`LocaleData`) decodes the first time that language formats something.
 *
 * Checks what the runtime relies on, so a CLDR refresh that brings something new fails the build instead of
 * formatting wrong: every app language has data, the digits are ten characters, grouping sizes are positive, every
 * skeleton has a pattern that uses only the date fields the runtime implements, and the name lists are complete.
 */
internal object FormatDataGenerator {
    val SKELETONS = listOf("yMd", "yMMMd", "yMMMMd", "jmm", "yMMMdjmm", "MMMdd", "MMMEdjmm")
    val UNITS = listOf("second", "minute", "hour", "day", "week", "month", "year")

    /** Date pattern fields the runtime formats (letter to the run lengths it supports). */
    private val FIELDS: Map<Char, IntRange> = mapOf(
        'y' to 1..2, 'M' to 1..4, 'L' to 1..3, 'd' to 1..2, 'E' to 1..3, 'c' to 3..3,
        'a' to 1..1, 'h' to 1..2, 'H' to 1..2, 'm' to 1..2,
    )

    @Suppress("UNCHECKED_CAST")
    fun generate(json: String, ids: List<String>): String {
        val root = MiniJson.parse(json) as? Map<String, Any?> ?: throw IllegalArgumentException("formats: not an object")
        val source = root["source"] as? String ?: throw IllegalArgumentException("formats: no 'source'")
        val locales = root["locales"] as? Map<String, Any?> ?: throw IllegalArgumentException("formats: no 'locales' object")
        val b = StringBuilder(L10nGenerator.HEADER)
        b.append("package ${L10nGenerator.PACKAGE}\n\n")
        b.append("/** CLDR formatting data per language (vendored in shared/l10n/cldr-formats.json: ${source.replace("*/", "")}). */\n")
        b.append("internal object L10nFormatData {\n")
        b.append("    fun json(id: String): String? = when (id) {\n")
        for (id in ids.sorted()) {
            val data = locales[id] as? Map<String, Any?> ?: throw IllegalArgumentException("formats: no data for locale '$id'")
            check(id, data)
            b.append("        ${L10nGenerator.literal(id)} -> ${L10nGenerator.literal(write(data))}\n")
        }
        b.append("        else -> null\n    }\n}\n")
        return b.toString()
    }

    @Suppress("UNCHECKED_CAST")
    private fun check(id: String, d: Map<String, Any?>) {
        fun fail(what: String): Nothing = throw IllegalArgumentException("formats $id: $what")
        fun str(k: String) = d[k] as? String ?: fail("no '$k'")
        fun list(k: String, size: Int) = (d[k] as? List<Any?>)?.takeIf { l -> l.size == size && l.all { it is String } } ?: fail("'$k' is not $size texts")
        if (str("digits").length != 10) fail("digits must be ten characters")
        listOf("decimal", "group", "minus", "now").forEach { str(it) }
        val grouping = (d["grouping"] as? List<Any?>)?.map { (it as? Number)?.toInt() ?: 0 } ?: fail("no 'grouping'")
        if (grouping.size != 3 || grouping.any { it < 1 }) fail("grouping must be three positive sizes")
        list("percent", 2)
        list("MMM", 12); list("MMMM", 12); list("LLL", 12); list("EEE", 7); list("ccc", 7); list("ampm", 2)
        val compact = d["compact"] as? Map<String, Any?> ?: fail("no 'compact'")
        for (power in 3..14) {
            val byCat = compact[power.toString()] as? Map<String, Any?> ?: fail("no compact patterns for 10^$power")
            if ("other" !in byCat) fail("compact 10^$power has no 'other'")
            byCat.forEach { (cat, p) -> if (p !is String || !p.contains('0')) fail("compact 10^$power $cat '$p' has no digits") }
        }
        val dates = d["dates"] as? Map<String, Any?> ?: fail("no 'dates'")
        for (s in SKELETONS) checkPattern(id, s, dates[s] as? String ?: fail("no date pattern for '$s'"))
        for (table in listOf("past", "future", "wide", "narrow")) {
            val t = d[table] as? Map<String, Any?> ?: fail("no '$table'")
            for (u in UNITS) {
                val byCat = t[u] as? Map<String, Any?> ?: fail("no '$table' patterns for '$u'")
                if (byCat["other"] !is String) fail("'$table' '$u' has no 'other'")
            }
        }
    }

    /** Fails on a field the runtime does not format (for example B, the flexible day period). */
    internal fun checkPattern(id: String, skeleton: String, p: String) {
        var i = 0
        while (i < p.length) {
            val c = p[i]
            if (c == '\'') {
                i++
                while (i < p.length && p[i] != '\'') i++
                i++
                continue
            }
            if (c in 'a'..'z' || c in 'A'..'Z') {
                var n = 1
                while (i + n < p.length && p[i + n] == c) n++
                val ok = FIELDS[c]
                require(ok != null && n in ok) { "formats $id: date pattern '$p' ($skeleton) uses '${c.toString().repeat(n)}', which the runtime does not format" }
                i += n
                continue
            }
            i++
        }
    }

    /** Minified JSON of a MiniJson value (key order kept). */
    internal fun write(v: Any?): String = StringBuilder().also { write(v, it) }.toString()

    private fun write(v: Any?, b: StringBuilder) {
        when (v) {
            null -> b.append("null")
            is String -> {
                b.append('"')
                for (c in v) when {
                    c == '"' -> b.append("\\\"")
                    c == '\\' -> b.append("\\\\")
                    c < ' ' -> b.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> b.append(c)
                }
                b.append('"')
            }
            is Boolean -> b.append(v)
            is Number -> b.append(if (v.toDouble() == Math.floor(v.toDouble())) v.toLong().toString() else v.toString())
            is Map<*, *> -> {
                b.append('{')
                v.entries.forEachIndexed { i, (k, x) -> if (i > 0) b.append(','); write(k as String, b); b.append(':'); write(x, b) }
                b.append('}')
            }
            is List<*> -> {
                b.append('[')
                v.forEachIndexed { i, x -> if (i > 0) b.append(','); write(x, b) }
                b.append(']')
            }
            else -> throw IllegalArgumentException("formats: unexpected value $v")
        }
    }
}
