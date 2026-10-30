package com.ecency.core.app

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.chat.ChatApiException
import com.ecency.core.api.chat.ChatChannel
import com.ecency.core.api.chat.ChatUser
import com.ecency.core.api.chat.firstText
import com.ecency.core.auth.AuthException
import com.ecency.core.l10n.L
import com.ecency.core.render.blocks.SafeTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** What a channel row or search result shows as its picture. Account names are valid Hive names (else [Initial]). */
sealed interface ChatAvatar {
    /** A person's or a community's avatar. */
    data class Account(val name: String) : ChatAvatar

    /** Two members of a group, overlapped. */
    data class Pair(val first: String, val second: String) : ChatAvatar

    /** The first letter of the name. */
    data class Initial(val letter: String) : ChatAvatar

    companion object {
        fun account(name: String?, fallbackTitle: String): ChatAvatar =
            SafeTarget.account(name?.lowercase())?.let { Account(it) } ?: initial(fallbackTitle)

        fun initial(title: String): ChatAvatar = Initial(title.take(1).uppercase())
    }
}

/** One row of the chat list (RN ChannelListItem). */
data class ChatRow(
    val channel: ChatChannel,
    val title: String,
    val avatar: ChatAvatar,
    val unread: Long,
    /** RN `moment(last activity).fromNow()`; empty without a time. */
    val time: String,
    /** A direct message with one of Ecency's own accounts: the row shows the official badge. */
    val official: Boolean = false,
) {
    val id: String get() = channel.id
    val favorite: Boolean get() = channel.isFavorite
    val muted: Boolean get() = channel.isMuted
}

/** A channel found by the search (RN SearchResults "Channels", with Join). */
data class ChatChannelResult(val channel: ChatChannel, val title: String, val avatar: ChatAvatar)

/** A person found by the search (RN SearchResults "Users", with a message button). */
data class ChatUserResult(val user: ChatUser, val title: String, val handle: String?, val avatar: ChatAvatar)

/** A conversation to open: the screen to push. */
data class ChatOpen(val channelId: String, val type: String) {
    val screen: AppScreen get() = AppScreen.ChatThread(channelId, type)
}

data class ChatsState(
    /** The account the rows belong to; UIs draw them only while it is the signed-in account. */
    val account: String? = null,
    /** RN StatusPill: "Connected" once the chat session is up. */
    val connected: Boolean = false,
    val rows: List<ChatRow> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val loaded: Boolean = false,
    /** RN's error line (server text, or RN's fallback). */
    val error: String? = null,
    val query: String = "",
    val searching: Boolean = false,
    val searchError: String? = null,
    val channelResults: List<ChatChannelResult> = emptyList(),
    val userResults: List<ChatUserResult> = emptyList(),
    val sortByName: Boolean = false,
    /** A conversation to push (after a join, a new direct message or group); the UI calls [ChatsModel.consumeOpen]. */
    val open: ChatOpen? = null,
) {
    /** RN `chats.empty`, shown when nothing failed and nothing is there. */
    val isEmpty: Boolean get() = loaded && rows.isEmpty() && error == null && !isLoading
    val hasResults: Boolean get() = channelResults.isNotEmpty() || userResults.isNotEmpty()

    /** RN shows New group signed in once the session is up. */
    val canStartGroup: Boolean get() = account != null && connected
}

/** What the channel options sheet offers (RN chatChannelOptionsSheet), in its order. */
enum class ChatChannelAction { MARK_READ, MEMBERS, RENAME, FAVORITE, UNFAVORITE, MUTE, UNMUTE, LEAVE_CHANNEL, LEAVE_CONVERSATION;

    val label: String
        get() = when (this) {
            MARK_READ -> L.chats.markAsRead
            MEMBERS -> L.native.chats.groupMembers
            RENAME -> L.native.chats.renameGroup
            FAVORITE -> L.chats.favorite
            UNFAVORITE -> L.chats.unfavorite
            MUTE -> L.chats.mute
            UNMUTE -> L.chats.unmute
            LEAVE_CHANNEL -> L.chats.leave
            LEAVE_CONVERSATION -> L.chats.leaveConversation
        }

