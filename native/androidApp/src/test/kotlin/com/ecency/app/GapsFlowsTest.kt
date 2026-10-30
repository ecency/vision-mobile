package com.ecency.app

import androidx.compose.ui.unit.sp
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.Navigator
import com.ecency.core.api.ecency.OnboardingApi
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.app.AppScreen
import com.ecency.core.app.NavState
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Refer & Earn, Delete Account, the update prompt and announcements on Android, against fakes only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GapsFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }
    private val opened = mutableListOf<String>()
    private val shared = mutableListOf<String>()
    private var storeOpened = 0

    private val host = object : AppHost {
        override fun openExternal(url: String) { opened += url }
        override fun toast(text: String) {}
        override fun copy(text: String) {}
        override fun share(text: String) { shared += text }
        override fun openStoreForUpdate() { storeOpened++ }
    }

    @Before
    fun setUp() {
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

    private fun signIn(vararg names: String = arrayOf("tester")) {
        val vault = KeyVault(node.store)
        names.forEach { vault.save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = node.key.toWif(), accessToken = "access-$it")) }
        vault.setCurrentUsername(names.last())
    }

    private fun launch(navigator: Navigator = Navigator(), fontScale: Float? = null) = compose.setContent {
        val d = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, fontScale ?: d.fontScale)) {
            EcencyTheme(dark = false) { EcencyApp(env, host, navigator) }
        }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun until(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            compose.waitForIdle()
            if (condition()) return
            if (System.currentTimeMillis() > end) throw AssertionError("condition not met in 10 s")
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            Thread.sleep(20)
        }
    }

    private fun waitFor(tag: String) = until { exists(tag) }

    /** RN shows an announcement 3 s after choosing it: the main looper's clock is moved past that. */
    private fun waitForAnnouncement(tag: String) = until {
        org.robolectric.shadows.ShadowLooper.idleMainLooper(1, java.util.concurrent.TimeUnit.SECONDS)
        exists(tag)
    }

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun requests(suffix: String) = node.auth.requests.filter { it.url.endsWith(suffix) }

    // ---- refer ----

    @Test
    fun referShowsPointsRowsAndSharesTheSignupLink() {
        signIn()
        node.auth.rest = { r ->
            when {
                r.url.endsWith("/referrals/tester/stats") -> HttpResult(200, """{"total":3,"rewarded":1}""")
                r.url.endsWith("/referrals/tester") -> HttpResult(200, """[{"id":2,"username":"friend-one","rewarded":1,"created":"2026-09-20T10:00:00+00:00"},{"id":1,"username":"friend-two","rewarded":0,"created":"2026-09-21T10:00:00+00:00"}]""")
                else -> null
            }
        }
        launch(Navigator(NavState().push(AppScreen.Refer)))
        waitFor("referral:friend-one")
        assertEquals("100", text("referEarned"))
        assertEquals("200", text("referPending"))
        assertTrue(exists("referral:friend-two"))
        // RN's label then share icon, centred: as much room before the label as after the icon (no empty icon slot)
        val button = compose.onNodeWithTag("referShare").fetchSemanticsNode().boundsInRoot
        val icon = compose.onNodeWithTag("referShareIcon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val label = compose.onNode(
            androidx.compose.ui.test.hasText("Refer") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("referShare")),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        // in sp: the icon follows the font scale
        assertEquals(22f * compose.density.density * compose.density.fontScale, icon.width, 1f)
        assertTrue("the icon follows the label: $label, $icon", icon.left >= label.right && icon.right <= button.right)
        assertEquals("centred: $button, $label, $icon", label.left - button.left, button.right - icon.right, 2f * compose.density.density)
        compose.onNodeWithTag("referShare").performClick()
        assertEquals(listOf("https://ecency.com/signup?referral=tester"), shared)
    }

    @Test
    fun theReferShareIconGrowsWithTheFontScale() {
        signIn()
        node.auth.rest = { r -> if (r.url.endsWith("/stats")) HttpResult(200, """{"total":0,"rewarded":0}""") else if ("/referrals/" in r.url) HttpResult(200, "[]") else null }
        launch(Navigator(NavState().push(AppScreen.Refer)), fontScale = 1.5f)
        waitFor("referShareIcon")
        val icon = compose.onNodeWithTag("referShareIcon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val expected = with(androidx.compose.ui.unit.Density(compose.density.density, 1.5f)) { 22.sp.toDp().toPx() }
        assertEquals(expected, icon.width, 1f)
        assertTrue("larger than at 1x", icon.width > 22f * compose.density.density + 1f)
    }

    @Test
    fun anEmptyReferListShowsRnsHint() {
        signIn()
        node.auth.rest = { r -> if (r.url.endsWith("/stats")) HttpResult(200, """{"total":0,"rewarded":0}""") else if ("/referrals/" in r.url) HttpResult(200, "[]") else null }
        launch(Navigator(NavState().push(AppScreen.Refer)))
        waitFor("referEmpty")
        assertEquals("Refer your loved ones today and earn free points", text("referEmpty"))
    }

    @Test
    fun referIsGoneAfterAnAccountChange() {
        signIn("other", "tester")
        node.auth.rest = { r -> if (r.url.endsWith("/stats")) HttpResult(200, """{"total":1,"rewarded":0}""") else if (r.url.endsWith("/referrals/tester")) HttpResult(200, """[{"id":1,"username":"friend-one","rewarded":0,"created":""}]""") else if ("/referrals/" in r.url) HttpResult(200, "[]") else null }
        val navigator = Navigator(NavState().push(AppScreen.Refer))
        launch(navigator)
        waitFor("referral:friend-one")
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launchSwitch("other")
        until { navigator.state.stack.none { it.screen == AppScreen.Refer } }
        assertFalse(exists("referral:friend-one"))
    }

    private fun kotlinx.coroutines.CoroutineScope.launchSwitch(name: String) {
        this.launch(block = { env.session.switchTo(name) })
    }

    // ---- delete account ----

    private fun openSettings() {
        launch(Navigator(NavState().push(AppScreen.Settings)))
        waitFor("menuDeleteAccount")
        compose.onNodeWithTag("menuDeleteAccount").performScrollTo().performClick()
        waitFor("deleteConfirm")
    }

    @Test
    fun deleteAccountAsksFirstAndCancelSendsNothing() {
        signIn()
        openSettings()
        assertEquals("Are you sure you want to delete account? It will erase all of your data", text("deleteConfirm"))
        assertTrue(requests("/request-delete").isEmpty())
        compose.onNodeWithTag("deleteConfirmNo").performClick()
        until { !exists("deleteConfirm") }
        assertTrue(requests("/request-delete").isEmpty())
        assertNotNull(KeyVault(node.store).account("tester"))
    }

    @Test
    fun confirmingSendsOneRequestAndSignsTheAccountOut() {
        signIn()
        node.auth.rest = { r -> if (r.url.endsWith("/request-delete")) HttpResult(200, "{}") else null }
        openSettings()
        compose.onNodeWithTag("deleteConfirmYes").performClick()
        until { KeyVault(node.store).account("tester") == null }
        until { !env.session.current.isLoggedIn }
        val sent = requests("/request-delete").single()
        assertTrue("\"username\":\"tester\"" in sent.body!! && "\"code\":\"access-tester\"" in sent.body!!)
        until { !exists("menuDeleteAccount") }
    }

    @Test
    fun aFailedDeletionRequestSaysSoAndKeepsTheAccount() {
        signIn()
        node.auth.rest = { r -> if (r.url.endsWith("/request-delete")) HttpResult(500, "") else null }
        openSettings()
        compose.onNodeWithTag("deleteConfirmYes").performClick()
        waitFor("deleteError")
        assertEquals(1, requests("/request-delete").size)
        assertNotNull(KeyVault(node.store).account("tester"))
        assertTrue(env.session.current.isLoggedIn)
    }

    // ---- update prompt ----

    @Test
    fun aNewerReleaseAsksAndUpdateNowOpensTheStore() {
        node.auth.rest = { r -> if (r.url == OnboardingApi.RELEASES_URL) HttpResult(200, """{"tag_name":"99.1.0"}""") else null }
        launch()
        waitFor("updateTitle")
        assertEquals("Update available v99.1.0", text("updateTitle"))
        compose.onNodeWithTag("updateNow").performClick()
        until { !exists("updateTitle") }
        assertEquals(1, storeOpened)
        assertNull("Update Now remembers nothing", env.prefs.getString(com.ecency.core.app.UpdateModel.LAST_CHECK))
    }

    @Test
    fun remindLaterStoresTheTimeAndOpensNothing() {
        node.auth.rest = { r -> if (r.url == OnboardingApi.RELEASES_URL) HttpResult(200, """{"tag_name":"99.1.0"}""") else null }
        launch()
        waitFor("updateLater")
        compose.onNodeWithTag("updateLater").performClick()
        until { !exists("updateTitle") }
        assertEquals(0, storeOpened)
        assertNotNull(env.prefs.getString(com.ecency.core.app.UpdateModel.LAST_CHECK))
    }

    // ---- announcements ----

    @Test
    fun anAnnouncementShowsAndItsButtonOpensTheLinkedPostInTheApp() {
        node.auth.rest = { r ->
            if (r.url.endsWith("/private-api/announcements")) HttpResult(200, """[{"id":"a1","title":"Hello","description":"Body text","button_text":"Read more","button_link":"/@alice/trending-one"}]""") else null
        }
        val navigator = Navigator()
        launch(navigator)
        waitForAnnouncement("announcementTitle")
        assertEquals("Hello", text("announcementTitle"))
        assertEquals("Body text", text("announcementBody"))
        compose.onNodeWithTag("announcementAction").performClick()
        until { navigator.state.stack.lastOrNull()?.screen == AppScreen.Post("alice", "trending-one") }
        assertTrue(opened.isEmpty())
        assertTrue("\"processed\":true" in env.prefs.getString(com.ecency.core.app.AnnouncementsModel.META)!!)
    }

    @Test
    fun laterClosesItAndAHostileLinkNeverBecomesAnAuthLink() {
        node.auth.rest = { r ->
            if (r.url.endsWith("/private-api/announcements")) HttpResult(200, """[{"id":"a2","title":"Hi","description":"d","button_text":"Go","button_link":"hive://sign/op/W10."}]""") else null
        }
        val navigator = Navigator()
        launch(navigator)
        waitForAnnouncement("announcementAction")
        compose.onNodeWithTag("announcementAction").performClick()
        until { !exists("announcementTitle") }
        assertNull(env.authLinks.current.prompt)
        assertTrue(navigator.state.stack.isEmpty())
        assertTrue("only ever an https page on ecency.com: $opened", opened.all { it.startsWith("https://ecency.com") })
    }

    // ---- waves: getting started, check-in, composer poll ----

    private fun wavesBackend(quests: String) {
        node.auth.rest = { r ->
            when {
                r.url.endsWith("/private-api/quests") -> HttpResult(200, quests)
                r.url.endsWith("/private-api/usr-activity") -> HttpResult(200, "{}")
                "/private-api/waves/" in r.url || "/private-api/promoted-entries" in r.url -> HttpResult(200, "[]")
                r.url.endsWith("/private-api/public/bots") -> HttpResult(200, "[]")
                else -> null
            }
        }
    }

    private fun openWaves() {
        launch()
        waitFor("bottomTab:WAVES")
        compose.onNodeWithTag("bottomTab:WAVES").performClick()
        waitFor("wavesScreen")
    }

    @Test
    fun aNewAccountSeesGettingStartedAndBrowsingWavesRecordsTheCheckIn() {
        signIn()
        wavesBackend("""{"daily":[{"id":"vote","progress":1}],"streak":{"current":0}}""")
        openWaves()
        waitFor("wavesOnboarding")
        assertEquals("Getting started", text("wavesOnboardingTitle"))
        assertEquals("1/4", text("wavesOnboardingProgress"))
        assertTrue(exists("wavesOnboardingItem:wave") && exists("wavesOnboardingItem:checkin"))
        until { requests("/private-api/usr-activity").isNotEmpty() }
        val checkIn = requests("/private-api/usr-activity").single()
        assertTrue("\"ty\":10" in checkIn.body!! && "\"code\":\"access-tester\"" in checkIn.body!!)
        compose.onNodeWithTag("wavesOnboardingDismiss").performClick()
        until { !exists("wavesOnboarding") }
        assertTrue("\"dismissed\":true" in env.prefs.getString(com.ecency.core.app.WavesOnboardingModel.key("tester"))!!)
    }

    @Test
    fun theFirstWaveRowOpensTheComposerAndAPollCanBeAttachedThere() {
        signIn()
        wavesBackend("""{"daily":[],"streak":{"current":0}}""")
        openWaves()
        waitFor("wavesOnboardingItem:wave")
        compose.onNodeWithTag("wavesOnboardingItem:wave").performClick()
        waitFor("waveComposer")
        compose.onNodeWithTag("wavePoll", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitFor("pollSheet")
        compose.onNodeWithTag("pollQuestion").performTextInput("Tea or coffee?")
        compose.onNodeWithTag("pollChoice0").performTextInput("Tea")
        compose.onNodeWithTag("pollChoice1").performTextInput("Coffee")
        compose.onNodeWithTag("pollAttach").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        until { env.waves.composer().current.poll != null }
        val poll = env.waves.composer().current.poll!!
        assertEquals("Tea or coffee?", poll.title)
        assertEquals(listOf("Tea", "Coffee"), poll.choices)
    }

    @Test
    fun theFeedTabShowsTheWavesCardToo() {
        signIn()
        wavesBackend("""{"daily":[],"streak":{"current":0}}""")
        launch()
        waitFor("wavesOnboarding")
        assertTrue("the Feed tab is the one on screen", exists("customise"))
        compose.onNodeWithTag("wavesOnboardingItem:wave").performClick()
        waitFor("waveComposer")
    }

    @Test
    fun signedOutTheWavesTabShowsNoCardAndRecordsNothing() {
        wavesBackend("""{"daily":[],"streak":{"current":0}}""")
        openWaves()
        compose.waitForIdle()
        assertFalse(exists("wavesOnboarding"))
        assertTrue(requests("/private-api/usr-activity").isEmpty())
        assertTrue(requests("/private-api/quests").isEmpty())
    }

    @Test
    fun aCheckInRefusedBehindTheLockIsRecordedAfterTheUnlock() {
        signIn()
        com.ecency.core.secure.PinLock(KeyVault(node.store), 16).setPin("1234")
        wavesBackend("""{"daily":[],"streak":{"current":0}}""")
        // the app comes back on the Waves tab, locked
        launch(Navigator(NavState().selectTab(com.ecency.core.app.AppTab.WAVES)))
        waitFor("pinScreen")
        compose.waitForIdle()
        assertTrue("nothing is sent from behind the lock", requests("/private-api/usr-activity").isEmpty())
        "1234".forEach { compose.onNodeWithTag("pinKey$it").performClick() }
        waitFor("wavesScreen")
        until { requests("/private-api/usr-activity").size == 1 }
    }

    // ---- notices about an account's keys ----

    @Test
    fun anAccountLeftWithoutASigningKeyIsToldOnceWithTheApp() {
        // an entry as an earlier build wrote it for an owner-key sign-in: the owner key and a token
        node.store.put("account.tester", """{"v":1,"username":"tester","authType":"owner","ownerKey":"${node.key.toWif()}","accessToken":"access-tester","hiveAuthExpiry":0}""".toByteArray())
        KeyVault(node.store).setCurrentUsername("tester")
        launch()
        waitFor("accountNotice:NO_SIGNING_KEY")
        assertEquals("Sign in again as @tester", text("accountNoticeTitle"))
        assertTrue("@tester has no posting or active key" in text("accountNotice:NO_SIGNING_KEY"))
        assertFalse("the owner key is gone from the vault", node.key.toWif() in String(node.store.map.getValue("account.tester")))
        compose.onNodeWithTag("accountNoticeOk").performClick()
        until { !exists("accountNoticeTitle") }
        assertNull(env.prefs.getString(com.ecency.core.app.AccountNoticesModel.PENDING))
        assertTrue("still signed in", env.session.current.isLoggedIn)
    }
}
