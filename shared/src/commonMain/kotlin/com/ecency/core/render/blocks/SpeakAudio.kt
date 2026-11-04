package com.ecency.core.render.blocks

import com.ecency.core.render.js.Js
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Liketu "Speak" voice posts (`json_metadata.speak`), as the RN app's `postHtmlRenderer` and
 * `speakAudioPlayer` handle them: the body's own audio markup is removed and a native player row
 * (play button, waveform, time) is drawn under the body.
 */
object SpeakAudio {
    /** vision-web's endpoint that transcodes the Opus/WebM clip to AAC so AVPlayer can play it. */
    const val ENDPOINT = "https://ecency.com/api/speak-audio"

    const val WAVE_HEIGHT = 34.0
    const val MIN_BAR_HEIGHT = 3.0
    const val BAR_GAP = 2.0

    /**
     * Drops `<audio>` elements and Liketu audio links from render-helper's HTML, as RN does for a Speak
     * post: `.replace(/<audio[\s\S]*?<\/audio>/gi, '')` then
     * `.replace(/<a[^>]*href="[^"]*cdn\.liketu\.com[^"]*\.(?:webm|mp3|m4a|ogg|wav)[^"]*"[^>]*>[\s\S]*?<\/a>/gi, '')`.
     * Hand-written scanners (ASCII case-insensitive like JS `/i`): `[\s\S]` must take every UTF-16
     * unit, including both halves of a surrogate pair, which a JVM or Kotlin/Native class range cannot.
     */
    fun stripBodyAudio(html: String): String {
        if (html.isEmpty()) return html
        return stripLiketuLinks(stripAudio(html))
    }

    /** `/<audio[\s\S]*?<\/audio>/gi` replaced with "". */
    internal fun stripAudio(s: String): String {
        val out = StringBuilder(s.length)
        var pos = 0
        while (true) {
            val open = Js.indexOfCi(s, "<audio", pos)
            if (open < 0) break
            val close = Js.indexOfCi(s, "</audio>", open + 6)
            // No close after this start means none after any later start either.
            if (close < 0) break
            out.append(s, pos, open)
            pos = close + 8
        }
        out.append(s, pos, s.length)
        return out.toString()
    }

    private val EXTS = listOf(".webm", ".mp3", ".m4a", ".ogg", ".wav")

    /**
     * The Liketu audio anchor expression replaced with "". Linear: every search the regex repeats per
     * start is looked up in tables built once (next `>`, `"`, host, extension end and `</a>` at or after
     * each index), and an `href="`'s outcome does not depend on where the anchor starts.
     */
    internal fun stripLiketuLinks(s: String): String {
        if (Js.indexOfCi(s, "cdn.liketu.com") < 0) return s
        val n = s.length
        val nextGt = nextIndex(n) { s[it] == '>' }
        val nextQuote = nextIndex(n) { s[it] == '"' }
        val nextHost = nextIndex(n) { Js.startsWithCi(s, "cdn.liketu.com", it) }
        val nextClose = nextIndex(n) { Js.startsWithCi(s, "</a>", it) }
        // End of the earliest extension starting at or after each index (the earliest start also ends first).
        val nextExtEnd = IntArray(n + 1) { -1 }
        for (k in n - 1 downTo 0) {
            val ext = EXTS.firstOrNull { Js.startsWithCi(s, it, k) }
            nextExtEnd[k] = if (ext != null) k + ext.length else nextExtEnd[k + 1]
        }
        // matchEnd[h]: where a match ends when the regex settles on the `href="` at h, or -1.
        // lastHref[i]: the rightmost h <= i with a match.
        val lastHref = IntArray(n + 1) { -1 }
        val matchEnd = IntArray(n + 1) { -1 }
        for (h in 0..n) {
            if (h + 6 <= n && Js.startsWithCi(s, "href=\"", h)) matchEnd[h] = afterHref(h + 6, nextQuote, nextHost, nextExtEnd, nextGt, nextClose)
            lastHref[h] = if (matchEnd[h] >= 0) h else if (h > 0) lastHref[h - 1] else -1
        }
        val out = StringBuilder(n)
        var pos = 0
        var p = 0
        while (true) {
            p = Js.indexOfCi(s, "<a", p)
            if (p < 0) break
            // `[^>]*` runs to the first `>` (or the end); `href="` must sit wholly before it, and greedy
            // backtracking tries the rightmost one first.
            val tagEnd = nextGt[minOf(p + 2, n)].let { if (it < 0) n else it }
            val h = if (tagEnd - 6 >= 0) lastHref[tagEnd - 6] else -1
            if (h < p + 2) {
                p++
                continue
            }
            val end = matchEnd[h]
            // A match always ends past its `<a`; never loop in place if that ever breaks.
            if (end <= p) break
            out.append(s, pos, p)
            pos = end
            p = pos
        }
        out.append(s, pos, n)
        return out.toString()
    }

