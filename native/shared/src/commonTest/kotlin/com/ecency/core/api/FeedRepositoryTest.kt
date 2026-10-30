package com.ecency.core.api

import com.ecency.core.api.Fixtures.array
import com.ecency.core.api.Fixtures.entry
import com.ecency.core.api.repository.FeedRepository
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.api.repository.PostRepository
import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FeedRepositoryTest {
    private fun page(prefix: String, count: Int) = array(*Array(count) { entry("author$it", "$prefix-$it") })

    @Test
    fun rankedFeedRequestsAndCursor() = runTest {
        val pages = ArrayDeque(listOf(page("p1", 3), page("p2", 2), "[]"))
        val transport = FakeTransport { FakeTransport.result(it, pages.removeFirst()) }
        val feeds = FeedRepository(HiveApi(clientWith(transport)), pageSize = 3)
        val source = FeedSource.Ranked(RankedSort.TRENDING, tag = "hive-100001")

        val first = feeds.page(source)
        assertEquals(PageCursor("author2", "p1-2"), first.nextCursor)
        // No observer and no cursor: both keys are left out, as the app sends undefined.
        assertEquals(
            """{"sort":"trending","limit":3,"tag":"hive-100001"}""",
            transport.sent[0].params.toString(),
        )

        val second = feeds.page(source, first.nextCursor, observer = "bob")
        assertEquals(
            """{"sort":"trending","start_author":"author2","start_permlink":"p1-2","limit":3,"tag":"hive-100001","observer":"bob"}""",
            transport.sent[1].params.toString(),
        )
        // A short ranked page is not the end (the bridge filters muted posts); only an empty one is.
        assertTrue(second.hasMore)
        val third = feeds.page(source, second.nextCursor)
        assertEquals(emptyList(), third.posts)
        assertNull(third.nextCursor)
        assertEquals(List(3) { "bridge.get_ranked_posts" }, transport.methods())
    }

    @Test
    fun accountFeedEndsOnAShortPage() = runTest {
        val pages = ArrayDeque(listOf(page("b1", 2), page("b2", 1)))
        val transport = FakeTransport { FakeTransport.result(it, pages.removeFirst()) }
        val feeds = FeedRepository(HiveApi(clientWith(transport)), pageSize = 2)
        val source = FeedSource.Account("alice", AccountPostsSort.FEED)
        val first = feeds.page(source, observer = "alice")
        assertEquals(
            """{"sort":"feed","account":"alice","limit":2,"observer":"alice"}""",
            transport.sent[0].params.toString(),
        )
        assertTrue(first.hasMore)
        val second = feeds.page(source, first.nextCursor)
        assertEquals(1, second.posts.size)
        assertNull(second.nextCursor)
        assertEquals("bridge.get_account_posts", transport.sent[1].method)
    }

    @Test
    fun keepsTheBridgeOrderAndCursorFollowsTheLastRankedEntry() = runTest {
        // Bridge order deliberately not by date: display and cursor must keep it.
        val body = array(
            entry("alice", "pinned", created = "2026-01-01T00:00:00", pinned = true),
            entry("bob", "older", created = "2026-02-01T00:00:00"),
            entry("carol", "newer", created = "2026-03-01T00:00:00"),
        )
        val feeds = FeedRepository(HiveApi(clientWith(FakeTransport { FakeTransport.result(it, body) })), pageSize = 3)
        val page = feeds.page(FeedSource.Ranked(RankedSort.CREATED, "hive-100001"))
        assertEquals(listOf("pinned", "older", "newer"), page.posts.map { it.permlink })
        assertEquals(PageCursor("carol", "newer"), page.nextCursor)
    }

    @Test
    fun nullOrNonArrayResultIsAnEmptyPage() = runTest {
        for (result in listOf("null", "{}", "\"oops\"")) {
            val feeds = FeedRepository(HiveApi(clientWith(FakeTransport { FakeTransport.result(it, result) })))
            val page = feeds.page(FeedSource.Ranked(RankedSort.HOT))
            assertEquals(emptyList(), page.posts, result)
            assertNull(page.nextCursor)
        }
    }

    @Test
    fun crossPostsAreResolvedAndAFailedResolveKeepsTheWrapper() = runTest {
        val crossPostMeta = { orig: String -> """{"tags":["cross-post"],"original_author":"zed","original_permlink":"$orig"}""" }
        val transport = FakeTransport { req ->
            when (req.method) {
                "bridge.get_ranked_posts" -> FakeTransport.result(
                    req,
                    array(entry("alice", "cp-ok", metadata = crossPostMeta("orig")), entry("bob", "cp-gone", metadata = crossPostMeta("gone"))),
                )
                "bridge.get_post" ->
                    if (req.params.toString().contains("\"orig\"")) FakeTransport.result(req, entry("zed", "orig"))
                    else FakeTransport.error(req, -32602, "Assert Exception:Post zed/gone does not exist")
                else -> HttpResult(500, "")
            }
        }
        val feeds = FeedRepository(HiveApi(clientWith(transport)))
        val posts = feeds.page(FeedSource.Ranked(RankedSort.CREATED)).posts
        assertEquals("zed/orig", posts[0].crossPostOriginal?.key.toString())
        assertNull(posts[1].crossPostOriginal)
        assertEquals(listOf("alice", "bob"), posts.map { it.author })
    }

    @Test
    fun pagerAccumulatesDeduplicatesAndRefreshes() = runTest {
        val pages = ArrayDeque(
            listOf(
                array(entry("a", "1"), entry("a", "2")),
                array(entry("a", "2"), entry("a", "3")), // ranking shifted: "2" again
                "[]",
                array(entry("a", "9")),
            ),
        )
        val transport = FakeTransport { FakeTransport.result(it, pages.removeFirst()) }
        val pager = FeedRepository(HiveApi(clientWith(transport)), pageSize = 2).pager(FeedSource.Ranked(RankedSort.HOT))
        assertEquals(listOf("1", "2"), pager.loadMore().map { it.permlink })
        assertEquals(listOf("3"), pager.loadMore().map { it.permlink })
        assertEquals(emptyList(), pager.loadMore())
        assertTrue(pager.isEndReached)
        assertEquals(emptyList(), pager.loadMore()) // no request once the end is reached
        assertEquals(3, transport.sent.size)
        assertEquals(listOf("1", "2", "3"), pager.posts.map { it.permlink })
        assertEquals(listOf("9"), pager.refresh().map { it.permlink })
        assertEquals(listOf("9"), pager.posts.map { it.permlink })
        assertTrue("start_author" !in transport.sent.last().params.toString())
    }

    @Test
    fun feedPostsWarmThePostCache() = runTest {
        val transport = FakeTransport { FakeTransport.result(it, array(entry("alice", "hello"))) }
        val api = HiveApi(clientWith(transport))
        val posts = PostRepository(api)
        val feedPost = FeedRepository(api, posts).page(FeedSource.Ranked(RankedSort.TRENDING), observer = "bob").posts.single()
        assertSame(feedPost, posts.getPost("alice", "hello", observer = "bob"))
        assertEquals(1, transport.sent.size) // served from cache
    }
}
