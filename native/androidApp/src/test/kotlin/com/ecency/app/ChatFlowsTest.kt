package com.ecency.app

import androidx.compose.ui.test.onNodeWithText

import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode

import androidx.compose.ui.semantics.getOrNull

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
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
import com.ecency.core.app.AppTab
import com.ecency.core.app.AuthServices
import com.ecency.core.app.DemoChat
import com.ecency.core.app.InterimPostBodyRenderer
import com.ecency.core.app.NavState
import com.ecency.core.app.SecurityConfig
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.HasSocketFactory
import com.ecency.core.hive.PrivateKey
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.KeyVaultAsync
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.VaultAccount
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

/** The offline chat API (DemoChat) behind the app, nothing on the network. */
class ChatNode {
    val key: PrivateKey = PrivateKey.fromSeed("ecency-native-spike-chats-ui-throwaway")
    val store = TestStore()
    val nowMillis = 1_791_288_000_000L
    val chat = DemoChat { nowMillis }
    val requests: MutableList<AuthHttpRequest> = Collections.synchronizedList(mutableListOf())
    var mono = 0L

    private val http = AuthHttp { request ->
        requests += request
        chat.http(request) ?: when {
            request.url.endsWith("/auth-api/hs-token-refresh") -> HttpResult(503, "{}")
            "/private-api/" in request.url -> HttpResult(200, "{}")
            else -> HttpResult(400, "{}")
        }
    }

    private val transport = RpcTransport { _, body ->
        val req = Json.parseToJsonElement(body).jsonObject
        val id = req["id"]!!.jsonPrimitive.content
        when (req["method"]!!.jsonPrimitive.content) {
            "condenser_api.get_accounts", "condenser_api.get_following" -> HttpResult(200, """{"jsonrpc":"2.0","result":[],"id":$id}""")
            "bridge.get_community" -> HttpResult(200, """{"jsonrpc":"2.0","result":{"name":"hive-125125","title":"Ecency","team":[]},"id":$id}""")
            else -> HttpResult(500, "no fixture")
        }
    }

    fun env() = AppEnvironment(
        api = EcencyApi(listOf("https://node-a.example"), transport),
        vault = KeyVaultAsync(KeyVault(store), Dispatchers.IO),
        renderer = InterimPostBodyRenderer("https://i.ecency.com"),
        dispatchers = AppDispatchers(Dispatchers.Main, Dispatchers.Default, Dispatchers.IO),
        proxyBase = "https://i.ecency.com",
        debugKeyImport = true,
        nowMillis = { nowMillis },
        security = SecurityConfig(pinIterations = 16, monotonicMillis = { mono }),
        auth = AuthServices(http, HasSocketFactory { host -> if (DemoChat.WEBSOCKET_PATH in host) chat.socket() else error("no relay") }),
    )

