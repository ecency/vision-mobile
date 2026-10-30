package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
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
import com.ecency.core.app.NavState
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.HasSocketFactory
import com.ecency.core.hive.PrivateKey
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.KeyVaultAsync
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.ui.unit.dp
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

/**
 * A node and Ecency API for the profile area (nothing on the network): "alice" with a profile, follows, RC and a
 * blog post, the signed-in "tester" (posting key [key], Ecency token), bookmarks, favourites and tags.
 */
class ProfileNode {
    val key: PrivateKey = PrivateKey.fromSeed("ecency-native-spike-profile-ui-throwaway")
    val sent: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val requests: MutableList<AuthHttpRequest> = Collections.synchronizedList(mutableListOf())
    val store = TestStore()
    private val nowS = 1_791_288_000L

    private fun account(name: String, posting: String) = """{"name":"$name","created":"2018-05-01T10:00:00","reputation":0,"post_count":3,""" +
        """"vesting_shares":"1000.000000 VESTS","delegated_vesting_shares":"0.000000 VESTS","received_vesting_shares":"0.000000 VESTS",""" +
        """"vesting_withdraw_rate":"0.000000 VESTS","to_withdraw":0,"withdrawn":0,"voting_manabar":{"current_mana":"873000000","last_update_time":$nowS},""" +
        """"posting":{"weight_threshold":1,"account_auths":[],"key_auths":[["${key.publicKey()}",1]]},"memo_key":"${key.publicKey()}",""" +
        """"json_metadata":"","posting_json_metadata":${Json.encodeToString(posting)}}"""

    private val profiles = mapOf(
        "alice" to """{"profile":{"name":"Alice A","about":"Hive dev","location":"Berlin","website":"alice.example"}}""",
        "tester" to """{"profile":{"name":"Tester","about":"Old about"}}""",
    )

    private fun post(author: String, permlink: String, parent: String? = null) = """{"author":"$author","permlink":"$permlink",""" +
        (parent?.let { """"parent_author":"$it","parent_permlink":"p",""" } ?: "") +
        """"category":"hive-1","title":"Title $permlink","body":"Body of $permlink","json_metadata":{"tags":["t"]},"created":"2026-10-06T10:00:00",""" +
        """"updated":"2026-10-06T10:00:00","depth":${if (parent == null) 0 else 1},"children":0,"net_rshares":0,"author_reputation":60,"payout":1.0,""" +
        """"is_paidout":false,"payout_at":"2026-10-13T10:00:00","pending_payout_value":"1.000 HBD","author_payout_value":"0.000 HBD",""" +
        """"curator_payout_value":"0.000 HBD","promoted":"0.000 HBD","max_accepted_payout":"1000000.000 HBD","percent_hbd":10000,"replies":[],""" +
        """"active_votes":[],"stats":{"hide":false,"gray":false,"total_votes":0,"flag_weight":0},"reblogs":0,"beneficiaries":[],"blacklists":[],"url":"/x"}"""

