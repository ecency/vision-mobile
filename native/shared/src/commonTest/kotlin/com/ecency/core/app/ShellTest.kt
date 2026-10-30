package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.model.Account
import com.ecency.core.api.model.Mana
import com.ecency.core.api.model.Manabar
import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellTest {
    @Test
    fun sideMenuItemsInRnOrder() {
        assertEquals(
            listOf("profile", "bookmarks", "drafts", "communities", "explore", "qr", "ask_esie", "refer", "docs", "settings", "logout"),
            SideMenu.items(loggedIn = true).map { it.id },
        )
        assertEquals(listOf("add_account", "qr", "docs", "settings"), SideMenu.items(loggedIn = false).map { it.id })
        // the main group scrolls at the top, the footer group is pinned to the bottom
        assertEquals(listOf("profile", "bookmarks", "drafts", "communities", "explore"), SideMenu.items(true, MenuGroup.MAIN).map { it.id })
        assertEquals(listOf("qr", "ask_esie", "refer", "docs", "settings", "logout"), SideMenu.items(true, MenuGroup.FOOTER).map { it.id })
        assertEquals(listOf("add_account"), SideMenu.items(false, MenuGroup.MAIN).map { it.id })
        assertEquals(listOf("qr", "docs", "settings"), SideMenu.items(false, MenuGroup.FOOTER).map { it.id })
        // pinned while the main group keeps a row; a landscape phone scrolls the whole menu
        assertTrue(SideMenu.pinsFooter(availableHeight = 500f, footerHeight = 300f))
        assertTrue(SideMenu.pinsFooter(availableHeight = 372f, footerHeight = 300f))
        assertFalse(SideMenu.pinsFooter(availableHeight = 371f, footerHeight = 300f))
        assertFalse(SideMenu.pinsFooter(availableHeight = 200f, footerHeight = 300f))
        assertEquals("Refer & Earn", SideMenuItem.REFER.title)
        assertEquals("Ask Esie", SideMenuItem.ASK_ESIE.title)
        assertEquals(AppIcon.AI_ASSIST, SideMenuItem.ASK_ESIE.icon)
    }

    @Test
    fun sideMenuActions() {
        assertEquals(MenuAction.Open(AppScreen.Profile("alice")), SideMenu.action(SideMenuItem.PROFILE, "alice", false))
        assertEquals(MenuAction.Open(AppScreen.Bookmarks(0)), SideMenu.action(SideMenuItem.BOOKMARKS, "alice", false))
        assertEquals(MenuAction.Open(AppScreen.Explore), SideMenu.action(SideMenuItem.EXPLORE, "alice", false))
        assertEquals(MenuAction.Open(AppScreen.QrScan), SideMenu.action(SideMenuItem.QR, null, false))
        assertEquals(MenuAction.Browser(SideMenu.DOCS_URL), SideMenu.action(SideMenuItem.DOCS, null, false))
        assertEquals(MenuAction.Open(AppScreen.Settings), SideMenu.action(SideMenuItem.SETTINGS, null, false))
        assertEquals(MenuAction.ConfirmLogout, SideMenu.action(SideMenuItem.LOGOUT, "alice", false))
        // web "Ask Esie": the direct message with esie; signed out it is not listed, and a stray tap asks for a login
        assertEquals(MenuAction.DirectMessage("esie"), SideMenu.action(SideMenuItem.ASK_ESIE, "alice", false))
        assertEquals(MenuAction.Open(AppScreen.Login), SideMenu.action(SideMenuItem.ASK_ESIE, null, false))
        // RN: add account opens the accounts sheet only when other accounts exist
        assertEquals(MenuAction.Open(AppScreen.Login), SideMenu.action(SideMenuItem.ADD_ACCOUNT, null, hasOtherAccounts = false))
        assertEquals(MenuAction.AccountsSheet, SideMenu.action(SideMenuItem.ADD_ACCOUNT, null, hasOtherAccounts = true))
        assertEquals("v4.0.0, 12", SideMenu.versionText("4.0.0", "12"))
    }

    @Test
    fun themeModes() {
        assertTrue(ThemeMode.SYSTEM.isDark(true))
        assertEquals(false, ThemeMode.SYSTEM.isDark(false))
        assertEquals(false, ThemeMode.LIGHT.isDark(true))
        assertTrue(ThemeMode.DARK.isDark(false))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.of(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.of("PURPLE"))
        assertEquals(ThemeMode.DARK, ThemeMode.of("DARK"))
        assertEquals(listOf(0, 1, 2), ThemeMode.entries.map { it.rnValue })
    }

    @Test
    fun toastsQueueOneAtATimeAndBannersSeparately() = runTest {
        val app = TestApp(this) { HttpResult(500, "") }
        val ui = app.env.ui
        assertEquals(-1L, ui.toast("  "))
        val a = ui.toast("Copied")
        assertEquals(a, ui.toast("Copied"), "the same text twice in a row shows once")
        val b = ui.toast("Saved")
        val banner = ui.banner("Reply on @alice", "replied", AppScreen.Post("alice", "p"))
        assertEquals("Copied", ui.current.toast?.text)
        assertEquals(banner, ui.current.banner?.id)
        assertEquals(3, ui.current.queued)
        ui.dismiss(999)
        assertEquals(a, ui.current.toast?.id)
        ui.dismiss(a)
        assertEquals(b, ui.current.toast?.id)
        ui.dismiss(banner)
        assertNull(ui.current.banner)
        assertEquals(UiMessage.TOAST_MILLIS, ui.current.toast?.durationMillis)
        ui.clear()
        assertNull(ui.current.toast)
    }

    @Test
    fun signOutDropsQueuedMessages() = runTest {
        val app = TestApp(this) { HttpResult(500, "") }
        app.signIn()
        app.env.session.start()
        testScheduler.advanceUntilIdle()
        app.env.ui.banner("Transfer from @x", "1 HIVE", null)
        app.env.session.signOut()
        testScheduler.advanceUntilIdle()
        assertNull(app.env.ui.current.banner, "a banner for the previous account is gone")
    }

    @Test
    fun connectivityAndBadges() = runTest {
        val app = TestApp(this) { HttpResult(500, "") }
        assertTrue(app.env.connectivity.current.online)
        app.env.connectivity.setOnline(false)
        assertEquals(false, app.env.connectivity.current.online)
        val badges = app.env.badges
        assertNull(badges.current.text(BadgeKind.NOTIFICATION), "no provider yet: no badge")
        badges.setCounts(notifications = 120, chats = -3)
        assertEquals("99+", badges.current.text(BadgeKind.NOTIFICATION))
        assertNull(badges.current.text(BadgeKind.CHAT))
        assertEquals(120, badges.current.count(AppTab.NOTIFICATION.badge))
        assertEquals(0, badges.current.count(AppTab.FEED.badge))
    }

    private val accountJson = """[{"name":"alice","reputation":0,"posting":{"key_auths":[]},
        "posting_json_metadata":"{\"profile\":{\"name\":\"Alice A\"}}",
        "vesting_shares":"1000.000000 VESTS","delegated_vesting_shares":"200.000000 VESTS",
        "received_vesting_shares":"100.000000 VESTS","vesting_withdraw_rate":"0.000000 VESTS","to_withdraw":0,"withdrawn":0,
        "voting_manabar":{"current_mana":"450000000","last_update_time":${TestApp.NOW / 1000 - 43200}}}]"""

    @Test
    fun votingPowerMatchesTheSdk() {
        val account = Account.fromJson(Json.parseToJsonElement(accountJson).let { (it as kotlinx.serialization.json.JsonArray)[0] })!!
        assertEquals(900.0, Mana.effectiveVests(account))
        // 450e6 of 900e6, plus 12 h of the 5 day refill (10%): 60%
        assertEquals(60.0, Mana.votingPowerPercent(account, TestApp.NOW))
        assertEquals(100.0, Mana.votingPowerPercent(account, TestApp.NOW + 10L * 86_400_000))
        assertEquals(0.0, Mana.votingPowerPercent(account.copy(votingManabar = null), TestApp.NOW))
        assertEquals(0.0, Mana.votingPowerPercent(account.copy(vestingShares = "0.000000 VESTS", delegatedVestingShares = "0.000000 VESTS", receivedVestingShares = "0.000000 VESTS"), TestApp.NOW))
        // power down: min(rate, left) comes off
        val down = account.copy(vestingWithdrawRate = "50.000000 VESTS", toWithdraw = 400e6, withdrawn = 380e6)
        assertEquals(880.0, Mana.effectiveVests(down))
        assertEquals("87.3", Mana.oneDecimal(87.25))
        assertEquals("100.0", Mana.oneDecimal(100.0))
        assertEquals("0.0", Mana.oneDecimal(0.0))
        // a tie not exact in binary: RN toFixed gives "87.3", this gives "87.4"; both are accepted
        assertTrue(Mana.oneDecimal(87.35) in setOf("87.3", "87.4"))
        // missing vesting fields count as 0 (RN: NaN, shown as 0)
        val sparse = Account.fromJson(Json.parseToJsonElement("""{"name":"x","vesting_shares":"100.000000 VESTS","voting_manabar":{"current_mana":"50000000","last_update_time":${TestApp.NOW / 1000}}}"""))!!
        assertEquals(100.0, Mana.effectiveVests(sparse))
        assertTrue(Mana.votingPowerPercent(sparse, TestApp.NOW) in setOf(0.0, 50.0))
        assertEquals(Manabar(450000000.0, TestApp.NOW / 1000 - 43200), account.votingManabar)
    }

    @Test
    fun drawerHeaderFromTheAccountAndWithout() = runTest {
        var down = false
        val app = TestApp(this) { req ->
            if (!down && req.method == "condenser_api.get_accounts") FakeTransport.result(req, accountJson) else HttpResult(500, "")
        }
        assertEquals(DrawerHeader("alice", "Alice A", "60.0"), app.env.drawerHeader("alice"))
        down = true
        val calls = app.transport.sent.size
        assertEquals(DrawerHeader("alice", "Alice A", "60.0"), app.env.drawerHeader("alice"), "cached for a minute: no new call")
        assertEquals(calls, app.transport.sent.size)
        assertEquals(DrawerHeader("bob", null, "0"), app.env.drawerHeader("bob"), "offline and not cached: the name only")
    }
}
