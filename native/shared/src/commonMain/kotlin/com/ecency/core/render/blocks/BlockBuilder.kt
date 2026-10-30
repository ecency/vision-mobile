package com.ecency.core.render.blocks

import com.ecency.core.render.blocks.Inline.Mark
import com.ecency.core.render.dom.CDATASection
import com.ecency.core.render.dom.Document
import com.ecency.core.render.dom.Element
import com.ecency.core.render.dom.LenientHtml
import com.ecency.core.render.dom.Node
import com.ecency.core.render.dom.RenderBodyParser
import com.ecency.core.render.dom.Text
import com.ecency.core.render.dom.XmlDomParser

/**
 * [BlockBuilder] options.
 *
 * - [waves]: two or more consecutive image-only paragraphs become a [Block.ImageGrid], as the RN
 *   app does for waves (`metadata.type == "wave"`).
 * - [metadataImage]: the post's first `json_metadata.image`; video blocks without their own
 *   thumbnail use it (proxied), as the RN app does.
 * - [proxyBase]: the image proxy the body was rendered with; only thumbnails it serves are kept.
 */
data class BuildOptions(
    val waves: Boolean = false,
    val metadataImage: String? = null,
    val proxyBase: String = "https://images.ecency.com",
)

/**
 * Turns render-helper's final post HTML (`renderPostBody(body, true, false)`) into the native block
 * model. The mapping follows what the RN app's renderer does with each tag, class and attribute
 * (`src/components/postHtmlRenderer`, `linkDataParser.ts`); `native/docs/post-rendering.md` has
 * the full table. Every tap target, embed and image URL goes through [SafeTarget]; an invalid one
 * leaves its text in place without a link.
 *
 * Whitespace collapses the way a browser displays it (runs become one space, none at line starts
 * or ends); `pre` keeps its text. Block elements inside inline ones end the current paragraph, so
 * nothing is dropped. Unknown tags contribute their children. [build] never throws: malformed
 * markup goes through render-helper's repair path, then through [LenientHtml].
 */
object BlockBuilder {
    /** Above this element depth the walk continues iteratively, without new marks or styles. */
    internal const val MAX_DEPTH = 120

    fun build(html: String): List<Block> = build(html, BuildOptions())

    fun build(html: String, options: BuildOptions): List<Block> = try {
        fromDocument(parse(html), options)
    } catch (e: Throwable) {
        if (e is OutOfMemoryError) throw e
        try {
            fromDocument(LenientHtml.parse(html), options)
        } catch (e2: Throwable) {
            if (e2 is OutOfMemoryError) throw e2
            emptyList()
        }
    }

    fun fromDocument(document: Document): List<Block> = fromDocument(document, BuildOptions())

    fun fromDocument(document: Document, options: BuildOptions): List<Block> {
        val root = document.documentElement ?: return emptyList()
        val body = if (lower(root.tagName) == "body") root else findBody(root) ?: root
        return Walker(options).top(body)
    }

    private fun parse(html: String): Document = try {
        XmlDomParser.parseFromString(RenderBodyParser.wrap(html))
    } catch (e: Exception) {
        RenderBodyParser.parseRepaired(html).document ?: LenientHtml.parse(html)
    }

    private fun findBody(root: Element): Element? {
        for (c in root.childNodes) if (c is Element && lower(c.tagName) == "body") return c
        return null
    }

    internal fun isCollapsible(c: Char) = c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u000C'

    internal fun lower(s: String): String {
        for (c in s) if (c in 'A'..'Z') return buildString(s.length) { for (ch in s) append(if (ch in 'A'..'Z') ch + 32 else ch) }
        return s
    }

    internal fun classes(el: Element): List<String> {
        val c = el.getAttribute("class") ?: return emptyList()
        return c.split(' ', '\t', '\n', '\r', '\u000C').filter { it.isNotEmpty() }
    }

