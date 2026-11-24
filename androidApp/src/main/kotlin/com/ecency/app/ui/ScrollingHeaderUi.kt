package com.ecency.app.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember

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

    /**
     * Another tab's list: when the header had scrolled away, the new list starts at its own top with the tabs still
     * pinned, as X does; when the header showed, the new list shows it too.
     */
    suspend fun keepPinned(from: LazyListState, to: LazyListState) {
        if (gone(from) && !gone(to)) to.scrollToItem(TABS_INDEX)
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
