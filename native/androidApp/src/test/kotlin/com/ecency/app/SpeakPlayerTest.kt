package com.ecency.app

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.dp
import com.ecency.app.render.LocalSpeakEngine
import com.ecency.app.render.PostActions
import com.ecency.app.render.PostBody
import com.ecency.app.render.SpeakEngine
import com.ecency.app.render.SpeakEvents
import com.ecency.app.render.SpeakStream
import com.ecency.core.render.blocks.Block
import com.ecency.core.render.blocks.Inline
import org.junit.Assert.assertEquals
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ecency.app.render.ProvideSpeakPlayers
import com.ecency.app.render.SpeakPlayers
import com.ecency.app.ui.Navigator
import com.ecency.core.app.AppScreen
import com.ecency.app.render.postBody
import com.ecency.core.render.blocks.SpeakPlayback
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** RN SpeakAudioPlayer behaviour with a scripted engine: lazy stream, seek, resume, one player at a time, errors. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeakPlayerTest {
    @get:Rule
    val compose = createComposeRule()

    private class FakeStream(val url: String, val events: SpeakEvents) : SpeakStream {
        var position = 0.0
        val seeks = mutableListOf<Double>()
        var released = false
        override val positionSeconds get() = position
        override fun seek(seconds: Double) {
            seeks += seconds
            position = seconds
        }
        override fun release() {
            released = true
        }
    }

    private val streams = mutableListOf<FakeStream>()
    private val engine = SpeakEngine { _: Context, url, events ->
        events.loadStarted()
        FakeStream(url, events).also { streams += it }
    }

    private val clip = Block.SpeakAudio("https://ecency.com/api/speak-audio?author=a&permlink=p", 90_000, listOf(0.2, 1.0, 0.5, 0.8))

    private fun show(blocks: List<Block>) = compose.setContent {
        CompositionLocalProvider(LocalSpeakEngine provides engine) { PostBody(blocks, 360.dp, PostActions()) }
    }

    @Test
    fun playLoadsLazilyPausesAndResumesWhereItWas() {
        show(listOf(Block.Paragraph(listOf(Inline.Text("Hello"))), clip))
        compose.onNodeWithTag("speak:time").assertTextEquals("1:30")
        assertEquals(0, streams.size) // nothing fetched before the first tap

        compose.onNodeWithContentDescription("Play voice").performClick()
        compose.waitForIdle()
        assertEquals(1, streams.size)
        assertEquals(clip.streamUrl, streams[0].url)
        compose.onNodeWithTag("speak:loading").assertExists()

        compose.runOnIdle { streams[0].events.loaded(88.0) }
        assertEquals(listOf(0.0), streams[0].seeks)
        compose.onNodeWithTag("speak:loading").assertDoesNotExist()
        compose.runOnIdle { streams[0].position = 30.0 }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("speak:time").assertTextEquals("0:30")

        compose.onNodeWithContentDescription("Pause voice").performClick()
        compose.waitForIdle()
        assertEquals(true, streams[0].released)
        assertEquals(false, SpeakPlayback.hasActive)
        compose.onNodeWithTag("speak:time").assertTextEquals("0:30")

        compose.onNodeWithContentDescription("Play voice").performClick()
        compose.waitForIdle()
        assertEquals(2, streams.size)
        compose.runOnIdle { streams[1].events.loaded(88.0) }
        assertEquals(listOf(30.0), streams[1].seeks)

        compose.runOnIdle { streams[1].events.ended() }
        compose.waitForIdle()
        assertEquals(true, streams[1].released)
        compose.onNodeWithContentDescription("Play voice").assertExists()
        compose.onNodeWithTag("speak:time").assertTextEquals("0:00")
    }

    @Test
    fun waveformTapBeforePlayingStartsAtThatSpot() {
        show(listOf(clip))
        compose.onNodeWithTag("speak:wave").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(1, streams.size)
        compose.runOnIdle { streams[0].events.loaded(90.0) }
        assertEquals(1, streams[0].seeks.size)
        assertEquals(45.0, streams[0].seeks[0], 2.0)
    }

    @Test
    fun startingAnotherPlayerPausesTheFirst() {
        val other = clip.copy(streamUrl = "https://ecency.com/api/speak-audio?author=b&permlink=q")
        show(listOf(clip, other))
        compose.onAllNodesWithTag("speak:play").assertCountEquals(2)
        compose.onAllNodesWithTag("speak:play")[0].performClick()
        compose.waitForIdle()
        compose.onAllNodesWithTag("speak:play")[1].performClick()
        compose.waitForIdle()
        assertEquals(2, streams.size)
        assertEquals(true, streams[0].released)
        assertEquals(false, streams[1].released)
        assertEquals(other.streamUrl, streams[1].url)
    }

    @Test
    fun streamErrorReturnsToATappablePausedState() {
        show(listOf(clip.copy(peaks = emptyList())))
        compose.onNodeWithContentDescription("Play voice").performClick()
        compose.waitForIdle()
        compose.runOnIdle { streams[0].events.failed() }
        compose.waitForIdle()
        assertEquals(true, streams[0].released)
        compose.onNodeWithContentDescription("Play voice").assertExists()
        compose.onNodeWithTag("speak:loading").assertDoesNotExist()
        compose.onNodeWithTag("speak:time").assertTextEquals("1:30")
    }

    @Test
    fun waveformTapWhilePlayingSeeksTheLiveStream() {
        show(listOf(clip))
        compose.onNodeWithContentDescription("Play voice").performClick()
        compose.waitForIdle()
        compose.runOnIdle { streams[0].events.loaded(90.0) }
        compose.onNodeWithTag("speak:wave").performTouchInput { click(center) }
        compose.waitForIdle()
        assertEquals(2, streams[0].seeks.size)
        assertEquals(45.0, streams[0].seeks[1], 2.0)
        assertEquals(1, streams.size)
        assertEquals(false, streams[0].released)
    }

    @Test
    fun keepsPlayingWhileTheRowScrollsOffAndBack() {
        val filler = List(80) { Block.Paragraph(listOf(Inline.Text("Paragraph $it of the post body"))) }
        compose.setContent {
            CompositionLocalProvider(LocalSpeakEngine provides engine) {
                ProvideSpeakPlayers {
                    LazyColumn(Modifier.testTag("list")) { postBody(listOf(clip) + filler, 360.dp, PostActions()) }
                }
            }
        }
        compose.onNodeWithContentDescription("Play voice").performClick()
        compose.runOnIdle { streams[0].events.loaded(90.0) }
        compose.runOnIdle { streams[0].position = 12.0 }
        compose.onNodeWithTag("list").performScrollToIndex(79)
        compose.onNodeWithTag("speak").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(300)
        assertEquals(false, streams[0].released)
        compose.onNodeWithTag("list").performScrollToIndex(0)
        compose.onNodeWithContentDescription("Pause voice").assertExists()
        // Progress is polled while the row is on screen; it catches up on the next tick.
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("speak:time").assertTextEquals("0:12")
        assertEquals(1, streams.size)
    }

    @Test
    fun appPauseStopsPlayback() {
        val owner = TestOwner()
        compose.setContent {
            CompositionLocalProvider(LocalSpeakEngine provides engine, LocalLifecycleOwner provides owner) {
                PostBody(listOf(clip), 360.dp, PostActions())
            }
        }
        compose.onNodeWithContentDescription("Play voice").performClick()
        compose.waitForIdle()
        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        compose.waitForIdle()
        assertEquals(true, streams[0].released)
        compose.onNodeWithContentDescription("Play voice").assertExists()
    }

    @Test
    fun leavingTheScreenStopsAndUnregisters() {
        var shown by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalSpeakEngine provides engine) {
                if (shown) ProvideSpeakPlayers { PostBody(listOf(clip), 360.dp, PostActions()) }
            }
        }
        compose.onNodeWithContentDescription("Play voice").performClick()
        compose.waitForIdle()
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        assertEquals(true, streams[0].released)
        assertEquals(false, SpeakPlayback.hasActive)
        // The gone player no longer holds the audio: a new one starts without stopping anything.
        var stops = 0
        val next = SpeakPlayback.Token { stops++ }
        SpeakPlayback.activate(next)
        assertEquals(0, stops)
        SpeakPlayback.deactivate(next)
    }

    @Test
    fun failureWhileOpeningLeavesNoStreamBehind() {
        val failing = SpeakEngine { _: Context, url, events ->
            val stream = FakeStream(url, events).also { streams += it }
            events.loadStarted()
            events.failed()
            stream
        }
        compose.setContent {
            CompositionLocalProvider(LocalSpeakEngine provides failing) { PostBody(listOf(clip), 360.dp, PostActions()) }
        }
        compose.onNodeWithContentDescription("Play voice").performClick()
        compose.waitForIdle()
        assertEquals(1, streams.size)
        assertEquals(true, streams[0].released)
        assertEquals(false, SpeakPlayback.hasActive)
        compose.onNodeWithContentDescription("Play voice").assertExists()
    }

    @Test
    fun pausedClipKeepsItsPlaceAcrossAPushAndPop() {
        val players = SpeakPlayers()
        var shown by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalSpeakEngine provides engine) {
                if (shown) ProvideSpeakPlayers(players) { PostBody(listOf(clip), 360.dp, PostActions()) }
            }
        }
        compose.onNodeWithContentDescription("Play voice").performClick()
        compose.runOnIdle { streams[0].events.loaded(90.0) }
        compose.runOnIdle { streams[0].position = 33.0 }
        compose.mainClock.advanceTimeBy(300)
        // Another screen is pushed: the post leaves composition, the clip pauses.
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        assertEquals(true, streams[0].released)
        // Back: same place, paused; playing again resumes there.
        compose.runOnIdle { shown = true }
        compose.onNodeWithTag("speak:time").assertTextEquals("0:33")
        compose.onNodeWithContentDescription("Play voice").performClick()
        compose.runOnIdle { streams[1].events.loaded(90.0) }
        assertEquals(listOf(33.0), streams[1].seeks)
        // The entry closes (screen popped): stopped.
        compose.runOnIdle { players.close() }
        compose.waitForIdle()
        assertEquals(true, streams[1].released)
    }

    @Test
    fun theSameClipRenderedAgainTakesTheNewerData() {
        val players = SpeakPlayers()
        var current by mutableStateOf(clip)
        compose.setContent {
            CompositionLocalProvider(LocalSpeakEngine provides engine) {
                ProvideSpeakPlayers(players) { PostBody(listOf(current), 360.dp, PostActions()) }
            }
        }
        compose.onNodeWithTag("speak:time").assertTextEquals("1:30")
        compose.runOnIdle { current = clip.copy(durationMs = 61_000, peaks = listOf(1.0)) }
        compose.onNodeWithTag("speak:time").assertTextEquals("1:01")
        compose.runOnIdle { assertEquals(listOf(1.0), players.peek(clip.streamUrl)!!.block.peaks) }
        // Playing keeps one player for the URL.
        compose.onNodeWithContentDescription("Play voice").performClick()
        compose.runOnIdle { current = clip }
        compose.waitForIdle()
        assertEquals(1, streams.size)
        assertEquals(false, streams[0].released)
        compose.onNodeWithContentDescription("Pause voice").assertExists()
    }

    @Test
    fun progressStopsWithTheStream() {
        val players = SpeakPlayers()
        compose.setContent {
            CompositionLocalProvider(LocalSpeakEngine provides engine) {
                ProvideSpeakPlayers(players) { PostBody(listOf(clip), 360.dp, PostActions()) }
            }
        }
        compose.onNodeWithContentDescription("Play voice").performClick()
        compose.runOnIdle { streams[0].events.loaded(90.0) }
        compose.runOnIdle { streams[0].position = 10.0 }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("speak:time").assertTextEquals("0:10")
        compose.onNodeWithContentDescription("Pause voice").performClick()
        compose.runOnIdle { streams[0].position = 50.0 }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("speak:time").assertTextEquals("0:10")
        // A late tick after the release reads nothing.
        compose.runOnIdle {
            val controller = players.controller(clip, engine, androidx.test.core.app.ApplicationProvider.getApplicationContext())
            controller.tick()
            assertEquals(10.0, controller.state.currentTime, 0.0)
        }
    }

    @Test
    fun platformInterruptionPausesThePlayer() {
        show(listOf(clip))
        compose.onNodeWithContentDescription("Play voice").performClick()
        compose.runOnIdle { streams[0].events.loaded(90.0) }
        compose.runOnIdle { streams[0].events.interrupted() }
        compose.waitForIdle()
        assertEquals(true, streams[0].released)
        compose.onNodeWithContentDescription("Play voice").assertExists()
    }

    @Test
    fun poppingTheScreenStopsItsPlayers() {
        val navigator = Navigator()
        navigator.push(AppScreen.Settings)
        val players = navigator.top.get("speakPlayers") { SpeakPlayers() }
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        val controller = players.controller(clip, engine, context)
        compose.runOnIdle { controller.togglePlay() }
        assertEquals(1, streams.size)
        assertEquals(true, controller.state.active)
        compose.runOnIdle { navigator.pop() }
        assertEquals(true, streams[0].released)
        assertEquals(false, controller.state.active)
        assertEquals(false, SpeakPlayback.hasActive)
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
}
