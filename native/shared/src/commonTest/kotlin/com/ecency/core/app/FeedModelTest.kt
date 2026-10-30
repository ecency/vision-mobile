package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Fixtures.array
import com.ecency.core.api.Fixtures.entry
import com.ecency.core.api.RankedSort
import com.ecency.core.api.model.PostKey
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeedModelTest {
    private val source = FeedSource.Ranked(RankedSort.TRENDING)

    private fun page(prefix: String, count: Int) = array(
        *Array(count) {
            entry(
                "author$it", "$prefix-$it", pending = "1.234 HBD",
                votes = listOf("x$it", "spike-voter").take(if (it == 0) 2 else 1),
                metadata = """{"tags":["test"],"image":["https://example.com/$prefix-$it.jpg"],"description":"Card $it of $prefix"}""",
            )
        },
    )

    @Test
    fun pagesAppendUntilAnEmptyPage() = runTest {
        val pages = ArrayDeque(listOf(page("p1", 10), page("p2", 4), "[]"))
        val app = TestApp(this) { FakeTransport.result(it, pages.removeFirst()) }
        val feed = app.env.feedModel(source)
        val seen = mutableListOf<FeedState>()
        val watch = feed.watch { seen += it }

        feed.start()
        assertTrue(feed.current.isLoading)
        testScheduler.advanceUntilIdle()
        assertEquals(10, feed.current.items.size)
        assertTrue(feed.current.loaded)
        assertFalse(feed.current.isLoading)

        feed.loadMore()
        assertTrue(feed.current.isLoadingMore)
        testScheduler.advanceUntilIdle()
        assertEquals(14, feed.current.items.size)
        assertFalse(feed.current.endReached)

        feed.loadMore()
        testScheduler.advanceUntilIdle()
        assertTrue(feed.current.endReached)
        assertEquals(14, feed.current.items.size)

        feed.loadMore() // at the end: no request
        testScheduler.advanceUntilIdle()
        assertEquals(3, app.transport.sent.size)
        val cursor = app.transport.sent[1].params.jsonObject
        assertEquals("p1-9", cursor["start_permlink"]?.jsonPrimitive?.content)

        // watch delivered the initial state and every change, and stops after close
        assertTrue(seen.first().items.isEmpty())
        assertEquals(14, seen.last().items.size)
        watch.close()
        assertTrue(watch.isClosed)
        feed.close()
    }

    @Test
    fun cardData() = runTest {
        val app = TestApp(this) { FakeTransport.result(it, page("p", 1)) }
        app.signIn()
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        val feed = app.env.feedModel(source)
        feed.start()
        testScheduler.advanceUntilIdle()
        val card = feed.current.items.single()
        assertEquals(PostKey("author0", "p-0"), card.key)
        assertEquals("https://i.ecency.com/u/author0/avatar/128", card.avatarUrl)
        assertEquals(50, card.reputation)
        assertEquals("Title of p-0", card.title)
        assertEquals("Card 0 of p", card.summary)
        assertTrue(card.image!!.startsWith("https://i.ecency.com/p/"), card.image)
        assertTrue(card.image!!.endsWith("width=600&height=500"), card.image)
        assertEquals("$1.234", card.payoutText)
        assertEquals(2, card.votes)
        assertEquals(0, card.comments)
        assertTrue(card.votedByMe)
        assertEquals(1_790_848_800_000L, card.createdEpochMillis) // 2026-10-01T10:00:00Z
        assertEquals("5d", TimeAgo.short(card.createdEpochMillis, TestApp.NOW))
        // the observer is the signed-in account
        assertEquals("spike-voter", app.transport.sent.last().params.jsonObject["observer"]?.jsonPrimitive?.content)
        feed.close()
    }

    @Test
    fun firstPageErrorThenRetry() = runTest {
        var fail = true
        val app = TestApp(this) { if (fail) HttpResult(503, "down") else FakeTransport.result(it, page("p", 3)) }
        val feed = app.env.feedModel(source)
        feed.start()
        testScheduler.advanceUntilIdle()
        val error = assertNotNull(feed.current.error)
        assertEquals(ErrorKind.NETWORK, error.kind)
        assertTrue(error.retryable)
        assertTrue(feed.current.items.isEmpty())
        assertFalse(feed.current.loaded)

        fail = false
        feed.retry()
        testScheduler.advanceUntilIdle()
        assertNull(feed.current.error)
        assertEquals(3, feed.current.items.size)
        feed.close()
    }

    @Test
    fun loadMoreErrorKeepsItemsAndRetryContinuesFromTheCursor() = runTest {
        var calls = 0
        val app = TestApp(this) {
            calls++
            when (calls) {
                1 -> FakeTransport.result(it, page("p1", 10))
                in 2..7 -> HttpResult(503, "down") // every attempt: three nodes, twice (SDK retry 5)
                else -> FakeTransport.result(it, page("p2", 2))
            }
        }
        val feed = app.env.feedModel(source)
        feed.start()
        testScheduler.advanceUntilIdle()
        feed.loadMore()
        testScheduler.advanceUntilIdle()
        assertEquals(10, feed.current.items.size)
        assertEquals(ErrorKind.NETWORK, feed.current.error?.kind)

        feed.loadMore() // blocked while the error shows
        testScheduler.advanceUntilIdle()
        assertEquals(7, calls)

        feed.retry()
        testScheduler.advanceUntilIdle()
        assertEquals(12, feed.current.items.size)
        assertEquals("p1-9", app.transport.sent.last().params.jsonObject["start_permlink"]?.jsonPrimitive?.content)
        feed.close()
    }

    @Test
    fun refreshReplacesItemsAndKeepsThemVisibleMeanwhile() = runTest {
        var prefix = "old"
        val app = TestApp(this) { FakeTransport.result(it, page(prefix, 2)) }
        val feed = app.env.feedModel(source)
        feed.start()
        testScheduler.advanceUntilIdle()
        prefix = "new"
        feed.refresh()
        assertTrue(feed.current.isRefreshing)
        assertEquals(2, feed.current.items.size) // still shown
        testScheduler.advanceUntilIdle()
        assertFalse(feed.current.isRefreshing)
        assertEquals(listOf("new-0", "new-1"), feed.current.items.map { it.key.permlink })
        // a refresh starts from the top
        assertNull(app.transport.sent.last().params.jsonObject["start_permlink"])
        feed.close()
    }

    @Test
    fun failedRefreshRetriesFromTheTop() = runTest {
        var down = false
        val app = TestApp(this) { if (down) HttpResult(503, "") else FakeTransport.result(it, page("p", 10)) }
        val feed = app.env.feedModel(source)
        feed.start()
        testScheduler.advanceUntilIdle()
        down = true
        feed.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(10, feed.current.items.size)
        assertNotNull(feed.current.error)
        down = false
        feed.retry()
        testScheduler.advanceUntilIdle()
        assertEquals(10, feed.current.items.size) // replaced, not appended
        assertNull(app.transport.sent.last().params.jsonObject["start_permlink"])
        feed.close()
    }

    @Test
    fun cardsAreBuiltOffTheMainThread() = runTest {
        val app = TestApp(this) { FakeTransport.result(it, page("p", 3)) }
        val feed = app.env.feedModel(source)
        feed.start()
        testScheduler.advanceUntilIdle()
        assertEquals(3, feed.current.items.size)
        assertEquals(emptyList(), app.renderer.offRenderCalls)
        assertTrue(app.render.dispatches > 0)
        feed.close()
    }
}
