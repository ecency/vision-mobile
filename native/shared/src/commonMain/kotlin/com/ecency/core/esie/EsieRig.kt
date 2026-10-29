package com.ecency.core.esie

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A part's transform in art coordinates, applied as CSS does with `transform-origin` ([px], [py]):
 * translate(px + tx, py + ty), rotate([rotation] degrees, clockwise), scale([sx], [sy]), translate(-px, -py).
 */
data class EsieTransform(
    val tx: Float = 0f,
    val ty: Float = 0f,
    val rotation: Float = 0f,
    val sx: Float = 1f,
    val sy: Float = 1f,
    val px: Float = 0f,
    val py: Float = 0f,
)

/**
 * Esie at one moment, as the renderers read it: per layer of [EsieArt.layers], [STRIDE] floats in [data]:
 * the opacity (0: not drawn), the matrix to apply before the layer's clip (a, b, c, d, e, f as SVG `matrix()`), and
 * the matrix to apply after it. A layer without a clip has the identity first. One buffer per view, refilled by
 * [EsieRig.fill] every frame without allocating.
 */
class EsieFrameBuffer {
    val data = FloatArray(EsieRig.layerCount * STRIDE)

    // per part scratch, indexed like EsieRig's parts
    internal val shown = BooleanArray(EsieRig.partCount)
    internal val tx = FloatArray(EsieRig.partCount)
    internal val ty = FloatArray(EsieRig.partCount)
    internal val rot = FloatArray(EsieRig.partCount)
    internal val sx = FloatArray(EsieRig.partCount)
    internal val sy = FloatArray(EsieRig.partCount)
    internal val px = FloatArray(EsieRig.partCount)
    internal val py = FloatArray(EsieRig.partCount)
    internal val moved = BooleanArray(EsieRig.partCount)
    internal val fade = FloatArray(EsieRig.partCount)
    internal val matrix = FloatArray(EsieRig.partCount * 6)

    fun alpha(layer: Int): Float = data[layer * STRIDE]

    companion object {
        const val STRIDE = 13
        const val CLIP_MATRIX = 1
        const val DRAW_MATRIX = 7
    }
}

/** Esie at one moment, for tests and checks: which parts show, how they move and fade. Renderers use [EsieFrameBuffer]. */
class EsieFrame internal constructor(private val buffer: EsieFrameBuffer) {
    /** The switchable parts shown in this frame (the rest of [EsieRig.SWITCHABLE] is hidden). */
    val shown: Set<String> = EsieRig.SWITCHABLE.filter { buffer.shown[EsieRig.indexOf(it)] }.toSet()

    val fins: EsieFins
        get() = when {
            "fin_wave" in shown -> EsieFins.WAVE
            "fin_front" in shown -> EsieFins.THUMBS_UP
            else -> EsieFins.REST
        }
    val mouth: String get() = EsieRig.MOUTHS.first { it in shown }

    fun isVisible(layer: EsieLayer): Boolean = layer.chain.none { it in EsieRig.SWITCHABLE && it !in shown }

    fun transform(part: String): EsieTransform? {
        val i = EsieRig.indexOf(part)
        if (!buffer.moved[i]) return null
        return EsieTransform(buffer.tx[i], buffer.ty[i], buffer.rot[i], buffer.sx[i], buffer.sy[i], buffer.px[i], buffer.py[i])
    }

    /** The layer's opacity: the master's times every animated fade on its chain (0 when hidden). */
    fun alpha(layer: EsieLayer): Float = buffer.alpha(EsieArt.layers.indexOf(layer))

    /** Where the layer can draw in this frame, in art coordinates (null when hidden). */
    fun drawnBox(layer: EsieLayer): EsieRect? = EsieRig.drawnBox(buffer, EsieArt.layers.indexOf(layer))
}

/** Computes Esie's frames from an [EsieSpec] and the time since it appeared; the same on both platforms. */
object EsieRig {
    val MOUTHS: List<String> = listOf("mouth_smile", "mouth_open", "mouth_o", "mouth_big", "mouth_frown", "mouth_hmm", "mouth_flat")

    /** Parts that moods switch on and off; every other part always shows. */
    val SWITCHABLE: Set<String> = setOf(
        "fin_back", "fin_front", "fin_wave", "fin_rest",
        "eye_l", "eye_r", "body_shade", "eyes_happy", "lids_sad",
        "brows_sad", "brows_think", "brows_surprised", "tear", "thought",
    ) + MOUTHS

