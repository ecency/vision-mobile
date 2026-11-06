package com.ecency.core.hive

import com.ecency.core.api.broadcast.BroadcastException
import com.ecency.core.api.broadcast.OperationJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #3635: the operations hive:// links ask key accounts to sign (proposals, limit_order_create2) against the SDK and
 * dhive (tools/golden/generate-link-ops.mjs, offline).
 */
class LinkOpGoldenVectorTest {
    private val key = PrivateKey.fromWif(GoldenVectors.WIF)

    private fun sdkOps(v: WalletVector) = Json.parseToJsonElement(v.operationsJson) as JsonArray

    private fun tx(ops: List<Operation>) = Transaction(GoldenVectors.REF_BLOCK_NUM, GoldenVectors.REF_BLOCK_PREFIX, GoldenVectors.EXPIRATION, ops)

    @Test
    fun linkOperationsSerializeLikeDhiveAndSignLikeTheSdk() {
        assertEquals(8, LinkOpGoldenVectors.transactions.size)
        LinkOpGoldenVectors.transactions.forEach { v ->
            sdkOps(v).forEach { assertTrue(OperationJson.canDecode(it as JsonArray), "${v.name}: the app can sign it") }
            val ops = sdkOps(v).map { OperationJson.decode(it as JsonArray) }
            val t = tx(ops)
            assertEquals(v.serialized, t.serialize().toHex(), v.name)
            assertEquals(v.txId, t.id(), v.name)
            assertEquals(v.digest, t.digest().toHex(), v.name)
            assertEquals(v.signature, t.sign(key).toHex(), v.name)
            // what the app broadcasts is the SDK's JSON
            assertEquals(sdkOps(v), JsonArray(ops.map(OperationJson::encode)), v.name)
        }
    }

    @Test
    fun theTypesGiveTheSdkOperations() {
        val u = LinkOpGoldenVectors.USER
        fun same(name: String, op: Operation) = assertEquals(
            sdkOps(LinkOpGoldenVectors.transactions.first { it.name == name }), JsonArray(listOf(OperationJson.encode(op))), name,
        )
        same("create_proposal", CreateProposal(u, "spike-bob", "2026-11-01T00:00:00", "2027-05-01T00:00:00", Asset.parse("125.500 HBD"), "Golden proposal 🐝", "native-golden-proposal"))
        same("update_proposal", UpdateProposal(342, u, Asset.parse("100.000 HBD"), "Golden proposal, smaller", "native-golden-proposal", null))
        same("update_proposal_end_date", UpdateProposal(4294967296, u, Asset.parse("1.000 HBD"), "Ends sooner", "native-golden-proposal", "2027-01-01T00:00:00"))
        same("remove_proposal_many", RemoveProposal(u, listOf(0, 7, 4294967296)))
        same(
            "limit_order_create2_sell_hive",
            LimitOrderCreate2(u, 1791288001, Asset.parse("10.000 HIVE"), Asset.parse("1.000 HIVE"), Asset.parse("0.250 HBD"), false, "2026-11-02T12:00:00"),
        )
    }

    @Test
    fun linkJsonTheBinaryFormCannotCarryIsRefused() {
        val u = LinkOpGoldenVectors.USER
        fun refused(json: String) = assertFailsWith<BroadcastException.InvalidOperation>(json) { OperationJson.decode(Json.parseToJsonElement(json) as JsonArray) }
        fun order(amount: String = "10.000 HIVE", rate: String = """{"base":"1.000 HIVE","quote":"0.250 HBD"}""", extra: String = "") =
            """["limit_order_create2",{"owner":"$u","orderid":1,"amount_to_sell":"$amount","exchange_rate":$rate,"fill_or_kill":false,"expiration":"2026-11-02T12:00:00"$extra}]"""
        assertTrue(OperationJson.canDecode(Json.parseToJsonElement(order()) as JsonArray))
        refused(order(amount = "10.000 HBD")) // the sold asset must be the price's base
        refused(order(rate = """{"base":"1.000 HIVE","quote":"0.250 HIVE"}"""))
        refused(order(rate = """{"base":"1.000 HIVE","quote":"0.000 HBD"}"""))
        refused(order(rate = """{"base":"1.000 HIVE","quote":"0.250 HBD","extra":"x"}"""))
        refused(order(amount = "10.000 VESTS", rate = """{"base":"1.000 VESTS","quote":"0.250 HBD"}"""))
        refused(order(rate = "\"1.000 HIVE/0.250 HBD\""))
        refused("""["create_proposal",{"creator":"$u","receiver":"r","start_date":"2026-11-01T00:00:00","end_date":"2027-05-01T00:00:00","daily_pay":"1.000 HIVE","subject":"s","permlink":"p","extensions":[]}]""")
        refused("""["create_proposal",{"creator":"$u","receiver":"r","start_date":"2027-05-01T00:00:00","end_date":"2026-11-01T00:00:00","daily_pay":"1.000 HBD","subject":"s","permlink":"p","extensions":[]}]""")
        refused("""["create_proposal",{"creator":"$u","receiver":"r","start_date":"2026-11-01T00:00:00Z","end_date":"2027-05-01T00:00:00","daily_pay":"1.000 HBD","subject":"s","permlink":"p","extensions":[]}]""")
        refused("""["create_proposal",{"creator":"$u","receiver":"r","start_date":"2026-11-01T00:00:00","end_date":"2027-05-01T00:00:00","daily_pay":"1.000 HBD","subject":"s","permlink":"p","extensions":[[0,{}]]}]""")
        fun update(id: String = "1", ext: String = "[]") =
            """["update_proposal",{"proposal_id":$id,"creator":"$u","daily_pay":"1.000 HBD","subject":"s","permlink":"p","extensions":$ext}]"""
        assertTrue(OperationJson.canDecode(Json.parseToJsonElement(update(ext = """[[1,{"end_date":"2027-01-01T00:00:00"}]]""")) as JsonArray))
        refused(update(id = "\"1\""))
        refused(update(id = "-1"))
        refused(update(id = "1.5"))
        refused(update(ext = """[[0,{}]]"""))
        refused(update(ext = """[[1,{"end_date":"2027-01-01T00:00:00","x":1}]]"""))
        refused(update(ext = """[[1,{"end_date":"2027-01-01T00:00:00"}],[1,{"end_date":"2027-01-02T00:00:00"}]]"""))
        refused(update(ext = """[{"type":"update_proposal_end_date","value":{"end_date":"2027-01-01T00:00:00"}}]"""))
        fun remove(ids: String) = """["remove_proposal",{"proposal_owner":"$u","proposal_ids":$ids,"extensions":[]}]"""
        refused(remove("[7,3]"))
        refused(remove("[3,3]"))
        refused(remove("[\"3\"]"))
        refused(remove("[]"))
        refused(remove("[1,2,3,4,5,6]"))
    }
}
