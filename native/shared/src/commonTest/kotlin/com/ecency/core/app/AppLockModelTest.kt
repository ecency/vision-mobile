package com.ecency.core.app

import com.ecency.core.api.model.PostKey
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.legacy.LegacyFixtures
import com.ecency.core.legacy.MapLegacyStorage
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.BiometricBinder
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinCheck
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

class AppLockModelTest {
    private fun TestScope.app(pin: String? = "1234"): TestApp {
        val app = TestApp(this) { HttpResult(500, "") }
        app.signIn()
        if (pin != null) PinLock(KeyVault(app.store), 16).setPin(pin)
        return app
    }

    private fun TestScope.started(app: TestApp): AppLockModel {
        app.env.session.start()
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        return app.env.appLock
    }

    private fun TestScope.type(model: AppLockModel, pin: String) {
        pin.forEach { model.enterDigit(it.digitToInt()) }
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun hidesContentUntilLoadedAndLocksAtStart() = runTest {
        val app = app()
        assertTrue(app.env.appLock.current.hidesContent)
        val lock = started(app)
        assertTrue(lock.current.isLocked)
        assertTrue(lock.current.hidesContent)
        assertEquals(TestApp.USER, lock.current.username)
        assertEquals("Enter PIN to unlock", lock.current.prompt!!.message)
        assertFalse(lock.current.prompt!!.canCancel)
        lock.cancel()
        assertTrue(lock.current.isLocked)
        type(lock, "1234")
        assertFalse(lock.current.isLocked)
        assertFalse(lock.current.hidesContent)
    }

    @Test
    fun noLockWhenOffOrSignedOut() = runTest {
        val off = started(app(pin = null))
        assertFalse(off.current.hidesContent)
        val app = TestApp(this) { HttpResult(500, "") }
        PinLock(KeyVault(app.store), 16).setPin("1234")
        assertFalse(started(app).current.isLocked)
    }

    @Test
    fun wrongTwiceWarnsAndTheThirdWipesAndSignsOut() = runTest {
        val app = app()
        val lock = started(app)
        val events = mutableListOf<AppEvent>()
        val collector = launch { app.env.events.collect { events += it } }
        app.env.localVotes.set(PostKey("a", "b"), LocalVote(10000, false))

        type(lock, "0000")
        val first = lock.current.prompt!!
        assertEquals("Enter PIN to unlock, 1 failed attempt", first.message)
        assertEquals(AppLockModel.INVALID_PIN, first.error)
        assertEquals(0, first.digits)
        assertFalse(first.isLastAttempt)

        type(lock, "1111")
        val second = lock.current.prompt!!
        assertEquals("Enter PIN to unlock, 2 failed attempts\nUser data will be wiped on next failed attempt", second.message)
        assertTrue(second.isLastAttempt)
        assertEquals(1, KeyVault(app.store).accounts().size)

        type(lock, "2222")
        assertNull(lock.current.prompt)
        assertFalse(lock.current.enabled)
        assertEquals(AppLockModel.WIPED_NOTICE, lock.current.notice)
        assertFalse(app.env.session.current.isLoggedIn)
        assertTrue(KeyVault(app.store).accounts().isEmpty())
        assertNull(app.env.localVotes.get(PostKey("a", "b")))
        assertEquals(listOf(AppEvent.DataWiped, AppEvent.SessionChanged(null)), events)
        collector.cancel()
    }

    @Test
    fun theCountShowsAgainAfterARestart() = runTest {
        val app = app()
        type(started(app), "0000")
        type(app.env.appLock, "0000")
        // new process: a new environment over the same store
        val again = TestApp(this) { HttpResult(500, "") }
        app.store.entries.forEach { (k, v) -> again.store.entries[k] = v }
        val lock = started(again)
        assertEquals(2, lock.current.prompt!!.failedAttempts)
        assertTrue(lock.current.prompt!!.message.endsWith(AppLockModel.RESET_WARNING))
        type(lock, "0000")
        assertTrue(KeyVault(again.store).accounts().isEmpty())
    }

    @Test
    fun locksAgainOnlyAfterTheTimeoutAway() = runTest {
        val app = app()
        val lock = started(app)
        type(lock, "1234")
        lock.onBackground()
        app.monotonic += 59_999
        lock.onForeground()
        testScheduler.advanceUntilIdle()
        assertFalse(lock.current.isLocked)
        lock.onBackground()
        app.monotonic += 60_000
        lock.onForeground()
        testScheduler.advanceUntilIdle()
        assertTrue(lock.current.isLocked)
        // a clock that went backwards locks too
        type(lock, "1234")
        lock.onBackground()
        app.monotonic -= 5
        lock.onForeground()
        testScheduler.advanceUntilIdle()
        assertTrue(lock.current.isLocked)
    }

    @Test
    fun relockReplacesAnOpenSettingsFlow() = runTest {
        val app = app()
        val lock = started(app)
        type(lock, "1234")
        lock.startChange()
        testScheduler.advanceUntilIdle()
        type(lock, "1234")
        assertEquals(PinStep.NEW, lock.current.prompt!!.step)
        lock.onBackground()
        app.monotonic += 120_000
        lock.onForeground()
        testScheduler.advanceUntilIdle()
        assertEquals(PinPurpose.UNLOCK, lock.current.prompt!!.purpose)
    }

    @Test
    fun enableSetsAPinEnteredTwice() = runTest {
        val app = app(pin = null)
        val lock = started(app)
        lock.startEnable()
        testScheduler.advanceUntilIdle()
        assertEquals("Set new PIN", lock.current.prompt!!.message)
        type(lock, "4321")
        assertEquals("Write again", lock.current.prompt!!.message)
        type(lock, "4322")
        assertEquals(AppLockModel.NOT_MATCHED, lock.current.prompt!!.error)
        assertEquals(PinStep.NEW, lock.current.prompt!!.step)
        type(lock, "4321")
        type(lock, "4321")
        assertNull(lock.current.prompt)
        assertTrue(lock.current.enabled)
        assertTrue(PinLock(KeyVault(app.store), 16).isEnabled())
    }

    @Test
    fun enableOffersBiometricsFirstThenSetsTheBackingPinAndBindsThem() = runTest {
        val app = app(pin = null)
        val lock = started(app)
        var binds = 0
        lock.biometricBinder = BiometricBinder { binds++; BINDING }
        lock.startEnable(biometricAvailable = true)
        testScheduler.advanceUntilIdle()
        assertTrue(lock.current.biometricOffer)
        assertNull(lock.current.prompt, "the offer comes before any PIN pad")
        assertFalse(lock.current.hidesContent)
        lock.acceptBiometricOffer()
        testScheduler.advanceUntilIdle()
        assertFalse(lock.current.biometricOffer)
        assertEquals(AppLockModel.SET_BACKUP, lock.current.prompt!!.message)
        type(lock, "4321")
        assertTrue(lock.current.prompt!!.withBiometric)
        // a mismatch keeps the biometric flow
        type(lock, "4322")
        assertEquals(AppLockModel.SET_BACKUP, lock.current.prompt!!.message)
        assertEquals(0, binds, "nothing is bound before the PIN is confirmed")
        type(lock, "4321")
        type(lock, "4321")
        assertNull(lock.current.prompt)
        assertTrue(lock.current.enabled)
        assertTrue(lock.current.biometricEnabled, "biometrics are the default unlock")
        assertEquals(1, binds)
        assertEquals(AppLockModel.BIOMETRIC_ON_NOTICE, lock.current.notice)
        val pin = PinLock(KeyVault(app.store), 16)
        assertTrue(pin.isEnabled())
        assertTrue(pin.isBiometricEnabled())
        assertEquals(0, pin.failedAttempts())
        // the PIN backs it: the lock screen starts with biometrics, and three wrong PINs still wipe
        lock.lockIfEnabled()
        testScheduler.advanceUntilIdle()
        assertTrue(lock.current.autoStartsBiometric)
        repeat(PinLock.MAX_ATTEMPTS) { type(lock, "0000") }
        assertFalse(PinLock(KeyVault(app.store), 16).hasPin(), "wiped")
    }

    @Test
    fun usePinInsteadAndNoBiometricsSetAPinOnly() = runTest {
        val app = app(pin = null)
        val lock = started(app)
        lock.biometricBinder = BiometricBinder { BINDING }
        lock.startEnable(biometricAvailable = true)
        lock.usePinInstead()
        testScheduler.advanceUntilIdle()
        assertFalse(lock.current.biometricOffer)
        assertEquals("Set new PIN", lock.current.prompt!!.message)
        assertFalse(lock.current.prompt!!.withBiometric)
        type(lock, "4321")
        type(lock, "4321")
        assertTrue(lock.current.enabled)
        assertFalse(lock.current.biometricEnabled)
        assertEquals("PIN lock is on.", lock.current.notice)
    }

    @Test
    fun aCancelledOfferLeavesTheLockOffAndNoBiometricsGoesStraightToThePin() = runTest {
        val lock = started(app(pin = null))
        lock.startEnable(biometricAvailable = true)
        lock.cancel()
        assertFalse(lock.current.biometricOffer)
        assertNull(lock.current.prompt)
        lock.acceptBiometricOffer()
        testScheduler.advanceUntilIdle()
        assertNull(lock.current.prompt, "no offer, nothing to accept")
        assertFalse(lock.current.enabled)
        lock.startEnable(biometricAvailable = false)
        testScheduler.advanceUntilIdle()
        assertFalse(lock.current.biometricOffer)
        assertEquals("Set new PIN", lock.current.prompt!!.message)
    }

    @Test
    fun anotherPadReplacingAPinConfirmationAnswersItsCallerFalse() = runTest {
        val lock = started(app())
        type(lock, "1234")
        for (replace in listOf<(AppLockModel) -> Unit>({ it.startChange() }, { it.startEnable(biometricAvailable = true) })) {
            val result = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val job = launch { result.complete(lock.verifyPin(whenOff = false)) }
            testScheduler.advanceUntilIdle()
            assertEquals(PinPurpose.VERIFY, lock.current.prompt?.purpose)
            replace(lock)
            testScheduler.advanceUntilIdle()
            assertTrue(result.isCompleted, "the caller is never left waiting")
            assertFalse(result.await())
            lock.cancel()
            testScheduler.advanceUntilIdle()
            job.cancel()
        }
    }

    @Test
    fun aBinderThatThrowsAfterThePinIsSetLeavesTheLockOnAndReported() = runTest {
        val app = app(pin = null)
        val lock = started(app)
        lock.biometricBinder = BiometricBinder { throw IllegalStateException("keystore unavailable") }
        lock.startEnable(biometricAvailable = true)
        lock.acceptBiometricOffer()
        testScheduler.advanceUntilIdle()
        type(lock, "4321")
        type(lock, "4321")
        assertNull(lock.current.prompt)
        assertTrue(lock.current.enabled, "the state follows the vault: the PIN is set")
        assertTrue(PinLock(KeyVault(app.store), 16).isEnabled())
        assertFalse(lock.current.biometricEnabled)
        assertEquals(AppLockModel.BIOMETRIC_UNAVAILABLE_NOTICE, lock.current.notice)
        assertNull(lock.current.error)
    }

    @Test
    fun aBiometricOfferWithNothingEnrolledAtBindLeavesThePinLockOnWithANotice() = runTest {
        val lock = started(app(pin = null))
        lock.biometricBinder = BiometricBinder { null } // enrollment removed between the prompt and the bind
        lock.startEnable(biometricAvailable = true)
        lock.acceptBiometricOffer()
        testScheduler.advanceUntilIdle()
        type(lock, "4321")
        type(lock, "4321")
        assertTrue(lock.current.enabled)
        assertFalse(lock.current.biometricEnabled)
        assertEquals(AppLockModel.BIOMETRIC_UNAVAILABLE_NOTICE, lock.current.notice)
    }

    @Test
    fun changeVerifiesTheOldPinThenTakesTheNewOne() = runTest {
        val app = app()
        val lock = started(app)
        type(lock, "1234")
        lock.startChange()
        testScheduler.advanceUntilIdle()
        type(lock, "9999")
        assertEquals(1, lock.current.prompt!!.failedAttempts)
        type(lock, "1234")
        type(lock, "5678")
        type(lock, "5678")
        assertNull(lock.current.prompt)
        assertEquals("PIN changed.", lock.current.notice)
        val pin = PinLock(KeyVault(app.store), 16)
        assertEquals(0, pin.failedAttempts())
        assertEquals(com.ecency.core.secure.PinCheck.Success, pin.verify("5678"))
    }

    @Test
    fun wrongPinsInSettingsFlowsCountTowardTheWipe() = runTest {
        val app = app()
        val lock = started(app)
        type(lock, "1234")
        lock.startDisable()
        testScheduler.advanceUntilIdle()
        type(lock, "0000")
        lock.cancel()
        lock.startBiometric(true)
        testScheduler.advanceUntilIdle()
        assertEquals(1, lock.current.prompt!!.failedAttempts)
        type(lock, "0000")
        lock.cancel()
        lock.startChange()
        testScheduler.advanceUntilIdle()
        type(lock, "0000")
        assertTrue(KeyVault(app.store).accounts().isEmpty())
        assertFalse(app.env.session.current.isLoggedIn)
    }

    @Test
    fun disableAndBiometricNeedThePin() = runTest {
        val app = app()
        val lock = started(app)
        lock.biometricBinder = BiometricBinder { BINDING }
        type(lock, "1234")
        lock.startBiometric(true)
        testScheduler.advanceUntilIdle()
        type(lock, "1234")
        assertTrue(lock.current.biometricEnabled)
        lock.startDisable()
        testScheduler.advanceUntilIdle()
        type(lock, "1234")
        assertFalse(lock.current.enabled)
        assertFalse(lock.current.biometricEnabled)
        assertEquals("PIN lock is off.", lock.current.notice)
    }

    @Test
    fun biometricUnlocksWithoutCountingOnlyOnTheLockScreen() = runTest {
        val app = app()
        PinLock(KeyVault(app.store), 16).setBiometricEnabled("1234", true) { BINDING }
        val lock = started(app)
        assertTrue(lock.current.offersBiometric)
        type(lock, "0000")
        lock.biometricSucceeded(BINDING)
        testScheduler.advanceUntilIdle()
        assertFalse(lock.current.isLocked)
        assertEquals(0, PinLock(KeyVault(app.store), 16).failedAttempts())
        // not a way around the PIN in settings flows
        lock.startDisable()
        testScheduler.advanceUntilIdle()
        lock.biometricSucceeded(BINDING)
        testScheduler.advanceUntilIdle()
        assertTrue(lock.current.enabled)
        assertEquals(PinPurpose.DISABLE, lock.current.prompt!!.purpose)
    }

    @Test
    fun aWipeRemovesThePrefsThatNameAnAccountAndKeepsTheDeviceSettings() = runTest {
        val app = app()
        val lock = started(app)
        val prefs = app.env.prefs
        val named = listOf(
            DigestModel.OFFERED_PREFIX + TestApp.USER, DigestModel.OFFERED_PREFIX + "someone-else",
            WavesOnboardingModel.key(TestApp.USER), "translate-dismissed:${PostKey("an-author", "a-post")}",
        )
        val kept = listOf(AppRating.FIRST_USE, AppRating.SESSIONS, AppRating.REQUESTED, WavesService.VOTE_WEIGHT_KEY, "feed.tabs")
        (named + kept).forEach { prefs.putString(it, "1") }
        assertTrue(named.all { key -> AccountPrefs.PREFIXES.any(key::startsWith) }, "the keys the models really write")

        lock.forgotPin()
        testScheduler.advanceUntilIdle()

        assertEquals(emptyList(), named.filter { prefs.getString(it) != null }, "nothing names an account after the wipe")
        assertEquals(kept, kept.filter { prefs.getString(it) == "1" })
    }

    @Test
    fun forgotPinWipes() = runTest {
        val app = app()
        val lock = started(app)
        lock.forgotPin()
        testScheduler.advanceUntilIdle()
        assertTrue(KeyVault(app.store).accounts().isEmpty())
        assertFalse(lock.current.isLocked)
        assertFalse(app.env.session.current.isLoggedIn)
    }

    @Test
    fun signingOutTheLastAccountTurnsTheLockOff() = runTest {
        val app = app()
        val lock = started(app)
        type(lock, "1234")
        app.env.session.signOut()
        testScheduler.advanceUntilIdle()
        assertFalse(lock.current.enabled)
        assertFalse(PinLock(KeyVault(app.store), 16).hasPin())
    }

    @Test
    fun deleteDigitAndInputWhileChecking() = runTest {
        val app = app()
        val lock = started(app)
        lock.enterDigit(1)
        lock.enterDigit(2)
        lock.deleteDigit()
        assertEquals(1, lock.current.prompt!!.digits)
        lock.enterDigit(2)
        lock.enterDigit(3)
        lock.enterDigit(4)
        // checking now: further input is ignored
        lock.enterDigit(9)
        assertTrue(lock.current.prompt!!.isChecking)
        testScheduler.advanceUntilIdle()
        assertFalse(lock.current.isLocked)
    }

    private fun TestScope.unlocked(): TestApp {
        val app = app()
        type(started(app), "1234")
        check(!app.env.appLock.current.hidesContent)
        return app
    }

    private fun relock(app: TestApp) {
        app.env.appLock.onBackground()
        app.monotonic += 60_000
        app.env.appLock.onForeground()
    }

    @Test
    fun relockHidesContentSynchronously() = runTest {
        val app = unlocked()
        relock(app)
        // no scheduler step: the content must be hidden in the same frame as the foreground event
        assertTrue(app.env.appLock.current.hidesContent, "content visible between onForeground and the async prompt")
        assertTrue(app.env.appLock.current.isLocked)
    }

    @Test
    fun relockShowsTheStoredCountOnceRead() = runTest {
        val app = unlocked()
        PinLock(KeyVault(app.store), 16).verify("0000") // counted outside the model
        relock(app)
        assertEquals(0, app.env.appLock.current.prompt!!.failedAttempts)
        testScheduler.advanceUntilIdle()
        assertEquals(1, app.env.appLock.current.prompt!!.failedAttempts)
    }

    @Test
    fun inFlightWrongSettingsCheckDoesNotUndoRelock() = runTest {
        val app = unlocked()
        val lock = app.env.appLock
        lock.startDisable()
        testScheduler.advanceUntilIdle()
        "0000".forEach { lock.enterDigit(it.digitToInt()) } // check queued, not run
        relock(app)
        testScheduler.advanceUntilIdle()
        assertTrue(lock.current.isLocked, "prompt now: ${lock.current.prompt}")
        // the settings attempt still counted, and the lock screen shows it
        assertEquals(1, lock.current.prompt!!.failedAttempts)
    }

    @Test
    fun inFlightRightSettingsCheckDoesNotUndoRelock() = runTest {
        val app = unlocked()
        val lock = app.env.appLock
        lock.biometricBinder = BiometricBinder { BINDING }
        lock.startBiometric(true)
        testScheduler.advanceUntilIdle()
        "1234".forEach { lock.enterDigit(it.digitToInt()) }
        relock(app)
        testScheduler.advanceUntilIdle()
        assertTrue(lock.current.isLocked, "prompt now: ${lock.current.prompt}")
        assertTrue(lock.current.biometricEnabled)
    }

    @Test
    fun aCheckTypedWhileAnotherRunsIsDropped() = runTest {
        val app = unlocked()
        val lock = app.env.appLock
        lock.startDisable()
        testScheduler.advanceUntilIdle()
        "0000".forEach { lock.enterDigit(it.digitToInt()) } // settings check in flight
        relock(app)
        "1234".forEach { lock.enterDigit(it.digitToInt()) } // typed on the relock pad before it settled
        testScheduler.advanceUntilIdle()
        // never two checks at once: the second entry was dropped, the pad cleared for a new try
        assertTrue(lock.current.isLocked)
        assertEquals(0, lock.current.prompt!!.digits)
        assertEquals(1, PinLock(KeyVault(app.store), 16).failedAttempts())
        type(lock, "1234")
        assertFalse(lock.current.isLocked)
    }

    @Test
    fun aStaleSettingsResultNeverReplacesTheLockPrompt() = runTest {
        val app = unlocked()
        val lock = app.env.appLock
        lock.startChange()
        testScheduler.advanceUntilIdle()
        "1234".forEach { lock.enterDigit(it.digitToInt()) } // success would move the flow to NEW
        relock(app)
        testScheduler.advanceUntilIdle()
        assertEquals(PinPurpose.UNLOCK, lock.current.prompt!!.purpose)
        assertEquals(PinStep.CURRENT, lock.current.prompt!!.step)
    }

    @Test
    fun coldStartLocksWhileAnyAccountRemains() = runTest {
        val app = TestApp(this) { HttpResult(500, "") }
        val vault = KeyVault(app.store)
        vault.save(VaultAccount("other-acct", AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF))
        app.signIn()
        PinLock(vault, 16).setPin("1234")
        vault.remove(TestApp.USER) // the current account signed out, another one with keys remains
        val lock = AppLockModel(app.env)
        lock.start()
        testScheduler.advanceUntilIdle()
        assertTrue(lock.current.hidesContent, "keys on device, lock on, but app opens unlocked")
        type(lock, "1234")
        lock.onBackground()
        app.monotonic += 60_000
        lock.onForeground()
        assertTrue(lock.current.isLocked, "relock skipped while no account is current")
    }

    @Test
    fun autoBiometricOncePerLock() = runTest {
        val app = app()
        PinLock(KeyVault(app.store), 16).setBiometricEnabled("1234", true) { BINDING }
        val lock = started(app)
        assertTrue(lock.current.autoStartsBiometric)
        lock.consumeAutoBiometric()
        assertFalse(lock.current.autoStartsBiometric)
        assertTrue(lock.current.offersBiometric)
        type(lock, "0000")
        // a wrong PIN shows the pad again but never re-fires the prompt
        assertTrue(lock.current.offersBiometric)
        assertFalse(lock.current.autoStartsBiometric)
        type(lock, "1234")
        relock(app)
        assertTrue(lock.current.autoStartsBiometric)
    }

    @Test
    fun aChangedEnrollmentTurnsBiometricsOffAndKeepsTheLock() = runTest {
        val app = app()
        PinLock(KeyVault(app.store), 16).setBiometricEnabled("1234", true) { BINDING }
        val lock = started(app)
        lock.biometricSucceeded("someone-else-enrolled")
        testScheduler.advanceUntilIdle()
        assertTrue(lock.current.isLocked)
        assertFalse(lock.current.biometricEnabled)
        assertFalse(lock.current.offersBiometric)
        assertEquals(AppLockModel.BIOMETRIC_CHANGED_NOTICE, lock.current.notice)
        assertFalse(lock.current.prompt!!.isChecking)
        type(lock, "1234")
        assertFalse(lock.current.isLocked)
    }

    @Test
    fun anInvalidatedKeyTurnsBiometricsOff() = runTest {
        val app = app()
        PinLock(KeyVault(app.store), 16).setBiometricEnabled("1234", true) { BINDING }
        val lock = started(app)
        lock.biometricInvalidated()
        testScheduler.advanceUntilIdle()
        assertTrue(lock.current.isLocked)
        assertFalse(lock.current.biometricEnabled)
        assertFalse(PinLock(KeyVault(app.store), 16).isBiometricEnabled())
        assertEquals(AppLockModel.BIOMETRIC_CHANGED_NOTICE, lock.current.notice)
    }

    @Test
    fun biometricsWithoutABinderStayOff() = runTest {
        val app = unlocked()
        val lock = app.env.appLock
        lock.startBiometric(true)
        testScheduler.advanceUntilIdle()
        type(lock, "1234")
        assertFalse(lock.current.biometricEnabled)
        assertEquals(AppLockModel.BIOMETRIC_UNAVAILABLE_NOTICE, lock.current.notice)
    }

    @Test
    fun changeKeepsOnlyAVerifiedFlagAndARelockEndsIt() = runTest {
        val app = unlocked()
        val lock = app.env.appLock
        lock.startChange()
        testScheduler.advanceUntilIdle()
        type(lock, "1234")
        type(lock, "5678")
        relock(app)
        type(lock, "1234") // unlock with the old PIN
        assertFalse(lock.current.isLocked)
        assertEquals(PinCheck.Success, PinLock(KeyVault(app.store), 16).verify("1234"))
    }

    // RN migration at start

    private fun TestScope.legacyApp(root: String = LegacyFixtures.PERSIST_ROOT, storage: MapLegacyStorage? = null): TestApp {
        val rn = storage ?: MapLegacyStorage(mapOf("persist:root" to root, "user" to LegacyFixtures.USERS))
        return TestApp(this, legacy = LegacyMigrationSource(rn, LegacyFixtures.PIN_KEY, LegacyFixtures.DEFAULT_PIN)) { HttpResult(500, "") }
    }

    @Test
    fun theFirstLaunchAfterTheUpdateLocksWithTheRnPin() = runTest {
        val app = legacyApp()
        app.env.session.start()
        app.env.appLock.start()
        // nothing shows before the migration and the lock decision are done
        assertTrue(app.env.appLock.current.hidesContent)
        testScheduler.advanceUntilIdle()
        val lock = app.env.appLock
        assertTrue(lock.current.isLocked, "the migrated lock must be on at the first start")
        assertTrue(app.env.session.current.isLoggedIn)
        assertNull(lock.current.notice)
        type(lock, LegacyFixtures.UNLOCK_PIN)
        assertFalse(lock.current.isLocked)
    }

    @Test
    fun anRnImportTheBuildCannotOpenIsToldOnceThenGivenUp() = runTest {
        // a PIN_KEY that does not open the RN data: PinUnavailable on every launch, which a retry never fixes
        val rn = MapLegacyStorage(mapOf("persist:root" to LegacyFixtures.PERSIST_ROOT, "user" to LegacyFixtures.USERS, "auth" to "{}"))
        val prefs = InMemoryPrefs()
        var previous: TestApp? = null
        val events = mutableListOf<AppEvent>()
        val reporter = RecordingReporter()
        fun launch(): AppLockModel {
            val app = TestApp(this, legacy = LegacyMigrationSource(rn, "not-the-build-pin-key", null)) { HttpResult(500, "") }
            app.env.prefs = prefs
            app.env.crashReporter = reporter
            previous?.store?.entries?.forEach { (k, v) -> app.store.entries[k] = v }
            previous = app
            backgroundScope.launch { app.env.events.collect { events += it } }
            return started(app)
        }
        val first = launch()
        assertEquals(AppLockModel.IMPORT_FAILED_NOTICE, first.current.notice)
        assertFalse(previous!!.env.session.current.isLoggedIn)
        assertTrue(rn.values.containsKey("user"), "kept for a retry")
        // told once only
        assertNull(launch().current.notice)
        assertTrue(rn.values.containsKey("user"))
        assertFalse(KeyVault(previous!!.store).legacyImported())
        // the third failure gives up: marked done, RN secrets deleted, settings kept
        assertNull(launch().current.notice)
        assertFalse(rn.values.containsKey("user"))
        assertFalse(rn.values.containsKey("auth"))
        assertTrue(rn.values.containsKey("persist:root"))
        assertTrue(KeyVault(previous!!.store).legacyImported())
        assertEquals("3", prefs.getString("legacy.importFailures"))
        assertEquals("PinUnavailable", prefs.getString("legacy.importFailureKind"))
        assertEquals(
            listOf(AppEvent.LegacyImportFailed("PinUnavailable", 1, false), AppEvent.LegacyImportFailed("PinUnavailable", 2, false),
                AppEvent.LegacyImportFailed("PinUnavailable", 3, true)),
            events.filterIsInstance<AppEvent.LegacyImportFailed>(),
        )
        // the crash reporter gets one breadcrumb per failed launch, kind and count only
        assertEquals(
            listOf("1" to "false", "2" to "false", "3" to "true"),
            reporter.crumbs.filter { it.first == "RN import failed" }.map { it.second.getValue("attempts") to it.second.getValue("abandoned") },
        )
        assertTrue(reporter.crumbs.all { it.second.keys == setOf("kind", "attempts", "abandoned") })
        // later launches neither count nor report again
        launch()
        assertEquals("3", prefs.getString("legacy.importFailures"))
    }

    @Test
    fun rnDataWithoutAccountsNeverTellsOfAFailedImport() = runTest {
        // a PIN_KEY that would not open this data, but there is no account to bring over: nothing to tell
        val rn = MapLegacyStorage(mapOf("persist:root" to LegacyFixtures.PERSIST_ROOT, "user" to "[]"))
        val app = TestApp(this, legacy = LegacyMigrationSource(rn, "not-the-build-pin-key", null)) { HttpResult(500, "") }
        assertNull(started(app).current.notice)
        assertNull(app.env.prefs.getString("legacy.importFailures"))
        assertTrue(KeyVault(app.store).legacyImported(), "done: nothing to import")
        assertFalse(rn.values.containsKey("user"), "and the RN secrets are cleaned up as after any import")
    }

    @Test
    fun anUnreadableRnStorageIsRetriedSilently() = runTest {
        val rn = MapLegacyStorage(mapOf("persist:root" to LegacyFixtures.PERSIST_ROOT, "user" to LegacyFixtures.USERS)).apply { unreadable = true }
        val app = TestApp(this, legacy = LegacyMigrationSource(rn, LegacyFixtures.PIN_KEY, null)) { HttpResult(500, "") }
        assertNull(started(app).current.notice)
        assertNull(app.env.prefs.getString("legacy.importFailures"))
    }

    @Test
    fun aDroppedRnLockIsToldOnce() = runTest {
        val root = LegacyFixtures.PERSIST_ROOT.replace(Regex("""(\\"encUnlockPin\\":\\")[^\\]+"""), "$1Z2FyYmFnZQ==")
        val app = legacyApp(root)
        val lock = started(app)
        assertFalse(lock.current.isLocked)
        assertEquals(AppLockModel.PIN_DROPPED_NOTICE, lock.current.notice)
        // a later launch over the same store does not repeat it
        val again = TestApp(this, legacy = LegacyMigrationSource(MapLegacyStorage(), LegacyFixtures.PIN_KEY, null)) { HttpResult(500, "") }
        app.store.entries.forEach { (k, v) -> again.store.entries[k] = v }
        assertNull(started(again).current.notice)
    }

    @Test
    fun unreadableRnStorageDoesNotBlockTheLock() = runTest {
        val app = app()
        val rn = MapLegacyStorage(mapOf("user" to "[]")).apply { unreadable = true }
        val withRn = TestApp(this, legacy = LegacyMigrationSource(rn, LegacyFixtures.PIN_KEY, null)) { HttpResult(500, "") }
        app.store.entries.forEach { (k, v) -> withRn.store.entries[k] = v }
        val lock = started(withRn)
        assertTrue(lock.current.isLocked)
        assertFalse(KeyVault(withRn.store).legacyImported())
    }

    @Test
    fun aCarriedOverBiometricSettingIsBoundByTheFirstPin() = runTest {
        val root = LegacyFixtures.PERSIST_ROOT.replace("\\\"isPinCodeOpen\\\":true", "\\\"isPinCodeOpen\\\":true,\\\"isBiometricEnabled\\\":true")
        val app = legacyApp(root)
        app.env.appLock.biometricBinder = BiometricBinder { BINDING }
        val lock = started(app)
        assertFalse(lock.current.offersBiometric, "unbound biometrics must not be offered")
        type(lock, LegacyFixtures.UNLOCK_PIN)
        assertTrue(PinLock(KeyVault(app.store), 16).isBiometricEnabled())
    }

    @Test
    fun thePinWipeDeletesTheRnSecretsAtOnce() = runTest {
        // the RN storage could not be read at this start: its keys are still on disk when the wipe runs
        val rn = MapLegacyStorage(mapOf("persist:root" to LegacyFixtures.PERSIST_ROOT, "user" to LegacyFixtures.USERS)).apply { unreadable = true }
        val app = TestApp(this, legacy = LegacyMigrationSource(rn, LegacyFixtures.PIN_KEY, null)) { HttpResult(500, "") }
        app.signIn()
        PinLock(KeyVault(app.store), 16).setPin("1234")
        val lock = started(app)
        assertTrue(lock.current.isLocked)
        rn.unreadable = false
        assertTrue(com.ecency.core.legacy.LegacyCleanup.hasSecrets(rn))
        repeat(3) { type(lock, "0000") }
        assertFalse(app.env.session.current.isLoggedIn, "the third wrong PIN wipes")
        assertFalse(com.ecency.core.legacy.LegacyCleanup.hasSecrets(rn), "the RN keys, tokens and PINs went with the wipe")
        assertTrue(KeyVault(app.store).legacyImported(), "and are never imported back")
    }

    @Test
    fun theFirstLaunchAfterTheImportLoadsTheImportedAccountsWallet() = runTest {
        // the platforms read the vault on IO, not on main: the account change must wait for that re-read
        val rn = MapLegacyStorage(mapOf("persist:root" to LegacyFixtures.PERSIST_ROOT, "user" to LegacyFixtures.USERS))
        val app = TestApp(this, legacy = LegacyMigrationSource(rn, LegacyFixtures.PIN_KEY, LegacyFixtures.DEFAULT_PIN), separateIo = true) {
            HttpResult(500, "")
        }
        val events = mutableListOf<AppEvent>()
        val collector = launch(app.main) { app.env.events.collect { events += it } }
        testScheduler.runCurrent()
        app.env.sendJournal.start()
        app.env.session.start()
        app.env.wallet.start()
        testScheduler.runCurrent()
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
        val user = app.env.session.current.username
        assertTrue(user != null, "the import signs the RN account in")
        assertEquals(user, app.env.wallet.current.account, "the wallet loads the imported account")
        assertEquals(listOf<AppEvent>(AppEvent.SessionChanged(user)), events.filterIsInstance<AppEvent.SessionChanged>(), "one account change")
        collector.cancel()
    }

    companion object {
        const val BINDING = "enrolled-at-enable"
    }
}