    /** At most this often (30 fps): the idle motion is slow, and every frame redraws every vector layer. */
    const val FRAME_MS = 33L

    /**
     * After this long with the same spec Esie settles: each continuous cycle (breathing, sways, waves, the tear's drip,
     * the thought dots) runs on to its next start and holds there, so nothing stops mid-move (the tear has dripped
     * away, the dots are even). From [SETTLED_MS] on only the blink runs, a short burst of frames per blink instead of
     * 30 frames a second. A talking Esie never settles; a new spec starts over.
     */
    const val SETTLE_MS = 30_000L

    /** The longest cycle ([EsieMotion.LOOK_STEP_MS] times the look spots): every cycle is held by then. */
    const val SETTLE_TAIL_MS = 4_500L

    /** From here a settled Esie only blinks. */
    const val SETTLED_MS = SETTLE_MS + SETTLE_TAIL_MS

    // ---- parts and layers, indexed once ----
    private val partNames: List<String> = EsieArtData.BOUNDS.keys.toList()
    internal val partCount: Int = partNames.size
    internal val layerCount: Int get() = EsieArtData.LAYERS.size
    private val partIndex: Map<String, Int> = partNames.withIndex().associate { it.value to it.index }
    internal fun indexOf(part: String): Int = partIndex[part] ?: throw IllegalArgumentException("no part $part")
    private val switchable = BooleanArray(partCount) { partNames[it] in SWITCHABLE }
    private val chains: Array<IntArray> = EsieArtData.LAYERS.map { l -> IntArray(l.chain.size) { indexOf(l.chain[it]) } }.toTypedArray()
    private val clipDepth: IntArray = IntArray(EsieArtData.LAYERS.size) { i ->
        val clips = EsieArtData.LAYERS[i].clips
        require(clips.size <= 1) { "one clip per layer" }
        clips.firstOrNull()?.depth ?: 0
    }
    private val layerOpacity = FloatArray(EsieArtData.LAYERS.size) { EsieArtData.LAYERS[it].opacity }

    private fun bounds(part: String) = EsieArtData.BOUNDS.getValue(part)
    private fun pivotX(part: String, f: Float) = bounds(part).let { it[0] + f * (it[2] - it[0]) }
    private fun pivotY(part: String, f: Float) = bounds(part).let { it[1] + f * (it[3] - it[1]) }

    private val ROOT = indexOf("esie")
    private val ROOT_X = pivotX("esie", 0.5f)
    private val ROOT_Y = pivotY("esie", 1f)
    private val TAIL_TIP = indexOf("tail_tip")
    private val TAIL_X = pivotX("tail_tip", 0.1f)
    private val TAIL_Y = pivotY("tail_tip", 0.05f)
    private val FIN_REST = indexOf("fin_rest")
    private val FIN_REST_X = pivotX("fin_rest", 0.7f)
    private val FIN_REST_Y = pivotY("fin_rest", 0f)
    private val ARM = indexOf("fin_wave_arm")
    private val ARM_X = pivotX("fin_wave_arm", 0.85f)
    private val ARM_Y = pivotY("fin_wave_arm", 0.88f)
    private val HAND = indexOf("fin_wave_hand")
    private val HAND_X = pivotX("fin_wave_hand", 0.72f)
    private val HAND_Y = pivotY("fin_wave_hand", 0.96f)
    private val LIDS = intArrayOf(indexOf("eye_l_lid"), indexOf("eye_r_lid"))
    private val LID_TRAVEL = floatArrayOf(EsieArt.lidTravel("eye_l_lid"), EsieArt.lidTravel("eye_r_lid"))
    private val PUPILS = intArrayOf(indexOf("eye_l_pupil"), indexOf("eye_r_pupil"))
    private val PUPIL_X = floatArrayOf(pivotX("eye_l_pupil", 0.5f), pivotX("eye_r_pupil", 0.5f))
    private val PUPIL_Y = floatArrayOf(pivotY("eye_l_pupil", 0.5f), pivotY("eye_r_pupil", 0.5f))
    private val EARS = intArrayOf(indexOf("ear_l"), indexOf("ear_r"))
    private val EAR_X = floatArrayOf(pivotX("ear_l", 0.5f), pivotX("ear_r", 0.5f))
    private val EAR_Y = floatArrayOf(pivotY("ear_l", 1f), pivotY("ear_r", 1f))
    private val EARS_GROUP = indexOf("ears")
    private val TEAR = indexOf("tear")
    private val TEAR_X = pivotX("tear", 0.5f)
    private val TEAR_Y = pivotY("tear", 0f)
    private val DOTS = intArrayOf(indexOf("thought_1"), indexOf("thought_2"), indexOf("thought_3"))
    private val DOT_X = floatArrayOf(pivotX("thought_1", 0.5f), pivotX("thought_2", 0.5f), pivotX("thought_3", 0.5f))
    private val DOT_Y = floatArrayOf(pivotY("thought_1", 0.5f), pivotY("thought_2", 0.5f), pivotY("thought_3", 0.5f))
    private val TALK_INDEX = IntArray(EsieMotion.TALK_MOUTHS.size) { indexOf(EsieMotion.TALK_MOUTHS[it]) }
    private val FIN_BACK = indexOf("fin_back")
    private val FIN_FRONT = indexOf("fin_front")
    private val FIN_WAVE = indexOf("fin_wave")
    private val EYE_L = indexOf("eye_l")
    private val EYE_R = indexOf("eye_r")
    private val BODY_SHADE = indexOf("body_shade")
    private val EYES_HAPPY = indexOf("eyes_happy")
    private val LIDS_SAD = indexOf("lids_sad")
    private val BROWS_SAD = indexOf("brows_sad")
    private val BROWS_THINK = indexOf("brows_think")
    private val BROWS_SURPRISED = indexOf("brows_surprised")
    private val THOUGHT = indexOf("thought")
    private val RESTING_MOUTH = IntArray(EsieExpression.entries.size) { indexOf(restingMouth(EsieExpression.entries[it])) }

