package com.ecency.core.render.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Chat's inline marks against remarkable and simpleMarkdownToHTML (ChatInlineFixtures). */
class ChatInlineTest {
    @Test
    fun everyCaseMatchesTheOracle() {
        for (case in ChatInlineFixtures.cases) {
            val expected = case.runs.map { InlineRun(it.text, it.bold, it.italic, it.strike, it.code) }
            assertEquals(expected, ChatInline.runs(case.input), "runs of <${case.input}>")
        }
    }

    @Test
    fun theFixturesCoverTheEdgeCases() {
        val inputs = ChatInlineFixtures.cases.map { it.input }.toSet()
        for (must in listOf("snake_case_name", "!ban <user> [30d]", "`*a*`", "\\*not italic\\*", "**a\n\nb**", "a*b*c")) {
            assertTrue(must in inputs, must)
        }
        assertTrue(ChatInlineFixtures.cases.size > 300)
    }
}
