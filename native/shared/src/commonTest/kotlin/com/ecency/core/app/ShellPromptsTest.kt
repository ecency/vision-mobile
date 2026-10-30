package com.ecency.core.app

import com.ecency.core.api.ecency.OnboardingApi
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.hive.KeyRole
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Delete account, the update prompt and announcements, all against fakes: no deletion request leaves the process. */
class ShellPromptsTest {
    private val alice = "spike-alice"
    private val bob = "spike-bob"

    private fun TestScope.app(signedIn: Boolean = true, welcomed: Boolean = true): TestApp {
        val app = appWithAccounts(this)
        if (signedIn) {
            val vault = KeyVault(app.store)
            listOf(bob, alice).forEach { vault.save(VaultAccount(it, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(it, KeyRole.POSTING), accessToken = "access-$it")) }
            vault.setCurrentUsername(alice)
        }
        if (welcomed) app.env.welcome.accept(consent = true)
        app.env.session.start()
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        return app
    }

    private fun TestApp.deletes(): List<AuthHttpRequest> = authBackend.sent.filter { it.url.endsWith("/private-api/request-delete") }

    // ---- delete account ----

    @Test
    fun deleteAsksOnceThenSendsRnsRequestAndSignsThatAccountOut() = runTest {
        val app = app()
        app.authBackend.rest = { r -> if (r.url.endsWith("/request-delete")) HttpResult(200, "{}") else null }
        app.env.localDrafts.save(alice, LocalDrafts.NEW_POST, LocalDraft(title = "must not stay", updated = 1))
        val model = app.env.deleteAccount
        assertEquals("Confirm Delete", DeleteAccountCopy.CONFIRM_TITLE)
        assertEquals("Are you sure you want to delete account? It will erase all of your data", DeleteAccountCopy.CONFIRM_BODY)
        model.ask()
        assertEquals(alice, model.current.asking)
        assertTrue(app.deletes().isEmpty(), "nothing is sent before Delete")
        model.confirm()
        model.confirm() // a second tap
        testScheduler.advanceUntilIdle()
        val sent = app.deletes().single()
        assertEquals("POST", sent.method)
        assertEquals("https://ecency.com/private-api/request-delete", sent.url)
        assertEquals(Json.parseToJsonElement("""{"username":"spike-alice","data":"","code":"access-spike-alice"}"""), Json.parseToJsonElement(sent.body!!))
        assertEquals("Your request for deletion is sent", app.env.ui.current.toast?.text)
        // RN dispatch(logout()): the account is gone from the device, the next one is current
        assertNull(KeyVault(app.store).account(alice))
        assertEquals(bob, app.env.session.current.username)
        assertNull(app.env.localDrafts.load(alice, LocalDrafts.NEW_POST), "the one teardown ran: its local drafts are gone")
        assertNull(model.current.asking)
        assertNull(model.current.error, "the second tap started nothing: no second request ran into the signed-out account")
    }

