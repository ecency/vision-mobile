package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Fixtures.entry
import com.ecency.core.api.Sent
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.api.RankedSort
import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoteAndReplyTest {
    private val key = PostKey("alice", "root")

    /** broadcast_transaction answers come from [onBroadcast]; bridge reads return one post. */
    private fun handler(onBroadcast: suspend (Sent) -> HttpResult): suspend FakeTransport.(Sent) -> HttpResult = { req ->
        when (req.method) {
            "condenser_api.broadcast_transaction" -> onBroadcast(req)
            "bridge.get_ranked_posts" -> FakeTransport.result(req, "[${entry("alice", "root", votes = listOf("x"))}]")
            "bridge.get_post" -> FakeTransport.result(req, entry("alice", "root", votes = listOf("x")))
            "bridge.get_discussion" -> FakeTransport.result(req, """{"alice/root":${entry("alice", "root", votes = listOf("x"))}}""")
            else -> TestApp.chain(req) ?: HttpResult(500, "")
        }
    }

    private fun Sent.tx() = params.jsonArray.single().jsonObject

    /** Signed in and loaded: a vote or reply signs as the account signed in when it is tapped. */
    private fun kotlinx.coroutines.test.TestScope.signedIn(app: TestApp) {
        app.signIn()
        app.env.session.start()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun optimisticVoteShowsEverywhereAndStaysOnSuccess() = runTest {
        val gate = CompletableDeferred<Unit>()
        val app = TestApp(this, handler = handler { gate.await(); FakeTransport.result(it, "null") })
        signedIn(app)
        val feed = app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        val post = app.env.postModel("alice", "root")
        feed.start()
        post.start()
        testScheduler.advanceUntilIdle()
        assertEquals(1, feed.current.items.single().votes)
        assertFalse(feed.current.items.single().votedByMe)

        app.env.votes.vote("alice", "root", 10000)
        // optimistic: before the broadcast ran
        testScheduler.runCurrent()
        assertTrue(app.env.votes.current.statuses[key]!!.inFlight)
        assertTrue(feed.current.items.single().votedByMe)
        assertEquals(2, feed.current.items.single().votes)
        assertTrue(post.current.card!!.votedByMe)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertNull(app.env.votes.current.statuses[key])
        assertTrue(feed.current.items.single().votedByMe)
        assertEquals(LocalVote(10000, pending = false), app.env.localVotes.get(key))
        val op = app.broadcasts().single().tx()["operations"].toString()
        assertEquals("""[["vote",{"voter":"spike-voter","author":"alice","permlink":"root","weight":10000}]]""", op)
        feed.close()
        post.close()
    }

    @Test
    fun rejectedVoteRollsBack() = runTest {
        val gate = CompletableDeferred<Unit>()
        val app = TestApp(this, handler = handler {
            gate.await()
            FakeTransport.error(it, -32000 + 1000, "missing required posting authority")
        })
        signedIn(app)
        val feed = app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        feed.start()
        testScheduler.advanceUntilIdle()
        app.env.votes.vote("alice", "root", 10000)
        testScheduler.runCurrent()
        assertTrue(feed.current.items.single().votedByMe)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        // missing authority: the SDK's fallback offers the auth upgrade (HiveSigner or HiveAuth for posting)
        val upgrade = app.env.signing.current.prompt as SigningPrompt.AuthUpgrade
        assertEquals(com.ecency.core.hive.KeyRole.POSTING, upgrade.authority)
        assertFalse(upgrade.keyEntry)
        app.env.signing.cancel()
        testScheduler.advanceUntilIdle()
        assertFalse(feed.current.items.single().votedByMe)
        assertEquals(1, feed.current.items.single().votes)
        assertNull(app.env.localVotes.get(key))
        assertNull(app.env.votes.current.statuses[key], "declining the upgrade is not an error")
        feed.close()
    }

    @Test
    fun networkFailureRollsBackAndRetryResendsTheSameTransaction() = runTest {
        var down = true
        val app = TestApp(this, handler = handler { if (down) HttpResult(503, "") else FakeTransport.result(it, "null") })
        signedIn(app)
        app.env.votes.vote("alice", "root", 5000)
        testScheduler.advanceUntilIdle()
        val status = assertNotNull(app.env.votes.current.statuses[key])
        assertEquals(ErrorKind.NETWORK, status.error?.kind)
        assertTrue(status.error!!.retryable)
        assertEquals(AppError.MAY_HAVE_LANDED_MESSAGE, status.error!!.message)
        assertNull(app.env.localVotes.get(key)) // rolled back
        val failed = app.broadcasts().map { it.tx() }.distinct()
        assertEquals(1, failed.size) // one transaction, tried on every node

        down = false
        app.env.votes.retry("alice", "root")
        testScheduler.advanceUntilIdle()
        assertNull(app.env.votes.current.statuses[key])
        assertEquals(LocalVote(5000, false), app.env.localVotes.get(key))
        // the retry re-sent the very same signed transaction (same signature), not a new one
        assertEquals(failed.single(), app.broadcasts().last().tx())
    }

    @Test
    fun duplicateVoteCountsAsSuccess() = runTest {
        val app = TestApp(this, handler = handler {
            FakeTransport.error(it, -31000, "Your current vote on this comment is identical to this vote.")
        })
        signedIn(app)
        app.env.votes.vote("alice", "root", 10000)
        testScheduler.advanceUntilIdle()
        assertNull(app.env.votes.current.statuses[key])
        assertEquals(LocalVote(10000, false), app.env.localVotes.get(key))
    }

    @Test
    fun voteNeedsASignedInAccountAndAValidWeight() = runTest {
        val app = TestApp(this, handler = handler { FakeTransport.result(it, "null") })
        app.env.votes.vote("alice", "root", 10000)
        testScheduler.advanceUntilIdle()
        assertEquals(ErrorKind.AUTH, app.env.votes.current.statuses[key]?.error?.kind)
        assertNull(app.env.localVotes.get(key))

        signedIn(app)
        app.env.votes.vote("alice", "root", 10001)
        testScheduler.advanceUntilIdle()
        assertEquals(ErrorKind.INVALID_INPUT, app.env.votes.current.statuses[key]?.error?.kind)
        assertTrue(app.broadcasts().isEmpty())
    }

    @Test
    fun outOfResourceCreditsVoteAndReplyRaiseTheAppWideOffer() = runTest {
        val app = TestApp(this, handler = handler { FakeTransport.error(it, -32000, "Account: spike-voter has 12 RC, needs 3400 RC. Please wait to transact, or power up HIVE.") })
        signedIn(app)
        // RN upvotePopover: rolled back, no error toast, the offer instead
        app.env.votes.vote("alice", "root", 10000)
        testScheduler.advanceUntilIdle()
        assertNull(app.env.votes.current.statuses[key], "no error status to show")
        assertNull(app.env.localVotes.get(key))
        assertTrue(app.env.rcOffer.current.open)
        assertEquals(AppScreen.of(RouteName.REDEEM, "rc_topup"), app.env.rcOffer.choose(RcOfferChoice.TOP_UP))
        assertFalse(app.env.rcOffer.current.open)

        // a reply: the draft stays, no error line, the offer instead
        val post = app.env.postModel("alice", "root")
        post.start()
        testScheduler.advanceUntilIdle()
        val reply = app.env.replyModel(key, post.current.post!!)
        reply.setText("Nice post")
        reply.send()
        testScheduler.advanceUntilIdle()
        assertNull(reply.current.error)
        assertFalse(reply.current.isSending)
        assertEquals("Nice post", reply.current.text)
        assertTrue(app.env.rcOffer.current.open)
        assertEquals(AppScreen.of(RouteName.ACCOUNT_BOOST), app.env.rcOffer.choose(RcOfferChoice.BOOST))
        assertNull(app.env.ui.current.toast)
        post.close()
        reply.close()
    }

    @Test
    fun replyDraftIsReusedOnRetryAndShowsOnTopAfterSuccess() = runTest {
        var down = true
        val app = TestApp(this, handler = handler { if (down) HttpResult(503, "") else FakeTransport.result(it, "null") })
        signedIn(app)
        val post = app.env.postModel("alice", "root")
        post.start()
        testScheduler.advanceUntilIdle()
        val reply = app.env.replyModel(key, post.current.post!!)

        reply.send() // blank
        assertEquals(ErrorKind.INVALID_INPUT, reply.current.error?.kind)

        reply.setText("Nice post")
        reply.send()
        testScheduler.advanceUntilIdle()
        assertEquals(ErrorKind.NETWORK, reply.current.error?.kind)
        assertEquals(AppError.MAY_HAVE_LANDED_MESSAGE, reply.current.error?.message)
        assertEquals("Nice post", reply.current.text) // kept
        val permlink = assertNotNull(reply.current.draftPermlink)
        assertTrue(permlink.startsWith("re-alice-20261006t120000000z"), permlink)
        val firstTx = app.broadcasts().last().tx()

        // the clock moves on; a retry must not mint a new permlink or transaction
        app.now += 30_000
        down = false
        reply.send()
        testScheduler.advanceUntilIdle()
        assertNull(reply.current.error)
        assertEquals(firstTx, app.broadcasts().last().tx())
        val sent = assertNotNull(reply.current.sent)
        assertEquals(PostKey("spike-voter", permlink), sent)
        assertEquals("", reply.current.text)

        // the post screen shows it on top, marked local, before the API indexes it
        val top = post.current.comments.first()
        assertEquals(sent, top.key)
        assertTrue(top.isLocal)
        assertEquals("Nice post", top.post.body)
        post.close()
        reply.close()
    }

    @Test
    fun editedReplyAfterFailureKeepsThePermlink() = runTest {
        var down = true
        val app = TestApp(this, handler = handler { if (down) HttpResult(503, "") else FakeTransport.result(it, "null") })
        signedIn(app)
        val reply = app.env.replyModel(key, com.ecency.core.api.model.Post("alice", "root"))
        reply.setText("first")
        reply.send()
        testScheduler.advanceUntilIdle()
        val permlink = reply.current.draftPermlink
        app.now += 60_000
        reply.setText("first, edited")
        down = false
        reply.send()
        testScheduler.advanceUntilIdle()
        val comment = app.broadcasts().last().tx()["operations"]!!.jsonArray[0].jsonArray[1].jsonObject
        assertEquals(permlink, comment["permlink"]!!.jsonPrimitive.content)
        assertEquals("first, edited", comment["body"]!!.jsonPrimitive.content)
        reply.close()
    }
}
