package com.ecency.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A chat message's blocks (#3468): web's lists, quotes, headings and code, with chat's mentions, links and marks. */
class ChatMessageBlocksTest {
    private fun text(blocks: List<ChatBlock>) = ChatText.flatten(blocks).joinToString("") { it.text }

    @Test
    fun aListAQuoteAndAHeadingKeepTheirMentionsLinksAndMarks() {
        val raw = "# hi @bob\n- see @alice and https://x.com/a\n- **two**\n\n> quoting @carol"
        val blocks = ChatText.blocks(ChatText.content(raw).text)
        assertEquals(3, blocks.size, "$blocks")
        val heading = blocks[0] as ChatHeadingBlock
        assertEquals(1, heading.level)
        assertEquals(listOf("hi ", "@bob"), heading.spans.map { it.text })
        assertEquals("https://ecency.com/@bob", heading.spans[1].url)
        val list = blocks[1] as ChatListBlock
        assertEquals(false, list.ordered)
        assertEquals(2, list.items.size)
        val first = (list.items[0].blocks.single() as ChatParagraphBlock).spans
        assertEquals(listOf("https://ecency.com/@alice", "https://x.com/a"), first.mapNotNull { it.url })
        assertEquals("@alice", first.first { it.url != null }.text)
        val second = (list.items[1].blocks.single() as ChatParagraphBlock).spans
        assertTrue(second.single().bold && second.single().text == "two", "$second")
        val quote = blocks[2] as ChatQuoteBlock
        val quoted = (quote.blocks.single() as ChatParagraphBlock).spans
        assertEquals("https://ecency.com/@carol", quoted.single { it.url != null }.url)
    }

    @Test
    fun aMentionInAQuoteOrAListIsBold() {
        val quote = ChatText.blocks("> hi @bob").single() as ChatQuoteBlock
        assertTrue((quote.blocks.single() as ChatParagraphBlock).spans.single { it.text == "@bob" }.bold)
        val list = ChatText.blocks("- مرحبا @bob\n- شكرا").single() as ChatListBlock
        assertTrue((list.items[0].blocks.single() as ChatParagraphBlock).spans.single { it.text == "@bob" }.bold)
        assertEquals("شكرا", text(list.items[1].blocks))
    }

    @Test
    fun aHashtagOrADashBeforeAWordIsNoBlock() {
        for (plain in listOf("#hashtag at the start", "-word", "1.5 is a number", "a - b")) {
            val block = ChatText.blocks(plain).single()
            assertTrue(block is ChatParagraphBlock, "$plain: $block")
            assertEquals(plain, text(listOf(block)))
        }
        assertTrue(ChatText.blocks("# introduceyourself").single() is ChatHeadingBlock)
        assertEquals(2, (ChatText.blocks("text\n---").single() as ChatHeadingBlock).level)
    }

    @Test
    fun codeBlocksShowTheirTextAsTyped() {
        val blocks = ChatText.blocks("```js\n**x** @bob https://x.com\n- no list\n```\nafter @carol")
        assertEquals("**x** @bob https://x.com\n- no list", (blocks[0] as ChatCodeBlock).text)
        assertTrue((blocks[1] as ChatParagraphBlock).spans.single { it.text == "@carol" }.bold)
        // a mention in a fence's info string is not shown, and the ones after it stay in place
        val info = ChatText.blocks("```@bob\nx\n```\nhi @carol https://x.com")
        assertEquals("x", (info[0] as ChatCodeBlock).text)
        val after = (info[1] as ChatParagraphBlock).spans
        assertEquals(listOf("hi ", "@carol", " ", "https://x.com"), after.map { it.text })
        assertEquals("https://x.com", after.last().url)
    }

    @Test
    fun orderedListsCountFromOneAsWebShowsThemAndNestedBulletsChangeShape() {
        val ordered = ChatText.blocks("3. a\n4. b").single() as ChatListBlock
        assertTrue(ordered.ordered)
        assertEquals(listOf("1.", "2."), ordered.items.map { it.marker })
        val nested = ChatText.blocks("- a\n  - b").single() as ChatListBlock
        assertEquals("•", nested.items[0].marker)
        val inner = nested.items[0].blocks[1] as ChatListBlock
        assertEquals(1, inner.depth)
        assertEquals("◦", inner.items[0].marker)
    }

    @Test
    fun angleBracketsHtmlAndTablesStayAsTyped() {
        assertEquals("!ban <user> [30d]\n<div>x</div>", text(ChatText.blocks("!ban <user> [30d]\n<div>x</div>")))
        assertEquals("a | b\n--|--\n1 | 2", text(ChatText.blocks("a | b\n--|--\n1 | 2")))
    }

    @Test
    fun aMessageThatIsOnlyAnImageHasNoBlocks() {
        val content = ChatText.content("https://images.example.com/a.png")
        assertEquals(1, content.images.size)
        assertEquals(emptyList(), ChatText.blocks(content.text))
    }

    @Test
    fun aMessageLongerThanTheMarkdownCapIsOneParagraphAsTyped() {
        val long = "- **a** https://x.com\n" + "x".repeat(ChatText.MAX_MARKDOWN)
        val block = ChatText.blocks(long).single() as ChatParagraphBlock
        assertTrue(block.spans.first().text.startsWith("- **a**"))
        assertEquals("https://x.com", block.spans.single { it.url != null }.url)
        // a long message under the cap still reads its blocks
        val many = (1..250).joinToString("\n") { "- item $it with **bold**" }
        val list = ChatText.blocks(many).single() as ChatListBlock
        assertEquals(250, list.items.size)
    }

    @Test
    fun theFlatReadingNamesEachBlockOnItsOwnLine() {
        val blocks = ChatText.blocks("# Title\n- a\n- b\n\n1. c\n\n> q\n\n---\n\n```\ncode\n```")
        assertEquals("Title\n• a\n• b\n1. c\nq\ncode", text(blocks))
        // one paragraph reads as before
        assertEquals(ChatText.spans("hi **@bob** there"), ChatText.flatten(ChatText.blocks("hi **@bob** there")))
    }
}
