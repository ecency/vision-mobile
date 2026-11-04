package com.ecency.core.render.methods

import com.ecency.core.render.dom.Attr
import com.ecency.core.render.dom.CDATASection
import com.ecency.core.render.dom.Comment
import com.ecency.core.render.dom.Document
import com.ecency.core.render.dom.DomException
import com.ecency.core.render.dom.Element
import com.ecency.core.render.dom.Node
import com.ecency.core.render.dom.ProcessingInstruction
import com.ecency.core.render.dom.Text
import com.ecency.core.render.dom.XmlSerializer

/** Small DOM helpers render-helper uses: `importNode`, `getSerializedInnerHTML`, `removeChildNodes`. */
internal object DomUtil {
    /** xmldom `importNode(node, deep)`: a copy owned by [doc]; attributes go through setAttributeNode as in cloneNode. */
    fun importNode(doc: Document, node: Node, deep: Boolean): Node {
        val copy: Node = when (node) {
            is Element -> Element(doc, node.tagName, node.namespaceURI, node.prefix, node.localName).also { el ->
                for (a in node.attributes) el.setAttributeNode(Attr(a.name, a.namespaceURI, a.prefix, a.localName, a.value))
            }
            is CDATASection -> doc.createCDATASection(node.data)
            is Text -> doc.createTextNode(node.data)
            is Comment -> doc.createComment(node.data)
            is ProcessingInstruction -> doc.createProcessingInstruction(node.target, node.data)
            else -> throw DomException("NotSupportedError", "cannot import ${node.nodeName}")
        }
        if (deep) for (child in node.childNodes.toList()) copy.appendChild(importNode(doc, child, true))
        return copy
    }

    /** `getSerializedInnerHTML`: the first child serialized on its own, or "". */
    fun serializedInnerHTML(node: Node): String {
        val first = node.firstChild ?: return ""
        return XmlSerializer.serializeToString(first)
    }

    fun removeChildNodes(node: Node) {
        while (true) node.removeChild(node.firstChild ?: break)
    }
}
