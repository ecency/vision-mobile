package com.ecency.core.render.methods

import com.ecency.core.render.js.Js
import com.ecency.core.render.dom.HtmlRepair
import com.ecency.core.render.dom.HtmlStringFixes
import com.ecency.core.render.dom.RenderBodyParser
import com.ecency.core.render.dom.XmlDomParser
import com.ecency.core.render.dom.XmlSerializer
import com.ecency.core.render.markdown.Markdown
import com.ecency.core.render.sanitize.HtmlSanitizer

/** render-helper `methods/markdown-to-html.method.ts`. */
internal object MarkdownToHtml {
    private val LEO_PREFIXES = listOf(
        "https://leofinance.io/threads/view/", "https://leofinance.io/posts/", "https://leofinance.io/threads/",
        "https://inleo.io/threads/view/", "https://inleo.io/posts/", "https://inleo.io/threads/",
    )

    private fun placeholder(index: Int) = "​$index​"

    fun markdownToHTML(source: String, ctx: RenderContext): String {
        var input = source
        for (prefix in LEO_PREFIXES) input = Js.replaceAll(input, prefix, "/@")
        if (input.isEmpty()) return ""

        // Entities become zero-width-space placeholders so markdown and the DOM keep them verbatim.
        val entityPlaceholders = ArrayList<String>()
        if (ctx.forApp) {
            val entities = Scanners.entityMatches(input)
            if (entities.isNotEmpty()) {
                for ((index, entity) in LinkedHashSet(entities).withIndex()) {
                    entityPlaceholders.add(entity)
                    input = Js.replaceAll(input, entity, placeholder(index))
                }
            }
        }

        var output = ""
        try {
            output = Markdown.render(input)
            output = HtmlStringFixes.fixBlockLevelTagsInParagraphs(output)
            val doc = XmlDomParser.parseFromString(RenderBodyParser.wrap(output))
            Traverse.traverse(doc, ctx, ImageState())
            output = XmlSerializer.serializeToString(doc)
        } catch (e: Throwable) {
            // Throwable, not Exception: render-helper also catches a stack overflow (RangeError)
            // here. The port's parsers and traversal are iterative, but a JVM StackOverflowError
            // from any remaining recursion takes the same fallback instead of escaping.
            if (isFatal(e)) throw e
            // xmldom rejected the markup, or a transform threw: sanitize, repair with the htmlparser2
            // port, parse and transform again; if that fails too, only sanitize.
            try {
                val repaired = HtmlRepair.repair(HtmlSanitizer.sanitize(output, ctx.sanitizeOptions, beforeTransform = true))
                val doc = XmlDomParser.parseFromString(RenderBodyParser.wrap(repaired))
                Traverse.traverse(doc, ctx, ImageState())
                output = XmlSerializer.serializeToString(doc)
            } catch (e2: Throwable) {
                if (isFatal(e2)) throw e2
                output = HtmlSanitizer.sanitize(output.ifEmpty { Markdown.render(input) }, ctx.sanitizeOptions)
            }
        }

        if (ctx.forApp && output.isNotEmpty() && entityPlaceholders.isNotEmpty()) {
            entityPlaceholders.forEachIndexed { index, entity -> output = Js.replaceAll(output, placeholder(index), entity) }
        }
        return RenderBodyParser.finish(output, ctx.sanitizeOptions)
    }
}
