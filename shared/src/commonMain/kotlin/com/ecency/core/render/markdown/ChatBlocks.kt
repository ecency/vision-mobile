package com.ecency.core.render.markdown

/** A block of a chat message as remarkable reads it, its text as [InlineRun]s (README "Chats"). */
internal sealed interface MdBlock {
    class Paragraph(val runs: List<InlineRun>) : MdBlock
    class Heading(val level: Int, val runs: List<InlineRun>) : MdBlock
    class Quote(val blocks: List<MdBlock>) : MdBlock
    /** [start] is the number the first item was written with (web's sanitizer drops it). */
    class ListBlock(val ordered: Boolean, val start: Long, val items: List<List<MdBlock>>) : MdBlock
    /** A fenced or indented code block, without its last line break. [info] is a fence's info string (not shown). */
    class Code(val text: String, val info: String) : MdBlock
    object Rule : MdBlock
}

/**
 * The blocks of a chat message as web's `simpleMarkdownToHTML` (remarkable, default preset) reads them: paragraphs,
 * headings (`# a`, setext), block quotes, bullet and ordered lists (nested), fenced and indented code, and rules.
 * HTML blocks, tables and footnotes are not read (their lines stay text), and the inline text of each block is read
 * with chat's inline marks ([ChatInline]). Pinned by ChatBlockFixtures.
 */
internal object ChatBlocks {
    private val block = BlockParser(full = false, chat = true)
    private val inline = InlineParser.chat()

    fun parse(src: String): List<MdBlock> {
        if (src.isEmpty()) return emptyList()
        val tokens = ArrayList<Token>()
        block.parse(src, inline, Env(), tokens)
        val reader = Reader(tokens)
        return reader.blocks { false }
    }

    private class Reader(val tokens: List<Token>) {
        var k = 0

        fun blocks(until: (Token) -> Boolean): List<MdBlock> {
            val out = ArrayList<MdBlock>()
            while (k < tokens.size && !until(tokens[k])) {
                val t = tokens[k++]
                when (t.type) {
                    "paragraph_open" -> {
                        val text = tokens[k++].content
                        k++ // paragraph_close
                        out += MdBlock.Paragraph(ChatInline.paragraph(text))
                    }
                    "heading_open" -> {
                        val runs = if (tokens[k].type == "inline") ChatInline.paragraph(tokens[k++].content) else emptyList()
                        k++ // heading_close
                        out += MdBlock.Heading(t.hLevel, runs)
                    }
                    "blockquote_open" -> {
                        out += MdBlock.Quote(blocks { it.type == "blockquote_close" })
                        k++
                    }
                    "bullet_list_open", "ordered_list_open" -> {
                        val close = t.type.replace("_open", "_close")
                        val items = ArrayList<List<MdBlock>>()
                        while (tokens[k].type != close) {
                            k++ // list_item_open
                            items += blocks { it.type == "list_item_close" }
                            k++
                        }
                        k++
                        val ordered = t.type == "ordered_list_open"
                        out += MdBlock.ListBlock(ordered, if (ordered) t.order.toLongOrNull() ?: 1 else 1, items)
                    }
                    "code" -> out += MdBlock.Code(t.content.removeSuffix("\n"), "")
                    "fence" -> out += MdBlock.Code(t.content.removeSuffix("\n"), t.params)
                    "hr" -> out += MdBlock.Rule
                }
            }
            return out
        }
    }
}
