package com.ecency.core.render.dom

import com.ecency.core.render.js.Js
import com.ecency.core.render.sanitize.HtmlSanitizer
import com.ecency.core.render.sanitize.SanitizeOptions

/**
 * The HTML half of render-helper's `markdownToHTML`, around its DOM transforms:
 *
 * 1. [parse]: `DOMParser.parseFromString('<body id="root">' + removeDuplicateAttributes(html) + '</body>')`.
 *    When xmldom throws, [parseRepaired] runs render-helper's recovery (sanitize, htmlparser2
 *    repair, parse again), and when that throws too the body is only sanitized.
 * 2. The caller runs the transforms on [ParsedBody.document] and serializes it with
 *    [XmlSerializer]. If a transform throws, render-helper falls back to [parseRepaired].
 * 3. [finish]: the post-serialize string cleanup and the final `sanitizeHtml`.
 *
 * [html] is the markdown output after [HtmlStringFixes.fixBlockLevelTagsInParagraphs].
 */
object RenderBodyParser {
    enum class Path {
        /** xmldom accepted the body as is. */
        XMLDOM,

        /** xmldom rejected it; the sanitized, htmlparser2-repaired body parsed. */
        REPAIRED,

        /** Both parses failed: [ParsedBody.sanitizedHtml] is the sanitized body, no DOM. */
        SANITIZED,
    }

    class ParsedBody internal constructor(val path: Path, val document: Document?, val sanitizedHtml: String?)

    fun wrap(html: String): String = "<body id=\"root\">" + HtmlStringFixes.removeDuplicateAttributes(html) + "</body>"

    fun parse(html: String, options: SanitizeOptions = SanitizeOptions()): ParsedBody = try {
        ParsedBody(Path.XMLDOM, XmlDomParser.parseFromString(wrap(html)), null)
    } catch (e: DomParseException) {
        parseRepaired(html, options)
    }

    fun parseRepaired(html: String, options: SanitizeOptions = SanitizeOptions()): ParsedBody = try {
        val repaired = HtmlRepair.repair(HtmlSanitizer.sanitize(html, options, beforeTransform = true))
        ParsedBody(Path.REPAIRED, XmlDomParser.parseFromString(wrap(repaired)), null)
    } catch (e: DomParseException) {
        ParsedBody(Path.SANITIZED, null, HtmlSanitizer.sanitize(html, options))
    }

    /** Serialized document (or the sanitized fallback) to the final post HTML. */
    fun finish(output: String, options: SanitizeOptions = SanitizeOptions()): String =
        HtmlSanitizer.sanitize(cleanup(output), options)

    /** Parse, serialize without transforms, finish: the pipeline minus the DOM transforms. */
    fun roundTrip(html: String, options: SanitizeOptions = SanitizeOptions()): String {
        val parsed = parse(html, options)
        val serialized = parsed.document?.let { XmlSerializer.serializeToString(it) } ?: parsed.sanitizedHtml!!
        return finish(serialized, options)
    }

    /** The regex chain render-helper applies to the serialized document, then `trim()`. */
    internal fun cleanup(input: String): String {
        var s = removeXhtmlNs(input)
        if (s.startsWith("<?xml")) {
            val q = s.indexOf('?', 5)
            if (q >= 0 && q + 1 < s.length && s[q + 1] == '>') s = s.substring(q + 2)
        }
        if (Js.regionMatchesLower(s, 0, "<!doctype")) {
            val gt = s.indexOf('>')
            if (gt >= 0) s = s.substring(gt + 1)
        }
        s = removeHtmlTags(s)
        s = removeHeads(s)
        s = replaceFirst(s, "<body id=\"root\">")
        s = replaceFirst(s, "</body>")
        return Js.trim(s)
    }

    private fun replaceFirst(s: String, lit: String): String {
        val i = s.indexOf(lit)
        return if (i < 0) s else s.substring(0, i) + s.substring(i + lit.length)
    }

    private fun isLineTerminator(c: Char) = c == '\n' || c == '\r' || c == ' ' || c == ' '

    /** `/ xmlns="http:\/\/www.w3.org\/1999\/xhtml"/g` (the dots match any non-newline char). */
    private fun removeXhtmlNs(s: String): String {
        val head = " xmlns=\"http://www"
        var i = s.indexOf(head)
        if (i < 0) return s
        val sb = StringBuilder(s.length)
        var last = 0
        while (i >= 0) {
            val a = i + head.length
            val matched = a + 19 <= s.length && !isLineTerminator(s[a]) && s.startsWith("w3", a + 1) &&
                !isLineTerminator(s[a + 3]) && s.startsWith("org/1999/xhtml\"", a + 4)
            if (matched) {
                sb.append(s, last, i)
                last = a + 19
                i = s.indexOf(head, last)
            } else {
                i = s.indexOf(head, i + 1)
            }
        }
        sb.append(s, last, s.length)
        return sb.toString()
    }

    /** `/<\/?html[^>]*>/g`. */
    private fun removeHtmlTags(s: String): String {
        var i = s.indexOf("html")
        if (i < 0) return s
        val sb = StringBuilder(s.length)
        var last = 0
        var from = 0
        while (true) {
            val lt = s.indexOf('<', from)
            if (lt < 0) break
            val nameAt = if (lt + 1 < s.length && s[lt + 1] == '/') lt + 2 else lt + 1
            if (s.startsWith("html", nameAt)) {
                val gt = s.indexOf('>', nameAt + 4)
                if (gt < 0) break // no '>' left, so no later "<html" can match either
                sb.append(s, last, lt)
                last = gt + 1
                from = gt + 1
                continue
            }
            from = lt + 1
        }
        sb.append(s, last, s.length)
        return sb.toString()
    }

    /** `/<head[^>]*>[\s\S]*?<\/head>/g`. */
    private fun removeHeads(s: String): String {
        if (s.indexOf("<head") < 0) return s
        val sb = StringBuilder(s.length)
        var last = 0
        var from = 0
        while (true) {
            val i = s.indexOf("<head", from)
            if (i < 0) break
            val gt = s.indexOf('>', i + 5)
            val close = if (gt < 0) -1 else s.indexOf("</head>", gt + 1)
            if (close >= 0) {
                sb.append(s, last, i)
                last = close + 7
                from = close + 7
            } else if (gt >= 0) {
                break // no "</head>" after this point, so no later "<head" can match either
            } else {
                from = i + 1
            }
        }
        sb.append(s, last, s.length)
        return sb.toString()
    }
}