    /** JS `parseInt(s) || 0`, as linkDataParser reads `data-start-time`. */
    internal fun parseIntJs(s: String?): Int {
        if (s == null) return 0
        var i = 0
        while (i < s.length && (s[i].isWhitespace() || s[i] == '﻿')) i++
        var neg = false
        if (i < s.length && (s[i] == '+' || s[i] == '-')) {
            neg = s[i] == '-'
            i++
        }
        var n = 0L
        var any = false
        while (i < s.length && s[i] in '0'..'9') {
            if (n < Int.MAX_VALUE) n = n * 10 + (s[i] - '0')
            any = true
            i++
        }
        if (!any) return 0
        val v = if (neg) -n else n
        return v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * linkDataParser's post link (anchored `...#@author/permlink` wins, a trailing query is cut),
     * then [SafeTarget.post].
     */
    internal fun postTarget(author: String?, permlink: String?): LinkTarget.Post? {
        var a = author
        var p = permlink?.takeIf { it.isNotEmpty() } ?: return null
        anchoredPost(p)?.let {
            a = it.first
            p = it.second
        }
        val q = p.lastIndexOf('?')
        if (q > -1) p = p.substring(0, q)
        return SafeTarget.post(a, p)
    }

    /** A `data-is-inline="true"` post link that the RN app draws as a preview card. */
    internal fun inlineCard(el: Element): LinkTarget.Post? {
        if (el.getAttribute("data-is-inline") != "true" || "markdown-post-link" !in classes(el)) return null
        if (el.getElementsByTagName("img").isNotEmpty()) return null
        return postTarget(el.getAttribute("data-author"), el.getAttribute("data-permlink"))
    }

    internal fun isVideoAnchor(el: Element): Boolean {
        val cls = classes(el)
        return "markdown-video-link" in cls || ("markdown-video-link-youtube" in cls && !el.getAttribute("data-youtube").isNullOrEmpty())
    }

    /** What a tap on [el] does, ignoring chips, cards and videos; null for no (or an unsafe) target. */
    internal fun anchorTarget(el: Element): LinkTarget? {
        val cls = classes(el)
        val href = el.getAttribute("data-href")?.takeIf { it.isNotBlank() } ?: el.getAttribute("href")?.takeIf { it.isNotBlank() }
        return when {
            "markdown-external-link" in cls -> {
                val yt = el.getAttribute("data-youtube")
                if (!yt.isNullOrEmpty()) SafeTarget.youtube(yt, parseIntJs(el.getAttribute("data-start-time")))
                else fragmentOnly(href)?.let { LinkTarget.Anchor(it) } ?: SafeTarget.external(href)
            }
            "markdown-author-link" in cls -> SafeTarget.author(el.getAttribute("data-author"))
            "markdown-post-link" in cls -> postTarget(el.getAttribute("data-author"), el.getAttribute("data-permlink"))
                ?.let { post -> fragmentOf(href)?.let { LinkTarget.Anchor(it, post) } ?: post } ?: SafeTarget.external(href)
            "markdown-tag-link" in cls -> SafeTarget.tagTarget(el.getAttribute("data-tag"), el.getAttribute("data-filter"))
            "markdown-witnesses-link" in cls || "markdown-proposal-link" in cls -> SafeTarget.external(href)
            "markdown-community-link" in cls -> SafeTarget.communityTarget(el.getAttribute("data-community"), el.getAttribute("data-filter"))
            else -> fragmentOnly(href)?.let { LinkTarget.Anchor(it) } ?: SafeTarget.external(href)
        }
    }

    /**
     * Author and permlink of an anchored `...#@author/permlink`, as linkDataParser's `(.*?#@)(.*)/(.*)`
     * reads them, in linear time (the regex backtracks cubically on a long data-permlink). `.` stops
     * at a line terminator, so the match is the first line whose first `#@` has a `/` after it on that
     * line: the author runs to that line's last `/`, the permlink to the line's end.
     */
    internal fun anchoredPost(p: String): Pair<String, String>? {
        var h = p.indexOf("#@")
        while (h >= 0) {
            var end = h + 2
            while (end < p.length && !isLineTerminator(p[end])) end++
            var slash = end - 1
            while (slash >= h + 2 && p[slash] != '/') slash--
            if (slash >= h + 2) return p.substring(h + 2, slash) to p.substring(slash + 1, end)
            h = p.indexOf("#@", end)
        }
        return null
    }

    /** What a Kotlin regex `.` does not match. JS's `.` does match U+0085; this keeps the old regex's reading. */
    private fun isLineTerminator(c: Char) = c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029'

    /** The fragment of a `#id` href (`#` alone is the top, an empty id), or null when [href] is more than that. */
    internal fun fragmentOnly(href: String?): String? {
        val h = href?.trim() ?: return null
        return if (h.startsWith("#")) fragment(h.substring(1)) else null
    }

    /** A post link's `#id` (never `#@author/permlink`, the comment form [postTarget] reads), or null. */
    internal fun fragmentOf(href: String?): String? {
        val h = href?.trim() ?: return null
        val at = h.indexOf('#')
        if (at < 0) return null
        val f = h.substring(at + 1)
        return if (f.isEmpty() || f.startsWith("@")) null else fragment(f)
    }

    /** Percent-decoded as a browser matches a fragment to an id; null when it cannot be an id (too long, a control). */
    private fun fragment(raw: String): String? {
        val decoded = runCatching { com.ecency.core.render.js.Js.decodeUriComponent(raw) }.getOrDefault(raw)
        if (decoded.length > MAX_ANCHOR_ID || decoded.any { it < ' ' }) return null
        return decoded
    }

    /** The longest id an anchor jump matches. */
    internal const val MAX_ANCHOR_ID = 256
    internal val YOUTUBE_EMBED = Regex("(?:youtube\\.com|youtube-nocookie\\.com)/embed/([A-Za-z0-9_-]{11})")

    internal val MARKS = mapOf(
        "strong" to Mark.Bold, "b" to Mark.Bold,
        "em" to Mark.Italic, "i" to Mark.Italic, "cite" to Mark.Italic, "var" to Mark.Italic, "dfn" to Mark.Italic,
        "del" to Mark.Strike, "s" to Mark.Strike, "strike" to Mark.Strike,
        "code" to Mark.Code, "kbd" to Mark.Code, "samp" to Mark.Code, "tt" to Mark.Code,
        "sub" to Mark.Sub, "sup" to Mark.Sup,
        "u" to Mark.Underline, "ins" to Mark.Underline,
        "mark" to Mark.Highlight,
    )

    /** Block-level tags with no meaning of their own: a paragraph boundary, then their children. */
    internal val PLAIN_BLOCKS = setOf(
        "p", "section", "article", "header", "footer", "main", "aside", "nav", "figure", "figcaption",
        "address", "dl", "dt", "dd", "hgroup", "summary", "body", "html", "form", "fieldset", "legend", "caption",
    )

    /** Every tag that starts and ends a block (text on either side never joins into one word). */
    internal val BLOCK_TAGS = PLAIN_BLOCKS + setOf(
        "h1", "h2", "h3", "h4", "h5", "h6", "center", "div", "blockquote", "ul", "ol", "li", "pre", "hr",
        "table", "thead", "tbody", "tfoot", "tr", "td", "th", "details", "iframe", "video", "img", "br",
    )

    /** Content never displayed. */
    internal val SKIPPED = setOf("script", "style", "head", "title", "noscript", "template", "colgroup", "col", "source", "track", "param")
}

/** Alignment and colour inherited from containers; `null` align means not set here. */
private data class Style(val align: BlockStyle.Align? = null, val justify: Boolean = false, val phishy: Boolean = false) {
    val isEmpty: Boolean get() = align == null && !justify && !phishy
    fun merge(inner: Style) = Style(inner.align ?: align, justify || inner.justify, phishy || inner.phishy)
    fun toBlockStyle() = BlockStyle(align ?: BlockStyle.Align.Start, justify, phishy)