    val destructive: Boolean get() = this == LEAVE_CHANNEL || this == LEAVE_CONVERSATION
}

/**
 * The Chats tab (RN chatsContainer): the account's channels sorted as RN sorts them, pull to refresh, search with
 * Join and message buttons, the channel options (mark read, favorite, mute, leave, rename) and the new group flow.
 * Nothing is asked while signed out or while the lock hides content; an account change drops the rows at once.
 */
class ChatsModel internal constructor(private val env: AppEnvironment) : StateModel<ChatsState>(ChatsState(), env.dispatchers) {
    private var channels: List<ChatChannel> = emptyList()
    private val lookup = mutableMapOf<String, ChatUser>()
    private var viewerId: String? = null
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var generation = 0
    private var shown = false

    init {
        scope.launch {
            combine(env.session.state, env.appLock.state) { s, l -> Triple(s.isLoading, s.username, l.hidesContent) }
                .distinctUntilChanged()
                .collect { sync() }
        }
        scope.launch {
            env.chat.renamed.collect { r ->
                channels = channels.map { if (it.id == r.channelId) it.copy(groupName = r.name.ifEmpty { null }) else it }
                rebuild()
            }
        }
    }

    private fun sync() {
        val session = env.session.current
        if (session.isLoading) return
        val user = session.username
        if (user != current.account) {
            generation++
            loadJob?.cancel()
            searchJob?.cancel()
            channels = emptyList()
            lookup.clear()
            viewerId = null
            setState { ChatsState(account = user, sortByName = it.sortByName) }
        }
        if (shown && user != null && !env.appLock.current.hidesContent && !current.loaded && loadJob?.isActive != true) load(refresh = true)
    }

    /** The tab is on screen: loads unless loaded (RN loads on mount and on every account change). */
    fun show() {
        shown = true
        sync()
    }

    /** Pull to refresh (RN `_loadChannels(true)`). */
    fun refresh() = load(refresh = true)

    @Throws(CancellationException::class)
    suspend fun refreshAndWait() {
        refresh()
        state.first { !it.isLoading && !it.isRefreshing }
    }

    private fun load(refresh: Boolean) {
        val user = current.account
        if (user == null) {
            setState { it.copy(error = LOGIN_REQUIRED) }
            return
        }
        if (!env.connectivity.current.online) {
            setState { it.copy(error = NO_INTERNET, loaded = true) }
            return
        }
        val gen = generation
        loadJob?.cancel()
        setState { it.copy(error = null, isLoading = !refresh || it.rows.isEmpty() && !it.loaded, isRefreshing = refresh && (it.rows.isNotEmpty() || it.loaded)) }
        loadJob = scope.launch {
            try {
                val session = env.chat.session(user, fresh = refresh && current.connected)
                if (gen != generation) return@launch
                viewerId = session.userId
                session.user?.let { lookup[it.id] = it }
                setState { it.copy(connected = true) }
                val started = env.nowMillis()
                val list = env.chat.withNewerRenames(env.chat.call(user) { env.chat.api.channels(it.token) }, started)
                if (gen != generation) return@launch
                channels = list
                seedDirectUsers(list)
                rebuild()
                setState { it.copy(isLoading = false, isRefreshing = false, loaded = true, error = null) }
                resolveUsers(user, gen, dmUserIds(list))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                setState { it.copy(isLoading = false, isRefreshing = false, loaded = true, error = message(e, L.native.chats.failedToLoadChats)) }
            }
        }
    }

    /** RN `_seedDirectUsers`: the `directUser` of each direct message is known without asking. */
    private fun seedDirectUsers(list: List<ChatChannel>) {
        for (c in list) if (c.isDirect) c.directUser?.let { lookup[it.id] = it }
        for (c in list) if (c.isGroup) c.groupUsers.forEach { u -> lookup.getOrPut(u.id) { u } }
    }