    /** From the value start [v]: `[^"]*cdn\.liketu\.com[^"]*\.(?:ext)[^"]*"[^>]*>[\s\S]*?<\/a>`, every search bounded by the closing quote. */
    private fun afterHref(v: Int, nextQuote: IntArray, nextHost: IntArray, nextExtEnd: IntArray, nextGt: IntArray, nextClose: IntArray): Int {
        val quote = nextQuote[v]
        if (quote < 0) return -1
        val host = nextHost[v]
        if (host < 0) return -1
        // An extension that ends by the quote also puts the host before it.
        val extEnd = nextExtEnd[host + 14]
        if (extEnd < 0 || extEnd > quote) return -1
        val gt = nextGt[quote + 1]
        if (gt < 0) return -1
        val close = nextClose[gt + 1]
        return if (close < 0) -1 else close + 4
    }

    /** For each index 0..n: the first index at or after it where [at] holds, or -1. */
    private inline fun nextIndex(n: Int, at: (Int) -> Boolean): IntArray {
        val next = IntArray(n + 1)
        next[n] = -1
        for (k in n - 1 downTo 0) next[k] = if (at(k)) k else next[k + 1]
        return next
    }

    /** `getSpeakAudioStreamUrl`: the transcoded stream for a wave, or "" without author and permlink. */
    fun streamUrl(author: String?, permlink: String?): String =
        if (author.isNullOrEmpty() || permlink.isNullOrEmpty()) {
            ""
        } else {
            "$ENDPOINT?author=${encodeComponent(author)}&permlink=${encodeComponent(permlink)}"
        }

    /** `formatDuration`: seconds as `m:ss`; anything not finite or not positive is `0:00`. */
    fun formatDuration(seconds: Double): String {
        val s = if (seconds.isFinite() && seconds > 0) floor(seconds).toLong() else 0L
        val m = s / 60
        return "$m:${(s % 60).toString().padStart(2, '0')}"
    }

    /** `playedBarCount`: bars drawn as played for a progress fraction, clamped to the bar count. */
    fun playedBarCount(barCount: Int, progress: Double): Int {
        if (barCount <= 0) return 0
        val clamped = max(0.0, min(1.0, if (progress.isFinite()) progress else 0.0))
        return min(barCount, jsRound(clamped * barCount))
    }

    /** `playbackProgress`: 0..1 from the current time and the duration. */
    fun playbackProgress(currentTime: Double, duration: Double): Double {
        if (!duration.isFinite() || duration <= 0) return 0.0
        return max(0.0, min(1.0, currentTime / duration))
    }

    /** One waveform bar in points, relative to the waveform's top left. */
    data class Bar(val x: Double, val y: Double, val width: Double, val height: Double, val played: Boolean)

    /** The RN player's bar layout for [width] points: heights relative to the loudest peak, rounded caps. */
    fun bars(peaks: List<Double>, width: Double, progress: Double): List<Bar> {
        if (peaks.isEmpty() || width <= 0) return emptyList()
        val maxPeak = max(peaks.max(), 0.0001)
        val barW = max(1.5, (width - (peaks.size - 1) * BAR_GAP) / peaks.size)
        val played = playedBarCount(peaks.size, progress)
        return peaks.mapIndexed { i, peak ->
            val h = max(MIN_BAR_HEIGHT, peak / maxPeak * WAVE_HEIGHT)
            Bar(i * (barW + BAR_GAP), (WAVE_HEIGHT - h) / 2, barW, h, i < played)
        }
    }

