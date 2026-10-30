package com.ecency.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken

/**
 * What Listen audio is about, for the media notification and the lock screen (RN `<Video source.metadata>`): the
 * shared [com.ecency.core.app.NowPlayingCard]. [artwork] is null while the PIN lock is on.
 */
data class ListenItem(val url: String, val title: String, val artist: String, val artwork: String? = null)

/** What a Listen stream reports back; mirrors react-native-video's callbacks in RN's player. Main thread. */
interface ListenEvents {
    fun loadStarted()
    fun buffering(isBuffering: Boolean)
    fun loaded(durationSeconds: Double)

    /** Playing or not as the player decided it: the notification, the lock screen, a headset button, audio focus. */
    fun playingChanged(isPlaying: Boolean)
    fun ended()
    fun failed()
}

/** One loaded track. Lives from the first play on and stays while paused, so the media controls stay too. */
interface ListenStream {
    val positionSeconds: Double
    fun play()
    fun pause()
    fun seek(seconds: Double)
    fun setSpeed(speed: Double)

    /** A new source for the same stream (a renewed URL, another language): loaded paused, at its start. */
    fun load(item: ListenItem)

    /** The same source with another now playing card (the PIN lock was turned on or off): no reload, no pause. */
    fun retitle(item: ListenItem)
    fun release()
}

/** Opens streams; tests swap in a fake through [LocalListenEngine]. */
fun interface ListenEngine {
    fun open(context: Context, item: ListenItem, events: ListenEvents): ListenStream
}

val LocalListenEngine = staticCompositionLocalOf<ListenEngine> { MediaListenEngine }

/**
 * Listen playback in the background with notification and lock-screen controls: Media3's [MediaSessionService]
 * holds the one ExoPlayer and its media session, and posts the media notification as a `mediaPlayback` foreground
 * service while it plays (RN uses react-native-video's `VideoPlaybackService` the same way). The app talks to it
 * through a [MediaController]. Play Console: the `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission must be declared in
 * the app content's foreground service form (see the README).
 */
class ListenPlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    companion object {
        const val RESUME_CHANNEL = SystemChannels.LISTEN
        const val RESUME_NOTIFICATION = 4401
        val SKIP_MILLIS = (com.ecency.core.app.NowPlaying.SKIP_SECONDS * 1000).toLong()

        /** The "Listen paused" notice goes too when its account leaves (logout, wipe). */
        fun cancelResumeNotice(context: Context) {
            runCatching { context.getSystemService(android.app.NotificationManager::class.java)?.cancel(RESUME_NOTIFICATION) }
        }

        /**
         * What the notification, the lock screen and other controllers may ask for: everything but previous and next
         * (one track), so the system shows the 15 s skips of [skipButtons] in their place.
         */
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        fun controllerCommands(): Player.Commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
            .removeAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .build()

        /**
         * The skips as session commands: media3 1.4's notification keeps a custom layout button only when it carries
         * a [androidx.media3.session.SessionCommand] (a player command button is dropped), and the system lock
         * screen on Android 13+ draws the notification's custom actions.
         */
        val SKIP_BACK = androidx.media3.session.SessionCommand("com.ecency.listen.SKIP_BACK", android.os.Bundle.EMPTY)
        val SKIP_FORWARD = androidx.media3.session.SessionCommand("com.ecency.listen.SKIP_FORWARD", android.os.Bundle.EMPTY)

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        fun skipButtons(): List<androidx.media3.session.CommandButton> = listOf(
            androidx.media3.session.CommandButton.Builder(androidx.media3.session.CommandButton.ICON_SKIP_BACK_15)
                .setSessionCommand(SKIP_BACK).setDisplayName(com.ecency.core.l10n.L.native.system.skipBack).build(),
            androidx.media3.session.CommandButton.Builder(androidx.media3.session.CommandButton.ICON_SKIP_FORWARD_15)
                .setSessionCommand(SKIP_FORWARD).setDisplayName(com.ecency.core.l10n.L.native.system.skipForward).build(),
        )

        /** The session commands every controller may send: the defaults and the two skips. */
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        fun sessionCommands(): androidx.media3.session.SessionCommands =
            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(SKIP_BACK).add(SKIP_FORWARD).build()

