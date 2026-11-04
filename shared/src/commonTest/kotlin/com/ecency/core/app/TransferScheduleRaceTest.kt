package com.ecency.core.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A recipient's existing schedule fills the form only while the form still asks for it. */
class TransferScheduleRaceTest {
    private val schedulesJson =
        """[{"id":4,"trigger_date":"2026-10-07T00:00:00","from":"spike-voter","to":"spike-bob","amount":"1.500 HIVE","memo":"bob memo","recurrence":168,"consecutive_failures":0,"remaining_executions":5}]"""

    private suspend fun TestScope.heldSchedule(): Triple<WalletWorld, TransferModel, CompletableDeferred<Unit>> {
        val w = WalletWorld(this)
        w.rpc["condenser_api.find_recurrent_transfers"] = { schedulesJson }
        w.env.wallet.start()
        val m = w.env.transferModel(AppScreen.Transfer("transfer", "HIVE", "hive"))!!
        m.start()
        testScheduler.advanceUntilIdle()
        m.setRecipient("spike-bob")
        testScheduler.advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        w.gates["condenser_api.find_recurrent_transfers"] = gate
        m.setRecurrence(Recurrence.DAILY.hours)
        testScheduler.runCurrent() // the schedules read is in flight
        return Triple(w, m, gate)
    }

    @Test
    fun theRecipientsScheduleFillsTheForm() = runTest {
        val (_, m, gate) = heldSchedule()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals("1.500", m.current.amount)
        assertEquals("bob memo", m.current.memo)
        assertEquals(168, m.current.recurrenceHours)
        assertEquals("5", m.current.executions)
        assertEquals("2026-10-07T00:00:00", m.current.existingScheduleDate)
    }

    @Test
    fun aRecipientChangedDuringTheReadGetsNoneOfTheOldOnesSchedule() = runTest {
        val (_, m, gate) = heldSchedule()
        m.setRecipient("spike-carol")
        testScheduler.advanceTimeBy(1_000) // carol's lookup runs (short of the read's timeout)
        testScheduler.runCurrent()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals("spike-carol", m.current.recipient)
        assertEquals("", m.current.amount, "carol's transfer prefilled with bob's amount")
        assertEquals("", m.current.memo, "carol's transfer prefilled with bob's memo")
        assertEquals(Recurrence.DAILY.hours, m.current.recurrenceHours)
        assertNull(m.current.existingScheduleDate)
    }

    @Test
    fun oneTimePickedDuringTheReadStaysOneTime() = runTest {
        val (_, m, gate) = heldSchedule()
        m.setRecurrence(null)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertNull(m.current.recurrenceHours, "scheduling turned back on")
        assertEquals("", m.current.amount)
        assertEquals("", m.current.memo)
        assertNull(m.current.existingScheduleDate)
    }

    @Test
    fun aFundChangedDuringTheReadGetsNoScheduleOfTheOldFund() = runTest {
        val (_, m, gate) = heldSchedule()
        m.setFund("HBD")
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals("HBD", m.current.fund)
        assertEquals("", m.current.amount, "an HBD transfer prefilled with the HIVE schedule's amount")
        assertEquals("", m.current.memo)
        assertNull(m.current.existingScheduleDate)
    }

    // the other entry point: the recipient's lookup reads the schedules when recurrence was picked first

    private suspend fun TestScope.heldLookupSchedule(): Pair<TransferModel, CompletableDeferred<Unit>> {
        val w = WalletWorld(this)
        w.rpc["condenser_api.find_recurrent_transfers"] = { schedulesJson }
        w.env.wallet.start()
        val m = w.env.transferModel(AppScreen.Transfer("transfer", "HIVE", "hive"))!!
        m.start()
        testScheduler.advanceUntilIdle()
        m.setRecurrence(Recurrence.DAILY.hours)
        testScheduler.advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        w.gates["condenser_api.find_recurrent_transfers"] = gate
        m.setRecipient("spike-bob")
        testScheduler.advanceTimeBy(1_000) // the lookup answered, the schedules read is in flight
        testScheduler.runCurrent()
        return m to gate
    }

    @Test
    fun theLookupFillsTheRecipientsSchedule() = runTest {
        val (m, gate) = heldLookupSchedule()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals("1.500", m.current.amount)
        assertEquals("bob memo", m.current.memo)
        assertEquals(168, m.current.recurrenceHours)
    }

