package com.ecency.core.app

import com.ecency.core.api.broadcast.WalletOps
import com.ecency.core.api.wallet.AssetLayer
import com.ecency.core.api.wallet.CoinActivity
import com.ecency.core.api.wallet.Delegation
import com.ecency.core.api.wallet.HistoryCategory
import com.ecency.core.api.wallet.PortfolioItem
import com.ecency.core.api.wallet.RecurrentSchedule
import com.ecency.core.api.wallet.Vests
import com.ecency.core.api.wallet.WalletHistory
import com.ecency.core.auth.OperationAuthority
import com.ecency.core.hive.ChainTime
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** A value row of the coin summary (RN CoinBasics): value then label; [opens] a delegations or recurrent list. */
data class SummaryRow(val label: String, val value: String, val opens: DetailsSheet? = null)

/** What a tap on a summary row opens. */
enum class DetailsSheet { OUTGOING_DELEGATIONS, INCOMING_DELEGATIONS, RECURRENT_TRANSFERS }

/** Where an asset action goes (RN assetDetailsScreen _onActionPress). */
sealed interface AssetAction {
    /** The server's action id ("transfer", "delegate_vesting_shares", ...). Not `id`: Swift sees every `id` of the core mangled together. */
    val actionName: String
    val label: String get() = WalletCopy.label(actionName)

    /** The transfer screen: [kind] for TransferModel, or one of the special screens by [transferType]. */
    data class Transfer(override val actionName: String, val transferType: String, val fund: String, val layer: AssetLayer) : AssetAction

    data class Trade(override val actionName: String, val fund: String) : AssetAction

    /** RN Redeem: "promote" or "boost_plus". */
    data class Redeem(override val actionName: String, val redeemType: String) : AssetAction

    data class Receive(override val actionName: String, val symbol: String, val address: String?) : AssetAction

    /** Where the button goes. */
    val screen: AppScreen
        get() = when (this) {
            is Transfer -> AppScreen.Transfer(transferType, fund, layer.value)
            is Trade -> AppScreen.Trade(fund)
            is Redeem -> AppScreen.Redeem(redeemType)
            is Receive -> AppScreen.Transfer("receive", symbol, AssetLayer.CHAIN.value, address.orEmpty())
        }
}

data class AssetDetailsState(
    val symbol: String,
    val account: String? = null,
    val asset: PortfolioItem? = null,
    val summary: List<SummaryRow> = emptyList(),
    val aprText: String? = null,
    val actions: List<AssetAction> = emptyList(),
    val pending: List<CoinActivity> = emptyList(),
    val activities: List<CoinActivity> = emptyList(),
    val activitiesEnabled: Boolean = true,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val historyFailed: Boolean = false,
    val endReached: Boolean = false,
    /** Hive layer: the operations the filter sheet or a category picked (null: the tab's full set). */
    val filter: List<String>? = null,
    /** Points: the history type asked for (web's points filter; 0 is every type). */
    val pointType: Int = 0,
    val recurrent: List<RecurrentSchedule> = emptyList(),
    /** The open order being cancelled or recurrent transfer being removed. */
    val busyKey: String? = null,
    val prompt: DetailsPrompt? = null,
    /** Whether the expanded row is shown, by row key. */
    val expanded: Set<String> = emptySet(),
    /** RN: the filter icon is shown for the hive layer only. */
    val filterable: Boolean = false,
    /** HIVE, HBD and HP have the balance analytics (RN). */
    val analyticsCoin: String? = null,
) {
    val isEmpty: Boolean get() = !isLoading && !historyFailed && activities.isEmpty()

    /** Hive layer: web's history categories this asset has (#2747); empty elsewhere. */
    val categories: List<HistoryCategory> get() = if (filterable) WalletHistory.categoriesFor(symbol) else emptyList()

    /** The category [filter] is, or null when the filter sheet picked another set. */
    val category: HistoryCategory? get() = WalletHistory.categoryOf(symbol, filter)

    /** Points: web's history types to filter by (0 first, for all). */
    val pointTypes: List<Int> get() = if (symbol == "POINTS") listOf(0) + WalletHistory.POINT_TYPES else emptyList()
}