    val transport = RpcTransport { _, body ->
        sent += body
        val req = Json.parseToJsonElement(body).jsonObject
        val method = req["method"]!!.jsonPrimitive.content
        val id = req["id"]!!.jsonPrimitive.content
        val params = req["params"]
        fun ok(result: String) = HttpResult(200, """{"jsonrpc":"2.0","result":$result,"id":$id}""")
        when (method) {
            "condenser_api.get_accounts" -> {
                val names = params!!.jsonArray[0].jsonArray.map { it.jsonPrimitive.content }
                ok(names.mapNotNull { n -> profiles[n]?.let { account(n, it) } }.joinToString(",", "[", "]"))
            }
            "bridge.get_profile" -> ok(
                """{"name":"${params!!.jsonObject["account"]!!.jsonPrimitive.content}","reputation":64.7,"stats":{"followers":3,"following":4},"metadata":{"profile":{"name":"x"}}}""",
            )
            "bridge.get_profiles" -> ok(params!!.jsonObject["accounts"]!!.jsonArray.joinToString(",", "[", "]") {
                """{"name":${it},"reputation":55,"active":"2026-10-01T00:00:00"}"""
            })
            "condenser_api.get_follow_count" -> ok("""{"account":"alice","follower_count":1234,"following_count":56}""")
            "condenser_api.get_followers" -> ok("""[{"follower":"bob","following":"alice"},{"follower":"carol","following":"alice"},{"follower":"dave","following":"alice"}]""")
            "condenser_api.get_following" -> ok("[]")
            "bridge.get_relationship_between_accounts" -> ok("""{"follows":false,"ignores":false,"blacklists":false,"follows_muted":false,"follows_blacklists":false}""")
            "rc_api.find_rc_accounts" -> ok("""{"rc_accounts":[{"account":"alice","rc_manabar":{"current_mana":"500","last_update_time":$nowS},"max_rc":"1000"}]}""")
            "bridge.get_account_posts" -> {
                val sort = params!!.jsonObject["sort"]!!.jsonPrimitive.content
                val account = params.jsonObject["account"]!!.jsonPrimitive.content
                if (params.jsonObject["start_author"] != null) ok("[]")
                else if (sort == "comments") ok("[${post(account, "re-one", parent = "bob")}]")
                else ok("[${post(account, "$sort-one")}]")
            }
            "condenser_api.get_dynamic_global_properties" -> ok(
                """{"head_block_number":100003,"head_block_id":"000186a3${"11".repeat(16)}","time":"2026-10-06T11:59:00"}""",
            )
            "condenser_api.get_block_header" -> ok("""{"previous":"000186a0${"22".repeat(16)}"}""")
            "condenser_api.broadcast_transaction" -> ok("null")
            else -> HttpResult(500, "no fixture")
        }
    }

    private val http = AuthHttp { request ->
        requests += request
        val path = request.url.substringAfter("/private-api/", "")
        when {
            request.url.endsWith("/auth-api/hs-token-refresh") ->
                HttpResult(200, """{"username":"tester","access_token":"access-tester","refresh_token":"refresh-tester","expires_in":604800}""")
            path.startsWith("pro-members") -> HttpResult(200, """{"members":["alice"],"count":1}""")
            path.startsWith("favorites-check") -> HttpResult(200, "false")
            path.startsWith("bookmarks?") -> HttpResult(200, """{"data":[{"_id":"b1","author":"alice","permlink":"saved","timestamp":1}],"pagination":{"total":1,"limit":20,"offset":0,"has_next":false}}""")
            path.startsWith("favorites?") -> HttpResult(200, """{"data":[{"_id":"f1","account":"bob","timestamp":1}],"pagination":{"total":1,"limit":20,"offset":0,"has_next":false}}""")
            path.startsWith("favorite-tags?") -> HttpResult(200, """{"data":[{"tag":"photography"}],"pagination":{"total":1,"limit":100,"offset":0,"has_next":false}}""")
            path.isNotEmpty() -> HttpResult(200, "{}")
            else -> HttpResult(400, "{}")
        }
    }

    fun env() = AppEnvironment(
        api = EcencyApi(listOf("https://node-a.example"), transport),
        vault = KeyVaultAsync(KeyVault(store), Dispatchers.IO),
        renderer = InterimPostBodyRenderer("https://i.ecency.com"),
        dispatchers = AppDispatchers(Dispatchers.Main, Dispatchers.Default, Dispatchers.IO),
        proxyBase = "https://i.ecency.com",
        debugKeyImport = true,
        nowMillis = { nowS * 1000 },
        auth = AuthServices(http, HasSocketFactory { error("no relay") }),
    )

    fun signIn() {
        val vault = KeyVault(store)
        vault.save(VaultAccount("tester", AuthType.POSTING_KEY, postingKey = key.toWif(), accessToken = "access-tester"))
        vault.setCurrentUsername("tester")
    }

