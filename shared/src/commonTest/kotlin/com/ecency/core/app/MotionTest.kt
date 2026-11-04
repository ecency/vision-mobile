package com.ecency.core.app

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionTest {
    @Test
    fun levelFollowsReduceMotionAndDurationScale() {
        assertEquals(MotionLevel.FULL, MotionPolicy.level(reduceMotion = false))
        assertEquals(MotionLevel.REDUCED, MotionPolicy.level(reduceMotion = true))
        assertEquals(MotionLevel.NONE, MotionPolicy.level(reduceMotion = false, durationScale = 0f))
        // off wins over reduced
        assertEquals(MotionLevel.NONE, MotionPolicy.level(reduceMotion = true, durationScale = 0f))
        // a faster or slower scale is still full motion (the platform scales durations itself)
        assertEquals(MotionLevel.FULL, MotionPolicy.level(reduceMotion = false, durationScale = 0.5f))
        assertEquals(MotionLevel.FULL, MotionPolicy.level(reduceMotion = false, durationScale = 10f))
        assertEquals(MotionLevel.NONE, MotionPolicy.level(reduceMotion = false, durationScale = -1f))
        assertEquals(MotionLevel.NONE, MotionPolicy.level(reduceMotion = false, durationScale = Float.NaN))
    }

    @Test
    fun reducedMotionCrossfadesAndDropsMoves() {
        assertEquals(PageMotion.SHARED_AXIS, MotionPolicy.page(MotionLevel.FULL))
        assertEquals(PageMotion.FADE, MotionPolicy.page(MotionLevel.REDUCED))
        assertEquals(PageMotion.NONE, MotionPolicy.page(MotionLevel.NONE))
        assertEquals(MotionTokens.PAGE_MS, MotionPolicy.pageMillis(MotionLevel.FULL))
        assertEquals(MotionTokens.PAGE_FADE_MS, MotionPolicy.pageMillis(MotionLevel.REDUCED))
        assertEquals(0, MotionPolicy.pageMillis(MotionLevel.NONE))
        // fades stay under reduce motion, nothing when off
        assertEquals(MotionTokens.SHORT_MS, MotionPolicy.fadeMillis(MotionLevel.REDUCED))
        assertEquals(0, MotionPolicy.fadeMillis(MotionLevel.NONE, MotionTokens.MEDIUM_MS))
        // the launch screen fades like RN's bootsplash (a fade, so kept under reduce motion), cut when motion is off
        assertEquals(220, MotionTokens.SPLASH_FADE_MS)
        assertEquals(220, MotionPolicy.fadeMillis(MotionLevel.REDUCED, MotionTokens.SPLASH_FADE_MS))
        assertEquals(0, MotionPolicy.fadeMillis(MotionLevel.NONE, MotionTokens.SPLASH_FADE_MS))
        for (level in MotionLevel.entries) {
            val full = level == MotionLevel.FULL
            assertEquals(full, MotionPolicy.pops(level), "pops at $level")
            assertEquals(full, MotionPolicy.shimmers(level), "shimmer at $level")
            assertEquals(full, MotionPolicy.pressExpands(level), "press at $level")
        }
    }

    @Test
    fun tokensAreOrderedAndShort() {
        with(MotionTokens) {
            assertTrue(PRESS_MS < SHORT_MS && SHORT_MS < MEDIUM_MS && MEDIUM_MS < PAGE_MS)
            assertTrue(PAGE_FADE_MS < PAGE_MS)
            // nothing a tap waits on runs longer than a page move
            assertTrue(PAGE_MS <= 300 && SHAKE_MS <= 500)
            assertTrue(POP_SCALE > 1f && PRESS_SCALE < 1f && PRESS_SCALE > 0.9f)
            assertTrue(FADE_THROUGH_SCALE in 0.9f..0.95f)
            assertTrue(POP.dampingRatio < 1f, "the pop overshoots")
            assertEquals(1f, SETTLE.dampingRatio, "settle does not bounce")
        }
    }

    @Test
    fun springResponseMatchesSwiftUi() {
        // SwiftUI's response is the undamped period: 2 pi / sqrt(k) for unit mass
        assertTrue(abs(SpringToken(1f, 400f).responseSeconds - 0.31416) < 1e-4)
        assertTrue(abs(MotionTokens.POP.responseSeconds - 0.28099) < 1e-4)
        // stiffer is quicker
        assertTrue(MotionTokens.SETTLE.responseSeconds < MotionTokens.POP.responseSeconds)
    }

    @Test
    fun shakeStartsAndEndsAtRestAndFades() {
        val frames = MotionPolicy.shakeKeyframes(3)
        assertEquals(listOf(0f, -1f, 1f), frames.take(3))
        assertEquals(0f, frames.last())
        assertEquals(2 + 2 * 3, frames.size)
        // each swing is no wider than the one before
        val swings = frames.drop(1).dropLast(1).map { abs(it) }
        assertTrue(swings.zipWithNext().all { (a, b) -> b <= a })
        assertEquals(listOf(0f), MotionPolicy.shakeKeyframes(0))
    }

    @Test
    fun pinShakesOnlyWhenAFullEntryIsRefused() {
        val empty = PinPrompt(PinPurpose.UNLOCK, PinStep.CURRENT, "Enter PIN")
        val full = empty.copy(digits = com.ecency.core.secure.PinLock.PIN_LENGTH - 1)
        val refused = empty.copy(error = "Invalid PIN", failedAttempts = 1)
        assertTrue(MotionPolicy.pinRejected(full, refused))
        assertTrue(MotionPolicy.pinRejected(empty.copy(isChecking = true, digits = 4), refused))
        assertTrue(!MotionPolicy.pinRejected(null, refused))
        assertTrue(!MotionPolicy.pinRejected(refused, refused.copy(digits = 1)))
        assertTrue(!MotionPolicy.pinRejected(refused.copy(digits = 1), refused))
        assertTrue(!MotionPolicy.pinRejected(full, refused.copy(isChecking = true)))
        assertTrue(!MotionPolicy.pinRejected(full, empty))
    }

    @Test
    fun hapticsAreLightAndFew() {
        assertEquals(HapticKind.LIGHT_IMPACT, MotionPolicy.haptic(HapticEvent.VOTE))
        assertEquals(HapticKind.LIGHT_IMPACT, MotionPolicy.haptic(HapticEvent.TOGGLE))
        assertEquals(HapticKind.SUCCESS, MotionPolicy.haptic(HapticEvent.SUCCESS))
        assertEquals(HapticKind.ERROR, MotionPolicy.haptic(HapticEvent.ERROR))
        assertEquals(HapticKind.SELECTION, MotionPolicy.haptic(HapticEvent.SELECTION))
    }
}