    companion object {
        val None = Style()
    }
}

/** One anchor; identity groups its pieces into a single [Inline.Link]. */
private class LinkRef(val target: LinkTarget)

/** [imageLink] is set only inside an anchor whose single child is the image (the RN rule). */
private class Ctx(val marks: Set<Mark>, val link: LinkRef?, val imageLink: LinkRef?, val style: Style, val heading: Int?) {
    fun with(
        marks: Set<Mark> = this.marks,
        link: LinkRef? = this.link,
        imageLink: LinkRef? = this.imageLink,
        style: Style = this.style,
        heading: Int? = this.heading,
    ) = Ctx(marks, link, imageLink, style, heading)
}

private sealed interface Piece {
    val link: LinkRef?

    class Txt(val text: String, val marks: Set<Mark>, override val link: LinkRef?) : Piece
    class Chip(val inline: Inline, override val link: LinkRef?) : Piece
    class Break(override val link: LinkRef?) : Piece
}

private class Walker(private val options: BuildOptions) {
    private var out: MutableList<Block> = ArrayList()
    private val pieces = ArrayList<Piece>()

    /** Ids met since the last block went out; the next block (or the end) gets a [Block.Anchor] before it. */
    private val pendingAnchors = ArrayList<String>()
    private var depth = 0
    private val fallbackThumb: String? by lazy { SafeTarget.proxify(options.metadataImage, options.proxyBase) }

    fun top(body: Element): List<Block> {
        val ctx = Ctx(emptySet(), null, null, Style.None, null)
        children(body, ctx)
        flush(ctx)
        return flattenContainers(out)
    }

    // ---- DOM walk ----

    private fun children(el: Node, ctx: Ctx) {
        val list = el.childNodes.toList()
        if (!options.waves) {
            for (c in list) node(c, ctx)
            return
        }
        // Waves: a run of image-only paragraphs separated by whitespace only, at any depth (the RN app's regex).
        var i = 0
        while (i < list.size) {
            if (isImageOnlyParagraph(list[i])) {
                val run = ArrayList<Element>()
                var j = i
                var end = i
                while (j < list.size) {
                    val k = list[j]
                    if (isImageOnlyParagraph(k)) {
                        run += k as Element
                        end = j
                    } else if (!(k is Text && isBlankText(k.data))) {
                        break
                    }
                    j++
                }
                if (run.size >= 2) {
                    flush(ctx)
                    out += Block.ImageGrid(run.mapNotNull { p -> image(p.childNodes.first { it is Element } as Element, null) })
                    i = end + 1
                    continue
                }
            }
            node(list[i], ctx)
            i++
        }
    }

