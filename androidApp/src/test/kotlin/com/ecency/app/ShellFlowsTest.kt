package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.Guarded
import com.ecency.app.ui.Navigator
import com.ecency.core.app.AppScreen
import com.ecency.core.app.AppTab
import com.ecency.core.app.RouteName
import com.ecency.core.app.SideMenu
import com.ecency.core.app.ThemeMode
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinLock
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

/** The app shell against a scripted node: tabs, side menu, back, placeholders, global UI, the lock over it all. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShellFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env() }
    private val navigator = Navigator()
    private val opened = mutableListOf<String>()

    private val host = object : AppHost {
        override fun openExternal(url: String) { opened += url }
        override fun toast(text: String) {}
        override fun copy(text: String) {}
        override fun buildNumber() = "7"
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

    private fun launch(fontScale: Float = 1f) = compose.setContent {
        val d = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, fontScale)) {
            EcencyApp(env, host, navigator)
        }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** The header and its picture inside the drawer, the account lines inside the header. */
    private fun assertTheHeaderFitsTheDrawer() {
        val drawer = bounds("drawer")
        val header = bounds("drawerHeader")
        val image = bounds("drawerHeaderImage")
        for ((name, box) in listOf("header" to header, "picture" to image)) {
            assertTrue("$name ($box) no wider than the drawer ($drawer)", box.width <= drawer.width + 1f)
            assertTrue("$name ($box) inside the drawer ($drawer)", box.left >= drawer.left - 1f && box.right <= drawer.right + 1f)
        }
        val user = bounds("drawerUser")
        assertTrue("@user ($user) inside the header ($header)", user.top >= header.top && user.bottom <= header.bottom + 1f)
    }

    @Test
    fun theDrawerHeaderFitsTheDrawerAtEveryFontScaleAndDirection() {
        node.signIn()
        launch()
        waitFor("headerUser")
        openDrawer()
        assertTheHeaderFitsTheDrawer()
    }

    @Test
    fun atTheLargestFontTheDrawerWidensAndItsHeaderGrowsWithTheAccountLines() {
        node.signIn()
        com.ecency.core.l10n.L10n.setLanguage("ar")
        try {
            launch(fontScale = 2f)
            waitFor("headerUser")
            openDrawer()
            assertTheHeaderFitsTheDrawer()
            val density = compose.activity.resources.displayMetrics.density
            assertTrue("wider than RN's 280 dp", bounds("drawer").width > 280 * density)
            val screen = compose.onAllNodes(androidx.compose.ui.test.isRoot()).fetchSemanticsNodes().maxBy { it.size.height }.boundsInRoot
            assertEquals("right to left: the drawer on the right edge", screen.right, bounds("drawer").right, 1f)
        } finally {
            com.ecency.core.l10n.L10n.setLanguage(com.ecency.core.l10n.L10n.SOURCE)
        }
    }

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(tag: String) {
        compose.await("exists(tag)") { exists(tag) }
        compose.waitForIdle()
    }

    private fun tags(prefix: String): List<String> =
        compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("tag starts with $prefix") {
            it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }?.startsWith(prefix) == true
        }, useUnmergedTree = true).fetchSemanticsNodes().map { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] }

    /** Runs what the shared models posted to the main looper (Robolectric pauses it between frames). */
    private fun idleMain() = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    private fun back() = compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

    private fun openDrawer() {
        compose.onNodeWithTag("account").performClick()
        waitFor("drawer")
    }

    @Test
    fun bottomTabsInRnOrderAndGatedWhileSignedOut() {
        launch()
        waitFor("card:alice/trending-one")
        assertEquals(AppTab.entries.map { "bottomTab:${it.name}" }, tags("bottomTab:"))
        compose.onNodeWithTag("createFab").assertExists()
        compose.onNodeWithTag("headerLogin").assertExists()
        assertFalse("boost only signed in", exists("headerBoost"))

        // the Waves tab is built (WavesFlowsTest covers it): its own tabs, no placeholder
        compose.onNodeWithTag("bottomTab:WAVES").performClick()
        waitFor("wavesScreen")
        assertFalse(exists("comingSoon"))
        compose.onNodeWithTag("waves:tab:for-you").assertExists()

        compose.onNodeWithTag("bottomTab:WALLET").performClick()
        waitFor("loginPrompt")
        assertTrue(compose.onAllNodes(hasText("Login to see")).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("loginPromptButton").performClick()
        waitFor("login")
        assertEquals(listOf(AppScreen.Login), navigator.screens)
        assertFalse("pushed screens cover the tab bar", exists("tabBar"))
    }

    /**
     * A small phone (320 x 700 dp): the footer group stays at the bottom, the main group scrolls on its own. A shorter
     * one (320 x 640, below) has no room left with "Ask Esie" in the footer and scrolls the whole menu instead.
     */
    @Test
    @Config(qualifiers = "w320dp-h700dp")
    fun sideMenuFooterIsPinnedAndTheMainGroupScrolls() {
        node.signIn()
        launch()
        waitFor("headerUser")
        openDrawer()
        val drawer = compose.onNodeWithTag("drawer").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("drawerFooter").fetchSemanticsNode().boundsInRoot
        val main = compose.onNodeWithTag("drawerMain").fetchSemanticsNode()
        val version = compose.onNodeWithTag("drawerVersion").fetchSemanticsNode().boundsInRoot
        val dp = compose.density.density
        // the footer block (items, then the version line) ends at the bottom of the menu
        assertEquals("the footer is at the bottom", drawer.bottom, footer.bottom, 1f)
        val logout = compose.onNodeWithTag("menu:logout", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the version line closes it: $version in $footer", version.top >= logout.bottom - 1f && version.bottom <= footer.bottom + 1f)
        assertEquals("a gap between the groups", SideMenu.GROUP_GAP_DP * dp, footer.top - main.boundsInRoot.bottom, 1f)
        assertTrue("the main group scrolls", main.config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy))
        val footerIds = listOf("qr", "ask_esie", "refer", "docs", "settings", "logout")
        val tops = footerIds.map { id ->
            val item = compose.onNodeWithTag("menu:$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$id is inside the footer: $item in $footer", item.top >= footer.top - 1f && item.bottom <= footer.bottom + 1f)
            item.top
        }
        // drawn top to bottom in that order: "Ask Esie" right under QR scan, above "Refer & Earn"
        assertEquals(tops.sorted(), tops)
        // scrolled to its end, the main group's last item shows and the footer did not move
        compose.onNodeWithTag("menu:explore").performScrollTo()
        compose.waitForIdle()
        assertEquals(footer.top, compose.onNodeWithTag("drawerFooter").fetchSemanticsNode().boundsInRoot.top, 1f)
        val explore = compose.onNodeWithTag("menu:explore").fetchSemanticsNode().boundsInRoot
        assertTrue("explore scrolled into view above the footer", explore.bottom <= footer.top)
    }

    /** A landscape phone: no room for the main group under a pinned footer, so the whole menu scrolls as one. */
    @Test
    @Config(qualifiers = "w640dp-h320dp")
    fun aShortScreenScrollsTheWholeMenu() = assertTheWholeMenuScrolls()

    /** A 320 x 640 dp portrait phone: the six footer rows leave the main group too little room, so it falls back too. */
    @Test
    @Config(qualifiers = "w320dp-h640dp")
    fun aSmallPortraitPhoneScrollsTheWholeMenu() = assertTheWholeMenuScrolls()

    private fun assertTheWholeMenuScrolls() {
        node.signIn()
        launch()
        waitFor("headerUser")
        openDrawer()
        // one scroll for the whole menu: the footer sits right after the main group, inside it
        // unclipped positions: part of the menu is below the visible area
        val main = compose.onNodeWithTag("drawerMain").fetchSemanticsNode()
        val footer = compose.onNodeWithTag("drawerFooter").fetchSemanticsNode()
        val mainBottom = main.positionInRoot.y + main.size.height
        assertEquals("the gap follows the main group", SideMenu.GROUP_GAP_DP * compose.density.density, footer.positionInRoot.y - mainBottom, 1f)
        assertTrue(compose.onNodeWithTag("drawerMenu").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsActions.ScrollBy))
        compose.onNodeWithTag("menu:logout").performScrollTo()
        compose.waitForIdle()
        val logout = compose.onNodeWithTag("menu:logout").fetchSemanticsNode().boundsInRoot
        assertTrue("logout scrolls into view", logout.bottom <= compose.onNodeWithTag("drawer").fetchSemanticsNode().boundsInRoot.bottom + 1f)
    }

    @Test
    fun signedInTabsShowTheHeaderAccountAndNoGate() {
        node.signIn()
        launch()
        waitFor("headerUser")
        compose.onNodeWithTag("headerUser", useUnmergedTree = true).assertTextContains("@tester", substring = true)
        compose.onNodeWithTag("headerBoost").assertExists()
        compose.onNodeWithTag("bottomTab:NOTIFICATION").performClick()
        // the notifications tab is built: its own screen, no gate for a signed-in user
        waitFor("notificationList")
        assertFalse(exists("loginPrompt"))
        compose.onNodeWithTag("bottomTab:WAVES").performClick()
        // and so is the Waves tab
        waitFor("wavesScreen")
        assertFalse(exists("comingSoon"))
    }

    @Test
    fun createButtonAsksForLoginThenOpensTheEditorPlaceholder() {
        launch()
        waitFor("createFab")
        compose.onNodeWithTag("createFab").performClick()
        waitFor("login")
        assertEquals(AppScreen.Login, navigator.state.top)
    }

    @Test
    fun signedOutDrawerItemsInRnOrderWithVersion() {
        launch()
        waitFor("account")
        openDrawer()
        assertEquals(SideMenu.LOGGED_OUT.map { "menu:${it.id}" }, tags("menu:"))
        compose.onNodeWithTag("drawerVersion", useUnmergedTree = true).assertTextContains("v4.0.0, 7")
        assertFalse(exists("drawerUser"))
        compose.onNodeWithTag("menu:docs").apply { runCatching { performScrollTo() } }.performClick() // pinned, or in the one scroll on a short screen
        compose.waitForIdle()
        assertEquals(listOf(SideMenu.DOCS_URL), opened)
        assertFalse(navigator.state.drawerOpen)
    }

    @Test
    fun signedInDrawerItemsHeaderAndPlaceholders() {
        node.signIn()
        launch()
        waitFor("headerUser")
        openDrawer()
        assertEquals(SideMenu.LOGGED_IN.map { "menu:${it.id}" }, tags("menu:"))
        compose.onNodeWithTag("drawerUser", useUnmergedTree = true).assertTextContains("@tester")
        compose.onNodeWithTag("menu:bookmarks").performScrollTo().performClick()
        waitFor("bookmarks")
        assertEquals(listOf<AppScreen>(AppScreen.Bookmarks(0)), navigator.screens)
        assertFalse(navigator.state.drawerOpen)
    }

    @Test
    fun backClosesTheDrawerThenPopsThenReturnsToFeed() {
        launch()
        waitFor("account")
        compose.onNodeWithTag("bottomTab:CHATS").performClick()
        waitFor("loginPrompt")
        openDrawer()
        back()
        compose.waitForIdle()
        assertFalse("first back closes the drawer", navigator.state.drawerOpen)
        assertEquals(AppTab.CHATS, navigator.state.tab)
        compose.runOnUiThread { navigator.push(AppScreen.Settings) }
        waitFor("settings")
        back()
        waitFor("tabBar")
        assertTrue(navigator.state.atRoot)
        back()
        compose.waitForIdle()
        assertEquals("then back to the Feed tab", AppTab.FEED, navigator.state.tab)
        assertFalse("at the Feed root the system handles back", compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    @Test
    fun logoutAsksFirstThenSignsOut() {
        node.signIn()
        launch()
        waitFor("headerUser")
        openDrawer()
        compose.onNodeWithTag("menu:logout").apply { runCatching { performScrollTo() } }.performClick() // pinned, or in the one scroll on a short screen
        waitFor("logoutCancel")
        compose.onNodeWithTag("logoutCancel").performClick()
        compose.waitForIdle()
        assertTrue(env.session.current.isLoggedIn)
        compose.onNodeWithTag("menu:logout").apply { runCatching { performScrollTo() } }.performClick() // pinned, or in the one scroll on a short screen
        waitFor("logoutConfirm")
        compose.onNodeWithTag("logoutConfirm").performClick()
        waitFor("headerLogin")
        assertFalse(env.session.current.isLoggedIn)
        assertTrue(KeyVault(node.store).accounts().isEmpty())
    }

    @Test
    fun theSideMenuOpensTheAccountSwitcher() {
        node.signIn()
        launch()
        waitFor("headerUser")
        openDrawer()
        compose.onNodeWithTag("drawerAccounts").performClick()
        compose.mainClock.advanceTimeBy(2_000) // the sheet slides up
        waitFor("accountSwitcher")
        assertFalse(navigator.state.drawerOpen)
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("accountAdd", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        waitFor("login") // "Add an existing account" opens the login screen
    }

    @Test
    fun switchingAccountsDropsScreensOfThePreviousOne() {
        node.signIn()
        KeyVault(node.store).save(
            com.ecency.core.secure.VaultAccount("tester-two", com.ecency.core.secure.AuthType.POSTING_KEY, postingKey = node.key.toWif()),
        )
        navigator.push(AppScreen.Post("alice", "trending-one"))
        navigator.push(AppScreen.of(RouteName.TRANSFER))
        launch()
        compose.await("env.session.current.accounts.size == 2") { idleMain(); env.session.current.accounts.size == 2 }
        kotlin.concurrent.thread { kotlinx.coroutines.runBlocking { env.session.switchTo("tester-two") } }
        compose.await("env.session.current.username == \"tester-two\" && navigator.screens.size == 1") { idleMain(); env.session.current.username == "tester-two" && navigator.screens.size == 1 }
        assertEquals(listOf<AppScreen>(AppScreen.Post("alice", "trending-one")), navigator.screens)
    }

    @Test
    fun drawerLogoutSwitchesToTheNextAccount() {
        node.signIn()
        com.ecency.core.secure.KeyVault(node.store).save(
            com.ecency.core.secure.VaultAccount("tester-two", com.ecency.core.secure.AuthType.POSTING_KEY, postingKey = node.key.toWif()),
        )
        launch()
        waitFor("headerUser")
        runCatching { compose.await("env.session.current.accounts.size == 2", boundMillis = 10_000) { idleMain(); env.session.current.accounts.size == 2 } }
            .onFailure { throw AssertionError("accounts=${env.session.current}", it) }
        openDrawer()
        compose.onNodeWithTag("menu:logout").apply { runCatching { performScrollTo() } }.performClick() // pinned, or in the one scroll on a short screen
        waitFor("logoutConfirm")
        compose.onNodeWithTag("logoutConfirm").performClick()
        runCatching { compose.await("env.session.current.username == \"tester-two\"", boundMillis = 10_000) { idleMain(); env.session.current.username == "tester-two" } }
            .onFailure { throw AssertionError("after logout=${env.session.current}", it) }
        assertTrue("RN: the next saved account becomes current", env.session.current.isLoggedIn)
        assertEquals(null, KeyVault(node.store).account("tester"))
    }

    @Test
    fun toastsBannersOfflineAndBadgesFromSharedCode() {
        launch()
        waitFor("tabBar")
        compose.runOnUiThread { env.ui.toast("Copied") }
        waitFor("toast")
        compose.mainClock.advanceTimeBy(5_000)
        compose.await("!exists(\"toast\")") { !exists("toast") }

        compose.runOnUiThread { env.ui.banner("Reply on @alice", "replied to you", AppScreen.Post("alice", "trending-one")) }
        waitFor("banner")
        compose.onNodeWithTag("banner").performClick()
        waitFor("postTitle")
        assertEquals(AppScreen.Post("alice", "trending-one"), navigator.state.top)

        compose.runOnUiThread { env.connectivity.setOnline(false) }
        waitFor("offlineBanner")
        compose.runOnUiThread { env.connectivity.setOnline(true) }
        compose.await("!exists(\"offlineBanner\")") { !exists("offlineBanner") }

        compose.runOnUiThread { navigator.pop() }
        waitFor("tabBar")
        assertFalse("no provider yet: no badge", exists("badge:NOTIFICATION"))
        compose.runOnUiThread { env.badges.setCounts(notifications = 3, chats = 0) }
        waitFor("badge:NOTIFICATION")
        compose.onNodeWithTag("badge:NOTIFICATION", useUnmergedTree = true).assertTextContains("3")
        assertFalse(exists("badge:CHATS"))
    }

    /** The toast's own window (the last one added: it stacks above sheets). */
    private fun toastWindowView(): android.view.View {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<*>
        val params = global.getDeclaredField("mParams").apply { isAccessible = true }.get(instance) as List<*>
        val index = params.indexOfLast { (it as android.view.WindowManager.LayoutParams).title == "EcencyToast" }
        return views[index] as android.view.View
    }

    /** TalkBack does not reach the toast's window: every toast is announced from the app's own view. */
    @Test
    fun everyToastIsAnnouncedToTalkBack() {
        val manager = compose.activity.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        org.robolectric.Shadows.shadowOf(manager).setEnabled(true)
        launch()
        waitFor("tabBar")
        compose.runOnUiThread { env.ui.toast("Copied to the clipboard") }
        waitFor("toast")
        val announced = org.robolectric.Shadows.shadowOf(manager).sentAccessibilityEvents
            .filter { it.eventType == android.view.accessibility.AccessibilityEvent.TYPE_ANNOUNCEMENT }
            .map { it.text.joinToString() }
        assertEquals(listOf("Copied to the clipboard"), announced)
    }

    @Test
    fun everyToastIsCenteredAtTheBottom() {
        launch()
        waitFor("tabBar")
        val app = compose.onNodeWithTag("tabBar").fetchSemanticsNode().layoutInfo.coordinates.findRootCoordinates().size
        for (text in listOf("Tag already exists", "Your request for deletion is sent. " + "Your account will be removed in a few days. ".repeat(4))) {
            compose.runOnUiThread { env.ui.toast(text) }
            compose.await("toast $text") { compose.onAllNodes(hasTestTag("toast") and hasText(text)).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            // the toast window covers the screen: its bounds are screen bounds
            val toast = compose.onNodeWithTag("toast").fetchSemanticsNode().boundsInWindow
            assertEquals("horizontally centered: $text", app.width / 2f, toast.center.x, 2f)
            assertTrue("in the bottom half", toast.top > app.height / 2f)
            assertTrue("inside the screen", toast.left >= 0f && toast.right <= app.width)
            // a keyboard 300 px tall: the toast moves up by as much and stays above it
            val window = toastWindowView()
            compose.runOnUiThread {
                val ime = androidx.core.view.WindowInsetsCompat.Builder()
                    .setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(), androidx.core.graphics.Insets.of(0, 0, 0, 300))
                    .setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(), true).build()
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(window, ime)
            }
            compose.waitForIdle()
            val lifted = compose.onNodeWithTag("toast").fetchSemanticsNode().boundsInWindow
            assertEquals("lifted above the keyboard", toast.bottom - 300f, lifted.bottom, 2f)
            compose.runOnUiThread {
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(window, androidx.core.view.WindowInsetsCompat.Builder().build())
            }
            compose.runOnUiThread { env.ui.dismiss(env.ui.state.value.toast!!.id) }
            compose.await("toast gone") { !exists("toast") }
        }
    }

    @Test
    fun themeChoiceIsSavedAndApplied() {
        launch()
        waitFor("tabBar")
        compose.runOnUiThread { navigator.push(AppScreen.Settings) }
        waitFor("settings:theme")
        compose.onNodeWithTag("settings:theme").performScrollTo().performClick()
        waitFor("theme:DARK")
        compose.onNodeWithTag("theme:DARK").performClick()
        compose.waitForIdle()
        assertEquals(ThemeMode.DARK, env.settings.current.theme)
        assertEquals("DARK", env.prefs.getString(com.ecency.core.app.SettingsModel.KEY_THEME))
    }

    @Test
    fun theLockHidesTheWholeShell() {
        node.signIn()
        PinLock(KeyVault(node.store), com.ecency.core.secure.PinLock.DEFAULT_ITERATIONS).setPin("1234")
        navigator.push(AppScreen.Settings) // restored from a saved state under the lock
        launch()
        waitFor("pinScreen")
        for (tag in listOf("tabBar", "account", "drawer", "createFab", "headerUser", "settings")) assertFalse("$tag behind the lock", exists(tag))
        compose.runOnUiThread { env.ui.toast("private") }
        compose.waitForIdle()
        assertFalse("no toast over the lock", exists("toast"))
        compose.runOnUiThread { env.connectivity.setOnline(false) }
        compose.waitForIdle()
        assertFalse(exists("offlineBanner"))
        val before = navigator.state
        back()
        compose.waitForIdle()
        assertEquals("back does not reach the app behind the lock", before, navigator.state)
        assertTrue(exists("pinScreen"))
    }

    @Test
    fun aScreenWhoseSetupThrowsShowsRetryInsteadOfCrashing() {
        var fail = true
        compose.setContent {
            Guarded({ if (fail) error("boom") else "ok" }) { Text(it, Modifier.testTag("guardedOk")) }
        }
        waitFor("errorBoundary")
        fail = false
        compose.onNodeWithTag("errorRetry").performClick()
        waitFor("guardedOk")
    }

    @Test
    fun processDeathRestoresTabAndStack() {
        val handle = androidx.lifecycle.SavedStateHandle()
        val before = com.ecency.app.ui.NavigationViewModel(handle)
        before.navigator.selectTab(AppTab.WALLET)
        before.navigator.push(AppScreen.of(RouteName.TRANSFER))
        val restored = com.ecency.app.ui.NavigationViewModel(
            androidx.lifecycle.SavedStateHandle(mapOf(com.ecency.app.ui.NavigationViewModel.KEY to handle.get<ArrayList<String>>(com.ecency.app.ui.NavigationViewModel.KEY))),
        )
        assertEquals(AppTab.WALLET, restored.navigator.state.tab)
        assertEquals(listOf(AppScreen.of(RouteName.TRANSFER)), restored.navigator.screens)
    }

    @Test
    fun backDoesNothingWhileTheLockStateIsUnreadable() {
        node.signIn()
        node.store.failReads = true
        navigator.push(AppScreen.Settings)
        launch()
        waitFor("lockCover")
        runCatching { compose.await("compose.onAllNodes(hasText(\"Retry\"), useUnmergedTree = true).fetchSemanticsNodes().isNo...", boundMillis = 10_000) { idleMain(); compose.onAllNodes(hasText("Retry"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
            .onFailure { throw AssertionError("lock=${env.appLock.current}", it) }
        val before = navigator.state
        back()
        compose.waitForIdle()
        assertEquals("no PIN pad takes back here; the shell guard must", before, navigator.state)
        assertFalse(exists("settings"))
    }

    @Test
    fun poppedEntriesCloseTheirModels() {
        launch()
        waitFor("tabBar")
        compose.runOnUiThread { navigator.push(AppScreen.Post("alice", "trending-one")) }
        waitFor("postTitle")
        val model = navigator.top.peek<com.ecency.core.app.PostModel>("post")!!
        assertFalse(model.watch {}.isClosed)
        back()
        waitFor("tabBar")
        assertTrue("the popped post's model is closed", model.watch {}.isClosed)
    }

    @Test
    fun signOutKeepsTheTabAndDropsGatedScreens() {
        node.signIn()
        navigator.selectTab(AppTab.WALLET)
        navigator.push(AppScreen.Post("alice", "trending-one"))
        navigator.push(AppScreen.of(RouteName.TRANSFER))
        launch()
        waitFor("transferForm")
        compose.await("env.session.current.isLoggedIn") { env.session.current.isLoggedIn }
        // off the test thread: signOut hops to the main looper, idled below
        kotlin.concurrent.thread { kotlinx.coroutines.runBlocking { env.session.signOut() } }
        compose.await("back on the post after signing out") {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() // the model's Main dispatcher
            navigator.screens == listOf<AppScreen>(AppScreen.Post("alice", "trending-one"))
        }
        assertEquals(AppTab.WALLET, navigator.state.tab)
        back()
        waitFor("loginPrompt") // the gated tab asks for sign-in again
    }
}
