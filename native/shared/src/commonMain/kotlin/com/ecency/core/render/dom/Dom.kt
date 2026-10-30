package com.ecency.core.render.dom

import com.ecency.core.render.js.Js
/**
 * A small mutable DOM with the semantics of @xmldom/xmldom 0.9.12, which is what
 * @ecency/render-helper parses post bodies into. Element and attribute names keep the case
 * they were parsed with, attribute lookups lower-case the name for HTML elements, and
 * insertion follows xmldom's hierarchy checks.
 *
 * Children are kept both as an ordered list (`childNodes`, live) and as sibling links, so
 * index access and sibling walks are O(1). Not thread-safe: one document per render.
 */
object NodeType {
    const val ELEMENT = 1
    const val ATTRIBUTE = 2
    const val TEXT = 3
    const val CDATA_SECTION = 4
    const val PROCESSING_INSTRUCTION = 7
    const val COMMENT = 8
    const val DOCUMENT = 9
    const val DOCUMENT_TYPE = 10
}

object Namespaces {
    const val HTML = "http://www.w3.org/1999/xhtml"
    const val XML = "http://www.w3.org/XML/1998/namespace"
    const val XMLNS = "http://www.w3.org/2000/xmlns/"
}

/** A DOMException from xmldom: [name] is e.g. "HierarchyRequestError" or "NamespaceError". */
class DomException(val name: String, message: String) : RuntimeException("$name: $message")

abstract class Node internal constructor(ownerDocument: Document?) {
    var ownerDocument: Document? = ownerDocument
        internal set
    abstract val nodeType: Int
    abstract val nodeName: String

    var parentNode: Node? = null
        internal set
    var previousSibling: Node? = null
        internal set
    var nextSibling: Node? = null
        internal set

    internal var kids: ArrayList<Node>? = null

    /** Live, read-only view of the children. */
    val childNodes: List<Node> get() = kids ?: EMPTY

    val firstChild: Node? get() = kids?.let { if (it.isEmpty()) null else it[0] }
    val lastChild: Node? get() = kids?.let { if (it.isEmpty()) null else it[it.size - 1] }

    fun hasChildNodes(): Boolean = !kids.isNullOrEmpty()

    open var nodeValue: String?
        get() = null
        set(_) {}

    /**
     * Elements: the concatenated data of all descendant text and CDATA nodes. Setting it
     * replaces the children with one text node (none for an empty string).
     */
    open var textContent: String?
        get() = nodeValue
        set(value) {
            nodeValue = value
        }

    private val doc: Document? get() = this as? Document ?: ownerDocument

    fun appendChild(newChild: Node): Node = insertBefore(newChild, null)

    fun insertBefore(newChild: Node, refChild: Node?): Node {
        insertNode(this, newChild, refChild, replacing = false)
        return newChild
    }

    fun removeChild(oldChild: Node): Node = detachFrom(this, oldChild)

    fun replaceChild(newChild: Node, oldChild: Node): Node {
        insertNode(this, newChild, oldChild, replacing = true)
        removeChild(oldChild)
        if (this is Document && newChild is Element) documentElement = newChild
        return oldChild
    }

    /** Merges adjacent text nodes throughout the subtree (xmldom `Node.normalize`). */
    fun normalize() {
        val stack = ArrayList<Node>()
        stack.add(this)
        while (stack.isNotEmpty()) {
            val node = stack.removeAt(stack.size - 1)
            val list = node.kids ?: continue
            var changed = false
            var i = 0
            while (i < list.size) {
                val child = list[i]
                if (child is Text && i + 1 < list.size && list[i + 1] is Text) {
                    var j = i + 1
                    val sb = StringBuilder(child.data)
                    while (j < list.size && list[j] is Text) {
                        val t = list[j] as Text
                        sb.append(t.data)
                        t.parentNode = null
                        t.previousSibling = null
                        t.nextSibling = null
                        j++
                    }
                    child.data = sb.toString()
                    val after = if (j < list.size) list[j] else null
                    child.nextSibling = after
                    after?.previousSibling = child
                    list.subList(i + 1, j).clear()
                    changed = true
                }
                i++
            }
            if (changed) node.doc?.touch()
            for (k in list.size - 1 downTo 0) stack.add(list[k])
        }
    }

    /** Elements below this node with the given qualified name, or all of them for "*". Live. */
    fun getElementsByTagName(qualifiedName: String): List<Element> = LiveElementList(this, qualifiedName)

