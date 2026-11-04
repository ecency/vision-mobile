package com.ecency.core.app

import com.ecency.core.api.broadcast.BroadcastException
import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.api.broadcast.WalletOps
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.hive.KeyRole
import com.ecency.core.render.url.WebUrl
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The fixes of the wallet review: one amount reading, sends outliving screens, links through WalletSends, signer roles. */
class WalletReviewFixesTest {
    private fun world(scope: TestScope) = WalletWorld(scope, tokensJson = """[{"symbol":"LEO","type":"ENGINE","meta":{"show":true}}]""")

    private suspend fun TestScope.transfer(w: WalletWorld, screen: AppScreen.Transfer): TransferModel {
        w.env.wallet.start()
        val m = w.env.transferModel(screen)!!
        m.start()
        testScheduler.advanceUntilIdle()
        return m
    }

    // ---- H1: one amount reading ----

    @Test
    fun typedDotFiveIsSignedAsHalfNotZero() = runTest {
        val w = world(this)
        val m = transfer(w, AppScreen.Transfer("transfer", "HIVE", "hive"))
        m.setRecipient("spike-bob")
        m.setAmount(".5")
        testScheduler.advanceUntilIdle()
        m.next(); m.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals("""["transfer",{"from":"spike-voter","to":"spike-bob","amount":"0.500 HIVE","memo":""}]""", w.broadcastOpsJson().single())
    }

