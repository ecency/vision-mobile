package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.rpc.HiveNodes
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.hive.ChainTime
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The node pool at start and the settings server picker (RN initSdkConfig, getNodes, `_changeApi`). */
class HiveNodeSettingsTest {
    private val published = """{"hived":["https://pub-a.example","https://pub-b.example","https://techcoderx.com"]}"""

    private fun TestScope.app(
        list: () -> HttpResult? = { HttpResult(200, published) },
        chainTime: (String) -> String? = { ChainTime.fromEpochSeconds(TestApp.NOW / 1000 - 3) },
    ): TestApp = TestApp(this) { req ->
        when {
            req.method == "condenser_api.get_dynamic_global_properties" -> chainTime(req.node)?.let { FakeTransport.result(req, """{"time":"$it","head_block_number":5}""") }
                ?: awaitCancellation()
            else -> FakeTransport.result(req, "1")
        }
    }.also { app ->
        app.env.managesHiveNodes = true
        app.authBackend.rest = { r -> if (r.url == HiveNodes.REMOTE_LIST_URL) list() else null }
    }

    @Test
    fun startInstallsThePublishedListWithoutBlockedNodes() = runTest {
        val app = app()
        app.env.hiveNodes.start()
        assertEquals(listOf("https://pub-a.example", "https://pub-b.example"), app.env.api.client.nodes)
    }

    @Test
    fun theSavedServerGoesFirstUnlessListedAndABlockedOneNever() = runTest {
        var app = app()
        app.env.prefs.putString(HiveNodeSettings.PREFS_KEY, "https://mine.example")
        app.env.hiveNodes.start()
        assertEquals(listOf("https://mine.example", "https://pub-a.example", "https://pub-b.example"), app.env.api.client.nodes)

        app = app()
        app.env.prefs.putString(HiveNodeSettings.PREFS_KEY, "https://pub-b.example")
        app.env.hiveNodes.start()
        assertEquals(listOf("https://pub-a.example", "https://pub-b.example"), app.env.api.client.nodes, "listed: keeps its place")

        app = app()
        app.env.prefs.putString(HiveNodeSettings.PREFS_KEY, "https://techcoderx.com")
        app.env.hiveNodes.start()
        assertTrue(app.env.api.client.nodes.none(HiveNodes::isBlocked))
    }

    @Test
    fun anUnsafeSavedServerReadsAsTheDefaultPool() = runTest {
        // the published list's rule holds for the saved server too: https, and no user or password in the host
        for (saved in listOf("http://mine.example", "https://user:pw@mine.example", "https://pub-a.example@mine.example")) {
            val app = app()
            app.env.prefs.putString(HiveNodeSettings.PREFS_KEY, saved)
            app.env.hiveNodes.start()
            assertEquals(listOf("https://pub-a.example", "https://pub-b.example"), app.env.api.client.nodes, saved)
            assertNull(app.env.hiveNodes.selected(), saved)
            assertEquals(ServerCheck.UNREACHABLE, app.env.hiveNodes.select(saved, app.env.hiveNodes.servers()), saved)
        }
        assertEquals(listOf("https://pub-a.example"), HiveNodes.pool("http://mine.example", listOf("https://pub-a.example")))
    }

    @Test
    fun anUnreadableListKeepsTheBuiltInOne() = runTest {
        for (answer in listOf(HttpResult(500, ""), HttpResult(200, "<html>"), HttpResult(200, "[]"), HttpResult(200, """["https://techcoderx.com"]"""))) {
            val app = app(list = { answer })
            app.env.prefs.putString(HiveNodeSettings.PREFS_KEY, "https://mine.example")
            app.env.hiveNodes.start()
            assertEquals(listOf("https://mine.example") + HiveNodes.DEFAULT, app.env.api.client.nodes, answer.body)
        }
    }

    @Test
    fun startDoesNothingInADemoRunAndRunsOnce() = runTest {
        var reads = 0
        val app = app(list = { reads++; HttpResult(200, published) })
        app.env.managesHiveNodes = false
        val before = app.env.api.client.nodes
        app.env.hiveNodes.start()
        assertEquals(before, app.env.api.client.nodes)
        app.env.managesHiveNodes = true
        app.env.hiveNodes.start()
        app.env.hiveNodes.start()
        assertEquals(1, reads)
    }

    @Test
    fun selectingAServerChecksItThenPutsItFirst() = runTest {
        val app = app(chainTime = { node ->
            when (node) {
                "https://stale.example" -> ChainTime.fromEpochSeconds(TestApp.NOW / 1000 - 60)
                "https://dead.example" -> null
                else -> ChainTime.fromEpochSeconds(TestApp.NOW / 1000 - 3)
            }
        })
        val list = app.env.hiveNodes.servers()
        assertEquals(listOf("https://pub-a.example", "https://pub-b.example"), list)

        assertEquals(ServerCheck.OK, app.env.hiveNodes.select("https://pub-b.example", list))
        assertEquals(listOf("https://pub-b.example", "https://pub-a.example"), app.env.api.client.nodes)
        assertEquals("https://pub-b.example", app.env.hiveNodes.selected())
        assertEquals("https://pub-b.example", app.transport.sent.last().node, "the check asked that node alone")

        // a stale or dead node changes nothing (RN keeps the previous server)
        val before = app.transport.sent.size
        assertEquals(ServerCheck.STALE, app.env.hiveNodes.select("https://stale.example", list))
        assertEquals(ServerCheck.UNREACHABLE, app.env.hiveNodes.select("https://dead.example", list))
        assertEquals(5_000, testScheduler.currentTime, "the check gives up after 5 s")
        assertEquals(2, app.transport.sent.size - before, "one request each, no failover")
        assertEquals(ServerCheck.UNREACHABLE, app.env.hiveNodes.select("https://hiveapi.actifit.io", list))
        assertEquals(ServerCheck.UNREACHABLE, app.env.hiveNodes.select("not a url", list))
        assertEquals("https://pub-b.example", app.env.hiveNodes.selected())
        assertEquals(listOf("https://pub-b.example", "https://pub-a.example"), app.env.api.client.nodes)
        assertNull(app.env.prefs.getString("server"))
    }
}
