package com.ecency.core.app

import kotlin.math.PI
import kotlin.math.sqrt

/**
 * The motion system's numbers: one source for both apps. Android maps them to Compose specs in `ui/Motion.kt`,
 * iOS to SwiftUI animations in `App/Motion.swift`; screens use those, never literal durations or curves.
 *
 * Rules the platforms follow (README "Motion"): animate transforms and opacity, not layout, in long lists; no
 * animation delays a user action (taps go through at once, a transition never blocks input to the screen it opens);
 * with reduce motion the moves become crossfades or nothing ([MotionPolicy]).
 */
object MotionTokens {
    /** A pressed state going in and out. */
    const val PRESS_MS = 90

    /** Icon swaps, colour changes, counters. */
    const val SHORT_MS = 150

    /** Enabled and disabled, idle to loading to done, content replacing a skeleton. */
    const val MEDIUM_MS = 250

    /** The launch screen fading into the first screen (RN bootsplash's `hide({ fade: true })`, 220 ms). */
    const val SPLASH_FADE_MS = 220

    /** Moving between screens (Material shared axis, fade through between tabs). */
    const val PAGE_MS = 300

    /** The crossfade that replaces a page move under reduce motion. */
    const val PAGE_FADE_MS = 150

    /** One sweep of the skeleton shimmer. */
    const val SHIMMER_MS = 1200

    /** The wrong PIN shake, all cycles. */
    const val SHAKE_MS = 400

    /** How long a send button shows its done tick where the composer stays open (chat). Never blocks input. */
    const val DONE_HOLD_MS = 900

    /** Material 3 standard: most state changes. */
    val STANDARD = CubicEasing(0.2f, 0f, 0f, 1f)

    /** Material 3 emphasized decelerate: things arriving (a screen sliding in). */
    val EMPHASIZED_DECELERATE = CubicEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Material 3 emphasized accelerate: things leaving. */
    val EMPHASIZED_ACCELERATE = CubicEasing(0.3f, 0f, 0.8f, 0.15f)

    /** The vote and toggle pop: a quick overshoot that settles. */
    val POP = SpringToken(dampingRatio = 0.45f, stiffness = 500f)

    /** Movement without a bounce (press release, counters). */
    val SETTLE = SpringToken(dampingRatio = 1f, stiffness = 700f)

    /** How far a toggled icon grows before it settles back. */
    const val POP_SCALE = 1.25f

    /** A pressed card or row. */
    const val PRESS_SCALE = 0.98f

    /** The darkening of a pressed card or row (iOS; Android keeps the Material ripple). */
    const val PRESS_DIM = 0.06f

    /** Shared axis: how far a screen moves sideways while it fades (Material: 30 dp). */
    const val SHARED_AXIS_DP = 30f

    /** Fade through: the incoming screen grows from this scale (Material: 92 percent). */
    const val FADE_THROUGH_SCALE = 0.92f

    /** Wrong PIN: the pad's dots move this far each way. */
    const val SHAKE_DP = 12f

    /** Wrong PIN: full left-right cycles in [SHAKE_MS]. */
    const val SHAKE_CYCLES = 3

    /** The skeleton's base and highlight opacity over the placeholder colour. */
    const val SHIMMER_BASE_ALPHA = 1f
    const val SHIMMER_HIGHLIGHT_ALPHA = 0.45f
}

