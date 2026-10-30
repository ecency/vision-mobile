package com.ecency.core.app

import com.ecency.core.api.broadcast.Amounts
import com.ecency.core.api.broadcast.BroadcastException
import com.ecency.core.api.broadcast.Dec
import com.ecency.core.api.broadcast.WalletOps
import com.ecency.core.auth.CryptoJsCipher
import com.ecency.core.hive.KeyRole
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The delta review of the wallet: one report per outcome, signers elsewhere, ASCII digits, the lookup backoff. */
class WalletDeltaFixesTest {
    private fun transferOps(w: WalletWorld) = WalletOps.ops(listOf(WalletOps.transfer(w.user, "spike-bob", "1.000 HIVE", "")))

    // ---- (a) the outcome and who reports it are one step ----

    @Test
    fun aScreenThatHandlesTheOutcomeAndLeavesGetsNoToastAndOneThatLeftGetsOne() = runTest {
        val w = WalletWorld(this)
        val gate = CompletableDeferred<Unit>()
        w.broadcastAnswer = { gate.await(); com.ecency.core.api.FakeTransport.result(it, "{}") }
        w.env.walletSends.send(w.user, transferOps(w), KeyRole.ACTIVE, "a1")
        // the screen: shows the outcome when it comes, then goes away
        launch(w.app.main) {
            w.env.walletSends.state.first { it.sends["a1"]?.isFinal == true }
            w.env.walletSends.disown("a1")
        }
        testScheduler.runCurrent()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertNull(w.env.ui.current.toast, "the screen showed it")
        assertNull(w.env.walletSends.phase("a1"))
    }

    // ---- (b) signers elsewhere: may have gone through vs definite ----

    @Test
    fun aHiveSignerPageClosedOnAValueSendMayHaveGoneThrough() = runTest {
        val w = WalletWorld(this)
        KeyVault(w.app.store).save(VaultAccount(w.user, AuthType.HIVE_SIGNER, accessToken = "access-${w.user}"))
        val done = CompletableDeferred<WalletSend>()
        w.env.walletSends.send(w.user, transferOps(w), KeyRole.ACTIVE, "a1") { done.complete(it) }
        testScheduler.advanceUntilIdle()
        assertIs<SigningPrompt.HiveSignerHot>(w.env.signing.current.prompt)
        w.env.signing.cancel() // the user closes HiveSigner's page: it may have broadcast before
        testScheduler.advanceUntilIdle()
        val failed = assertIs<SendPhase.Failed>(done.await().phase)
        assertEquals(BroadcastException.UNDECIDED_MESSAGE, failed.error.message)
        assertFalse(failed.retryable)
    }

    @Test
    fun aDeclinedAuthUpgradeIsStillJustCancelled() = runTest {
        val w = WalletWorld(this)
        KeyVault(w.app.store).save(VaultAccount(w.user, AuthType.POSTING_KEY, postingKey = AppTestKeys.wif(w.user, KeyRole.POSTING)))
        val done = CompletableDeferred<WalletSend>()
        w.env.walletSends.send(w.user, transferOps(w), KeyRole.ACTIVE, "a1") { done.complete(it) }
        testScheduler.advanceUntilIdle()
        assertIs<SigningPrompt.AuthUpgrade>(w.env.signing.current.prompt)
        w.env.signing.cancel() // nothing left the app
        testScheduler.advanceUntilIdle()
        assertEquals(SendPhase.Cancelled, done.await().phase)
    }

    private fun hiveAuthWorld(scope: TestScope): WalletWorld {
        val w = WalletWorld(scope)
        KeyVault(w.app.store).save(
            VaultAccount(w.user, AuthType.HIVE_AUTH, hiveAuthKey = HAS_KEY, hiveAuthToken = "has-token", hiveAuthExpiry = TestApp.NOW + 60_000_000, accessToken = "access-${w.user}"),
        )
        w.app.relay.autoApproveSign = false
        w.app.relay.waitExpire = TestApp.NOW + 30_000
        return w
    }

