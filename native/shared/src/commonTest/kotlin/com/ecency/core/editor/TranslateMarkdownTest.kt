package com.ecency.core.editor

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** [TranslateMarkdown] and [EmojiStrip] against RN's own sources ([TranslateGoldenVectors]). */
class TranslateMarkdownTest {
    @Test
    fun documentsMatchRn() = runTest {
        for (c in TranslateGoldenVectors.cases) {
            val sent = mutableListOf<String>()
            val result = TranslateMarkdown.translate(c.markdown, translate = { sent += it; "T($it)" })
            assertEquals(c.requests, sent, "requests for ${c.markdown.take(60)}")
            assertEquals(c.result, result, "result for ${c.markdown.take(60)}")
        }
    }

    @Test
    fun chunksMatchRn() {
        for (c in TranslateGoldenVectors.chunks) assertEquals(c.chunks, TranslateMarkdown.chunk(c.text, c.max), c.text)
    }

    @Test
    fun emojiMatchRn() {
        for (c in TranslateGoldenVectors.emoji) {
            val s = EmojiStrip.strip(c.text)
            assertEquals(c.clean, s.clean, c.text)
            assertEquals(c.emojis, s.emojis, c.text)
        }
    }

    @Test
    fun aFailedRequestFailsTheWholeDocument() = runTest {
        var n = 0
        assertFailsWith<IllegalStateException> {
            TranslateMarkdown.translate("# one\n\n# two\n\n# three", translate = { if (++n == 2) throw IllegalStateException("down") else "x" })
        }
        assertEquals(2, n)
    }

    @Test
    fun cancelIsAskedBeforeEveryRequest() = runTest {
        var n = 0
        val progress = mutableListOf<Pair<Int, Int>>()
        assertFailsWith<TranslateMarkdown.Cancelled> {
            TranslateMarkdown.translate("# one\n\n# two\n\n# three", translate = { n++; "x" }, onProgress = { d, t -> progress += d to t }, isCancelled = { n >= 1 })
        }
        assertEquals(1, n)
        assertEquals(listOf(0 to 3, 1 to 3), progress)
    }

    @Test
    fun nothingIsSentForCodeOnly() = runTest {
        var n = 0
        val doc = "```\ncode\n```\n\n![a](https://x.y/z.png)"
        assertEquals(doc, TranslateMarkdown.translate(doc, translate = { n++; it }))
        assertEquals(0, n)
        assertTrue(TranslateMarkdown.requests(doc).isEmpty())
    }
}