    // keyframe tables (offsets and values), allocated once
    private val HALF = floatArrayOf(0f, 0.5f, 1f)
    private val BREATHE_SX = floatArrayOf(1f, EsieMotion.BREATHE_SX, 1f)
    private val BREATHE_SY = floatArrayOf(1f, EsieMotion.BREATHE_SY, 1f)
    private val SIGH_TY = floatArrayOf(0f, 8f, 0f)
    private val SIGH_SX = floatArrayOf(1f, 1.01f, 1f)
    private val SIGH_SY = floatArrayOf(1f, 0.985f, 1f)
    private val HOP_AT = floatArrayOf(0f, 0.4f, 1f)
    private val HOP_TY = floatArrayOf(0f, EsieMotion.HOP_UP, 0f)
    private val HOP_SX = floatArrayOf(1f, 0.98f, 1f)
    private val HOP_SY = floatArrayOf(1f, 1.03f, 1f)
    private val JOLT_AT = floatArrayOf(0f, 0.35f, 1f)
    private val JOLT_TY = floatArrayOf(0f, EsieMotion.JOLT_UP, 0f)
    private val JOLT_SX = floatArrayOf(1f, 0.97f, 1f)
    private val JOLT_SY = floatArrayOf(1f, 1.04f, 1f)
    private val PONDER = floatArrayOf(0f, EsieMotion.PONDER_DEG, 0f)
    private val SWAY = floatArrayOf(0f, EsieMotion.SWAY_DEG, 0f)
    private val BLINK_AT = floatArrayOf(0f, 0.9f, 0.94f, 1f)
    private val BLINK = floatArrayOf(0f, 0f, 1f, 0f)
    private val ARM_WAVE = floatArrayOf(2f, -5f, 2f)
    private val WIGGLE = floatArrayOf(0f, EsieMotion.WIGGLE_DEG, 0f)
    private val DRIP_AT = floatArrayOf(0f, 0.15f, 1f)
    private val DRIP_SCALE = floatArrayOf(0.6f, 1f, 1f)
    private val DRIP_TY = floatArrayOf(0f, 0f, EsieMotion.DRIP_FALL)
    private val DRIP_FADE_AT = floatArrayOf(0f, 0.15f, 0.8f, 1f)
    private val DRIP_FADE = floatArrayOf(0f, 1f, 1f, 0f)
    private val DOT_AT = floatArrayOf(0f, 0.4f, 1f)
    private val DOT_SCALE = floatArrayOf(0.6f, 1f, 0.6f)
    private val DOT_FADE = floatArrayOf(0.35f, 1f, 0.35f)

    /** Where in its cycle a blink starts: frames are needed from here to the end of the cycle. */
    val BLINK_START_MS: Long = (EsieMotion.BLINK_MS * 0.9f).toLong()

