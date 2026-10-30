package com.ecency.core.app

import com.ecency.core.api.FakeTransport
import com.ecency.core.api.Sent
import com.ecency.core.api.rpc.HttpResult
import com.ecency.core.hive.KeyRole
import com.ecency.core.secure.AuthType
import com.ecency.core.secure.KeyVault
import com.ecency.core.secure.VaultAccount
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * A wallet test world: a master-password account [USER] (posting and active keys stored), a chain answering the
 * wallet reads from [rpc] (plus what a broadcast needs) and Ecency's HTTP endpoints from [http]. Every number is
 * synthetic. [broadcastAnswer] decides each broadcast_transaction (default: accepted).
 */
class WalletWorld(scope: TestScope, val user: String = USER, tokensJson: String = "[]") {
    val rpc = mutableMapOf<String, (Sent) -> String?>()
    val http = mutableMapOf<String, (com.ecency.core.auth.AuthHttpRequest) -> HttpResult?>()
    var broadcastAnswer: suspend (Sent) -> HttpResult = { FakeTransport.result(it, "{}") }
    var statusAnswer: (Sent) -> String = { "\"unknown\"" }

    /** A method whose answer waits for its gate (an in-flight read held while the test acts). */
    val gates = mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<Unit>>()

    val app = TestApp(scope) { request ->
        gates[request.method]?.await()
        when (request.method) {
            "condenser_api.broadcast_transaction" -> broadcastAnswer(request)
            "transaction_status_api.find_transaction" -> FakeTransport.result(request, """{"status":${statusAnswer(request)}}""")
            "condenser_api.get_dynamic_global_properties" -> FakeTransport.result(
                request,
                """{"head_block_number":100675644,"head_block_id":"0601303c${"11".repeat(16)}","time":"$chainTime","total_vesting_fund_hive":"200000000.000 HIVE","total_vesting_shares":"400000000000.000000 VESTS"}""",
            )
            else -> rpc[request.method]?.invoke(request)?.let { FakeTransport.result(request, it) }
                ?: TestApp.chain(request) ?: HttpResult(500, "no fixture for ${request.method}")
        }
    }

    /** The chain's head time (find_transaction's undecided cases compare it with the expiration). */
    var chainTime = "2026-10-06T11:59:00"

    init {
        app.authBackend.rest = { req -> http.entries.firstOrNull { req.url.contains(it.key) }?.value?.invoke(req) }
        rpc["condenser_api.get_accounts"] = { accountsJson(it, tokensJson) }
        rpc["condenser_api.lookup_accounts"] = { r ->
            val prefix = r.params.jsonArray[0].jsonPrimitive.content
            "[" + KNOWN.filter { it.startsWith(prefix) }.joinToString(",") { "\"$it\"" } + "]"
        }
        http["/wallet-api/portfolio-v2"] = { HttpResult(200, PORTFOLIO) }
        http["raw.githubusercontent.com"] = { HttpResult(200, "spike-scam\n") }
        KeyVault(app.store).run {
            save(
                VaultAccount(
                    user, AuthType.MASTER_PASSWORD, postingKey = AppTestKeys.wif(user, KeyRole.POSTING),
                    activeKey = AppTestKeys.wif(user, KeyRole.ACTIVE), accessToken = "access-$user",
                ),
            )
            setCurrentUsername(user)
        }
        app.env.session.start()
    }

    val env get() = app.env

    /** Every operation broadcast so far, as condenser JSON arrays. */
    fun broadcastOps(): List<JsonArray> = app.broadcasts().flatMap { b ->
        ((b.params.jsonArray[0] as JsonObject)["operations"] as JsonArray).map { it as JsonArray }
    }

    fun broadcastOpsJson(): List<String> = broadcastOps().map { it.toString() }

    /**
     * The key role that signed each broadcast: the transaction is rebuilt from what was sent, its digest computed and
     * every signature recovered, then matched against the account's keys. Null for a key that is not the account's.
     */
    fun signerRoles(): List<KeyRole?> = app.broadcasts().map { b ->
        val tx = b.params.jsonArray[0] as JsonObject
        val ops = (tx["operations"] as JsonArray).map { com.ecency.core.api.broadcast.OperationJson.decodeOwn(it as JsonArray) }
        val t = com.ecency.core.hive.Transaction(
            tx["ref_block_num"]!!.jsonPrimitive.content.toInt(), tx["ref_block_prefix"]!!.jsonPrimitive.content.toLong(),
            tx["expiration"]!!.jsonPrimitive.content, ops,
        )
        val sig = (tx["signatures"] as JsonArray).single().jsonPrimitive.content
        val pub = com.ecency.core.hive.Signature.fromHex(sig).recover(t.digest()).toString()
        listOf(KeyRole.POSTING, KeyRole.ACTIVE, KeyRole.OWNER, KeyRole.MEMO).firstOrNull { AppTestKeys.pub(user, it) == pub }
    }