    fun signIn(pin: String? = null, vararg users: String = arrayOf("tester")) {
        val vault = KeyVault(store)
        users.forEach { vault.save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = key.toWif(), accessToken = "access-$it")) }
        vault.setCurrentUsername(users.first())
        if (pin != null) PinLock(vault, 16).setPin(pin)
    }

    fun chatCalls(): List<String> = chat.sent.toList().map { "${it.method} ${it.url.substringAfter("/api/mattermost/")}" }

    fun body(r: AuthHttpRequest) = Json.parseToJsonElement(r.body ?: "{}").jsonObject
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ChatFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = ChatNode()
    private val env by lazy { node.env() }
    private val copied = mutableListOf<String>()

    private val host = object : AppHost {
        override fun openExternal(url: String) {}
        override fun toast(text: String) {}
        override fun copy(text: String) {
            copied += text
        }
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

    private fun launch(tab: AppTab = AppTab.CHATS, fontScale: Float = 1f) {
        navigator = Navigator(NavState(tab = tab))
        compose.setContent { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, fontScale)) { EcencyApp(env, host, navigator) } }
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun theListAndAThreadReadForTalkBackAndFitAtFontScale2() {
        node.signIn()
        launch(fontScale = 2f)
        waitFor("chatRow:ch-ecency")
        compose.assertAccessible("chats")
        compose.assertNothingClipped("chats")
        compose.onNodeWithTag("chatRow:ch-ecency").performClick()
        waitFor("composer")
        compose.await("messages") { compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("a message") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("bubble:") == true }).fetchSemanticsNodes().isNotEmpty() }
        compose.assertAccessible("chat thread")
        compose.assertNothingClipped("chat thread")
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /** Runs the main looper and moves its clock a little, so debounces (search, link cards) and delays come due. */
    private fun idleMain() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))

    private fun waitFor(tag: String) {
        compose.await("a node tagged $tag") { idleMain(); exists(tag) }
        compose.waitForIdle()
    }

    private fun waitUntil(what: () -> Boolean) {
        compose.await("the awaited condition") { idleMain(); what() }
        compose.waitForIdle()
    }

    /** A click through the node's semantics: sheet rows can sit outside Robolectric's touch bounds. */
    private fun tap(tag: String) = compose.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)

    private fun hasTextNode(text: String) = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun signedOutTheTabAsksToSignIn() {
        launch()
        waitFor("loginPrompt")
        assertTrue(hasTextNode("Sign in to start chatting with your communities."))
        assertTrue(node.chat.sent.isEmpty())
    }

    @Test
    fun theListShowsRnsRowsTheBadgeAndTheStatus() {
        node.signIn()
        launch(AppTab.FEED)
        waitFor("badge:CHATS")
        compose.onNodeWithTag("badge:CHATS", useUnmergedTree = true).assertTextContains("5")
        compose.onNodeWithTag("bottomTab:CHATS").performClick()
        waitFor("chatRow:ch-ecency")
        assertTrue(hasTextNode("Photography") && hasTextNode("Book club"))
        compose.onNodeWithTag("chatUnread:ch-ecency", useUnmergedTree = true).assertTextContains("3")
        assertTrue(hasTextNode("Connected"))
        // mark as read from the row's options
        tap("chatOptions:ch-ecency")
        waitFor("option:MARK_READ")
        assertTrue(exists("option:LEAVE_CHANNEL"))
        tap("option:MARK_READ")
        waitUntil { !exists("chatUnread:ch-ecency") }
        assertTrue("POST channels/ch-ecency/view" in node.chatCalls())
        waitUntil { env.badges.current.chats == 2 }
    }

    @Test
    fun aConversationLoadsMarksReadSendsAndReacts() {
        node.signIn()
        launch()
        waitFor("chatRow:ch-ecency")
        compose.onNodeWithTag("chatRow:ch-ecency").performClick()
        waitUntil { navigator.state.top == AppScreen.ChatThread("ch-ecency", "O") }
        waitFor("joins:group_p2")
        assertTrue(hasTextNode("2 people joined"))
        assertTrue(exists("unreadMarker"))
        assertTrue(hasTextNode("Ecency"))
        waitUntil { "POST channels/ch-ecency/view" in node.chatCalls() }
        // send
        compose.onNodeWithTag("composer").performTextInput("hello :smile:")
        compose.onNodeWithTag("send").performClick()
        waitUntil { hasTextNode("hello 😄") }
        val sent = node.chat.sent.last { it.method == "POST" && it.url.endsWith("/ch-ecency/posts") }
        assertEquals("hello 😄", node.body(sent)["message"]!!.jsonPrimitive.content)
        // react from the options sheet
        val question = node.chat.sent.size
        compose.onNodeWithTag("messages").performScrollToKey("p4")
        waitFor("message:p4")
        compose.onNodeWithTag("bubble:p4").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnLongClick)
        waitFor("quick:+1")
        tap("quick:+1")
        waitFor("reaction:p4:+1")
        assertTrue(node.chat.sent.drop(question).any { it.method == "POST" && it.url.endsWith("/p4/reactions") })
        // copy from the options sheet, opened the TalkBack way: the bubble's double tap names and opens the options
        val bubble = compose.onNodeWithTag("bubble:p4").fetchSemanticsNode()
        assertEquals(com.ecency.core.app.ChatUiCopy.MESSAGE_OPTIONS, bubble.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].label)
        compose.onNodeWithTag("bubble:p4").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitFor("option:COPY")
        tap("option:COPY")
        waitUntil { copied.isNotEmpty() }
        assertTrue(copied.last().startsWith("Has anyone tried"))
        // editing our own message: the banner's cancel button widens sideways only, so the banner keeps its text height
        compose.onNodeWithTag("messages").performScrollToNode(androidx.compose.ui.test.hasText("hello 😄", substring = true))
        val mine = compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("own message actions") {
            it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("messageActions:") == true
        }, useUnmergedTree = true).fetchSemanticsNodes().last().config[androidx.compose.ui.semantics.SemanticsProperties.TestTag]
        compose.onNodeWithTag("messages").performScrollToNode(hasTestTag(mine))
        compose.onNodeWithTag(mine, useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitFor("option:EDIT")
        tap("option:EDIT")
        waitFor("editingBanner")
        val banner = with(compose.density) { compose.onNodeWithTag("editingBanner", useUnmergedTree = true).fetchSemanticsNode().size.height.toDp() }
        val cancel = with(compose.density) { compose.onNodeWithTag("cancelEdit", useUnmergedTree = true).fetchSemanticsNode().size.height.toDp() }
        val label = with(compose.density) { compose.onNodeWithText(com.ecency.core.app.ChatThreadModel.EDITING, useUnmergedTree = true).fetchSemanticsNode().size.height.toDp() }
        assertTrue("cancel button $cancel tall", cancel.value <= 20f)
        assertTrue("editing banner $banner taller than its label $label", banner.value <= maxOf(label.value, cancel.value) + 0.5f)
    }

    @Test
    fun aMessageShowsWebsInlineMarksAndKeepsAngleBrackets() {
        node.signIn()
        launch()
        waitFor("chatRow:ch-ecency")
        compose.onNodeWithTag("chatRow:ch-ecency").performClick()
        waitUntil { navigator.state.top == AppScreen.ChatThread("ch-ecency", "O") }
        compose.onNodeWithTag("composer").performTextInput("**bold** `x_y` my_var ~~old~~ !ban <user> [30d]")
        compose.onNodeWithTag("send").performClick()
        val shown = "bold x_y my_var old !ban <user> [30d]"
        waitUntil { hasTextNode(shown) }
        val text = compose.onAllNodes(hasText(shown, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty() }
            .first { it.text == shown }
        fun styleOf(word: String) = text.spanStyles.filter { r -> text.text.substring(r.start, r.end) == word }.map { it.item }
        assertTrue(styleOf("bold").any { it.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold })
        assertTrue(styleOf("x_y").any { it.fontFamily == androidx.compose.ui.text.font.FontFamily.Monospace && it.background == com.ecency.app.ui.ChatCodeShade })
        assertTrue(styleOf("old").any { it.textDecoration == androidx.compose.ui.text.style.TextDecoration.LineThrough })
        assertTrue("snake_case stays plain", styleOf("my_var").all { it.fontStyle == null })
    }

    @Test
    fun searchJoinsAChannelAndANewGroupOpens() {
        node.signIn()
        launch()
        waitFor("chatRow:ch-ecency")
        compose.onNodeWithTag("chatsSearch").performTextInput("devs")
        waitFor("join:ch-public")
        tap("join:ch-public")
        waitUntil { navigator.state.top == AppScreen.ChatThread("ch-public", "O") }
        assertTrue("POST channels/ch-public/join" in node.chatCalls())
        compose.onNodeWithTag("back").performClick()
        waitFor("chatsNewGroup")
        tap("chatsNewGroup")
        waitFor("newGroupSheet")
        assertTrue(hasTextNode("Add 2 to 7 people to start a group conversation."))
        compose.onNodeWithTag("groupSearch").performTextInput("spike")
        waitFor("person:spike-bob")
        tap("person:spike-bob")
        waitFor("chip:spike-bob")
        compose.onNodeWithTag("groupSearch").performTextInput("carol")
        waitFor("person:spike-carol")
        tap("person:spike-carol")
        waitFor("chip:spike-carol")
        tap("groupCreate")
        waitUntil { (navigator.state.top as? AppScreen.ChatThread)?.type == "G" }
        assertEquals(listOf("spike-bob", "spike-carol"), node.body(node.chat.sent.last { it.url.endsWith("/group") })["usernames"]!!.toString().removeSurrounding("[", "]").split(",").map { it.trim('"') })
    }

    /** #3602 and #3603: "Ask Esie" opens her conversation; her name carries the official badge in the header, her messages and the list. */
    @Test
    fun askEsieOpensHerConversationMarkedOfficial() {
        node.signIn()
        launch(AppTab.FEED)
        compose.onNodeWithTag("account").performClick()
        waitFor("drawer")
        compose.onNodeWithTag("menu:ask_esie").performScrollTo().performClick()
        waitUntil { navigator.state.top == AppScreen.ChatThread("ch-dm-esie", "D") }
        assertFalse(navigator.state.drawerOpen)
        waitFor("threadTitle")
        compose.onNodeWithTag("threadTitle", useUnmergedTree = true).assertTextContains("esie")
        waitUntil { compose.onAllNodes(hasTestTag("officialBadge"), useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        // read as "Official Ecency account", never as a bare "Official"
        val labels = compose.onAllNodes(hasTestTag("officialBadge"), useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription] }
        assertEquals(List(2) { listOf("Official Ecency account") }, labels)
        assertTrue(node.chatCalls().contains("POST direct"))
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("bottomTab:CHATS").performClick()
        waitFor("chatRow:ch-dm-esie")
        // only Esie's row is official: spike-alice's direct message is not
        assertEquals(1, compose.onAllNodes(hasTestTag("officialBadge"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertTrue(compose.onAllNodes(hasTestTag("chatRow:ch-dm-alice").and(androidx.compose.ui.test.hasAnyDescendant(hasTestTag("officialBadge"))), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun aDirectMessageWarnsAndChatPrivacyIsASetting() {
        node.signIn()
        launch()
        waitFor("chatRow:ch-dm-alice")
        compose.onNodeWithTag("chatRow:ch-dm-alice").performClick()
        waitFor("dmWarning")
        assertTrue(hasTextNode("New conversation - Stay safe"))
        compose.onNodeWithTag("dmWarningClose").performClick()
        waitUntil { !exists("dmWarning") }
        navigator.push(AppScreen.Settings)
        waitFor("settings:dmPrivacy")
        waitUntil { node.chatCalls().any { it == "GET me/dm-privacy" } }
        compose.onNodeWithTag("settings:dmPrivacy").performClick()
        waitFor("dmPrivacy:FOLLOWERS")
        tap("dmPrivacy:FOLLOWERS")
        waitUntil { node.chat.privacy == "followers" }
        compose.onNodeWithTag("settings:dmPrivacy:value", useUnmergedTree = true).assertTextContains("Only people I follow")
    }

    @Test
    fun nothingOfAConversationShowsBehindTheLock() {
        node.signIn(pin = "1234")
        launch()
        waitFor("pinKey1")
        "1234".forEach { compose.onNodeWithTag("pinKey$it").performClick() }
        waitFor("chatRow:ch-ecency")
        compose.onNodeWithTag("chatRow:ch-ecency").performClick()
        waitFor("messages")
        env.appLock.onBackground()
        node.mono += env.security.lockTimeoutMillis + 1
        env.appLock.onForeground()
        waitUntil { env.appLock.current.hidesContent }
        waitUntil { !exists("messages") }
        assertFalse(exists("chatThread"))
        assertFalse(hasTextNode("Has anyone tried"))
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.onFirst() = this[0]
