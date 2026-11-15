package com.ecency.core.app

import com.ecency.core.api.model.Post
import com.ecency.core.render.blocks.Block
import com.ecency.core.render.blocks.plainText
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Text a reader selected in a post or comment body (#3699; web's selection popover): what Quote puts in the reply,
 * what Share sends, and the whole body as plain text for the Select text view. RN's bodies were not selectable.
 */
object BodySelection {
    /**
     * The selection as a markdown quote: every line after `> ` (a blank line as `>`, so the paragraphs after it stay
     * in the quote), then a blank line for the reply. Web prefixes only the first line, which quotes only the first
     * paragraph of a longer selection. Null when nothing but whitespace was selected.
     */
    fun quote(selection: String): String? {
        val text = selection.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.isEmpty()) return null
        return text.lines().joinToString("\n") { line -> line.trimEnd().let { if (it.isEmpty()) ">" else "> $it" } } + "\n\n"
    }

    /** [draft] with the quote of [selection] after it, a blank line between; the quote alone when the draft is blank. */
    fun appendQuote(draft: String, selection: String): String? {
        val quote = quote(selection) ?: return null
        return if (draft.isBlank()) quote else draft.trimEnd() + "\n\n" + quote
    }

    /** The share sheet's text: the selection in quotes, then the post's link (web shared the selection and the link). */
    fun shareText(selection: String, post: Post): String = "\"${selection.trim()}\"\n\n${PostActionsModel.postUrl(post)}"

    /** The body as plain text for the Select text view: one block a paragraph, list items on their own lines. */
    fun text(blocks: List<Block>): String = blocks.mapNotNull { blockText(it).trim().ifEmpty { null } }.joinToString("\n\n")

    private fun blockText(block: Block): String = when (block) {
        is Block.Paragraph -> block.inlines.plainText()
        is Block.Heading -> block.inlines.plainText()
        is Block.Quote -> text(block.blocks)
        is Block.Container -> text(block.blocks)
        is Block.Details -> listOf(block.summary.plainText().trim(), text(block.blocks)).filter { it.isNotEmpty() }.joinToString("\n\n")
        is Block.Code -> block.text
        is Block.ListBlock -> block.items.mapIndexed { i, item ->
            val marker = if (block.ordered) "${block.start + i}." else "•"
            "$marker ${text(item).replace("\n\n", "\n")}"
        }.joinToString("\n")
        is Block.Table -> block.rows.joinToString("\n") { row -> row.cells.joinToString(" | ") { text(it.blocks).replace("\n\n", " ") } }
        else -> ""
    }
}

data class SelectTextState(
    /** The body as plain text ([BodySelection.text]); null until rendered. */
    val text: String? = null,
)

/** The Select text view (#3699): the whole body of [post] as one selectable text, for selections across paragraphs. */
class SelectTextModel internal constructor(private val env: AppEnvironment, val post: Post) :
    StateModel<SelectTextState>(SelectTextState(), env.dispatchers) {
    fun start() {
        if (current.text != null) return
        scope.launch {
            val text = withContext(env.dispatchers.render) { BodySelection.text(env.renderer.blocks(post)) }
            setState { it.copy(text = text) }
        }
    }
}
