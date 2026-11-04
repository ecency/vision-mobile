package com.ecency.core.render.dom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The mutable DOM API the render-helper transforms rely on (xmldom semantics). */
class DomApiTest {
    private fun parse(html: String) = XmlDomParser.parseFromString("<body id=\"root\">$html</body>")

    private fun Node.serialize() = XmlSerializer.serializeToString(this)

    @Test
    fun treeShapeAndSiblingLinks() {
        val doc = parse("<p class=\"a\">Hi <b>there</b></p><img src=\"x\"><!--c-->")
        val body = doc.documentElement!!
        assertEquals("body", body.tagName)
        assertEquals(NodeType.ELEMENT, body.nodeType)
        assertEquals(listOf("p", "img", "#comment"), body.childNodes.map { it.nodeName })
        val p = body.firstChild as Element
        val img = p.nextSibling as Element
        assertSame(p, img.previousSibling)
        assertSame(body, p.parentNode)
        assertSame(doc, p.ownerDocument)
        assertNull(p.previousSibling)
        assertEquals(NodeType.COMMENT, body.lastChild!!.nodeType)
        assertEquals("Hi ", (p.firstChild as Text).data)
        assertEquals("#text", p.firstChild!!.nodeName)
        assertEquals("there", p.lastChild!!.textContent)
    }

    @Test
    fun attributesKeepParsedCaseAndLookupsLowerCase() {
        val doc = parse("<div Data-X=\"1\" class=\"c\">x</div>")
        val div = doc.documentElement!!.firstChild as Element
        assertEquals(listOf("Data-X", "class"), div.attributes.map { it.name })
        // xmldom lower-cases the looked-up name for HTML elements, so mixed-case parsed names are not found.
        assertNull(div.getAttribute("Data-X"))
        assertEquals("c", div.getAttribute("CLASS"))
        div.setAttribute("Title", "t")
        assertEquals("t", div.getAttribute("title"))
        assertEquals("title", div.attributes.last().name)
        div.setAttribute("class", "d")
        assertEquals("d", div.getAttribute("class"))
        assertTrue(div.hasAttribute("class"))
        div.removeAttribute("class")
        assertTrue(!div.hasAttribute("class"))
        // A detached-subtree serialization declares the namespace on its root, like xmldom.
        assertEquals("<div Data-X=\"1\" title=\"t\" xmlns=\"http://www.w3.org/1999/xhtml\">x</div>", div.serialize())
    }

    @Test
    fun textContentSkipsCommentsAndReplacesChildren() {
        val doc = parse("<p>a<!--x--><b>b</b><![CDATA[c]]></p>")
        val p = doc.documentElement!!.firstChild as Element
        assertEquals("abc", p.textContent)
        p.textContent = "new <text>"
        assertEquals(1, p.childNodes.size)
        assertEquals("<p xmlns=\"http://www.w3.org/1999/xhtml\">new &lt;text&gt;</p>", p.serialize())
        p.textContent = ""
        assertEquals(0, p.childNodes.size)
    }

    @Test
    fun mutationsKeepListAndLinksInSync() {
        val doc = parse("<p>one</p><p>two</p>")
        val body = doc.documentElement!!
        val (one, two) = body.childNodes.toList()
        val span = doc.createElement("SPAN")
        assertEquals("span", span.tagName)
        body.insertBefore(span, two)
        assertEquals(listOf(one, span, two), body.childNodes.toList())
        assertSame(span, one.nextSibling)
        assertSame(span, two.previousSibling)
        val text = doc.createTextNode("t")
        body.replaceChild(text, span)
        assertNull(span.parentNode)
        assertEquals(listOf(one, text, two), body.childNodes.toList())
        // appendChild moves a node that already has a parent.
        (one as Element).appendChild(two)
        assertEquals(listOf(one, text), body.childNodes.toList())
        assertSame(one, two.parentNode)
        assertNull(text.nextSibling)
        body.removeChild(text)
        assertEquals(listOf(one), body.childNodes.toList())
        assertEquals("<body id=\"root\" xmlns=\"http://www.w3.org/1999/xhtml\"><p>one<p>two</p></p></body>", doc.serialize())
        assertFailsWith<DomException> { body.removeChild(text) }
    }

    @Test
    fun getElementsByTagNameIsLiveAndCaseInsensitiveForHtml() {
        val doc = parse("<div><IMG src=\"a\"><p><img src=\"b\"></p></div>")
        val imgs = doc.getElementsByTagName("img")
        // Parsed tag names keep their case, and lookups lower-case the query: "IMG" does not match.
        assertEquals(listOf("b"), imgs.map { it.getAttribute("src") })
        doc.documentElement!!.appendChild(doc.createElement("img"))
        assertEquals(2, imgs.size)
        assertEquals(6, doc.getElementsByTagName("*").size)
    }

    @Test
    fun documentAcceptsOneElementAndNormalizeMergesText() {
        val doc = parse("x")
        assertFailsWith<DomException> { doc.appendChild(doc.createElement("p")) }
        val body = doc.documentElement!!
        body.appendChild(doc.createTextNode("y"))
        body.appendChild(doc.createTextNode("z"))
        assertEquals(3, body.childNodes.size)
        doc.normalize()
        assertEquals(1, body.childNodes.size)
        assertEquals("xyz", (body.firstChild as Text).data)
    }

    @Test
    fun malformedInputThrowsParseException() {
        assertFailsWith<DomParseException> { parse("<b><i>x</b></i>") }
        assertFailsWith<DomParseException> { parse("<o:p>x</o:p>") }
        assertFailsWith<DomParseException> { parse("<!-- a -- b -->") }
        assertFailsWith<DomParseException> { XmlDomParser.parseFromString("just text") }
    }
}
