package com.ecency.core.render.methods

import com.ecency.core.render.dom.CDATASection
import com.ecency.core.render.dom.Comment
import com.ecency.core.render.dom.Document
import com.ecency.core.render.dom.DocumentType
import com.ecency.core.render.dom.Element
import com.ecency.core.render.dom.Node
import com.ecency.core.render.dom.ProcessingInstruction
import com.ecency.core.render.js.Js
import com.ecency.core.render.sanitize.AuthorAttributes
import kotlin.random.Random

/**
 * render-helper 2.5.45 `methods/render-boundary.method.ts` and `strip-author-markers.method.ts` (#1919).
 *
 * The final sanitizer re-tokenizes the serialized string, so anything the DOM and xss read differently would ship
 * untransformed. The render stamps every element it serializes with a fresh per-render nonce (`data-rh`); the final
 * sanitizer trusts render-helper's own link markers and policy only on tags carrying it. The nonce is never derived
 * from the body, and `data-rh` is not whitelisted, so the sanitizer removes it.
 */
internal object RenderBoundary {
    const val RENDER_NONCE_ATTRIBUTE = "data-rh"

    /** Stands in for a node that must not reach the output; xss removes comments in the final pass. */
    private const val DROPPED = "<!---->"

    private val WRAPPER_ELEMENTS = setOf("html", "head", "body")

    /** `/^[A-Za-z][A-Za-z0-9:_.-]*$/` */
    private fun isValidElementName(name: String): Boolean {
        if (name.isEmpty() || !Js.isAsciiAlpha(name[0])) return false
        for (c in name) if (!(Js.isAsciiAlpha(c) || Js.isDigit(c) || c == ':' || c == '_' || c == '.' || c == '-')) return false
        return true
    }

    /** `/^[A-Za-z0-9_:.-]+$/`: attribute names xss keeps as written. */
    fun isValidAttributeName(name: String): Boolean {
        if (name.isEmpty()) return false
        for (c in name) if (!(Js.isAsciiAlpha(c) || Js.isDigit(c) || c == ':' || c == '_' || c == '.' || c == '-')) return false
        return true
    }

    /** Elements the transforms must not walk and the output must not contain: script, style, malformed names. */
    fun isHiddenElement(node: Node): Boolean {
        val name = node.nodeName
        if (!isValidElementName(name)) return true
        val lower = Js.toLowerCase(name)
        val local = lower.substring(lower.lastIndexOf(':') + 1)
        return local == "script" || local == "style"
    }

    /**
     * A fresh nonce per render. Upstream draws it from `crypto.getRandomValues`; `kotlin.random.Random` is enough
     * here: the nonce never comes from or reaches the body (`data-rh` written by an author is stripped), it only
     * tells the tags this render emitted from the author's within the one render.
     */
    fun createRenderNonce(): String {
        val sb = StringBuilder("rh")
        repeat(4) { sb.append(Random.nextLong(0, 0x100000000L).toString(36)) }
        return sb.toString()
    }

    private fun escapeText(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * `renderNodeFilter`: skips what must not reach the output and stamps every element it emits. A String return is
     * written verbatim; nothing in the tree is moved or removed.
     */
    fun nodeFilter(nonce: String): (Node) -> Any? = { node ->
        when (node) {
            is Element -> {
                if (isHiddenElement(node)) {
                    DROPPED
                } else {
                    for (a in node.attributes.toList()) if (!isValidAttributeName(a.name)) node.removeAttributeNode(a)
                    if (Js.toLowerCase(node.nodeName) !in WRAPPER_ELEMENTS) node.setAttribute(RENDER_NONCE_ATTRIBUTE, nonce)
                    node
                }
            }
            is CDATASection -> escapeText(node.data)
            is ProcessingInstruction, is Comment, is DocumentType -> DROPPED
            else -> node
        }
    }

    /**
     * `stripAuthorMarkers`: on every element before the transforms, attribute names lower-cased (the first spelling
     * wins), render-helper's `data-*` link markers dropped and only the class tokens an author may write kept.
     * Iterative over a snapshot of each child list.
     */
    fun stripAuthorMarkers(doc: Document) {
        val stack = ArrayList<Node>()
        stack.add(doc)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            for (child in node.childNodes.toList()) {
                if (child !is Element || isHiddenElement(child)) continue
                normalizeElement(child)
                stack.add(child)
            }
        }
    }

    private fun normalizeElement(el: Element) {
        val seen = HashSet<String>()
        for (attr in el.attributes.toList()) {
            val name = attr.name
            if (!isValidAttributeName(name)) {
                el.removeAttributeNode(attr)
                continue
            }
            val lower = Js.toLowerCase(name)
            if (lower in seen || lower in AuthorAttributes.RENDER_HELPER_DATA_ATTRIBUTES) {
                seen.add(lower)
                el.removeAttributeNode(attr)
                continue
            }
            seen.add(lower)
            var value = attr.value
            if (lower == "class") {
                value = AuthorAttributes.authorClasses(value)
                if (value.isEmpty()) {
                    el.removeAttributeNode(attr)
                    continue
                }
            }
            if (name != lower) {
                el.removeAttributeNode(attr)
                el.setAttribute(lower, value)
            } else if (value != attr.value) {
                el.setAttribute(lower, value)
            }
        }
    }
}