    /** Prefix bound to [namespaceURI] by xmlns attributes on this node or its ancestors. */
    fun lookupPrefix(namespaceURI: String?): String? {
        var el: Node? = this
        while (el != null) {
            if (el is Element) {
                for (a in el.attrList) {
                    if (a.namespaceURI == Namespaces.XMLNS && a.value == namespaceURI) {
                        return if (a.prefix != null) a.localName else ""
                    }
                }
            }
            el = el.parentNode
        }
        return null
    }

    internal fun descendantsInOrder(visit: (Node) -> Unit) {
        val stack = ArrayList<Node>()
        kids?.let { for (k in it.size - 1 downTo 0) stack.add(it[k]) }
        while (stack.isNotEmpty()) {
            val n = stack.removeAt(stack.size - 1)
            visit(n)
            n.kids?.let { for (k in it.size - 1 downTo 0) stack.add(it[k]) }
        }
    }

    internal companion object {
        val EMPTY: List<Node> = emptyList()

        private fun detach(child: Node, parent: Node = child.parentNode ?: throwNotFound()): Node {
            if (child.parentNode !== parent) throwNotFound()
            val list = parent.kids!!
            val prev = child.previousSibling
            val next = child.nextSibling
            prev?.nextSibling = next
            next?.previousSibling = prev
            val index = if (next == null) list.size - 1 else list.indexOf(child)
            list.removeAt(index)
            child.parentNode = null
            child.previousSibling = null
            child.nextSibling = null
            parent.doc?.touch()
            if (parent is Document && parent.documentElement === child) parent.documentElement = null
            return child
        }

        internal fun detachFrom(parent: Node, child: Node): Node = detach(child, parent)

        private fun throwNotFound(): Nothing = throw DomException("NotFoundError", "child not in parent")

        private fun hierarchy(msg: String): Nothing = throw DomException("HierarchyRequestError", msg)

        /** xmldom `_insertBefore` with its pre-insertion (or pre-replacement) checks. */
        fun insertNode(parent: Node, node: Node, child: Node?, replacing: Boolean) {
            if (parent !is Document && parent !is Element) hierarchy("Unexpected parent node type ${parent.nodeType}")
            if (child != null && child.parentNode !== parent) throwNotFound()
            if (node is Document) hierarchy("Unexpected node type ${node.nodeType}")
            if (node is DocumentType && parent !is Document) hierarchy("Unexpected node type ${node.nodeType}")
            if (parent is Document) {
                val list = parent.childNodes
                if (node is Element) {
                    val hasOther = list.any { it is Element && (!replacing || it !== child) }
                    val doctype = list.firstOrNull { it is DocumentType }
                    val beforeDoctype = child != null && doctype != null && list.indexOf(doctype) > list.indexOf(child)
                    if (hasOther || (!replacing && child is DocumentType) || beforeDoctype) {
                        hierarchy("Only one element can be added and only after doctype")
                    }
                }
                if (node is DocumentType) {
                    if (list.any { it is DocumentType && (!replacing || it !== child) }) hierarchy("Only one doctype is allowed")
                    val el = list.firstOrNull { it is Element }
                    if (child != null && list.indexOf(el) < list.indexOf(child)) {
                        hierarchy("Doctype can only be inserted before an element")
                    }
                    if (!replacing && child == null && el != null) hierarchy("Doctype can not be appended since element is present")
                }
            }
            node.parentNode?.let { detach(node, it) }
            val list = parent.kids ?: ArrayList<Node>().also { parent.kids = it }
            val prev = if (child != null) child.previousSibling else (if (list.isEmpty()) null else list[list.size - 1])
            node.previousSibling = prev
            node.nextSibling = child
            prev?.nextSibling = node
            child?.previousSibling = node
            if (child == null) list.add(node) else list.add(list.indexOf(child), node)
            node.parentNode = parent
            if (parent is Document) {
                node.ownerDocument = parent
                if (node is Element && parent.documentElement == null) parent.documentElement = node
                if (node is DocumentType) parent.doctype = node
            }
            parent.doc?.touch()
        }
    }
}

class Attr internal constructor(
    val name: String,
    val namespaceURI: String?,
    val prefix: String?,
    val localName: String,
    value: String,
) {
    var value: String = value
        internal set
    val nodeName: String get() = name
    val nodeValue: String get() = value
}

