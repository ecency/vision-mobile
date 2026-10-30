package com.ecency.core.api.wallet

import com.ecency.core.api.EcencyException
import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.EcencyRestApi
import com.ecency.core.api.guarded
import com.ecency.core.api.json.SafeJson
import com.ecency.core.api.model.arr
import com.ecency.core.api.model.asDouble
import com.ecency.core.api.model.asLong
import com.ecency.core.api.model.int
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.api.rpc.HiveRpcClient
import com.ecency.core.auth.AuthHttp
import com.ecency.core.auth.AuthHttpRequest
import com.ecency.core.auth.EcencyPrivateApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.coroutines.cancellation.CancellationException

/** A pending savings withdrawal (get_savings_withdraw_from). */
data class SavingsWithdrawal(val requestId: Long, val from: String, val to: String, val amount: String, val memo: String, val complete: String)

/** An HBD to HIVE conversion waiting for its date; [collateralized] for HIVE to HBD with collateral. */
data class ConversionRequest(val requestId: Long, val amount: String, val date: String, val collateralized: Boolean)

/** An internal market order still open (get_open_orders). */
data class OpenOrder(val orderId: Long, val created: String, val expiration: String, val base: String, val quote: String, val forSale: Long)

/** A recurrent transfer schedule (find_recurrent_transfers). */
data class RecurrentSchedule(
    val id: Long,
    val to: String,
    val amount: String,
    val memo: String,
    val recurrence: Int,
    val remainingExecutions: Int,
    val triggerDate: String,
)

/** A power down route (get_withdraw_routes outgoing); [percent] in chain units (10000 is 100%). */
data class WithdrawRoute(val toAccount: String, val percent: Int, val autoVest: Boolean)

/** An outgoing HP delegation; [expiring] rows are undelegated VESTS in their 5 day cooldown (until [timestamp]). */
data class Delegation(val account: String, val vestingShares: String, val timestamp: String?, val expiring: Boolean = false)

/** One price level of the internal market book (amounts in milli units, as hived sends them). */
data class BookOrder(val hive: Long, val hbd: Long, val realPrice: Double)

data class OrderBook(val bids: List<BookOrder>, val asks: List<BookOrder>)

/** A Hive Engine token's contract data (TOKENS table). */
data class EngineToken(val symbol: String, val name: String, val precision: Int, val stakingEnabled: Boolean, val delegationEnabled: Boolean)

/** One Ecency Points history row (`/private-api/point-list`). */
data class PointTransaction(val id: Long, val type: Int, val amount: String, val sender: String?, val receiver: String?, val memo: String?, val created: String)

/** One balance-history row: the raw balance after the operation (HIVE and HBD x 1e3, VESTS x 1e6) and its time. */
data class BalanceHistoryEntry(val raw: Double, val timestamp: String)

/** A page of balance history, newest first, and its page number (pages count down to 1 going back in time). */
data class BalanceHistoryPage(val entries: List<BalanceHistoryEntry>, val currentPage: Int) {
    /** The page before this one in time, or null at the start of the history. */
    val olderPage: Int? get() = (currentPage - 1).takeIf { it >= 1 }

    companion object {
        val COINS = listOf("HIVE", "HBD", "VESTS")
        val GRANULARITIES = listOf("yearly", "monthly", "daily")

        /** Hive account names: nothing that would leave the URL path. */
        fun validAccount(name: String) = name.length in 3..16 && name.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }
    }
}

/** One aggregated period (yearly, monthly, daily): the raw closing, lowest and highest balance. */
data class AggregatedBalance(val date: String, val raw: Double, val minRaw: Double, val maxRaw: Double)

/** A price row of the boost+ or RC top-up lists. */
data class DurationPrice(val duration: Int, val price: Double)

/** Tips on a post (`/private-api/post-tips`): count and totals per currency. */
data class PostTips(val count: Int, val totals: List<Pair<String, String>>)

/**
 * The wallet's reads, as @ecency/sdk 2.4.26 makes them for the RN app: Hive RPC for the account, history and pending
 * requests, the balance-api for incoming delegations, Ecency's wallet API for the portfolio, the private API for
 * points and the paid lists, and the Hive Engine proxy. No call here writes anything except [claimPoints].
 */
