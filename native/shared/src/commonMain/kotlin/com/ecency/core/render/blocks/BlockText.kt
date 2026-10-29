package com.ecency.core.render.blocks

/**
 * The text a reader sees in a block list, in reading order: chips as `@user` / `#tag`, line breaks
 * as newlines, one newline between blocks. Images, videos, rules and post preview cards contribute
 * nothing. Used for copy actions and to prove [BlockBuilder] loses no text.
 */
object BlockText {
    fun of(blocks: List<Block>): String = buildString { blocks.forEach { appendBlock(this, it) } }

    fun ofInlines(inlines: List<Inline>): String = buildString { inlines.forEach { appendInline(this, it) } }

    private fun appendBlock(sb: StringBuilder, b: Block) {
        when (b) {
            is Block.Paragraph -> b.inlines.forEach { appendInline(sb, it) }
            is Block.Heading -> b.inlines.forEach { appendInline(sb, it) }
            is Block.Code -> sb.append(b.text)
            is Block.Quote -> b.blocks.forEach { appendBlock(sb, it) }
            is Block.ListBlock -> b.items.forEach { item -> item.forEach { appendBlock(sb, it) }; sb.append('\n') }
            is Block.Table -> b.rows.forEach { r -> r.cells.forEach { c -> c.blocks.forEach { appendBlock(sb, it) }; sb.append('\t') }; sb.append('\n') }
            is Block.Container -> b.blocks.forEach { appendBlock(sb, it) }
            is Block.Details -> {
                b.summary.forEach { appendInline(sb, it) }
                sb.append('\n')
                b.blocks.forEach { appendBlock(sb, it) }
            }
            is Block.Image, is Block.ImageGrid, is Block.Video, is Block.PostPreview, is Block.SpeakAudio, Block.Rule -> {}
            is Block.Anchor -> return
        }
        if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
    }

    private fun appendInline(sb: StringBuilder, i: Inline) {
        when (i) {
            is Inline.Text -> sb.append(i.text)
            is Inline.Link -> i.children.forEach { appendInline(sb, it) }
            is Inline.Mention -> sb.append('@').append(i.author)
            is Inline.Tag -> sb.append('#').append(i.tag)
            Inline.LineBreak -> sb.append('\n')
        }
    }
}
