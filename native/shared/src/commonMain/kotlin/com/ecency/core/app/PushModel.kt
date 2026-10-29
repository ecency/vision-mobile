package com.ecency.core.app

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.auth.HasSocketFactory
import com.ecency.core.l10n.L
import com.ecency.core.render.blocks.SafeTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/**
 * The device's push service as the shared code needs it: Firebase Cloud Messaging on both platforms (RN registers
 * `fcm-android` and `fcm-ios` tokens with the Ecency backend). Callbacks may arrive on any thread, once.
 * [None] is the build without a Firebase configuration: nothing is asked, registered or received.
 */
interface PushPlatform {
    /** The `system` the backend stores with the token: `fcm-android` or `fcm-ios`. */
    val system: String

    /** False without a Firebase configuration in this build, or where the service cannot work at all. */
    val isConfigured: Boolean

    /** Whether notifications are allowed; with [request] the system prompt is shown first when it never was. */
    fun permission(request: Boolean, done: (Boolean) -> Unit)

    /** The current registration token, null when the service has none to give (no Play services, simulator, offline). */
    fun token(done: (String?) -> Unit)

    /** Deletes the token, so the backend's next send to it fails as unregistered. */
    fun deleteToken(done: (Boolean) -> Unit)

    /** RN at start: the app icon badge to 0 and delivered notifications out of the tray. */
    fun clearDelivered()

    object None : PushPlatform {
        override val system: String get() = "none"
        override val isConfigured: Boolean get() = false
        override fun permission(request: Boolean, done: (Boolean) -> Unit) = done(false)
        override fun token(done: (String?) -> Unit) = done(null)
        override fun deleteToken(done: (Boolean) -> Unit) = done(false)
        override fun clearDelivered() {}
    }
}

/** RN's per-type push switches, in the order of the settings screen. [id] is enotify's `notify_types` number. */
enum class PushType(val key: String, val id: Int, private val labelText: () -> String) {
    FOLLOW("follow", 3, { L.settings.notification.follow }),
    VOTE("vote", 1, { L.settings.notification.vote }),
    COMMENT("comment", 4, { L.settings.notification.comment }),
    MENTION("mention", 2, { L.settings.notification.mention }),
    FAVORITE("favorite", 13, { L.settings.notification.favorite }),
    BOOKMARK("bookmark", 15, { L.settings.notification.bookmark }),
    TAGS("tags", 23, { L.settings.notification.tags }),
    REBLOG("reblog", 5, { L.settings.notification.reblog }),
    TRANSFERS("transfers", 6, { L.settings.notification.transfers }),
    SCHEDULED_PUBLISHED("scheduledPublished", 22, { L.settings.notification.scheduledPublished }),
    DELEGATIONS("delegations", 10, { L.settings.notification.delegations }),
    PAYOUTS("payouts", 19, { L.settings.notification.payouts }),
    ACCOUNT_UPDATE("accountUpdate", 20, { L.settings.notification.accountUpdate }),
    WEEKLY_EARNINGS("weeklyEarnings", 21, { L.settings.notification.weeklyEarnings }),
    ;

    val label: String get() = labelText()
}

/** RN `isNotificationOpen` and `notificationDetails`: everything on by default. Kept on the device, per install. */
data class PushSettings(val enabled: Boolean = true, val off: Set<PushType> = emptySet()) {
    fun isOn(type: PushType): Boolean = type !in off

    /** The `notify_types` the backend gets: the ids of the types that are on. */
    fun notifyTypes(): List<Int> = PushType.entries.filter { isOn(it) }.map { it.id }.sorted()

    internal fun save(prefs: Prefs) {
        prefs.putString(KEY_ENABLED, if (enabled) "1" else "0")
        prefs.putString(KEY_OFF, off.joinToString(",") { it.key })
    }

    companion object {
        internal const val KEY_ENABLED = "push.enabled"
        internal const val KEY_OFF = "push.typesOff"

        internal fun load(prefs: Prefs): PushSettings {
            val off = prefs.getString(KEY_OFF).orEmpty().split(',').mapNotNull { key -> PushType.entries.firstOrNull { it.key == key } }.toSet()
            return PushSettings(prefs.getString(KEY_ENABLED) != "0", off)
        }
    }
}

