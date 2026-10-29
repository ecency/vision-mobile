package com.ecency.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.playerMayLoad
import com.ecency.core.api.EcencyApi
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.rpc.RpcTransport
import com.ecency.core.app.AppDispatchers
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.InterimPostBodyRenderer
import com.ecency.core.app.PerfMarks
import com.ecency.core.hive.PrivateKey
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.KeyVaultAsync
import com.ecency.core.secure.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/** In-memory secure store for UI tests. */
private class MemoryStore : SecureStore {
    val map = Collections.synchronizedMap(linkedMapOf<String, ByteArray>())
    override fun put(name: String, value: ByteArray) { map[name] = value.copyOf() }
    override fun get(name: String): ByteArray? = map[name]?.copyOf()
    override fun delete(name: String) { map.remove(name) }
    override fun names(): Set<String> = map.keys.toSet()
    override fun wipe() = map.clear()
    override fun <T> withLock(block: () -> T): T = synchronized(this) { block() }
}

/**
 * The main flows against a scripted node (no network, nothing broadcast anywhere): feed, open a
 * post, tabs, errors and retry, vote, reply, debug sign-in, perf marks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppFlowsTest {
    @get:Rule
    val compose = createComposeRule()

    private val key = PrivateKey.fromSeed("ecency-native-spike-ui-test-throwaway")
    private val sent = Collections.synchronizedList(mutableListOf<String>())
    private val opened = mutableListOf<String>()
    private val marks = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var feedDown = false
    @Volatile private var broadcastDown = false

    private fun entry(author: String, permlink: String, parent: Pair<String, String>? = null, votes: List<String> = emptyList(), children: Int = 0) = """{
        "author":"$author","permlink":"$permlink",
        ${parent?.let { """"parent_author":"${it.first}","parent_permlink":"${it.second}",""" } ?: ""}
        "category":"hive-100001","title":"Title $permlink","body":"Body of $permlink with **bold** text",
        "json_metadata":{"tags":["test"],"description":"Summary of $permlink"},
        "created":"2026-10-06T10:00:00","updated":"2026-10-06T10:00:00","depth":${if (parent == null) 0 else 1},"children":$children,
        "net_rshares":1000,"author_reputation":60.0,"payout":1.5,"is_paidout":false,"payout_at":"2026-10-13T10:00:00",
        "pending_payout_value":"1.500 HBD","author_payout_value":"0.000 HBD","curator_payout_value":"0.000 HBD",
        "promoted":"0.000 HBD","max_accepted_payout":"1000000.000 HBD","percent_hbd":10000,"replies":[],
        "active_votes":[${votes.joinToString(",") { """{"voter":"$it","rshares":100}""" }}],
        "community":"hive-100001","community_title":"Test Community",
        "stats":{"hide":false,"gray":false,"total_votes":${votes.size},"flag_weight":0.0},"reblogs":0,"beneficiaries":[],"blacklists":[],
        "url":"/hive-100001/@$author/$permlink"}"""

    private val transport = RpcTransport { _, body ->
        sent += body
        val req = Json.parseToJsonElement(body).jsonObject
        val method = req["method"]!!.jsonPrimitive.content
        val id = req["id"]!!.jsonPrimitive.content
        fun ok(result: String) = HttpResult(200, """{"jsonrpc":"2.0","result":$result,"id":$id}""")
        when (method) {
            "bridge.get_ranked_posts" -> {
                if (feedDown) return@RpcTransport HttpResult(503, "down")
                val sort = req["params"]!!.jsonObject["sort"]!!.jsonPrimitive.content
                if (req["params"]!!.jsonObject["start_author"] != null) ok("[]")
                else ok("[${entry("alice", "$sort-one", votes = listOf("x"), children = 1)},${entry("bob", "$sort-two")}]")
            }
            "bridge.get_post" -> ok(entry("alice", "trending-one", votes = listOf("x"), children = 1))
            "bridge.get_discussion" -> ok(
                """{"alice/trending-one":${entry("alice", "trending-one", votes = listOf("x"), children = 1)},""" +
                    """"carol/re-one":${entry("carol", "re-one", parent = "alice" to "trending-one")}}""",
            )
            "condenser_api.get_accounts" -> ok(
                """[{"name":"tester","posting":{"weight_threshold":1,"account_auths":[],"key_auths":[["${key.publicKey()}",1]]},"memo_key":"${key.publicKey()}"}]""",
            )
            "condenser_api.get_dynamic_global_properties" -> ok(
                """{"head_block_number":100003,"head_block_id":"000186a3${"11".repeat(16)}","time":"2026-10-06T11:59:00"}""",
            )
            "condenser_api.get_block_header" -> ok("""{"previous":"000186a0${"22".repeat(16)}"}""")
            "condenser_api.get_content" -> ok("""{"author":"","permlink":""}""")
            "condenser_api.broadcast_transaction" -> if (broadcastDown) HttpResult(503, "") else ok("null")
            else -> HttpResult(500, "no fixture")
        }
    }

    private val store = MemoryStore()

    private val env by lazy {
        AppEnvironment(
            api = EcencyApi(listOf("https://node-a.example"), transport),
            vault = KeyVaultAsync(KeyVault(store), Dispatchers.IO),
            renderer = InterimPostBodyRenderer("https://i.ecency.com"),
            dispatchers = AppDispatchers(Dispatchers.Main, Dispatchers.Default, Dispatchers.IO),
            proxyBase = "https://i.ecency.com",
            debugKeyImport = true,
            nowMillis = { 1_791_288_000_000L },
            auth = TestAuth().services,
        ).also {
            // these flows read the trending feed: it leads the signed-in tabs (RN default is Following first)
            it.prefs.putString(com.ecency.core.app.FeedTabs.PREFS_KEY, "trending,hot,created,friends,communities")
        }
    }

    private val host = object : AppHost {
        override fun openExternal(url: String) { opened += url }
        override fun toast(text: String) {}
        override fun copy(text: String) {}
    }

    @Before
    fun setUp() {
        // no image bytes: every request fails fast without touching the network
        SingletonImageLoader.setUnsafe { ctx ->
            ImageLoader.Builder(ctx).components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                        ErrorResult(null, chain.request, IllegalStateException("no network in tests"))
                })
            }.build()
        }
        PerfMarks.resetOnce()
        PerfMarks.listener = { e, _ -> marks += e }
    }

    @After
    fun tearDown() {
        PerfMarks.listener = null
        SingletonImageLoader.reset()
    }

    private val navigator = com.ecency.app.ui.Navigator()

    private fun launch() = compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host, navigator) } }

    private fun waitFor(tag: String) = compose.await("a node tagged $tag") { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitForText(text: String) =
        compose.await("the text $text") { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    /** Lets a bottom sheet finish its enter animation; clicks during it are dropped. */
    private fun settle() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun signIn() {
        val vault = KeyVault(store)
        vault.save(com.ecency.core.secure.VaultAccount("tester", com.ecency.core.secure.AuthType.POSTING_KEY, postingKey = key.toWif()))
        vault.setCurrentUsername("tester")
    }

    @Test
    fun feedShowsCardsAndOpensThePostWithComments() {
        launch()
        waitFor("card:alice/trending-one")
        compose.onNodeWithText("Title trending-one").assertIsDisplayed()
        compose.onNodeWithText("Summary of trending-one").assertIsDisplayed()
        compose.onAllNodes(hasText("$ 1.500"), useUnmergedTree = true)[0].assertExists()

        compose.onNodeWithTag("open:alice/trending-one").performClick()
        waitFor("postTitle")
        waitFor("bodyStart")
        waitForText("Body of trending-one")
        compose.onNodeWithTag("postList").performScrollToNode(hasTestTag("comment:carol/re-one"))
        compose.onNodeWithTag("comment:carol/re-one").assertExists()

        // Draw-time marks (app_start, feed_first_content, post_body_drawn) need a real frame: Robolectric
        // never runs draw callbacks, so they are checked on the emulator (adb logcat -s EcencyPerf).
        compose.await("the post_body_ready mark") { "post_body_ready" in marks }
        assertTrue(marks.toString(), marks.containsAll(listOf("feed_first_page", "post_open_tap", "post_body_ready")))

        compose.onNodeWithTag("back").performClick()
        waitFor("card:alice/trending-one")
    }

    @Test
    fun tabsLoadTheirOwnFeed() {
        launch()
        waitFor("card:alice/trending-one")
        compose.onNodeWithTag("tab:HOT").performClick()
        waitFor("card:alice/hot-one")
        compose.onNodeWithTag("tab:CREATED").performClick()
        waitFor("card:alice/created-one")
    }

    @Test
    fun feedErrorShowsRetry() {
        feedDown = true
        launch()
        waitFor("retry")
        feedDown = false
        compose.onNodeWithTag("retry").performClick()
        waitFor("card:alice/trending-one")
    }

    @Test
    fun voteWhileSignedOutOpensSignIn() {
        launch()
        waitFor("card:alice/trending-one")
        compose.onAllNodes(hasTestTag("vote"))[0].performClick()
        waitFor("login")
        assertFalse(sent.any { "broadcast_transaction" in it })
    }

    @Test
    fun signInRejectsAWrongKeyWithTheRnMessage() {
        launch()
        waitFor("account")
        compose.onNodeWithTag("account").performClick()
        waitFor("menu:add_account")
        compose.waitForIdle()
        compose.onNodeWithTag("menu:add_account").performClick()
        waitFor("login")
        compose.onNodeWithTag("loginUser").performTextInput("tester")
        compose.onNodeWithTag("loginKey").performTextInput(PrivateKey.fromSeed("some-other-throwaway").toWif())
        compose.onNodeWithTag("loginSubmit").performClick()
        waitFor("loginError")
        assertTrue(store.names().none { it.startsWith("account.") })
    }

    @Test
    fun signedInVoteBroadcastsOnceAndShowsAsVoted() {
        signIn()
        launch()
        waitFor("card:alice/trending-one")
        compose.onAllNodes(hasTestTag("vote"))[0].performClick()
        waitFor("voteSheet")
        settle()
        compose.onNodeWithTag("upvote").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.await("the vote broadcast") { sent.any { "broadcast_transaction" in it } }
        val tx = sent.single { "broadcast_transaction" in it }
        assertTrue(tx, "\"voter\":\"tester\",\"author\":\"alice\",\"permlink\":\"trending-one\",\"weight\":10000" in tx)
        compose.await("the card shows the vote") { votedShown() }
        compose.onNodeWithText("2", useUnmergedTree = true).assertExists() // 1 vote + ours
    }

    private fun votedShown() = compose.onAllNodes(hasTestTag("vote")).fetchSemanticsNodes().any { node ->
        node.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription) { null }?.contains("Upvoted") == true
    }

    @Test
    fun replyFailureKeepsTheDraftAndRetrySucceeds() {
        signIn()
        launch()
        waitFor("open:alice/trending-one")
        compose.onNodeWithTag("open:alice/trending-one").performClick()
        waitFor("postActions")
        broadcastDown = true
        compose.onNodeWithTag("postActions").assertExists()
        // the bar's comments icon scrolls to the comments, whose header holds the Reply to the post
        compose.onAllNodes(hasTestTag("postComments") and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("postActions")))[0].performClick()
        waitFor("commentsReply")
        compose.onNodeWithTag("commentsReply").performClick()
        waitFor("replySheet")
        settle()
        compose.onNodeWithTag("replyText").performTextInput("Nice post")
        compose.onNodeWithTag("replySend").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        waitFor("replyError")
        val first = sent.filter { "broadcast_transaction" in it }.map { it.substringAfter("\"params\":").substringBefore(",\"id\"") }.distinct()
        assertEquals(1, first.size)

        broadcastDown = false
        compose.onNodeWithTag("replySend").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.await("the reply sheet closes") { compose.onAllNodes(hasTestTag("replySheet")).fetchSemanticsNodes().isEmpty() }
        val all = sent.filter { "broadcast_transaction" in it }.map { it.substringAfter("\"params\":").substringBefore(",\"id\"") }
        assertEquals(first.single(), all.last()) // the same signed transaction, not a second reply
        compose.onNodeWithTag("postList").performScrollToNode(hasTestTag("localComment"))
    }

    @Test
    fun playerOnlyLoadsAllowlistedHttpsEmbeds() {
        assertTrue(playerMayLoad("https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1"))
        assertFalse(playerMayLoad("http://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertFalse(playerMayLoad("https://evil.example/embed/x"))
        assertFalse(playerMayLoad("javascript:alert(1)"))
        assertFalse(playerMayLoad("intent://x#Intent;end"))
        assertTrue(opened.isEmpty())
    }
}
