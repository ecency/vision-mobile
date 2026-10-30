package com.ecency.core.render.markdown

import com.ecency.core.render.js.Js
/**
 * remarkable's Renderer with its default rules and render-helper's options: html on, xhtmlOut off,
 * breaks on, langPrefix "language-", no linkTarget, and a highlight function that returns the code
 * unchanged (so fenced code is emitted unescaped, as in the shipped app).
 */
internal object Renderer {
    private const val LANG_PREFIX = "language-"

    /**
     * [rawFences]: render-helper's highlight returns the code unchanged (true); without a
     * highlight function (remarkable's default preset) fenced code is escaped (false).
     */
    fun render(tokens: List<Token>, rawFences: Boolean = true): String {
        val sb = StringBuilder()
        for (i in tokens.indices) {
            val t = tokens[i]
            if (t.type == "inline") renderInline(t.children, sb, rawFences) else rule(tokens, i, sb, rawFences)
        }
        return sb.toString()
    }

    private fun renderInline(tokens: List<Token>, sb: StringBuilder, rawFences: Boolean) {
        for (i in tokens.indices) rule(tokens, i, sb, rawFences)
    }

    private fun nextToken(tokens: List<Token>, idxIn: Int): Int {
        val idx = idxIn + 1
        if (idx >= tokens.size - 2) return idx
        if (tokens[idx].type == "paragraph_open" && tokens[idx].tight &&
            tokens[idx + 1].type == "inline" && tokens[idx + 1].content.isEmpty() &&
            tokens[idx + 2].type == "paragraph_close" && tokens[idx + 2].tight
        ) {
            return nextToken(tokens, idx + 2)
        }
        return idx
    }

    private fun getBreak(tokens: List<Token>, idxIn: Int): String {
        val idx = nextToken(tokens, idxIn)
        if (idx < tokens.size && tokens[idx].type == "list_item_close") return ""
        return "\n"
    }

    private fun escape(s: String) = Utils.escapeHtml(s)

    /** `Number(id + 1).toString()` and the `fnref` id with its sub id. */
    private fun refId(t: Token): Pair<String, String> {
        val n = (t.id + 1).toString()
        var id = "fnref$n"
        if (t.subId > 0) id += ":" + t.subId
        return n to id
    }