data class PushState(
    val settings: PushSettings = PushSettings(),
    /** Whether pushes can reach this install (configured, allowed, a token exists); null until checked. */
    val available: Boolean? = null,
    /** A tapped notification's destination, for the platform's navigation to open and then [PushModel.consumeOpen]. */
    val open: NavEntry? = null,
) {
    /** RN `settings.notification.fcm_unavailable`, shown under the switches. */
    val unavailableNotice: String?
        get() = if (available == false) {
            L.settings.notification.fcmUnavailable
        } else {
            null
        }
}

/** An account on its way out: its access token is read before the vault forgets it. */
class LeavingAccount internal constructor(val username: String, internal val code: String?)

/**
 * Push for both apps (RN utils/pushRegistration.ts, the push parts of applicationContainer, useInitApplication and
 * settingsContainer). One per app.
 *
 * - Registration: the device token is registered for every signed-in account that has an access token, with the
 *   switches of [PushSettings]: at start, when the network comes back (at most once a minute), on foreground
 *   after a day, for an account that just became current, when the token was refreshed, and again for an
 *   account the server refused once its access token changed.
 * - Logout: the leaving account's row is disabled with the current token (never blocking the logout). The token
 *   is deleted when no account is left or a row could not be disabled, and the accounts that stay register
 *   again. Registrations wait for running releases, and releases for registrations already sending.
 * - A release (or a wipe) that could not finish, offline for example, is remembered in [Prefs] as the account
 *   names and the device token, never an access token. It is finished at the next start, reconnect or
 *   foreground by deleting the device token (the backend's sends to it then fail as unregistered); until then
 *   nothing registers, so a row that should be off is never refreshed under the old token.
 * - A push addressed to another signed-in account shows no banner, changes no count and marks nothing.
 * - A tapped notification is kept until the accounts were read and the app lock is open, then routed with
 *   [PushRouting]; nothing of it shows on the lock screen.
 * - While the app is open, a push (or, where push is not available, the websocket) refreshes the unread count and
 *   shows the in-app banner. Neither does anything while signed out or locked.
 */
class PushModel internal constructor(private val env: AppEnvironment) : StateModel<PushState>(PushState(), env.dispatchers) {
    private val platform: PushPlatform get() = env.pushPlatform
    private var started = false
    private var foreground = true

    /** Releases queued or running; registrations wait for 0. */
    private val releases = MutableStateFlow(0)

    /** Registrations past their wait whose request has not settled; releases wait for 0. */
    private val inFlight = MutableStateFlow(0)

    /** Serializes the register-device sends of each account (main thread only). */
    private val sendLocks = mutableMapOf<String, kotlinx.coroutines.sync.Mutex>()

    private fun sendLock(username: String) = sendLocks.getOrPut(username) { kotlinx.coroutines.sync.Mutex() }
    private val releaseLock = Mutex()
    private val departing = mutableSetOf<String>()

    /** Accounts whose last registration the server refused (401, 403), with the access token it refused. */
    private val rejected = mutableMapOf<String, String>()
    private var lastRegistration: Long? = null
    private var pendingOpen: PushPayload? = null
    private var lastBannerId = ""
    private val socket = ActivitySocket(scope, { env.auth.sockets }, { env.activityWebsocketUrl }, env.nowMillis) { handle(it, PushRouting.SOCKET_TYPES) }

    /** Websocket connections opened so far (tests). */
    internal val socketConnects: Int get() = socket.connects

    /** Call once at app start, after the platform set [AppEnvironment.pushPlatform] and [AppEnvironment.prefs]. */
    fun start() {
        if (started) return
        started = true
        setState { it.copy(settings = PushSettings.load(env.prefs)) }
        scope.launch {
            env.session.awaitLoaded()
            if (platform.isConfigured) platform.clearDelivered()
            // RN asks at start, signed in or not
            checkAvailability(request = true)
            retryPending()
            registerAll()
        }
        // an account that just became current registers right away (RN componentDidUpdate)
        scope.launch {
            var loaded = false
            var last: String? = null
            env.session.state.map { it.isLoading to it.username }.distinctUntilChanged().collect { (loading, user) ->
                if (loading) return@collect
                if (!loaded) {
                    loaded = true
                    last = user
                } else if (user != last) {
                    last = user
                    if (user != null) register(user)
                }
            }
        }
        // RN `_fetchApp` on reconnect, at most once a minute
        scope.launch {
            var online = env.connectivity.current.online
            env.connectivity.state.collect { c ->
                if (c.online && !online) {
                    retryPending()
                    if (olderThan(DEDUPE_MILLIS)) registerAll()
                }
                online = c.online
            }
        }
        // the tapped notification waits for the accounts and the lock; the websocket runs only while it may
        scope.launch {
            combine(env.session.state, env.appLock.state, state) { s, l, p -> Triple(if (s.isLoading) null else s.username, s.isLoading || l.hidesContent, p.available) }
                .distinctUntilChanged()
                .collect { deliverOpen(); updateSocket() }
        }
    }

