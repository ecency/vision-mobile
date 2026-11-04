package com.ecency.core.render.blocks

import com.ecency.core.render.LinearScaling
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The anchored `...#@author/permlink` split of a post link's data-permlink. */
class AnchoredPostTest {
    // The regex the parser replaced, kept as the oracle for short inputs.
    private val oracleRegex = Regex("(.*?#@)(.*)/(.*)")

    private fun oracle(p: String): Pair<String, String>? =
        if ("#@" in p) oracleRegex.find(p)?.let { it.groupValues[2] to it.groupValues[3] } else null

    private fun check(p: String) = assertEquals(oracle(p), BlockBuilder.anchoredPost(p), "input: " + p.map { it.code }.joinToString(","))

    @Test
    fun edgeCasesMatchTheRegex() {
        listOf(
            "", "#@", "#@/", "/#@", "a#@b", "a#@b/c", "#@a/b/c", "x#@a#@b/c", "x#@a/b#@c/d", "#@/c", "#@a/", "##@@a/b",
            "#@#@/", "a/b#@c", "#@a/b?x=1", "p\n#@a/b", "#@a\n/b", "#@a/b\nc", "\n#@a/b\n", "#@a\r/b", "a\r\n#@b/c",
            "#@a /b", "#@a /b", "#@a\u0085/b", "#@a\u000B/b", "#@a\u000C/b", "#@a\n#@b/c", "#@a/b\n#@c/d",
            "#@😀/😀", "#\n@a/b", "#@a/b/", "@#a/b", "#@a//b",
        ).forEach(::check)
    }

    @Test
    fun generatedInputsMatchTheRegex() {
        val alphabet = "#@/a?\n\r  \u0085 "
        val rnd = Random(20261029)
        repeat(60_000) {
            val len = rnd.nextInt(0, 14)
            check(buildString { repeat(len) { append(alphabet[rnd.nextInt(alphabet.length)]) } })
        }
    }

    @Test
    fun longPermlinkStaysLinear() {
        // No "/" after the "#@" runs: the backtracking regex tried every start, every "#@" and every split.
        LinearScaling.assertLinear("anchored permlink", 150, { n -> "#@".repeat(n) + "a" }) { p ->
            BlockBuilder.build("<p><a class=\"markdown-post-link\" data-author=\"x\" data-permlink=\"$p\">t</a></p>")
        }
    }
}
