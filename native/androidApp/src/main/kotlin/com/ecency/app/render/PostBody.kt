package com.ecency.app.render

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

import androidx.compose.ui.semantics.Role

import androidx.compose.ui.platform.LocalDensity
import com.ecency.app.ui.Glyph
import com.ecency.core.app.AppIcon
import android.graphics.drawable.Animatable
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import coil3.asDrawable
import com.ecency.core.render.blocks.Inline
import com.ecency.core.render.blocks.imageUrls
import com.ecency.core.render.blocks.isHiveAccountName
import com.ecency.core.render.blocks.plainText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.ecency.core.render.blocks.isRtlContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ecency.core.render.blocks.Block
import com.ecency.core.render.blocks.BlockStyle
import com.ecency.core.render.blocks.LinkTarget
import com.ecency.core.app.UiCopy

/** What the host screen does on taps; mirrors the RN renderer's handler props. */
class PostActions(
    val onLink: (LinkTarget) -> Unit = {},
    /** Image tap: the tapped URL and every body image in document order, for the gallery. */
    val onImage: (url: String, all: List<String>) -> Unit = { _, _ -> },
    /** Paragraph long-press with its plain text, for the copy sheet. */
    val onParagraphLongPress: (plainText: String) -> Unit = {},
    /** A tap on plain paragraph text (not a link): a wave in a list opens its thread. Null leaves taps alone. */
    val onParagraphTap: (() -> Unit)? = null,
    /** Avatar for a mention chip; null draws the chip without one. Invalid account names get none. */
    val avatarUrl: (username: String) -> String? = { if (isHiveAccountName(it)) "https://images.ecency.com/u/$it/avatar/128" else null },
    /**
     * The "Show images" setting. When false each image is a same-size placeholder and its bytes are
     * never requested until the user taps it; a revealed image stays revealed for the session.
     */
    val showImages: Boolean = true,
)

/**
 * These actions with in-post jumps (#2403) taken first: [onAnchor] gets every [LinkTarget.Anchor] and returns
 * whether it scrolled; one it did not take (another post's link) goes on to [PostActions.onLink].
 */
fun PostActions.withAnchors(onAnchor: (LinkTarget.Anchor) -> Boolean): PostActions = PostActions(
    onLink = { t -> if (t !is LinkTarget.Anchor || !onAnchor(t)) onLink(t) },
    onImage = onImage, onParagraphLongPress = onParagraphLongPress, onParagraphTap = onParagraphTap, avatarUrl = avatarUrl, showImages = showImages,
)

/** The lazy item key of top-level block [index] in [postBody] (a jump finds the body's rows by it). */
fun postBlockKey(index: Int): String = "block:$index"

/** The gallery pages through the images the reader can see: hidden, unrevealed ones are left out. */
internal fun PostActions.openGallery(url: String, images: Lazy<List<String>>) =
    onImage(url, com.ecency.core.app.ContentImages.viewable(images.value, showImages, RevealedImages::isRevealed))

/** Images revealed by a tap while "Show images" is off. Session scoped by URL, like RN's revealedImages. */
object RevealedImages {
    private val urls = mutableStateSetOf<String>()

    fun isRevealed(url: String): Boolean = url in urls

    fun reveal(url: String) {
        urls += url
    }

    fun clear() = urls.clear()
}

/**
 * Emits one lazy item per top-level block, so a post screen can put header, body and comments in a
 * single LazyColumn and only compose what is on screen.
 */
fun LazyListScope.postBody(blocks: List<Block>, contentWidth: Dp, actions: PostActions) {
    // Walked only when an image is tapped, not on every recomposition of the list.
    val images = lazy(LazyThreadSafetyMode.NONE) { blocks.imageUrls() }
    val direction = bodyDirection(blocks)
    items(blocks.size, key = ::postBlockKey, contentType = { blocks[it]::class }) { index ->
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            BlockView(blocks[index], contentWidth, actions, images)
        }
    }
}

/** Non-lazy variant for short bodies such as comments. */
@Composable
fun PostBody(blocks: List<Block>, contentWidth: Dp, actions: PostActions, modifier: Modifier = Modifier) {
    val images = remember(blocks) { lazy(LazyThreadSafetyMode.NONE) { blocks.imageUrls() } }
    val direction = remember(blocks) { bodyDirection(blocks) }
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
        Column(modifier.width(contentWidth)) {
            blocks.forEach { BlockView(it, contentWidth, actions, images) }
        }
    }
}

