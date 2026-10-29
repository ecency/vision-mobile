package com.ecency.core.render.blocks

import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostMetadata
import com.ecency.core.api.model.SpeakMeta
import com.ecency.core.app.RenderHelperPostBodyRenderer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Liketu Speak voice posts: RN's body cleanup and player logic, against goldens from the RN code. */
class SpeakAudioTest {
    @Test
    fun stripMatchesRn() {
        val bad = SpeakFixtures.strips.filter { SpeakAudio.stripBodyAudio(it.html) != it.stripped }
        assertEquals(emptyList(), bad.map { "${it.name}: ${SpeakAudio.stripBodyAudio(it.html)}" })
        assertTrue(SpeakFixtures.strips.count { it.html != it.stripped } >= 10, "most cases must strip something")
    }

    @Test
    fun utilsMatchRn() {
        for ((a, p, url) in SpeakFixtures.streamUrls) assertEquals(url, SpeakAudio.streamUrl(a, p), "$a $p")
        for ((d, s) in SpeakFixtures.durations) assertEquals(s, SpeakAudio.formatDuration(d), "$d")
        for ((c, p, n) in SpeakFixtures.playedBars) assertEquals(n, SpeakAudio.playedBarCount(c, p), "$c $p")
        for ((t, d, p) in SpeakFixtures.progress) assertEquals(p, SpeakAudio.playbackProgress(t, d), "$t $d")
    }

    @Test
    fun barsFollowRnLayout() {
        val bars = SpeakAudio.bars(listOf(0.5, 1.0, 0.0, 0.25), width = 100.0, progress = 0.5)
        // barW = (100 - 3 * 2) / 4 = 23.5
        assertEquals(listOf(0.0, 25.5, 51.0, 76.5), bars.map { it.x })
        assertEquals(listOf(17.0, 34.0, 3.0, 8.5), bars.map { it.height })
        assertEquals(listOf(8.5, 0.0, 15.5, 12.75), bars.map { it.y })
        assertEquals(listOf(true, true, false, false), bars.map { it.played })
        // Very many peaks: bars never get thinner than 1.5 points.
        assertEquals(1.5, SpeakAudio.bars(List(200) { 1.0 }, 100.0, 0.0).first().width)
        // All-zero peaks: minimum height, no division by zero.
        assertEquals(listOf(3.0, 3.0), SpeakAudio.bars(listOf(0.0, 0.0), 10.0, 0.0).map { it.height })
        assertEquals(emptyList(), SpeakAudio.bars(emptyList(), 100.0, 0.5))
        assertEquals(emptyList(), SpeakAudio.bars(listOf(1.0), 0.0, 0.5))
    }

    @Test
    fun metadataParse() {
        fun parse(json: String) = PostMetadata.parse(Json.parseToJsonElement(json)).speak
        assertEquals(
            SpeakMeta("https://cdn.liketu.com/a.webm", 12_500, listOf(0.1, 1.0, 2.0)),
            parse("""{"speak":{"audio_url":"https://cdn.liketu.com/a.webm","duration_ms":12500.7,"waveform_peaks":[0.1,1,"x",2,null]}}"""),
        )
        assertEquals(SpeakMeta("u"), parse("""{"speak":{"audio_url":"u","duration_ms":-5}}"""))
        // RN reads duration_ms with JS `> 0` and `/ 1000`: numeric strings count.
        assertEquals(SpeakMeta("u", 9000), parse("""{"speak":{"audio_url":"u","duration_ms":" 9000 ","waveform_peaks":"1,2"}}"""))
        assertEquals(SpeakMeta("u"), parse("""{"speak":{"audio_url":"u","duration_ms":"9s"}}"""))
        assertEquals(SpeakMeta("u", 1500), parse("""{"speak":{"audio_url":"u","duration_ms":"1.5e3"}}"""))
        assertEquals(SpeakMeta("u", 500), parse("""{"speak":{"audio_url":"u","duration_ms":".5e3"}}"""))
        for (odd in listOf("9000d", "9000f", "0x10", "Infinity", "-5", "NaN", "1_000", "9000L")) {
            assertEquals(SpeakMeta("u"), parse("""{"speak":{"audio_url":"u","duration_ms":"$odd"}}"""), odd)
        }
        // RN shows the player for any JS-truthy audio_url.
        assertEquals(SpeakMeta("5"), parse("""{"speak":{"audio_url":5}}"""))
        assertEquals(SpeakMeta("true"), parse("""{"speak":{"audio_url":true}}"""))
        assertEquals(SpeakMeta("{}"), parse("""{"speak":{"audio_url":{}}}"""))
        assertNull(parse("""{"speak":{"audio_url":""}}"""))
        assertNull(parse("""{"speak":{"audio_url":0}}"""))
        assertNull(parse("""{"speak":{"audio_url":false}}"""))
        assertNull(parse("""{"speak":{"audio_url":null}}"""))
        assertNull(parse("""{"speak":{}}"""))
        assertNull(parse("""{"speak":"https://cdn.liketu.com/a.webm"}"""))
        assertNull(parse("""{"image":["x"]}"""))
    }

