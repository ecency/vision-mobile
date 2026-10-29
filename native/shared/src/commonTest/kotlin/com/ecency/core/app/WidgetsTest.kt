package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.hive.GoldenVectors
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinLock
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetsTest {
    @Test
    fun shortcutsParseOnlyTheirFourShapes() {
        assertEquals(AppShortcut.Notifications, AppShortcuts.parse(AppShortcuts.NOTIFICATIONS))
        assertEquals(AppShortcut.Perks, AppShortcuts.parse("ECENCY://Perks/"))
        assertEquals(AppShortcut.Write, AppShortcuts.parse("ecency://compose/post?x=1"))
        assertEquals(AppShortcut.NewWave, AppShortcuts.parse(AppShortcuts.NEW_WAVE))
        for (bad in listOf(
            "ecency://transfer", "ecency://login", "hive://notifications", "https://ecency.com/notifications",
            "ecency://compose", "ecency://compose/wave/extra", "ecency://notifications x", "ecency://per\tks", "",
            "ecency://notifications/" + "a".repeat(80),
        )) assertNull(AppShortcuts.parse(bad), bad)
    }

    @Test
    fun shortcutsOpenTheirPlaceAndTheLoginGatesStillApply() {
        assertEquals(NavEntry.tab(AppTab.NOTIFICATION), AppShortcut.Notifications.entry)
        assertEquals(NavEntry.tab(AppTab.WAVES), AppShortcut.NewWave.entry)
        // signed out, the screens that need an account send the reader to the login (NavState.open gates them)
        val perks = NavState().open(AppShortcut.Perks.entry, loggedIn = false)
        assertTrue(perks.top !is AppScreen.Perks, "perks needs a login")
        val write = NavState().open(AppShortcut.Write.entry, loggedIn = true)
        assertTrue(write.top is AppScreen.Editor)
        for (link in listOf(AppShortcuts.NOTIFICATIONS, AppShortcuts.PERKS, AppShortcuts.WRITE, AppShortcuts.NEW_WAVE)) {
            assertNull(com.ecency.core.auth.AuthLinks.parse(link), "the sign-in and signing links never claim $link")
        }
    }

    @Test
    fun theSnapshotHidesAccountValuesWhenLockedOrSignedOut() {
        assertEquals(WidgetSnapshot.EMPTY, WidgetFeed.snapshot(null, locked = false, unread = 4, streak = 3))
        assertEquals(WidgetSnapshot(signedIn = true, locked = true), WidgetFeed.snapshot("alice", locked = true, unread = 4, streak = 3))
        assertEquals(WidgetSnapshot(true, false, 4, 3), WidgetFeed.snapshot("alice", locked = false, unread = 4, streak = 3))
        assertEquals(0, WidgetFeed.snapshot("alice", locked = false, unread = -2, streak = null).unread)
    }

    @Test
    fun theSnapshotRoundTripsAndAnythingOddReadsAsNothing() {
        val s = WidgetSnapshot(true, false, 12, 5)
        assertEquals("""{"v":1,"signedIn":true,"locked":false,"unread":12,"streak":5}""", s.encode())
        assertEquals(s, WidgetSnapshot.decode(s.encode()))
        assertEquals(WidgetSnapshot.EMPTY, WidgetSnapshot.decode(null))
        assertEquals(WidgetSnapshot.EMPTY, WidgetSnapshot.decode("not json"))
        assertEquals(WidgetSnapshot.EMPTY, WidgetSnapshot.decode("""{"v":2,"signedIn":true,"locked":false,"unread":1}"""))
        assertNull(WidgetSnapshot.decode("""{"v":1,"signedIn":true,"locked":false,"unread":-1}""").unread)
        assertNull(WidgetSnapshot.decode("""{"v":1,"signedIn":true,"locked":true,"unread":7}""").unread, "locked hides the count")
        assertEquals("1 unread", WidgetSnapshot.unreadLabel(1))
        assertEquals("3-day streak", WidgetSnapshot.streakLabel(3))
    }

    private class Store : WidgetStore {
        val writes = mutableListOf<String>()
        override fun write(snapshot: String) {
            writes += snapshot
        }
        val last: WidgetSnapshot get() = WidgetSnapshot.decode(writes.lastOrNull())
    }

    private fun TestScope.app(pin: Boolean): Pair<TestApp, Store> {
        val app = TestApp(this) { TestApp.chain(it) ?: HttpResult(500, "") }
        listOf("spike-alice", "spike-bob").forEach { name ->
            KeyVault(app.store).save(VaultAccount(name, AuthType.POSTING_KEY, postingKey = GoldenVectors.WIF, accessToken = "token-$name"))
        }
        KeyVault(app.store).setCurrentUsername("spike-alice")
        if (pin) PinLock(KeyVault(app.store), 16).setPin("1234")
        val store = Store()
        app.env.widgets.store = store
        app.env.session.start()
        app.env.appLock.start()
        app.env.widgets.start()
        testScheduler.advanceUntilIdle()
        if (pin) {
            "1234".forEach { app.env.appLock.enterDigit(it.digitToInt()) }
            testScheduler.advanceUntilIdle()
        }
        return app to store
    }

    @Test
    fun aNewWaveShortcutAsksForTheComposerTheOthersOnlyNavigate() = runTest {
        val (app, _) = app(pin = false)
        assertEquals(NavEntry.tab(AppTab.NOTIFICATION), app.env.shortcutEntry(AppShortcuts.NOTIFICATIONS))
        assertNull(app.env.waves.pendingComposeText)
        assertEquals(NavEntry.tab(AppTab.WAVES), app.env.shortcutEntry(AppShortcuts.NEW_WAVE))
        assertNull(app.env.waves.pendingComposeText, "nothing until the entry really opened")
        app.env.entryOpened()
        assertEquals("", app.env.waves.takeComposeText(), "an empty composer, nothing prefilled")
        app.env.entryOpened()
        assertNull(app.env.waves.pendingComposeText, "once per shortcut")
        assertNull(app.env.shortcutEntry("ecency://transfer?to=bob"))
        // a shared text waiting (through the login) is never overwritten by a shortcut from another app
        app.env.waves.requestCompose("shared text")
        app.env.shortcutEntry(AppShortcuts.NEW_WAVE)
        app.env.entryOpened()
        assertEquals("shared text", app.env.waves.takeComposeText())
    }

    @Test
    fun theFeedWritesTheStreakForTheReaderAndClearsOnLogout() = runTest {
        val (app, store) = app(pin = false)
        assertEquals(WidgetSnapshot(signedIn = true, locked = false), store.last)
        app.env.widgets.streakRead("spike-alice", 6)
        testScheduler.advanceUntilIdle()
        assertEquals(6, store.last.streak)
        val writes = store.writes.size
        app.env.widgets.streakRead("spike-alice", 6)
        testScheduler.advanceUntilIdle()
        assertEquals(writes, store.writes.size, "an unchanged snapshot is not written again")
        // another account's streak never shows for this reader
        app.env.widgets.streakRead("spike-bob", 40)
        testScheduler.advanceUntilIdle()
        assertNull(store.last.streak)
        app.env.widgets.streakRead("spike-alice", 6)
        app.env.session.logout("spike-alice")
        testScheduler.advanceUntilIdle()
        assertNull(store.last.streak, "the logout clears it")
        assertTrue(store.writes.none { "token" in it || "spike-" in it }, "no names, tokens or keys reach the widget")
    }

    @Test
    fun withThePinLockOnTheFeedWritesNoAccountValues() = runTest {
        val (app, store) = app(pin = true)
        app.env.widgets.streakRead("spike-alice", 6)
        testScheduler.advanceUntilIdle()
        assertTrue(store.last.locked)
        assertNull(store.last.streak)
        assertFalse(store.writes.any { "\"streak\"" in it })
    }
}
