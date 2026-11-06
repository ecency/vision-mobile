package com.ecency.core.app

import kotlin.math.abs

/**
 * RN's welcome launch animation (README "Welcome and terms, register"): RN's welcome screen covers itself for
 * 3.55 s with `screens/launch`, a 150 x 150 Lottie animation on the page colour that draws the Ecency mark. Native
 * draws the same shapes (`LaunchRevealData`, generated from RN's file) with no animation library:
 * - a wide blue sweep traced around the mark, outside it, that fades out (frames 0 to 33);
 * - the mark filled by two strokes, each traced along its own path and seen only through its part of the mark: the
 *   lower part from frame 23 to 58, the upper from frame 27 to 61; the whole mark then holds until 3.55 s.
 * Frames are Lottie's (20 a second); times between them interpolate with each keyframe's own easing curve.
 * Shown only at full motion: Reduce Motion (and animations off) skip it, so the screen shows at once.
 */
object LaunchReveal {
    /** RN WelcomeScreen: `setTimeout(() => setShowAnimation(false), 3550)`; the animation is 71 frames at 20 fps. */
    const val DURATION_MS = 3550L

    /** RN's LottieView: 150 x 150 (dp, pt), centered. */
    const val SIZE = 150f

    /** The composition's size: the shapes' coordinates are in a [VIEW_BOX] square. */
    const val VIEW_BOX = 500f

    /** RN's stroke colour, the brand blue, in both themes (as the launch screen's mark). */
    const val COLOR: Long = 0xFF004BD8

    private const val FRAME_MS = 50f

    /** Where a sweep shows: outside the mark, or inside one part of it. */
    enum class Area { OUTSIDE_MARK, LOWER, UPPER }

    /** A stroke along [path] (open, start point then cubics), [width] wide with round caps, seen only in [area]. */
    class Sweep internal constructor(
        val path: FloatArray,
        val width: Float,
        val area: Area,
        internal val start: Float,
        internal val trim: Keyframes,
        internal val fade: Keyframes?,
    )

    /** One sweep at a moment: drawn from its start to [trimEnd] of its length, at [alpha]. */
    data class Stroke(val trimEnd: Float, val alpha: Float)

    /** Two keyframes of a value with Lottie's easing between them (cubic bezier out of the first, into the second). */
    internal class Keyframes(val from: Float, val to: Float, val fromValue: Float, val toValue: Float, val ease: Bezier)

    internal class Bezier(private val x1: Float, private val y1: Float, private val x2: Float, private val y2: Float) {
        private fun at(t: Float, a: Float, b: Float): Float {
            val u = 1 - t
            return 3 * u * u * t * a + 3 * u * t * t * b + t * t * t
        }

        /** y for x on the curve (x and y from 0 to 1), by bisection: x(t) only grows. */
        fun y(x: Float): Float {
            if (x <= 0f) return 0f
            if (x >= 1f) return 1f
            var lo = 0f
            var hi = 1f
            var t = x
            repeat(40) {
                val d = at(t, x1, x2) - x
                if (abs(d) < 1e-5f) return at(t, y1, y2)
                if (d > 0) hi = t else lo = t
                t = (lo + hi) / 2
            }
            return at(t, y1, y2)
        }
    }

    private fun Keyframes.value(frame: Float): Float = when {
        frame <= from -> fromValue
        frame >= to -> toValue
        else -> fromValue + (toValue - fromValue) * ease.y((frame - from) / (to - from))
    }

    val mark: List<FloatArray> get() = LaunchRevealData.MARK
    val upperArea: FloatArray get() = LaunchRevealData.UPPER_AREA
    val lowerArea: FloatArray get() = LaunchRevealData.LOWER_AREA

    /** In drawing order (bottom first), with RN's layers' widths (238 and 129) at their 59 % scale. */
    val sweeps: List<Sweep> = listOf(
        Sweep(
            LaunchRevealData.OUTER_SWEEP, 238f * 0.59f, Area.OUTSIDE_MARK, start = 0f,
            trim = Keyframes(0f, 26f, 0f, 1f, Bezier(0.4f, 0f, 0.2f, 1f)),
            fade = Keyframes(23f, 33f, 1f, 0f, Bezier(0.167f, 0.167f, 0.833f, 0.833f)),
        ),
        Sweep(LaunchRevealData.LOWER_SWEEP, 129f * 0.59f, Area.LOWER, start = 21f, trim = Keyframes(23f, 58f, 0f, 1f, Bezier(0.167f, 0.167f, 0.2f, 1f)), fade = null),
        Sweep(LaunchRevealData.UPPER_SWEEP, 129f * 0.59f, Area.UPPER, start = 27f, trim = Keyframes(27f, 61f, 0f, 1f, Bezier(0.167f, 0.167f, 0.2f, 1f)), fade = null),
    )

    /** Only at full motion: Reduce Motion and animations off show the welcome screen at once. */
    fun shows(level: MotionLevel): Boolean = level == MotionLevel.FULL

    /** Each sweep (as [sweeps]) [elapsedMs] after the screen appeared; a sweep before its layer starts draws nothing. */
    fun frame(elapsedMs: Long): List<Stroke> {
        val f = elapsedMs.coerceAtLeast(0) / FRAME_MS
        return sweeps.map { s ->
            if (f < s.start) Stroke(0f, 1f) else Stroke(s.trim.value(f), s.fade?.value(f) ?: 1f)
        }
    }
}
