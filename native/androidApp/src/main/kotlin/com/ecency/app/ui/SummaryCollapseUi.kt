package com.ecency.app.ui

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ecency.core.app.MotionLevel
import com.ecency.core.app.MotionTokens
import com.ecency.core.app.SummaryCollapse
import kotlinx.coroutines.delay

/**
 * The header above a lazy list that closes on scroll down and opens near the top or on a drag up (RN profileView,
 * shared [SummaryCollapse]). The list reports the reader's drags through [connection] (put it on the list); its offset
 * is exact on the first row and an estimate further down, where only "past the dead zone" matters. Kept in the
 * screen's stack entry, so a covered screen (a post opened from the profile) comes back as it was left.
 */
@Stable
class SummaryCollapseState {
    private var listState: LazyListState? = null
    private var density: Density? = null
    private val rule = SummaryCollapse()
    var open by mutableStateOf(true)
        private set
    internal var recheckAt by mutableStateOf<Long?>(null)

    private fun now() = SystemClock.uptimeMillis()

    internal fun attach(list: LazyListState, density: Density) {
        listState = list
        this.density = density
    }

    private fun Float.dp(): Double = density?.let { with(it) { toDp().value.toDouble() } } ?: 0.0

    private fun offsetDp(): Double {
        val list = listState ?: return 0.0
        val px = if (list.firstVisibleItemIndex == 0) {
            list.firstVisibleItemScrollOffset.toFloat()
        } else {
            val rows = list.layoutInfo.visibleItemsInfo
            val avg = if (rows.isEmpty()) 0f else rows.sumOf { it.size }.toFloat() / rows.size
            list.firstVisibleItemIndex * avg + list.firstVisibleItemScrollOffset
        }
        return px.dp()
    }

    private fun settle(wait: Long?, at: Long) {
        open = rule.isOpen
        recheckAt = wait?.let { at + it }
    }

    /** The list's visible height at the last drag: a later top reached at another height is a clamp, not code. */
    private var dragViewport = -1

    private fun viewport() = listState?.layoutInfo?.viewportSize?.height ?: -1

    internal fun scrolledBy(deltaPx: Float) {
        dragViewport = viewport()
        val at = now()
        settle(rule.onScrollBy(offsetDp(), deltaPx.dp(), at), at)
    }

    /**
     * The list is at its top without a drag: moved there by code (scroll to top, the first page anchor), or clamped
     * there because it got the closed header's room and no longer scrolls (short content; then it is held closed).
     */
    internal fun atTopByCode(scrollable: Boolean) {
        val at = now()
        // the header closing grows the list: a top reached while it grew is a clamp (held), never a scroll to top
        val resized = viewport() != dragViewport
        settle(rule.layout(offsetDp(), scrollable && !resized, at), at)
    }

    internal fun recheck(at: Long) = settle(rule.recheck(at), at)

    /** A tap on the compact header (RN CollapsibleCard handleOnExpanded), or the scroll to top popup. */
    fun expand() {
        val at = now()
        if (rule.expand(at)) settle(null, at)
    }

    /** A tap on the title of an open card (community Details). */
    fun collapse() {
        val at = now()
        if (rule.collapse(at)) settle(null, at)
    }

    fun toggle() = if (open) collapse() else expand()

    /** Another tab's list under the header: open over its top. */
    fun reset() = settle(null, now().also { rule.reset(it) })

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            when {
                // a list scrolled down moves its content up: RN's positive delta
                consumed.y != 0f -> scrolledBy(-consumed.y)
                // the reader pulls down where the list cannot follow (its top, or content too short to scroll)
                available.y > 0f && source == NestedScrollSource.UserInput -> scrolledBy(-available.y)
            }
            return Offset.Zero
        }
    }
}

/** [state] (from the screen's stack entry) driven by [listState]. */
@Composable
fun rememberSummaryCollapse(state: SummaryCollapseState, listState: LazyListState): SummaryCollapseState {
    state.attach(listState, LocalDensity.current)
    val density = LocalDensity.current
    LaunchedEffect(state, listState) {
        val top = with(density) { SummaryCollapse.EXPAND_AT.toFloat().dp.toPx() }
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= top }
            .collect { atTop ->
                // a drag that reaches the top was already counted (the rule ignores a top it already knows)
                if (atTop && !state.open) state.atTopByCode(listState.canScrollForward || listState.canScrollBackward)
            }
    }
    val recheckAt = state.recheckAt
    LaunchedEffect(state, recheckAt) {
        if (recheckAt != null) {
            delay((recheckAt - SystemClock.uptimeMillis()).coerceAtLeast(0))
            state.recheck(recheckAt)
        }
    }
    return state
}

/** Hands a list's scroll to [state]. */
fun Modifier.collapsesSummary(state: SummaryCollapseState): Modifier = nestedScroll(state.connection)

/**
 * The collapsible header: slides shut and open at full motion, fades when reduced, lands at once when off. Never
 * taller than [maxHeight] of the screen, scrolling inside past that (large text), so the list below keeps room to
 * be scrolled and close it.
 */
@Composable
fun CollapsingSummary(state: SummaryCollapseState, modifier: Modifier = Modifier, maxFraction: Float = 0.7f, content: @Composable ColumnScope.() -> Unit) {
    val motion = LocalMotion.current
    BoxWithConstraints(modifier) {
        val cap: Dp = maxHeight * maxFraction
        AnimatedVisibility(state.open, enter = motion.summaryEnter(), exit = motion.summaryExit()) {
            Column(Modifier.heightIn(max = cap).verticalScroll(rememberScrollState()), content = content)
        }
    }
}

private fun Motion.summaryEnter(): EnterTransition = when (level) {
    MotionLevel.FULL -> expandVertically(tween(MotionTokens.PAGE_MS, easing = Motion.Standard), expandFrom = Alignment.Top) +
        fadeIn(tween(MotionTokens.PAGE_MS, easing = Motion.Standard))
    MotionLevel.REDUCED -> fadeIn(tween(MotionTokens.PAGE_FADE_MS))
    MotionLevel.NONE -> EnterTransition.None
}

private fun Motion.summaryExit(): ExitTransition = when (level) {
    MotionLevel.FULL -> shrinkVertically(tween(MotionTokens.PAGE_MS, easing = Motion.Standard), shrinkTowards = Alignment.Top) +
        fadeOut(tween(MotionTokens.PAGE_MS, easing = Motion.Standard))
    MotionLevel.REDUCED -> fadeOut(tween(MotionTokens.PAGE_FADE_MS))
    MotionLevel.NONE -> ExitTransition.None
}
