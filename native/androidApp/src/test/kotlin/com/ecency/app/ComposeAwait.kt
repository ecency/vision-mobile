package com.ecency.app

import android.os.Looper
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollToNode
import org.robolectric.Shadows.shadowOf

/**
 * Waits for [condition] in a Robolectric Compose test whose app does real work on background threads (the vault,
 * signing, rendering: `Dispatchers.IO` and `Dispatchers.Default`).
 *
 * `ComposeTestRule.waitUntil(10_000)` was timing sensitive there in two ways: it measures wall-clock time, while
 * how long that background work takes depends on what else the machine is doing (a cold first composition on a
 * loaded machine has taken longer than 10 s); and between frames it only sleeps, so a continuation that a
 * background thread posted to the main looper waits for whatever happens to idle the looper next.
 *
 * Here every round runs the main looper's due messages, then checks, then moves the frame clock (unless the test
 * drives the clock by hand). It returns as soon as the condition holds. [FAILURE_BOUND_MILLIS] is not a time
 * a passing test ever waits for: it only ends a test whose condition never comes true, and says what was missing.
 * A smaller [boundMillis] is only for an optional wait wrapped in runCatching ("if it shows at all").
 */
fun ComposeTestRule.await(what: String, boundMillis: Long = FAILURE_BOUND_MILLIS, condition: () -> Boolean) {
    val giveUpAt = System.nanoTime() + boundMillis * 1_000_000
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        if (System.nanoTime() > giveUpAt) throw AssertionError("never happened: $what")
        if (mainClock.autoAdvance) mainClock.advanceTimeByFrame()
        // background threads get the processor
        Thread.sleep(5)
    }
}

/**
 * Scrolls the list tagged [listTag] to [target], retrying until it is there: a row that comes from background work
 * (a loaded discussion, a rendered body) may not be in the list yet on a slow machine, and a single
 * `performScrollToNode` then fails at once. Returns as soon as one scroll finds it.
 */
fun ComposeTestRule.scrollListTo(listTag: String, target: SemanticsMatcher) = await("$target in $listTag") {
    runCatching { onNode(hasTestTag(listTag)).performScrollToNode(target) }.isSuccess
}

/** Only reached by a failing test. */
const val FAILURE_BOUND_MILLIS = 120_000L
