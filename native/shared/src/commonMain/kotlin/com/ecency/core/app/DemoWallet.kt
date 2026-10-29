package com.ecency.core.app

import com.ecency.core.api.rpc.HttpResult
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Synthetic wallet data for the debug demo runs (screenshots, UI tests): made-up balances, history and market rows
 * for the demo accounts, answered offline by [DemoHiveTransport] and [DemoAuthServices]. Broadcasts stay refused
 * ([DemoHiveTransport] answers no broadcast_transaction), unless [hangBroadcasts] holds them open so the
 * "check your wallet" state can be shown; nothing reaches a chain either way.
 */
object DemoWallet {
    /** Debug only: a broadcast never answers (every node "times out"), so a send turns undecided. */
    @kotlin.concurrent.Volatile
    var hangBroadcasts: Boolean = false

    /** The extra account fields of a demo account (balances, rewards, savings). */
    fun accountFields(): Map<String, JsonPrimitive> = mapOf(
        "balance" to JsonPrimitive("1234.567 HIVE"), "hbd_balance" to JsonPrimitive("89.012 HBD"),
        "savings_balance" to JsonPrimitive("50.000 HIVE"), "savings_hbd_balance" to JsonPrimitive("300.000 HBD"),
        "reward_hive_balance" to JsonPrimitive("0.512 HIVE"), "reward_hbd_balance" to JsonPrimitive("1.204 HBD"),
        "reward_vesting_balance" to JsonPrimitive("2048.000000 VESTS"), "next_vesting_withdrawal" to JsonPrimitive("1969-12-31T23:59:59"),
    )

    const val DGP = """{"head_block_number":100000000,"head_block_id":"05f5e10011111111111111111111111111111111","time":"2026-10-06T12:00:00","total_vesting_fund_hive":"200000000.000 HIVE","total_vesting_shares":"400000000000.000000 VESTS"}"""

    /** A Hive RPC call of the wallet, or null when it is not a wallet call. */
    suspend fun rpc(method: String, params: JsonElement?): JsonElement? =
        if (method == "condenser_api.broadcast_transaction") { if (hangBroadcasts) awaitCancellation() else null } else read(method, params)

