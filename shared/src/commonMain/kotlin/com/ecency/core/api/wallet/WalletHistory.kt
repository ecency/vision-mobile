package com.ecency.core.api.wallet

import com.ecency.core.api.model.asDouble
import com.ecency.core.api.model.asLong
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.app.AppIcon
import com.ecency.core.app.Num
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One `get_account_history` entry: its index, operation name, time, transaction id and fields. */
data class HistoryRow(val num: Long, val type: String, val timestamp: String, val trxId: String, val fields: JsonObject) {
    fun s(key: String): String? = fields.str(key)

    companion object {
        fun fromJson(e: JsonElement): HistoryRow? {
            val pair = e as? JsonArray ?: return null
            val num = pair.getOrNull(0).asLong() ?: return null
            val body = pair.getOrNull(1).obj() ?: return null
            val op = body["op"] as? JsonArray ?: return null
            val type = (op.getOrNull(0) as? JsonPrimitive)?.content ?: return null
            return HistoryRow(num, type, body.str("timestamp").orEmpty(), body.str("trx_id").orEmpty(), op.getOrNull(1).obj() ?: JsonObject(emptyMap()))
        }
    }
}

/**
 * A wallet history row as the RN app shows it (CoinActivity): label key, icon, value text, details, memo, and what
 * repeating it needs. [trxIndex] orders chain rows; [trxId] is the explorer id when there is one.
 */
data class CoinActivity(
    val textKey: String,
    val icon: AppIcon,
    val value: String,
    val created: String,
    val trxIndex: Long?,
    val trxId: String? = null,
    val details: String? = null,
    val memo: String? = null,
    val sender: String? = null,
    val receiver: String? = null,
    val repeatable: Boolean = false,
    val recurrence: String? = null,
    val executions: String? = null,
    val expires: String? = null,
    val cancelable: Boolean = false,
    /** Engine rows: the engine's own id (dedupe key). */
    val engineId: String? = null,
) {
    /** The row's key in lists (RN `activity_item_<engineTrxId ?? trxIndex>_<created>`). */
    val key: String get() = "${engineId ?: trxIndex?.toString() ?: ""}_$created"

    /**
     * [value] as the row shows it: its amounts in the app language ("1,500 HBD" in de-DE). [value] itself stays the
     * chain's POSIX text, since a repeated transfer reads its amount from it.
     */
    val displayValue: String = LocaleFormat.amountsIn(value.trim())
}

/**
 * RN wallet history (utils/wallet.ts groomingTransactionData, utils/walletHistory.ts) over SDK 2.4.26's per-asset
 * select filters: which operations each tab asks for, the bitmask, the row text, and the order (rows arrive
 * ascending by index page by page; the list is newest first by index, deduped by it).
 */
