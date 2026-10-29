package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Fixtures.array
import com.ecency.core.api.Fixtures.entry
import com.ecency.core.api.RankedSort
import com.ecency.core.api.Sent
import com.ecency.core.api.broadcast.PayoutType
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.render.blocks.SafeTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
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

/** Review fixes of the vertical slice: session changes, reply lifetime, write-once options, paging, partial updates. */
class SliceFixesTest {
    private val key = PostKey("alice", "root")

    private fun discussion(comments: Int) = buildString {
        append("{\"alice/root\":")
        append(entry("alice", "root", votes = listOf("x"), replies = (0 until comments).map { "bob/c-$it" }))
        for (i in 0 until comments) append(",\"bob/c-$i\":").append(entry("bob", "c-$i", parent = "alice" to "root"))
        append("}")
    }

    private fun handler(
        comments: Int = 1,
        onBroadcast: suspend (Sent) -> HttpResult = { FakeTransport.result(it, "null") },
        exists: () -> Boolean = { false },
    ): suspend FakeTransport.(Sent) -> HttpResult = { req ->
        when (req.method) {
            "condenser_api.broadcast_transaction" -> onBroadcast(req)
            "bridge.get_ranked_posts" -> FakeTransport.result(req, array(entry("alice", "root", votes = listOf("x")), entry("bob", "two")))
            "bridge.get_post" -> FakeTransport.result(req, entry("alice", "root", votes = listOf("x")))
            "bridge.get_discussion" -> FakeTransport.result(req, discussion(comments))
            "condenser_api.get_accounts" -> FakeTransport.result(req, "[]")
            "condenser_api.get_content" -> if (exists()) {
                val p = req.params.jsonArray
                FakeTransport.result(req, """{"author":${p[0]},"permlink":${p[1]},"title":"","body":"first","parent_author":"alice","parent_permlink":"root"}""")
            } else {
                TestApp.chain(req)!!
            }
            else -> TestApp.chain(req) ?: HttpResult(500, "")
        }
    }

    private fun Sent.operations() = params.jsonArray.single().jsonObject["operations"]!!.jsonArray

    /** Signed in and loaded: a vote or reply signs as the account signed in when it is tapped. */
    private fun kotlinx.coroutines.test.TestScope.signedIn(app: TestApp) {
        app.signIn()
        app.env.session.start()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun voteResultsOfASignedOutSessionAreIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val app = TestApp(this, handler = handler(onBroadcast = { gate.await(); FakeTransport.result(it, "null") }))
        signedIn(app)
        val feed = app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        feed.start()
        testScheduler.advanceUntilIdle()
        app.env.votes.vote("alice", "root", 10000)
        testScheduler.runCurrent()
        assertTrue(app.env.votes.current.statuses[key]!!.inFlight)

        app.env.session.signOut()
        testScheduler.runCurrent()
        assertTrue(app.env.votes.current.statuses.isEmpty())
        assertNull(app.env.localVotes.get(key))

        // the broadcast lands after the sign-out: the previous account's vote must not come back
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertNull(app.env.localVotes.get(key))
        assertTrue(app.env.votes.current.statuses.isEmpty())
        assertFalse(feed.current.items.first().votedByMe)
        feed.close()
    }

    @Test
    fun rollbackOfAVoteFromASignedOutSessionDoesNotRestoreItsPreviousVote() = runTest {
        var fail = false
        val gate = CompletableDeferred<Unit>()
        val app = TestApp(this, handler = handler(onBroadcast = {
            if (fail) { gate.await(); HttpResult(503, "") } else FakeTransport.result(it, "null")
        }))
        signedIn(app)
        app.env.votes.vote("alice", "root", 10000)
        testScheduler.advanceUntilIdle()
        assertEquals(LocalVote(10000, false), app.env.localVotes.get(key))
        fail = true
        app.env.votes.vote("alice", "root", 0)
        testScheduler.runCurrent()
        app.env.session.signOut()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertNull(app.env.localVotes.get(key), "the rollback must not restore the old account's upvote")
        assertTrue(app.env.votes.current.statuses.isEmpty())
    }

