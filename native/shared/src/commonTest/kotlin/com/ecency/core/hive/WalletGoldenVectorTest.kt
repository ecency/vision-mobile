package com.ecency.core.hive

import com.ecency.core.api.InvalidInputException
import com.ecency.core.api.broadcast.Amounts
import com.ecency.core.api.broadcast.BroadcastException
import com.ecency.core.api.broadcast.Dec
import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.api.broadcast.WalletOps
import com.ecency.core.api.broadcast.WalletOps.EngineAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wallet operations against dhive's bytes, the SDK's transaction ids and JSON, and deterministic signatures
 * (native/tools/golden/generate-wallet.mjs, offline).
 */
class WalletGoldenVectorTest {
    private val key = PrivateKey.fromWif(GoldenVectors.WIF)
    private val u = WalletGoldenVectors.USER

    private fun sdkOps(v: WalletVector) = Json.parseToJsonElement(v.operationsJson) as JsonArray

    private fun decoded(v: WalletVector) = sdkOps(v).map { OperationJson.decode(it as JsonArray) }

    private fun tx(ops: List<Operation>) = Transaction(GoldenVectors.REF_BLOCK_NUM, GoldenVectors.REF_BLOCK_PREFIX, GoldenVectors.EXPIRATION, ops)

    private fun vector(name: String) = WalletGoldenVectors.transactions.first { it.name == name }

    @Test
    fun everyVectorSerializesLikeDhiveHashesLikeTheSdkAndSignsDeterministically() {
        assertTrue(WalletGoldenVectors.transactions.size >= 30)
        WalletGoldenVectors.transactions.forEach { v ->
            val t = tx(decoded(v))
            assertEquals(v.serialized, t.serialize().toHex(), v.name)
            assertEquals(v.txId, t.id(), v.name)
            assertEquals(v.digest, t.digest().toHex(), v.name)
            assertEquals(v.signature, t.sign(key).toHex(), v.name)
            assertEquals(GoldenVectors.PUBLIC_KEY, Signature.fromHex(v.signature).recover(v.digest.hexToBytes()).toString(), v.name)
        }
    }

    @Test
    fun encodingADecodedOperationGivesTheSdkJsonBack() {
        // field by field (object key order is not part of a signature; the custom_json `json` text is, exactly)
        WalletGoldenVectors.transactions.forEach { v ->
            assertEquals(sdkOps(v), WalletOps.ops(decoded(v)), v.name)
        }
    }

