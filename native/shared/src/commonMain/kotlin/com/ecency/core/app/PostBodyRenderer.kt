package com.ecency.core.app

import com.ecency.core.api.model.Post
import com.ecency.core.render.Entry
import com.ecency.core.render.ImageProxy
import com.ecency.core.render.RenderHelper
import com.ecency.core.render.blocks.Block
import com.ecency.core.render.blocks.BlockBuilder
import com.ecency.core.render.blocks.BlockText
import com.ecency.core.render.blocks.BuildOptions
import com.ecency.core.render.blocks.SafeTarget
import com.ecency.core.render.blocks.SpeakAudio
import com.ecency.core.render.dom.HtmlStringFixes
import com.ecency.core.render.dom.RenderBodyParser
import com.ecency.core.render.dom.XmlSerializer
import com.ecency.core.render.markdown.Markdown

/**
 * Post body to what the screens draw. Implementations are pure and may be slow (a long post
 * takes milliseconds to tens of milliseconds): the models only call them on
 * [AppDispatchers.render], never on the main thread. They must never throw.
 */
interface PostBodyRenderer {
    /** The body as native blocks. */
    fun blocks(post: Post): List<Block>

    /** Plain-text card summary, at most [SUMMARY_LENGTH] characters. */
    fun summary(post: Post): String

    /** The card image, already proxied, or null. */
    fun cardImage(post: Post): String?

    /** The same renderer over another image proxy (settings "Image Server"); renderers without a proxy return themselves. */
    fun withProxyBase(base: String): PostBodyRenderer = this

    companion object {
        /** The RN app's SUMMARY_LENGTH. */
        const val SUMMARY_LENGTH = 150

        /** The renderer the app uses: the Kotlin port of render-helper, then native blocks. */
        fun default(proxyBase: String = ImageProxy.DEFAULT_BASE): PostBodyRenderer = RenderHelperPostBodyRenderer(proxyBase)
    }
}

/**
 * The app renderer: `renderPostBodySafe(body, forApp = true)` as the RN app calls render-helper,
 * then [BlockBuilder]. Summary and card image use `postBodySummary` and `catchPostImage(600, 500)`
 * like the RN feed cards.
 */
class RenderHelperPostBodyRenderer(
    private val proxyBase: String = ImageProxy.DEFAULT_BASE,
    val platform: RenderHelper.Platform = RenderHelper.Platform.ANDROID,
) : PostBodyRenderer {
    private val helper = RenderHelper(proxyBase)

    override fun withProxyBase(base: String): PostBodyRenderer = RenderHelperPostBodyRenderer(base, platform)

    private fun entry(post: Post) = Entry(
        author = post.author,
        permlink = post.permlink,
        lastUpdate = post.updated,
        body = post.body,
        jsonMetadata = post.metadata.rawJson,
    )

    override fun blocks(post: Post): List<Block> = safely(emptyList()) {
        val speak = post.metadata.speak
        val rendered = helper.renderPostBodySafe(post.body, forApp = true)
        // A Speak voice post: the native player replaces the body's own audio markup (RN postHtmlRenderer).
        val html = if (speak != null) SpeakAudio.stripBodyAudio(rendered) else rendered
        val blocks = BlockBuilder.build(html, BuildOptions(metadataImage = post.metadata.images.firstOrNull(), proxyBase = proxyBase))
        if (speak == null) blocks else blocks + Block.SpeakAudio(SpeakAudio.streamUrl(post.author, post.permlink), speak.durationMs, speak.waveformPeaks)
    }

    override fun summary(post: Post): String = safely("") {
        helper.postBodySummary(entry(post), PostBodyRenderer.SUMMARY_LENGTH, platform)
    }

    override fun cardImage(post: Post): String? = safely(null) {
        helper.catchPostImage(entry(post), 600, 500)?.takeIf { it.isNotEmpty() }
    }

    private inline fun <T> safely(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        fallback
    }
}

