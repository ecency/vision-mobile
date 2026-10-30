package com.ecency.core.app

import com.ecency.core.api.chat.ChatApi
import com.ecency.core.api.chat.ChatApiException
import com.ecency.core.api.chat.ChatBootstrap
import com.ecency.core.api.chat.ChatChannel
import com.ecency.core.api.chat.ChatUser
import com.ecency.core.api.chat.DmPrivacy
import com.ecency.core.auth.AuthException
import com.ecency.core.l10n.L
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** A group renamed on this device or live: [name] is empty when the name was removed. */
data class ChatRename(val channelId: String, val name: String, val atMillis: Long)

/** What the list knew about a channel when it opened it (RN's navigation params), kept in memory only. */
internal class ChatHint(val channel: ChatChannel?, val title: String, val users: Map<String, ChatUser>)

/**
 * The chat session of each signed-in account and the chat badge (RN mattermost.ts bootstrap, chatApi.ts token,
 * applicationContainer `_refreshUnreadChats`). One per app.
 *
 * Bootstrap: `POST /api/mattermost/bootstrap` with the account's Ecency access token (renewed once when the server
 * refuses it); one request at a time per account, the answer reused for 30 s; a logout or wipe voids every answer
 * still on the way, so a chat token never outlives its account. Tokens are kept in memory only and passed per call
 * ([ChatApi]); a request refused with 401 bootstraps again once.
 *
 * Badge: read when the account is loaded or unlocked, on foreground, after a channel was read; nothing is asked
 * while signed out, offline or while the lock hides content. A failed read keeps the last count.
 */
class ChatService internal constructor(private val env: AppEnvironment) {
    /** RN chatApi on `ECENCY_BACKEND_API` ([AppEnvironment.chatHost]). */
    val api: ChatApi by lazy { ChatApi(env.auth.http, env.chatHost) }

    private class Cached(val session: ChatBootstrap, val expiresAt: Long)

    private val cache = mutableMapOf<String, Cached>()
    private val inFlight = mutableMapOf<String, Deferred<ChatBootstrap>>()

    /** Bumped by [forget]: an answer that comes back after it is never cached nor used. */
    private var epoch = 0
    private val accountEpochs = mutableMapOf<String, Int>()

    private val renames = mutableMapOf<String, ChatRename>()
    private val renameEvents = MutableSharedFlow<ChatRename>(extraBufferCapacity = 16)

    /** Every rename made here or seen live; the list and an open conversation follow it. */
    val renamed: SharedFlow<ChatRename> = renameEvents.asSharedFlow()

    internal val hints = LinkedHashMap<String, ChatHint>()

    /** DM safety notices closed in this run of the app (RN keeps them per screen). */
    private val dismissedWarnings = mutableSetOf<String>()

    /**
     * Channel id to the accounts whose screens put something in [hints], [renames] or [dismissedWarnings] for it
     * ("" when no account was signed in): [forget] drops what the departing accounts saw and keeps the rest.
     */
    private val seenBy = mutableMapOf<String, MutableSet<String>>()

    private fun seen(channelId: String, owner: String?) {
        seenBy.getOrPut(channelId) { mutableSetOf() } += owner ?: ""
    }

    private var started = false
    private var active = false
    private var badgeJob: Job? = null
    private var badgeAccount: String? = null

    /** Bootstraps made so far (tests). */
    var bootstraps = 0
        private set

    // ---- session ----

    private fun key(username: String) = epoch to (accountEpochs[username] ?: 0)

    /**
     * RN bootstrapMattermostSession for [username]: the cached session while it is fresh (30 s), the one on its
     * way, or a new one. [fresh] skips the cache (a 401 from a call). Main thread.
     */
    @Throws(Exception::class)
    suspend fun session(username: String, fresh: Boolean = false): ChatBootstrap = withContext(env.dispatchers.main) {
        val now = env.nowMillis()
        if (!fresh) cache[username]?.takeIf { it.expiresAt > now }?.let { return@withContext it.session }
        if (fresh) cache.remove(username)
        val started = key(username)
        inFlight[username]?.let { joined ->
            val result = joined.await()
            // the same check as below: a bootstrap the account's logout voided is not handed to a waiter either
            if (key(username) != started) throw AuthException.NotSignedIn()
            return@withContext result
        }
        val job = env.background.async(start = CoroutineStart.LAZY) { bootstrap(username) }
        inFlight[username] = job
        job.start()
        try {
            val result = job.await()
            // the account left (logout, wipe) while this was on the way: the token is not kept nor handed out
            if (key(username) != started) throw AuthException.NotSignedIn()
            cache[username] = Cached(result, env.nowMillis() + CACHE_MILLIS)
            result
        } finally {
            if (inFlight[username] === job) inFlight.remove(username)
        }
    }

