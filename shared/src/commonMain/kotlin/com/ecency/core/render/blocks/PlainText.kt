package com.ecency.core.render.blocks

/**
 * Plain text of a run of inlines, as handed to the copy sheet on a paragraph long-press. Chips read
 * as `@user` and `#tag`, line breaks stay, and runs of spaces collapse to one (the RN app passes the
 * paragraph through postBodySummary, which does the same).
 */
fun List<Inline>.plainText(): String {
    val sb = StringBuilder()
    fun walk(list: List<Inline>) {
        for (inline in list) when (inline) {
            is Inline.Text -> sb.append(inline.text)
            is Inline.Link -> walk(inline.children)
            is Inline.Mention -> sb.append('@').append(inline.author)
            is Inline.Tag -> sb.append('#').append(inline.tag)
            Inline.LineBreak -> sb.append('\n')
        }
    }
    walk(this)
    return sb.toString()
        .replace(Regex("[ \\t\\u00A0]+"), " ")
        .replace(Regex(" ?\\n ?"), "\n")
        .trim()
}

/**
 * Every image URL in a body in document order, once each: the list the gallery pages through. Images
 * inside a link are left out, since tapping them follows the link instead of opening the gallery.
 */
fun List<Block>.imageUrls(): List<String> {
    val out = LinkedHashSet<String>()
    fun walk(b: Block) {
        when (b) {
            is Block.Image -> if (b.link == null) out += b.src
            is Block.ImageGrid -> b.images.forEach(::walk)
            is Block.Quote -> b.blocks.forEach(::walk)
            is Block.ListBlock -> b.items.flatten().forEach(::walk)
            is Block.Table -> b.rows.forEach { r -> r.cells.forEach { c -> c.blocks.forEach(::walk) } }
            is Block.Container -> b.blocks.forEach(::walk)
            is Block.Details -> b.blocks.forEach(::walk)
            else -> {}
        }
    }
    forEach(::walk)
    return out.toList()
}

private val HIVE_ACCOUNT = Regex("^[a-z][a-z0-9-]+[a-z0-9](\\.[a-z][a-z0-9-]+[a-z0-9])*$")

/**
 * True for a valid Hive account name (3 to 16 chars; dot separated segments of 3 or more that start
 * with a letter), so it is safe to put in a URL path such as the avatar URL.
 */
fun isHiveAccountName(name: String): Boolean = name.length in 3..16 && HIVE_ACCOUNT.matches(name)

/**
 * True when a body reads right to left: its first strong character (a letter, skipping mentions, tags, links' URLs,
 * digits and punctuation) is Hebrew, Arabic, Syriac, Thaana, N'Ko or another right to left script, as HTML's
 * `dir="auto"` decides. The post body takes this direction, not the app's (README "Localization", RTL): an English
 * post stays left to right in an Arabic UI and an Arabic post reads right to left in an English one. Mentions and tags
 * are skipped because `@user` is Latin in every language; a body without letters is left to right.
 */
fun List<Block>.isRtlContent(): Boolean = firstStrongRtl(this) ?: false

private fun firstStrongRtl(blocks: List<Block>): Boolean? {
    for (b in blocks) {
        val found = when (b) {
            is Block.Paragraph -> firstStrongRtlInline(b.inlines)
            is Block.Heading -> firstStrongRtlInline(b.inlines)
            is Block.Quote -> firstStrongRtl(b.blocks)
            is Block.ListBlock -> firstStrongRtl(b.items.flatten())
            is Block.Table -> firstStrongRtl(b.rows.flatMap { r -> r.cells.flatMap { it.blocks } })
            is Block.Container -> firstStrongRtl(b.blocks)
            is Block.Details -> firstStrongRtlInline(b.summary) ?: firstStrongRtl(b.blocks)
            is Block.Image -> b.alt?.let(::firstStrongRtlText)
            else -> null
        }
        if (found != null) return found
    }
    return null
}

private fun firstStrongRtlInline(inlines: List<Inline>): Boolean? {
    for (i in inlines) {
        val found = when (i) {
            is Inline.Text -> firstStrongRtlText(i.text)
            is Inline.Link -> firstStrongRtlInline(i.children)
            else -> null
        }
        if (found != null) return found
    }
    return null
}

/**
 * Direction of the first strong character of [text]: true right to left, false left to right, null none. Only
 * letters are strong, as in UAX #9 (HTML `dir="auto"`): digits (Arabic-Indic and Persian too), combining marks,
 * punctuation, symbols and emoji are skipped, so "٢٠٢٤ Year in review" is left to right. Right to left scripts outside
 * the BMP count too (Adlam, Hanifi Rohingya, Mende Kikakui and the old scripts in U+10800 to U+10FFF), except their
 * digits and marks.
 */
fun firstStrongRtlText(text: String): Boolean? {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            val cp = 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
            supplementaryStrongRtl(cp)?.let { return it }
            i += 2
            continue
        }
        if (c.isLetter()) return isRtlChar(c)
        i++
    }
    return null
}

private fun isRtlChar(c: Char): Boolean {
    val code = c.code
    return code in 0x0590..0x08FF || code in 0xFB1D..0xFDFF || code in 0xFE70..0xFEFF
}

/** A supplementary code point's strong direction (true right to left), or null when it is weak or neutral. */
private fun supplementaryStrongRtl(cp: Int): Boolean? = when {
    cp in 0x10D30..0x10D39 || cp in 0x10D24..0x10D27 -> null // Hanifi Rohingya digits and marks
    cp in 0x1E950..0x1E959 || cp in 0x1E944..0x1E94B -> null // Adlam digits and marks
    cp in 0x1E8D0..0x1E8D6 -> null // Mende Kikakui marks
    cp in 0x10800..0x10FFF || cp in 0x1E800..0x1EFFF -> true
    cp in 0x1D400..0x1D7FF -> false // mathematical Latin and Greek letters
    else -> null
}