    fun fins(mood: EsieMood): EsieFins = when (mood) {
        EsieMood.THUMBS_UP -> EsieFins.THUMBS_UP
        // the approved happy render waves
        EsieMood.WAVE, EsieMood.HAPPY -> EsieFins.WAVE
        EsieMood.REST, EsieMood.SAD, EsieMood.THINKING, EsieMood.SURPRISED -> EsieFins.REST
    }

    fun expression(mood: EsieMood): EsieExpression = when (mood) {
        EsieMood.HAPPY -> EsieExpression.HAPPY
        EsieMood.SAD -> EsieExpression.SAD
        EsieMood.THINKING -> EsieExpression.THINKING
        EsieMood.SURPRISED -> EsieExpression.SURPRISED
        EsieMood.REST, EsieMood.THUMBS_UP, EsieMood.WAVE -> EsieExpression.NEUTRAL
    }

    /** The mouth of an expression when not talking. */
    fun restingMouth(expression: EsieExpression): String = when (expression) {
        EsieExpression.HAPPY -> "mouth_big"
        EsieExpression.SAD -> "mouth_frown"
        EsieExpression.THINKING -> "mouth_hmm"
        EsieExpression.SURPRISED -> "mouth_o"
        EsieExpression.NEUTRAL -> "mouth_smile"
    }

    /** The fins at [elapsedMs]: a limited wave rests its fins once its cycles are done (unless motion is reduced). */
    fun finsAt(spec: EsieSpec, elapsedMs: Long): EsieFins {
        val fins = fins(spec.mood)
        val cycles = spec.waveCycles
        return if (fins == EsieFins.WAVE && cycles != null && !spec.reduceMotion && elapsedMs >= cycles * EsieMotion.ARM_WAVE_MS) EsieFins.REST
        else fins
    }

    /** The switchable parts shown for [spec] at [elapsedMs]. */
    fun shownParts(spec: EsieSpec, elapsedMs: Long): Set<String> = frame(spec, elapsedMs).shown

    /** Whether [spec] holds its pose at [elapsedMs], only blinking ([SETTLED_MS]). */
    fun isSettled(spec: EsieSpec, elapsedMs: Long): Boolean = settles(spec) && elapsedMs >= SETTLED_MS

    private fun settles(spec: EsieSpec) = !spec.reduceMotion && !spec.talking

    /**
     * The time a cycle of [period] (starting at [offset]) shows at [t]: [t] itself, until the cycle's first start at or
     * after [SETTLE_MS], where it holds.
     */
    private fun held(settles: Boolean, t: Long, period: Long, offset: Long = 0L): Long {
        if (!settles || t < SETTLE_MS) return t
        val firstStart = SETTLE_MS + (((offset - SETTLE_MS) % period) + period) % period
        return minOf(t, firstStart)
    }

    /**
     * How long after a frame at [elapsedMs] the next one is due, or -1 when nothing changes any more (a still pose).
     * [FRAME_MS] while Esie moves; once settled, nothing until the next blink, then frames through it; under reduce
     * motion only a talking mouth swaps, on its own beat.
     */
    fun nextFrameDelayMs(spec: EsieSpec, elapsedMs: Long): Long {
        val t = maxOf(0L, elapsedMs)
        if (spec.reduceMotion) return if (spec.talking) EsieMotion.TALK_STEP_MS - t % EsieMotion.TALK_STEP_MS else -1
        if (!isSettled(spec, t)) return FRAME_MS
        val p = t % EsieMotion.BLINK_MS
        return if (p >= BLINK_START_MS) FRAME_MS else BLINK_START_MS - p
    }

    /** Esie for [spec] at [elapsedMs], allocated for tests and checks; renderers call [fill] with their buffer. */
    fun frame(spec: EsieSpec, elapsedMs: Long): EsieFrame = EsieFrame(EsieFrameBuffer().also { fill(spec, elapsedMs, it) })

