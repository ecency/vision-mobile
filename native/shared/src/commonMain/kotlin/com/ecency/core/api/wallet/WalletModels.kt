package com.ecency.core.api.wallet

import com.ecency.core.api.model.arr
import com.ecency.core.api.model.asDouble
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Where an asset lives (portfolio-v2 `layer`). */
enum class AssetLayer(val value: String) {
    POINTS("points"), HIVE("hive"), ENGINE("engine"), CHAIN("chain");

    companion object {
        fun of(value: String?): AssetLayer? = entries.firstOrNull { it.value == value?.lowercase() }
    }
}

/** One extra row of an asset (portfolio-v2 `extraData`): label key, value text and optional sub value. */
data class AssetExtra(val dataKey: String, val value: String, val subValue: String? = null)

/**
 * One asset of the portfolio (SDK PortfolioItem). Every number is the server's: the wallet API computes balances,
 * fiat rates, APR, pending rewards and the actions per asset; the app formats them.
 */
data class PortfolioItem(
    val name: String,
    val symbol: String,
    val layer: AssetLayer,
    val balance: Double,
    val fiatRate: Double?,
    val currency: String,
    val precision: Int,
    val iconUrl: String? = null,
    val address: String? = null,
    val pendingRewards: Double = 0.0,
    val liquid: Double? = null,
    val savings: Double? = null,
    val staked: Double? = null,
    val apr: Double? = null,
    val actions: List<String> = emptyList(),
    val extraData: List<AssetExtra> = emptyList(),
) {
    fun extra(key: String): AssetExtra? = extraData.firstOrNull { it.dataKey == key }

    companion object {
        /** SDK parseToken: tolerant, null without a symbol. */
        fun fromJson(element: JsonElement?, fallbackCurrency: String): PortfolioItem? {
            val o = element.obj() ?: return null
            val symbol = (o.str("symbol") ?: o.str("ticker"))?.takeIf { it.isNotBlank() } ?: return null
            val layer = AssetLayer.of(o.str("layer")) ?: return null
            fun num(k: String): Double? = o[k].asDouble()?.takeIf { it.isFinite() }
            return PortfolioItem(
                name = o.str("name") ?: symbol,
                symbol = symbol,
                layer = layer,
                balance = num("balance") ?: num("accountBalance") ?: 0.0,
                fiatRate = num("fiatRate"),
                currency = (o.str("currency") ?: fallbackCurrency).uppercase(),
                precision = o["precision"].asDouble()?.toInt()?.takeIf { it in 0..18 } ?: 3,
                iconUrl = o.str("iconUrl")?.takeIf { it.startsWith("https://") },
                address = o.str("address"),
                pendingRewards = num("pendingRewards") ?: 0.0,
                liquid = num("liquid"),
                savings = num("savings"),
                staked = num("staked"),
                apr = num("apr"),
                actions = o["actions"].arr().orEmpty().mapNotNull { a ->
                    when (a) {
                        is JsonPrimitive -> a.content.takeIf { a.isString }
                        else -> a.obj()?.str("id")
                    }
                },
                extraData = o["extraData"].arr().orEmpty().mapNotNull { e ->
                    val x = e.obj() ?: return@mapNotNull null
                    AssetExtra(x.str("dataKey") ?: return@mapNotNull null, x.str("value").orEmpty(), x.str("subValue"))
                },
            )
        }

        /** SDK extractTokens: the list under one of the known keys, at the root or under data/result/portfolio. */
        internal fun list(root: JsonElement?): List<JsonElement> {
            val keys = listOf("wallets", "tokens", "assets", "items", "portfolio", "balances")
            fun under(o: JsonObject?): List<JsonElement>? = o?.let { obj -> keys.firstNotNullOfOrNull { obj[it].arr()?.toList() } }
            root.arr()?.let { return it.toList() }
            val o = root.obj() ?: return emptyList()
            return under(o) ?: listOf("data", "result", "portfolio").firstNotNullOfOrNull { k -> o[k].arr()?.toList() ?: under(o[k].obj()) } ?: emptyList()
        }
    }
}