class WalletApi(
    private val client: HiveRpcClient,
    private val rest: EcencyRestApi,
    private val http: AuthHttp,
    private val privateApi: EcencyPrivateApi,
    val host: String = "https://ecency.com",
    private val restNodes: List<String> = REST_NODES,
) {
    // ---- chain ----

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun account(name: String): WalletAccount? = guarded {
        client.call("condenser_api.get_accounts", buildJsonArray { add(buildJsonArray { add(JsonPrimitive(name)) }) }, validate = { it is JsonArray })
            .arr()?.firstOrNull()?.let(WalletAccount::fromJson)
    }

    /** SDK getDynamicPropsQueryOptions' hivePerMVests: total_vesting_fund_hive / total_vesting_shares * 1e6. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun hivePerMVests(): Double = guarded {
        val p = client.call("condenser_api.get_dynamic_global_properties").obj()
        val fund = Vests.amount(p?.str("total_vesting_fund_hive"))
        val shares = Vests.amount(p?.str("total_vesting_shares"))
        if (shares == 0.0) 0.0 else fund / shares * 1e6
    }

    /**
     * `condenser_api.get_account_history [user, start, limit, low, high]` (SDK getHiveAssetTransactionsQueryOptions):
     * [start] -1 for the newest; [limit] narrowed to start + 1 for a non-negative cursor (the node asserts
     * start >= limit - 1). Rows as the chain sends them: ascending by index.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun history(account: String, start: Long, limit: Int, operations: List<String>): List<HistoryRow> = guarded {
        val (low, high) = WalletHistory.mask(operations)
        val narrowed = if (start < 0) limit.toLong() else minOf(limit.toLong(), start + 1)
        client.call(
            "condenser_api.get_account_history",
            buildJsonArray {
                add(JsonPrimitive(account)); add(JsonPrimitive(start)); add(JsonPrimitive(narrowed))
                add(low?.let { JsonPrimitive(it) } ?: JsonNull); add(high?.let { JsonPrimitive(it) } ?: JsonNull)
            },
        ).arr().orEmpty().mapNotNull(HistoryRow::fromJson)
    }

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun savingsWithdrawals(account: String): List<SavingsWithdrawal> = guarded {
        client.call("condenser_api.get_savings_withdraw_from", one(account)).arr().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            SavingsWithdrawal(o["request_id"].asLong() ?: return@mapNotNull null, o.str("from").orEmpty(), o.str("to").orEmpty(), o.str("amount").orEmpty(), o.str("memo").orEmpty(), o.str("complete").orEmpty())
        }.sortedBy { it.requestId }
    }

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun conversions(account: String): List<ConversionRequest> = guarded {
        val plain = client.call("condenser_api.get_conversion_requests", one(account)).arr().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            ConversionRequest(o["requestid"].asLong() ?: return@mapNotNull null, o.str("amount").orEmpty(), o.str("conversion_date").orEmpty(), false)
        }
        val collateral = runCatching {
            client.call("condenser_api.get_collateralized_conversion_requests", one(account)).arr().orEmpty().mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                ConversionRequest(o["requestid"].asLong() ?: return@mapNotNull null, o.str("collateral_amount").orEmpty(), o.str("conversion_date").orEmpty(), true)
            }
        }.getOrElse { if (it is CancellationException) throw it else emptyList() }
        (plain + collateral).sortedBy { it.requestId }
    }

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun openOrders(account: String): List<OpenOrder> = guarded {
        client.call("condenser_api.get_open_orders", one(account)).arr().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val price = o["sell_price"].obj()
            OpenOrder(
                o["orderid"].asLong() ?: return@mapNotNull null, o.str("created").orEmpty(), o.str("expiration").orEmpty(),
                price?.str("base").orEmpty(), price?.str("quote").orEmpty(), o["for_sale"].asLong() ?: 0,
            )
        }.sortedBy { it.orderId }
    }

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun recurrentTransfers(account: String): List<RecurrentSchedule> = guarded {
        client.call("condenser_api.find_recurrent_transfers", one(account)).arr().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            RecurrentSchedule(
                o["id"].asLong() ?: 0, o.str("to") ?: return@mapNotNull null, o.str("amount").orEmpty(), o.str("memo").orEmpty(),
                o.int("recurrence") ?: 24, o.int("remaining_executions") ?: 0, o.str("trigger_date").orEmpty(),
            )
        }
    }

    @Throws(EcencyException::class, CancellationException::class)
    suspend fun withdrawRoutes(account: String): List<WithdrawRoute> = guarded {
        client.call("condenser_api.get_withdraw_routes", buildJsonArray { add(JsonPrimitive(account)); add(JsonPrimitive("outgoing")) }).arr().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            WithdrawRoute(o.str("to_account") ?: return@mapNotNull null, o.int("percent") ?: 0, (o["auto_vest"] as? JsonPrimitive)?.content == "true")
        }
    }

    /**
     * Every outgoing delegation (SDK getVestingDelegationsQueryOptions, RN fetchExistingDelegation and the
     * delegations modal): pages of [pageSize] from the last delegatee, the repeated first row dropped, until a short
     * page; at most [maxPages].
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun outgoingDelegations(account: String, pageSize: Int = 1000, maxPages: Int = 20): List<Delegation> = guarded {
        val all = LinkedHashMap<String, Delegation>()
        var from = ""
        for (page in 0 until maxPages) {
            val rows = client.call(
                "condenser_api.get_vesting_delegations",
                buildJsonArray { add(JsonPrimitive(account)); add(JsonPrimitive(from)); add(JsonPrimitive(pageSize)) },
            ).arr().orEmpty().mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                Delegation(o.str("delegatee") ?: return@mapNotNull null, o.str("vesting_shares").orEmpty(), o.str("min_delegation_time"))
            }
            val fresh = rows.filter { it.account !in all }
            fresh.forEach { all[it.account] = it }
            if (rows.size < pageSize || fresh.isEmpty()) break
            from = rows.last().account
        }
        all.values.toList()
    }

    /** `database_api.find_vesting_delegation_expirations {account}`: undelegated VESTS in their cooldown. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun expiringDelegations(account: String): List<Delegation> = guarded {
        client.call("database_api.find_vesting_delegation_expirations", buildJsonObject { put("account", account) }).obj()
            ?.get("delegations").arr().orEmpty().mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                val v = o["vesting_shares"].obj()
                val amount = v?.get("amount").asLong() ?: return@mapNotNull null
                val precision = v?.get("precision").asLong()?.toInt() ?: 6
                Delegation("expiring-${o["id"].asLong() ?: amount}", "${naiText(amount, precision)} VESTS", o.str("expiration"), expiring = true)
            }
    }

    /**
     * SDK getReceivedVestingSharesQueryOptions: the balance-api's `incoming_delegations` (raw VESTS x 1e6), largest
     * first. A read-only REST call to the public nodes, the first that answers.
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun incomingDelegations(account: String): List<Delegation> {
        val body = restGet("/balance-api/accounts/${account}/delegations")
        return body.obj()?.get("incoming_delegations").arr().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val raw = o["amount"].asLong() ?: return@mapNotNull null
            (o.str("delegator") ?: return@mapNotNull null) to raw
        }.sortedByDescending { it.second }.map { (who, raw) -> Delegation(who, "${naiText(raw, 6)} VESTS", null) }
    }

    /**
     * SDK getBalanceHistoryInfiniteQueryOptions: `GET /balance-api/accounts/{name}/balance-history` with `coin-type`,
     * `page-size`, `direction=desc` and, after the first page, `page` (the first answer names `total_pages`, its own
     * number; older pages count down to 1). Entries newest first, raw amounts (3 decimals, VESTS 6).
     */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun balanceHistory(account: String, coinType: String, pageSize: Int, page: Int?): BalanceHistoryPage {
        require(BalanceHistoryPage.validAccount(account) && coinType in BalanceHistoryPage.COINS)
        val query = "coin-type=$coinType&page-size=$pageSize&direction=desc" + (page?.let { "&page=$it" } ?: "")
        val o = restGet("/balance-api/accounts/$account/balance-history?$query").obj()
        val entries = o?.get("operations_result").arr().orEmpty().mapNotNull { e ->
            val r = e.obj() ?: return@mapNotNull null
            val raw = r["balance"].asDouble() ?: return@mapNotNull null
            BalanceHistoryEntry(raw, r.str("timestamp") ?: return@mapNotNull null)
        }
        return BalanceHistoryPage(entries, page ?: (o?.int("total_pages") ?: 0))
    }

    /** SDK getAggregatedBalanceQueryOptions: `GET /balance-api/accounts/{name}/aggregated-history`, newest period first. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun aggregatedBalance(account: String, coinType: String, granularity: String): List<AggregatedBalance> {
        require(BalanceHistoryPage.validAccount(account) && coinType in BalanceHistoryPage.COINS && granularity in BalanceHistoryPage.GRANULARITIES)
        return restGet("/balance-api/accounts/$account/aggregated-history?coin-type=$coinType&granularity=$granularity").arr().orEmpty().mapNotNull { e ->
            val r = e.obj() ?: return@mapNotNull null
            fun raw(k: String) = r[k].obj()?.get("balance").asDouble()
            AggregatedBalance(r.str("date").orEmpty(), raw("balance") ?: return@mapNotNull null, raw("min_balance") ?: 0.0, raw("max_balance") ?: 0.0)
        }
    }

    /** `condenser_api.get_order_book [500]` (SDK getOrderBookQueryOptions). */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun orderBook(limit: Int = 500): OrderBook = guarded {
        val o = client.call("condenser_api.get_order_book", buildJsonArray { add(JsonPrimitive(limit)) }).obj()
        fun side(k: String) = o?.get(k).arr().orEmpty().mapNotNull { e ->
            val r = e.obj() ?: return@mapNotNull null
            BookOrder(r["hive"].asLong() ?: 0, r["hbd"].asLong() ?: 0, r["real_price"].asDouble() ?: return@mapNotNull null)
        }
        OrderBook(side("bids"), side("asks"))
    }

    /** `condenser_api.get_ticker` lowest_ask (HBD per HIVE). */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun lowestAsk(): Double? = guarded {
        client.call("condenser_api.get_ticker").obj()?.get("lowest_ask").asDouble()?.takeIf { it.isFinite() && it > 0 }
    }

    // ---- Ecency ----

    /** `POST {host}/wallet-api/portfolio-v2 {username, onlyEnabled, currency}` (SDK getPortfolioQueryOptions). */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun portfolio(username: String, currency: String, onlyEnabled: Boolean): List<PortfolioItem> {
        val body = rest.post("$host/wallet-api/portfolio-v2", buildJsonObject {
            put("username", username); put("onlyEnabled", onlyEnabled); put("currency", currency)
        })
        val fiat = (body.obj()?.str("fiatCurrency") ?: body.obj()?.str("currency") ?: currency).uppercase()
        val items = PortfolioItem.list(body).mapNotNull { PortfolioItem.fromJson(it, fiat) }
            // RN: SPK support removed
            .filter { it.symbol.uppercase() !in setOf("SPK", "LARYNX", "LP") }
        if (items.isEmpty()) throw EcencyHttpException(200, "Portfolio payload contained no tokens")
        return items
    }

    /** `POST /private-api/points {username}`: balance and unclaimed points. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun points(username: String): Pair<String, String> {
        val o = rest.post("$host/private-api/points", buildJsonObject { put("username", username) }).obj()
        // a 2xx without a balance is a shape change or an error body, not an empty wallet
        val points = o?.str("points") ?: throw EcencyHttpException(200, "Points payload contained no balance")
        return points to (o.str("unclaimed_points") ?: "0.000")
    }

    /** `POST /private-api/point-list {username, type}` (0: every type; web's points filter sends one), in the API's order. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun pointHistory(username: String, type: Int = 0): List<PointTransaction> =
        rest.post("$host/private-api/point-list", buildJsonObject { put("username", username); put("type", type) }).arr().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            PointTransaction(o["id"].asLong() ?: return@mapNotNull null, o.int("type") ?: 0, o.str("amount").orEmpty(), o.str("sender"), o.str("receiver"), o.str("memo"), o.str("created").orEmpty())
        }

    /** `POST /private-api/points-claim {code}` (claims the unclaimed points). A 406 answers with a body. */
    @Throws(Exception::class)
    suspend fun claimPoints(username: String) {
        val result = privateApi.post("points-claim", username)
        if (result.status !in 200..299 && result.status != 406) throw EcencyHttpException(result.status, SafeJson.parseOrNull(result.body).obj()?.str("message") ?: "HTTP ${result.status}")
    }

    /** `POST /private-api/boost-plus-price {code}` or `rc-delegation-price`: `[{duration, price}]`. */
    @Throws(Exception::class)
    suspend fun prices(username: String, path: String): List<DurationPrice> {
        val result = privateApi.post(path, username)
        if (result.status !in 200..299) throw EcencyHttpException(result.status)
        return SafeJson.parseOrNull(result.body).arr().orEmpty().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val d = o["duration"].asDouble()?.takeIf { it.isFinite() }?.toInt() ?: return@mapNotNull null
            DurationPrice(d, o["price"].asDouble()?.takeIf { it.isFinite() } ?: return@mapNotNull null)
        }
    }

    /** `POST /private-api/boosted-plus-account {code, account}`: when an active boost ends (epoch millis), or null. */
    @Throws(Exception::class)
    suspend fun boostedUntil(username: String, account: String): String? {
        val result = privateApi.post("boosted-plus-account", username, buildJsonObject { put("account", account) })
        if (result.status !in 200..299) return null
        return SafeJson.parseOrNull(result.body).obj()?.str("expires")
    }

    /** `GET /private-api/post-tips/{author}/{permlink}`. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun postTips(author: String, permlink: String): PostTips {
        val o = rest.get("$host/private-api/post-tips/$author/$permlink").obj()
        val meta = o?.get("meta").obj()
        return PostTips(meta?.int("count") ?: 0, meta?.get("totals").obj()?.entries?.map { (k, v) -> k to ((v as? JsonPrimitive)?.content ?: "") }.orEmpty())
    }

    // ---- Hive Engine (through Ecency's proxy) ----

    /** `POST /private-api/engine-api` find on tokens/tokens: precision and flags of [symbols]. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun engineTokens(symbols: List<String>): List<EngineToken> = engineFind("tokens", "tokens", buildJsonObject {
        putJsonObject("symbol") { put("\$in", JsonArray(symbols.map(::JsonPrimitive))) }
    }).mapNotNull { e ->
        val o = e.obj() ?: return@mapNotNull null
        EngineToken(
            o.str("symbol") ?: return@mapNotNull null, o.str("name").orEmpty(), o.int("precision") ?: return@mapNotNull null,
            (o["stakingEnabled"] as? JsonPrimitive)?.content == "true", (o["delegationEnabled"] as? JsonPrimitive)?.content == "true",
        )
    }

    /** Engine balances of [account]: symbol to (balance, stake) as exact text. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun engineBalances(account: String): Map<String, Pair<String, String>> = engineFind("tokens", "balances", buildJsonObject { put("account", account) })
        .mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            (o.str("symbol") ?: return@mapNotNull null) to ((o.str("balance") ?: "0") to (o.str("stake") ?: "0"))
        }.toMap()

    /** `GET /private-api/engine-account-history?account&symbol&limit&offset`. */
    @Throws(EcencyException::class, CancellationException::class)
    suspend fun engineHistory(account: String, symbol: String, limit: Int, offset: Int): List<JsonObject> =
        rest.get("$host/private-api/engine-account-history?account=$account&symbol=$symbol&limit=$limit&offset=$offset").arr().orEmpty().mapNotNull { it.obj() }

    private suspend fun engineFind(contract: String, table: String, query: JsonObject): List<JsonElement> {
        val body = rest.post("$host/private-api/engine-api", buildJsonObject {
            put("jsonrpc", "2.0"); put("method", "find")
            putJsonObject("params") { put("contract", contract); put("table", table); put("query", query) }
            put("id", 1)
        })
        return body.obj()?.get("result").arr()?.toList() ?: emptyList()
    }

    private suspend fun restGet(path: String): JsonElement? {
        var last: Exception? = null
        for (node in restNodes) {
            try {
                val r = http.send(AuthHttpRequest("GET", node + path, mapOf("Accept" to "application/json"), timeoutMillis = 10_000))
                if (r.status in 200..299) return SafeJson.parseOrNull(r.body)
                last = EcencyHttpException(r.status)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw (last as? EcencyException) ?: EcencyHttpException.ofSendFailure(last ?: IllegalStateException("no REST node"))
    }

    private fun one(account: String) = buildJsonArray { add(JsonPrimitive(account)) }

    companion object {
        /** SDK hive-tx REST nodes (callREST). */
        val REST_NODES = listOf("https://api.hive.blog", "https://rpc.mahdiyari.info", "https://api.syncad.com")

        /** An integer amount with [precision] implied decimals as text ("123456789", 6 is "123.456789"). */
        fun naiText(amount: Long, precision: Int): String {
            if (precision <= 0) return amount.toString()
            val digits = amount.toString().padStart(precision + 1, '0')
            return digits.dropLast(precision) + "." + digits.takeLast(precision)
        }
    }
}
