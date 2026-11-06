package com.ecency.core.render.dom

import com.ecency.core.render.js.Js

/**
 * Last-resort tree builder for markup that both xmldom and render-helper's repair path reject.
 * It never fails: tags are matched with a simple open-element stack (an end tag closes up to its
 * open match, unmatched end tags are ignored), void elements self-close, and text and attribute
 * values are entity-decoded. Only the native block builder uses it, so links and images survive
 * where the old fallback kept plain text only.
 *
 * Tags are found by [TagScanner], which matches exactly what the regular expression
 * `<(/?)([A-Za-z][A-Za-z0-9-]*)((?:\s+[^\s=/>"']+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+))?)*)\s*(/?)>` matched
 * (`LenientHtmlTest` compares the two), without its recursion: both regex engines recurse once per attribute, so a
 * tag with about 20,000 attributes overflowed the stack (a crash on Kotlin/Native), and an unclosed tag with many
 * quoted values could backtrack exponentially (#3613). An element keeps its first [MAX_ATTRIBUTES] attributes.
 */
object LenientHtml {
    /** Attributes one element keeps; the rest are dropped, so setting them stays linear in the tag's length. */
    internal const val MAX_ATTRIBUTES = 256

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
        val tags = TagScanner(src)
        var pos = 0
        fun text(s: String) {
            if (s.isNotEmpty()) stack.last().appendChild(doc.createTextNode(EntityDecoder.decode(s, EntityDecoder.V6)))
        }
        while (pos < src.length) {
            val m = tags.find(pos) ?: break
            text(src.substring(pos, m.start))
            pos = m.end
            val name = Js.lower(m.name)
            if (m.closing) {
                val i = stack.indexOfLast { it.tagName == name }
                if (i > 0) while (stack.size > i) stack.removeAt(stack.size - 1)
                continue
            }
            val el = doc.createElement(name)
            for (a in ATTR.findAll(m.attributes).take(MAX_ATTRIBUTES)) {
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
            if (name !in VOID && !m.selfClosing) stack += el
        }
        if (pos < src.length) text(src.substring(pos))
        return doc
    }

    /** A start or end tag: [start] is its `<`, [end] just past its `>`; [attributes] is the raw attribute text. */
    internal class Tag(val start: Int, val end: Int, val closing: Boolean, val name: String, val attributes: String, val selfClosing: Boolean)

    /**
     * Finds tags in [src] as the regular expression in the class comment would, iteratively and in linear time.
     *
     * The expression's only real choices are after an attribute name: a value or none, and a quoted value either as
     * quoted or (its last alternative) unquoted from the quote on. What follows a "boundary" (a position where the
     * attribute loop may take another attribute or stop) is the same expression suffix wherever the tag started, so the
     * first match from a boundary depends on the position alone: it is computed once per position (depth first with an
     * explicit stack, the expression's own order of alternatives) and kept for every later tag start.
     */
    internal class TagScanner(private val src: String) {
        private class Hit(val attributesEnd: Int, val end: Int, val selfClosing: Boolean)

        private class Frame(val pos: Int, val next: IntArray) {
            var i = 0
        }

        private val memo = HashMap<Int, Hit>()

        /** The first tag at or after [from], or null. */
        fun find(from: Int): Tag? {
            var i = src.indexOf('<', from)
            while (i >= 0) {
                matchAt(i)?.let { return it }
                i = src.indexOf('<', i + 1)
            }
            return null
        }

        private fun matchAt(start: Int): Tag? {
            var p = start + 1
            val closing = p < src.length && src[p] == '/'
            if (closing) p++
            if (p >= src.length || !isAsciiLetter(src[p])) return null
            val nameStart = p
            while (p < src.length && (isAsciiLetter(src[p]) || src[p] in '0'..'9' || src[p] == '-')) p++
            val hit = boundary(p) ?: return null
            return Tag(start, hit.end, closing, src.substring(nameStart, p), src.substring(p, hit.attributesEnd), hit.selfClosing)
        }

        /** The first match of the rest of the tag from the attribute boundary [from]. */
        private fun boundary(from: Int): Hit? {
            memo[from]?.let { return it.takeIf { h -> h !== FAIL } }
            val stack = ArrayList<Frame>()
            stack += Frame(from, next(from))
            while (stack.isNotEmpty()) {
                val f = stack.last()
                if (f.i < f.next.size) {
                    val k = f.next[f.i]
                    val known = memo[k]
                    when {
                        known == null -> stack += Frame(k, next(k))
                        known !== FAIL -> {
                            memo[f.pos] = known
                            stack.removeAt(stack.size - 1)
                        }
                        else -> f.i++
                    }
                } else {
                    memo[f.pos] = stop(f.pos) ?: FAIL
                    stack.removeAt(stack.size - 1)
                }
            }
            return memo[from].takeIf { it !== FAIL }
        }

        /**
         * The boundaries one more attribute can reach from [p], in the expression's order: `\s+name`, then a value
         * (`"..."`, `'...'`, unquoted) or none.
         */
        private fun next(p: Int): IntArray {
            var q = p
            while (q < src.length && isSpace(src[q])) q++
            if (q == p || q >= src.length || !isAttributeNameChar(src[q])) return EMPTY
            var e = q
            while (e < src.length && isAttributeNameChar(src[e])) e++
            var r = e
            while (r < src.length && isSpace(src[r])) r++
            if (r >= src.length || src[r] != '=') return intArrayOf(e)
            var v = r + 1
            while (v < src.length && isSpace(src[v])) v++
            val out = ArrayList<Int>(4)
            if (v < src.length && (src[v] == '"' || src[v] == '\'')) {
                val close = src.indexOf(src[v], v + 1)
                if (close >= 0) out += close + 1
            }
            var u = v
            while (u < src.length && !isSpace(src[u]) && src[u] != '>') u++
            if (u > v) out += u
            out += e // the value is optional: no value (it cannot match with an '=' next, as in the expression)
            return out.toIntArray()
        }

        /** `\s*(/?)>` at [p]. */
        private fun stop(p: Int): Hit? {
            var q = p
            while (q < src.length && isSpace(src[q])) q++
            return when {
                q + 1 < src.length && src[q] == '/' && src[q + 1] == '>' -> Hit(p, q + 2, true)
                q < src.length && src[q] == '>' -> Hit(p, q + 1, false)
                else -> null
            }
        }

        private companion object {
            val FAIL = Hit(-1, -1, false)
            val EMPTY = IntArray(0)

            fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

            /** The expression's `\s`: ASCII space, tab, line feed, vertical tab, form feed, carriage return. */
            fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

            /** `[^\s=/>"']`. */
            fun isAttributeNameChar(c: Char) = !isSpace(c) && c != '=' && c != '/' && c != '>' && c != '"' && c != '\''
        }
    }
}
