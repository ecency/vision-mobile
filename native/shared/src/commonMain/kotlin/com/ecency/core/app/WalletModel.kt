package com.ecency.core.app

import com.ecency.core.api.broadcast.WalletOps
import com.ecency.core.api.wallet.AssetLayer
import com.ecency.core.api.wallet.PortfolioItem
import com.ecency.core.api.wallet.ProfileToken
import com.ecency.core.api.wallet.WalletApi
import com.ecency.core.hive.KeyRole
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** One card of the Wallet tab (RN AssetCard). */
data class AssetRow(
    val item: PortfolioItem,
    val name: String,
    val balanceText: String,
    /** "$12.34", null without a fiat rate. */
    val fiatText: String?,
    /** RN ClaimButton title ("1.234 HP"), null when nothing is pending (the claim mask applied). */
    val pendingText: String?,
    val claiming: Boolean,
) {
    val symbol: String get() = item.symbol
}

data class WalletState(
    /** Whose portfolio this is; rows are only drawn while it is the signed-in account. */
    val account: String? = null,
    val rows: List<AssetRow> = emptyList(),
    /** Everything the portfolio answered (the total counts all of it, as RN). */
    val all: List<PortfolioItem> = emptyList(),
    val totalText: String = "--.--",
    val updatedAtMillis: Long? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val loaded: Boolean = false,
    val error: AppError? = null,
    val currency: WalletCurrency = WalletCurrency.USD,
    /** HIVE per million VESTS, 0 until read. */
    val hivePerMVests: Double = 0.0,
    /** The symbols shown, in order (RN selectedAssets). */
    val selection: List<String> = WalletModel.DEFAULT_ASSETS,
) {
    val isUpdating: Boolean get() = isLoading || isRefreshing

    /** RN: the skeleton while the first fetch of an empty list runs. */
    val showSkeleton: Boolean get() = isLoading && rows.isEmpty()

    /** RN error banner: only when not fetching. */
    val showError: Boolean get() = error != null && !isUpdating

    fun item(symbol: String): PortfolioItem? = all.firstOrNull { it.symbol == symbol }
}

/**
 * RN walletScreen + useAssetsQuery + useClaimRewardsMutation, app-wide (one per environment, like the session):
 * the signed-in account's portfolio (`wallet-api/portfolio-v2`, onlyEnabled) in RN's order, DEFAULT_ASSETS
 * [POINTS, HP, HIVE, HBD] then the profile's tokens, refreshed on demand ([refresh]) and every [POLL_MILLIS] while a
 * screen holds it ([visible]), and the claim of pending rewards per asset. An account change empties it and loads
 * the new account's; an answer still on its way for the previous account is dropped.
 */
class WalletModel internal constructor(private val env: AppEnvironment) : StateModel<WalletState>(WalletState(), env.dispatchers) {
    private val api: WalletApi get() = env.walletApi
    private var job: Job? = null
    private var poll: Job? = null
    private var started = false
    private var watchers = 0