    @Test
    fun aHiveAuthRequestThatExpiredMayHaveBeenSigned() = runTest {
        val w = hiveAuthWorld(this)
        val done = CompletableDeferred<WalletSend>()
        w.env.walletSends.send(w.user, transferOps(w), KeyRole.ACTIVE, "a1") { done.complete(it) }
        testScheduler.advanceUntilIdle() // the wallet never answers before the request expires
        val failed = assertIs<SendPhase.Failed>(done.await().phase)
        assertEquals(BroadcastException.UNDECIDED_MESSAGE, failed.error.message)
        assertFalse(failed.retryable)
    }

    @Test
    fun aHiveAuthRejectionIsADefiniteFailure() = runTest {
        val w = hiveAuthWorld(this)
        val done = CompletableDeferred<WalletSend>()
        w.env.walletSends.send(w.user, transferOps(w), KeyRole.ACTIVE, "a1") { done.complete(it) }
        repeat(5) { testScheduler.runCurrent() }
        assertIs<SigningPrompt.HiveAuthBroadcast>(w.env.signing.current.prompt)
        w.app.relay.push(buildJsonObject { put("cmd", "sign_nack"); put("uuid", "sign-1"); put("data", CryptoJsCipher.encrypt("sign-1", HAS_KEY)) })
        testScheduler.advanceUntilIdle()
        val failed = assertIs<SendPhase.Failed>(done.await().phase)
        assertTrue(failed.error.message != BroadcastException.UNDECIDED_MESSAGE, failed.error.message)
    }

    @Test
    fun aHiveAuthSignatureThatWasNotBroadcastIsAFailureNotASuccess() = runTest {
        val w = hiveAuthWorld(this)
        val done = CompletableDeferred<WalletSend>()
        w.env.walletSends.send(w.user, transferOps(w), KeyRole.ACTIVE, "a1") { done.complete(it) }
        repeat(5) { testScheduler.runCurrent() }
        assertIs<SigningPrompt.HiveAuthBroadcast>(w.env.signing.current.prompt)
        // the wallet signed but did not broadcast: nothing is on chain
        w.app.relay.push(buildJsonObject { put("cmd", "sign_ack"); put("uuid", "sign-1"); put("broadcast", false); put("data", "signed-only") })
        testScheduler.advanceUntilIdle()
        val failed = assertIs<SendPhase.Failed>(done.await().phase)
        // RN useHiveAuth: hiveauth.transaction_fail
        assertEquals("Transaction Failed", failed.error.message)
    }

    // ---- (c) amounts are ASCII digits on every platform ----

    @Test
    fun otherScriptsDigitsAreNotAmounts() {
        // Arabic-Indic, Extended Arabic-Indic, Devanagari and fullwidth digits; a mix with ASCII too
        listOf("٥", "١.٥", "۵", "५", "５", "１.５", "1٥", ".５").forEach { text ->
            assertNull(Dec.of(text), text)
            assertEquals("0.000", Amounts.fixed(text, 3), text)
            assertNull(Amounts.normalize(text), text)
        }
        assertEquals("5", Dec.of("5").toString())
    }

    @Test
    fun typedFieldsKeepAsciiDigitsAndReadTheLanguagesDigitsAsThem() = runTest {
        val w = WalletWorld(this)
        w.rpc["condenser_api.find_recurrent_transfers"] = { "[]" }
        w.env.wallet.start()
        val t = w.env.transferModel(AppScreen.Transfer("transfer", "HIVE", "hive"))!!
        t.start()
        testScheduler.advanceUntilIdle()
        t.setExecutions("٣5x")
        assertEquals("35", t.current.executions)
        val tip = w.env.tipModel("spike-author", "a-post")
        tip.start()
        testScheduler.advanceUntilIdle()
        tip.setAmount("1")
        tip.setAmount("٥")
        assertEquals("5", tip.current.amount)
        tip.setAmount("x")
        assertEquals("5", tip.current.amount)
    }

    // ---- (e) the lookup backoff ----