    @Test
    fun rendererAddsPlayerAndDropsAudioOnlyForSpeakPosts() {
        val renderer = RenderHelperPostBodyRenderer()
        val body = "Hello\n\n[Listen to the original voice](https://cdn.liketu.com/audio/x.webm)\n\n<audio src=\"https://example.com/a.mp3\"></audio>"
        val meta = PostMetadata.parse(
            Json.parseToJsonElement("""{"speak":{"audio_url":"https://cdn.liketu.com/audio/x.webm","duration_ms":4000,"waveform_peaks":[1,2]}}"""),
        )
        val speak = renderer.blocks(Post(author = "example-user", permlink = "re-liketu-speak-x", body = body, metadata = meta))
        assertEquals(
            Block.SpeakAudio("https://ecency.com/api/speak-audio?author=example-user&permlink=re-liketu-speak-x", 4000, listOf(1.0, 2.0)),
            speak.last(),
        )
        assertEquals(1, speak.count { it is Block.SpeakAudio })
        assertEquals("Hello", BlockText.of(speak).trim())

        val plain = renderer.blocks(Post(author = "example-user", permlink = "p", body = body))
        assertTrue(plain.none { it is Block.SpeakAudio })
    }

    @Test
    fun playerStateFollowsRn() {
        val block = Block.SpeakAudio("u", 90_000, emptyList())
        var s = SpeakPlayerState.initial(block)
        assertEquals(90.0, s.duration)
        assertEquals("1:30", s.timeLabel)
        assertFalse(s.active)

        // Play: the platform player is created; loading shows the spinner only while playing.
        s = s.togglePlay().loadStarted()
        assertTrue(s.active)
        assertTrue(s.showSpinner)
        assertEquals("0:00", s.timeLabel)
        var seek = s.loaded(88.0)
        assertEquals(0.0, seek.to)
        s = seek.state
        assertEquals(88.0, s.duration)
        assertFalse(s.loading)
        s = s.progressed(30.0)
        assertEquals("0:30", s.timeLabel)

        // Pause keeps the position; a waveform tap while paused moves it without a live seek.
        s = s.togglePlay()
        assertFalse(s.active)
        seek = s.seekToFraction(0.5)
        assertEquals(44.0, seek.to)
        s = seek.state
        assertEquals(44.0, s.currentTime)
        // Resume: the new player seeks back to where it was.
        s = s.togglePlay().loadStarted()
        assertFalse(s.copy(paused = true).showSpinner)
        s = s.progressed(0.0) // ignored while loading
        assertEquals(44.0, s.currentTime)
        seek = s.loaded(Double.NaN)
        assertEquals(44.0, seek.to)
        assertEquals(88.0, seek.state.duration)

        // End: back to the start, paused.
        s = seek.state.ended()
        assertFalse(s.active)
        assertEquals(0.0, s.currentTime)

        // Error: tappable paused state.
        s = s.togglePlay().loadStarted().failed()
        assertFalse(s.started)
        assertFalse(s.showSpinner)
        assertEquals("1:28", s.timeLabel)
    }

    @Test
    fun waveformTapBeforeFirstPlayStartsThere() {
        var s = SpeakPlayerState.initial(Block.SpeakAudio("u", 10_000, listOf(1.0)))
        val seek = s.seekToFraction(1.7)
        assertNull(seek.to)
        s = seek.state
        assertTrue(s.active)
        assertEquals(10.0, s.currentTime)
        assertEquals(10.0, s.loaded(10.0).to)
        assertNull(s.loaded(10.0).state.seekOnLoad)
        // No duration known yet: taps do nothing.
        val none = SpeakPlayerState.initial(Block.SpeakAudio("u", 0, emptyList()))
        assertEquals(SpeakPlayerState.Seek(none, null), none.seekToFraction(0.5))
    }

    @Test
    fun onlyOnePlayerAudible() {
        val stopped = ArrayList<String>()
        val a = SpeakPlayback.Token { stopped += "a" }
        val b = SpeakPlayback.Token { stopped += "b" }
        SpeakPlayback.activate(a)
        SpeakPlayback.activate(a)
        assertEquals(emptyList(), stopped)
        SpeakPlayback.activate(b)
        assertEquals(listOf("a"), stopped)
        SpeakPlayback.deactivate(a) // not the active one: b stays registered
        SpeakPlayback.activate(a)
        assertEquals(listOf("a", "b"), stopped)
        SpeakPlayback.deactivate(a)
        SpeakPlayback.activate(b)
        assertEquals(listOf("a", "b"), stopped)
        SpeakPlayback.deactivate(b)
    }
}
