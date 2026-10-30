package com.ecency.core.api

import com.ecency.core.api.rpc.AllNodesFailedException
import com.ecency.core.api.rpc.HiveNodes
import com.ecency.core.api.rpc.HiveRpcClient
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.rpc.NodeFailureException
import com.ecency.core.api.rpc.NodeHealthTracker
import com.ecency.core.api.rpc.RpcErrorException
import com.ecency.core.api.rpc.RpcTimeoutException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Node selection and failover as @ecency/sdk 2.4.26 does it for the RN app (hive-tx callRPC / callRPCBroadcast,
 * NodeHealthTracker), over fake transports and the test scheduler's clock.
 */
class NodeFailoverTest {
    private val a = NODES[0]
    private val b = NODES[1]
    private val c = NODES[2]
    private val props = """{"head_block_number":100}"""

    /** A client whose clock is the test scheduler's, so timeouts and cooldowns run on virtual time. */
    private fun TestScope.client(transport: FakeTransport, readTimeout: Long = 1_000, jitter: suspend () -> Unit = {}) =
        HiveRpcClient(NODES, transport, readTimeoutMillis = readTimeout, broadcastTimeoutMillis = 2_000, nowMillis = { testScheduler.currentTime }, jitter = jitter)

    private suspend fun HiveRpcClient.config() = call("condenser_api.get_config")

    // ---- reads: retries, rounds, budget ----

    @Test
    fun aPoolOfOneNodeIsAskedAgain() = runTest {
        var n = 0
        val transport = FakeTransport { req -> if (n++ < 2) HttpResult(503, "") else FakeTransport.result(req, "1") }
        val client = HiveRpcClient(listOf(a), transport, readTimeoutMillis = 1_000, nowMillis = { testScheduler.currentTime }, jitter = {})
        assertEquals(JsonPrimitive(1), client.config())
        assertEquals(listOf(a, a, a), transport.sent.map { it.node })
    }

    @Test
    fun noNewAttemptStartsAfterTwiceTheTimeout() = runTest {
        val transport = FakeTransport { awaitCancellation() }
        val e = assertFailsWith<AllNodesFailedException> { client(transport).config() }
        // 1 s per attempt, 2 s in all: the third attempt would start at the deadline
        assertEquals(2, e.failures.size)
        assertEquals(2_000, testScheduler.currentTime)
        assertEquals(listOf(a, b), transport.sent.map { it.node })
    }

    @Test
    fun attemptsArePausedByTheJitter() = runTest {
        val pauses = mutableListOf<Long>()
        val transport = FakeTransport { req -> if (req.node == a) HttpResult(502, "") else FakeTransport.result(req, "1") }
        client(transport, jitter = { pauses += testScheduler.currentTime; delay(75) }).config()
        assertEquals(listOf(0L), pauses, "one pause, between the failed attempt and the next")
        assertEquals(75, testScheduler.currentTime)
        // the default pause is the SDK's 50 to 100 ms
        val defaultClient = HiveRpcClient(NODES, transport, readTimeoutMillis = 1_000)
        val before = testScheduler.currentTime
        defaultClient.config()
        assertTrue(testScheduler.currentTime - before in 50..100, "paused ${testScheduler.currentTime - before} ms")
    }

    @Test
    fun anAnswerOfTheChainIsThrownAtOnceAndNeverRetried() = runTest {
        for ((code, message) in listOf(-32602 to "Invalid parameters", 10 to "Assert Exception: account does not exist", -32700 to "Parse error")) {
            val transport = FakeTransport { FakeTransport.error(it, code, message) }
            val e = assertFailsWith<RpcErrorException> { client(transport).config() }
            assertEquals(code, e.code)
            assertEquals(1, transport.sent.size, "$code is an answer, not a sick node")
        }
    }

    @Test
    fun nodeLevelRpcErrorsFailOver() = runTest {
        for ((code, message) in listOf(-32603 to "Internal Error", -32601 to "Method not found", -32000 to "Server error", -32602 to "Unable to parse endpoint data")) {
            val transport = FakeTransport { req -> if (req.node == a) FakeTransport.error(req, code, message) else FakeTransport.result(req, "1") }
            client(transport).config()
            assertEquals(listOf(a, b), transport.sent.map { it.node }, "$code $message")
        }
    }

