package com.ecency.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ecency.core.app.DictationHost
import com.ecency.core.app.DictationSessions
import com.ecency.core.l10n.L

/**
 * Dictation keeps recording in the background (README "Background media"): while a recording runs, this `microphone`
 * foreground service holds the app in the foreground for the system (Android 11+ gives the microphone only to a
 * visible app or such a service) and shows a notification with a Stop action. It records nothing itself: the
 * shared [com.ecency.core.app.DictationModel] owns the recorder; Stop holds the clip unsent (no points are spent from a
 * notification, which the lock screen can reach), and the user confirms "Transcribe" in the app. Started only from a tap in
 * the app (Android 14 refuses a microphone service started from the background). Not exported.
 */
class DictationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // the notification's Stop: the recording ends (and is transcribed) through the shared model
            EcencyGraph.current()?.dictationSessions?.stopFromNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(this),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        }.isSuccess
        if (!started) {
            // refused (Android 14 without the microphone permission, a start from the background): no notification and
            // no Stop are left, so the recording must not go on out of sight (README "Background media")
            EcencyGraph.current()?.dictationSessions?.backgroundUnavailable()
            stopSelf()
        }
        // never restarted by the system: a recording does not survive the process
        return START_NOT_STICKY
    }

    /** Swiped away from recents: the recording goes with the task (the sheet closes, the model throws it away). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        EcencyGraph.current()?.dictationSessions?.stopAll()
        stopSelf()
    }

    companion object {
        const val CHANNEL = SystemChannels.DICTATION
        const val NOTIFICATION_ID = 4402
        const val ACTION_STOP = "com.ecency.app.dictation.STOP"

        // in the app language (README "Localization"), read when the notification is built
        val TITLE: String get() = L.native.system.dictationTitle
        val TEXT: String get() = L.native.system.dictationText
        val STOP: String get() = L.dictation.stop

        fun notification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && manager != null) SystemChannels.ensure(manager, CHANNEL)
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val stop = PendingIntent.getService(
                context,
                1,
                Intent(context, DictationService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // the lock screen shows that the app records, without the Stop action
            val title = TITLE
            val public = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(TEXT)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .apply { open?.let(::setContentIntent) }
                .addAction(0, STOP, stop)
                .build()
        }
    }
}

/**
 * Starts and stops [DictationService] with each recording ([DictationSessions.host]), and watches for the system
 * taking the microphone, so silence is never transcribed and billed; the clip then waits unsent:
 * - Android 10+: the system reports this app's recording as silenced ([DictationSessions.systemInterrupted]);
 * - a transient focus loss (a call, an alarm) ([DictationSessions.audioFocusLost]);
 * - below Android 10, which has no silenced report, a permanent focus loss too. On 10+ a permanent loss (music or
 *   Listen starting) does not take the microphone and is ignored.
 */
class AndroidDictationHost(
    context: Context,
    private val onInterrupted: () -> Unit,
    private val onFocusLost: () -> Unit = onInterrupted,
    private val sdk: Int = Build.VERSION.SDK_INT,
) : DictationHost {
    constructor(context: Context, sessions: DictationSessions) : this(context, { sessions.systemInterrupted() }, { sessions.audioFocusLost() })

    companion object {
        /** Whether this focus change stops the recording (see the class comment). */
        fun holdsOn(change: Int, sdk: Int): Boolean = when (change) {
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> true
            android.media.AudioManager.AUDIOFOCUS_LOSS -> sdk < 29
            else -> false
        }
    }

    private val app = context.applicationContext ?: context
    private val audio = app.getSystemService(android.media.AudioManager::class.java)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var focus: android.media.AudioFocusRequest? = null
    private var recordingCallback: android.media.AudioManager.AudioRecordingCallback? = null

    private fun interrupted() = main.post { onInterrupted() }

    override fun recordingStarted(): Boolean {
        val serviceStarted = runCatching { ContextCompat.startForegroundService(app, Intent(app, DictationService::class.java)) }.isSuccess
        // notifications off (POST_NOTIFICATIONS denied on Android 13+): the service runs but its Stop is invisible
        val visible = androidx.core.app.NotificationManagerCompat.from(app).areNotificationsEnabled()
        watchMicrophone()
        return serviceStarted && visible
    }

    private fun watchMicrophone() {
        val manager = audio ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val request = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setOnAudioFocusChangeListener({ change ->
                    if (holdsOn(change, sdk)) main.post { onFocusLost() }
                }, main)
                .build()
            focus = request
            runCatching { manager.requestAudioFocus(request) }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            // only this app's own recordings are reported to it
            val callback = object : android.media.AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
                    if (configs.orEmpty().any { it.isClientSilenced }) interrupted()
                }
            }
            recordingCallback = callback
            runCatching { manager.registerAudioRecordingCallback(callback, main) }
        }
    }

    override fun recordingEnded() {
        runCatching { app.stopService(Intent(app, DictationService::class.java)) }
        runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(DictationService.NOTIFICATION_ID) }
        if (Build.VERSION.SDK_INT >= 26) focus?.let { r -> runCatching { audio?.abandonAudioFocusRequest(r) } }
        focus = null
        if (Build.VERSION.SDK_INT >= 29) recordingCallback?.let { c -> runCatching { audio?.unregisterAudioRecordingCallback(c) } }
        recordingCallback = null
    }
}