/** A cubic bezier easing with the CSS / Material control points. */
data class CubicEasing(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

/** A spring as Compose specifies it (damping ratio, stiffness for unit mass). */
data class SpringToken(val dampingRatio: Float, val stiffness: Float) {
    /** SwiftUI's `response` for the same spring: the undamped period, 2 pi / sqrt(stiffness), in seconds. */
    val responseSeconds: Double get() = 2 * PI / sqrt(stiffness.toDouble())
}

/** How much motion the user wants. */
enum class MotionLevel {
    /** Everything: moves, pops, bounces, shimmer. */
    FULL,

    /** Reduce motion is on: no moves, pops or shimmer; state changes crossfade. */
    REDUCED,

    /** Animations are off (Android "Remove animations", or a duration scale of 0): changes land at once. */
    NONE,
}

/** How a screen replaces another. */
enum class PageMotion { SHARED_AXIS, FADE, NONE }

/** Which interactions give haptic feedback: few, and light. */
enum class HapticEvent {
    /** A vote went out (the weight was chosen). */
    VOTE,

    /** A two-state toggle the user flipped (bookmark, favourite, follow, reblog). */
    TOGGLE,

    /** Something landed: a comment, wave or message was sent, a link was copied. */
    SUCCESS,

    /** A wrong PIN. */
    ERROR,

    /** Pull to refresh crossed its threshold. */
    REFRESH,

    /** A tab or segment changed. */
    SELECTION,
}

/** The platform haptic each [HapticEvent] maps to. */
enum class HapticKind { LIGHT_IMPACT, SUCCESS, ERROR, SELECTION }

/** The reduce motion choice, one place for both apps (pure, unit tested). */
object MotionPolicy {
    /**
     * [reduceMotion]: the system's reduce motion switch (iOS `accessibilityReduceMotion`; Android has none of its
     * own, so false). [durationScale]: Android's animator duration scale (1 on iOS); 0 turns animations off.
     */
    fun level(reduceMotion: Boolean, durationScale: Float = 1f): MotionLevel = when {
        durationScale <= 0f || durationScale.isNaN() -> MotionLevel.NONE
        reduceMotion -> MotionLevel.REDUCED
        else -> MotionLevel.FULL
    }

    fun page(level: MotionLevel): PageMotion = when (level) {
        MotionLevel.FULL -> PageMotion.SHARED_AXIS
        MotionLevel.REDUCED -> PageMotion.FADE
        MotionLevel.NONE -> PageMotion.NONE
    }

    fun pageMillis(level: MotionLevel): Int = when (level) {
        MotionLevel.FULL -> MotionTokens.PAGE_MS
        MotionLevel.REDUCED -> MotionTokens.PAGE_FADE_MS
        MotionLevel.NONE -> 0
    }

    /** A crossfade or colour change of [baseMs]: kept under reduce motion (fades are not motion), none when off. */
    fun fadeMillis(level: MotionLevel, baseMs: Int = MotionTokens.SHORT_MS): Int = if (level == MotionLevel.NONE) 0 else baseMs

    /** Scale pops, bounces and the PIN shake: only at full motion. */
    fun pops(level: MotionLevel): Boolean = level == MotionLevel.FULL

    /** The moving shimmer: only at full motion; otherwise the skeleton is a still placeholder. */
    fun shimmers(level: MotionLevel): Boolean = level == MotionLevel.FULL

    /** The pressed state that grows from the touch point (iOS): full motion only; reduced keeps a plain dim. */
    fun pressExpands(level: MotionLevel): Boolean = level == MotionLevel.FULL

    /** Haptics do not depend on the motion level: the user turns them off in the system. */
    fun haptic(event: HapticEvent): HapticKind = when (event) {
        HapticEvent.VOTE, HapticEvent.TOGGLE, HapticEvent.REFRESH -> HapticKind.LIGHT_IMPACT
        HapticEvent.SUCCESS -> HapticKind.SUCCESS
        HapticEvent.ERROR -> HapticKind.ERROR
        HapticEvent.SELECTION -> HapticKind.SELECTION
    }

    /**
     * Whether the PIN pad shakes: a full entry was just refused (the pad came back empty with an error, from a full
     * or checking entry). A deleted digit, or typing over an earlier error, never shakes.
     */
    fun pinRejected(before: PinPrompt?, now: PinPrompt): Boolean =
        before != null && now.error != null && !now.isChecking && now.digits == 0 &&
            (before.isChecking || before.digits >= com.ecency.core.secure.PinLock.PIN_LENGTH - 1)

    /**
     * The wrong PIN shake as offsets (in units of [MotionTokens.SHAKE_DP]) at evenly spaced key times, starting and
     * ending at rest; each cycle swings both ways and the swings fade out.
     */
    fun shakeKeyframes(cycles: Int = MotionTokens.SHAKE_CYCLES): List<Float> {
        if (cycles <= 0) return listOf(0f)
        val frames = mutableListOf(0f)
        for (i in 0 until cycles) {
            val amplitude = 1f - i.toFloat() / cycles
            frames += -amplitude
            frames += amplitude
        }
        frames += 0f
        return frames
    }
}
