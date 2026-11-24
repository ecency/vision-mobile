package com.ecency.app.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.flow.first

/**
 * A header that is the first row of its list (the profile summary, the community Details card) and scrolls away with
 * it, one to one with the finger, as Instagram and X do; the tabs are the next row and stay pinned once they reach
 * the top (a sticky header). Nothing opens or closes by itself, so nothing jumps: the list only scrolls (#3734).
 */
object ScrollingHeader {
    /** The header row's index; the pinned tabs are the next one. */
    const val HEADER_INDEX = 0
    const val TABS_INDEX = 1

    /** Where the header row ends in the list's viewport (px), or null once it has scrolled out of view. */
    fun headerBottom(list: LazyListState): Int? =
        list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == HEADER_INDEX }?.let { it.offset + it.size }

    /** The header has scrolled out of view (the tabs are pinned). */
    fun gone(list: LazyListState): Boolean = list.firstVisibleItemIndex > HEADER_INDEX

    /** The key of a list's last row, its [PinFiller]. */
    const val FILLER_KEY = "pin-filler"

    /** The content type of a loading row: the first page replacing it may be shorter, so the filler does not count it. */
    const val LOADING = "pin-loading"

    /**
     * Another tab's list (each tab keeps its own): when the header had scrolled away, the new list starts at its own
     * top with the tabs still pinned, as X does, even if it was read further down before; when the header showed, the
     * new list shows it where it was.
     */
    suspend fun keepPinned(from: LazyListState, to: LazyListState) {
        if (gone(from)) to.scrollToItem(TABS_INDEX) else to.scrollToItem(HEADER_INDEX, from.firstVisibleItemScrollOffset)
    }
}

/**
 * The last row of a list with pinned tabs (iOS `PinFiller`): as tall as the rows after the tabs fall short of the
 * list's height, so a short tab (one post, a wallet) can still scroll the header away and keep the tabs pinned.
 *
 * Measured from the list's layout while the rows from the tabs to it are all known (the header or the tabs' own row
 * first in view); held otherwise, only shrinking as far as the rows in view prove. Loading rows
 * ([ScrollingHeader.LOADING]) do not count, as the first page replacing them may be shorter. While [reserved] (a new
 * list, a tab switch placing the list) it is a screen tall, so the rows are never cut short of the pinned position
 * before they were measured.
 */
@Stable
class PinFiller internal constructor(private val list: LazyListState) {
    var reserved by mutableStateOf(true)
        private set
    private var held = 0
    internal val height: State<Int> = derivedStateOf { measure() }

    /** A screen tall until [release]: for a tab switch, before the list is placed. */
    fun reserve() {
        reserved = true
    }

    /** Measured again once the list has laid out its rows since (the new tab's, with the reserved filler). */
    suspend fun release() {
        val seen = list.layoutInfo
        snapshotFlow { list.layoutInfo }.first { it !== seen }
        reserved = false
    }

    /** The list's first layout: measured from it on. */
    internal fun laidOut() {
        reserved = false
    }

    private fun measure(): Int {
        val info = list.layoutInfo
        val items = info.visibleItemsInfo
        val tabs = items.firstOrNull { it.index == ScrollingHeader.TABS_INDEX } ?: return held
        val filler = items.firstOrNull { it.key == ScrollingHeader.FILLER_KEY }
        val viewport = info.viewportSize.height
        // where the tabs' row starts in its own place (not where it is pinned)
        val tabsTop = when (list.firstVisibleItemIndex) {
            ScrollingHeader.HEADER_INDEX -> items.firstOrNull { it.index == ScrollingHeader.HEADER_INDEX }?.let { it.offset + it.size }
            ScrollingHeader.TABS_INDEX -> -list.firstVisibleItemScrollOffset
            else -> null
        }
        held = if (tabsTop != null) {
            val loading = items.filter { it.contentType == ScrollingHeader.LOADING }.sumOf { it.size }
            val last = items.last()
            val rows = (filler?.offset ?: (last.offset + last.size)) - (tabsTop + tabs.size) - loading
            val need = (viewport - tabs.size - rows).coerceAtLeast(0)
            // exact with the filler in view, or with a loading row (nothing else comes after it); otherwise the rows
            // reach past the list's end and only bound it
            if (filler != null || loading > 0) need else minOf(held, need)
        } else if (filler != null) {
            // further down: the rows after the tabs reach at least to the filler's top
            minOf(held, (viewport - filler.offset).coerceAtLeast(0))
        } else {
            held
        }
        return held
    }
}

/** [list]'s [PinFiller]: reserved until the list's first layout. */
@Composable
fun rememberPinFiller(list: LazyListState): PinFiller {
    val filler = remember(list) { PinFiller(list) }
    LaunchedEffect(filler) {
        snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > 0 }
        filler.laidOut()
    }
    return filler
}

/** The [PinFiller] row; keyed [ScrollingHeader.FILLER_KEY]. */
@Composable
fun LazyItemScope.PinFillerRow(filler: PinFiller) {
    if (filler.reserved) {
        Spacer(Modifier.fillParentMaxHeight())
    } else {
        val px by filler.height
        Spacer(Modifier.height(with(LocalDensity.current) { px.toDp() }))
    }
}

/** How far the header has scrolled under a bar of [barPx] (0 open, 1 gone), read in layout and drawing only. */
@Composable
fun rememberHeaderProgress(list: LazyListState, barPx: Float): State<Float> = remember(list, barPx) {
    derivedStateOf {
        val bottom = ScrollingHeader.headerBottom(list) ?: return@derivedStateOf 1f
        // the bar fades in over the last bar height of the header, and is whole once the tabs reach it
        ((2 * barPx - bottom) / barPx).coerceIn(0f, 1f)
    }
}

/** How far the pinned tabs sit below the top so they stop under a bar of [barPx]: 0 while the header shows above. */
@Composable
fun rememberTabsShift(list: LazyListState, barPx: Float): State<Float> = remember(list, barPx) {
    derivedStateOf {
        val bottom = ScrollingHeader.headerBottom(list) ?: return@derivedStateOf barPx
        (barPx - bottom).coerceIn(0f, barPx)
    }
}
