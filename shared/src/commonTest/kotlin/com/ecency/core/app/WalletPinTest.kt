package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.PinLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RN asks for the PIN before a wallet operation when the app lock is on (assetDetailsScreen, walletContainer,
 * pointsContainer, useLinkProcessor: the PINCODE screen first). Native asks once the user confirmed, before signing.
 */
class WalletPinTest {
    private fun TestScope.world(pin: Boolean): WalletWorld {
        val w = WalletWorld(this)
        if (pin) PinLock(KeyVault(w.app.store), 16).setPin("1234")
        w.env.appLock.start()
        testScheduler.advanceUntilIdle()
        if (pin) type(w, "1234") // the cold start lock
        assertNull(w.env.appLock.current.prompt)
        return w
    }

    private fun TestScope.type(w: WalletWorld, pin: String) {
        pin.forEach { w.env.appLock.enterDigit(it.digitToInt()) }
        testScheduler.advanceUntilIdle()
    }

    private fun TestScope.asksPin(w: WalletWorld) {
        testScheduler.advanceUntilIdle()
        assertEquals(PinPurpose.VERIFY, w.env.appLock.current.prompt?.purpose, "the PIN is asked before signing")
        assertTrue(w.app.broadcasts().isEmpty(), "nothing signed before the PIN")
    }

    private suspend fun TestScope.transfer(w: WalletWorld, screen: AppScreen.Transfer, to: String = "spike-bob", amount: String = "1"): TransferModel {
        w.env.wallet.start()
        val m = w.env.transferModel(screen)!!
        m.start()
        testScheduler.advanceUntilIdle()
        m.setRecipient(to)
        m.setAmount(amount)
        testScheduler.advanceUntilIdle()
        m.next()
        assertEquals(TransferPrompt.Confirm, m.current.prompt)
        m.confirm()
        return m
    }

    @Test
    fun aTransferSignsOnlyAfterThePin() = runTest {
        val w = world(pin = true)
        val m = transfer(w, AppScreen.Transfer("transfer", "HIVE", "hive"))
        asksPin(w)
        type(w, "1234")
        assertEquals(1, w.app.broadcasts().size)
        assertIs<SendPhase.Done>(m.current.phase)
        assertNull(w.env.appLock.current.prompt)
    }

    @Test
    fun cancellingThePinSignsNothingAndKeepsTheForm() = runTest {
        val w = world(pin = true)
        val m = transfer(w, AppScreen.Transfer("transfer", "HIVE", "hive"))
        asksPin(w)
        w.env.appLock.cancel()
        testScheduler.advanceUntilIdle()
        assertTrue(w.app.broadcasts().isEmpty())
        assertNull(m.current.phase)
        assertFalse(m.current.closed)
        assertEquals("spike-bob", m.current.recipients.single())
        // confirming again asks again
        m.next()
        m.confirm()
        asksPin(w)
        type(w, "1234")
        assertEquals(1, w.app.broadcasts().size)
    }

    @Test
    fun aWrongPinSignsNothingAndCountsAsAFailedAttempt() = runTest {
        val w = world(pin = true)
        transfer(w, AppScreen.Transfer("transfer", "HIVE", "hive"))
        asksPin(w)
        type(w, "9999")
        assertEquals(1, w.env.appLock.current.prompt?.failedAttempts)
        assertEquals(PinPurpose.VERIFY, w.env.appLock.current.prompt?.purpose)
        assertTrue(w.app.broadcasts().isEmpty())
    }

    @Test
    fun aPowerDownSignsOnlyAfterThePin() = runTest {
        val w = world(pin = true)
        w.rpc["condenser_api.get_withdraw_routes"] = { "[]" }
        val m = w.env.powerDownModel(routesOnly = false)
        m.start()
        testScheduler.advanceUntilIdle()
        m.setHp("130")
        m.next()
        m.confirm()
        asksPin(w)
        type(w, "1234")
        assertEquals(listOf("""["withdraw_vesting",{"account":"spike-voter","vesting_shares":"260000.000000 VESTS"}]"""), w.broadcastOpsJson())
    }

    @Test
    fun aPointsTransferSignsOnlyAfterThePin() = runTest {
        val w = world(pin = true)
        w.http["/private-api/points"] = { HttpResult(200, """{"points":"1,500.500","unclaimed_points":"0.750"}""") }
        transfer(w, AppScreen.Transfer("ecency_point_transfer", "POINT", "points"), amount = "10")
        asksPin(w)
        type(w, "1234")
        assertEquals(1, w.app.broadcasts().size)
    }

    @Test
    fun withTheLockOffNothingIsAsked() = runTest {
        val w = world(pin = false)
        val m = transfer(w, AppScreen.Transfer("transfer", "HIVE", "hive"))
        testScheduler.advanceUntilIdle()
        assertNull(w.env.appLock.current.prompt)
        assertEquals(1, w.app.broadcasts().size)
        assertIs<SendPhase.Done>(m.current.phase)
    }

    @Test
    fun aRewardClaimIsNotAskedAsInRn() = runTest {
        val w = world(pin = true)
        w.env.wallet.start()
        testScheduler.advanceUntilIdle()
        w.env.wallet.claim("HIVE")
        testScheduler.advanceUntilIdle()
        assertNull(w.env.appLock.current.prompt)
        assertTrue(w.broadcastOpsJson().single().startsWith("""["claim_reward_balance""""))
    }
}
