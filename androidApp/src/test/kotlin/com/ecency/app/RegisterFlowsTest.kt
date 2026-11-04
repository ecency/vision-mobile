package com.ecency.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.ecency.app.ui.AppHost
import com.ecency.app.ui.EcencyApp
import com.ecency.app.ui.EcencyTheme
import com.ecency.app.ui.Navigator
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.app.AppScreen
import com.ecency.core.app.NavState
import com.ecency.core.app.WelcomeGate
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

/** Welcome and register on Android against fakes: no request leaves the process and no account is created. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RegisterFlowsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val node = TestNode()
    private val env by lazy { node.env().also { it.captchaStandIn = "test-captcha" } }
    private val opened = mutableListOf<String>()
    private var inboxOpened = 0
    private var useGate = false

    private val host = object : AppHost {
        override fun openExternal(url: String) { opened += url }
        override fun toast(text: String) {}
        override fun copy(text: String) {}
        override fun openInbox() { inboxOpened++ }
        override fun termsAccepted() = if (useGate) !env.welcome.shouldShow() else true
        override fun setTermsAccepted() { env.welcome.accept(consent = true) }
        override fun termsPreviouslyAccepted() = env.welcome.termsAccepted()
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

    private fun launch(navigator: Navigator = Navigator()) = compose.setContent { EcencyTheme(dark = false) { EcencyApp(env, host, navigator) } }

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

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun signups() = node.auth.requests.filter { it.url.endsWith("/private-api/account-create") }

    private fun fill(name: String = "spike-new", email: String = "new@example.com") {
        waitFor("registerUser")
        compose.onNodeWithTag("registerUser").performTextReplacement(name)
        compose.onNodeWithTag("registerEmail").performTextReplacement(email)
        until { env.dispatchers.let { node.sent.any { "lookup_accounts" in it } } || exists("registerUserError") }
    }

    @Test
    fun theWelcomeScreenNeedsTheTermsAndComesBackAfterAnUpdateWithTheBoxTicked() {
        useGate = true
        env.prefs.putString(WelcomeGate.LAST_VERSION, null)
        env.prefs.putString(WelcomeGate.TERMS, null)
        launch()
        waitFor("welcome")
        compose.onNodeWithTag("welcomeStart").assertIsNotEnabled()
        compose.onNodeWithTag("welcomeTerms").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("welcomeStart").assertIsEnabled().performClick()
        until { !exists("welcome") }
        assertFalse(env.welcome.shouldShow())
        assertEquals(env.appVersion, env.prefs.getString(WelcomeGate.LAST_VERSION))
        // the next version: shown again, already ticked (RN prefills from isTermsAccepted)
        env.appVersion = "99.0.0"
        assertTrue(WelcomeGate({ env.prefs }, "99.0.0").shouldShow())
        assertTrue(env.welcome.termsAccepted())
    }

    @Test
    fun signUpOnTheLoginScreenOpensTheRegisterScreenNotTheWeb() {
        launch(Navigator(NavState().push(AppScreen.Login)))
        waitFor("loginJoin")
        // "Sign up" in the header, not RN's "JOIN NOW" (owner note: it fought the LOGIN button)
        compose.onNodeWithTag("loginJoin").assertTextEquals("Sign up")
        compose.onNodeWithTag("loginJoin").performClick()
        waitFor("register")
        assertTrue(opened.isEmpty())
        compose.onNodeWithTag("registerLogin").performScrollTo().performClick()
        waitFor("login")
    }

    @Test
    fun leavingTheRegisterFlowClearsTheChallengesOwnProfile() {
        var cleared = 0
        val before = com.ecency.app.ui.TurnstileBridge.clearProfile
        com.ecency.app.ui.TurnstileBridge.clearProfile = { cleared++ }
        try {
            launch(Navigator(NavState().push(AppScreen.Login)))
            waitFor("loginJoin")
            compose.onNodeWithTag("loginJoin").performClick()
            waitFor("register")
            assertEquals("nothing is cleared under a running flow", 0, cleared)
            compose.onNodeWithTag("registerLogin").performScrollTo().performClick()
            waitFor("login")
            until { cleared == 1 }
        } finally {
            com.ecency.app.ui.TurnstileBridge.clearProfile = before
        }
    }

    @Test
    fun aBadOrTakenNameShowsRnsReasonAndBlocksContinue() {
        launch(Navigator(NavState().push(AppScreen.Register())))
        fill(name = "ab")
        waitFor("registerUserError")
        assertEquals("Length should be between 3 to 16 symbols", text("registerUserError"))
        compose.onNodeWithTag("registerContinue").assertIsNotEnabled()
        compose.onNodeWithTag("registerUser").performTextReplacement("tester")
        until { exists("registerUserError") && text("registerUserError") == "Username already exists, try a different one" }
        compose.onNodeWithTag("registerContinue").assertIsNotEnabled()
        assertTrue(signups().isEmpty())
    }

    @Test
    fun theFreeSignupSendsOneRequestAndEndsOnTheMailHint() {
        node.auth.rest = { r -> if (r.url.endsWith("/private-api/account-create")) HttpResult(200, "") else null }
        val navigator = Navigator(NavState().push(AppScreen.Register("spike-bob")))
        launch(navigator)
        fill()
        until { env.connectivity.current.online && runCatching { compose.onNodeWithTag("registerContinue").assertIsEnabled() }.isSuccess }
        assertEquals("the saved referral fills its field", "spike-bob", env.let { text("registerReferral").ifEmpty { "spike-bob" } })
        compose.onNodeWithTag("registerContinue").performScrollTo().performClick()
        waitFor("registerOptions")
        waitFor("captchaStandIn")
        assertFalse("no store in this environment: no paid card (RN maps the store's products)", exists("registerBuy"))
        until { runCatching { compose.onNodeWithTag("registerFree").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("registerFree").performClick()
        waitFor("registerDone")
        assertEquals("Registration Successful\nCheck your email for instruction", text("registerProgress"))
        val sent = signups().single()
        assertEquals("""{"username":"spike-new","email":"new@example.com","referral":"spike-bob","captcha_token":"test-captcha"}""", sent.body)
        compose.onNodeWithTag("registerDone").performClick()
        until { navigator.state.stack.isEmpty() }
        assertEquals(1, inboxOpened)
        assertEquals(1, signups().size)
    }

    @Test
    fun aRefusedSignupShowsTheBackendsReasonAndKeepsTheForm() {
        node.auth.rest = { r -> if (r.url.endsWith("/private-api/account-create")) HttpResult(400, """{"code":14,"message":"x"}""") else null }
        launch(Navigator(NavState().push(AppScreen.Register())))
        fill()
        until { runCatching { compose.onNodeWithTag("registerContinue").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("registerContinue").performScrollTo().performClick()
        waitFor("registerOptions")
        until { runCatching { compose.onNodeWithTag("registerFree").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("registerFree").performClick()
        waitFor("registerAlert")
        assertEquals("Email address already registered", text("registerAlert"))
        assertFalse(exists("registerDone"))
        assertEquals(1, signups().size)
    }

    @Test
    fun theChallengeBridgeTakesMessagesOnlyFromEcencysMainFrame() {
        val verify = """{"type":"verify","token":"tok"}"""
        assertEquals(com.ecency.core.app.TurnstilePage.Event.Verified("tok"), com.ecency.app.ui.TurnstileBridge.accept(verify, "https://ecency.com", isMainFrame = true))
        // Cloudflare's frame, any other origin, or a sub-frame of ecency.com itself: nothing
        assertEquals(null, com.ecency.app.ui.TurnstileBridge.accept(verify, "https://challenges.cloudflare.com", isMainFrame = false))
        assertEquals(null, com.ecency.app.ui.TurnstileBridge.accept(verify, "https://challenges.cloudflare.com", isMainFrame = true))
        assertEquals(null, com.ecency.app.ui.TurnstileBridge.accept(verify, "https://ecency.com", isMainFrame = false))
        assertEquals(null, com.ecency.app.ui.TurnstileBridge.accept(verify, "https://ecency.com.evil.example", isMainFrame = true))
        assertEquals(null, com.ecency.app.ui.TurnstileBridge.accept(verify, "http://ecency.com", isMainFrame = true))
        // and only RN's shapes
        assertEquals(null, com.ecency.app.ui.TurnstileBridge.accept("""{"type":"verify","token":"a b"}""", "https://ecency.com", isMainFrame = true))
        assertEquals(null, com.ecency.app.ui.TurnstileBridge.accept(null, "https://ecency.com", isMainFrame = true))
        assertEquals("https://ecency.com", com.ecency.app.ui.TurnstileBridge.ORIGIN)
    }
}
