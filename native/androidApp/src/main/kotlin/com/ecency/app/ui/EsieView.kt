package com.ecency.app.ui

import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ecency.core.app.MotionLevel
import com.ecency.core.esie.EsieArt
import com.ecency.core.esie.EsieFrameBuffer
import com.ecency.core.esie.EsieGradient
import com.ecency.core.esie.EsieLayer
import com.ecency.core.esie.EsieMood
import com.ecency.core.esie.EsiePath
import com.ecency.core.esie.EsiePlacement
import com.ecency.core.esie.EsieRig
import com.ecency.core.esie.EsieSolid
import com.ecency.core.esie.EsieSpec
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext

/** The mood an [EsieView] shows, for tests (README "Esie"). */
val EsieMoodKey = SemanticsPropertyKey<EsieMood>("EsieMood")
var SemanticsPropertyReceiver.esieMood by EsieMoodKey

/** Whether an [EsieView] animates at all (false under reduced motion unless she talks), for tests. */
val EsieAnimatesKey = SemanticsPropertyKey<Boolean>("EsieAnimates")
var SemanticsPropertyReceiver.esieAnimates by EsieAnimatesKey

/** Esie at one of the app's [EsiePlacement]s: that placement's mood, size ([height] overrides it) and wave. */
@Composable
fun EsieView(placement: EsiePlacement, modifier: Modifier = Modifier, label: String? = null, height: Dp = placement.height.dp) =
    EsieView(placement.mood, modifier, height, waveCycles = placement.waveCycles, label = label)

/** The width an Esie of [mood] takes at [height]. */
fun esieWidth(mood: EsieMood, height: Dp): Dp = EsieRig.viewport(mood).let { height * (it.width / it.height) }

/**
 * Esie, drawn from the shared vector art ([EsieArt]) and animated by the shared rig ([EsieRig]).
 *
 * [height] sets the size; the width follows the mood's framing. Decorative by default (hidden from TalkBack, the
 * text next to it carries the message); pass [label] to make it an image with that description. Frames come at most
 * 30 a second, only while the screen is resumed and the view is composed, and stop once she settles
 * ([EsieRig.SETTLE_MS], then only blinks). Under reduced motion ([LocalMotion]) she holds a still pose; a talking
 * mouth still swaps on its own beat.
 */
@Composable
fun EsieView(
    mood: EsieMood,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    talking: Boolean = false,
    waveCycles: Int? = null,
    looking: Boolean = false,
    label: String? = null,
) {
    val reduceMotion = LocalMotion.current.level != MotionLevel.FULL
    val spec = EsieSpec(mood, talking, waveCycles, looking, reduceMotion)
    val viewport = remember(mood) { EsieRig.viewport(mood) }
    // a new spec (mood, talking, wave, motion level) starts over
    val elapsed = remember(spec) { mutableLongStateOf(0L) }
    val buffer = remember { EsieFrameBuffer() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val running = spec.animates && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    if (running) {
        LaunchedEffect(spec) {
            // resumes where it paused, so a returning screen does not replay the hop or the wave
            val start = withInfiniteAnimationFrameMillis { it } - elapsed.longValue
            while (true) {
                val wait = EsieRig.nextFrameDelayMs(spec, elapsed.longValue)
                if (wait < 0) break
                val due = elapsed.longValue + wait
                // a settled Esie sleeps until her next blink instead of waking every frame
                if (wait > EsieRig.FRAME_MS) infiniteDelay(wait - EsieRig.FRAME_MS)
                // frames under 33 ms apart are skipped: nothing is written, so nothing redraws
                var now = start + elapsed.longValue
                while (now - start < due) now = withInfiniteAnimationFrameMillis { it }
                elapsed.longValue = now - start
            }
        }
    }
    Canvas(
        modifier.size(esieWidth(mood, height), height).testTag("esie").semantics {
            esieMood = mood
            esieAnimates = spec.animates
            if (label == null) hideFromAccessibility() else contentDescription = label
        },
    ) {
        EsieRig.fill(spec, elapsed.longValue, buffer)
        val scale = size.height / viewport.height
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.scale(scale, scale)
            native.translate(-viewport.left, -viewport.top)
            EsieDrawing.draw(native, buffer)
            native.restore()
        }
    }
}

/** A delay that, like the frame loop, stops under a test's infinite animation policy instead of hanging it. */
private suspend fun infiniteDelay(ms: Long) {
    val policy = coroutineContext[InfiniteAnimationPolicy]
    if (policy == null) delay(ms) else policy.onInfiniteOperation { delay(ms) }
}