class Element internal constructor(
    ownerDocument: Document,
    val tagName: String,
    val namespaceURI: String?,
    val prefix: String?,
    val localName: String,
) : Node(ownerDocument) {
    override val nodeType: Int get() = NodeType.ELEMENT
    override val nodeName: String get() = tagName

    internal val attrList = ArrayList<Attr>(4)

    init {
        kids = ArrayList(2)
    }

    /** Attributes in document order. */
    val attributes: List<Attr> get() = attrList

    private fun isHtmlElement(): Boolean = ownerDocument!!.isHtml && namespaceURI == Namespaces.HTML

    private fun key(name: String): String = if (isHtmlElement()) Js.lower(name) else name

    fun getAttributeNode(name: String): Attr? {
        val k = key(name)
        for (a in attrList) if (a.name == k) return a
        return null
    }

    fun getAttribute(name: String): String? = getAttributeNode(name)?.value

    fun hasAttribute(name: String): Boolean = getAttributeNode(name) != null

    fun hasAttributes(): Boolean = attrList.isNotEmpty()

    fun setAttribute(name: String, value: String) {
        val k = key(name)
        val existing = getAttributeNode(k)
        if (existing != null) {
            existing.value = value
            ownerDocument!!.touch()
        } else {
            setAttributeNode(Attr(k, null, null, k, value))
        }
    }

    fun removeAttribute(name: String) {
        val a = getAttributeNode(name) ?: return
        attrList.remove(a)
        ownerDocument!!.touch()
    }

    /** NamedNodeMap.setNamedItem: replaces an attribute with the same namespace and local name in place. */
    internal fun setAttributeNode(attr: Attr) {
        for (i in attrList.indices) {
            val old = attrList[i]
            if (old.localName == attr.localName && old.namespaceURI == attr.namespaceURI) {
                attrList[i] = attr
                ownerDocument!!.touch()
                return
            }
        }
        attrList.add(attr)
        ownerDocument!!.touch()
    }

    /**
     * The parser's attribute insertion: [setAttributeNode] for each attribute in order, with the
     * same-(namespace, local name) lookup done through a map instead of a scan, so an element
     * with n attributes costs O(n) rather than O(n^2). The sax layer already rejects repeated
     * qualified names; a map hit means two prefixes bound to one namespace, replaced in place.
     */
    internal fun setParsedAttributes(attrs: List<Attr>) {
        if (attrs.isEmpty()) return
        if (attrList.isEmpty() && attrs.size <= 8) {
            for (a in attrs) setAttributeNode(a)
            return
        }
        val index = HashMap<Pair<String?, String>, Int>(attrList.size + attrs.size)
        for ((i, a) in attrList.withIndex()) index.getOrPut(a.namespaceURI to a.localName) { i }
        for (a in attrs) {
            val key = a.namespaceURI to a.localName
            val at = index[key]
            if (at != null) {
                attrList[at] = a
            } else {
                index[key] = attrList.size
                attrList.add(a)
            }
        }
        ownerDocument!!.touch()
    }

    override var textContent: String?
        get() {
            val sb = StringBuilder()
            descendantsInOrder { if (it is Text || it is CDATASection) sb.append((it as CharacterData).data) }
            return sb.toString()
        }
        set(value) {
            while (true) removeChild(firstChild ?: break)
            if (!value.isNullOrEmpty()) appendChild(ownerDocument!!.createTextNode(value))
        }

    override fun toString(): String = "<$tagName>"
}

abstract class CharacterData internal constructor(ownerDocument: Document?, data: String) : Node(ownerDocument) {
    var data: String = data
        set(value) {
            field = value
            ownerDocument?.touch()
        }
    val length: Int get() = data.length

    override var nodeValue: String?
        get() = data
        set(value) {
            data = value ?: ""
        }

    fun appendData(text: String) {
        data += text
    }
}

class Text internal constructor(ownerDocument: Document?, data: String) : CharacterData(ownerDocument, data) {
    override val nodeType: Int get() = NodeType.TEXT
    override val nodeName: String get() = "#text"
    override fun toString(): String = "#text($data)"
}

class CDATASection internal constructor(ownerDocument: Document?, data: String) : CharacterData(ownerDocument, data) {
    override val nodeType: Int get() = NodeType.CDATA_SECTION
    override val nodeName: String get() = "#cdata-section"
}

class Comment internal constructor(ownerDocument: Document?, data: String) : CharacterData(ownerDocument, data) {
    override val nodeType: Int get() = NodeType.COMMENT
    override val nodeName: String get() = "#comment"
}

class ProcessingInstruction internal constructor(ownerDocument: Document?, val target: String, data: String) :
    CharacterData(ownerDocument, data) {
    override val nodeType: Int get() = NodeType.PROCESSING_INSTRUCTION
    override val nodeName: String get() = target
}

class DocumentType internal constructor(
    ownerDocument: Document?,
    val name: String,
    val publicId: String?,
    val systemId: String?,
    val internalSubset: String?,
) : Node(ownerDocument) {
    override val nodeType: Int get() = NodeType.DOCUMENT_TYPE
    override val nodeName: String get() = name
}

