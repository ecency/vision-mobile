package com.ecency.core.app

import com.ecency.core.l10n.L
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The links the widgets (and any other shortcut) open the app with (README "Widgets"). They only name a place in the
 * app: nothing is signed, sent or prefilled. They go through the same gates as every link: the accounts are read
 * first, the screen opens behind the app lock, and a screen that needs a login sends a signed-out reader to it.
 */
sealed interface AppShortcut {
    val entry: NavEntry

    /** The Notifications tab (its login prompt when signed out). */
    data object Notifications : AppShortcut {
        override val entry get() = NavEntry.tab(AppTab.NOTIFICATION)
    }

    /** Perks: the daily quests and the streak (needs a login). */
    data object Perks : AppShortcut {
        override val entry get() = NavEntry.screen(AppScreen.Perks)
    }

    /** A new post in the editor (needs a login). */
    data object Write : AppShortcut {
        override val entry get() = NavEntry.screen(AppScreen.Editor())
    }

    /** The Waves tab with an empty composer (the login first when signed out). */
    data object NewWave : AppShortcut {
        override val entry get() = NavEntry.tab(AppTab.WAVES)
    }
}

object AppShortcuts {
    const val NOTIFICATIONS = "ecency://notifications"
    const val PERKS = "ecency://perks"
    const val WRITE = "ecency://compose/post"
    const val NEW_WAVE = "ecency://compose/wave"

    /** `ecency://notifications`, `ecency://perks`, `ecency://compose/post`, `ecency://compose/wave`; nothing else. */
    fun parse(url: String): AppShortcut? {
        val u = url.trim()
        if (u.length > 64 || u.any { it.isWhitespace() || it.isISOControl() }) return null
        val scheme = u.substringBefore("://", "")
        if (!scheme.equals("ecency", ignoreCase = true)) return null
        // a query or fragment carries nothing these links use
        val path = u.substringAfter("://").substringBefore('?').substringBefore('#').trimEnd('/').lowercase()
        return when (path) {
            "notifications" -> AppShortcut.Notifications
            "perks" -> AppShortcut.Perks
            "compose/post" -> AppShortcut.Write
            "compose/wave" -> AppShortcut.NewWave
            else -> null
        }
    }
}

/**
 * What the widgets show. Only values that are not secret and that the app itself read: never a key, a token or a
 * session code, and the widget never reaches the network. Written by the app ([WidgetFeed]) into storage the widget
 * reads (iOS: the App Group's defaults; Android: the app's own preferences).
 *
 * Privacy, per widget: the unread count and the quest streak are the account's own and are not shown while the PIN
 * lock is on ([locked]) nor after a logout or wipe (null). The quick actions (Write, New wave) show no data.
 */
data class WidgetSnapshot(
    val signedIn: Boolean = false,
    /** The PIN lock is on (or not read yet): the widgets show no account values. */
    val locked: Boolean = true,
    val unread: Int? = null,
    val streak: Int? = null,
) {
    fun encode(): String = buildJsonObject {
        put("v", VERSION)
        put("signedIn", signedIn)
        put("locked", locked)
        unread?.let { put("unread", it) }
        streak?.let { put("streak", it) }
    }.toString()

    companion object {
        const val VERSION = 1

        /** The storage key both platforms use. */
        const val KEY = "widget.snapshot"

        /** Nothing to show: signed out, wiped, or unreadable. */
        val EMPTY = WidgetSnapshot()

        /** Untrusted: anything unreadable, of another version or out of range reads as [EMPTY] fields. */
        fun decode(text: String?): WidgetSnapshot {
            val o = text?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return EMPTY
            if (o["v"]?.jsonPrimitive?.intOrNull != VERSION) return EMPTY
            val signedIn = o["signedIn"]?.jsonPrimitive?.booleanOrNull ?: false
            val locked = o["locked"]?.jsonPrimitive?.booleanOrNull ?: true
            if (!signedIn || locked) return WidgetSnapshot(signedIn, locked = true)
            return WidgetSnapshot(
                signedIn = true,
                locked = false,
                unread = o["unread"]?.jsonPrimitive?.intOrNull?.takeIf { it in 0..MAX_COUNT },
                streak = o["streak"]?.jsonPrimitive?.intOrNull?.takeIf { it in 0..MAX_COUNT },
            )
        }

        const val MAX_COUNT = 100_000

        /** The widgets' copy (both platforms keep it in step with these). */
        fun unreadLabel(n: Int): String = L.native.widgets.unread(count = n)
        fun streakLabel(n: Int): String = PerksCopy.streak(n)
        val LOCKED_TEXT: String get() = L.native.widgets.lockedText
        val SIGNED_OUT_TEXT: String get() = L.native.widgets.signedOutText
    }
}

/** Where the platform keeps the snapshot and tells its widgets to redraw. */
interface WidgetStore {
    fun write(snapshot: String)
}

/**
 * Keeps the widgets' snapshot current: the signed-in account, the PIN lock setting, the unread count (as the
 * notifications model refreshes it) and the streak (as Perks reads the quests). Each change writes once; a logout or
 * wipe writes the empty snapshot at once ([accountsLeft]). Main thread.
 */
class WidgetFeed internal constructor(private val env: AppEnvironment) :
    StateModel<WidgetSnapshot>(WidgetSnapshot.EMPTY, env.dispatchers) {
    /** Set by the platform before [start]. */
    var store: WidgetStore? = null
    private val streak = MutableStateFlow<Pair<String, Int>?>(null)
    private var started = false
    private var written: String? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(env.session.state, env.appLock.state, env.notifications.state, streak) { session, lock, notes, streak ->
                val user = session.username.takeIf { !session.isLoading }
                snapshot(user, locked = !lock.loaded || lock.enabled, unread = notes.unread.takeIf { notes.account == user }, streak = streak?.takeIf { it.first == user }?.second)
            }.distinctUntilChanged().collect(::publish)
        }
    }

    /** Perks read the quests of [user]. */
    fun streakRead(user: String, days: Int) {
        streak.value = user to days.coerceIn(0, WidgetSnapshot.MAX_COUNT)
    }

    /** The current account left (logout, wipe): the widgets show nothing of it from now on. */
    internal fun accountsLeft() {
        streak.value = null
        publish(WidgetSnapshot.EMPTY)
    }

    private fun publish(next: WidgetSnapshot) {
        setState { next }
        val text = next.encode()
        if (text == written) return
        written = text
        runCatching { store?.write(text) }
    }

    companion object {
        internal fun snapshot(user: String?, locked: Boolean, unread: Int?, streak: Int?): WidgetSnapshot = when {
            user == null -> WidgetSnapshot.EMPTY
            locked -> WidgetSnapshot(signedIn = true, locked = true)
            else -> WidgetSnapshot(signedIn = true, locked = false, unread = unread?.coerceIn(0, WidgetSnapshot.MAX_COUNT), streak = streak)
        }
    }
}
