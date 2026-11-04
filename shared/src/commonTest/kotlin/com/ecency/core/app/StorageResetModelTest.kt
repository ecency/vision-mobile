package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.SecureStoreException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The blocking "sign in again" prompt after the secure storage lost its device key. */
class StorageResetModelTest {
    private fun TestScope.app(pin: String? = null): TestApp {
        val app = TestApp(this) { HttpResult(500, "") }
        app.signIn()
        if (pin != null) PinLock(KeyVault(app.store), 16).setPin(pin)
        return app
    }

    private fun TestScope.start(app: TestApp) {
        app.env.session.start()
        app.env.appLock.start()
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun aLostKeyOpensThePromptAndNothingIsWipedUntilTheUserConfirms() = runTest {
        val app = app()
        start(app)
        assertFalse(app.env.storageReset.current.needed)
        val before = app.store.entries.keys.toSet()
        assertTrue(before.isNotEmpty())

        app.store.keyLost = true
        // any vault call meets it: here a read of the accounts
        runCatching { app.env.vault.vault.account(TestApp.USER) }
        testScheduler.advanceUntilIdle()

        assertTrue(app.env.storageReset.current.needed)
        // the prompt alone removes nothing, however long it shows
        assertEquals(before, app.store.entries.keys.toSet())
        assertTrue(app.store.keyLost)
    }

    @Test
    fun confirmingWipesSignsOutAndAsksForTheLoginScreen() = runTest {
        val app = app(pin = "1234")
        app.store.keyLost = true
        val events = mutableListOf<AppEvent>()
        val collector = launch { app.env.events.collect { events += it } }
        // the start itself meets the lost key (the lock state cannot be read)
        start(app)
        val reset = app.env.storageReset
        assertTrue(reset.current.needed)
        assertTrue(events.none { it is AppEvent.DataWiped })

        reset.confirm()
        assertTrue(reset.current.working)
        testScheduler.advanceUntilIdle()

        assertEquals(StorageResetState(), reset.current)
        assertTrue(app.store.entries.keys.none { it.startsWith("account.") || it.startsWith("pin.") })
        assertFalse(app.store.keyLost)
        assertNull(app.env.session.current.username)
        // the lock is off and the app shows again
        assertFalse(app.env.appLock.current.hidesContent)
        assertFalse(app.env.appLock.current.enabled)
        assertEquals(listOf(AppEvent.DataWiped, AppEvent.SessionChanged(null), AppEvent.SignInAgain), events.filter { it is AppEvent.DataWiped || it is AppEvent.SessionChanged || it is AppEvent.SignInAgain })
        // the store works again: the next sign-in can be stored
        app.signIn("someone-else")
        assertEquals("someone-else", app.env.vault.vault.account("someone-else")?.username)
        collector.cancel()
    }

    @Test
    fun anUnavailableOrCorruptedStoreNeverOpensThePrompt() = runTest {
        val app = app()
        start(app)
        val vault = app.env.vault.vault
        app.store.failingReads["account.${TestApp.USER}"] = SecureStoreException.Unavailable(-25308)
        assertTrue(runCatching { vault.account(TestApp.USER) }.exceptionOrNull() is SecureStoreException.Unavailable)
        app.store.failingReads["account.${TestApp.USER}"] = SecureStoreException.Corrupted("account.${TestApp.USER}")
        runCatching { vault.account(TestApp.USER) }
        app.store.namesUnavailable = true
        runCatching { vault.usernames() }
        testScheduler.advanceUntilIdle()

        assertFalse(app.env.storageReset.current.needed)
    }

    @Test
    fun confirmWithoutThePromptWipesNothing() = runTest {
        val app = app()
        start(app)
        val before = app.store.entries.keys.toSet()

        app.env.storageReset.confirm()
        testScheduler.advanceUntilIdle()

        assertEquals(before, app.store.entries.keys.toSet())
        assertEquals(TestApp.USER, app.env.session.current.username)
        assertFalse(app.env.storageReset.current.working)
    }

    @Test
    fun aWipeTheStoreRefusedKeepsThePromptForAnotherTry() = runTest {
        val app = app()
        start(app)
        app.store.keyLost = true
        runCatching { app.env.vault.vault.account(TestApp.USER) }
        testScheduler.advanceUntilIdle()
        app.store.wipeUnavailable = true

        app.env.storageReset.confirm()
        testScheduler.advanceUntilIdle()

        val failed = app.env.storageReset.current
        assertTrue(failed.needed)
        assertFalse(failed.working)
        assertEquals(StorageResetModel.FAILED, failed.error)

        app.store.wipeUnavailable = false
        app.env.storageReset.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals(StorageResetState(), app.env.storageReset.current)
        assertTrue(app.store.entries.keys.none { it.startsWith("account.") })
    }
}
