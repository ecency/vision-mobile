package com.ecency.core.app

import com.ecency.core.api.EcencyApi
import com.ecency.core.api.RankedSort
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.api.AccountPostsSort
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The debug demo node: synthetic answers the app's own readers accept, and nothing that could reach a chain. */
class DemoHiveTransportTest {
    private val demo = DemoHiveTransport { 1_791_288_000L }
    private val api = EcencyApi(listOf("https://demo-hive.invalid"), demo)

    @Test
    fun aBroadcastIsRefusedAsTheChainWouldNotAsAnUnreachableNode() {
        // a 5xx may have reached a node (the send would wait as undecided); the demo's refusal is the chain's verdict
        for (method in listOf("condenser_api.broadcast_transaction", "condenser_api.broadcast_transaction_synchronous", "network_broadcast_api.broadcast_transaction")) {
            val r = demo.answer("""{"jsonrpc":"2.0","method":"$method","params":[{}],"id":7}""")
            assertEquals(200, r.status, method)
            assertTrue("\"error\"" in r.body && "\"id\":7" in r.body, "$method: ${r.body}")
        }
    }

    /** Delegate RC's reads (the iOS UI test draws them): 64 billion to delegate, no delegation yet. */
    @Test
    fun theRcDelegationReadsAcceptTheSyntheticAnswers() = runTest {
        val rc = kotlin.test.assertNotNull(api.hive.rcDelegator(DemoHiveTransport.AUTHORS[0]))
        assertEquals(64_000_000_000, rc.delegable(1_791_288_000L))
        assertEquals(0L, api.hive.rcDelegated(DemoHiveTransport.AUTHORS[0], DemoHiveTransport.AUTHORS[1]))
    }

    @Test
    fun theAppsReadersAcceptTheSyntheticAnswers() = runTest {
        val trending = api.feeds.page(FeedSource.Ranked(RankedSort.TRENDING), null, "spike-alice")
        assertEquals(6, trending.posts.size)
        assertTrue(trending.posts.all { it.author in DemoHiveTransport.AUTHORS })
        val following = api.feeds.page(FeedSource.Account("spike-alice", AccountPostsSort.FEED), null, "spike-alice")
        assertEquals(6, following.posts.size, "the Following feed of an account that exists nowhere else")
        assertNotNull(api.posts.getPost("spike-bob", "fake-post-2", "spike-alice"))
        val account = assertNotNull(api.accounts.getAccount("spike-carol"))
        assertEquals("Spike Carol", account.profile.displayName)
        assertNotNull(api.accounts.getProfile("spike-carol"))
        assertNull(api.accounts.getAccount("someone-real"), "only the made-up accounts exist")
    }

    /** The profile's Comments and Replies tabs list comments (replies to posts), not the account's posts. */
    @Test
    fun theProfilesCommentsAndRepliesTabsListComments() = runTest {
        val comments = api.feeds.page(FeedSource.Account("spike-bob", AccountPostsSort.COMMENTS), null, "spike-alice").posts
        assertTrue(comments.isNotEmpty(), "the Comments tab has demo comments")
        assertTrue(comments.all { it.author == "spike-bob" && !it.parentAuthor.isNullOrEmpty() && it.parentAuthor != "spike-bob" }, "comments of spike-bob on others' posts")
        assertEquals(listOf(false, false, true), comments.map { it.isMuted }, "the third is gray, for the muted row")
        val replies = api.feeds.page(FeedSource.Account("spike-alice", AccountPostsSort.REPLIES), null, "spike-alice").posts
        assertTrue(replies.isNotEmpty() && replies.all { it.author != "spike-alice" && it.parentAuthor == "spike-alice" }, "replies of others to spike-alice")
    }

    /** Opening a listed comment reads it again (get_post, get_discussion): the same comment, not a made-up root post. */
    @Test
    fun aListedCommentOpensAsTheSameComment() = runTest {
        val listed = api.feeds.page(FeedSource.Account("spike-bob", AccountPostsSort.COMMENTS), null, "spike-alice").posts +
            api.feeds.page(FeedSource.Account("spike-alice", AccountPostsSort.REPLIES), null, "spike-alice").posts
        for (c in listed) {
            val key = "${c.author}/${c.permlink}"
            val post = assertNotNull(api.hive.post(c.author, c.permlink, "spike-alice"), key)
            assertEquals(listOf(c.body, c.parentAuthor, c.parentPermlink, c.depth, c.children), listOf(post.body, post.parentAuthor, post.parentPermlink, post.depth, post.children), key)
            val thread = assertNotNull(api.hive.discussion(c.author, c.permlink, "spike-alice"), key)
            val head = assertNotNull(thread.firstOrNull { it.author == c.author && it.permlink == c.permlink }, key)
            assertEquals(listOf(c.body, c.parentAuthor, c.depth), listOf(head.body, head.parentAuthor, head.depth), key)
            val below = thread.filter { it !== head }
            assertEquals(c.children, below.size, "$key: its replies, and only them")
            assertTrue(below.all { it.parentAuthor == c.author && it.parentPermlink == c.permlink && it.depth == c.depth + 1 }, "$key: $below")
        }
    }

    /** Keys that only look like a demo comment's (no parent name, a signed number) open the generic post, as on iOS. */
    @Test
    fun onlyDemoCommentKeysOpenAsComments() = runTest {
        for (permlink in listOf("re-fake-post-1", "re-spike-alice-fake-post-+1", "re-spike-alice-fake-post-4", "re-spike-bob-fake-post-1")) {
            assertNull(assertNotNull(api.hive.post("spike-bob", permlink, "spike-alice"), permlink).parentAuthor, permlink)
        }
        assertEquals("spike-alice", api.hive.post("spike-bob", "re-spike-alice-fake-post-1", "spike-alice")?.parentAuthor)
    }

    @Test
    fun theDemoTransactionIsInABlockWithTheDemoTransfer() = runTest {
        // what a HiveAuth send looks up once the wallet answered: where the transaction is, then its operations
        assertEquals("within_irreversible_block", api.hive.transactionStatus(DemoWallet.DEMO_TX_ID, null))
        val ops = assertNotNull(api.hive.transactionOperations(DemoWallet.DEMO_TX_ID))
        assertTrue(SigningModel.sameOperations(DemoAuthServices.transferOps("spike-alice", "spike-bob"), ops), "$ops")
        // any other transaction is not known (an undecided send stays undecided)
        assertEquals("unknown", api.hive.transactionStatus("ab".repeat(20), null))
        assertNull(runCatching { api.hive.transactionOperations("ab".repeat(20)) }.getOrNull())
    }

    @Test
    fun nothingADemoRunDoesCanReachAChain() {
        // chain state is unavailable; broadcasts are refused with the chain's kind of error (no result, nothing sent)
        assertEquals(503, demo.answer("""{"jsonrpc":"2.0","id":1,"method":"condenser_api.get_dynamic_global_properties","params":[]}""").status)
        for (method in listOf("condenser_api.broadcast_transaction", "network_broadcast_api.broadcast_transaction")) {
            val body = demo.answer("""{"jsonrpc":"2.0","id":1,"method":"$method","params":[]}""").body
            assertTrue("\"error\"" in body && "\"result\"" !in body, method)
        }
        assertEquals(400, demo.answer("not json").status)
    }
}