    /** Writes Esie for [spec] at [elapsedMs] after she appeared into [out], without allocating. */
    fun fill(spec: EsieSpec, elapsedMs: Long, out: EsieFrameBuffer) {
        val t = if (spec.reduceMotion) 0L else maxOf(0L, elapsedMs)
        // once settling, each continuous cycle holds at its next start; one-off motions and the blink keep the real time
        val st = settles(spec)
        val still = spec.reduceMotion
        for (i in 0 until partCount) {
            out.shown[i] = false
            out.moved[i] = false
            out.fade[i] = 1f
        }
        val expression = expression(spec.mood)

        // what shows
        when (finsAt(spec, elapsedMs)) {
            EsieFins.THUMBS_UP -> { out.shown[FIN_BACK] = true; out.shown[FIN_FRONT] = true }
            EsieFins.WAVE -> out.shown[FIN_WAVE] = true
            EsieFins.REST -> out.shown[FIN_REST] = true
        }
        if (expression == EsieExpression.HAPPY) {
            // closed arc eyes replace the eyes; the head shade goes with them (approved happy render)
            out.shown[EYES_HAPPY] = true
        } else {
            out.shown[EYE_L] = true; out.shown[EYE_R] = true; out.shown[BODY_SHADE] = true
            when (expression) {
                EsieExpression.SAD -> { out.shown[LIDS_SAD] = true; out.shown[BROWS_SAD] = true; out.shown[TEAR] = true }
                EsieExpression.THINKING -> { out.shown[BROWS_THINK] = true; out.shown[THOUGHT] = true }
                EsieExpression.SURPRISED -> out.shown[BROWS_SURPRISED] = true
                else -> Unit
            }
        }
        if (spec.talking) {
            val step = (maxOf(0L, elapsedMs) / EsieMotion.TALK_STEP_MS).toInt()
            out.shown[TALK_INDEX[step % TALK_INDEX.size]] = true
        } else {
            out.shown[RESTING_MOUTH[expression.ordinal]] = true
        }

        // the whole body
        if (!still) {
            when (expression) {
                EsieExpression.HAPPY -> held(st, t, EsieMotion.BREATHE_MS, EsieMotion.HOP_MS).let { tb ->
                    if (tb < EsieMotion.HOP_MS) hop(out, tb) else breathe(out, tb - EsieMotion.HOP_MS)
                }
                EsieExpression.SAD -> {
                    val p = phase(held(st, t, EsieMotion.SIGH_MS), EsieMotion.SIGH_MS)
                    move(out, ROOT, ROOT_X, ROOT_Y, ty = keyframes(p, HALF, SIGH_TY, EsieMotion.EASE_IN_OUT),
                        sx = keyframes(p, HALF, SIGH_SX, EsieMotion.EASE_IN_OUT), sy = keyframes(p, HALF, SIGH_SY, EsieMotion.EASE_IN_OUT))
                }
                EsieExpression.THINKING ->
                    move(out, ROOT, ROOT_X, ROOT_Y, rot = keyframes(phase(held(st, t, EsieMotion.PONDER_MS), EsieMotion.PONDER_MS), HALF, PONDER, EsieMotion.EASE_IN_OUT))
                EsieExpression.SURPRISED -> held(st, t, EsieMotion.BREATHE_MS, EsieMotion.JOLT_MS).let { tb ->
                    if (tb < EsieMotion.JOLT_MS) jolt(out, tb) else breathe(out, tb - EsieMotion.JOLT_MS)
                }
                EsieExpression.NEUTRAL -> breathe(out, held(st, t, EsieMotion.BREATHE_MS))
            }
            // idle sways; the resting fin's CSS sway runs in reverse, the same curve for a symmetric swing
            move(out, TAIL_TIP, TAIL_X, TAIL_Y, rot = keyframes(phase(held(st, t, EsieMotion.SWAY_MS), EsieMotion.SWAY_MS), HALF, SWAY, EsieMotion.EASE_IN_OUT))
            move(out, FIN_REST, FIN_REST_X, FIN_REST_Y, rot = keyframes(phase(held(st, t, EsieMotion.REST_FIN_SWAY_MS), EsieMotion.REST_FIN_SWAY_MS), HALF, SWAY, EsieMotion.EASE_IN_OUT))
        }

        // blink: the lids come down from where they are parked (never under reduce motion)
        val closed = if (still) 0f else keyframes(phase(t, EsieMotion.BLINK_MS), BLINK_AT, BLINK, EsieMotion.EASE)
        for (k in LIDS.indices) move(out, LIDS[k], 0f, 0f, ty = LID_TRAVEL[k] * (1 - closed))

        // the waving arm and hand (still: the first keyframe, a raised hand)
        move(out, ARM, ARM_X, ARM_Y, rot = keyframes(phase(held(st, t, EsieMotion.ARM_WAVE_MS), EsieMotion.ARM_WAVE_MS), HALF, ARM_WAVE, EsieMotion.EASE_IN_OUT))
        // alternate: 12 to -16 in one half second, back in the next
        val handPhase = phase(held(st, t, EsieMotion.HAND_WAVE_MS * 2), EsieMotion.HAND_WAVE_MS * 2)
        val handTo = if (handPhase < 0.5f) EsieMotion.EASE_IN_OUT.ease(handPhase * 2) else 1 - EsieMotion.EASE_IN_OUT.ease(handPhase * 2 - 1)
        move(out, HAND, HAND_X, HAND_Y, rot = 12f + (-16f - 12f) * handTo)

        // pupils
        var lookX = 0f
        var lookY = 0f
        when (expression) {
            EsieExpression.SAD -> { lookX = EsieMotion.SAD_PUPILS.first; lookY = EsieMotion.SAD_PUPILS.second }
            EsieExpression.THINKING -> { lookX = EsieMotion.THINKING_PUPILS.first; lookY = EsieMotion.THINKING_PUPILS.second }
            EsieExpression.NEUTRAL -> if (spec.looking && !still) {
                val spots = EsieMotion.LOOK_SPOTS
                // held where the first spot is reached again (its easing done)
                val tm = held(st, t, EsieMotion.LOOK_STEP_MS * spots.size, EsieMotion.LOOK_EASE_MS)
                val step = (tm / EsieMotion.LOOK_STEP_MS).toInt()
                val to = spots[step % spots.size]
                val from = if (step == 0) to else spots[(step - 1) % spots.size]
                val p = EsieMotion.LOOK_EASE.ease((tm - step * EsieMotion.LOOK_STEP_MS).toFloat() / EsieMotion.LOOK_EASE_MS)
                lookX = from.first + (to.first - from.first) * p
                lookY = from.second + (to.second - from.second) * p
            }
            else -> Unit
        }
        for (k in PUPILS.indices) {
            if (expression == EsieExpression.SURPRISED) {
                move(out, PUPILS[k], PUPIL_X[k], PUPIL_Y[k], sx = EsieMotion.SURPRISED_PUPIL_SCALE, sy = EsieMotion.SURPRISED_PUPIL_SCALE)
            } else {
                move(out, PUPILS[k], 0f, 0f, tx = lookX, ty = lookY)
            }
        }

        // ears, each about its own bottom centre
        val earRotation = when {
            expression == EsieExpression.SAD -> EsieMotion.SAD_EAR_DEG
            expression == EsieExpression.HAPPY && !still && t < EsieMotion.WIGGLE_MS * EsieMotion.WIGGLES ->
                keyframes(phase(t, EsieMotion.WIGGLE_MS), HALF, WIGGLE, EsieMotion.EASE_IN_OUT)
            else -> 0f
        }
        if (earRotation != 0f) for (k in EARS.indices) move(out, EARS[k], EAR_X[k], EAR_Y[k], rot = earRotation)
        if (expression == EsieExpression.SURPRISED) move(out, EARS_GROUP, 0f, 0f, ty = EsieMotion.SURPRISED_EARS_UP)

        // the tear drips (still: hangs at the eye)
        if (expression == EsieExpression.SAD && !still) {
            // held at the start of a drip: the tear has run off and is not shown
            val p = phase(held(st, t, EsieMotion.DRIP_MS), EsieMotion.DRIP_MS)
            val scale = keyframes(p, DRIP_AT, DRIP_SCALE, EsieMotion.EASE_IN)
            move(out, TEAR, TEAR_X, TEAR_Y, ty = keyframes(p, DRIP_AT, DRIP_TY, EsieMotion.EASE_IN), sx = scale, sy = scale)
            out.fade[TEAR] = keyframes(p, DRIP_FADE_AT, DRIP_FADE, EsieMotion.EASE_IN)
        }

        // the thought dots pulse one after another
        if (expression == EsieExpression.THINKING && !still) {
            for (k in DOTS.indices) {
                val offset = k * EsieMotion.DOT_STAGGER_MS
                val p = phase(held(st, t, EsieMotion.DOT_MS, offset) - offset, EsieMotion.DOT_MS)
                val scale = keyframes(p, DOT_AT, DOT_SCALE, EsieMotion.EASE_IN_OUT)
                move(out, DOTS[k], DOT_X[k], DOT_Y[k], sx = scale, sy = scale)
                out.fade[DOTS[k]] = keyframes(p, DOT_AT, DOT_FADE, EsieMotion.EASE_IN_OUT)
            }
        }

        // part matrices, then per layer: opacity, the matrix before its clip and the one after
        for (i in 0 until partCount) partMatrix(out, i)
        val data = out.data
        for (l in chains.indices) {
            val chain = chains[l]
            val base = l * EsieFrameBuffer.STRIDE
            var alpha = layerOpacity[l]
            for (p in chain) {
                if (switchable[p] && !out.shown[p]) { alpha = 0f; break }
                alpha *= out.fade[p]
            }
            data[base] = alpha
            identity(data, base + EsieFrameBuffer.CLIP_MATRIX)
            identity(data, base + EsieFrameBuffer.DRAW_MATRIX)
            if (alpha <= 0f) continue
            val depth = clipDepth[l]
            for (k in chain.indices) {
                val target = if (k < depth) base + EsieFrameBuffer.CLIP_MATRIX else base + EsieFrameBuffer.DRAW_MATRIX
                multiplyInPlace(data, target, out.matrix, chain[k] * 6)
            }
        }
    }

