package com.ecency.core.app

import com.ecency.core.api.ecency.OnboardingApi
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.hive.KeyRole
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one ordering rule of the prompts that open by themselves: the account notice, the update prompt, the
 * announcement, the digest offer and the rating prompt. Driven through the real models against fakes.
 */
class RootPromptsTest {
    private val alice = "spike-alice"

    private fun TestScope.app(pin: Boolean = false): TestApp {
        val app = appWithAccounts(this)
        val vault = KeyVault(app.store)
        vault.save(VaultAccount(alice, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(alice, KeyRole.POSTING), accessToken = "access-$alice"))
        vault.setCurrentUsername(alice)
        if (pin) PinLock(vault, 16).setPin("1234")
        app.env.appVersion = "4.0.0"
        app.env.welcome.accept(consent = true)
        app.authBackend.rest = { r ->
            when {
                r.url == OnboardingApi.RELEASES_URL -> HttpResult(200, """{"tag_name":"4.1.0"}""")
                r.url.endsWith("/private-api/announcements") -> HttpResult(200, """[{"id":"a1","title":"Hello","description":"Text","button_text":"Read","button_link":"/@spike-bob/a-post","auth":false}]""")
                "/api/newsletter/" in r.url -> HttpResult(200, """{"subscriptions":[]}""")
                else -> null
            }
        }
        app.env.session.start()
        app.env.appLock.start()
        // the model exists from the start, as in the apps (their root observes it)
        app.env.rootPrompts
        testScheduler.advanceUntilIdle()
        return app
    }

    private fun TestScope.unlock(app: TestApp) {
        "1234".forEach { app.env.appLock.enterDigit(it.digitToInt()) }
        testScheduler.advanceUntilIdle()
    }

    private fun TestScope.shown(app: TestApp): RootPrompt? {
        testScheduler.advanceUntilIdle()
        return app.env.rootPrompts.current.prompt
    }

    /** Everything at once: a notice, a newer release, an announcement and the digest offer. */
    private fun TestScope.raiseAll(app: TestApp) {
        app.env.digest.show("own", alice, firstPublish = true)
        app.env.announcements.start()
        app.env.updates.check()
        app.env.accountNotices.noSigningKey(listOf(alice))
        testScheduler.advanceUntilIdle()
        assertTrue(app.env.digest.current.open)
        assertTrue(app.env.announcements.current.prompt != null)
        assertTrue(app.env.updates.current.prompt != null)
        assertTrue(app.env.accountNotices.current.notice != null)
    }

    @Test
    fun theRuleItself() {
        val all = RootPrompt.entries.toSet()
        assertEquals(listOf(RootPrompt.ACCOUNT_NOTICE, RootPrompt.UPDATE, RootPrompt.ANNOUNCEMENT, RootPrompt.DIGEST), RootPrompt.entries.toList(), "the order of turns")
        assertNull(RootPromptRule.pick(null, blocked = true, pending = all))
        assertNull(RootPromptRule.pick(RootPrompt.UPDATE, blocked = true, pending = all), "a lock takes the prompt on screen down too")
        assertNull(RootPromptRule.pick(null, blocked = false, pending = emptySet()))
        assertEquals(RootPrompt.ACCOUNT_NOTICE, RootPromptRule.pick(null, blocked = false, pending = all))
        assertEquals(RootPrompt.ANNOUNCEMENT, RootPromptRule.pick(null, blocked = false, pending = setOf(RootPrompt.DIGEST, RootPrompt.ANNOUNCEMENT)))
        assertEquals(RootPrompt.DIGEST, RootPromptRule.pick(RootPrompt.DIGEST, blocked = false, pending = all), "the one on screen is never replaced")
        assertEquals(RootPrompt.UPDATE, RootPromptRule.pick(RootPrompt.DIGEST, blocked = false, pending = setOf(RootPrompt.UPDATE)), "an answered one gives way")
        assertTrue(RootPromptRule.mayAskForRating(blocked = false, foreground = true, pending = emptySet()))
        assertFalse(RootPromptRule.mayAskForRating(blocked = true, foreground = true, pending = emptySet()))
        assertFalse(RootPromptRule.mayAskForRating(blocked = false, foreground = false, pending = emptySet()))
        assertFalse(RootPromptRule.mayAskForRating(blocked = false, foreground = true, pending = setOf(RootPrompt.DIGEST)))
    }

    @Test
    fun oneAtATimeInOrderEachAfterTheOneBeforeWasAnswered() = runTest {
        val app = app()
        raiseAll(app)
        assertEquals(RootPrompt.ACCOUNT_NOTICE, shown(app))
        app.env.accountNotices.dismiss()
        assertEquals(RootPrompt.UPDATE, shown(app))
        app.env.updates.later()
        assertEquals(RootPrompt.ANNOUNCEMENT, shown(app))
        app.env.announcements.later()
        assertEquals(RootPrompt.DIGEST, shown(app))
        app.env.digest.dismiss()
        assertNull(shown(app))
    }

    @Test
    fun thePromptOnScreenStaysWhenAnotherArrives() = runTest {
        val app = app()
        app.env.digest.show("own", alice, firstPublish = true)
        assertEquals(RootPrompt.DIGEST, shown(app))
        // an update and a notice arrive while the user is in the digest sheet: they wait
        app.env.updates.check()
        app.env.accountNotices.noSigningKey(listOf(alice))
        assertEquals(RootPrompt.DIGEST, shown(app))
        app.env.digest.dismiss()
        assertEquals(RootPrompt.ACCOUNT_NOTICE, shown(app))
        app.env.accountNotices.dismiss()
        assertEquals(RootPrompt.UPDATE, shown(app))
    }

