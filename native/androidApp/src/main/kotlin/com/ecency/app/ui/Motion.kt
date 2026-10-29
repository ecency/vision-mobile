package com.ecency.app.ui

import com.ecency.core.app.AppIcon
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.ecency.core.app.CubicEasing
import com.ecency.core.app.HapticEvent
import com.ecency.core.app.HapticKind
import com.ecency.core.app.MotionLevel
import com.ecency.core.app.MotionPolicy
import com.ecency.core.app.MotionTokens
import com.ecency.core.app.PageMotion
import com.ecency.core.app.SpringToken
import com.ecency.core.app.ShellUiCopy

/**
 * The motion system on Android: the shared [MotionTokens] as Compose specs, picked by the [MotionLevel] the
 * system asks for ([rememberSystemMotion]). Every animated screen reads [LocalMotion]; nothing else holds a
 * duration or a curve.
 */
@Immutable
class Motion(val level: MotionLevel) {
    val pops: Boolean get() = MotionPolicy.pops(level)
    val shimmers: Boolean get() = MotionPolicy.shimmers(level)
    val page: PageMotion get() = MotionPolicy.page(level)

    /** A colour, opacity or icon crossfade (kept under reduce motion, none when animations are off). */
    fun <T> fade(ms: Int = MotionTokens.SHORT_MS): FiniteAnimationSpec<T> =
        MotionPolicy.fadeMillis(level, ms).let { if (it == 0) snap() else tween(it, easing = Standard) }

    /** A pop (vote, toggles): the overshooting spring at full motion, a plain fade otherwise. */
    fun <T> pop(): AnimationSpec<T> = if (pops) MotionTokens.POP.spec() else fade()

    /** Movement without a bounce. */
    fun <T> settle(): FiniteAnimationSpec<T> = when (level) {
        MotionLevel.FULL -> MotionTokens.SETTLE.spec()
        MotionLevel.REDUCED -> fade()
        MotionLevel.NONE -> snap()
    }

    override fun equals(other: Any?) = other is Motion && other.level == level
    override fun hashCode() = level.hashCode()

    companion object {
        val Full = Motion(MotionLevel.FULL)
        val Standard: Easing = MotionTokens.STANDARD.compose()
        val EmphasizedDecelerate: Easing = MotionTokens.EMPHASIZED_DECELERATE.compose()
        val EmphasizedAccelerate: Easing = MotionTokens.EMPHASIZED_ACCELERATE.compose()
    }
}

fun CubicEasing.compose(): Easing = CubicBezierEasing(x1, y1, x2, y2)

fun <T> SpringToken.spec(): FiniteAnimationSpec<T> = spring(dampingRatio = dampingRatio, stiffness = stiffness)

/** The motion level of this composition; the app root provides the system's, tests set their own. */
val LocalMotion = staticCompositionLocalOf { Motion.Full }

/**
 * The system's motion setting: Android has no reduce motion switch of its own; "Remove animations" (accessibility)
 * and the developer option set the animator duration scale, and 0 turns our animations off ([MotionLevel.NONE]).
 * Followed live through a settings observer.
 */
