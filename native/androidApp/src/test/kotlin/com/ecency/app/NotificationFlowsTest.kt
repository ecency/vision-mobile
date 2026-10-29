package com.ecency.app

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.push.FirebasePushPlatform
import com.ecency.app.push.PushIntents
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.Navigator
import com.ecency.app.ui.notificationTime
import com.ecency.core.api.EcencyApi
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.api.rpc.RpcTransport
import com.ecency.core.app.AppDispatchers
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.AppScreen
import com.ecency.core.app.AppTab
import com.ecency.core.app.AuthServices
import com.ecency.core.app.InterimPostBodyRenderer
import com.ecency.core.app.NavState
import com.ecency.core.app.NotificationFilter
import com.ecency.core.app.PushPlatform
import com.ecency.core.app.PushType
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Collections

/** A node and Ecency API for the notifications tab (nothing on the network): two accounts with their own inboxes. */
class NotificationsNode {
    val key: PrivateKey = PrivateKey.fromSeed("ecency-native-spike-notifications-ui-throwaway")
    val requests: MutableList<AuthHttpRequest> = Collections.synchronizedList(mutableListOf())
    val store = TestStore()
    val nowS = 1_791_288_000L

    @Volatile var unread = mapOf("tester" to 3, "other" to 1)

    private fun rows(user: String) = if (user == "tester") {
        """[
        {"id":"n-1","type":"vote","source":"bob","read":0,"timestamp":${nowS - 120},"author":"tester","permlink":"my-post","title":"My post","weight":10000},
        {"id":"n-2","type":"follow","source":"carol","read":0,"timestamp":${nowS - 7200},"follower":"carol"},
        {"id":"n-3","type":"transfer","source":"dave","read":1,"timestamp":${nowS - 3 * 86400},"amount":"1.000 HIVE"},
        {"id":"n-4","type":"reply","source":"erin","read":1,"timestamp":${nowS - 30 * 86400},"author":"erin","permlink":"re-my-post","parent_title":"My post","parent_img_url":"https://example.com/p.png"}
        ]"""
    } else {
        """[{"id":"o-1","type":"mention","source":"zed","read":0,"timestamp":${nowS - 60},"author":"zed","permlink":"others-post","title":"Only for other","post":true}]"""
    }

    private val http = AuthHttp { request ->
        requests += request
        val path = request.url.substringAfter("/private-api/", "")
        val body = request.body?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        val user = body?.get("code")?.jsonPrimitive?.content.orEmpty().substringAfter("access-")
        when {
            request.url.endsWith("/auth-api/hs-token-refresh") -> HttpResult(503, "{}")
            path == "notifications" -> HttpResult(200, if (body?.get("since")?.jsonPrimitive?.content.isNullOrEmpty()) rows(user) else "[]")
            path == "notifications/unread" -> HttpResult(200, """{"count":${unread[user] ?: 0}}""")
            path == "notifications/mark" -> HttpResult(200, """{"unread":${if (body?.get("id") == null) 0 else 2}}""")
            path.startsWith("leaderboard/") -> HttpResult(200, """[{"_id":"alice","count":5,"points":"12.500"},{"_id":"bob","count":9,"points":"8.000","quests_done":true}]""")
            path.startsWith("pro-members") -> HttpResult(200, """{"members":[],"count":0}""")
            path.isNotEmpty() -> HttpResult(200, "{}")
            else -> HttpResult(400, "{}")
        }
    }

