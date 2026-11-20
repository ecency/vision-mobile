package com.ecency.core.render.methods

import com.ecency.core.render.dom.HtmlRepair
import com.ecency.core.render.dom.HtmlStringFixes
import com.ecency.core.render.dom.RenderBodyParser
import com.ecency.core.render.dom.XmlDomParser
import com.ecency.core.render.dom.XmlSerializer
import com.ecency.core.render.js.Js
import com.ecency.core.render.markdown.BlockParser
import com.ecency.core.render.markdown.Markdown
import com.ecency.core.render.sanitize.HtmlSanitizer

/** render-helper `methods/markdown-to-html.method.ts` (2.5.54). */
internal object MarkdownToHtml {
    private val LEO_PREFIXES = listOf(
        "https://leofinance.io/threads/view/", "https://leofinance.io/posts/", "https://leofinance.io/threads/",
        "https://inleo.io/threads/view/", "https://inleo.io/posts/", "https://inleo.io/threads/",
    )

    fun markdownToHTML(source: String, context: RenderContext): String {
        var input = source
        for (prefix in LEO_PREFIXES) input = Js.replaceAll(input, prefix, "/@")
        if (input.isEmpty()) return ""

        val ampEscapes = input.contains("\\&") && !Helper.hasAmpSentinel(input)

        // App render: entities become placeholders (delimiter, index, delimiter) outside URLs (2.5.45, #2092); code
        // gets them back from the parser's own code tokens (2.5.53, #2154). The delimiter is a character the body
        // does not hold, so author text never reads as a placeholder.
        val entityPlaceholders = ArrayList<String>()
        var delimiter: String? = null
        if (context.forApp) {
            val entities = Scanners.entityMatches(input)
            if (entities.isNotEmpty()) {
                val chosen = EntityProtection.placeholderDelimiter(input)
                delimiter = chosen
                // The parser expands tabs to four-column stops; a placeholder is shorter than its entity, so a tab
                // after one would land on another stop: expand them first, exactly as the parser would.
                input = EntityProtection.expandTabsLikeRemarkable(input)
                val indexOf = HashMap<String, Int>()
                for (e in entities) if (e !in indexOf) {
                    indexOf[e] = entityPlaceholders.size
                    entityPlaceholders.add(e)
                }
                input = EntityProtection.protectInput(input, indexOf, chosen)
            }
        }
        val unswap: (String) -> String = { text -> if (delimiter == null) text else EntityProtection.restore(text, entityPlaceholders, delimiter) }
        val codeUnswap = if (delimiter != null) unswap else null
        // a's in-site path guard reads which delimiter this render chose (2.5.54, #2165)
        val ctx = context.withPlaceholderDelimiter(delimiter)
        fun renderMarkdown() = Markdown.render(input, ampEscapes, unswap, codeUnswap)

        val nonce = RenderBoundary.createRenderNonce()
        var output = ""
        try {
            output = renderMarkdown()
            output = HtmlStringFixes.fixBlockLevelTagsInParagraphs(output)
            val doc = XmlDomParser.parseFromString(RenderBodyParser.wrap(output))
            RenderBoundary.stripAuthorMarkers(doc)
            Traverse.traverse(doc, ctx, ImageState())
            output = XmlSerializer.serializeToString(doc, RenderBoundary.nodeFilter(nonce))
        } catch (e: Throwable) {
            // not reported: untrusted post markup the parser rejects; repaired below
            // Throwable, not Exception: render-helper also catches a stack overflow (RangeError) here.
            if (isFatal(e)) throw e
            try {
                val repaired = HtmlRepair.repair(HtmlSanitizer.sanitize(output, ctx.sanitizeOptions, beforeTransform = true))
                val doc = XmlDomParser.parseFromString(RenderBodyParser.wrap(repaired))
                RenderBoundary.stripAuthorMarkers(doc)
                Traverse.traverse(doc, ctx, ImageState())
                output = XmlSerializer.serializeToString(doc, RenderBoundary.nodeFilter(nonce))
            } catch (e2: Throwable) {
                if (isFatal(e2)) throw e2
                com.ecency.core.app.ErrorReports.quiet(e2, "render.markdownRepair")
                // the sanitize-only fallback skipped the transforms: the sanitizer applies their rules (#1919)
                output = HtmlSanitizer.sanitize(
                    output.ifEmpty { renderMarkdown() },
                    ctx.sanitizeOptions.copy(untransformed = true),
                )
            }
        }

        // The delimiter is not in the body, so every match is one of ours.
        if (output.isNotEmpty() && entityPlaceholders.isNotEmpty()) output = unswap(output)
        return RenderBodyParser.finish(output, ctx.sanitizeOptions.copy(renderNonce = nonce))
    }
}