object WalletHistory {
    /** hive-tx `utils.operations` (the operation ids of the history bitmask). */
    val OPERATION_IDS: Map<String, Int> = listOf(
        "vote", "comment", "transfer", "transfer_to_vesting", "withdraw_vesting", "limit_order_create", "limit_order_cancel", "feed_publish",
        "convert", "account_create", "account_update", "witness_update", "account_witness_vote", "account_witness_proxy", "pow", "custom",
        "report_over_production", "delete_comment", "custom_json", "comment_options", "set_withdraw_vesting_route", "limit_order_create2",
        "claim_account", "create_claimed_account", "request_account_recovery", "recover_account", "change_recovery_account", "escrow_transfer",
        "escrow_dispute", "escrow_release", "pow2", "escrow_approve", "transfer_to_savings", "transfer_from_savings", "cancel_transfer_from_savings",
        "custom_binary", "decline_voting_rights", "reset_account", "set_reset_account", "claim_reward_balance", "delegate_vesting_shares",
        "account_create_with_delegation", "witness_set_properties", "account_update2", "create_proposal", "update_proposal_votes",
        "remove_proposal", "update_proposal", "collateralized_convert", "recurrent_transfer", "fill_convert_request", "author_reward",
        "curation_reward", "comment_reward", "liquidity_reward", "interest", "fill_vesting_withdraw", "fill_order", "shutdown_witness",
        "fill_transfer_from_savings", "hardfork", "comment_payout_update", "return_vesting_delegation", "comment_benefactor_reward",
        "producer_reward", "clear_null_account_balance", "proposal_pay", "sps_fund", "hardfork_hive", "hardfork_hive_restore", "delayed_voting",
        "consolidate_treasury_balance", "effective_comment_vote", "ineffective_delete_comment", "sps_convert", "expired_account_notification",
        "changed_recovery_account", "transfer_to_vesting_completed", "pow_reward", "vesting_shares_split", "account_created",
        "fill_collateralized_convert_request", "system_warning", "fill_recurrent_transfer", "failed_recurrent_transfer", "limit_order_cancelled",
        "producer_missed", "proposal_fee", "collateralized_convert_immediate_conversion", "escrow_approved", "escrow_rejected", "proxy_cleared",
        "declined_voting_rights",
    ).withIndex().associate { (i, name) -> name to i }

    private val BASE = listOf(
        "transfer", "transfer_to_savings", "transfer_from_savings", "fill_transfer_from_savings", "recurrent_transfer",
        "fill_recurrent_transfer", "claim_reward_balance", "author_reward", "comment_benefactor_reward",
    )

    /** RN HIVE_LAYER_HISTORY_OPS. */
    val OPS: Map<String, List<String>> = mapOf(
        "HIVE" to BASE + listOf("transfer_to_vesting", "fill_vesting_withdraw", "fill_order", "fill_convert_request"),
        // interest and proposal pay as web's HBD history has them (RN leaves them out)
        "HBD" to BASE + listOf("fill_order", "fill_convert_request", "interest", "proposal_pay"),
        "HP" to listOf(
            "claim_reward_balance", "author_reward", "comment_benefactor_reward", "curation_reward", "transfer_to_vesting",
            "withdraw_vesting", "fill_vesting_withdraw", "delegate_vesting_shares",
        ),
    )

    fun opsFor(symbol: String): List<String> = OPS[symbol] ?: OPS.getValue("HIVE")

    /** The categories [symbol]'s history offers: All, then each one that holds an operation of the asset. */
    fun categoriesFor(symbol: String): List<HistoryCategory> =
        HistoryCategory.entries.filter { it == HistoryCategory.ALL || it.opsFor(symbol).isNotEmpty() }

    /** The category a picked operation set is (All for none), or null for a set the filter sheet made. */
    fun categoryOf(symbol: String, filter: List<String>?): HistoryCategory? {
        if (filter.isNullOrEmpty()) return HistoryCategory.ALL
        val picked = filter.toSet()
        return categoriesFor(symbol).firstOrNull { it != HistoryCategory.ALL && it.opsFor(symbol).toSet() == picked }
    }

    /** Web's points history types (`PointTransactionType`), in web's order; 0 is every type. */
    val POINT_TYPES: List<Int> = listOf(10, 20, 30, 100, 110, 120, 130, 150, 160, 170, 998, 999)

    /** RN resolveHistoryOps: never an empty request (that is "everything", a page of producer rewards on a witness). */
    fun resolveOps(symbol: String, selected: List<String>?): List<String> {
        val available = opsFor(symbol)
        if (selected.isNullOrEmpty()) return available
        return available.filter { it in selected }.ifEmpty { available }
    }

    /** SDK makeBitMaskFilter: ids below 64 in the low word, the rest in the high word, as decimal text; null for 0. */
    fun mask(operations: List<String>): Pair<String?, String?> {
        var low = 0UL
        var high = 0UL
        operations.mapNotNull(OPERATION_IDS::get).forEach { id -> if (id < 64) low = low or (1UL shl id) else high = high or (1UL shl (id - 64)) }
        return (if (low == 0UL) null else low.toString()) to (if (high == 0UL) null else high.toString())
    }

