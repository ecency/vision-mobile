package com.ecency.core.api.community

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.clientWith
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.search.SearchApi
import com.ecency.core.api.search.SearchApiException
import com.ecency.core.api.search.SearchSort
import com.ecency.core.auth.FakeAuthHttp
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Request shapes follow @ecency/sdk; fixtures are shaped like live hivemind answers with synthetic values. */
class CommunityApiTest {
    private fun api(results: Map<String, String>): Pair<CommunityApi, FakeTransport> {
        val t = FakeTransport.byMethod(results)
        return CommunityApi(clientWith(t)) to t
    }

    @Test
    fun listCommunitiesSendsTheSdkParameters() = runTest {
        val (api, t) = api(mapOf("bridge.list_communities" to "[${COMMUNITY_JSON}]"))
        val list = api.communities(CommunitySort.RANK, null, 50, null)
        assertEquals("hive-100001", list.single().name)
        val p = t.sent.single().params.jsonObject
        assertEquals("", p["last"]!!.jsonPrimitive.content)
        assertEquals(50, p["limit"]!!.jsonPrimitive.content.toInt())
        assertEquals("rank", p["sort"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, p["query"]) // null, not "", when nothing is searched
        assertFalse("observer" in p) // undefined is dropped by JSON.stringify

        api.communities(CommunitySort.RANK, "travel", 100, "spike-voter")
        val q = t.sent.last().params.jsonObject
        assertEquals("travel", q["query"]!!.jsonPrimitive.content)
        assertEquals("spike-voter", q["observer"]!!.jsonPrimitive.content)
    }

    @Test
    fun communityParsesTeamContextAndAlwaysSendsAnObserver() = runTest {
        val (api, t) = api(mapOf("bridge.get_community" to COMMUNITY_JSON))
        val c = api.community("hive-100001")!!
        assertEquals("", t.sent.single().params.jsonObject["observer"]!!.jsonPrimitive.content)
        assertEquals("Test Square", c.title)
        assertEquals("Be kind", c.flagText)
        assertEquals(1200, c.subscribers)
        assertEquals(listOf("hive-100001", "spike-owner", "spike-admin", "spike-mod"), c.team.map { it.account })
        assertEquals(CommunityRole.ADMIN, c.roleOf("spike-admin"))
        assertTrue(c.isModerator("spike-mod"))
        assertFalse(c.canEditSettings("spike-mod")) // settings are owner and admin only
        assertTrue(c.canEditSettings("spike-owner"))
        assertFalse(c.isModerator("stranger"))
        assertFalse(c.isModerator(null))
        assertEquals("admin", c.observerRole)
        assertEquals(true, c.observerSubscribed)
        assertEquals("Topic", c.type)
    }

    @Test
    fun roleOfAnAccountInACommunity() = runTest {
        val (api, t) = api(mapOf("bridge.get_community_context" to """{"role":"mod","subscribed":true,"title":""}"""))
        assertEquals("mod", api.role("spike-mod", "hive-100001"))
        assertEquals("""{"account":"spike-mod","name":"hive-100001"}""", t.sent.single().params.toString())
        val (none, _) = api(mapOf("bridge.get_community_context" to "{}"))
        assertEquals("guest", none.role("stranger", "hive-100001"))
    }

    @Test
    fun missingCommunityIsNull() = runTest {
        val (api, _) = api(mapOf("bridge.get_community" to "null"))
        assertNull(api.community("hive-404"))
    }

    @Test
    fun subscriptionsAndSubscribersArePositionalRows() = runTest {
        val (api, t) = api(
            mapOf(
                "bridge.list_all_subscriptions" to """[["hive-1","One","admin",""],["hive-2","Two","guest","label"],[],"junk"]""",
                "bridge.list_subscribers" to """[["alice","guest",null,"2025-12-24T04:07:42"],["bob","mod","Helper","2025-02-13T15:37:12"]]""",
            ),
        )
        val subs = api.subscriptions("spike-voter")
        assertEquals(listOf(Subscription("hive-1", "One", "admin", ""), Subscription("hive-2", "Two", "guest", "label")), subs)
        val page = api.subscribers("hive-1")
        assertEquals(Subscriber("alice", "guest", "", "2025-12-24T04:07:42"), page.items[0])
        assertNull(page.next) // a short page is the end
        val first = t.sent.last().params.jsonObject
        assertFalse("last" in first) // hivemind reads "" as a cursor and answers nothing
        assertEquals(100, first["limit"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun aFullSubscriberPageHasACursor() = runTest {
        val rows = (1..100).joinToString(",", "[", "]") { """["user$it","guest",null,""]""" }
        val (api, t) = api(mapOf("bridge.list_subscribers" to rows))
        val page = api.subscribers("hive-1")
        assertEquals("user100", page.next)
        api.subscribers("hive-1", page.next)
        assertEquals("user100", t.sent.last().params.jsonObject["last"]!!.jsonPrimitive.content)
    }

    @Test
    fun activitiesPageWithTheStringCursorHivemindSent() = runTest {
        val rows = (1..3).joinToString(",", "[", "]") {
            """{"id":"8928174652837068$it","msg":"@user$it subscribed to Test Square","url":"c/hive-1","date":"2026-10-08T05:00:2$it","type":"subscribe","score":5$it}"""
        }
        val (api, t) = api(mapOf("bridge.account_notifications" to rows))
        val page = api.activities("hive-1", 3)
        assertEquals("89281746528370683", page.next)
        api.activities("hive-1", 3, page.next)
        val p = t.sent.last().params.jsonObject
        assertEquals("89281746528370683", p["last_id"]!!.jsonPrimitive.content)
        assertTrue(p["last_id"]!!.jsonPrimitive.isString)
        assertFalse("last_id" in t.sent.first().params.jsonObject)
        assertNull(api.activities("hive-1", 50).next) // short page
    }

    @Test
    fun lookupRefusesNamesTheChainCannotHold() = runTest {
        val (api, t) = api(mapOf("condenser_api.lookup_accounts" to """["alice","alice1"]"""))
        assertEquals(listOf("alice", "alice1"), api.lookupAccounts("alice", 20))
        assertEquals("""["alice",20]""", t.sent.single().params.toString())
        // 16 characters but 17 UTF-8 bytes: hived would assert, so nothing is sent
        assertEquals(emptyList(), api.lookupAccounts("sebastián.bilbao"))
        assertEquals(emptyList(), api.lookupAccounts(""))
        assertEquals(1, t.sent.size)
    }

    @Test
    fun trendingTagsDropCommunitiesAndBlanks() = runTest {
        val (api, t) = api(
            mapOf("condenser_api.get_trending_tags" to """[{"name":"","comments":1},{"name":"hbd"},{"name":"hive-13323"},{"name":"photography"}]"""),
        )
        assertEquals(listOf("hbd", "photography"), api.trendingTags())
        assertEquals("""["",20]""", t.sent.single().params.toString())
    }

    // ---- search API ----

    @Test
    fun searchSendsTheSdkClientHeadersAndBody() = runTest {
        val http = FakeAuthHttp { HttpResult(200, SEARCH_PAGE) }
        val api = SearchApi(http, "https://ecency.example")
        val page = api.search("hive type:post", SearchSort.NEWEST, false, "2026-09-29T12:00:00", null)
        val request = http.sent.single()
        assertEquals("POST", request.method)
        assertEquals("https://ecency.example/search-api/search", request.url)
        assertEquals("ecency-sdk", request.headers["X-Ecency-Client"])
        assertFalse("User-Agent" in request.headers) // the transport names the app, once, for every Ecency call
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("""{"q":"hive type:post","sort":"newest","hide_low":false,"since":"2026-09-29T12:00:00"}""", body.toString())

        assertEquals("scroll-1", page.scrollId)
        assertEquals(2, page.hits.size)
        val hit = page.hits[0]
        assertEquals("alice/first", hit.key)
        assertEquals(70, hit.reputation) // "70.00" as sent
        assertEquals("Hive is great", hit.snippet) // <mark> removed
        assertEquals("2026-10-08T13:58:42", hit.created)
        assertEquals(1.5, hit.payout)
        assertEquals(3, hit.votes)
        assertEquals(2, hit.children)

        api.search("hive", SearchSort.RELEVANCE, scrollId = "scroll-1")
        val next = Json.parseToJsonElement(http.sent.last().body!!).jsonObject
        assertEquals("scroll-1", next["scroll_id"]!!.jsonPrimitive.content)
        assertFalse("since" in next)
    }

    @Test
    fun theTransportNamesTheAppOnEveryFirstPartyCall() {
        val identity = com.ecency.core.auth.ClientIdentity
        val request = com.ecency.core.auth.AuthHttpRequest("POST", "https://ecency.com/search-api/search", SearchApi(FakeAuthHttp { HttpResult(200, "{}") }).headers(), "{}")
        val sent = identity.headers(request, "TestBuild/1.2.3")
        assertEquals("TestBuild/1.2.3", sent["User-Agent"])
        assertEquals("ecency-sdk", sent["X-Ecency-Client"])
        // only the first-party hosts are told who the app is
        for (other in listOf(
            "https://play.3speak.tv/api/embed?v=a/b", "https://evilhivesigner.com/api/me", "https://raw.githubusercontent.com/x/y", "http://ecency.com/x",
            "https://ecency.com.evil.example/x", "https://notecency.com/x", "https://ecency.com@evil.example/x", "not a url",
        )) {
            assertFalse("User-Agent" in identity.headers(request.copy(url = other), "TestBuild/1.2.3"), other)
        }
        for (own in listOf("https://ecency.com/private-api/waves/feed", "https://translate.ecency.com/detect", "https://POLL.ecency.com/rpc/poll", "https://hivesigner.com/api/me")) {
            assertEquals("TestBuild/1.2.3", identity.headers(request.copy(url = own), "TestBuild/1.2.3")["User-Agent"], own)
        }
        // the 3Speak stream lookup the reels make goes out without the app's name
        val threeSpeak = com.ecency.core.auth.AuthHttpRequest("GET", com.ecency.core.api.waves.WavesApi.threeSpeakEmbedApi("spike-alice", "abcd1234"))
        assertEquals(emptyMap(), identity.headers(threeSpeak, "TestBuild/1.2.3"))
        // a request that names itself keeps its own
        assertEquals("Own/1", identity.headers(request.copy(headers = mapOf("user-agent" to "Own/1")), "TestBuild/1.2.3")["user-agent"])
        // the build's value, else a generic default that says nothing beyond the app and its version
        assertEquals("TestBuild/1.2.3", identity.userAgent(" TestBuild/1.2.3 "))
        assertEquals("Ecency/${com.ecency.core.EcencyCore.VERSION}", identity.DEFAULT_USER_AGENT)
        for (unset in listOf(null, "", "  ", "bad\nvalue", "x".repeat(201), "na\u00efve")) assertEquals(identity.DEFAULT_USER_AGENT, identity.userAgent(unset))
    }

    @Test
    fun searchFailuresKeepTheStatus() = runTest {
        val rejected = SearchApi(FakeAuthHttp { HttpResult(400, """{"error":"Maximum 5 tags!"}""") })
        val e = assertFailsWith<SearchApiException> { rejected.search("x", SearchSort.RELEVANCE) }
        assertEquals(400, e.status)
        assertTrue(e.isRejected)
        assertEquals("Maximum 5 tags!", e.detail)

        val throttled = SearchApi(FakeAuthHttp { HttpResult(429, "") })
        assertFalse(assertFailsWith<SearchApiException> { throttled.search("x", SearchSort.RELEVANCE) }.isRejected)

        val notASearch = SearchApi(FakeAuthHttp { HttpResult(200, "\"maintenance\"") })
        assertFailsWith<SearchApiException> { notASearch.search("x", SearchSort.RELEVANCE) }

        val offline = SearchApi(FakeAuthHttp { throw IllegalStateException("offline") })
        assertEquals(0, assertFailsWith<SearchApiException> { offline.search("x", SearchSort.RELEVANCE) }.status)
    }

    companion object {
        const val COMMUNITY_JSON = """{"id":1,"lang":"en","name":"hive-100001","team":[["hive-100001","owner",""],["spike-owner","owner",""],["spike-admin","admin",""],["spike-mod","mod","Helper"]],"about":"About the test square","title":"Test Square","context":{"role":"admin","title":"","subscribed":true},"is_nsfw":false,"type_id":1,"settings":{},"flag_text":"Be kind","created_at":"2019-11-12 09:52:15","description":"A test community.","num_authors":40,"num_pending":280,"subscribers":1200,"sum_pending":27}"""

        const val SEARCH_PAGE = """{"took":0.5,"hits":2,"scroll_id":"scroll-1","results":[
            {"id":null,"author":"alice","permlink":"first","category":"hive-100001","children":2,"author_rep":"70.00","author_reputation":"70.00","title":"First","title_marked":null,"body":"Body","body_marked":"<mark>Hive</mark> is great","img_url":"","payout":1.5,"total_votes":3,"up_votes":3,"created_at":"2026-10-08T13:58:42+00:00","tags":["hive"],"app":"ecency/4.0.0","depth":0},
            {"id":null,"author":"bob","permlink":"second","category":"hive","children":0,"author_rep":0,"author_reputation":"55.2","title":"Second","body":"","img_url":"","total_votes":0,"up_votes":0,"created_at":"2026-10-07T10:00:00+00:00","tags":[],"app":"","depth":1}
        ]}"""
    }
}