    @Test
    fun malformedRepliesFailOver() = runTest {
        val bad = listOf(
            "", "not json", "{}", """{"jsonrpc":"2.0","id":1}""", """{"jsonrpc":"1.0","result":1,"id":1}""",
            """{"jsonrpc":"2.0","result":1,"id":"other"}""", """{"jsonrpc":"2.0","result":1,"id":"1"}""",
            """{"jsonrpc":2.0,"result":1,"id":1}""", "[1,2]", "{\"jsonrpc\":\"2.0\",\"result\":",
        )
        for (body in bad) {
            val transport = FakeTransport { req -> if (req.node == a) HttpResult(200, body) else FakeTransport.result(req, "1") }
            client(transport).config()
            assertEquals(listOf(a, b), transport.sent.map { it.node }, body)
        }
        // a proxy's own 4xx page is the node's failure; a JSON-RPC reply with a 4xx status is still an answer
        val proxy = FakeTransport { req -> if (req.node == a) HttpResult(403, "<html>forbidden</html>") else FakeTransport.result(req, "1") }
        client(proxy).config()
        assertEquals(listOf(a, b), proxy.sent.map { it.node })
        val rpc4xx = FakeTransport { req -> FakeTransport.error(req, -32602, "Invalid parameters").copy(status = 400) }
        assertFailsWith<RpcErrorException> { client(rpc4xx).config() }
        assertEquals(1, rpc4xx.sent.size)
    }

    @Test
    fun aCancellationTheTransportRaisesItselfFailsOver() = runTest {
        val transport = FakeTransport { req ->
            if (req.node == a) throw kotlinx.coroutines.CancellationException("engine shut the call") else FakeTransport.result(req, "1")
        }
        assertEquals(JsonPrimitive(1), client(transport).config())
        assertEquals(listOf(a, b), transport.sent.map { it.node })
        val e = runCatching {
            client(FakeTransport { req -> if (req.node == a) throw kotlinx.coroutines.CancellationException("x") else HttpResult(503, "") })
                .broadcast("condenser_api.broadcast_transaction", buildJsonArray {}, Long.MAX_VALUE)
        }.exceptionOrNull()
        assertTrue(e is NodeFailureException && e.mayHaveReachedNode, "a broadcast reads it as maybe sent: $e")
    }

    // ---- health: who is asked first ----

    @Test
    fun threeFailuresInARowSendANodeToTheBackFor30Seconds() = runTest {
        val transport = FakeTransport { req -> if (req.node == a) throw IllegalStateException("connection reset") else FakeTransport.result(req, "1") }
        val client = client(transport)
        repeat(3) { client.config() }
        assertEquals(listOf(a, b, a, b, a, b), transport.sent.map { it.node }, "two failures demote nothing")
        client.config()
        assertEquals(b, transport.sent.last().node, "the third did")
        assertEquals(listOf(b, c, a), client.health.orderedNodes(NODES, probe = false))
        testScheduler.advanceTimeBy(30_001)
        assertTrue(client.health.isNodeHealthy(a), "healthy again once the window passed")
        // and back among the healthy ones, behind the node measured fast meanwhile (the SDK ranks by latency)
        assertEquals(listOf(b, a, c), client.health.orderedNodes(NODES, probe = false))
    }

    @Test
    fun anApiFailingTwiceCoolsDownOnThatNodeOnly() = runTest {
        val transport = FakeTransport { req ->
            if (req.node == a && req.method.startsWith("bridge.")) HttpResult(503, "hivemind down") else FakeTransport.result(req, "1")
        }
        val client = client(transport)
        client.call("bridge.get_post")
        client.call("bridge.get_post")
        assertEquals(listOf(a, b, a, b), transport.sent.map { it.node })
        client.call("bridge.get_discussion")
        assertEquals(b, transport.sent.last().node, "bridge cools down on node a for 60 s")
        // condenser_api on node a is fine: a is still ahead of the unmeasured c (b answered fast, so it leads)
        assertEquals(listOf(b, c, a), client.health.orderedNodes(NODES, "bridge", probe = false))
        assertEquals(listOf(b, a, c), client.health.orderedNodes(NODES, "condenser_api", probe = false))
        testScheduler.advanceTimeBy(60_001)
        assertTrue(client.health.isNodeHealthy(a, "bridge"))
    }

