package com.ecency.core.esie

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EsieTest {
    @Test
    fun everyLayerPathRoundTrips() {
        for (layer in EsieArt.layers) assertEquals(layer.d, layer.path.toSvg(), "layer ${layer.part}")
        for (id in listOf("cBody", "cMuzzle", "c_eye_l", "c_eye_r")) assertTrue(EsieArt.clip(id).ops.isNotEmpty())
    }

    @Test
    fun pathParserReadsEveryCommandAndRejectsTheRest() {
        val p = EsiePath.parse("M1,2 3,4L5,6C7,8 9,10 11,12Q13,14 15,16Z")
        assertEquals(listOf(EsiePath.MOVE, EsiePath.LINE, EsiePath.LINE, EsiePath.CUBIC, EsiePath.QUAD, EsiePath.CLOSE), p.ops.toList())
        assertEquals((1..16).map { it.toFloat() }, p.coords.toList())
        assertEquals("M1.0,2.0L3.0,4.0L5.0,6.0C7.0,8.0 9.0,10.0 11.0,12.0Q13.0,14.0 15.0,16.0Z", p.toSvg())
        assertEquals(EsieRect(1f, 2f, 15f, 16f), p.hull)
        assertEquals("M-1.5,0.3", EsiePath.parse("M-1.5,.3").toSvg())
        assertFailsWith<IllegalArgumentException> { EsiePath.parse("M1,2 A3,4 0 0 1 5,6") }
        assertFailsWith<IllegalArgumentException> { EsiePath.parse("M1,2 L3") }
        assertFailsWith<IllegalArgumentException> { EsiePath.parse("1,2") }
    }

    @Test
    fun everyPartOfTheMasterIsThere() {
        val expected = listOf(
            "esie", "tail", "tail_body", "body", "body_fill", "body_shade", "tail_tip",
            "eye_l", "eye_l_white", "eye_l_pupil", "eye_l_shine", "eye_l_lid", "eye_r", "eye_r_white", "eye_r_pupil", "eye_r_shine", "eye_r_lid",
            "eyes_happy", "lids_sad", "brows_think", "brows_surprised", "brows_sad",
            "mouth", "mouth_smile", "mouth_open", "mouth_o", "mouth_big", "mouth_frown", "mouth_hmm", "mouth_flat",
            "ears", "ear_l", "ear_r", "belly", "navel", "fin_back", "fin_wave", "fin_wave_arm", "fin_wave_hand", "fin_rest",
            "muzzle", "muzzle_fill", "muzzle_shade", "blush_r", "fin_front", "tear", "thought", "thought_1", "thought_2", "thought_3",
        )
        assertEquals(expected.toSet(), EsieArt.parts)
        for (part in EsieRig.SWITCHABLE + "esie") assertTrue(part in EsieArt.parts, part)
        // z-order of the master: tail first, thought dots last; the waving arm and resting fin under the muzzle
        val order = EsieArt.layers.map { it.part }
        assertEquals("tail_body", order.first())
        assertEquals("thought_3", order.last())
        assertTrue(order.indexOf("fin_wave_arm") < order.indexOf("muzzle_fill"))
        assertTrue(order.indexOf("fin_rest") < order.indexOf("muzzle_fill"))
        assertTrue(order.indexOf("muzzle_fill") < order.indexOf("fin_front"))
        assertTrue(order.indexOf("body_fill") < order.indexOf("eye_l_white"))
    }

    @Test
    fun clipsGradientsAndOpacityMatchTheMaster() {
        for (layer in EsieArt.layers) for (clip in layer.clips) {
            assertTrue(clip.depth in 1..layer.chain.size, "clip depth of ${layer.part}")
            EsieArt.clip(clip.id)
        }
        val lids = EsieArt.layers.filter { it.part == "eye_l_lid" }
        // the eyelid is clipped by the eye white after the eye's transform, before its own (so it slides inside)
        assertEquals(listOf(EsieClip(2, "c_eye_l")), lids.first().clips)
        // the head shade's four shapes are one layer, so the group's 0.55 applies once where they overlap (as SVG)
        val shade = EsieArt.layers.single { it.part == "body_shade" }
        assertEquals(4, shade.path.ops.count { it == EsiePath.MOVE })
        assertEquals(0.55f, shade.opacity)
        assertEquals(listOf(EsieClip(3, "cBody")), shade.clips)
        // gradients are in the shape's bounding box: the body's radial gradient is an ellipse over its box
        val body = EsieArt.layers.single { it.part == "body_fill" }.fill as EsieGradient
        assertTrue(body.radial)
        val box = EsieArt.bounds("body_fill")
        assertTrue(abs(body.matrix[0] - box.width) < 2 && abs(body.matrix[3] - box.height) < 2)
        assertEquals(0xFF1F66E8, body.colors.first())
        // the mirrored resting fin keeps its gradient on the mirrored shape
        val rest = EsieArt.layers.single { it.part == "fin_rest" }.fill as EsieGradient
        assertTrue(rest.matrix[0] < 0, "mirrored")
        assertEquals(-425f, EsieArt.lidTravel("eye_l_lid"))
    }

    @Test
    fun moodsShowTheirParts() {
        fun shown(mood: EsieMood) = EsieRig.shownParts(EsieSpec(mood), 0)
        val eyes = setOf("eye_l", "eye_r", "body_shade")
        assertEquals(setOf("fin_rest", "mouth_smile") + eyes, shown(EsieMood.REST))
        assertEquals(setOf("fin_back", "fin_front", "mouth_smile") + eyes, shown(EsieMood.THUMBS_UP))
        assertEquals(setOf("fin_wave", "mouth_smile") + eyes, shown(EsieMood.WAVE))
        assertEquals(setOf("fin_wave", "eyes_happy", "mouth_big"), shown(EsieMood.HAPPY))
        assertEquals(setOf("fin_rest", "lids_sad", "brows_sad", "tear", "mouth_frown") + eyes, shown(EsieMood.SAD))
        assertEquals(setOf("fin_rest", "brows_think", "thought", "mouth_hmm") + eyes, shown(EsieMood.THINKING))
        assertEquals(setOf("fin_rest", "brows_surprised", "mouth_o") + eyes, shown(EsieMood.SURPRISED))
        for (mood in EsieMood.entries) {
            val frame = EsieRig.frame(EsieSpec(mood), 0)
            assertEquals(1, EsieRig.MOUTHS.count { it in frame.shown }, "one mouth for $mood")
            // parts that never switch always show
            assertTrue(EsieArt.layers.filter { layer -> layer.chain.none { it in EsieRig.SWITCHABLE } }.all(frame::isVisible))
        }
        assertEquals(EsieFins.REST, EsieRig.frame(EsieSpec(EsieMood.SAD), 0).fins)
        assertEquals(EsieFins.WAVE, EsieRig.frame(EsieSpec(EsieMood.HAPPY), 0).fins)
    }

    @Test
    fun talkingCyclesTheMouthInAnyMood() {
        val spec = EsieSpec(EsieMood.SAD, talking = true)
        val mouths = (0 until 6).map { EsieRig.frame(spec, it * EsieMotion.TALK_STEP_MS + 10).mouth }
        assertEquals(EsieMotion.TALK_MOUTHS, mouths)
        // instant swaps survive reduce motion
        assertEquals("mouth_o", EsieRig.frame(spec.copy(reduceMotion = true), EsieMotion.TALK_STEP_MS + 1).mouth)
        assertTrue(spec.copy(reduceMotion = true).animates)
        assertTrue(!EsieSpec(EsieMood.SAD, reduceMotion = true).animates)
    }

    @Test
    fun aLimitedWaveComesToRest() {
        val spec = EsieSpec(EsieMood.WAVE, waveCycles = 3)
        assertEquals(EsieFins.WAVE, EsieRig.frame(spec, 2999).fins)
        assertEquals(EsieFins.REST, EsieRig.frame(spec, 3000).fins)
        assertEquals(EsieFins.WAVE, EsieRig.frame(EsieSpec(EsieMood.WAVE), 60_000).fins)
        // reduced motion keeps the still greeting
        assertEquals(EsieFins.WAVE, EsieRig.frame(spec.copy(reduceMotion = true), 60_000).fins)
    }

    @Test
    fun happyHopsOnceThenBreathes() {
        val spec = EsieSpec(EsieMood.HAPPY)
        val top = EsieRig.frame(spec, 240).transform("esie")!!
        assertTrue(top.ty < -35f, "up at 40 %: ${top.ty}")
        assertTrue(abs(EsieRig.frame(spec, 600).transform("esie")!!.ty) < 0.01f)
        assertTrue(abs(EsieRig.frame(spec, 600 + 240).transform("esie")!!.ty) < 0.01f, "one hop")
        // the ears wiggle three times, then settle
        assertNotNull(EsieRig.frame(spec, 250).transform("ear_l"))
        assertNull(EsieRig.frame(spec, 1600).transform("ear_l"))
    }

    @Test
    fun blinkClosesTheLidsBriefly() {
        val spec = EsieSpec(EsieMood.REST)
        assertEquals(-425f, EsieRig.frame(spec, 1000).transform("eye_l_lid")!!.ty)
        assertTrue(abs(EsieRig.frame(spec, 3760).transform("eye_l_lid")!!.ty) < 1f, "closed at 94 %")
        assertEquals(-406f, EsieRig.frame(spec, 3999).transform("eye_r_lid")!!.ty, 1f)
    }

    @Test
    fun reducedMotionIsAStillPose() {
        for (mood in EsieMood.entries) {
            val spec = EsieSpec(mood, waveCycles = 2, looking = true, reduceMotion = true)
            val a = EsieRig.frame(spec, 0)
            val b = EsieRig.frame(spec, 1234)
            for (part in EsieArt.parts) assertEquals(a.transform(part), b.transform(part), "$mood $part")
            assertEquals(a.shown, b.shown)
            assertNull(a.transform("esie"), "no breathe, hop or sigh for $mood")
            assertNull(a.transform("tail_tip"), "no sway")
            assertNull(a.transform("tear"), "no drip")
            // the lids stay open
            assertEquals(-425f, a.transform("eye_l_lid")!!.ty)
        }
    }

    @Test
    fun pivotsAreInsideTheirParts() {
        val times = listOf(0L, 130L, 240L, 777L, 1900L, 3760L)
        for (mood in EsieMood.entries) for (t in times) {
            val frame = EsieRig.frame(EsieSpec(mood, looking = true), t)
            for (part in EsieArt.parts) {
                val tr = frame.transform(part) ?: continue
                if (tr.rotation == 0f && tr.sx == 1f && tr.sy == 1f) continue
                assertTrue(EsieArt.bounds(part).inset(1f).contains(tr.px, tr.py), "pivot of $part ($mood at $t)")
            }
        }
    }

    @Test
    fun theViewportHoldsEverythingTheMoodShowsOverWholeCycles() {
        for (mood in EsieMood.entries) {
            val view = EsieRig.viewport(mood)
            // off the viewport's own sampling grid, through the hop, the jolt, the waves and the drips
            for (spec in listOf(EsieSpec(mood, looking = true), EsieSpec(mood, waveCycles = 2), EsieSpec(mood, talking = true))) {
                var t = 7L
                while (t < 8_000L) {
                    val frame = EsieRig.frame(spec, t)
                    for (layer in EsieArt.layers) {
                        val box = frame.drawnBox(layer) ?: continue
                        assertTrue(view.contains(box.left, box.top) && view.contains(box.right, box.bottom), "${layer.part} in $mood at $t: $box outside $view")
                    }
                    t += 37
                }
            }
        }
        // the hop lifts the head above where it rests, and the frame has room for it
        val restingTop = EsieRig.frame(EsieSpec(EsieMood.HAPPY), 1_000).drawnBox(EsieArt.layers.single { it.part == "body_fill" })!!.top
        val hopTop = EsieRig.frame(EsieSpec(EsieMood.HAPPY), 240).drawnBox(EsieArt.layers.single { it.part == "body_fill" })!!.top
        assertTrue(hopTop < restingTop - 30)
        assertTrue(EsieRig.viewport(EsieMood.HAPPY).top <= hopTop)
        // resting Esie is framed without the waving arm's room, so she sits centred
        assertTrue(EsieRig.viewport(EsieMood.REST).width < EsieRig.viewport(EsieMood.WAVE).width - 200)
    }

    @Test
    fun anUnchangedEsieSettlesToBlinksOnly() {
        val spec = EsieSpec(EsieMood.SAD)
        assertEquals(EsieRig.FRAME_MS, EsieRig.nextFrameDelayMs(spec, 0))
        // settling: every cycle runs on to its next start, so frames go on until the longest one is held
        assertEquals(EsieRig.FRAME_MS, EsieRig.nextFrameDelayMs(spec, EsieRig.SETTLED_MS - 1))
        // settled: nothing until the next blink (90 % of its 4 s cycle), then frames through it
        assertEquals(EsieRig.BLINK_START_MS - 2_000, EsieRig.nextFrameDelayMs(spec, 38_000))
        assertEquals(EsieRig.FRAME_MS, EsieRig.nextFrameDelayMs(spec, 39_700))
        // the continuous motion holds, the lids still blink
        val a = EsieRig.frame(spec, 35_000)
        val b = EsieRig.frame(spec, 37_000)
        for (part in listOf("esie", "tail_tip", "fin_rest", "tear")) assertEquals(a.transform(part), b.transform(part), part)
        assertTrue(abs(EsieRig.frame(spec, 35_760).transform("eye_l_lid")!!.ty) < 1f, "still blinks")
        // each cycle holds at its start, so no jump: the sigh (3.6 s) next starts at 32.4 s
        val before = EsieRig.frame(spec, 32_399).transform("esie")!!
        val at = EsieRig.frame(spec, 32_400).transform("esie")!!
        assertTrue(abs(before.ty - at.ty) < 0.01f && abs(at.ty) < 0.01f && abs(at.sy - 1f) < 0.0001f, "$before $at")
        // the tear runs off instead of hanging mid-drip
        val tear = EsieArt.layers.single { it.part == "tear" }
        assertEquals(0f, EsieRig.frame(spec, 40_000).alpha(tear))
        assertTrue(EsieRig.frame(spec, 29_000).alpha(tear) > 0f || EsieRig.frame(spec, 29_300).alpha(tear) > 0f)
        // the thought dots hold even (each at its own start), not at mixed sizes
        val thinking = EsieRig.frame(EsieSpec(EsieMood.THINKING), 40_000)
        val dots = listOf("thought_1", "thought_2", "thought_3").map { thinking.transform(it)!!.sx }
        assertTrue(dots.all { abs(it - 0.6f) < 0.001f }, "$dots")
        // over the next 30 s the settled Esie draws a fraction of the 900 frames a moving one would
        var t = EsieRig.SETTLE_MS
        var frames = 0
        while (t < 60_000) { t += EsieRig.nextFrameDelayMs(spec, t); frames++ }
        assertTrue(frames < 260, "$frames frames")
        // talking never settles; under reduce motion nothing runs except a talking mouth on its beat
        assertEquals(EsieRig.FRAME_MS, EsieRig.nextFrameDelayMs(spec.copy(talking = true), 60_000))
        assertEquals(-1L, EsieRig.nextFrameDelayMs(spec.copy(reduceMotion = true), 0))
        assertEquals(EsieMotion.TALK_STEP_MS - 40, EsieRig.nextFrameDelayMs(spec.copy(reduceMotion = true, talking = true), 180))
    }

    @Test
    fun aFrameBufferHoldsOpacityAndMatrices() {
        val buffer = EsieFrameBuffer()
        EsieRig.fill(EsieSpec(EsieMood.HAPPY), 240, buffer)
        val layers = EsieArt.layers
        val eye = layers.indexOfFirst { it.part == "eye_l_white" }
        assertEquals(0f, buffer.alpha(eye), "hidden while happy")
        val body = layers.indexOfFirst { it.part == "body_fill" }
        assertEquals(1f, buffer.alpha(body))
        // mid hop: the body's matrix lifts it (f < 0), and the same buffer refills for another mood
        assertTrue(buffer.data[body * EsieFrameBuffer.STRIDE + EsieFrameBuffer.DRAW_MATRIX + 5] < -30f)
        EsieRig.fill(EsieSpec(EsieMood.REST), 0, buffer)
        assertEquals(1f, buffer.alpha(eye))
        assertEquals(0f, buffer.data[body * EsieFrameBuffer.STRIDE + EsieFrameBuffer.DRAW_MATRIX + 5])
        // a clipped lid: the eye's transform before the clip, its own (parked up) after
        val lid = layers.indexOfFirst { it.part == "eye_l_lid" }
        assertEquals(-425f, buffer.data[lid * EsieFrameBuffer.STRIDE + EsieFrameBuffer.DRAW_MATRIX + 5])
        assertEquals(0f, buffer.data[lid * EsieFrameBuffer.STRIDE + EsieFrameBuffer.CLIP_MATRIX + 5])
    }

    @Test
    fun timingFunctionsFollowCss() {
        assertEquals(0.5f, EsieMotion.EASE_IN_OUT.ease(0.5f), 0.001f)
        assertEquals(0f, EsieMotion.EASE.ease(0f))
        assertEquals(1f, EsieMotion.EASE.ease(1f))
        // the hop's easing overshoots
        assertTrue((1..99).any { EsieMotion.HOP_EASE.ease(it / 100f) > 1f })
        assertEquals(5f, EsieRig.keyframes(0.25f, floatArrayOf(0f, 0.5f, 1f), floatArrayOf(0f, 10f, 0f), CubicBezier(0f, 0f, 1f, 1f)), 0.01f)
    }
}

