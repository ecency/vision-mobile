package com.ecency.core.api.chat

import com.ecency.core.api.model.arr
import com.ecency.core.api.model.obj
import com.ecency.core.app.OfficialAccounts
import com.ecency.core.l10n.L
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * The shapes Ecency's chat API (`/api/mattermost/...`, a relay in front of Mattermost) answers with, read the way
 * the RN app reads them (src/providers/chat/mattermost.ts, src/screens/chats). Every field comes from other people
 * (names, messages, group names, reactions): it is untrusted text. None of these readers throw; a field of the
 * wrong type reads as absent, as a missing field does in the RN app.
 */

/** A JSON number (never a numeric string: RN checks `Number.isFinite`, which is false for "3"). */
internal fun JsonElement?.finiteNumber(): Double? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull || p.isString) return null
    return p.doubleOrNull?.takeIf { it.isFinite() }
}

/** A JSON string, or null for anything else (RN `typeof x === 'string'`). */
internal fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Truthiness of a JSON value as JS sees it (`!!x`). */
internal fun JsonElement?.truthy(): Boolean = when (val p = this) {
    null, is JsonNull -> false
    is JsonPrimitive -> when {
        p.isString -> p.content.isNotEmpty()
        p.booleanOrNull != null -> p.booleanOrNull == true
        else -> p.doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: true
    }
    else -> true
}

/** A JS `a || b` over strings: the first non-empty string. */
internal fun firstText(vararg values: String?): String? = values.firstOrNull { !it.isNullOrEmpty() }

/** A Mattermost user as the chat API sends it. */
data class ChatUser(
    val id: String,
    val username: String? = null,
    val nickname: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    /** `name`, which some answers carry. */
    val name: String? = null,
    /** `props.hive_username`. */
    val hiveProp: String? = null,
) {
    /**
     * RN getHiveUsernameFromMattermostUser: `props.hive_username || nickname || username || name`. The Hive account
     * (avatar, profile, mentions) is taken as sent; only the chosen names after it are checked ([safeNickname]).
     */
    val hiveName: String? get() = firstText(hiveProp, safeNickname, username, safeName)

    /** RN's display name in member lists: `nickname || name || <hive name or username>`. */
    val displayName: String get() = firstText(safeNickname, safeName) ?: hiveName ?: id

    /**
     * [nickname] and [name] unless they pose as an official account this user is not ([OfficialAccounts.safeName],
     * web safeChatName): chat shows these, never the raw ones. Worked out once per user.
     */
    val safeNickname: String? by lazy { OfficialAccounts.safeName(nickname, username) }
    val safeName: String? by lazy { OfficialAccounts.safeName(name, username) }

    /** One of Ecency's own accounts, by its chat username only (web isOfficialChatAccount on `username`). */
    val isOfficial: Boolean get() = OfficialAccounts.isOfficial(username)

    companion object {
        fun parse(e: JsonElement?): ChatUser? {
            val o = e.obj() ?: return null
            val id = o["id"].text()?.takeIf { it.isNotEmpty() } ?: o["user_id"].text()?.takeIf { it.isNotEmpty() } ?: return null
            return ChatUser(
                id = id,
                username = o["username"].text(),
                nickname = o["nickname"].text(),
                firstName = o["first_name"].text(),
                lastName = o["last_name"].text(),
                name = o["name"].text(),
                hiveProp = o["props"].obj()?.get("hive_username").text(),
            )
        }

        /** A list, or a map of id to user (RN normalizeUsersFromMap), or `{users: ...}`. */
        fun parseList(e: JsonElement?): List<ChatUser> {
            val list: List<JsonElement> = when (e) {
                is JsonArray -> e
                is JsonObject -> (e["users"] as? JsonArray) ?: (e["users"] as? JsonObject)?.values?.toList()
                    ?: if (e["id"] != null) listOf(e) else e.values.toList()
                else -> emptyList()
            }
            return list.mapNotNull(::parse)
        }
    }
}

/** One reaction: [emojiName] is Mattermost's name ("+1", "heart"). */
data class ChatReaction(val emojiName: String, val userId: String, val postId: String = "", val createAt: Long = 0) {
    companion object {
        fun parse(e: JsonElement?): ChatReaction? {
            val o = e.obj() ?: return null
            val emoji = o["emoji_name"].text()?.takeIf { it.isNotEmpty() } ?: return null
            return ChatReaction(emoji, o["user_id"].text().orEmpty(), o["post_id"].text().orEmpty(), o["create_at"].finiteNumber()?.toLong() ?: 0)
        }
    }
}