    /** RN `_collectDmUserIds`: direct message partners not known yet (teammate id and the ids in the name). */
    private fun dmUserIds(list: List<ChatChannel>): List<String> {
        val ids = LinkedHashSet<String>()
        for (c in list) {
            if (!c.isDirect) continue
            c.teammateId?.let { if (it != viewerId && it !in lookup) ids += it }
            for (id in c.dmUserIds) if (id != viewerId && id !in lookup) ids += id
        }
        return ids.toList()
    }

    private suspend fun resolveUsers(user: String, gen: Int, ids: List<String>) {
        if (ids.isEmpty()) return
        try {
            val users = env.chat.call(user) { env.chat.api.usersByIds(it.token, ids) }
            if (gen != generation) return
            users.forEach { lookup[it.id] = it }
            rebuild()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // RN ignores lookup failures so the channels still show
        }
    }

    // ---- rows ----

    private fun rebuild() {
        val now = env.nowMillis()
        val sorted = sort(channels, current.sortByName, lookup)
        setState { it.copy(rows = sorted.map { c -> row(c, now) }) }
    }

    private fun row(c: ChatChannel, now: Long): ChatRow {
        var title = firstText(c.displayName, c.name) ?: L.native.chats.unknown
        var avatar: ChatAvatar? = null
        var official = false
        if (c.isDirect) {
            val partner = c.directUser ?: c.teammateId?.let { lookup[it] }
            if (partner != null) {
                official = partner.isOfficial
                title = firstText(partner.hiveName, partner.safeNickname, partner.username) ?: title
                avatar = ChatAvatar.account(title, title)
            }
        }
        if (c.isGroup) {
            title = ChatGroups.title(c, title)
            val names = c.groupUsers.mapNotNull { it.hiveName }.filter { it.isNotEmpty() }.take(2)
            if (names.size == 2) {
                val a = SafeTarget.account(names[0].lowercase())
                val b = SafeTarget.account(names[1].lowercase())
                if (a != null && b != null) avatar = ChatAvatar.Pair(a, b)
            }
        }
        if (avatar == null) {
            // a group's header is its name, which must not read as a community
            val community = if (c.isGroup) null else communityOf(c)
            avatar = community?.let { ChatAvatar.Account(it) } ?: ChatAvatar.initial(title)
        }
        val last = listOf(c.lastPostAt, c.updateAt, c.createAt).firstOrNull { it != 0L }
        return ChatRow(c, title, avatar, ChatUnread.total(c), last?.let { FromNow.format(it, now) }.orEmpty(), official)
    }

    fun toggleSort() {
        setState { it.copy(sortByName = !it.sortByName) }
        rebuild()
    }

    // ---- channel options ----

    /** RN `_confirmChannelOptions`: what the sheet offers for [row]. */
    fun actions(row: ChatRow): List<ChatChannelAction> = buildList {
        if (row.unread > 0) add(ChatChannelAction.MARK_READ)
        if (row.channel.isGroup && row.channel.groupOwner) add(ChatChannelAction.RENAME)
        add(if (row.favorite) ChatChannelAction.UNFAVORITE else ChatChannelAction.FAVORITE)
        add(if (row.muted) ChatChannelAction.UNMUTE else ChatChannelAction.MUTE)
        add(if (row.channel.isConversation) ChatChannelAction.LEAVE_CONVERSATION else ChatChannelAction.LEAVE_CHANNEL)
    }

    /** The sheet's title for [row]. */
    fun actionsTitle(row: ChatRow): String = if (row.channel.isGroup) ChatGroups.title(row.channel) else row.channel.shownName