    /** RN transferTypes: the operations the grooming knows. */
    private val TRANSFER_TYPES = setOf(
        "curation_reward", "author_reward", "comment_benefactor_reward", "claim_reward_balance", "transfer", "recurrent_transfer",
        "fill_recurrent_transfer", "transfer_to_savings", "transfer_from_savings", "transfer_to_vesting", "withdraw_vesting", "fill_order",
        "escrow_transfer", "escrow_dispute", "escrow_release", "escrow_approve", "delegate_vesting_shares", "cancel_transfer_from_savings",
        "fill_convert_request", "fill_transfer_from_savings", "fill_vesting_withdraw", "interest", "proposal_pay",
    )

    /** RN RepeatableTransfers. */
    private val REPEATABLE = setOf("transfer", "outgoing_transfer_title", "tokens_transfer")

    private fun symbolOf(asset: String?) = Vests.symbol(asset)
    private fun amountOf(asset: String?) = Vests.amount(asset)

    /** The SDK's per-asset `select` for [symbol] ("HIVE", "HBD", "HP"); [requested] the operations asked for. */
    fun keepForAsset(row: HistoryRow, symbol: String, requested: Set<String>): Boolean {
        val f = row.fields
        return when (symbol) {
            "HP" -> when (row.type) {
                "author_reward", "comment_benefactor_reward" -> amountOf(f.str("vesting_payout")) > 0
                "claim_reward_balance" -> amountOf(f.str("reward_vests")) > 0
                "transfer_to_vesting" -> true
                "transfer", "transfer_to_savings", "recurrent_transfer", "fill_recurrent_transfer" -> symbolOf(f.str("amount")) in setOf("VESTS", "HP")
                "curation_reward", "withdraw_vesting", "delegate_vesting_shares", "fill_vesting_withdraw", "return_vesting_delegation",
                "producer_reward", "set_withdraw_vesting_route" -> true
                else -> requested.isEmpty() || row.type in requested
            }
            else -> {
                val sym = if (symbol == "HBD") "HBD" else "HIVE"
                when (row.type) {
                    "author_reward", "comment_benefactor_reward" -> amountOf(f.str(if (sym == "HBD") "hbd_payout" else "hive_payout")) > 0
                    "claim_reward_balance" -> amountOf(f.str(if (sym == "HBD") "reward_hbd" else "reward_hive")) > 0
                    "transfer", "transfer_to_savings", "transfer_to_vesting", "recurrent_transfer",
                    "transfer_from_savings", "fill_transfer_from_savings", "fill_recurrent_transfer" -> symbolOf(f.str("amount")) == sym
                    "cancel_transfer_from_savings", "fill_order", "limit_order_create", "limit_order_cancel", "fill_convert_request",
                    "fill_collateralized_convert_request", "limit_order_create2" -> true
                    "curation_reward" -> sym == "HIVE"
                    "proposal_pay", "interest" -> sym == "HBD" || row.type in requested
                    else -> row.type in requested
                }
            }
        }
    }

    /** RN parseToken(x).toFixed(3) with "," made ".". */
    private fun fixed3(value: Double) = Num.fixed(value, 3)

