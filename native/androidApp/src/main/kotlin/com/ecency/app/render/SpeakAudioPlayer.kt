package com.ecency.app.render

import androidx.compose.ui.text.style.TextDirection
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.ecency.core.render.blocks.Block
import com.ecency.core.render.blocks.SpeakAudio
import com.ecency.core.render.blocks.SpeakPlayback
import com.ecency.core.render.blocks.SpeakPlayerState
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.Role
import com.ecency.core.app.ReadingUiCopy

/** What a playing stream reports back; mirrors react-native-video's callbacks in RN's player. */
interface SpeakEvents {
    fun loadStarted()
    fun buffering(isBuffering: Boolean)
    fun loaded(durationSeconds: Double)
    fun ended()
    fun failed()

    /** The platform paused playback itself (audio focus lost, headphones unplugged). */
    fun interrupted()
}

/** One playing clip. Exists only while its row is playing. */
interface SpeakStream {
    val positionSeconds: Double
    fun seek(seconds: Double)
    fun release()
}

/** Opens streams; tests swap in a fake through [LocalSpeakEngine]. */
fun interface SpeakEngine {
    fun open(context: Context, url: String, events: SpeakEvents): SpeakStream
}

/** Media3 ExoPlayer, audio only, playing as soon as it is ready. */
object ExoSpeakEngine : SpeakEngine {
    override fun open(context: Context, url: String, events: SpeakEvents): SpeakStream {
        val player = ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        var ready = false
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> events.buffering(true)
                    Player.STATE_READY -> if (ready) {
                        events.buffering(false)
                    } else {
                        ready = true
                        events.loaded(player.duration.takeIf { it > 0 }?.let { it / 1000.0 } ?: Double.NaN)
                    }
                    Player.STATE_ENDED -> events.ended()
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) = events.failed()

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
                ) {
                    events.interrupted()
                }
            }
        })
        events.loadStarted()
        player.setMediaItem(MediaItem.fromUri(url))
        player.playWhenReady = true
        player.prepare()
        return object : SpeakStream {
            override val positionSeconds: Double get() = player.currentPosition / 1000.0
            override fun seek(seconds: Double) = player.seekTo((seconds * 1000).toLong())
            override fun release() = player.release()
        }
    }
}

val LocalSpeakEngine = staticCompositionLocalOf<SpeakEngine> { ExoSpeakEngine }

/** `$borderColor`, the same in both RN themes. */
private val SpeakUnplayed = Color(0xFFC5C5C5)

/**
 * One voice clip's player: RN `SpeakAudioPlayer`'s logic around the shared [SpeakPlayerState]. The
 * stream exists only while playing (pausing releases it, resuming seeks back) and starting one player
 * stops any other ([SpeakPlayback]). Main thread only.
 */
class SpeakController internal constructor(
    block: Block.SpeakAudio,
    private val engine: SpeakEngine,
    private val context: Context,
) : SpeakEvents {
    var block: Block.SpeakAudio by mutableStateOf(block)
        private set
    var state: SpeakPlayerState by mutableStateOf(SpeakPlayerState.initial(block))
        private set
    private var stream: SpeakStream? = null
    private var opening = false
    private val token = SpeakPlayback.Token { apply(state.stopped()) }

    /** The same clip rendered again with newer data (an edited post): take its peaks and duration. */
    internal fun refresh(next: Block.SpeakAudio) {
        if (next == block) return
        block = next
        if (!state.started) state = SpeakPlayerState.initial(next)
    }

    fun togglePlay() = apply(state.togglePlay())

    fun seek(fraction: Double) {
        val seek = state.seekToFraction(fraction)
        apply(seek.state)
        seek.to?.let { stream?.seek(it) }
    }

    /** Paused from outside: the app went to the background or the screen closed. */
    fun stop() = apply(state.stopped())

    /** RN's 250 ms `onProgress`, polled by the row while it is on screen. No stream, no change. */
    fun tick() {
        val s = stream ?: return
        state = state.progressed(s.positionSeconds)
    }

    override fun loadStarted() = apply(state.loadStarted())
    override fun buffering(isBuffering: Boolean) = apply(state.buffering(isBuffering))
    override fun loaded(durationSeconds: Double) {
        val seek = state.loaded(durationSeconds)
        apply(seek.state)
        seek.to?.let { stream?.seek(it) }
    }
    override fun ended() {
        stream?.seek(0.0)
        apply(state.ended())
    }
    override fun failed() = apply(state.failed())
    override fun interrupted() = stop()

    private fun apply(next: SpeakPlayerState) {
        state = next
        if (next.active) {
            SpeakPlayback.activate(token)
            if (stream == null && !opening && block.streamUrl.isNotEmpty()) {
                opening = true
                val opened = engine.open(context, block.streamUrl, this)
                opening = false
                stream = opened
                // A failure reported while opening already stopped this player.
                if (!state.active) release()
            }
        } else {
            SpeakPlayback.deactivate(token)
            release()
        }
    }

    private fun release() {
        stream?.let {
            stream = null
            it.release()
        }
    }
}

/**
 * The screen's voice players, keyed by stream: a playing row keeps playing while it scrolls off and
 * back (RN keeps the post body mounted). Held by the screen's stack entry, so a clip paused by a pushed
 * screen resumes where it was after the pop. Everything stops when the app pauses (RN
 * `playInBackground={false}` pauses on host pause), when the screen leaves composition and when its
 * entry closes.
 */
class SpeakPlayers : AutoCloseable {
    private val players = HashMap<Any, SpeakController>()

