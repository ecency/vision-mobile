package com.ecency.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.ecency.core.l10n.L
import com.ecency.core.l10n.L10n
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.launch

/**
 * The notification channels the app makes, named in the app language (README "Localization"): the catalog, not
 * resources, so the names follow the in-app language on every Android version (resources follow it only from
 * Android 13, through the per-app language). Creating a channel again with its id only renames it; the user's
 * importance and sound settings stay.
 */
internal object SystemChannels {
    const val DICTATION = "dictation"
    const val LISTEN = "listen"
    val ALL = listOf(DICTATION, LISTEN)

    /** The channel's name and description in the current language. */
    fun texts(id: String): Pair<String, String> = when (id) {
        DICTATION -> L.native.system.dictationChannel to L.native.system.dictationChannelDescription
        LISTEN -> L.native.system.listenChannel to L.native.system.listenChannelDescription
        else -> throw IllegalArgumentException("unknown channel $id")
    }

    @RequiresApi(26)
    fun ensure(manager: NotificationManager, id: String) {
        val (name, description) = texts(id)
        manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW).also { it.description = description })
    }

    /** Renames the channels already made (none is created here: a channel shows in the settings once it was used). */
    fun relabel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        for (id in ALL) runCatching { if (manager.getNotificationChannel(id) != null) ensure(manager, id) }
    }
}

/**
 * What Android shows outside the app's screens follows a change of the app language at once: the notification channel
 * names ([SystemChannels]) and every placed widget (drawn again in the new language). Started once per process with
 * the app's environment.
 */
internal object SystemLanguageSurfaces {
    private var job: Job? = null

    @Synchronized
    fun follow(context: Context) {
        if (job != null) return
        val app = context.applicationContext ?: context
        // the language now was drawn already; each later one (even one set before the collector runs) redraws
        val now = L10n.language.value
        job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            L10n.language.dropWhile { it == now }.collect { languageChanged(app) }
        }
    }

    suspend fun languageChanged(context: Context) {
        SystemChannels.relabel(context)
        com.ecency.app.widgets.redrawWidgets(context)
    }
}