    /** RN groomingTransactionData; null for an operation the wallet does not show. */
    fun groom(row: HistoryRow, hivePerMVests: Double): CoinActivity? {
        if (row.type !in TRANSFER_TYPES) return null
        val f = row.fields
        val base = CoinActivity(
            textKey = row.type, icon = AppIcon.TICKET, value = "", created = row.timestamp, trxIndex = row.num,
            trxId = TransactionExplorer.resolveTrxId(row.trxId), repeatable = row.type in REPEATABLE,
        )
        fun hp(vests: String?) = fixed3(Vests.toHp(amountOf(vests), hivePerMVests))
        fun parts(hbd: String?, hive: String?, vests: String?): String {
            val h = fixed3(amountOf(hbd))
            val v = fixed3(amountOf(hive))
            val p = hp(vests)
            return "${if (h.toDouble() > 0) "$h HBD" else ""} ${if (v.toDouble() > 0) "$v HIVE" else ""} ${if (p.toDouble() > 0) "$p HP" else ""}"
        }
        fun fromTo(from: String?, to: String?) = if (!from.isNullOrEmpty() && !to.isNullOrEmpty()) L.native.walletHistory.fromTo(from = from, to = to) else null
        return when (row.type) {
            "curation_reward" -> base.copy(
                value = "${hp(f.str("reward"))} HP",
                details = f.str("comment_author")?.takeIf { it.isNotEmpty() }?.let { "@$it/${f.str("comment_permlink").orEmpty()}" },
            )
            "author_reward", "comment_benefactor_reward" -> base.copy(
                value = parts(f.str("hbd_payout"), f.str("hive_payout"), f.str("vesting_payout")),
                details = if (!f.str("author").isNullOrEmpty() && !f.str("permlink").isNullOrEmpty()) "@${f.str("author")}/${f.str("permlink")}" else null,
                icon = if (row.type == "comment_benefactor_reward") AppIcon.COMMENT else AppIcon.TICKET,
            )
            "claim_reward_balance" -> base.copy(value = parts(f.str("reward_hbd"), f.str("reward_hive"), f.str("reward_vests")))
            "transfer", "transfer_to_savings", "transfer_from_savings", "transfer_to_vesting" -> base.copy(
                value = f.str("amount").orEmpty(), icon = AppIcon.SWAP, details = fromTo(f.str("from"), f.str("to")),
                sender = f.str("from"), receiver = f.str("to"), memo = f.str("memo")?.takeIf { it.isNotEmpty() },
            )
            "recurrent_transfer", "fill_recurrent_transfer" -> base.copy(
                value = f.str("amount").orEmpty(), icon = AppIcon.AUTORENEW, details = fromTo(f.str("from"), f.str("to")),
                sender = f.str("from"), receiver = f.str("to"), memo = f.str("memo")?.takeIf { it.isNotEmpty() },
                recurrence = if (row.type == "recurrent_transfer") f.str("recurrence").orEmpty() else "",
                executions = (if (row.type == "recurrent_transfer") f.str("executions") else f.str("remaining_executions")).orEmpty(),
            )
            "withdraw_vesting" -> base.copy(value = "${hp(f.str("vesting_shares"))} HP", icon = AppIcon.MONEY, details = f.str("acc")?.let { "@$it" })
            "fill_order" -> base.copy(value = "${f.str("current_pays").orEmpty()} = ${f.str("open_pays").orEmpty()}", icon = AppIcon.REORDER)
            "escrow_transfer", "escrow_dispute", "escrow_release", "escrow_approve" -> base.copy(
                value = f.str("escrow_id").orEmpty(), icon = AppIcon.ESCROW, details = fromTo(f.str("from"), f.str("to")), memo = f.str("agent"),
            )
            "delegate_vesting_shares" -> base.copy(
                value = f.str("vesting_shares").orEmpty(), icon = AppIcon.DELEGATION, details = fromTo(f.str("delegator"), f.str("delegatee")),
            )
            "cancel_transfer_from_savings" -> base.copy(value = "0", icon = AppIcon.CLEAR, details = f.str("from")?.let { L.native.walletHistory.fromUserId(from = it, id = f.str("request_id").orEmpty()) })
            "fill_convert_request" -> base.copy(value = f.str("amount_out").orEmpty(), icon = AppIcon.HOURGLASS_FULL, details = f.str("owner")?.let { L.native.walletHistory.userId(user = it, id = f.str("requestid").orEmpty()) })
            "fill_transfer_from_savings" -> base.copy(
                value = f.str("amount").orEmpty(), icon = AppIcon.HOURGLASS_FULL,
                details = f.str("from")?.let { L.native.walletHistory.fromToId(from = it, to = f.str("to").orEmpty(), id = f.str("request_id").orEmpty()) },
            )
            // web's HBD history rows (RN has none)
            "interest" -> base.copy(value = f.str("interest").orEmpty(), icon = AppIcon.MONEY, details = f.str("owner")?.let { "@$it" })
            "proposal_pay" -> base.copy(value = f.str("payment").orEmpty(), icon = AppIcon.MONEY, details = fromTo(f.str("payer"), f.str("receiver")))
            "fill_vesting_withdraw" -> base.copy(
                value = f.str("deposited").orEmpty(), icon = AppIcon.HOURGLASS_FULL,
                details = f.str("from_account")?.let { L.native.walletHistory.fromToAccount(from = it, to = f.str("to_account").orEmpty()) },
            )
            else -> null
        }
    }

