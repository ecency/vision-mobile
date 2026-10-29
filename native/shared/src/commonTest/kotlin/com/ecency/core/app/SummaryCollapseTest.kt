package com.ecency.core.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RN profileView's summary collapse rules (dead zone, signed run, layout jumps, cooldown and recheck). */
class SummaryCollapseTest {
    /** Scrolls from the current offset to [to] in steps of [step], at [now]. */
    private fun SummaryCollapse.scrollTo(from: Double, to: Double, now: Long, step: Double = 10.0): Long? {
        var at = from
        var last: Long? = null
        val sign = if (to > from) 1 else -1
        while (at != to) {
            at = if (sign > 0) minOf(to, at + step) else maxOf(to, at - step)
            last = onScroll(at, now)
        }
        return last
    }

    @Test
    fun closesOnlyPastTheDeadZoneWithADownwardRun() {
        val c = SummaryCollapse()
        c.scrollTo(0.0, 80.0, 0)
        assertTrue(c.isOpen, "80 is still inside the dead zone")
        c.onScroll(90.0, 0)
        assertFalse(c.isOpen, "past 80 with a run of 90 down")
    }

    @Test
    fun aJumpPastTheThresholdIsALayoutBlipNotScrolling() {
        val c = SummaryCollapse()
        assertNull(c.onScroll(400.0, 0))
        assertTrue(c.isOpen, "one delta of 400 zeroes the run")
        c.onScroll(415.0, 0)
        assertTrue(c.isOpen, "a run of 15 is under 20")
        c.onScroll(425.0, 0)
        assertFalse(c.isOpen, "a run of 25 closes it")
    }

    @Test
    fun opensNearTheTopOrAfterAnUpwardRunMidList() {
        val c = SummaryCollapse()
        c.scrollTo(0.0, 2000.0, 0)
        assertFalse(c.isOpen)
        c.scrollTo(2000.0, 1950.0, 1000)
        assertFalse(c.isOpen, "50 up is not a reveal")
        c.onScroll(1940.0, 1000)
        assertTrue(c.isOpen, "60 up mid-list reveals it")
        c.scrollTo(1940.0, 1930.0, 2000)
        c.scrollTo(1930.0, 1945.0, 2000)
        assertTrue(c.isOpen, "a direction flip restarts the run: 15 down does not close")
        c.scrollTo(1945.0, 1965.0, 2000)
        assertFalse(c.isOpen, "a run of 20 down does")
        c.scrollTo(1965.0, 8.0, 3000, step = 300.0)
        assertTrue(c.isOpen, "back at the top")
    }

    @Test
    fun aToggleInsideTheCooldownWaitsAndTheRecheckSettlesIt() {
        val c = SummaryCollapse()
        c.scrollTo(0.0, 200.0, 1000)
        assertFalse(c.isOpen)
        // straight back to the top 300 ms after it closed: the open waits out the cooldown
        val wait = c.scrollTo(200.0, 0.0, 1300)
        assertFalse(c.isOpen)
        assertEquals(SummaryCollapse.COOLDOWN_MS - 300 + SummaryCollapse.RECHECK_SLACK_MS, wait)
        assertNull(c.recheck(1300 + wait!!))
        assertTrue(c.isOpen, "no further scroll event needed")
    }

    @Test
    fun aRecheckThatComesTooEarlyAsksAgain() {
        val c = SummaryCollapse()
        c.scrollTo(0.0, 200.0, 1000)
        c.scrollTo(200.0, 0.0, 1100)
        assertEquals(50L + SummaryCollapse.RECHECK_SLACK_MS, c.recheck(1500))
        assertFalse(c.isOpen)
    }

    @Test
    fun aTapOpenStampsTheCooldownSoTheNextScrollDoesNotCloseItAtOnce() {
        val c = SummaryCollapse()
        c.scrollTo(0.0, 2000.0, 0)
        assertTrue(c.expand(5000))
        assertFalse(c.expand(5000), "already open")
        assertTrue(c.isOpen)
        assertEquals(SummaryCollapse.COOLDOWN_MS - 100 + SummaryCollapse.RECHECK_SLACK_MS, c.scrollTo(2000.0, 2100.0, 5100))
        assertTrue(c.isOpen)
        assertNull(c.recheck(5600))
        assertFalse(c.isOpen)
    }

