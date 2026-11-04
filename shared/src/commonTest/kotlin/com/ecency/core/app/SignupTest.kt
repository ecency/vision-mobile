package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Welcome and register: RN's rules against its own output, the register flow against fakes. No account is ever created. */
class SignupTest {
    private val taken = setOf("spike-alice", "spike-bob")

    /** A node that answers `lookup_accounts` like the chain: names from the prefix on, in order. */
    private fun TestScope.app(lookupDown: () -> Boolean = { false }): TestApp = TestApp(this) { req ->
        if (req.method == "condenser_api.lookup_accounts") {
            if (lookupDown()) return@TestApp HttpResult(500, "")
            val prefix = Regex("\"params\":\\[\"([^\"]*)\"").find(req.body)!!.groupValues[1]
            FakeTransport.result(req, (taken + "zzz-neighbour").filter { it >= prefix }.sorted().joinToString(",", "[", "]") { "\"$it\"" })
        } else HttpResult(500, "")
    }

    private fun TestApp.signups(): List<AuthHttpRequest> = authBackend.sent.filter { it.url.endsWith("/private-api/account-create") }

    private fun TestScope.filled(app: TestApp, name: String = "spike-new", referral: String = ""): RegisterModel {
        val model = app.env.registerModel("")
        model.setUsername(name)
        model.setEmail("new@example.com")
        if (referral.isNotEmpty()) model.setReferral(referral)
        testScheduler.advanceUntilIdle()
        return model
    }

    private fun TestScope.atOptions(app: TestApp, name: String = "spike-new"): RegisterModel = filled(app, name).also {
        it.continueToOptions()
        it.captchaVerified("captcha-token-1")
    }

    @Test
    fun theLoginHeaderSaysSignUpNotJoinNow() {
        // owner note 10-28: "JOIN NOW" fought the LOGIN button; the header now reads like the register screen it opens
        assertEquals("Sign up", LoginCopy.JOIN)
        assertEquals(com.ecency.core.l10n.L.register.title, LoginCopy.JOIN)
        assertEquals("LOGIN", LoginCopy.LOGIN)
    }

    // ---- RN golden vectors ----

    @Test
    fun usernameRulesMatchRn() {
        SignupGoldens.USERNAMES.forEach { (name, expected, exchange) ->
            assertEquals(expected, UsernameRules.error(name)?.name?.lowercase(), "getUsernameError(\"$name\")")
            assertEquals(exchange, UsernameRules.isExchangeLike(name), "isExchangeLikeUsername(\"$name\")")
        }
    }

    @Test
    fun restrictedPrefixMatchesRn() = SignupGoldens.RESTRICTED_PREFIX.forEach { (name, expected) ->
        assertEquals(expected, UsernameRules.hasRestrictedPrefix(name), name)
    }

    @Test
    fun emailPatternMatchesRn() = SignupGoldens.EMAILS.forEach { (email, expected) ->
        assertEquals(expected, UsernameRules.isValidEmail(email), "email \"$email\"")
    }

    @Test
    fun versionNumbersMatchRn() = SignupGoldens.VERSIONS.forEach { (text, expected) ->
        assertEquals(expected, AppVersions.parse(text), "parseVersionNumber(\"$text\")")
    }

    @Test
    fun errorMessagesAreRnsCopy() {
        assertEquals("Length should be between 3 to 16 symbols", UsernameError.LENGTH.message)
        assertEquals("Should start with a letter", UsernameError.START_LETTER.message)
        assertEquals(UsernameError.SYMBOLS.message, UsernameError.TRAILING_HYPHEN.message)
        assertEquals("Should not contain underscore", UsernameError.UNDERSCORE.message)
        assertEquals("Should not contain double hyphens", UsernameError.DOUBLE_HYPHENS.message)
    }

    // ---- welcome ----

    @Test
    fun welcomeShowsOnTheFirstLaunchAndAgainAfterAnUpdateWithTheTermsTicked() {
        val prefs = InMemoryPrefs()
        val first = WelcomeGate({ prefs }, "4.0.0")
        assertTrue(first.shouldShow())
        assertFalse(first.termsAccepted())
        assertFalse(first.accept(consent = false), "Get started needs the box")
        assertTrue(first.shouldShow())
        assertTrue(first.accept(consent = true))
        assertFalse(first.shouldShow())
        assertFalse(WelcomeGate({ prefs }, "4.0.0").shouldShow(), "the same version again")
        assertFalse(WelcomeGate({ prefs }, "3.9.9").shouldShow(), "a downgrade is not a new version")
        val updated = WelcomeGate({ prefs }, "4.0.1")
        assertTrue(updated.shouldShow(), "RN shows it after each update")
        assertTrue(updated.termsAccepted(), "with the box already ticked")
    }

    // ---- register form ----