    private suspend fun bootstrap(username: String): ChatBootstrap {
        bootstraps++
        val access = env.accounts.code(username) ?: throw ChatApiException(401, MISSING_TOKEN)
        return try {
            api.bootstrap(access, env.accounts.refreshToken(username), username)
        } catch (e: ChatApiException) {
            if (e.status != 401) throw e
            // the access token expired: renewed once (RN fails here and the user logs in again)
            val renewed = env.sessionRenewals.renew(username) ?: throw e
            api.bootstrap(renewed, env.accounts.refreshToken(username), username)
        }
    }

    /** Runs [block] with [username]'s chat session; a 401 bootstraps again and runs it once more. */
    @Throws(Exception::class)
    suspend fun <T> call(username: String, block: suspend (ChatBootstrap) -> T): T {
        val s = session(username)
        return try {
            block(s)
        } catch (e: ChatApiException) {
            if (e.status != 401) throw e
            block(session(username, fresh = true))
        }
    }

    /**
     * Accounts left the device ([AppEnvironment.accountsLeft]; null: all of them): their sessions, cached or on the
     * way, are gone for good. RN clears the shared token and the bootstrap cache on logout.
     */
    internal fun forget(usernames: List<String>?) {
        if (usernames == null) {
            epoch++
            cache.clear()
            inFlight.clear()
        } else {
            for (u in usernames) {
                accountEpochs[u] = (accountEpochs[u] ?: 0) + 1
                cache.remove(u)
                inFlight.remove(u)
            }
        }
        // channel titles, DM partners, renames and closed notices are kept by channel id: whatever a departed account
        // saw goes with it, also where an account that stays saw it too (that one reads it again with its next list).
        // What only the accounts that stay saw is kept.
        val gone = if (usernames == null) seenBy.keys.toSet() else seenBy.filterValues { owners -> owners.any { it in usernames || it == "" } }.keys
        for (id in gone) {
            hints.remove(id)
            dismissedWarnings.remove(id)
            renames.remove(id)
            seenBy.remove(id)
        }
    }

    // ---- badge ----

    /** Call once at app start (after the session and the lock start); safe to call again. */
    fun start() {
        if (started) return
        started = true
        env.background.launch {
            combine(env.session.state, env.appLock.state, env.connectivity.state) { s, l, c -> listOf(s.isLoading, s.username, l.hidesContent, c.online) }
                .distinctUntilChanged()
                .collect { sync() }
        }
    }

    private fun sync() {
        val session = env.session.current
        if (session.isLoading) return
        val user = session.username
        if (user != badgeAccount) {
            badgeAccount = user
            badgeJob?.cancel()
            env.badges.setChats(0)
            active = false
        }
        val nowActive = user != null && !env.appLock.current.hidesContent && env.connectivity.current.online
        val became = nowActive && !active
        active = nowActive
        if (became) refreshUnread()
    }

    /** The app came to the foreground (RN `_handleAppStateChange`). */
    fun onForeground() {
        sync()
        refreshUnread()
    }

