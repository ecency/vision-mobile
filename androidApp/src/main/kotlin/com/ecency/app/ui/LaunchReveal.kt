package com.ecency.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.ecency.core.app.LaunchReveal

/** A shared cubic path (start point, then c1, c2, end per segment) as a Compose path. */
private fun FloatArray.toPath(close: Boolean): Path = Path().also { p ->
    p.moveTo(this[0], this[1])
    var i = 2
    while (i + 5 < size) {
        p.cubicTo(this[i], this[i + 1], this[i + 2], this[i + 3], this[i + 4], this[i + 5])
        i += 6
    }
    if (close) p.close()
}

private class RevealPaths {
    val mark = Path().apply { LaunchReveal.mark.forEach { addPath(it.toPath(close = true)) } }
    val upper = LaunchReveal.upperArea.toPath(close = true)
    val lower = LaunchReveal.lowerArea.toPath(close = true)
    val sweeps = LaunchReveal.sweeps.map { s -> PathMeasure().apply { setPath(s.path.toPath(close = false), false) } }
}

/**
 * RN's welcome launch animation (`screens/launch`, README "Welcome and terms, register"): the Ecency mark drawn on
 * the page colour over the welcome screen for [LaunchReveal.DURATION_MS], then [onDone]. It takes the touches under
 * it while it shows (RN's overlay did) and is hidden from TalkBack. Only shown at full motion (the caller checks
 * [LaunchReveal.shows]).
 */
@Composable
fun LaunchRevealOverlay(onDone: () -> Unit) {
    val colors = LocalEcencyColors.current
    val paths = remember { RevealPaths() }
    var elapsed by remember { mutableLongStateOf(0L) }
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (elapsed < LaunchReveal.DURATION_MS) {
            elapsed = withFrameMillis { it } - start
        }
        done()
    }
    Box(
        Modifier.fillMaxSize().background(colors.background).pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
            .testTag("welcomeLaunch").clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(LaunchReveal.SIZE.dp)) {
            val k = size.width / LaunchReveal.VIEW_BOX
            val blue = Color(LaunchReveal.COLOR)
            val strokes = LaunchReveal.frame(elapsed)
            scale(k, k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
                LaunchReveal.sweeps.forEachIndexed { i, sweep ->
                    val stroke = strokes[i]
                    if (stroke.trimEnd <= 0f || stroke.alpha <= 0f) return@forEachIndexed
                    val measure = paths.sweeps[i]
                    val part = Path().also { measure.getSegment(0f, measure.length * stroke.trimEnd, it, true) }
                    val style = Stroke(width = sweep.width, cap = StrokeCap.Round, join = StrokeJoin.Miter)
                    when (sweep.area) {
                        LaunchReveal.Area.OUTSIDE_MARK -> clipPath(paths.mark, ClipOp.Difference) { drawPath(part, blue, stroke.alpha, style) }
                        LaunchReveal.Area.LOWER -> clipPath(paths.mark) { clipPath(paths.lower) { drawPath(part, blue, stroke.alpha, style) } }
                        LaunchReveal.Area.UPPER -> clipPath(paths.mark) { clipPath(paths.upper) { drawPath(part, blue, stroke.alpha, style) } }
                    }
                }
            }
        }
    }
}
