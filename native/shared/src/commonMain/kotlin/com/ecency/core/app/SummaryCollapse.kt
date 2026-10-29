package com.ecency.core.app

import kotlin.math.abs

/**
 * RN profileView's summary collapse, also used for the community Details card: the header above a list closes
 * as the reader scrolls down and opens again near the top, or mid-list after a deliberate drag upward (#1915).
 *
 * A dead zone and a cooldown keep it from oscillating: it closes only once scrolled past [COLLAPSE_AT] with a
 * downward run of [COLLAPSE_RUN], opens within [EXPAND_AT] of the top or after an upward run of [REVEAL_RUN], and
 * ignores further flips for [COOLDOWN_MS] after a toggle. The run is signed (positive down), sums same-direction
 * deltas and restarts when the direction flips; a single delta of [LAYOUT_JUMP] or more is a layout blip (tab
 * switch, reflow), not the reader, and zeroes it. Offsets are in points (dp) from the top, as RN's.
 *
 * Pure: every call passes the time. A wanted toggle inside the cooldown returns the milliseconds to wait; the
 * platform then calls [recheck] with the time it waited for, so returning to the top still settles without a
 * further scroll event (RN's recheck timer).
 */
class SummaryCollapse {
    var isOpen: Boolean = true
        private set
    private var lastToggleAt: Long? = null
    private var lastOffset = 0.0
    private var run = 0.0
    /** Closed by a tap: the top does not open it again until the reader has scrolled past the dead zone. */
    private var heldClosed = false

    /** A new scroll offset; the delta is the change since the last one. Returns a recheck delay, or null. */
    fun onScroll(offset: Double, nowMillis: Long): Long? = onScrollBy(offset, offset - lastOffset, nowMillis)

    /**
     * A new scroll offset with the exact [delta] the reader moved it (Android, where the offset is an estimate). A
     * zero delta is not the reader: [layout] of a list whose scrolling is not known, which holds a closed header.
     */
    fun onScrollBy(offset: Double, delta: Double, nowMillis: Long): Long? {
        if (delta == 0.0) return layout(offset, scrollable = false, nowMillis = nowMillis)
        if (abs(delta) >= LAYOUT_JUMP) {
            run = 0.0
        } else if (delta != 0.0) {
            val sameDirection = run == 0.0 || (delta > 0) == (run > 0)
            run = if (sameDirection) run + delta else delta
        }
        lastOffset = offset
        if (offset > COLLAPSE_AT) heldClosed = false
        return evaluate(nowMillis)
    }

    /**
     * The offset moved without the reader: a clamp after the header closed or the viewport changed, a relayout, a
     * programmatic scroll. It never counts as a drag. A closed header over a list that cannot [scrollable] scroll is
     * held closed, so the top does not reopen it (that would loop: open, the list shrinks and scrolls, it closes, the
     * list clamps back to the top); a tap, a real drag up or a scroll to top reopens it. A list that can scroll and is
     * moved by code from below the dead zone to the top (status bar tap, scroll to top, tab reselect) opens it, as
     * reaching the top by hand does. Returns a recheck delay, or null.
     */
    fun layout(offset: Double, scrollable: Boolean, nowMillis: Long): Long? {
        val cameToTop = lastOffset > EXPAND_AT && offset <= EXPAND_AT
        lastOffset = offset
        run = 0.0
        if (isOpen) return null
        if (!scrollable) {
            heldClosed = true
            return null
        }
        if (!cameToTop) return null
        heldClosed = false
        return evaluate(nowMillis)
    }

    /**
     * Another list under the header (a tab switch): it starts open over that list's top, with the cooldown stamped
     * so the new list's first layout does not close it at once.
     */
    fun reset(nowMillis: Long) {
        toggle(true, nowMillis)
        lastOffset = 0.0
    }

    /** The cooldown a call asked to wait out has passed ([nowMillis]: the time it was asked for). */
    fun recheck(nowMillis: Long): Long? = evaluate(nowMillis)

    /** RN _handleOnSummaryExpanded (a tap): opens and stamps the cooldown, so the next scroll does not close it at once. */
    fun expand(nowMillis: Long): Boolean {
        if (isOpen) return false
        toggle(true, nowMillis)
        return true
    }

    /**
     * A tap on the header that shows while it is open (the community Details title): closes it, and it stays closed
     * at the top (where the tap happens) until the reader scrolls past the dead zone or drags up to reveal it.
     */
    fun collapse(nowMillis: Long): Boolean {
        if (!isOpen) return false
        toggle(false, nowMillis)
        heldClosed = true
        return true
    }

    private fun evaluate(nowMillis: Long): Long? {
        val wantCollapse = isOpen && lastOffset > COLLAPSE_AT && run >= COLLAPSE_RUN
        val wantExpand = !isOpen && ((lastOffset <= EXPAND_AT && !heldClosed) || run <= -REVEAL_RUN)
        if (!wantCollapse && !wantExpand) return null
        val since = lastToggleAt?.let { nowMillis - it }
        if (since != null && since < COOLDOWN_MS) return COOLDOWN_MS - since + RECHECK_SLACK_MS
        toggle(wantExpand, nowMillis)
        return null
    }

    private fun toggle(open: Boolean, nowMillis: Long) {
        heldClosed = false
        lastToggleAt = nowMillis
        run = 0.0
        isOpen = open
    }

    companion object {
        const val COLLAPSE_AT = 80.0
        const val EXPAND_AT = 8.0
        const val COOLDOWN_MS = 550L
        const val REVEAL_RUN = 60.0
        const val COLLAPSE_RUN = 20.0
        const val LAYOUT_JUMP = 250.0
        const val RECHECK_SLACK_MS = 20L
    }
}
