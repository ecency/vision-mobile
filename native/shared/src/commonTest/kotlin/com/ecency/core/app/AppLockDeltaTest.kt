package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinCheck
import com.ecency.core.secure.PinLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** App lock fixes from review A: start once per process, broken verifier fails closed, guard tests. */
class AppLockDeltaTest {
    private fun TestScope.app(pin: String? = "1234", signedIn: Boolean = true): TestApp {
        val app = TestApp(this) { HttpResult(500, "") }
        if (signedIn) app.signIn()
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

    private fun relock(app: TestApp) {
        app.env.appLock.onBackground()
        app.monotonic += 60_000
        app.env.appLock.onForeground()
    }

    @Test
    fun startingAgainAfterAnUnlockKeepsTheAppUnlocked() = runTest {
        val app = app()
        val lock = started(app)
        type(lock, "1234")
        assertFalse(lock.current.isLocked)

        // an activity recreation runs the UI's start effect again over the same environment
        lock.start()
        assertNull(lock.current.prompt, "no PIN pad set synchronously")
        testScheduler.advanceUntilIdle()
        assertFalse(lock.current.isLocked, "a second start must not relock")
        assertNull(lock.current.prompt)
    }

    @Test
    fun aFailedStartCanBeRetried() = runTest {
        val app = app()
        app.store.keyLost = true
        val lock = started(app)
        assertTrue(lock.current.hidesContent)
        val error = lock.current.error
        assertTrue(error != null, "the failure is shown")
        app.store.keyLost = false
        lock.clearError()
        lock.start()
        testScheduler.advanceUntilIdle()
        assertTrue(lock.current.loaded)
        assertTrue(lock.current.isLocked)
    }

    @Test
    fun aBrokenVerifierKeepsTheLockOnAndOnlyAWipeOpensIt() = runTest {
        val app = app()
        app.store.entries["pin.verifier"] = "pbkdf2-sha256\$garbage".encodeToByteArray()
        val lock = started(app)
        assertTrue(lock.current.enabled)
        assertTrue(lock.current.isLocked, "an undecodable verifier must not turn the lock off")
        type(lock, "1234")
        assertTrue(lock.current.isLocked, "no PIN matches a broken verifier")
        lock.forgotPin()
        testScheduler.advanceUntilIdle()
        assertFalse(lock.current.isLocked)
        assertTrue(KeyVault(app.store).accounts().isEmpty(), "the way out wipes the device's accounts")
    }

    @Test
    fun anUnreadableVerifierEntryKeepsTheLockOn() = runTest {
        val app = app()
        // not UTF-8: the store reports the entry as corrupted
        app.store.entries["pin.verifier"] = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val lock = started(app)
        assertTrue(lock.current.isLocked)
        val pinLock = PinLock(KeyVault(app.store), 16)
        assertTrue(pinLock.isEnabled())
        assertTrue(pinLock.verify("1234") is PinCheck.Wrong)
    }

    @Test
    fun aBrokenVerifierNeverUnlocksWithBiometrics() = runTest {
        val app = app()
        val pinLock = PinLock(KeyVault(app.store), 16)
        assertEquals(PinCheck.Success, pinLock.setBiometricEnabled("1234", true) { "binding" })
        app.store.entries["pin.verifier"] = "broken".encodeToByteArray()
        assertEquals(PinCheck.BiometricChanged, pinLock.unlockWithBiometric("binding"))
        assertFalse(pinLock.isBiometricEnabled())
        assertTrue(pinLock.isEnabled())
    }

    @Test
    fun noRelockWithoutStoredAccounts() = runTest {
        val app = app(signedIn = false)
        val lock = started(app)
        assertTrue(lock.current.enabled)
        assertFalse(lock.current.hasAccounts)
        assertFalse(lock.current.isLocked)
        relock(app)
        assertFalse(lock.current.isLocked, "nothing on the device to guard")
    }

    @Test
    fun aRelockDuringTheChangeConfirmationKeepsTheOldPin() = runTest {
        val app = app()
        val lock = started(app)
        type(lock, "1234")
        lock.startChange()
        testScheduler.advanceUntilIdle()
        type(lock, "1234") // current
        type(lock, "5678") // new
        // the confirmation is submitted, and the app relocks before its check runs
        "5678".forEach { lock.enterDigit(it.digitToInt()) }
        relock(app)
        testScheduler.advanceUntilIdle()
        assertTrue(lock.current.isLocked)
        val pinLock = PinLock(KeyVault(app.store), 16)
        assertTrue(pinLock.verify("5678") is PinCheck.Wrong, "the new PIN must not have been written")
        assertEquals(PinCheck.Success, pinLock.verify("1234"))
    }

    @Test
    fun aStorageErrorDuringAnUnlockShowsOnThePadUntilTheNextCheck() = runTest {
        val app = app()
        val lock = started(app)
        app.store.keyLost = true
        type(lock, "1234")
        assertTrue(lock.current.isLocked)
        val error = lock.current.error
        assertTrue(error != null)
        assertEquals(error.message, lock.current.promptError, "the pad shows what an alert cannot while locked")
        app.store.keyLost = false
        type(lock, "0000")
        assertNull(lock.current.error, "a new check replaces the old failure")
        assertEquals(AppLockModel.INVALID_PIN, lock.current.promptError)
    }

    @Test
    fun cancellingASettingsPadDropsTheErrorItShowed() = runTest {
        val app = app()
        val lock = started(app)
        type(lock, "1234")
        lock.startDisable()
        testScheduler.advanceUntilIdle()
        app.store.namesUnavailable = true
        lock.refresh()
        testScheduler.advanceUntilIdle()
        app.store.namesUnavailable = false
        assertTrue(lock.current.promptError != null, "the pad shows the error")
        lock.cancel()
        assertNull(lock.current.prompt)
        assertNull(lock.current.error, "no alert repeating what the pad showed")
    }

    @Test
    fun finishingASettingsPadDropsTheErrorItShowed() = runTest {
        val app = app()
        val lock = started(app)
        type(lock, "1234")
        lock.startDisable()
        testScheduler.advanceUntilIdle()
        // a settings read fails while the check runs; the pad shows it, then the flow finishes
        app.store.namesUnavailable = true
        lock.refresh()
        "1234".forEach { lock.enterDigit(it.digitToInt()) }
        testScheduler.advanceUntilIdle()
        app.store.namesUnavailable = false
        assertNull(lock.current.prompt)
        assertFalse(lock.current.enabled)
        assertNull(lock.current.error, "no alert repeating what the pad showed")
    }
}
