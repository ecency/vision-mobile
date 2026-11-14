package com.ecency.core.app

import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.api.broadcast.WalletOps
import com.ecency.core.hive.KeyRole
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Owner request: Delegate RC from another user's profile menu (`delegate_rc`, a posting custom_json, SDK
 * buildDelegateRcOp). Amounts are typed in billions of RC; at least 5 billion (as web), never more than the sender
 * can delegate. Every number is synthetic.
 */
class RcDelegationTest {
    private val now get() = TestApp.NOW / 1000

    /**
     * [user]'s RC: max 900 billion of which 100 billion received (not delegable), 300 billion current mana at [now]
     * (no regeneration), and [existing] already delegated to spike-bob.
     */
    private fun world(scope: TestScope, existing: Long? = null, current: Long = 300_000_000_000, updated: Long = now): WalletWorld {
        val w = WalletWorld(scope)
        w.rpc["rc_api.find_rc_accounts"] = {
            """{"rc_accounts":[{"account":"${w.user}","rc_manabar":{"current_mana":$current,"last_update_time":$updated},"max_rc_creation_adjustment":{"amount":"0","precision":6,"nai":"@@000000037"},"max_rc":900000000000,"delegated_rc":0,"received_delegated_rc":100000000000}]}"""
        }
        w.rpc["rc_api.list_rc_direct_delegations"] = { r ->
            val start = r.params.jsonObject["start"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf(w.user, "spike-bob"), start)
            if (existing == null) """{"rc_direct_delegations":[{"from":"${w.user}","to":"spike-carol","delegated_rc":7000000000}]}"""
            else """{"rc_direct_delegations":[{"from":"${w.user}","to":"spike-bob","delegated_rc":$existing}]}"""
        }
        return w
    }

    private fun delegateRcJson(op: kotlinx.serialization.json.JsonArray) = Json.parseToJsonElement(op[1].jsonObject["json"]!!.jsonPrimitive.content)

    @Test
    fun theOperationIsTheSdksPostingCustomJson() {
        val op = OperationJson.encode(WalletOps.delegateRc("spike-voter", "spike-bob", 5_000_000_000))
        assertEquals("custom_json", op[0].jsonPrimitive.content)
        val body = op[1].jsonObject
        assertEquals("rc", body["id"]!!.jsonPrimitive.content)
        assertEquals("[]", body["required_auths"].toString())
        assertEquals("""["spike-voter"]""", body["required_posting_auths"].toString())
        assertEquals("""["delegate_rc",{"from":"spike-voter","delegatees":["spike-bob"],"max_rc":5000000000}]""", body["json"]!!.jsonPrimitive.content)
    }

    @Test
    fun theSheetShowsWhatCanBeDelegatedAndTheCurrentDelegation() = runTest {
        val w = world(this, existing = 20_000_000_000)
        val m = w.env.rcDelegateModel("spike-bob")
        m.start()
        testScheduler.advanceUntilIdle()
        // delegable: the lower of own max (900 - 100 received) and current mana (300), all in billions
        assertEquals(300_000_000_000, m.current.available)
        assertEquals(20_000_000_000, m.current.existing)
        // the new total may take back what spike-bob already has
        assertEquals(320_000_000_000, m.current.maxTotal)
        assertNull(m.current.problem)
    }

    @Test
    fun anAmountIsTypedInBillionsAndChecked() = runTest {
        val w = world(this, existing = 20_000_000_000)
        val m = w.env.rcDelegateModel("spike-bob")
        m.start()
        testScheduler.advanceUntilIdle()
        m.setAmount("4.999")
        assertEquals(4_999_000_000, m.current.amountRc)
        assertEquals(RcAmountProblem.TOO_LOW, m.current.problem)
        assertFalse(m.current.reviewEnabled)
        m.setAmount("320.000000001")
        assertEquals(RcAmountProblem.TOO_MUCH, m.current.problem)
        m.setAmount("20")
        assertEquals(RcAmountProblem.SAME, m.current.problem)
        // the language's comma and digits are read as the dot and ASCII digits
        m.setAmount("5,5")
        assertEquals(5_500_000_000, m.current.amountRc)
        assertNull(m.current.problem)
        assertTrue(m.current.reviewEnabled)
        // more than 9 decimals is below one RC: refused as typed
        m.setAmount("5.5000000001")
        assertEquals("5.5", m.current.amount)
        m.setAmount("abc")
        assertEquals("5.5", m.current.amount)
    }