/** The account fields the wallet's screens need (condenser_api.get_accounts), amounts as the chain prints them. */
data class WalletAccount(
    val name: String,
    val balance: String = "0.000 HIVE",
    val hbdBalance: String = "0.000 HBD",
    val savingsBalance: String = "0.000 HIVE",
    val savingsHbdBalance: String = "0.000 HBD",
    val rewardHive: String = "0.000 HIVE",
    val rewardHbd: String = "0.000 HBD",
    val rewardVests: String = "0.000000 VESTS",
    val vestingShares: String = "0.000000 VESTS",
    val delegatedVestingShares: String = "0.000000 VESTS",
    val receivedVestingShares: String = "0.000000 VESTS",
    val vestingWithdrawRate: String = "0.000000 VESTS",
    /** Chain time of the next power down payment; "1969-12-31T23:59:59" when none runs. */
    val nextVestingWithdrawal: String = NO_WITHDRAWAL,
    /** Raw VESTS units (x 1e6). */
    val toWithdraw: Double = 0.0,
    val withdrawn: Double = 0.0,
    /** The profile's `tokens` list (posting_json_metadata), for the manage assets selection. */
    val profileTokens: List<ProfileToken> = emptyList(),
    /** The HIVE value of the unclaimed VESTS reward (chain `reward_vesting_hive`). */
    val rewardVestingHive: String = "0.000 HIVE",
) {
    val isPoweringDown: Boolean get() = nextVestingWithdrawal.isNotEmpty() && !nextVestingWithdrawal.startsWith("1969") && !nextVestingWithdrawal.startsWith("1970-01-01T00:00:00")

    companion object {
        const val NO_WITHDRAWAL = "1969-12-31T23:59:59"

        fun fromJson(element: JsonElement?): WalletAccount? {
            val o = element.obj() ?: return null
            val name = o.str("name")?.takeIf { it.isNotBlank() } ?: return null
            fun a(k: String, d: String) = o.str(k) ?: d
            return WalletAccount(
                name = name,
                balance = a("balance", "0.000 HIVE"),
                hbdBalance = a("hbd_balance", "0.000 HBD"),
                savingsBalance = a("savings_balance", "0.000 HIVE"),
                savingsHbdBalance = a("savings_hbd_balance", "0.000 HBD"),
                rewardHive = a("reward_hive_balance", "0.000 HIVE"),
                rewardHbd = a("reward_hbd_balance", "0.000 HBD"),
                rewardVests = a("reward_vesting_balance", "0.000000 VESTS"),
                vestingShares = a("vesting_shares", "0.000000 VESTS"),
                delegatedVestingShares = a("delegated_vesting_shares", "0.000000 VESTS"),
                receivedVestingShares = a("received_vesting_shares", "0.000000 VESTS"),
                vestingWithdrawRate = a("vesting_withdraw_rate", "0.000000 VESTS"),
                nextVestingWithdrawal = a("next_vesting_withdrawal", NO_WITHDRAWAL),
                toWithdraw = o["to_withdraw"].asDouble() ?: 0.0,
                withdrawn = o["withdrawn"].asDouble() ?: 0.0,
                profileTokens = ProfileToken.fromMetadata(o["posting_json_metadata"]),
                rewardVestingHive = a("reward_vesting_hive", "0.000 HIVE"),
            )
        }
    }
}

/** One entry of `profile.tokens` (RN ProfileToken): `{symbol, type: ENGINE|HIVE|CHAIN, meta: {show, address?}}`. */
data class ProfileToken(val symbol: String, val type: String, val show: Boolean, val raw: JsonObject?) {
    companion object {
        /**
         * RN migrateSelectedTokens + populateSelectedAssets: the old `{engine: [...]}` object becomes ENGINE entries,
         * entries without meta show, duplicates by symbol and type are dropped.
         */
        fun fromMetadata(metadata: JsonElement?): List<ProfileToken> {
            val tokens = metadata.obj()?.get("profile").obj()?.get("tokens") ?: return emptyList()
            val list = tokens.arr()?.mapNotNull { t ->
                val o = t.obj() ?: return@mapNotNull null
                val symbol = o.str("symbol")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val type = o.str("type")?.uppercase() ?: "ENGINE"
                val show = o["meta"].obj()?.get("show")?.let { (it as? JsonPrimitive)?.content != "false" } ?: true
                ProfileToken(symbol, type, show, o)
            } ?: tokens.obj()?.get("engine").arr()?.mapNotNull { t ->
                val symbol = (t as? JsonPrimitive)?.content ?: t.obj()?.str("symbol") ?: return@mapNotNull null
                ProfileToken(symbol, "ENGINE", true, null)
            } ?: emptyList()
            return list.distinctBy { "${it.symbol}-${it.type}" }
        }
    }
}

/** Hive Power from VESTS (RN utils/conversions): vests / 1e6 * hivePerMVests. */
object Vests {
    fun toHp(vests: Double, hivePerMVests: Double): Double = if (vests == 0.0 || hivePerMVests == 0.0) 0.0 else vests / 1e6 * hivePerMVests

    fun fromHp(hp: Double, hivePerMVests: Double): Double = if (hp == 0.0 || hivePerMVests == 0.0) 0.0 else hp * 1e6 / hivePerMVests

    /** RN parseToken: the leading number of "1.234 HIVE" (0 when unreadable). */
    fun amount(asset: String?): Double = asset?.trim()?.substringBefore(' ')?.replace(",", "")?.toDoubleOrNull() ?: 0.0

    fun symbol(asset: String?): String = asset?.trim()?.substringAfter(' ', "").orEmpty().trim()
}