    @Test
    fun aRedirectRefusedByTheTransportKeepsItsStatusInTheSignupError() = runTest {
        val app = app()
        var calls = 0
        app.authBackend.rest = { r ->
            if (r.url.endsWith("/private-api/account-create")) {
                calls++
                throw com.ecency.core.api.EcencyHttpException(302, "Unexpected redirect (302)")
            } else {
                null
            }
        }
        val e = kotlin.test.assertFailsWith<com.ecency.core.api.ecency.SignupException> { app.env.onboardingApi.signUp("spike-new", "a@example.com", "", "token") }
        assertEquals(302, e.status, "an answer, not a lost connection (status 0)")
        assertEquals(1, calls)
    }

    @Test
    fun aFreeNameIsValidAfterTheDebounceAndATakenOneSaysSo() = runTest {
        val app = app()
        val model = app.env.registerModel("")
        model.setUsername("Spike-New")
        assertEquals("spike-new", model.current.username, "lowercased as typed")
        testScheduler.advanceTimeBy(499)
        assertTrue(app.transport.sent.isEmpty(), "nothing is looked up before the 500 ms pause")
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.transport.sent.size)
        assertTrue("[\"spike-new\",50]" in app.transport.sent[0].body, "SDK lookupAccountsQueryOptions parameters")
        assertTrue(model.current.usernameValid)
        assertEquals("", model.current.usernameError)

