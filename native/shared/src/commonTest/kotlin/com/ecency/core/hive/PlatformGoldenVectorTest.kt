package com.ecency.core.hive

import com.ecency.core.api.broadcast.BroadcastException
import com.ecency.core.api.broadcast.OperationJson
import com.ecency.core.api.broadcast.WalletOps
import com.ecency.core.app.BridgeOps
import com.ecency.core.app.BridgeRefused
import com.ecency.core.app.BridgeSignatures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Governance serializers, the dApp bridge's operation builder and signBuffer against the SDK, dhive and RN's own
 * builder function (native/tools/golden/generate-platform.mjs, offline).
 */
class PlatformGoldenVectorTest {
    private val key = PrivateKey.fromWif(GoldenVectors.WIF)
    private val u = PlatformGoldenVectors.USER

    private fun sdkOps(v: WalletVector) = Json.parseToJsonElement(v.operationsJson) as JsonArray

    private fun tx(ops: List<Operation>) = Transaction(GoldenVectors.REF_BLOCK_NUM, GoldenVectors.REF_BLOCK_PREFIX, GoldenVectors.EXPIRATION, ops)

    @Test
    fun governanceOperationsSerializeLikeDhiveAndSignLikeTheSdk() {
        assertEquals(6, PlatformGoldenVectors.transactions.size)
        PlatformGoldenVectors.transactions.forEach { v ->
            val ops = sdkOps(v).map { OperationJson.decode(it as JsonArray) }
            val t = tx(ops)
            assertEquals(v.serialized, t.serialize().toHex(), v.name)
            assertEquals(v.txId, t.id(), v.name)
            assertEquals(v.digest, t.digest().toHex(), v.name)
            assertEquals(v.signature, t.sign(key).toHex(), v.name)
            assertEquals(sdkOps(v), WalletOps.ops(ops), v.name)
        }
    }

    @Test
    fun theBuildersGiveTheSdkOperations() {
        fun same(name: String, op: Operation) = assertEquals(
            sdkOps(PlatformGoldenVectors.transactions.first { it.name == name }), WalletOps.ops(listOf(op)), name,
        )
        same("update_proposal_votes", UpdateProposalVotes(u, listOf(342), true))
        same("update_proposal_votes_remove_many", UpdateProposalVotes(u, listOf(0, 7, 4294967296), false))
        same("account_witness_vote", AccountWitnessVote(u, "good-karma", true))
        same("account_witness_unvote", AccountWitnessVote(u, "ecency", false))
        same("account_witness_proxy", AccountWitnessProxy(u, "ecency"))
        same("account_witness_proxy_clear", AccountWitnessProxy(u, ""))
    }

    @Test
    fun proposalIdsMustBeSortedDistinctNumbersWithNoExtensions() {
        fun decode(json: String) = OperationJson.decode(Json.parseToJsonElement(json) as JsonArray)
        // hived keeps the ids in a flat_set and signs them sorted: an unsorted list would sign other bytes
        assertFailsWith<BroadcastException.InvalidOperation> { decode("""["update_proposal_votes",{"voter":"$u","proposal_ids":[7,3],"approve":true,"extensions":[]}]""") }
        assertFailsWith<BroadcastException.InvalidOperation> { decode("""["update_proposal_votes",{"voter":"$u","proposal_ids":[3,3],"approve":true,"extensions":[]}]""") }
        assertFailsWith<BroadcastException.InvalidOperation> { decode("""["update_proposal_votes",{"voter":"$u","proposal_ids":["3"],"approve":true,"extensions":[]}]""") }
        assertFailsWith<BroadcastException.InvalidOperation> { decode("""["update_proposal_votes",{"voter":"$u","proposal_ids":[],"approve":true,"extensions":[]}]""") }
        assertFailsWith<BroadcastException.InvalidOperation> { decode("""["update_proposal_votes",{"voter":"$u","proposal_ids":[1],"approve":true,"extensions":[[0,{}]]}]""") }
        assertFailsWith<BroadcastException.InvalidOperation> { decode("""["update_proposal_votes",{"voter":"$u","proposal_ids":[1,2,3,4,5,6],"approve":true,"extensions":[]}]""") }
        assertFailsWith<BroadcastException.InvalidOperation> { decode("""["account_witness_vote",{"account":"$u","witness":"w","approve":"yes"}]""") }
        assertTrue(OperationJson.canDecode(Json.parseToJsonElement("""["update_proposal_votes",{"voter":"$u","proposal_ids":[1,2],"approve":true}]""") as JsonArray))
    }