    // ---- availability and permission ----

    private suspend fun checkAvailability(request: Boolean): Boolean {
        val ok = platform.isConfigured && permission(request) && token() != null
        setState { it.copy(available = ok) }
        return ok
    }

    private suspend fun permission(request: Boolean): Boolean =
        withTimeoutOrNull(PERMISSION_TIMEOUT_MILLIS) { suspendCancellableCoroutine { c -> platform.permission(request) { if (c.isActive) c.resume(it) } } } ?: false

    private suspend fun token(): String? =
        withTimeoutOrNull(PLATFORM_TIMEOUT_MILLIS) { suspendCancellableCoroutine { c -> platform.token { if (c.isActive) c.resume(it) } } }?.takeIf { it.isNotEmpty() }

    private suspend fun deleteToken(): Boolean =
        withTimeoutOrNull(PLATFORM_TIMEOUT_MILLIS) { suspendCancellableCoroutine { c -> platform.deleteToken { if (c.isActive) c.resume(it) } } } ?: false

    // ---- registration ----

    private fun olderThan(millis: Long): Boolean = lastRegistration?.let { env.nowMillis() - it > millis } ?: true

    /** The platform's token changed (FCM rotates tokens): every account registers the new one. */
    fun onTokenRefreshed() {
        if (started) registerAll()
    }

    /** Back in front: the daily re-registration, a retry for refused accounts, and the websocket may reconnect. */
    fun onForeground() {
        foreground = true
        if (!started) return
        retryPending()
        scope.launch {
            if (state.value.available == false && platform.isConfigured) {
                // allowed in the system settings meanwhile?
                if (checkAvailability(request = false)) registerAll()
            } else if (olderThan(REFRESH_MILLIS)) {
                registerAll()
            } else {
                retryRejected()
            }
            updateSocket()
        }
    }

    fun onBackground() {
        foreground = false
        updateSocket()
    }

    /** Registers the token for every signed-in account (RN `_registerDeviceForNotifications`). */
    private fun registerAll() {
        if (!platform.isConfigured) return
        lastRegistration = env.nowMillis()
        scope.launch {
            signedIn().forEach { if (it !in departing) register(it) }
        }
    }

