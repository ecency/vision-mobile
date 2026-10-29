package com.ecency.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ecency.core.app.MotionTokens
import com.ecency.core.app.ShellUiCopy

/**
 * The one skeleton (moving gradient) for loading states whose layout is known: feed cards, the post body, the
 * profile header, wallet rows, notifications and the chat list. Spinners stay for work of unknown size or that
 * blocks the screen.
 *
 * One [Skeleton] container runs one shimmer clock for all its bones; the gradient position is read in the draw
 * phase only, so a frame redraws without recomposing. The clock runs only while the container is on screen and at
 * full motion (reduce motion or animations off: a still placeholder). Lazy lists dispose off-screen skeleton rows,
 * which stops their clock too.
 */
@Composable
fun Skeleton(modifier: Modifier = Modifier, tag: String = "skeleton", content: @Composable ColumnScope.() -> Unit) {
    val motion = LocalMotion.current
    var onScreen by remember { mutableStateOf(true) }
    Column(
        modifier
            .onGloballyPositioned { onScreen = !it.boundsInWindow().isEmpty }
            .semantics { contentDescription = ShellUiCopy.LOADING }
            .testTag(tag),
    ) {
        if (motion.shimmers && onScreen) {
            val transition = rememberInfiniteTransition(label = "shimmer")
            val progress = transition.animateFloat(
                0f, 1f, infiniteRepeatable(tween(MotionTokens.SHIMMER_MS, easing = LinearEasing), RepeatMode.Restart), label = "shimmer",
            )
            CompositionLocalProvider(LocalShimmer provides progress) { content() }
        } else {
            CompositionLocalProvider(LocalShimmer provides null) { content() }
        }
    }
}

private val LocalShimmer = staticCompositionLocalOf<State<Float>?> { null }

/** Tells [onValue] whether a shimmer clock runs here (tests). */
@Composable
internal fun ShimmerProbe(onValue: (Boolean) -> Unit) {
    val running = LocalShimmer.current != null
    androidx.compose.runtime.SideEffect { onValue(running) }
}

/** One placeholder shape of a [Skeleton]. */
@Composable
fun Bone(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(4.dp)) {
    val colors = LocalEcencyColors.current
    val base = if (colors.isDark) colors.lightBackground.copy(alpha = 0.6f) else colors.lightBackground
    val highlight = if (colors.isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = MotionTokens.SHIMMER_HIGHLIGHT_ALPHA * 2)
    val shimmer = LocalShimmer.current
    Box(
        modifier.clip(shape).drawBehind {
            drawRect(base)
            val p = shimmer?.value ?: return@drawBehind
            // the band crosses the bone from beyond its start to beyond its end (right to left in RTL)
            val band = size.width.coerceAtLeast(120f)
            val travel = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) 1 - p else p
            val x = -band + (size.width + 2 * band) * travel
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, highlight, Color.Transparent),
                    start = Offset(x - band / 2, 0f),
                    end = Offset(x + band / 2, size.height),
                ),
            )
        },
    )
}

@Composable
private fun Line(width: Float = 1f, height: Dp = 12.dp, modifier: Modifier = Modifier) =
    Bone(modifier.fillMaxWidth(width).height(height))

/** A feed card while the first page loads: header, 16:9 image, title, two summary lines, actions. */
@Composable
fun FeedCardSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(Modifier.padding(start = 12.dp, top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Bone(Modifier.size(50.dp), CircleShape)
            Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Bone(Modifier.width(110.dp).height(14.dp))
                Bone(Modifier.width(160.dp).height(12.dp))
            }
        }
        Bone(Modifier.padding(horizontal = 9.dp).fillMaxWidth().aspectRatio(16f / 9f), RoundedCornerShape(8.dp))
        Column(Modifier.padding(horizontal = 17.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Line(0.8f, 18.dp)
            Line(1f)
            Line(0.6f)
        }
        Row(Modifier.padding(start = 12.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Bone(Modifier.size(26.dp), CircleShape)
            Spacer(Modifier.width(12.dp))
            Bone(Modifier.width(48.dp).height(12.dp))
        }
    }
}

/** Feed skeleton: [count] cards under one shimmer clock. */
@Composable
fun FeedSkeleton(count: Int = 3, modifier: Modifier = Modifier) {
    Skeleton(modifier.fillMaxWidth(), tag = "skeleton:feed") { repeat(count) { FeedCardSkeleton() } }
}

/** The post body while it renders: paragraphs of lines and an image. */
@Composable
fun PostBodySkeleton(modifier: Modifier = Modifier) {
    Skeleton(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), tag = "skeleton:body") {
        val widths = listOf(1f, 0.95f, 0.9f, 0.6f)
        widths.forEach { Line(it, 14.dp, Modifier.padding(bottom = 10.dp)) }
        Bone(Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(vertical = 6.dp), RoundedCornerShape(8.dp))
        widths.reversed().forEach { Line(it, 14.dp, Modifier.padding(top = 10.dp)) }
    }
}

/** The profile summary while the account loads: cover, avatar, name, bio lines, counts. */
@Composable
fun ProfileHeaderSkeleton(modifier: Modifier = Modifier) {
    Skeleton(modifier.fillMaxWidth(), tag = "skeleton:profile") {
        Box {
            Bone(Modifier.fillMaxWidth().height(160.dp), RoundedCornerShape(0.dp))
            Bone(Modifier.padding(start = 16.dp, top = 120.dp).size(80.dp), CircleShape)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Line(0.45f, 18.dp)
            Line(0.3f)
            Line(0.9f)
            Line(0.7f)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Bone(Modifier.width(70.dp).height(14.dp))
                Bone(Modifier.width(70.dp).height(14.dp))
            }
        }
    }
}

/**
 * List rows while a list loads (notifications, chats, wallet): [avatar] draws the leading circle (a square tile
 * when false), two lines and a trailing value.
 */
@Composable
fun RowsSkeleton(count: Int = 6, avatar: Boolean = true, tag: String = "skeleton:rows", modifier: Modifier = Modifier) {
    Skeleton(modifier.fillMaxWidth(), tag = tag) {
        repeat(count) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Bone(Modifier.size(if (avatar) 44.dp else 36.dp), if (avatar) CircleShape else RoundedCornerShape(8.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Line(0.55f, 14.dp)
                    Line(0.85f)
                }
                Bone(Modifier.padding(start = 12.dp).width(48.dp).height(14.dp))
            }
        }
    }
}