    /**
     * The iOS fake transport's way in (Swift calls it synchronously): the whole JSON-RPC answer to [requestBody]
     * when it is a wallet read, else null. Broadcasts are never answered here.
     */
    fun answer(requestBody: String): HttpResult? {
        val request = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(requestBody) as? JsonObject }.getOrNull() ?: return null
        val method = (request["method"] as? JsonPrimitive)?.content ?: return null
        val result = read(method, request["params"]) ?: return null
        return HttpResult(200, buildJsonObject { put("jsonrpc", "2.0"); put("result", result); put("id", request["id"] ?: JsonPrimitive(0)) }.toString())
    }

    private fun read(method: String, params: JsonElement?): JsonElement? = when (method) {
        "condenser_api.get_dynamic_global_properties" -> kotlinx.serialization.json.Json.parseToJsonElement(DGP)
        "condenser_api.get_account_history" -> history((params as? JsonArray)?.getOrNull(1)?.let { (it as? JsonPrimitive)?.content?.toLongOrNull() } ?: -1)
        "condenser_api.get_savings_withdraw_from" -> json("""[{"id":1,"from":"spike-alice","to":"spike-alice","memo":"","request_id":7001,"amount":"25.000 HBD","complete":"2026-10-09T08:00:00"}]""")
        "condenser_api.get_conversion_requests", "condenser_api.get_collateralized_conversion_requests" -> JsonArray(emptyList())
        "condenser_api.get_open_orders" -> json("""[{"id":3,"created":"2026-10-05T09:00:00","expiration":"2026-11-01T09:00:00","seller":"spike-alice","orderid":912345678,"for_sale":10000,"sell_price":{"base":"10.000 HIVE","quote":"2.500 HBD"}}]""")
        "condenser_api.find_recurrent_transfers" -> json("""[{"id":4,"trigger_date":"2026-10-08T00:00:00","from":"spike-alice","to":"spike-bob","amount":"5.000 HIVE","memo":"Weekly","recurrence":168,"consecutive_failures":0,"remaining_executions":8}]""")
        "condenser_api.get_withdraw_routes" -> json("""[{"id":1,"from_account":"spike-alice","to_account":"spike-carol","percent":2500,"auto_vest":true}]""")
        "condenser_api.get_vesting_delegations" -> json("""[{"id":1,"delegator":"spike-alice","delegatee":"spike-bob","vesting_shares":"200000.000000 VESTS","min_delegation_time":"2026-01-15T10:00:00"},{"id":2,"delegator":"spike-alice","delegatee":"spike-carol","vesting_shares":"50000.000000 VESTS","min_delegation_time":"2026-03-02T10:00:00"}]""")
        "database_api.find_vesting_delegation_expirations" -> json("""{"delegations":[{"id":9,"delegator":"spike-alice","vesting_shares":{"amount":"10000000000","precision":6,"nai":"@@000000037"},"expiration":"2026-10-08T12:00:00"}]}""")
        "condenser_api.get_order_book" -> json(
            """{"bids":[{"created":"x","hbd":25000,"hive":100000,"real_price":"0.25000000"},{"created":"x","hbd":48000,"hive":200000,"real_price":"0.24000000"}],""" +
                """"asks":[{"created":"x","hbd":26000,"hive":100000,"real_price":"0.26000000"},{"created":"x","hbd":53000,"hive":200000,"real_price":"0.26500000"}]}""",
        )
        "condenser_api.get_ticker" -> json("""{"latest":"0.25500000","lowest_ask":"0.26000000","highest_bid":"0.25000000","percent_change":"1.2","hive_volume":"1000.000 HIVE","hbd_volume":"255.000 HBD"}""")
        "transaction_status_api.find_transaction" -> json("""{"status":"unknown"}""")
        else -> null
    }

    private fun history(start: Long): JsonElement {
        if (start in 0..999) return JsonArray(emptyList())
        fun row(num: Long, op: String, fields: String, time: String): String {
            val id = "a1".repeat(19) + (num % 100).toString().padStart(2, '0')
            return """[$num,{"trx_id":"$id","block":1,"trx_in_block":0,"op_in_trx":0,"virtual_op":false,"timestamp":"$time","op":["$op",$fields]}]"""
        }
        return json(
            "[" + listOf(
                row(1001, "transfer_to_vesting", """{"from":"spike-alice","to":"spike-alice","amount":"100.000 HIVE"}""", "2026-10-01T10:00:00"),
                row(1002, "transfer", """{"from":"spike-bob","to":"spike-alice","amount":"12.500 HIVE","memo":"Thanks for the help"}""", "2026-10-03T14:20:00"),
                row(1003, "claim_reward_balance", """{"account":"spike-alice","reward_hive":"1.250 HIVE","reward_hbd":"0.000 HBD","reward_vests":"500.000000 VESTS"}""", "2026-10-04T08:00:00"),
                row(1004, "transfer", """{"from":"spike-alice","to":"spike-carol","amount":"3.000 HIVE","memo":""}""", "2026-10-05T18:45:00"),
                row(1005, "fill_order", """{"current_owner":"spike-alice","current_orderid":1,"current_pays":"10.000 HIVE","open_owner":"spike-dave","open_orderid":2,"open_pays":"2.500 HBD"}""", "2026-10-06T09:30:00"),
            ).joinToString(",") + "]",
        )
    }

    /** An Ecency HTTP call of the wallet, or null when it is not one. */
    fun http(url: String, body: String?): HttpResult? {
        val path = url.substringAfter("ecency.com/", "")
        return when {
            path.startsWith("wallet-api/portfolio-v2") -> HttpResult(200, PORTFOLIO)
            path == "private-api/point-list" -> HttpResult(
                200,
                """[{"id":31,"amount":"0.250","type":10,"sender":null,"receiver":null,"memo":null,"created":"2026-10-06T09:00:00+00:00"},""" +
                    """{"id":30,"amount":"-50.000","type":997,"sender":null,"receiver":null,"memo":"AI image","created":"2026-10-05T09:00:00+00:00"},""" +
                    """{"id":29,"amount":"100.000","type":999,"sender":"spike-bob","receiver":"spike-alice","memo":"Gift","created":"2026-10-04T09:00:00+00:00"}]""",
            )
            path == "private-api/points-claim" -> HttpResult(200, "{}")
            path == "private-api/boost-plus-price" -> HttpResult(200, """[{"duration":7,"price":1500},{"duration":14,"price":2800},{"duration":30,"price":5500}]""")
            path == "private-api/rc-delegation-price" -> HttpResult(200, """[{"duration":1,"price":100},{"duration":3,"price":250},{"duration":7,"price":500}]""")
            path == "private-api/boosted-plus-account" -> HttpResult(200, """{"account":"spike-alice","expires":null}""")
            path.startsWith("private-api/post-tips/") -> HttpResult(200, """{"meta":{"count":2,"totals":{"HIVE":"1.500","POINTS":"20.000"}},"list":[]}""")
            path.startsWith("private-api/engine-account-history") -> HttpResult(
                200,
                if ("offset=0" in path) """[{"_id":"e1","blockNumber":40750424,"transactionId":"${"b1".repeat(20)}-0","timestamp":1791200000,"operation":"tokens_transfer","from":"spike-bob","to":"spike-alice","symbol":"LEO","quantity":"5.000","memo":"Engine gift"},""" +
                    """{"_id":"e2","blockNumber":40750000,"transactionId":"40750000-1","timestamp":1791100000,"operation":"tokens_stake","from":"spike-alice","to":"spike-alice","symbol":"LEO","quantity":"10.000"}]""" else "[]",
            )
            path == "private-api/engine-api" -> HttpResult(
                200,
                if (body?.contains("\"balances\"") == true) """{"result":[{"account":"spike-alice","symbol":"LEO","balance":"42.125","stake":"120.000"},{"account":"spike-alice","symbol":"BEE","balance":"3.10000000","stake":"0"}]}"""
                else """{"result":[{"symbol":"LEO","name":"Leo","precision":3,"stakingEnabled":true,"delegationEnabled":true},{"symbol":"BEE","name":"Bee","precision":8,"stakingEnabled":true,"delegationEnabled":false}]}""",
            )
            url.startsWith("https://raw.githubusercontent.com/openhive-network/watchmen/") -> HttpResult(200, "spike-scammer\n")
            "/balance-api/accounts/" in url -> HttpResult(200, """{"outgoing_delegations":[],"incoming_delegations":[{"delegator":"spike-dave","amount":"300000000000"}]}""")
            else -> null
        }
    }

    private fun json(text: String): JsonElement = kotlinx.serialization.json.Json.parseToJsonElement(text)

    val PORTFOLIO = """{"username":"spike-alice","currency":"usd","wallets":[
        {"name":"Ecency Points","symbol":"POINTS","layer":"points","balance":1250.5,"fiatRate":0.002,"currency":"USD","precision":3,"actions":[{"id":"ecency_point_transfer"},{"id":"promote"},{"id":"boost"}],"pendingRewards":0.75,"liquid":1250.5},
        {"name":"Staked Hive","symbol":"HP","layer":"hive","balance":2500.0,"fiatRate":0.25,"currency":"USD","precision":3,"actions":[{"id":"delegate_vesting_shares"},{"id":"withdraw_vesting"},{"id":"set_withdraw_vesting_route"}],
         "extraData":[{"dataKey":"received_hive_power","value":"+ 150.000 HP"},{"dataKey":"delegated_hive_power","value":"- 125.000 HP"},{"dataKey":"net_hive_power","value":"2,525.000 HP"}],"pendingRewards":1.024,"apr":4.594,"liquid":2475.0,"staked":2500.0},
        {"name":"Hive","symbol":"HIVE","layer":"hive","balance":1284.567,"fiatRate":0.25,"currency":"USD","precision":3,"actions":[{"id":"transfer"},{"id":"transfer_to_savings"},{"id":"transfer_to_vesting"},{"id":"swap_token"},{"id":"recurrent_transfer"}],"pendingRewards":0.512,"liquid":1234.567,"savings":50.0},
        {"name":"Hive Dollar","symbol":"HBD","layer":"hive","balance":389.012,"fiatRate":1,"currency":"USD","precision":3,"actions":[{"id":"transfer"},{"id":"transfer_to_savings"},{"id":"transfer_from_savings"},{"id":"convert"},{"id":"swap_token"}],"pendingRewards":1.204,"apr":15,"liquid":89.012,"savings":300.0},
        {"name":"Leo","symbol":"LEO","layer":"engine","balance":162.125,"fiatRate":0.08,"currency":"USD","precision":3,"actions":[{"id":"transfer"},{"id":"stake"},{"id":"unstake"},{"id":"delegate"}],"extraData":[{"dataKey":"delegations_out","value":"0.00"}],"pendingRewards":0,"liquid":42.125,"staked":120.0},
        {"name":"BNB Chain","symbol":"BNB","layer":"chain","balance":0,"fiatRate":600,"currency":"USD","precision":18,"actions":[{"id":"receive"}],"address":"0x0000000000000000000000000000000000000000"}
    ]}"""

    /** A demo account's profile tokens (posting_json_metadata.profile.tokens). */
    val PROFILE_TOKENS: JsonArray = buildJsonArray {
        add(buildJsonObject { put("symbol", "LEO"); put("type", "ENGINE"); put("meta", buildJsonObject { put("show", true) }) })
    }

    internal fun withTokens(profile: JsonObject): JsonObject = JsonObject(profile + ("tokens" to PROFILE_TOKENS))
}
