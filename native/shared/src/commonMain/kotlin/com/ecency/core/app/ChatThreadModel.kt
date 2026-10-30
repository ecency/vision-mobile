package com.ecency.core.app

import com.ecency.core.api.chat.ChatApiException
import com.ecency.core.api.chat.ChatChannel
import com.ecency.core.api.chat.ChatPost
import com.ecency.core.api.chat.ChatReaction
import com.ecency.core.api.chat.ChatUser
import com.ecency.core.api.chat.firstText
import com.ecency.core.api.ecency.ImageSigningUnavailable
import com.ecency.core.api.ecency.ImageUploader
import com.ecency.core.api.model.Post
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.search.PostUrlParser
import com.ecency.core.l10n.L
import com.ecency.core.render.ImageProxy
import com.ecency.core.render.blocks.LinkTarget
import com.ecency.core.render.blocks.SafeTarget
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random

/** The quoted message above a reply or in the composer (RN ReplyPreview): author and up to 100 characters. */
/** [username]: the quoted author's name as the chat has it, null when unknown (then [author] shows "Unknown user"). */
data class ChatQuote(val postId: String, val username: String?, val text: String) {
    /** The name to show, in the current language when unknown. Never sent: the chat gets [username]. */
    val author: String get() = username ?: L.chats.anonymous
}

/** A link card: from the message's `link_*` props, or a Hive post found in the text (title fetched). */
data class ChatLinkCard(val url: String, val title: String, val summary: String, val image: String?, val post: PostKey? = null)

/** One row of a conversation (newest first, as RN's inverted list). */
sealed interface ChatItem {
    /**
     * The row's id. Exported to Swift as `rowId`: a protocol property `id` of another type would make the Objective-C
     * export rename the `id` of UiMessage, which the app's Swift code reads.
     */
    @OptIn(kotlin.experimental.ExperimentalObjCName::class)
    @kotlin.native.ObjCName("rowId")
    val id: String
    val timestamp: Long
}

data class ChatMessageItem(
    override val id: String,
    val post: ChatPost,
    val own: Boolean,
    /** The author's name as the chat has it; null when unknown ([author] then shows "Unknown user"). */
    val authorName: String?,
    /** The author's account for the avatar and the quick profile; null when not a valid Hive name. */
    val authorAccount: String?,
    /** Posted by one of Ecency's own accounts (web isOfficialPostAuthor): the name carries the official badge. */
    val authorOfficial: Boolean = false,
    val spans: List<ChatSpan>,
    /** Image urls of the message, through the image proxy. */
    val images: List<String>,
    val quote: ChatQuote?,
    val card: ChatLinkCard?,
    val reactions: List<ReactionPill>,
    /** RN: "Edited" before the time when the message was edited. */
    val edited: Boolean,
    val time: String,
    override val timestamp: Long,
) : ChatItem {
    /** The name to show, in the current language when unknown. */
    val author: String get() = authorName ?: L.chats.anonymous
}

/** "@name joined 2 hours ago" (RN SystemMessageItem); [account] opens the quick profile. */
data class ChatJoinItem(override val id: String, val name: String?, val account: String?, val text: String, override val timestamp: Long) : ChatItem

/** "N people joined", expandable (RN GroupedSystemMessages). */
data class ChatJoinGroupItem(override val id: String, val entries: List<ChatJoinItem>, override val timestamp: Long) : ChatItem {
    val text: String get() = L.native.plurals.chats.peopleJoined(count = entries.size)
}

/** A group rename notice (RN GroupRenameNotice), plain text. */
data class ChatNoticeItem(override val id: String, val text: String, override val timestamp: Long) : ChatItem

/** A mention suggestion (RN MentionSuggestions): `@name` and a display name when different. */
data class ChatMention(val user: ChatUser, val name: String, val displayName: String?)

/** A member in the members sheet (RN OnlineUsersModal). */
data class ChatMemberRow(val userId: String, val username: String, val displayName: String, val online: Boolean, val account: String?)

/** What the message options sheet offers (RN ChatOptionsSheet), in its order. */
enum class ChatMessageAction(private val labelText: () -> String, val destructive: Boolean = false) {
    REPLY({ L.chats.reply }), COPY({ L.chats.copy }), SHARE({ L.chats.share }), TRANSLATE({ L.chats.translate }), EDIT({ L.chats.editMessage }), PIN({ L.chats.pin }), UNPIN({ L.chats.unpin }),
    REMOVE({ L.chats.remove }, destructive = true),
    ;

    val label: String get() = labelText()
}

data class ChatThreadState(
    val account: String? = null,
    val channelId: String = "",
    val type: String = "",
    val title: String = "",
    /** A direct message with one of Ecency's own accounts: the header shows the official badge. */
    val official: Boolean = false,
    val memberCount: Int = 0,
    val pinnedCount: Int = 0,
    val groupOwner: Boolean = false,
    val groupName: String = "",
    val items: List<ChatItem> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val loaded: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    /** The item that carries "New messages" above it. */
    val unreadItemId: String? = null,
    /** An item to scroll to once (the first unread, a pinned message); the UI calls [ChatThreadModel.consumeScroll]. */
    val scrollTo: String? = null,
    val text: String = "",
    /** Bumped when the model replaced [text] (a mention, a sent message): UIs that keep their own field copy it. */
    val textVersion: Int = 0,
    val editingId: String? = null,
    val replyTo: ChatQuote? = null,
    val preview: ChatLinkCard? = null,
    val fetchingPreview: Boolean = false,
    val mentions: List<ChatMention> = emptyList(),
    val sending: Boolean = false,
    /**
     * Bumped once for every send or edit the server took. The send button's tick and success haptic follow it, not
     * [sending] (a failed send and the 30 s bound also end [sending]) nor [error] (that is the thread load's error).
     */
    val sentVersion: Int = 0,
    val uploading: Boolean = false,
    val typing: String? = null,
    val ban: ChatBanInfo? = null,
    val banText: String? = null,
    val showDmWarning: Boolean = false,
    val canModerate: Boolean = false,
    val viewerId: String? = null,
    val connected: Boolean = false,
    /** An alert to show once (an image the account cannot sign); the UI calls [ChatThreadModel.clearAlert]. */
    val alert: String? = null,
) {
    val isDirect: Boolean get() = type == "D"
    val isGroup: Boolean get() = type == "G"
    val canPin: Boolean get() = isDirect || canModerate
    val canSend: Boolean get() = text.isNotBlank() && !sending && !uploading
}

