package com.ecency.core.api

import com.ecency.core.api.repository.FeedSource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Read-only calls against real Hive nodes, skipped unless ECENCY_LIVE_TEST=1.
 * Never broadcasts: only the TaPoS reference is computed, nothing is signed or sent.
 *
 *     ECENCY_LIVE_TEST=1 ./gradlew :shared:testAndroidHostTest --tests '*LiveSmokeTest*'
 */
class LiveSmokeTest {
    private val enabled = System.getenv("ECENCY_LIVE_TEST") == "1"

    @Test
    fun readsFeedPostDiscussionProfileAndChainHead() = runBlocking {
        if (!enabled) {
            println("LiveSmokeTest skipped (set ECENCY_LIVE_TEST=1 to run)")
            return@runBlocking
        }
        val ecency = EcencyApi(nodes = listOf("https://api.hive.blog") + com.ecency.core.api.rpc.HiveNodes.DEFAULT)
        val page = ecency.feeds.page(FeedSource.Ranked(RankedSort.TRENDING), limit = 3)
        assertTrue(page.posts.isNotEmpty())
        val next = ecency.feeds.page(FeedSource.Ranked(RankedSort.TRENDING), page.nextCursor, limit = 3)
        assertTrue(next.posts.none { it.key in page.posts.map { p -> p.key } }, "cursor is exclusive")
        val first = page.posts.first()
        assertNotNull(ecency.posts.getPost(first.author, first.permlink, refresh = true))
        val discussion = ecency.posts.getDiscussion(first.author, first.permlink)
        assertNotNull(discussion.root)
        assertNotNull(ecency.accounts.getProfile(first.author))
        assertNotNull(ecency.accounts.getAccount(first.author))
        val ref = ecency.broadcaster.reference()
        assertTrue(ref.refBlockPrefix > 0)
        println("LiveSmokeTest ok via ${ecency.client.currentNode}: ${page.posts.size + next.posts.size} posts, ${discussion.entries.size} discussion entries")
    }
}
