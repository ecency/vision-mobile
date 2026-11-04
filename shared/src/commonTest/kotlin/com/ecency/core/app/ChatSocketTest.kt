package com.ecency.core.app

import com.ecency.core.auth.HasSocket
import com.ecency.core.auth.HasSocketFactory
import com.ecency.core.auth.HasSocketRefused
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A chat websocket double: records what was sent, lets the test push frames and drop the connection. */
private class FakeChatSockets(private val test: TestScope) : HasSocketFactory {
    val opened = mutableListOf<String>()
    val sent = mutableListOf<String>()
    var refuse: Exception? = null
    var hang = false
    var answerPings = true
    /** Answers the authentication challenge with `hello` (a server that is really up). */
    var hello = true
    /** Answers the challenge with this frame instead (`%d` is the challenge's seq). */
    var authReply: String? = null
    /** Closes the connection right after the challenge (a server that accepts and then closes). */
    var closeOnAuth = false
    /** The close code the next connection reports. */
    var closeCode: Int? = null
    private var current: Channel<String>? = null

    fun push(text: String) {
        current?.trySend(text)
    }

    fun drop() {
        current?.close()
    }

    override suspend fun open(host: String): HasSocket {
        opened += host
        refuse?.let { throw it }
        if (hang) awaitCancellation()
        val ch = Channel<String>(Channel.UNLIMITED)
        current = ch
        return object : HasSocket {
            override suspend fun send(text: String) {
                sent += text
                val action = Json.parseToJsonElement(text).jsonObject["action"]?.jsonPrimitive?.content
                if (action == "authentication_challenge") {
                    val seq = Json.parseToJsonElement(text).jsonObject["seq"]!!.jsonPrimitive.content
                    authReply?.let { ch.trySend(it.replace("%d", seq)) }
                        ?: if (hello) ch.trySend("""{"event":"hello","data":{},"broadcast":{}}""") else Unit
                    if (closeOnAuth) ch.close()
                }
                if (action == "ping" && answerPings) ch.trySend("""{"status":"OK","data":{"text":"pong"}}""")
            }
            override val incoming: ReceiveChannel<String> = ch
            override suspend fun close() {
                ch.close()
            }
            override val closeCode: Int? = this@FakeChatSockets.closeCode
        }
    }

    fun actions() = sent.map { Json.parseToJsonElement(it).jsonObject["action"]!!.jsonPrimitive.content }
}

class ChatSocketTest {
    private class Rig(test: TestScope) {
        val scope = CoroutineScope(StandardTestDispatcher(test.testScheduler))
        val sockets = FakeChatSockets(test)
        val events = mutableListOf<ChatEvent>()
        var up = false
        val socket = ChatSocket(scope, { sockets }, { test.testScheduler.currentTime }, { events += it }) { up = it }
        val scheduler = test.testScheduler

        fun run(millis: Long = 0) {
            scheduler.advanceTimeBy(millis)
            scheduler.runCurrent()
        }

        fun close() = scope.cancel()
    }

    private fun posted(id: String, channel: String = "c1") =
        """{"event":"posted","data":{"post":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive("""{"id":"$id","channel_id":"$channel","user_id":"u2","message":"hi","create_at":5}"""))}},"broadcast":{"channel_id":"$channel"},"seq":1}"""

