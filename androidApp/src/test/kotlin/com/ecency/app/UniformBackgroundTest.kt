package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyColors
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.Navigator
import com.ecency.core.api.RankedSort
import com.ecency.core.api.repository.FeedSource
import com.ecency.core.app.AppScreen
import com.ecency.core.app.AppTab
import com.ecency.core.app.NavState
import com.ecency.core.app.RouteName
import com.ecency.core.app.ThemeMode
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every route sits on the theme background, light and dark, loaded (or failed: the fake node answers few calls) and
 * while its data loads, and halfway through the push that brings it in (README "Themed backgrounds"). A page that
 * paints another colour shows as a full width band of that colour (a gray page, black under a loader, the gaps
 * between cards); filter strips, striped rows and skeletons are content and are left out by their tags. The window
 * behind the app, the Material surfaces (sheets, dialogs) and the pull to refresh indicator take the theme too, and
 * all of it follows a theme change at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UniformBackgroundTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
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
    fun tearDown() {
        node.holdAll?.cancel()
        node.auth.holdAll?.cancel()
        SingletonImageLoader.reset()
    }

    private fun idleMain() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))

    private fun settle(rounds: Int = 20) {
        repeat(rounds) { idleMain(); compose.mainClock.advanceTimeBy(100) }
        compose.waitForIdle()
    }

    private class Route(val name: String, val tab: AppTab? = null, val screen: AppScreen? = null)

    private val routes = listOf(
        Route("feedTab", tab = AppTab.FEED),
        Route("wavesTab", tab = AppTab.WAVES),
        Route("chatsTab", tab = AppTab.CHATS),
        Route("walletTab", tab = AppTab.WALLET),
        Route("notificationsTab", tab = AppTab.NOTIFICATION),
        Route("post", screen = AppScreen.Post("alice", "trending-one")),
        Route("profile", screen = AppScreen.Profile("alice")),
        Route("follows", screen = AppScreen.Follows("alice", following = false)),
        Route("profileEdit", screen = AppScreen.ProfileEdit),
        Route("bookmarks", screen = AppScreen.Bookmarks()),
        Route("feed", screen = AppScreen.Feed(FeedSource.Ranked(RankedSort.TRENDING, "hive"), "#hive")),
        Route("login", screen = AppScreen.Login),
        Route("settings", screen = AppScreen.Settings),
        Route("backupKeys", screen = AppScreen.BackupKeys),
        Route("emailDigests", screen = AppScreen.EmailDigests),
        Route("refer", screen = AppScreen.Refer),
        Route("perks", screen = AppScreen.Perks),
        Route("getPoints", screen = AppScreen.of(RouteName.BOOST)),
        Route("accountBoost", screen = AppScreen.of(RouteName.ACCOUNT_BOOST)),
        Route("spin", screen = AppScreen.SpinGame),
        Route("communities", screen = AppScreen.Communities),
        Route("community", screen = AppScreen.Community("hive-100001")),
        Route("communityMembers", screen = AppScreen.CommunityMembers("hive-100001")),
        Route("communitySettings", screen = AppScreen.CommunitySettings("hive-100001")),
        Route("communityActivities", screen = AppScreen.CommunityActivities("hive-100001")),
        Route("search", screen = AppScreen.Search),
        Route("tagResult", screen = AppScreen.TagResult("hive")),
        Route("voters", screen = AppScreen.Voters("alice", "trending-one")),
        Route("reblogs", screen = AppScreen.Reblogs("alice", "trending-one")),
        Route("editHistory", screen = AppScreen.EditHistory("alice", "trending-one")),
        Route("botComments", screen = AppScreen.BotComments("alice", "trending-one")),
        Route("editor", screen = AppScreen.of(RouteName.EDITOR)),
        Route("drafts", screen = AppScreen.Drafts()),
        Route("aiImage", screen = AppScreen.AiImageGenerator),
        Route("assetDetails", screen = AppScreen.AssetDetails("HIVE")),
        Route("transfer", screen = AppScreen.of(RouteName.TRANSFER)),
        Route("assetsSelect", screen = AppScreen.AssetsSelect),
        Route("trade", screen = AppScreen.Trade()),
        Route("redeem", screen = AppScreen.of(RouteName.REDEEM)),
        Route("register", screen = AppScreen.Register()),
        Route("accountList", screen = AppScreen.AccountList("Accounts", emptyList())),
        Route("pending", screen = AppScreen.Pending(RouteName.POLL_WIZARD)),
    )

    /** Content that may span the width in a token colour: filter strips, RN's striped rows, skeletons. */
    private val contentTags = listOf("skeleton", "filterStrip", "filterBar", "browserTabs", "community:", "joined:", "member:")

    private fun want(dark: Boolean) = (if (dark) EcencyColors.Dark else EcencyColors.Light).background.toArgb()

    private fun hex(c: Int) = Integer.toHexString(c)

    /** The content view drawn into a bitmap (captureToImage waits for a frame the paused looper never draws), saved
     *  as android-[name].png when `BG_SHOT_DIR` is set. */
    private fun shot(name: String): android.graphics.Bitmap {
        val view = compose.activity.findViewById<android.view.View>(android.R.id.content)
        val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(image)) }
        System.getenv("BG_SHOT_DIR")?.takeIf { it.isNotEmpty() }?.let { dir ->
            java.io.File(dir, "android-$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        return image
    }

    private fun contentBounds(): List<Rect> = compose.onAllNodes(
        SemanticsMatcher("content") { n -> n.config.getOrNull(SemanticsProperties.TestTag)?.let { t -> contentTags.any { t.startsWith(it) } } == true },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().map { n ->
        // a tag sits inside its node's padding: the strip's own padding counts as the strip
        val pad = 12 * compose.activity.resources.displayMetrics.density
        n.boundsInRoot.let { Rect(it.left, it.top - pad, it.right, it.bottom + pad) }
    }

    /**
     * Full width bands (every second pixel of a row one colour) other than the theme background, 3 dp or taller,
     * outside [contentBounds]: "colour at y (height)".
     */
    private fun bands(image: android.graphics.Bitmap, want: Int): List<String> {
        val density = compose.activity.resources.displayMetrics.density
        val content = contentBounds()
        fun rowColour(y: Int): Int? {
            val c = image.getPixel(0, y)
            return if ((0 until image.width step 2).all { image.getPixel(it, y) == c }) c else null
        }
        val found = mutableListOf<String>()
        var y = 0
        while (y < image.height) {
            val c = rowColour(y)
            if (c == null || c == want) { y++; continue }
            var end = y
            while (end + 1 < image.height && rowColour(end + 1) == c) end++
            val top = y.toFloat()
            val bottom = (end + 1).toFloat()
            val inContent = content.any { it.top <= top + 1 && it.bottom >= bottom - 1 }
            if ((end - y + 1) / density >= 3f && !inContent) found += "${hex(c)} at ${(y / density).toInt()}dp (${((end - y + 1) / density).toInt()}dp)"
            y = end + 1
        }
        return found
    }

    /** What is not on the theme background in this frame: bands, edges, the window. */
    private fun problems(name: String, dark: Boolean): List<String> {
        val image = shot(name)
        val want = want(dark)
        val found = mutableListOf<String>()
        bands(image, want).takeIf { it.isNotEmpty() }?.let { found += "$name: full width bands of another colour $it" }
        // the page's own colour shows in its gutters: most of the left and right edge columns
        val edge = (0 until image.height step 8).flatMap { listOf(image.getPixel(0, it), image.getPixel(image.width - 1, it)) }
        val share = edge.count { it == want } * 100 / edge.size
        if (share < 60) found += "$name: the page edges are $share% the theme background ${hex(want)}"
        // the window behind the app: what shows before the first frame, around the keyboard and in system animations
        val window = (compose.activity.window.decorView.background as? android.graphics.drawable.ColorDrawable)?.color
        if (window != want) found += "$name: the window background is ${window?.let(::hex)}, not ${hex(want)}"
        return found
    }

    private fun assertUniform(name: String, dark: Boolean) = assertEquals(emptyList<String>(), problems(name, dark))

    private fun launch(dark: Boolean, hold: Boolean): Navigator {
        node.signIn()
        if (hold) {
            node.holdAll = CompletableDeferred()
            node.auth.holdAll = CompletableDeferred()
        }
        // the app picks its theme from the settings, over any EcencyTheme around it
        env.settings.setTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        val navigator = Navigator(NavState(tab = AppTab.FEED))
        compose.setContent { EcencyTheme(dark = dark) { EcencyApp(env, host, navigator) } }
        settle()
        return navigator
    }

    private fun everyRoute(dark: Boolean, hold: Boolean) {
        val navigator = launch(dark, hold)
        val side = (if (dark) "dark" else "light") + if (hold) "-loading" else ""
        val found = mutableListOf<String>()
        for (route in routes) {
            compose.runOnUiThread { route.tab?.let(navigator::selectTab); route.screen?.let(navigator::push) }
            // halfway through the push: the screen coming in over the one going out
            if (route.screen != null) {
                idleMain()
                compose.mainClock.advanceTimeBy(150)
                found += problems("${route.name}-$side-push", dark)
            }
            settle()
            found += problems("${route.name}-$side", dark)
            if (route.screen != null) compose.runOnUiThread { navigator.pop() }
            settle(5)
        }
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun everyRouteOnTheLightTheme() = everyRoute(dark = false, hold = false)

    @Test
    fun everyRouteOnTheDarkTheme() = everyRoute(dark = true, hold = false)

    @Test
    fun everyRouteWhileLoadingOnTheLightTheme() = everyRoute(dark = false, hold = true)

    @Test
    fun everyRouteWhileLoadingOnTheDarkTheme() = everyRoute(dark = true, hold = true)

    /** The appearance setting changes the page, its loaders and the window at once, no restart. */
    @Test
    fun aThemeChangeRepaintsEverything() {
        val navigator = launch(dark = false, hold = true)
        compose.runOnUiThread { navigator.push(AppScreen.Settings) }
        settle()
        assertUniform("settings-before-switch", dark = false)
        compose.runOnUiThread { env.settings.setTheme(ThemeMode.DARK) }
        settle()
        assertUniform("settings-switched-dark", dark = true)
        compose.runOnUiThread { navigator.pop(); navigator.push(AppScreen.Feed(FeedSource.Ranked(RankedSort.TRENDING, "hive"), "#hive")) }
        settle()
        assertUniform("feed-loading-switched-dark", dark = true)
        compose.runOnUiThread { env.settings.setTheme(ThemeMode.LIGHT) }
        settle()
        assertUniform("feed-loading-switched-light", dark = false)
    }

    /** Sheets, dialogs and the other Material surfaces resolve to the theme background, not Material's own colours. */
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    private fun materialSurfaces(dark: Boolean) {
        val colors = if (dark) EcencyColors.Dark else EcencyColors.Light
        val seen = mutableMapOf<String, Color>()
        compose.setContent {
            EcencyTheme(dark = dark) {
                val s = MaterialTheme.colorScheme
                seen["background"] = s.background
                seen["surface"] = s.surface
                seen["surfaceContainerLowest"] = s.surfaceContainerLowest
                seen["surfaceContainerLow"] = s.surfaceContainerLow
                seen["surfaceContainerHigh"] = s.surfaceContainerHigh
                seen["surfaceBright"] = s.surfaceBright
                seen["surfaceDim"] = s.surfaceDim
                // tonal elevation mixes this into a surface: the theme background mixes nothing in
                seen["surfaceTint"] = s.surfaceTint
                seen["sheet"] = BottomSheetDefaults.ContainerColor
                seen["dialog"] = AlertDialogDefaults.containerColor
                // tracks and filled fields: the theme's light background, not Material's purple gray
                seen["surfaceContainerHighest"] = s.surfaceContainerHighest
                seen["surfaceVariant"] = s.surfaceVariant
            }
        }
        compose.waitForIdle()
        val expected = listOf("background", "surface", "surfaceContainerLowest", "surfaceContainerLow", "surfaceContainerHigh", "surfaceBright", "surfaceDim", "surfaceTint", "sheet", "dialog")
            .associateWith { hex(colors.background.toArgb()) } +
            listOf("surfaceContainerHighest", "surfaceVariant").associateWith { hex(colors.lightBackground.toArgb()) }
        assertEquals("dark=$dark", expected, seen.mapValues { hex(it.value.toArgb()) })
    }

    @Test
    fun materialSurfacesOnTheLightTheme() = materialSurfaces(dark = false)

    @Test
    fun materialSurfacesOnTheDarkTheme() = materialSurfaces(dark = true)

    /** The platform date and time pickers (the editor's schedule and poll end) take the app's theme, not the window's. */
    @Test
    fun platformPickersTakeTheAppTheme() {
        for (dark in listOf(false, true)) {
            val colors = if (dark) EcencyColors.Dark else EcencyColors.Light
            val theme = compose.activity.resources.newTheme().apply { applyStyle(com.ecency.app.ui.pickerTheme(dark), true) }
            fun attr(a: Int) = android.util.TypedValue().also { assertTrue(theme.resolveAttribute(a, it, true)) }.data
            assertEquals("picker page (dark=$dark)", hex(colors.background.toArgb()), hex(attr(android.R.attr.colorBackground)))
            assertEquals("picker accent (dark=$dark)", hex(colors.blueText.toArgb()), hex(attr(android.R.attr.colorAccent)))
        }
    }

    /** The pull to refresh indicator: the shared spinner's blue on the theme background, like every other loader. */
    private fun refreshIndicator(dark: Boolean) {
        // a feed whose first page never answers: its skeleton (light gray bones) under the indicator at its top
        val navigator = launch(dark, hold = true)
        compose.runOnUiThread { navigator.push(AppScreen.Feed(FeedSource.Ranked(RankedSort.TRENDING, "hive"), "#hive")) }
        compose.await("the feed") { idleMain(); compose.onAllNodes(hasTestTag("feed"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        settle()
        val density = compose.activity.resources.displayMetrics.density
        // pulled past the threshold and held. Not released: a refresh of a feed with no cards shows the skeleton, not
        // the indicator (FeedModel.refresh: isRefreshing only with cards), and Material 1.4 hides the indicator after a
        // release that leaves isRefreshing false, where 1.3 left it standing
        compose.onNodeWithTag("feed").performTouchInput {
            down(androidx.compose.ui.geometry.Offset(centerX, 4 * density))
            repeat(20) { advanceEventTime(30); moveBy(androidx.compose.ui.geometry.Offset(0f, 10 * density)) }
        }
        settle(10)
        val colors = if (dark) EcencyColors.Dark else EcencyColors.Light
        val image = shot("refresh-${if (dark) "dark" else "light"}")
        compose.onNodeWithTag("feed").performTouchInput { up() }
        val feed = compose.onNodeWithTag("feed").fetchSemanticsNode().boundsInRoot
        val bg = colors.background.toArgb()
        val blue = colors.blueText.toArgb()
        fun ch(c: Int, s: Int) = (c shr s) and 0xff
        fun distance(a: Int, b: Int) = listOf(16, 8, 0).maxOf { kotlin.math.abs(ch(a, it) - ch(b, it)) }
        // the indicator: a 40 dp disc at the top middle of the list
        val cx = ((feed.left + feed.right) / 2).toInt()
        val xs = (cx - (30 * density).toInt())..(cx + (30 * density).toInt())
        val ys = feed.top.toInt() until (feed.top + 110 * density).toInt().coerceAtMost(image.height)
        val marked = ys.flatMap { y -> xs.map { x -> Triple(x, y, image.getPixel(x, y)) } }.filter { distance(it.third, bg) > 8 }
        assertTrue("the refresh indicator shows (dark=$dark)", marked.isNotEmpty())
        // its strongest colour is the arc: the theme's blue (or a blend of it over the background), not Material's gray
        fun blend(c: Int): Float? {
            val t = listOf(16, 8, 0).filter { ch(blue, it) != ch(bg, it) }.map { (ch(c, it) - ch(bg, it)).toFloat() / (ch(blue, it) - ch(bg, it)) }.average().toFloat()
            return t.takeIf { listOf(16, 8, 0).all { s -> kotlin.math.abs(ch(bg, s) + t * (ch(blue, s) - ch(bg, s)) - ch(c, s)) < 14 } }
        }
        val arc = marked.maxBy { distance(it.third, bg) }.third
        assertTrue("the refresh arc ${hex(arc)} is the theme's blue ${hex(blue)} (dark=$dark)", (blend(arc) ?: 0f) > 0.5f)
        // the 40 dp disc around the arc (13 dp from its middle, between the arc and the disc's edge): the theme background
        val ring = marked.filter { (blend(it.third) ?: 0f) > 0.5f }
        val cy = (ring.minOf { it.second } + ring.maxOf { it.second }) / 2
        val mx = (ring.minOf { it.first } + ring.maxOf { it.first }) / 2
        val off = (13 * density).toInt()
        assertEquals("the refresh disc (dark=$dark)", listOf(hex(bg), hex(bg)), listOf(hex(image.getPixel(mx - off, cy)), hex(image.getPixel(mx + off, cy))))
    }

    @Test
    fun refreshIndicatorOnTheLightTheme() = refreshIndicator(dark = false)

    @Test
    fun refreshIndicatorOnTheDarkTheme() = refreshIndicator(dark = true)
}