    @Test
    fun aSecondTapWhileTheRequestIsOnItsWaySendsNothing() = runTest {
        val app = app()
        val held = CompletableDeferred<Unit>()
        var reached = 0
        app.authBackend.gate = { r -> if (r.url.endsWith("/request-delete")) { reached++; held.await() } }
        app.authBackend.rest = { r -> if (r.url.endsWith("/request-delete")) HttpResult(200, "{}") else null }
        val model = app.env.deleteAccount
        model.ask()
        model.confirm()
        testScheduler.runCurrent()
        model.confirm()
        model.cancel() // and the question cannot be closed under a running request
        testScheduler.runCurrent()
        assertEquals(1, reached, "one request, however often Delete is tapped")
        assertTrue(model.current.isSending)
        held.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.deletes().size)
        assertNull(KeyVault(app.store).account(alice))
    }

    @Test
    fun cancelSendsNothingAndKeepsTheAccount() = runTest {
        val app = app()
        val model = app.env.deleteAccount
        model.ask()
        model.cancel()
        model.confirm()
        testScheduler.advanceUntilIdle()
        assertTrue(app.deletes().isEmpty())
        assertEquals(alice, app.env.session.current.username)
    }

    @Test
    fun aFailedRequestIsReportedNotRetriedAndTheAccountStays() = runTest {
        val app = app()
        app.authBackend.rest = { r -> if (r.url.endsWith("/request-delete")) HttpResult(500, "") else null }
        val model = app.env.deleteAccount
        model.ask()
        model.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.deletes().size)
        assertEquals(DeleteAccountCopy.NOT_SENT, model.current.error)
        assertNull(model.current.asking)
        assertEquals(alice, app.env.session.current.username)
        assertNotNull(KeyVault(app.store).account(alice))
    }

    @Test
    fun theConfirmationClosesOnAnAccountChangeAndNeverDeletesTheNextAccount() = runTest {
        val app = app()
        app.authBackend.rest = { r -> if (r.url.endsWith("/request-delete")) HttpResult(200, "{}") else null }
        val model = app.env.deleteAccount
        model.ask()
        app.env.session.switchTo(bob)
        testScheduler.advanceUntilIdle()
        assertNull(model.current.asking)
        model.confirm()
        testScheduler.advanceUntilIdle()
        assertTrue(app.deletes().isEmpty())
        assertNotNull(KeyVault(app.store).account(bob))
    }

    @Test
    fun aConfirmThatRacesAnAccountChangeIsRefused() = runTest {
        val app = app()
        app.authBackend.rest = { r -> if (r.url.endsWith("/request-delete")) HttpResult(200, "{}") else null }
        val model = app.env.deleteAccount
        model.ask()
        // the session already says bob; the model's collector has not run yet
        app.env.session.switchTo(bob)
        model.confirm()
        testScheduler.advanceUntilIdle()
        assertTrue(app.deletes().isEmpty())
        assertNotNull(KeyVault(app.store).account(alice))
        assertNotNull(KeyVault(app.store).account(bob))
    }

    @Test
    fun aRequestOnItsWayStillSignsOutItsOwnAccountAfterASwitch() = runTest {
        val app = app()
        val held = CompletableDeferred<Unit>()
        app.authBackend.gate = { r -> if (r.url.endsWith("/request-delete")) held.await() }
        app.authBackend.rest = { r -> if (r.url.endsWith("/request-delete")) HttpResult(200, "{}") else null }
        val model = app.env.deleteAccount
        model.ask()
        model.confirm()
        testScheduler.runCurrent()
        app.env.session.switchTo(bob)
        testScheduler.runCurrent()
        assertTrue(model.current.isSending)
        held.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertTrue("\"username\":\"spike-alice\"" in app.deletes().single().body!!)
        assertNull(KeyVault(app.store).account(alice), "alice asked, alice left")
        assertNotNull(KeyVault(app.store).account(bob))
        assertEquals(bob, app.env.session.current.username)
    }

    @Test
    fun signedOutThereIsNothingToDelete() = runTest {
        val app = app(signedIn = false)
        app.env.deleteAccount.ask()
        assertNull(app.env.deleteAccount.current.asking)
    }

    // ---- update prompt ----

    private fun TestApp.release(tag: String) {
        authBackend.rest = { r -> if (r.url == OnboardingApi.RELEASES_URL) HttpResult(200, """{"tag_name":"$tag","name":"x"}""") else null }
    }

    @Test
    fun aNewerReleaseAsksWithRnsCopy() = runTest {
        val app = app()
        app.env.appVersion = "4.0.0"
        app.release("4.1.0")
        app.env.updates.check()
        testScheduler.advanceUntilIdle()
        val prompt = app.env.updates.current.prompt!!
        assertEquals("Update available v4.1.0", prompt.title)
        assertEquals("Using latest version of Ecency gives you the best experience", prompt.body)
        assertEquals("Remind Later", prompt.later)
        assertEquals("Update Now", prompt.update)
        assertEquals("https://api.github.com/repos/ecency/vision-mobile/releases/latest", app.authBackend.sent.last().url)
    }

    @Test
    fun onlyPlainVersionTagsOfThisAppsPrefixCount() = runTest {
        val app = app()
        app.env.appVersion = "4.0.0"
        // tags that are not plain X.Y.Z never prompt, however high they read
        for (tag in listOf("v9.0.0", "9.0", "9", "9.0.0-beta", "9.0.0.1", "release-9.0.0", "9.0.0 ", "9.0.0<b>", "native-9.0.0")) {
            app.release(tag)
            app.env.updates.check()
            testScheduler.advanceUntilIdle()
            assertNull(app.env.updates.current.prompt, tag)
        }
        // a build that sets its own prefix sees only its own tags, and other apps' plain tags no longer count
        app.env.updateSource = UpdateSource.of(null, "native-")
        app.release("9.0.0")
        app.env.updates.check()
        testScheduler.advanceUntilIdle()
        assertNull(app.env.updates.current.prompt, "an RN release does not prompt the native app")
        app.release("native-4.1.0")
        app.env.updates.check()
        testScheduler.advanceUntilIdle()
        assertEquals("Update available v4.1.0", app.env.updates.current.prompt?.title)
    }

    @Test
    fun theUpdateSourceIsABuildSettingWithRnsDefault() = runTest {
        assertEquals(UpdateSource(OnboardingApi.RELEASES_URL, ""), UpdateSource.of(null, null))
        assertEquals(UpdateSource(OnboardingApi.RELEASES_URL, ""), UpdateSource.of("  ", ""))
        assertEquals("https://api.github.com/repos/ecency/other/releases/latest", UpdateSource.of("https://api.github.com/repos/ecency/other/releases/latest", null).releasesUrl)
        // only GitHub's API over https; anything else falls back to the default
        for (bad in listOf("http://api.github.com/repos/a/b/releases/latest", "https://evil.example/repos/a/b", "https://api.github.com.evil.example/repos/a/b", "https://user@api.github.com/repos/a/b")) {
            assertEquals(OnboardingApi.RELEASES_URL, UpdateSource.of(bad, null).releasesUrl, bad)
        }
        assertEquals("", UpdateSource.of(null, "bad prefix<").tagPrefix)
        val app = app()
        app.env.appVersion = "4.0.0"
        app.env.updateSource = UpdateSource.of("https://api.github.com/repos/ecency/other/releases/latest", null)
        app.authBackend.rest = { r -> if (r.url == "https://api.github.com/repos/ecency/other/releases/latest") HttpResult(200, """{"tag_name":"4.2.0"}""") else null }
        app.env.updates.check()
        testScheduler.advanceUntilIdle()
        assertEquals("Update available v4.2.0", app.env.updates.current.prompt?.title)
    }

    @Test
    fun theSameOrAnOlderReleaseDoesNotAsk() = runTest {
        val app = app()
        app.env.appVersion = "4.0.0"
        for (tag in listOf("4.0.0", "3.5.11", "", "not-a-version")) {
            app.release(tag)
            app.env.updates.check()
            testScheduler.advanceUntilIdle()
            assertNull(app.env.updates.current.prompt, tag)
        }
    }

    @Test
    fun remindLaterWaitsTwoDaysAndUpdateNowAsksAgainNextTime() = runTest {
        val app = app()
        app.env.appVersion = "4.0.0"
        app.release("4.1.0")
        val updates = app.env.updates
        updates.check()
        testScheduler.advanceUntilIdle()
        updates.later()
        assertNull(updates.current.prompt)
        val calls = app.authBackend.sent.size
        app.now += UpdateModel.RECHECK_MILLIS - 1
        updates.check()
        testScheduler.advanceUntilIdle()
        assertNull(updates.current.prompt)
        assertEquals(calls, app.authBackend.sent.size, "not even asked within 48 hours")
        app.now += 1
        updates.check()
        testScheduler.advanceUntilIdle()
        assertNotNull(updates.current.prompt)
        // Update Now stores nothing: the next start asks again until the app was updated
        updates.dismiss()
        updates.check()
        testScheduler.advanceUntilIdle()
        assertNotNull(updates.current.prompt)
    }

    @Test
    fun aFailedCheckIsSilentAndAHostileTagShowsNothing() = runTest {
        val app = app()
        app.env.appVersion = "4.0.0"
        app.authBackend.rest = { r -> if (r.url == OnboardingApi.RELEASES_URL) HttpResult(403, "rate limited") else null }
        app.env.updates.check()
        testScheduler.advanceUntilIdle()
        assertNull(app.env.updates.current.prompt)
        // a tag with anything but a plain version is not this app's release: nothing of it is shown
        app.release("9.0.0<b>pwn</b> tap here")
        app.env.updates.check()
        testScheduler.advanceUntilIdle()
        assertNull(app.env.updates.current.prompt)
    }

    @Test
    fun storeLinksNameTheNativeAppId() {
        assertEquals("market://details?id=app.esteem.mobile.android", UpdateLinks.android("com.android.vending"))
        assertEquals("https://github.com/ecency/vision-mobile/releases", UpdateLinks.android(null))
        assertEquals("https://github.com/ecency/vision-mobile/releases", UpdateLinks.android("com.other.store"))
        assertEquals("https://play.google.com/store/apps/details?id=app.esteem.mobile.android", UpdateLinks.PLAY_WEB)
        assertEquals("https://itunes.apple.com/lookup?bundleId=app.esteem.mobile.ios", UpdateLinks.APP_STORE_LOOKUP)
        assertTrue(UpdateLinks.isAppStorePage("https://apps.apple.com/us/app/ecency/id123"))
        assertFalse(UpdateLinks.isAppStorePage("https://apps.apple.com.evil.example/x"))
        assertFalse(UpdateLinks.isAppStorePage("itms-apps://evil"))
        assertFalse(UpdateLinks.isAppStorePage(null))
    }

    @Test
    fun theAppStorePageComesFromApplesLookup() = runTest {
        val app = app()
        app.authBackend.rest = { r ->
            if (r.url == UpdateLinks.APP_STORE_LOOKUP) HttpResult(200, """{"resultCount":1,"results":[{"trackViewUrl":"https://apps.apple.com/us/app/ecency/id42?uo=4"}]}""") else null
        }
        assertEquals("https://apps.apple.com/us/app/ecency/id42", app.env.onboardingApi.appStorePage(UpdateLinks.APP_STORE_LOOKUP))
        app.authBackend.rest = { r -> if (r.url == UpdateLinks.APP_STORE_LOOKUP) HttpResult(200, """{"resultCount":0,"results":[]}""") else null }
        assertNull(app.env.onboardingApi.appStorePage(UpdateLinks.APP_STORE_LOOKUP))
    }

    // ---- announcements ----

    private fun TestApp.announce(json: String) {
        authBackend.rest = { r -> if (r.url.endsWith("/private-api/announcements")) HttpResult(200, json) else null }
    }

    private val sample =
        """[{"id":"a1","title":"Hello","description":"Line one\nLine two","button_text":"Read more","button_link":"/@spike-bob/a-post","auth":false}]"""

    private fun TestScope.shown(app: TestApp): AnnouncementPrompt? {
        app.env.announcements.start()
        testScheduler.advanceUntilIdle()
        return app.env.announcements.current.prompt
    }

    @Test
    fun theFirstAnnouncementShowsAfterThreeSecondsFromAPublicGet() = runTest {
        val app = app()
        app.announce(sample)
        app.env.announcements.start()
        testScheduler.advanceTimeBy(2_999)
        assertNull(app.env.announcements.current.prompt)
        testScheduler.advanceUntilIdle()
        val prompt = app.env.announcements.current.prompt!!
        assertEquals("Hello", prompt.title)
        assertEquals("Line one\nLine two", prompt.body)
        assertEquals("Later", prompt.later)
        assertEquals("Read more", prompt.action)
        assertEquals(alice, prompt.account)
        val request = app.authBackend.sent.single { it.url.endsWith("/announcements") }
        assertEquals("GET", request.method)
        assertNull(request.body, "no token on this call")
    }

    @Test
    fun aNewVersionLaunchShowsNoneUntilTheWelcomeWasAccepted() = runTest {
        val app = app(welcomed = false)
        app.announce(sample)
        assertNull(shown(app))
        assertTrue(app.env.acceptWelcome(consent = true))
        testScheduler.advanceUntilIdle()
        assertNotNull(app.env.announcements.current.prompt)
    }

    @Test
    fun proposalPromptsAreSkippedAndSignedInOnlyOnesNeedAnAccount() = runTest {
        val guest = app(signedIn = false)
        guest.announce("""[{"id":"p","title":"Vote","description":"d","button_text":"Vote","proposal_ids":[1]},{"id":"m","title":"Members","description":"d","button_text":"Go","auth":true}]""")
        assertNull(shown(guest), "the first non-proposal one needs an account: nothing shows, as RN")

        val user = app()
        user.announce("""[{"id":"p","title":"Vote","description":"d","button_text":"Vote","proposal_ids":[1]},{"id":"m","title":"Members","description":"d","button_text":"Go","auth":true}]""")
        assertEquals("Members", shown(user)?.title)
    }

    @Test
    fun laterAsksAgainAfterTwoDaysAndTheButtonNeverAgain() = runTest {
        val app = app()
        app.announce(sample)
        val model = app.env.announcements
        assertNotNull(shown(app))
        model.later()
        model.check()
        testScheduler.advanceUntilIdle()
        assertNull(model.current.prompt)
        app.now += AnnouncementsModel.PROMPT_AGAIN_MILLIS - 1
        model.check()
        testScheduler.advanceUntilIdle()
        assertNull(model.current.prompt)
        app.now += 1
        model.check()
        testScheduler.advanceUntilIdle()
        assertNotNull(model.current.prompt)

        val target = model.act()
        val inApp = assertIs<AnnouncementTarget.InApp>(target)
        assertEquals(AppScreen.Post("spike-bob", "a-post"), inApp.entry.screens.single())
        app.now += 10 * AnnouncementsModel.PROMPT_AGAIN_MILLIS
        model.check()
        testScheduler.advanceUntilIdle()
        assertNull(model.current.prompt, "processed: never again for this account")
        // RN's reducer: seen again later, it stays processed
        assertTrue("\"a1_spike-alice\":{\"processed\":true" in app.env.prefs.getString(AnnouncementsModel.META)!!)
    }

    @Test
    fun theMemoryIsPerAccountAndAPromptNeverCrossesAccounts() = runTest {
        val app = app()
        app.announce(sample)
        val model = app.env.announcements
        assertNotNull(shown(app))
        model.act()
        app.env.session.switchTo(bob)
        testScheduler.advanceUntilIdle()
        assertEquals(bob, model.current.prompt?.account, "bob has not seen it")
        // alice's prompt would be closed by the switch without a mark
        app.env.session.switchTo(alice)
        testScheduler.advanceTimeBy(100)
        assertNull(model.current.prompt)
        assertFalse("a1_spike-bob" in app.env.prefs.getString(AnnouncementsModel.META)!!, "closing on a switch marks nothing")
    }

    @Test
    fun aPromptChosenForOneAccountIsNotShownToTheNext() = runTest {
        val app = app()
        app.announce("""[{"id":"m","title":"Members","description":"d","button_text":"Go","auth":true}]""")
        app.env.announcements.start()
        testScheduler.advanceTimeBy(1_000)
        // signed out during the three seconds
        app.env.session.logoutAll()
        testScheduler.advanceUntilIdle()
        assertNull(app.env.announcements.current.prompt)
    }

    @Test
    fun linksAreMadeHttpsLikeRnAndOnlyOpenContentOrAWebPage() {
        fun target(link: String) = AnnouncementsModel.target(link)
        assertEquals(AppScreen.Post("spike-bob", "a-post"), assertIs<AnnouncementTarget.InApp>(target("/@spike-bob/a-post")).entry.screens.single())
        assertEquals(AppScreen.Profile("spike-bob"), assertIs<AnnouncementTarget.InApp>(target("https://ecency.com/@spike-bob")).entry.screens.single())
        assertEquals("https://example.org/page", assertIs<AnnouncementTarget.Web>(target("https://example.org/page")).url)
        // an auth or signup page is only ever a web page here: an announcement cannot start those flows
        assertIs<AnnouncementTarget.Web>(target("https://ecency.com/auth?code=abc"))
        assertIs<AnnouncementTarget.Web>(target("/signup?referral=someone"))
        assertNull(target(""))
        // RN glues any other text to "https://ecency.com": without a leading slash that names another host
        // (".evil.example/x" gives ecency.com.evil.example, "evil.example/x" gives ecency.comevil.example).
        // Only a path with one leading slash is a relative link; everything else is no link at all.
        for (hostile in listOf(
            ".evil.example/x", "evil.example/x", "@evil.example/x", ":443@evil.example/x", "-evil.example", "?x=1", "#frag",
            "//evil.example/x", "/\\evil.example/x", "/", "hive://sign/op/W10.", "ecency://transfer?to=x", "javascript:alert(1)",
            "http://example.org", "HTTPS://evil.example/x", " https://evil.example", "https:evil.example", "\\evil.example",
        )) {
            assertNull(target(hostile), "\"$hostile\" must not open anything")
        }
        // whatever does open is either the absolute https URL as given or a path on ecency.com itself
        for (link in listOf("/@spike-bob/a-post", "/signup?referral=x", "/faq", "/a.evil.example/x")) {
            when (val t = target(link)) {
                is AnnouncementTarget.Web -> assertTrue(t.url.startsWith("https://ecency.com/"), "$link opened ${t.url}")
                is AnnouncementTarget.InApp, null -> {}
            }
        }
        assertNull(target("https://user@evil.example/"), "userinfo is refused")
    }

    @Test
    fun hostileRowsAreCleanedCappedOrDropped() {
        fun parse(json: String) = OnboardingApi.announcement(Json.parseToJsonElement(json))
        assertNull(parse("""{"title":"no id"}"""))
        assertNull(parse("""{"id":"x"}"""))
        assertNull(parse("""[1,2]"""))
        val a = parse("""{"id":"x","title":"A\u0000B‮reversed","description":"${"d".repeat(5000)}","button_text":"${"b".repeat(500)}","button_link":"https://example.org/\nx","ops":"hive://sign/op/W10.","auth":"yes"}""")!!
        assertEquals("ABreversed", a.title)
        assertEquals(OnboardingApi.MAX_DESCRIPTION, a.description.length)
        assertEquals(OnboardingApi.MAX_BUTTON, a.buttonText.length)
        assertEquals("", a.buttonLink, "a link with a line break is no link")
        assertTrue(a.auth)
        assertFalse(a.toString().contains("hive://sign"), "the server's signing blob is never read")
    }

    @Test
    fun aFailedFetchShowsNothingAndTheButtonWithoutTextStillCloses() = runTest {
        val app = app()
        app.authBackend.rest = { r -> if (r.url.endsWith("/announcements")) HttpResult(500, "") else null }
        assertNull(shown(app))
        app.announce("""[{"id":"plain","title":"Just so you know","description":"","button_text":"","button_link":""}]""")
        app.now += AnnouncementsModel.STALE_MILLIS
        app.env.announcements.check()
        testScheduler.advanceUntilIdle()
        val prompt = app.env.announcements.current.prompt!!
        assertEquals("OK", prompt.action)
        assertNull(prompt.target)
        assertNull(app.env.announcements.act())
        assertNull(app.env.announcements.current.prompt)
    }
}
