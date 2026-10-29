package com.ecency.core.render.markdown

import com.ecency.core.render.LinearScaling
import org.kotlincrypto.hash.sha2.SHA256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Byte-for-byte parity with render-helper's Remarkable (see native/tools/render). */
class MarkdownTest {
    /**
     * Cases known to differ from the JS oracle, with the reason. Must stay empty unless a
     * difference is understood and accepted; every entry also needs a line in the README.
     */
    private val allowedDeviations: Map<String, String> = emptyMap()

    private fun describe(expected: String, actual: String): String {
        var i = 0
        while (i < expected.length && i < actual.length && expected[i] == actual[i]) i++
        val from = maxOf(0, i - 40)
        fun window(s: String) = s.substring(minOf(from, s.length), minOf(s.length, i + 60)).replace("\n", "\\n")
        return "first difference at $i\n  expected: ...${window(expected)}...\n  actual:   ...${window(actual)}..."
    }

    @Test
    fun matchesTheJsOracle() {
        val failures = ArrayList<String>()
        for (case in MarkdownFixtures.cases) {
            if (case.name in allowedDeviations) continue
            val result = runCatching { Markdown.render(case.markdown) }
            val expected = case.html
            if (expected == null) {
                if (result.isSuccess) failures.add("${case.name}: expected an exception, got ${result.getOrNull()}")
                continue
            }
            result.fold(
                onSuccess = { actual -> if (actual != expected) failures.add("${case.name}: ${describe(expected, actual)}") },
                onFailure = { e -> failures.add("${case.name}: threw $e") },
            )
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${MarkdownFixtures.cases.size} cases differ from remarkable:\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun allowedDeviationsAreReal() {
        val names = MarkdownFixtures.cases.map { it.name }.toSet()
        for (name in allowedDeviations.keys) assertTrue(name in names, "unknown case $name")
    }

    private fun bigDocument(repeats: Int = MarkdownFixtures.BIG_REPEATS): String {
        val joined = MarkdownFixtures.cases.filter { it.html != null }.joinToString("\n\n") { it.markdown }
        return List(repeats) { joined }.joinToString("\n\n")
    }

    @Test
    fun largeDocumentMatchesAndIsFast() {
        val doc = bigDocument()
        assertEquals(MarkdownFixtures.BIG_LENGTH, doc.length)
        val html = Markdown.render(doc)
        // UTF-16LE code units: the output can hold lone surrogates, which UTF-8 encoders replace.
        val utf16 = ByteArray(html.length * 2).also { b ->
            html.forEachIndexed { i, c -> b[2 * i] = c.code.toByte(); b[2 * i + 1] = (c.code shr 8).toByte() }
        }
        val sha = SHA256().digest(utf16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        assertEquals(MarkdownFixtures.BIG_SHA256, sha, "large document output differs from remarkable")
        // Fast means linear in the document size, checked by growth (an absolute bound fails under load).
        LinearScaling.assertLinear("large document", 2, ::bigDocument) { Markdown.render(it) }
    }

    /**
     * Inputs that are linear in remarkable stay linear here (the port must not add backtracking).
     * Unclosed emphasis runs such as "*a " repeated are quadratic in remarkable itself, so they
     * are not listed.
     */
    @Test
    fun pathologicalInputsStayLinear() {
        // Each shape is built at size n and 4n and compared by growth, not by an absolute bound
        // (see LinearScaling), so the test holds on a loaded machine and still catches quadratic paths.
        val shapes: List<Pair<String, (Int) -> String>> = listOf(
            "comment" to { n -> "<!--" + "a-".repeat(n) },
            "open tag" to { n -> "<a " + "b ".repeat(n) },
            "brackets" to { n -> "[".repeat(n) },
            "backticks" to { n -> "`".repeat(n) + "x" },
            "angles" to { n -> "<".repeat(n) },
            "emails" to { n -> "x@y.".repeat(n / 3) },
            "url parens" to { n -> "https://example.com/" + "a(".repeat(n / 2) },
            "phones" to { n -> "+1 555 123 4567" + ",1".repeat(n / 3) + " www.example.com" },
            "quotes" to { n -> "> ".repeat(n / 20) + "deep" },
            "links" to { n -> "see https://example.com/a?b=c and www.foo.org ".repeat(n / 40) },
            "prose" to { n -> "some plain words here and there, a.b c.d ".repeat(n / 30) },
        )
        for ((name, build) in shapes) {
            LinearScaling.assertLinear(name, 25_000, build) { Markdown.render(it) }
        }
    }
}
