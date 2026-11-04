package com.ecency.core.app

import com.ecency.core.api.chat.ChatPost
import com.ecency.core.api.chat.ChatReaction
import com.ecency.core.api.chat.text
import com.ecency.core.api.json.SafeJson
import com.ecency.core.api.model.obj
import com.ecency.core.auth.HasSocket
import com.ecency.core.auth.HasSocketFactory
import com.ecency.core.auth.HasSocketRefused
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.cancellation.CancellationException

/** A live chat event for one channel (RN useMattermostWebSocket's callbacks). */
sealed interface ChatEvent {
    val channelId: String

    data class Posted(override val channelId: String, val post: ChatPost) : ChatEvent
    data class Edited(override val channelId: String, val post: ChatPost) : ChatEvent
    data class Deleted(override val channelId: String, val postId: String) : ChatEvent
    data class ReactionAdded(override val channelId: String, val reaction: ChatReaction) : ChatEvent
    data class ReactionRemoved(override val channelId: String, val reaction: ChatReaction) : ChatEvent
    data class Typing(override val channelId: String, val userId: String) : ChatEvent
}

/**
 * RN mattermostWebSocket.ts: one connection to the chat API's websocket for the open conversation.
 *
 * Opens [url] (the chat token is in the query, as RN sends it), sends `authentication_challenge`, and after the
 * server's `hello` pings every 30 s; no pong within 10 s, or none for 40 s, drops the connection. A dropped
 * connection comes back after 1, 2, 4 ... 30 s, for at most an hour of being disconnected; only a connection that got
 * the server's `hello` counts as up (one that opens and closes before it is a failed attempt, so the backoff grows and
 * the hour runs). A connection that does not open within 15 s, a handshake refused with 401, 403, 404 or 426, a close
 * with one of those codes or 1002 or 1003 (RN's doNotReconnectCodes) and a FAIL answer to the authentication
 * challenge end it for good. `posted` and `post_edited` events are delivered once each (the last 1000 are remembered).
 *
 * [onConnected] tells whether a connection is up. Everything runs in [scope] (the screen model's main scope).
 */
