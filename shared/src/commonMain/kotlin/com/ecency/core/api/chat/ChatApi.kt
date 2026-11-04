package com.ecency.core.api.chat

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.EcencyRestApi
import com.ecency.core.api.json.SafeJson
import com.ecency.core.api.model.obj
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.ClientIdentity
import com.ecency.core.l10n.L
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException

/**
 * A refused chat request. [message] is what the RN client shows (src/config/chatApi.ts interceptor): the body's
 * `message`, else `error` (a string or `{message}`), else "Request failed (<status>)". [code] is the body's `code`
 * (a rename by someone else: `not_owner`). A posting ban ([isBan]) carries [bannedUntil] (epoch ms) and [reason].
 */
class ChatApiException(
    status: Int,
    message: String,
    val code: String? = null,
    val prop: String? = null,
    val bannedUntil: Double? = null,
    val reason: String? = null,
) : EcencyHttpException(status, message) {
    /** RN `isBanError`: the body's `prop` is `ecency_chat_banned_until`. */
    val isBan: Boolean get() = prop == BAN_PROP

    companion object {
        const val BAN_PROP = "ecency_chat_banned_until"

        fun of(status: Int, body: String): ChatApiException {
            val o = SafeJson.parseOrNull(body).obj()
            val error = o?.get("error")
            val text = o?.get("message").text()?.takeIf { it.isNotEmpty() }
                ?: error.text()?.takeIf { it.isNotEmpty() }
                ?: error.obj()?.get("message").text()?.takeIf { it.isNotEmpty() }
                ?: L.native.chatApi.requestFailed(status = status.toString())
            return ChatApiException(
                status, text,
                code = o?.get("code").text(),
                prop = o?.get("prop").text(),
                bannedUntil = o?.get("bannedUntil")?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull() },
                reason = o?.get("reason").text(),
            )
        }
    }
}

/**
 * Ecency's chat API, the calls src/providers/chat/mattermost.ts makes, on [base] (RN `ECENCY_BACKEND_API`).
 *
 * Every call but [bootstrap] carries the chat token of one account in `X-MM-Token` (RN setChatApiToken). The token
 * is passed per call, never kept here: two accounts can never share or overwrite each other's token. The token
 * and the account's access token only ever go to a first-party https host ([ClientIdentity.isFirstPartyHost]);
 * with any other [base] nothing is sent at all.
 */
class ChatApi(private val http: AuthHttp, base: String) {
    val base: String = canonicalBase(base) ?: base.trimEnd('/')

    /** Whether [base] may receive credentials; false makes every call fail before anything is sent. */
    val trusted: Boolean = canonicalBase(base) != null

    /** RN chatApi: `wss://` (or `ws://`) on the same host, the token in the query as the RN client sends it. */
    fun websocketUrl(token: String): String? {
        if (!trusted) return null
        return "wss://" + base.removePrefix("https://") + "/api/mattermost/websocket?token=" + EcencyRestApi.encode(token)
    }

    // ---- session ----

    /** `POST /api/mattermost/bootstrap {accessToken, refreshToken?, username}`: the chat token and the viewer's user. */
    @Throws(Exception::class)
    suspend fun bootstrap(accessToken: String, refreshToken: String?, username: String): ChatBootstrap {
        val body = buildJsonObject {
            put("accessToken", accessToken)
            refreshToken?.let { put("refreshToken", it) }
            put("username", username)
        }
        val o = send("POST", "/api/mattermost/bootstrap", null, body).obj()
        val token = o?.get("token").text()?.takeIf { it.isNotEmpty() } ?: throw ChatApiException(200, "Chat session could not be prepared.")
        return ChatBootstrap(token, ChatUser.parse(o?.get("user")))
    }

    // ---- channels ----

    /** `GET /channels` (RN fetchMattermostChannels + normalizeMattermostChannels). */
    @Throws(Exception::class)
    suspend fun channels(token: String): List<ChatChannel> = ChatChannel.parseList(send("GET", "/api/mattermost/channels", token))

    /** RN searchMattermostChannels: fewer than 2 characters asks nothing; 405 retries as GET, 400 is no result. */
    @Throws(Exception::class)
    suspend fun searchChannels(token: String, query: String): List<ChatChannel> {
        val term = query.trim()
        if (term.length < 2) return emptyList()
        val body = buildJsonObject {
            put("term", term)
            put("include_public", true)
            put("include_private", true)
        }
        return try {
            ChatChannel.parseList(unwrap(send("POST", "/api/mattermost/channels/search", token, body), "channels"))
        } catch (e: ChatApiException) {
            when (e.status) {
                405 -> ChatChannel.parseList(unwrap(send("GET", "/api/mattermost/channels/search?term=${EcencyRestApi.encode(term)}", token), "channels"))
                400 -> emptyList()
                else -> throw e
            }
        }
    }