/**
 * The app render's entity placeholders (render-helper 2.5.53 markdownToHTML): each distinct entity outside a URL
 * becomes `<delimiter><index><delimiter>`; an escaped one (`\\&amp;`) is left for the parser, as the web reads it.
 */
internal object EntityProtection {
    /** `PLACEHOLDER_DELIMITERS`: a zero-width space, then a few other invisibles. */
    private const val DELIMITERS = "\u200B\u2060\u2061\u2062\u2063\u2064"

    /** `PRIVATE_DELIMITERS_FROM` .. `PRIVATE_DELIMITERS_TO`: the private use area past AmpPairing's pair. */
    private const val PRIVATE_FROM = 0xE100
    private const val PRIVATE_TO = 0xF8FF

    /**
     * `placeholderDelimiter` (2.5.53, #2154): the first of [DELIMITERS] the body does not hold (raw or written as a
     * reference), then the first free private use character, and when the body holds every one of those, a pair of
     * private use characters it never holds in that order. Linear.
     */
    fun placeholderDelimiter(source: String): String {
        val input = if (source.indexOf('&') >= 0) source + Helper.decodeEntities(source) else source
        for (d in DELIMITERS) if (input.indexOf(d) < 0) return d.toString()
        val size = PRIVATE_TO - PRIVATE_FROM + 1
        val used = BooleanArray(size)
        for (c in input) if (c.code in PRIVATE_FROM..PRIVATE_TO) used[c.code - PRIVATE_FROM] = true
        val free = used.indexOfFirst { !it }
        if (free >= 0) return (PRIVATE_FROM + free).toChar().toString()
        val pairs = HashSet<Int>()
        for (i in 0 until input.length - 1) {
            val first = input[i].code - PRIVATE_FROM
            val second = input[i + 1].code - PRIVATE_FROM
            if (first in 0 until size && second in 0 until size) pairs.add(first * size + second)
        }
        // At most input.length pairs are taken, so a free one turns up within that.
        var pair = 0
        while (pair in pairs) pair++
        return charArrayOf((PRIVATE_FROM + pair / size).toChar(), (PRIVATE_FROM + pair % size).toChar()).concatToString()
    }

    /**
     * `expandTabsLikeRemarkable`: each tab as Remarkable's block parser will expand it, to the next four-column stop
     * of its line read after the parser's line-break and no-break-space normalisation. Only the tabs change.
     */
    fun expandTabsLikeRemarkable(src: String): String {
        if (src.indexOf('\t') < 0) return src
        val widths = ArrayList<String>()
        val normalized = BlockParser.normalize(src)
        var lineStart = 0
        var lastTabPos = 0
        for (offset in normalized.indices) {
            val c = normalized[offset]
            if (c == '\n') {
                lineStart = offset + 1
                lastTabPos = 0
            } else if (c == '\t') {
                widths.add("    ".substring((offset - lineStart - lastTabPos) % 4))
                lastTabPos = offset - lineStart + 1
            }
        }
        val sb = StringBuilder(src.length + widths.size * 3)
        var k = 0
        for (c in src) if (c == '\t') sb.append(widths[k++]) else sb.append(c)
        return sb.toString()
    }

