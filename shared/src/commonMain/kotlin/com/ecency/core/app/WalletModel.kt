package com.ecency.core.app

import com.ecency.core.api.broadcast.WalletOps
import com.ecency.core.api.wallet.AssetLayer
import com.ecency.core.api.wallet.PortfolioItem
import com.ecency.core.api.wallet.ProfileToken
import com.ecency.core.api.wallet.Vests
import com.ecency.core.api.wallet.WalletAccount
import com.ecency.core.api.wallet.WalletApi
import com.ecency.core.hive.KeyRole
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs

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
    /** Everything the portfolio answered (the total counts all of it, as RN); a claimed reward it still reports reads 0. */
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

    /** When the portfolio on screen began to be read ([Freshness.stamp]). */
    private var readAt = Long.MIN_VALUE

    /** RN claimsCollection: symbol to (claimed value, expiry), so a claimed reward reads 0 until the server agrees. */
    private val claimMask = mutableMapOf<String, ClaimMask>()

    /** A claimed reward and when its mask ends. */
    private class ClaimMask(val amount: Double, val until: Long)

    /** The portfolio as the server answered it; [WalletState.all] is it with the claims of [claimMask] applied. */
    private var served: List<PortfolioItem> = emptyList()

    /** The account and currency code [served] answered for: a reload for the same pair merges into it ([merged]). */
    private var servedFor: Pair<String, String>? = null
    private val claimingNow = mutableSetOf<String>()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            env.events.collect { e ->
                if (e is AppEvent.SessionChanged) e.username?.let { departed -= it }
                if (e is AppEvent.SessionChanged && e.username != current.account) {
                    job?.cancel()
                    claimMask.clear()
                    claimingNow.clear()
                    served = emptyList()
                    servedFor = null
                    override = null
                    setState { WalletState(currency = WalletCurrency.of(env.prefs)) }
                    if (e.username != null) load(refresh = false)
                }
            }
        }
        // README "Foreground refresh": back after a long trip with the wallet on screen
        scope.launch { env.freshness.staleReturns.collect { if (watchers > 0) refreshIfStale() } }
        // the swaps an earlier run left pending come back before the first load reads a balance (loads read them first)
        load(refresh = false)
    }

    /** A screen showing the wallet appeared (or went away, [visible] false): polls every [POLL_MILLIS] while shown. */
    fun visible(shown: Boolean) {
        watchers = (watchers + if (shown) 1 else -1).coerceAtLeast(0)
        if (shown) refreshIfStale()
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

    /** The portfolio again without the spinner (README "Foreground refresh"; asset details on show). */
    internal fun refreshQuietly() {
        if (job?.isActive == true || !current.loaded) return
        load(refresh = true, quiet = true)
    }

    private fun refreshIfStale() {
        if (env.freshness.isStale(readAt)) refreshQuietly()
    }

    /** Reloads now and returns when done (pull to refresh). */
    suspend fun refreshAndWait() {
        refresh()
        job?.join()
    }

    /**
     * RN _delayedRefreshCoinsData: the portfolio again 3 s after a broadcast (the chain and the API need a moment). Open
     * asset histories read theirs then too ([AppEvent.WalletSent]): RN left them as they were until a pull.
     */
    fun refreshLater() {
        val user = current.account ?: return
        scope.launch {
            delay(AFTER_BROADCAST_DELAY_MILLIS)
            if (owns(user)) {
                load(refresh = true)
                env.emit(AppEvent.WalletSent(user))
            }
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
                // a closed signing prompt says nothing
                AppError.from(e, "wallet.claim").takeIf { it.kind != ErrorKind.CANCELLED }?.let { env.ui.toast(WalletCopy.claimFailed(it.text), it) }
                false
            }
            claimingNow -= symbol
            if (ok && owns(user)) {
                claimMask[symbol] = ClaimMask(claimed, env.nowMillis() + CLAIM_MASK_MILLIS)
                env.ui.toast(WalletCopy.CLAIMED)
            }
            publish()
            if (ok) readUntilClaimed(user, symbol, claimed)
        }
    }

    /**
     * RN reads the portfolio once, 2 s after a claim; the portfolio can still answer the old balance then, which stayed
     * on screen until a manual refresh (tester report). Reads again, quietly, at [CLAIM_REREAD_MILLIS] (at most five
     * reads) and stops once the portfolio shows the claim: its pending reward fell below the claimed amount, which only
     * a claim does. The balance is always the server's: the app never adds the claim itself, so a read that already
     * holds it, with a transfer or a new reward besides, cannot count it twice.
     */
    private suspend fun readUntilClaimed(user: String, symbol: String, claimed: Double) {
        for (wait in CLAIM_REREAD_MILLIS) {
            delay(wait)
            if (!owns(user)) return
            if (job?.isActive != true) load(refresh = true, quiet = true)
            job?.join()
            val shown = served.firstOrNull { it.symbol == symbol }?.let { it.pendingRewards < claimed - CLAIM_EPSILON } == true
            if (shown) return
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
            // not reported: rethrown (the caller maps it) unless the claim landed after all
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

    /**
     * A swap's sale of [amount] [symbol] from [account] that may be on chain since [at] (this phone's clock). Taken off
     * the swap form's balance until the chain has decided it for good: past its transaction's expiration it is in a
     * block or never will be. [expiresBy] bounds that expiration in chain time, once known. [floor]: the last chain
     * read before it less the swaps still waiting and this one; a read sent after it (in a later millisecond, [atMono]) at or below that shows it.
     * [orderId]: its limit order's id, which only this swap's own operation carries in the account's history.
     * [latestBy]: for one without [expiresBy], the latest chain time it could still land, from the last chain read
     * before it (its head, the time since, an hour for a late approval and a transaction's hour); null without one.
     * [atMono]: [at] on the monotonic clock, which orders the loads of this run against it (a phone clock set back
     * never makes a later load look sent before it); [Long.MIN_VALUE] for one restored, which every load follows.
     */
    private class Swap(
        val account: String, val symbol: String, val amount: Double, val at: Long, var expiresBy: Long?,
        val floor: Double?, val floorSentAt: Long, val actionId: String?, val orderId: Long?, val latestBy: Long? = null,
        /** Its outcome is undecided: a wallet app may still approve it later (a HiveAuth request closed while waiting). */
        val lateApproval: Boolean,
        val atMono: Long,
    ) {
        /** How long after its acceptance could be bounded it can still land: a late approval adds its hour. */
        val window: Long get() = MAX_TRANSACTION_LIFETIME_MILLIS * (if (lateApproval) 2 else 1)

        /** Short looks booked for its order: its balance drop showed before its block reached the history. */
        var orderLooks = 0
    }

    /** App-wide, so a swap screen opened again, or one closed before the swap ended, still takes the sale off. */
    private val swaps = mutableListOf<Swap>()

    /** The action of every swap recorded here (settled ones too): their broadcasts are not another send. */
    private val swapActions = mutableSetOf<String>()

    /** The chain's liquid HIVE and HBD of [account] (get_accounts), from a load sent at [sentAt] that read the head time [head]. */
    private class ChainRead(val account: String, val liquid: Map<String, Double>, val head: Long, val sentAt: Long, val sentMono: Long)

    private var chainRead: ChainRead? = null
    private var settleLoad: Job? = null

    /** Loads [settle] may still book: enough for the longest wait, never endless on a stalled chain. */
    private var settleLoadsLeft = 0

    /**
     * [account]'s sale of [amount] [symbol] may be on chain. [keySigned]: this app signed it, with an expiration of this
     * phone's clock plus [KEY_TRANSACTION_LIFETIME_MILLIS] (HiveBroadcaster), written before now. Signed elsewhere (or
     * its outcome undecided) the chain still caps it at acceptance plus [MAX_TRANSACTION_LIFETIME_MILLIS] ([undecided]: a late approval adds an hour), and the
     * first load sent after now bounds the acceptance by its head time.
     */
    internal fun spent(account: String, symbol: String, amount: Double, keySigned: Boolean, actionId: String? = null, signedExpiresAt: Long? = null, orderId: Long? = null, undecided: Boolean = false) {
        // an account that left the device records nothing (an outcome of its send can still arrive)
        if (account in departed) return
        val now = env.nowMillis()
        val waiting = swaps.filter { it.account == account && it.symbol == symbol }.sumOf { it.amount }
        // no floor from a read sent before another send of this account (one not among its swaps here): it may have
        // spent as much
        actionId?.let { swapActions += it }
        val known = swapActions.toSet()
        val floorRead = chainRead?.takeIf { it.account == account && !env.signing.broadcastSince(account, it.sentAt, known) }
        val floor = floorRead?.liquid?.get(symbol)?.let { it - waiting - amount }
        // the signed expiration itself; else this phone's clock now, which was later than at signing unless it stepped back
        val expiresBy = if (keySigned) signedExpiresAt ?: (now + KEY_TRANSACTION_LIFETIME_MILLIS) else null
        // signed elsewhere: accepted by now, it lands within its transaction's hour; undecided, a wallet app may still
        // approve it after now (a HiveAuth request closed while waiting), within the hour, and it lives an hour more. So
        // it lands no later than the last chain head plus the time since that read (on the monotonic clock: a phone
        // clock set back never shortens it) plus that window. Kept across a restart, where the phone's clock never decides.
        val window = MAX_TRANSACTION_LIFETIME_MILLIS * (if (undecided) 2 else 1)
        val latestBy = if (expiresBy != null) null else chainRead?.takeIf { it.account == account }?.let {
            it.head + (env.security.monotonicMillis() - it.sentMono).coerceAtLeast(0) + window
        }
        swaps += Swap(account, symbol, amount, now, expiresBy, floor, floorRead?.sentAt ?: now, actionId, orderId, latestBy, undecided, env.security.monotonicMillis())
        settleLoadsLeft = SETTLE_LOADS
        saveSwaps()
    }

    /**
     * What the swap form may offer of [symbol]: the last chain read of the account (the portfolio, which can lag
     * behind the chain, only when there is none; a failed read keeps the last) less every swap the chain may not have
     * decided, or shown. A swap leaves early on a chain read sent after it that is down by the whole swap from the
     * read before it ([Swap.floor]), when the account's history read just before it has the swap's own order: a
     * deposit only moves the balance up, and a transfer from another wallet is not that order. Otherwise it waits for
     * its expiration: accepted is not decided, nor is a block, and a read that has not gone down by it may come from a
     * server without it.
     */
    internal fun available(symbol: String): Double? {
        // nothing to offer before the saved pending swaps are back
        if (!swapsRestored) {
            restoreOnce()
            return null
        }
        val account = current.account ?: return null
        val base = chainRead?.takeIf { it.account == account }?.liquid?.get(symbol) ?: current.item(symbol)?.let(::liquid) ?: return null
        val pending = swaps.filter { it.account == account && it.symbol == symbol }.sumOf { it.amount }
        return (base - pending).coerceAtLeast(0.0)
    }

    /**
     * A load of [account] sent at [sentMono] (the monotonic clock) landed with the chain read [read], null when its account or time read
     * failed, or the load did (it then settles nothing), and [landed]: the swap orders the account's history had just
     * before. Swaps it shows leave ([Swap.floor], [Swap.orderId]); swaps it can bound get
     * their expiration bound, and those the chain decided [NODE_LAG_MILLIS] before its head time leave too (the account
     * read may come from a server of the node URL a little behind the one that told the time). A swap still waiting
     * books the load after.
     */
    private fun settle(account: String, sentMono: Long, read: ChainRead?, landed: Set<Long> = emptySet()) {
        val head = read?.head
        var orderSoon = false
        val before = swaps.size
        if (read != null && head != null) {
            // a send of this account started since the floor read, the swap's and those waiting aside, could be the drop
            val known = swapActions.toSet()
            val dropped = swaps.filter { sw ->
                sw.account == account && sentMono > sw.atMono && sw.floor != null && (read.liquid[sw.symbol]?.let { it <= sw.floor + HALF_UNIT } == true) &&
                    !env.signing.broadcastSince(account, sw.floorSentAt, known)
            }
            // one from another wallet could be too, so the swap's own order must be on chain; an accepted swap shows in
            // the balance before its block reaches the history, so a few short looks follow before its expiration wait
            swaps.removeAll { sw -> sw in dropped && sw.orderId != null && sw.orderId in landed }
            dropped.filter { it in swaps && it.orderLooks < ORDER_LOOKS }.forEach { it.orderLooks++; orderSoon = true }
            val bound = swaps.filter { it.account == account && it.expiresBy == null && sentMono > it.atMono }
            bound.forEach { sw -> sw.expiresBy = (head + sw.window).let { by -> sw.latestBy?.let { minOf(it, by) } ?: by } }
            swaps.removeAll { it.account == account && it.expiresBy?.let { by -> head >= by + NODE_LAG_MILLIS } == true }
            // a bound just set is saved too: after a restart the hour runs from it, never from a later load
            if (swaps.size != before || bound.isNotEmpty() || saveFailed) saveSwaps()
        }
        // a failed or set aside read in the middle of a swap's short looks keeps them going
        if (read == null || head == null) {
            swaps.filter { it.account == account && it.orderLooks in 1 until ORDER_LOOKS }.forEach { it.orderLooks++; orderSoon = true }
        }
        val waiting = swaps.filter { it.account == account }
        if (waiting.isEmpty() || settleLoadsLeft-- <= 0) return
        val wait = waiting.minOf { sw -> if (head == null) NODE_LAG_MILLIS else sw.expiresBy?.let { it + NODE_LAG_MILLIS - head } ?: NODE_LAG_MILLIS }
        settleLoad?.cancel()
        settleLoad = scope.launch {
            delay(if (orderSoon) ORDER_LOOK_MILLIS else wait.coerceAtLeast(NODE_LAG_MILLIS) + 1_000)
            if (current.account == account) load(refresh = true, quiet = true)
        }
    }

    /**
     * The pending swaps outlive the process (an app restart inside their window would otherwise offer the swapped
     * funds again): saved to the secure store at each change ([SWAPS_ENTRY]; a wipe clears it, [forgetSwaps] an
     * account's), read back at [start]. A restored swap has no floor, as the sends this app started before the restart
     * are not known any more: it waits for its expiration (its signed one, or an hour from the first load after).
     */
    private fun saveSwaps() {
        // finishes even if the model closes meanwhile: a swap recorded must reach the store
        scope.launch(kotlinx.coroutines.NonCancellable) {
            // a list written before the saved one was read back would drop it
            if (!ensureSwapsRestored()) {
                saveFailed = true
                return@launch
            }
            swapWrites.withLock {
                // the list as it is when written (on main), so an older one never lands after a newer change
                val snapshot = swaps.map { StoredSwap(it.account, it.symbol, it.amount, it.at, it.expiresBy, it.actionId, it.orderId, it.latestBy, it.lateApproval) }
                try {
                    withContext(env.dispatchers.io) {
                        val store = env.vault.vault.store
                        store.withLock { if (snapshot.isEmpty()) store.delete(SWAPS_ENTRY) else store.putString(SWAPS_ENTRY, StoredSwap.encode(snapshot)) }
                    }
                    saveFailed = false
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // kept in memory; the next load writes it again
                    saveFailed = true
                    ErrorReports.quiet(e, "wallet.saveSwaps")
                }
            }
        }
    }

    private var restoring: Job? = null

    /** Starts reading the saved swaps back (the swap form asked for a balance first); a load follows to show it. */
    private fun restoreOnce() {
        if (restoring?.isActive == true) return
        restoring = scope.launch(kotlinx.coroutines.NonCancellable) {
            if (ensureSwapsRestored() && job?.isActive != true) load(refresh = true, quiet = true)
        }
    }

    /**
     * Reads the saved swaps back, once per run, before any balance is read or swap saved. A failed read is not an
     * empty list: it is tried again by the next load or save, and until then the swap form has no balance.
     */
    private suspend fun ensureSwapsRestored(): Boolean = restoreLock.withLock {
        if (!swapsRestored) swapsRestored = restoreSavedSwaps()
        swapsRestored
    }

    private val restoreLock = kotlinx.coroutines.sync.Mutex()

    private suspend fun restoreSavedSwaps(): Boolean = swapWrites.withLock {
        val stored = try {
            withContext(env.dispatchers.io) { env.vault.vault.store.getString(SWAPS_ENTRY) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorReports.quiet(e, "wallet.restoreSwaps")
            return@withLock false
        } ?: return@withLock true
        val now = env.nowMillis()
        val known = swaps.mapNotNull { it.actionId }.toSet()
        // each is settled by the chain's time: its expiration, or the latest it could land ([Swap.latestBy]); this
        // phone's clock, which may have moved while the app was closed, never drops one
        val back = StoredSwap.decode(stored).filter { it.actionId !in known && it.account !in departed }
        if (back.isEmpty()) return@withLock true
        back.forEach { s ->
            s.actionId?.let { swapActions += it }
            // never later than now: the first load after the restart bounds an unbound one, whatever the clock said then
            val at = minOf(s.at, now - 1)
            swaps += Swap(s.account, s.symbol, s.amount, at, s.expiresBy, null, at, s.actionId, s.orderId, s.latestBy, s.lateApproval, Long.MIN_VALUE)
        }
        settleLoadsLeft = SETTLE_LOADS
        true
    }

    /** Accounts left the device ([AppEnvironment.accountsLeft]); null: all. Their pending swaps go, saved ones too. */
    internal suspend fun forgetSwaps(accounts: List<String>?) {
        // the saved list is filtered itself: this run may not have read it back yet
        accounts?.let { departed += it }
        swapWrites.withLock {
            swaps.removeAll { accounts == null || it.account in accounts }
            try {
                withContext(env.dispatchers.io) {
                    val store = env.vault.vault.store
                    store.withLock {
                        val left = if (accounts == null) emptyList() else store.getString(SWAPS_ENTRY)?.let(StoredSwap::decode).orEmpty().filterNot { it.account in accounts }
                        if (left.isEmpty()) store.delete(SWAPS_ENTRY) else store.putString(SWAPS_ENTRY, StoredSwap.encode(left))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the next load writes the list as it is in memory, without the account
                saveFailed = true
                ErrorReports.quiet(e, "wallet.forgetSwaps")
            }
        }
    }

    private val swapWrites = kotlinx.coroutines.sync.Mutex()

    /** The saved swaps were read back ([ensureSwapsRestored]): until then loads and saves try again, and [available] is null. */
    private var swapsRestored = false

    /** The last write of the swaps failed: the next load writes them again. */
    private var saveFailed = false

    /** Accounts that left the device in this run ([forgetSwaps]) until they sign in again: they record no swap. */
    private val departed = mutableSetOf<String>()

    /** The ids of [account]'s latest limit orders on chain (its history's `limit_order_create` rows). */
    private suspend fun landedOrders(account: String): Set<Long> =
        api.history(account, -1, LANDED_ORDERS_READ, listOf("limit_order_create"))
            .filter { it.s("owner") == account }
            .mapNotNull { it.s("orderid")?.toLongOrNull() }
            .toSet()

    private fun chainLiquid(account: WalletAccount) = mapOf("HIVE" to Vests.amount(account.balance), "HBD" to Vests.amount(account.hbdBalance))

    private fun liquid(item: PortfolioItem): Double = item.liquid ?: item.balance

    private fun owns(user: String) = current.account == user && env.session.current.username == user

    /** [quiet]: no spinner, and a failure keeps what is shown without the banner (README "Foreground refresh"). */
    private fun load(refresh: Boolean, quiet: Boolean = false) {
        job?.cancel()
        var loading: String? = null
        val stamp = env.freshness.stamp()
        setState { it.copy(isLoading = !refresh, isRefreshing = refresh && !quiet, error = if (quiet) it.error else null, currency = WalletCurrency.of(env.prefs)) }
        job = scope.launch {
            try {
                env.session.awaitLoaded()
                // never a balance before the swaps an earlier run left pending are back (a failed read: tried again next load)
                ensureSwapsRestored()
                val user = env.session.current.username
                if (user == null) {
                    served = emptyList()
                    servedFor = null
                    setState { WalletState(currency = it.currency) }
                    return@launch
                }
                setState { if (it.account == user) it else WalletState(account = user, isLoading = true, currency = it.currency) }
                val currency = current.currency
                val sentAt = env.nowMillis()
                // the same moment on a clock the user cannot set back (time since this read never rests on the phone's clock)
                val sentMono = env.security.monotonicMillis()
                loading = user
                val orders = swaps.filter { it.account == user }.mapNotNull { it.orderId }
                val accountJob = async {
                    // the swap orders on chain, read before the account (the two may still come from different servers)
                    val landed = if (orders.isEmpty()) {
                        emptySet()
                    } else {
                        runCatching { landedOrders(user) }.getOrElse {
                            if (it is CancellationException) throw it
                            // no order then: the swap waits for its expiration
                            ErrorReports.quiet(it, "wallet.swapOrders")
                            emptySet()
                        }
                    }
                    landed to runCatching { api.account(user) }.getOrNull()
                }
                val globals = async { runCatching { api.chainGlobals() }.getOrNull() }
                val items = api.portfolio(user, currency.code, onlyEnabled = true)
                // a failed account read keeps the selection shown (the profile's tokens), never the defaults alone
                val (landed, chain) = accountJob.await()
                val tokens = chain?.profileTokens
                val chainNow = globals.await()
                val perMVests = chainNow?.hivePerMVests ?: current.hivePerMVests
                if (!owns(user)) return@launch
                readAt = stamp
                val key = user to currency.code
                val selection = tokens?.let(::selectionFor) ?: current.selection
                served = if (refresh && servedFor == key) merged(served, items, selection) else items
                servedFor = key
                // the chain read only moves forward: one without a head time, or from a node behind the last, is set
                // aside (a failed one too), and settles nothing
                val head = chainNow?.headTimeMillis
                val last = chainRead?.takeIf { it.account == user }
                val forward = if (chain != null && head != null && (last == null || head >= last.head)) ChainRead(user, chainLiquid(chain), head, sentAt, sentMono) else null
                if (forward != null) chainRead = forward
                settle(user, sentMono, forward, landed)
                loading = null
                setState {
                    withRows(
                        it.copy(
                            hivePerMVests = perMVests, isLoading = false, isRefreshing = false, loaded = true,
                            updatedAtMillis = env.nowMillis(), selection = selection, error = null,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // a quiet refresh keeps what is on screen and is reported as a warning only
                val error = if (quiet) null.also { ErrorReports.quiet(e, "wallet.refresh") } else AppError.from(e, "wallet.load")
                // a swap still waiting books its next look whatever failed
                loading?.let { settle(it, 0, null) }
                setState { it.copy(isLoading = false, isRefreshing = false, error = if (quiet) it.error else error) }
            }
        }
    }

    /**
     * A reload's answer over the portfolio shown (README "Wallet", reloads keep rows): an answered symbol takes the new
     * item. A symbol the answer left out keeps its last one only while the answer is plainly partial (it lacks one of
     * [DEFAULT_ASSETS], which a whole answer always holds) and the symbol is still in [selection], so a partial answer
     * (POINTS alone after quick claims) never drops a row or the total's share of it, while a token turned off in Manage
     * Assets (the enabled-only portfolio leaves it out) or one a whole answer no longer has goes, with its value and any
     * stale pending reward. A full load ([retry], the first load, a currency or account change) replaces.
     */
    private fun merged(old: List<PortfolioItem>, fresh: List<PortfolioItem>, selection: List<String>): List<PortfolioItem> {
        val bySymbol = fresh.associateBy { it.symbol }
        val partial = DEFAULT_ASSETS.any { it !in bySymbol }
        val kept = old.mapNotNull { bySymbol[it.symbol] ?: it.takeIf { partial && it.symbol in selection } }
        val known = old.mapTo(HashSet()) { it.symbol }
        return kept + fresh.filter { it.symbol !in known }
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
        claimMask.entries.removeAll { it.value.until <= now }
        val all = served.map(::claimed)
        val rows = s.selection.mapNotNull { sym -> all.firstOrNull { it.symbol == sym } }.map { item ->
            AssetRow(
                item = item,
                name = WalletCopy.assetName(item),
                balanceText = WalletFormat.amount(item.balance),
                fiatText = item.fiatRate?.let { WalletFormat.fiat(item.balance * it, s.currency) },
                pendingText = if (item.pendingRewards > 0) "${WalletFormat.amount(item.pendingRewards)} ${item.symbol}" else null,
                claiming = item.symbol in claimingNow,
            )
        }
        val total = all.sumOf { it.balance * (it.fiatRate ?: 0.0) }
        return s.copy(all = all, rows = rows, totalText = if (s.loaded) WalletFormat.fiat(total, s.currency) else "--.--")
    }

    /**
     * RN claimsCollection: a claimed reward reads 0 while the server still reports exactly that amount as pending, for
     * 3 minutes at most. Any other pending amount is the server's and shows; the balance is never changed here.
     */
    private fun claimed(item: PortfolioItem): PortfolioItem {
        val mask = claimMask[item.symbol] ?: return item
        return if (abs(item.pendingRewards - mask.amount) < CLAIM_EPSILON) item.copy(pendingRewards = 0.0) else item
    }

    companion object {
        private const val CLAIM_EPSILON = 1e-9

        /** RN DEFAULT_ASSETS. */
        val DEFAULT_ASSETS = listOf("POINTS", "HP", "HIVE", "HBD")

        /** SDK refetchInterval. */
        const val POLL_MILLIS = 120_000L

        /** RN claim cache expiry. */
        const val CLAIM_MASK_MILLIS = 180_000L

        /** The waits between the portfolio reads after a claim: RN's 2 s first, then until about 40 s. */
        val CLAIM_REREAD_MILLIS = listOf(2_000L, 3_000L, 5_000L, 10_000L, 20_000L)

        const val AFTER_BROADCAST_DELAY_MILLIS = 3_000L

        /** Half of 0.001, the chain's smallest HIVE/HBD amount: float noise in a balance comparison. */
        private const val HALF_UNIT = 0.0005

        /** Limit orders of the account's history read for a waiting swap's: far more than one sitting places. */
        private const val LANDED_ORDERS_READ = 50

        /** A swap whose drop shows before its order: looked at again this soon (a block is 3 s), at most [ORDER_LOOKS] times. */
        private const val ORDER_LOOK_MILLIS = 5_000L
        private const val ORDER_LOOKS = 6

        /**
         * How far behind the head another node, or another server behind the same URL, may answer: above the 90 s
         * (30 blocks) a node may lag before the app's node health sets it aside (NodeHealthTracker).
         */
        const val NODE_LAG_MILLIS = 120_000L

        /** What this app's signer gives a transaction. */
        const val KEY_TRANSACTION_LIFETIME_MILLIS = com.ecency.core.api.broadcast.HiveBroadcaster.EXPIRATION_MILLIS

        /** Hive's longest transaction lifetime (HIVE_MAX_TIME_UNTIL_EXPIRATION): a signer elsewhere may set any up to it. */
        const val MAX_TRANSACTION_LIFETIME_MILLIS = 3_600_000L

        /** How long a selection saved here wins over a lagging node's profile. */
        const val OVERRIDE_MILLIS = 10 * 60_000L

        /** RN populateSelectedAssets: the defaults, then ENGINE and CHAIN tokens of the profile that show (no legacy SPK). */
        fun selectedSymbols(tokens: List<ProfileToken>): List<String> =
            (DEFAULT_ASSETS + tokens.filter { it.show && it.type != "HIVE" && it.symbol != "SPK" }.map { it.symbol }).distinct()

        private const val SWAPS_ENTRY = "wallet.pendingSwaps"
        // a failed load looks again a node lag on: enough looks for the longest a swap can wait (an undecided one's
        // two hours, Swap.window) and its margin, with some to spare
        private const val SETTLE_LOADS = (2 * MAX_TRANSACTION_LIFETIME_MILLIS / NODE_LAG_MILLIS).toInt() + 10 + ORDER_LOOKS
    }
}

/** A pending swap as saved across restarts ([WalletModel]'s secure store entry): no key, no memo, nothing signed. */
internal data class StoredSwap(
    val account: String, val symbol: String, val amount: Double, val at: Long, val expiresBy: Long?,
    val actionId: String?, val orderId: Long?, val latestBy: Long? = null, val lateApproval: Boolean = false,
) {
    companion object {
        fun encode(swaps: List<StoredSwap>): String = kotlinx.serialization.json.buildJsonArray {
            swaps.forEach { s ->
                add(
                    kotlinx.serialization.json.buildJsonObject {
                        put("account", kotlinx.serialization.json.JsonPrimitive(s.account))
                        put("symbol", kotlinx.serialization.json.JsonPrimitive(s.symbol))
                        put("amount", kotlinx.serialization.json.JsonPrimitive(s.amount))
                        put("at", kotlinx.serialization.json.JsonPrimitive(s.at))
                        s.expiresBy?.let { put("expiresBy", kotlinx.serialization.json.JsonPrimitive(it)) }
                        s.actionId?.let { put("actionId", kotlinx.serialization.json.JsonPrimitive(it)) }
                        s.orderId?.let { put("orderId", kotlinx.serialization.json.JsonPrimitive(it)) }
                        s.latestBy?.let { put("latestBy", kotlinx.serialization.json.JsonPrimitive(it)) }
                        if (s.lateApproval) put("lateApproval", kotlinx.serialization.json.JsonPrimitive(true))
                    },
                )
            }
        }.toString()

        /** What [raw] holds that reads as a swap; anything else is skipped. */
        fun decode(raw: String): List<StoredSwap> {
            val array = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray }.getOrNull() ?: return emptyList()
            return array.mapNotNull { el ->
                val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
                fun p(k: String) = o[k] as? kotlinx.serialization.json.JsonPrimitive
                fun text(k: String) = p(k)?.takeIf { it.isString }?.content
                fun long(k: String) = p(k)?.takeIf { !it.isString }?.content?.toLongOrNull()
                val account = text("account")?.takeIf(com.ecency.core.secure.KeyVault::isValidUsername) ?: return@mapNotNull null
                val symbol = text("symbol")?.takeIf { it == "HIVE" || it == "HBD" } ?: return@mapNotNull null
                val amount = p("amount")?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.takeIf { it > 0 && it.isFinite() } ?: return@mapNotNull null
                StoredSwap(account, symbol, amount, long("at") ?: return@mapNotNull null, long("expiresBy"), text("actionId"), long("orderId"), long("latestBy"), p("lateApproval")?.takeIf { !it.isString }?.content == "true")
            }
        }
    }
}
