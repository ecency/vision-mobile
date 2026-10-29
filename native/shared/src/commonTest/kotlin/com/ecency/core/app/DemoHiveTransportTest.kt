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
        for (method in listOf("condenser_api.broadcast_transaction", "condenser_api.get_dynamic_global_properties", "network_broadcast_api.broadcast_transaction")) {
            assertEquals(503, demo.answer("""{"jsonrpc":"2.0","id":1,"method":"$method","params":[]}""").status, method)
        }
        assertEquals(400, demo.answer("not json").status)
    }
}