    /** RN searchMattermostUsers: fewer than 2 characters asks nothing; 405 retries as POST. */
    @Throws(Exception::class)
    suspend fun searchUsers(token: String, query: String): List<ChatUser> {
        val term = query.trim()
        if (term.length < 2) return emptyList()
        return try {
            ChatUser.parseList(send("GET", "/api/mattermost/users/search?q=${EcencyRestApi.encode(term)}", token))
        } catch (e: ChatApiException) {
            if (e.status != 405) throw e
            ChatUser.parseList(send("POST", "/api/mattermost/users/search", token, buildJsonObject { put("q", term) }))
        }
    }

    @Throws(Exception::class)
    suspend fun join(token: String, channelId: String): ChatChannel? = ChatChannel.parse(send("POST", "${channel(channelId)}/join", token))

    @Throws(Exception::class)
    suspend fun setFavorite(token: String, channelId: String, favorite: Boolean): ChatChannel? =
        ChatChannel.parse(send("POST", "${channel(channelId)}/favorite", token, buildJsonObject { put("favorite", favorite) }))

    @Throws(Exception::class)
    suspend fun setMuted(token: String, channelId: String, muted: Boolean): ChatChannel? =
        ChatChannel.parse(send("POST", "${channel(channelId)}/mute", token, buildJsonObject { put("muted", muted) }))

    /** RN leaveMattermostChannel: a 404 falls back to `DELETE /channels/{id}/members/me`. */
    @Throws(Exception::class)
    suspend fun leave(token: String, channelId: String) {
        try {
            send("POST", "${channel(channelId)}/leave", token)
        } catch (e: ChatApiException) {
            if (e.status != 404) throw e
            send("DELETE", "${channel(channelId)}/members/me", token)
        }
    }

    /** RN markMattermostChannelViewed: `POST /channels/{id}/view {channel_id, prev_channel_id?}`. */
    @Throws(Exception::class)
    suspend fun markViewed(token: String, channelId: String) {
        send("POST", "${channel(channelId)}/view", token, buildJsonObject { put("channel_id", channelId) })
    }

    @Throws(Exception::class)
    suspend fun members(token: String, channelId: String): List<ChatMember> = ChatMember.parseList(send("GET", "${channel(channelId)}/members", token))

    /** `POST /users/ids {ids}`; nothing is asked for an empty list. */
    @Throws(Exception::class)
    suspend fun usersByIds(token: String, ids: List<String>): List<ChatUser> {
        if (ids.isEmpty()) return emptyList()
        return ChatUser.parseList(send("POST", "/api/mattermost/users/ids", token, buildJsonObject { put("ids", JsonArray(ids.map { JsonPrimitive(it) })) }))
    }

    /**
     * RN startMattermostDirectMessage: `POST /direct` with `{userId, username}` for a chat user or `{username}` for a
     * Hive name. Answers the channel, or `{channelId}` (then a channel with only its id).
     */
    @Throws(Exception::class)
    suspend fun direct(token: String, username: String, userId: String? = null): ChatChannel? {
        val body = buildJsonObject {
            userId?.let { put("userId", it) }
            put("username", username)
        }
        return ChatChannel.parse(send("POST", "/api/mattermost/direct", token, body))
    }

    /** RN createMattermostGroup: `POST /group {usernames}` -> `{channelId}`. The server's refusal text is the error. */
    @Throws(Exception::class)
    suspend fun createGroup(token: String, usernames: List<String>): String {
        val o = send("POST", "/api/mattermost/group", token, buildJsonObject { put("usernames", JsonArray(usernames.map { JsonPrimitive(it) })) }).obj()
        return o?.get("channelId").text()?.takeIf { it.isNotEmpty() } ?: throw ChatApiException(200, "Could not start the group. Please try again.")
    }

    /** RN renameMattermostGroup: `PUT /channels/{id}/name {name}` -> `{name}` ("" clears it). */
    @Throws(Exception::class)
    suspend fun renameGroup(token: String, channelId: String, name: String): String? =
        send("PUT", "${channel(channelId)}/name", token, buildJsonObject { put("name", name) }).obj()?.get("name").text()

    // ---- posts ----

    /** `GET /channels/{id}/posts[?before=<post id>]`. */
    @Throws(Exception::class)
    suspend fun posts(token: String, channelId: String, before: String?, viewerId: String?): ChatPostsPage {
        val query = before?.let { "?before=${EcencyRestApi.encode(it)}" }.orEmpty()
        return ChatPostsPage.parse(send("GET", "${channel(channelId)}/posts$query", token), viewerId)
    }

