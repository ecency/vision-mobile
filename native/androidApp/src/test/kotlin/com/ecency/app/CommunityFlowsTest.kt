package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.Navigator
import com.ecency.core.api.EcencyApi
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.rpc.RpcTransport
import com.ecency.core.app.AppDispatchers
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.AppScreen
import com.ecency.core.app.AuthServices
import com.ecency.core.app.InterimPostBodyRenderer
import com.ecency.core.app.RouteName
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.KeyVaultAsync
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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

/** Communities, search and explore against a scripted node (nothing on the network, nothing broadcast anywhere). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CommunityFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    @Volatile private var role = "admin"
    private val searches: MutableList<AuthHttpRequest> = Collections.synchronizedList(mutableListOf())

    private fun community() =
        """{"id":1,"lang":"en","name":"hive-100001","team":[["hive-100001","owner",""],["spike-owner","owner",""],["tester","$role",""],["spike-mod","mod","Helper"]],""" +
            """"about":"About it","title":"Test Community","context":{},"is_nsfw":false,"type_id":1,"flag_text":"Be kind","created_at":"2019-11-12 09:52:15",""" +
            """"description":"A test community for flows.","num_authors":40,"num_pending":280,"subscribers":1200}"""

    private val transport = RpcTransport { url, body ->
        val req = Json.parseToJsonElement(body).jsonObject
        val id = req["id"]!!.jsonPrimitive.content
        fun ok(result: String) = HttpResult(200, """{"jsonrpc":"2.0","result":$result,"id":$id}""")
        when (req["method"]!!.jsonPrimitive.content) {
            "bridge.get_community" -> ok(community())
            "bridge.list_all_subscriptions" -> ok("""[["hive-100001","Test Community","guest",""]]""")
            "bridge.list_communities" -> ok(
                """[{"id":2,"name":"hive-200002","title":"Second Place","about":"Another one","context":{},"is_nsfw":false,"type_id":1,"num_authors":3,"num_pending":7,"subscribers":12}]""",
            )
            "bridge.list_subscribers" -> ok("""[["spike-alice","guest",null,""],["spike-bob","member","Writer",""]]""")
            "bridge.account_notifications" -> ok("""[{"id":"9","msg":"@spike-alice subscribed to Test Community","url":"","date":"2026-10-06T11:00:00","type":"subscribe","score":5}]""")
            "condenser_api.lookup_accounts" -> ok("""["spike-alice","spike-alicia"]""")
            "bridge.get_account_posts" -> ok("[${node.entry("ecency", "latest")}]")
            else -> {
                node.transport.post(url, body)
            }
        }
    }

    private val searchHttp = AuthHttp { request ->
        if (request.url.endsWith("/search-api/search")) {
            searches += request
            HttpResult(200, """{"took":1,"hits":1,"results":[{"author":"alice","permlink":"found-it","title":"Found it","category":"hive","children":2,"author_rep":"61.00","body_marked":"about <mark>hive</mark>","payout":1,"up_votes":3,"created_at":"2026-10-06T10:00:00+00:00","depth":0}]}""")
        } else {
            node.auth.services.http.send(request)
        }
    }

    private val env by lazy {
        AppEnvironment(
            api = EcencyApi(listOf("https://node-a.example"), transport),
            vault = KeyVaultAsync(KeyVault(node.store), Dispatchers.IO),
            renderer = InterimPostBodyRenderer("https://i.ecency.com"),
            dispatchers = AppDispatchers(Dispatchers.Main, Dispatchers.Default, Dispatchers.IO),
            proxyBase = "https://i.ecency.com",
            debugKeyImport = true,
            nowMillis = { 1_791_288_000_000L },
            auth = AuthServices(searchHttp, node.auth.services.sockets),
        ).also {
            // signed-in readers get RN's feed tabs (Following first); these flows start from the trending feed (as TestNode.env)
            it.prefs.putString(com.ecency.core.app.FeedTabs.PREFS_KEY, "trending,hot,created,friends,communities")
        }
    }
    private val navigator = Navigator()
    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {}
    }

    @Before
    fun setUp() {
        acceptTerms()
        SingletonImageLoader.setUnsafe { ctx ->
            ImageLoader.Builder(ctx).components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                        ErrorResult(null, chain.request, IllegalStateException("no network in tests"))
                })
            }.build()
        }
    }

    @After
    fun tearDown() = SingletonImageLoader.reset()

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun idleMain() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun waitFor(tag: String) {
        compose.await("exists(tag)") { idleMain(); exists(tag) }
        compose.waitForIdle()
    }

    private fun waitForText(text: String) {
        compose.await("compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemant...") { idleMain(); compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Lets sheet animations finish (Robolectric pauses the main looper between frames). */
    private fun settle() {
        compose.mainClock.advanceTimeBy(2_000)
        idleMain()
        compose.waitForIdle()
    }

    /** A tap inside a modal sheet: its window takes no injected touches under Robolectric. */
    private fun tap(tag: String) {
        settle()
        compose.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        settle()
    }

    private fun launchSignedIn() {
        node.signIn()
        compose.setContent { EcencyApp(env, host, navigator) }
        compose.await("env.session.current.username == \"tester\"") { idleMain(); env.session.current.username == "tester" }
        waitFor("card:alice/trending-one")
    }

    private fun push(screen: AppScreen) = compose.runOnUiThread { navigator.push(screen) }

    /** The `json` of the last community custom_json broadcast. */
    private fun lastCommunityOp(): String {
        val tx = Json.parseToJsonElement(node.broadcasts().last()).jsonObject["params"]!!.jsonArray[0].jsonObject
        val op = tx["operations"]!!.jsonArray[0].jsonArray[1].jsonObject
        assertEquals("community", op["id"]!!.jsonPrimitive.content)
        return op["json"]!!.jsonPrimitive.content
    }

    @Test
    fun sideMenuOpensCommunitiesAndAJoinFromDiscoverBroadcasts() {
        launchSignedIn()
        compose.onNodeWithTag("account").performClick()
        waitFor("drawer")
        compose.onNodeWithTag("menu:communities").performClick()
        waitFor("joined:hive-100001")
        compose.onNodeWithTag("tab:discover").performClick()
        waitFor("community:hive-200002")
        compose.onNodeWithText("12 Members • 3 Posters • 7 Posts").assertExists()
        compose.onNode(hasTestTag("join") and androidx.compose.ui.test.hasAnyAncestor(hasTestTag("community:hive-200002"))).performClick()
        compose.await("node.broadcasts().isNotEmpty()") { idleMain(); node.broadcasts().isNotEmpty() }
        assertEquals("""["subscribe",{"community":"hive-200002"}]""", lastCommunityOp())
        waitForText("Leave")
    }

    @Test
    fun theDetailsCardClosesOnScrollAndOpensAtTheTopOrOnItsTitle() {
        launchSignedIn()
        push(AppScreen.Community("hive-100001"))
        waitFor("communityDetailsBody")
        // the title closes and opens it (RN CollapsibleCard)
        tap("communityDetailsToggle")
        compose.await("!exists(\"communityDetailsBody\")") { idleMain(); !exists("communityDetailsBody") }
        tap("communityDetailsToggle")
        waitFor("communityDetailsBody")
        // scrolling the feed down closes it, the title and the tabs stay; back at the top it opens again
        compose.mainClock.advanceTimeBy(1_000)
        waitFor("card:alice/trending-one")
        compose.onNodeWithTag("feed").performTouchInput { swipeUp(durationMillis = 600) }
        compose.await("!exists(\"communityDetailsBody\")") { idleMain(); !exists("communityDetailsBody") }
        assertTrue(exists("communityDetailsToggle"))
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("feed").performTouchInput { swipeDown(durationMillis = 600) }
        waitFor("communityDetailsBody")
        // closed, then another tab: it opens over that tab's list
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("feed").performTouchInput { swipeUp(durationMillis = 600) }
        compose.await("!exists(\"communityDetailsBody\")") { idleMain(); !exists("communityDetailsBody") }
        tap("tab:HOT")
        waitFor("communityDetailsBody")
    }

    @Test
    fun aModeratorManagesMembersFromTheCommunityPage() {
        launchSignedIn()
        // a card's community opens the community page
        compose.onAllNodes(hasText("in Test Community", substring = true), useUnmergedTree = true)[0].performClick()
        waitFor("communityDetails")
        compose.onNodeWithTag("communityDescription").assertTextContains("A test community for flows.")
        compose.onNodeWithText("Test Community community").assertExists()
        // the manage sheet closes on Cancel without going anywhere
        compose.onNodeWithTag("manage").performClick()
        waitFor("manageSheet")
        tap("manageCancel")
        compose.await("!exists(\"manageSheet\")") { idleMain(); !exists("manageSheet") }
        assertTrue(navigator.top.screen is AppScreen.Community)
        compose.onNodeWithTag("manage").performClick()
        waitFor("manageSheet")
        tap("manage:MEMBERS")
        waitFor("member:spike-owner")
        compose.onNodeWithTag("members").performScrollToNode(hasTestTag("member:spike-alice"))
        assertTrue(navigator.top.screen is AppScreen.CommunityMembers)

        tap("role:spike-alice")
        waitFor("roleSheet")
        assertTrue(exists("roleCancel"))
        tap("roleOption:muted")
        compose.await("node.broadcasts().isNotEmpty()") { idleMain(); node.broadcasts().isNotEmpty() }
        assertEquals("""["setRole",{"community":"hive-100001","account":"spike-alice","role":"muted"}]""", lastCommunityOp())
        compose.await("compose.onAllNodes(hasText(\"Muted\"), useUnmergedTree = true).fetchSemanticsNodes().isNo...") { idleMain(); compose.onAllNodes(hasText("Muted"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun assigningARoleNeedsAnExistingAccount() {
        launchSignedIn()
        push(AppScreen.CommunityMembers("hive-100001", "Test Community"))
        waitFor("assignRole")
        compose.onNodeWithTag("assignRole").performClick()
        waitFor("roleSheet")
        compose.onNodeWithTag("roleAccount").performTextInput("spike-ali")
        waitFor("suggestion:spike-alice")
        tap("suggestion:spike-alice")
        compose.waitForIdle()
        tap("roleOption:mod")
        compose.await("node.broadcasts().isNotEmpty()") { idleMain(); node.broadcasts().isNotEmpty() }
        assertEquals("""["setRole",{"community":"hive-100001","account":"spike-alice","role":"mod"}]""", lastCommunityOp())
    }

    @Test
    fun settingsAreReadOnlyForAMod() {
        role = "mod"
        launchSignedIn()
        push(AppScreen.CommunitySettings("hive-100001"))
        waitFor("settingsNotice")
        assertFalse(exists("saveSettings"))
        compose.onNodeWithText("Only the owner and admins can change these settings.").assertExists()
    }

    @Test
    fun anAdminSavesSettings() {
        launchSignedIn()
        push(AppScreen.CommunitySettings("hive-100001"))
        waitFor("saveSettings")
        compose.onNodeWithTag("field:rules").performScrollTo().performTextInput("No spam. ")
        compose.onNodeWithTag("saveSettings").performScrollTo().performClick()
        compose.await("node.broadcasts().isNotEmpty()") { idleMain(); node.broadcasts().isNotEmpty() }
        val json = Json.parseToJsonElement(lastCommunityOp()).jsonArray
        assertEquals("updateProps", json[0].jsonPrimitive.content)
        assertEquals("No spam. Be kind", json[1].jsonObject["props"]!!.jsonObject["flag_text"]!!.jsonPrimitive.content)
        compose.await("navigator.top.screen !is AppScreen.CommunitySettings") { idleMain(); navigator.top.screen !is AppScreen.CommunitySettings }
    }

    @Test
    fun activityMentionsOpenProfiles() {
        launchSignedIn()
        push(AppScreen.CommunityActivities("hive-100001", "Test Community"))
        waitFor("activity:9")
        compose.onNodeWithText("Test Community activity").assertExists()
    }

    @Test
    fun headerSearchFindsPostsAndPeople() {
        launchSignedIn()
        compose.onNodeWithTag("headerSearch").performClick()
        waitFor("searchInput")
        waitFor("result:ecency/latest") // before typing: @ecency's latest
        compose.onNodeWithTag("searchInput").performTextInput("Hive")
        waitFor("result:alice/found-it")
        val body = Json.parseToJsonElement(searches.last().body!!).jsonObject
        assertEquals("hive type:post", body["q"]!!.jsonPrimitive.content)
        assertEquals("ecency-sdk", searches.last().headers["X-Ecency-Client"])

        compose.onNodeWithTag("searchTab:PEOPLE").performClick()
        waitFor("person:spike-alice")
        compose.onNodeWithTag("searchTab:COMMUNITIES").performClick()
        waitFor("community:hive-200002")

        compose.onNodeWithTag("searchTab:POSTS").performClick()
        compose.onNodeWithTag("searchFilters").performClick()
        waitFor("filtersSheet")
        compose.onNodeWithTag("filter:tags").performScrollTo().performTextInput("a b c d e f")
        tap("applyFilters")
        waitFor("filterError")
        compose.onNodeWithTag("filterError", useUnmergedTree = true).assertTextContains("Use at most 5 tags")
    }

    @Test
    fun exploreListsDappsAndOpensThemInTheBrowser() {
        launchSignedIn()
        push(AppScreen.Explore)
        waitFor("dapp:PeakD")
        compose.onNodeWithText("Explore Ecosystem").assertExists()
        compose.onNodeWithTag("dapp:PeakD").performClick()
        // the browser wave: the dApp opens in the browser's tab, no placeholder any more (PlatformFlowsTest)
        compose.await("peakd in the tab") { idleMain(); navigator.top.peek<com.ecency.core.app.DappBrowserModel>("browser")?.current?.activeTab?.url == "https://peakd.com" }
        waitFor("dappPage")
    }

    @Test
    fun aTagLinkOpensTheTagScreen() {
        launchSignedIn()
        push(AppScreen.TagResult("photography", "hot"))
        waitFor("searchInput")
        waitFor("card:alice/hot-one")
    }

    // the scroll to top above the system navigation bar on these pushed screens (no tab bar below them)

    private fun assertScrollTopAboveTheNavigationBar(screen: AppScreen, ready: String) {
        com.ecency.app.ui.DebugLaunch.demoScrollTop = true
        try {
            launchSignedIn()
            push(screen)
            waitFor(ready)
            waitFor("scrollTop")
            val navBar = 126
            compose.runOnUiThread {
                val root = compose.activity.findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0)
                val insets = androidx.core.view.WindowInsetsCompat.Builder()
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), androidx.core.graphics.Insets.of(0, 0, 0, navBar))
                    .setVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars(), true).build()
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root, insets)
            }
            compose.waitForIdle()
            val window = compose.activity.window.decorView.height.toFloat()
            val top = compose.onNodeWithTag("scrollTop", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val margin = 16 * compose.activity.resources.displayMetrics.density
            assertEquals("16 dp above the navigation bar ($top in $window)", window - navBar - margin, top.bottom, 1f)
        } finally {
            com.ecency.app.ui.DebugLaunch.demoScrollTop = false
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun theCommunitysScrollTopSitsAboveTheNavigationBar() =
        assertScrollTopAboveTheNavigationBar(AppScreen.Community("hive-100001"), "feed")

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun theTagResultsScrollTopSitsAboveTheNavigationBar() =
        assertScrollTopAboveTheNavigationBar(AppScreen.TagResult("photography", "hot"), "card:alice/hot-one")
}