    /**
     * The part of the art to show for [mood]: everything it draws over every cycle (the hop, the jolt, the swinging
     * hand, a wave that comes to rest), sampled from the rig itself. One frame per mood, so nothing jumps or clips.
     */
    fun viewport(mood: EsieMood): EsieRect = EsieViewports.ALL[mood.ordinal].let { EsieRect(it[0], it[1], it[2], it[3]) }

    /** Art units around the sampled drawing (antialiased edges, the gaps between samples). */
    const val VIEWPORT_MARGIN = 12f

    /** The time [viewport] samples: past every one-off motion and every cycle. */
    const val VIEWPORT_SAMPLE_MS = 7_200L

    /**
     * Samples [mood]'s viewport from the rig (thousands of frames, too slow for the first draw on the main thread):
     * the result is baked into [EsieViewports], and a test checks the two agree.
     */
    internal fun sampleViewport(mood: EsieMood): EsieRect {
        val buffer = EsieFrameBuffer()
        var box: EsieRect? = null
        for (spec in listOf(EsieSpec(mood, looking = true), EsieSpec(mood, waveCycles = 1))) {
            var t = 0L
            while (t <= VIEWPORT_SAMPLE_MS) {
                fill(spec, t, buffer)
                for (l in chains.indices) {
                    val r = drawnBox(buffer, l) ?: continue
                    box = box?.union(r) ?: r
                }
                t += 20
            }
        }
        return box!!.inset(VIEWPORT_MARGIN)
    }