    @Test
    fun aRecipientChangedDuringTheLookupsReadGetsNoneOfTheOldOnesSchedule() = runTest {
        val (m, gate) = heldLookupSchedule()
        m.setRecipient("spike-carol")
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals("", m.current.amount, "carol's transfer prefilled with bob's amount")
        assertEquals("", m.current.memo)
        assertEquals(Recurrence.DAILY.hours, m.current.recurrenceHours)
        assertNull(m.current.existingScheduleDate)
    }

    @Test
    fun oneTimePickedDuringTheLookupsReadStaysOneTime() = runTest {
        val (m, gate) = heldLookupSchedule()
        m.setRecurrence(null)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertNull(m.current.recurrenceHours, "scheduling turned back on")
        assertEquals("", m.current.amount)
        assertNull(m.current.existingScheduleDate)
    }

    @Test
    fun aLateScheduleNeverChangesWhatTheConfirmationShowed() = runTest {
        val (w, m, gate) = heldSchedule()
        m.setAmount("2")
        m.setExecutions("3")
        m.next()
        assertEquals(TransferPrompt.Confirm, m.current.prompt)
        gate.complete(Unit) // bob's schedule lands while the confirmation shows
        testScheduler.advanceUntilIdle()
        assertEquals("2", m.current.amount, "the confirmation's amount changed under it")
        assertEquals("3", m.current.executions)
        m.confirm()
        testScheduler.advanceUntilIdle()
        val op = w.broadcastOpsJson().single()
        assertTrue(op.contains(""""amount":"2.000 HIVE""""), op)
        assertTrue(op.contains(""""memo":"""""), op)
        assertTrue(op.contains(""""recurrence":24"""), op)
        assertTrue(op.contains(""""executions":3"""), op)
    }

    @Test
    fun aLatePrecisionNeverChangesWhatTheConfirmationSigns() = runTest {
        val w = WalletWorld(this)
        val held = CompletableDeferred<Unit>()
        w.http["/private-api/engine-api"] = { req ->
            if (req.body!!.contains("\"balances\"")) com.ecency.core.api.rpc.HttpResult(200, """{"result":[{"account":"spike-voter","symbol":"LEO","balance":"3.50000000","stake":"7.250"}]}""")
            else com.ecency.core.api.rpc.HttpResult(200, """{"result":[{"symbol":"LEO","name":"Leo","precision":0,"stakingEnabled":true,"delegationEnabled":true}]}""")
        }
        w.app.authBackend.gate = { req -> if (req.url.contains("engine-api") && req.body!!.contains("\"tokens\",\"query\"")) held.await() }
        w.env.wallet.start()
        val m = w.env.transferModel(AppScreen.Transfer("transfer", "LEO", "engine"))!!
        m.start()
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        assertNull(m.current.tokenPrecision, "the precision read is held")
        m.setRecipient("spike-bob")
        m.setAmount("1.000")
        testScheduler.advanceTimeBy(1_000)
        testScheduler.runCurrent()
        m.next()
        assertEquals(TransferPrompt.Confirm, m.current.prompt)
        held.complete(Unit) // the precision lands while the confirmation shows
        testScheduler.advanceUntilIdle()
        m.confirm()
        testScheduler.advanceUntilIdle()
        val json = w.broadcastOps().single()[1].toString()
        assertTrue(json.contains("""\"quantity\":\"1\""""), json)
    }

    @Test
    fun anEditAfterNextNeverChangesWhatIsSigned() = runTest {
        val w = WalletWorld(this)
        w.env.wallet.start()
        val m = w.env.transferModel(AppScreen.Transfer("transfer", "HIVE", "hive"))!!
        m.start()
        testScheduler.advanceUntilIdle()
        m.setRecipient("spike-bob")
        m.setAmount("1")
        testScheduler.advanceUntilIdle()
        m.next()
        assertEquals(TransferPrompt.Confirm, m.current.prompt)
        m.setAmount("2") // a write while the confirmation shows
        m.setMemo("late")
        m.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals("""["transfer",{"from":"spike-voter","to":"spike-bob","amount":"1.000 HIVE","memo":""}]""", w.broadcastOpsJson().single())
    }
}