    /** One option of the sheet (RENAME and MEMBERS are opened by the UI). */
    fun perform(row: ChatRow, action: ChatChannelAction) {
        val user = current.account ?: return
        val gen = generation
        val id = row.id
        scope.launch {
            try {
                when (action) {
                    ChatChannelAction.MARK_READ -> {
                        env.chat.call(user) { env.chat.api.markViewed(it.token, id) }
                        if (gen != generation) return@launch
                        val viewedAt = maxOf(row.channel.lastPostAt, env.nowMillis())
                        update(id) { it.copy(mentionCounters = it.mentionCounters.map { 0.0 }, messageCounter = it.messageCounter?.let { 0.0 }, lastViewedAt = viewedAt, lastViewAt = viewedAt) }
                        env.chat.refreshUnread()
                    }
                    ChatChannelAction.FAVORITE, ChatChannelAction.UNFAVORITE -> {
                        val on = action == ChatChannelAction.FAVORITE
                        env.chat.call(user) { env.chat.api.setFavorite(it.token, id, on) }
                        if (gen == generation) update(id) { it.copy(isFavorite = on) }
                    }
                    ChatChannelAction.MUTE, ChatChannelAction.UNMUTE -> {
                        val on = action == ChatChannelAction.MUTE
                        env.chat.call(user) { env.chat.api.setMuted(it.token, id, on) }
                        if (gen == generation) update(id) { it.copy(isMuted = on) }
                        env.chat.refreshUnread()
                    }
                    ChatChannelAction.LEAVE_CHANNEL, ChatChannelAction.LEAVE_CONVERSATION -> {
                        env.chat.call(user) { env.chat.api.leave(it.token, id) }
                        if (gen != generation) return@launch
                        channels = channels.filter { it.id != id }
                        rebuild()
                        load(refresh = true)
                    }
                    ChatChannelAction.RENAME, ChatChannelAction.MEMBERS -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                val fallback = when (action) {
                    ChatChannelAction.MARK_READ -> L.native.chats.unableToMarkChannel
                    ChatChannelAction.FAVORITE, ChatChannelAction.UNFAVORITE -> L.native.chats.unableToUpdateFavorites
                    ChatChannelAction.MUTE, ChatChannelAction.UNMUTE -> L.native.chats.unableToUpdateMute
                    else -> UNABLE_TO_LEAVE
                }
                setState { it.copy(error = message(e, fallback)) }
            }
        }
    }

    private fun update(id: String, change: (ChatChannel) -> ChatChannel) {
        channels = channels.map { if (it.id == id) change(it) else it }
        rebuild()
    }

    // ---- open ----

    /** RN `_openChannel`: the screen for [row]; what the list knows goes with it in memory. */
    fun open(row: ChatRow): AppScreen {
        env.chat.hint(row.id, ChatHint(row.channel, row.title, lookup.toMap()), owner = current.account)
        return AppScreen.ChatThread(row.id, row.channel.type)
    }

    fun consumeOpen() = setState { it.copy(open = null) }

    /** [owner]: the account the request that found [c] started for. */
    private fun openChannel(c: ChatChannel, title: String, owner: String) {
        env.chat.hint(c.id, ChatHint(c, title, lookup.toMap()), owner)
        setState { it.copy(open = ChatOpen(c.id, c.type)) }
    }

    /** RN `_handleNewGroup`: after the sheet started a group, the list again and the group opens. */
    fun groupCreated(channelId: String) {
        val user = current.account ?: return
        val gen = generation
        scope.launch {
            val created = try {
                val started = env.nowMillis()
                val list = env.chat.withNewerRenames(env.chat.call(user) { env.chat.api.channels(it.token) }, started)
                if (gen != generation) return@launch
                channels = list
                seedDirectUsers(list)
                rebuild()
                list.firstOrNull { it.id == channelId }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (gen != generation) return@launch
            val c = created ?: ChatChannel(channelId, type = "G")
            openChannel(c, ChatGroups.title(c, L.native.chats.group), user)
        }
    }

    // ---- search ----

    /** RN search: the query lowercased, searched 350 ms after the last change, from 2 characters. */
    fun setQuery(text: String) {
        val q = text.lowercase()
        setState { it.copy(query = q) }
        searchJob?.cancel()
        if (com.ecency.core.render.js.Js.trim(q).length < 2) {
            setState { it.copy(channelResults = emptyList(), userResults = emptyList(), searchError = null, searching = false) }
            return
        }
        val user = current.account ?: return
        val gen = generation
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            search(user, gen, com.ecency.core.render.js.Js.trim(q))
        }
    }

    private suspend fun search(user: String, gen: Int, term: String) {
        setState { it.copy(searching = true, searchError = null) }
        try {
            env.chat.session(user)
            val lower = term.lowercase()
            val local = channels.filter { c ->
                val name = c.shownName.lowercase()
                val group = if (c.isGroup) (listOfNotNull(c.groupName) + ChatGroups.memberNames(c)).joinToString(" ").lowercase() else ""
                name.contains(lower) || group.contains(lower)
            }
            val found = scope.async { env.chat.call(user) { env.chat.api.searchChannels(it.token, term) } }
            val people = scope.async { env.chat.call(user) { env.chat.api.searchUsers(it.token, term) } }
            val apiChannels = found.await()
            val users = people.await()
            if (gen != generation) return
            val merged = LinkedHashMap<String, ChatChannel>()
            local.forEach { merged[it.id] = it }
            apiChannels.forEach { if (it.id !in merged) merged[it.id] = it }
            // people with a Hive name first
            val sortedUsers = users.filter { it.hiveName != null } + users.filter { it.hiveName == null }
            setState {
                it.copy(
                    searching = false,
                    channelResults = merged.values.map { c ->
                        val title = firstText(c.displayName, c.name) ?: L.native.chats.unknown
                        ChatChannelResult(c, title, communityOf(c)?.let { id -> ChatAvatar.Account(id) } ?: ChatAvatar.initial(title))
                    },
                    userResults = sortedUsers.map { u ->
                        val hive = u.hiveName
                        val title = firstText(u.safeNickname, u.safeName, u.username, hive).orEmpty()
                        ChatUserResult(u, title, hive?.takeIf { it != title }?.let { "@$it" }, ChatAvatar.account(hive, title))
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (gen == generation) setState { it.copy(searching = false, searchError = message(e, L.native.chats.unableToSearch)) }
        }
    }

    /** RN `_handleJoinChannel`: join, mark it read (so its history does not count as unread), open it. */
    fun join(result: ChatChannelResult) {
        val user = current.account ?: return
        val gen = generation
        scope.launch {
            try {
                val id = result.channel.id
                val joined = env.chat.call(user) { env.chat.api.join(it.token, id) }
                val joinedId = joined?.id ?: id
                val viewed = try {
                    env.chat.call(user) { env.chat.api.markViewed(it.token, joinedId) }
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (gen != generation) return@launch
                var merged = (joined ?: result.channel).let { j ->
                    j.copy(
                        id = joinedId,
                        name = j.name.ifEmpty { result.channel.name },
                        displayName = j.displayName.ifEmpty { result.channel.displayName },
                        header = j.header.ifEmpty { result.channel.header },
                        type = j.type.ifEmpty { result.channel.type },
                    )
                }
                if (viewed) merged = merged.copy(mentionCounters = emptyList(), messageCounter = 0.0)
                channels = if (channels.any { it.id == joinedId }) channels.map { if (it.id == joinedId) merged else it } else listOf(merged) + channels
                rebuild()
                env.chat.refreshUnread()
                openChannel(merged, firstText(merged.displayName, merged.name, result.title).orEmpty(), user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) setState { it.copy(searchError = message(e, UNABLE_TO_JOIN)) }
            }
        }
    }

    /** RN `_handleStartDm`: the direct message with a person found by the search. */
    fun startDirect(result: ChatUserResult) {
        val user = current.account ?: return
        val gen = generation
        val u = result.user
        scope.launch {
            try {
                val name = firstText(u.username, u.safeNickname, OfficialAccounts.safeName(u.firstName, u.username), OfficialAccounts.safeName(u.lastName, u.username), u.id).orEmpty()
                val dm = env.chat.call(user) { env.chat.api.direct(it.token, name, u.id) }
                lookup[u.id] = u
                val channel = dm?.takeIf { it.id.isNotEmpty() } ?: run {
                    val list = env.chat.call(user) { env.chat.api.channels(it.token) }
                    list.firstOrNull { it.isDirect && (it.teammateId == u.id || u.id in it.dmUserIds) }
                } ?: throw ChatApiException(0, UNABLE_TO_START_DM)
                if (gen != generation) return@launch
                val direct = if (channel.type.isEmpty()) channel.copy(type = "D") else channel
                channels = if (channels.any { it.id == direct.id }) channels.map { if (it.id == direct.id) it.copy(teammateId = it.teammateId ?: u.id) else it } else listOf(direct) + channels
                rebuild()
                openChannel(direct, firstText(u.hiveName, u.username, u.safeNickname).orEmpty(), user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) setState { it.copy(searchError = message(e, L.native.chats.unableToStartChat)) }
            }
        }
    }

    companion object {
        val LOGIN_REQUIRED: String get() = L.chats.loginRequired
        val NO_INTERNET: String get() = L.native.chats.noInternet
        val EMPTY: String get() = L.chats.empty
        val SEARCH_PLACEHOLDER: String get() = L.chats.searchPlaceholder
        val CONNECTED: String get() = L.chats.connected
        val CONNECTING: String get() = L.chats.connecting
        val UNABLE_TO_LEAVE: String get() = L.chats.unableToLeaveChannel
        val UNABLE_TO_JOIN: String get() = L.chats.unableToJoinChannel
        val UNABLE_TO_START_DM: String get() = L.chats.unableToStartDm
        const val SEARCH_DEBOUNCE_MILLIS = 350L

        /** RN extractHiveCommunityIdentifier: a `hive-NNN` in the name, display name or header (lower case, valid only). */
        fun communityOf(c: ChatChannel): String? {
            val m = Regex("hive-[0-9]+", RegexOption.IGNORE_CASE).find("${c.name} ${c.displayName} ${c.header}") ?: return null
            return SafeTarget.community(m.value.lowercase())
        }

        /** What RN shows for a failed chat call: the server's text, else a network message, else [fallback]. */
        fun message(e: Throwable, fallback: String): String = when {
            e is ChatApiException -> e.message ?: fallback
            e is EcencyHttpException && e.isNetwork -> AppError.NETWORK_MESSAGE
            e is AuthException -> AppError.from(e).message
            else -> fallback
        }

        /**
         * RN sortedChannels: favorites, unread, the rest, muted; favorites, unread and muted by last interaction, then
         * unread count, then name; the rest by last interaction then name. Sorted by name: every channel by its shown
         * name (a direct message by its partner's username, a group by its title).
         */
        fun sort(channels: List<ChatChannel>, byName: Boolean, lookup: Map<String, ChatUser> = emptyMap()): List<ChatChannel> {
            val favorites = mutableListOf<ChatChannel>()
            val unread = mutableListOf<ChatChannel>()
            val regular = mutableListOf<ChatChannel>()
            val muted = mutableListOf<ChatChannel>()
            for (c in channels) {
                when {
                    c.isMuted -> muted += c
                    c.isFavorite -> favorites += c
                    ChatUnread.meta(c).unreadCount > 0 -> unread += c
                    else -> regular += c
                }
            }
            if (byName) {
                fun shown(c: ChatChannel): String = when {
                    c.isDirect -> c.directUser?.username.orEmpty()
                    c.isGroup -> ChatGroups.title(c)
                    else -> c.shownName
                }.lowercase()
                return (favorites + unread + regular + muted).sortedWith { a, b -> Collate.compare(shown(a), shown(b)) }
            }
            val name = { c: ChatChannel -> c.shownName.lowercase() }
            val withUnread = Comparator<ChatChannel> { a, b ->
                when {
                    a.lastInteraction != b.lastInteraction -> b.lastInteraction.compareTo(a.lastInteraction)
                    ChatUnread.total(a) != ChatUnread.total(b) -> ChatUnread.total(b).compareTo(ChatUnread.total(a))
                    else -> Collate.compare(name(a), name(b))
                }
            }
            val plain = Comparator<ChatChannel> { a, b ->
                if (a.lastInteraction != b.lastInteraction) b.lastInteraction.compareTo(a.lastInteraction) else Collate.compare(name(a), name(b))
            }
            return favorites.sortedWith(withUnread) + unread.sortedWith(withUnread) + regular.sortedWith(plain) + muted.sortedWith(withUnread)
        }
    }
}