    private fun node(n: Node, ctx: Ctx) {
        when (n) {
            is Element -> element(n, ctx)
            is Text -> pieces += Piece.Txt(n.data, ctx.marks, ctx.link)
            is CDATASection -> pieces += Piece.Txt(n.data, ctx.marks, ctx.link)
            else -> {}
        }
    }

    private fun element(el: Element, ctx: Ctx) {
        if (depth >= BlockBuilder.MAX_DEPTH) {
            deep(el, ctx)
            return
        }
        depth++
        try {
            val tag = BlockBuilder.lower(el.localName)
            anchorIds(el, tag, ctx)
            dispatch(el, tag, ctx)
        } finally {
            depth--
        }
    }

    private fun dispatch(el: Element, tag: String, ctx: Ctx) {
        val mark = BlockBuilder.MARKS[tag]
        when {
            mark != null -> children(el, ctx.with(marks = ctx.marks + mark))
            tag == "br" -> pieces += Piece.Break(ctx.link)
            tag == "img" -> {
                flush(ctx)
                image(el, ctx.imageLink?.target)?.let { out += it }
            }
            tag == "a" -> anchor(el, ctx)
            tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' -> section(el, ctx, ownStyle(el), heading = tag[1] - '0')
            tag == "center" -> section(el, ctx, ownStyle(el).merge(Style(align = BlockStyle.Align.Center)), container = true)
            tag == "div" -> section(el, ctx, ownStyle(el), container = true)
            tag in BlockBuilder.PLAIN_BLOCKS -> section(el, ctx, ownStyle(el))
            tag == "blockquote" -> {
                flush(ctx)
                val inner = ctx.with(heading = null)
                val blocks = collect(inner) { children(el, inner) }
                if (blocks.isNotEmpty()) out += Block.Quote(blocks)
            }
            tag == "ul" || tag == "ol" -> list(el, ctx, ordered = tag == "ol")
            tag == "li" -> {
                // An item outside any list still shows a bullet.
                flush(ctx)
                val inner = ctx.with(heading = null)
                out += Block.ListBlock(false, 1, listOf(collect(inner) { children(el, inner) }))
            }
            tag == "pre" -> {
                flush(ctx)
                var text = el.textContent.orEmpty()
                if (text.startsWith("\n")) text = text.substring(1)
                text = text.trimEnd('\n')
                if (text.isNotBlank()) out += Block.Code(text)
            }
            tag == "hr" -> {
                flush(ctx)
                out += Block.Rule
            }
            tag == "table" -> table(el, ctx)
            tag == "details" -> details(el, ctx)
            tag == "iframe" -> {
                flush(ctx)
                iframe(el)?.let { out += it }
            }
            tag == "video" -> {
                // The RN app does not render <video>; only an allowlisted player URL becomes a video.
                // Fallback text inside is shown either way.
                flush(ctx)
                videoTag(el)?.let { out += it }
                children(el, ctx)
                flush(ctx)
            }
            tag in BlockBuilder.SKIPPED -> {}
            else -> children(el, ctx)
        }
    }

    /**
     * An element's `id` and `data-id` (what render-helper's sanitizer keeps: `id` on a, span, div, p and headings when
     * it is a valid name, `data-id` on a; web's anchor handler reads `data-id`). A block element starts a block of its
     * own, so the text before it goes out first.
     */
    private fun anchorIds(el: Element, tag: String, ctx: Ctx) {
        val id = el.getAttribute("id")?.trim().orEmpty()
        val dataId = el.getAttribute("data-id")?.trim().orEmpty()
        if (id.isEmpty() && dataId.isEmpty()) return
        if (tag in BlockBuilder.BLOCK_TAGS) flush(ctx)
        for (v in listOf(id, dataId)) if (v.isNotEmpty() && v.length <= BlockBuilder.MAX_ANCHOR_ID && v !in pendingAnchors) pendingAnchors += v
    }

    /** A block-level element: ends the current paragraph, runs its children, ends the last one. */
    private fun section(el: Element, ctx: Ctx, own: Style, heading: Int? = null, container: Boolean = false) {
        flush(ctx)
        val merged = ctx.style.merge(own)
        val inner = ctx.with(style = merged, heading = heading ?: ctx.heading)
        if (own.isEmpty) {
            children(el, inner)
            flush(inner)
            return
        }
        val blocks = collect(inner) { children(el, inner) }
        if (blocks.isEmpty()) return
        // Text blocks already carry the style; a container is only needed to place images and embeds.
        if (!container && blocks.all { it is Block.Paragraph || it is Block.Heading || it is Block.Anchor }) {
            out += blocks
        } else {
            out += Block.Container(blocks, merged.toBlockStyle())
        }
    }

