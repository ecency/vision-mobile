package com.ecency.core.api

import com.ecency.core.api.rpc.AllNodesFailedException
import com.ecency.core.api.rpc.HiveNodes
import com.ecency.core.api.rpc.HiveRpcClient
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.rpc.NodeFailureException
import com.ecency.core.api.rpc.RpcErrorException
import com.ecency.core.api.rpc.RpcTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HiveRpcClientTest {
    private val ok = """{"head_block_number":1}"""

    @Test
    fun requestBodyIsJsonRpc20WithParamsInOrder() = runTest {
        val transport = FakeTransport { FakeTransport.result(it, ok) }
        clientWith(transport).call("condenser_api.get_accounts", buildJsonArray { add(buildJsonArray { add(JsonPrimitive("alice")) }) })
        assertEquals(
            """{"jsonrpc":"2.0","method":"condenser_api.get_accounts","params":[["alice"]],"id":1}""",
            transport.sent.single().body,
        )
        assertEquals(NODES[0], transport.sent.single().node)
    }

    @Test
    fun failsOverOnHttp5xx429NetworkErrorAndGarbage() = runTest {
        val failures = ArrayDeque(
            listOf<suspend (Sent) -> HttpResult>(
                { HttpResult(503, "<html>down</html>") },
                { HttpResult(429, "slow down") },
                { throw IllegalStateException("connection reset") },
                { HttpResult(200, "<html>cloudflare</html>") },
            ),
        )
        val pool = listOf("https://n1.example", "https://n2.example", "https://n3.example", "https://n4.example", "https://n5.example")
        val transport = FakeTransport { req -> failures.removeFirstOrNull()?.invoke(req) ?: FakeTransport.result(req, ok) }
        val client = HiveRpcClient(pool, transport, readTimeoutMillis = 1_000, jitter = {})
        val result = client.call("condenser_api.get_dynamic_global_properties")
        assertEquals(ok, result.toString())
        assertEquals(pool, transport.sent.map { it.node })
        // the rate-limited node goes to the back; one failure of any other kind demotes nothing yet (SDK)
        client.call("condenser_api.get_dynamic_global_properties")
        assertEquals("https://n1.example", transport.sent.last().node)
        assertEquals(listOf("https://n1.example", "https://n3.example", "https://n4.example", "https://n5.example", "https://n2.example"), client.health.orderedNodes(pool, "condenser_api", probe = false))
    }

    @Test
    fun failsOverOnMismatchedIdAndNodeLevelRpcErrors() = runTest {
        var n = 0
        val transport = FakeTransport { req ->
            when (n++) {
                0 -> HttpResult(200, """{"jsonrpc":"2.0","result":{},"id":999}""")
                1 -> FakeTransport.error(req, -32603, "Internal Error")
                else -> FakeTransport.result(req, ok)
            }
        }
        clientWith(transport).call("bridge.get_post")
        assertEquals(NODES, transport.sent.map { it.node })
    }

    @Test
    fun applicationRpcErrorIsThrownWithoutFailover() = runTest {
        val transport = FakeTransport { FakeTransport.error(it, -32602, "Invalid parameters: limit") }
        val e = assertFailsWith<RpcErrorException> { clientWith(transport).call("bridge.get_ranked_posts") }
        assertEquals(-32602, e.code)
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun allNodesFailingReportsEveryAttemptAfterTwoRounds() = runTest {
        val transport = FakeTransport { HttpResult(502, "bad gateway") }
        val e = assertFailsWith<AllNodesFailedException> { clientWith(transport).call("bridge.get_post") }
        // SDK retry 5: six attempts, the second round over the pool after every node was tried once
        assertEquals(6, e.failures.size)
        assertTrue(e.failures.all { it is NodeFailureException })
        assertEquals(NODES + NODES, transport.sent.map { it.node })
    }

    @Test
    fun nodeLevelRpcErrorOnEveryNodeSurfacesTheRpcError() = runTest {
        val transport = FakeTransport { FakeTransport.error(it, -32003, "Assert Exception") }
        assertFailsWith<RpcErrorException> { clientWith(transport).call("bridge.get_post") }
        assertEquals(6, transport.sent.size)
    }

    @Test
    fun slowNodeTimesOutAndTheNextNodeAnswers() = runTest {
        val transport = FakeTransport { req ->
            if (req.node == NODES[0]) delay(60_000)
            FakeTransport.result(req, ok)
        }
        val client = clientWith(transport)
        client.call("condenser_api.get_dynamic_global_properties")
        assertEquals(listOf(NODES[0], NODES[1]), transport.sent.map { it.node })
        // Virtual time: one read timeout (1 s) was spent on the slow node, not its full 60 s.
        assertEquals(1_000, testScheduler.currentTime)
    }

    @Test
    fun timeoutsOnEveryNodeAreReported() = runTest {
        val transport = FakeTransport { awaitCancellation() }
        val e = assertFailsWith<AllNodesFailedException> { clientWith(transport).call("bridge.get_post") }
        assertTrue(e.failures.all { it is RpcTimeoutException })
        // with a frozen clock only the retry count ends it (the wall-clock budget has its own test)
        assertEquals(6_000, testScheduler.currentTime)
    }

    @Test
    fun cancellingTheCallerCancelsTheRequestWithoutFailover() = runTest {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val transport = FakeTransport {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val job = async { clientWith(transport).call("bridge.get_post") }
        started.await()
        job.cancelAndJoin()
        assertTrue(cancelled)
        assertTrue(job.isCancelled)
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun blockedAndDuplicateNodesAreDropped() {
        val sanitized = HiveNodes.sanitize(
            listOf("https://api.hive.blog/", "https://techcoderx.com", "https://API.hive.blog", " https://anyx.io "),
        )
        assertEquals(listOf("https://api.hive.blog", "https://anyx.io"), sanitized)
        assertTrue(HiveNodes.DEFAULT.none(HiveNodes::isBlocked))
        assertFailsWith<com.ecency.core.api.InvalidInputException> { HiveRpcClient(listOf("https://hiveapi.actifit.io"), FakeTransport { error("unused") }) }
    }

    @Test
    fun broadcastNeverFailsOverAfterAChainRejection() = runTest {
        val transport = FakeTransport { FakeTransport.error(it, -32003, "missing required posting authority") }
        assertFailsWith<RpcErrorException> {
            clientWith(transport).broadcast("condenser_api.broadcast_transaction", buildJsonArray {}, Long.MAX_VALUE)
        }
        assertEquals(1, transport.sent.size)
    }

    @Test
    fun broadcastFailsOverOnNodeErrorsButTriesEachNodeOnce() = runTest {
        val transport = FakeTransport { HttpResult(503, "") }
        assertFailsWith<NodeFailureException> {
            clientWith(transport).broadcast("condenser_api.broadcast_transaction", buildJsonArray {}, Long.MAX_VALUE)
        }
        assertEquals(NODES, transport.sent.map { it.node })
    }

    @Test
    fun broadcastTimeoutFailsOverOnlyInsideTheWindow() = runTest {
        val slow = FakeTransport { awaitCancellation() }
        // Window already closed: the first timeout is final.
        val closed = clientWith(slow, now = { 10_000 })
        assertIs<RpcTimeoutException>(
            runCatching { closed.broadcast("condenser_api.broadcast_transaction", buildJsonArray {}, 5_000) }.exceptionOrNull(),
        )
        assertEquals(1, slow.sent.size)

        // Window open: the next node is tried.
        val second = FakeTransport { req -> if (req.node == NODES[0]) awaitCancellation() else FakeTransport.result(req, "{}") }
        clientWith(second, now = { 0 }).broadcast("condenser_api.broadcast_transaction", buildJsonArray {}, 5_000)
        assertEquals(listOf(NODES[0], NODES[1]), second.sent.map { it.node })
    }

    @Test
    fun anOuterTimeoutIsCancellationNotANodeFailure() = runTest {
        for (isBroadcast in listOf(false, true)) {
            val transport = FakeTransport { awaitCancellation() }
            val client = HiveRpcClient(NODES, transport, readTimeoutMillis = 10_000, broadcastTimeoutMillis = 10_000, nowMillis = { 0 })
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(300) {
                    if (isBroadcast) client.broadcast("condenser_api.broadcast_transaction", buildJsonArray {}, Long.MAX_VALUE)
                    else client.call("bridge.get_post")
                }
            }
            assertEquals(1, transport.sent.size, "broadcast=$isBroadcast: no further nodes after the caller gave up")
        }
    }

    @Test
    fun broadcastConnectionBrokenMidRequestRespectsTheDeadline() = runTest {
        var now = 0L
        val transport = FakeTransport { req ->
            now += 50_000 // the request took long; the failover window (10 s) has passed
            if (req.node == NODES[0]) throw IllegalStateException("unexpected end of stream")
            FakeTransport.error(req, -32003, "transaction expired")
        }
        val client = clientWith(transport, now = { now })
        val e = assertFailsWith<NodeFailureException> {
            client.broadcast("condenser_api.broadcast_transaction", buildJsonArray {}, failoverUntilMillis = 10_000)
        }
        assertTrue(e.mayHaveReachedNode)
        assertEquals(listOf(NODES[0]), transport.sent.map { it.node }, "the next node would only say 'expired'")
    }

    @Test
    fun broadcastPreConnectionFailuresFailOverEvenPastTheDeadline() = runTest {
        val transport = FakeTransport { req ->
            if (req.node == NODES[0]) throw IllegalStateException("Failed to connect to node-a.example/10.0.0.1:443 (Connection refused)")
            FakeTransport.result(req, "{}")
        }
        clientWith(transport, now = { 1_000_000 }).broadcast("condenser_api.broadcast_transaction", buildJsonArray {}, failoverUntilMillis = 0)
        assertEquals(listOf(NODES[0], NODES[1]), transport.sent.map { it.node })
    }

    @Test
    fun deeplyNestedBodiesAreNodeFailuresNotStackOverflows() = runTest {
        val transport = FakeTransport { HttpResult(200, "[".repeat(100_000) + "]".repeat(100_000)) }
        val e = assertFailsWith<AllNodesFailedException> { clientWith(transport).call("bridge.get_post") }
        assertTrue(e.failures.all { it is NodeFailureException })
    }

    @Test
    fun concurrentCallsGetDistinctRequestIds() = runTest {
        val transport = FakeTransport { FakeTransport.result(it, "1") }
        val client = clientWith(transport)
        coroutineScope { repeat(200) { launch(Dispatchers.Default) { client.call("condenser_api.get_config") } } }
        assertEquals(200, transport.sent.map { it.id }.toSet().size)
    }
}