/**
 * A body's own direction, from its first strong letter ([isRtlContent]), not the app's: an English post stays left
 * to right in an Arabic UI (README "Localization", RTL).
 */
internal fun bodyDirection(blocks: List<Block>): LayoutDirection = if (blocks.isRtlContent()) LayoutDirection.Rtl else LayoutDirection.Ltr

@Composable
internal fun BlockView(block: Block, maxWidth: Dp, actions: PostActions, images: Lazy<List<String>>) {
    val theme = LocalPostTheme.current
    when (block) {
        is Block.Paragraph -> ParagraphView(block.inlines, block.style, actions, theme.bodySize, theme.bodyWeight, theme.paragraphSpacing)
        is Block.Heading -> {
            val size = theme.headingSizes[block.level - 1]
            val margin = (size.value * theme.headingMarginsEm[block.level - 1]).dp
            ParagraphView(block.inlines, block.style, actions, size, FontWeight.Bold, margin)
        }
        is Block.Image -> ImageView(block, maxWidth, actions, images)
        is Block.ImageGrid -> ImageGridView(block, maxWidth, actions, images)
        // RN: 5 margin, 5 border in $darkIconColor, 5 padding; the user agent adds 1em top and bottom and 30 on the right.
        is Block.Quote -> Column(
            Modifier
                .padding(start = 5.dp, top = 16.dp, bottom = 16.dp, end = 30.dp)
                .drawBehind {
                    // the bar on the start side: the left of a left to right body, the right of a right to left one
                    val bar = 5.dp.toPx()
                    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - bar else 0f
                    drawRect(theme.muted, topLeft = Offset(x, 0f), size = size.copy(width = bar))
                }
                .padding(start = 10.dp)
                .testTag("quote"),
        ) {
            block.blocks.forEach { BlockView(it, maxWidth - 45.dp, actions, images) }
        }
        is Block.ListBlock -> ListView(block, maxWidth, actions, images, depth = 0, trimTop = false, trimBottom = false)
        // code reads left to right in any body
        is Block.Code -> CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { BasicText(
            block.text,
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .background(theme.muted.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
            style = TextStyle(color = theme.text, fontFamily = FontFamily.Monospace, fontSize = 14.sp, textDirection = TextDirection.Ltr),
        ) }
        Block.Rule -> Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .height(1.dp)
                .background(theme.muted),
        )
        is Block.Table -> TableView(block, maxWidth, actions, images)
        // With "Show images" off the thumbnail is not fetched; the play button stays, as in RN.
        is Block.Video -> VideoThumb(
            block.thumbnail.takeIf { actions.showImages },
            maxWidth,
            threeSpeak = THREE_SPEAK.containsMatchIn(block.embedUrl),
        ) {
            val youtubeId = block.youtubeId
            actions.onLink(if (youtubeId != null) LinkTarget.Youtube(youtubeId, block.startSeconds) else LinkTarget.Video(block.embedUrl))
        }
        is Block.PostPreview -> PostPreviewCard(block, actions)
        is Block.Container -> Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = block.style.horizontal(),
        ) {
            block.blocks.forEach { BlockView(it.withStyle(block.style), maxWidth, actions, images) }
        }
        is Block.Details -> DetailsView(block, maxWidth, actions, images)
        is Block.SpeakAudio -> SpeakAudioPlayer(block, maxWidth)
        // a jump target (#2403): nothing to draw
        is Block.Anchor -> Unit
    }
}

@Composable
private fun ParagraphView(
    inlines: List<Inline>,
    style: BlockStyle,
    actions: PostActions,
    fontSize: TextUnit,
    weight: FontWeight,
    spacing: Dp,
) {
    val theme = LocalPostTheme.current
    val built = rememberInlineText(inlines, actions)
    val color = if (style.phishy) theme.danger else theme.text
    BasicText(
        built.text,
        Modifier
            .fillMaxWidth()
            .padding(vertical = spacing)
            .pointerInput(inlines, actions) {
                detectTapGestures(
                    onLongPress = { actions.onParagraphLongPress(inlines.plainText()) },
                    onTap = actions.onParagraphTap?.let { tap -> { _ -> tap() } },
                )
            },
        style = TextStyle(
            color = color,
            fontSize = fontSize,
            fontWeight = weight,
            lineHeight = fontSize * theme.lineHeight,
            textAlign = paragraphAlign(style, hasChips = built.inlineContent.isNotEmpty()),
            textDirection = TextDirection.Content,
        ),
        inlineContent = built.inlineContent,
    )
}