/**
 * A message (Mattermost post). [type] is empty for a plain message, `system_*` for notices. The `props` the app
 * reads are named; [reactions] come from `metadata.reactions`, else `props.reactions` (RN).
 */
data class ChatPost(
    val id: String,
    val channelId: String = "",
    val userId: String = "",
    val message: String = "",
    val type: String = "",
    val createAt: Long = 0,
    val updateAt: Long = 0,
    val editAt: Long = 0,
    val rootId: String = "",
    val pendingPostId: String = "",
    val isPinned: Boolean = false,
    val reactions: List<ChatReaction> = emptyList(),
    /** `props.message` and `text`, RN's fallbacks when `message` is empty. */
    val propsMessage: String = "",
    val text: String = "",
    val parentId: String? = null,
    val parentUsername: String? = null,
    val parentMessage: String? = null,
    val linkUrl: String? = null,
    val linkTitle: String = "",
    val linkSummary: String = "",
    val linkImage: String = "",
    /** System add / join notices: who was added (props addedUserId, userId, added_user_id, user_id). */
    val addedUserId: String? = null,
    /** props addedUsername / username. */
    val addedUsername: String? = null,
    /** A group rename notice (`system_header_change`): the new name, trimmed; empty when the name was removed. */
    val newHeader: String? = null,
    /** `post.user` when the answer embeds it. */
    val user: ChatUser? = null,
    /** `props.override_username` or `props.from_webhook`: a webhook or bot post that can name any user. */
    val relayed: Boolean = false,
) {
    /** RN `create_at || update_at`. */
    val timestamp: Long get() = if (createAt != 0L) createAt else updateAt

    /** The body RN shows for a plain post: `message || props.message || text`. */
    val body: String get() = firstText(message, propsMessage, text).orEmpty()

    /** True when [parentId] or [parentUsername] or [parentMessage] came with the post (RN passes `post.props` as the preview). */
    val hasParentPreview: Boolean get() = parentId != null || parentUsername != null || parentMessage != null

    val isSystemJoin: Boolean get() = type in SYSTEM_JOIN_TYPES

    companion object {
        val SYSTEM_JOIN_TYPES = setOf("system_add_to_channel", "system_add_to_team", "system_join_team", "system_join_channel")
        const val HEADER_CHANGE = "system_header_change"

        /** RN normalizePost: `payload.post` when there is one, else the payload. */
        fun parse(e: JsonElement?): ChatPost? {
            val root = e.obj() ?: return null
            val o = root["post"].obj() ?: root
            val id = o["id"].text()?.takeIf { it.isNotEmpty() } ?: return null
            val props = o["props"].obj()
            val reactions = (o["metadata"].obj()?.get("reactions") as? JsonArray ?: props?.get("reactions") as? JsonArray)
                ?.mapNotNull(ChatReaction::parse).orEmpty()
            val addedUserId = firstText(props?.get("addedUserId").text(), props?.get("userId").text(), props?.get("added_user_id").text(), props?.get("user_id").text())
            return ChatPost(
                id = id,
                channelId = o["channel_id"].text().orEmpty(),
                userId = o["user_id"].text() ?: o["user"].obj()?.get("id").text().orEmpty(),
                message = o["message"].text().orEmpty(),
                type = o["type"].text().orEmpty(),
                createAt = o["create_at"].finiteNumber()?.toLong() ?: 0,
                updateAt = o["update_at"].finiteNumber()?.toLong() ?: 0,
                editAt = o["edit_at"].finiteNumber()?.toLong() ?: 0,
                rootId = o["root_id"].text().orEmpty(),
                pendingPostId = o["pending_post_id"].text().orEmpty(),
                isPinned = o["is_pinned"].truthy(),
                reactions = reactions,
                propsMessage = props?.get("message").text().orEmpty(),
                text = o["text"].text().orEmpty(),
                parentId = props?.get("parent_id").text(),
                parentUsername = props?.get("parent_username").text(),
                parentMessage = props?.get("parent_message").text(),
                linkUrl = props?.get("link_url").text()?.takeIf { it.isNotEmpty() },
                linkTitle = props?.get("link_title").text().orEmpty(),
                linkSummary = props?.get("link_summary").text().orEmpty(),
                linkImage = props?.get("link_image").text().orEmpty(),
                addedUserId = addedUserId,
                addedUsername = firstText(props?.get("addedUsername").text(), props?.get("username").text()),
                newHeader = props?.get("new_header").text()?.trim(),
                user = ChatUser.parse(o["user"]),
                relayed = props?.get("override_username").truthy() || props?.get("from_webhook").truthy(),
            )
        }

        /**
         * RN normalizePosts: an array, `{posts: [...]}`, `{order, posts: {id: post}}` (in order), `{posts: {id: post}}`
         * or `{items: [...]}`. Unreadable entries are skipped.
         */
        fun parseList(e: JsonElement?): List<ChatPost> {
            val list: List<JsonElement?> = when (e) {
                is JsonArray -> e
                is JsonObject -> {
                    val posts = e["posts"]
                    val order = e["order"] as? JsonArray
                    when {
                        posts is JsonArray -> posts
                        posts is JsonObject && order != null -> order.mapNotNull { id -> id.text()?.let { posts[it] } }
                        posts is JsonObject -> posts.values.toList()
                        e["items"] is JsonArray -> e["items"] as JsonArray
                        else -> emptyList()
                    }
                }
                else -> emptyList()
            }
            return list.mapNotNull(::parse)
        }

        /** RN sortPosts: newest first by `create_at` (stable for equal times). */
        fun sortNewestFirst(posts: List<ChatPost>): List<ChatPost> = posts.sortedByDescending { it.createAt }
    }
}

