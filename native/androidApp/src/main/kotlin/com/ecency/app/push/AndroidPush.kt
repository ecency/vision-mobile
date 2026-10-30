package com.ecency.app.push

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ecency.app.EcencyGraph
import com.ecency.core.app.PushPlatform
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Firebase Cloud Messaging for the shared push model. Firebase starts by itself (FirebaseInitProvider) only when
 * the build had a google-services.json; without one there is no FirebaseApp and this platform reports
 * [isConfigured] false, so nothing is asked, registered or received.
 */
class FirebasePushPlatform(context: Context) : PushPlatform {
    private val app = context.applicationContext
    private val prefs get() = app.getSharedPreferences("ecency.push", Context.MODE_PRIVATE)

    /** Starts the Android 13+ permission dialog (false when it could not); set by the activity while it lives. Main thread. */
    @Volatile var requester: (() -> Boolean)? = null

    /** Callers waiting for the dialog that is showing. Main thread. */
    private val waiting = mutableListOf<(Boolean) -> Unit>()

    override val system: String get() = "fcm-android"

    override val isConfigured: Boolean
        get() = runCatching { FirebaseApp.getApps(app).isNotEmpty() }.getOrDefault(false)

    private fun granted(): Boolean =
        if (Build.VERSION.SDK_INT < 33) NotificationManagerCompat.from(app).areNotificationsEnabled()
        else ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Firebase's own start-up token is off in the manifest (`firebase_messaging_auto_init_enabled`), so no token
     * exists before the user allowed notifications. Allowed (now, or in an earlier version of the app): turn it
     * on; Firebase remembers the switch.
     */
    private fun consented() {
        if (!isConfigured) return
        runCatching { FirebaseMessaging.getInstance().apply { if (!isAutoInitEnabled) isAutoInitEnabled = true } }
    }

    override fun permission(request: Boolean, done: (Boolean) -> Unit) {
        if (granted()) {
            consented()
            return done(true)
        }
        val ask = requester
        // the system dialog shows once from here; afterwards the user changes it in the system settings
        if (Build.VERSION.SDK_INT < 33 || !request || ask == null || prefs.getBoolean(ASKED, false)) return done(false)
        waiting += done
        if (waiting.size == 1 && !ask()) abandoned()
    }

    /**
     * The dialog was answered. The result may reach an activity recreated while the dialog showed; the callbacks
     * live here, not in the activity, so that answer is not lost. Only now does the one-time prompt count as used.
     */
    fun answered(isGranted: Boolean) {
        prefs.edit().putBoolean(ASKED, true).apply()
        if (isGranted) consented()
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { it(isGranted) }
    }

    /** An activity was created: a dialog answered while none could take the result is settled from the real state. */
    fun recheck() {
        if (waiting.isNotEmpty() && granted()) answered(true)
    }

    /** The dialog went away without an answer (the activity finished, the request was interrupted): ask again another time. */
    fun abandoned() {
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { it(false) }
    }

    override fun token(done: (String?) -> Unit) {
        // never before consent: asking Firebase for a token mints one
        if (!isConfigured || !granted()) return done(null)
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task -> done(if (task.isSuccessful) task.result else null) }
        } catch (e: Exception) {
            // no Play services, a broken configuration: push cannot work on this device
            done(null)
        }
    }

    override fun deleteToken(done: (Boolean) -> Unit) {
        if (!isConfigured) return done(false)
        try {
            FirebaseMessaging.getInstance().deleteToken().addOnCompleteListener { task -> done(task.isSuccessful) }
        } catch (e: Exception) {
            done(false)
        }
    }

    override fun clearDelivered() {
        runCatching { app.getSystemService(NotificationManager::class.java)?.cancelAll() }
    }

    private companion object {
        const val ASKED = "permissionAsked"
    }
}

/** Token changes and pushes that arrive while the app is open. Tapped notifications come in through the launcher intent. */
class EcencyMessagingService : FirebaseMessagingService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onNewToken(token: String) {
        // without a running app there is nothing to do: the next start registers the current token
        main.post { EcencyGraph.current()?.push?.onTokenRefreshed() }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = HashMap(message.data)
        val title = message.notification?.title
        val body = message.notification?.body
        // RN `onMessage`: only while the app is in front (the shared model also drops it while locked or signed out)
        main.post { if (EcencyGraph.foreground) EcencyGraph.current()?.push?.received(data, title, body) }
    }
}

/** The launcher intent of a tapped system notification: FCM puts the message's data into its extras. */
object PushIntents {
    private val KEYS = listOf("id", "type", "source", "target", "permlink1", "permlink2", "permlink3", "amount", "tag", "hiveUri")

    /**
     * The push data in [intent], or null when it is not a tapped FCM notification. Only the known keys are read
     * and only as strings; the activity is exported, so any app can send such an intent and the shared routing
     * treats every value as hostile.
     */
    fun data(intent: Intent?): Map<String, String>? {
        val extras = intent?.extras ?: return null
        if (extras.getString("google.message_id").isNullOrEmpty() && extras.getString("message_id").isNullOrEmpty()) return null
        val type = extras.getString("type")?.takeIf { it.isNotEmpty() } ?: return null
        return buildMap {
            put("type", type)
            for (key in KEYS) if (key != "type") extras.getString(key)?.let { put(key, it) }
        }
    }

    /** After handling: a recreated activity or a re-delivered intent must not route the same tap again. */
    fun consume(intent: Intent?) {
        intent?.removeExtra("type")
        intent?.removeExtra("google.message_id")
        intent?.removeExtra("message_id")
    }
}