sealed interface DetailsPrompt {
    data class CancelOrder(val orderId: Long) : DetailsPrompt

    data class DeleteRecurrent(val schedule: RecurrentSchedule) : DetailsPrompt
}

/**
 * RN assetDetailsScreen: the coin summary, the server's actions, pending requests (hive layer), the history (chain
 * history for HIVE, HBD and HP; Hive Engine history; Points history), the recurrent transfers (HIVE) and the order
 * cancel. Chain history pages arrive ascending: the list is deduped by index and ordered newest first
 * ([WalletHistory.order]); a page that filters down to nothing advances by itself up to [MAX_AUTO_ADVANCE] times.
 */
class AssetDetailsModel internal constructor(private val env: AppEnvironment, val symbol: String) :
    StateModel<AssetDetailsState>(AssetDetailsState(symbol), env.dispatchers) {
    private var job: Job? = null
    private var nextStart: Long? = -1
    private var enginePage = 0
    private var raw = emptyList<CoinActivity>()
    private var autoAdvanced = 0
    private var started = false

    fun start() {
        if (started) return
        started = true
        // the portfolio (asset, balances, fiat rates) comes from the app-wide wallet, started here when opened directly
        env.wallet.start()
        scope.launch {
            env.wallet.state.collect { w ->
                val item = w.item(symbol)
                if (item != null && item != current.asset) setState { s -> s.withAsset(item, w.hivePerMVests) }
            }
        }
        scope.launch {
            env.events.collect { e -> if (e is AppEvent.SessionChanged && e.username != current.account) { job?.cancel(); setState { AssetDetailsState(symbol) } } }
        }
        load(refresh = false)
    }

    fun refresh() {
        env.wallet.refresh()
        load(refresh = true)
    }

    fun loadMore() {
        val s = current
        if (job?.isActive == true || s.endReached || !s.activitiesEnabled || s.isLoading) return
        job = scope.launch { page(first = false) }
    }

    /** The filter sheet's result (hive layer); null or a full set is "everything" (RN stores undefined). */
    fun setFilter(ops: List<String>?) {
        val all = WalletHistory.opsFor(symbol)
        val picked = ops?.filter { it in all }?.takeIf { it.isNotEmpty() && it.size < all.size }
        if (picked == current.filter) return
        setState { it.copy(filter = picked) }
        load(refresh = false)
    }

    /** A category chip: its operations as the history filter (All clears it). */
    fun setCategory(category: HistoryCategory) =
        setFilter(if (category == HistoryCategory.ALL) null else category.opsFor(symbol))

    /** A points type chip (0: every type): the API filters, as web's points page does. */
    fun setPointType(type: Int) {
        if (symbol != "POINTS" || type == current.pointType || (type != 0 && type !in WalletHistory.POINT_TYPES)) return
        setState { it.copy(pointType = type) }
        load(refresh = false)
    }

    fun toggle(key: String) = setState { it.copy(expanded = if (key in it.expanded) it.expanded - key else it.expanded + key) }

    /**
     * RN repeat button: the transfer screen prefilled with the other party, the amount (absolute) and the memo. The
     * amount and memo go through [AppEnvironment.transferPrefill] (never the saved navigation). Null when the row
     * cannot be repeated.
     */
    fun repeat(row: CoinActivity): AppScreen? {
        if (!row.repeatable) return null
        val me = current.account ?: return null
        val item = current.asset ?: return null
        val other = if (row.receiver != null && row.receiver != me) row.receiver else row.sender ?: return null
        val amount = row.value.trim().substringBefore(' ').removePrefix("-")
        val fund = if (item.layer == AssetLayer.POINTS) "POINT" else item.symbol
        val type = if (item.layer == AssetLayer.POINTS) "ecency_point_transfer" else "transfer"
        env.transferPrefill = TransferPrefill(type, fund, other, amount, row.memo.orEmpty())
        return AppScreen.Transfer(type, fund, item.layer.value, other)
    }

    fun dismissPrompt() = setState { it.copy(prompt = null) }

    fun cancelOrder(orderId: Long) {
        if (current.busyKey != null) return
        setState { it.copy(prompt = DetailsPrompt.CancelOrder(orderId)) }
    }

    fun deleteRecurrent(schedule: RecurrentSchedule) {
        if (current.busyKey != null) return
        setState { it.copy(prompt = DetailsPrompt.DeleteRecurrent(schedule)) }
    }

    fun confirmPrompt() {
        val user = current.account ?: return
        when (val p = current.prompt) {
            is DetailsPrompt.CancelOrder -> send(user, "order:${p.orderId}", listOf(WalletOps.limitOrderCancel(user, p.orderId)), null, null)
            is DetailsPrompt.DeleteRecurrent -> {
                val sym = Vests.symbol(p.schedule.amount).ifEmpty { "HIVE" }
                // RN useDeleteRecurrentTransferMutation: amount 0, the schedule's recurrence (at least 24 h), 2 executions
                val op = WalletOps.recurrentTransfer(user, p.schedule.to, "0.000 $sym", p.schedule.memo, maxOf(24, p.schedule.recurrence), 2)
                send(user, "recurrent:${p.schedule.to}", listOf(op), RecurrentCopy.DELETE_SUCCESS) { e -> RecurrentCopy.deleteFailed(e) }
            }
            null -> Unit
        }
        setState { it.copy(prompt = null) }
    }

    private fun send(user: String, key: String, ops: List<com.ecency.core.hive.Operation>, success: String?, failure: ((String) -> String)?) {
        val json = WalletOps.ops(ops)
        val actionId = env.idempotencyKey()
        setState { it.copy(busyKey = key) }
        env.walletSends.send(user, json, OperationAuthority.required(json), actionId) { final ->
            kotlinx.coroutines.withContext(env.dispatchers.main) {
                env.walletSends.disown(actionId)
                setState { it.copy(busyKey = null) }
                when (val p = final.phase) {
                    is SendPhase.Done -> {
                        success?.let(env.ui::toast)
                        if (key.startsWith("recurrent:")) setState { s -> s.copy(recurrent = s.recurrent.filterNot { it.to == key.removePrefix("recurrent:") }).withAsset(s.asset, env.wallet.current.hivePerMVests) }
                        scope.launch { kotlinx.coroutines.delay(1_000); loadPending(user) }
                    }
                    is SendPhase.Failed -> env.ui.toast(failure?.invoke(p.error.message) ?: WalletCopy.operationFailed(WalletCopy.label(SigningModel.operationName(json)), p.error.message))
                    is SendPhase.Unconfirmed -> env.ui.toast(p.message)
                    else -> Unit
                }
            }
        }
    }

    private fun load(refresh: Boolean) {
        job?.cancel()
        nextStart = -1
        enginePage = 0
        autoAdvanced = 0
        raw = emptyList()
        setState { it.copy(isLoading = !refresh, isRefreshing = refresh, historyFailed = false, endReached = false, activities = if (refresh) it.activities else emptyList()) }
        job = scope.launch {
            env.session.awaitLoaded()
            val user = env.session.current.username ?: return@launch
            setState { it.copy(account = user) }
            val item = env.wallet.current.item(symbol)
            val layer = item?.layer ?: if (symbol in setOf("HIVE", "HBD", "HP")) AssetLayer.HIVE else if (symbol == "POINTS") AssetLayer.POINTS else AssetLayer.ENGINE
            if (layer == AssetLayer.HIVE) {
                launch { loadPending(user) }
                if (symbol == "HIVE") launch { loadRecurrent(user) }
            }
            if (layer == AssetLayer.CHAIN) {
                setState { it.copy(activitiesEnabled = false, isLoading = false, isRefreshing = false) }
                return@launch
            }
            page(first = true, layer = layer)
        }
    }

    private suspend fun page(first: Boolean, layer: AssetLayer? = null) {
        val user = current.account ?: return
        val l = layer ?: current.asset?.layer ?: AssetLayer.HIVE
        if (!first) setState { it.copy(isLoadingMore = true) }
        try {
            when (l) {
                AssetLayer.POINTS -> {
                    val type = current.pointType
                    val rows = env.walletApi.pointHistory(user, type).map(WalletHistory::groomPoints)
                    raw = rows
                    setState { it.copy(activities = rows, endReached = true) }
                }
                AssetLayer.ENGINE -> {
                    val rows = env.walletApi.engineHistory(user, symbol, PAGE, PAGE * enginePage).mapNotNull(WalletHistory::groomEngine)
                    enginePage++
                    raw = WalletHistory.orderEngine(raw + rows)
                    setState { it.copy(activities = raw, endReached = rows.isEmpty()) }
                }
                AssetLayer.HIVE -> {
                    val ops = WalletHistory.resolveOps(symbol, current.filter)
                    val perMVests = env.wallet.current.hivePerMVests.takeIf { it > 0 } ?: env.walletApi.hivePerMVests()
                    while (true) {
                        val start = nextStart ?: break
                        val rows = env.walletApi.history(user, start, PAGE, ops)
                        nextStart = WalletHistory.nextStart(rows)
                        val groomed = WalletHistory.page(rows, symbol, ops, perMVests)
                        raw = WalletHistory.order(raw + groomed)
                        setState { it.copy(activities = raw, endReached = nextStart == null) }
                        // RN: while nothing at all shows, the next page is fetched by itself, at most 5 times per list
                        if (raw.isNotEmpty() || nextStart == null || autoAdvanced >= MAX_AUTO_ADVANCE) break
                        autoAdvanced++
                    }
                }
                AssetLayer.CHAIN -> Unit
            }
            setState { it.copy(isLoading = false, isLoadingMore = false, isRefreshing = false, historyFailed = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            setState { it.copy(isLoading = false, isLoadingMore = false, isRefreshing = false, historyFailed = true) }
        }
    }

    /** RN usePendingRequestsQuery: savings withdrawals, conversions and open orders that name the symbol, soonest first. */
    private suspend fun loadPending(user: String) {
        val api = env.walletApi
        val rows = kotlinx.coroutines.coroutineScope {
            val savings = async { runCatching { api.savingsWithdrawals(user) }.getOrDefault(emptyList()) }
            val conversions = async { runCatching { api.conversions(user) }.getOrDefault(emptyList()) }
            val orders = async { runCatching { api.openOrders(user) }.getOrDefault(emptyList()) }
            savings.await().filter { it.amount.contains(symbol) }.map {
                CoinActivity("withdraw_savings", AppIcon.SWAP, it.amount, it.complete, it.requestId, details = L.native.walletHistory.fromTo(from = it.from, to = it.to), memo = it.memo.ifEmpty { null })
            } + conversions.await().filter { it.amount.contains(symbol) }.map {
                CoinActivity(if (it.collateralized) "collateralized_convert_request" else "convert_request", AppIcon.HOURGLASS_FULL, it.amount, it.date, it.requestId)
            } + orders.await().filter { it.base.contains(symbol) || it.quote.contains(symbol) }.map {
                CoinActivity(
                    "open_order", AppIcon.REORDER, if (it.base.contains(symbol)) it.base else it.quote, it.created, it.orderId,
                    details = "@ ${it.base} = ${it.quote}", expires = it.expiration, cancelable = true,
                )
            }
        }
        if (current.account != user) return
        setState { it.copy(pending = rows.sortedBy { r -> sortTime(r.expires?.takeIf { e -> e.isNotEmpty() } ?: r.created) }) }
    }

    private fun sortTime(t: String): Long = runCatching { ChainTime.toEpochSeconds(t.take(19)) }.getOrDefault(Long.MAX_VALUE)

    private suspend fun loadRecurrent(user: String) {
        val list = runCatching { env.walletApi.recurrentTransfers(user) }.getOrNull() ?: return
        if (current.account != user) return
        // RN: the HIVE summary counts HIVE schedules only
        setState { it.copy(recurrent = list.filter { r -> Vests.symbol(r.amount) == "HIVE" }).withAsset(it.asset, env.wallet.current.hivePerMVests) }
    }

    private fun AssetDetailsState.withAsset(item: PortfolioItem?, hivePerMVests: Double): AssetDetailsState {
        item ?: return this
        val currency = env.wallet.current.currency
        val summary = buildList {
            if (item.symbol != "HP") add(SummaryRow(WalletCopy.BALANCE, WalletFormat.amount(item.liquid ?: item.balance)))
            item.staked?.takeIf { it > 0 }?.let { add(SummaryRow(WalletCopy.STAKED, WalletFormat.amount(it))) }
            item.savings?.takeIf { it > 0 }?.let { add(SummaryRow(WalletCopy.SAVINGS, WalletFormat.amount(it))) }
            item.fiatRate?.let { add(SummaryRow(WalletCopy.ESTIMATED_VALUE, "~" + WalletFormat.fiat(item.balance * it, currency))) }
            item.extraData.forEach { e ->
                val opens = when (e.dataKey) {
                    "delegated_hive_power" -> DetailsSheet.OUTGOING_DELEGATIONS
                    "received_hive_power" -> DetailsSheet.INCOMING_DELEGATIONS
                    else -> null
                }
                add(SummaryRow(WalletCopy.extraLabel(e.dataKey, e.subValue), e.value, opens))
            }
            val total = recurrent.sumOf { Vests.amount(it.amount) }
            if (total > 0) add(SummaryRow(WalletCopy.TOTAL_RECURRENT, LocaleFormat.toFixed(total, 3) + " " + item.symbol, DetailsSheet.RECURRENT_TRANSFERS))
        }
        val actions = item.actions.filter { it != "recurrent_transfer" }.map { id -> action(item, id) }
        return copy(
            asset = item, summary = summary, actions = actions, aprText = item.apr?.let(WalletFormat::apr),
            activitiesEnabled = item.layer != AssetLayer.CHAIN, filterable = item.layer == AssetLayer.HIVE,
            analyticsCoin = when (item.symbol) { "HIVE" -> "HIVE"; "HBD" -> "HBD"; "HP" -> "VESTS"; else -> null },
        )
    }

    /** RN _onActionPress routing. */
    private fun action(item: PortfolioItem, id: String): AssetAction = when {
        item.layer == AssetLayer.POINTS && (id == "promote" || id == "boost") -> AssetAction.Redeem(id, if (id == "promote") "promote" else "boost_plus")
        item.layer == AssetLayer.POINTS -> AssetAction.Transfer(id, if (id == "ecency_point_transfer") "ecency_point_transfer" else id, "POINT", item.layer)
        item.layer == AssetLayer.HIVE && id == "swap_token" -> AssetAction.Trade(id, item.symbol)
        id == "receive" -> AssetAction.Receive(id, item.symbol, item.address)
        else -> AssetAction.Transfer(id, id, item.symbol, item.layer)
    }

    companion object {
        /** RN ACTIVITIES_FETCH_LIMIT. */
        const val PAGE = 100

        /** RN MAX_AUTO_ADVANCE_PAGES. */
        const val MAX_AUTO_ADVANCE = 5
    }
}

/** RN en-US `recurrent.*`. */
object RecurrentCopy {
    val DELETE_SUCCESS: String get() = L.recurrent.deleteSuccess

    fun title(total: Int) = L.recurrent.title(total = total.toString())

    fun transferIn(time: String) = L.recurrent.transferIn(time = time)

    fun remaining(executions: Int) = L.native.plurals.recurrent.remainingExecutions(executions = executions)

    fun unsubscribe(username: String) = L.recurrent.unsubscribeConfirmation(username = username)

    fun deleteFailed(error: String) = L.recurrent.deleteFailed(error = error)

    /** The chain sends both counts as text; a count that is not a whole number reads as 0. */
    fun scheduleSummary(executions: String, hours: String) =
        L.native.plurals.recurrent.scheduleSummary(executions = executions.toIntOrNull() ?: 0, hours = hours.toIntOrNull() ?: 0)
}

data class DelegationsState(
    val sheet: DetailsSheet,
    val rows: List<DelegationRow> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = false,
    val error: AppError? = null,
) {
    val title: String get() = WalletCopy.label(if (sheet == DetailsSheet.OUTGOING_DELEGATIONS) "delegated_hive_power" else "received_hive_power") + " (${rows.size})"

    val visible: List<DelegationRow> get() = if (query.isBlank()) rows else rows.filter { it.account.contains(query.trim().lowercase()) }
}

/** One delegation row: HP text, a date when known, and for an expiring one the time left. */
data class DelegationRow(val account: String, val hp: String, val date: String?, val expiring: Boolean, val timeLeft: String?, val updatable: Boolean)

/** RN delegationsModal: outgoing (with the ones in their cooldown) or incoming delegations, searchable. */
class DelegationsModel internal constructor(private val env: AppEnvironment, private val account: String, sheet: DetailsSheet) :
    StateModel<DelegationsState>(DelegationsState(sheet), env.dispatchers) {
    fun start() = refresh()

    fun setQuery(q: String) = setState { it.copy(query = q) }

    fun refresh() {
        setState { it.copy(isLoading = true, error = null) }
        scope.launch {
            try {
                val rate = env.wallet.current.hivePerMVests.takeIf { it > 0 } ?: env.walletApi.hivePerMVests()
                val list: List<Delegation> = if (current.sheet == DetailsSheet.OUTGOING_DELEGATIONS) {
                    env.walletApi.outgoingDelegations(account) + runCatching { env.walletApi.expiringDelegations(account) }.getOrDefault(emptyList())
                } else {
                    env.walletApi.incomingDelegations(account)
                }
                val rows = list.map { d ->
                    DelegationRow(
                        account = d.account,
                        hp = LocaleFormat.toFixed(Vests.toHp(Vests.amount(d.vestingShares), rate), 3) + " HP",
                        date = d.timestamp?.takeIf { it.isNotEmpty() },
                        expiring = d.expiring,
                        timeLeft = if (d.expiring) timeLeft(d.timestamp) else null,
                        updatable = current.sheet == DetailsSheet.OUTGOING_DELEGATIONS && !d.expiring,
                    )
                }
                setState { it.copy(rows = rows, isLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(isLoading = false, error = AppError.from(e)) }
            }
        }
    }

    /** RN _formatTimeLeft. */
    private fun timeLeft(expiration: String?): String {
        val end = runCatching { ChainTime.toEpochSeconds(expiration.orEmpty().take(19)) * 1000 }.getOrNull() ?: return WalletCopy.EXPIRING_SOON
        val left = end - env.nowMillis()
        if (left <= 0) return WalletCopy.EXPIRING_SOON
        val days = left / 86_400_000
        val hours = left % 86_400_000 / 3_600_000
        val mins = left % 3_600_000 / 60_000
        return when {
            days > 0 -> WalletCopy.timeRemainingDays(days, hours)
            hours > 0 || mins > 0 -> WalletCopy.timeRemainingHours(hours, mins)
            else -> WalletCopy.EXPIRING_SOON
        }
    }
}