    @Test
    fun aRateLimitHonoursRetryAfterOrCoolsTenSeconds() = runTest {
        var limited = true
        var retryAfter: String? = "30"
        val transport = FakeTransport { req ->
            if (req.node == a && limited) HttpResult(429, "slow down", retryAfter?.let { mapOf("retry-after" to it) } ?: emptyMap())
            else FakeTransport.result(req, "1")
        }
        val client = client(transport)
        client.config()
        assertEquals(listOf(a, b), transport.sent.map { it.node })
        testScheduler.advanceTimeBy(29_000)
        assertEquals(b, client.currentNode, "Retry-After: 30 s")
        testScheduler.advanceTimeBy(1_001)
        assertTrue(client.health.isNodeHealthy(a))
        // no header: 10 s
        retryAfter = null
        client.setNodes(listOf(a, c))
        client.config()
        assertEquals(c, transport.sent.last().node)
        testScheduler.advanceTimeBy(9_000)
        assertEquals(c, client.currentNode)
        testScheduler.advanceTimeBy(1_001)
        assertEquals(a, client.currentNode)
        limited = false
        client.config()
        assertEquals(a, transport.sent.last().node)
    }

    @Test
    fun aNodeLaggingThirtyBlocksBehindGoesToTheBack() = runTest {
        val heads = mapOf(a to 1_000, b to 1_100, c to 1_100)
        val transport = FakeTransport { req -> FakeTransport.result(req, """{"head_block_number":${heads.getValue(req.node)}}""") }
        val client = client(transport)
        // every node answers once (the pool rotated in front of it); what each said stays with the node
        for (first in listOf(b, c, a)) {
            client.setNodes(listOf(first) + NODES.filter { it != first })
            client.call("condenser_api.get_dynamic_global_properties")
        }
        client.setNodes(NODES)
        assertEquals(listOf(b, c, a), client.health.orderedNodes(NODES, probe = false), "node a is 100 blocks behind the median")
        testScheduler.advanceTimeBy(120_001)
        assertEquals(NODES, client.health.orderedNodes(NODES, probe = false), "observations older than 2 minutes count for nothing")
    }

    @Test
    fun measuredLatencyReordersThePoolAndShortensTheTimeout() = runTest {
        var slowA = 1_500L
        val starts = mutableListOf<Long>()
        val transport = FakeTransport { req ->
            starts += testScheduler.currentTime
            if (req.node == a) delay(slowA) else delay(100)
            FakeTransport.result(req, "1")
        }
        val client = client(transport, readTimeout = 10_000)
        repeat(3) { client.config() }
        assertEquals(listOf(a, a, a), transport.sent.map { it.node }, "unproven nodes keep the pool order")
        // three samples at 1.5 s: node a ranks behind the unproven ones (1 s prior)
        client.config()
        assertEquals(b, transport.sent.last().node)
        // and its own deadline for this method is max(2 s, 4 x 1.5 s) = 6 s, not 10 s
        slowA = 60_000
        client.setNodes(listOf(a))
        starts.clear()
        assertFailsWith<AllNodesFailedException> { client.config() }
        assertEquals(6_000, starts[1] - starts[0], "first attempt bounded at 6 s")
        // the slow failure fed the profile (0.3 x 6 s + 0.7 x 1.5 s = 2.85 s): the next bound is the 10 s ceiling
        assertEquals(10_000, starts[2] - starts[1])
        // another method on the same node was never measured: the full ceiling
        starts.clear()
        assertFailsWith<AllNodesFailedException> { client.call("condenser_api.get_dynamic_global_properties") }
        assertEquals(10_000, starts[1] - starts[0])
    }

    @Test
    fun anIdleNodeIsProbedAgainAfterAMinute() = runTest {
        val transport = FakeTransport { req ->
            delay(if (req.node == a) 1_500 else 100)
            FakeTransport.result(req, "1")
        }
        val client = client(transport, readTimeout = 10_000)
        repeat(3) { client.config() } // a measured slow
        client.config() // b now first
        assertEquals(b, transport.sent.last().node)
        testScheduler.advanceTimeBy(60_001)
        client.config()
        // the stalest healthy node not sampled for a minute (c, never asked) is tried first once
        assertEquals(c, transport.sent.last().node)
    }

    @Test
    fun anAnswerTheValidatorRejectsFailsOverAndCoolsTheApi() = runTest {
        val transport = FakeTransport { req -> FakeTransport.result(req, if (req.node == a) "null" else "[]") }
        val client = client(transport)
        val names = buildJsonArray { add(buildJsonArray { add(JsonPrimitive("alice")) }) }
        assertEquals(JsonArray(emptyList()), client.call("condenser_api.get_accounts", names, validate = HiveRpcClient.IS_ARRAY))
        assertEquals(listOf(a, b), transport.sent.map { it.node })
        // node a's condenser_api is cooling down: later calls start elsewhere, even after a success there
        client.call("condenser_api.get_accounts", names)
        assertEquals(b, transport.sent.last().node)
        // every node lying: the walk ends with the validation failures
        val liar = FakeTransport { req -> FakeTransport.result(req, "null") }
        val e = assertFailsWith<AllNodesFailedException> { client(liar).call("condenser_api.get_accounts", names, validate = HiveRpcClient.IS_ARRAY) }
        assertTrue(e.failures.all { it is NodeFailureException && "validation" in it.reason })
    }

