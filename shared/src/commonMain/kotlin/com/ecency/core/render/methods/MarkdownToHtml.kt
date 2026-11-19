package com.ecency.core.render.methods

import com.ecency.core.render.dom.HtmlRepair
import com.ecency.core.render.dom.HtmlStringFixes
import com.ecency.core.render.dom.RenderBodyParser
import com.ecency.core.render.dom.XmlDomParser
import com.ecency.core.render.dom.XmlSerializer
import com.ecency.core.render.js.Js
import com.ecency.core.render.markdown.Markdown
import com.ecency.core.render.sanitize.HtmlSanitizer

/** render-helper `methods/markdown-to-html.method.ts` (2.5.52). */
internal object MarkdownToHtml {
    private val LEO_PREFIXES = listOf(
        "https://leofinance.io/threads/view/", "https://leofinance.io/posts/", "https://leofinance.io/threads/",
        "https://inleo.io/threads/view/", "https://inleo.io/posts/", "https://inleo.io/threads/",
    )

    fun markdownToHTML(source: String, ctx: RenderContext): String {
        var input = source
        for (prefix in LEO_PREFIXES) input = Js.replaceAll(input, prefix, "/@")
        if (input.isEmpty()) return ""

        val ampEscapes = input.contains("\\&") && !Helper.hasAmpSentinel(input)

        // App render: entities become zero-width-space placeholders, outside URLs and code (2.5.45, #2092, #2093).
        val entityPlaceholders = ArrayList<String>()
        if (ctx.forApp) {
            val entities = Scanners.entityMatches(input)
            if (entities.isNotEmpty()) {
                val indexOf = HashMap<String, Int>()
                for (e in entities) if (e !in indexOf) {
                    indexOf[e] = entityPlaceholders.size
                    entityPlaceholders.add(e)
                }
                input = EntityProtection.protectInput(input, indexOf)
            }
        }

        val nonce = RenderBoundary.createRenderNonce()
        var output = ""
        try {
            output = Markdown.render(input, ampEscapes)
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
                    output.ifEmpty { Markdown.render(input, ampEscapes) },
                    ctx.sanitizeOptions.copy(untransformed = true),
                )
            }
        }

        if (ctx.forApp && output.isNotEmpty() && entityPlaceholders.isNotEmpty()) {
            output = EntityProtection.restore(output, entityPlaceholders)
        }
        return RenderBodyParser.finish(output, ctx.sanitizeOptions.copy(renderNonce = nonce))
    }
}

/**
 * The app render's entity placeholders (render-helper 2.5.45 markdownToHTML): each distinct entity outside a URL and
 * outside code becomes `​<index>​`; an escaped one (`\&amp;`) is left for the parser, as the web reads it.
 */
internal object EntityProtection {
    private const val ZWSP = '​'

    fun protectInput(input: String, indexOf: Map<String, Int>): String {
        val sb = StringBuilder(input.length + 16)
        var last = 0
        for ((start, end) in CodeRanges.ranges(input)) {
            if (start < last) continue
            protect(input.substring(last, start), indexOf, sb)
            sb.append(input, start, end)
            last = end
        }
        protect(input.substring(last), indexOf, sb)
        return sb.toString()
    }

    /** `swap` outside each `URL_RUN` match. */
    private fun protect(text: String, indexOf: Map<String, Int>, sb: StringBuilder) {
        var last = 0
        var i = 0
        while (i < text.length) {
            val end = urlRunAt(text, i)
            if (end < 0) {
                i++
                continue
            }
            swap(text.substring(last, i), indexOf, sb)
            sb.append(text, i, end)
            last = end
            i = end
        }
        swap(text.substring(last), indexOf, sb)
    }