@Composable
private fun ImageView(block: Block.Image, maxWidth: Dp, actions: PostActions, images: Lazy<List<String>>) {
    val theme = LocalPostTheme.current
    val context = LocalContext.current
    // Until the bitmap is known: full width at 16:9, as the RN app. After load: natural size, never
    // upscaled. Like RN's AutoHeightImage (and iOS), source pixels count as dp, not as device pixels.
    var natural by remember(block.src) { mutableStateOf<Pair<Float, Float>?>(null) }
    var animated by remember(block.src) { mutableStateOf(false) }
    val size = natural
    val width = if (size == null) maxWidth else minOf(maxWidth, size.first.dp)
    val ratio = if (size == null || size.second == 0f) 16f / 9f else size.first / size.second
    if (!actions.showImages && !RevealedImages.isRevealed(block.src)) {
        // Return before AsyncImage exists, so nothing is requested until the user asks.
        HiddenImage(width, width / ratio) { RevealedImages.reveal(block.src) }
        return
    }
    Box(
        Modifier
            .padding(vertical = 4.dp)
            .width(width)
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(theme.imageRadius))
            .background(if (size == null) theme.placeholder else Color.Transparent)
            .clickable(role = Role.Button) {
                val link = block.link
                if (link != null) actions.onLink(link) else actions.openGallery(block.src, images)
            }
            .testTag("image"),
    ) {
        AsyncImage(
            model = block.src,
            contentDescription = block.alt,
            contentScale = ContentScale.Crop,
            onSuccess = { state ->
                // Unspecified for drawables without a size (vectors, colors); reading it would throw.
                val s = state.painter.intrinsicSize
                if (s.isSpecified && s.width > 0 && s.height > 0) natural = s.width to s.height
                animated = state.result.image.asDrawable(context.resources) is Animatable
            },
            modifier = Modifier.matchParentSize(),
        )
        if (animated) GifBadge(Modifier.align(Alignment.BottomStart))
    }
}

/** RN AutoHeightImage's badge on animated images. */
@Composable
private fun GifBadge(modifier: Modifier) {
    BasicText(
        "GIF",
        modifier
            .padding(6.dp)
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
            .padding(horizontal = 3.dp, vertical = 1.dp)
            .testTag("gif"),
        style = TextStyle(color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, textDirection = TextDirection.Content),
    )
}

/** RN HiddenImagePlaceholder: same box as the image, dashed outline, icon and "Tap to load image". */
@Composable
internal fun HiddenImage(width: Dp, height: Dp, compact: Boolean = height < 72.dp, onReveal: () -> Unit) {
    val theme = LocalPostTheme.current
    Column(
        Modifier
            .padding(vertical = 4.dp)
            .size(width, height)
            .clip(RoundedCornerShape(theme.imageRadius))
            .background(theme.placeholder)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    theme.hiddenBorder,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(theme.imageRadius.toPx()),
                    style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * stroke, 3 * stroke))),
                )
            }
            .clickable(onClick = onReveal, role = Role.Button)
            .testTag("image-hidden"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ImageGlyph(if (compact) 20.dp else 28.dp, theme.chipBorder)
        if (!compact) {
            BasicText(
                UiCopy.TAP_TO_LOAD_IMAGE,
                Modifier.padding(top = 6.dp, start = 8.dp, end = 8.dp),
                style = TextStyle(color = theme.chipBorder, fontSize = 12.sp, textAlign = TextAlign.Center, textDirection = TextDirection.Content),
            )
        }
    }
}

/** Outline picture glyph (frame, sun, hill), drawn so the renderer needs no icon font. */
@Composable
private fun ImageGlyph(size: Dp, color: Color) {
    Box(
        Modifier.size(size).drawBehind {
            val s = this.size.width
            val stroke = s * 0.08f
            drawRoundRect(color, Offset(s * 0.1f, s * 0.15f), Size(s * 0.8f, s * 0.7f), CornerRadius(s * 0.1f), style = Stroke(stroke))
            drawCircle(color, s * 0.08f, Offset(s * 0.36f, s * 0.38f))
            val hill = androidx.compose.ui.graphics.Path().apply {
                moveTo(s * 0.18f, s * 0.76f)
                lineTo(s * 0.42f, s * 0.52f)
                lineTo(s * 0.58f, s * 0.66f)
                lineTo(s * 0.68f, s * 0.56f)
                lineTo(s * 0.82f, s * 0.76f)
            }
            drawPath(hill, color, style = Stroke(stroke))
        },
    )
}