    @Test
    fun nothingShowsWhileLockedAndTheTurnsStartAfterTheUnlock() = runTest {
        val app = app(pin = true)
        assertTrue(app.env.appLock.current.hidesContent)
        raiseAll(app)
        assertNull(shown(app), "nothing over the lock screen")
        assertFalse(app.env.rootPrompts.mayAskForRating())
        unlock(app)
        assertEquals(RootPrompt.ACCOUNT_NOTICE, shown(app))
        // locking again takes the prompt down; it is back after the unlock
        app.env.appLock.onBackground()
        app.monotonic += 10 * 60_000
        app.env.appLock.onForeground()
        testScheduler.advanceUntilIdle()
        if (app.env.appLock.current.hidesContent) {
            assertNull(shown(app))
            unlock(app)
        }
        assertEquals(RootPrompt.ACCOUNT_NOTICE, shown(app))
    }

    @Test
    fun nothingOpensOverAPinPad() = runTest {
        // a settings flow (turn the lock on)
        val off = app()
        raiseAll(off)
        assertEquals(RootPrompt.ACCOUNT_NOTICE, shown(off))
        off.env.appLock.startEnable()
        testScheduler.advanceUntilIdle()
        assertEquals(PinPurpose.ENABLE, off.env.appLock.current.prompt?.purpose)
        assertFalse(off.env.appLock.current.hidesContent, "not the lock screen: the pad alone blocks")
        assertNull(shown(off), "nothing over the settings PIN pad")
        assertFalse(off.env.rootPrompts.mayAskForRating())
        off.env.appLock.cancel()
        assertEquals(RootPrompt.ACCOUNT_NOTICE, shown(off))

        // the PIN confirmation before something sensitive
        val on = app(pin = true)
        unlock(on)
        raiseAll(on)
        assertEquals(RootPrompt.ACCOUNT_NOTICE, shown(on))
        val verified = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val job = launch(on.main) { verified.complete(on.env.appLock.verifyPin(whenOff = false)) }
        testScheduler.advanceUntilIdle()
        assertEquals(PinPurpose.VERIFY, on.env.appLock.current.prompt?.purpose)
        assertNull(shown(on), "nothing over the PIN confirmation")
        unlock(on)
        assertTrue(verified.await())
        assertEquals(RootPrompt.ACCOUNT_NOTICE, shown(on))
        job.cancel()
    }

    @Test
    fun nothingOpensOverTheKeysScreen() = runTest {
        val app = app()
        raiseAll(app)
        assertEquals(RootPrompt.ACCOUNT_NOTICE, shown(app))
        val keys = app.env.backupKeysModel()
        keys.start()
        assertNull(shown(app), "nothing over the keys screen")
        assertFalse(app.env.appLock.current.hidesContent)
        assertNull(app.env.appLock.current.prompt, "no PIN pad either: the screen alone blocks")
        assertFalse(app.env.rootPrompts.mayAskForRating())
        // a second start changes nothing, and the screen counts once
        keys.start()
        keys.close()
        assertEquals(RootPrompt.ACCOUNT_NOTICE, shown(app))
        // a keys screen that shut by itself (the app left the foreground) no longer blocks either
        val second = app.env.backupKeysModel()
        second.start()
        assertNull(shown(app))
        app.env.appLock.onBackground()
        app.env.appLock.onForeground()
        assertEquals(RootPrompt.ACCOUNT_NOTICE, shown(app))
        second.close()
        assertEquals(RootPrompt.ACCOUNT_NOTICE, shown(app))
    }

    @Test
    fun nothingOpensUnderTheSignInAgainPrompt() = runTest {
        val app = app()
        raiseAll(app)
        assertEquals(RootPrompt.ACCOUNT_NOTICE, shown(app))
        app.store.keyLost = true
        runCatching { app.env.vault.vault.account(alice) }
        assertNull(shown(app))
        assertTrue(app.env.storageReset.current.needed)
    }

    @Test
    fun nothingShowsBeforeTheTermsAreAccepted() = runTest {
        val app = app()
        app.env.setWelcomeDone(false)
        raiseAll(app)
        assertNull(shown(app), "nothing over the welcome screen")
        assertFalse(app.env.rootPrompts.mayAskForRating())
        app.env.setWelcomeDone(true)
        assertEquals(RootPrompt.ACCOUNT_NOTICE, shown(app))
    }

    @Test
    fun theRatingPromptWaitsForAFreeRootAndStaysUnspentUntilThen() = runTest {
        val app = app()
        var asked = 0
        app.env.requestReviewAsync = { asked++; true }
        repeat(4) { app.env.rating.recordSession() }
        app.now += 3 * AppRating.DAY_MILLIS
        assertTrue(app.env.rating.shouldRequest())
        app.env.updates.check()
        assertEquals(RootPrompt.UPDATE, shown(app))
        app.env.maybeRequestReview()
        testScheduler.advanceUntilIdle()
        assertEquals(0, asked, "not over the update prompt")
        assertTrue(app.env.rating.shouldRequest(), "the one request is still there")
        app.env.updates.later()
        assertNull(shown(app))
        app.env.isForeground = false
        app.env.maybeRequestReview()
        testScheduler.advanceUntilIdle()
        assertEquals(0, asked, "not in the background")
        app.env.isForeground = true
        app.env.maybeRequestReview()
        testScheduler.advanceUntilIdle()
        assertEquals(1, asked)
        assertFalse(app.env.rating.shouldRequest())
    }
}