class ChatSocket internal constructor(
    private val scope: CoroutineScope,
    private val sockets: () -> HasSocketFactory,
    private val nowMillis: () -> Long,
    private val onEvent: (ChatEvent) -> Unit,
    private val onConnected: (Boolean) -> Unit,
) {
    private var job: Job? = null
    private var socket: HasSocket? = null
    private var seq = 0
    private var lastPong = 0L
    private val seen = LinkedHashSet<String>()

    /** Connections opened so far (tests). */
    var connects = 0
        private set

    /** True while a connection is up and authenticated or authenticating. */
    var connected = false
        private set(value) {
            if (field != value) {
                field = value
                onConnected(value)
            }
        }

    /** Connects to [url] and authenticates with [token]; an existing connection is closed first. Null [url] disconnects. */
    fun connect(url: String?, token: String) {
        disconnect()
        url ?: return
        job = scope.launch { run(url, token) }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        val s = socket
        socket = null
        connected = false
        seen.clear()
        if (s != null) scope.launch { withContext(NonCancellable) { runCatching { s.close() } } }
    }

    val isActive: Boolean get() = job?.isActive == true

    /** RN sendTyping: `user_typing` for [channelId] while connected; nothing otherwise. */
    fun sendTyping(channelId: String, parentId: String = "") {
        val s = socket ?: return
        if (!connected) return
        val message = buildJsonObject {
            put("action", "user_typing")
            put("seq", ++seq)
            putJsonObject("data") {
                put("channel_id", channelId)
                put("parent_id", parentId)
            }
        }
        scope.launch { runCatching { s.send(message.toString()) } }
    }

    /** How one connection ended. */
    private class Outcome {
        var hello = false
        var authFailed = false
        var authSeq = 0
    }

    private suspend fun run(url: String, token: String) {
        var attempts = 0
        var firstDisconnect: Long? = null
        while (true) {
            val opened = try {
                // RN: a connection that does not open in 15 s is given up, no reconnect
                withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) { sockets().open(url) } ?: return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // a refused handshake (bad token, endpoint gone) is final, as RN's close codes
                if (HasSocketRefused.statusOf(e) in NEVER_RECONNECT) return
                null
            }
            val outcome = Outcome()
            if (opened != null) {
                connects++
                socket = opened
                connected = true
                lastPong = nowMillis()
                try {
                    outcome.authSeq = seq + 1
                    opened.send(json("authentication_challenge") { put("token", token) })
                    session(opened, opened.incoming, outcome)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // broken: reconnect below
                } finally {
                    socket = null
                    connected = false
                    withContext(NonCancellable) { runCatching { opened.close() } }
                }
                // a refused token, or a close code RN never reconnects after, ends it for good
                if (outcome.authFailed || opened.closeCode in NEVER_RECONNECT) return
            }
            if (outcome.hello) {
                // a session that was really up: the next drop starts a fresh backoff and a fresh hour
                attempts = 0
                firstDisconnect = nowMillis()
            } else {
                // refused, or accepted and closed before the server's hello: one more failed attempt
                firstDisconnect = firstDisconnect ?: nowMillis()
            }
            if (nowMillis() - firstDisconnect > MAX_DISCONNECTED_MILLIS) return
            attempts++
            delay(minOf(RECONNECT_BASE_MILLIS shl (attempts - 1).coerceAtMost(15), MAX_RECONNECT_DELAY_MILLIS))
        }
    }

    /** Reads until the connection drops; pings once the server said hello. */
    private suspend fun session(s: HasSocket, incoming: ReceiveChannel<String>, outcome: Outcome) {
        var heartbeat: Job? = null
        var pongTimeout: Job? = null
        try {
            for (text in incoming) {
                val o = SafeJson.parseOrNull(text).obj() ?: continue
                val event = o["event"].text()
                if (event == "pong" || o["data"].obj()?.get("text").text() == "pong") {
                    lastPong = nowMillis()
                    pongTimeout?.cancel()
                    continue
                }
                // Mattermost answers the challenge with {"status": "FAIL", "seq_reply": n} when it refuses the token
                if (event == null && o["status"].text() == "FAIL" && (o["seq_reply"] as? JsonPrimitive)?.content == outcome.authSeq.toString()) {
                    outcome.authFailed = true
                    return
                }
                if (event == "hello") {
                    outcome.hello = true
                    heartbeat?.cancel()
                    heartbeat = scope.launch {
                        while (true) {
                            delay(PING_INTERVAL_MILLIS)
                            if (nowMillis() - lastPong > PING_INTERVAL_MILLIS + PONG_TIMEOUT_MILLIS) {
                                runCatching { s.close() }
                                return@launch
                            }
                            runCatching { s.send(json("ping") {}) }
                            pongTimeout?.cancel()
                            pongTimeout = scope.launch {
                                delay(PONG_TIMEOUT_MILLIS)
                                runCatching { s.close() }
                            }
                        }
                    }
                    continue
                }
                parse(o, event)?.let(onEvent)
            }
        } finally {
            heartbeat?.cancel()
            pongTimeout?.cancel()
        }
    }

    private fun parse(o: JsonObject, event: String?): ChatEvent? {
        val data = o["data"].obj() ?: return null
        val channelId = o["broadcast"].obj()?.get("channel_id").text().orEmpty()
        return when (event) {
            "posted", "post_edited" -> {
                val post = ChatPost.parse(data["post"].obj()) ?: return null
                val key = "$event-${post.id}"
                if (!seen.add(key)) return null
                if (seen.size > SEEN_LIMIT) seen.remove(seen.first())
                val channel = channelId.ifEmpty { post.channelId }
                if (event == "posted") ChatEvent.Posted(channel, post) else ChatEvent.Edited(channel, post)
            }
            "post_deleted" -> {
                val id = data["post_id"].text() ?: ChatPost.parse(data["post"].obj())?.id ?: return null
                ChatEvent.Deleted(channelId, id)
            }
            "reaction_added", "reaction_removed" -> {
                val r = ChatReaction.parse(data["reaction"].obj()) ?: return null
                if (event == "reaction_added") ChatEvent.ReactionAdded(channelId, r) else ChatEvent.ReactionRemoved(channelId, r)
            }
            "typing" -> data["user_id"].text()?.takeIf { it.isNotEmpty() }?.let { ChatEvent.Typing(channelId, it) }
            else -> null
        }
    }

    private fun json(action: String, data: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String = buildJsonObject {
        put("seq", ++seq)
        put("action", action)
        putJsonObject("data", data)
    }.toString()

    companion object {
        const val CONNECT_TIMEOUT_MILLIS = com.ecency.core.auth.KtorHasSocketFactory.CONNECT_TIMEOUT_MILLIS
        const val PING_INTERVAL_MILLIS = 30_000L
        const val PONG_TIMEOUT_MILLIS = 10_000L
        const val RECONNECT_BASE_MILLIS = 1_000L
        const val MAX_RECONNECT_DELAY_MILLIS = 30_000L
        const val MAX_DISCONNECTED_MILLIS = 60 * 60_000L
        private const val SEEN_LIMIT = 1000
        /** RN mattermostWebSocket.ts doNotReconnectCodes: handshake statuses and close codes that end it for good. */
        private val NEVER_RECONNECT = setOf(401, 403, 404, 426, 1002, 1003)
    }
}