    private val transport = RpcTransport { _, body ->
        val req = Json.parseToJsonElement(body).jsonObject
        val id = req["id"]!!.jsonPrimitive.content
        when (req["method"]!!.jsonPrimitive.content) {
            "condenser_api.get_accounts", "condenser_api.get_following" -> HttpResult(200, """{"jsonrpc":"2.0","result":[],"id":$id}""")
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
        nowMillis = { nowS * 1000 },
        security = SecurityConfig(pinIterations = NotificationFlowsTest.ITERATIONS),
        auth = AuthServices(http, HasSocketFactory { error("no relay") }),
    )

    fun signIn(vararg users: String = arrayOf("tester"), pin: String? = null) {
        val vault = KeyVault(store)
        users.forEach { vault.save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = key.toWif(), accessToken = "access-$it")) }
        vault.setCurrentUsername(users.first())
        if (pin != null) PinLock(vault, NotificationFlowsTest.ITERATIONS).setPin(pin)
    }

    fun calls(path: String) = requests.filter { it.url.endsWith("/private-api/$path") }

    fun field(request: AuthHttpRequest, key: String) = Json.parseToJsonElement(request.body!!).jsonObject[key]?.toString()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationFlowsTest {
    companion object {
        const val ITERATIONS = 16
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = NotificationsNode()
    private val env by lazy { node.env() }

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

    private lateinit var navigator: Navigator

    private fun launch(tab: AppTab = AppTab.NOTIFICATION, fontScale: Float = 1f) {
        navigator = Navigator(NavState(tab = tab))
        compose.setContent { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(androidx.compose.ui.platform.LocalDensity.current.density, fontScale)) { EcencyApp(env, host, navigator) } }
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun theRowsReadForTalkBackAndFitAtFontScale2() {
        node.signIn()
        launch(fontScale = 2f)
        waitFor("notification:n-1")
        compose.assertAccessible("notifications")
        compose.assertNothingClipped("notifications")
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun idleMain() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun waitFor(tag: String) {
        compose.await("exists(tag)") { idleMain(); exists(tag) }
        compose.waitForIdle()
    }

    private fun waitUntil(what: () -> Boolean) {
        compose.await("what()") { idleMain(); what() }
        compose.waitForIdle()
    }

    private fun hasTextNode(text: String) = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun rowState(id: String) = compose.onNodeWithTag("notification:$id", useUnmergedTree = true).fetchSemanticsNode()
        .config.getOrNull(SemanticsProperties.StateDescription)

    private fun type(pin: String) {
        pin.forEach { compose.onNodeWithTag("pinKey$it").performClick() }
        waitUntil { env.appLock.current.prompt?.isChecking != true }
    }

    @Test
    fun signedOutTheTabAsksToLoginAndTheLeaderboardStillShows() {
        launch()
        waitFor("loginPrompt")
        assertTrue(hasTextNode("Login to see"))
        assertFalse(exists("notificationList"))
        compose.onNodeWithTag("notificationsPage:Leaderboard").performClick()
        waitFor("leader:alice")
        // the daily board puts finished quests first
        assertTrue(hasTextNode("Top Users"))
        assertTrue(hasTextNode("12.500"))
        compose.onNodeWithTag("leaderboardFilter").performClick()
        waitFor("leaderboardFilter:1")
        compose.onNodeWithTag("leaderboardFilter:1").performClick()
        waitUntil { node.calls("leaderboard/week").isNotEmpty() }
        assertTrue(node.calls("notifications").isEmpty())
        assertTrue(node.calls("notifications/unread").isEmpty())
    }

    @Test
    fun theBadgeShowsOnEveryTabAndTheListUsesRnCopy() {
        node.signIn()
        launch(AppTab.FEED)
        waitFor("badge:NOTIFICATION")
        compose.onNodeWithTag("badge:NOTIFICATION", useUnmergedTree = true).assertTextContains("3")
        assertTrue("nothing is listed before the tab is opened", node.calls("notifications").isEmpty())

        compose.onNodeWithTag("bottomTab:NOTIFICATION").performClick()
        waitFor("notification:n-1")
        assertTrue(hasTextNode("bob voted 100%"))
        assertTrue(hasTextNode("My post"))
        assertTrue(hasTextNode("2 minutes ago"))
        assertEquals("Unread", rowState("n-1"))
        // row by row: the 48 dp touch targets leave fewer rows on Robolectric's small screen
        compose.onNodeWithTag("notificationRows").performScrollToKey("n-2")
        waitFor("notification:n-2")
        assertTrue(hasTextNode("carol followed you"))
        compose.onNodeWithTag("notificationRows").performScrollToKey("n-3")
        waitFor("notification:n-3")
        assertTrue(hasTextNode("dave transferred 1.000 HIVE"))
        assertTrue(hasTextNode("3 days ago"))
        assertEquals("Read", rowState("n-3"))
        // older than a week: a date, not "30 days ago"
        compose.onNodeWithTag("notificationRows").performScrollToKey("n-4")
        waitFor("notification:n-4")
        assertTrue(hasTextNode("erin replied to"))
        assertFalse(hasTextNode("30 days ago"))
        assertTrue(hasTextNode("2026"))
    }

    @Test
    fun tappingARowMarksItReadAndOpensItsTarget() {
        node.signIn()
        launch()
        waitFor("notification:n-1")
        compose.onNodeWithTag("notification:n-1").performClick()
        waitUntil { navigator.state.top == AppScreen.Post("tester", "my-post") }
        waitUntil { node.calls("notifications/mark").isNotEmpty() }
        assertEquals("\"n-1\"", node.field(node.calls("notifications/mark").single(), "id"))
        waitUntil { env.badges.current.notifications == 2 }

        navigator.pop()
        waitFor("notification:n-1")
        assertEquals("Read", rowState("n-1"))
        compose.onNodeWithTag("notificationRows").performScrollToKey("n-2")
        waitFor("notification:n-2")
        compose.onNodeWithTag("notification:n-2").performClick()
        waitUntil { navigator.state.top == AppScreen.Profile("carol") }
        navigator.pop()
        waitFor("notificationRows")
        compose.onNodeWithTag("notificationRows").performScrollToKey("n-3")
        waitFor("notification:n-3")
        compose.onNodeWithTag("notification:n-3").performClick()
        waitUntil { navigator.state.tab == AppTab.WALLET }
    }

    @Test
    fun theFilterIsSentToTheApiAndMarkAllClearsTheBadge() {
        node.signIn()
        launch()
        waitFor("notification:n-1")
        assertTrue(hasTextNode("ALL"))
        compose.onNodeWithTag("notificationFilter").performClick()
        waitFor("notificationFilter:3")
        compose.onNodeWithTag("notificationFilter:3").performClick()
        waitUntil { node.calls("notifications").any { node.field(it, "filter") == "\"rvotes\"" } }
        waitUntil { hasTextNode("VOTES") }
        waitFor("notification:n-1")

        compose.onNodeWithTag("markAllRead").performClick()
        waitUntil { node.calls("notifications/mark").isNotEmpty() }
        assertNull(node.field(node.calls("notifications/mark").single(), "id"))
        waitUntil { !exists("badge:NOTIFICATION") }
        assertEquals("Read", rowState("n-1"))
        assertEquals("Read", rowState("n-2"))
    }

    @Test
    fun aSwipeOrItsTalkBackActionMarksOneRow() {
        node.signIn()
        launch()
        waitFor("swipeToRead:n-1")
        // right to left marks it read without opening it
        compose.onNodeWithTag("swipeToRead:n-1").performTouchInput { swipeLeft() }
        waitUntil { node.calls("notifications/mark").isNotEmpty() }
        assertEquals("\"n-1\"", node.field(node.calls("notifications/mark").single(), "id"))
        waitUntil { rowState("n-1") == "Read" }
        assertEquals(AppTab.NOTIFICATION, navigator.state.tab)
        assertEquals(null, navigator.state.top)
        assertFalse("a read row has nothing to swipe", exists("swipeToRead:n-1"))
        // the TalkBack equivalent
        compose.onNodeWithTag("notificationRows").performScrollToKey("n-2")
        waitFor("notification:n-2")
        val actions = compose.onNodeWithTag("notification:n-2", useUnmergedTree = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        actions.single { it.label == com.ecency.core.app.ReadingUiCopy.MARK_AS_READ }.action()
        waitUntil { node.calls("notifications/mark").size == 2 }
        assertEquals("\"n-2\"", node.field(node.calls("notifications/mark").last(), "id"))
        waitUntil { rowState("n-2") == "Read" }
        val readActions = compose.onNodeWithTag("notification:n-2", useUnmergedTree = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        assertTrue(readActions.none { it.label == com.ecency.core.app.ReadingUiCopy.MARK_AS_READ })

        assertFalse("no read status menu any more", exists("notificationReadFilter"))
    }

    private fun chipOn(filter: NotificationFilter) = compose.onNodeWithTag("notificationChip:${filter.name}", useUnmergedTree = true)
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState) == androidx.compose.ui.state.ToggleableState.On

    @Test
    fun theQuickChipsSelectRepliesOrMentionsAndTheSelectedOneGoesBackToAll() {
        node.signIn()
        launch()
        waitFor("notification:n-1")
        assertFalse(chipOn(NotificationFilter.REPLIES))
        assertFalse(chipOn(NotificationFilter.MENTIONS))
        val chipNode = compose.onNodeWithTag("notificationChip:REPLIES", useUnmergedTree = true).fetchSemanticsNode()
        val chip = chipNode.config
        assertEquals(androidx.compose.ui.semantics.Role.Checkbox, chip.getOrNull(SemanticsProperties.Role))
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("a 48 dp touch area: ${chipNode.touchBoundsInRoot}", chipNode.touchBoundsInRoot.height >= 48 * density - 1f && chipNode.touchBoundsInRoot.width >= 48 * density - 1f)

        compose.onNodeWithTag("notificationChip:REPLIES").performClick()
        waitUntil { node.calls("notifications").any { node.field(it, "filter") == "\"replies\"" } }
        assertEquals(NotificationFilter.REPLIES, env.notifications.current.filter)
        waitUntil { chipOn(NotificationFilter.REPLIES) }
        assertFalse(chipOn(NotificationFilter.MENTIONS))
        assertTrue("the menu shows the chip's filter", hasTextNode("REPLIES"))

        // the selected chip again: back to All
        compose.onNodeWithTag("notificationChip:REPLIES").performClick()
        waitUntil { env.notifications.current.filter == NotificationFilter.ACTIVITIES }
        waitUntil { !chipOn(NotificationFilter.REPLIES) }
        assertNull(node.field(node.calls("notifications").last(), "filter"))

        // the menu keeps both: Mentions chosen there selects its chip
        compose.onNodeWithTag("notificationFilter").performClick()
        waitFor("notificationFilter:${NotificationFilter.MENTIONS.ordinal}")
        compose.onNodeWithTag("notificationFilter:${NotificationFilter.MENTIONS.ordinal}").performClick()
        waitUntil { chipOn(NotificationFilter.MENTIONS) }
        assertEquals("\"mentions\"", node.field(node.calls("notifications").last(), "filter"))
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun theFilterBarFitsANarrowPhoneAtFontScale2() {
        node.signIn()
        launch(fontScale = 2f)
        waitFor("notification:n-1")
        val width = compose.activity.resources.displayMetrics.widthPixels.toFloat()
        for (tag in listOf("notificationFilter", "notificationChip:REPLIES", "notificationChip:MENTIONS", "markAllRead")) {
            val box = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag ($box) on screen", box.left >= -1f && box.right <= width + 1f && box.width > 0f)
        }
        compose.assertNothingClipped("notifications")
    }

    @Test
    fun switchingAccountsShowsOnlyTheNewAccountsInbox() {
        node.signIn("tester", "other")
        launch()
        waitFor("notification:n-1")
        compose.onNodeWithTag("badge:NOTIFICATION", useUnmergedTree = true).assertTextContains("3")
        // off the main thread: the session model does its state changes on main
        kotlin.concurrent.thread { runBlocking { env.session.switchTo("other") } }
        waitFor("notification:o-1")
        assertFalse(exists("notification:n-1"))
        assertFalse(hasTextNode("bob voted"))
        assertTrue(hasTextNode("zed mentioned in"))
        compose.onNodeWithTag("badge:NOTIFICATION", useUnmergedTree = true).assertTextContains("1")
    }

    @Test
    fun aTappedPushStaysBehindThePinAndOpensAfterTheUnlock() {
        node.signIn(pin = "1234")
        launch(AppTab.FEED)
        waitFor("pinScreen")
        env.push.opened(mapOf("type" to "reply", "source" to "erin", "target" to "tester", "permlink1" to "re-my-post", "id" to "r-9"))
        compose.waitForIdle()
        idleMain()
        assertTrue(exists("pinScreen"))
        assertTrue("nothing was pushed while locked", navigator.state.stack.isEmpty())
        assertFalse(hasTextNode("erin"))
        assertFalse(exists("banner"))
        assertTrue(node.calls("notifications/mark").isEmpty())
        assertTrue(node.calls("notifications/unread").isEmpty())

        type("1234")
        waitUntil { navigator.state.top == AppScreen.Post("erin", "re-my-post") }
        waitUntil { node.calls("notifications/mark").isNotEmpty() }
        // a late tap: the activity is not among the rows the list would load
        assertEquals("\"r-9\"", node.field(node.calls("notifications/mark").single(), "id"))
    }

    @Test
    fun aTappedWriteReminderOpensTheRealEditorAndAnAccountSwitchClosesIt() {
        node.signIn("tester", "other")
        launch(AppTab.FEED)
        waitFor("bottomTab:FEED")
        env.push.opened(mapOf("type" to "inactive", "target" to "tester"))
        waitFor("editor")
        assertFalse("the built editor, not the Coming soon placeholder", exists("comingSoon"))
        assertTrue(navigator.state.top is AppScreen.Editor)

        // the editor belonged to tester: a switch closes it, and other's reminder does not open tester's editor
        kotlin.concurrent.thread { runBlocking { env.session.switchTo("other") } }
        waitUntil { env.session.current.username == "other" && navigator.state.stack.isEmpty() }
        assertFalse(exists("editor"))
        env.push.opened(mapOf("type" to "inactive", "target" to "tester"))
        compose.waitForIdle()
        idleMain()
        assertTrue("a reminder for another account opens nothing", navigator.state.stack.isEmpty())
    }

    @Test
    fun aHostileTappedPushOpensNothing() {
        node.signIn()
        launch(AppTab.FEED)
        waitFor("bottomTab:FEED")
        env.push.opened(mapOf("type" to "reply", "source" to "https://evil.example", "permlink1" to "x", "url" to "https://evil.example"))
        env.push.opened(mapOf("type" to "open", "screen" to "BackupKeysScreen"))
        // what any app can send to the exported activity: no signing prompt, no mark for a guessed id
        env.push.opened(mapOf("type" to "hiveuri", "hiveUri" to "hive://sign/op/WyJ2b3RlIix7fV0.", "id" to "guessed"))
        compose.waitForIdle()
        idleMain()
        assertTrue(navigator.state.stack.isEmpty())
        assertEquals(AppTab.FEED, navigator.state.tab)
        assertNull(env.authLinks.current.prompt)
        assertTrue(node.calls("notifications/mark").isEmpty())
    }

    @Test
    fun aForegroundPushShowsTheBannerAndItsTapOpensThePost() {
        node.signIn()
        launch(AppTab.FEED)
        waitFor("badge:NOTIFICATION")
        node.unread = mapOf("tester" to 4)
        env.push.received(mapOf("type" to "reply", "source" to "erin", "target" to "tester", "permlink1" to "re-my-post", "id" to "r-1"), "x", "y")
        waitFor("banner")
        assertTrue(hasTextNode("New reply on @tester"))
        assertTrue(hasTextNode("Tap to continue conversation..."))
        waitUntil { env.badges.current.notifications == 4 }
        compose.onNodeWithTag("banner").performClick()
        waitUntil { navigator.state.top == AppScreen.Post("erin", "re-my-post") }
    }

    private class TokenPlatform : PushPlatform {
        override val system = "fcm-android"
        override val isConfigured = true
        override fun permission(request: Boolean, done: (Boolean) -> Unit) = done(true)
        override fun token(done: (String?) -> Unit) = done("device-1")
        override fun deleteToken(done: (Boolean) -> Unit) = done(true)
        override fun clearDelivered() {}
    }

    @Test
    fun pushSwitchesInSettingsAreSavedAndSentToTheBackend() {
        node.signIn()
        env.pushPlatform = TokenPlatform()
        navigator = Navigator(NavState().push(AppScreen.Settings))
        compose.setContent { EcencyApp(env, host, navigator) }
        waitFor("pushSettings")
        waitUntil { node.calls("register-device").isNotEmpty() }
        assertFalse("push works here: no notice", exists("pushUnavailable"))
        compose.onNodeWithTag("push:all").performScrollTo().assertIsOn()
        compose.onNodeWithTag("push:vote").performScrollTo().assertIsOn()
        assertTrue(hasTextNode("Scheduled post published"))
        assertTrue(hasTextNode("Weekly earnings"))

        val before = node.calls("register-device").size
        compose.onNodeWithTag("push:vote").performScrollTo().performClick()
        waitUntil { node.calls("register-device").size > before }
        val call = node.calls("register-device").last()
        assertEquals("[2,3,4,5,6,10,13,15,19,20,21,22,23]", node.field(call, "notify_types"))
        assertEquals("1", node.field(call, "allows_notify"))
        assertEquals("\"fcm-android\"", node.field(call, "system"))
        assertEquals("\"device-1\"", node.field(call, "token"))
        compose.onNodeWithTag("push:vote").assertIsOff()
        assertEquals(setOf(PushType.VOTE), env.push.current.settings.off)

        compose.onNodeWithTag("push:all").performScrollTo().performClick()
        waitUntil { node.field(node.calls("register-device").last(), "allows_notify") == "0" }
        assertFalse("the per-type switches hide with the master switch off", exists("push:vote"))
    }

    @Test
    fun withoutFirebaseTheSettingsSayPushIsUnavailableAndNothingIsRegistered() {
        node.signIn()
        val platform = FirebasePushPlatform(ApplicationProvider.getApplicationContext())
        assertFalse("no google-services.json in this build", platform.isConfigured)
        var token: String? = "unset"
        platform.token { token = it }
        assertNull(token)
        env.pushPlatform = platform
        navigator = Navigator(NavState().push(AppScreen.Settings))
        compose.setContent { EcencyApp(env, host, navigator) }
        waitFor("pushUnavailable")
        assertTrue(node.calls("register-device").isEmpty())
    }

    @Test
    fun onlyATappedFcmNotificationIsReadFromAnIntent() {
        assertNull(PushIntents.data(null))
        assertNull(PushIntents.data(Intent(Intent.ACTION_MAIN)))
        assertNull("not from FCM", PushIntents.data(Intent().putExtra("type", "vote").putExtra("target", "tester")))
        assertNull("no type", PushIntents.data(Intent().putExtra("google.message_id", "m1")))
        val intent = Intent().putExtra("google.message_id", "m1").putExtra("type", "reply").putExtra("source", "erin")
            .putExtra("permlink1", "re-").putExtra("permlink2", "post").putExtra("id", "r-1")
            .putExtra("url", "https://evil.example").putExtra("screen", "BackupKeysScreen").putExtra("amount", 5)
            .putExtra("hiveUri", "hive://sign/op/WyJ2b3RlIix7fV0.")
        val data = PushIntents.data(intent)!!
        assertEquals(mapOf("type" to "reply", "id" to "r-1", "source" to "erin", "permlink1" to "re-", "permlink2" to "post"), data)
        PushIntents.consume(intent)
        assertNull("a recreated activity does not route the same tap again", PushIntents.data(intent))
    }

    @Test
    fun showImagesOffHidesTheThumbnailsAndTheRewardTapShowsTheHint() {
        node.signIn()
        navigator = Navigator(NavState().push(AppScreen.Settings))
        compose.setContent { EcencyApp(env, host, navigator) }
        waitFor("settings:showImages")
        compose.onNodeWithTag("settings:showImages").performScrollTo().assertIsOn()
        compose.onNodeWithTag("settings:showImages").performClick()
        waitUntil { !env.notifications.current.showImages }
        compose.runOnUiThread { navigator.pop(); navigator.selectTab(AppTab.NOTIFICATION) }
        waitFor("notification:n-1")
        compose.onNodeWithTag("notificationRows").performScrollToKey("n-4")
        waitFor("notification:n-4")
        assertFalse("the reply row has a parent image, hidden now", exists("notificationImage:n-4"))
        compose.runOnUiThread { env.notifications.setShowImages(true) }
        waitFor("notificationImage:n-4")

        compose.onNodeWithTag("notificationsPage:Leaderboard").performClick()
        waitFor("leaderReward:alice")
        compose.onNodeWithTag("leaderReward:alice").performClick()
        waitUntil { env.ui.current.toast?.text == "Earn points by actively participating in Ecency." }
    }

    @Test
    fun aPermissionAnswerGivenAcrossActivityRecreationIsKept() {
        val platform = FirebasePushPlatform(ApplicationProvider.getApplicationContext())
        val app = org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
        var dialogs = 0
        platform.requester = { dialogs++; true }
        var first: Boolean? = null
        platform.permission(request = true) { first = it }
        assertEquals(1, dialogs)
        assertNull("the dialog is still open", first)
        // the activity is recreated: its requester goes, the waiting caller stays; a second caller joins, no second dialog
        platform.requester = null
        platform.requester = { dialogs++; true }
        var second: Boolean? = null
        platform.permission(request = true) { second = it }
        assertEquals(1, dialogs)
        platform.recheck()
        assertNull("not granted yet: nothing is assumed", first)
        // the user allows; the result reaches the new activity
        app.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        platform.answered(true)
        assertEquals(true, first)
        assertEquals(true, second)
    }

    @Test
    fun anInterruptedPermissionDialogDoesNotUseUpTheOneTimePrompt() {
        val platform = FirebasePushPlatform(ApplicationProvider.getApplicationContext())
        var dialogs = 0
        platform.requester = { dialogs++; true }
        var answer: Boolean? = null
        platform.permission(request = true) { answer = it }
        platform.abandoned() // the activity finished, or the request came back empty
        assertEquals(false, answer)
        platform.permission(request = true) { answer = it }
        assertEquals("asked again", 2, dialogs)
        platform.answered(false)
        assertEquals(false, answer)
        // a real answer was given: the dialog never shows again from the app
        platform.permission(request = true) { answer = it }
        assertEquals(2, dialogs)
        // granted during recreation with nobody to take the result: the recheck settles it
        val other = FirebasePushPlatform(ApplicationProvider.getApplicationContext())
        ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences("ecency.push", 0).edit().clear().commit()
        other.requester = { true }
        var late: Boolean? = null
        other.permission(request = true) { late = it }
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        other.recheck()
        assertEquals(true, late)
    }

    @Test
    fun olderNotificationsShowADateInTheDeviceLocale() {
        val now = node.nowS * 1000
        assertEquals("5 minutes ago", notificationTime(now - 5 * 60_000, now))
        assertNull(notificationTime(0, now))
        val old = notificationTime(now - 8 * 86_400_000L, now)!!
        assertFalse(old.endsWith("ago"))
        assertTrue(old, "2026" in old)
    }
}
