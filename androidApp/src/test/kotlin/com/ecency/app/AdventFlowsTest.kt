package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.Navigator
import com.ecency.core.api.EcencyApi
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.app.AdventSeason
import com.ecency.core.app.AppDispatchers
import com.ecency.core.app.AppEnvironment
import com.ecency.core.app.AppScreen
import com.ecency.core.app.AppTab
import com.ecency.core.app.DemoAdvent
import com.ecency.core.app.DemoHiveTransport
import com.ecency.core.app.InterimPostBodyRenderer
import com.ecency.core.app.NavState
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.HasSocketFactory
import com.ecency.core.app.AuthServices
import com.ecency.core.hive.PrivateKey
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.KeyVaultAsync
import com.ecency.core.app.SecurityConfig
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #3739: the Advent Calendar on Android, over the offline games API ([DemoAdvent]) on 9 Dec 2026. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class AdventFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val key = PrivateKey.fromSeed("ecency-native-spike-advent-ui-throwaway")
    private val store = TestStore()

    /** 9 Dec 2026, 10:00 UTC: door 9 is open (a written answer), door 3 drawn (a quiz). */
    @Volatile private var now = AdventSeason.FIRST_DOOR + 8 * AdventSeason.DAY_MILLIS + 10 * 3_600_000L
    private val advent = DemoAdvent { now }

    private val env by lazy {
        AppEnvironment(
            api = EcencyApi(listOf("https://node-a.example"), DemoHiveTransport { now / 1000 }),
            vault = KeyVaultAsync(KeyVault(store), Dispatchers.IO),
            renderer = InterimPostBodyRenderer("https://i.ecency.com"),
            dispatchers = AppDispatchers(Dispatchers.Main, Dispatchers.Default, Dispatchers.IO),
            proxyBase = "https://i.ecency.com",
            debugKeyImport = true,
            nowMillis = { now },
            security = SecurityConfig(pinIterations = 16, monotonicMillis = { 0L }),
            auth = AuthServices(AuthHttp { request -> advent.http(request) ?: HttpResult(503, "{}") }, HasSocketFactory { error("no relay") }),
        )
    }

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

    private fun signIn() {
        val vault = KeyVault(store)
        vault.save(VaultAccount("tester", AuthType.POSTING_KEY, postingKey = key.toWif(), accessToken = "access-tester"))
        vault.setCurrentUsername("tester")
        advent.seedHistory("tester")
    }

    private fun launch(state: NavState) {
        val navigator = Navigator(state)
        compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host, navigator) } }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String) = compose.await("a node tagged $tag") { exists(tag) }

    private fun settle() {
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    @Test
    fun theTeaserCountsDownToTheFirstDoorUnderItsHeadline() {
        now = AdventSeason.SHOW_FROM + 10 * 3_600_000L
        launch(NavState(tab = AppTab.FEED).push(AppScreen.Advent))
        waitFor("adventCountdown")
        assertTrue("the countdown", compose.onAllNodes(hasTestTag("adventCountdown") and androidx.compose.ui.test.hasText("5 days 14 h 0 min"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun todaysDoorTakesAWrittenAnswerAndTheCalendarMarksItAnswered() {
        signIn()
        launch(NavState(tab = AppTab.FEED).push(AppScreen.Advent))
        waitFor("adventDoor:9")
        assertTrue("the answered count", exists("adventAnswered"))
        compose.onNodeWithTag("adventDoor:9").performScrollTo().performClick()
        waitFor("adventAnswer")
        settle()
        compose.onNodeWithTag("adventAnswer").performTextInput("Snow on the roofs and tea by the window.")
        compose.onNodeWithTag("adventSubmit").performScrollTo().performClick()
        waitFor("adventDone")
        assertEquals("one answer sent", 1, advent.calls.count { it == "POST /v1/advent/2026/doors/9/answer" })
        compose.await("the door reads as answered") {
            compose.onAllNodes(hasTestTag("adventDoor:9") and androidx.compose.ui.test.hasContentDescription("Draw 02:00", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun aDrawnDoorShowsItsWinnersAndTheRightAnswer() {
        signIn()
        launch(NavState(tab = AppTab.FEED).push(AppScreen.Advent))
        waitFor("adventDoor:3")
        compose.onNodeWithTag("adventDoor:3").performScrollTo().performClick()
        waitFor("adventWinner:1")
        assertTrue("you won", exists("adventYouWon"))
        assertTrue("the seed block", exists("adventSeed"))
    }

    @Test
    fun theFeedBannerOpensTheCalendarAndClosesForTheDay() {
        signIn()
        launch(NavState(tab = AppTab.FEED))
        waitFor("adventBanner")
        compose.onNodeWithTag("adventBannerClose").performClick()
        compose.await("the banner closed") { !exists("adventBanner") }
        assertEquals("2026-12-09", env.prefs.getString("advent_banner_closed"))
    }

    @Test
    fun theSideMenuLeadsWithAdventAndItsDoorWhileTheSeasonShows() {
        signIn()
        launch(NavState(tab = AppTab.FEED).copy(drawerOpen = true))
        waitFor("menu:advent")
        assertTrue("the door badge", exists("menuBadge:advent"))
        compose.onNodeWithTag("menu:advent").performClick()
        waitFor("advent")
    }

    @Test
    fun afterTheSeasonNeitherMenuItemNorBannerShows() {
        now = AdventSeason.HIDE_AT + 1000
        signIn()
        launch(NavState(tab = AppTab.FEED).copy(drawerOpen = true))
        waitFor("menu:profile")
        settle()
        assertTrue("no menu item", !exists("menu:advent"))
        assertTrue("no banner", !exists("adventBanner"))
    }

    /** `ADVENT_SHOT_DIR=<folder>` in the test's environment: the calendar, a door and a result as PNGs (design review). */
    @Test
    @Config(qualifiers = "w390dp-h844dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun screenshotsForTheDesignReview() {
        val dir = System.getenv("ADVENT_SHOT_DIR")?.takeIf { it.isNotEmpty() } ?: return
        fun save(name: String) {
            settle()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            java.io.File(dir, "advent-android-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        signIn()
        launch(NavState(tab = AppTab.FEED))
        waitFor("adventBanner")
        save("banner")
        compose.onNodeWithTag("adventBannerOpen").performClick()
        waitFor("adventDoor:9")
        save("calendar")
        compose.onNodeWithTag("adventDoor:9").performScrollTo().performClick()
        waitFor("adventAnswer")
        compose.onNodeWithTag("adventAnswer").performTextInput("Snow on the roofs and tea by the window.")
        save("door")
    }

    /** As [screenshotsForTheDesignReview]: a drawn door the account won. */
    @Test
    @Config(qualifiers = "w390dp-h844dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun resultScreenshotForTheDesignReview() {
        val dir = System.getenv("ADVENT_SHOT_DIR")?.takeIf { it.isNotEmpty() } ?: return
        signIn()
        launch(NavState(tab = AppTab.FEED).push(AppScreen.Advent))
        waitFor("adventDoor:3")
        compose.onNodeWithTag("adventDoor:3").performScrollTo().performClick()
        waitFor("adventWinner:1")
        settle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(dir, "advent-android-result.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** As [screenshotsForTheDesignReview]: the teaser on 25 Nov with its countdown. */
    @Test
    @Config(qualifiers = "w390dp-h844dp")
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun teaserScreenshotForTheDesignReview() {
        val dir = System.getenv("ADVENT_SHOT_DIR")?.takeIf { it.isNotEmpty() } ?: return
        now = AdventSeason.SHOW_FROM + 10 * 3_600_000L
        launch(NavState(tab = AppTab.FEED).push(AppScreen.Advent))
        waitFor("adventCountdown")
        settle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(dir, "advent-android-teaser.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
