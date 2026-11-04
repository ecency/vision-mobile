package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Fixtures.entry
import com.ecency.core.api.Sent
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.search.SearchDate
import com.ecency.core.api.search.SearchFilters
import com.ecency.core.api.search.SearchSort
import com.ecency.core.api.search.SearchType
import com.ecency.core.auth.AuthHttpRequest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchModelTest {
    /** Search API pages in order (each a list of author/permlink) and a scroll id while more remain. */
    private class SearchNode {
        val requests = mutableListOf<AuthHttpRequest>()
        var pages = ArrayDeque<List<Pair<String, String>>>()
        var status = 200

        fun answer(request: AuthHttpRequest): HttpResult? {
            if (!request.url.endsWith("/search-api/search")) return null
            requests += request
            if (status != 200) return HttpResult(status, """{"error":"refused"}""")
            val page = pages.removeFirstOrNull().orEmpty()
            val hits = page.joinToString(",") { (a, p) ->
                """{"author":"$a","permlink":"$p","title":"T $p","category":"hive","children":1,"author_rep":"60.00","body_marked":"a <mark>hive</mark> post","payout":2,"up_votes":4,"created_at":"2026-10-06T10:00:00+00:00","depth":0}"""
            }
            val scroll = if (pages.isNotEmpty()) "\"scroll_id\":\"s${pages.size}\"," else ""
            return HttpResult(200, """{"took":1,"hits":9,$scroll"results":[$hits]}""")
        }
    }

    private fun rpc(): suspend FakeTransport.(Sent) -> HttpResult = { req ->
        when (req.method) {
            "bridge.get_account_posts" -> FakeTransport.result(req, "[${entry("ecency", "latest")}]")
            "bridge.get_post" -> FakeTransport.result(req, entry("alice", "pasted-post"))
            "condenser_api.lookup_accounts" -> FakeTransport.result(req, """["alice","alice2"]""")
            "bridge.list_communities" -> FakeTransport.result(req, "[${CommunityNode.listed("hive-1", "One")},${CommunityNode.listed("hive-2", "Two")}]")
            "bridge.list_all_subscriptions" -> FakeTransport.result(req, "[]")
            else -> TestApp.chain(req) ?: HttpResult(500, "no fixture for ${req.method}")
        }
    }

    private fun TestScope.app(node: SearchNode): TestApp = TestApp(this, handler = rpc()).also { it.authBackend.rest = node::answer }

    @Test
    fun nothingTypedShowsEcencysLatestPosts() = runTest {
        val node = SearchNode()
        val app = app(node)
        val model = SearchModel(app.env)
        model.start()
        testScheduler.advanceUntilIdle()
        assertEquals(SearchPostsMode.INITIAL, model.current.posts.mode)
        assertEquals(listOf("ecency/latest"), model.current.posts.items.map { it.key })
        val p = app.transport.sent.single { it.method == "bridge.get_account_posts" }.params.jsonObject
        assertEquals("ecency", p["account"]!!.jsonPrimitive.content)
        assertEquals("7", p["limit"]!!.jsonPrimitive.content)
        assertTrue(node.requests.isEmpty())
        model.close()
    }

    @Test
    fun typingSearchesAfterThePauseAndPages() = runTest {
        val node = SearchNode().apply { pages = ArrayDeque(listOf(listOf("a" to "1", "b" to "2"), listOf("b" to "2", "c" to "3"))) }
        val app = app(node)
        val model = SearchModel(app.env)
        model.start()
        model.setInput("Hiv")
        model.setInput("Hive")
        testScheduler.advanceTimeBy(400)
        assertEquals("", model.current.query) // still typing
        testScheduler.advanceUntilIdle()
        assertEquals("Hive", model.current.query)
        assertEquals(1, node.requests.size) // one request after the pause
        val body = Json.parseToJsonElement(node.requests[0].body!!).jsonObject
        assertEquals("hive type:post", body["q"]!!.jsonPrimitive.content) // the posts tab searches posts by default
        assertEquals("relevance", body["sort"]!!.jsonPrimitive.content)
        assertEquals("false", body["hide_low"]!!.jsonPrimitive.content)
        assertEquals("ecency-sdk", node.requests[0].headers["X-Ecency-Client"])

        val first = model.current.posts
        assertEquals(SearchPostsMode.SEARCH, first.mode)
        assertEquals(listOf("a/1", "b/2"), first.items.map { it.key })
        assertEquals("a hive post", first.items[0].snippet)
        assertEquals("$ 2", first.items[0].payoutText)
        assertFalse(first.endReached)

        model.loadMorePosts()
        testScheduler.advanceUntilIdle()
        assertEquals("s1", Json.parseToJsonElement(node.requests[1].body!!).jsonObject["scroll_id"]!!.jsonPrimitive.content)
        assertEquals(listOf("a/1", "b/2", "c/3"), model.current.posts.items.map { it.key }) // overlapping pages deduped
        assertTrue(model.current.posts.endReached)
        model.loadMorePosts()
        testScheduler.advanceUntilIdle()
        assertEquals(2, node.requests.size)
        model.close()
    }

    @Test
    fun aPastedPostLinkOpensThatPost() = runTest {
        val node = SearchNode()
        val app = app(node)
        val model = SearchModel(app.env)
        model.submit("https://peakd.com/hive-1/@alice/pasted-post")
        testScheduler.advanceUntilIdle()
        assertEquals(SearchPostsMode.POST_URL, model.current.posts.mode)
        assertEquals(listOf("alice/pasted-post"), model.current.posts.items.map { it.key })
        assertTrue(node.requests.isEmpty())

        // the people tab looks up the link's author
        model.selectTab(SearchTab.PEOPLE)
        testScheduler.advanceUntilIdle()
        assertEquals("""["alice",50]""", app.transport.sent.last { it.method == "condenser_api.lookup_accounts" }.params.toString())
        assertEquals(listOf("alice", "alice2"), model.current.people.items)
        model.close()
    }

    @Test
    fun filtersBuildTheQueryAndInvalidOnesAreNotSent() = runTest {
        val node = SearchNode().apply { pages = ArrayDeque(listOf(listOf("a" to "1"))) }
        val app = app(node)
        val model = SearchModel(app.env)
        model.start()
        testScheduler.advanceUntilIdle()

        val filters = SearchFilters(author = "@Alice", tags = "a, b", type = SearchType.ALL, date = SearchDate.WEEK, sort = SearchSort.NEWEST)
        assertNull(model.validateFilters(filters))
        model.applyFilters(filters)
        testScheduler.advanceUntilIdle()
        assertEquals(5, model.current.activeFilterCount)
        val body = Json.parseToJsonElement(node.requests.single().body!!).jsonObject
        assertEquals("author:alice tag:a,b", body["q"]!!.jsonPrimitive.content)
        assertEquals("newest", body["sort"]!!.jsonPrimitive.content)
        assertEquals("2026-09-29T12:00:00", body["since"]!!.jsonPrimitive.content)

        val tooMany = filters.copy(tags = "a b c d e f")
        assertEquals("Use at most 5 tags", model.validateFilters(tooMany))
        // a date alone is no criterion: the posts tab explains instead of sending
        model.applyFilters(SearchFilters(date = SearchDate.YEAR))
        testScheduler.advanceUntilIdle()
        assertEquals("Enter a search term, or an author, category or tag to filter by", model.current.posts.emptyText)
        assertEquals(1, node.requests.size)
        model.close()
    }

    @Test
    fun aRefusedSearchSaysSoAndALaterPageFailureKeepsResults() = runTest {
        val node = SearchNode().apply { status = 400 }
        val app = app(node)
        val model = SearchModel(app.env)
        model.submit("hive")
        testScheduler.advanceUntilIdle()
        assertEquals(SearchCopy.ERROR, model.current.posts.emptyText)

        node.status = 200
        node.pages = ArrayDeque(listOf(listOf("a" to "1"), listOf("b" to "2")))
        model.retry()
        testScheduler.advanceUntilIdle()
        assertEquals(1, model.current.posts.items.size)
        node.status = 500
        model.loadMorePosts()
        testScheduler.advanceUntilIdle()
        assertEquals(1, model.current.posts.items.size)
        assertEquals(SearchCopy.ERROR, model.current.posts.moreError)
        assertNull(model.current.posts.emptyText)
        model.close()
    }

    @Test
    fun onlyTheVisibleTabLoads() = runTest {
        val node = SearchNode().apply { pages = ArrayDeque(listOf(listOf("a" to "1"))) }
        val app = app(node)
        val model = SearchModel(app.env)
        model.submit("one")
        testScheduler.advanceUntilIdle()
        assertTrue(app.transport.sent.none { it.method == "bridge.list_communities" || it.method == "condenser_api.lookup_accounts" })

        model.selectTab(SearchTab.COMMUNITIES)
        testScheduler.advanceUntilIdle()
        val p = app.transport.sent.last { it.method == "bridge.list_communities" }.params.jsonObject
        assertEquals("one", p["query"]!!.jsonPrimitive.content)
        assertEquals("100", p["limit"]!!.jsonPrimitive.content)
        assertEquals(setOf("hive-1", "hive-2"), model.current.communities.rows.map { it.name }.toSet())
        // back to posts: nothing reloads
        model.selectTab(SearchTab.POSTS)
        testScheduler.advanceUntilIdle()
        assertEquals(1, node.requests.size)
        model.close()
    }

    @Test
    fun peopleShowNothingHereBeforeASearch() = runTest {
        val app = app(SearchNode())
        val model = SearchModel(app.env)
        model.selectTab(SearchTab.PEOPLE)
        testScheduler.advanceUntilIdle()
        assertEquals(SearchCopy.NOTHING_HERE, model.current.people.emptyText)
        assertTrue(app.transport.sent.none { it.method == "condenser_api.lookup_accounts" })
        model.close()
    }

    @Test
    fun aSlowOlderAnswerNeverReplacesANewerOne() = runTest {
        val slow = kotlinx.coroutines.CompletableDeferred<Unit>()
        val app = TestApp(this) { req ->
            when (req.method) {
                "condenser_api.lookup_accounts" -> {
                    val prefix = req.params.toString()
                    if ("\"old\"" in prefix) {
                        slow.await()
                        FakeTransport.result(req, """["old-answer"]""")
                    } else {
                        FakeTransport.result(req, """["new", "new-answer"]""")
                    }
                }
                "bridge.get_post" -> {
                    slow.await()
                    FakeTransport.result(req, entry("old", "post"))
                }
                "bridge.get_account_posts" -> FakeTransport.result(req, "[${entry("ecency", "latest")}]")
                "bridge.list_all_subscriptions" -> FakeTransport.result(req, "[]")
                else -> HttpResult(500, "")
            }
        }
        // people tab
        val search = SearchModel(app.env)
        search.selectTab(SearchTab.PEOPLE)
        search.submit("old")
        testScheduler.runCurrent()
        search.submit("new")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("new", "new-answer"), search.current.people.items)
        // posts tab: a pasted link still loading when the field is cleared
        val posts = SearchModel(app.env)
        posts.submit("https://peakd.com/@old/post")
        assertEquals(SearchPostsMode.POST_URL, posts.current.posts.mode)
        testScheduler.runCurrent()
        posts.submit("")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("ecency/latest"), posts.current.posts.items.map { it.key })
        // account autocomplete
        val lookup = AccountLookupModel(app.env)
        lookup.setInput("old")
        testScheduler.advanceTimeBy(400)
        lookup.setInput("new")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("new", "new-answer"), lookup.current.suggestions)

        slow.complete(Unit) // the old answers arrive now: nothing changes
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("new", "new-answer"), search.current.people.items)
        assertEquals(listOf("ecency/latest"), posts.current.posts.items.map { it.key })
        assertEquals(SearchPostsMode.INITIAL, posts.current.posts.mode)
        assertNull(posts.current.posts.error) // nor an abandoned request's failure or timeout
        assertNull(search.current.people.error)
        assertEquals(listOf("new", "new-answer"), lookup.current.suggestions)
        assertTrue(lookup.current.known)
        search.close(); posts.close(); lookup.close()
    }

    @Test
    fun highlightsAreCaseInsensitiveLiterals() {
        assertEquals(listOf(0..3, 9..12), SearchModel.highlights("Hive and hive.", "hive"))
        assertEquals(listOf(1..2), SearchModel.highlights("a.*b", ".*"))
        assertTrue(SearchModel.highlights("text", " ").isEmpty())
    }

    @Test
    fun tagResultDebouncesTheField() = runTest {
        val app = app(SearchNode())
        val model = TagResultModel(app.env, " photography ", "hot")
        assertEquals("photography", model.current.tag)
        assertEquals(TagTab.HOT, model.current.tab)
        model.setInput("#travel")
        testScheduler.advanceTimeBy(900)
        assertEquals("photography", model.current.tag)
        testScheduler.advanceUntilIdle()
        assertEquals("travel", model.current.tag)
        assertEquals("#travel", model.current.input)
        assertEquals(com.ecency.core.api.repository.FeedSource.Ranked(com.ecency.core.api.RankedSort.HOT, "travel"), model.feedSource())
        model.close()
    }

    @Test
    fun dappDirectoryAndUrlField() {
        assertEquals(13, Dapps.DIRECTORY.size)
        assertEquals(listOf("Ecency", "Raidstead", "PeakD"), Dapps.DIRECTORY.take(3).map { it.name })
        assertEquals("https://peakd.com", Dapps.normalizeUrl(" https://peakd.com "))
        assertEquals("https://hive.blog/trending", Dapps.normalizeUrl("hive.blog/trending"))
        assertEquals("https://www.google.com/search?q=hive%20games%3F%20%26%20more", Dapps.normalizeUrl("hive games? & more"))
        assertNull(Dapps.normalizeUrl("  "))
        assertEquals(AppScreen.DappBrowser("https://peakd.com"), Dapps.screen("https://peakd.com"))
    }
}