/**
 * Former stand-in renderer, kept for comparison tests only. It skipped render-helper's DOM
 * transforms (no chips, no video links). The app uses [RenderHelperPostBodyRenderer].
 * Earlier: TEMPORARY renderer until the Kotlin port of render-helper's `renderPostBody` merges. It runs
 * the stages that exist on this branch (markdown, parse, serialize, finish, blocks) WITHOUT
 * render-helper's DOM transforms, so there are no `@user` / `#tag` chips, no video link
 * classes and no link classification yet. Body images are routed through the image proxy so
 * readers never fetch author-chosen URLs directly. Do not build on its quirks.
 */
class InterimPostBodyRenderer(private val proxyBase: String = ImageProxy.DEFAULT_BASE) : PostBodyRenderer {
    private val proxy = ImageProxy(proxyBase)

    override fun withProxyBase(base: String): PostBodyRenderer = InterimPostBodyRenderer(base)

    override fun blocks(post: Post): List<Block> = safely(emptyList()) {
        BlockBuilder.build(html(post.body), BuildOptions(metadataImage = post.metadata.images.firstOrNull(), proxyBase = proxyBase))
            .map(::proxied)
    }

    override fun summary(post: Post): String = safely("") {
        val declared = post.metadata.description?.let { cut(plain(BlockText.of(BlockBuilder.build(html(it))))) }
        declared?.takeIf { it.isNotEmpty() } ?: cut(plain(BlockText.of(BlockBuilder.build(html(post.body)))))
    }

    override fun cardImage(post: Post): String? = safely(null) {
        val first = post.metadata.images.firstNotNullOfOrNull { SafeTarget.httpUrl(it) } ?: return@safely null
        // catchPostImage(post, 600, 500, 'match') in the RN app
        proxy.proxify(first, 600, 500).takeIf { it.startsWith("$proxyBase/") }
    }

    private fun html(markdown: String): String {
        val rendered = try {
            Markdown.render(markdown)
        } catch (e: Exception) {
            // remarkable throws on a few inputs (lone surrogates in link targets); show the text
            return RenderBodyParser.finish(escape(markdown))
        }
        val parsed = RenderBodyParser.parse(HtmlStringFixes.fixBlockLevelTagsInParagraphs(rendered))
        val serialized = parsed.document?.let { XmlSerializer.serializeToString(it) } ?: parsed.sanitizedHtml.orEmpty()
        return RenderBodyParser.finish(serialized)
    }

    private fun proxied(block: Block): Block = when (block) {
        is Block.Image -> proxiedImage(block)
        is Block.ImageGrid -> Block.ImageGrid(block.images.map(::proxiedImage))
        is Block.Quote -> Block.Quote(block.blocks.map(::proxied))
        is Block.ListBlock -> block.copy(items = block.items.map { it.map(::proxied) })
        is Block.Table -> Block.Table(block.rows.map { r -> Block.Table.Row(r.cells.map { c -> c.copy(blocks = c.blocks.map(::proxied)) }) })
        is Block.Container -> block.copy(blocks = block.blocks.map(::proxied))
        is Block.Details -> block.copy(blocks = block.blocks.map(::proxied))
        else -> block
    }

    private fun proxiedImage(image: Block.Image): Block.Image =
        image.copy(src = SafeTarget.proxify(image.src, proxyBase) ?: image.src)

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun plain(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /** Cut at a word boundary, as postBodySummary; text without spaces is cut by length. */
    private fun cut(s: String): String {
        if (s.length <= PostBodyRenderer.SUMMARY_LENGTH) return s
        val head = s.take(PostBodyRenderer.SUMMARY_LENGTH + 1)
        val space = head.lastIndexOf(' ')
        return (if (space > 0) head.take(space) else head.take(PostBodyRenderer.SUMMARY_LENGTH)).trimEnd()
    }

    private inline fun <T> safely(fallback: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        fallback
    }
}
