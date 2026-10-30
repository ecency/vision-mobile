package com.ecency.core.render.dom

import com.ecency.core.render.js.Js

/**
 * Last-resort tree builder for markup that both xmldom and render-helper's repair path reject.
 * It never fails: tags are matched with a simple open-element stack (an end tag closes up to its
 * open match, unmatched end tags are ignored), void elements self-close, and text and attribute
 * values are entity-decoded. Only the native block builder uses it, so links and images survive
 * where the old fallback kept plain text only.
 */
object LenientHtml {
    private val TAG = Regex("<(/?)([A-Za-z][A-Za-z0-9-]*)((?:\\s+[^\\s=/>\"']+(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+))?)*)\\s*(/?)>")
    private val ATTR = Regex("([^\\s=/>\"']+)(?:\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+))?")
    private val COMMENT = Regex("<!--[\\s\\S]*?(?:-->|$)|<![^>]*>|<\\?[^>]*>")
    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
    private val RAW = setOf("script", "style", "textarea", "title")

    fun parse(html: String): Document {
        val doc = Document()
        val body = doc.createElement("body")
        doc.appendChild(body)
        val stack = ArrayList<Element>()
        stack += body
        val src = COMMENT.replace(html, "")
        var pos = 0
        fun text(s: String) {
            if (s.isNotEmpty()) stack.last().appendChild(doc.createTextNode(EntityDecoder.decode(s, EntityDecoder.V6)))
        }
        while (pos < src.length) {
            val m = TAG.find(src, pos) ?: break
            text(src.substring(pos, m.range.first))
            pos = m.range.last + 1
            val name = Js.lower(m.groupValues[2])
            if (m.groupValues[1] == "/") {
                val i = stack.indexOfLast { it.tagName == name }
                if (i > 0) while (stack.size > i) stack.removeAt(stack.size - 1)
                continue
            }
            val el = doc.createElement(name)
            for (a in ATTR.findAll(m.groupValues[3])) {
                val raw = a.groupValues[2]
                val value = if (raw.length >= 2 && (raw[0] == '"' || raw[0] == '\'')) raw.substring(1, raw.length - 1) else raw
                try {
                    el.setAttribute(a.groupValues[1], EntityDecoder.decode(value, EntityDecoder.V6, EntityDecoder.Mode.ATTRIBUTE))
                } catch (e: Exception) {
                    // an unusable attribute name: skip it
                }
            }
            stack.last().appendChild(el)
            if (name in RAW) {
                val close = src.indexOf("</$name", pos, ignoreCase = true)
                val end = if (close < 0) src.length else close
                el.appendChild(doc.createTextNode(src.substring(pos, end)))
                pos = if (close < 0) src.length else (src.indexOf('>', close).takeIf { it >= 0 }?.plus(1) ?: src.length)
                continue
            }
            if (name !in VOID && m.groupValues[4] != "/") stack += el
        }
        if (pos < src.length) text(src.substring(pos))
        return doc
    }
}