    private suspend fun signedIn(): List<String> = try {
        env.accounts.accounts().map { it.username }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    private suspend fun codeOf(username: String): String? = try {
        env.accounts.code(username)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun stillWanted(username: String): Boolean = username !in departing && username in signedIn()

    /** Accounts the server refused register again once their access token changed. */
    private suspend fun retryRejected() {
        for ((username, code) in rejected.toMap()) {
            val now = codeOf(username)
            if (now == null) rejected.remove(username) else if (now != code) register(username)
        }
    }

    /** RN `_enableNotification` over `beginPushRegistration`. */
    private fun register(username: String): Job = scope.launch {
        if (!platform.isConfigured) return@launch
        // accounts without an access token are skipped silently, as RN
        val code = codeOf(username) ?: return@launch
        if (!permission(request = true)) {
            setState { it.copy(available = false) }
            return@launch
        }
        // a logout may still be disabling rows or replacing the token: register after it
        val settled = withTimeoutOrNull(RELEASE_WAIT_MILLIS) { releases.first { it == 0 } } != null
        if (!stillWanted(username)) return@launch
        // an unfinished release rotates the token first; while it cannot (offline), nothing registers
        when (resolvePending()) {
            Pending.NONE -> {}
            Pending.ROTATED -> {
                registerAll()
                return@launch
            }
            Pending.BLOCKED -> return@launch
        }
        inFlight.update { it + 1 }
        try {
            if (!settled) {
                // the releases still running may disable this row or replace the token after this request:
                // register once more when they are done, so a late disable is not the last write
                scope.launch {
                    releases.first { it == 0 }
                    if (stillWanted(username)) register(username)
                }
            }
            val token = token()
            if (token == null) {
                setState { it.copy(available = false) }
                return@launch
            }
            setState { it.copy(available = true) }
            try {
                // one send per account at a time, the switches read once it is this send's turn: a request built from
                // older switches (the first launch's defaults before the RN import, a quick toggle) is never the last write
                sendLock(username).withLock {
                    // a logout that ran while this send waited its turn: nothing more for that account
                    if (!stillWanted(username)) return@withLock
                    val settings = current.settings
                    env.notificationsApi.registerDevice(code, username, token, platform.system, settings.enabled, settings.notifyTypes())
                }
                rejected.remove(username)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EcencyHttpException) {
                if (e.status == 401 || e.status == 403) {
                    val renewed = codeOf(username)
                    if (renewed != null && renewed != code && stillWanted(username)) register(username) else rejected[username] = code
                }
                // offline or a server error: the next start, reconnect or daily refresh retries
            } catch (_: Exception) {
            }
        } finally {
            inFlight.update { it - 1 }
        }
    }

    // ---- wipe ----

    /**
     * Every account was wiped from the device ([AppEnvironment.accountsLeft]). The access tokens are gone, so no
     * row can be disabled: the device token itself goes, now or at the next start, reconnect or foreground. Only
     * the pending mark is written here; nothing waits for the platform or the network.
     */
    internal fun wiped() {
        pendingOpen = null
        rejected.clear()
        setState { it.copy(open = null) }
        if (platform.isConfigured) {
            addPending(setOf(WIPED), "")
            retryPending()
        }
    }

    // ---- logout ----

    /**
     * Before [usernames] are removed from the vault: reads their access tokens and keeps them out of every
     * registration from now on. Follow with [release] when they were removed, or [staying] when the logout failed.
     */
    internal suspend fun leaving(usernames: List<String>): List<LeavingAccount> {
        if (!platform.isConfigured) return emptyList()
        departing += usernames
        return usernames.map { LeavingAccount(it, codeOf(it)) }
    }

    internal fun staying(accounts: List<LeavingAccount>) {
        departing -= accounts.map { it.username }.toSet()
    }

    /**
     * [accounts] left the device: their rows are disabled in the background (RN `disablePushRegistrations`).
     * Never throws and never blocks the caller on the network.
     */
    internal fun release(accounts: List<LeavingAccount>) {
        if (accounts.isEmpty()) return
        departing -= accounts.map { it.username }.toSet()
        accounts.forEach { rejected.remove(it.username) }
        if (!platform.isConfigured) return
        // remembered before any request: a release cut short (offline, the process killed) is finished later
        addPending(accounts.map { it.username }.toSet(), "")
        releases.update { it + 1 }
        scope.launch {
            try {
                // bounded as a whole: when it runs out the entry stays pending for the next retry and the lock is free
                releaseLock.withLock { withTimeoutOrNull(RELEASE_BOUND_MILLIS) { releaseNow(accounts) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // a failed release never breaks the chain
            } finally {
                withContext(NonCancellable) { releases.update { it - 1 } }
            }
        }
    }

    private suspend fun releaseNow(accounts: List<LeavingAccount>) {
        // a registration already on its way must land first, or it could switch a leaving account back on
        withTimeoutOrNull(RELEASE_WAIT_MILLIS) { inFlight.first { it == 0 } }
        // no token to be had right now: the release stays pending
        val token = token() ?: return
        addPending(emptySet(), token)
        var allDisabled = true
        for (account in accounts) {
            val code = account.code
            val ok = code != null && try {
                env.notificationsApi.registerDevice(code, account.username, token, platform.system, false, emptyList())
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!ok) allDisabled = false
        }
        // With no account left the token goes. With accounts left it stays, unless a row could not be disabled:
        // deleting the token is then the only way to stop that account's pushes. The delete comes after the
        // requests: deleting first would mint a new token and the requests would disable the wrong rows.
        val remaining = signedIn()
        if (remaining.isNotEmpty() && allDisabled) {
            donePending(accounts.map { it.username }.toSet())
            return
        }
        // a failed delete leaves the release pending
        if (!deleteToken()) return
        clearPending()
        if (remaining.isNotEmpty()) registerAll()
    }

    // ---- releases that could not finish ----

    private enum class Pending { NONE, ROTATED, BLOCKED }

    /** What [Prefs] holds for unfinished releases: the device token they were for (empty when unknown) and the account names. */
    private fun pending(): Pair<String, Set<String>>? {
        val raw = env.prefs.getString(KEY_PENDING)?.takeIf { it.isNotEmpty() } ?: return null
        val names = raw.substringAfter('\n', "").split(',').filter { it.isNotEmpty() }.toSet()
        return if (names.isEmpty()) null else raw.substringBefore('\n') to names
    }

    private fun savePending(token: String, names: Set<String>) =
        env.prefs.putString(KEY_PENDING, if (names.isEmpty()) null else token + "\n" + names.joinToString(","))

    /** Adds [names] to the unfinished releases; the first known [token] is kept. */
    private fun addPending(names: Set<String>, token: String) {
        val old = pending()
        if (old == null && names.isEmpty()) return
        savePending(old?.first?.ifEmpty { null } ?: token, old?.second.orEmpty() + names)
    }

    private fun donePending(names: Set<String>) {
        val old = pending() ?: return
        savePending(old.first, old.second - names)
    }

    private fun clearPending() = env.prefs.putString(KEY_PENDING, null)

    /** True while a release is remembered as unfinished (tests, diagnostics). */
    val hasPendingRelease: Boolean get() = pending() != null

    /**
     * Finishes remembered releases by rotating the device token: without the access tokens of accounts that
     * are gone, deleting the token is the one thing that still stops their pushes. Done already when the device
     * token is no longer the remembered one.
     */
    private suspend fun resolvePending(): Pending {
        if (pending() == null) return Pending.NONE
        return releaseLock.withLock {
            val p = pending() ?: return@withLock Pending.NONE
            val current = token()
            if (current != null && p.first.isNotEmpty() && current != p.first) {
                clearPending()
                return@withLock Pending.ROTATED
            }
            if (!deleteToken()) return@withLock Pending.BLOCKED
            clearPending()
            Pending.ROTATED
        }
    }

    /** At start, on reconnect and on foreground: finish what a logout or wipe left behind, then register who stays. */
    private fun retryPending() {
        if (!platform.isConfigured || pending() == null) return
        scope.launch {
            // a release running right now finishes itself (it is bounded)
            withTimeoutOrNull(RELEASE_BOUND_MILLIS + RELEASE_WAIT_MILLIS) { releases.first { it == 0 } }
            if (resolvePending() == Pending.ROTATED) registerAll()
        }
    }

    // ---- settings ----

    /** RN settings "Push Notification": the master switch. */
    fun setEnabled(enabled: Boolean) = changeSettings(current.settings.copy(enabled = enabled))

    fun setType(type: PushType, on: Boolean) =
        changeSettings(current.settings.let { it.copy(off = if (on) it.off - type else it.off + type) })

    /** The switches changed in the preferences (the RN settings import): read again and sent once started. Main thread. */
    internal fun reloadSettings() {
        val settings = PushSettings.load(env.prefs)
        if (settings == current.settings) return
        setState { it.copy(settings = settings) }
        if (started) registerAll()
    }

    /** Saves on the device and sends the new switches to the backend for every signed-in account (RN `_setPushToken`). */
    private fun changeSettings(settings: PushSettings) {
        if (settings == current.settings) return
        setState { it.copy(settings = settings) }
        settings.save(env.prefs)
        if (started) registerAll()
    }

    // ---- received and tapped ----

    /** A push arrived while the app is open (FCM `onMessage`): [data] plus the notification's own title and body. */
    fun received(data: Map<String, String>, title: String?, body: String?) = handle(PushPayload.of(data, title, body), PushRouting.FOREGROUND_TYPES)

    private fun handle(payload: PushPayload, countTypes: Set<String>) {
        // nothing private while signed out or behind the lock; the count is read again after the unlock anyway
        val user = env.session.current.username
        if (user == null || env.appLock.current.hidesContent) return
        // a push for another account signed in on this device: nothing of it shows in this one
        val addressee = PushRouting.addressee(payload)
        if (addressee != null && addressee != user) return
        if (payload.type in countTypes) env.notifications.refreshUnread()
        val banner = PushRouting.banner(payload) ?: return
        if (banner.id.isNotEmpty() && banner.id == lastBannerId) return
        lastBannerId = banner.id
        env.ui.banner(banner.title, banner.body, banner.entry, banner.avatarUser)
    }

    /**
     * The user tapped a system notification (its [data]). Routed once the accounts were read and the app lock is
     * open; until then only the latest tap is kept, in memory.
     */
    fun opened(data: Map<String, String>) {
        pendingOpen = PushPayload.of(data, null, null)
        deliverOpen()
    }

    private fun deliverOpen() {
        val payload = pendingOpen ?: return
        val session = env.session.current
        if (session.isLoading || env.appLock.current.hidesContent) return
        pendingOpen = null
        // RN marks the activity read whatever the type. Here only for a push addressed to the current account
        // (the id is id-shaped already, and the server marks only the caller's own unread rows).
        if (session.username != null && payload.id.isNotEmpty() && PushRouting.addressee(payload) == session.username) {
            env.notifications.markFromPush(payload.id)
        }
        val action = PushRouting.opened(payload) ?: return
        // A push for another account signed in on this device opens only what anyone may see (a post, a profile,
        // a tag): never a screen that works for an account, which would be the current one, not the addressee.
        val addressee = PushRouting.addressee(payload)
        val forSomeoneElse = session.username != null && addressee != null && addressee != session.username
        // RN hiveuri: the link model asks before it signs; never for a push addressed to another account
        action.link?.let { link ->
            if (!forSomeoneElse) env.authLinks.handleFromPush(link)
            return
        }
        val entry = action.entry ?: return
        if (forSomeoneElse && entry.screens.any { it.route.requiresLogin }) return
        setState { it.copy(open = entry) }
    }

    /** The platform opened [PushState.open]. */
    fun consumeOpen() = setState { it.copy(open = null) }

    // ---- websocket fallback ----

    private fun updateSocket() {
        val session = env.session.current
        val user = session.username?.takeIf {
            started && foreground && !session.isLoading && !env.appLock.current.hidesContent && current.available == false
        }
        socket.update(user)
    }

    override fun close() {
        socket.update(null)
        super.close()
    }

    companion object {
        internal const val KEY_PENDING = "push.pendingRelease"

        /** The pending entry of a wipe: every account is gone. */
        private const val WIPED = "*"

        /** The longest one release may hold the lock: the wait for registrations in flight, the requests, the token calls. */
        const val RELEASE_BOUND_MILLIS = 120_000L

        /** RN PUSH_RELEASE_WAIT_MS. */
        const val RELEASE_WAIT_MILLIS = 30_000L

        /** RN PUSH_REGISTRATION_DEDUPE_MS. */
        const val DEDUPE_MILLIS = 60_000L

        /** RN PUSH_REGISTRATION_REFRESH_MS. */
        const val REFRESH_MILLIS = 24 * 60 * 60_000L

        /** The token and delete calls of the platform never hold a registration or a logout longer than this. */
        const val PLATFORM_TIMEOUT_MILLIS = 20_000L

        /** The system prompt waits for the user; a registration gives up after this and is retried later. */
        const val PERMISSION_TIMEOUT_MILLIS = 120_000L
    }
}

/**
 * RN `_connectNotificationServer`: the enotify websocket, used only where push is not available. One connection
 * for the signed-in account; when it drops it reconnects after 2, 4, 8, 16 and 30 seconds and then gives up
 * until the next [update] (foreground, unlock, account change). The attempt count starts over once a connection
 * delivered a message or lasted [STABLE_MILLIS].
 */
internal class ActivitySocket(
    private val scope: CoroutineScope,
    private val sockets: () -> HasSocketFactory,
    private val url: () -> String?,
    private val nowMillis: () -> Long,
    private val onEvent: (PushPayload) -> Unit,
) {
    private var job: Job? = null
    private var user: String? = null

    /** Connections opened so far (tests). */
    var connects = 0
        private set

    /** Connects for [user], or disconnects for null. The same user with a live connection changes nothing. */
    fun update(user: String?) {
        if (user == this.user && (user == null || job?.isActive == true)) return
        job?.cancel()
        job = null
        this.user = user
        val name = SafeTarget.account(user) ?: return
        val base = url()?.takeIf { it.startsWith("wss://") } ?: return
        job = scope.launch { run("$base?user=$name") }
    }

    private suspend fun run(address: String) {
        var attempts = 0
        while (true) {
            try {
                val socket = sockets().open(address)
                connects++
                val openedAt = nowMillis()
                try {
                    for (text in socket.incoming) {
                        attempts = 0
                        PushPayload.fromSocket(text, nowMillis())?.let(onEvent)
                    }
                } finally {
                    withContext(NonCancellable) { runCatching { socket.close() } }
                }
                if (nowMillis() - openedAt >= STABLE_MILLIS) attempts = 0
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // could not connect, or the connection broke: back off below
            }
            if (attempts >= MAX_ATTEMPTS) return
            attempts++
            delay(minOf(1000L shl attempts, MAX_DELAY_MILLIS))
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 5
        const val MAX_DELAY_MILLIS = 30_000L
        const val STABLE_MILLIS = 30_000L
    }
}