    /** RN matchesAssetTicker: the value names the asset (HP also VESTS); a power up belongs to HP structurally. */
    fun matchesTicker(a: CoinActivity?, symbol: String): Boolean {
        if (a == null || a.value.isEmpty()) return false
        if (symbol == "HP" && a.textKey == "transfer_to_vesting") return true
        val tickers = if (symbol == "HP") listOf("HP", "VESTS") else listOf(symbol)
        return tickers.any { a.value.contains(it) }
    }

    /** RN orderChainActivities: dedupe by index, newest index first; rows without one last, newest time first. */
    fun order(rows: List<CoinActivity>): List<CoinActivity> {
        val seen = mutableSetOf<Long>()
        val kept = rows.filter { r -> r.trxIndex == null || seen.add(r.trxIndex) }
        return kept.sortedWith { a, b ->
            val ai = a.trxIndex
            val bi = b.trxIndex
            when {
                ai != null && bi != null -> bi.compareTo(ai)
                ai != null -> -1
                bi != null -> 1
                else -> (b.created).compareTo(a.created)
            }
        }
    }

    /** One page of chain history for [symbol], filtered and groomed (not yet ordered across pages). */
    fun page(rows: List<HistoryRow>, symbol: String, requested: List<String>, hivePerMVests: Double): List<CoinActivity> {
        val set = requested.toSet()
        return rows.filter { keepForAsset(it, symbol, set) }.mapNotNull { groom(it, hivePerMVests) }.filter { matchesTicker(it, symbol) }
    }

    /** SDK getNextAccountHistoryPageParam: the page's oldest index minus one, or null at the start of history. */
    fun nextStart(page: List<HistoryRow>): Long? {
        val oldest = page.firstOrNull()?.num ?: return null
        return if (oldest > 0) oldest - 1 else null
    }

    // ---- Hive Engine and Points ----

    private val ENGINE_ICONS = mapOf(
        "tokens_create" to AppIcon.CREATED,
        "tokens_transfer" to AppIcon.SWAP, "tokens_transferOwnership" to AppIcon.SWAP, "tokens_transferToContract" to AppIcon.SWAP,
        "tokens_transferFromContract" to AppIcon.MONEY, "tokens_transferFee" to AppIcon.MONEY,
        "tokens_updatePrecision" to AppIcon.REORDER, "tokens_updateUrl" to AppIcon.REORDER, "tokens_updateMetadata" to AppIcon.REORDER,
        "tokens_enableStaking" to AppIcon.ESCROW, "tokens_enableDelegation" to AppIcon.ESCROW, "tokens_issue" to AppIcon.ESCROW,
        "tokens_delegate" to AppIcon.DELEGATION, "tokens_stake" to AppIcon.DELEGATION,
        "tokens_cancelUnstake" to AppIcon.CLEAR,
        "tokens_undelegateDone" to AppIcon.HOURGLASS_FULL, "tokens_unstakeDone" to AppIcon.HOURGLASS_FULL,
        "tokens_undelegateStart" to AppIcon.HOURGLASS_TOP, "tokens_unstakeStart" to AppIcon.HOURGLASS_TOP,
    )

