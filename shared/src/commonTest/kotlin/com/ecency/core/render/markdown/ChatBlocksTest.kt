package com.ecency.core.render.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Chat's block markdown against remarkable and simpleMarkdownToHTML (ChatBlockFixtures). */
class ChatBlocksTest {
    @Test
    fun everyCaseMatchesTheOracle() {
        for (case in ChatBlockFixtures.cases) {
            assertEquals(case.tree, tree(ChatBlocks.parse(case.input)), "blocks of <${case.input}>")
        }
    }

    @Test
    fun theFixturesCoverTheEdgeCases() {
        val inputs = ChatBlockFixtures.cases.map { it.input }.toSet()
        for (must in listOf(
            "- a\n- b", "-word", "1. a\n2. b", "#hashtag", "# introduceyourself", "text\n---", "> quote @bob\n> more",
            "# @bob says hi", "```\n- not list\n# not heading\n```", "!ban <user> [30d]", "- a\n  - b\n- c",
        )) {
            assertTrue(must in inputs, must)
        }
        assertTrue(ChatBlockFixtures.cases.size > 400)
    }

    // the generator's serialized form: p(runs) h2(runs) quote[...] ul[li[...]] ol3[...] code("text") hr
    private fun tree(blocks: List<MdBlock>): String = blocks.joinToString(",") { b ->
        when (b) {
            is MdBlock.Paragraph -> "p(${runs(b.runs)})"
            is MdBlock.Heading -> "h${b.level}(${runs(b.runs)})"
            is MdBlock.Quote -> "quote[${tree(b.blocks)}]"
            is MdBlock.ListBlock -> (if (b.ordered) "ol${b.start}" else "ul") + "[" + b.items.joinToString(",") { "li[${tree(it)}]" } + "]"
            is MdBlock.Code -> "code(${str(b.text)})"
            MdBlock.Rule -> "hr"
        }
    }

    private fun runs(runs: List<InlineRun>): String {
        val merged = ArrayList<InlineRun>()
        for (r in runs) {
            val text = r.text.replace(Regex(" *\n *"), "\n")
            val p = merged.lastOrNull()
            if (p != null && p.copy(text = "") == r.copy(text = "")) merged[merged.size - 1] = p.copy(text = p.text + text)
            else if (text.isNotEmpty()) merged += r.copy(text = text)
        }
        return merged.joinToString("") { r ->
            (if (r.bold) "b" else "") + (if (r.italic) "i" else "") + (if (r.strike) "s" else "") + (if (r.code) "c" else "") + str(r.text)
        }
    }

    // JSON.stringify for the characters the cases hold
    private fun str(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u" + c.code.toString(16).padStart(4, '0'))
                else -> append(c)
            }
        }
        append('"')
    }
}
