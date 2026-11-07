package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.hive.KeyRole
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tester report: claiming points, HIVE and HP one after another sometimes left only the POINTS row and a total of points
 * alone. A reload after an action answered by part of the portfolio, or with the account read failed, must keep the
 * rows already shown (README "Wallet", "Reloads keep rows").
 */
class WalletReloadMergeTest {
    private val tokens = """[{"symbol":"LEO","type":"ENGINE","meta":{"show":true}},{"symbol":"BNB","type":"CHAIN","meta":{"show":true,"address":"0xabc"}}]"""
    private val all = listOf("POINTS", "HP", "HIVE", "HBD", "LEO", "BNB")

    /** The fixture's POINTS wallet alone, [balance] as given. */
    private fun pointsOnly(balance: String = "1500.5", pending: String = "0.75") =
        """{"wallets":[{"name":"Ecency Points","symbol":"POINTS","layer":"points","balance":$balance,"fiatRate":0.002,"currency":"USD","precision":3,"pendingRewards":$pending,"liquid":$balance}]}"""

    private suspend fun TestScope.loaded(): WalletWorld {
        val w = WalletWorld(this, tokensJson = tokens)
        w.http["/private-api/points-claim"] = { HttpResult(200, "{}") }
        w.env.wallet.start()
        testScheduler.advanceUntilIdle()
        assertEquals(all, w.env.wallet.current.rows.map { it.symbol })
        return w
    }

    @Test
    fun aReloadAnsweringOnlyPointsKeepsEveryRowAndTheTotal() = runTest {
        val w = loaded()
        val total = w.env.wallet.current.totalText
        w.http["/wallet-api/portfolio-v2"] = { HttpResult(200, pointsOnly()) }
        w.env.wallet.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(all, w.env.wallet.current.rows.map { it.symbol })
        assertEquals(total, w.env.wallet.current.totalText)
    }

    @Test
    fun aPartialAnswerStillUpdatesTheSymbolsItHolds() = runTest {
        val w = loaded()
        w.http["/wallet-api/portfolio-v2"] = { HttpResult(200, pointsOnly(balance = "1501.25", pending = "0")) }
        w.env.wallet.refresh()
        testScheduler.advanceUntilIdle()
        val s = w.env.wallet.current
        assertEquals(all, s.rows.map { it.symbol })
        assertEquals("1,501.250", s.rows.first { it.symbol == "POINTS" }.balanceText)
        assertEquals("950.000", s.rows.first { it.symbol == "HP" }.balanceText)
    }

    @Test
    fun aFailedAccountReadKeepsTheProfileTokenRows() = runTest {
        val w = loaded()
        w.rpc["condenser_api.get_accounts"] = { throw IllegalStateException("node down") }
        w.env.wallet.refresh()
        testScheduler.advanceUntilIdle()
        assertEquals(all, w.env.wallet.current.rows.map { it.symbol })
    }

    @Test
    fun quickClaimsWithFullPartialFullAnswersNeverDropARow() = runTest {
        val w = loaded()
        var call = 0
        w.http["/wallet-api/portfolio-v2"] = { HttpResult(200, if (call++ % 2 == 0) pointsOnly() else WalletWorld.PORTFOLIO) }
        val seen = mutableListOf<WalletState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { w.env.wallet.state.collect { seen += it } }
        w.env.wallet.claim("POINTS")
        testScheduler.advanceTimeBy(300)
        w.env.wallet.claim("HIVE")
        testScheduler.advanceTimeBy(300)
        w.env.wallet.claim("HP")
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertTrue(call >= 3, "the claims read the portfolio again ($call reads)")
        val dropped = seen.filter { it.rows.map { r -> r.symbol } != all }
        assertTrue(dropped.isEmpty(), "rows dropped: ${dropped.map { s -> s.rows.map { it.symbol } }}")
        assertTrue(seen.none { it.totalText == "\$3.00" }, "the total never counts points alone")
    }

    @Test
    fun aReloadThatFailsOrAnswersEmptyLeavesTheRows() = runTest {
        val w = loaded()
        val total = w.env.wallet.current.totalText
        for (answer in listOf(HttpResult(500, "oops"), HttpResult(200, """{"wallets":[]}"""), HttpResult(200, """{"wallets":[{"symbol":"X","layer":"unknown"}]}"""))) {
            w.http["/wallet-api/portfolio-v2"] = { answer }
            w.env.wallet.refresh()
            testScheduler.advanceUntilIdle()
            assertEquals(all, w.env.wallet.current.rows.map { it.symbol }, "after $answer")
            assertEquals(total, w.env.wallet.current.totalText)
            w.env.wallet.retry()
            testScheduler.advanceUntilIdle()
            assertEquals(all, w.env.wallet.current.rows.map { it.symbol }, "after a retry answered $answer")
        }
    }

    /** A deliberate removal on the manage assets screen still removes the row, partial answers or not. */
    @Test
    fun aSelectionChangeStillRemovesARow() = runTest {
        val w = loaded()
        w.http["/wallet-api/portfolio-v2"] = { HttpResult(200, pointsOnly()) }
        w.env.wallet.refresh()
        testScheduler.advanceUntilIdle()
        w.env.wallet.setSelection(emptyList())
        assertEquals(WalletModel.DEFAULT_ASSETS, w.env.wallet.current.rows.map { it.symbol })
    }

    /** A token the account really no longer has goes once a full load (not a reload after an action) answers without it. */
    @Test
    fun aFullLoadReplacesThePortfolio() = runTest {
        val w = loaded()
        w.http["/wallet-api/portfolio-v2"] = { HttpResult(200, WalletWorld.PORTFOLIO.replace(""""symbol":"LEO","layer":"engine"""", """"symbol":"LEO","layer":"nope"""")) }
        w.env.wallet.retry()
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("POINTS", "HP", "HIVE", "HBD", "BNB"), w.env.wallet.current.rows.map { it.symbol })
    }

    @Test
    fun anAccountSwitchResetsAndTheOldAccountsAnswerIsDropped() = runTest {
        val w = loaded()
        val bob = "spike-bob"
        KeyVault(w.app.store).save(VaultAccount(bob, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(bob, KeyRole.POSTING), accessToken = "access-$bob"))
        w.http["/wallet-api/portfolio-v2"] = { r -> HttpResult(200, if (r.body.orEmpty().contains(bob)) pointsOnly(balance = "7") else WalletWorld.PORTFOLIO) }
        // the old account's reload is held in flight while the switch happens
        val held = CompletableDeferred<Unit>()
        w.gates["condenser_api.get_accounts"] = held
        w.env.wallet.refresh()
        testScheduler.runCurrent()
        w.env.session.switchTo(bob)
        testScheduler.runCurrent()
        held.complete(Unit)
        testScheduler.advanceUntilIdle()
        repeat(2) {
            launch(w.app.main) { yield() }
            testScheduler.advanceUntilIdle()
        }
        val s = w.env.wallet.current
        assertEquals(bob, s.account)
        assertEquals(listOf("POINTS"), s.rows.map { it.symbol })
        assertEquals("7.000", s.rows.single().balanceText)
        assertEquals(listOf("POINTS"), s.all.map { it.symbol })
    }
}