    @Test
    fun authenticatesThenPingsAndDeliversEachPostOnce() = runTest {
        val r = Rig(this)
        try {
            r.socket.connect("wss://ecency.com/api/mattermost/websocket?token=t", "t")
            r.run()
            assertTrue(r.up)
            val auth = Json.parseToJsonElement(r.sockets.sent.first()).jsonObject
            assertEquals("authentication_challenge", auth["action"]!!.jsonPrimitive.content)
            assertEquals("t", auth["data"]!!.jsonObject["token"]!!.jsonPrimitive.content)
            r.sockets.push(posted("p1"))
            r.sockets.push(posted("p1"))
            r.sockets.push("""{"event":"post_deleted","data":{"post_id":"p9"},"broadcast":{"channel_id":"c1"}}""")
            r.sockets.push("""{"event":"typing","data":{"user_id":"u3"},"broadcast":{"channel_id":"c1"}}""")
            r.sockets.push("not json")
            r.run()
            assertEquals(listOf("Posted", "Deleted", "Typing"), r.events.map { it::class.simpleName })
            r.run(ChatSocket.PING_INTERVAL_MILLIS)
            assertEquals(listOf("authentication_challenge", "ping"), r.sockets.actions())
            r.socket.sendTyping("c1")
            r.run()
            assertEquals("user_typing", r.sockets.actions().last())
        } finally {
            r.close()
        }
    }

    @Test
    fun aMissingPongDropsTheConnectionAndItComesBackWithBackoff() = runTest {
        val r = Rig(this)
        try {
            r.socket.connect("wss://ecency.com/api/mattermost/websocket?token=t", "t")
            r.run()
            r.sockets.answerPings = false
            r.run(ChatSocket.PING_INTERVAL_MILLIS)
            r.run(ChatSocket.PONG_TIMEOUT_MILLIS)
            assertFalse(r.up, "no pong within 10 s")
            assertEquals(1, r.sockets.opened.size)
            r.run(ChatSocket.RECONNECT_BASE_MILLIS)
            assertEquals(2, r.sockets.opened.size, "back after 1 s")
            assertTrue(r.up)
            r.sockets.drop()
            r.run()
            r.run(ChatSocket.RECONNECT_BASE_MILLIS)
            assertEquals(3, r.sockets.opened.size, "a connection that worked starts the backoff over")
        } finally {
            r.close()
        }
    }

    @Test
    fun aRefusedTokenAndAConnectTimeoutEndIt() = runTest {
        val r = Rig(this)
        try {
            r.sockets.refuse = HasSocketRefused(401)
            r.socket.connect("wss://ecency.com/api/mattermost/websocket?token=t", "t")
            r.run(ChatSocket.MAX_RECONNECT_DELAY_MILLIS * 4)
            assertEquals(1, r.sockets.opened.size)
            assertFalse(r.socket.isActive)

            r.sockets.refuse = IllegalStateException("connection reset")
            r.socket.connect("wss://ecency.com/api/mattermost/websocket?token=t", "t")
            r.run()
            r.run(1_000)
            r.run(2_000)
            assertEquals(4, r.sockets.opened.size, "network failures back off 1 s, 2 s")

            r.sockets.refuse = null
            r.sockets.hang = true
            r.socket.connect("wss://ecency.com/api/mattermost/websocket?token=t", "t")
            r.run(ChatSocket.CONNECT_TIMEOUT_MILLIS + 1)
            assertFalse(r.socket.isActive, "15 s without a connection ends it")
            r.socket.disconnect()
        } finally {
            r.close()
        }
    }

    @Test
    fun aServerThatAcceptsThenClosesBacksOffAndGivesUpAfterAnHour() = runTest {
        val r = Rig(this)
        try {
            r.sockets.hello = false
            r.sockets.closeOnAuth = true
            r.socket.connect("wss://ecency.com/api/mattermost/websocket?token=t", "t")
            r.run()
            r.run(60_000)
            // opens at 0, 1, 3, 7, 15 and 31 s: an open without hello is a failed attempt, the backoff grows
            assertEquals(6, r.sockets.opened.size, "opens in the first minute")
            r.run(ChatSocket.MAX_DISCONNECTED_MILLIS)
            assertFalse(r.socket.isActive, "an hour without a hello ends it")
            val opens = r.sockets.opened.size
            assertTrue(opens < 130, "at most one open per 30 s: $opens")
            r.run(ChatSocket.MAX_DISCONNECTED_MILLIS)
            assertEquals(opens, r.sockets.opened.size)
        } finally {
            r.close()
        }
    }