    private fun accountsJson(r: Sent, tokens: String): String {
        val names = r.params.jsonArray[0].jsonArray.map { it.jsonPrimitive.content }
        return "[" + names.filter { it == user }.joinToString(",") {
            val meta = com.ecency.core.api.broadcast.JsJson.quote("""{"profile":{"name":"Spike","tokens":$tokens}}""")
            """{"name":"$it","balance":"12.345 HIVE","hbd_balance":"5.000 HBD","savings_balance":"1.000 HIVE","savings_hbd_balance":"20.000 HBD",
               "reward_hive_balance":"0.100 HIVE","reward_hbd_balance":"0.200 HBD","reward_vesting_balance":"1234.567890 VESTS",
               "vesting_shares":"2000000.000000 VESTS","delegated_vesting_shares":"100000.000000 VESTS","received_vesting_shares":"0.000000 VESTS",
               "vesting_withdraw_rate":"0.000000 VESTS","next_vesting_withdrawal":"1969-12-31T23:59:59","to_withdraw":0,"withdrawn":0,
               "posting_json_metadata":$meta,"json_metadata":"","owner":{"weight_threshold":1,"account_auths":[],"key_auths":[]},
               "active":{"weight_threshold":1,"account_auths":[],"key_auths":[["${AppTestKeys.pub(user, KeyRole.ACTIVE)}",1]]},
               "posting":{"weight_threshold":1,"account_auths":[],"key_auths":[["${AppTestKeys.pub(user, KeyRole.POSTING)}",1]]},
               "memo_key":"${AppTestKeys.pub(user, KeyRole.MEMO)}"}"""
        } + "]"
    }

    companion object {
        const val USER = "spike-voter"
        val KNOWN = listOf("spike-voter", "spike-bob", "spike-carol", "spike-scam", "bittrex", "spike-bobby")

        /** hivePerMVests of the fixture: 200000000 / 400000000000 * 1e6 = 500. */
        const val HIVE_PER_MVESTS = 500.0

        val PORTFOLIO = """{"username":"spike-voter","currency":"usd","wallets":[
            {"name":"Ecency Points","symbol":"POINTS","layer":"points","balance":1500.5,"fiatRate":0.002,"currency":"USD","precision":3,"actions":[{"id":"ecency_point_transfer"},{"id":"promote"},{"id":"boost"}],"pendingRewards":0.75,"liquid":1500.5},
            {"name":"Staked Hive","symbol":"HP","layer":"hive","balance":950.0,"fiatRate":0.25,"currency":"USD","precision":3,"actions":[{"id":"delegate_vesting_shares"},{"id":"withdraw_vesting"},{"id":"set_withdraw_vesting_route"}],
             "extraData":[{"dataKey":"delegated_hive_power","value":"- 50.000 HP"},{"dataKey":"powering_down_hive_power","value":"- 1.000 HP","subValue":"144h"}],"pendingRewards":0.617,"apr":4.5945,"liquid":900.0,"staked":950.0},
            {"name":"Hive","symbol":"HIVE","layer":"hive","balance":12.345,"fiatRate":0.25,"currency":"USD","precision":3,"actions":[{"id":"transfer"},{"id":"transfer_to_savings"},{"id":"transfer_to_vesting"},{"id":"swap_token"},{"id":"recurrent_transfer"}],"pendingRewards":0.1,"liquid":12.345,"savings":1.0},
            {"name":"Hive Dollar","symbol":"HBD","layer":"hive","balance":5.0,"fiatRate":1,"currency":"USD","precision":3,"actions":[{"id":"transfer"},{"id":"convert"}],"pendingRewards":0.2,"apr":10,"liquid":5.0,"savings":20.0},
            {"name":"Leo","symbol":"LEO","layer":"engine","balance":3.5,"fiatRate":0.1,"currency":"USD","precision":3,"actions":[{"id":"transfer"},{"id":"stake"}],"extraData":[{"dataKey":"delegations_out","value":"2.000"}],"pendingRewards":0,"liquid":3.5,"staked":7.25},
            {"name":"Spk","symbol":"SPK","layer":"spk","balance":1},
            {"name":"BNB Chain","symbol":"BNB","layer":"chain","balance":0.5,"fiatRate":300,"currency":"USD","precision":18,"actions":[{"id":"receive"}],"address":"0xabc"}
        ]}"""
    }
}