    @Throws(Exception::class)
    suspend fun post(token: String, channelId: String, postId: String): ChatPost? = ChatPost.parse(send("GET", "${post(channelId, postId)}", token))

    /**
     * RN sendMattermostMessage: `POST /channels/{id}/posts {message, rootId, props, pendingPostId}`. A ban comes back
     * as a [ChatApiException] with [ChatApiException.isBan].
     */
    @Throws(Exception::class)
    suspend fun send(token: String, channelId: String, message: String, rootId: String, props: JsonObject, pendingPostId: String): ChatPost? {
        val body = buildJsonObject {
            put("message", message)
            put("rootId", rootId)
            put("props", props)
            put("pendingPostId", pendingPostId)
        }
        return ChatPost.parse(send("POST", "${channel(channelId)}/posts", token, body))
    }

    @Throws(Exception::class)
    suspend fun edit(token: String, channelId: String, postId: String, message: String): ChatPost? =
        ChatPost.parse(send("PATCH", post(channelId, postId), token, buildJsonObject { put("message", message) }))

    @Throws(Exception::class)
    suspend fun delete(token: String, channelId: String, postId: String) {
        send("DELETE", post(channelId, postId), token)
    }

    @Throws(Exception::class)
    suspend fun react(token: String, channelId: String, postId: String, emoji: String, add: Boolean) {
        send(if (add) "POST" else "DELETE", "${post(channelId, postId)}/reactions", token, buildJsonObject { put("emoji", emoji) })
    }

    @Throws(Exception::class)
    suspend fun setPinned(token: String, channelId: String, postId: String, pinned: Boolean) {
        send(if (pinned) "POST" else "DELETE", "${post(channelId, postId)}/pin", token)
    }

    /** RN fetchMattermostPinnedPosts plus the modal's reading: `order` with `posts`, a list, or a map; newest first. */
    @Throws(Exception::class)
    suspend fun pinned(token: String, channelId: String): List<ChatPost> =
        ChatPost.sortNewestFirst(ChatPost.parseList(send("GET", "${channel(channelId)}/pinned", token)))

    // ---- privacy ----

    @Throws(Exception::class)
    suspend fun dmPrivacy(token: String): DmPrivacy {
        val e = send("GET", "/api/mattermost/me/dm-privacy", token)
        val value = e.obj()?.get("privacy").text() ?: e.text()
        return DmPrivacy.of(value) ?: DmPrivacy.ALL
    }

    @Throws(Exception::class)
    suspend fun setDmPrivacy(token: String, privacy: DmPrivacy): DmPrivacy {
        val e = send("PUT", "/api/mattermost/me/dm-privacy", token, buildJsonObject { put("privacy", privacy.apiValue) })
        return DmPrivacy.of(e.obj()?.get("privacy").text()) ?: privacy
    }

    // ---- plumbing ----

    private fun channel(id: String) = "/api/mattermost/channels/${EcencyRestApi.encode(id)}"

    private fun post(channelId: String, postId: String) = "${channel(channelId)}/posts/${EcencyRestApi.encode(postId)}"

    /** RN `data.channels || data`. */
    private fun unwrap(e: JsonElement?, key: String): JsonElement? = (e as? JsonObject)?.get(key) ?: e

    private suspend fun send(method: String, path: String, token: String?, body: JsonObject? = null): JsonElement? {
        if (!trusted) throw ChatApiException(0, "Chat is not available in this build.")
        val headers = buildMap {
            put("Content-Type", "application/json")
            token?.let { put(TOKEN_HEADER, it) }
        }
        val result = try {
            http.send(AuthHttpRequest(method, base + path, headers, body?.toString(), timeoutMillis = TIMEOUT_MILLIS))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EcencyHttpException.ofSendFailure(e)
        }
        if (result.status !in 200..299) throw ChatApiException.of(result.status, result.body)
        if (result.body.isBlank()) return null
        return SafeJson.parseOrNull(result.body)
    }

    companion object {
        const val TOKEN_HEADER = "X-MM-Token"

        private val PLAIN_HTTPS_HOST = Regex("""^https://([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+)$""")

        /**
         * [base] as `https://<host>` in lower case when it is exactly a first-party https host (a trailing slash
         * aside): no port (not even 443 or 0443), path, query, fragment, user info or anything but ASCII letters,
         * digits, dots and hyphens. Null otherwise.
         */
        fun canonicalBase(base: String?): String? {
            val m = PLAIN_HTTPS_HOST.matchEntire(base?.trimEnd('/') ?: return null) ?: return null
            val canonical = "https://" + m.groupValues[1].lowercase()
            return canonical.takeIf { ClientIdentity.isFirstPartyHost("$it/") }
        }

        /** RN chatApi `timeout: 20000`. */
        const val TIMEOUT_MILLIS = 20_000L
    }
}
