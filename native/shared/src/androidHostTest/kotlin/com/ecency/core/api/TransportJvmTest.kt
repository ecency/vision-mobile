package com.ecency.core.api

import com.ecency.core.api.rpc.AllNodesFailedException
import com.ecency.core.api.rpc.HiveRpcClient
import com.ecency.core.api.rpc.KtorTransport
import com.ecency.core.api.rpc.NodeFailureException
import com.ecency.core.api.rpc.RpcTimeoutException
import com.ecency.core.api.rpc.platformHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The real Ktor/OkHttp transport against local sockets (no external network). */
class TransportJvmTest {
    /** Accepts connections and answers each with [respond] (or never, when it returns null). */
    private fun localServer(respond: () -> ByteArray?): String {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            while (true) {
                val socket = server.accept()
                thread(isDaemon = true) {
                    socket.getInputStream().read(ByteArray(64 * 1024))
                    val reply = respond()
                    if (reply == null) Thread.sleep(120_000) else socket.getOutputStream().apply { write(reply); flush() }
                    socket.close()
                }
            }
        }
        return "http://127.0.0.1:${server.localPort}"
    }

    @Test
    fun silentNodeHitsTheClientsBroadcastTimeoutNotOkHttpsDefault() = runBlocking {
        val url = localServer { null }
        // 11 s is past OkHttp's 10 s default read timeout: the client's own timeout must be the one that fires.
        val client = HiveRpcClient(listOf(url), KtorTransport(), readTimeoutMillis = 11_000, broadcastTimeoutMillis = 11_000)
        val started = System.currentTimeMillis()
        val e = runCatching { client.broadcast("condenser_api.broadcast_transaction", JsonArray(emptyList()), Long.MAX_VALUE) }.exceptionOrNull()
        val elapsed = System.currentTimeMillis() - started
        assertIs<RpcTimeoutException>(e, "got $e after ${elapsed}ms")
        assertTrue(elapsed >= 10_900, "fired after ${elapsed}ms")
    }

    @Test
    fun oversizedResponsesAreNodeFailures() = runBlocking {
        val body = "x".repeat(4096)
        val url = localServer { "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray() }
        val client = HiveRpcClient(listOf(url), KtorTransport(platformHttpClient(), maxResponseBytes = 1024))
        val e = runCatching { client.call("condenser_api.get_config") }.exceptionOrNull()
        assertIs<AllNodesFailedException>(e)
        // a pool of one node is asked again, up to the retry budget; every attempt failed the same way
        assertTrue(e.failures.isNotEmpty() && e.failures.all { it.message!!.contains("exceeds 1024 bytes") }, e.failures.map { it.message }.toString())
    }

    @Test
    fun jvmNetworkErrorsAreClassified() {
        fun classify(e: Exception) = HiveRpcClient.classifyTransportError("n", "m", 1_000, e)
        assertIs<RpcTimeoutException>(classify(SocketTimeoutException("timeout")))
        assertFalse((classify(ConnectException("Connection refused")) as NodeFailureException).mayHaveReachedNode)
        assertFalse((classify(UnknownHostException("node.example")) as NodeFailureException).mayHaveReachedNode)
        assertFalse((classify(SocketTimeoutException("connect timed out")) as NodeFailureException).mayHaveReachedNode)
        assertTrue((classify(java.io.IOException("unexpected end of stream")) as NodeFailureException).mayHaveReachedNode)
        // Wrapped causes are looked through.
        assertFalse((classify(IllegalStateException("wrapper", ConnectException("refused"))) as NodeFailureException).mayHaveReachedNode)
        assertEquals("n", (classify(RuntimeException("x")) as NodeFailureException).node)
    }
}