    fun controller(block: Block.SpeakAudio, engine: SpeakEngine, context: Context): SpeakController {
        val key: Any = block.streamUrl.ifEmpty { null } ?: block
        return players.getOrPut(key) { SpeakController(block, engine, context.applicationContext ?: context) }.also { it.refresh(block) }
    }

    fun stopAll() = players.values.forEach { it.stop() }

    /** The player for [streamUrl], if one was made (tests; does not refresh). */
    internal fun peek(streamUrl: String): SpeakController? = players[streamUrl]

    override fun close() = stopAll()
}

val LocalSpeakPlayers = staticCompositionLocalOf<SpeakPlayers?> { null }

/** Stops [players] when the app pauses and when this leaves composition (they are kept, not dropped). */
@Composable
private fun StopOnLeave(players: SpeakPlayers) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(players, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) players.stopAll() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            players.stopAll()
        }
    }
}

/** Gives the bodies in [content] [players] (keep them in the screen's stack entry so they survive a push). */
@Composable
fun ProvideSpeakPlayers(players: SpeakPlayers = remember { SpeakPlayers() }, content: @Composable () -> Unit) {
    StopOnLeave(players)
    CompositionLocalProvider(LocalSpeakPlayers provides players, content = content)
}

/** The row: draws [SpeakController.state]. Without a screen scope it gets a row-scoped one. */
@Composable
internal fun SpeakAudioPlayer(block: Block.SpeakAudio, width: Dp) {
    val provided = LocalSpeakPlayers.current
    val players = provided ?: remember { SpeakPlayers() }.also { StopOnLeave(it) }
    val engine = LocalSpeakEngine.current
    val context = LocalContext.current
    val controller = remember(players, block, engine) { players.controller(block, engine, context) }
    val theme = LocalPostTheme.current
    val state = controller.state
    LaunchedEffect(controller, state.active) {
        while (state.active) {
            delay(250)
            controller.tick()
        }
    }

    // media controls and their progress stay left to right in a right to left body or UI (Material bidirectionality:
    // playback runs along the tape, not the reading direction); the wave is drawn and tapped left to right
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) { Row(
        Modifier
            .padding(top = 8.dp, bottom = 4.dp)
            .width(width)
            .clip(RoundedCornerShape(14.dp))
            .background(theme.placeholder)
            .padding(vertical = 8.dp, horizontal = 10.dp)
            .testTag("speak"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(theme.link)
                .clickable(role = Role.Button) { controller.togglePlay() }
                .semantics { contentDescription = if (state.paused) ReadingUiCopy.PLAY_VOICE else ReadingUiCopy.PAUSE_VOICE }
                .testTag("speak:play"),
            contentAlignment = Alignment.Center,
        ) {
            if (state.showSpinner) {
                CircularProgressIndicator(Modifier.size(20.dp).testTag("speak:loading"), color = Color.White, strokeWidth = 2.dp)
            } else {
                PlayPauseGlyph(playing = !state.paused)
            }
        }
        var waveWidthPx by remember { mutableIntStateOf(0) }
        Box(
            Modifier
                .padding(start = 10.dp)
                .weight(1f)
                .height(SpeakAudio.WAVE_HEIGHT.dp)
                .onSizeChanged { waveWidthPx = it.width }
                .pointerInput(controller) {
                    detectTapGestures { offset ->
                        if (size.width > 0) controller.seek((offset.x / size.width).toDouble())
                    }
                }
                .semantics { contentDescription = ReadingUiCopy.VOICE_POSITION }
                .testTag("speak:wave"),
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(Modifier.fillMaxWidth().height(SpeakAudio.WAVE_HEIGHT.dp)) {
                if (waveWidthPx <= 0) return@Canvas
                val pt = density
                if (controller.block.peaks.isEmpty()) {
                    val top = (size.height - 4.dp.toPx()) / 2
                    val r = CornerRadius(2.dp.toPx())
                    drawRoundRect(SpeakUnplayed, Offset(0f, top), Size(size.width, 4.dp.toPx()), r)
                    drawRoundRect(theme.link, Offset(0f, top), Size(size.width * state.progress.toFloat(), 4.dp.toPx()), r)
                } else {
                    for (bar in SpeakAudio.bars(controller.block.peaks, (size.width / pt).toDouble(), state.progress)) {
                        drawRoundRect(
                            if (bar.played) theme.link else SpeakUnplayed,
                            Offset((bar.x * pt).toFloat(), (bar.y * pt).toFloat()),
                            Size((bar.width * pt).toFloat(), (bar.height * pt).toFloat()),
                            CornerRadius((bar.width * pt / 2).toFloat()),
                        )
                    }
                }
            }
        }
        BasicText(
            state.timeLabel,
            Modifier.padding(start = 10.dp).widthIn(min = 34.dp).testTag("speak:time"),
            style = TextStyle(color = theme.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, textDirection = TextDirection.Content),
        )
    } }
}

/** MaterialIcons `play-arrow` / `pause` at 24, drawn so the body renderer needs no icon font. */
@Composable
private fun PlayPauseGlyph(playing: Boolean) {
    Canvas(Modifier.size(24.dp)) {
        val u = size.width / 24f
        if (playing) {
            drawRect(Color.White, Offset(6 * u, 5 * u), Size(4 * u, 14 * u))
            drawRect(Color.White, Offset(14 * u, 5 * u), Size(4 * u, 14 * u))
        } else {
            val path = Path().apply {
                moveTo(8 * u, 5 * u)
                lineTo(8 * u, 19 * u)
                lineTo(19 * u, 12 * u)
                close()
            }
            drawPath(path, Color.White)
        }
    }
}