    /** The app's own builders give exactly the SDK's operations. */
    @Test
    fun theBuildersMatchTheSdkBuilders() {
        fun same(name: String, ops: List<Operation>) = assertEquals(sdkOps(vector(name)), WalletOps.ops(ops), name)
        same("transfer_multi", WalletOps.multiTransfer(u, "ecency, spike-bob  spike-carol", "1.250 HIVE", "thanks"))
        same("transfer_hbd_unicode_memo", listOf(WalletOps.transfer(u, "ecency", "0.001 HBD", "Привет 🐝 memo")))
        same("recurrent_transfer_daily", listOf(WalletOps.recurrentTransfer(u, "ecency", "2.000 HIVE", "monthly", 24, 12)))
        same("recurrent_transfer_delete", listOf(WalletOps.recurrentTransfer(u, "ecency", "0.000 HBD", "", 731, 2)))
        same("transfer_to_savings", listOf(WalletOps.transferToSavings(u, u, "10.500 HBD", "")))
        same("transfer_from_savings", listOf(WalletOps.transferFromSavings(u, u, "3.000 HIVE", "out", 1791288000L)))
        same("cancel_transfer_from_savings", listOf(WalletOps.cancelTransferFromSavings(u, 4294967295L)))
        same("claim_interest", WalletOps.claimInterest(u, u, "0.001 HBD", "", 123456789L))
        same("transfer_to_vesting", listOf(WalletOps.transferToVesting(u, "spike-bob", "100.000 HIVE")))
        same("withdraw_vesting", listOf(WalletOps.withdrawVesting(u, "123456.789012 VESTS")))
        same("withdraw_vesting_stop", listOf(WalletOps.withdrawVesting(u, "0.000000 VESTS")))
        same("delegate_vesting_shares", listOf(WalletOps.delegateVestingShares(u, "ecency", "2000000.000000 VESTS")))
        same("undelegate_vesting_shares", listOf(WalletOps.delegateVestingShares(u, "ecency", "0.000000 VESTS")))
        same("set_withdraw_vesting_route", listOf(WalletOps.setWithdrawVestingRoute(u, "spike-bob", 2500, true)))
        same("set_withdraw_vesting_route_remove", listOf(WalletOps.setWithdrawVestingRoute(u, "spike-bob", 0, false)))
        same("convert", listOf(WalletOps.convert(u, "5.000 HBD", (1791288000123L and 0xFFFFFFFFL))))
        same("claim_reward_balance_hp", listOf(WalletOps.claimRewardBalance(u, "0.000 HIVE", "0.000 HBD", "1234.567890 VESTS")))
        same("claim_reward_balance_all", listOf(WalletOps.claimRewardBalance(u, "1.234 HIVE", "0.567 HBD", "10.000000 VESTS")))
        same("limit_order_create", listOf(WalletOps.limitOrderCreate(u, "10.000 HIVE", "2.345 HBD", false, "2026-11-02T12:00:00", 912345678L)))
        same("limit_order_create_hbd", listOf(WalletOps.limitOrderCreate(u, "1.000 HBD", "4.100 HIVE", false, "2026-11-02T12:00:00", 999999999L)))
        same("limit_order_cancel", listOf(WalletOps.limitOrderCancel(u, 912345678L)))
        same("point_transfer", listOf(WalletOps.pointTransfer(u, "ecency", "12.500 POINTS", "points memo")))
        same("point_transfer_multi", WalletOps.multiPointTransfer(u, "ecency,spike-bob", "1.000 POINT", ""))
        same("engine_transfer", listOf(WalletOps.engine(u, EngineAction.TRANSFER, "LEO", "ecency", "1.5", "engine")))
        same("engine_stake", listOf(WalletOps.engine(u, EngineAction.STAKE, "LEO", u, "0.001")))
        same("engine_unstake", listOf(WalletOps.engine(u, EngineAction.UNSTAKE, "LEO", u, "2")))
        same("engine_delegate", listOf(WalletOps.engine(u, EngineAction.DELEGATE, "LEO", "ecency", "10.12345678")))
        same("engine_undelegate", listOf(WalletOps.engine(u, EngineAction.UNDELEGATE, "LEO", "ecency", "10")))
        same("scot_claim_token", listOf(WalletOps.engineClaim(u, listOf("LEO"))))
        same("promote", listOf(WalletOps.promote(u, "spike-author", "native-golden-post", 7)))
        same("boost_plus", listOf(WalletOps.boostPlus(u, u, 30)))
        same("rc_delegation", listOf(WalletOps.rcDelegation(u, 1)))
    }

    @Test
    fun invalidInputIsRefusedBeforeAnythingIsSigned() {
        assertFailsWith<InvalidInputException> { WalletOps.transfer(u, "", "1.000 HIVE", "") }
        assertFailsWith<InvalidInputException> { WalletOps.transfer(u, "ecency", "1.0001 HIVE", "") }
        assertFailsWith<InvalidInputException> { WalletOps.transfer(u, "ecency", "0.000 HIVE", "") }
        assertFailsWith<InvalidInputException> { WalletOps.transfer(u, "ecency", "1.000 VESTS", "") }
        assertFailsWith<InvalidInputException> { WalletOps.recurrentTransfer(u, "ecency", "1.000 HIVE", "", 23, 2) }
        assertFailsWith<InvalidInputException> { WalletOps.setWithdrawVestingRoute(u, "x", 10001, false) }
        assertFailsWith<InvalidInputException> { WalletOps.transferToVesting(u, u, "1.000 HBD") }
        assertFailsWith<InvalidInputException> { WalletOps.convert(u, "1.000 HIVE", 1) }
        assertFailsWith<InvalidInputException> { WalletOps.rcDelegation(u, 0) }
        assertFailsWith<InvalidInputException> { WalletOps.claimRewardBalance(u, "0.000 HIVE", "0.000 HBD", "0.000000 VESTS") }
        assertFailsWith<InvalidInputException> { WalletOps.limitOrderCreate(u, "1.000 HIVE", "1.000 HIVE", false, "2026-11-02T12:00:00", 1) }
        assertFailsWith<InvalidInputException> { WalletOps.transferFromSavings(u, u, "1.000 HIVE", "", 0x1_0000_0000L) }
    }

