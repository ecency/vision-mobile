package com.ecency.core.app

import com.ecency.core.api.model.Post
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RN postDisplayView's view count and postStatsModal over a scripted stats proxy. */
class PostStatsTest {
    private val post = Post(author = "alice", permlink = "hello", url = "/hive-125125/@alice/hello", created = "2026-09-01T10:00:00")

    @Test
    fun theCountIsTheVisitsForThePathWithoutCategoryOverThePostsLifetime() = runTest {
        val sent = mutableListOf<AuthHttpRequest>()
        val app = TestApp(this) { TestApp.chain(it) ?: HttpResult(500, "") }
        app.authBackend.rest = { r ->
            if (r.url.endsWith("/api/stats")) {
                sent += r
                val dims = Json.parseToJsonElement(r.body!!).jsonObject["dimensions"]!!.jsonArray
                if (dims.isEmpty()) {
                    HttpResult(200, """{"results":[{"metrics":[120,1534,1800,95],"dimensions":[]}],"query":{"metrics":["visitors","visits","pageviews","visit_duration"]}}""")
                } else {
                    HttpResult(
                        200,
                        """{"results":[{"metrics":[1,1,25,0],"dimensions":["Desktop"]},{"metrics":[1,1,75,0],"dimensions":["Mobile"]},{"metrics":[1],"dimensions":["Bad"]}],""" +
                            """"query":{"metrics":["visitors","visits","pageviews","visit_duration"]}}""",
                    )
                }
            } else null
        }
        val m = app.env.postStatsModel(post)
        m.start()
        testScheduler.advanceUntilIdle()
        assertEquals("1.5K", m.current.viewsText)
        assertEquals(1, m.current.minutes)
        val body = Json.parseToJsonElement(sent.single().body!!).jsonObject
        assertEquals("https://ecency.com/api/stats", sent.single().url)
        assertEquals("%2F%40alice%2Fhello", body["url"]!!.jsonPrimitive.content)
        assertEquals(listOf("visitors", "visits", "pageviews", "visit_duration"), body["metrics"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("2026-09-01", body["date_range"]!!.jsonArray[0].jsonPrimitive.content)
        // the SDK's getStatsQueryOptions body as it is (vision-next packages/sdk): dimensions always, filterBy event:page
        assertEquals(setOf("metrics", "url", "dimensions", "filterBy", "date_range"), body.keys)
        assertEquals("event:page", body["filterBy"]!!.jsonPrimitive.content)
        m.loadDevices()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(DeviceShare("Mobile", 0.75), DeviceShare("Desktop", 0.25)), m.current.devices)
        assertEquals(AppScreen.Redeem("promote", "alice/hello"), m.promoteScreen)
    }

    @Test
    fun aFailedOrEmptyAnswerReadsAsZeros() = runTest {
        val app = TestApp(this) { TestApp.chain(it) ?: HttpResult(500, "") }
        app.authBackend.rest = { r -> if (r.url.endsWith("/api/stats")) HttpResult(504, "") else null }
        val m = app.env.postStatsModel(post)
        m.start()
        testScheduler.advanceUntilIdle()
        assertEquals("0", m.current.viewsText)
        assertTrue(!m.current.loading)
    }

    @Test
    fun pathsAndNumbers() {
        assertEquals("/@alice/p", PostStatsModel.stripCategory("/hive-1/@alice/p"))
        assertEquals("/@alice/p", PostStatsModel.stripCategory("/@alice/p"))
        assertEquals("/x/y/@a/p", PostStatsModel.stripCategory("/x/y/@a/p"), "only the first segment")
        assertEquals("999", PostStatsModel.abbreviated(999))
        assertEquals("1K", PostStatsModel.abbreviated(1000))
        assertEquals("12.3K", PostStatsModel.abbreviated(12_345))
        assertEquals("0.1M", PostStatsModel.abbreviated(100_000))
        assertEquals("2.5M", PostStatsModel.abbreviated(2_500_000))
    }
}