/**
 * One conversation (RN chatThreadContainer): paging back through the history, the unread marker and read state,
 * live events over the websocket while the screen is active, sending, editing, replying, reactions, pins,
 * mentions, link cards, image attachments, the DM safety notice and the posting-ban notice.
 *
 * The model belongs to the account signed in when it was made: an account change stops everything it does.
 * [setActive] follows the screen (on screen, app in front, not locked): only then is the websocket connected.
 */
class ChatThreadModel internal constructor(
    private val env: AppEnvironment,
    val channelId: String,
    type: String,
) : StateModel<ChatThreadState>(ChatThreadState(channelId = channelId, type = type), env.dispatchers) {
    private val owner: String? = env.session.current.username
    private var posts: List<ChatPost> = emptyList()
    private val roots = mutableMapOf<String, ChatPost>()

    /**
     * The spans of each message by post id and text: a rebuild (every new message, reaction or typing user) parses
     * only messages that are new or edited (RN memoizes per message too).
     */
    private val spanCache = HashMap<String, Pair<String, List<ChatSpan>>>()

    internal var spanParses = 0
        private set

    private fun spansFor(id: String, text: String): List<ChatSpan> {
        spanCache[id]?.let { (cached, spans) -> if (cached == text) return spans }
        if (spanCache.size > SPAN_CACHE_MAX) spanCache.clear()
        spanParses++
        return ChatText.spans(text).also { spanCache[id] = text to it }
    }
    private val lookup = mutableMapOf<String, ChatUser>()
    private var memberIds: List<String> = emptyList()
    private var onlineIds: List<String> = emptyList()
    private var before: String? = null
    private var anchor: Long? = null
    private var scrolledToUnread = false
    private var lastMarked = 0L
    private var markedAny = false
    private var groupRenamedLive = false
    private var groupUsers: List<ChatUser> = emptyList()
    private var channelName: String = ""
    private var channelHeader: String = ""
    private var loadJob: Job? = null
    private var previewJob: Job? = null
    private var banJob: Job? = null
    private var sendBoundJob: Job? = null
    private var started = false
    private var active = false

    /** The list (or a profile) handed over what it knew about the channel. */
    private val hinted: Boolean = env.chat.hints[channelId]?.channel != null
    private var dead = false
    private val repliedHere = mutableSetOf<String>()
    private val typingUntil = mutableMapOf<String, Long>()
    private var lastTypingSent = 0L
    private val cards = mutableMapOf<PostKey, ChatLinkCard?>()
    private val socket = ChatSocket(scope, { env.auth.sockets }, env.nowMillis, ::onEvent) { up -> setState { it.copy(connected = up) } }

    // what the last send put on the wire, to recognise its echo (RN lastSent* refs)
    private var pendingId: String? = null
    private var pendingMessage: String? = null
    private var pendingRoot: String? = null
    private var pendingAt = 0L
    /** Which send set the pending fields: a later send (a re-tap after the bound) takes them over. */
    private var pendingGen = 0
    /** Bumped by every send: only the latest one frees the button. */
    private var sendGen = 0
    /** Pending post ids the server took (by echo or by answer): each counts once as sent, its echo is shown once. */
    private val landed = mutableSetOf<String>()

    /** What a send took from the composer: on success those parts go, unless the user has replaced them since. */
    private class Captured(val message: String, val editing: String?, val replyTo: String?, val previewUrl: String?, val mentions: List<ChatMention>)

    /** A create on the wire, by pending post id: its echo counts even after a later send took the pending fields. */
    private class Flight(val gen: Int, val captured: Captured)
    private val inFlight = mutableMapOf<String, Flight>()

    init {
        val hint = env.chat.hints[channelId]
        val channel = hint?.channel
        hint?.users?.let { lookup.putAll(it) }
        channelName = hint?.title ?: channel?.shownName.orEmpty()
        channelHeader = channel?.header ?: channel?.purpose.orEmpty()
        anchor = channel?.let { c -> listOf(c.lastViewedAt, c.lastViewAt).firstOrNull { it != null && it != 0L } }
        groupUsers = channel?.groupUsers.orEmpty()
        groupUsers.forEach { u -> lookup.getOrPut(u.id) { u } }
        setState {
            it.copy(
                account = owner,
                title = channelName.ifEmpty { channelId },
                groupOwner = channel?.groupOwner == true,
                groupName = channel?.groupName.orEmpty(),
            )
        }
        refreshTitle()
        scope.launch {
            combine(env.session.state, env.appLock.state) { s, l -> s.username to l.hidesContent }.distinctUntilChanged().collect { (user, hidden) ->
                if (user != owner) stop() else if (hidden) socket.disconnect() else if (active) connect()
            }
        }
        scope.launch {
            env.chat.renamed.collect { r ->
                if (r.channelId == channelId) {
                    groupRenamedLive = true
                    setState { it.copy(groupName = r.name) }
                    refreshTitle()
                }
            }
        }
    }

    private val user: String? get() = owner.takeIf { !dead && env.session.current.username == it }

    /** The account left: nothing of this conversation is asked for or shown again. */
    private fun stop() {
        if (dead) return
        dead = true
        loadJob?.cancel()
        previewJob?.cancel()
        socket.disconnect()
        posts = emptyList()
        roots.clear()
        lookup.clear()
        setState { ChatThreadState(account = null, channelId = channelId, type = it.type, sentVersion = it.sentVersion) }
    }

    // ---- start ----

    /** Loads the conversation once: session, members, pinned count, moderation, the group's details, the first page. */
    fun start() {
        if (started) return
        started = true
        val u = user ?: return
        scope.launch {
            try {
                val session = env.chat.session(u)
                session.user?.let { lookup[it.id] = it }
                setState { it.copy(viewerId = session.userId) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // the first page reports the failure
            }
            if (user == null) return@launch
            launch { loadMembers(u) }
            launch { loadPinnedCount(u) }
            // without the list's details (a conversation restored after the app was closed), and for a group always
            if (current.isGroup || !hinted) launch { refreshChannel(u) } else launch { resolveModeration(u) }
            load(refresh = false)
            if (active) connect()
        }
    }

    private suspend fun loadMembers(u: String) {
        try {
            val members = env.chat.call(u) { env.chat.api.members(it.token, channelId) }
            memberIds = members.map { it.userId }
            val viewer = current.viewerId
            if (anchor == null && viewer != null) {
                members.firstOrNull { it.userId == viewer }?.lastViewedAt?.let { anchor = it; rebuild() }
            }
            if (memberIds.isNotEmpty()) {
                val users = env.chat.call(u) { env.chat.api.usersByIds(it.token, memberIds) }
                users.forEach { lookup[it.id] = it }
                if (user == null) return
                rebuild()
            }
            refreshTitle()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // RN ignores member failures so the messages still show
        }
    }

    private suspend fun loadPinnedCount(u: String) {
        try {
            val pinned = env.chat.call(u) { env.chat.api.pinned(it.token, channelId) }
            if (user != null) setState { it.copy(pinnedCount = pinned.size) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            setState { it.copy(pinnedCount = 0) }
        }
    }

    /** RN resolveModeration: the viewer moderates the community this channel belongs to (owner, admin, mod). */
    private suspend fun resolveModeration(u: String) {
        val community = if (current.isGroup) null else ChatsModel.communityOf(ChatChannel(channelId, name = channelName, displayName = channelName, header = channelHeader))
        if (community == null) {
            setState { it.copy(canModerate = false) }
            return
        }
        try {
            val c = env.communityApi.community(community, u)
            val role = c?.team?.firstOrNull { it.account == u }?.role
            if (user != null) setState { it.copy(canModerate = role in MODERATOR_ROLES) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            setState { it.copy(canModerate = false) }
        }
    }

    /**
     * RN: a group's name, owner and members from the list, so a rename made elsewhere is never stale; for a channel
     * opened without the list (restored), its name and header, then whether the viewer moderates it.
     */
    private suspend fun refreshChannel(u: String) {
        try {
            val c = env.chat.call(u) { env.chat.api.channels(it.token) }.firstOrNull { it.id == channelId }
            if (c == null) {
                resolveModeration(u)
                return
            }
            if (user == null) return
            // opened by id alone (a restored screen, a debug link): the channel says what it is
            if (current.type.isEmpty() && c.type.isNotEmpty()) setState { it.copy(type = c.type, groupOwner = c.groupOwner, groupName = c.groupName.orEmpty()) }
            if (!hinted) {
                channelName = if (c.isDirect) channelName.ifEmpty { c.directUser?.hiveName ?: c.shownName } else c.shownName
                channelHeader = c.header.ifEmpty { c.purpose }
                if (anchor == null) anchor = listOf(c.lastViewedAt, c.lastViewAt).firstOrNull { it != null && it != 0L }
            }
            resolveModeration(u)
            groupUsers = c.groupUsers
            groupUsers.forEach { x -> lookup.getOrPut(x.id) { x } }
            setState { it.copy(groupOwner = c.groupOwner, groupName = if (groupRenamedLive) it.groupName else c.groupName.orEmpty()) }
            refreshTitle()
            rebuild()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /** RN headerTitle: a group's name or members; a direct message's partner; else the channel's name. */
    private fun refreshTitle() {
        val s = current
        var official = false
        val title = when {
            s.isGroup -> ChatGroups.safeGroupName(s.groupName) ?: ChatGroups.membersTitle(groupUsers, channelName.ifEmpty { L.native.chatThread.group })
            else -> {
                val viewer = s.viewerId
                val other = if (viewer != null && memberIds.size == 2) memberIds.firstOrNull { it != viewer }?.let { lookup[it] } else null
                official = s.isDirect && other?.isOfficial == true
                if (other != null) firstText(other.safeNickname, other.safeName, other.username, other.hiveName) ?: channelName.ifEmpty { channelId }
                else channelName.ifEmpty { channelId }
            }
        }
        setState { it.copy(title = title, official = official) }
    }

    // ---- posts ----

    /** Pull to refresh: the newest page again. */
    fun refresh() = load(refresh = true)

    @Throws(CancellationException::class)
    suspend fun refreshAndWait() {
        refresh()
        state.first { !it.isLoading && !it.isRefreshing }
    }

    /** The list reached its end (the oldest message): the page before. */
    fun loadMore() {
        if (current.loaded && current.hasMore) load(refresh = false)
    }

    private fun load(refresh: Boolean) {
        val u = user ?: return
        if (current.isLoading || current.isRefreshing) return
        if (!refresh && !current.hasMore) return
        val from = if (refresh) null else before
        setState { it.copy(isLoading = !refresh, isRefreshing = refresh, error = null) }
        loadJob = scope.launch {
            try {
                val viewer = current.viewerId
                val page = try {
                    env.chat.call(u) { env.chat.api.posts(it.token, channelId, from, viewer) }
                } catch (e: ChatApiException) {
                    // RN: not a member yet, join and read again
                    if (e.status !in setOf(401, 403, 404)) throw e
                    val joined = env.chat.call(u) { env.chat.api.join(it.token, channelId) }
                    env.chat.call(u) { env.chat.api.posts(it.token, joined?.id ?: channelId, from, viewer) }
                }
                if (user == null) return@launch
                page.users.forEach { lookup[it.id] = it }
                page.memberCount?.let { c -> setState { it.copy(memberCount = c) } }
                page.onlineUserIds?.let { onlineIds = it }
                posts = if (refresh) page.posts else (posts + page.posts).distinctBy { it.id }
                page.posts.lastOrNull()?.let { before = it.id }
                setState { it.copy(isLoading = false, isRefreshing = false, loaded = true, hasMore = page.posts.isNotEmpty()) }
                rebuild()
                resolveUsers(u, posts.map { it.userId }.filter { it.isNotEmpty() && it !in lookup }.distinct())
                val missingRoots = page.posts.map { it.rootId }.filter { it.isNotEmpty() && posts.none { p -> p.id == it } && it !in roots }.distinct()
                fetchRoots(u, missingRoots)
                fetchCards(page.posts)
                afterLoad()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (user == null) return@launch
                setState { it.copy(isLoading = false, isRefreshing = false, loaded = true, error = ChatsModel.message(e, L.native.chatThread.unableToLoadMessages)) }
            }
        }
    }

    /** RN: fewer than 15 rows asks for the page before; the unread marker is scrolled to once; the channel is marked read. */
    private fun afterLoad() {
        val s = current
        if (s.items.size < AUTO_FILL_ROWS && s.hasMore && posts.isNotEmpty()) {
            scope.launch { load(refresh = false) }
        }
        if (!scrolledToUnread && s.unreadItemId != null) {
            scrolledToUnread = true
            setState { it.copy(scrollTo = it.unreadItemId) }
        }
        markViewed()
    }

    /** RN `_markChannelViewed` with the newest post's time; at most once per newer post. */
    private fun markViewed() {
        val u = user ?: return
        val latest = posts.maxOfOrNull { it.timestamp } ?: return
        if (latest == 0L || latest <= lastMarked) return
        lastMarked = latest
        scope.launch {
            try {
                env.chat.call(u) { env.chat.api.markViewed(it.token, channelId) }
                markedAny = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                lastMarked = 0
            }
        }
    }

    fun consumeScroll() = setState { it.copy(scrollTo = null) }

    private suspend fun resolveUsers(u: String, ids: List<String>) {
        if (ids.isEmpty()) return
        try {
            env.chat.call(u) { env.chat.api.usersByIds(it.token, ids) }.forEach { lookup[it.id] = it }
            if (user != null) rebuild()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun fetchRoots(u: String, ids: List<String>) {
        if (ids.isEmpty()) return
        for (id in ids) {
            try {
                env.chat.call(u) { env.chat.api.post(it.token, channelId, id) }?.let { roots[it.id] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
        if (user == null) return
        rebuild()
        resolveUsers(u, roots.values.map { it.userId }.filter { it.isNotEmpty() && it !in lookup }.distinct())
    }

    /** RN HiveLinkPreview for messages without link props: the post a message links to, read from the chain. */
    private suspend fun fetchCards(page: List<ChatPost>) {
        val keys = page.mapNotNull { hiveLink(it)?.second }.filter { it !in cards }.distinct().take(MAX_CARD_FETCHES)
        if (keys.isEmpty()) return
        for (key in keys) {
            cards[key] = try {
                env.api.posts.getPost(key.author, key.permlink, observer = null)?.let { p -> card(p, "") }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
        if (user != null) rebuild()
    }

    private suspend fun card(p: Post, url: String): ChatLinkCard? {
        if (p.title.isEmpty()) return null
        val summary = p.metadata.description?.takeIf { it.isNotEmpty() } ?: withContext(env.dispatchers.render) { env.renderer.summary(p) }.take(150)
        val image = withContext(env.dispatchers.render) { env.renderer.cardImage(p) }
        return ChatLinkCard(url.ifEmpty { "https://ecency.com/@${p.author}/${p.permlink}" }, p.title, summary, image, p.key)
    }

    /** The first non-image url of a message without link props, when it is a Hive post. */
    private fun hiveLink(post: ChatPost): Pair<String, PostKey>? {
        if (post.linkUrl != null) return null
        val body = post.body
        val urls = ChatText.extractUrls(body)
        if (urls.isEmpty()) return null
        val images = ChatText.extractImageUrls(body)
        val first = urls.firstOrNull { it !in images } ?: return null
        val parsed = PostUrlParser.parse(first) ?: return null
        val author = parsed.author ?: return null
        val permlink = parsed.permlink ?: return null
        return SafeTarget.post(author, permlink)?.let { first to PostKey(author, permlink) }
    }

    // ---- items ----

    /** Builds the rows from the posts (message text, links, mentions, markdown) and the unread marker. */
    private fun rebuild() {
        val now = env.nowMillis()
        val viewer = current.viewerId
        val proxy = ImageProxy(env.proxyBase)
        val out = mutableListOf<ChatItem>()
        var group = mutableListOf<ChatJoinItem>()
        fun flush() {
            when {
                group.size > 1 -> out += ChatJoinGroupItem("group_${group.first().id}", group.sortedBy { it.timestamp }, group.first().timestamp)
                group.size == 1 -> out += group.first()
            }
            group = mutableListOf()
        }
        for (p in posts) {
            if (p.isSystemJoin) {
                group += joinItem(p, now)
                continue
            }
            flush()
            out += if (current.isGroup && p.type == ChatPost.HEADER_CHANGE) notice(p) else message(p, viewer, now, proxy)
        }
        flush()
        // RN firstUnreadIndex: the oldest of the rows above the last read time (counted from the newest)
        val last = anchor ?: 0L
        var unread: String? = null
        for (item in out) if (item.timestamp > last) unread = item.id else break
        setState { it.copy(items = out, unreadItemId = unread, showDmWarning = dmWarning(viewer)) }
    }

    private fun joinItem(p: ChatPost, now: Long): ChatJoinItem {
        val name = ChatText.addedUsername(p, lookup)
        val account = name?.removePrefix("@")?.let { SafeTarget.account(it) }
        val text = if (name != null) ChatText.joinedSuffix(p.timestamp, now) else ChatText.content(p.body, false).text
        return ChatJoinItem(p.id, name, account, text, p.timestamp)
    }

    private fun notice(p: ChatPost): ChatNoticeItem {
        val actor = lookup[p.userId]?.hiveName ?: L.native.chatThread.someone
        val name = ChatGroups.renamedName(p)
        val text = if (name.isNotEmpty()) L.native.chatThread.renamedTheGroupTo(actor = actor, name = name) else L.native.chatThread.removedTheGroupName(actor = actor)
        return ChatNoticeItem(p.id, text, p.timestamp)
    }

    private fun message(p: ChatPost, viewer: String?, now: Long, proxy: ImageProxy): ChatMessageItem {
        val author = lookup[p.userId] ?: p.user
        val name = firstText(author?.hiveName, author?.safeNickname, author?.username, author?.safeName, p.userId)
        val content = ChatText.content(ChatText.body(p, lookup, now))
        val quote = if (p.rootId.isEmpty()) null else quoteFor(p)
        val card = p.linkUrl?.let { url ->
            ChatLinkCard(url, p.linkTitle, p.linkSummary, p.linkImage.takeIf { it.isNotEmpty() }?.let { proxy.proxify(it) })
        } ?: hiveLink(p)?.let { (url, key) -> cards[key]?.copy(url = url) }
        return ChatMessageItem(
            id = p.id,
            post = p,
            own = viewer != null && p.userId == viewer,
            authorName = name,
            authorAccount = name?.lowercase()?.let { SafeTarget.account(it) },
            authorOfficial = OfficialAccounts.postedByOfficial(p, lookup),
            spans = spansFor(p.id, content.text),
            images = content.images.map { proxy.proxify(it) },
            quote = quote,
            card = card,
            reactions = ChatReactions.pills(p.reactions, viewer),
            edited = p.editAt > 0,
            time = if (p.timestamp != 0L) FromNow.format(p.timestamp, now) else "",
            timestamp = p.timestamp,
        )
    }

    /** RN `_renderReplyPreview`: the post's own parent props, else the fetched root message. */
    private fun quoteFor(p: ChatPost): ChatQuote? {
        if (p.hasParentPreview) return ChatQuote(p.parentId ?: p.rootId, p.parentUsername, ChatText.quote(p.parentMessage.orEmpty()))
        val root = roots[p.rootId] ?: posts.firstOrNull { it.id == p.rootId } ?: return null
        val name = lookup[root.userId]?.hiveName
        return ChatQuote(root.id, name, ChatText.quote(root.message))
    }

    /** RN DmWarningBanner: a direct message where the viewer has not written yet (and the notice was not closed). */
    private fun dmWarning(viewer: String?): Boolean {
        if (!current.isDirect || viewer == null) return false
        if (env.chat.warningDismissed(channelId)) return false
        val replied = channelId in repliedHere || posts.any { it.userId == viewer }
        return !replied && posts.isNotEmpty()
    }

    fun dismissDmWarning() {
        env.chat.dismissWarning(channelId, owner)
        setState { it.copy(showDmWarning = false) }
    }

    // ---- live ----

    /** The screen is on screen with the app in front (true) or not: the websocket follows it (RN useFocusEffect). */
    fun setActive(on: Boolean) {
        if (active == on) return
        active = on
        if (on) connect() else {
            socket.disconnect()
            // RN: leaving a conversation that was read refreshes the badge
            if (markedAny) env.chat.refreshUnread()
        }
    }

    private fun connect() {
        val u = user ?: return
        if (!active || !started || env.appLock.current.hidesContent || socket.isActive) return
        scope.launch {
            try {
                val session = env.chat.session(u)
                if (!active || user == null || session.userId == null) return@launch
                socket.connect(env.chat.api.websocketUrl(session.token), session.token)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private fun onEvent(e: ChatEvent) {
        if (e.channelId != channelId || user == null) return
        val viewer = current.viewerId
        when (e) {
            is ChatEvent.Posted -> posted(e.post, viewer)
            is ChatEvent.Edited -> if (e.post.userId != viewer) {
                posts = posts.map { if (it.id == e.post.id) e.post else it }
                rebuild()
            }
            is ChatEvent.Deleted -> {
                posts = posts.filter { it.id != e.postId }
                rebuild()
            }
            is ChatEvent.ReactionAdded -> if (e.reaction.userId != viewer) changeReactions(e.reaction.postId) { list ->
                if (list.any { it.userId == e.reaction.userId && it.emojiName == e.reaction.emojiName }) list else list + e.reaction
            }
            is ChatEvent.ReactionRemoved -> if (e.reaction.userId != viewer) changeReactions(e.reaction.postId) { list ->
                list.filter { !(it.userId == e.reaction.userId && it.emojiName == e.reaction.emojiName) }
            }
            is ChatEvent.Typing -> if (e.userId != viewer) typing(e.userId)
        }
    }

    private fun posted(post: ChatPost, viewer: String?) {
        if (current.isGroup && post.type == ChatPost.HEADER_CHANGE) {
            groupRenamedLive = true
            env.chat.rememberRename(channelId, ChatGroups.renamedName(post), owner)
        }
        val own = viewer != null && post.userId == viewer
        val pendingMatch = own && post.pendingPostId.isNotEmpty() && post.pendingPostId in inFlight
        val fallbackMatch = own && post.pendingPostId.isEmpty() && pendingMessage != null &&
            ChatEmoji.emojify(post.message) == pendingMessage && post.rootId == (pendingRoot ?: "") &&
            kotlin.math.abs(post.createAt - pendingAt) < ECHO_WINDOW_MILLIS
        val echoed = if (pendingMatch) post.pendingPostId else pendingId.takeIf { fallbackMatch }
        if (echoed != null) {
            // the echo proves the create landed even when its HTTP answer never comes (also for a send a later one
            // superseded after the bound)
            clearBan()
            val flight = inFlight.remove(echoed)
            val first = landed.add(echoed)
            repliedHere += channelId
            if (posts.none { it.id == post.id }) posts = listOf(post) + posts
            if (first) flight?.let { finishComposer(it.captured) }
            val latest = flight?.gen == sendGen
            setState { it.copy(sending = if (latest) false else it.sending, sentVersion = if (first) it.sentVersion + 1 else it.sentVersion) }
            if (pendingId == echoed) clearPending()
            rebuild()
            // the sent message is scrolled to, as on the answer's path (the thread may sit at the unread marker above)
            if (first) setState { it.copy(scrollTo = post.id) }
            markViewed()
            return
        }
        if (own && post.pendingPostId.isNotEmpty() && post.pendingPostId in landed) return
        if (posts.none { it.id == post.id }) {
            posts = listOf(post) + posts
            rebuild()
            markViewed()
        }
        val u = user
        if (u != null && post.userId.isNotEmpty() && post.userId !in lookup) scope.launch { resolveUsers(u, listOf(post.userId)) }
    }

    private fun changeReactions(postId: String, change: (List<ChatReaction>) -> List<ChatReaction>) {
        posts = posts.map { if (it.id == postId) it.copy(reactions = change(it.reactions)) else it }
        rebuild()
    }

    private fun typing(userId: String) {
        val until = env.nowMillis() + TYPING_MILLIS
        typingUntil[userId] = until
        updateTyping()
        scope.launch {
            delay(TYPING_MILLIS)
            if ((typingUntil[userId] ?: 0) <= env.nowMillis()) typingUntil.remove(userId)
            updateTyping()
        }
    }

    private fun updateTyping() {
        val viewer = current.viewerId
        val names = typingUntil.keys.filter { it != viewer }.mapNotNull { id -> lookup[id]?.let { u -> firstText(u.hiveName, u.safeNickname, u.username) ?: L.native.chatThread.someone } }
        setState { it.copy(typing = ChatText.typing(names)) }
    }

    // ---- composer ----

    /** The composer's text changed: mentions, the typing event and the link card follow it. */
    fun setText(text: String) {
        setState { it.copy(text = text) }
        updateMentions(text)
        val now = env.nowMillis()
        if (text.isNotEmpty() && socket.connected && now - lastTypingSent >= TYPING_SEND_MILLIS) {
            lastTypingSent = now
            socket.sendTyping(channelId)
        }
        schedulePreview(text)
    }

    private fun updateMentions(text: String) {
        val q = ChatText.mentionQuery(text)
        val users = ChatText.suggestions(lookup.values.toList(), q?.second)
        setState { s ->
            s.copy(mentions = users.map { u ->
                val name = ChatText.mentionName(u)
                val display = firstText(u.safeNickname, u.safeName, u.username, name)
                ChatMention(u, name, display?.takeIf { it != name })
            })
        }
    }

    /** A suggestion was tapped: the `@query` becomes `@name `. */
    fun selectMention(m: ChatMention) {
        val text = current.text
        val q = ChatText.mentionQuery(text)
        val next = ChatText.insertMention(text, q?.first, q?.second, m.name)
        replaceText(next)
    }

    private fun replaceText(text: String) {
        setState { it.copy(text = text, textVersion = it.textVersion + 1) }
        updateMentions(text)
        schedulePreview(text)
    }

    /** RN: the first non-image url of the text, 500 ms after the last change; a Hive post's title, summary and image. */
    private fun schedulePreview(text: String) {
        previewJob?.cancel()
        if (text.isBlank()) {
            setState { it.copy(preview = null, fetchingPreview = false) }
            return
        }
        previewJob = scope.launch {
            delay(PREVIEW_DEBOUNCE_MILLIS)
            val urls = ChatText.extractUrls(text)
            val images = ChatText.extractImageUrls(text)
            val first = urls.firstOrNull { it !in images }
            if (first == null) {
                setState { it.copy(preview = null) }
                return@launch
            }
            val parsed = PostUrlParser.parse(first)
            val key = parsed?.let { p -> p.author?.let { a -> p.permlink?.let { pl -> SafeTarget.post(a, pl)?.let { PostKey(a, pl) } } } }
            if (key == null) {
                // RN also reads other pages' metadata here; the app asks no third-party site while someone types
                setState { it.copy(preview = null) }
                return@launch
            }
            setState { it.copy(fetchingPreview = true) }
            val card = try {
                env.api.posts.getPost(key.author, key.permlink, observer = owner)?.let { card(it, first) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            setState { it.copy(preview = card, fetchingPreview = false) }
        }
    }

    fun replyTo(item: ChatMessageItem) {
        val name = lookup[item.post.userId]?.hiveName
        setState { it.copy(editingId = null, replyTo = ChatQuote(item.post.id, name, ChatText.quote(item.post.message))) }
    }

    fun cancelReply() = setState { it.copy(replyTo = null) }

    /** RN `_handleStartEdit`: the message's text in the composer. */
    fun startEdit(item: ChatMessageItem) {
        setState { it.copy(replyTo = null, editingId = item.post.id) }
        replaceText(ChatText.body(item.post, lookup, env.nowMillis()))
    }

    /** RN `_resetEditing`. */
    fun cancelEdit() {
        setState { it.copy(editingId = null, replyTo = null, preview = null) }
        replaceText("")
    }


    /**
     * A send succeeded: the edit, reply quote, link card and mention suggestions it went out with leave the composer
     * (RN clears them on success), unless the user has replaced them since; the text goes when it is still the sent
     * message or empty, so text typed for the next message stays.
     */
    private fun finishComposer(sent: Captured) {
        setState { s ->
            val typed = s.text.trim()
            val clearText = typed.isEmpty() || ChatEmoji.emojify(typed) == sent.message
            s.copy(
                text = if (clearText) "" else s.text,
                textVersion = if (clearText) s.textVersion + 1 else s.textVersion,
                editingId = if (s.editingId == sent.editing) null else s.editingId,
                replyTo = if (s.replyTo?.postId == sent.replyTo) null else s.replyTo,
                preview = if (s.preview?.url == sent.previewUrl) null else s.preview,
                mentions = if (clearText || s.mentions == sent.mentions) emptyList() else s.mentions,
            )
        }
    }

    private fun clearPending() {
        pendingId = null
        pendingMessage = null
        pendingRoot = null
        pendingAt = 0
        pendingGen = 0
        sendBoundJob?.cancel()
    }

    /**
     * RN `_handleSend`: an edit, or a new message (a reply with its quote, the link card as props). A send owns the
     * pending fields only while no later send took them, and the button only while it is the latest send: one that
     * ends after the 30 s bound never frees the button, clears the pending echo or wipes text typed since for a later
     * send. A re-tap of the same message after the bound reuses its pending post id, so the server keeps one post.
     */
    fun send() {
        val u = user ?: return
        val trimmed = current.text.trim()
        if (trimmed.isEmpty() || current.sending) return
        val message = ChatEmoji.emojify(trimmed)
        val editing = current.editingId
        val captured = Captured(message, editing, current.replyTo?.postId, current.preview?.url, current.mentions)
        val gen = ++sendGen
        setState { it.copy(sending = true, error = null) }
        scope.launch {
            var pid: String? = null
            fun ownsPending() = pid != null && pendingId == pid && pendingGen == gen
            try {
                var first = true
                if (editing != null) {
                    val updated = env.chat.call(u) { env.chat.api.edit(it.token, channelId, editing, message) }
                    posts = posts.map { if (it.id == editing) (updated ?: it).copy(message = message, editAt = maxOf(it.editAt, updated?.editAt ?: env.nowMillis())) else it }
                    rebuild()
                } else {
                    val reply = current.replyTo
                    val root = reply?.let { r -> posts.firstOrNull { it.id == r.postId }?.rootId?.ifEmpty { null } ?: r.postId }.orEmpty()
                    val preview = current.preview
                    val props = buildJsonObject {
                        if (reply != null && root.isNotEmpty()) {
                            put("parent_id", reply.postId)
                            put("parent_username", reply.username.orEmpty())
                            put("parent_message", posts.firstOrNull { it.id == reply.postId }?.message ?: reply.text)
                        }
                        if (preview != null) {
                            put("link_url", preview.url)
                            put("link_title", preview.title)
                            put("link_summary", preview.summary)
                            put("link_image", preview.image.orEmpty())
                        }
                    }
                    // a re-tap of the message still in flight (after the bound) is the same create: the server
                    // dedupes on the pending post id
                    val retry = pendingId?.takeIf { pendingMessage == message && pendingRoot == root }
                    val sendId = retry ?: "${current.viewerId ?: "user"}_${env.nowMillis()}_${randomSuffix()}"
                    pid = sendId
                    pendingId = sendId
                    pendingMessage = message
                    pendingRoot = root
                    // a retry's own echo may come without its id: the fallback window starts again
                    pendingAt = env.nowMillis()
                    pendingGen = gen
                    inFlight[sendId] = Flight(gen, captured)
                    sendBoundJob?.cancel()
                    sendBoundJob = scope.launch {
                        delay(SEND_BOUND_MILLIS)
                        if (ownsPending()) setState { it.copy(sending = false) }
                    }
                    val created = env.chat.call(u) { env.chat.api.send(it.token, channelId, message, root, props, sendId) }
                    // only a create that landed shows the ban is over (an edit is not checked by the server)
                    clearBan()
                    first = landed.add(sendId)
                    if (inFlight[sendId]?.gen == gen) inFlight.remove(sendId)
                    if (created != null) {
                        repliedHere += channelId
                        if (first) {
                            if (posts.none { it.id == created.id }) posts = ChatPost.sortNewestFirst(posts + created)
                            rebuild()
                            setState { it.copy(scrollTo = created.id) }
                            resolveUsers(u, listOf(created.userId).filter { it.isNotEmpty() && it !in lookup })
                        }
                    }
                }
                // an echo that came first already finished the composer
                if (first) finishComposer(captured)
                if (ownsPending()) clearPending()
                // the tick and the freed button in one state
                setState { it.copy(sentVersion = if (first) it.sentVersion + 1 else it.sentVersion, sending = if (gen == sendGen) false else it.sending) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val id = pid
                when {
                    // the echo already proved it landed
                    id != null && id in landed -> Unit
                    // a re-tap of the same message is still in flight and reports for both
                    id != null && pendingId == id && pendingGen != gen -> Unit
                    else -> {
                        val ban = (e as? ChatApiException)?.takeIf { it.isBan }
                        val info = ban?.let { ChatBan.info(it.bannedUntil, it.reason, env.nowMillis()) }
                        when {
                            info != null -> showBan(info)
                            ban != null -> env.ui.toast(ChatBan.GENERIC)
                            else -> env.ui.toast(ChatsModel.message(e, L.native.chatThread.unableToSendYour))
                        }
                    }
                }
                if (ownsPending()) clearPending()
            } finally {
                if (gen == sendGen) setState { it.copy(sending = false) }
            }
        }
    }

    private fun randomSuffix(): String = buildString { repeat(9) { append("0123456789abcdefghijklmnopqrstuvwxyz"[Random.nextInt(36)]) } }

    private fun showBan(info: ChatBanInfo) {
        banJob?.cancel()
        setState { it.copy(ban = info, banText = ChatBan.notice(info, env.nowMillis())) }
        banJob = scope.launch {
            while (true) {
                delay(ChatBan.TICK_MILLIS)
                val now = env.nowMillis()
                if (now >= info.bannedUntil) {
                    clearBan()
                    return@launch
                }
                setState { it.copy(banText = ChatBan.notice(info, now)) }
            }
        }
    }

    private fun clearBan() {
        banJob?.cancel()
        banJob = null
        if (current.ban != null) setState { it.copy(ban = null, banText = null) }
    }

    /** An image the platform picked: uploaded through the app's image pipeline, its url added to the text (RN `_handleAttachImage`). */
    fun attach(file: PickedImage) {
        val u = user ?: return
        if (current.uploading || current.sending) return
        setState { it.copy(uploading = true) }
        scope.launch {
            try {
                val url = ImageUploads.upload(env, u, file)
                if (user == null) return@launch
                val prev = current.text
                replaceText(if (prev.isNotEmpty()) "${prev.trim()} $url" else url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // RN: an account that cannot sign uploads gets an alert; anything else the upload's message
                setState { it.copy(alert = if (e is ImageSigningUnavailable) DECRYPT_FAIL else ImageUploader.message(e)) }
            } finally {
                setState { it.copy(uploading = false) }
            }
        }
    }

    fun clearAlert() = setState { it.copy(alert = null) }

    // ---- message actions ----

    /** RN ChatOptionsSheet: what the sheet offers for [item]. */
    fun actions(item: ChatMessageItem): List<ChatMessageAction> = buildList {
        add(ChatMessageAction.REPLY)
        add(ChatMessageAction.COPY)
        add(ChatMessageAction.SHARE)
        add(ChatMessageAction.TRANSLATE)
        if (item.own) add(ChatMessageAction.EDIT)
        if (current.canPin) add(if (item.post.isPinned) ChatMessageAction.UNPIN else ChatMessageAction.PIN)
        if (item.own || current.canModerate) add(ChatMessageAction.REMOVE)
    }

    /** What Copy, Share and Translate use: the message as sent. */
    fun messageText(item: ChatMessageItem): String = item.post.body

    /** RN Translate: the translation sheet over the message's text. */
    fun translationModel(item: ChatMessageItem, readerLanguage: String): TranslationSheetModel =
        env.translationSheetModel(Post("", "", body = messageText(item)), readerLanguage)

    /** RN `_handleAddReaction`: adds, or removes the viewer's own; the list changes once the server agreed. */
    fun react(item: ChatMessageItem, emojiName: String) {
        val u = user ?: return
        val viewer = current.viewerId ?: return
        val postId = item.post.id
        scope.launch {
            try {
                val had = posts.firstOrNull { it.id == postId }?.reactions?.any { it.emojiName == emojiName && it.userId == viewer } == true
                env.chat.call(u) { env.chat.api.react(it.token, channelId, postId, emojiName, add = !had) }
                changeReactions(postId) { list ->
                    if (had) list.filter { !(it.emojiName == emojiName && it.userId == viewer) }
                    else list + ChatReaction(emojiName, viewer, postId, env.nowMillis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                env.ui.toast(REACTION_ERROR)
            }
        }
    }

    /** RN `_handleRemovePost`, after the UI's "Remove message?" confirmation. */
    fun remove(item: ChatMessageItem) {
        val u = user ?: return
        scope.launch {
            try {
                env.chat.call(u) { env.chat.api.delete(it.token, channelId, item.post.id) }
                posts = posts.filter { it.id != item.post.id }
                rebuild()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                env.ui.toast(ChatsModel.message(e, L.native.chatThread.unableToRemoveMessage))
            }
        }
    }

    /** RN pin / unpin: "Success!" or "Error", the pinned count follows. */
    fun setPinned(postId: String, pinned: Boolean, onDone: (() -> Unit)? = null) {
        val u = user ?: return
        scope.launch {
            try {
                env.chat.call(u) { env.chat.api.setPinned(it.token, channelId, postId, pinned) }
                posts = posts.map { if (it.id == postId) it.copy(isPinned = pinned) else it }
                rebuild()
                setState { it.copy(pinnedCount = if (pinned) it.pinnedCount + 1 else maxOf(0, it.pinnedCount - 1)) }
                env.ui.toast(L.alert.success)
                onDone?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                env.ui.toast(L.alert.error)
            }
        }
    }

    /** A pinned message was tapped: scroll to it when it is loaded. */
    fun scrollToMessage(postId: String) {
        if (current.items.any { it.id == postId }) setState { it.copy(scrollTo = postId) }
    }

    // ---- members, pinned, reactors ----

    /**
     * RN OnlineUsersModal: online first, then by display name. Groups and direct messages list only their members;
     * until those are known a group falls back to the members the list knew.
     */
    fun members(): List<ChatMemberRow> {
        val ids = when {
            memberIds.isNotEmpty() -> memberIds
            current.isGroup -> listOfNotNull(current.viewerId) + groupUsers.map { it.id }
            current.isDirect -> emptyList()
            else -> lookup.keys.toList()
        }
        return ids.distinct().mapNotNull { id ->
            val u = lookup[id] ?: return@mapNotNull null
            val username = firstText(u.hiveName, u.username, u.safeNickname, u.safeName) ?: L.native.chatThread.unknown
            val display = firstText(u.safeNickname, u.safeName) ?: username
            ChatMemberRow(id, username, display, id in onlineIds, SafeTarget.account(username.lowercase()))
        }.sortedWith { a, b -> if (a.online != b.online) (if (a.online) -1 else 1) else Collate.compare(a.displayName, b.displayName) }
    }

    /** RN `{total} members • {online} online`. */
    fun membersSubtitle(rows: List<ChatMemberRow>): String =
        L.native.chatThread.membersOnline(members = if (current.memberCount > 0) current.memberCount else rows.size, online = rows.count { it.online }.toString())

    fun pinnedModel(): ChatPinnedModel = ChatPinnedModel(env, this, owner)

    fun reactorsModel(item: ChatMessageItem, emojiName: String): ChatReactorsModel =
        ChatReactorsModel(env, owner, item.post.reactions, current.viewerId, emojiName, lookup.toMap())

    internal fun userFor(id: String): ChatUser? = lookup[id]

    /** The target of a tapped link: posts and profiles open in the app, hive:// goes to the signing flow. */
    fun linkTarget(url: String): LinkTarget? = SafeTarget.external(url)

    override fun close() {
        socket.disconnect()
        if (markedAny) env.chat.refreshUnread()
        super.close()
    }

    companion object {
        internal const val SPAN_CACHE_MAX = 5_000
        val MODERATOR_ROLES = setOf("owner", "admin", "mod")
        const val AUTO_FILL_ROWS = 15
        const val ECHO_WINDOW_MILLIS = 30_000L
        const val SEND_BOUND_MILLIS = 30_000L
        const val TYPING_MILLIS = 5_000L
        const val TYPING_SEND_MILLIS = 2_000L
        const val PREVIEW_DEBOUNCE_MILLIS = 500L
        private const val MAX_CARD_FETCHES = 10
        val EMPTY: String get() = L.chats.threadEmpty
        val NO_MORE: String get() = L.chats.noMoreMessages
        val NEW_MESSAGES: String get() = L.chats.newMessages
        val PLACEHOLDER: String get() = L.profile.message
        val EDITING: String get() = L.chats.editingMessage
        val REACTION_ERROR: String get() = L.chats.reactionError
        val REMOVE_TITLE: String get() = L.chats.removeMessage
        val REMOVE_BODY: String get() = L.chats.removeMessageBody
        val DM_WARNING_TITLE: String get() = L.chats.dmWarningTitle
        val DM_WARNING_BODY: String get() = L.chats.dmWarningBody
        val DM_WARNING_SETTINGS: String get() = L.chats.dmWarningSettings
        val DECRYPT_FAIL: String get() = L.alert.decryptFailAlert
    }
}

data class ChatPinnedState(val items: List<ChatPinnedRow> = emptyList(), val isLoading: Boolean = false, val error: String? = null, val loaded: Boolean = false)

/** A pinned message (RN PinnedMessagesModal): author, time, three lines. */
data class ChatPinnedRow(val post: ChatPost, val authorName: String?, val time: String, val text: String) {
    /** The name to show, in the current language when unknown. */
    val author: String get() = authorName ?: L.chats.anonymous
}

/** RN PinnedMessagesModal: loaded when shown, newest first; unpin removes the row. */
class ChatPinnedModel internal constructor(private val env: AppEnvironment, private val thread: ChatThreadModel, private val owner: String?) :
    StateModel<ChatPinnedState>(ChatPinnedState(), env.dispatchers) {
    fun start() {
        val u = owner?.takeIf { env.session.current.username == it } ?: return
        setState { it.copy(isLoading = true, error = null) }
        scope.launch {
            try {
                val posts = env.chat.call(u) { env.chat.api.pinned(it.token, thread.channelId) }
                val now = env.nowMillis()
                setState {
                    it.copy(isLoading = false, loaded = true, items = posts.map { p ->
                        val a = thread.userFor(p.userId)
                        ChatPinnedRow(p, firstText(a?.hiveName, a?.safeNickname, a?.username), if (p.timestamp != 0L) FromNow.format(p.timestamp, now) else "", firstText(p.message, p.text).orEmpty())
                    })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(isLoading = false, loaded = true, error = ChatsModel.message(e, L.native.chatThread.failedToLoadPinned)) }
            }
        }
    }

    fun unpin(row: ChatPinnedRow) = thread.setPinned(row.post.id, false) {
        setState { s -> s.copy(items = s.items.filter { it.post.id != row.post.id }) }
    }

    companion object {
        val TITLE: String get() = L.chats.pinnedMessages
        val EMPTY: String get() = L.chats.noPinnedMessages
    }
}

data class ChatReactorsState(
    val tabs: List<GroupedReaction> = emptyList(),
    val selected: String? = null,
    val rows: List<ChatReactorRow> = emptyList(),
)

/** One person in the who-reacted sheet: "You", "@name" or "Someone" while unknown. */
data class ChatReactorRow(val userId: String, val label: String, val account: String?)

/** RN chatReactorsSheet: one tab per emoji, the viewer first, unknown reactors looked up once. */
class ChatReactorsModel internal constructor(
    private val env: AppEnvironment,
    private val owner: String?,
    reactions: List<ChatReaction>,
    private val viewerId: String?,
    initialEmoji: String,
    lookup: Map<String, ChatUser>,
) : StateModel<ChatReactorsState>(ChatReactorsState(), env.dispatchers) {
    private val users = lookup.toMutableMap()

    init {
        val tabs = ChatGroups.reactions(reactions, viewerId)
        setState { ChatReactorsState(tabs, initialEmoji.takeIf { e -> tabs.any { it.emojiName == e } } ?: tabs.firstOrNull()?.emojiName) }
        rebuild()
    }

    fun start() {
        val u = owner?.takeIf { env.session.current.username == it } ?: return
        val missing = ChatGroups.missingUserIds(current.tabs.flatMap { it.userIds }, users.keys)
        if (missing.isEmpty()) return
        scope.launch {
            try {
                env.chat.call(u) { env.chat.api.usersByIds(it.token, missing) }.forEach { users[it.id] = it }
                rebuild()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun select(emojiName: String) {
        setState { it.copy(selected = emojiName) }
        rebuild()
    }

    private fun rebuild() {
        val tab = current.tabs.firstOrNull { it.emojiName == current.selected } ?: current.tabs.firstOrNull()
        val ids = tab?.userIds.orEmpty().sortedBy { if (it == viewerId) 0 else 1 }
        setState {
            it.copy(rows = ids.map { id ->
                val name = users[id]?.let { u -> ChatGroups.userName(u) }?.takeIf { n -> n.isNotEmpty() }
                val label = when {
                    id == viewerId -> L.native.chatThread.you
                    name != null -> "@$name"
                    else -> L.native.chatThread.someone
                }
                ChatReactorRow(id, label, name?.let { n -> SafeTarget.account(n.lowercase()) })
            })
        }
    }

    companion object {
        val TITLE: String get() = L.native.chatThread.title
    }
}
