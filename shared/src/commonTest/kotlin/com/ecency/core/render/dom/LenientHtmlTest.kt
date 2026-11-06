package com.ecency.core.render.dom

import com.ecency.core.render.LinearScaling
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/** #3613: the last-resort parser on tags no post should carry, in linear time and without deep recursion. */
class LenientHtmlTest {
    private fun parseOrFail(html: String): Document = try {
        LenientHtml.parse(html)
    } catch (e: Throwable) {
        // a StackOverflowError on the JVM; Kotlin/Native may not get this far
        fail("LenientHtml.parse threw ${e::class.simpleName} on ${html.length} chars")
    }

    @Test
    fun aTagWithFiftyThousandAttributesParsesInLinearTime() {
        // distinct names, quoted, unquoted and bare attributes in turn
        fun tag(n: Int) = buildString {
            append("<p")
            for (i in 0 until n) {
                when (i % 3) {
                    0 -> append(" a$i=\"v\"")
                    1 -> append(" b$i=v")
                    else -> append(" c$i")
                }
            }
            append(">text</p><a href=\"https://x.example\">l</a>")
        }
        val doc = parseOrFail(tag(50_000))
        val body = doc.childNodes.single() as Element
        val p = body.childNodes[0] as Element
        assertEquals("p", p.tagName)
        assertEquals("text", p.textContent)
        assertEquals("v", p.getAttribute("a0"))
        assertEquals("https://x.example", (body.childNodes[1] as Element).getAttribute("href"))
        LinearScaling.assertLinear("lenient tag attributes", 50_000, ::tag) { parseOrFail(it) }
    }

    /** The expression the scanner replaced: on short input it is the oracle for what a tag is. */
    private val oldTag = Regex("<(/?)([A-Za-z][A-Za-z0-9-]*)((?:\\s+[^\\s=/>\"']+(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+))?)*)\\s*(/?)>")

    private fun sameAsTheExpression(src: String) {
        val scanner = LenientHtml.TagScanner(src)
        var from = 0
        while (from <= src.length) {
            val m = oldTag.find(src, from)
            val t = scanner.find(from)
            if (m == null) {
                assertEquals(null, t?.let { src.substring(it.start, it.end) }, "no tag in ${src.debug()} from $from")
                return
            }
            assertEquals(
                listOf(m.range.first, m.range.last + 1, m.groupValues[1] == "/", m.groupValues[2], m.groupValues[3], m.groupValues[4] == "/"),
                t?.let { listOf(it.start, it.end, it.closing, it.name, it.attributes, it.selfClosing) },
                "tag in ${src.debug()} from $from",
            )
            from = m.range.last + 1
        }
    }

    private fun String.debug() = "\"" + replace("\n", "\\n").replace("\t", "\\t") + "\""

    @Test
    fun theScannerFindsWhatTheExpressionFound() {
        listOf(
            "<a href=x/>", "<a href=\"x>y\" c", "<a b=\"x>y\" c>", "<a b='x' c=\"y\"/>", "<a b = \"x\" / >", "<a b= c>", "<a b =>",
            "<a b=\"x\"c>", "<a b=\"x\"\"y\">", "<a b=\"unclosed>", "</p >", "</p x>", "<1a>", "< a>", "<a/b>", "<a\tb\n=\n'v'>",
            "<a b=x\u000Bc>", "text <b>bold</b> <img src=\"i.png\"> <br/> tail", "<a b=\"x\" c='y d=\"z\">", "<<a>>", "<a-b c=d>",
        ).forEach(::sameAsTheExpression)
        // and on random markup made of the characters that matter
        val alphabet = "<<<>>/=\"\"''  \t\nabAB1-x"
        val random = kotlin.random.Random(3613)
        repeat(20_000) {
            val length = random.nextInt(1, 32)
            sameAsTheExpression(buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } })
        }
    }

    @Test
    fun anElementKeepsItsFirstAttributesOnly() {
        val html = "<p" + (0 until 300).joinToString("") { " a$it=$it" } + ">x</p>"
        val p = (LenientHtml.parse(html).childNodes.single() as Element).childNodes.single() as Element
        assertEquals(LenientHtml.MAX_ATTRIBUTES, p.attributes.size)
        assertEquals("0", p.getAttribute("a0"))
        assertEquals("255", p.getAttribute("a255"))
        assertEquals(null, p.getAttribute("a256"))
    }

    @Test
    fun anUnclosedTagWithManyQuotedAttributesIsText() {
        // no '>': every quoted value could also be read unquoted, which a backtracking matcher tries in every combination
        fun tag(n: Int) = "<p" + " a=\"v\"".repeat(n) + " tail"
        assertEquals(tag(50_000), (parseOrFail(tag(50_000)).childNodes.single() as Element).textContent)
        LinearScaling.assertLinear("lenient unclosed tag", 50_000, ::tag) { parseOrFail(it) }
    }
}