    fun broadcasts() = sent.filter { "broadcast_transaction" in it }

    fun privateCalls(path: String) = requests.filter { it.url.contains("/private-api/$path") }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProfileFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = ProfileNode()
    private val env by lazy { node.env() }
    private val opened = mutableListOf<String>()

    private val host = object : AppHost {
        override fun openExternal(url: String) { opened += url }
        override fun toast(text: String) {}
        override fun copy(text: String) {}
        override fun buildNumber() = "7"
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

    private lateinit var navigator: Navigator

    private fun launch(vararg screens: AppScreen) {
        var state = NavState()
        screens.forEach { state = state.push(it) }
        navigator = Navigator(state)
        compose.setContent { EcencyApp(env, host, navigator) }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String) {
        compose.await("exists(tag)") { idleMain(); exists(tag) }
        compose.waitForIdle()
    }

    /** Runs what the shared models posted to the main looper (Robolectric pauses it between frames). */
    private fun idleMain() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun hasTextNode(text: String) = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun profileShowsTheSummaryTabsAndBlog() {
        node.signIn()
        launch(AppScreen.Profile("alice"))
        waitFor("profileSummary")
        compose.onNodeWithTag("profileName", useUnmergedTree = true).assertTextContains("Alice A")
        // no reputation next to the name or on any avatar (only the quick profile sheet shows it)
        compose.onNodeWithTag("profileUsername", useUnmergedTree = true).assertTextEquals("@alice")
        assertFalse("no reputation on the profile", hasTextNode("(64)"))
        compose.onNodeWithTag("profileAbout", useUnmergedTree = true).assertTextContains("Hive dev")
        compose.onNodeWithTag("followers").assertTextContains("1.2K", substring = true)
        waitFor("proBadge")
        waitFor("follow")
        waitFor("tab:comments")
        compose.onNodeWithTag("profileList").performScrollToKey("alice/blog-one".let { "blog:$it" })
        waitFor("card:alice/blog-one")
        assertTrue("no reputation badge on the card avatars", compose.onAllNodes(hasTestTag("reputation"), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("profileList").performScrollToIndex(0)
        // a tap on the bars shows the percent texts
        compose.onNodeWithTag("manaBars").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitFor("vpText")
        compose.onNodeWithTag("vpText", useUnmergedTree = true).assertTextContains("Voting power: 87.3%", substring = true)
        compose.onNodeWithTag("rcText", useUnmergedTree = true).assertTextContains("Resource Credits: 50.0%", substring = true)
        // the website opens in the browser with https
        compose.onNodeWithTag("website").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals(listOf("https://alice.example"), opened)
        // comments tab lists comment rows
        compose.onNodeWithTag("tab:comments").performClick()
        compose.await("runCatching { compose.onNodeWithTag(\"profileList\").performScrollToKey(\"comments:alice/r...") { idleMain(); runCatching { compose.onNodeWithTag("profileList").performScrollToKey("comments:alice/re-one") }.isSuccess }
        waitFor("comment:alice/re-one")
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun theSummaryCollapsesOnScrollDownAndOpensAtTheTopOrOnTap() {
        launch(AppScreen.Profile("alice"))
        waitFor("card:alice/blog-one")
        assertFalse(exists("profileCompactHeader"))
        // RN profileView: scrolling the list down closes the summary; the header with the user and the tabs stay
        compose.onNodeWithTag("profileList").performTouchInput { swipeUp(durationMillis = 600) }
        waitFor("profileCompactHeader")
        compose.await("!exists(\"profileSummary\")") { idleMain(); !exists("profileSummary") }
        assertTrue(exists("profileTabs"))
        assertTrue(exists("card:alice/blog-one"))
        // a tap on the header opens it again
        compose.onNodeWithTag("profileExpand").performClick()
        waitFor("profileSummary")
        compose.await("!exists(\"profileCompactHeader\")") { idleMain(); !exists("profileCompactHeader") }
        // closed again, then back to the top by scrolling: it opens without a tap
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("profileList").performTouchInput { swipeUp(durationMillis = 600) }
        waitFor("profileCompactHeader")
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("profileList").performTouchInput { swipeDown(durationMillis = 600) }
        waitFor("profileSummary")
        compose.await("!exists(\"profileCompactHeader\")") { idleMain(); !exists("profileCompactHeader") }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun aShortListThatNoLongerScrollsOnceClosedStaysClosed() {
        launch(AppScreen.Profile("alice"))
        // one card: it scrolls under the open summary, and fits once the summary is gone
        waitFor("card:alice/blog-one")
        compose.onNodeWithTag("profileList").performTouchInput { swipeUp(durationMillis = 600) }
        waitFor("profileCompactHeader")
        // the list clamps to its top once it has the room: layout, not the reader
        repeat(4) { compose.mainClock.advanceTimeBy(500); idleMain() }
        compose.waitForIdle()
        assertTrue("held closed, no open and close loop", exists("profileCompactHeader"))
        assertFalse(exists("profileSummary"))
        // a short pull (under the 60 dp reveal run) does not undo the hold; a full pull opens it
        compose.onNodeWithTag("profileList").performTouchInput { swipeDown(startY = top + 40f, endY = top + 40f + 20.dp.toPx(), durationMillis = 400) }
        repeat(2) { compose.mainClock.advanceTimeBy(500); idleMain() }
        compose.waitForIdle()
        assertTrue("a 20 dp pull keeps it closed", exists("profileCompactHeader"))
        compose.onNodeWithTag("profileList").performTouchInput { swipeDown(durationMillis = 600) }
        waitFor("profileSummary")
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun theClosedSummaryStaysClosedAfterAPostAndBackAndATabSwitchOpensIt() {
        launch(AppScreen.Profile("alice"))
        waitFor("card:alice/blog-one")
        compose.onNodeWithTag("profileList").performTouchInput { swipeUp(durationMillis = 600) }
        waitFor("profileCompactHeader")
        compose.runOnIdle { navigator.push(AppScreen.Post("alice", "blog-one")) }
        compose.await("profile covered") { idleMain(); !exists("profileCompactHeader") }
        compose.runOnIdle { navigator.pop() }
        waitFor("profileCompactHeader")
        compose.waitForIdle()
        assertFalse("kept in the stack entry", exists("profileSummary"))
        // a tab switch opens it over the new list
        compose.onNodeWithTag("tab:posts").performClick()
        waitFor("profileSummary")
        compose.await("!exists(\"profileCompactHeader\")") { idleMain(); !exists("profileCompactHeader") }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun aPhoneSizedOpenSummaryShowsCountsAndBarsWithoutScrollingInside() {
        launch(AppScreen.Profile("alice"))
        waitFor("manaBars")
        compose.waitForIdle()
        val bars = compose.onNodeWithTag("manaBars").fetchSemanticsNode().boundsInRoot
        val followers = compose.onNodeWithTag("followers").fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNodeWithTag("profileTabs").fetchSemanticsNode().boundsInRoot
        assertTrue("bars $bars above the tabs $tabs", bars.bottom <= tabs.top + 1f)
        assertTrue(followers.bottom <= tabs.top)
    }

    @Test
    fun theWavesTabListsTheAuthorsWaves() {
        val asked = java.util.Collections.synchronizedList(mutableListOf<String>())
        env.waves.api = com.ecency.core.api.waves.WavesApi(com.ecency.core.auth.AuthHttp { request ->
            asked += request.url
            com.ecency.core.api.rpc.HttpResult(
                200,
                if ("author=alice" in request.url && "cursor=" !in request.url) {
                    """[{"id":1,"author":"alice","permlink":"wave-own","title":"","body":"A wave on my profile","json_metadata":{"app":"ecency/4.0.0-mobile"},
                       "timestamp":"2026-10-06T11:00:00+00:00","pending_payout_value":"0.100 HBD","net_votes":1,"children":0,"host":"ecency.waves","_cursor":"c1"}]"""
                } else {
                    "[]"
                },
            )
        })
        node.signIn()
        launch(AppScreen.Profile("alice"))
        waitFor("profileSummary")
        waitFor("tab:waves")
        compose.onNodeWithTag("tab:waves").performClick()
        compose.await("runCatching { compose.onNodeWithTag(\"profileList\").performScrollToKey(\"waves:alice/wave...") { idleMain(); runCatching { compose.onNodeWithTag("profileList").performScrollToKey("waves:alice/wave-own") }.isSuccess }
        waitFor("wave:alice/wave-own")
        assertFalse(exists("placeholderTab"))
        // RN: the author filter alone, no observer (a mute of theirs must not blank their own profile)
        assertEquals("https://ecency.com/private-api/waves/feed?limit=20&author=alice", asked.first { "/waves/feed" in it })
        assertTrue(asked.none { "promoted" in it })
    }

    @Test
    fun followBroadcastsAndFlipsTheButton() {
        node.signIn()
        launch(AppScreen.Profile("alice"))
        waitFor("follow")
        compose.onNodeWithTag("follow", useUnmergedTree = true).assertTextContains("Follow")
        compose.onNodeWithTag("follow").performClick()
        compose.await("node.broadcasts().isNotEmpty()") { idleMain(); node.broadcasts().isNotEmpty() }
        compose.await("hasTextNode(\"Unfollow\")") { idleMain(); hasTextNode("Unfollow") }
        assertTrue(node.broadcasts().single().contains("""\"what\":[\"blog\"]"""))
    }

    @Test
    fun reportAsksThenMutesAndShowsTheMutedView() {
        node.signIn()
        launch(AppScreen.Profile("alice"))
        waitFor("profileMenu")
        compose.onNodeWithTag("profileMenu").performClick()
        waitFor("menu:REPORT")
        compose.onNodeWithTag("menu:REPORT").performClick()
        waitFor("confirmReport")
        assertTrue(hasTextNode("Are you sure you want report?"))
        compose.onNodeWithTag("confirmReport").performClick()
        compose.await("runCatching { compose.onNodeWithTag(\"profileList\").performScrollToKey(\"muted\") }.isSuccess") { idleMain(); runCatching { compose.onNodeWithTag("profileList").performScrollToKey("muted") }.isSuccess }
        waitFor("mutedView")
        assertEquals(1, node.privateCalls("report").size)
        assertTrue(node.broadcasts().single().contains("""\"what\":[\"ignore\"]"""))
    }

    @Test
    fun signedOutProfileHasNoActions() {
        launch(AppScreen.Profile("alice"))
        waitFor("profileSummary")
        assertFalse(exists("follow"))
        assertFalse(exists("profileMenu"))
        assertFalse(exists("editProfile"))
    }

    @Test
    fun followersOpenTheListAndSearchFilters() {
        launch(AppScreen.Profile("alice"))
        waitFor("followers")
        // the summary scrolls inside its cap on a small screen
        compose.onNodeWithTag("followers").performScrollTo().performClick()
        waitFor("user:carol")
        assertTrue(hasTextNode("Follower (1234)"))
        compose.onNodeWithTag("searchToggle").performClick()
        compose.onNodeWithTag("followsSearch").performTextInput("CAR")
        compose.await("!exists(\"user:bob\")") { idleMain(); !exists("user:bob") }
        assertTrue(exists("user:carol"))
        compose.onNodeWithTag("user:carol").performClick()
        compose.await("navigator.screens.last() == AppScreen.Profile(\"carol\")") { idleMain(); navigator.screens.last() == AppScreen.Profile("carol") }
    }

    @Test
    fun theAvatarAndCoverOfferRnsImageSources() {
        node.signIn()
        launch(AppScreen.Profile("tester"))
        waitFor("editProfile")
        compose.onNodeWithTag("editProfile").performClick()
        waitFor("editAvatar")
        // the pen badge is outside the avatar's circle clip: its far corner pokes past the circle and is still
        // drawn (it was a child of the clipped avatar and lost that corner)
        val circle = compose.onNodeWithTag("editAvatarCircle", useUnmergedTree = true).fetchSemanticsNode()
        val badgeNode = compose.onNodeWithTag("editAvatarBadge", useUnmergedTree = true).fetchSemanticsNode()
        var up = badgeNode.parent
        while (up != null) { assertFalse("the badge is not inside the clipped circle", up.id == circle.id); up = up.parent }
        val a = circle.boundsInRoot
        val b = badgeNode.boundsInRoot
        assertEquals(32f * compose.density.density, b.width, 1f)
        val r = a.width / 2
        val dx = b.right - a.center.x
        val dy = b.bottom - a.center.y
        assertTrue("the badge reaches past the circle", dx * dx + dy * dy > r * r)
        compose.onNodeWithTag("editAvatar").performClick()
        waitFor("source:GALLERY")
        // RN OptionsModal: gallery, camera and (Android) files, plus the native link entry
        for (tag in listOf("source:CAMERA", "source:FILES", "source:link")) assertTrue(tag, exists(tag))
        assertTrue(hasTextNode(com.ecency.core.app.ProfileEditCopy.OPEN_GALLERY))
        // the chooser sheet: each route says what it does, Cancel closes it without opening anything
        assertTrue(exists("mediaSourceSheet"))
        for (source in com.ecency.core.app.MediaSourceMenus.profileImage(camera = true)) assertTrue(source.source.name, hasTextNode(source.source.subtitle))
        // a click through semantics: sheet rows can sit outside Robolectric's touch bounds
        compose.onNodeWithTag("mediaSourceCancel").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.await("the sheet closed") { idleMain(); !exists("mediaSourceSheet") }
        assertFalse(exists("imageUploading"))
    }

    @Test
    fun ownProfileEditSavesAccountUpdate2AndCloses() {
        node.signIn()
        launch(AppScreen.Profile("tester"))
        waitFor("editProfile")
        compose.onNodeWithTag("editProfile").performClick()
        waitFor("field:NAME")
        assertFalse(exists("saveProfile"))
        compose.onNodeWithTag("field:NAME").performTextClearance()
        compose.onNodeWithTag("field:NAME").performTextInput("New Name")
        waitFor("saveProfile")
        // an image link for the cover
        compose.onNodeWithTag("editCover").performClick()
        waitFor("source:link")
        compose.onNodeWithTag("source:link").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitFor("imageUrl")
        compose.onNodeWithTag("imageUrl").performTextInput("https://img.example/cover.png")
        compose.onNodeWithTag("imageUrlOk").performClick()
        compose.onNodeWithTag("saveProfile").performClick()
        compose.await("node.broadcasts().isNotEmpty()") { idleMain(); node.broadcasts().isNotEmpty() }
        compose.await("navigator.screens.last() == AppScreen.Profile(\"tester\")") { idleMain(); navigator.screens.last() == AppScreen.Profile("tester") }
        val tx = node.broadcasts().single()
        assertTrue(tx.contains("account_update2"))
        assertTrue(tx.contains("""\"name\":\"New Name\""""))
        assertTrue(tx.contains("""\"cover_image\":\"https://img.example/cover.png\""""))
        assertTrue(tx.contains("""\"about\":\"Old about\""""))
        compose.await("hasTextNode(\"New Name\")") { idleMain(); hasTextNode("New Name") }
    }

    @Test
    fun bookmarksListAndRemoveAfterConfirm() {
        node.signIn()
        launch(AppScreen.Bookmarks(0))
        waitFor("user:alice")
        assertTrue(hasTextNode("alice/saved"))
        compose.onNodeWithTag("user:alice").performTouchInput { longClick() }
        waitFor("confirmRemove")
        compose.onNodeWithTag("confirmRemove").performClick()
        compose.await("node.privateCalls(\"bookmarks-delete\").isNotEmpty()") { idleMain(); node.privateCalls("bookmarks-delete").isNotEmpty() }
        val body = Json.parseToJsonElement(node.privateCalls("bookmarks-delete").single().body!!).jsonObject
        assertEquals("b1", body["id"]!!.jsonPrimitive.content)
        compose.onNodeWithTag("btab:TAGS").performClick()
        waitFor("tag:photography")
        compose.onNodeWithTag("btab:FAVORITES").performClick()
        waitFor("user:bob")
    }

    @Test
    fun bookmarksNeedAnAccount() {
        launch()
        compose.runOnUiThread { navigator.pushGated(AppScreen.Bookmarks(0), loggedIn = false) }
        compose.await("navigator.screens.lastOrNull() == AppScreen.Login") { idleMain(); navigator.screens.lastOrNull() == AppScreen.Login }
    }

    @Test
    fun accountListFiltersAndOpensProfiles() {
        launch(AppScreen.AccountList("Voters", listOf(com.ecency.core.app.AccountListEntry("alice", 1_791_287_000_000L), com.ecency.core.app.AccountListEntry("bob"))))
        waitFor("user:alice")
        compose.onNodeWithTag("accountSearch").performTextInput("bo")
        compose.await("!exists(\"user:alice\")") { idleMain(); !exists("user:alice") }
        compose.onNodeWithTag("user:bob").performClick()
        compose.await("navigator.screens.last() == AppScreen.Profile(\"bob\")") { idleMain(); navigator.screens.last() == AppScreen.Profile("bob") }
    }

    @Test
    fun anotherAccountsWalletTabShowsItsDetailsReadOnly() {
        launch(AppScreen.Profile("alice", "wallet"))
        waitFor("profileList")
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithTag("profileList").performScrollToNode(hasTestTag("profileWallet")) }.isSuccess
        }
        waitFor("walletLine:Hive")
        assertTrue(exists("walletLine:Staked HIVE"))
        assertTrue(exists("walletLine:Savings"))
        assertTrue("read only: no claim button", !exists("claim:HIVE"))
    }

    // owner note 10-29: no gap between the top and the cover (Android shows no app bar while the summary is open)

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test
    fun theCoverStartsAtTheTopOfAnotherProfileAndTheTabsFollowTheSummary() {
        launch(AppScreen.Profile("alice"))
        waitFor("profileSummary")
        val cover = bounds("profileCover")
        assertEquals("the cover $cover at the top of the profile", bounds("profile").top, cover.top, 0.5f)
        assertEquals("the tabs right under the summary", bounds("profileSummary").bottom, bounds("profileTabs").top, 0.5f)
    }

    @Test
    fun theCoverStartsAtTheTopOfTheOwnProfileInDark() {
        node.signIn()
        navigator = Navigator(NavState().push(AppScreen.Profile("tester")))
        compose.setContent { com.ecency.app.ui.EcencyTheme(dark = true) { EcencyApp(env, host, navigator) } }
        waitFor("profileSummary")
        assertTrue("the own profile", exists("editProfile"))
        assertEquals(bounds("profile").top, bounds("profileCover").top, 0.5f)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun theProfilesScrollTopSitsAboveTheNavigationBar() {
        com.ecency.app.ui.DebugLaunch.demoScrollTop = true
        try {
            launch(AppScreen.Profile("alice"))
            waitFor("profileScrollTop")
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
            val top = bounds("profileScrollTop")
            val margin = 16 * compose.activity.resources.displayMetrics.density
            assertEquals("16 dp above the navigation bar ($top in $window)", window - navBar - margin, top.bottom, 1f)
        } finally {
            com.ecency.app.ui.DebugLaunch.demoScrollTop = false
        }
    }
}

