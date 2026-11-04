package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Fixtures.array
import com.ecency.core.api.Fixtures.entry
import com.ecency.core.api.RankedSort
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Fixes from the delta review: reply reopen, votes during progressive comments, refreshAndWait on close, paging ends. */
class DeltaFixesTest {
    private val key = PostKey("alice", "root")

    private fun discussion(comments: Int) = buildString {
        append("{\"alice/root\":")
        append(entry("alice", "root", replies = (0 until comments).map { "bob/c-$it" }))
        for (i in 0 until comments) append(",\"bob/c-$i\":").append(entry("bob", "c-$i", parent = "alice" to "root"))
        append("}")
    }

    private fun handler(
        comments: Int = 1,
        broadcastGate: CompletableDeferred<Unit>? = null,
    ): suspend FakeTransport.(com.ecency.core.api.Sent) -> HttpResult = { req ->
        when (req.method) {
            "condenser_api.broadcast_transaction" -> { broadcastGate?.await(); FakeTransport.result(req, "null") }
            "bridge.get_post" -> FakeTransport.result(req, entry("alice", "root"))
            "bridge.get_discussion" -> FakeTransport.result(req, discussion(comments))
            "bridge.get_ranked_posts" -> FakeTransport.result(req, array(entry("alice", "root")))
            else -> TestApp.chain(req) ?: HttpResult(500, "")
        }
    }

    @Test
    fun aSentReplyLeavesTheNextComposerForThatParentFreshAndEmpty() = runTest {
        val app = TestApp(this, handler = handler())
        app.signIn()
        app.env.session.start()
        val post = app.env.postModel("alice", "root")
        post.start()
        testScheduler.advanceUntilIdle()
        val parent = post.current.post!!
        val reply = app.env.replyModel(key, parent)
        reply.setText("Nice post")
        reply.send()
        testScheduler.advanceUntilIdle()
        assertNotNull(reply.current.sent)

        // a UI that closes the composer on `sent` must not get the sent model back
        val next = app.env.replyModel(key, parent)
        assertNotSame(reply, next)
        assertNull(next.current.sent)
        assertEquals("", next.current.text)
        assertNull(next.current.draftPermlink)
        // and the new draft is the app-wide one again
        next.setText("second")
        assertSame(next, app.env.replyModel(key, parent))
        post.close()
    }

    @Test
    fun aVoteWhileTheFirstCommentsAreShownSurvivesTheRest() = runTest {
        // the vote stays in flight, so nothing but the comment load itself touches the list afterwards
        val gate = CompletableDeferred<Unit>()
        val app = TestApp(this, handler = handler(comments = 13, broadcastGate = gate))
        app.signIn()
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        val post = app.env.postModel("alice", "root")
        val voted = PostKey("bob", "c-0")
        var votedAt = -1
        val watch = post.watch { s ->
            // the first 12 are on screen and the 13th is still rendering
            if (votedAt < 0 && s.comments.size == 12) {
                votedAt = s.comments.size
                app.env.votes.vote(voted.author, voted.permlink, 10000)
            }
        }
        post.start()
        // no virtual time passes: the broadcast's own timeout must not fire and roll the vote back
        testScheduler.runCurrent()
        assertEquals(12, votedAt)
        assertEquals(13, post.current.comments.size)
        assertTrue(app.env.votes.current.statuses[voted]!!.inFlight)
        assertTrue(post.current.comments.first { it.key == voted }.card.votedByMe, "the final list keeps the vote")
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        watch.close()
        post.close()
    }

    @Test
    fun refreshAndWaitEndsWhenTheModelClosesMidRefresh() = runTest {
        var gate: CompletableDeferred<Unit>? = null
        val base = handler()
        val app = TestApp(this) { req -> gate?.await(); base(req) }
        val feed = app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        val post = app.env.postModel("alice", "root")
        feed.start()
        post.start()
        testScheduler.advanceUntilIdle()
        gate = CompletableDeferred()
        val feedWait = launch(app.main) { feed.refreshAndWait() }
        val postWait = launch(app.main) { post.refreshAndWait() }
        testScheduler.runCurrent()
        assertTrue(feedWait.isActive)
        assertTrue(postWait.isActive)

        feed.close()
        post.close()
        testScheduler.advanceUntilIdle()
        assertTrue(feedWait.isCompleted, "closing the feed ends its wait")
        assertTrue(postWait.isCompleted, "closing the post ends its wait")
    }

    @Test
    fun aRepeatedCursorEndsTheFeed() = runTest {
        // a node that ignores the cursor answers every page with the same posts
        val app = TestApp(this) { FakeTransport.result(it, array(entry("alice", "a"), entry("bob", "b"))) }
        val feed = app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        feed.start()
        testScheduler.advanceUntilIdle()
        assertFalse(feed.current.endReached)
        feed.loadMore()
        testScheduler.advanceUntilIdle()
        assertTrue(feed.current.endReached, "same cursor again: the feed ends instead of asking forever")
        val calls = app.transport.sent.size
        feed.loadMore()
        testScheduler.advanceUntilIdle()
        assertEquals(calls, app.transport.sent.size)
        feed.close()
    }

    @Test
    fun threePagesOfNothingNewEndTheFeed() = runTest {
        // the cursor moves, but every later page holds only posts already shown
        val app = TestApp(this) { req ->
            val start = req.params.jsonObject["start_permlink"]?.jsonPrimitive?.content
            FakeTransport.result(req, if (start == "a") array(entry("alice", "a"), entry("bob", "b")) else array(entry("bob", "b"), entry("alice", "a")))
        }
        val feed = app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        feed.start()
        testScheduler.advanceUntilIdle()
        repeat(2) {
            feed.loadMore()
            testScheduler.advanceUntilIdle()
            assertFalse(feed.current.endReached, "page ${feed.current.pages}")
        }
        feed.loadMore()
        testScheduler.advanceUntilIdle()
        assertEquals(4, feed.current.pages)
        assertTrue(feed.current.endReached, "three pages in a row added nothing")
        assertEquals(4, app.transport.sent.count { it.method == "bridge.get_ranked_posts" })
        feed.close()
    }
}