    @Test
    fun anAuthFailAndANeverReconnectCloseCodeEndIt() = runTest {
        val r = Rig(this)
        try {
            r.sockets.authReply = """{"status":"FAIL","seq_reply":%d,"error":{"id":"api.web_socket_router.not_authenticated.app_error"}}"""
            r.socket.connect("wss://ecency.com/api/mattermost/websocket?token=t", "t")
            r.run()
            r.run(ChatSocket.MAX_RECONNECT_DELAY_MILLIS * 4)
            assertEquals(1, r.sockets.opened.size, "a refused token is not retried")
            assertFalse(r.socket.isActive)

            // a FAIL for another request (not the challenge) is not a refusal
            r.sockets.authReply = null
            r.socket.connect("wss://ecency.com/api/mattermost/websocket?token=t", "t")
            r.run()
            r.sockets.push("""{"status":"FAIL","seq_reply":999}""")
            r.run()
            assertTrue(r.up)

            for (code in listOf(1003, 401)) {
                r.sockets.closeCode = code
                r.socket.connect("wss://ecency.com/api/mattermost/websocket?token=t", "t")
                r.run()
                val opens = r.sockets.opened.size
                r.sockets.drop()
                r.run()
                r.run(ChatSocket.MAX_RECONNECT_DELAY_MILLIS * 4)
                assertEquals(opens, r.sockets.opened.size, "close code $code is final (RN doNotReconnectCodes)")
                assertFalse(r.socket.isActive)
            }

            r.sockets.closeCode = 1000
            r.socket.connect("wss://ecency.com/api/mattermost/websocket?token=t", "t")
            r.run()
            val opens = r.sockets.opened.size
            r.sockets.drop()
            r.run()
            r.run(ChatSocket.RECONNECT_BASE_MILLIS)
            assertEquals(opens + 1, r.sockets.opened.size, "a server restart (1000) reconnects")
            r.socket.disconnect()
        } finally {
            r.close()
        }
    }

    @Test
    fun aHandshakeStatusIsReadFromTheEnginesOwnWordingOnly() {
        assertEquals(401, HasSocketRefused.statusOf(IllegalStateException("Handshake exception, expected status code 101 but was 401")))
        assertEquals(403, HasSocketRefused.statusOf(IllegalStateException("Expected HTTP 101 response but was '403 Forbidden'")))
        assertEquals(426, HasSocketRefused.statusOf(RuntimeException("wrapped", IllegalStateException("Expected HTTP 101 response but was '426 Upgrade Required'"))))
        assertEquals(404, HasSocketRefused.statusOf(HasSocketRefused(404)))
        // a status-like number elsewhere in a message (here the token in the url) is not a refusal
        assertEquals(null, HasSocketRefused.statusOf(IllegalStateException("Connect timeout has expired [url=wss://ecency.com/api/mattermost/websocket?token=abc-404-x, connect_timeout=unknown ms]")))
        assertEquals(null, HasSocketRefused.statusOf(IllegalStateException("Failed to connect to ecency.com/192.0.2.10:443")))
        // Darwin reports other statuses as NSURLErrorBadServerResponse without the code: retried, not final
        assertEquals(null, HasSocketRefused.statusOf(IllegalStateException("There was a bad response from the server.")))
    }

    @Test
    fun chatThreadsAreSavedByChannelOnly() {
        val screen = AppScreen.ChatThread("ch-1", "D")
        val line = NavState.encodeScreen(screen)
        assertEquals(screen, NavState.decodeScreen(line))
        assertTrue(RouteName.CHAT_THREAD.built && RouteName.CHAT_THREAD.requiresLogin)
        assertEquals(AppScreen.ChatThread("ch-9"), AppScreen.of(RouteName.CHAT_THREAD, "ch-9"))
        assertEquals(null, NavState.decodeScreen(NavState.encodeScreen(AppScreen.ChatThread(""))))
    }
}