        model.setUsername("spike-alice")
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.usernameValid)
        assertTrue(model.current.userExists)
        assertEquals("Username already exists, try a different one", model.current.usernameError)
    }

    @Test
    fun aNameTheChainRefusesIsNeverLookedUp() = runTest {
        val app = app()
        val model = app.env.registerModel("")
        model.setUsername("ab")
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.usernameValid)
        assertEquals("Length should be between 3 to 16 symbols", model.current.usernameError)
        model.setUsername("bittrexx")
        testScheduler.advanceUntilIdle()
        assertEquals(UsernameError.EXCHANGE.message, model.current.usernameError)
        model.setUsername("uid12345")
        testScheduler.advanceUntilIdle()
        assertEquals(UsernameError.RESTRICTED.message, model.current.usernameError)
        assertTrue(app.transport.sent.isEmpty())
    }

    @Test
    fun onlyTheLastTypedNameIsJudged() = runTest {
        val app = app()
        val model = app.env.registerModel("")
        model.setUsername("spike-alice")
        testScheduler.advanceTimeBy(300)
        model.setUsername("spike-new")
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.transport.sent.size)
        assertTrue(model.current.usernameValid)
    }

    @Test
    fun offlineTheNameIsNotAcceptedAndNothingIsSent() = runTest {
        val app = app()
        app.env.connectivity.setOnline(false)
        val model = filled(app)
        assertFalse(model.current.usernameValid)
        assertEquals("", model.current.usernameError)
        assertFalse(model.current.canContinue)
        assertTrue(app.transport.sent.isEmpty())
    }

    @Test
    fun aFailedLookupAlertsAndDoesNotAcceptTheName() = runTest {
        val app = app(lookupDown = { true })
        val model = filled(app)
        assertFalse(model.current.usernameValid)
        assertEquals(RegisterAlert("Error", "An error occurred"), model.current.alert)
    }

    @Test
    fun theReferralMustBeAnExistingAccountOrEmpty() = runTest {
        val app = app()
        val model = filled(app, referral = "Spike-Bob")
        assertEquals("spike-bob", model.current.referral)
        assertTrue(model.current.referralValid)
        assertTrue(model.current.canContinue)
        model.setReferral("nobody-here")
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.referralValid)
        assertFalse(model.current.canContinue)
        model.setReferral("")
        assertTrue(model.current.referralValid)
    }

    @Test
    fun continueNeedsEveryFieldAndChecksTheRulesAgain() = runTest {
        val app = app()
        val model = app.env.registerModel("")
        model.continueToOptions()
        assertFalse(model.current.optionsOpen)
        model.setUsername("spike-new")
        model.setEmail("not-an-email")
        testScheduler.advanceUntilIdle()
        assertFalse(model.current.emailValid)
        model.continueToOptions()
        assertFalse(model.current.optionsOpen)
        model.setEmail("new@example.com")
        // a name typed a moment ago is still "valid" until the debounce ran: Continue judges it itself
        model.setUsername("ab")
        model.continueToOptions()
        assertFalse(model.current.optionsOpen)
        assertFalse(model.current.usernameValid)
        assertEquals("Length should be between 3 to 16 symbols", model.current.usernameError)
    }

    @Test
    fun aLinksValuesFillTheFormOnceAndAreValidatedLikeTypedOnes() = runTest {
        val app = app()
        app.env.session.start()
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.authLinks.handle("https://ecency.com/signup?referral=spike-bob&username=Spike-New&email=new@example.com"))
        testScheduler.advanceUntilIdle()
        val nav = assertIs<LinkNavigation.Signup>(app.env.authLinks.current.navigation)
        assertEquals("spike-bob", nav.referral)
        val model = app.env.registerModel(nav.referral)
        testScheduler.advanceUntilIdle()
        assertEquals("spike-new", model.current.username)
        assertEquals("new@example.com", model.current.email)
        assertEquals("spike-bob", model.current.referral)
        assertTrue(model.current.canContinue)
        assertNull(app.env.signupPrefill, "taken once")
        assertEquals("", app.env.registerModel("").current.email)
    }

    @Test
    fun aLinksReferralThatIsNoAccountNameIsDropped() = runTest {
        val app = app()
        app.env.session.start()
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        app.env.authLinks.handle("https://ecency.com/signup?referral=%3Cscript%3E")
        testScheduler.advanceUntilIdle()
        assertEquals("", assertIs<LinkNavigation.Signup>(app.env.authLinks.current.navigation).referral)
        assertEquals("", app.env.signupPrefill?.referral)
    }

    // ---- the free signup ----

    @Test
    fun registerFreeSendsTheSdksRequestOnceAndShowsSuccess() = runTest {
        val app = app()
        app.authBackend.rest = { r -> if (r.url.endsWith("/account-create")) HttpResult(202, "") else null }
        val model = filled(app, referral = "spike-bob")
        model.continueToOptions()
        assertTrue(model.current.optionsOpen)
        assertFalse(model.current.canRegisterFree, "not before the challenge passed")
        model.registerFree()
        testScheduler.advanceUntilIdle()
        assertTrue(app.signups().isEmpty())

        model.captchaVerified("captcha-token-1")
        assertTrue(model.current.canRegisterFree)
        model.registerFree()
        model.registerFree() // a second tap while the first is on its way
        assertTrue(model.current.isRegistering)
        testScheduler.advanceUntilIdle()
        val sent = app.signups().single()
        assertEquals("POST", sent.method)
        assertEquals("https://ecency.com/private-api/account-create", sent.url)
        assertEquals("""{"username":"spike-new","email":"new@example.com","referral":"spike-bob","captcha_token":"captcha-token-1"}""", sent.body)
        assertFalse("code" in sent.body!!.replace("captcha_token", ""), "no account token on a signup")
        assertTrue(model.current.isRegistered)
        assertFalse(model.current.isRegistering)
        assertEquals("", model.current.captchaToken)
        model.registerFree()
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.signups().size, "never twice")
    }

    @Test
    fun aRefusedSignupShowsRnsMessagePerCodeAndAsksForANewChallenge() = runTest {
        val app = app()
        var answer = HttpResult(400, """{"code":14,"message":"backend says email exists"}""")
        app.authBackend.rest = { r -> if (r.url.endsWith("/account-create")) answer else null }
        val model = atOptions(app)
        val key = model.current.captchaKey
        model.registerFree()
        testScheduler.advanceUntilIdle()
        assertEquals(RegisterAlert("Fail!", "Email address already registered"), model.current.alert)
        assertFalse(model.current.isRegistered)
        assertEquals("", model.current.captchaToken, "a token is single use")
        assertEquals(key + 1, model.current.captchaKey)
        assertFalse(model.current.canRegisterFree)
        assertEquals(1, app.signups().size, "a failed signup is not retried")

        // an unmapped code shows the backend's own sentence
        answer = HttpResult(400, """{"code":999,"message":"Something specific"}""")
        model.dismissAlert()
        model.captchaVerified("captcha-token-2")
        model.registerFree()
        testScheduler.advanceUntilIdle()
        assertEquals("Something specific", model.current.alert?.body)

        answer = HttpResult(500, """{"code":14,"message":"ignored"}""")
        model.captchaVerified("captcha-token-3")
        model.registerFree()
        testScheduler.advanceUntilIdle()
        assertEquals(RegisterCopy.ERROR_500, model.current.alert?.body)

        answer = HttpResult(403, "<html>blocked</html>")
        model.captchaVerified("captcha-token-4")
        model.registerFree()
        testScheduler.advanceUntilIdle()
        assertEquals("An error occurred", model.current.alert?.body)
        assertEquals(4, app.signups().size)
    }

    @Test
    fun aLostAnswerIsAFailureAndIsNotSentAgain() = runTest {
        val app = app()
        app.authBackend.rest = { r -> if (r.url.endsWith("/account-create")) throw IllegalStateException("connection reset") else null }
        val model = atOptions(app)
        model.registerFree()
        testScheduler.advanceUntilIdle()
        assertEquals(RegisterAlert("Fail!", "An error occurred"), model.current.alert)
        assertEquals(1, app.signups().size)
        assertFalse(model.current.isRegistering)
    }

    @Test
    fun aNameThatBecameInvalidIsStoppedBeforeTheBackend() = runTest {
        val app = app()
        app.authBackend.rest = { r -> if (r.url.endsWith("/account-create")) HttpResult(200, "") else null }
        val model = atOptions(app)
        model.setUsername("uid12345")
        model.captchaVerified("captcha-token-1")
        model.registerFree()
        testScheduler.advanceUntilIdle()
        assertEquals(RegisterAlert("Fail!", UsernameError.RESTRICTED.message), model.current.alert)
        assertTrue(app.signups().isEmpty())
    }

    @Test
    fun captchaTokensAreOnlyTakenWhileTheOptionsAreOpenAndExpire() = runTest {
        val app = app()
        val model = filled(app)
        model.captchaVerified("early-token")
        assertEquals("", model.current.captchaToken)
        model.continueToOptions()
        model.captchaVerified("has space")
        model.captchaVerified("")
        model.captchaVerified("x".repeat(5000))
        assertEquals("", model.current.captchaToken)
        model.captchaVerified("good-token")
        assertEquals("good-token", model.current.captchaToken)
        model.captchaExpired()
        assertFalse(model.current.canRegisterFree)
        val key = model.current.captchaKey
        model.captchaFailed()
        assertEquals(key + 1, model.current.captchaKey)
        model.captchaVerified("good-token")
        model.closeOptions()
        assertEquals("", model.current.captchaToken, "the next session starts with a new challenge")
    }

    // ---- the Turnstile page ----

    @Test
    fun theChallengePageOnlyLoadsItsTwoHostsAsTheTopDocument() {
        assertTrue(TurnstilePage.allowsLoad("https://ecency.com/embed/turnstile", true))
        assertTrue(TurnstilePage.allowsLoad("https://challenges.cloudflare.com/cdn-cgi/x", null))
        assertFalse(TurnstilePage.allowsLoad("https://evil.example/", true))
        assertFalse(TurnstilePage.allowsLoad("https://ecency.com.evil.example/", null))
        assertFalse(TurnstilePage.allowsLoad("http://ecency.com/embed/turnstile", true))
        assertFalse(TurnstilePage.allowsLoad("https://ecency.com@evil.example/", true).also { /* RN's prefix rule needs the slash after the host */ })
        // the challenge computes in sub-frames of several schemes
        assertTrue(TurnstilePage.allowsLoad("about:srcdoc", false))
        assertTrue(TurnstilePage.allowsLoad("blob:https://challenges.cloudflare.com/1", false))
    }

    @Test
    fun bridgeMessagesCountOnlyFromTheEmbedPageAndOnlyInRnsShapes() {
        assertTrue(TurnstilePage.acceptsMessagesFrom("https://ecency.com/embed/turnstile"))
        assertTrue(TurnstilePage.acceptsMessagesFrom("https://ecency.com/embed/turnstile?x=1"))
        assertFalse(TurnstilePage.acceptsMessagesFrom("https://ecency.com/@someone/post"))
        assertFalse(TurnstilePage.acceptsMessagesFrom("https://challenges.cloudflare.com/x"))
        assertFalse(TurnstilePage.acceptsMessagesFrom("https://ecency.com/embed/turnstile-evil"))
        assertFalse(TurnstilePage.acceptsMessagesFrom(null))

        assertEquals(TurnstilePage.Event.Verified("tok"), TurnstilePage.parse("""{"type":"verify","token":"tok"}"""))
        assertEquals(TurnstilePage.Event.Expired, TurnstilePage.parse("""{"type":"expire"}"""))
        assertEquals(TurnstilePage.Event.Failed, TurnstilePage.parse("""{"type":"error"}"""))
        assertNull(TurnstilePage.parse("""{"type":"verify"}"""))
        assertNull(TurnstilePage.parse("""{"type":"verify","token":""}"""))
        assertNull(TurnstilePage.parse("""{"type":"verify","token":123}"""))
        assertNull(TurnstilePage.parse("""{"type":"verify","token":"a b"}"""))
        assertNull(TurnstilePage.parse("""{"type":"navigate","url":"https://evil.example"}"""))
        assertNull(TurnstilePage.parse("not json"))
        assertNull(TurnstilePage.parse(null))
        assertNull(TurnstilePage.parse("[" .repeat(50)))
    }

    @Test
    fun theRegisterScreenSavesOnlyItsReferral() {
        val screen = AppScreen.Register("spike-bob")
        assertEquals(screen, NavState.decodeScreen(NavState.encodeScreen(screen)))
        assertEquals(AppScreen.Register("spike-bob"), AppScreen.of(RouteName.REGISTER, "spike-bob"))
        assertFalse(RouteName.REGISTER.requiresLogin)
    }
}