        /** A skip command acts on [player]; anything else is not supported. */
        fun handleSkip(player: Player, command: androidx.media3.session.SessionCommand): Int = when (command.customAction) {
            SKIP_BACK.customAction -> { player.seekBack(); androidx.media3.session.SessionResult.RESULT_SUCCESS }
            SKIP_FORWARD.customAction -> { player.seekForward(); androidx.media3.session.SessionResult.RESULT_SUCCESS }
            else -> androidx.media3.session.SessionResult.RESULT_ERROR_NOT_SUPPORTED
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            // headphones unplugged or a Bluetooth headset gone: playback pauses (Android's becoming noisy)
            .setHandleAudioBecomingNoisy(true)
            // the notification's and lock screen's skip back and forward
            .setSeekBackIncrementMs(SKIP_MILLIS)
            .setSeekForwardIncrementMs(SKIP_MILLIS)
            // the network stays up with the screen off while a long post streams
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val skips = skipButtons()
        val commands = controllerCommands()
        val sessionCommands = sessionCommands()
        session = MediaSession.Builder(this, player)
            .setCustomLayout(skips)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(commands)
                        .setAvailableSessionCommands(sessionCommands)
                        .setCustomLayout(skips)
                        .build()

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: androidx.media3.session.SessionCommand,
                    args: android.os.Bundle,
                ): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> =
                    com.google.common.util.concurrent.Futures.immediateFuture(androidx.media3.session.SessionResult(handleSkip(session.player, customCommand)))
            })
            .apply { open?.let(::setSessionActivity) }
            .build()
        // Android 12+ refuses to start a foreground service from the background (a renewed URL resuming playback
        // there): say so with a plain notification that opens the app, as Media3 asks apps to
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() = notifyResume(open)
        })
    }

    internal fun notifyResume(open: PendingIntent?) {
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        if (android.os.Build.VERSION.SDK_INT >= 26) SystemChannels.ensure(manager, RESUME_CHANNEL)
        val notification = androidx.core.app.NotificationCompat.Builder(this, RESUME_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(com.ecency.core.l10n.L.native.system.listenPaused)
            .setContentText(com.ecency.core.l10n.L.native.system.listenPausedText)
            .setAutoCancel(true)
            .apply { open?.let(::setContentIntent) }
            .build()
        runCatching { manager.notify(RESUME_NOTIFICATION, notification) }
    }

    /** The service is not exported: only this app (and the system's own media controls) can reach it. */
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from recents while paused: nothing is left to keep the service for. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onDestroy() {
        clearListener()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}

/**
 * Streams through [ListenPlaybackService]. Each stream has its own controller and media id; the service has ONE
 * player, so a stream acts only while its own item is the player's (another post's Listen bar may have taken it, see
 * [com.ecency.core.render.blocks.SpeakPlayback]) and puts its item back, at its last place, when it plays again.
 */
object MediaListenEngine : ListenEngine {
    private var serial = 0

    override fun open(context: Context, item: ListenItem, events: ListenEvents): ListenStream {
        val app = context.applicationContext ?: context
        val mediaId = "listen-${++serial}"
        val token = SessionToken(app, ComponentName(app, ListenPlaybackService::class.java))
        val future = MediaController.Builder(app, token).buildAsync()
        return MediaListenStream(app, mediaId, item, events, future)
    }
}

private class MediaListenStream(
    private val context: Context,
    private val mediaId: String,
    private var item: ListenItem,
    private val events: ListenEvents,
    private val future: com.google.common.util.concurrent.ListenableFuture<MediaController>,
) : ListenStream {
    private var controller: MediaController? = null
    private var released = false
    private var wantPlay = false
    private var speed = 1.0
    private var lastPosition = 0L
    private var loaded = false

    private val mine: Boolean get() = controller?.currentMediaItem?.mediaId == mediaId

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!mine) return
            when (playbackState) {
                Player.STATE_BUFFERING -> events.buffering(true)
                Player.STATE_READY -> if (loaded) {
                    events.buffering(false)
                } else {
                    loaded = true
                    val ms = controller?.duration ?: C.TIME_UNSET
                    events.loaded(if (ms > 0 && ms != C.TIME_UNSET) ms / 1000.0 else Double.NaN)
                }
                Player.STATE_ENDED -> events.ended()
                else -> {}
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!mine) return
            wantPlay = playWhenReady
            events.playingChanged(playWhenReady)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (mine) events.failed()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // another stream took the player: this one is paused where it was
            if (mediaItem?.mediaId != mediaId && wantPlay) {
                wantPlay = false
                events.playingChanged(false)
            }
        }
    }

    init {
        events.loadStarted()
        future.addListener({
            if (released) return@addListener
            val c = runCatching { future.get() }.getOrNull()
            if (c == null) {
                events.failed()
                return@addListener
            }
            controller = c
            c.addListener(listener)
            take(c)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun media(): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setUri(item.url)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(item.title).setArtist(item.artist).setSubtitle(com.ecency.core.app.NowPlaying.ALBUM)
                .setAlbumTitle(com.ecency.core.app.NowPlaying.ALBUM)
                // loaded by the session's bitmap loader; none while the PIN lock is on
                .apply { item.artwork?.let { setArtworkUri(android.net.Uri.parse(it)) } }
                .build(),
        )
        .build()

    /** Puts this stream's item into the player (at its last place) unless it is there already. */
    private fun take(c: MediaController) {
        if (!mine) {
            loaded = false
            events.loadStarted()
            c.setMediaItem(media(), lastPosition)
            c.prepare()
        }
        c.setPlaybackSpeed(speed.toFloat())
        c.playWhenReady = wantPlay
    }

    override val positionSeconds: Double
        get() = controller?.takeIf { mine }?.currentPosition?.also { lastPosition = it }?.div(1000.0) ?: lastPosition / 1000.0

    override fun play() {
        wantPlay = true
        controller?.let(::take)
    }

    override fun pause() {
        wantPlay = false
        controller?.takeIf { mine }?.pause()
    }

    override fun seek(seconds: Double) {
        lastPosition = (seconds * 1000).toLong().coerceAtLeast(0)
        controller?.takeIf { mine }?.seekTo(lastPosition)
    }

    override fun setSpeed(speed: Double) {
        this.speed = speed
        controller?.takeIf { mine }?.setPlaybackSpeed(speed.toFloat())
    }

    override fun retitle(item: ListenItem) {
        if (item.url != this.item.url) return
        this.item = item
        val c = controller?.takeIf { mine } ?: return
        // same uri and media id: ExoPlayer takes the new metadata without preparing the source again
        c.replaceMediaItem(c.currentMediaItemIndex, media())
    }

    override fun load(item: ListenItem) {
        this.item = item
        wantPlay = false
        lastPosition = 0
        val c = controller?.takeIf { mine } ?: return
        // replaced on the same controller: no stop, no clearing, so the session keeps its notification
        loaded = false
        events.loadStarted()
        c.playWhenReady = false
        c.setMediaItem(media(), 0)
        c.prepare()
    }

    override fun release() {
        if (released) return
        released = true
        controller?.let { c ->
            c.removeListener(listener)
            if (mine) {
                c.stop()
                c.clearMediaItems()
            }
        }
        controller = null
        MediaController.releaseFuture(future)
    }
}
