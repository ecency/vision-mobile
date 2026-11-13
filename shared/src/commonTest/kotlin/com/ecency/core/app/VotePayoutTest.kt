package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Fixtures.entry
import com.ecency.core.api.RankedSort
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * RN upvotePopover + voteCacheUtils: a vote moves the payout by its estimated value at once (added for an upvote,
 * subtracted for a downvote, the voter's share taken off for an unvote), on the feed card, the post header and a
 * comment; a failed vote puts the payout back, and chain data that already holds the vote is not counted twice.
 *
 * The voter's estimate is $0.250 (150000000 rshares) and every other rshare pays at the same rate, so the
 * fixture post (900000000 rshares from "x") pays $1.500 before the vote and $1.750 once the chain has it.
 */
class VotePayoutTest {
    private val user = TestApp.USER
    private val voteRshares = 150_000_000L

    /** The chain: [indexed] is whether the reader's vote is in the post's data yet; broadcasts wait for [gate]. */
    private class Chain {
        var indexed = false
        var serverVote = 0L
        var gate: CompletableDeferred<Unit>? = null
        var broadcast: (com.ecency.core.api.Sent) -> HttpResult = { FakeTransport.result(it, "null") }
        var pending = "1.500 HBD"
        var indexedPending = "1.750 HBD"
        var maxAccepted = "1000000.000 HBD"
    }

    private fun post(c: Chain, author: String = "alice", permlink: String = "root", parent: Pair<String, String>? = null) =
        rawPost(c, author, permlink, parent).replace("\"max_accepted_payout\":\"1000000.000 HBD\"", "\"max_accepted_payout\":\"${c.maxAccepted}\"")

    private fun rawPost(c: Chain, author: String, permlink: String, parent: Pair<String, String>?) =
        if (c.indexed) {
            // net_rshares is the sum of the votes' rshares, as on chain (the voter's share is read from it)
            entry(
                author, permlink, parent = parent, pending = c.indexedPending, votes = listOf("x", user),
                voteRshares = mapOf("x" to 900_000_000L, user to c.serverVote), netRshares = 900_000_000L + c.serverVote,
            )
        } else {
            entry(author, permlink, parent = parent, pending = c.pending, votes = listOf("x"), voteRshares = mapOf("x" to 900_000_000L), netRshares = 900_000_000L)
        }

    private fun app(scope: TestScope, c: Chain) = TestApp(scope) { req ->
        when (req.method) {
            "condenser_api.broadcast_transaction" -> { c.gate?.await(); c.broadcast(req) }
            "bridge.get_ranked_posts" -> FakeTransport.result(req, "[${post(c)}]")
            "bridge.get_post" -> FakeTransport.result(req, post(c))
            "bridge.get_discussion" -> FakeTransport.result(
                req, """{"alice/root":${post(c)},"bob/re":${post(c, "bob", "re", parent = "alice" to "root")}}""",
            )
            // the voter: 10000 VESTS at full mana; HF28 at a reserve rate of 10 gives 150000000 rshares, $0.250
            "condenser_api.get_accounts" -> FakeTransport.result(
                req, """[{"name":"$user","vesting_shares":"10000.000000 VESTS","voting_manabar":{"current_mana":"10000000000","last_update_time":${TestApp.NOW / 1000}}}]""",
            )
            "condenser_api.get_feed_history" -> FakeTransport.result(req, """{"current_median_history":{"base":"0.250 HBD","quote":"1.000 HIVE"}}""")
            "condenser_api.get_reward_fund" -> FakeTransport.result(req, """{"recent_claims":"120000000000000","reward_balance":"800000.000 HIVE"}""")
            "database_api.get_hardfork_properties" -> FakeTransport.result(req, """{"last_hardfork":28}""")
            else -> TestApp.chain(req) ?: HttpResult(500, "")
        }
    }

    /** Signed in, the feed and the post loaded, and the vote sheet's estimate read (as opening the sheet does). */
    private suspend fun TestScope.ready(app: TestApp): Pair<FeedModel, PostModel> {
        app.signIn()
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        val feed = app.env.feedModel(FeedSource.Ranked(RankedSort.TRENDING))
        val post = app.env.postModel("alice", "root")
        feed.start()
        post.start()
        testScheduler.advanceUntilIdle()
        val preview = assertNotNull(app.env.votePreview())
        assertEquals("$0.250", preview.text(10000))
        return feed to post
    }

    private fun FeedModel.payout() = current.items.single().payoutText
    private fun PostModel.payout() = current.card!!.payoutText
    private fun PostModel.commentPayout() = current.comments.single { it.key.permlink == "re" }.card.payoutText

    @Test
    fun anUpvoteAddsItsValueEverywhereAtOnceAndChainDataWithTheVoteIsNotCountedTwice() = runTest {
        val c = Chain().apply { gate = CompletableDeferred() }
        val app = app(this, c)
        val (feed, post) = ready(app)
        assertEquals("$1.500", feed.payout())
        assertEquals("$1.500", post.payout())

        app.env.votes.vote("alice", "root", 10000)
        testScheduler.runCurrent()
        // optimistic: before the broadcast ran
        assertEquals("$1.750", feed.payout())
        assertEquals("$1.750", post.payout())
        assertEquals(2, feed.current.items.single().votes)

        c.gate!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals("$1.750", feed.payout(), "a vote that landed keeps its value")

        // the chain now has the vote (the payout and the voter's rshares): shown once, not twice
        c.indexed = true
        c.serverVote = voteRshares
        feed.refresh()
        post.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals("$1.750", feed.payout())
        assertEquals("$1.750", post.payout())
        assertEquals(2, feed.current.items.single().votes)
        feed.close()
        post.close()
    }

    @Test
    fun aFailedVotePutsThePayoutBack() = runTest {
        val c = Chain().apply {
            gate = CompletableDeferred()
            broadcast = { HttpResult(503, "") }
        }
        val app = app(this, c)
        val (feed, post) = ready(app)
        app.env.votes.vote("alice", "root", 10000)
        testScheduler.runCurrent()
        assertEquals("$1.750", feed.payout())
        c.gate!!.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertNotNull(app.env.votes.current.statuses[com.ecency.core.api.model.PostKey("alice", "root")]?.error)
        assertEquals("$1.500", feed.payout())
        assertEquals("$1.500", post.payout())
        feed.close()
        post.close()
    }

    @Test
    fun aDownvoteSubtractsItsValueButNeverBelowZero() = runTest {
        val c = Chain().apply { gate = CompletableDeferred() }
        val app = app(this, c)
        val (feed, post) = ready(app)
        app.env.votes.vote("alice", "root", -10000)
        testScheduler.runCurrent()
        assertEquals("$1.250", feed.payout())
        assertEquals("$1.250", post.payout())
        feed.close()
        post.close()

        val small = Chain().apply { gate = CompletableDeferred(); pending = "0.100 HBD" }
        val app2 = app(this, small)
        val (feed2, post2) = ready(app2)
        app2.env.votes.vote("alice", "root", -10000)
        testScheduler.runCurrent()
        assertEquals("$0.000", feed2.payout())
        feed2.close()
        post2.close()
    }

    @Test
    fun removingAVoteTakesTheVotersShareOffUntilTheChainDropsIt() = runTest {
        // the reader's earlier vote is already on chain: $0.250 of the $1.750
        val c = Chain().apply { indexed = true; serverVote = voteRshares; gate = CompletableDeferred() }
        val app = app(this, c)
        val (feed, post) = ready(app)
        assertEquals("$1.750", feed.payout())
        app.env.votes.vote("alice", "root", 0)
        testScheduler.runCurrent()
        assertEquals("$1.500", feed.payout())
        assertEquals("$1.500", post.payout())
        // hivemind keeps a removed vote in total_votes: the count stays as the server will show it
        assertEquals(2, feed.current.items.single().votes)
        c.gate!!.complete(Unit)
        testScheduler.advanceUntilIdle()

        // the chain dropped it (the vote stays with 0 rshares): its payout is the truth again
        c.serverVote = 0
        c.indexedPending = "1.500 HBD"
        feed.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals("$1.500", feed.payout())
        feed.close()
        post.close()
    }

    @Test
    fun aCommentVoteMovesTheCommentsPayout() = runTest {
        val c = Chain().apply { gate = CompletableDeferred() }
        val app = app(this, c)
        val (feed, post) = ready(app)
        assertEquals("$1.500", post.commentPayout())
        app.env.votes.vote("bob", "re", 10000)
        testScheduler.runCurrent()
        assertEquals("$1.750", post.commentPayout())
        assertEquals("$1.500", post.payout(), "only the voted entry moves")
        feed.close()
        post.close()
    }

    @Test
    fun onceTheChainHoldsTheVoteAChangeFromAnotherClientShowsTheServersPayout() = runTest {
        val c = Chain()
        val app = app(this, c)
        val (feed, post) = ready(app)
        app.env.votes.vote("alice", "root", 10000)
        testScheduler.advanceUntilIdle()
        assertEquals("$1.750", feed.payout())

        // the chain has this app's vote
        c.indexed = true
        c.serverVote = voteRshares
        feed.refresh()
        post.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals("$1.750", feed.payout())

        // the reader changed the vote to 50 % on another client: the fresh payout is the truth, the old estimate is spent
        c.serverVote = voteRshares / 2
        c.indexedPending = "1.625 HBD"
        feed.refresh()
        post.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals("$1.625", feed.payout())
        assertEquals("$1.625", post.payout())
        feed.close()
        post.close()
    }

    @Test
    fun aVoteOnAPostThatDeclinedRewardsAddsNothingToItsPayout() = runTest {
        val c = Chain().apply { gate = CompletableDeferred(); pending = "0.000 HBD"; maxAccepted = "0.000 HBD" }
        val app = app(this, c)
        val (feed, post) = ready(app)
        assertEquals(true, feed.current.items.single().isPayoutDeclined)
        app.env.votes.vote("alice", "root", 10000)
        testScheduler.runCurrent()
        assertEquals("$0.000", feed.payout())
        assertEquals("$0.000", post.payout())
        assertEquals(2, feed.current.items.single().votes, "the vote itself still shows")
        feed.close()
        post.close()
    }
}