    fun protectInput(input: String, indexOf: Map<String, Int>, delimiter: String): String {
        val sb = StringBuilder(input.length + 16)
        protect(input, indexOf, delimiter, sb)
        return sb.toString()
    }

    /** `swap` outside each `URL_RUN` match. */
    private fun protect(text: String, indexOf: Map<String, Int>, delimiter: String, sb: StringBuilder) {
        var last = 0
        var i = 0
        while (i < text.length) {
            val end = urlRunAt(text, i)
            if (end < 0) {
                i++
                continue
            }
            swap(text.substring(last, i), indexOf, delimiter, sb)
            sb.append(text, i, end)
            last = end
            i = end
        }
        swap(text.substring(last), indexOf, delimiter, sb)
    }

    private fun swap(text: String, indexOf: Map<String, Int>, delimiter: String, sb: StringBuilder) {
        var last = 0
        var amp = text.indexOf('&')
        while (amp >= 0) {
            val end = Scanners.entityEnd(text, amp)
            if (end < 0) {
                amp = text.indexOf('&', amp + 1)
                continue
            }
            sb.append(text, last, amp)
            if (escaped(text, amp)) sb.append(text, amp, end) else sb.append(delimiter).append(indexOf.getValue(text.substring(amp, end))).append(delimiter)
            last = end
            amp = text.indexOf('&', end)
        }
        sb.append(text, last, text.length)
    }

    /** An odd run of backslashes right before [at]. */
    private fun escaped(text: String, at: Int): Boolean {
        var k = at - 1
        while (k >= 0 && text[k] == '\\') k--
        return (at - 1 - k) % 2 == 1
    }

    private fun isUrlChar(c: Char) = !(Js.isSpace(c) || c == '<' || c == '>' || c == '"' || c == '\'' || c == '`' || c == '(' || c == ')')

    /**
     * `URL_RUN` at [i]: `/https?:\/\/(?:[^\s<>"'`()]|\([^\s<>"'`()]*\)|\()+/gi`, one level of balanced parentheses
     * or an unclosed `(` inside; the end, or -1.
     */
    private fun urlRunAt(s: String, i: Int): Int {
        if (!Js.regionMatchesLower(s, i, "http")) return -1
        var j = i + 4
        if (j < s.length && (s[j] == 's' || s[j] == 'S')) j++
        if (!s.startsWith("://", j)) return -1
        val start = j + 3
        var k = start
        while (k < s.length) {
            val c = s[k]
            if (isUrlChar(c)) {
                k++
                continue
            }
            if (c != '(') break
            var p = k + 1
            while (p < s.length && isUrlChar(s[p])) p++
            k = if (p < s.length && s[p] == ')') p + 1 else k + 1
        }
        return if (k > start) k else -1
    }

    /**
     * `unswap`: `/<delimiter>(0|[1-9]\d*)<delimiter>/g` back to the entities, one pass; a match whose index is not
     * ours stays as it is (the delimiter is not in the body, so that is never the author's text).
     */
    fun restore(output: String, entities: List<String>, delimiter: String): String {
        var i = output.indexOf(delimiter)
        if (i < 0) return output
        val d = delimiter.length
        val sb = StringBuilder(output.length)
        var last = 0
        while (i >= 0) {
            var j = i + d
            val ok = if (j < output.length && output[j] == '0') {
                j++
                true
            } else if (j < output.length && output[j] in '1'..'9') {
                while (j < output.length && output[j] in '0'..'9') j++
                true
            } else {
                false
            }
            if (!ok || !output.startsWith(delimiter, j)) {
                i = output.indexOf(delimiter, i + 1)
                continue
            }
            val digits = output.substring(i + d, j)
            val entity = if (digits.length > 9) null else entities.getOrNull(digits.toInt())
            val end = j + d
            sb.append(output, last, i).append(entity ?: output.substring(i, end))
            last = end
            i = output.indexOf(delimiter, end)
        }
        sb.append(output, last, output.length)
        return sb.toString()
    }
}