    private fun ownStyle(el: Element): Style {
        var align: BlockStyle.Align? = null
        var justify = false
        var phishy = false
        for (c in BlockBuilder.classes(el)) when (c) {
            "text-center" -> align = BlockStyle.Align.Center
            "text-right" -> align = BlockStyle.Align.End
            "text-left" -> align = BlockStyle.Align.Left
            "text-justify" -> justify = true
            "phishy" -> phishy = true
        }
        when (el.getAttribute("data-align")?.trim()?.let { BlockBuilder.lower(it) }) {
            "left" -> align = BlockStyle.Align.Left
            "center" -> align = BlockStyle.Align.Center
            "right" -> align = BlockStyle.Align.End
            "justify" -> justify = true
        }
        return Style(align, justify, phishy)
    }

    private fun image(el: Element, link: LinkTarget?): Block.Image? {
        // only through the image proxy: render-helper keeps an author's class="no-replace" (its own marker for video
        // thumbnails) and URLs too long for the proxy unproxied, which would let a post fetch from any server with
        // the reader's IP. An image the proxy cannot serve is left out.
        val safe = SafeTarget.image(el.getAttribute("src")) ?: return null
        val src = SafeTarget.proxify(safe, options.proxyBase) ?: return null
        return Block.Image(src, el.getAttribute("alt")?.takeIf { it.isNotBlank() }, link)
    }

    private fun thumbnail(src: String?): String? = SafeTarget.proxiedImage(src, options.proxyBase) ?: fallbackThumb

    /**
     * An allowlisted player becomes a video. Any other http(s) iframe source becomes a link to it (the link
     * sheet warns before opening), so the post shows what is there instead of a hole.
     */
    private fun iframe(el: Element): Block? {
        val raw = el.getAttribute("src")
        val src = SafeTarget.embed(raw) ?: return embedLink(raw)
        val yt = BlockBuilder.YOUTUBE_EMBED.find(src)?.groupValues?.get(1)?.let { SafeTarget.youtubeId(it) }
        return Block.Video(src, fallbackThumb, yt)
    }

    private fun embedLink(src: String?): Block.Paragraph? {
        val target = SafeTarget.external(src) as? LinkTarget.External ?: return null
        return Block.Paragraph(listOf(Inline.Link(target, listOf(Inline.Text(target.url)))))
    }

    private fun videoTag(el: Element): Block.Video? {
        val src = listOf(el.getAttribute("src")) + el.childNodes.mapNotNull { c ->
            (c as? Element)?.takeIf { BlockBuilder.lower(it.localName) == "source" }?.getAttribute("src")
        }
        val url = src.firstNotNullOfOrNull { SafeTarget.embed(it) } ?: return null
        return Block.Video(url, thumbnail(el.getAttribute("poster")))
    }

    // ---- links ----

    private fun anchor(el: Element, ctx: Ctx) {
        if (BlockBuilder.isVideoAnchor(el)) {
            videoLink(el, ctx)
            return
        }
        val cls = BlockBuilder.classes(el)
        if ("markdown-author-link" in cls) {
            val author = SafeTarget.account(el.getAttribute("data-author"))
            if (author != null && chipText(el)?.equals("@$author", ignoreCase = true) == true) {
                pieces += Piece.Chip(Inline.Mention(author), ctx.link)
                return
            }
        }
        if ("markdown-tag-link" in cls) {
            val tag = SafeTarget.tagTarget(el.getAttribute("data-tag"), el.getAttribute("data-filter"))
            if (tag != null && chipText(el)?.equals("#${tag.tag}", ignoreCase = true) == true) {
                pieces += Piece.Chip(Inline.Tag(tag.tag, tag.filter), ctx.link)
                return
            }
        }
        BlockBuilder.inlineCard(el)?.let { post ->
            flush(ctx)
            out += Block.PostPreview(post.author, post.permlink, SafeTarget.httpUrl(el.getAttribute("data-href")))
            return
        }
        val target = BlockBuilder.anchorTarget(el)
        if (target == null) {
            children(el, ctx.with(imageLink = null))
            return
        }
        val ref = LinkRef(target)
        children(el, ctx.with(link = ref, imageLink = if (onlyImage(el)) ref else null))
    }

