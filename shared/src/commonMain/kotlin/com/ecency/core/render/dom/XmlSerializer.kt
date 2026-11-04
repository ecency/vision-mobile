package com.ecency.core.render.dom

import com.ecency.core.render.js.Js
/**
 * Port of xmldom 0.9.12 `XMLSerializer.serializeToString` for HTML documents (default
 * options): void elements without children print as `<br/>`, other empty elements get an
 * explicit end tag, text escapes `<`, `&` and `>`, attribute values also escape `"`, tab, LF
 * and CR, script/style children are written raw, and the root element of a document
 * declares the XHTML namespace (render-helper strips that afterwards).
 */
object XmlSerializer {
    private class Ns(val prefix: String?, val namespace: String?)

    private class Frame(val node: Node, val ns: List<Ns>, val closeTag: String?)

    fun serializeToString(node: Node): String {
        val sb = StringBuilder()
        val ref: Node = (node as? Document)?.documentElement ?: node
        var visible: List<Ns> = emptyList()
        if (ref is Element) {
            val uri = ref.namespaceURI
            if (uri != null && uri.isNotEmpty() && ref.prefix == null && ref.lookupPrefix(uri) == null) {
                visible = listOf(Ns(null, uri))
            }
        }
        write(node, sb, visible)
        return sb.toString()
    }

    private fun write(root: Node, sb: StringBuilder, rootNs: List<Ns>) {
        // Iterative so that deeply nested posts cannot overflow the stack.
        val stack = ArrayList<Any>()
        stack.add(Frame(root, rootNs, null))
        while (stack.isNotEmpty()) {
            val item = stack.removeAt(stack.size - 1)
            if (item is String) {
                sb.append(item)
                continue
            }
            val frame = item as Frame
            val n = frame.node
            when (n) {
                is Element -> {
                    val childNs = enterElement(n, sb, frame.ns) ?: continue
                    stack.add("</${n.tagName}>")
                    pushChildren(stack, n, childNs)
                }
                is Document -> pushChildren(stack, n, frame.ns)
                is CDATASection -> sb.append("<![CDATA[").append(n.data.replace("]]>", "]]]]><![CDATA[>")).append("]]>")
                is Text -> escapeText(n.data, sb)
                is Comment -> sb.append("<!--").append(n.data).append("-->")
                is ProcessingInstruction -> sb.append("<?").append(n.target).append(' ').append(n.data).append("?>")
                is DocumentType -> {
                    sb.append("<!DOCTYPE ").append(n.name)
                    val pub = n.publicId
                    val sys = n.systemId
                    if (!pub.isNullOrEmpty()) {
                        sb.append(" PUBLIC ").append(pub)
                        if (!sys.isNullOrEmpty() && sys != ".") sb.append(' ').append(sys)
                    } else if (!sys.isNullOrEmpty() && sys != ".") {
                        sb.append(" SYSTEM ").append(sys)
                    }
                    if (!n.internalSubset.isNullOrEmpty()) sb.append(" [").append(n.internalSubset).append(']')
                    sb.append('>')
                }
                else -> sb.append("??").append(n.nodeName)
            }
        }
    }

    private fun pushChildren(stack: ArrayList<Any>, n: Node, ns: List<Ns>) {
        val kids = n.childNodes
        for (k in kids.size - 1 downTo 0) stack.add(Frame(kids[k], ns, null))
    }

    /** Writes the start tag; returns the namespace context for the children, or null when done. */
    private fun enterElement(n: Element, sb: StringBuilder, namespaces: List<Ns>): List<Ns>? {
        val nodeName = n.tagName
        sb.append('<').append(nodeName)
        val attrs = n.attrList
        var childNs: ArrayList<Ns>? = null
        fun ns(): ArrayList<Ns> = childNs ?: ArrayList(namespaces).also { childNs = it }
        for (a in attrs) {
            if (a.prefix == "xmlns") {
                ns().add(Ns(a.localName, a.value))
            } else if (a.name == "xmlns") {
                ns().add(Ns("", a.value))
            }
        }
        for (a in attrs) {
            if (needNamespaceDefine(a.prefix, a.namespaceURI, childNs ?: namespaces)) {
                val p = a.prefix ?: ""
                addAttribute(sb, if (p.isNotEmpty()) "xmlns:$p" else "xmlns", a.namespaceURI!!)
                ns().add(Ns(p, a.namespaceURI))
            }
            addAttribute(sb, a.name, a.value)
        }
        if (needNamespaceDefine(n.prefix, n.namespaceURI, childNs ?: namespaces)) {
            val p = n.prefix ?: ""
            addAttribute(sb, if (p.isNotEmpty()) "xmlns:$p" else "xmlns", n.namespaceURI!!)
            ns().add(Ns(p, n.namespaceURI))
        }
        val context = childNs ?: namespaces
        if (!n.hasChildNodes() && Js.lower(nodeName) in VOID) {
            sb.append("/>")
            return null
        }
        sb.append('>')
        val lower = Js.lower(nodeName)
        if (lower == "script" || lower == "style") {
            for (child in n.childNodes) {
                val data = (child as? CharacterData)?.data
                if (!data.isNullOrEmpty()) sb.append(data) else write(child, sb, ArrayList(context))
            }
            sb.append("</").append(nodeName).append('>')
            return null
        }
        return context
    }

    private fun needNamespaceDefine(prefix0: String?, uri: String?, visible: List<Ns>): Boolean {
        val prefix = prefix0 ?: ""
        if (uri.isNullOrEmpty()) return false
        if ((prefix == "xml" && uri == Namespaces.XML) || uri == Namespaces.XMLNS) return false
        for (i in visible.size - 1 downTo 0) {
            val ns = visible[i]
            if (ns.prefix == prefix) return ns.namespace != uri
        }
        return true
    }

    private fun addAttribute(sb: StringBuilder, name: String, value: String) {
        sb.append(' ').append(name).append("=\"")
        for (c in value) {
            when (c) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '&' -> sb.append("&amp;")
                '"' -> sb.append("&quot;")
                '\t' -> sb.append("&#9;")
                '\n' -> sb.append("&#10;")
                '\r' -> sb.append("&#13;")
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private fun escapeText(data: String, sb: StringBuilder) {
        for (c in data) {
            when (c) {
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '&' -> sb.append("&amp;")
                else -> sb.append(c)
            }
        }
    }

    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
}
