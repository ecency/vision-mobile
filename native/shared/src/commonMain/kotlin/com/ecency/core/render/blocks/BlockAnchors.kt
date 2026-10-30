package com.ecency.core.render.blocks

/**
 * In-post jumps (#2403): which top-level block an `#id` link scrolls to. The UIs keep one lazy row per top-level
 * block, so a jump is "scroll that row to the top"; an anchor inside a quote, list, table, container or spoiler
 * jumps to the block that holds it.
 */
object BlockAnchors {
    /** Every id in [blocks] and the top-level index it jumps to; the first element with an id wins, as in a browser. */
    fun index(blocks: List<Block>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        blocks.forEachIndexed { i, b -> ids(b) { id -> if (id !in out) out[id] = i } }
        return out
    }

    /**
     * The top-level index [fragment] jumps to, or null when the body has no such place: an element whose `id` or
     * `data-id` is the fragment; then a heading whose text, made into a slug as GitHub and most markdown sites do
     * ("Hello, World!" is `hello-world`), is the fragment's slug (render-helper gives headings no ids, so links
     * written for those sites still land); then, as a browser does, `#` alone and `#top` are the top of the post.
     */
    fun resolve(blocks: List<Block>, fragment: String): Int? {
        if (blocks.isEmpty()) return null
        if (fragment.isEmpty()) return 0
        index(blocks)[fragment]?.let { return it }
        val wanted = slug(fragment)
        if (wanted.isNotEmpty()) {
            blocks.forEachIndexed { i, b -> if (headings(b).any { slug(BlockText.ofInlines(it)) == wanted }) return i }
        }
        return if (fragment.equals("top", ignoreCase = true)) 0 else null
    }

    /** GitHub's heading slug: lower case, letters, digits, `-` and `_` kept, white space to `-`, the rest dropped. */
    fun slug(text: String): String = buildString {
        for (c in text.trim().lowercase()) when {
            c.isLetterOrDigit() || c == '-' || c == '_' -> append(c)
            c.isWhitespace() -> append('-')
        }
    }

    private fun ids(b: Block, onId: (String) -> Unit) {
        when (b) {
            is Block.Anchor -> b.ids.forEach(onId)
            is Block.Quote -> b.blocks.forEach { ids(it, onId) }
            is Block.ListBlock -> b.items.forEach { item -> item.forEach { ids(it, onId) } }
            is Block.Table -> b.rows.forEach { r -> r.cells.forEach { c -> c.blocks.forEach { ids(it, onId) } } }
            is Block.Container -> b.blocks.forEach { ids(it, onId) }
            is Block.Details -> b.blocks.forEach { ids(it, onId) }
            else -> {}
        }
    }

    private fun headings(b: Block): List<List<Inline>> = when (b) {
        is Block.Heading -> listOf(b.inlines)
        is Block.Quote -> b.blocks.flatMap(::headings)
        is Block.Container -> b.blocks.flatMap(::headings)
        is Block.Details -> b.blocks.flatMap(::headings)
        is Block.ListBlock -> b.items.flatten().flatMap(::headings)
        else -> emptyList()
    }

    /**
     * What a tap on [target] does on the post [current] (author, permlink): the top-level index to scroll to, or null
     * when it is not a jump within it (another post opens, an unknown id does nothing).
     */
    fun jump(blocks: List<Block>, target: LinkTarget.Anchor, current: LinkTarget.Post?): Int? {
        val post = target.post
        if (post != null && (current == null || !post.author.equals(current.author, ignoreCase = true) || post.permlink != current.permlink)) return null
        return resolve(blocks, target.id)
    }
}