    @Test
    fun lookupsBackOffToThirtySeconds() {
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 30_000L, 30_000L, 30_000L), (0..5).map(WalletSends::lookupDelay))
    }

    @Test
    fun anUndecidedSendResendsEveryFiveSecondsThenBacksOffItsLookups() = runTest {
        val w = WalletWorld(this)
        // the device clock follows the virtual time from the real time (the broadcaster signs on the real clock)
        val base = kotlin.time.Clock.System.now().toEpochMilliseconds()
        w.app.clock = { base + testScheduler.currentTime }
        // every node drops the connection after the request went out: it may have landed
        val rounds = mutableListOf<Long>()
        w.broadcastAnswer = {
            if (rounds.lastOrNull() != testScheduler.currentTime) rounds += testScheduler.currentTime
            throw IllegalStateException("connection reset by peer")
        }
        w.env.walletSends.send(w.user, transferOps(w), KeyRole.ACTIVE, "a1")
        testScheduler.advanceTimeBy(5 * 60_000)
        testScheduler.runCurrent()
        assertIs<SendPhase.Checking>(w.env.walletSends.phase("a1"))
        val gaps = rounds.zipWithNext { a, b -> b - a }
        // inside the resend window (about 40 s: expiration minus timeout minus 5 s) every 5 s, then 5, 10, 20, 30, 30...
        val firstLonger = gaps.indexOfFirst { it > 5_000 }
        assertTrue(firstLonger in 7..10, "resends every 5 s for about 40 s: $gaps")
        assertEquals(listOf(10_000L, 20_000L, 30_000L, 30_000L), gaps.subList(firstLonger, firstLonger + 4), "$gaps")
        assertTrue(gaps.drop(firstLonger + 2).all { it == 30_000L }, "capped at 30 s: $gaps")
    }

    @Test
    fun theJournalBacksOffItsLookupsAndSkipsTheHeadForUnknown() = runTest {
        val w = WalletWorld(this)
        w.app.store.putString(
            SendJournal.ENTRY,
            """[{"account":"spike-voter","actionId":"a1","txId":"${"ab".repeat(20)}","expiration":"2026-10-06T11:45:00","operation":"transfer"}]""",
        )
        val lookups = mutableListOf<Long>()
        w.statusAnswer = { lookups += testScheduler.currentTime; "\"unknown\"" }
        val heads = { w.app.transport.sent.count { it.method == "condenser_api.get_dynamic_global_properties" } }
        val before = heads()
        w.env.sendJournal.start()
        testScheduler.advanceTimeBy(2 * 60_000)
        testScheduler.runCurrent()
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 30_000L, 30_000L), lookups.zipWithNext { a, b -> b - a }.take(5), "$lookups")
        assertEquals(before, heads(), "an unknown answer needs no head read")
        assertNull(w.env.sendJournal.current.entries.single().outcome)
    }

    @Test
    fun anUnknownAnswerADayPastTheExpirationClosesTheEntryAsUnconfirmed() = runTest {
        val w = WalletWorld(this)
        val expiration = "2026-10-06T11:45:00"
        val expires = com.ecency.core.hive.ChainTime.toEpochSeconds(expiration) * 1000
        w.app.store.putString(
            SendJournal.ENTRY,
            """[{"account":"spike-voter","actionId":"a1","txId":"${"ab".repeat(20)}","expiration":"$expiration","operation":"transfer"}]""",
        )
        w.statusAnswer = { "\"unknown\"" }
        // exactly a day after: still checking
        w.app.now = expires + SendJournal.DEVICE_AGE_OUT_MILLIS
        w.env.sendJournal.start()
        testScheduler.advanceUntilIdle()
        assertNull(w.env.sendJournal.current.entries.single().outcome)
        // past it: could not confirm, and nothing was signed or sent
        w.app.now = expires + SendJournal.DEVICE_AGE_OUT_MILLIS + 1
        w.env.sendJournal.recheck()
        testScheduler.advanceUntilIdle()
        val entry = w.env.sendJournal.current.entries.single()
        assertEquals(JournalOutcome.UNCONFIRMED, entry.outcome)
        assertTrue(entry.message.startsWith("Could not confirm"), entry.message)
        assertTrue(w.app.broadcasts().isEmpty())
    }

    companion object {
        private const val HAS_KEY = "3f0c2e9a-1b7d-4c55-9e2a-6d8f0b1c2a3e"
    }
}