    @Test
    fun decodingRefusesRequestIdsThatAreNotUint32Numbers() {
        fun op(id: String) = Json.parseToJsonElement("""["convert",{"owner":"a","amount":"1.000 HBD","requestid":$id}]""") as JsonArray
        assertEquals(7L, (OperationJson.decode(op("7")) as Convert).requestId)
        listOf("\"7\"", "-1", "4294967296", "1.5", "1e3").forEach { bad ->
            assertFailsWith<BroadcastException.InvalidOperation>(bad) { OperationJson.decode(op(bad)) }
        }
        val withPair = Json.parseToJsonElement(
            """["recurrent_transfer",{"from":"a","to":"b","amount":"1.000 HIVE","memo":"","recurrence":24,"executions":2,"extensions":[[1,{"pair_id":1}]]}]""",
        ) as JsonArray
        assertFailsWith<BroadcastException.InvalidOperation> { OperationJson.decode(withPair) }
    }

    @Test
    fun amountsTruncateLikeRn() {
        assertEquals("1.234", Amounts.fixed("1.2349", 3))
        assertEquals("1.999", Amounts.fixed("1.999999999", 3))
        assertEquals("12.000", Amounts.fixed("12", 3))
        assertEquals("12.000", Amounts.fixed("12.", 3))
        assertEquals("0.000", Amounts.fixed("abc", 3))
        assertEquals("0.000", Amounts.fixed("-0.0001", 3))
        assertEquals("-0.250", Amounts.fixed("-0.25", 3))
        assertEquals("12345678912.123456", Amounts.fixed("12345678912.12345678", 6))
        assertEquals("5.000000 VESTS", Amounts.asset("5", "VESTS"))
        assertEquals("1.5", Amounts.tokenQuantity("1.50000000"))
        assertEquals("2", Amounts.tokenQuantity("2.000", 3))
        assertEquals("1", Amounts.tokenQuantity("1.999", 0))
        assertEquals("0.12345678", Amounts.tokenQuantity("0.123456789", 12))
        assertEquals("0", Amounts.tokenQuantity("x"))
        assertEquals("1.23", Amounts.typed("1,234", 2))
        assertEquals("1.", Amounts.typed("1.", 3))
    }

    @Test
    fun historyTimesParseChainAndIsoForms() {
        val h = com.ecency.core.api.wallet.WalletHistory
        assertEquals(1_791_288_000_000L, h.epochMillis("2026-10-06T12:00:00"))
        assertEquals(1_791_288_000_123L, h.epochMillis("2026-10-06T12:00:00.123Z"))
        assertEquals(1_791_288_000_051L, h.epochMillis("2026-10-06T14:00:00.051909+02:00"))
        assertNull(h.epochMillis("yesterday"))
    }

    @Test
    fun decimalsCompareExactly() {
        assertTrue(Dec.of("10.000")!! == Dec.of("10")!!)
        assertTrue(Dec.of("0.001")!! > Dec.ZERO)
        assertTrue(Dec.of("12345678912.12345678")!! > Dec.of("12345678912.12345677")!!)
        assertEquals("3.750", Dec.of("1.250")!!.times(3).toString())
        assertEquals("1000", Dec.of("125")!!.times(8).toString())
        assertNull(Dec.of("-1"))
        assertNull(Dec.of("1e3"))
        assertEquals("1.234", Dec.ofAsset("1.234 HIVE").toString())
        assertEquals("1234.5", Dec.ofAsset("1,234.5 HP").toString())
    }
}