/** Paths and paints built once from the shared art, reused by every EsieView. Drawn on the main thread only. */
private object EsieDrawing {
    private val layers = EsieArt.layers
    private val paths: List<android.graphics.Path> by lazy { layers.map { it.path.toAndroidPath() } }
    private val clips: List<android.graphics.Path?> by lazy { layers.map { l -> l.clips.firstOrNull()?.let { EsieArt.clip(it.id).toAndroidPath() } } }
    private val fills: List<Paint?> by lazy { layers.map(::fillPaint) }
    private val strokes: List<Paint?> by lazy { layers.map(::strokePaint) }
    private val matrix = Matrix()
    private val values = FloatArray(9).also { it[8] = 1f }

    fun draw(canvas: android.graphics.Canvas, frame: EsieFrameBuffer) {
        val data = frame.data
        for (i in layers.indices) {
            val base = i * EsieFrameBuffer.STRIDE
            val alpha = data[base]
            if (alpha <= 0f) continue
            canvas.save()
            clips[i]?.let { clip ->
                canvas.concat(matrixAt(data, base + EsieFrameBuffer.CLIP_MATRIX))
                canvas.clipPath(clip)
            }
            canvas.concat(matrixAt(data, base + EsieFrameBuffer.DRAW_MATRIX))
            fills[i]?.let { canvas.drawPath(paths[i], it.withAlpha(layers[i], alpha, stroke = false)) }
            strokes[i]?.let { canvas.drawPath(paths[i], it.withAlpha(layers[i], alpha, stroke = true)) }
            canvas.restore()
        }
    }

    /** SVG a b c d e f into the shared Matrix (reused: Canvas.concat copies it). */
    private fun matrixAt(d: FloatArray, o: Int): Matrix {
        values[0] = d[o]; values[1] = d[o + 2]; values[2] = d[o + 4]
        values[3] = d[o + 1]; values[4] = d[o + 3]; values[5] = d[o + 5]
        matrix.setValues(values)
        return matrix
    }

    private fun Paint.withAlpha(layer: EsieLayer, alpha: Float, stroke: Boolean): Paint {
        val solid = if (stroke) layer.stroke?.argb else (layer.fill as? EsieSolid)?.argb
        if (solid != null) {
            color = solid.toInt()
            this.alpha = ((solid ushr 24).toInt() * alpha).toInt().coerceIn(0, 255)
        } else {
            this.alpha = (255 * alpha * ((layer.fill as? EsieGradient)?.alpha ?: 1f)).toInt().coerceIn(0, 255)
        }
        return this
    }

    private fun fillPaint(layer: EsieLayer): Paint? {
        val fill = layer.fill ?: return null
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            when (fill) {
                is EsieSolid -> color = fill.argb.toInt()
                is EsieGradient -> shader = fill.toShader()
            }
        }
    }

    private fun strokePaint(layer: EsieLayer): Paint? {
        val stroke = layer.stroke ?: return null
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke.width
            strokeCap = Paint.Cap.ROUND
            color = stroke.argb.toInt()
        }
    }

    private fun EsieGradient.toShader(): Shader {
        val colors = IntArray(this.colors.size) { this.colors[it].toInt() }
        val shader = if (radial) RadialGradient(coords[0], coords[1], coords[2], colors, offsets, Shader.TileMode.CLAMP)
        else LinearGradient(coords[0], coords[1], coords[2], coords[3], colors, offsets, Shader.TileMode.CLAMP)
        // gradient units to art coordinates (SVG matrix a b c d e f)
        shader.setLocalMatrix(Matrix().apply { setValues(floatArrayOf(matrix[0], matrix[2], matrix[4], matrix[1], matrix[3], matrix[5], 0f, 0f, 1f)) })
        return shader
    }
}

/** The shared path commands as a framework path. */
fun EsiePath.toAndroidPath(): android.graphics.Path {
    val path = android.graphics.Path()
    var c = 0
    for (op in ops) {
        when (op) {
            EsiePath.MOVE -> { path.moveTo(coords[c], coords[c + 1]); c += 2 }
            EsiePath.LINE -> { path.lineTo(coords[c], coords[c + 1]); c += 2 }
            EsiePath.CUBIC -> { path.cubicTo(coords[c], coords[c + 1], coords[c + 2], coords[c + 3], coords[c + 4], coords[c + 5]); c += 6 }
            EsiePath.QUAD -> { path.quadTo(coords[c], coords[c + 1], coords[c + 2], coords[c + 3]); c += 4 }
            EsiePath.CLOSE -> path.close()
        }
    }
    return path
}

/** Debug only (`--ez esieGallery true`): every mood side by side, for comparing with the approved renders. */
@Composable
fun EsieGallery() {
    val colors = LocalEcencyColors.current
    Column(
        Modifier.fillMaxSize().background(colors.background).statusBarsPadding().verticalScroll(rememberScrollState()).testTag("esieGallery"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EsieMood.entries.chunked(2).forEach { row ->
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { mood ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        EsieView(mood, height = 150.dp)
                        Text(mood.label, color = colors.darkGray)
                    }
                }
            }
        }
    }
}