    /** RN groomingEngineHistory. [o] one engine-account-history row (timestamp in seconds). */
    fun groomEngine(o: JsonObject): CoinActivity? {
        val operation = o.str("operation") ?: return null
        val seconds = o["timestamp"].asDouble() ?: return null
        val from = o.str("from")
        val to = o.str("to")
        return CoinActivity(
            textKey = operation,
            icon = ENGINE_ICONS[operation] ?: AppIcon.TICKET,
            value = "${o.str("quantity").orEmpty()} ${o.str("symbol").orEmpty()}",
            created = isoOfMillis((seconds * 1000).toLong()),
            trxIndex = o["blockNumber"].asLong(),
            trxId = TransactionExplorer.resolveTrxId(o.str("transactionId")),
            details = o.str("authorperm")?.takeIf { it.isNotEmpty() } ?: if (!from.isNullOrEmpty() && !to.isNullOrEmpty()) L.native.walletHistory.fromTo(from = from, to = to) else null,
            memo = o.str("memo").orEmpty(),
            sender = from, receiver = to, expires = "", repeatable = operation in REPEATABLE,
            engineId = o.str("id") ?: o.str("_id"),
        )
    }

    /** RN engine list: deduped by the engine id, newest first by time. */
    fun orderEngine(rows: List<CoinActivity>): List<CoinActivity> = rows.distinctBy { it.engineId ?: it.key }.sortedByDescending { it.created }

    /** RN POINTS_TYPES / resolvePointType: icon and label key of a points row. */
    fun pointType(type: Int, amount: String): Pair<AppIcon, String> = when (type) {
        997 -> if (Vests.amount(amount) > 0) AppIcon.REFUND to "burn_refund_title" else AppIcon.BOOST to "burned_title"
        999 -> AppIcon.SWAP to "incoming_transfer_title"
        998 -> AppIcon.SWAP to "outgoing_transfer_title"
        170 -> AppIcon.TROPHY to "community_reward"
        160 -> AppIcon.TARGET to "referral_title"
        150 -> AppIcon.SHRINK to "delegation_title"
        100 -> AppIcon.EDIT to "post_title"
        110 -> AppIcon.COMMENT to "comment_title"
        120 -> AppIcon.VOTE_UP to "vote_title"
        130 -> AppIcon.REBLOG to "reblog_title"
        10 -> AppIcon.CHECKIN to "checkin_title"
        20 -> AppIcon.PERSON to "login_title"
        30 -> AppIcon.MARK_ALL_READ to "checkin_extra_title"
        else -> AppIcon.TICKET to "points_activity"
    }

    /** RN groomingPointsTransactionData. [CoinActivity.value] stays POSIX (repeat reads the amount back); it shows through displayValue. */
    fun groomPoints(t: PointTransaction): CoinActivity {
        val (icon, key) = pointType(t.type, t.amount)
        return CoinActivity(
            textKey = key, icon = icon, value = "${t.amount} Points", created = t.created, trxIndex = t.id,
            details = if (!t.sender.isNullOrEmpty()) L.native.walletHistory.fromUser(user = t.sender) else t.receiver?.takeIf { it.isNotEmpty() }?.let { L.native.walletHistory.toUser(user = it) },
            memo = t.memo, sender = t.sender, receiver = t.receiver, repeatable = key in REPEATABLE,
        )
    }

