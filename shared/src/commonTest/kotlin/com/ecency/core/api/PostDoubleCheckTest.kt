package com.ecency.core.api

import com.ecency.core.api.rpc.HiveRpcClient
import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #3712: on the post screen, a post one node does not have is asked of other nodes before it is "not found" (SDK
 * 2.9.0 getPostQueryOptions + verifyPostOnAlternateNode, web #2002): a node that lags behind the chain answers a post
 * made seconds ago as missing. Other reads take the one answer.
 */
class PostDoubleCheckTest {
    private val a = NODES[0]
    private val b = NODES[1]
    private val c = NODES[2]
    private val entry = Fixtures.entry("spike-author", "spike-post")
    private val missing = "Assert Exception:Post @spike-author/spike-post does not exist"

    private fun TestScope.api(transport: FakeTransport, nodes: List<String> = NODES) =
        HiveApi(HiveRpcClient(nodes, transport, readTimeoutMillis = 1_000, nowMillis = { testScheduler.currentTime }, jitter = {}))

    /** [answers] by node: the post, null, "missing" (hivemind's assert) or "down" (HTTP 503). */
    private fun nodes(answers: Map<String, String>) = FakeTransport { req ->
        when (answers[req.node]) {
            "post" -> FakeTransport.result(req, entry)
            "null" -> FakeTransport.result(req, "null")
            "missing" -> FakeTransport.error(req, -32602, missing)
            "other" -> FakeTransport.result(req, Fixtures.entry("spike-author", "another-post"))
            else -> HttpResult(503, "")
        }
    }

    @Test
    fun aNullFromOneNodeIsAskedOfAnotherThatHasThePost() = runTest {
        val transport = nodes(mapOf(a to "null", b to "post", c to "post"))
        assertEquals("spike-post", api(transport).postVerified("spike-author", "spike-post", "spike-me")?.permlink)
        assertTrue(transport.sent.any { it.node != a }, "another node was asked")
    }

    @Test
    fun aDoesNotExistFromALaggingNodeIsAskedOfAnotherToo() = runTest {
        // hivemind answers a post it lacks with an assert: a lagging node answers a new post that way
        val transport = nodes(mapOf(a to "missing", b to "post", c to "missing"))
        assertEquals("spike-post", api(transport).postVerified("spike-author", "spike-post", "spike-me")?.permlink)
    }

    @Test
    fun whenTheOtherNodesAnswerItIsNotFound() = runTest {
        assertNull(api(nodes(mapOf(a to "null", b to "null", c to "missing"))).postVerified("spike-author", "spike-post", "spike-me"))
        // another node answering and the rest down: still an answer
        assertNull(api(nodes(mapOf(a to "null", b to "null"))).postVerified("spike-author", "spike-post", "spike-me"))
    }

    @Test
    fun whenEveryOtherNodeFailsTheReadFailsInsteadOfNotFound() = runTest {
        assertFails { api(nodes(mapOf(a to "null"))).postVerified("spike-author", "spike-post", "spike-me") }
    }

    @Test
    fun aPoolOfOneNodeTakesItsAnswer() = runTest {
        assertNull(api(nodes(mapOf(a to "null")), listOf(a)).postVerified("spike-author", "spike-post", "spike-me"))
    }

    @Test
    fun anotherPostInTheAnswerIsNotThePost() = runTest {
        assertNull(api(nodes(mapOf(a to "null", b to "other", c to "null"))).postVerified("spike-author", "spike-post", "spike-me"))
    }

    @Test
    fun anOrdinaryPostReadTakesTheOneAnswer() = runTest {
        // feeds, link cards and the editor: a missing post costs one read, as before (the SDK checks the post page only)
        val transport = nodes(mapOf(a to "missing", b to "post", c to "post"))
        assertNull(api(transport).post("spike-author", "spike-post", "spike-me"))
        assertEquals(setOf(a), transport.sent.map { it.node }.toSet())
    }

    @Test
    fun atMostThreeOtherNodesAreAsked() = runTest {
        val many = (1..6).map { "https://node-$it.example" }
        val transport = FakeTransport { req -> FakeTransport.error(req, -32602, missing) }
        assertNull(api(transport, many).postVerified("spike-author", "spike-post", "spike-me"))
        assertEquals(1 + HiveApi.VERIFY_NODES, transport.sent.map { it.node }.toSet().size)
    }
}