/**
 * A channel of the viewer: public (O) and private (P) community channels, direct messages (D) and group
 * conversations (G). Unread numbers are kept as RN reads them ([ChatUnread]).
 */
data class ChatChannel(
    val id: String,
    val name: String = "",
    val displayName: String = "",
    val type: String = "",
    val header: String = "",
    val purpose: String = "",
    val lastPostAt: Long = 0,
    val updateAt: Long = 0,
    val createAt: Long = 0,
    /** `last_viewed_at` as sent; null when absent or null (RN `?? last_post_at`). */
    val lastViewedAt: Long? = null,
    /** `last_view_at`, the older name. */
    val lastViewAt: Long? = null,
    val isFavorite: Boolean = false,
    val isMuted: Boolean = false,
    val teammateId: String? = null,
    val directUser: ChatUser? = null,
    /** Groups: the name its owner gave it; null when none. */
    val groupName: String? = null,
    val groupOwner: Boolean = false,
    /** Groups: the other members. */
    val groupUsers: List<ChatUser> = emptyList(),
    /** Only `false` from the server matters ([ChatUnread.eligible]). */
    val unreadEligible: Boolean? = null,
    /** The finite values among the six mention counters RN reads. */
    val mentionCounters: List<Double> = emptyList(),
    /** RN's message counter: the first finite of message_count, unread_messages, unread_msg_count, unread_count. */
    val messageCounter: Double? = null,
) {
    val isDirect: Boolean get() = type == "D"
    val isGroup: Boolean get() = type == "G"

    /** A direct message or a group: private conversations (RN isConversationChannel). */
    val isConversation: Boolean get() = isDirect || isGroup

    /** RN `display_name || name`. */
    val shownName: String get() = firstText(displayName, name).orEmpty()

    /** RN lastInteraction for sorting: `last_viewed_at ?? last_post_at ?? 0`. */
    val lastInteraction: Long get() = lastViewedAt ?: lastPostAt

    /** The two member ids a direct message channel's name holds (`a__b`). */
    val dmUserIds: List<String> get() = name.split("__").filter { it.isNotEmpty() }

    companion object {
        private val MENTION_FIELDS = listOf("unread_mentions", "mention_count", "mentions_count", "mention_count_root", "urgent_mention_count", "channel_wide_mention_count")
        private val MESSAGE_FIELDS = listOf("message_count", "unread_messages", "unread_msg_count", "unread_count")

        fun parse(e: JsonElement?): ChatChannel? {
            val root = e.obj() ?: return null
            val o = root["channel"].obj()?.takeIf { root["id"] == null } ?: root
            val id = firstText(o["id"].text(), o["channel_id"].text(), o["channelId"].text()) ?: return null
            val viewed = o["last_viewed_at"]
            val viewAt = o["last_view_at"]
            return ChatChannel(
                id = id,
                name = o["name"].text().orEmpty(),
                displayName = o["display_name"].text().orEmpty(),
                type = o["type"].text().orEmpty(),
                header = o["header"].text().orEmpty(),
                purpose = o["purpose"].text().orEmpty(),
                lastPostAt = o["last_post_at"].finiteNumber()?.toLong() ?: 0,
                updateAt = o["update_at"].finiteNumber()?.toLong() ?: 0,
                createAt = o["create_at"].finiteNumber()?.toLong() ?: 0,
                lastViewedAt = viewed.finiteNumber()?.toLong(),
                lastViewAt = viewAt.finiteNumber()?.toLong(),
                isFavorite = o["is_favorite"].truthy(),
                isMuted = o["is_muted"].truthy(),
                teammateId = o["teammate_id"].text()?.takeIf { it.isNotEmpty() },
                directUser = ChatUser.parse(o["directUser"]),
                groupName = o["group_name"].text(),
                groupOwner = o["group_owner"].truthy(),
                groupUsers = (o["groupUsers"] as? JsonArray)?.mapNotNull(ChatUser::parse).orEmpty(),
                unreadEligible = (o["unread_eligible"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull,
                mentionCounters = MENTION_FIELDS.mapNotNull { o[it].finiteNumber() },
                messageCounter = MESSAGE_FIELDS.firstNotNullOfOrNull { o[it].finiteNumber() },
            )
        }

        /** RN normalizeMattermostChannels: an array, or `{channels: [...]}`; anything else is none. */
        fun parseList(e: JsonElement?): List<ChatChannel> {
            val list = e as? JsonArray ?: (e as? JsonObject)?.get("channels") as? JsonArray ?: return emptyList()
            return list.mapNotNull(::parse)
        }
    }
}

/** A channel member: who and when they last read it. */
data class ChatMember(val userId: String, val lastViewedAt: Long?) {
    companion object {
        fun parseList(e: JsonElement?): List<ChatMember> {
            val list = e as? JsonArray ?: (e as? JsonObject)?.get("members").arr() ?: return emptyList()
            return list.mapNotNull { m ->
                val o = m.obj() ?: return@mapNotNull null
                val id = firstText(o["user_id"].text(), o["id"].text()) ?: return@mapNotNull null
                ChatMember(id, o["last_viewed_at"].finiteNumber()?.toLong()?.takeIf { it != 0L })
            }
        }
    }
}

/** One page of `GET /channels/{id}/posts`: the posts newest first and what the answer says about the channel. */
data class ChatPostsPage(
    val posts: List<ChatPost>,
    val users: List<ChatUser> = emptyList(),
    /** The viewer's last read time, from `member`, `membership` or the viewer's row of `members`. */
    val lastViewedAt: Long? = null,
    val memberCount: Int? = null,
    val onlineUserIds: List<String>? = null,
) {
    companion object {
        fun parse(e: JsonElement?, viewerId: String?): ChatPostsPage {
            val o = e as? JsonObject
            val membership = o?.get("member").obj() ?: o?.get("membership").obj()
                ?: (o?.get("members") as? JsonArray)?.mapNotNull { it.obj() }?.firstOrNull { m ->
                    viewerId != null && (m["user_id"].text() == viewerId || m["id"].text() == viewerId)
                }
            // RN `last_viewed_at || last_view_at`: a zero falls through to the older name
            val viewed = membership?.let { m ->
                listOf(m["last_viewed_at"], m["last_view_at"]).firstNotNullOfOrNull { it.finiteNumber()?.toLong()?.takeIf { v -> v != 0L } }
            }
            return ChatPostsPage(
                posts = ChatPost.sortNewestFirst(ChatPost.parseList(e)),
                users = o?.get("users")?.let { ChatUser.parseList(it) }.orEmpty(),
                lastViewedAt = viewed,
                memberCount = o?.get("memberCount").finiteNumber()?.toInt()?.takeIf { it > 0 },
                onlineUserIds = (o?.get("onlineUserIds") as? JsonArray)?.mapNotNull { it.text() },
            )
        }
    }
}

/** What `POST /api/mattermost/bootstrap` hands back: the chat token for the account and its Mattermost user. */
class ChatBootstrap(val token: String, val user: ChatUser?) {
    val userId: String? get() = user?.id

    /** Never prints the token. */
    override fun toString(): String = "ChatBootstrap(user=${user?.id})"
}

/** RN MattermostDmPrivacy. */
enum class DmPrivacy(val apiValue: String, private val labelText: () -> String) {
    ALL("all", { L.settings.dmPrivacy.allowAll }),
    FOLLOWERS("followers", { L.settings.dmPrivacy.followersOnly }),
    NONE("none", { L.settings.dmPrivacy.noOne }),
    ;

    val label: String get() = labelText()

    companion object {
        fun of(value: String?): DmPrivacy? = entries.firstOrNull { it.apiValue == value }
    }
}