    /** The RN app links an image only when it is the anchor's single child; otherwise a tap opens the gallery. */
    private fun onlyImage(el: Element): Boolean {
        var img = 0
        for (c in el.childNodes) when {
            c is Element && BlockBuilder.lower(c.localName) == "img" -> img++
            c is Text && isBlankText(c.data) -> {}
            else -> return false
        }
        return img == 1
    }

    /** The anchor's text when it is plain text only (a chip candidate), else null. */
    private fun chipText(el: Element): String? {
        for (c in el.childNodes) if (c !is Text) return null
        return el.textContent?.trim()
    }

    /**
     * A video link is a block: the video (when its player URL or YouTube id is valid), then any label
     * text the anchor has (render-helper emits none), without a link.
     */
    private fun videoLink(el: Element, ctx: Ctx) {
        flush(ctx)
        videoOf(el)?.let { out += it }
        val inner = ctx.with(link = null, imageLink = null)
        for (c in el.childNodes.toList()) {
            if (isMedia(c)) continue
            node(c, inner)
        }
        flush(inner)
    }

    private fun isMedia(n: Node) = n is Element && BlockBuilder.lower(n.localName).let { it == "img" || it == "iframe" }

    private fun videoOf(el: Element): Block.Video? {
        val firstChildSrc = (el.childNodes.firstOrNull { it is Element } as? Element)?.getAttribute("src")
        val iframeSrc = el.getElementsByTagName("iframe").firstOrNull()?.getAttribute("src")
        val embed = listOf(el.getAttribute("data-embed-src"), el.getAttribute("data-video-href"), iframeSrc, firstChildSrc)
            .firstNotNullOfOrNull { SafeTarget.embed(it) }
        val youtube = SafeTarget.youtubeId(el.getAttribute("data-youtube"))
            ?: embed?.let { BlockBuilder.YOUTUBE_EMBED.find(it)?.groupValues?.get(1) }?.let { SafeTarget.youtubeId(it) }
        val url = embed ?: youtube?.let { "https://www.youtube.com/embed/$it" } ?: return null
        val thumbSrc = el.getElementsByTagName("img").firstOrNull { "video-thumbnail" in BlockBuilder.classes(it) }?.getAttribute("src")
        return Block.Video(url, thumbnail(thumbSrc), youtube, maxOf(0, BlockBuilder.parseIntJs(el.getAttribute("data-start-time"))))
    }

    // ---- past MAX_DEPTH ----

    private class Visit(val node: Node, val ctx: Ctx)
    private class Exit(val ctx: Ctx)

    /**
     * Iterative walk for very deep subtrees: no new marks or styles, but text, line breaks, block
     * boundaries, links, images, preview cards and embeds are kept.
     */
    private fun deep(root: Element, ctx: Ctx) {
        val stack = ArrayList<Any>()
        stack += Visit(root, ctx)
        while (stack.isNotEmpty()) {
            val top = stack.removeAt(stack.size - 1)
            if (top is Exit) {
                flush(top.ctx)
                continue
            }
            val n = (top as Visit).node
            val c = top.ctx
            when (n) {
                is Text -> pieces += Piece.Txt(n.data, c.marks, c.link)
                is CDATASection -> pieces += Piece.Txt(n.data, c.marks, c.link)
                is Element -> {
                    val tag = BlockBuilder.lower(n.localName)
                    val card = if (tag == "a") BlockBuilder.inlineCard(n) else null
                    when {
                        tag in BlockBuilder.SKIPPED -> {}
                        tag == "br" -> pieces += Piece.Break(c.link)
                        tag == "img" -> {
                            flush(c)
                            image(n, c.imageLink?.target)?.let { out += it }
                        }
                        tag == "iframe" -> {
                            flush(c)
                            iframe(n)?.let { out += it }
                        }
                        tag == "hr" -> {
                            flush(c)
                            out += Block.Rule
                        }
                        card != null -> {
                            flush(c)
                            out += Block.PostPreview(card.author, card.permlink, SafeTarget.httpUrl(n.getAttribute("data-href")))
                        }
                        tag == "a" && BlockBuilder.isVideoAnchor(n) -> {
                            flush(c)
                            videoOf(n)?.let { out += it }
                            val inner = c.with(link = null, imageLink = null)
                            stack += Exit(inner)
                            for (k in n.childNodes.indices.reversed()) {
                                val kid = n.childNodes[k]
                                if (!isMedia(kid)) stack += Visit(kid, inner)
                            }
                        }
                        else -> {
                            var inner = c
                            if (tag == "a") {
                                val ref = BlockBuilder.anchorTarget(n)?.let { LinkRef(it) }
                                inner = if (ref != null) c.with(link = ref, imageLink = if (onlyImage(n)) ref else null) else c.with(imageLink = null)
                            }
                            val block = tag in BlockBuilder.BLOCK_TAGS
                            if (block) {
                                flush(c)
                                stack += Exit(inner)
                            }
                            for (k in n.childNodes.indices.reversed()) stack += Visit(n.childNodes[k], inner)
                        }
                    }
                }
                else -> {}
            }
        }
    }

