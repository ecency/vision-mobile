package com.ecency.app.ui

import androidx.compose.foundation.layout.heightIn


import com.ecency.core.app.AppIcon
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ecency.app.ListenEngine
import com.ecency.app.ListenEvents
import com.ecency.app.ListenItem
import com.ecency.app.ListenStream
import com.ecency.core.api.ecency.ListenTrack
import com.ecency.core.app.Listen
import com.ecency.core.app.ListenAlert
import com.ecency.core.app.ListenBarState
import com.ecency.core.app.ListenButton
import com.ecency.core.app.ListenModel
import com.ecency.core.app.ListenPlayerState
import com.ecency.core.render.blocks.SpeakPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import com.ecency.core.app.ReadingUiCopy
import com.ecency.core.app.UiCopy

/**
 * RN `listenAudio.tsx` around the shared [ListenPlayerState]: the stream exists from the first play on (paused or not,
 * so the media notification keeps its controls), starting it stops any other player app-wide ([SpeakPlayback], shared
 * with 3Speak voice posts), and an expired URL is renewed through [ListenModel.renewUrls]. Held by the post screen's
 * stack entry: it keeps playing behind pushed screens and in the background, and stops when the screen closes.
 * Main thread only.
 */
class ListenController(
    private val model: ListenModel,
    private val engine: ListenEngine,
    context: Context,
    private val uiLanguage: String,
    /** Stops this player when accounts leave the device (logout, wipe); null in tests that do not need it. */
    private val sessions: com.ecency.core.app.BackgroundSessions? = null,
    /** Emits when the now playing card must be read again (the PIN lock turned on or off); null in tests that do not need it. */
    cardChanges: kotlinx.coroutines.flow.Flow<Any?>? = null,
    private val describe: (ListenTrack) -> ListenItem,
) : ListenEvents, AutoCloseable {
    private val context = context.applicationContext ?: context
    var state: ListenPlayerState? by mutableStateOf(null)
        private set
    var tracks: List<ListenTrack> by mutableStateOf(emptyList())
        private set
    private var stream: ListenStream? = null
    private var streamUrl: String? = null

    /** What the stream was last told (play or pause), so a state change sends each command once. */
    private var commandedPlaying = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var renewal: Job? = null
    private val token = SpeakPlayback.Token { state?.let { apply(it.stopped()) } }

    /** The open stream (tests). */
    val hasStream: Boolean get() = stream != null

    private val process: androidx.lifecycle.Lifecycle? =
        runCatching { androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle }.getOrNull()
    private val appState = androidx.lifecycle.LifecycleEventObserver { _, event ->
        when (event) {
            androidx.lifecycle.Lifecycle.Event.ON_START -> model.onForeground()
            androidx.lifecycle.Lifecycle.Event.ON_STOP -> model.onBackground()
            else -> Unit
        }
    }

    /** Teardown: the audio stops and its media notification and lock screen card go (README "Background media"). */
    /** Registered for each open stream (a teardown empties the registry, a later stream registers again). */
    private var teardown: com.ecency.core.app.BackgroundSessions.Entry? = null

    init {
        scope.launch { model.state.collect { onTracks(it) } }
        // the lock screen card follows the PIN lock setting while it plays (no artwork while the lock is on)
        cardChanges?.let { changes -> scope.launch { changes.collect { retitle() } } }
        // the app in the background or back (not the bar's row, which leaves the screen as the post scrolls)
        process?.addObserver(appState)
    }

    private fun onTracks(bar: ListenBarState) {
        val next = if (bar.showsPlayer) bar.tracks else emptyList()
        if (next.isEmpty()) {
            // no audio for this reader any more (another account, an edit that queued new audio): the player goes
            tracks = emptyList()
            state = null
            stopStream()
            return
        }
        tracks = next
        val s = state
        if (s == null) {
            state = ListenPlayerState.initial(next, uiLanguage)
            return
        }
        // new signed URLs (a status renewal): the source follows, from where it is
        if (renewal == null && stream != null && s.track(next)?.url != streamUrl) reopen()
    }

    // ---- what the row does

    /** Play or pause. Play on a player whose URL could not be renewed tries once more first (RN plays the dead URL). */
    fun toggle() {
        val s = state ?: return
        if (s.failed && s.paused) {
            state = s.retry().toggle()
            renew()
            return
        }
        apply(s.toggle())
    }

    fun nextSpeed() {
        val next = state?.nextSpeed() ?: return
        apply(next)
        stream?.setSpeed(next.speed)
    }

    fun switchTrack(lang: String) {
        val s = state ?: return
        val next = s.switchTrack(lang, s.track(tracks)) ?: return
        state = next
        if (stream != null) reopen()
    }

    fun drag(value: Double) {
        state = state?.drag(value)
    }

    fun dragEnded(value: Double) {
        val step = state?.dragEnded(value) ?: return
        state = step.state
        step.seekTo?.let { stream?.seek(it) }
    }

    /** "Try again" after the URL could not be renewed. */
    fun retry() {
        val s = state ?: return
        state = s.retry()
        renew()
    }

    // ---- what the stream reports

    override fun loadStarted() {
        state = state?.loadStarted()
    }

    override fun buffering(isBuffering: Boolean) {
        state = state?.buffering(isBuffering)
    }

    override fun loaded(durationSeconds: Double) {
        val step = state?.loaded(durationSeconds) ?: return
        state = step.state
        step.seekTo?.let { stream?.seek(it) }
    }

    override fun playingChanged(isPlaying: Boolean) {
        val s = state ?: return
        if (isPlaying && s.failed) {
            // the lock screen or a headset asked to play a source that could not be had: refused, "Try again" stays
            stream?.pause()
            return
        }
        val next = s.playbackChanged(isPlaying)
        // the player is already doing it: nothing to tell it back
        if (next.paused == !isPlaying) commandedPlaying = isPlaying
        apply(next)
    }

    override fun ended() {
        val step = state?.ended() ?: return
        stream?.seek(0.0)
        apply(step.state)
    }

    override fun failed() {
        val s = state ?: return
        val (next, renewIt) = s.error()
        // the stream (and with it the media session and its foreground service) stays: new URLs load into it
        stopTicker()
        state = next
        if (renewIt) renew() else apply(next)
    }

    private fun renew() {
        renewal?.cancel()
        // assigned before it runs: on the immediate dispatcher a renewal may finish before launch returns
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val ok = try {
                model.renewUrls()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (renewal === coroutineContext[Job]) renewal = null
            val s = state ?: return@launch
            val next = s.renewed(ok)
            state = next
            if (ok) {
                // the renewed URLs, even when the tracks collector has not run yet: never reload the expired one
                freshTracks()
                if (next.started) reopen()
            } else {
                apply(next)
            }
        }
        renewal = job
        job.start()
    }

    /** The model's tracks right now, so a renewal does not wait for the collector to hand them over. */
    private fun freshTracks() {
        val bar = model.current
        if (bar.showsPlayer && bar.tracks.isNotEmpty()) tracks = bar.tracks
    }

    // ---- the stream

    private fun apply(next: ListenPlayerState) {
        state = next
        // no source while new URLs are asked for, nor after they could not be had (until "Try again")
        if (next.started && stream == null && renewal == null && !next.failed) open(next)
        val playing = !next.paused
        if (playing) SpeakPlayback.activate(token) else SpeakPlayback.deactivate(token)
        if (playing != commandedPlaying) {
            commandedPlaying = playing
            if (playing) stream?.play() else stream?.pause()
        }
        if (playing) startTicker() else stopTicker()
    }

    private fun open(s: ListenPlayerState) {
        val track = s.track(tracks) ?: return
        streamUrl = track.url
        commandedPlaying = false
        val opened = engine.open(context, describe(track), this)
        stream = opened
        teardown?.close()
        teardown = sessions?.register { teardown() }
        opened.setSpeed(s.speed)
    }

    /** A new source for the same player: a switched track or a renewed URL, picked up at [ListenPlayerState.resume]. */
    private fun reopen() {
        val s = state ?: return
        if (!s.started) {
            releaseStream()
            return
        }
        val current = stream
        val track = s.track(tracks)
        if (current != null && track != null) {
            // the same player and session: the notification and the foreground service stay up across a new URL
            streamUrl = track.url
            commandedPlaying = false
            current.load(describe(track))
            current.setSpeed(s.speed)
        } else {
            releaseStream()
            open(s)
        }
        apply(state ?: return)
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        // RN's progressUpdateInterval, 500 ms; runs in the background too (the renewal count depends on it)
        ticker = scope.launch {
            while (isActive) {
                delay(500)
                val s = state ?: break
                val position = stream?.positionSeconds ?: continue
                if (!s.loading) state = s.progressed(position)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun releaseStream() {
        teardown?.close()
        teardown = null
        stopTicker()
        stream?.let {
            stream = null
            streamUrl = null
            it.release()
        }
        commandedPlaying = false
    }

    private fun stopStream() {
        SpeakPlayback.deactivate(token)
        releaseStream()
    }

    private fun retitle() {
        val s = state ?: return
        val current = stream ?: return
        val track = s.track(tracks) ?: return
        if (track.url == streamUrl) current.retitle(describe(track))
    }

    /** Accounts left the device: stopped where it was, the stream released (its notification goes with it). */
    private fun teardown() {
        renewal?.cancel()
        renewal = null
        state = state?.stopped()
        stopStream()
        com.ecency.app.ListenPlaybackService.cancelResumeNotice(context)
    }

    /** The screen closed: the audio stops and the media notification goes. */
    override fun close() {
        process?.removeObserver(appState)
        renewal?.cancel()
        stopStream()
        scope.cancel()
    }
}

// ---------------------------------------------------------------- the bar

/**
 * RN `ListenBar` above the post body: log in, unlock for Points (confirmed first), the audio being made, or the player.
 * The app's foreground and background reach the model through [ListenController] (the screen's owner).
 */
@Composable
fun ListenSection(model: ListenModel, controller: ListenController, onLogin: () -> Unit, onGetPoints: () -> Unit) {
    val s by model.state.collectAsState()
    if (!s.hidden) {
        val player = controller.state
        if (s.showsPlayer && player != null) ListenPlayerRow(controller, player) else ListenBarRow(s, model, onLogin)
    }
    ListenDialogs(s, model, onGetPoints)
}

@Composable
private fun ListenBarRow(s: ListenBarState, model: ListenModel, onLogin: () -> Unit) {
    val colors = LocalEcencyColors.current
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, colors.lightBackground, RoundedCornerShape(12.dp))
            .background(colors.background).padding(horizontal = 12.dp, vertical = 10.dp).testTag("listenBar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Glyph(AppIcon.HEADPHONES, colors.blueText, 22.dp)
        Column(Modifier.weight(1f).widthIn(min = 120.dp)) {
            Text(ReadingUiCopy.LISTEN_TO_THIS_POST, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.text)
            Text(s.subtitle, fontSize = 12.sp, color = colors.darkGray, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp).testTag("listenSubtitle"))
        }
        if (s.preparing) Preparing(s)
        when (s.button) {
            ListenButton.LOGIN -> PillButton(ReadingUiCopy.LOG_IN, "listenLogin", onClick = onLogin)
            ListenButton.LOADING -> CircularProgressIndicator(Modifier.size(20.dp).testTag("listenLoading"), color = colors.blueText, strokeWidth = 2.dp)
            ListenButton.UNLOCK -> PillButton(s.unlockLabel, "listenUnlock", busy = s.busy, onClick = model::requestUnlock)
            ListenButton.NONE -> Unit
        }
    }
}

/** RN `PreparingState`: the server's percentage once the worker has the job, else the place in line or an estimate. */
@Composable
private fun Preparing(s: ListenBarState) {
    val colors = LocalEcencyColors.current
    val percent = s.preparingPercent
    if (percent != null) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.testTag("listenProgress").semantics { contentDescription = "$percent%" }) {
            Box(Modifier.width(96.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.lightBackground)) {
                Box(Modifier.width((96 * percent / 100).dp).height(6.dp).background(colors.blue))
            }
            Text(s.preparingText, fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.padding(top = 4.dp))
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("listenPreparing")) {
            CircularProgressIndicator(Modifier.size(16.dp), color = colors.blueText, strokeWidth = 2.dp)
            Text(s.preparingText, fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun PillButton(label: String, tag: String, busy: Boolean = false, onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    Box(
        Modifier.clip(RoundedCornerShape(16.dp)).background(colors.blue).clickable(enabled = !busy, role = Role.Button, onClick = onClick)
            .widthIn(min = 72.dp).padding(horizontal = 14.dp, vertical = 7.dp).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp).testTag("$tag:busy"), color = androidx.compose.ui.graphics.Color.White, strokeWidth = 2.dp)
        else Text(label, color = androidx.compose.ui.graphics.Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListenPlayerRow(controller: ListenController, s: ListenPlayerState) {
    val colors = LocalEcencyColors.current
    val tracks = controller.tracks
    val current = s.track(tracks)
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, colors.lightBackground, RoundedCornerShape(12.dp))
            .background(colors.background).padding(horizontal = 10.dp, vertical = 8.dp).testTag("listenPlayer"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // the transport and its scrubber stay left to right in RTL (Material: media playback is not mirrored)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(colors.blue).clickable(role = Role.Button) { controller.toggle() }
                    .semantics { contentDescription = if (s.paused) ReadingUiCopy.PLAY else ReadingUiCopy.PAUSE }.testTag("listenPlay"),
                contentAlignment = Alignment.Center,
            ) {
                if (s.showSpinner) CircularProgressIndicator(Modifier.size(20.dp).testTag("listenBuffering"), color = androidx.compose.ui.graphics.Color.White, strokeWidth = 2.dp)
                else Glyph(if (s.paused) AppIcon.PLAY else AppIcon.PAUSE, androidx.compose.ui.graphics.Color.White, 24.dp, filled = true)
            }
            Text(s.timeLabel, fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.testTag("listenTime"))
            Slider(
                value = s.shownTime.toFloat().coerceIn(0f, s.sliderMax.toFloat()),
                onValueChange = { controller.drag(it.toDouble()) },
                onValueChangeFinished = { controller.state?.seeking?.let(controller::dragEnded) },
                valueRange = 0f..s.sliderMax.toFloat(),
                colors = SliderDefaults.colors(thumbColor = colors.blueText, activeTrackColor = colors.blueText, inactiveTrackColor = colors.lightBackground),
                modifier = Modifier.weight(1f).height(32.dp).semantics { contentDescription = ReadingUiCopy.SEEK }.testTag("listenSeek"),
            )
            Text(s.durationLabel, fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.testTag("listenDuration"))
            Chip(s.speedLabel, "listenSpeed", description = ReadingUiCopy.PLAYBACK_SPEED) { controller.nextSpeed() }
        }
        }
        if (s.failed) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("listenFailed")) {
                Text(ReadingUiCopy.THE_AUDIO_COULD_NOT, fontSize = 12.sp, color = colors.darkGray, modifier = Modifier.weight(1f))
                Text(
                    UiCopy.TRY_AGAIN, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.blueText,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(role = Role.Button) { controller.retry() }.padding(4.dp).testTag("listenRetry"),
                )
            }
        }
        if (tracks.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.semantics { contentDescription = ReadingUiCopy.AUDIO_LANGUAGE }) {
                tracks.forEach { t ->
                    Chip(Listen.languageName(t.lang), "listenLang:${t.lang}", active = t.lang == current?.lang) { controller.switchTrack(t.lang) }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, tag: String, active: Boolean = false, description: String? = null, onClick: () -> Unit) {
    val colors = LocalEcencyColors.current
    Text(
        label, fontSize = 12.sp, color = if (active) colors.blueText else colors.text,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, if (active) colors.blueText else colors.lightBackground, RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics { selected = active; description?.let { contentDescription = it } }.testTag(tag),
    )
}

@Composable
private fun ListenDialogs(s: ListenBarState, model: ListenModel, onGetPoints: () -> Unit) {
    val confirm = s.confirmText
    if (confirm != null) {
        AlertDialog(
            onDismissRequest = model::cancelUnlock,
            title = { Text(ReadingUiCopy.LISTEN_TO_THIS_POST) },
            text = { Text(confirm) },
            dismissButton = { TextButton(onClick = model::cancelUnlock, modifier = Modifier.testTag("listenCancel")) { Text(UiCopy.CANCEL) } },
            confirmButton = { TextButton(onClick = model::confirmUnlock, modifier = Modifier.testTag("listenConfirm")) { Text(UiCopy.CONFIRM) } },
            modifier = Modifier.testTag("listenConfirmDialog"),
        )
    }
    when (val alert = s.alert) {
        is ListenAlert.NotEnoughPoints -> AlertDialog(
            onDismissRequest = model::dismissAlert,
            title = { Text(ReadingUiCopy.NOT_ENOUGH_POINTS) },
            text = { Text(alert.message) },
            dismissButton = { TextButton(onClick = model::dismissAlert) { Text(UiCopy.CANCEL) } },
            confirmButton = {
                TextButton(onClick = { model.dismissAlert(); onGetPoints() }, modifier = Modifier.testTag("listenGetPoints")) { Text(ReadingUiCopy.GET_POINTS) }
            },
            modifier = Modifier.testTag("listenNotEnough"),
        )
        ListenAlert.UnlockFailed -> AlertDialog(
            onDismissRequest = model::dismissAlert,
            title = { Text(UiCopy.FAIL) },
            text = { Text(ReadingUiCopy.COULD_NOT_UNLOCK_THE) },
            confirmButton = { TextButton(onClick = model::dismissAlert, modifier = Modifier.testTag("listenFailedOk")) { Text(UiCopy.OK) } },
            modifier = Modifier.testTag("listenUnlockFailed"),
        )
        null -> Unit
    }
}