    @Test
    fun anExplicitTimeoutIsExact() = runTest {
        val transport = FakeTransport { awaitCancellation() }
        val client = client(transport, readTimeout = 10_000)
        assertFailsWith<AllNodesFailedException> { client.call("condenser_api.get_block_header", JsonArray(emptyList()), timeoutMillis = 3_000) }
        // two attempts of 3 s, the budget being 2 x 3 s
        assertEquals(6_000, testScheduler.currentTime)
    }

    // ---- broadcasts ----

    @Test
    fun broadcastsUseTheHealthOrderAndNeverAskANodeTwice() = runTest {
        var ok = 0
        val transport = FakeTransport { req ->
            when {
                req.method != "condenser_api.broadcast_transaction" -> FakeTransport.result(req, "1")
                req.node == a -> HttpResult(429, "")
                else -> FakeTransport.result(req, "{}").also { ok++ }
            }
        }
        val client = client(transport)
        client.broadcast("condenser_api.broadcast_transaction", buildJsonArray {}, Long.MAX_VALUE)
        client.broadcast("condenser_api.broadcast_transaction", buildJsonArray {}, Long.MAX_VALUE)
        assertEquals(listOf(a, b, b), transport.sent.map { it.node }, "the rate-limited node is not asked again while it cools down")
        assertEquals(2, ok)

        val failing = FakeTransport { HttpResult(503, "") }
        assertFailsWith<NodeFailureException> { client(failing).broadcast("condenser_api.broadcast_transaction", buildJsonArray {}, Long.MAX_VALUE) }
        assertEquals(NODES, failing.sent.map { it.node }, "each node once, no second round for a broadcast")
    }

    @Test
    fun aBroadcastTimeoutIsWhatIsReportedAfterLaterNodesFail() = runTest {
        val transport = FakeTransport { req -> if (req.node == a) awaitCancellation() else HttpResult(503, "") }
        val e = runCatching { client(transport).broadcast("condenser_api.broadcast_transaction", buildJsonArray {}, Long.MAX_VALUE) }.exceptionOrNull()
        // the transaction may be in through node a: the caller must look before signing anew
        assertIs<RpcTimeoutException>(e)
        assertEquals(NODES, transport.sent.map { it.node })
    }

    // ---- pool sources ----

    @Test
    fun remoteListParsing() {
        assertEquals(listOf("https://a.example", "https://b.example"), HiveNodes.parseRemote("""{"hived":["https://a.example/","https://b.example","https://techcoderx.com"]}"""))
        assertEquals(listOf("https://a.example"), HiveNodes.parseRemote("""["https://a.example"]"""))
        // https only, no user info; a port is fine
        assertEquals(listOf("https://c.example:8443"), HiveNodes.parseRemote("""["http://a.example","https://user:pw@b.example","https://evil.example@good.example","https://c.example:8443"]"""))
        assertTrue(HiveNodes.isSafeRemote("https://api.hive.blog/rpc?x=1"))
        for (u in listOf("HTTP://a.example", "https://", "https://:443", "https://a b.example", "https://u@a.example/")) assertTrue(!HiveNodes.isSafeRemote(u), u)
        for (bad in listOf("", "{}", """{"hived":[]}""", """{"hived":"x"}""", "[1,2]", """["ftp://a.example"]""", """["https://hiveapi.actifit.io"]""", "<html>")) {
            assertNull(HiveNodes.parseRemote(bad), bad)
        }
    }

    @Test
    fun savedServerGoesFirstUnlessAlreadyListedOrBlocked() {
        val fetched = listOf("https://a.example", "https://b.example")
        assertEquals(listOf("https://mine.example") + fetched, HiveNodes.pool("https://mine.example/", fetched))
        assertEquals(fetched, HiveNodes.pool("https://b.example", fetched), "a listed server keeps its place (RN initSdkConfig)")
        assertEquals(fetched, HiveNodes.pool("https://techcoderx.com", fetched))
        assertEquals(fetched, HiveNodes.pool(" ", fetched))
        assertEquals(fetched, HiveNodes.pool(null, fetched))
        assertEquals(listOf("https://b.example", "https://a.example"), HiveNodes.withFirst("https://b.example", fetched))
    }