/** An HTML document (xmldom `createHTMLDocument(false)`, the `text/html` parse target). */
class Document internal constructor() : Node(null) {
    override val nodeType: Int get() = NodeType.DOCUMENT
    override val nodeName: String get() = "#document"

    /** Always true: only `text/html` documents are produced. */
    val isHtml: Boolean get() = true

    var documentElement: Element? = null
        internal set
    var doctype: DocumentType? = null
        internal set

    /** Bumped on every mutation; live element lists use it to know when to recompute. */
    internal var version = 0
        private set

    internal fun touch() {
        version++
    }

    init {
        kids = ArrayList(2)
    }

    /** Lower-cases the name (HTML document) and puts the element in the XHTML namespace. */
    fun createElement(tagName: String): Element {
        val name = Js.lower(tagName)
        return Element(this, name, Namespaces.HTML, null, name)
    }

    fun createElementNS(namespaceURI: String?, qualifiedName: String): Element {
        val v = validateAndExtract(namespaceURI, qualifiedName)
        return Element(this, qualifiedName, v.namespace, v.prefix, v.localName)
    }

    fun createTextNode(data: String): Text = Text(this, data)

    fun createComment(data: String): Comment = Comment(this, data)

    fun createCDATASection(data: String): CDATASection {
        if (data.contains("]]>")) throw DomException("InvalidCharacterError", "data contains \"]]>\"")
        return CDATASection(this, data)
    }

    fun createProcessingInstruction(target: String, data: String): ProcessingInstruction =
        ProcessingInstruction(this, target, data)

    internal fun createAttributeNS(namespaceURI: String?, qualifiedName: String, value: String, checkedName: Boolean = false): Attr {
        val v = validateAndExtract(namespaceURI, qualifiedName, checkedName)
        return Attr(qualifiedName, v.namespace, v.prefix, v.localName, value)
    }

    /** createElementNS for a name the parser already matched against `QName`. */
    internal fun createElementNSChecked(namespaceURI: String?, qualifiedName: String): Element {
        val v = validateAndExtract(namespaceURI, qualifiedName, nameChecked = true)
        return Element(this, qualifiedName, v.namespace, v.prefix, v.localName)
    }

    internal class Extracted(val namespace: String?, val prefix: String?, val localName: String)

    internal companion object {
        /** DOM "validate and extract" as xmldom implements it. */
        fun validateAndExtract(namespace: String?, qualifiedName: String, nameChecked: Boolean = false): Extracted {
            if (!nameChecked && !XmlGrammar.isQName(qualifiedName)) {
                throw DomException("InvalidCharacterError", "invalid character in qualified name \"$qualifiedName\"")
            }
            val ns = if (namespace.isNullOrEmpty()) null else namespace
            var prefix: String? = null
            var local = qualifiedName
            val colon = qualifiedName.indexOf(':')
            if (colon >= 0) {
                prefix = qualifiedName.substring(0, colon)
                val next = qualifiedName.indexOf(':', colon + 1)
                local = if (next < 0) qualifiedName.substring(colon + 1) else qualifiedName.substring(colon + 1, next)
            }
            if (prefix != null && ns == null) throw DomException("NamespaceError", "prefix is non-null and namespace is null")
            if (prefix == "xml" && ns != Namespaces.XML) throw DomException("NamespaceError", "xml prefix")
            if ((prefix == "xmlns" || qualifiedName == "xmlns") && ns != Namespaces.XMLNS) {
                throw DomException("NamespaceError", "xmlns without the XMLNS namespace")
            }
            if (ns == Namespaces.XMLNS && prefix != "xmlns" && qualifiedName != "xmlns") {
                throw DomException("NamespaceError", "XMLNS namespace without xmlns")
            }
            return Extracted(ns, prefix, local)
        }
    }
}

/** xmldom's live `getElementsByTagName` result: recomputed whenever the document changed. */
private class LiveElementList(private val root: Node, private val name: String) : AbstractList<Element>() {
    private val lower = Js.lower(name)
    private var cache: List<Element> = emptyList()
    private var seen = -1

    private fun current(): List<Element> {
        val doc = root as? Document ?: root.ownerDocument
        val v = doc?.version ?: 0
        if (v != seen || doc == null) {
            val out = ArrayList<Element>()
            root.descendantsInOrder { n ->
                if (n is Element) {
                    if (name == "*") {
                        out.add(n)
                    } else {
                        val q = if (n.prefix != null) n.prefix + ":" + n.localName else n.localName
                        val want = if (n.namespaceURI == Namespaces.HTML) lower else name
                        if (q == want) out.add(n)
                    }
                }
            }
            cache = out
            seen = v
        }
        return cache
    }

    override val size: Int get() = current().size
    override fun get(index: Int): Element = current()[index]
}