    // ---- structures ----

    private fun list(el: Element, ctx: Ctx, ordered: Boolean) {
        flush(ctx)
        val inner = ctx.with(heading = null)
        val items = ArrayList<List<Block>>()
        val stray = ArrayList<Node>()
        fun flushStray() {
            if (stray.isEmpty()) return
            val nodes = stray.toList()
            stray.clear()
            val blocks = collect(inner) { nodes.forEach { node(it, inner) } }
            if (blocks.isEmpty()) return
            // Content between items (a nested list written without its li) belongs to the item above.
            if (items.isEmpty()) items += blocks else items[items.size - 1] = items.last() + blocks
        }
        for (c in el.childNodes.toList()) {
            if (c is Element && BlockBuilder.lower(c.localName) == "li") {
                flushStray()
                items += collect(inner) { children(c, inner) }
            } else {
                stray += c
            }
        }
        flushStray()
        if (items.isEmpty()) return
        val start = if (ordered) el.getAttribute("start")?.trim()?.toIntOrNull() ?: 1 else 1
        out += Block.ListBlock(ordered, start, items)
    }

    private fun table(el: Element, ctx: Ctx) {
        flush(ctx)
        val cellCtx = Ctx(ctx.marks, ctx.link, ctx.imageLink, Style.None, null)
        var rows = ArrayList<Block.Table.Row>()
        // Content outside rows stays in document order: it ends the table so far, rows after it start a new one.
        fun endTable() {
            if (rows.isNotEmpty()) out += Block.Table(rows)
            rows = ArrayList()
        }
        fun strayNode(n: Node) {
            val blocks = collect(cellCtx) { node(n, cellCtx) }
            if (blocks.isEmpty()) return
            endTable()
            out += blocks
        }
        fun row(tr: Element) {
            val cells = ArrayList<Block.Table.Cell>()
            val stray = ArrayList<Node>()
            fun flushStray() {
                if (stray.isEmpty()) return
                val nodes = stray.toList()
                stray.clear()
                val blocks = collect(cellCtx) { nodes.forEach { node(it, cellCtx) } }
                if (blocks.isNotEmpty()) cells += Block.Table.Cell(false, blocks)
            }
            for (c in tr.childNodes.toList()) {
                val t = (c as? Element)?.let { BlockBuilder.lower(it.localName) }
                if (t == "td" || t == "th") {
                    flushStray()
                    cells += Block.Table.Cell(t == "th", collect(cellCtx) { children(c, cellCtx) })
                } else {
                    stray += c
                }
            }
            flushStray()
            if (cells.isNotEmpty()) rows += Block.Table.Row(cells)
        }
        for (c in el.childNodes.toList()) {
            when ((c as? Element)?.let { BlockBuilder.lower(it.localName) }) {
                "tr" -> row(c as Element)
                "thead", "tbody", "tfoot" -> for (g in c.childNodes.toList()) {
                    if (g is Element && BlockBuilder.lower(g.localName) == "tr") row(g) else strayNode(g)
                }
                else -> strayNode(c)
            }
        }
        endTable()
    }

    private fun details(el: Element, ctx: Ctx) {
        flush(ctx)
        val inner = ctx.with(heading = null)
        // A summary counts only as the first child; elsewhere it is ordinary content, so text keeps its order.
        val first = el.childNodes.firstOrNull { !(it is Text && isBlankText(it.data)) }
        val summaryEl = (first as? Element)?.takeIf { BlockBuilder.lower(it.localName) == "summary" }
        val summaryInlines = ArrayList<Inline>()
        val extra = ArrayList<Block>()
        if (summaryEl != null) {
            for (b in collect(inner) { children(summaryEl, inner) }) {
                val inl = when (b) {
                    is Block.Paragraph -> b.inlines
                    is Block.Heading -> b.inlines
                    else -> null
                }
                if (inl == null) {
                    extra += b
                } else {
                    if (summaryInlines.isNotEmpty()) summaryInlines += Inline.LineBreak
                    summaryInlines += inl
                }
            }
        }
        val blocks = collect(inner) { for (c in el.childNodes.toList()) if (c !== summaryEl) node(c, inner) }
        out += Block.Details(summaryInlines, extra + blocks)
    }

    // ---- top level ----

