package com.ecency.core.api

import com.ecency.core.api.model.Post
import com.ecency.core.api.repository.PostRepository
import com.ecency.core.api.rpc.HiveRpcClient
import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
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
    private val d = "https://node-d.example"
    private val entry = Fixtures.entry("spike-author", "spike-post")
    private val missing = "Assert Exception:Post @spike-author/spike-post does not exist"

    private fun TestScope.client(transport: FakeTransport, nodes: List<String> = NODES) =
        HiveRpcClient(nodes, transport, readTimeoutMillis = 1_000, nowMillis = { testScheduler.currentTime }, jitter = {})

    private fun TestScope.api(transport: FakeTransport, nodes: List<String> = NODES) = HiveApi(client(transport, nodes))

    /** [answers] by node: the post, null, "missing" (hivemind's assert) or "down" (HTTP 503). */
    private fun nodes(answers: Map<String, String>) = FakeTransport { req ->
        when (answers[req.node]) {
            "post" -> FakeTransport.result(req, entry)
            "null" -> FakeTransport.result(req, "null")
            "missing" -> FakeTransport.error(req, -32602, missing)
            "other" -> FakeTransport.result(req, Fixtures.entry("spike-author", "another-post"))
            "invalid" -> FakeTransport.error(req, -32602, "Invalid parameters: observer")
            // every node's answer for a deleted post (HAF hivemind, seen 2026-11-26): the reason only in `data`
            "deleted" -> FakeTransport.error(req, -31999, "Invalid parameters", "\"Post spike-author/spike-post was deleted 1 time(s)\"")
            "other-invalid" -> FakeTransport.error(req, -31999, "Invalid parameters", "\"Post spike-author/spike-post has an invalid observer\"")
            "no-bridge" -> FakeTransport.error(req, -32601, "Method not found")
            "broken" -> FakeTransport.error(req, -32603, "Internal error: relation \"hivemind_app.hive_posts\" does not exist")
            "limited" -> HttpResult(429, "slow down", mapOf("retry-after" to "60"))
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

    /** Sentry ECENCY-MOBILE-3R3: a deleted comment opened from a notification was "Something went wrong" and Retry. */
    @Test
    fun aDeletedPostIsNotFound() = runTest {
        assertNull(api(nodes(mapOf(a to "deleted", b to "deleted", c to "deleted"))).postVerified("spike-author", "spike-post", "spike-me"))
        assertNull(api(nodes(mapOf(a to "deleted", b to "deleted", c to "deleted"))).post("spike-author", "spike-post", "spike-me"))
        assertNull(api(nodes(mapOf(a to "deleted", b to "deleted", c to "deleted"))).discussion("spike-author", "spike-post", "spike-me"))
        // the same words as an assert message, as the web app checks them (vision-web c650c46fe6)
        val asAssert = FakeTransport { req -> FakeTransport.error(req, -32602, "Assert Exception:Post spike-author/spike-post was deleted 2 time(s)") }
        assertNull(api(asAssert).post("spike-author", "spike-post", "spike-me"))
    }

    @Test
    fun anotherInvalidParametersAnswerIsStillAFailure() = runTest {
        assertFails { api(nodes(mapOf(a to "other-invalid", b to "other-invalid", c to "other-invalid"))).post("spike-author", "spike-post", "spike-me") }
        // the deletion words echoed inside an assert's details (a crafted permlink) are not the node's answer
        val echoed = FakeTransport { req ->
            FakeTransport.error(req, -32602, "Assert Exception:invalid permlink", """{"extension":{"assertion_expression":"Post a/b was deleted 1 time(s)"}}""")
        }
        assertFails { api(echoed).post("spike-author", "spike-post", "spike-me") }
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

    /** Review of #3714: another post in one node's answer is not an answer; the next node still has the post. */
    @Test
    fun anotherPostFromOneNodeStillAsksTheNext() = runTest {
        assertEquals("spike-post", api(nodes(mapOf(a to "null", b to "other", c to "post"))).postVerified("spike-author", "spike-post", "spike-me")?.permlink)
    }

    /** Review of #3714: only "the post does not exist" confirms a miss; another error is a failed check. */
    @Test
    fun anUnrelatedErrorFromAnotherNodeIsNotAnAnswer() = runTest {
        assertFails { api(nodes(mapOf(a to "null", b to "invalid"))).postVerified("spike-author", "spike-post", "spike-me") }
        assertEquals("spike-post", api(nodes(mapOf(a to "null", b to "invalid", c to "post"))).postVerified("spike-author", "spike-post", "spike-me")?.permlink)
    }

    /** Review of #3714: with every other node cooling, nothing confirmed the miss: the read fails, it is not "not found". */
    @Test
    fun withEveryOtherNodeCoolingTheReadFailsInsteadOfNotFound() = runTest {
        val transport = nodes(mapOf(a to "null", b to "post", c to "post"))
        val client = client(transport)
        client.health.recordRateLimit(b, 60_000)
        client.health.recordRateLimit(c, 60_000)
        assertFails { HiveApi(client).postVerified("spike-author", "spike-post", "spike-me") }
        assertEquals(listOf(a), transport.sent.map { it.node }, "a cooling node is left alone, the first is not asked again")
    }

    /** Review of #3714: what the other nodes answer counts for their health, and a node that cools meanwhile is skipped. */
    @Test
    fun theOtherNodesAnswersCountForTheirHealth() = runTest {
        val transport = nodes(mapOf(a to "null", b to "limited", c to "null"))
        val client = client(transport)
        assertNull(HiveApi(client).postVerified("spike-author", "spike-post", "spike-me"))
        assertFalse(client.health.isNodeHealthy(b, "bridge"), "a 429 with Retry-After cools the node")
        // b cooling; c is rate limited while the first read is out: neither is asked
        lateinit var late: HiveRpcClient
        val racing = FakeTransport { req ->
            if (req.node == a) late.health.recordRateLimit(c, 60_000)
            FakeTransport.result(req, "null")
        }
        late = client(racing, listOf(a, b, c, d))
        late.health.recordRateLimit(b, 60_000)
        assertNull(HiveApi(late).postVerified("spike-author", "spike-post", "spike-me"))
        assertEquals(setOf(a, d), racing.sent.map { it.node }.toSet())
    }

    /**
     * Review of #3714: a post published here a moment ago shows its local copy while no node can be reached, and only
     * then: the copy is not kept as the post (the next read asks again), and a request a node refuses stays an error.
     */
    @Test
    fun theLocalCopyStandsInOnlyWhileNoNodeAnswers() = runTest {
        var down = true
        val transport = FakeTransport { req -> if (down) HttpResult(503, "") else FakeTransport.result(req, entry) }
        val repo = PostRepository(HiveApi(client(transport)), nowMillis = { testScheduler.currentTime })
        repo.addLocalPost(Post("spike-author", "spike-post", title = "Local copy"))
        assertEquals("Local copy", repo.getPostVerified("spike-author", "spike-post", "spike-me")?.title)
        down = false
        assertTrue(repo.getPostVerified("spike-author", "spike-post", "spike-me")?.title != "Local copy", "the copy was not kept")

        val refusing = PostRepository(HiveApi(client(FakeTransport { req -> FakeTransport.error(req, -32602, "Invalid parameters: observer") })), nowMillis = { testScheduler.currentTime })
        refusing.addLocalPost(Post("spike-author", "spike-post", title = "Local copy"))
        assertFails { refusing.getPostVerified("spike-author", "spike-post", "spike-me") }
    }

    /** Review of #3714: a broken node's error that reads "not found" or "does not exist" is a failure, not an answer. */
    @Test
    fun aBrokenNodesNotFoundTextIsNoAnswer() = runTest {
        assertFails { api(nodes(mapOf(a to "null", b to "no-bridge", c to "broken"))).postVerified("spike-author", "spike-post", "spike-me") }
    }

    /** Review of #3714: when no other node answered, a just-published post shows its local copy whichever node failed last. */
    @Test
    fun theLocalCopyStandsInWhateverTheOtherNodesFailedWith() = runTest {
        for (order in listOf(mapOf(a to "null", b to "invalid", c to "down"), mapOf(a to "null", b to "down", c to "invalid"))) {
            val repo = PostRepository(HiveApi(client(nodes(order))), nowMillis = { testScheduler.currentTime })
            repo.addLocalPost(Post("spike-author", "spike-post", title = "Local copy"))
            assertEquals("Local copy", repo.getPostVerified("spike-author", "spike-post", "spike-me")?.title, "$order")
        }
    }

    /** Review of #3714: a broken first node's "Method not found" is a failed read, also with no other node to ask. */
    @Test
    fun aBrokenFirstNodesNotFoundTextIsNoAnswer() = runTest {
        assertFails { api(nodes(mapOf(a to "no-bridge")), listOf(a)).postVerified("spike-author", "spike-post", "spike-me") }
        assertFails { api(nodes(mapOf(a to "no-bridge")), listOf(a)).post("spike-author", "spike-post", "spike-me") }
    }
}
