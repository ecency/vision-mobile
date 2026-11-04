package com.ecency.app.render

import androidx.compose.ui.semantics.Role

import androidx.compose.ui.text.style.TextDirection
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ecency.core.render.blocks.Inline
import com.ecency.core.render.blocks.LinkTarget
import com.ecency.core.render.blocks.plainText

@Immutable
internal class InlineText(val text: AnnotatedString, val inlineContent: Map<String, InlineTextContent>)

private val ChipText = TextStyle(fontSize = 12.sp, textDirection = TextDirection.Content)
private const val CHIP_HEIGHT_DP = 18
private const val AVATAR_DP = 14

// RN tagWrapper/tagText: label margins 6 on both sides, 4 on the left after an avatar.
private const val AVATAR_INSET_DP = 2
private const val LABEL_START_AFTER_AVATAR_DP = 4
private const val LABEL_PADDING_DP = 6

private fun chipWidthDp(labelDp: Float, avatar: Boolean): Float =
    labelDp + LABEL_PADDING_DP + if (avatar) AVATAR_INSET_DP + AVATAR_DP + LABEL_START_AFTER_AVATAR_DP else LABEL_PADDING_DP

/**
 * Builds one AnnotatedString per paragraph: marks become span styles, links become clickable
 * annotations, and `@user` / `#tag` become inline chips sized from the measured label.
 */
@Composable
internal fun rememberInlineText(inlines: List<Inline>, actions: PostActions): InlineText {
    val theme = LocalPostTheme.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(inlines, theme, actions) {
        val chips = LinkedHashMap<String, InlineTextContent>()
        val linkStyle = TextLinkStyles(SpanStyle(color = theme.link))

        // A long-press on a chip copies the paragraph, as anywhere else in it, instead of navigating.
        val longPress = { actions.onParagraphLongPress(inlines.plainText()) }

        fun chip(id: String, label: String, avatar: String?, target: LinkTarget) {
            val labelSize = measurer.measure(label, ChipText).size
            // +1dp so rounding never ellipsizes the label inside the placeholder.
            val widthDp = chipWidthDp(with(density) { labelSize.width.toDp() }.value, avatar != null) + 1
            // RN's chip is 18 high; with a large font scale the label needs more, so grow with it.
            val heightDp = maxOf(CHIP_HEIGHT_DP.toFloat(), with(density) { labelSize.height.toDp() }.value + 4)
            // Placeholder sizes are in sp; divide out the font scale so the chip box stays in dp.
            val heightSp = (heightDp / density.fontScale).sp
            chips[id] = InlineTextContent(Placeholder((widthDp / density.fontScale).sp, heightSp, PlaceholderVerticalAlign.TextCenter)) {
                Chip(label, avatar, onLongClick = longPress) { actions.onLink(target) }
            }
        }

        val text = buildAnnotatedString {
            fun emit(list: List<Inline>) {
                for (inline in list) when (inline) {
                    is Inline.Text -> withStyle(inline.marks.toSpanStyle(theme)) { append(inline.text) }
                    is Inline.Link -> withLink(LinkAnnotation.Clickable(inline.target.toString(), linkStyle) { actions.onLink(inline.target) }) {
                        emit(inline.children)
                    }
                    is Inline.Mention -> {
                        val id = "m${chips.size}"
                        chip(id, "@${inline.author}", actions.avatarUrl(inline.author), LinkTarget.Author(inline.author))
                        append(' ')
                        appendInlineContent(id, "@${inline.author}")
                        append(' ')
                    }
                    is Inline.Tag -> {
                        val id = "t${chips.size}"
                        chip(id, "#${inline.tag}", null, LinkTarget.Tag(inline.tag, inline.filter))
                        append(' ')
                        appendInlineContent(id, "#${inline.tag}")
                        append(' ')
                    }
                    Inline.LineBreak -> append('\n')
                }
            }
            emit(inlines)
        }
        InlineText(text, chips)
    }
}

private fun Set<Inline.Mark>.toSpanStyle(theme: PostTheme): SpanStyle {
    if (isEmpty()) return SpanStyle()
    val decorations = buildList {
        if (Inline.Mark.Strike in this@toSpanStyle) add(TextDecoration.LineThrough)
        if (Inline.Mark.Underline in this@toSpanStyle) add(TextDecoration.Underline)
    }
    return SpanStyle(
        fontWeight = if (Inline.Mark.Bold in this) FontWeight.Bold else null,
        fontStyle = if (Inline.Mark.Italic in this) FontStyle.Italic else null,
        fontFamily = if (Inline.Mark.Code in this) FontFamily.Monospace else null,
        background = when {
            Inline.Mark.Code in this -> theme.muted.copy(alpha = 0.5f)
            Inline.Mark.Highlight in this -> theme.link.copy(alpha = 0.2f)
            else -> androidx.compose.ui.graphics.Color.Unspecified
        },
        baselineShift = when {
            Inline.Mark.Sup in this -> BaselineShift.Superscript
            Inline.Mark.Sub in this -> BaselineShift.Subscript
            else -> null
        },
        fontSize = if (Inline.Mark.Sup in this || Inline.Mark.Sub in this) 12.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
    )
}

@Composable
private fun Chip(label: String, avatar: String?, onLongClick: () -> Unit, onClick: () -> Unit) {
    val theme = LocalPostTheme.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxSize()
            .clip(shape)
            .border(Dp.Hairline, theme.chipBorder, shape)
            .combinedClickable(onLongClick = onLongClick, onClick = onClick, role = Role.Button)
            .testTag("chip:$label"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (avatar != null) {
            // The placeholder disc shows at once, so the chip never looks empty while the avatar loads.
            AsyncImage(
                model = avatar,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .padding(start = AVATAR_INSET_DP.dp)
                    .size(AVATAR_DP.dp)
                    .clip(CircleShape)
                    .background(theme.placeholder)
                    .testTag("chip-avatar:$avatar"),
            )
        }
        BasicText(
            label,
            Modifier.padding(start = if (avatar != null) LABEL_START_AFTER_AVATAR_DP.dp else LABEL_PADDING_DP.dp, end = LABEL_PADDING_DP.dp),
            style = ChipText.copy(color = theme.link),
            maxLines = 1,
        )
    }
}
