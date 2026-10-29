package com.ecency.core.esie

/**
 * What Esie shows: a pose of the fins plus an expression. The four placements use [REST] (empty lists), [SAD]
 * (load failures, offline), [HAPPY] (success moments) and [WAVE] (welcome, login); [THINKING] and [SURPRISED]
 * are for Esie AI (thinking while it answers, surprised by an unexpected result).
 */
enum class EsieMood {
    REST, THUMBS_UP, WAVE, HAPPY, SAD, THINKING, SURPRISED;

    /** A readable name ("thumbs up"), for accessibility values and the gallery. */
    val label: String get() = name.lowercase().replace('_', ' ')
}

/** The fins: the thumbs-up fin and forearm, the waving arm and hand, or the mirrored side fin resting. */
enum class EsieFins { THUMBS_UP, WAVE, REST }

/** The face: eyes, brows, lids and the mouth at rest. */
enum class EsieExpression { NEUTRAL, HAPPY, SAD, THINKING, SURPRISED }

/**
 * One Esie on screen.
 *
 * @param talking the mouth cycles through the talking shapes (any mood).
 * @param waveCycles with a waving mood: wave this many cycles, then rest the fins (null: wave on).
 * @param looking the pupils look around (neutral expressions only; the others place the pupils themselves).
 * @param reduceMotion the system's reduce motion setting: a still pose, no hop, drip, sway, blink or wave; only the
 *   talking mouth still swaps (instantly).
 */
data class EsieSpec(
    val mood: EsieMood,
    val talking: Boolean = false,
    val waveCycles: Int? = null,
    val looking: Boolean = false,
    val reduceMotion: Boolean = false,
) {
    /** Whether frames change over time at all: a still spec needs one frame and no clock. */
    val animates: Boolean get() = !reduceMotion || talking
}

/**
 * The approved motion (owner validated 10-22; the CSS it comes from is `native/tools/esie/art/motion.css`).
 * Durations in milliseconds, angles in degrees, distances in art units (the master is 1740 wide).
 */
object EsieMotion {
    /** Idle: scale (1.012, 0.988) about the bottom centre and back, ease-in-out. */
    const val BREATHE_MS = 3200L
    const val BREATHE_SX = 1.012f
    const val BREATHE_SY = 0.988f

    /** The tail tip's sway to -7 degrees and back; the resting side fin sways the same way at its own pace. */
    const val SWAY_MS = 2600L
    const val REST_FIN_SWAY_MS = 2800L
    const val SWAY_DEG = -7f

    /** Blink: lids down from 90 % to 94 % of the cycle, up again by 100 % (CSS `ease`). */
    const val BLINK_MS = 4000L

    /** Look around: the pupils move to the next spot every 900 ms, easing for 350 ms. */
    const val LOOK_STEP_MS = 900L
    const val LOOK_EASE_MS = 350L
    val LOOK_SPOTS: List<Pair<Float, Float>> = listOf(0f to 0f, -26f to 6f, 22f to -14f, 18f to 16f, -14f to -18f)

    /** Talking: the mouth swaps every 140 ms through these shapes. */
    const val TALK_STEP_MS = 140L
    val TALK_MOUTHS: List<String> = listOf("mouth_open", "mouth_o", "mouth_flat", "mouth_open", "mouth_smile", "mouth_o")

    /** Wave: the arm rotates 2 to -5 degrees and back each second, the hand 12 to -16 degrees every half second. */
    const val ARM_WAVE_MS = 1000L
    const val HAND_WAVE_MS = 500L

    /** Happy: one hop up 40 with a stretch (overshooting easing), then idle; the ears wiggle 3 times. */
    const val HOP_MS = 600L
    const val HOP_UP = -40f
    const val WIGGLE_MS = 500L
    const val WIGGLES = 3
    const val WIGGLE_DEG = 12f

    /** Sad: a slow sigh instead of breathing, ears down 10 degrees, pupils down, a tear dripping. */
    const val SIGH_MS = 3600L
    const val SAD_EAR_DEG = -10f
    const val DRIP_MS = 2400L
    const val DRIP_FALL = 90f

    /** Thinking: a slight tilt, pupils up and right, the thought dots pulsing one after another. */
    const val PONDER_MS = 3000L
    const val PONDER_DEG = -2f
    const val DOT_MS = 1800L
    const val DOT_STAGGER_MS = 300L

    /** Surprised: one jolt up 26 with a squash, ears up 14, pupils shrunk to 72 %. */
    const val JOLT_MS = 450L
    const val JOLT_UP = -26f
    const val SURPRISED_EARS_UP = -14f
    const val SURPRISED_PUPIL_SCALE = 0.72f

    /** Pupil offsets of the expressions that place them (sad down, thinking up and right). */
    val SAD_PUPILS: Pair<Float, Float> = -6f to 40f
    val THINKING_PUPILS: Pair<Float, Float> = 26f to -34f

    // CSS timing functions
    internal val EASE = CubicBezier(0.25f, 0.1f, 0.25f, 1f)
    internal val EASE_IN = CubicBezier(0.42f, 0f, 1f, 1f)
    internal val EASE_IN_OUT = CubicBezier(0.42f, 0f, 0.58f, 1f)
    internal val LOOK_EASE = CubicBezier(0.3f, 0.7f, 0.3f, 1f)
    internal val HOP_EASE = CubicBezier(0.3f, 1.6f, 0.5f, 1f)
    internal val JOLT_EASE = CubicBezier(0.2f, 1.8f, 0.4f, 1f)
}

/** A CSS `cubic-bezier(x1, y1, x2, y2)` timing function; y may overshoot. */
internal class CubicBezier(private val x1: Float, private val y1: Float, private val x2: Float, private val y2: Float) {
    private fun coord(t: Float, a: Float, b: Float): Float {
        val u = 1 - t
        return 3 * u * u * t * a + 3 * u * t * t * b + t * t * t
    }

    fun ease(x: Float): Float {
        if (x <= 0f) return 0f
        if (x >= 1f) return 1f
        // bisection on x(t): monotonic for x1, x2 in [0, 1]
        var lo = 0f
        var hi = 1f
        var t = x
        repeat(24) {
            val cx = coord(t, x1, x2)
            if (cx < x) lo = t else hi = t
            t = (lo + hi) / 2
        }
        return coord(t, y1, y2)
    }
}