class EsiePlacementTest {
    @Test
    fun placementsUseTheApprovedMoods() {
        assertEquals("thumbs up", EsieMood.THUMBS_UP.label)
        assertEquals("rest", EsieMood.REST.label)
        assertEquals(EsieMood.REST, EsiePlacement.EMPTY.mood)
        assertEquals(EsieMood.SAD, EsiePlacement.ERROR.mood)
        assertEquals(EsieMood.HAPPY, EsiePlacement.SUCCESS.mood)
        assertEquals(EsieMood.WAVE, EsiePlacement.WELCOME.mood)
        assertEquals(EsieMood.WAVE, EsiePlacement.LOGIN.mood)
        assertEquals(EsieMood.THUMBS_UP, EsiePlacement.PROMO.mood)
        for (p in EsiePlacement.entries) assertTrue(p.height in 96..160, "modest size for $p")
        // greetings wave a few cycles, then rest
        assertEquals(EsieFins.REST, EsieRig.frame(EsiePlacement.WELCOME.spec(reduceMotion = false), 10_000).fins)
        assertNull(EsiePlacement.EMPTY.waveCycles)
        assertTrue(EsiePlacement.ERROR.spec(reduceMotion = true).reduceMotion)
    }
}

class EsieViewportsTest {
    /** The baked viewports ([EsieViewports]) are what the rig samples; on a mismatch the message is the new file body. */
    @Test
    fun bakedViewportsMatchTheSampledOnes() {
        val sampled = EsieMood.entries.map { EsieRig.sampleViewport(it) }
        val code = sampled.zip(EsieMood.entries).joinToString("\n") { (r, m) ->
            "        floatArrayOf(${r.left}f, ${r.top}f, ${r.right}f, ${r.bottom}f), // ${m.name}"
        }
        for ((i, mood) in EsieMood.entries.withIndex()) {
            val baked = EsieRig.viewport(mood)
            val r = sampled[i]
            val close = abs(baked.left - r.left) < 0.5f && abs(baked.top - r.top) < 0.5f && abs(baked.right - r.right) < 0.5f && abs(baked.bottom - r.bottom) < 0.5f
            assertTrue(close, "regenerate EsieViewports.ALL with:\n$code")
        }
    }
}
