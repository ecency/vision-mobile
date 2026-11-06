package com.ecency.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RN's welcome launch animation (src/screens/launch), drawn natively (README "Welcome and terms, register"). */
class LaunchRevealTest {
    private fun at(ms: Long) = LaunchReveal.frame(ms)

    @Test
    fun itRunsRnsThreeAndAHalfSecondsOnlyAtFullMotion() {
        // RN WelcomeScreen: setTimeout(() => setShowAnimation(false), 3550); 71 frames at 20 fps
        assertEquals(3550L, LaunchReveal.DURATION_MS)
        assertEquals(150f, LaunchReveal.SIZE)
        assertTrue(LaunchReveal.shows(MotionLevel.FULL))
        assertFalse(LaunchReveal.shows(MotionLevel.REDUCED), "Reduce Motion skips it")
        assertFalse(LaunchReveal.shows(MotionLevel.NONE), "animations off skip it")
    }

    @Test
    fun eachWelcomeLineHasRnsIcon() {
        // RN _renderInfo('question' | 'emotsmile' | 'people', ...), SimpleLineIcons at 30
        assertEquals(WelcomeCopy.LINES.size, WelcomeArt.LINE_ICONS.size)
        assertEquals(listOf(AppIcon.HELP, AppIcon.SMILE, AppIcon.GROUP), WelcomeArt.LINE_ICONS)
        assertEquals(30, WelcomeArt.ICON_SIZE)
    }

    @Test
    fun theOuterSweepDrawsFirstThenTheMarkFillsInTwoParts() {
        val sweeps = LaunchReveal.sweeps
        assertEquals(listOf(LaunchReveal.Area.OUTSIDE_MARK, LaunchReveal.Area.LOWER, LaunchReveal.Area.UPPER), sweeps.map { it.area })
        // the Lottie stroke widths (238 and 129) at the layers' 59 % scale
        assertEquals(238f * 0.59f, sweeps[0].width, 0.01f)
        assertEquals(129f * 0.59f, sweeps[1].width, 0.01f)
        assertEquals(129f * 0.59f, sweeps[2].width, 0.01f)

        val start = at(0)
        assertEquals(listOf(0f, 0f, 0f), start.map { it.trimEnd })
        assertEquals(1f, start[0].alpha)
        // frame 13 (650 ms): the outer sweep is under way, the mark not started
        val early = at(650)
        assertTrue(early[0].trimEnd in 0.5f..0.99f, "${early[0].trimEnd}")
        assertEquals(0f, early[1].trimEnd)
        assertEquals(0f, early[2].trimEnd)
        // frame 26 (1300 ms): the outer sweep is complete; frame 33 (1650 ms) it has faded out
        assertEquals(1f, at(1300)[0].trimEnd, 0.001f)
        assertEquals(0f, at(1650)[0].alpha, 0.001f)
        // frame 40 (2000 ms): both halves of the mark are filling, the lower one (started at frame 23) ahead
        val mid = at(2000)
        assertTrue(mid[1].trimEnd > mid[2].trimEnd && mid[2].trimEnd > 0f, "${mid.map { it.trimEnd }}")
        // frame 61 (3050 ms) on: the mark is whole and held until the screen shows
        listOf(3050L, 3549L, 9000L).forEach { ms ->
            val end = at(ms)
            assertEquals(listOf(1f, 1f, 1f), end.map { it.trimEnd }, "at $ms")
            assertEquals(0f, end[0].alpha)
            assertEquals(1f, end[1].alpha)
            assertEquals(1f, end[2].alpha)
        }
    }

    @Test
    fun eachSweepOnlyGrows() {
        var last = at(0)
        for (ms in 0L..3550L step 10) {
            val now = at(ms)
            now.indices.forEach { i -> assertTrue(now[i].trimEnd >= last[i].trimEnd - 1e-6f, "sweep $i at $ms") }
            last = now
        }
    }

    @Test
    fun theShapesAreRnsInTheCompositionsBox() {
        assertEquals(500f, LaunchReveal.VIEW_BOX)
        val all = LaunchReveal.mark + listOf(LaunchReveal.upperArea, LaunchReveal.lowerArea) + LaunchReveal.sweeps.map { it.path }
        all.forEach { p ->
            assertEquals(0, (p.size - 2) % 6, "a start point and whole cubic segments")
        }
        // the mark sits inside the box (its outline's points; control points may stand outside): inner and outer outline
        assertEquals(2, LaunchReveal.mark.size)
        LaunchReveal.mark.forEach { p ->
            val points = listOf(0) + (1..(p.size - 2) / 6).map { 6 * it - 2 + 2 }
            points.forEach { i -> assertTrue(p[i] in 0f..500f && p[i + 1] in 0f..500f, "${p[i]}, ${p[i + 1]}") }
        }
    }
}