@Composable
fun rememberSystemMotion(): Motion {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var scale by remember { mutableFloatStateOf(durationScale(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scale = durationScale(resolver)
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return remember(scale) { Motion(MotionPolicy.level(reduceMotion = false, durationScale = scale)) }
}

/** The system animator duration scale (1 when unreadable). */
internal fun durationScale(resolver: android.content.ContentResolver): Float =
    runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)

// ---- page transitions ----

/**
 * Between screens of the stack: Material shared axis X at full motion ([forward] slides in from the end, back from
 * the start), a crossfade under reduce motion, nothing when off. The outgoing screen fades in the first third, the
 * incoming one in the rest, so the two never read as one.
 */
fun Motion.pageTransform(forward: Boolean, density: androidx.compose.ui.unit.Density): ContentTransform = when (page) {
    PageMotion.SHARED_AXIS -> {
        val shift = with(density) { MotionTokens.SHARED_AXIS_DP.dp.roundToPx() }
        val total = MotionTokens.PAGE_MS
        val out = (total * 0.3f).toInt()
        val sign = if (forward) 1 else -1
        (
            slideInHorizontally(tween(total, easing = EmphasizedDecelerateEasing)) { sign * shift } +
                fadeIn(tween(total - out, delayMillis = out, easing = LinearOutSlowIn))
            ) togetherWith (
            slideOutHorizontally(tween(total, easing = EmphasizedDecelerateEasing)) { -sign * shift } +
                fadeOut(tween(out, easing = FastOutLinearIn))
            )
    }
    PageMotion.FADE -> fadeIn(tween(MotionTokens.PAGE_FADE_MS)) togetherWith fadeOut(tween(MotionTokens.PAGE_FADE_MS))
    PageMotion.NONE -> EnterTransition.None togetherWith ExitTransition.None
}.let { ContentTransform(it.targetContentEnter, it.initialContentExit, if (forward) 1f else -1f, SizeTransform(clip = false)) }

/** Between tabs: Material fade through (the incoming tab grows slightly as it fades in); a crossfade when reduced. */
fun Motion.tabTransform(): ContentTransform = when (page) {
    PageMotion.SHARED_AXIS -> {
        val total = MotionTokens.PAGE_MS
        val out = (total * 0.3f).toInt()
        (
            fadeIn(tween(total - out, delayMillis = out, easing = LinearOutSlowIn)) +
                scaleIn(tween(total - out, delayMillis = out, easing = EmphasizedDecelerateEasing), initialScale = MotionTokens.FADE_THROUGH_SCALE)
            ) togetherWith fadeOut(tween(out, easing = FastOutLinearIn))
    }
    PageMotion.FADE -> fadeIn(tween(MotionTokens.PAGE_FADE_MS)) togetherWith fadeOut(tween(MotionTokens.PAGE_FADE_MS))
    PageMotion.NONE -> EnterTransition.None togetherWith ExitTransition.None
}.let { ContentTransform(it.targetContentEnter, it.initialContentExit, 0f, SizeTransform(clip = false)) }

private val EmphasizedDecelerateEasing = MotionTokens.EMPHASIZED_DECELERATE.compose()
private val LinearOutSlowIn: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)
private val FastOutLinearIn: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)

// ---- state changes ----

/**
 * An icon (or any small content) that swaps with a crossfade and, at full motion, a small scale in: works with any
 * icon set, since it only animates what [content] draws for each [target].
 */
@Composable
fun <T> AnimatedSwap(target: T, modifier: Modifier = Modifier, label: String = "swap", content: @Composable (T) -> Unit) {
    val motion = LocalMotion.current
    AnimatedContent(
        targetState = target,
        modifier = modifier,
        transitionSpec = {
            val ms = MotionPolicy.fadeMillis(motion.level)
            if (ms == 0) {
                EnterTransition.None togetherWith ExitTransition.None
            } else if (motion.pops) {
                (fadeIn(tween(ms)) + scaleIn(MotionTokens.POP.spec(), initialScale = 0.6f)) togetherWith fadeOut(tween(ms / 2))
            } else {
                fadeIn(tween(ms)) togetherWith fadeOut(tween(ms))
            }.using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.Center,
        label = label,
    ) { Incoming { content(it) } }
}

/**
 * A counter that rolls: a higher number comes up from below, a lower one down from above (full motion); a
 * crossfade when reduced. Lays out like a plain text of the current value.
 */
@Composable
fun AnimatedCounter(value: Long, modifier: Modifier = Modifier, content: @Composable (Long) -> Unit) {
    val motion = LocalMotion.current
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = { counterTransform(motion) },
        label = "counter",
    ) { Incoming { content(it) } }
}

/**
 * The content of an animated swap: the outgoing copy is left out of the semantics tree while it animates away, so
 * accessibility and tests see one node per tag (the incoming value), never two.
 */
