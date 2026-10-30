package com.ecency.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.NavigationViewModel
import com.ecency.app.ui.Navigator
import com.ecency.core.app.AppScreen
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Links that open the app: cold start, a running app (onNewIntent), and a link that arrives while locked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkEntryTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val node = TestNode()
    private val env by lazy { node.env() }
    private val post = AppScreen.Post("alice", "trending-one")

    @Before
    fun setUp() {
        acceptTerms()
        EcencyGraph.install(env)
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
        EcencyGraph.install(null)
        SingletonImageLoader.reset()
    }

    private fun link(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .setClass(ApplicationProvider.getApplicationContext(), MainActivity::class.java)

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun navigator(scenario: ActivityScenario<MainActivity>): Navigator {
        var nav: Navigator? = null
        scenario.onActivity { nav = ViewModelProvider(it)[NavigationViewModel::class.java].navigator }
        return nav!!
    }

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    @Test
    fun aColdStartLinkOpensItsScreenOnceEvenAfterRecreation() {
        val scenario = ActivityScenario.launch<MainActivity>(link("https://ecency.com/@alice/trending-one"))
        compose.await("exists(\"postTitle\")") { idle(); exists("postTitle") }
        assertEquals(listOf<AppScreen>(post), navigator(scenario).screens)
        scenario.onActivity { navigator(scenario).push(AppScreen.Profile("bob")) } // the reader moved on
        scenario.recreate() // savedInstanceState != null: the launch intent is not handled again
        compose.await("exists(\"profile\")") { idle(); exists("profile") }
        assertEquals(listOf(post, AppScreen.Profile("bob")), navigator(scenario).screens)
        scenario.close()
    }

    @Test
    fun aColdStartLinkUnderTheWelcomeScreenOpensOnceTheTermsAreAccepted() {
        // RN #2627: a first launch from a link showed the welcome screen and then lost the link
        env.prefs.putString(com.ecency.core.app.WelcomeGate.LAST_VERSION, "")
        env.prefs.putString(com.ecency.core.app.WelcomeGate.TERMS, "")
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("ecency.ui", android.content.Context.MODE_PRIVATE).edit().putBoolean("termsAccepted", false).commit()
        val scenario = ActivityScenario.launch<MainActivity>(link("https://ecency.com/@alice/trending-one"))
        compose.await("exists(\"welcome\")") { idle(); exists("welcome") }
        assertFalse("nothing behind the welcome screen", exists("postTitle"))
        // the target is kept while the terms show
        compose.await("navigator(scenario).screens.isNotEmpty()") { idle(); navigator(scenario).screens.isNotEmpty() }
        assertEquals(listOf<AppScreen>(post), navigator(scenario).screens)
        compose.onNode(hasTestTag("welcomeTerms"), useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.onNode(hasTestTag("welcomeStart"), useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.await("exists(\"postTitle\")") { idle(); exists("postTitle") }
        assertEquals(listOf<AppScreen>(post), navigator(scenario).screens)
        scenario.close()
    }

    @Test
    fun widgetShortcutsOpenTheirPlaceAndScreensThatNeedAnAccountAskForTheLogin() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.await("exists(\"tabBar\")") { idle(); exists("tabBar") }
        scenario.onActivity { it.handleLink(link(com.ecency.core.app.AppShortcuts.NOTIFICATIONS)) }
        compose.await("the Notifications tab") { idle(); navigator(scenario).state.tab == com.ecency.core.app.AppTab.NOTIFICATION }
        scenario.onActivity { it.handleLink(link(com.ecency.core.app.AppShortcuts.PERKS)) }
        idle()
        compose.waitForIdle()
        assertFalse("signed out: Perks is behind the login", AppScreen.Perks in navigator(scenario).screens)
        scenario.onActivity { it.handleLink(link(com.ecency.core.app.AppShortcuts.NEW_WAVE)) }
        compose.await("the Waves tab") { idle(); navigator(scenario).state.tab == com.ecency.core.app.AppTab.WAVES }
        scenario.onActivity { it.handleLink(link("ecency://compose/evil")) }
        idle()
        assertFalse("an unknown shortcut opens nothing", navigator(scenario).screens.any { it is AppScreen.Editor })
        scenario.close()
    }

    @Test
    fun aLinkToTheRunningAppGoesOnTopOfTheOpenTab() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.await("exists(\"tabBar\")") { idle(); exists("tabBar") }
        scenario.onActivity { it.handleLink(link("https://ecency.com/@alice")) } // what onNewIntent calls
        compose.await("navigator(scenario).screens.isNotEmpty()") { idle(); navigator(scenario).screens.isNotEmpty() }
        assertEquals(listOf<AppScreen>(AppScreen.Profile("alice")), navigator(scenario).screens)
        scenario.onActivity { it.handleLink(link("https://evil.example/@alice/x")) }
        idle()
        assertEquals("other hosts are ignored", 1, navigator(scenario).screens.size)
        scenario.close()
    }

    @Test
    fun anUnclaimedEcencyPageOpensInTheBrowserNotNothing() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.await("exists(\"tabBar\")") { idle(); exists("tabBar") }
        scenario.onActivity { activity ->
            shadowOf(activity).clearNextStartedActivities()
            activity.handleLink(link("https://ecency.com/faq"))
            assertNotABareView(shadowOf(activity).nextStartedActivity, "https://ecency.com/faq")
            // a page a screen claims, and other hosts, start nothing
            activity.handleLink(link("https://ecency.com/@alice/feed"))
            activity.handleLink(link("https://evil.example/faq"))
            assertEquals(null, shadowOf(activity).nextStartedActivity)
        }
        idle()
        assertEquals(listOf<AppScreen>(AppScreen.Profile("alice", "")), navigator(scenario).screens)
        scenario.close()
    }

    /**
     * The browser fallback never hands Android a bare VIEW: this app is the verified handler of ecency.com links, so
     * one would come straight back here and loop. Without a Custom Tabs browser it is a chooser that leaves the app
     * out (Robolectric resolves no intents, so the intent itself is checked).
     */
    private fun assertNotABareView(started: Intent?, url: String) {
        assertEquals(Intent.ACTION_CHOOSER, started?.action)
        @Suppress("DEPRECATION") val target = started?.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals(Intent.ACTION_VIEW, target?.action)
        assertEquals(url, target?.dataString)
        @Suppress("DEPRECATION") val excluded = started?.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)?.toList()
        assertEquals(listOf(android.content.ComponentName(ApplicationProvider.getApplicationContext(), MainActivity::class.java)), excluded)
    }

    @Test
    fun theFallbackForAPageThatWouldComeBackHereNeverLoops() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.await("exists(\"tabBar\")") { idle(); exists("tabBar") }
        scenario.onActivity { activity ->
            shadowOf(activity).clearNextStartedActivities()
            // matches the manifest's /@.* App Link pattern, and no screen claims it
            activity.handleLink(link("https://ecency.com/@alice/Some.Thing"))
            assertNotABareView(shadowOf(activity).nextStartedActivity, "https://ecency.com/@alice/Some.Thing")
            assertEquals(null, shadowOf(activity).nextStartedActivity)
        }
        scenario.close()
    }

    @Test
    fun aDefaultBrowserWithoutCustomTabsGetsThePageThroughTheBrowserSelector() {
        val pm = shadowOf(ApplicationProvider.getApplicationContext<android.content.Context>().packageManager)
        pm.addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),
            android.content.pm.ResolveInfo().apply { activityInfo = android.content.pm.ActivityInfo().apply { packageName = "org.example.plain"; name = "Browser" } },
        )
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.await("exists(\"tabBar\")") { idle(); exists("tabBar") }
        scenario.onActivity { activity ->
            shadowOf(activity).clearNextStartedActivities()
            activity.handleLink(link("https://ecency.com/@alice/Some.Thing"))
            val started = shadowOf(activity).nextStartedActivity
            assertEquals("no chooser", Intent.ACTION_VIEW, started?.action)
            assertEquals("https://ecency.com/@alice/Some.Thing", started?.dataString)
            // the selector resolves browser apps only, so this app (an ecency.com handler) is never picked
            assertEquals(Intent.ACTION_MAIN, started?.selector?.action)
            assertTrue(started?.selector?.hasCategory(Intent.CATEGORY_APP_BROWSER) == true)
            assertEquals(null, shadowOf(activity).nextStartedActivity)
        }
        scenario.close()
    }

    @Test
    fun withACustomTabsBrowserTheTabIsPinnedToIt() {
        val pm = shadowOf(ApplicationProvider.getApplicationContext<android.content.Context>().packageManager)
        val browser = "org.example.browser"
        pm.addResolveInfoForIntent(
            Intent(Intent.ACTION_VIEW, Uri.parse("http://")),
            android.content.pm.ResolveInfo().apply { activityInfo = android.content.pm.ActivityInfo().apply { packageName = browser; name = "Browser" } },
        )
        pm.addResolveInfoForIntent(
            Intent("android.support.customtabs.action.CustomTabsService").setPackage(browser),
            android.content.pm.ResolveInfo().apply { serviceInfo = android.content.pm.ServiceInfo().apply { packageName = browser; name = "Tabs" } },
        )
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.await("exists(\"tabBar\")") { idle(); exists("tabBar") }
        scenario.onActivity { activity ->
            shadowOf(activity).clearNextStartedActivities()
            activity.handleLink(link("https://ecency.com/@alice/Some.Thing"))
            val started = shadowOf(activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, started?.action)
            assertEquals(browser, started?.`package`)
        }
        scenario.close()
    }

    @Test
    fun aLinkWhileLockedOpensBehindTheLock() {
        node.signIn()
        PinLock(KeyVault(node.store), PinLock.DEFAULT_ITERATIONS).setPin("1234")
        val scenario = ActivityScenario.launch<MainActivity>(link("https://ecency.com/@alice/trending-one"))
        compose.await("exists(\"pinScreen\")") { idle(); exists("pinScreen") }
        assertEquals(listOf<AppScreen>(post), navigator(scenario).screens)
        assertFalse("nothing of the post shows over the lock", exists("postTitle"))
        assertTrue(exists("pinScreen"))
        scenario.close()
    }

    @Test
    fun authLinksGoToTheLinkModelNotTheNavigation() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.await("exists(\"tabBar\")") { idle(); exists("tabBar") }
        scenario.onActivity { it.handleLink(link("ecency://login?username=tester&callback=https%3A%2F%2Fexample.com%2Fcb")) }
        idle()
        assertTrue(navigator(scenario).state.atRoot)
        scenario.close()
    }

    @Test
    fun aColdStartEntryWaitsForTheAccountsBeforeGating() {
        node.signIn()
        val gate = java.util.concurrent.CountDownLatch(1)
        node.store.readGate = gate
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val transfer = AppScreen.of(com.ecency.core.app.RouteName.TRANSFER)
        scenario.onActivity { it.openEntry(com.ecency.core.app.NavEntry(com.ecency.core.app.AppTab.WALLET, listOf(transfer))) }
        idle()
        assertTrue("still reading the accounts: nothing opened yet", navigator(scenario).state.atRoot)
        gate.countDown()
        compose.await("navigator(scenario).screens.isNotEmpty()") { idle(); navigator(scenario).screens.isNotEmpty() }
        assertEquals("signed in: the gated screen, not the login screen", listOf(transfer), navigator(scenario).screens)
        assertEquals(com.ecency.core.app.AppTab.WALLET, navigator(scenario).state.tab)
        scenario.close()
    }
}