    @Test
    fun aReplyKeepsSendingAfterItsScreenIsGoneAndTheDraftIsAppWide() = runTest {
        val gate = CompletableDeferred<Unit>()
        val app = TestApp(this, handler = handler(onBroadcast = { gate.await(); FakeTransport.result(it, "null") }))
        signedIn(app)
        val events = mutableListOf<AppEvent>()
        val collector = launch(app.main) { app.env.events.collect { events += it } }
        val post = app.env.postModel("alice", "root")
        post.start()
        testScheduler.advanceUntilIdle()
        val parent = post.current.post!!
        val reply = app.env.replyModel(key, parent)
        reply.setText("Nice post")
        reply.send()
        testScheduler.runCurrent()
        assertTrue(reply.current.isSending)

        // the reader leaves the post: the screen closes its models
        post.close()
        reply.close()
        assertSame(reply, app.env.replyModel(key, parent), "one draft per parent for the whole app")

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        val sent = assertNotNull(reply.current.sent, "the send finished although the screen was gone")
        assertEquals(1, app.broadcasts().size)
        assertTrue(events.any { it is AppEvent.CommentPosted && it.comment == sent }, events.toString())

        // coming back shows it on top
        val again = app.env.postModel("alice", "root")
        again.start()
        testScheduler.advanceUntilIdle()
        assertEquals(sent, again.current.comments.first().key)

        // a draft left unsent is still there after the screen went away
        val comment = again.current.comments.last().post
        app.env.replyModel(key, comment).setText("half written")
        again.close()
        assertEquals("half written", app.env.replyModel(key, comment).current.text)

        // signing out drops the drafts of that account
        app.env.session.signOut()
        testScheduler.advanceUntilIdle()
        val fresh = app.env.replyModel(key, comment)
        assertEquals("", fresh.current.text)
        collector.cancel()
    }