    /** JS `Math.round` (halves round up). */
    private fun jsRound(x: Double): Int = floor(x + 0.5).roundToInt()

    /** `encodeURIComponent` for account names and permlinks. */
    private fun encodeComponent(s: String): String = Js.encodeUri(s)
        .replace("#", "%23").replace("$", "%24").replace("&", "%26").replace("+", "%2B").replace(",", "%2C")
        .replace("/", "%2F").replace(":", "%3A").replace(";", "%3B").replace("=", "%3D").replace("?", "%3F").replace("@", "%40")
}

/**
 * The RN player's state (`speakAudioPlayer.tsx`), as an immutable value: the UI keeps one per row and
 * drives a platform player from [active]. The platform player exists only while [active], as RN mounts
 * its `<Video>` only while playing, so at most one is alive across the screen.
 */
data class SpeakPlayerState(
    val started: Boolean = false,
    val paused: Boolean = true,
    val loading: Boolean = false,
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    /** A seek tapped before the stream loaded; applied by [loaded]. */
    val seekOnLoad: Double? = null,
) {
    /** The platform player should exist and play. */
    val active: Boolean get() = started && !paused
    val progress: Double get() = SpeakAudio.playbackProgress(currentTime, duration)
    val timeLabel: String get() = SpeakAudio.formatDuration(if (started) currentTime else duration)

    /** The spinner shows only while playing, so a late buffering event never hides the play button. */
    val showSpinner: Boolean get() = loading && !paused

    fun togglePlay(): SpeakPlayerState = copy(started = true, paused = !paused)

    /** A pause from elsewhere (another player started). */
    fun stopped(): SpeakPlayerState = copy(paused = true)

    /** Waveform tap at [fraction]. [Seek.to] is for the live player, null when none should seek now. */
    fun seekToFraction(fraction: Double): Seek {
        if (duration <= 0) return Seek(this, null)
        val target = max(0.0, min(1.0, fraction)) * duration
        return if (started) {
            Seek(copy(currentTime = target), target)
        } else {
            Seek(copy(currentTime = target, seekOnLoad = target, started = true, paused = false), null)
        }
    }

    fun loadStarted(): SpeakPlayerState = copy(loading = true)

    fun buffering(isBuffering: Boolean): SpeakPlayerState = copy(loading = isBuffering)

    /** The stream loaded with [streamDuration] seconds: seek to the tapped spot, else where it paused. */
    fun loaded(streamDuration: Double): Seek {
        val next = copy(
            duration = if (streamDuration.isFinite() && streamDuration > 0) streamDuration else duration,
            seekOnLoad = null,
            loading = false,
        )
        return Seek(next, seekOnLoad ?: currentTime)
    }

    fun progressed(time: Double): SpeakPlayerState = if (loading) this else copy(currentTime = time)

    /** Played to the end: back to the start, paused. */
    fun ended(): SpeakPlayerState = copy(paused = true, currentTime = 0.0)

    /** Stream failure: a tappable paused state instead of a spinner. */
    fun failed(): SpeakPlayerState = copy(loading = false, paused = true, started = false)

    data class Seek(val state: SpeakPlayerState, val to: Double?)

    companion object {
        fun initial(block: Block.SpeakAudio): SpeakPlayerState =
            SpeakPlayerState(duration = if (block.durationMs > 0) block.durationMs / 1000.0 else 0.0)
    }
}

/**
 * `speakPlaybackCoordinator`: one Speak player audible at a time. A player that starts registers its
 * token; the previous one is stopped. Main thread only.
 */
object SpeakPlayback {
    class Token(val stop: () -> Unit)

    private var active: Token? = null

    /** A player is registered as the audible one (tests check that stopped players unregister). */
    val hasActive: Boolean get() = active != null

    fun activate(token: Token) {
        val previous = active
        if (previous != null && previous !== token) previous.stop()
        active = token
    }

    fun deactivate(token: Token) {
        if (active === token) active = null
    }
}