    @Test
    fun aRecurrentDotFiveIsNeverTheZeroThatDeletesASchedule() = runTest {
        val w = world(this)
        w.rpc["condenser_api.find_recurrent_transfers"] = { "[]" }
        val m = transfer(w, AppScreen.Transfer("transfer", "HIVE", "hive"))
        m.setRecurrence(Recurrence.DAILY.hours)
        m.setRecipient("spike-bob")
        m.setAmount(".5")
        m.setExecutions("3")
        testScheduler.advanceUntilIdle()
        m.next(); m.confirm()
        testScheduler.advanceUntilIdle()
        assertTrue(w.broadcastOpsJson().single().contains(""""amount":"0.500 HIVE""""), w.broadcastOpsJson().single())
    }

    @Test
    fun aTipOfDotFiveIsHalf() = runTest {
        val w = world(this)
        val m = w.env.tipModel("spike-author", "a-post")
        m.start()
        testScheduler.advanceUntilIdle()
        m.setAmount(".5")
        m.send(); m.confirm()
        testScheduler.advanceUntilIdle()
        assertTrue(w.broadcastOpsJson().single().contains(""""amount":"0.500 HIVE""""))
    }

    @Test
    fun theAmountReadingIsOneForChecksAndSigning() {
        val a = com.ecency.core.api.broadcast.Amounts
        val d = com.ecency.core.api.broadcast.Dec
        assertEquals("0.500", a.fixed(".5", 3))
        assertEquals("0.5", d.of(".5").toString())
        listOf("", ".", "1e3", "1.2.3", " ").forEach { bad ->
            assertNull(d.of(bad), bad)
            assertEquals("0.000", a.fixed(bad, 3), bad)
        }
    }

    @Test
    fun hpFieldsTakeOnlyAmountText() = runTest {
        val w = world(this)
        w.rpc["condenser_api.get_vesting_delegations"] = { "[]" }
        val m = w.env.delegateModel("spike-bob")
        m.start()
        testScheduler.advanceUntilIdle()
        m.setHp("1")
        m.setHp("1e3") // not an amount: the field keeps what it had
        assertEquals("1", m.current.hp)
        m.setHp(".5")
        assertTrue(m.current.amountValid)
    }

    // ---- M2: a send outlives its screen ----

    @Test
    fun aSendWhoseScreenClosedToastsItsOutcomeAndIsForgotten() = runTest {
        val w = world(this)
        val gate = CompletableDeferred<Unit>()
        w.broadcastAnswer = { gate.await(); com.ecency.core.api.FakeTransport.result(it, "{}") }
        val m = transfer(w, AppScreen.Transfer("transfer", "HIVE", "hive"))
        m.setRecipient("spike-bob"); m.setAmount("1")
        testScheduler.advanceUntilIdle()
        m.next(); m.confirm()
        testScheduler.runCurrent()
        val id = m.current.actionId!!
        m.close() // the user left the screen while the send runs
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(1, w.app.broadcasts().size)
        assertEquals(WalletCopy.SUCCESSFUL, w.env.ui.current.toast?.text)
        assertNull(w.env.walletSends.phase(id), "released once reported")
    }

    // ---- M3: links through WalletSends ----

    @Test
    fun aLinkSendThatTheChainCannotDecideAnswersUnconfirmed() = runTest {
        val w = world(this)
        // the device clock moves with the virtual time, so the failover window closes as it would
        w.app.clock = { TestApp.NOW + testScheduler.currentTime }
        w.broadcastAnswer = { kotlinx.coroutines.awaitCancellation() }
        w.statusAnswer = { "\"too_old\"" }
        val op = com.ecency.core.auth.HiveUri.ops("transfer" to kotlinx.serialization.json.buildJsonObject {
            put("from", kotlinx.serialization.json.JsonPrimitive("__signer")); put("to", kotlinx.serialization.json.JsonPrimitive("spike-bob"))
            put("amount", kotlinx.serialization.json.JsonPrimitive("1.000 HIVE")); put("memo", kotlinx.serialization.json.JsonPrimitive(""))
        })
        w.env.authLinks.handle(com.ecency.core.auth.HiveUri.encodeOps(op, com.ecency.core.auth.HiveUri.Params(callback = "otherapp://done")))
        testScheduler.advanceUntilIdle()
        assertIs<LinkPrompt.SignConfirm>(w.env.authLinks.current.prompt)
        w.env.authLinks.confirm()
        testScheduler.advanceUntilIdle()
        val callback = w.env.authLinks.current.callback!!
        assertEquals("unconfirmed", WebUrl.parse(callback)!!.searchParams.toMap()["error"])
        val signatures = w.app.broadcasts().map { (it.params as JsonArray)[0].jsonObject["signatures"].toString() }.toSet()
        assertEquals(1, signatures.size, "one signed transaction")
        assertTrue(w.app.broadcasts().size < 200, "resends of the same transaction only, bounded: ${w.app.broadcasts().size}")
        assertTrue(w.env.walletSends.current.sends.isEmpty())
    }

    // ---- M4: single-recipient kinds ----

    @Test
    fun aSingleRecipientKindRefusesSeveralNames() = runTest {
        val w = world(this)
        val m = transfer(w, AppScreen.Transfer("transfer_to_savings", "HIVE", "hive"))
        m.setRecipient("spike-bob spike-carol")
        m.setAmount("1")
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.tooManyRecipients)
        assertFalse(m.current.nextEnabled)
        val t = transfer(w, AppScreen.Transfer("transfer", "HIVE", "hive"))
        t.setRecipient("spike-bob spike-carol")
        t.setAmount("1")
        testScheduler.advanceUntilIdle()
        assertTrue(t.current.nextEnabled)
        t.setRecurrence(Recurrence.WEEKLY.hours) // a recurrent transfer has one recipient
        t.setExecutions("3")
        assertFalse(t.current.nextEnabled)
    }

    // ---- M5: the right key signs ----

    @Test
    fun valueOperationsAreSignedWithTheActiveKeyAndClaimsWithThePostingKey() = runTest {
        val w = world(this)
        val m = transfer(w, AppScreen.Transfer("transfer", "HIVE", "hive"))
        m.setRecipient("spike-bob"); m.setAmount("1")
        testScheduler.advanceUntilIdle()
        m.next(); m.confirm()
        testScheduler.advanceUntilIdle()
        w.env.wallet.claim("HP")
        testScheduler.advanceUntilIdle()
        assertEquals(listOf<KeyRole?>(KeyRole.ACTIVE, KeyRole.POSTING), w.signerRoles())
    }

    // ---- L9: signers elsewhere ----

    @Test
    fun aHiveSignerFailureAfterTheRequestLeftSaysCheckYourWalletNotTryAgain() = runTest {
        val w = world(this)
        KeyVault(w.app.store).save(VaultAccount(w.user, AuthType.HIVE_SIGNER, accessToken = "access-${w.user}"))
        w.app.authBackend.down = true
        val ops = WalletOps.ops(listOf(WalletOps.claimRewardBalance(w.user, "0.000 HIVE", "0.000 HBD", "1.000000 VESTS")))
        val done = CompletableDeferred<WalletSend>()
        w.env.walletSends.send(w.user, ops, KeyRole.POSTING, "a1") { done.complete(it) }
        testScheduler.advanceUntilIdle()
        val failed = assertIs<SendPhase.Failed>(done.await().phase)
        assertEquals(BroadcastException.UNDECIDED_MESSAGE, failed.error.message)
        assertFalse(failed.retryable)
    }

    // ---- L11: the journal's cap ----

    @Test
    fun aJournalFullOfUndecidedSendsRefusesNewOnesAndDropsDecidedFirst() = runTest {
        val w = world(this)
        val rows = (1..SendJournal.MAX_ENTRIES).joinToString(",") { i ->
            """{"account":"spike-voter","actionId":"u$i","txId":"${i.toString().padStart(2, '0').repeat(20)}","expiration":"2026-10-06T11:59:00","operation":"transfer"}"""
        }
        w.app.store.putString(SendJournal.ENTRY, "[$rows]")
        w.env.sendJournal.start()
        testScheduler.runCurrent()
        val done = CompletableDeferred<WalletSend>()
        w.env.walletSends.send(w.user, WalletOps.ops(listOf(WalletOps.transfer(w.user, "spike-bob", "1.000 HIVE", ""))), KeyRole.ACTIVE, "new") { done.complete(it) }
        testScheduler.runCurrent()
        assertEquals(WalletSends.JOURNAL_FULL, assertIs<SendPhase.Failed>(done.await().phase).error.message)
        assertTrue(w.app.broadcasts().isEmpty())
    }

    @Test
    fun recordingOverTheCapDropsDecidedEntriesNeverUndecidedOnes() = runTest {
        val w = world(this)
        val decided = """{"account":"spike-voter","actionId":"d1","txId":"${"dd".repeat(20)}","expiration":"2026-10-06T11:00:00","operation":"transfer","outcome":"WENT_THROUGH"}"""
        val undecided = (2..SendJournal.MAX_ENTRIES).joinToString(",") { i ->
            """{"account":"spike-voter","actionId":"u$i","txId":"${i.toString().padStart(2, '0').repeat(20)}","expiration":"2026-10-06T11:59:00","operation":"transfer"}"""
        }
        // the decided entry last: a cap that cut the oldest rows would drop an undecided one instead
        w.app.store.putString(SendJournal.ENTRY, "[$undecided,$decided]")
        w.env.sendJournal.record("spike-voter", "new", "transfer", "ee".repeat(20), "2026-10-06T12:01:00")
        val ids = Json.parseToJsonElement(w.app.store.getString(SendJournal.ENTRY)!!).let { it as JsonArray }.map { it.jsonObject["actionId"]!!.jsonPrimitive.content }
        assertFalse("d1" in ids)
        assertTrue("u2" in ids && "new" in ids)
        assertEquals(SendJournal.MAX_ENTRIES, ids.size)
    }

    // ---- L12: an account that leaves stops signing ----

    @Test
    fun aLogoutStopsTheAccountsUndecidedSend() = runTest {
        val w = world(this)
        w.broadcastAnswer = { kotlinx.coroutines.awaitCancellation() }
        val m = transfer(w, AppScreen.Transfer("transfer", "HIVE", "hive"))
        m.setRecipient("spike-bob"); m.setAmount("1")
        testScheduler.advanceUntilIdle()
        m.next(); m.confirm()
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertIs<SendPhase.Checking>(m.current.phase)
        w.env.session.logout(w.user)
        testScheduler.runCurrent()
        val after = w.app.transport.sent.size
        testScheduler.advanceTimeBy(10 * 60_000)
        testScheduler.runCurrent()
        assertTrue(w.env.walletSends.current.sends.isEmpty())
        assertEquals(after, w.app.transport.sent.size, "nothing is asked or sent for an account that left")
    }

    // ---- L6, L7, L13, L14 ----

    @Test
    fun powerDownMaxSendsTheExactVests() = runTest {
        val w = world(this)
        w.rpc["condenser_api.get_withdraw_routes"] = { "[]" }
        w.rpc["condenser_api.get_accounts"] = { """[{"name":"spike-voter","vesting_shares":"1000.123457 VESTS","delegated_vesting_shares":"0.000000 VESTS","next_vesting_withdrawal":"1969-12-31T23:59:59"}]""" }
        val m = w.env.powerDownModel(routesOnly = false)
        m.start()
        testScheduler.advanceUntilIdle()
        m.max()
        m.next()
        assertEquals(StakePrompt.ConfirmPowerDown("1000.123457"), m.current.prompt)
    }

    @Test
    fun aSwapIsNeverFillOrKill() = runTest {
        val w = world(this)
        w.rpc["condenser_api.get_order_book"] = { """{"bids":[{"hbd":2500,"hive":10000,"real_price":"0.25"}],"asks":[{"hbd":2600,"hive":10000,"real_price":"0.26"}]}""" }
        w.rpc["condenser_api.get_ticker"] = { """{"lowest_ask":"0.26"}""" }
        w.rpc["condenser_api.get_open_orders"] = { "[]" }
        w.env.wallet.start()
        val m = w.env.tradeModel("HIVE")
        // the trade screen refreshes the order book on a timer until closed: a failed assertion must still close it, or runTest's
        // cleanup runs that timer forever (the transport log grows until the test JVM runs out of memory)
        try {
            m.start()
            testScheduler.advanceTimeBy(1_000)
            m.setAmount("4")
            m.next(); m.confirm()
            testScheduler.advanceTimeBy(3_000)
            testScheduler.runCurrent()
            // the order as built and sent, read from the one broadcast
            val order = w.broadcastOps().single()
            assertEquals("limit_order_create", order[0].jsonPrimitive.content)
            assertEquals("false", order[1].jsonObject["fill_or_kill"]!!.jsonPrimitive.content)
        } finally {
            m.close()
        }
    }

    @Test
    fun linkDecodingWantsJsonNumbersForPercentsAndSchedules() {
        fun op(text: String) = Json.parseToJsonElement(text) as JsonArray
        assertFailsWith<BroadcastException.InvalidOperation> {
            OperationJson.decode(op("""["set_withdraw_vesting_route",{"from_account":"a","to_account":"b","percent":"2500","auto_vest":false}]"""))
        }
        assertFailsWith<BroadcastException.InvalidOperation> {
            OperationJson.decode(op("""["recurrent_transfer",{"from":"a","to":"b","amount":"1.000 HIVE","memo":"","recurrence":"24","executions":2,"extensions":[]}]"""))
        }
        OperationJson.decode(op("""["set_withdraw_vesting_route",{"from_account":"a","to_account":"b","percent":2500,"auto_vest":false}]"""))
    }

    @Test
    fun aRouteChangeTheChainCannotConfirmIsNotCalledFailed() = runTest {
        val w = world(this)
        w.rpc["condenser_api.get_withdraw_routes"] = { "[]" }
        w.broadcastAnswer = { kotlinx.coroutines.awaitCancellation() }
        w.statusAnswer = { "\"too_old\"" }
        val m = w.env.powerDownModel(routesOnly = true)
        m.start()
        testScheduler.advanceUntilIdle()
        m.addRoute("spike-bob", 10, false)
        testScheduler.advanceUntilIdle()
        val alert = assertIs<StakePrompt.Alert>(m.current.prompt)
        assertEquals(StakeCopy.WITHDRAW_ACCOUNTS, alert.title)
        assertEquals(BroadcastException.UNCONFIRMED_MESSAGE, alert.message)
    }

    // ---- L8 ----

    @Test
    fun aTransferLinkToAnInvalidNameIsRefused() = runTest {
        val w = world(this)
        w.env.authLinks.handle("ecency://transfer?to=Not..A..Name")
        testScheduler.advanceUntilIdle()
        assertNull(w.env.authLinks.current.navigation)
        assertIs<LinkPrompt.Refused>(w.env.authLinks.current.prompt)
    }
}