@Composable
private fun AnimatedVisibilityScope.Incoming(content: @Composable () -> Unit) {
    val leaving = transition.targetState == EnterExitState.PostExit
    androidx.compose.foundation.layout.Box(if (leaving) Modifier.clearAndSetSemantics {} else Modifier) { content() }
}

private fun AnimatedContentTransitionScope<Long>.counterTransform(motion: Motion): ContentTransform {
    val ms = MotionPolicy.fadeMillis(motion.level)
    if (ms == 0) return EnterTransition.None togetherWith ExitTransition.None
    if (!motion.pops) return fadeIn(tween(ms)) togetherWith fadeOut(tween(ms))
    val up = targetState > initialState
    val sign = if (up) 1 else -1
    return (slideInVertically(tween(ms, easing = Motion.Standard)) { sign * it / 2 } + fadeIn(tween(ms))) togetherWith
        (slideOutVertically(tween(ms, easing = Motion.Standard)) { -sign * it / 2 } + fadeOut(tween(ms / 2))) using
        SizeTransform(clip = false)
}

/**
 * Pops (grows past full size and springs back) whenever [key] changes after the first composition and [enabled]
 * holds then: the vote fill, a toggled bookmark or favourite, a selected tab. Nothing under reduce motion. A graphics layer transform only, so the
 * row's layout never moves.
 */
fun Modifier.popOnChange(key: Any?, enabled: Boolean = true): Modifier = composed {
    val motion = LocalMotion.current
    val scale = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(key) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        if (!motion.pops || !enabled) return@LaunchedEffect
        scale.snapTo(1f)
        scale.animateTo(MotionTokens.POP_SCALE, tween(MotionTokens.PRESS_MS, easing = Motion.Standard))
        scale.animateTo(1f, MotionTokens.POP.spec())
    }
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/**
 * Shakes sideways each time [trigger] grows (the wrong PIN): the shared keyframes over [MotionTokens.SHAKE_MS].
 * Nothing under reduce motion (the error text says it).
 */
fun Modifier.shake(trigger: Int): Modifier = composed {
    val motion = LocalMotion.current
    val offset = remember { Animatable(0f) }
    val px = with(LocalDensity.current) { MotionTokens.SHAKE_DP.dp.toPx() }
    LaunchedEffect(trigger) {
        if (trigger <= 0 || !motion.pops) return@LaunchedEffect
        val frames = MotionPolicy.shakeKeyframes()
        val step = MotionTokens.SHAKE_MS / (frames.size - 1)
        offset.snapTo(0f)
        offset.animateTo(
            0f,
            keyframes {
                durationMillis = MotionTokens.SHAKE_MS
                frames.forEachIndexed { i, f -> (f * px) at (i * step) using LinearEasing }
            },
        )
    }
    graphicsLayer { translationX = offset.value }
}

/**
 * An icon-only control: an unbounded ripple from the touch point, sized to the icon's touch target ([radius]),
 * as Material icon buttons draw it. Bounded controls (cards, rows, pills) keep `clickable`'s default ripple.
 */
fun Modifier.iconClickable(
    radius: Dp = 20.dp,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = ripple(bounded = false, radius = radius),
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = Role.Button,
        onClick = onClick,
    )
}

/** How far a pressed control sinks. */
object MotionPressScale {
    const val ROW = MotionTokens.PRESS_SCALE
    const val FAB = 0.92f
}

/**
 * Sinks to [scale] while [interaction] is pressed and springs back on release (a graphics layer transform, no
 * layout). The click itself is never delayed: the release animation runs after it.
 */