    @Test
    fun aTapCloseAtTheTopStaysClosedUntilTheReaderScrollsPastTheDeadZoneAndBack() {
        val c = SummaryCollapse()
        assertTrue(c.collapse(0))
        assertFalse(c.collapse(0))
        assertFalse(c.isOpen)
        c.onScroll(2.0, 1000)
        c.onScroll(0.0, 1000)
        assertFalse(c.isOpen, "the top does not undo the tap")
        c.scrollTo(0.0, 120.0, 2000)
        c.scrollTo(120.0, 0.0, 3000, step = 30.0)
        assertTrue(c.isOpen, "after scrolling past the dead zone the top opens it again")
        assertTrue(c.collapse(4000))
        c.onScroll(-70.0, 5000)
        assertTrue(c.isOpen, "a pull down past the reveal run opens it")
        c.expand(6000)
        assertTrue(c.isOpen)
    }

    @Test
    fun aListThatClampsToTheTopAfterTheHeaderClosedIsLayoutNotTheReader() {
        val c = SummaryCollapse()
        c.scrollTo(0.0, 150.0, 0)
        assertFalse(c.isOpen)
        // short content: the list got the header's room, no longer scrolls and clamps to 0
        assertNull(c.onScrollBy(0.0, 0.0, 2000))
        assertFalse(c.isOpen, "no drag: it stays closed (no open, shrink, close loop)")
        c.layout(0.0, scrollable = false, nowMillis = 2500)
        assertNull(c.recheck(3000))
        assertFalse(c.isOpen)
        c.onScrollBy(4.0, 4.0, 3000)
        assertFalse(c.isOpen, "a small drag at the top does not undo the hold")
        c.onScrollBy(0.0, -70.0, 4000)
        assertTrue(c.isOpen, "a real drag down (pull) reveals it")
    }

    @Test
    fun codeThatMovesAScrollableListToTheTopOpensItButAClampMidListDoesNot() {
        val c = SummaryCollapse()
        c.scrollTo(0.0, 2000.0, 0)
        assertFalse(c.isOpen)
        // the viewport grew near the end: a clamp upward is layout, never an upward run
        assertNull(c.layout(1800.0, scrollable = true, nowMillis = 1000))
        c.onScroll(1790.0, 1000)
        assertFalse(c.isOpen, "the clamp did not count towards a reveal run")
        // a status bar tap or scroll to top: the list lands at the top by code
        assertNull(c.layout(0.0, scrollable = true, nowMillis = 2000))
        assertTrue(c.isOpen)
        // a tap close at the top, then a relayout there (the card's own size change): it stays closed
        assertTrue(c.collapse(3000))
        assertNull(c.layout(0.0, scrollable = true, nowMillis = 4000))
        assertNull(c.recheck(5000))
        assertFalse(c.isOpen)
    }

    @Test
    fun aTabSwitchOpensItOverTheNewListAndStampsTheCooldown() {
        val c = SummaryCollapse()
        c.scrollTo(0.0, 2000.0, 0)
        assertFalse(c.isOpen)
        c.reset(5000)
        assertTrue(c.isOpen)
        // the new list's first moves inside the cooldown do not close it at once
        assertEquals(SummaryCollapse.COOLDOWN_MS - 100 + SummaryCollapse.RECHECK_SLACK_MS, c.scrollTo(0.0, 200.0, 5100))
        assertTrue(c.isOpen)
    }

    @Test
    fun exactDeltasOverrideTheOffsets() {
        val c = SummaryCollapse()
        // Android: the offset is an estimate that can jump; the run follows the exact deltas
        c.onScrollBy(500.0, 10.0, 0)
        assertTrue(c.isOpen, "a run of 10")
        c.onScrollBy(900.0, 15.0, 0)
        assertFalse(c.isOpen, "a run of 25 despite a 400 jump in the estimate")
    }
}
