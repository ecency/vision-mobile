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