    @Test
    fun theBridgeBuildsWhatRnBuilds() {
        assertTrue(PlatformGoldenVectors.bridge.size >= 25)
        PlatformGoldenVectors.bridge.forEach { v ->
            val data = Json.parseToJsonElement(v.dataJson) as JsonObject
            val built = BridgeOps.build(v.type, data, u, { PlatformGoldenVectors.HIVE_PER_MVESTS }, { PlatformGoldenVectors.REQUEST_ID })
            assertEquals(Json.parseToJsonElement(v.operationsJson), built, "${v.type} ${v.dataJson}")
        }
    }

    @Test
    fun everyBridgeOperationTheAppSignsSerializes() {
        // a key account signs what the bridge builds: every built operation must have a binary form
        PlatformGoldenVectors.bridge.forEach { v ->
            val data = Json.parseToJsonElement(v.dataJson) as JsonObject
            BridgeOps.build(v.type, data, u, { PlatformGoldenVectors.HIVE_PER_MVESTS }, { PlatformGoldenVectors.REQUEST_ID })
                .forEach { assertTrue(OperationJson.canDecode(it as JsonArray), "${v.type}: $it") }
        }
    }

    @Test
    fun theBridgeRefusesAmountsItWouldHaveToRound() {
        fun build(type: String, json: String) = BridgeOps.build(type, Json.parseToJsonElement(json) as JsonObject, u, { 500.0 }, { 1L })
        // RN signs parseFloat(x).toFixed(3): "1.2345" could sign 1.234 or 1.235; here it is refused
        assertFailsWith<BridgeRefused> { build("transfer", """{"to":"a","amount":"1.2345","currency":"HIVE"}""") }
        assertFailsWith<BridgeRefused> { build("transfer", """{"to":"a","amount":"1e3","currency":"HIVE"}""") }
        assertFailsWith<BridgeRefused> { build("transfer", """{"to":"a","amount":"-1","currency":"HIVE"}""") }
        assertFailsWith<BridgeRefused> { build("transfer", """{"to":"a","amount":"1.5abc","currency":"HIVE"}""") }
        assertFailsWith<BridgeRefused> { build("transfer", """{"to":"a","amount":"1","currency":"POINTS"}""") }
        assertFailsWith<BridgeRefused> { build("transfer", """{"amount":"1","currency":"HIVE"}""") }
        assertFailsWith<BridgeRefused> { build("vote", """{"author":"a","permlink":"p","weight":10001}""") }
        assertFailsWith<BridgeRefused> { build("post", """{"permlink":"p","json_metadata":"{not json"}""") }
        assertFailsWith<BridgeRefused> { build("broadcast", """{"operations":"[]"}""") }
        assertFailsWith<BridgeRefused> { build("broadcast", """{"operations":[["vote"]]}""") }
        assertFailsWith<BridgeRefused> { build("delegation", """{"delegatee":"a","amount":"1.0000001","unit":"VESTS"}""") }
        assertFailsWith<BridgeRefused> { build("nothing", "{}") }
        // a JSON number is fine
        assertEquals("1.500 HIVE", ((build("transfer", """{"to":"a","amount":1.5,"currency":"HIVE"}""")[0] as JsonArray)[1] as JsonObject)["amount"].toString().trim('"'))
    }

    @Test
    fun signBufferSignsTheSha256OfTheUtf8Message() {
        assertEquals(4, PlatformGoldenVectors.signBuffer.size)
        PlatformGoldenVectors.signBuffer.forEach { v ->
            val digest = BridgeSignatures.digest(v.message)
            assertEquals(v.digest, digest.toHex(), v.message)
            assertEquals(v.signature, key.sign(digest).toHex(), v.message)
            assertEquals(GoldenVectors.PUBLIC_KEY, Signature.fromHex(v.signature).recover(digest).toString())
        }
    }
}