    /** Where layer [l] of [buffer] can draw, in art coordinates (null when hidden): its hull, inside its clip. */
    internal fun drawnBox(buffer: EsieFrameBuffer, l: Int): EsieRect? {
        val data = buffer.data
        val base = l * EsieFrameBuffer.STRIDE
        if (data[base] <= 0f) return null
        val layer = EsieArtData.LAYERS[l]
        val full = FloatArray(6)
        for (k in 0 until 6) full[k] = data[base + EsieFrameBuffer.CLIP_MATRIX + k]
        multiplyInPlace(full, 0, data, base + EsieFrameBuffer.DRAW_MATRIX)
        var box = mapRect(full, 0, layer.path.hull.inset(layer.stroke?.width?.div(2) ?: 0f))
        for (clip in layer.clips) box = box.intersect(mapRect(data, base + EsieFrameBuffer.CLIP_MATRIX, EsieArt.clip(clip.id).hull))
        return box
    }

    /** Where [layer] can draw, untransformed: its path with half its stroke, inside its clips. */
    fun drawnHull(layer: EsieLayer): EsieRect {
        var hull = layer.path.hull.inset(layer.stroke?.width?.div(2) ?: 0f)
        for (clip in layer.clips) hull = hull.intersect(EsieArt.clip(clip.id).hull)
        return hull
    }

    private fun mapRect(m: FloatArray, o: Int, r: EsieRect): EsieRect {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var rr = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (k in 0 until 4) {
            val x = if (k and 1 == 0) r.left else r.right
            val y = if (k < 2) r.top else r.bottom
            val mx = m[o] * x + m[o + 2] * y + m[o + 4]
            val my = m[o + 1] * x + m[o + 3] * y + m[o + 5]
            l = minOf(l, mx); rr = maxOf(rr, mx); t = minOf(t, my); b = maxOf(b, my)
        }
        return EsieRect(l, t, rr, b)
    }