fun Modifier.pressScale(interaction: androidx.compose.foundation.interaction.InteractionSource, scale: Float = MotionPressScale.ROW): Modifier = composed {
    val motion = LocalMotion.current
    val pressed by interaction.collectIsPressedAsState()
    val value by androidx.compose.animation.core.animateFloatAsState(
        if (pressed && motion.pops) scale else 1f,
        if (pressed) tween(MotionTokens.PRESS_MS, easing = Motion.Standard) else motion.settle(),
        label = "press",
    )
    graphicsLayer {
        scaleX = value
        scaleY = value
    }
}

// ---- send buttons ----

/** What a send button shows: its label or icon, a spinner while sending, a tick once sent. */
enum class SendButtonPhase { IDLE, SENDING, DONE }

/**
 * The send button's phase: a spinner while [sending], and the success haptic only when [sent] changes to a new
 * non-null value (the model's positive success signal: chat's sent version, a reply's or wave's sent key). The end
 * of [sending] alone means nothing: a failed send, a cancelled signing, an authority prompt or a send bound all end
 * it too. Where the composer stays open ([holdsDone], chat) a success also shows a tick for
 * [MotionTokens.DONE_HOLD_MS]. The tick never blocks: the button takes the next send at once, which ends it.
 */
@Composable
fun rememberSendPhase(sending: Boolean, sent: Any?, holdsDone: Boolean = true): SendButtonPhase {
    val haptics = rememberHaptics()
    var done by remember { mutableStateOf(false) }
    // what [sent] was at the first composition (or the last success): a value already there is no new success
    val last = remember { arrayOf(sent) }
    LaunchedEffect(sent) {
        done = false
        val succeeded = sent != null && sent != last[0]
        last[0] = sent
        if (!succeeded) return@LaunchedEffect
        haptics(HapticEvent.SUCCESS)
        if (holdsDone) {
            done = true
            kotlinx.coroutines.delay(MotionTokens.DONE_HOLD_MS.toLong())
            done = false
        }
    }
    LaunchedEffect(sending) { if (sending) done = false }
    return when {
        sending -> SendButtonPhase.SENDING
        done -> SendButtonPhase.DONE
        else -> SendButtonPhase.IDLE
    }
}

/** A send button's content: [idle], a spinner, or a tick, swapped with [AnimatedSwap]. */
@Composable
fun SendContent(phase: SendButtonPhase, tint: androidx.compose.ui.graphics.Color, size: Dp = 20.dp, idle: @Composable () -> Unit) {
    AnimatedSwap(phase, label = "send") {
        when (it) {
            SendButtonPhase.SENDING -> androidx.compose.material3.CircularProgressIndicator(
                androidx.compose.ui.Modifier.size(size).testTag("sending"), color = tint, strokeWidth = 2.dp,
            )
            SendButtonPhase.DONE -> androidx.compose.material3.Icon(
                AppIcon.CHECK.vector(), contentDescription = ShellUiCopy.SENT, tint = tint, modifier = androidx.compose.ui.Modifier.size(size).testTag("sent"),
            )
            SendButtonPhase.IDLE -> idle()
        }
    }
}

// ---- haptics ----

/** The `HapticFeedbackConstants` for a [HapticKind] on [sdk] (pure, unit tested): light, and system-standard. */
fun hapticConstant(kind: HapticKind, sdk: Int = Build.VERSION.SDK_INT): Int = when (kind) {
    HapticKind.LIGHT_IMPACT -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CONTEXT_CLICK
    HapticKind.SUCCESS -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK
    HapticKind.ERROR -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    HapticKind.SELECTION -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
}

/** Test hook: every haptic the app asked for, in order. */
object HapticProbe {
    @Volatile var onHaptic: ((HapticEvent) -> Unit)? = null
}

/**
 * Plays the haptic for [HapticEvent] through the view, so the system's touch feedback setting applies. Haptics do
 * not follow the motion level.
 */
@Composable
fun rememberHaptics(): (HapticEvent) -> Unit {
    val view = LocalView.current
    return remember(view) {
        { event ->
            HapticProbe.onHaptic?.invoke(event)
            view.performHapticFeedback(hapticConstant(MotionPolicy.haptic(event)))
        }
    }
}