    @Test
    fun aConfirmedDelegationIsSignedWithThePostingKeyOnce() = runTest {
        val w = world(this)
        val m = w.env.rcDelegateModel("spike-bob")
        m.start()
        testScheduler.advanceUntilIdle()
        assertEquals(0L, m.current.existing, "another account's delegation is not spike-bob's")
        m.setAmount("12.5")
        m.review()
        val review = assertIs<RcDelegatePrompt.Review>(m.current.prompt)
        assertEquals(12_500_000_000, review.maxRc)
        assertFalse(review.remove)
        assertTrue(w.broadcastOps().isEmpty(), "nothing is sent before the confirmation")
        m.confirm()
        m.confirm()
        testScheduler.advanceUntilIdle()
        val op = w.broadcastOps().single()
        assertEquals("""["delegate_rc",{"from":"${w.user}","delegatees":["spike-bob"],"max_rc":12500000000}]""", delegateRcJson(op).toString())
        assertEquals(listOf<KeyRole?>(KeyRole.POSTING), w.signerRoles())
        assertIs<SendPhase.Done>(m.current.phase)
        assertTrue(m.current.closed)
    }

    @Test
    fun anExistingDelegationCanBeRemoved() = runTest {
        val w = world(this, existing = 20_000_000_000)
        val m = w.env.rcDelegateModel("spike-bob")
        m.start()
        testScheduler.advanceUntilIdle()
        assertTrue(m.current.canRemove)
        m.remove()
        val review = assertIs<RcDelegatePrompt.Review>(m.current.prompt)
        assertTrue(review.remove)
        assertEquals(0L, review.maxRc)
        m.confirm()
        testScheduler.advanceUntilIdle()
        assertEquals("""["delegate_rc",{"from":"${w.user}","delegatees":["spike-bob"],"max_rc":0}]""", delegateRcJson(w.broadcastOps().single()).toString())
    }

    @Test
    fun withoutADelegationThereIsNothingToRemove() = runTest {
        val w = world(this)
        val m = w.env.rcDelegateModel("spike-bob")
        m.start()
        testScheduler.advanceUntilIdle()
        assertFalse(m.current.canRemove)
        m.remove()
        assertNull(m.current.prompt)
        m.setAmount("0")
        assertEquals(RcAmountProblem.TOO_LOW, m.current.problem)
    }

    @Test
    fun manaRegeneratesUpToTheOwnMaximum() = runTest {
        // 100 billion current, updated 2.5 days ago: half of the 900 billion max came back, capped at own 800
        val w = world(this, current = 100_000_000_000, updated = now - 216_000)
        val m = w.env.rcDelegateModel("spike-bob")
        m.start()
        testScheduler.advanceUntilIdle()
        assertEquals(550_000_000_000, m.current.available)
        // five days later the own maximum caps it (received RC cannot be passed on)
        val later = world(this, current = 100_000_000_000, updated = now - 432_000)
        val m2 = later.env.rcDelegateModel("spike-bob")
        m2.start()
        testScheduler.advanceUntilIdle()
        assertEquals(800_000_000_000, m2.current.available)
    }

    @Test
    fun aFailedReadSaysSoAndOffersNothingToSend() = runTest {
        val w = world(this)
        w.rpc["rc_api.find_rc_accounts"] = { null }
        val m = w.env.rcDelegateModel("spike-bob")
        m.start()
        testScheduler.advanceUntilIdle()
        assertNull(m.current.available)
        assertTrue(m.current.loadFailed)
        m.setAmount("10")
        assertFalse(m.current.reviewEnabled)
    }

    @Test
    fun theProfileMenuOffersDelegateRcAfterDelegateHp() {
        val s = ProfileState(username = "spike-bob", isOwn = false, loggedIn = true)
        val menu = s.menu
        assertEquals(menu.indexOf(ProfileMenuItem.DELEGATE) + 1, menu.indexOf(ProfileMenuItem.DELEGATE_RC))
        assertEquals("DELEGATE RC", s.menuLabel(ProfileMenuItem.DELEGATE_RC))
    }
}