    private fun rule(tokens: List<Token>, idx: Int, sb: StringBuilder, rawFences: Boolean) {
        val t = tokens[idx]
        when (t.type) {
            "blockquote_open" -> sb.append("<blockquote>\n")
            "blockquote_close" -> sb.append("</blockquote>").append(getBreak(tokens, idx))
            "code" -> if (t.block) {
                sb.append("<pre><code>").append(escape(t.content)).append("</code></pre>").append(getBreak(tokens, idx))
            } else {
                sb.append("<code>").append(escape(t.content)).append("</code>")
            }
            "fence" -> {
                var langClass = ""
                if (t.params.isNotEmpty()) {
                    val fenceName = Js.splitSpaces(t.params).joinToString(" ")
                    val langName = escape(Utils.replaceEntities(Utils.unescapeMd(fenceName)))
                    langClass = " class=\"$LANG_PREFIX$langName\""
                }
                // highlight(str) returns str; `|| escapeHtml(content)` only kicks in for "".
                val highlighted = if (rawFences) t.content.ifEmpty { escape(t.content) } else escape(t.content)
                sb.append("<pre><code").append(langClass).append('>').append(highlighted).append("</code></pre>").append(getBreak(tokens, idx))
            }
            "heading_open" -> sb.append("<h").append(t.hLevel).append('>')
            "heading_close" -> sb.append("</h").append(t.hLevel).append(">\n")
            "hr" -> sb.append("<hr>").append(getBreak(tokens, idx))
            "bullet_list_open" -> sb.append("<ul>\n")
            "bullet_list_close" -> sb.append("</ul>").append(getBreak(tokens, idx))
            "list_item_open" -> sb.append("<li>")
            "list_item_close" -> sb.append("</li>\n")
            "ordered_list_open" -> {
                val order = t.order.trimStart('0')
                val start = if (order.isNotEmpty() && order != "1") " start=\"" + Js.numberToString(t.order) + "\"" else ""
                sb.append("<ol").append(start).append(">\n")
            }
            "ordered_list_close" -> sb.append("</ol>").append(getBreak(tokens, idx))
            "paragraph_open" -> if (!t.tight) sb.append("<p>")
            "paragraph_close" -> {
                val addBreak = !(t.tight && idx != 0 && tokens[idx - 1].type == "inline" && tokens[idx - 1].content.isEmpty())
                if (!t.tight) sb.append("</p>")
                if (addBreak) sb.append(getBreak(tokens, idx))
            }
            "link_open" -> {
                sb.append("<a href=\"").append(escape(t.href)).append('"')
                val title = t.title
                if (!title.isNullOrEmpty()) sb.append(" title=\"").append(escape(Utils.replaceEntities(title))).append('"')
                sb.append('>')
            }
            "link_close" -> sb.append("</a>")
            "image" -> {
                sb.append("<img src=\"").append(escape(t.src)).append('"')
                sb.append(" alt=\"").append(if (t.alt.isNotEmpty()) escape(Utils.replaceEntities(Utils.unescapeMd(t.alt))) else "").append('"')
                val title = t.title
                if (!title.isNullOrEmpty()) sb.append(" title=\"").append(escape(Utils.replaceEntities(title))).append('"')
                sb.append('>')
            }
            "table_open" -> sb.append("<table>\n")
            "table_close" -> sb.append("</table>\n")
            "thead_open" -> sb.append("<thead>\n")
            "thead_close" -> sb.append("</thead>\n")
            "tbody_open" -> sb.append("<tbody>\n")
            "tbody_close" -> sb.append("</tbody>\n")
            "tr_open" -> sb.append("<tr>")
            "tr_close" -> sb.append("</tr>\n")
            "th_open" -> sb.append("<th").append(if (t.align.isNotEmpty()) " style=\"text-align:${t.align}\"" else "").append('>')
            "th_close" -> sb.append("</th>")
            "td_open" -> sb.append("<td").append(if (t.align.isNotEmpty()) " style=\"text-align:${t.align}\"" else "").append('>')
            "td_close" -> sb.append("</td>")
            "strong_open" -> sb.append("<strong>")
            "strong_close" -> sb.append("</strong>")
            "em_open" -> sb.append("<em>")
            "em_close" -> sb.append("</em>")
            "del_open" -> sb.append("<del>")
            "del_close" -> sb.append("</del>")
            "ins_open" -> sb.append("<ins>")
            "ins_close" -> sb.append("</ins>")
            "mark_open" -> sb.append("<mark>")
            "mark_close" -> sb.append("</mark>")
            "sub" -> sb.append("<sub>").append(escape(t.content)).append("</sub>")
            "sup" -> sb.append("<sup>").append(escape(t.content)).append("</sup>")
            "hardbreak" -> sb.append("<br>\n")
            "softbreak" -> sb.append("<br>\n")
            "text" -> sb.append(escape(t.content))
            "htmlblock", "htmltag" -> sb.append(t.content)
            "abbr_open" -> sb.append("<abbr title=\"").append(escape(Utils.replaceEntities(t.title ?: "undefined"))).append("\">")
            "abbr_close" -> sb.append("</abbr>")
            "footnote_ref" -> {
                val (n, id) = refId(t)
                sb.append("<sup class=\"footnote-ref\"><a href=\"#fn").append(n).append("\" id=\"").append(id).append("\">[").append(n).append("]</a></sup>")
            }
            "footnote_block_open" -> sb.append("<hr class=\"footnotes-sep\">\n<section class=\"footnotes\">\n<ol class=\"footnotes-list\">\n")
            "footnote_block_close" -> sb.append("</ol>\n</section>\n")
            "footnote_open" -> sb.append("<li id=\"fn").append(t.id + 1).append("\"  class=\"footnote-item\">")
            "footnote_close" -> sb.append("</li>\n")
            "footnote_anchor" -> {
                val (_, id) = refId(t)
                sb.append(" <a href=\"#").append(id).append("\" class=\"footnote-backref\">↩</a>")
            }
            "dl_open" -> sb.append("<dl>\n")
            "dt_open" -> sb.append("<dt>")
            "dd_open" -> sb.append("<dd>")
            "dl_close" -> sb.append("</dl>\n")
            "dt_close" -> sb.append("</dt>\n")
            "dd_close" -> sb.append("</dd>\n")
            else -> throw IllegalStateException("no render rule for ${t.type}")
        }
    }
}