    @Test
    fun aRebuiltReplyDoesNotResendCommentOptionsOnceTheCommentExists() = runTest {
        var down = true
        var exists = false
        val app = TestApp(this, handler = handler(onBroadcast = { if (down) HttpResult(503, "") else FakeTransport.result(it, "null") }, exists = { exists }))
        signedIn(app)
        val reply = app.env.replyModel(key, com.ecency.core.api.model.Post("alice", "root"), PayoutType.DECLINE)
        reply.setText("first")
        reply.send()
        testScheduler.advanceUntilIdle()
        val first = app.broadcasts().last().operations().map { it.jsonArray[0].jsonPrimitive.content }
        assertEquals(listOf("comment", "comment_options"), first)

        // the first attempt did land: the edit goes out without comment_options (write-once)
        exists = true
        down = false
        reply.setText("first, edited")
        reply.send()
        testScheduler.advanceUntilIdle()
        // it is out already and the text changed: the user is asked, and chooses to update it
        assertTrue(reply.current.conflict)
        reply.resolveConflict(update = true)
        testScheduler.advanceUntilIdle()
        assertNotNull(reply.current.sent)
        val edit = app.broadcasts().last().operations()
        assertEquals(listOf("comment"), edit.map { it.jsonArray[0].jsonPrimitive.content })
        assertEquals("first, edited", edit[0].jsonArray[1].jsonObject["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun aRebuiltReplyKeepsCommentOptionsWhileTheCommentDoesNotExist() = runTest {
        var down = true
        val app = TestApp(this, handler = handler(onBroadcast = { if (down) HttpResult(503, "") else FakeTransport.result(it, "null") }))
        signedIn(app)
        val reply = app.env.replyModel(key, com.ecency.core.api.model.Post("alice", "root"), PayoutType.DECLINE)
        reply.setText("first")
        reply.send()
        testScheduler.advanceUntilIdle()
        down = false
        reply.setText("first, edited")
        reply.send()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("comment", "comment_options"), app.broadcasts().last().operations().map { it.jsonArray[0].jsonPrimitive.content })
    }

    @Test
    fun aVoteRebuildsOnlyTheVotedCardAndComment() = runTest {
        val app = TestApp(this, handler = handler(comments = 3))
        signedIn(app)
        val feed = app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        val post = app.env.postModel("alice", "root")
        feed.start()
        post.start()
        testScheduler.advanceUntilIdle()
        val cards = feed.current.items
        val comments = post.current.comments
        app.env.votes.vote("alice", "root", 10000)
        testScheduler.runCurrent()
        assertTrue(feed.current.items[0].votedByMe)
        assertSame(cards[1], feed.current.items[1], "the other card keeps its instance")
        assertSame(comments, post.current.comments, "no comment changed: the list keeps its identity")

        app.env.votes.vote("bob", "c-1", 10000)
        testScheduler.runCurrent()
        assertSame(comments[0], post.current.comments[0])
        assertSame(comments[2], post.current.comments[2])
        assertNotSame(comments[1], post.current.comments[1])
        assertTrue(post.current.comments[1].card.votedByMe)
        testScheduler.advanceUntilIdle()
        feed.close()
        post.close()
    }

    @Test
    fun aPageOfDuplicatesStillCountsAsAPage() = runTest {
        // the next page holds only posts already shown (its cursor moves, so the feed goes on)
        val app = TestApp(this) { req ->
            val next = req.params.jsonObject["start_permlink"] != null
            FakeTransport.result(req, if (next) array(entry("bob", "b"), entry("alice", "a")) else array(entry("alice", "a"), entry("bob", "b")))
        }
        val feed = app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        feed.start()
        testScheduler.advanceUntilIdle()
        assertEquals(1, feed.current.pages)
        feed.loadMore()
        testScheduler.advanceUntilIdle()
        assertEquals(2, feed.current.items.size)
        assertFalse(feed.current.endReached)
        assertEquals(2, feed.current.pages, "nothing new arrived, yet the page counter moves so the UI asks again")
        feed.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(1, feed.current.pages)
        feed.close()
    }

    @Test
    fun sessionChangesRefreshFeedsAndPostsInPlace() = runTest {
        val app = TestApp(this, handler = handler())
        signedIn(app)
        val feed = app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        val post = app.env.postModel("alice", "root")
        val idle = app.env.feedModel(FeedSource.Ranked(RankedSort.HOT))
        feed.start()
        post.start()
        testScheduler.advanceUntilIdle()
        val before = app.transport.sent.size

        app.env.session.signOut() // emits SessionChanged(null)
        testScheduler.advanceUntilIdle()
        val calls = app.transport.sent.drop(before).map { it.method }
        assertTrue("bridge.get_ranked_posts" in calls, calls.toString())
        assertTrue("bridge.get_post" in calls || "bridge.get_discussion" in calls, calls.toString())
        assertFalse(idle.current.loaded, "a feed never started stays idle")
        assertTrue(feed.current.loaded)
        assertNotNull(post.current.post)
        feed.close()
        post.close()
        idle.close()
    }

    @Test
    fun refreshAndWaitReturnsOnceTheRefreshFinished() = runTest {
        var gate: CompletableDeferred<Unit>? = null
        val base = handler()
        val app = TestApp(this) { req -> gate?.await(); base(req) }
        val feed = app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        val post = app.env.postModel("alice", "root")
        feed.start()
        post.start()
        testScheduler.advanceUntilIdle()
        var feedDone = false
        var postDone = false
        gate = CompletableDeferred()
        launch(app.main) { feed.refreshAndWait(); feedDone = true }
        launch(app.main) { post.refreshAndWait(); postDone = true }
        testScheduler.runCurrent()
        assertTrue(feed.current.isRefreshing)
        assertFalse(feedDone)
        assertFalse(postDone)
        gate!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue(feedDone)
        assertTrue(postDone)
        assertFalse(feed.current.isRefreshing)
        feed.close()
        post.close()
    }

    @Test
    fun aLongDiscussionShowsItsFirstCommentsBeforeTheRest() = runTest {
        val app = TestApp(this, handler = handler(comments = 30))
        val post = app.env.postModel("alice", "root")
        val sizes = mutableListOf<Int>()
        val watch = post.watch { sizes += it.comments.size }
        post.start()
        testScheduler.advanceUntilIdle()
        assertEquals(30, post.current.comments.size)
        assertEquals(listOf(0, 12, 30), sizes.distinct(), "first screens, then the rest")

        // a reload keeps the full list until the new one is complete
        sizes.clear()
        post.refresh()
        testScheduler.advanceUntilIdle()
        assertTrue(sizes.none { it in 1 until 30 }, sizes.toString())
        watch.close()
        post.close()
    }

    @Test
    fun playersOfWholeSiteHostsMustBeEmbedPaths() {
        assertNotNull(SafeTarget.embed("https://archive.org/embed/some-item"))
        assertNull(SafeTarget.embed("https://archive.org/account/login"))
        assertNull(SafeTarget.embed("https://archive.org/details/some-item"))
        assertNotNull(SafeTarget.embed("https://3speak.tv/embed?v=someone/abc"))
        assertNotNull(SafeTarget.embed("https://3speak.tv/watch?v=someone/abc"))
        assertNull(SafeTarget.embed("https://3speak.tv/upload"))
        assertNotNull(SafeTarget.embed("https://www.youtube.com/embed/dQw4w9WgXcQ"))
    }
}
