package com.ecency.core.l10n

/**
 * Pseudo locales built from the English table, for tests and debug builds only (never selectable in a release
 * build: the apps call [L10n.usePseudo] only behind their debug flag): `en-XA` accents every letter and pads each
 * message by 40% inside brackets (finds clipped or concatenated copy), `ar-XB` forces every literal run right to left
 * and turns on RTL (finds layouts and placeholders that do not mirror). Placeholders, plural and select syntax stay
 * intact, so every message keeps English's arguments.
 */
internal object PseudoLocales {
    const val ACCENTED = "en-XA"
    const val RTL = "ar-XB"

    private const val PLAIN = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val ACCENTS = "åƀçðéƒĝĥîĵķļɱñöþǫŕšţûṽŵẋýžÅƁÇÐÉƑĜĤÎĴĶĻṀÑÖÞǪŔŠŢÛṼŴẊÝŽ"

    fun install(id: String) {
        val rtl = id == RTL
        val table = Array<String?>(L10nData.SIZE) { i -> transform(L10n.englishSource(i), rtl) }
        L10n.install(id, table, rtl = rtl, pluralLocale = L10n.SOURCE)
    }

    fun accent(text: String): String = buildString(text.length) {
        for (c in text) append(PLAIN.indexOf(c).let { if (it >= 0) ACCENTS[it] else c })
    }

    internal fun transform(message: String, rtl: Boolean): String {
        val parsed = try { IcuMessage.parse(message) } catch (e: IcuSyntaxException) { return message }
        // RLM first, as Android's own ar-XB: a strong right to left mark, so text that takes its direction from its
        // first strong letter (Compose `TextDirection.Content`, SwiftUI) reads it as right to left like real Arabic
        val text: (String) -> String = if (rtl) { s -> if (s.isBlank()) s else "\u200F\u202E$s\u202C\u200F" } else ::accent
        val body = source(parsed.parts, inPlural = false, text)
        if (rtl) return body
        val length = parsed.parts.sumOf { (it as? IcuMessage.Text)?.text?.length ?: 4 }
        return "[$body ${"·".repeat((length * 2 + 4) / 5)}]"
    }

    /** Writes parts back as an ICU message, quoting syntax characters in text. */
    private fun source(parts: List<IcuMessage.Part>, inPlural: Boolean, text: (String) -> String): String = buildString {
        for (p in parts) when (p) {
            is IcuMessage.Text -> {
                var quoted = false
                for (c in text(p.text)) {
                    if (c == '{' || c == '}' || (inPlural && c == '#')) {
                        if (!quoted) { append('\''); quoted = true }
                        append(c)
                    } else {
                        if (quoted) { append('\''); quoted = false }
                        if (c == '\'') append("''") else append(c)
                    }
                }
                if (quoted) append('\'')
            }
            is IcuMessage.Arg -> append('{').append(p.name).append('}')
            is IcuMessage.NumberArg -> append('{').append(p.name).append(", number}")
            IcuMessage.Pound -> append('#')
            is IcuMessage.Plural -> {
                append('{').append(p.name).append(", plural,")
                if (p.offset != 0) append(" offset:").append(p.offset)
                p.options.forEach { (sel, sub) -> append(' ').append(sel).append(" {").append(source(sub, true, text)).append('}') }
                append('}')
            }
            is IcuMessage.Select -> {
                append('{').append(p.name).append(", select,")
                p.options.forEach { (sel, sub) -> append(' ').append(sel).append(" {").append(source(sub, false, text)).append('}') }
                append('}')
            }
        }
    }
}
