package com.ecency.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The RN upvote control: a circle with a chevron (AntDesign upcircleo / upcircle). [filled]
 * when voted; [down] draws the downvoted state (red, chevron pointing down).
 */
@Composable
fun UpvoteGlyph(color: Color, filled: Boolean, down: Boolean = false, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * 0.075f
        val r = this.size.minDimension / 2 - stroke / 2
        if (filled) drawCircle(color, radius = r + stroke / 2) else drawCircle(color, radius = r, style = Stroke(stroke))
        val chevron = if (filled) Color.White else color
        val w = this.size.width
        val h = this.size.height
        val dy = if (down) -1f else 1f
        val tipY = h / 2 - dy * h * 0.12f
        val baseY = h / 2 + dy * h * 0.1f
        drawLine(chevron, Offset(w * 0.3f, baseY), Offset(w / 2, tipY), stroke, StrokeCap.Round)
        drawLine(chevron, Offset(w / 2, tipY), Offset(w * 0.7f, baseY), stroke, StrokeCap.Round)
    }
}
