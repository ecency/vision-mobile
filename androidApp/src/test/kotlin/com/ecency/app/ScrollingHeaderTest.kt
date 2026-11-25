package com.ecency.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import com.ecency.app.ui.NewCardsOnTop
import com.ecency.app.ui.PinFiller
import com.ecency.app.ui.PinFillerRow
import com.ecency.app.ui.ScrollingHeader
import com.ecency.app.ui.rememberPinFiller
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The scrolling header's list rules (#3734) on a bare list: a header row, pinned tabs, cards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h640dp")
class ScrollingHeaderTest {
    @get:Rule val compose = createComposeRule()

    /** Review of #3734: new cards on top (a quiet refresh) with the tabs pinned keep the header away. */
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Test
    fun newCardsOnTopWithTheTabsPinnedKeepTheHeaderAway() {
        val list = LazyListState()
        val cards = mutableStateOf((1..30).map { "c$it" })
        compose.setContent {
            NewCardsOnTop(list, cards.value.first(), firstCardIndex = 2, pinnedIndex = 1)
            LazyColumn(state = list) {
                item(key = "header") { Box(Modifier.fillMaxWidth().height(300.dp)) }
                stickyHeader(key = "pinned") { Box(Modifier.fillMaxWidth().height(48.dp)) }
                items(cards.value, key = { it }) { Box(Modifier.fillMaxWidth().height(120.dp)) }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { runBlocking { list.scrollToItem(1) } }
        compose.waitForIdle()
        compose.runOnIdle { cards.value = listOf("new") + cards.value }
        compose.waitForIdle()
        assertEquals("the tabs still pinned at the top", 1, list.firstVisibleItemIndex)
        assertEquals(0, list.firstVisibleItemScrollOffset)
    }

    private class Rows(val loading: Boolean, val count: Int)

    private var scope: kotlinx.coroutines.CoroutineScope? = null

    /** A bare profile: a 300 px header, pinned tabs, rows of 100 px (or one loading row of 600 px), the filler. */
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    private fun setList(list: LazyListState, rows: androidx.compose.runtime.MutableState<Rows>, filler: Array<PinFiller?>) {
        compose.setContent {
            val f = rememberPinFiller(list)
            filler[0] = f
            scope = androidx.compose.runtime.rememberCoroutineScope()
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f)) {
                LazyColumn(state = list, modifier = Modifier.height(640.dp)) {
                    item(key = "header") { Box(Modifier.fillMaxWidth().height(300.dp)) }
                    stickyHeader(key = "tabs") { Box(Modifier.fillMaxWidth().height(48.dp)) }
                    if (rows.value.loading) item(key = "loading", contentType = ScrollingHeader.LOADING) { Box(Modifier.fillMaxWidth().height(600.dp)) }
                    items((1..rows.value.count).map { "r$it" }, key = { it }) { Box(Modifier.fillMaxWidth().height(100.dp)) }
                    item(key = ScrollingHeader.FILLER_KEY) { PinFillerRow(f) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun settle() {
        repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    }

    /** Review of #3734: a tab switch to a short tab with the tabs pinned keeps them pinned (the filler reserved). */
    @Test
    fun aSwitchToAShortTabWithTheTabsPinnedKeepsThemPinned() {
        val list = LazyListState()
        val rows = mutableStateOf(Rows(loading = false, count = 30))
        val filler = arrayOfNulls<PinFiller>(1)
        setList(list, rows, filler)
        settle()
        compose.runOnIdle { runBlocking { list.scrollToItem(0, 300) } }
        settle()
        // as ProfileScreen's selectTab: reserve, the new rows, placed at the pinned position, released
        compose.runOnIdle {
            filler[0]!!.reserve()
            rows.value = Rows(loading = false, count = 1)
            scope!!.launch {
                list.scrollToItem(0, 300)
                filler[0]!!.release()
            }
        }
        settle()
        assertTrue("the header still away: ${list.firstVisibleItemIndex}/${list.firstVisibleItemScrollOffset}", list.firstVisibleItemIndex >= 1 || list.firstVisibleItemScrollOffset >= 300)
    }

    /** Review of #3734: a first page shorter than the loading row it replaces keeps the tabs pinned. */
    @Test
    fun aFirstPageShorterThanItsLoadingRowKeepsTheTabsPinned() {
        val list = LazyListState()
        val rows = mutableStateOf(Rows(loading = true, count = 0))
        val filler = arrayOfNulls<PinFiller>(1)
        setList(list, rows, filler)
        settle()
        compose.runOnIdle { runBlocking { list.scrollToItem(ScrollingHeader.TABS_INDEX) } }
        settle()
        compose.runOnIdle { rows.value = Rows(loading = false, count = 1) }
        settle()
        assertTrue("the header still away: ${list.firstVisibleItemIndex}/${list.firstVisibleItemScrollOffset}", list.firstVisibleItemIndex >= 1)
    }
}
