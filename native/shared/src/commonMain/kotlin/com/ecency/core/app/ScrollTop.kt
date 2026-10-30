package com.ecency.core.app

/**
 * RN ScrollTopPopup's rules. Post lists (postsTabContent: feed, tag, community, profile): shown when the reader
 * scrolls UP more than 5000 points from the top, and hidden only by a tap, after which it stays away for a second.
 * Waves ([hidesOnScrollDown]): also hidden again when scrolling down or back within the threshold.
 * Offsets are in points (dp), as RN's.
 */
class ScrollTopRule(private val hidesOnScrollDown: Boolean = false, private val nowMillis: () -> Long) {
    var visible: Boolean = false
        private set
    private var blockedUntil = 0L
    private var last = 0.0

    /** A new scroll offset; returns whether the popup shows. */
    fun onScroll(offset: Double): Boolean {
        val up = offset < last
        val down = offset > last
        last = offset
        if (up && offset > THRESHOLD && nowMillis() >= blockedUntil) {
            visible = true
        } else if (hidesOnScrollDown && (down || offset <= THRESHOLD)) {
            visible = false
        }
        return visible
    }

    /** The popup was tapped: the list goes to the top and the popup stays away for a second (RN). */
    fun onTap() {
        visible = false
        blockedUntil = nowMillis() + BLOCK_MILLIS
    }

    companion object {
        const val THRESHOLD = 5000.0
        const val BLOCK_MILLIS = 1000L
    }
}
