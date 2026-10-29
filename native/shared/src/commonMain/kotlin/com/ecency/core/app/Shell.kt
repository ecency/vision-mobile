package com.ecency.core.app

import com.ecency.core.l10n.L

/** RN settings.theme: follow the system (default), or force light or dark. [rnValue] is RN's `colorTheme` index. */
enum class ThemeMode(val rnValue: Int, private val titleText: () -> String) {
    SYSTEM(0, { L.settings.theme.system }),
    LIGHT(1, { L.settings.theme.light }),
    DARK(2, { L.settings.theme.dark }),
    ;

    val title: String get() = titleText()

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** A saved name; anything unknown is [SYSTEM]. */
        fun of(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** Something shown over the app for a while: RN ToastNotification (bottom) or ForegroundNotification (top). */
sealed interface UiMessage {
    val id: Long
    val durationMillis: Long

    data class Toast(override val id: Long, val text: String, override val durationMillis: Long = TOAST_MILLIS) : UiMessage

    /** The in-app banner for a notification while the app is open; tapping it opens [screen]. */
    data class Banner(
        override val id: Long,
        val title: String,
        val body: String,
        val screen: AppScreen?,
        override val durationMillis: Long = BANNER_MILLIS,
        /** Where the tap goes when it is more than one screen (a tab, a gated screen); wins over [screen]. */
        val entry: NavEntry? = null,
        /** The account whose avatar the banner shows (RN ForegroundNotification); empty for none. */
        val avatarUser: String = "",
    ) : UiMessage

    companion object {
        /** RN toast duration. */
        const val TOAST_MILLIS = 4000L

        /** RN foreground notification duration. */
        const val BANNER_MILLIS = 5000L
    }
}

data class UiMessagesState(val toast: UiMessage.Toast? = null, val banner: UiMessage.Banner? = null, val queued: Int = 0)

/**
 * The toast and banner channel shared code (and the platforms) post to; each platform renders [state] and calls
 * [dismiss] when a message timed out or was tapped. One toast and one banner show at a time, the rest wait.
 */
class UiMessages internal constructor(dispatchers: AppDispatchers) : StateModel<UiMessagesState>(UiMessagesState(), dispatchers) {
    private var nextId = 1L
    private val toasts = ArrayDeque<UiMessage.Toast>()
    private val banners = ArrayDeque<UiMessage.Banner>()

    /** Main thread. Returns the toast's id. Blank text is ignored (-1). */
    fun toast(text: String): Long {
        if (text.isBlank()) return -1
        // the same text twice in a row shows once (RN only shows a toast when its text changed)
        toasts.lastOrNull()?.takeIf { it.text == text }?.let { return it.id }
        val toast = UiMessage.Toast(nextId++, text)
        toasts += toast
        publish()
        return toast.id
    }

    /** Main thread. Returns the banner's id. */
    fun banner(title: String, body: String, screen: AppScreen?): Long {
        val banner = UiMessage.Banner(nextId++, title, body, screen)
        banners += banner
        publish()
        return banner.id
    }

    /** Main thread. A notification banner whose tap opens [entry]; [avatarUser] must be a validated account name or empty. */
    fun banner(title: String, body: String, entry: NavEntry?, avatarUser: String): Long {
        val banner = UiMessage.Banner(nextId++, title, body, null, entry = entry, avatarUser = avatarUser)
        banners += banner
        publish()
        return banner.id
    }

    /** The message [id] went away (timed out, tapped, swiped); the next one shows. Unknown ids do nothing. */
    fun dismiss(id: Long) {
        val removed = toasts.removeAll { it.id == id } or banners.removeAll { it.id == id }
        if (removed) publish()
    }

    /** Drops everything (sign-out, wipe: a banner may name the previous account). */
    fun clear() {
        toasts.clear()
        banners.clear()
        publish()
    }

    private fun publish() = setState { UiMessagesState(toasts.firstOrNull(), banners.firstOrNull(), toasts.size + banners.size) }
}

data class ConnectivityState(val online: Boolean = true)

/** Network reachability, fed by each platform's monitor; the shell shows RN's "No connection!" banner while offline. */
class ConnectivityModel internal constructor(dispatchers: AppDispatchers) : StateModel<ConnectivityState>(ConnectivityState(), dispatchers) {
    fun setOnline(online: Boolean) {
        if (current.online != online) setState { it.copy(online = online) }
    }
}

data class Badges(val notifications: Int = 0, val chats: Int = 0) {
    fun count(kind: BadgeKind?): Int = when (kind) {
        BadgeKind.NOTIFICATION -> notifications
        BadgeKind.CHAT -> chats
        null -> 0
    }

    /** RN's badge text: the count, "99+" from 99 (RN iconView), nothing at 0. */
    fun text(kind: BadgeKind?): String? = count(kind).let { if (it <= 0) null else if (it >= 99) "99+" else it.toString() }
}

/** Unread counts for the tab icons: [NotificationsModel] feeds the notification count, [ChatService] the chat count. */
class BadgeModel internal constructor(dispatchers: AppDispatchers) : StateModel<Badges>(Badges(), dispatchers) {
    fun setCounts(notifications: Int, chats: Int) = setState { Badges(notifications.coerceAtLeast(0), chats.coerceAtLeast(0)) }

    /** The notification count alone; the chat count stays. */
    fun setNotifications(count: Int) = setState { it.copy(notifications = count.coerceAtLeast(0)) }

    /** The chat count alone ([ChatService]); the notification count stays. */
    fun setChats(count: Int) = setState { it.copy(chats = count.coerceAtLeast(0)) }
}