    /**
     * Epoch millis of a chain time ("2026-10-06T12:00:00", UTC) or an ISO time with fraction and offset
     * ("2026-10-10T07:10:35.051909+02:00", "...Z"); null when unreadable. For the "time ago" of the history rows.
     */
    fun epochMillis(time: String): Long? {
        val m = Regex("""^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(\.\d+)?(Z|[+-]\d{2}:?\d{2})?$""").matchEntire(time.trim()) ?: return null
        val base = runCatching { com.ecency.core.hive.ChainTime.toEpochSeconds(m.groupValues[1]) }.getOrNull() ?: return null
        val frac = m.groupValues[2].drop(1).take(3).padEnd(3, '0').ifEmpty { "000" }.toLong()
        val zone = m.groupValues[3]
        val offset = if (zone.isEmpty() || zone == "Z") 0L else {
            val digits = zone.drop(1).replace(":", "")
            val minutes = digits.take(2).toLong() * 60 + digits.drop(2).toLong()
            (if (zone[0] == '-') -1 else 1) * minutes * 60
        }
        return (base - offset) * 1000 + frac
    }

    /** "YYYY-MM-DDTHH:MM:SS.000Z" (JS toISOString) for epoch millis. */
    fun isoOfMillis(millis: Long): String {
        val s = com.ecency.core.hive.ChainTime.fromEpochSeconds((millis / 1000).coerceAtLeast(0))
        return "$s.${(millis % 1000).toString().padStart(3, '0')}Z"
    }
}

/**
 * Web's wallet history groups (SDK `HIVE_ACCOUNT_OPERATION_GROUPS`, also the decks' columns), as category filters
 * of an asset's chain history; each sends its operations as the history bitmask, cut to the asset's own set.
 * Deliberate difference: Transfers also holds `fill_transfer_from_savings` (a savings withdrawal arriving), which
 * web's group leaves out although its HIVE and HBD lists show it.
 */
enum class HistoryCategory(val ops: List<String>, private val labelText: () -> String) {
    ALL(emptyList(), { L.native.walletHistory.categoryAll }),
    TRANSFERS(
        listOf(
            "transfer", "transfer_to_savings", "transfer_from_savings", "fill_transfer_from_savings", "cancel_transfer_from_savings",
            "recurrent_transfer", "fill_recurrent_transfer", "escrow_transfer",
        ),
        { L.native.walletHistory.categoryTransfers },
    ),
    MARKET(
        listOf("fill_convert_request", "fill_order", "fill_collateralized_convert_request", "limit_order_create2", "limit_order_create", "limit_order_cancel"),
        { L.native.walletHistory.categoryMarket },
    ),
    INTEREST(listOf("interest"), { L.native.walletHistory.categoryInterest }),
    STAKE(
        listOf(
            "return_vesting_delegation", "withdraw_vesting", "transfer_to_vesting", "set_withdraw_vesting_route", "update_proposal_votes",
            "fill_vesting_withdraw", "account_witness_proxy", "delegate_vesting_shares",
        ),
        { L.native.walletHistory.categoryStake },
    ),
    REWARDS(
        listOf("author_reward", "curation_reward", "producer_reward", "claim_reward_balance", "comment_benefactor_reward", "liquidity_reward", "proposal_pay"),
        { L.native.walletHistory.categoryRewards },
    ),
    ;

    val label: String get() = labelText()

    /** The operations this category asks for on [symbol]'s history (All: the asset's whole set). */
    fun opsFor(symbol: String): List<String> {
        val available = WalletHistory.opsFor(symbol)
        return if (this == ALL) available else available.filter { it in ops }
    }
}

/** RN utils/transactionExplorer. */
object TransactionExplorer {
    private val HEX40 = Regex("^[0-9a-f]{40}$")

    /** The Hive transaction id behind [raw] (a trailing "-n" operation index dropped), or null (virtual ops, malformed). */
    fun resolveTrxId(raw: String?): String? {
        val id = raw?.trim()?.lowercase()?.replace(Regex("-\\d+$"), "") ?: return null
        if (!HEX40.matches(id) || id.all { it == '0' }) return null
        return id
    }

    fun link(trxId: String): String = "https://hivexplorer.com/tx/$trxId"
}
