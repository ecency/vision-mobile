package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RN postDisplayView's tip count beside the gift in the post's action bar: `tipsQuery.data.meta.count` of
 * `GET /private-api/post-tips/{author}/{permlink}`, refetched after a tip from this screen.
 */
class PostTipsTest {
    @Test
    fun theCountComesFromThePostTipsEndpointAndShowsCompact() = runTest {
        val w = WalletWorld(this)
        val asked = mutableListOf<String>()
        w.http["/private-api/post-tips"] = { r -> asked += r.url; HttpResult(200, """{"meta":{"count":1234,"totals":{"HIVE":"1.000"}},"list":[]}""") }
        val m = w.env.postTipsModel("spike-author", "a-post")
        assertTrue(m.current.loading)
        assertNull(m.current.countText, "nothing beside the gift while loading")
        m.start()
        testScheduler.advanceUntilIdle()
        assertEquals(1234, m.current.count)
        assertEquals("1.2K", m.current.countText)
        assertEquals("1,234 tips", m.current.a11yCount)
        assertTrue(asked.single().endsWith("/private-api/post-tips/spike-author/a-post"), asked.toString())
    }

    @Test
    fun noTipsOrAFailedReadShowNoNumber() = runTest {
        val w = WalletWorld(this)
        w.http["/private-api/post-tips"] = { HttpResult(200, """{"meta":{"count":0,"totals":{}},"list":[]}""") }
        val zero = w.env.postTipsModel("spike-author", "a-post")
        zero.start()
        testScheduler.advanceUntilIdle()
        assertNull(zero.current.countText)
        assertEquals("0 tips", zero.current.a11yCount)
        w.http["/private-api/post-tips"] = { HttpResult(503, "") }
        val failed = w.env.postTipsModel("spike-author", "b-post")
        failed.start()
        testScheduler.advanceUntilIdle()
        assertTrue(!failed.current.loading)
        assertNull(failed.current.countText)
        assertNull(failed.current.a11yCount, "an unknown count is not said as zero")
        assertNull(failed.current.count)
        assertEquals("1 tip", PostTipsState(loading = false, count = 1).a11yCount)
        assertEquals("7", PostTipsState(loading = false, count = 7).countText)
    }

    @Test
    fun aTipSentToThePostRefreshesItsCount() = runTest {
        val w = WalletWorld(this)
        var count = 2
        w.http["/private-api/post-tips"] = { HttpResult(200, """{"meta":{"count":$count,"totals":{}},"list":[]}""") }
        val m = w.env.postTipsModel("spike-author", "a-post")
        val other = w.env.postTipsModel("spike-author", "other-post")
        m.start()
        other.start()
        testScheduler.advanceUntilIdle()
        assertEquals("2", m.current.countText)
        count = 3
        val tip = w.env.tipModel("spike-author", "a-post")
        tip.start()
        testScheduler.advanceUntilIdle()
        tip.setAmount("1")
        tip.send()
        tip.confirm()
        testScheduler.advanceUntilIdle()
        assertTrue(tip.current.closed)
        assertEquals("3", m.current.countText, "the tipped post reads its count again")
        assertEquals("2", other.current.countText, "another post keeps its count")
    }

    private suspend fun kotlinx.coroutines.test.TestScope.tip(w: WalletWorld) {
        val tip = w.env.tipModel("spike-author", "a-post")
        tip.start()
        testScheduler.advanceUntilIdle()
        tip.setAmount("1")
        tip.send()
        tip.confirm()
    }

    @Test
    fun aTipCountsAtOnceTheHigherStaysUntilTheFinalReadWhichWins() = runTest {
        val w = WalletWorld(this)
        var server = 2
        var reads = 0
        w.http["/private-api/post-tips"] = { reads++; HttpResult(200, """{"meta":{"count":$server,"totals":{}},"list":[]}""") }
        val m = w.env.postTipsModel("spike-author", "a-post")
        m.start()
        testScheduler.advanceUntilIdle()
        tip(w)
        testScheduler.advanceTimeBy(PostTipsModel.CONFIRM_DELAY_MS + 500)
        testScheduler.runCurrent()
        assertEquals("3", m.current.countText, "the first confirm read lags: the sent tip stays counted")
        val before = reads
        testScheduler.advanceUntilIdle()
        assertEquals(before + 1, reads, "one final confirm read")
        assertEquals("2", m.current.countText, "the final read is the server's word, even lower")
        // a server that caught up and more wins at once
        server = 6
        tip(w)
        testScheduler.advanceUntilIdle()
        assertEquals("6", m.current.countText)
    }

    @Test
    fun aTipWhileTheCountIsUnknownReadsInsteadOfCountingOne() = runTest {
        val w = WalletWorld(this)
        var up = false
        w.http["/private-api/post-tips"] = { if (up) HttpResult(200, """{"meta":{"count":7,"totals":{}},"list":[]}""") else HttpResult(503, "") }
        val m = w.env.postTipsModel("spike-author", "a-post")
        m.start()
        testScheduler.advanceUntilIdle()
        assertNull(m.current.count)
        tip(w)
        testScheduler.advanceUntilIdle()
        assertNull(m.current.countText, "no made up 1 over an unknown count")
        up = true
        tip(w)
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertEquals("7", m.current.countText, "read at once, not after the confirm delay")
    }

    @Test
    fun theOptimisticCountShowsBeforeTheConfirmRead() = runTest {
        val w = WalletWorld(this)
        w.http["/private-api/post-tips"] = { HttpResult(200, """{"meta":{"count":2,"totals":{}},"list":[]}""") }
        val m = w.env.postTipsModel("spike-author", "a-post")
        m.start()
        testScheduler.advanceUntilIdle()
        tip(w)
        // the broadcast lands, the confirm read waits
        testScheduler.advanceTimeBy(PostTipsModel.CONFIRM_DELAY_MS - 100)
        testScheduler.runCurrent()
        assertEquals("3", m.current.countText)
    }
}