    /** RN `_refreshUnreadChats`: the badge from every channel's unread count. */
    fun refreshUnread() {
        val user = env.session.current.username ?: return
        if (!active) return
        badgeJob?.cancel()
        badgeJob = env.background.launch {
            try {
                val total = ChatUnread.globalTotal(call(user) { api.channels(it.token) })
                if (env.session.current.username == user && badgeAccount == user) env.badges.setChats(total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // the last count stays
            }
        }
    }

    // ---- renames, hints ----

    /** A group got [name] (here or live): remembered with the time, so a list fetch started before it keeps it. */
    /** [owner] is the account the rename was made or seen for, taken when its request started. */
    internal fun rememberRename(channelId: String, name: String, owner: String?) {
        val r = ChatRename(channelId, name, env.nowMillis())
        renames[channelId] = r
        seen(channelId, owner)
        renameEvents.tryEmit(r)
    }

    /** RN `_withNewerRenames`: [channels] with the renames made since [fetchStartedAt]. */
    internal fun withNewerRenames(channels: List<ChatChannel>, fetchStartedAt: Long): List<ChatChannel> {
        if (renames.isEmpty()) return channels
        return channels.map { c ->
            val r = renames[c.id]
            if (r != null && r.atMillis >= fetchStartedAt) c.copy(groupName = r.name.ifEmpty { null }) else c
        }
    }

    /** [owner] is the account whose list or request produced [hint], taken when it started (not at write time). */
    internal fun hint(channelId: String, hint: ChatHint, owner: String?) {
        hints.remove(channelId)
        hints[channelId] = hint
        seen(channelId, owner)
        while (hints.size > MAX_HINTS) hints.remove(hints.keys.first())
    }

    /** The DM safety notice of [channelId] was closed (kept for this run of the app). */
    internal fun dismissWarning(channelId: String, owner: String?) {
        dismissedWarnings += channelId
        seen(channelId, owner)
    }

    internal fun warningDismissed(channelId: String): Boolean = channelId in dismissedWarnings

    // ---- entry points ----

    /**
     * RN profile / quick profile "Message": the direct message with [username] (a Hive name). Returns the channel
     * id; throws when the account is not on chat ("User has not joined chats").
     */
    @Throws(Exception::class)
    suspend fun openDirect(username: String): String {
        val me = withContext(env.dispatchers.main) { env.session.current.username } ?: throw AuthException.NotSignedIn()
        val channel = call(me) { api.direct(it.token, username) }
        val id = channel?.id ?: throw ChatApiException(404, DM_ERROR)
        withContext(env.dispatchers.main) { hint(id, ChatHint(channel, username, emptyMap()), owner = me) }
        return id
    }

    /** The settings row "Chat Privacy". */
    fun privacyModel(): ChatPrivacyModel = ChatPrivacyModel(env)

    /** The chat list (Chats tab); the tab owns it. */
    fun chatsModel(): ChatsModel = ChatsModel(env)

    /** RN chatNewGroupSheet. */
    fun newGroupModel(): ChatNewGroupModel = ChatNewGroupModel(env)

    /** RN chatRenameGroupSheet for [channelId] (its owner only). */
    fun renameModel(channelId: String, currentName: String): ChatRenameModel = ChatRenameModel(env, channelId, currentName)

    /** One conversation; its screen owns it. */
    fun threadModel(channelId: String, type: String): ChatThreadModel = ChatThreadModel(env, channelId, type)

    companion object {
        /** RN BOOTSTRAP_CACHE_TTL_MS. */
        const val CACHE_MILLIS = 30_000L
        const val MAX_HINTS = 50
        val MISSING_TOKEN: String get() = L.native.chatService.missingToken
        val DM_ERROR: String get() = L.native.chatService.dmError
    }
}

data class ChatPrivacyState(
    val account: String? = null,
    val value: DmPrivacy = DmPrivacy.ALL,
    val loaded: Boolean = false,
    val saving: Boolean = false,
)

/**
 * RN settings "Chat Privacy" (`settings.dm-privacy`): who may send the signed-in account direct messages. Read when
 * the settings screen shows; a change is sent at once with RN's toasts. Signed out it shows nothing.
 */
class ChatPrivacyModel internal constructor(private val env: AppEnvironment) : StateModel<ChatPrivacyState>(ChatPrivacyState(), env.dispatchers) {
    private var job: Job? = null

    /** The screen shows: the account's setting (RN getMattermostDmPrivacy; best effort, the default stays on failure). */
    fun start() {
        val user = env.session.current.username
        if (user != current.account) setState { ChatPrivacyState(account = user) }
        user ?: return
        job?.cancel()
        job = scope.launch {
            try {
                val value = env.chat.call(user) { env.chat.api.dmPrivacy(it.token) }
                if (current.account == user) setState { it.copy(value = value, loaded = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // RN keeps the default
            }
        }
    }

    /** RN DM_PRIVACY: `PUT /me/dm-privacy`, "DM privacy settings updated successfully" or "Failed to update DM privacy settings". */
    fun select(privacy: DmPrivacy) {
        val user = current.account ?: return
        if (current.saving) return
        setState { it.copy(saving = true) }
        scope.launch {
            try {
                val saved = env.chat.call(user) { env.chat.api.setDmPrivacy(it.token, privacy) }
                if (current.account == user) setState { it.copy(value = saved, saving = false, loaded = true) }
                env.ui.toast(UPDATED)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                setState { it.copy(saving = false) }
                env.ui.toast(FAILED)
            }
        }
    }

    companion object {
        val TITLE: String get() = L.settings.dmPrivacy.title
        val UPDATED: String get() = L.settings.dmPrivacyUpdated
        val FAILED: String get() = L.settings.dmPrivacyFailed
    }
}