    /**
     * Top-level containers would make the lazy list compose a whole `<center>`-wrapped post at once.
     * Their text blocks already carry the style, so they move up; other blocks keep a one-block
     * container only when they need its alignment.
     */
    private fun flattenContainers(blocks: List<Block>): List<Block> {
        if (blocks.none { it is Block.Container }) return blocks
        val result = ArrayList<Block>(blocks.size)
        fun visit(b: Block, style: BlockStyle?) {
            when {
                b is Block.Container -> b.blocks.forEach { visit(it, b.style) }
                b is Block.Anchor -> result += b
                style == null || b is Block.Paragraph || b is Block.Heading || style.align == BlockStyle.Align.Start -> result += b
                else -> result += Block.Container(listOf(b), style)
            }
        }
        blocks.forEach { visit(it, null) }
        return result
    }

    // ---- paragraphs ----

    /** Runs [body] into a fresh block list. Callers flush their own pending text first. */
    private inline fun collect(ctx: Ctx, body: () -> Unit): List<Block> {
        val saved = out
        out = ArrayList()
        body()
        flush(ctx)
        val result = out
        out = saved
        return result
    }

    /** Turns the pending inline pieces into a paragraph (or heading), collapsing whitespace. */
    private fun flush(ctx: Ctx) {
        if (pendingAnchors.isNotEmpty()) {
            out += Block.Anchor(pendingAnchors.toList())
            pendingAnchors.clear()
        }
        if (pieces.isEmpty()) return
        val inlines = buildInlines(pieces)
        pieces.clear()
        if (inlines.isEmpty()) return
        val style = ctx.style.toBlockStyle()
        out += if (ctx.heading != null) Block.Heading(ctx.heading, inlines, style) else Block.Paragraph(inlines, style)
    }

    private class Seg(val inline: Inline, val link: LinkRef?)

    private fun buildInlines(src: List<Piece>): List<Inline> {
        val segs = ArrayList<Seg>()
        val text = StringBuilder()
        var textMarks: Set<Mark> = emptySet()
        var textLink: LinkRef? = null
        var lineStart = true
        var pending: Piece.Txt? = null // owner of a collapsed space not yet emitted
        var hasContent = false

        fun endText() {
            if (text.isNotEmpty()) {
                segs += Seg(Inline.Text(text.toString(), textMarks), textLink)
                text.clear()
            }
        }
        fun appendText(s: String, marks: Set<Mark>, link: LinkRef?) {
            if (text.isNotEmpty() && (link !== textLink || (marks !== textMarks && marks != textMarks))) endText()
            textMarks = marks
            textLink = link
            text.append(s)
        }
        fun emitPendingSpace() {
            val p = pending ?: return
            pending = null
            if (!lineStart) appendText(" ", p.marks, p.link)
        }

        for (piece in src) when (piece) {
            is Piece.Txt -> {
                val s = piece.text
                var i = 0
                while (i < s.length) {
                    val c = s[i]
                    if (BlockBuilder.isCollapsible(c)) {
                        if (pending == null) pending = piece
                        i++
                        continue
                    }
                    emitPendingSpace()
                    var j = i
                    while (j < s.length && !BlockBuilder.isCollapsible(s[j])) j++
                    appendText(s.substring(i, j), piece.marks, piece.link)
                    lineStart = false
                    hasContent = true
                    i = j
                }
            }
            is Piece.Chip -> {
                emitPendingSpace()
                endText()
                segs += Seg(piece.inline, piece.link)
                lineStart = false
                hasContent = true
            }
            is Piece.Break -> {
                pending = null
                endText()
                segs += Seg(Inline.LineBreak, piece.link)
                lineStart = true
            }
        }
        endText()
        if (!hasContent) return emptyList()
        while (segs.isNotEmpty() && segs.last().inline == Inline.LineBreak) segs.removeAt(segs.size - 1)

        // Group consecutive segments of the same anchor into one link.
        val result = ArrayList<Inline>()
        var i = 0
        while (i < segs.size) {
            val link = segs[i].link
            if (link == null) {
                result += segs[i].inline
                i++
                continue
            }
            var j = i
            while (j < segs.size && segs[j].link === link) j++
            result += Inline.Link(link.target, segs.subList(i, j).map { it.inline })
            i = j
        }
        return result
    }

    // ---- waves ----

    private fun isImageOnlyParagraph(n: Node): Boolean {
        if (n !is Element || BlockBuilder.lower(n.localName) != "p") return false
        var img = 0
        for (c in n.childNodes) {
            when {
                c is Element && BlockBuilder.lower(c.localName) == "img" && SafeTarget.image(c.getAttribute("src")) != null -> img++
                c is Text && isBlankText(c.data) -> {}
                else -> return false
            }
        }
        return img == 1
    }

    private fun isBlankText(s: String) = s.all { BlockBuilder.isCollapsible(it) }
}
