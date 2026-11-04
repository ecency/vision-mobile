package com.ecency.core.render.blocks

/**
 * Native post body model. The shared core turns render-helper-equivalent HTML into these blocks once
 * per body; Compose and SwiftUI draw them with native text, image and list views. Nothing here is
 * HTML-specific, so the UI layers never parse markup.
 */
sealed interface Block {
    data class Paragraph(val inlines: List<Inline>, val style: BlockStyle = BlockStyle.Default) : Block

    data class Heading(val level: Int, val inlines: List<Inline>, val style: BlockStyle = BlockStyle.Default) : Block {
        init {
            require(level in 1..6) { "heading level $level" }
        }
    }

    /** A standalone image. [link] is set when the image is wrapped in an anchor; a tap follows it instead of opening the viewer. */
    data class Image(val src: String, val alt: String?, val link: LinkTarget? = null) : Block

    /** Two or more consecutive images in a wave, laid out as half-width tiles. */
    data class ImageGrid(val images: List<Image>) : Block

    data class Quote(val blocks: List<Block>) : Block

    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<Block>>) : Block

    data class Code(val text: String) : Block

    data object Rule : Block

    data class Table(val rows: List<Row>) : Block {
        data class Row(val cells: List<Cell>)
        data class Cell(val header: Boolean, val blocks: List<Block>)

        /** Header cell count of the widest row; above 3 columns the table scrolls horizontally, as in the RN app. */
        val maxHeaderColumns: Int get() = rows.maxOfOrNull { r -> r.cells.count { it.header } } ?: 0
    }

    /** A video shown as a thumbnail with a play button; [embedUrl] goes to the player on tap. */
    data class Video(
        val embedUrl: String,
        val thumbnail: String?,
        val youtubeId: String? = null,
        val startSeconds: Int = 0,
    ) : Block

    /** Inline link to another Hive post, drawn as a preview card. */
    data class PostPreview(val author: String, val permlink: String, val href: String?) : Block

    /** Layout wrapper for `<center>`, `<div class=...>` and similar. */
    data class Container(val blocks: List<Block>, val style: BlockStyle) : Block

    data class Details(val summary: List<Inline>, val blocks: List<Block>) : Block

    /**
     * Liketu Speak voice player under the body (`json_metadata.speak`). [streamUrl] is the transcoded
     * stream ([SpeakAudio.streamUrl], "" when the post has no author or permlink); [peaks] may be empty
     * (a plain progress track is drawn instead).
     */
    data class SpeakAudio(val streamUrl: String, val durationMs: Long, val peaks: List<Double>) : Block

    /**
     * Where an element with an `id` (or the `data-id` web's anchor handler reads) starts: the jump target of an
     * in-post `#id` link ([LinkTarget.Anchor], [BlockAnchors]). Drawn as nothing (zero height); it sits right before
     * the block that holds the element.
     */
    data class Anchor(val ids: List<String>) : Block
}

data class BlockStyle(
    val align: Align = Align.Start,
    val justify: Boolean = false,
    val phishy: Boolean = false,
) {
    /**
     * [Start]: no alignment set, the body's own start (left in a left to right body, right in a right to left one,
     * [isRtlContent]). [Left] and [End] are the HTML's `left` and `right`, absolute in CSS whatever the direction
     * ([End] keeps its name for the existing callers; it is the right edge, as iOS's `.right`).
     */
    enum class Align { Start, Center, End, Left }

    companion object {
        val Default = BlockStyle()
    }
}

sealed interface Inline {
    data class Text(val text: String, val marks: Set<Mark> = emptySet()) : Inline

    data class Link(val target: LinkTarget, val children: List<Inline>) : Inline

    /** `@user` chip with avatar. */
    data class Mention(val author: String) : Inline

    /** `#tag` chip. */
    data class Tag(val tag: String, val filter: String? = null) : Inline

    data object LineBreak : Inline

    enum class Mark { Bold, Italic, Strike, Code, Sub, Sup, Underline, Highlight }
}

/** What a tap does. Mirrors render-helper's link classes as read by the RN app's linkDataParser. */
sealed interface LinkTarget {
    data class Author(val author: String) : LinkTarget
    data class Tag(val tag: String, val filter: String?) : LinkTarget
    data class Community(val community: String, val filter: String?) : LinkTarget
    data class Post(val author: String, val permlink: String) : LinkTarget
    data class Youtube(val videoId: String, val startSeconds: Int) : LinkTarget
    data class Video(val embedUrl: String) : LinkTarget

    /** An http(s) URL: opens the link sheet (copy, in-app browser, external). */
    data class External(val url: String) : LinkTarget

    /**
     * A `hive://` or `ecency://` URI. Goes to the app's link processor, which must ask the user to
     * confirm before any signing operation.
     */
    data class AppUri(val uri: String) : LinkTarget

    /**
     * An in-post jump (#2403): `#id`, or a link to [post] with `#id` (a jump when [post] is the one being read, else
     * that post opens). The fragment as written, percent-decoded; resolved by [BlockAnchors.resolve]. Never a
     * new screen for the same post.
     */
    data class Anchor(val id: String, val post: Post? = null) : LinkTarget
}