    private fun swap(text: String, indexOf: Map<String, Int>, sb: StringBuilder) {
        var last = 0
        var amp = text.indexOf('&')
        while (amp >= 0) {
            val end = Scanners.entityEnd(text, amp)
            if (end < 0) {
                amp = text.indexOf('&', amp + 1)
                continue
            }
            sb.append(text, last, amp)
            if (escaped(text, amp)) sb.append(text, amp, end) else sb.append(ZWSP).append(indexOf.getValue(text.substring(amp, end))).append(ZWSP)
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

    /** `/​(0|[1-9]\d*)​/g` back to the entities; an index that is not ours is the author's text. */
    fun restore(output: String, entities: List<String>): String {
        val sb = StringBuilder(output.length)
        var last = 0
        var i = output.indexOf(ZWSP)
        while (i >= 0) {
            var j = i + 1
            val ok = if (j < output.length && output[j] == '0') {
                j++
                true
            } else if (j < output.length && output[j] in '1'..'9') {
                while (j < output.length && output[j] in '0'..'9') j++
                true
            } else {
                false
            }
            val entity = if (ok && j < output.length && output[j] == ZWSP) {
                val digits = output.substring(i + 1, j)
                if (digits.length > 9) null else entities.getOrNull(digits.toInt())
            } else {
                null
            }
            if (entity == null) {
                i = output.indexOf(ZWSP, i + 1)
                continue
            }
            sb.append(output, last, i).append(entity)
            last = j + 1
            i = output.indexOf(ZWSP, last)
        }
        sb.append(output, last, output.length)
        return sb.toString()
    }
}

/**
 * render-helper 2.5.46+ `codeRanges`: code in the markdown source as [start, end) ranges in order: fenced blocks
 * (inside blockquotes and list items too, to the matching close, the end of their container, or the end) and inline
 * code spans (a backtick run to the next run of the same length). Linear.
 */
internal object CodeRanges {
    private const val QUOTE = -1

    private fun isSpaceAt(line: String, at: Int) = at in line.indices && line[at] == ' '
    private fun charAt(line: String, at: Int): Char? = if (at in line.indices) line[at] else null

    class Read(val containers: List<Int>, val at: Int, val column: Int, val carry: Int)

    fun readContainers(line: String, from: Int = 0, fromColumn: Int = 0, carriedIndent: Int = 0): Read {
        val containers = ArrayList<Int>()
        var at = from
        var column = fromColumn
        var first = true
        while (true) {
            var indent = if (first) carriedIndent else 0
            first = false
            val spacesFrom = indent
            while (indent < 4 && isSpaceAt(line, at + indent - spacesFrom)) indent++
            if (indent > 3) break
            val markerAt = at + indent - spacesFrom
            val c = charAt(line, markerAt)
            if (c == '>') {
                containers.add(QUOTE)
                at = markerAt + 1
                column += indent - spacesFrom + 1
                if (isSpaceAt(line, at)) {
                    at++
                    column++
                }
                continue
            }
            var markerLength = 0
            if (c == '-' || c == '*' || c == '+') {
                markerLength = 1
            } else {
                var d = markerAt
                while (d - markerAt < 9 && d in line.indices && line[d] in '0'..'9') d++
                if (d > markerAt && (charAt(line, d) == '.' || charAt(line, d) == ')')) markerLength = d - markerAt + 1
            }
            if (markerLength == 0) break
            val afterMarker = column + indent - spacesFrom + markerLength
            var gapColumns = 0
            var gapChars = 0
            while (gapColumns < 5) {
                val g = charAt(line, markerAt + markerLength + gapChars)
                if (g == ' ') gapColumns++
                else if (g == '\t') gapColumns += 4 - ((afterMarker + gapColumns) % 4)
                else break
                gapChars++
            }
            if (gapChars == 0) break
            val wide = gapColumns >= 5
            val width = indent + markerLength + (if (wide) 1 else gapColumns)
            containers.add(width)
            at = markerAt + markerLength + (if (wide) 1 else gapChars)
            column += width - spacesFrom
            if (wide) {
                if (charAt(line, at - 1) == '\t') {
                    val tabWidth = 4 - (afterMarker % 4)
                    return Read(containers, at, column + tabWidth - 1, tabWidth - 1)
                }
                break
            }
        }
        return Read(containers, at, column, if (containers.isEmpty()) carriedIndent else 0)
    }

    class Inside(val at: Int, val column: Int, val carry: Int, val matched: Int, val prefixAt: Int)

    fun insideContainersAt(line: String, containers: List<Int>, blank: Boolean): Inside {
        var at = 0
        var column = 0
        var carry = 0
        for (k in containers.indices) {
            val container = containers[k]
            val prefix = Inside(-1, column, carry, k, at)
            if (container == QUOTE) {
                var indent = 0
                while (indent < 4 && isSpaceAt(line, at + indent)) indent++
                if (indent > 3 || charAt(line, at + indent) != '>') return prefix
                at += indent + 1
                column += indent + 1
                carry = 0
                if (isSpaceAt(line, at)) {
                    at++
                    column++
                }
            } else {
                if (blank) return Inside(line.length, column, 0, containers.size, line.length)
                var needed = container - carry
                carry = 0
                while (needed > 0) {
                    if (isSpaceAt(line, at)) {
                        at++
                        column++
                        needed--
                    } else if (charAt(line, at) == '\t') {
                        val width = 4 - (column % 4)
                        at++
                        column += width
                        needed -= width
                    } else {
                        return prefix
                    }
                }
                carry = -needed
            }
        }
        return Inside(at, column, carry, containers.size, at)
    }

    class Fence(val char: Char, val length: Int, val end: Int)

    fun fenceAt(line: String, at: Int, column: Int = 0, carried: Int = 0): Fence? {
        var indent = carried
        var col = column
        var start = at
        while (indent <= 3) {
            if (isSpaceAt(line, start)) {
                indent++
                col++
            } else if (charAt(line, start) == '\t') {
                val width = 4 - (col % 4)
                indent += width
                col += width
            } else {
                break
            }
            start++
        }
        if (indent > 3) return null
        val ch = charAt(line, start)
        if (ch != '`' && ch != '~') return null
        var end = start
        while (charAt(line, end) == ch) end++
        return if (end - start >= 3) Fence(ch, end - start, end) else null
    }

    fun ranges(src: String): List<Pair<Int, Int>> {
        val ranges = ArrayList<Pair<Int, Int>>()
        val runs = ArrayList<IntArray>()
        var item: List<Int>? = null
        var pos = 0
        while (pos < src.length) {
            val eol = src.indexOf('\n', pos)
            val lineEnd = if (eol == -1) src.length else eol
            val line = src.substring(pos, lineEnd)
            val blank = Js.trim(line).isEmpty()
            var containers: List<Int> = emptyList()
            var open: Fence? = null
            val read = readContainers(line)
            if (read.containers.isNotEmpty()) {
                containers = read.containers
                open = fenceAt(line, read.at, read.column, read.carry)
                item = if (containers.any { it != QUOTE }) containers else null
            } else if (!blank) {
                val it0 = item
                if (it0 != null) {
                    var cur: List<Int> = it0
                    var inside = insideContainersAt(line, cur, false)
                    if (inside.at == -1 && inside.matched > 0) {
                        cur = cur.subList(0, inside.matched).toList()
                        item = cur
                        inside = Inside(inside.prefixAt, inside.column, inside.carry, inside.matched, inside.prefixAt)
                    }
                    if (inside.at == -1) {
                        item = null
                    } else {
                        val nested = readContainers(line, inside.at, inside.column, inside.carry)
                        if (nested.containers.isNotEmpty()) {
                            containers = cur + nested.containers
                            open = fenceAt(line, nested.at, nested.column, nested.carry)
                            if (nested.containers.any { it != QUOTE }) item = containers
                        } else {
                            open = fenceAt(line, inside.at, inside.column, inside.carry)
                            if (open != null) containers = cur
                        }
                    }
                }
                if (open == null) open = fenceAt(line, 0)
            }
            if (open != null) {
                val fence = open
                var end = src.length
                var p = lineEnd + 1
                while (p < src.length) {
                    val e = src.indexOf('\n', p)
                    val le = if (e == -1) src.length else e
                    val next = src.substring(p, le)
                    val inside = insideContainersAt(next, containers, Js.trim(next).isEmpty())
                    val at = inside.at
                    if (at == -1) {
                        end = p - 1
                        break
                    }
                    val close = fenceAt(next, at, inside.column, inside.carry)
                    if (close != null && close.char == fence.char && close.length >= fence.length && Js.trim(next.substring(close.end)).isEmpty()) {
                        end = le
                        break
                    }
                    p = le + 1
                }
                ranges.add(pos to end)
                pos = end + 1
                continue
            }
            var i = pos
            while (i < lineEnd) {
                if (src[i] != '`') {
                    i++
                    continue
                }
                var j = i
                while (j < lineEnd && src[j] == '`') j++
                runs.add(intArrayOf(i, j - i))
                i = j
            }
            pos = lineEnd + 1
        }
        val nextSame = IntArray(runs.size) { -1 }
        val lastOfLength = HashMap<Int, Int>()
        for (k in runs.size - 1 downTo 0) {
            nextSame[k] = lastOfLength[runs[k][1]] ?: -1
            lastOfLength[runs[k][1]] = k
        }
        var k = 0
        while (k < runs.size) {
            val close = nextSame[k]
            if (close == -1) {
                k++
                continue
            }
            ranges.add(runs[k][0] to runs[close][0] + runs[close][1])
            k = close + 1
        }
        return ranges.sortedBy { it.first }
    }
}
