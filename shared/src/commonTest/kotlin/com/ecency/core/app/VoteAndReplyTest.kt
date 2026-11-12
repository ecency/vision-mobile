package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Fixtures
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
import kotlin.test.assertNotEquals
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

    /**
     * Live test: a vote of an account with little HP has no payout weight, so the bridge lists it with rshares 0, the
     * same as a removed vote. The post screen asks the chain for that account's vote alone (database_api.list_votes)
     * and shows the vote it holds.
     */
    @Test
    fun aVoteWithoutPayoutWeightShowsAsVotedOnThePost() = runTest {
        for ((percent, voted) in listOf(100 to true, 0 to false)) {
            val asked = mutableListOf<String>()
            val zero = mapOf(TestApp.USER to 0L)
            val app = TestApp(this, handler = { req ->
                when (req.method) {
                    "bridge.get_post" -> FakeTransport.result(req, entry("alice", "root", votes = listOf(TestApp.USER), voteRshares = zero))
                    "bridge.get_discussion" -> FakeTransport.result(req, """{"alice/root":${entry("alice", "root", votes = listOf(TestApp.USER), voteRshares = zero)}}""")
                    "database_api.list_votes" -> {
                        asked += req.params.toString()
                        listVotes(req, mapOf(TestApp.USER to percent))
                    }
                    else -> TestApp.chain(req) ?: HttpResult(500, "")
                }
            })
            signedIn(app)
            val post = app.env.postModel("alice", "root")
            post.start()
            testScheduler.advanceUntilIdle()
            assertEquals(voted, post.current.card?.votedByMe, "percent $percent")
            assertEquals(1, post.current.card?.votes, "one voter either way")
            assertEquals(1, asked.size, "asked once, for this post")
            assertTrue("alice" in asked.single() && "root" in asked.single())
        }
    }

    @Test
    fun anotherAccountsWeightlessVoteIsLookedUpAfterASwitch() = runTest {
        // review: the lookup was remembered per post only, so the next account's vote on an open post was never asked
        val bob = "spike-bob"
        val zero = mapOf(TestApp.USER to 0L, bob to 0L)
        val app = TestApp(this, handler = { req ->
            when (req.method) {
                "bridge.get_post" -> FakeTransport.result(req, entry("alice", "root", votes = listOf(TestApp.USER, bob), voteRshares = zero))
                "bridge.get_discussion" -> FakeTransport.result(req, """{"alice/root":${entry("alice", "root", votes = listOf(TestApp.USER, bob), voteRshares = zero)}}""")
                // the first account's vote was removed (percent 0), the second's is a weightless upvote
                "database_api.list_votes" -> listVotes(req, mapOf(TestApp.USER to 0, bob to 100))
                else -> TestApp.chain(req) ?: HttpResult(500, "")
            }
        })
        app.signIn(bob)
        signedIn(app)
        val post = app.env.postModel("alice", "root")
        post.start()
        testScheduler.advanceUntilIdle()
        assertEquals(false, post.current.card?.votedByMe)
        app.env.session.switchTo(bob)
        testScheduler.advanceUntilIdle()
        assertEquals(true, post.current.card?.votedByMe, "the second account's vote")
    }

    /**
     * `database_api.list_votes` answering the lookup of the voter [req] asks for on alice/root: that voter's row with
     * its percent from [percents], or, for anyone else, the next row of the index (another post's), as the seek does.
     */
    private fun listVotes(req: Sent, percents: Map<String, Int>): HttpResult {
        val voter = req.params.jsonObject.getValue("start").jsonArray[2].jsonPrimitive.content
        val row = percents[voter]?.let { """{"voter":"$voter","author":"alice","permlink":"root","rshares":0,"vote_percent":$it,"weight":0,"last_update":"2026-10-01T10:00:00"}""" }
            ?: """{"voter":"spike-a","author":"alice","permlink":"root-2","rshares":5,"vote_percent":100,"weight":0,"last_update":"2026-10-01T10:00:00"}"""
        return FakeTransport.result(req, """{"votes":[$row]}""")
    }

    /** A post whose bridge copy lists [voters] with rshares 0; the chain answers [percents] (null: fails). */
    private fun weightless(voters: List<String>, percents: () -> Map<String, Int>?, asked: MutableList<String> = mutableListOf()) =
        { req: Sent ->
            val zero = voters.associateWith { 0L }
            when (req.method) {
                "bridge.get_post" -> FakeTransport.result(req, entry("alice", "root", votes = voters, voteRshares = zero))
                "bridge.get_discussion" -> FakeTransport.result(req, """{"alice/root":${entry("alice", "root", votes = voters, voteRshares = zero)}}""")
                "database_api.list_votes" -> {
                    asked += req.params.toString()
                    percents()?.let { listVotes(req, it) } ?: HttpResult(500, "")
                }
                else -> TestApp.chain(req) ?: HttpResult(500, "")
            }
        }

    @Test
    fun aWeightlessVoteRemovedElsewhereGoesOnRefresh() = runTest {
        var percent = 100
        val app = TestApp(this, handler = { req -> weightless(listOf(TestApp.USER), { mapOf(TestApp.USER to percent) })(req) })
        signedIn(app)
        val post = app.env.postModel("alice", "root")
        post.start()
        testScheduler.advanceUntilIdle()
        assertEquals(true, post.current.card?.votedByMe)
        // removed on another client: the chain keeps the voter with percent 0
        percent = 0
        post.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(false, post.current.card?.votedByMe, "the refresh follows the chain")
    }

    @Test
    fun aWeightlessVoteRemovedWhileThePostWasClosedGoesWhenItIsOpenedAgain() = runTest {
        // review: the overlay is app-wide, but which screen set it was known only to that screen
        var percent = 100
        val app = TestApp(this, handler = { req -> weightless(listOf(TestApp.USER), { mapOf(TestApp.USER to percent) })(req) })
        signedIn(app)
        val first = app.env.postModel("alice", "root")
        first.start()
        testScheduler.advanceUntilIdle()
        assertEquals(true, first.current.card?.votedByMe)
        first.close()
        percent = 0
        val again = app.env.postModel("alice", "root")
        again.start()
        testScheduler.advanceUntilIdle()
        assertEquals(false, again.current.card?.votedByMe, "a new screen asks the chain again")
    }

    @Test
    fun aVoteMadeOnTheDeviceIsNeverTakenForTheChainsOne() = runTest {
        // review: a device vote without an estimate and of the same weight read as the screen's own overlay, and a
        // lagging lookup then took it away
        var percent = 100
        val app = TestApp(this, handler = { req ->
            if (req.method == "condenser_api.broadcast_transaction") FakeTransport.result(req, "null")
            else weightless(listOf(TestApp.USER), { mapOf(TestApp.USER to percent) })(req)
        })
        signedIn(app)
        val post = app.env.postModel("alice", "root")
        post.start()
        testScheduler.advanceUntilIdle()
        assertEquals(true, post.current.card?.votedByMe)
        // removed elsewhere, then voted again here at the same weight before the node caught up
        percent = 0
        app.env.votes.vote("alice", "root", 100)
        testScheduler.advanceUntilIdle()
        post.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(true, post.current.card?.votedByMe, "the vote made on this device stands")
    }

    @Test
    fun aWeightlessVoteShowsAgainAfterSwitchingAwayAndBack() = runTest {
        val bob = "spike-bob"
        val app = TestApp(this, handler = { req -> weightless(listOf(TestApp.USER), { mapOf(TestApp.USER to 100) })(req) })
        app.signIn(bob)
        signedIn(app)
        val post = app.env.postModel("alice", "root")
        post.start()
        testScheduler.advanceUntilIdle()
        assertEquals(true, post.current.card?.votedByMe)
        app.env.session.switchTo(bob)
        testScheduler.advanceUntilIdle()
        assertEquals(false, post.current.card?.votedByMe)
        app.env.session.switchTo(TestApp.USER)
        testScheduler.advanceUntilIdle()
        assertEquals(true, post.current.card?.votedByMe, "back on the first account")
    }

    @Test
    fun aFailedWeightlessLookupIsAskedAgainOnRefresh() = runTest {
        var down = true
        val app = TestApp(this, handler = { req -> weightless(listOf(TestApp.USER), { if (down) null else mapOf(TestApp.USER to 100) })(req) })
        signedIn(app)
        val post = app.env.postModel("alice", "root")
        post.start()
        testScheduler.advanceUntilIdle()
        assertEquals(false, post.current.card?.votedByMe)
        down = false
        post.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(true, post.current.card?.votedByMe)
    }

    @Test
    fun aVoteWithPayoutWeightAsksNothingMore() = runTest {
        var asked = 0
        val app = TestApp(this, handler = { req ->
            when (req.method) {
                "bridge.get_post" -> FakeTransport.result(req, entry("alice", "root", votes = listOf(TestApp.USER)))
                "bridge.get_discussion" -> FakeTransport.result(req, """{"alice/root":${entry("alice", "root", votes = listOf(TestApp.USER))}}""")
                "database_api.list_votes" -> { asked++; FakeTransport.result(req, """{"votes":[]}""") }
                else -> TestApp.chain(req) ?: HttpResult(500, "")
            }
        })
        signedIn(app)
        app.env.postModel("alice", "root").start()
        testScheduler.advanceUntilIdle()
        assertEquals(0, asked)
    }

    @Test
    fun aSecondReplyGetsItsOwnTimeToLand() = runTest {
        // review: a reply sent late in another one's watch inherited what was left of it and could stay "sending"
        val landed = mutableSetOf<String>()
        val app = TestApp(this) { req ->
            when (req.method) {
                "condenser_api.broadcast_transaction" -> FakeTransport.result(req, "null")
                "database_api.find_comments" -> TestApp.commentAsked(req).second.let { permlink ->
                    TestApp.comments(req, permlink.takeIf { it in landed }?.let { entry(TestApp.USER, it, parent = "alice" to "root") })
                }
                "bridge.get_post" -> FakeTransport.result(req, entry("alice", "root"))
                "bridge.get_discussion" -> FakeTransport.result(req, """{"alice/root":${entry("alice", "root")}}""")
                else -> TestApp.chain(req) ?: HttpResult(500, "")
            }
        }
        // reply permlinks carry the time: the clock follows the virtual time
        app.clock = { TestApp.NOW + testScheduler.currentTime }
        signedIn(app)
        val post = app.env.postModel("alice", "root")
        post.start()
        testScheduler.advanceUntilIdle()
        val first = app.env.replyModel(key, post.current.post!!)
        first.setText("First, never lands")
        first.send()
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertNotNull(first.current.sent)
        // late in the first one's window, a second reply to the same post
        testScheduler.advanceTimeBy(70_000)
        testScheduler.runCurrent()
        val second = app.env.replyModel(key, post.current.post!!)
        second.setText("Second")
        second.send()
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        val sent = assertNotNull(second.current.sent)
        assertNotEquals(first.current.sent, sent)
        // it lands after the first window would have ended
        testScheduler.advanceTimeBy(15_000)
        testScheduler.runCurrent()
        landed += sent.permlink
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()
        assertFalse(post.current.comments.first { it.key == sent }.isSending, "the second reply's own window")
    }

    @Test
    fun theSendingWatchNeverCancelsAPullToRefresh() = runTest {
        // review: the watch's cached reload used to cancel a user's refresh in flight, which then showed stale replies
        var onChain: String? = null
        var indexed = false
        var gate: CompletableDeferred<Unit>? = null
        val app = TestApp(this) { req ->
            when (req.method) {
                "condenser_api.broadcast_transaction" -> FakeTransport.result(req, "null")
                "database_api.find_comments" -> TestApp.comments(req, onChain?.takeIf { TestApp.commentAsked(req).second == it }?.let { entry(TestApp.USER, it, parent = "alice" to "root") })
                "bridge.get_post" -> FakeTransport.result(req, entry("alice", "root"))
                "bridge.get_discussion" -> {
                    gate?.await()
                    FakeTransport.result(
                        req,
                        if (indexed && onChain != null) """{"alice/root":${entry("alice", "root", replies = listOf("${TestApp.USER}/$onChain"))},"${TestApp.USER}/$onChain":${entry(TestApp.USER, onChain!!, parent = "alice" to "root")}}"""
                        else """{"alice/root":${entry("alice", "root")}}""",
                    )
                }
                else -> TestApp.chain(req) ?: HttpResult(500, "")
            }
        }
        signedIn(app)
        val post = app.env.postModel("alice", "root")
        post.start()
        testScheduler.advanceUntilIdle()
        val reply = app.env.replyModel(key, post.current.post!!)
        reply.setText("Nice post")
        reply.send()
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        val sent = assertNotNull(reply.current.sent)
        // the reply lands and hivemind has it; the user pulls to refresh, and the read is slow
        onChain = sent.permlink
        indexed = true
        gate = CompletableDeferred()
        post.refresh()
        testScheduler.runCurrent()
        // the watch finds the reply on chain while the refresh is still reading
        testScheduler.advanceTimeBy(4_000)
        testScheduler.runCurrent()
        gate!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        val served = post.current.comments.single()
        assertEquals(sent, served.key)
        assertFalse(served.isLocal, "the refresh's answer, not the cache")
    }

    @Test
    fun aRefusedVoteIsOneHandledReportAtPostVote() = runTest {
        val app = TestApp(this, handler = handler { FakeTransport.error(it, -32000, "Assert Exception: voting too often") })
        val reporter = RecordingReporter()
        app.env.crashReporter = reporter
        signedIn(app)
        app.env.votes.vote("alice", "root", 10000)
        testScheduler.advanceUntilIdle()
        assertNotNull(app.env.votes.current.statuses[key]?.error, "the row shows the failure as before")
        // the fixture's unanswered side reads report quietly; the failure the screen shows is one handled report
        val r = reporter.reports.filter { it.tags["quiet"] == null }.single()
        assertEquals("post.vote", r.tags["where"])
        assertEquals("rejected", r.tags["error.kind"])
        assertEquals("error", r.level)
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

    /**
     * Live test: a reply kept "sending to the chain" for more than 75 s after it was on chain, until hivemind returned
     * it. Once the chain holds it (`database_api.find_comments`, chain state) it shows as posted; the local copy stays
     * on top until the discussion has it.
     */
    @Test
    fun aReplyOnChainStopsSayingSendingBeforeHivemindHasIt() = runTest {
        var onChain: String? = null
        var indexed = false
        val app = TestApp(this) { req ->
            when (req.method) {
                "condenser_api.broadcast_transaction" -> FakeTransport.result(req, "null")
                "database_api.find_comments" -> TestApp.comments(req, onChain?.takeIf { TestApp.commentAsked(req).second == it }?.let { entry(TestApp.USER, it, parent = "alice" to "root") })
                "bridge.get_post" -> FakeTransport.result(req, entry("alice", "root"))
                "bridge.get_discussion" -> FakeTransport.result(
                    req,
                    if (indexed && onChain != null) """{"alice/root":${entry("alice", "root", replies = listOf("${TestApp.USER}/$onChain"))},"${TestApp.USER}/$onChain":${entry(TestApp.USER, onChain!!, parent = "alice" to "root")}}"""
                    else """{"alice/root":${entry("alice", "root")}}""",
                )
                else -> TestApp.chain(req) ?: HttpResult(500, "")
            }
        }
        signedIn(app)
        val post = app.env.postModel("alice", "root")
        post.start()
        testScheduler.advanceUntilIdle()
        val reply = app.env.replyModel(key, post.current.post!!)
        reply.setText("Nice post")
        reply.send()
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        val sent = assertNotNull(reply.current.sent)
        val local = post.current.comments.first()
        assertEquals(sent, local.key)
        assertTrue(local.isLocal)
        assertTrue(local.isSending, "not on chain yet")

        onChain = sent.permlink
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()
        val confirmed = post.current.comments.first()
        assertEquals(sent, confirmed.key)
        assertTrue(confirmed.isLocal, "the local copy stays until hivemind has it")
        assertFalse(confirmed.isSending, "on chain: posted, not sending")

        indexed = true
        post.refresh()
        testScheduler.advanceUntilIdle()
        val served = post.current.comments.single()
        assertEquals(sent, served.key)
        assertFalse(served.isLocal)
        assertFalse(served.isSending)
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

    @Test
    fun aVoteBeyondTheFirst1000ShowsThroughV2() = runTest {
        // #3671 acceptance: v2 answers the observer's own vote directly, however many voted before it
        val voted = Fixtures.v2(entry("alice", "root"), totalVotes = 1500, observerVote = """{"rshares":12,"percent":2500}""")
        val app = TestApp(this, handler = { req ->
            when (req.method) {
                "bridge.get_post_v2" -> FakeTransport.result(req, voted)
                "bridge.get_discussion_v2" -> FakeTransport.result(req, """{"alice/root":$voted}""")
                else -> TestApp.chain(req) ?: HttpResult(500, "")
            }
        })
        signedIn(app)
        val post = app.env.postModel("alice", "root")
        post.start()
        testScheduler.advanceUntilIdle()
        assertEquals(true, post.current.card?.votedByMe)
        assertEquals(1500, post.current.card?.votes)
        // known from the read: no lookup
        assertTrue(app.transport.sent.none { it.method == "database_api.list_votes" })
        post.close()
    }

    @Test
    fun aVoteBeyondTheV1CapIsLookedUpOnTheChain() = runTest {
        // a node without v2: 1000 listed voters without the observer say nothing, so the chain is asked
        val crowd = (0 until 1000).map { "spike-v" + it.toString().padStart(4, '0') }
        val listed = entry("alice", "root", votes = crowd)
        val asked = mutableListOf<String>()
        for ((row, voted) in listOf(TestApp.USER to true, "spike-zz" to false)) {
            asked.clear()
            val app = TestApp(this, handler = { req ->
                when (req.method) {
                    "bridge.get_post" -> FakeTransport.result(req, listed)
                    "bridge.get_discussion" -> FakeTransport.result(req, """{"alice/root":$listed}""")
                    // a lower-bound seek: an account that did not vote gets the next row
                    "database_api.list_votes" -> {
                        asked += req.params.toString()
                        FakeTransport.result(req, """{"votes":[{"voter":"$row","author":"alice","permlink":"root","rshares":9,"vote_percent":3000,"weight":1,"last_update":"2026-10-01T10:00:00"}]}""")
                    }
                    else -> TestApp.chain(req) ?: HttpResult(500, "")
                }
            })
            signedIn(app)
            val post = app.env.postModel("alice", "root")
            post.start()
            testScheduler.advanceUntilIdle()
            assertEquals(voted, post.current.card?.votedByMe, row)
            assertEquals("""{"start":["alice","root","${TestApp.USER}"],"limit":1,"order":"by_comment_voter"}""", asked.single())
            post.close()
        }
    }

    /** A v1 entry read for spike-voter: [rows] voters (spike-voter among them when [listed], with no weight), [total] counted. */
    private fun v1Post(listed: Boolean, total: Int, rows: Int = 3): com.ecency.core.api.model.Post {
        val others = (1..rows).map { """{"voter":"spike-other$it","rshares":1000,"percent":10000}""" }
        val mine = if (listed) listOf("""{"voter":"spike-voter","rshares":0}""") else emptyList()
        val json = """{"author":"spike-author","permlink":"p","stats":{"total_votes":$total},"active_votes":[${(others + mine).joinToString(",")}]}"""
        return com.ecency.core.api.model.Post.fromJson(kotlinx.serialization.json.Json.parseToJsonElement(json), observer = "spike-voter")!!
    }

    @Test
    fun aWeightlessVoteMadeHereIsCountedOnceOnANodeWithoutV2() {
        // the vote landed: the v1 list holds the voter's weightless row (unknown for the button) and the count has it
        val post = v1Post(listed = true, total = 4)
        assertFalse(post.knowsVoteOf("spike-voter"))
        assertEquals(4, LocalVotes.view(post, "spike-voter", LocalVote(10000, pending = false)).votes, "never twice")
        // removed here: hivemind keeps a removed vote in total_votes, so nothing comes off
        assertEquals(4, LocalVotes.view(post, "spike-voter", LocalVote(0, pending = false)).votes)
        // not in the list yet: the vote is added
        assertEquals(4, LocalVotes.view(v1Post(listed = false, total = 3), "spike-voter", LocalVote(10000, pending = false)).votes)
    }

    @Test
    fun aVoteChangedOverOneReadFromTheChainIsNotCountedAgain() {
        // a cut list (1000 rows) without the voter: the read cannot tell, but the replaced vote was read from the chain
        val post = v1Post(listed = false, total = 1500, rows = 1000)
        assertFalse(post.knowsVoteOf("spike-voter"))
        assertEquals(1500, LocalVotes.view(post, "spike-voter", LocalVote(5000, pending = false, earlierCounted = true)).votes)
        assertEquals(1501, LocalVotes.view(post, "spike-voter", LocalVote(5000, pending = false)).votes, "a first vote is added")
    }

    @Test
    fun aVoteOverOneReadFromTheChainRemembersItIsCounted() = runTest {
        val app = TestApp(this, handler = handler { FakeTransport.result(it, "null") })
        signedIn(app)
        // read from the chain by the post screen
        app.env.localVotes.set(key, LocalVote(10000, pending = false, fromChain = true))
        app.env.votes.vote("alice", "root", 5000)
        testScheduler.advanceUntilIdle()
        assertEquals(true, app.env.localVotes.get(key)?.earlierCounted, "the weight change is not a new vote in the count")
        // over a vote made here: a card's older read may not have it yet, so it is not taken as counted
        app.env.votes.vote("alice", "root", 3000)
        testScheduler.advanceUntilIdle()
        assertEquals(false, app.env.localVotes.get(key)?.earlierCounted)
    }
}