    /** RN claimsCollection: symbol to (claimed value, expiry), so a claimed reward reads 0 until the server agrees. */
    private val claimMask = mutableMapOf<String, Pair<Double, Long>>()
    private val claimingNow = mutableSetOf<String>()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            env.events.collect { e ->
                if (e is AppEvent.SessionChanged && e.username != current.account) {
                    job?.cancel()
                    claimMask.clear()
                    claimingNow.clear()
                    override = null
                    setState { WalletState(currency = WalletCurrency.of(env.prefs)) }
                    if (e.username != null) load(refresh = false)
                }
            }
        }
        load(refresh = false)
    }

    /** A screen showing the wallet appeared (or went away, [visible] false): polls every [POLL_MILLIS] while shown. */
    fun visible(shown: Boolean) {
        watchers = (watchers + if (shown) 1 else -1).coerceAtLeast(0)
        poll?.cancel()
        if (watchers > 0) {
            poll = scope.launch {
                while (true) {
                    delay(POLL_MILLIS)
                    if (job?.isActive != true) load(refresh = true)
                }
            }
        }
    }

    /** Pull to refresh, the header's refresh icon, the app coming to the foreground (RN guards with !isFetching). */
    fun refresh() {
        if (job?.isActive == true) return
        load(refresh = true)
    }

    fun retry() = load(refresh = false)

    /** Reloads now and returns when done (pull to refresh). */
    suspend fun refreshAndWait() {
        refresh()
        job?.join()
    }

    /** RN _delayedRefreshCoinsData: the portfolio again 3 s after a broadcast (the chain and the API need a moment). */
    fun refreshLater() {
        val user = current.account ?: return
        scope.launch {
            delay(AFTER_BROADCAST_DELAY_MILLIS)
            if (owns(user)) load(refresh = true)
        }
    }

    /** The currency setting changed (settings screen): RN resets the wallet data and refetches. */
    fun currencyChanged() {
        job?.cancel()
        load(refresh = false)
    }

    /**
     * The claim button of [symbol] (RN ClaimButton onPress): with something pending it claims; POINTS without
     * pending opens Get Points instead (the UI checks [AssetRow.pendingText] and routes). One claim per asset at a time.
     */
    fun claim(symbol: String) {
        val user = current.account ?: return
        val item = current.item(symbol) ?: return
        if (symbol in claimingNow || item.pendingRewards <= 0) return
        claimingNow += symbol
        publish()
        val claimed = item.pendingRewards
        scope.launch {
            val ok = try {
                when {
                    symbol == "POINTS" -> claimPoints(user)
                    item.layer == AssetLayer.ENGINE -> broadcast(user, WalletOps.ops(listOf(WalletOps.engineClaim(user, listOf(symbol)))), KeyRole.POSTING)
                    else -> {
                        val account = api.account(user) ?: throw IllegalStateException("account not found")
                        val op = WalletOps.claimRewardBalance(
                            user,
                            if (symbol == "HIVE") account.rewardHive else "0.000 HIVE",
                            if (symbol == "HBD") account.rewardHbd else "0.000 HBD",
                            if (symbol == "HP") account.rewardVests else "0.000000 VESTS",
                        )
                        broadcast(user, WalletOps.ops(listOf(op)), KeyRole.POSTING)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                env.ui.toast(WalletCopy.claimFailed(AppError.from(e).message))
                false
            }
            claimingNow -= symbol
            if (ok && owns(user)) {
                claimMask[symbol] = claimed to env.nowMillis() + CLAIM_MASK_MILLIS
                env.ui.toast(WalletCopy.CLAIMED)
            }
            publish()
            if (ok) {
                // RN: the portfolio again 2 s later (the chain needs a moment)
                delay(CLAIM_REFRESH_DELAY_MILLIS)
                if (owns(user)) load(refresh = true)
            }
        }
    }

    /** RN POINTS claim: a 406 or a failure is checked against a fresh portfolio (pending 0 counts as claimed). */
    private suspend fun claimPoints(user: String): Boolean {
        return try {
            api.claimPoints(user)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val fresh = runCatching { api.portfolio(user, current.currency.code, true) }.getOrNull()
            if (fresh?.firstOrNull { it.symbol == "POINTS" }?.pendingRewards == 0.0) true else throw e
        }
    }

    /** A claim as one action (claims are not repeat safe: a second claim of the same rewards is a new one). */
    private suspend fun broadcast(user: String, ops: kotlinx.serialization.json.JsonArray, authority: KeyRole): Boolean {
        val result = kotlinx.coroutines.CompletableDeferred<WalletSend>()
        val actionId = env.idempotencyKey()
        env.walletSends.send(user, ops, authority, actionId) { result.complete(it) }
        val final = result.await()
        env.walletSends.disown(actionId)
        return when (val p = final.phase) {
            is SendPhase.Done -> true
            is SendPhase.Cancelled -> false
            is SendPhase.Failed -> throw AppException(p.error)
            is SendPhase.Unconfirmed -> { env.ui.toast(p.message); false }
            else -> false
        }
    }

    private fun owns(user: String) = current.account == user && env.session.current.username == user

    private fun load(refresh: Boolean) {
        job?.cancel()
        setState { it.copy(isLoading = !refresh, isRefreshing = refresh, error = null, currency = WalletCurrency.of(env.prefs)) }
        job = scope.launch {
            try {
                env.session.awaitLoaded()
                val user = env.session.current.username
                if (user == null) {
                    setState { WalletState(currency = it.currency) }
                    return@launch
                }
                setState { if (it.account == user) it else WalletState(account = user, isLoading = true, currency = it.currency) }
                val currency = current.currency
                val accountJob = async { runCatching { api.account(user) }.getOrNull() }
                val rate = async { runCatching { api.hivePerMVests() }.getOrDefault(current.hivePerMVests) }
                val items = api.portfolio(user, currency.code, onlyEnabled = true)
                val tokens = accountJob.await()?.profileTokens.orEmpty()
                val perMVests = rate.await()
                if (!owns(user)) return@launch
                setState {
                    withRows(
                        it.copy(
                            all = items, hivePerMVests = perMVests, isLoading = false, isRefreshing = false, loaded = true,
                            updatedAtMillis = env.nowMillis(), selection = selectionFor(tokens), error = null,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setState { it.copy(isLoading = false, isRefreshing = false, error = AppError.from(e)) }
            }
        }
    }

    /** Main thread: the manage assets screen saved a new selection (RN dispatches setSelectedAssets before the write). */
    internal fun setSelection(tokens: List<ProfileToken>) {
        val symbols = selectedSymbols(tokens)
        override = symbols to env.nowMillis()
        setState { withRows(it.copy(selection = symbols)) }
    }

    /** The selection just saved here, until the chain shows it (a node lags the write) or [OVERRIDE_MILLIS] pass. */
    private var override: Pair<List<String>, Long>? = null

    private fun selectionFor(tokens: List<ProfileToken>): List<String> {
        val chain = selectedSymbols(tokens)
        val o = override ?: return chain
        if (chain == o.first || env.nowMillis() - o.second > OVERRIDE_MILLIS) {
            override = null
            return chain
        }
        return o.first
    }

    private fun publish() = setState(::withRows)

    private fun withRows(s: WalletState): WalletState {
        val now = env.nowMillis()
        claimMask.entries.removeAll { it.value.second <= now }
        val rows = s.selection.mapNotNull { sym -> s.all.firstOrNull { it.symbol == sym } }.map { item ->
            val mask = claimMask[item.symbol]
            val pending = if (mask != null && mask.first == item.pendingRewards) 0.0 else item.pendingRewards
            AssetRow(
                item = item,
                name = WalletCopy.assetName(item),
                balanceText = WalletFormat.amount(item.balance),
                fiatText = item.fiatRate?.let { WalletFormat.fiat(item.balance * it, s.currency) },
                pendingText = if (pending > 0) "${WalletFormat.amount(pending)} ${item.symbol}" else null,
                claiming = item.symbol in claimingNow,
            )
        }
        val total = s.all.sumOf { it.balance * (it.fiatRate ?: 0.0) }
        return s.copy(rows = rows, totalText = if (s.loaded) WalletFormat.fiat(total, s.currency) else "--.--")
    }

    companion object {
        /** RN DEFAULT_ASSETS. */
        val DEFAULT_ASSETS = listOf("POINTS", "HP", "HIVE", "HBD")

        /** SDK refetchInterval. */
        const val POLL_MILLIS = 120_000L

        /** RN claim cache expiry. */
        const val CLAIM_MASK_MILLIS = 180_000L

        const val CLAIM_REFRESH_DELAY_MILLIS = 2_000L

        const val AFTER_BROADCAST_DELAY_MILLIS = 3_000L

        /** How long a selection saved here wins over a lagging node's profile. */
        const val OVERRIDE_MILLIS = 10 * 60_000L

        /** RN populateSelectedAssets: the defaults, then ENGINE and CHAIN tokens of the profile that show (no legacy SPK). */
        fun selectedSymbols(tokens: List<ProfileToken>): List<String> =
            (DEFAULT_ASSETS + tokens.filter { it.show && it.type != "HIVE" && it.symbol != "SPK" }.map { it.symbol }).distinct()
    }
}