    private fun move(out: EsieFrameBuffer, i: Int, px: Float, py: Float, tx: Float = 0f, ty: Float = 0f, rot: Float = 0f, sx: Float = 1f, sy: Float = 1f) {
        out.moved[i] = true
        out.px[i] = px; out.py[i] = py; out.tx[i] = tx; out.ty[i] = ty; out.rot[i] = rot; out.sx[i] = sx; out.sy[i] = sy
    }

    private fun breathe(out: EsieFrameBuffer, t: Long) {
        val p = phase(t, EsieMotion.BREATHE_MS)
        move(out, ROOT, ROOT_X, ROOT_Y, sx = keyframes(p, HALF, BREATHE_SX, EsieMotion.EASE_IN_OUT), sy = keyframes(p, HALF, BREATHE_SY, EsieMotion.EASE_IN_OUT))
    }

    private fun hop(out: EsieFrameBuffer, t: Long) {
        val p = t.toFloat() / EsieMotion.HOP_MS
        move(out, ROOT, ROOT_X, ROOT_Y, ty = keyframes(p, HOP_AT, HOP_TY, EsieMotion.HOP_EASE),
            sx = keyframes(p, HOP_AT, HOP_SX, EsieMotion.HOP_EASE), sy = keyframes(p, HOP_AT, HOP_SY, EsieMotion.HOP_EASE))
    }

    private fun jolt(out: EsieFrameBuffer, t: Long) {
        val p = t.toFloat() / EsieMotion.JOLT_MS
        move(out, ROOT, ROOT_X, ROOT_Y, ty = keyframes(p, JOLT_AT, JOLT_TY, EsieMotion.JOLT_EASE),
            sx = keyframes(p, JOLT_AT, JOLT_SX, EsieMotion.JOLT_EASE), sy = keyframes(p, JOLT_AT, JOLT_SY, EsieMotion.JOLT_EASE))
    }

    /** translate(px + tx, py + ty) rotate scale translate(-px, -py) as a, b, c, d, e, f. */
    private fun partMatrix(out: EsieFrameBuffer, i: Int) {
        val m = out.matrix
        val o = i * 6
        if (!out.moved[i]) { identity(m, o); return }
        val r = out.rot[i] * (PI / 180).toFloat()
        val cs = cos(r)
        val sn = sin(r)
        val a = cs * out.sx[i]
        val b = sn * out.sx[i]
        val c = -sn * out.sy[i]
        val d = cs * out.sy[i]
        val px = out.px[i]
        val py = out.py[i]
        m[o] = a; m[o + 1] = b; m[o + 2] = c; m[o + 3] = d
        m[o + 4] = px + out.tx[i] - a * px - c * py
        m[o + 5] = py + out.ty[i] - b * px - d * py
    }

    private fun identity(m: FloatArray, o: Int) {
        m[o] = 1f; m[o + 1] = 0f; m[o + 2] = 0f; m[o + 3] = 1f; m[o + 4] = 0f; m[o + 5] = 0f
    }

    /** m (at [mo]) = m * n (at [no]), in place. */
    private fun multiplyInPlace(m: FloatArray, mo: Int, n: FloatArray, no: Int) {
        val a = m[mo]; val b = m[mo + 1]; val c = m[mo + 2]; val d = m[mo + 3]; val e = m[mo + 4]; val f = m[mo + 5]
        val a2 = n[no]; val b2 = n[no + 1]; val c2 = n[no + 2]; val d2 = n[no + 3]; val e2 = n[no + 4]; val f2 = n[no + 5]
        m[mo] = a * a2 + c * b2
        m[mo + 1] = b * a2 + d * b2
        m[mo + 2] = a * c2 + c * d2
        m[mo + 3] = b * c2 + d * d2
        m[mo + 4] = a * e2 + c * f2 + e
        m[mo + 5] = b * e2 + d * f2 + f
    }

    /** Position in a repeating cycle of [period], 0 until 1. */
    private fun phase(t: Long, period: Long): Float = (((t % period) + period) % period).toFloat() / period

    /** CSS keyframes: [values] at [offsets] (0 to 1), [easing] within each segment. */
    internal fun keyframes(p: Float, offsets: FloatArray, values: FloatArray, easing: CubicBezier): Float {
        if (p <= offsets[0]) return values[0]
        for (i in 0 until offsets.size - 1) {
            if (p <= offsets[i + 1]) {
                val span = offsets[i + 1] - offsets[i]
                val local = if (span <= 0f) 1f else (p - offsets[i]) / span
                return values[i] + (values[i + 1] - values[i]) * easing.ease(local)
            }
        }
        return values[values.size - 1]
    }
}