@Composable
private fun ImageGridView(block: Block.ImageGrid, maxWidth: Dp, actions: PostActions, images: Lazy<List<String>>) {
    val tile = (maxWidth - 2.dp) / 2
    Column(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(12.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        block.images.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEach { img ->
                    if (!actions.showImages && !RevealedImages.isRevealed(img.src)) {
                        HiddenImage(tile, tile * 3 / 4) { RevealedImages.reveal(img.src) }
                        return@forEach
                    }
                    AsyncImage(
                        model = img.src,
                        contentDescription = img.alt,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(tile)
                            .aspectRatio(4f / 3f)
                            .background(LocalPostTheme.current.placeholder)
                            .clickable(role = Role.Button) { actions.openGallery(img.src, images) },
                    )
                }
            }
        }
    }
}

/**
 * RN lists: 1em top and bottom margin (none at the edge of a parent item), a 30 wide marker box,
 * items 12 apart and no paragraph margins inside items. Bullets go disc, circle, square by depth.
 */
@Composable
private fun ListView(
    block: Block.ListBlock,
    maxWidth: Dp,
    actions: PostActions,
    images: Lazy<List<String>>,
    depth: Int,
    trimTop: Boolean,
    trimBottom: Boolean,
) {
    val theme = LocalPostTheme.current
    val margin = (theme.bodySize.value).dp
    val bullet = BULLETS[minOf(depth, BULLETS.lastIndex)]
    Column(Modifier.padding(top = if (trimTop) 0.dp else margin, bottom = if (trimBottom) 0.dp else margin).testTag("list")) {
        block.items.forEachIndexed { i, item ->
            Row(Modifier.padding(bottom = theme.listItemSpacing)) {
                BasicText(
                    if (block.ordered) "${block.start + i}." else bullet,
                    Modifier.widthIn(min = MARKER_BOX).padding(end = 6.dp),
                    style = TextStyle(
                        color = theme.text,
                        fontSize = theme.bodySize,
                        lineHeight = theme.bodySize * theme.lineHeight,
                        textAlign = TextAlign.End,
                        textDirection = TextDirection.Content,
                    ),
                )
                Column {
                    item.forEachIndexed { j, child ->
                        // Paragraphs inside list items have no vertical margin, as in the RN app.
                        NoParagraphSpacing {
                            if (child is Block.ListBlock) {
                                ListView(child, maxWidth - MARKER_BOX, actions, images, depth + 1, trimTop = j == 0, trimBottom = j == item.lastIndex)
                            } else {
                                BlockView(child, maxWidth - MARKER_BOX, actions, images)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val MARKER_BOX = 30.dp
private val BULLETS = listOf("\u2022", "\u25E6", "\u25AA")

@Composable
private fun NoParagraphSpacing(content: @Composable () -> Unit) {
    val theme = LocalPostTheme.current
    CompositionLocalProvider(LocalPostTheme provides theme.copy(paragraphSpacing = 0.dp), content = content)
}

@Composable
private fun TableView(block: Block.Table, maxWidth: Dp, actions: PostActions, images: Lazy<List<String>>) {
    val theme = LocalPostTheme.current
    val columns = block.maxHeaderColumns
    val minCol = maxWidth / 3 - 12.dp
    val scroll = columns > 3
    val tableWidth = if (scroll) minCol * columns else maxWidth
    Box(Modifier.padding(vertical = 6.dp).then(if (scroll) Modifier.horizontalScroll(rememberScrollState()).testTag("table-scroll") else Modifier)) {
        Column(Modifier.width(tableWidth)) {
            block.rows.forEach { row ->
                Row(Modifier.height(IntrinsicSize.Min)) {
                    row.cells.forEach { cell ->
                        val cellWidth = tableWidth / maxOf(1, row.cells.size)
                        Column(
                            Modifier
                                .weight(1f)
                                .widthIn(min = if (scroll) minCol else 0.dp)
                                .fillMaxHeight()
                                .background(if (cell.header) theme.muted else theme.tableRow)
                                .border(0.5.dp, theme.tableBorder)
                                .padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            // RN th: bold 14 on $darkIconColor.
                            val provided = if (cell.header) {
                                theme.copy(bodySize = 14.sp, bodyWeight = FontWeight.Bold, paragraphSpacing = 0.dp)
                            } else {
                                theme.copy(paragraphSpacing = 0.dp)
                            }
                            CompositionLocalProvider(LocalPostTheme provides provided) {
                                cell.blocks.forEach { BlockView(it.centered(), cellWidth - 20.dp, actions, images) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun VideoThumb(thumbnail: String?, maxWidth: Dp, threeSpeak: Boolean, onClick: () -> Unit) {
    val theme = LocalPostTheme.current
    Box(
        Modifier
            .padding(vertical = 6.dp)
            .width(maxWidth)
            .aspectRatio(if (threeSpeak) 4f / 3f else 16f / 9f)
            .background(if (threeSpeak) Color.Transparent else theme.muted)
            .clickable(onClick = onClick, role = Role.Button)
            .semantics { contentDescription = com.ecency.core.app.UiCopy.VIDEO }
            .testTag("video"),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null) {
            AsyncImage(
                model = thumbnail,
                contentDescription = null,
                contentScale = if (threeSpeak) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        Box(Modifier.size(60.dp).clip(CircleShape).background(theme.text), contentAlignment = Alignment.Center) {
            // Play triangle drawn directly, so the body renderer needs no icon font.
            Box(
                Modifier.size(24.dp).drawBehind {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width * 0.2f, 0f)
                        lineTo(size.width, size.height / 2)
                        lineTo(size.width * 0.2f, size.height)
                        close()
                    }
                    drawPath(path, if (theme == PostTheme.Dark) Color.Black else Color.White)
                },
            )
        }
    }
}

@Composable
private fun PostPreviewCard(block: Block.PostPreview, actions: PostActions) {
    val theme = LocalPostTheme.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .border(1.dp, theme.chipBorder, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button) { actions.onLink(LinkTarget.Post(block.author, block.permlink)) }
            .padding(12.dp),
    ) {
        BasicText("@${block.author}", style = TextStyle(color = theme.link, fontSize = 13.sp, fontWeight = FontWeight.Bold, textDirection = TextDirection.Content))
        BasicText(block.permlink, style = TextStyle(color = theme.text, fontSize = 14.sp, textDirection = TextDirection.Content), maxLines = 2)
    }
}

@Composable
private fun DetailsView(block: Block.Details, maxWidth: Dp, actions: PostActions, images: Lazy<List<String>>) {
    val theme = LocalPostTheme.current
    var open by rememberSaveable { mutableStateOf(false) }
    val summary = rememberInlineText(block.summary, actions)
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(Modifier.clickable(role = Role.Button) { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val size = with(LocalDensity.current) { theme.bodySize.toDp() }
            Glyph(if (open) AppIcon.EXPAND else AppIcon.CHEVRON_RIGHT, theme.text, size, Modifier.padding(end = 4.dp))
            BasicText(summary.text, style = TextStyle(color = theme.text, fontSize = theme.bodySize, fontWeight = FontWeight.Bold, textDirection = TextDirection.Content), inlineContent = summary.inlineContent)
        }
        if (open) block.blocks.forEach { BlockView(it, maxWidth, actions, images) }
    }
}

private val THREE_SPEAK = Regex("3speak\\.tv", RegexOption.IGNORE_CASE)

/**
 * Compose draws inline content at its unjustified offset, so a justified line with a chip overlaps
 * the chip and the next word. Such paragraphs fall back to start alignment, which is what RN Android
 * does for every `text-justify` paragraph anyway (only RN iOS justifies).
 */
internal fun paragraphAlign(style: BlockStyle, hasChips: Boolean): TextAlign =
    if (style.justify && hasChips) TextAlign.Start else style.textAlign()

// HTML's left and right are absolute (BlockStyle.Align); no alignment is the body's start
private fun BlockStyle.textAlign() = when {
    justify -> TextAlign.Justify
    align == BlockStyle.Align.Center -> TextAlign.Center
    align == BlockStyle.Align.End -> TextAlign.Right
    align == BlockStyle.Align.Left -> TextAlign.Left
    else -> TextAlign.Start
}

private fun BlockStyle.horizontal(): Alignment.Horizontal = when (align) {
    BlockStyle.Align.Center -> Alignment.CenterHorizontally
    BlockStyle.Align.End -> AbsoluteAlignment.Right
    BlockStyle.Align.Left -> AbsoluteAlignment.Left
    BlockStyle.Align.Start -> Alignment.Start
}

/** Containers pass their alignment down to text children that do not set their own. */
private fun Block.withStyle(style: BlockStyle): Block = when (this) {
    is Block.Paragraph -> if (this.style == BlockStyle.Default) copy(style = style) else this
    is Block.Heading -> if (this.style == BlockStyle.Default) copy(style = style) else this
    else -> this
}

/** Table cells center their content, header or not, as in the RN app. */
private fun Block.centered(): Block = withStyle(BlockStyle(align = BlockStyle.Align.Center))