    @Test
    fun retryAfterParsing() {
        val now = 1_791_000_000_000L // 2026-10-03T04:00:00Z
        assertEquals(120_000, HiveRpcClient.parseRetryAfterMillis("120", now))
        assertEquals(1_500, HiveRpcClient.parseRetryAfterMillis("1.5", now))
        assertEquals(30_000, HiveRpcClient.parseRetryAfterMillis("Sat, 03 Oct 2026 04:00:30 GMT", now))
        assertEquals(86_400_000, HiveRpcClient.parseRetryAfterMillis("Sun, 04 Oct 2026 04:00:00 GMT", now))
        // capped at a day, never overflowing
        assertEquals(HiveRpcClient.MAX_RETRY_AFTER_MILLIS, HiveRpcClient.parseRetryAfterMillis("1e300", now))
        assertEquals(HiveRpcClient.MAX_RETRY_AFTER_MILLIS, HiveRpcClient.parseRetryAfterMillis("99999999999999999", now))
        assertEquals(HiveRpcClient.MAX_RETRY_AFTER_MILLIS, HiveRpcClient.parseRetryAfterMillis("Fri, 01 Jan 9999 00:00:00 GMT", now))
        for (none in listOf(null, "", "0", "-5", "soon", "Sat, 03 Oct 2026 03:59:00 GMT", "Sat, 33 Oct 2026 04:00:30 GMT")) {
            assertEquals(0, HiveRpcClient.parseRetryAfterMillis(none, now), none.toString())
        }
    }

    @Test
    fun theTrackerIsSafeAcrossThreads() = runTest {
        val t = NodeHealthTracker { 1_000_000L }
        val nodes = List(4_000) { "https://n$it.example" }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            nodes.chunked(500).map { chunk ->
                launch { chunk.forEach { n -> repeat(3) { t.recordFailure(n) }; t.recordSuccess("https://shared.example", "x", 5, "m") } }
            }.forEach { it.join() }
        }
        // no update was lost: every node took its three failures
        assertTrue(nodes.none { t.isNodeHealthy(it) })
        assertEquals(5.0, t.usableLatencyMs("https://shared.example", "m"))
    }

    @Test
    fun trackerUnitRules() {
        var now = 1_000_000L
        val t = NodeHealthTracker { now }
        // unknown nodes are healthy and keep the given order
        assertEquals(NODES, t.orderedNodes(NODES, probe = false))
        // per-API strikes far apart never add up
        t.recordFailure(a, "bridge")
        now += 30_001
        t.recordFailure(a, "bridge")
        assertTrue(t.isNodeHealthy(a, "bridge"))
        // a defective answer cools the API at once, and a success does not lift it
        t.recordDefectiveResponse(a, "bridge")
        t.recordSuccess(a, "bridge")
        assertTrue(!t.isNodeHealthy(a, "bridge") && t.isNodeHealthy(a, "condenser_api"))
        now += 60_001
        assertTrue(t.isNodeHealthy(a, "bridge"))
        // a header-less 429 never shortens a longer window
        t.recordRateLimit(b, 3_600_000)
        t.recordRateLimit(b, null)
        now += 3_000_000
        assertTrue(!t.isNodeHealthy(b))
        // an instant failure is no latency sample; a slow one is
        t.recordSlowFailure(c, 500)
        assertNull(t.usableLatencyMs(c))
        repeat(3) { t.recordSlowFailure(c, 3_000, "m") }
        assertEquals(3_000.0, t.usableLatencyMs(c, "m"))
        assertNull(t.usableLatencyMs(c, "other"), "no fallback from a method to the node's overall profile")
        // an enormous Retry-After parks a node for a day at most
        val e = "https://node-e.example"
        t.recordRateLimit(e, Long.MAX_VALUE)
        assertTrue(!t.isNodeHealthy(e), "parked, not wrapped around into the past")
        now += HiveRpcClient.MAX_RETRY_AFTER_MILLIS + 1
        assertTrue(t.isNodeHealthy(e))
        // header-less 429s in a row: 10 s, then 20 s
        val d = "https://node-d.example"
        t.recordRateLimit(d, null)
        now += 10_001
        assertTrue(t.isNodeHealthy(d))
        t.recordRateLimit(d, null)
        now += 10_001
        assertTrue(!t.isNodeHealthy(d), "20 s the second time")
        now += 10_000
        assertTrue(t.isNodeHealthy(d))
        assertEquals("rc_api", NodeHealthTracker.apiOf("rc_api.find_rc_accounts"))
        assertEquals("call", NodeHealthTracker.apiOf("call"))
    }
}
