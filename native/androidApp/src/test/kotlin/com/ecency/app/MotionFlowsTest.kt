package com.ecency.app

import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import com.ecency.app.ui.FirstPageAnchor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.test.core.app.ApplicationProvider
import com.ecency.app.ui.AnimatedCounter
import com.ecency.app.ui.AnimatedNavHost
import com.ecency.app.ui.AnimatedSwap
import com.ecency.app.ui.FeedSkeleton
import com.ecency.app.ui.HapticProbe
import com.ecency.app.ui.LocalMotion
import com.ecency.app.ui.Motion
import com.ecency.app.ui.Navigator
import com.ecency.app.ui.ScreenBackHandler
import com.ecency.app.ui.SendButtonPhase
import com.ecency.app.ui.Skeleton
import com.ecency.app.ui.ShimmerProbe
import com.ecency.app.ui.hapticConstant
import com.ecency.app.ui.rememberSendPhase
import com.ecency.app.ui.rememberSystemMotion
import com.ecency.core.api.model.PostKey
import com.ecency.core.app.AppScreen
import com.ecency.core.app.HapticEvent
import com.ecency.core.app.HapticKind
import com.ecency.core.app.MotionLevel
import com.ecency.core.app.MotionTokens
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The motion system on Android: tokens as specs, the reduce motion path, page transitions, skeletons, haptics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MotionFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() {
        HapticProbe.onHaptic = null
    }

    // ---- pure choices ----

    @Test
    fun hapticConstantsAreLightAndFallBackOnOldSystems() {
        assertEquals(HapticFeedbackConstants.CONFIRM, hapticConstant(HapticKind.SUCCESS, 30))
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, hapticConstant(HapticKind.SUCCESS, 29))
        assertEquals(HapticFeedbackConstants.REJECT, hapticConstant(HapticKind.ERROR, 34))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, hapticConstant(HapticKind.ERROR, 24))
        assertEquals(HapticFeedbackConstants.TOGGLE_ON, hapticConstant(HapticKind.LIGHT_IMPACT, 34))
        assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, hapticConstant(HapticKind.LIGHT_IMPACT, 33))
        assertEquals(HapticFeedbackConstants.SEGMENT_TICK, hapticConstant(HapticKind.SELECTION, 34))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticConstant(HapticKind.SELECTION, 26))
    }

    @Test
    fun navigatorKeepsPoppedModelsUntilTheTransitionSettles() {
        val nav = Navigator()
        nav.push(AppScreen.Profile("alice"))
        val profile = nav.top
        val closed = booleanArrayOf(false)
        profile.get("probe") { AutoCloseable { closed[0] = true } }
        assertSame(nav.root, nav.backTarget())
        nav.push(AppScreen.Profile("bob"))
        assertSame(profile, nav.backTarget())

        // without the animated host, a pop closes at once (as before)
        nav.pop()
        nav.pop()
        assertTrue(closed[0])

        // with it, the popped entry stays open until settle
        nav.push(AppScreen.Profile("carol"))
        val held = booleanArrayOf(false)
        nav.top.get("probe") { AutoCloseable { held[0] = true } }
        nav.holdsPopped = true
        nav.pop()
        assertFalse(held[0])
        nav.settle()
        assertTrue(held[0])

        // the drawer or the tab root: back is not a pop, nothing to preview
        nav.openDrawer()
        assertNull(nav.backTarget())
    }

    @Test
    fun entriesDroppedBelowAnUnchangedTopCloseAtOnce() {
        val nav = Navigator()
        nav.holdsPopped = true
        fun probe(): BooleanArray = booleanArrayOf(false).also { flag -> nav.top.get<AutoCloseable>("probe") { AutoCloseable { flag[0] = true } } }
        // a gated screen (drafts) under a public profile on top
        nav.push(AppScreen.Profile("alice"))
        val alice = probe()
        nav.push(AppScreen.Drafts())
        val drafts = probe()
        nav.push(AppScreen.Profile("bob"))
        val bob = probe()
        nav.accountIs("spike-zed")
        nav.accountIs("spike-yan")
        assertEquals(listOf("alice", "bob"), nav.screens.map { (it as AppScreen.Profile).author })
        assertTrue("the drafts screen under the top closed at once", drafts[0])
        assertFalse(alice[0] || bob[0])

        // a pop of several: only the top is drawn leaving and waits for the transition
        nav.push(AppScreen.Profile("carol"))
        val carol = probe()
        nav.selectTab(com.ecency.core.app.AppTab.WAVES)
        assertTrue("bob was never shown leaving", bob[0] && alice[0])
        assertFalse("carol waits for the transition", carol[0])
        nav.settle()
        assertTrue(carol[0])
    }

    // ---- compose ----

    @Test
    fun systemMotionFollowsTheAnimatorDurationScale() {
        val resolver = ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        var level: MotionLevel? = null
        compose.setContent { level = rememberSystemMotion().level }
        compose.waitForIdle()
        assertEquals(MotionLevel.NONE, level)
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        compose.waitForIdle()
        assertEquals(MotionLevel.FULL, level)
    }

    @Test
    fun pushAndPopLandAtFullMotion() = pushAndPop(MotionLevel.FULL)

    @Test
    fun pushAndPopLandUnderReducedMotion() = pushAndPop(MotionLevel.REDUCED)

    @Test
    fun pushAndPopLandWithAnimationsOff() = pushAndPop(MotionLevel.NONE)

    private fun pushAndPop(level: MotionLevel) {
        val nav = Navigator()
        compose.setContent { Host(nav, level) }
        compose.onNodeWithTag("screen").assertTextEquals("root")
        compose.runOnUiThread { nav.push(AppScreen.Profile("alice")) }
        compose.onNodeWithTag("screen").assertTextEquals("alice")
        val alice = nav.top
        val closed = booleanArrayOf(false)
        alice.get("probe") { AutoCloseable { closed[0] = true } }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("screen").assertTextEquals("root")
        assertSame(nav.root, nav.top)
        // the transition finished: the popped screen's models closed
        assertTrue(closed[0])
    }

    @Test
    fun predictiveBackSeeksThenPopsOrSpringsBack() {
        val nav = Navigator()
        compose.setContent { Host(nav, MotionLevel.FULL) }
        compose.runOnUiThread { nav.push(AppScreen.Profile("alice")) }
        compose.onNodeWithTag("screen").assertTextEquals("alice")
        val dispatcher = compose.activity.onBackPressedDispatcher

        // a gesture that is cancelled leaves the screen where it was
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 0f, 0.4f, BackEventCompat.EDGE_LEFT))
        }
        compose.waitForIdle()
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        compose.waitForIdle()
        assertEquals(1, nav.state.stack.size)
        compose.onNodeWithTag("screen").assertTextEquals("alice")

        // one that is let go pops
        compose.runOnUiThread {
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(200f, 0f, 0.7f, BackEventCompat.EDGE_LEFT))
            dispatcher.onBackPressed()
        }
        compose.waitForIdle()
        assertTrue(nav.state.stack.isEmpty())
        compose.onNodeWithTag("screen").assertTextEquals("root")
    }

    @Test
    fun aLeavingScreensOwnBackHandlerIsOffDuringItsExit() {
        val nav = Navigator()
        val backs = mutableListOf<String>()
        compose.setContent {
            // the app's own back (EcencyApp), registered before the host
            androidx.activity.compose.BackHandler { backs += "app" }
            CompositionLocalProvider(LocalMotion provides Motion(MotionLevel.FULL)) {
                AnimatedNavHost(nav) { entry ->
                    val name = (entry.screen as? AppScreen.Profile)?.author ?: "root"
                    // a busy transfer's leave guard
                    if (entry.screen != null) ScreenBackHandler { backs += name }
                    Text(name, Modifier.testTag("screen:$name"))
                }
            }
        }
        compose.runOnUiThread { nav.push(AppScreen.Profile("bob")) }
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals("the top screen's own handler takes back", listOf("bob"), backs)

        // bob is popped and still drawn leaving over the tabs: the next back is the app's, not bob's guard
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { nav.pop() }
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("screen:bob").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(listOf("bob", "app"), backs)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun aRollingCounterAndASwapShowOneTaggedNodeMidTransition() {
        var count by mutableStateOf(1L)
        var on by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides Motion(MotionLevel.FULL)) {
                AnimatedCounter(count) { Text(it.toString(), Modifier.testTag("count")) }
                AnimatedSwap(on) { Text(if (it) "on" else "off", Modifier.testTag("swap")) }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        count = 2L
        on = true
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(48)
        compose.onAllNodesWithTag("count").assertCountEquals(1)
        compose.onNodeWithTag("count").assertTextEquals("2")
        compose.onAllNodesWithTag("swap").assertCountEquals(1)
        compose.onNodeWithTag("swap").assertTextEquals("on")
        compose.mainClock.autoAdvance = true
    }

    @Composable
    private fun Host(nav: Navigator, level: MotionLevel) {
        CompositionLocalProvider(LocalMotion provides Motion(level)) {
            AnimatedNavHost(nav) { entry ->
                Text((entry.screen as? AppScreen.Profile)?.author ?: "root", Modifier.testTag("screen"))
            }
        }
    }

    @Test
    fun voteStateLandsAtEveryMotionLevel() {
        var level by mutableStateOf(MotionLevel.FULL)
        var voted by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides Motion(level)) {
                com.ecency.app.ui.ActionsRow(card(voted), voteInFlight = false, onVote = {}, onComments = null)
            }
        }
        for (l in MotionLevel.entries) {
            level = l
            voted = false
            compose.onNodeWithTag("vote").assertContentDescriptionEquals("Upvote")
            compose.onNodeWithTag("votes", useUnmergedTree = true).assertTextEquals("4")
            voted = true
            compose.onNodeWithTag("vote").assertContentDescriptionEquals("Upvoted")
            compose.onNodeWithTag("votes", useUnmergedTree = true).assertTextEquals("5")
        }
    }

    private fun card(voted: Boolean) = com.ecency.core.app.PostCard(
        PostKey("alice", "p"), "alice", "", 25, "t", "", null, "2026-01-01T00:00:00", 0L, "c", null, null,
        1.0, "$1.00", false, if (voted) 5 else 4, 0, voted, false, false, false, emptyList(), null,
    )

    @Test
    fun shimmerRunsOnlyAtFullMotionAndOnScreen() {
        var level by mutableStateOf(MotionLevel.FULL)
        var running: Boolean? = null
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides Motion(level)) {
                androidx.compose.foundation.layout.Column {
                    Skeleton {
                        com.ecency.app.ui.Bone(Modifier.size(40.dp))
                        ShimmerProbe { running = it }
                    }
                    FeedSkeleton(1)
                }
            }
        }
        for (l in MotionLevel.entries) {
            level = l
            compose.waitForIdle()
            assertEquals("shimmer at $l", l == MotionLevel.FULL, running)
            compose.onNodeWithTag("skeleton:feed").assertExists()
        }
    }

    @Test
    fun shimmerStopsWhileTheSkeletonHasNoArea() {
        var running: Boolean? = null
        compose.setContent {
            // nothing of it is on screen: no clock
            Skeleton(Modifier.size(0.dp)) { ShimmerProbe { running = it } }
        }
        compose.waitForIdle()
        assertEquals(false, running)
    }

    @Test
    fun sendGoesThroughProgressToDoneWithTheSuccessHaptic() {
        val haptics = mutableListOf<HapticEvent>()
        HapticProbe.onHaptic = { haptics += it }
        var sending by mutableStateOf(false)
        // chat's sent version: already 2 when the screen opened (no success then)
        var sent by mutableStateOf<Any?>(2)
        var phase: SendButtonPhase? = null
        compose.setContent { phase = rememberSendPhase(sending, sent) }
        compose.waitForIdle()
        assertEquals(SendButtonPhase.IDLE, phase)
        assertEquals(emptyList<HapticEvent>(), haptics)
        sending = true
        compose.waitForIdle()
        assertEquals(SendButtonPhase.SENDING, phase)
        compose.mainClock.autoAdvance = false
        sending = false
        sent = 3
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(64)
        assertEquals(SendButtonPhase.DONE, phase)
        assertEquals(listOf(HapticEvent.SUCCESS), haptics)
        compose.mainClock.advanceTimeBy(MotionTokens.DONE_HOLD_MS + 100L)
        assertEquals(SendButtonPhase.IDLE, phase)
        compose.mainClock.autoAdvance = true

        // a failed send, or the send bound freeing the button: sending ends, nothing was sent, no tick, no haptic
        sending = true
        compose.waitForIdle()
        sending = false
        compose.waitForIdle()
        assertEquals(SendButtonPhase.IDLE, phase)
        assertEquals(1, haptics.size)

        // the slow send's answer comes after the bound: that is the success
        compose.mainClock.autoAdvance = false
        sent = 4
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(64)
        assertEquals(SendButtonPhase.DONE, phase)
        assertEquals(2, haptics.size)
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun aSheetSendThatEndsWithoutBeingSentGivesNoSuccessHaptic() {
        val haptics = mutableListOf<HapticEvent>()
        HapticProbe.onHaptic = { haptics += it }
        var sending by mutableStateOf(false)
        // a reply's or wave's sent key
        var sent by mutableStateOf<Any?>(null)
        var phase: SendButtonPhase? = null
        compose.setContent { phase = rememberSendPhase(sending, sent, holdsDone = false) }
        // cancelled signing, the posting authority prompt, the RC offer, a conflict: sending ends with nothing sent
        repeat(2) {
            sending = true
            compose.waitForIdle()
            assertEquals(SendButtonPhase.SENDING, phase)
            sending = false
            compose.waitForIdle()
            assertEquals(SendButtonPhase.IDLE, phase)
        }
        assertEquals(emptyList<HapticEvent>(), haptics)
        sending = true
        compose.waitForIdle()
        sending = false
        sent = "spike-zed/re-1"
        compose.waitForIdle()
        assertEquals(listOf(HapticEvent.SUCCESS), haptics)
        assertEquals("the sheet closes: no tick", SendButtonPhase.IDLE, phase)
    }

    // ---- the feed's first page replacing the skeleton ----

    private var feedItems by mutableStateOf(emptyList<Int>())
    private var headerHeight by mutableStateOf(0.dp)
    private lateinit var feedList: LazyListState

    private fun feed(header: Dp, skeleton: Dp = 3000.dp) {
        headerHeight = header
        compose.setContent {
            feedList = rememberLazyListState()
            FirstPageAnchor(feedList, hasItems = feedItems.isNotEmpty(), key = "feed")
            LazyColumn(state = feedList, modifier = Modifier.fillMaxSize().testTag("feedList")) {
                item(key = "header") { Box(Modifier.fillMaxWidth().height(headerHeight)) }
                if (feedItems.isEmpty()) item(key = "loading") { Box(Modifier.fillMaxWidth().height(skeleton)) }
                items(feedItems, key = { it }) { Box(Modifier.fillMaxWidth().height(200.dp)) }
            }
        }
        compose.waitForIdle()
    }

    private fun dragUp() {
        compose.onNodeWithTag("feedList").performTouchInput { swipeUp(durationMillis = 1500) }
        compose.waitForIdle()
    }

    @Test
    fun aFeedNobodyScrolledIsAtTheTopWhenItsFirstPageLands() {
        feed(header = 0.dp)
        // the header fills in while the skeleton shows
        headerHeight = 400.dp
        compose.waitForIdle()
        feedItems = (1..20).toList()
        compose.waitForIdle()
        assertEquals(0, feedList.firstVisibleItemIndex)
        assertEquals(0, feedList.firstVisibleItemScrollOffset)
    }

    @Test
    fun aTallHeaderScrolledDuringTheLoadKeepsItsOffset() {
        feed(header = 6000.dp)
        dragUp()
        val offset = feedList.firstVisibleItemScrollOffset
        assertEquals(0, feedList.firstVisibleItemIndex)
        assertTrue("scrolled into the header: $offset", offset > 0)
        feedItems = (1..20).toList()
        compose.waitForIdle()
        assertEquals(0, feedList.firstVisibleItemIndex)
        assertEquals(offset, feedList.firstVisibleItemScrollOffset)
    }

    @Test
    fun aRetryBackToTheSkeletonForgetsTheFirstLoadsScroll() {
        feed(header = 40.dp)
        dragUp()
        feedItems = (1..20).toList()
        compose.waitForIdle()
        assertTrue(feedList.firstVisibleItemIndex >= 1)
        // an error retry: the skeleton again, nobody scrolls this time
        feedItems = emptyList()
        compose.waitForIdle()
        feedItems = (1..20).toList()
        compose.waitForIdle()
        assertEquals(0, feedList.firstVisibleItemIndex)
        assertEquals(0, feedList.firstVisibleItemScrollOffset)
    }

    @Test
    fun aFeedScrolledPastItsHeaderIsNotYankedToTheTop() {
        feed(header = 40.dp)
        dragUp()
        assertEquals(1, feedList.firstVisibleItemIndex)
        feedItems = (1..20).toList()
        compose.waitForIdle()
        assertTrue("stays below the header: ${feedList.firstVisibleItemIndex}", feedList.firstVisibleItemIndex >= 1)
    }
}
