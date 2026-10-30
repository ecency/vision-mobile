package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Fixtures.entry
import com.ecency.core.api.Sent
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.repository.CommentSort
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.render.blocks.Block
import com.ecency.core.render.blocks.BlockText
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostModelTest {
    private val rootMeta = """{"tags":["test"],"pinned_reply":"carol/c-pinned"}"""

    private fun root(updated: String = "2026-10-01T10:00:00") = entry(
        "alice", "root", created = updated, replies = listOf("bob/c-top", "carol/c-pinned"), metadata = rootMeta,
    ).replace("\"body\":\"Body of root\"", "\"body\":\"# Hello\\n\\nSome **bold** text ![img](https://example.com/a.png)\"")

    private fun discussion(updated: String = "2026-10-01T10:00:00") = """{
        "alice/root": ${root(updated)},
        "bob/c-top": ${entry("bob", "c-top", parent = "alice" to "root", replies = listOf("dave/c-2"), pending = "5.000 HBD")},
        "carol/c-pinned": ${entry("carol", "c-pinned", parent = "alice" to "root")},
        "dave/c-2": ${entry("dave", "c-2", parent = "bob" to "c-top", depth = 2, replies = listOf("erin/c-3"))},
        "erin/c-3": ${entry("erin", "c-3", parent = "dave" to "c-2", depth = 3, children = 4)}
    }"""

    private fun handler(failDiscussion: () -> Boolean = { false }, updated: () -> String = { "2026-10-01T10:00:00" }): suspend FakeTransport.(Sent) -> HttpResult = { req ->
        when (req.method) {
            "bridge.get_post" -> FakeTransport.result(req, root(updated()))
            "bridge.get_discussion" -> if (failDiscussion()) HttpResult(503, "") else FakeTransport.result(req, discussion(updated()))
            else -> TestApp.chain(req) ?: HttpResult(500, "")
        }
    }

    @Test
    fun postBodyAndThreadedComments() = runTest {
        val app = TestApp(this, handler = handler())
        val model = app.env.postModel("alice", "root")
        model.start()
        assertTrue(model.current.isLoading)
        testScheduler.advanceUntilIdle()
        val s = model.current
        assertEquals("Title of root", s.card?.title)
        val body = assertNotNull(s.body)
        assertTrue(body.first() is Block.Heading)
        assertTrue(BlockText.of(body).contains("Some bold text"))
        // body images go through the proxy
        val image = body.flatMap { if (it is Block.Image) listOf(it) else emptyList() }.single()
        assertTrue(image.src.startsWith("https://i.ecency.com/p/"), image.src)

        // pinned reply first, then by payout; replies flattened to level 3, cut below it
        assertEquals(listOf("c-pinned" to 1, "c-top" to 1, "c-2" to 2, "c-3" to 3), s.comments.map { it.key.permlink to it.level })
        assertTrue(s.comments.last().hasHiddenReplies)
        assertFalse(s.comments.first().hasHiddenReplies)
        assertTrue(s.comments.all { it.blocks.isNotEmpty() })
        assertEquals("https://i.ecency.com/u/bob/avatar/128", s.comments[1].card.avatarUrl)
        assertEquals("$5.000", s.comments[1].card.payoutText)
        model.close()
    }

    @Test
    fun renderingRunsOffTheMainThreadAndIsCachedPerLastUpdate() = runTest {
        var updated = "2026-10-01T10:00:00"
        val app = TestApp(this, handler = handler(updated = { updated }))
        val first = app.env.postModel("alice", "root")
        first.start()
        testScheduler.advanceUntilIdle()
        assertEquals(emptyList(), app.renderer.offRenderCalls)
        assertEquals(1, app.renderer.blockCalls.count { it == "root" })
        first.close()

        // reopening: same post, same last update -> no new render
        val second = app.env.postModel("alice", "root")
        second.start()
        testScheduler.advanceUntilIdle()
        assertNotNull(second.current.body)
        assertEquals(1, app.renderer.blockCalls.count { it == "root" })

        // an edit (new last update) renders again
        updated = "2026-10-02T10:00:00"
        second.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(2, app.renderer.blockCalls.count { it == "root" })
        assertEquals(emptyList(), app.renderer.offRenderCalls)
        second.close()
    }

    @Test
    fun bodyIsNullUntilTheRenderDispatcherRuns() = runTest {
        val app = TestApp(this, handler = handler())
        val model = app.env.postModel("alice", "root")
        model.start()
        // run only main-dispatcher work: post arrives, render is queued but not executed yet
        var guard = 0
        while (model.current.post == null && guard++ < 1000) testScheduler.runCurrent()
        assertNotNull(model.current.post)
        testScheduler.advanceUntilIdle()
        assertNotNull(model.current.body)
        model.close()
    }

    @Test
    fun notFound() = runTest {
        val app = TestApp(this) { req ->
            if (req.method == "bridge.get_post") FakeTransport.error(req, -32602, "Invalid parameters: Post alice/none does not exist")
            else FakeTransport.result(req, "{}")
        }
        val model = app.env.postModel("alice", "none")
        model.start()
        testScheduler.advanceUntilIdle()
        assertTrue(model.current.notFound, "state ${model.current}")
        assertNull(model.current.error)
        model.close()
    }

    @Test
    fun commentErrorThenRetry() = runTest {
        var fail = true
        val app = TestApp(this, handler = handler(failDiscussion = { fail }))
        val model = app.env.postModel("alice", "root")
        model.start()
        testScheduler.advanceUntilIdle()
        assertNotNull(model.current.body)
        assertEquals(ErrorKind.NETWORK, model.current.commentsError?.kind)
        fail = false
        model.retry()
        testScheduler.advanceUntilIdle()
        assertNull(model.current.commentsError)
        assertEquals(4, model.current.comments.size)
        model.close()
    }

    @Test
    fun postErrorThenRetry() = runTest {
        var down = true
        val app = TestApp(this) { req -> if (down) HttpResult(503, "") else handler()(this, req) }
        val model = app.env.postModel("alice", "root")
        model.start()
        testScheduler.advanceUntilIdle()
        assertEquals(ErrorKind.NETWORK, model.current.error?.kind)
        assertTrue(model.current.error!!.retryable)
        down = false
        model.retry()
        testScheduler.advanceUntilIdle()
        assertNull(model.current.error)
        assertNotNull(model.current.body)
        assertEquals(4, model.current.comments.size)
        model.close()
    }

    @Test
    fun commentSortReordersButKeepsThePinnedReplyFirst() = runTest {
        val app = TestApp(this, handler = handler())
        val model = app.env.postModel("alice", "root")
        model.start()
        testScheduler.advanceUntilIdle()
        model.setCommentSort(CommentSort.AGE)
        testScheduler.advanceUntilIdle()
        assertEquals(CommentSort.AGE, model.current.commentSort)
        assertEquals(PostKey("carol", "c-pinned"), model.current.comments.first().key)
        model.close()
    }
}
