package com.ecency.core.app

import com.ecency.core.api.EcencyHttpException
import com.ecency.core.api.EcencyTimeoutException
import com.ecency.core.api.ecency.AccountOrderMeta
import com.ecency.core.api.ecency.PurchaseApi
import com.ecency.core.api.ecency.PurchaseOrder
import com.ecency.core.api.json.SafeJson
import com.ecency.core.api.model.arr
import com.ecency.core.api.model.long
import com.ecency.core.api.model.obj
import com.ecency.core.api.model.str
import com.ecency.core.l10n.L
import com.ecency.core.l10n.LocaleFormat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/** RN's store products (boostScreen, accountBoostScreen, spinGameScreen, registerAccountModal ITEM_SKUS). */
object PurchaseSkus {
    val POINTS = listOf("099points", "199points", "499points", "999points", "4999points", "9999points")
    const val BOOST = "999boosts"
    const val SPINS = "499spins"
    const val ACCOUNT = "999accounts"

    /**
     * Ecency Pro for 12 months (web `PRO_SKU`, issue #3555): a Play one-time product consumed after delivery like the
     * others, on the App Store a Non-Renewing Subscription or a Consumable (both are finished after delivery). Buying
     * again while a member extends by 12 months (Ecency's Pro fulfilment).
     */
    const val PRO = "1999pro"

    /** RN `FREE_ESTM`: the last Get Points row, which opens the spin game. Not a store product. */
    const val FREE_POINTS = "freePoints"

    /** RN productItemLine `DEALS`. */
    val DEALS: Map<String, String> get() = mapOf("9999points" to L.native.purchases.dealBest, "4999points" to L.native.purchases.dealPopular)

    /** RN accountBoostScreen `ACCOUNT_BOOST_VESTS`: the delegation a boost gives, shown in HP. */
    const val ACCOUNT_BOOST_VESTS = 553_311.0

    val ALL: List<String> = POINTS + listOf(BOOST, SPINS, ACCOUNT, PRO)

    /** RN `_getTitle`: "FREE POINTS" stays, anything else is its digits and " POINTS". */
    fun title(storeTitle: String): String {
        val upper = storeTitle.uppercase()
        if (upper == "FREE POINTS") return upper
        return L.native.purchases.pointsTitle(points = upper.filter { it in '0'..'9' })
    }

    /** RN `_getItems`: `sort((a, b) => parseFloat(a.price) - parseFloat(b.price)).reverse()`; an unreadable price sorts last. */
    fun sortByPriceDescending(products: List<StoreProduct>): List<StoreProduct> =
        products.sortedWith(compareBy<StoreProduct> { it.price.toDoubleOrNull() == null }.thenByDescending { it.price.toDoubleOrNull() ?: 0.0 })

    /** Boost+ in HP with one decimal (RN `vestsToHp(...).toFixed(1)`). */
    fun boostHp(hivePerMVests: Double): String = LocaleFormat.toFixed(ACCOUNT_BOOST_VESTS / 1e6 * hivePerMVests, 1)
}

/** RN en-US `boost.*`, `buy_account.*`, the purchase alerts, and the few texts this app adds (no RN copy exists). */
object PurchaseCopy {
    val POINTS_TITLE: String get() = L.boost.title
    val BOOST_TITLE: String get() = L.boost.account.title
    val BOOST_DESC: String get() = L.boost.account.desc
    val POINTS_SUCCESS: String get() = L.boost.pointsPurchaseSuccess
    fun pointsFailed(message: String) = L.boost.pointsPurchaseFailMsg(message = message)
    fun confirmTitle(username: String) = L.boost.confirmPurchase(username = username)
    fun confirmBody(points: String, username: String, price: String) = L.boost.confirmPurchaseSummary(points = points, username = username, price = price)
    fun boostRow(hp: String) = L.native.purchase.boostRow(hp = hp)
    val BUY_ACCOUNT: String get() = L.buyAccount.title
    fun buyAccount(price: String) = L.buyAccount.btnRegister(price = price)
    val CLAIM_ACCOUNT: String get() = L.buyAccount.claim
    val REGISTER_FAIL: String get() = L.register.registerFail
    val WARNING: String get() = L.alert.warning
    val FAIL: String get() = L.alert.fail
    val CANCEL: String get() = L.alert.cancel
    val CONFIRM: String get() = L.alert.confirm
    val CONNECTION_ISSUES: String get() = L.alert.connectionIssues
    val GOOGLE_PLAY_VERSION: String get() = L.alert.googlePlayVersion
    val NOT_LOGGED_IN: String get() = L.login.notLoggedinAlert
    val NOT_LOGGED_IN_DESC: String get() = L.login.notLoggedinAlertDesc
    val ACCOUNT_META_MISSING: String get() = L.native.purchase.accountMetaMissing

    val STUCK: String get() = L.native.purchase.stuck

    fun earlierPurchase(target: String) = L.native.purchase.earlierPurchase(target = target)
    fun earlierPending(target: String) = L.native.purchase.earlierPending(target = target)

    // not RN: RN shows a pending payment as paid and a recorded order as a plain success
    val PENDING: String get() = L.native.purchase.pending
    val PROCESSING: String get() = L.native.purchase.processing
}

/** Who a purchase is for (RN: the screen's username param or the current account; for a paid account, the new account's details). */
data class PurchaseTarget(val user: String = "", val meta: AccountOrderMeta? = null)

/** What happened to a purchase, for the screens that started it ([fromBuy]) or for nobody (a recovered one). */
sealed interface PurchaseOutcome {
    val productId: String
    val fromBuy: Boolean

    /** Ecency accepted the order and the store purchase was finished. */
    data class Delivered(override val productId: String, override val fromBuy: Boolean) : PurchaseOutcome

    /** The store holds the payment as pending: nothing was posted. */
    data class Pending(override val productId: String, override val fromBuy: Boolean) : PurchaseOutcome

    /** The order's outcome is not known yet (no answer, or Ecency had it before): it is kept and asked again. */
    data class Processing(override val productId: String, override val fromBuy: Boolean) : PurchaseOutcome

    /** The order could not be placed; the purchase is kept for a later try. */
    data class Failed(override val productId: String, override val fromBuy: Boolean, val message: String) : PurchaseOutcome

    /**
     * The store still holds an earlier purchase of this product, saved for [target] (another account than this buy's):
     * it is completed for [target] first ([pending]: its payment is still pending, no new purchase opens).
     */
    data class Earlier(override val productId: String, override val fromBuy: Boolean, val target: String, val pending: Boolean) : PurchaseOutcome

    /** An earlier purchase of this product Ecency keeps refusing: the user is told once to contact support (a new purchase opens). */
    data class Stuck(override val productId: String, override val fromBuy: Boolean) : PurchaseOutcome

    /** The store refused or the user cancelled ([report] says whether the user still has to hear about it). */
    data class StoreFailed(override val productId: String, override val fromBuy: Boolean, val error: StoreError, val report: IapReport) : PurchaseOutcome
}

data class PurchaseServiceState(
    val connected: Boolean = false,
    /** Products by id, as the store last answered. */
    val products: Map<String, StoreProduct> = emptyMap(),
    /** Products with a paid, unfinished purchase on the device (RN `unconsumedPurchases`). */
    val unfinished: Set<String> = emptySet(),
    /** Products whose payment the store holds as pending. */
    val pending: Set<String> = emptySet(),
    /** Products being bought or ordered right now (RN `isProcessing`). */
    val busy: Set<String> = emptySet(),
)

/**
 * RN inAppPurchaseContainer as one app-wide service (RN mounts one per purchase screen): the store connection, the
 * product list, purchases, the Ecency order and the finish. One per app ([AppEnvironment.purchases]); each app sets
 * [AppEnvironment.store] and calls [start] at launch (StoreKit wants its transaction listener from the start) and
 * [onForeground] when the app comes back.
 *
 * The money rules:
 * - A purchase is finished (Play consume, StoreKit finish) only after Ecency accepted its order; a failed finish is
 *   not a failed purchase (it is retried at the next recovery).
 * - A pending payment is never posted and never shown as paid (RN posted Play's pending purchases).
 * - One receipt is never posted twice at once ([inFlight]), and every unfinished paid purchase is posted again at
 *   start, on foreground and after an account change until Ecency took it, so a paid purchase is never dropped.
 * - 409 is not taken at its word: Ecency answers it for a receipt it stored before but also for any failed insert
 *   (its database down or locked, for as long as that lasts). The purchase is finished on it only when this device
 *   saw Ecency accept the receipt before, or when Play says the purchase was acknowledged (Ecency's worker
 *   acknowledges after it delivered). Otherwise it stays unfinished and is posted again later (silently): on Play an
 *   order Ecency failed lapses into Google's automatic refund instead of being consumed with nothing delivered (RN
 *   consumed it); on the App Store the transaction waits in `Transaction.unfinished`, since a finished one is never
 *   refunded.
 * - Who an order is for is saved before the store sheet opens (`purchases.intents`), so an interrupted purchase is
 *   delivered to the account it was bought for, not to whoever is signed in at the next start (RN), and a paid account
 *   keeps its username and e-mail (RN `pendingAccountPurchaseMeta`).
 */
class PurchaseService internal constructor(private val env: AppEnvironment) {
    private val mutableState = MutableStateFlow(PurchaseServiceState())
    val state: StateFlow<PurchaseServiceState> = mutableState.asStateFlow()
    val current: PurchaseServiceState get() = mutableState.value

    private val mutableOutcomes = MutableSharedFlow<PurchaseOutcome>(extraBufferCapacity = 32)
    val outcomes: SharedFlow<PurchaseOutcome> = mutableOutcomes.asSharedFlow()

    /** Swift: every outcome from now on, on the main thread. */
    fun watchOutcomes(onEach: (PurchaseOutcome) -> Unit): Closeable = Closeable(env.background.launch { outcomes.collect { onEach(it) } })

    /** Swift: the state now and every change, on the main thread. */
    fun watch(onEach: (PurchaseServiceState) -> Unit): Closeable = Closeable(env.background.launch { state.collect { onEach(it) } })

    val errors = IapErrors({ env.crashReporter }, { env.store.platform }, env.nowMillis)

    private val api by lazy { PurchaseApi(env.auth.http, env.accounts, env.auth.tokenHost) }

    /** Receipts being posted now, by [orderKey]. */
    private val inFlight = mutableSetOf<String>()

    /** Products whose store sheet this run opened and has not heard back from. */
    private val awaiting = mutableSetOf<String>()

    /** The purchases the store held at the last look, by token. */
    private var held: Map<String, StorePurchase> = emptyMap()

    /** Rechecks scheduled this run, by receipt hash (bounded by [MAX_RECHECKS]). */
    private val rechecks = mutableMapOf<String, Int>()

    private var started = false
    private var lastRecoveryMillis = 0L

    private val listener = object : StoreListener {
        override fun onPurchases(purchases: List<StorePurchase>) {
            env.background.launch { purchases.forEach { process(it, fromBuy = it.productId in awaiting) } }
        }

        override fun onError(error: StoreError) {
            env.background.launch { storeFailed(error, IapStage.PURCHASE) }
        }
    }

    /** At launch, after [AppEnvironment.store] is set: connect, listen, and post whatever an earlier run left. Main thread. */
    fun start() {
        if (started) return
        started = true
        env.store.setListener(listener)
        env.background.launch {
            connect()
            recover(counting = true)
        }
        env.background.launch {
            env.events.collect { if (it is AppEvent.SessionChanged && it.username != null) recover(counting = true) }
        }
    }

    /** The app came back: purchases that completed meanwhile (a settled pending payment) are delivered. Main thread. */
    fun onForeground() {
        if (!started || env.nowMillis() - lastRecoveryMillis < FOREGROUND_RECOVERY_MILLIS) return
        env.background.launch { recover(counting = true) }
    }

    /** RN `initConnection`; null when connected. */
    internal suspend fun connect(): StoreError? {
        if (current.connected) return null
        val error = callStore<Unit> { done -> env.store.connect { e -> done(if (e == null) Unit else null, e) } }.second
        if (error == null) mutableState.update { it.copy(connected = true) } else errors.report(error, IapStage.INIT)
        return error
    }

    /**
     * RN `_getItems` for [ids]: the store's products (RN alerts "connection issues" when it fails, so the error is
     * returned for the screen to show).
     */
    suspend fun products(ids: List<String>): Pair<List<StoreProduct>, StoreError?> {
        connect()?.let { return emptyList<StoreProduct>() to it }
        val (list, error) = callStore<List<StoreProduct>> { done -> env.store.products(ids, done) }
        if (error != null) {
            errors.report(error, IapStage.PRODUCTS)
            return emptyList<StoreProduct>() to error
        }
        // only products the store sells: Play's placeholders for unknown ids have no price
        val sold = list.orEmpty().filter { it.productId in ids && it.localizedPrice.isNotEmpty() }
        mutableState.update { s -> s.copy(products = s.products + sold.associateBy { it.productId }) }
        return sold to null
    }

    /**
     * RN `_buyItem` for a store product. An earlier purchase of [productId] the store still holds goes first and keeps
     * the target it was saved with (it was paid for that account): a paid one is delivered to it instead of buying
     * again (RN), a pending one blocks a second purchase; the user is told when that target is another account. Only
     * without an earlier purchase (or one with no saved target) is [target] saved, before the store sheet opens. The
     * outcome arrives on [outcomes]. Main thread.
     */
    fun buy(productId: String, target: PurchaseTarget) {
        if (productId !in PurchaseSkus.ALL || productId in current.busy) return
        mutableState.update { it.copy(busy = it.busy + productId) }
        env.background.launch {
            connect()?.let { e ->
                storeFailed(e.copy(productId = productId), IapStage.INIT, fromBuy = true)
                return@launch
            }
            val held = held.values.filter { it.productId == productId && it.state != StorePurchaseState.REVOKED }
            // one Ecency already took (only its finish is missing, or the store handed it back re-signed) is no earlier
            // purchase: it is finished quietly and this buy goes on
            held.filter { isAccepted(it) }.forEach { process(it, fromBuy = false) }
            mutableState.update { it.copy(busy = it.busy + productId) }
            val ofProduct = held.filterNot { isAccepted(it) }
            val earlier = ofProduct.firstOrNull { !stuck(it) }
            // a purchase Ecency keeps refusing no longer blocks new ones: it stays unfinished (and retried), the user
            // is told once to contact support
            if (earlier == null) ofProduct.firstOrNull { stuck(it) }?.let { s ->
                if (Marks.tellStuck(env.prefs, orderKey(s))) mutableOutcomes.emit(PurchaseOutcome.Stuck(productId, true))
            }
            val saved = earlier?.let { Intents.find(env.prefs, productId, orderKey(it)) }
            val buyer = target.user.ifEmpty { env.session.current.username.orEmpty() }
            // no account (a paid account bought signed out): no token
            val token = if (buyer.isEmpty()) "" else accountToken(buyer)
            val earlierToken = earlier?.accountToken?.lowercase().orEmpty()
            // an earlier purchase carrying another account's token is that account's: this buy's target is not its own
            val othersToken = productId != PurchaseSkus.ACCOUNT && earlierToken.isNotEmpty() && earlierToken != token
            if (saved == null && !othersToken) Intents.save(env.prefs, productId, target, env.nowMillis())
            awaiting += productId
            if (earlier == null) {
                // the store holds no earlier Pro purchase: a pending one shown for the buyer is gone
                if (productId == PurchaseSkus.PRO && buyer.isNotEmpty()) env.proPurchases.dropPending(buyer)
                val (_, error) = callStore<Unit>(timeout = false) { done -> env.store.purchase(productId, token) { e -> done(if (e == null) Unit else null, e) } }
                if (error != null) storeFailed(error.copy(productId = error.productId ?: productId), IapStage.REQUEST, fromBuy = true)
                return@launch
            }
            // whom the earlier purchase goes to, as process() credits it (a paid account: its saved details)
            val other = if (productId == PurchaseSkus.ACCOUNT) {
                saved?.target?.takeIf { !it.sameAs(target) }?.label()
            } else {
                val credited = if (saved == null && earlierToken.isEmpty()) buyer else creditedUser(earlier)
                credited?.takeIf { !it.equals(buyer, ignoreCase = true) }
            }
            val pending = earlier.state == StorePurchaseState.PENDING
            if (pending && productId == PurchaseSkus.PRO) (other ?: buyer.ifEmpty { null })?.let { env.proPurchases.update(it, ProProgress.PENDING) }
            when {
                pending && other != null -> emit(PurchaseOutcome.Earlier(productId, true, other, pending = true))
                pending -> emit(PurchaseOutcome.Pending(productId, true))
                else -> {
                    if (other != null) mutableOutcomes.emit(PurchaseOutcome.Earlier(productId, true, other, pending = false))
                    // another account's purchase: this buyer is told (Earlier), its delivery is not this buy's success
                    process(earlier, fromBuy = other == null)
                }
            }
        }
    }

    /**
     * A purchase's identity on this device (the in-flight set, the accepted marks): the App Store transaction id, since
     * the same transaction can come back with another signed form; Play's purchase token otherwise.
     */
    /**
     * An App Store purchase Ecency keeps refusing (a failed order answers 409 for good): 409 counted on
     * [STUCK_CONFLICTS] recoveries (a start, a return to the foreground or an account change, at least
     * [CONFLICT_GAP_MILLIS] apart; never the automatic rechecks), or counted once and still not accepted
     * [STUCK_AGE_MILLIS] after the store's purchase date. It stays unfinished
     * and is posted again, but no longer blocks buying the product. Play needs none of this: Google refunds an
     * unacknowledged purchase after 3 days.
     */
    internal fun stuck(p: StorePurchase): Boolean {
        if (env.store.platform == PLAY || p.state != StorePurchaseState.PURCHASED) return false
        if (isAccepted(p)) return false
        val conflicts = Marks.conflicts(env.prefs, orderKey(p))
        // the age alone never decides (a device clock a day ahead would mark a fresh purchase): it needs a refusal too
        return conflicts >= STUCK_CONFLICTS ||
            (conflicts >= 1 && p.purchasedAtMillis > 0 && env.nowMillis() - p.purchasedAtMillis > STUCK_AGE_MILLIS)
    }

    /**
     * Ecency accepted [p] on this device. An earlier build marked App Store purchases by the hash of their signed
     * receipt: that mark still counts for the same receipt.
     */
    internal fun isAccepted(p: StorePurchase): Boolean =
        Marks.accepted(env.prefs, orderKey(p)) || (env.store.platform == APP_STORE && Marks.accepted(env.prefs, receiptHash(p.token)))

    internal fun orderKey(p: StorePurchase): String =
        if (env.store.platform == APP_STORE && p.ref.isNotEmpty()) receiptHash("tx:${p.ref}") else receiptHash(p.token)

    private fun PurchaseTarget.sameAs(o: PurchaseTarget) = user == o.user && meta?.username == o.meta?.username

    /** The account a saved target names: the paid account's name, else the user (empty: the signed-in account). */
    private fun PurchaseTarget.label(): String = meta?.username?.takeIf { it.isNotEmpty() } ?: user.ifEmpty { env.session.current.username.orEmpty() }

    /**
     * RN `_consumeAvailablePurchases`: every purchase the store holds, processed again (outcomes are silent).
     * [counting]: a recovery trigger (start, foreground, account change), whose 409 answers count towards [stuck];
     * the automatic rechecks do not count.
     */
    internal suspend fun recover(counting: Boolean = false) {
        lastRecoveryMillis = env.nowMillis()
        // who an interrupted purchase is credited to needs the accounts on the device (iOS starts purchases and
        // reports the foreground before the session has loaded)
        env.session.awaitLoaded()
        if (connect() != null) return
        val (list, error) = callStore<List<StorePurchase>> { done -> env.store.purchases(done) }
        if (error != null) {
            errors.report(error, IapStage.RECOVER)
            return
        }
        val purchases = list.orEmpty()
        // StoreKit's Transaction.unfinished never lists an Ask to Buy request: its synthetic pending entry stays until
        // a transaction of that product shows up, or for [SYNTHETIC_PENDING_MILLIS] (Apple lets such requests lapse)
        val now = env.nowMillis()
        val waiting = held.values.filter { h ->
            h.isSyntheticPending && purchases.none { it.productId == h.productId } && now - h.purchasedAtMillis < SYNTHETIC_PENDING_MILLIS
        }
        held = (purchases + waiting).associateBy { orderKey(it) }
        // a Pro payment shown as pending that the store no longer holds as pending is not pending any more
        if ((purchases + waiting).none { it.productId == PurchaseSkus.PRO && it.state == StorePurchaseState.PENDING }) env.proPurchases.dropPending(null)
        mutableState.update { s ->
            s.copy(
                unfinished = purchases.filter { it.state == StorePurchaseState.PURCHASED && !stuck(it) }.map { it.productId }.toSet(),
                pending = (purchases + waiting).filter { it.state == StorePurchaseState.PENDING }.map { it.productId }.toSet(),
            )
        }
        for (p in purchases) process(p, fromBuy = p.productId in awaiting, counting = counting)
    }

    private suspend fun storeFailed(error: StoreError, stage: IapStage, fromBuy: Boolean? = null) {
        val report = errors.report(error, stage)
        val productId = error.productId ?: awaiting.singleOrNull() ?: ""
        val buy = fromBuy ?: (productId in awaiting)
        awaiting -= productId
        mutableState.update { it.copy(busy = it.busy - productId) }
        // RN: a terminal payment error of the paid account drops its saved details (only for that product). A transient
        // one (network, store service) keeps them: the purchase may still arrive paid, and needs them then.
        if (productId == PurchaseSkus.ACCOUNT && !error.isTransient) Intents.removeUnbound(env.prefs, PurchaseSkus.ACCOUNT)
        mutableOutcomes.emit(PurchaseOutcome.StoreFailed(productId, buy, error, report))
    }

    private suspend fun emit(outcome: PurchaseOutcome) {
        awaiting -= outcome.productId
        mutableState.update { it.copy(busy = it.busy - outcome.productId) }
        mutableOutcomes.emit(outcome)
    }

    /** RN `_consumePurchase`, with the rules in the class comment. Main thread. */
    internal suspend fun process(p: StorePurchase, fromBuy: Boolean, counting: Boolean = false) {
        if (p.productId !in PurchaseSkus.ALL || p.token.isEmpty()) return
        val key = orderKey(p)
        if (!inFlight.add(key)) return
        try {
            // a real transaction of the product replaces its synthetic pending entry (an approved Ask to Buy)
            if (!p.isSyntheticPending) held = held.filterValues { !(it.isSyntheticPending && it.productId == p.productId) }
            if (p.state != StorePurchaseState.REVOKED) held = held + (key to p)
            when (p.state) {
                StorePurchaseState.PENDING -> {
                    if (p.productId == PurchaseSkus.PRO) creditedUser(p)?.let { env.proPurchases.update(it, ProProgress.PENDING) }
                    mutableState.update { it.copy(pending = it.pending + p.productId) }
                    emit(PurchaseOutcome.Pending(p.productId, fromBuy))
                    return
                }
                StorePurchaseState.REVOKED -> {
                    // refunded before it was finished: nothing to deliver (Ecency rejects revoked transactions too)
                    env.crashReporter.breadcrumb("iap", "revoked purchase finished", mapOf("productId" to p.productId))
                    held = held - key
                    Intents.removeKey(env.prefs, key)
                    finish(p)
                    return
                }
                StorePurchaseState.PURCHASED -> Unit
            }
            mutableState.update { it.copy(pending = it.pending - p.productId, unfinished = it.unfinished + p.productId, busy = it.busy + p.productId) }
            val hash = key
            // Ecency took this purchase before (its finish failed or the store delivered it again): finish it, never
            // post it again, and never touch a saved target (it is not this purchase's: that one went with its order).
            // On the App Store the key is the transaction id, so a re-signed receipt of the same transaction (a new
            // jwsRepresentation) is not a new order: Ecency dedupes on the receipt's hash only.
            if (isAccepted(p)) {
                delivered(p, fromBuy)
                return
            }
            // from now on this purchase keeps the target it was bought for, whatever a later buy saves; a purchase that
            // carries an account token only takes a saved target of that account (an earlier buy's is not its own)
            val token = p.accountToken.lowercase()
            Intents.bind(env.prefs, p.productId, key) { i ->
                p.productId == PurchaseSkus.ACCOUNT || token.isEmpty() || i.target.user.isEmpty() || accountToken(i.target.user) == token
            }
            val order = order(p) ?: run {
                emit(PurchaseOutcome.Failed(p.productId, fromBuy, PurchaseCopy.ACCOUNT_META_MISSING))
                return
            }
            when (val result = post(order)) {
                OrderResult.Accepted -> {
                    Marks.accept(env.prefs, hash)
                    delivered(p, fromBuy)
                }
                OrderResult.Duplicate -> {
                    if (counting) Marks.conflict(env.prefs, hash, env.nowMillis())
                    // a 409 can be a failed insert that repeats for a whole outage: no timing makes it a stored order
                    val confirmed = Marks.accepted(env.prefs, hash) || (env.store.platform == PLAY && p.acknowledged)
                    if (confirmed) {
                        if (!Marks.accepted(env.prefs, hash)) {
                            env.crashReporter.capture(
                                CrashReport(
                                    "IapOrderDuplicate", "purchase-order 409 taken as recorded", isMessage = true, level = "warning",
                                    tags = mapOf("iap.stage" to IapStage.ORDER.tag, "iap.product" to p.productId, "iap.platform" to env.store.platform),
                                    fingerprint = listOf("iap", "order-409"),
                                ),
                            )
                        }
                        delivered(p, fromBuy)
                    } else {
                        stillProcessing(p, fromBuy, hash)
                    }
                }
                OrderResult.Unknown -> stillProcessing(p, fromBuy, hash)
                is OrderResult.Failed -> {
                    if (p.productId == PurchaseSkus.PRO) creditedUser(p)?.let { env.proPurchases.update(it, null) }
                    errors.reportThrown(result.error, IapStage.ORDER, p.productId)
                    emit(PurchaseOutcome.Failed(p.productId, fromBuy, result.error.message ?: PurchaseCopy.CONNECTION_ISSUES))
                }
            }
        } finally {
            inFlight -= key
        }
    }

    private suspend fun delivered(p: StorePurchase, fromBuy: Boolean) {
        // a Pro purchase: the roster is read again past its cache until the account is on it (README "Ecency Pro")
        if (p.productId == PurchaseSkus.PRO) creditedUser(p)?.let {
            env.proPurchases.update(it, ProProgress.DELIVERED)
            env.proMembers.activate(it)
        }
        Intents.removeKey(env.prefs, orderKey(p))
        finish(p)
        held = held - orderKey(p)
        mutableState.update { it.copy(unfinished = it.unfinished - p.productId) }
        emit(PurchaseOutcome.Delivered(p.productId, fromBuy))
    }

    private suspend fun stillProcessing(p: StorePurchase, fromBuy: Boolean, hash: String) {
        if (p.productId == PurchaseSkus.PRO) creditedUser(p)?.let { env.proPurchases.update(it, ProProgress.PROCESSING) }
        if (stuck(p)) mutableState.update { it.copy(unfinished = it.unfinished - p.productId) }
        emit(PurchaseOutcome.Processing(p.productId, fromBuy))
        val n = rechecks[hash] ?: 0
        if (n >= MAX_RECHECKS) return
        rechecks[hash] = n + 1
        env.background.launch {
            delay(RECHECK_MILLIS)
            recover()
        }
    }

    /** RN finishTransaction after the order: never fatal. */
    private suspend fun finish(p: StorePurchase) {
        val (_, error) = callStore<Unit> { done -> env.store.finish(p) { e -> done(if (e == null) Unit else null, e) } }
        if (error != null) errors.report(error, IapStage.FINISH, p.productId)
    }

    /** The order for [p], or null when it cannot be placed now (a paid account without its details, nobody to credit). */
    private fun order(p: StorePurchase): PurchaseOrder? {
        val intent = Intents.find(env.prefs, p.productId, orderKey(p))
        val signedIn = env.session.current.username
        if (p.productId == PurchaseSkus.ACCOUNT) {
            val meta = intent?.meta?.takeIf { it.username.isNotEmpty() && it.email.isNotEmpty() } ?: return null
            // RN's last guard: a name the chain rejects is never ordered (left to the store's refund)
            if (UsernameRules.error(meta.username.lowercase()) != null) return null
            return PurchaseOrder(env.store.platform, p.productId, p.token, signedIn ?: "ecency", meta)
        }
        val user = creditedUser(p) ?: return null
        return PurchaseOrder(env.store.platform, p.productId, p.token, user)
    }

    /**
     * The account a purchase is credited to: the target saved with this very purchase on this device; else the
     * account its token ([StorePurchase.accountToken]) names among the accounts on this device (an interrupted
     * purchase never goes to whoever is signed in when it names another of them); else the newest saved target of the
     * product (only one of the token's account when there is a token); else the signed-in account. A token that
     * names none of the accounts here, without a saved target of its account, goes to the signed-in account and
     * leaves a breadcrumb.
     */
    internal fun creditedUser(p: StorePurchase): String? {
        val key = orderKey(p)
        Intents.all(env.prefs).lastOrNull { it.key == key }?.target?.user?.takeIf { it.isNotEmpty() }?.let { return it }
        val token = p.accountToken.lowercase()
        val session = env.session.current
        if (token.isNotEmpty()) {
            (session.accounts.map { it.username } + listOfNotNull(session.username)).firstOrNull { accountToken(it) == token }?.let { return it }
        }
        val saved = Intents.find(env.prefs, p.productId)?.target?.user?.takeIf { it.isNotEmpty() }
        if (token.isNotEmpty()) {
            if (saved != null && accountToken(saved) == token) return saved
            env.crashReporter.breadcrumb("iap", "purchase token names no account on this device", mapOf("productId" to p.productId))
            return session.username
        }
        return saved ?: session.username
    }

    private sealed interface OrderResult {
        data object Accepted : OrderResult
        data object Duplicate : OrderResult
        data object Unknown : OrderResult
        data class Failed(val error: Exception) : OrderResult
    }

    /** RN `_purchaseOrderWithRetry`: 3 tries (1 s, then 2 s apart); 409 and a timeout stop at once. */
    private suspend fun post(order: PurchaseOrder): OrderResult {
        var last: Exception = EcencyHttpException(0)
        for (attempt in 0 until ORDER_ATTEMPTS) {
            try {
                api.purchaseOrder(order, env.session.current.username)
                return OrderResult.Accepted
            } catch (e: CancellationException) {
                throw e
            } catch (e: EcencyTimeoutException) {
                return OrderResult.Unknown
            } catch (e: EcencyHttpException) {
                if (e.status == 409) return OrderResult.Duplicate
                last = e
            } catch (e: Exception) {
                last = e
            }
            if (attempt < ORDER_ATTEMPTS - 1) delay(1_000L shl attempt)
        }
        return OrderResult.Failed(last)
    }

    /** A store call as a suspension on the main thread; null and an error when the store never answered. */
    private suspend fun <T> callStore(timeout: Boolean = true, call: ((T?, StoreError?) -> Unit) -> Unit): Pair<T?, StoreError?> {
        val wait: suspend () -> Pair<T?, StoreError?> = {
            suspendCancellableCoroutine { c: CancellableContinuation<Pair<T?, StoreError?>> ->
                var resumed = false
                try {
                    call { value, error ->
                        env.background.launch {
                            if (!resumed && c.isActive) {
                                resumed = true
                                c.resume(value to error)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!resumed) {
                        resumed = true
                        c.resume(null to StoreError(StoreError.UNKNOWN, e.message.orEmpty()))
                    }
                }
            }
        }
        return withContext(env.dispatchers.main) {
            if (!timeout) wait() else withTimeoutOrNull(STORE_TIMEOUT_MILLIS) { wait() } ?: (null to StoreError(StoreError.SERVICE_ERROR, L.native.purchases.theStoreDidNot))
        }
    }

    /** Saved order targets, by product (RN pendingAccountPurchaseMeta for every product). */
    /**
     * Saved order targets. A buy saves an unbound target for its product before the sheet opens; the first time a
     * paid purchase of that product is processed the target is bound to it ([bind], by [orderKey]), so a later buy of
     * the same product (a new target) never changes whom an earlier purchase goes to.
     */
    internal object Intents {
        const val KEY = "purchases.intents"
        private const val MAX = 16
        const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000

        data class Intent(val productId: String, val target: PurchaseTarget, val atMillis: Long, val key: String? = null) {
            val meta: AccountOrderMeta? get() = target.meta
        }

        fun all(prefs: Prefs): List<Intent> {
            val arr = prefs.getString(KEY)?.let { SafeJson.parseOrNull(it, 4) }.arr() ?: return emptyList()
            return arr.mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                val p = o.str("p")?.takeIf { it in PurchaseSkus.ALL } ?: return@mapNotNull null
                val meta = o["m"].obj()?.let { m -> AccountOrderMeta(m.str("u").orEmpty(), m.str("e").orEmpty(), m.str("r").orEmpty()) }
                Intent(p, PurchaseTarget(o.str("u").orEmpty(), meta), o.long("t") ?: 0, o.str("h"))
            }
        }

        /** The target bound to the purchase [key], else the newest unbound one of [productId]. */
        fun find(prefs: Prefs, productId: String, key: String? = null): Intent? {
            val all = all(prefs)
            return key?.let { k -> all.lastOrNull { it.key == k } } ?: all.lastOrNull { it.productId == productId && it.key == null }
        }

        /**
         * Binds the newest unbound target of [productId] that [accepts] to the purchase [key], unless one is bound to
         * it already.
         */
        fun bind(prefs: Prefs, productId: String, key: String, accepts: (Intent) -> Boolean = { true }) {
            val all = all(prefs)
            if (all.any { it.key == key }) return
            val i = all.indexOfLast { it.productId == productId && it.key == null && accepts(it) }
            if (i < 0) return
            write(prefs, all.toMutableList().also { it[i] = it[i].copy(key = key) })
        }

        /** A new buy's target: replaces the product's unbound one, never a target bound to a purchase. */
        fun save(prefs: Prefs, productId: String, target: PurchaseTarget, now: Long) {
            val kept = all(prefs).filter { (it.productId != productId || it.key != null) && (it.key != null || now - it.atMillis < MAX_AGE_MILLIS) }
            write(prefs, (kept + Intent(productId, target, now)).takeLast(MAX))
        }

        /** The target bound to [key] (its order went through). */
        fun removeKey(prefs: Prefs, key: String) {
            val all = all(prefs)
            if (all.any { it.key == key }) write(prefs, all.filter { it.key != key })
        }

        /** The product's unbound target (a payment that failed for good). */
        fun removeUnbound(prefs: Prefs, productId: String) {
            val all = all(prefs)
            if (all.any { it.productId == productId && it.key == null }) write(prefs, all.filterNot { it.productId == productId && it.key == null })
        }

        /** Test helper: everything of [productId]. */
        fun remove(prefs: Prefs, productId: String) {
            val all = all(prefs)
            if (all.any { it.productId == productId }) write(prefs, all.filter { it.productId != productId })
        }

        private fun write(prefs: Prefs, list: List<Intent>) {
            if (list.isEmpty()) return prefs.putString(KEY, null)
            prefs.putString(
                KEY,
                buildJsonArray {
                    list.forEach { i ->
                        add(
                            buildJsonObject {
                                put("p", i.productId)
                                put("u", i.target.user)
                                i.meta?.let { m -> put("m", buildJsonObject { put("u", m.username); put("e", m.email); put("r", m.referral) }) }
                                put("t", i.atMillis)
                                i.key?.let { put("h", it) }
                            },
                        )
                    }
                }.toString(),
            )
        }
    }

    /**
     * Per purchase key (hashes only, never a receipt): accepted by Ecency on this device, how many separate order
     * attempts Ecency answered 409, and whether the user was told the purchase is stuck.
     */
    internal object Marks {
        const val ACCEPTED = "purchases.accepted"
        const val CONFLICTS = "purchases.conflicts"
        const val CONFLICT_AT = "purchases.conflictAt"
        const val STUCK_TOLD = "purchases.stuckTold"
        private const val MAX = 64

        /**
         * A recovery's 409 for [hash]: counted when none was counted in the last [CONFLICT_GAP_MILLIS] (an outage of
         * Ecency's database answers 409 to every attempt for its whole length). Returns the count.
         */
        fun conflict(prefs: Prefs, hash: String, now: Long): Long {
            val all = list(prefs, CONFLICTS)
            val at = list(prefs, CONFLICT_AT)
            val n = all.firstOrNull { it.first == hash }?.second ?: 0L
            val last = at.firstOrNull { it.first == hash }?.second
            if (last != null && now - last < CONFLICT_GAP_MILLIS) return n
            write(prefs, CONFLICTS, (all.filter { it.first != hash } + (hash to n + 1)).takeLast(MAX))
            write(prefs, CONFLICT_AT, (at.filter { it.first != hash } + (hash to now)).takeLast(MAX))
            return n + 1
        }

        fun conflicts(prefs: Prefs, hash: String): Long = list(prefs, CONFLICTS).firstOrNull { it.first == hash }?.second ?: 0L

        /** True the first time only. */
        fun tellStuck(prefs: Prefs, hash: String): Boolean {
            val all = list(prefs, STUCK_TOLD)
            if (all.any { it.first == hash }) return false
            write(prefs, STUCK_TOLD, (all + (hash to 1L)).takeLast(MAX))
            return true
        }

        fun accepted(prefs: Prefs, hash: String): Boolean = list(prefs, ACCEPTED).any { it.first == hash }

        fun accept(prefs: Prefs, hash: String) {
            if (!accepted(prefs, hash)) write(prefs, ACCEPTED, (list(prefs, ACCEPTED) + (hash to 0L)).takeLast(MAX))
        }

        private fun list(prefs: Prefs, key: String): List<Pair<String, Long>> {
            val o = prefs.getString(key)?.let { SafeJson.parseOrNull(it, 3) }.obj() ?: return emptyList()
            return o.entries.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.toLongOrNull()?.let { k to it } }
        }

        private fun write(prefs: Prefs, key: String, entries: List<Pair<String, Long>>) =
            prefs.putString(key, JsonObject(entries.associate { it.first to JsonPrimitive(it.second) }).toString())
    }

    companion object {
        const val PLAY = "play_store"
        const val APP_STORE = "app_store"

        /** RN `PURCHASE_ORDER_MAX_ATTEMPTS`. */
        const val ORDER_ATTEMPTS = 3

        /** An order without a known outcome is asked again after this long, at most [MAX_RECHECKS] times a run. */
        const val RECHECK_MILLIS = 30_000L
        const val MAX_RECHECKS = 3

        /** An App Store purchase answered 409 this many separate times, or this old unaccepted, stops blocking ([stuck]). */
        const val STUCK_CONFLICTS = 3L
        const val STUCK_AGE_MILLIS = 24L * 60 * 60 * 1000

        /** Counted 409s of one purchase are at least this far apart. */
        const val CONFLICT_GAP_MILLIS = 6L * 60 * 60 * 1000

        /** How long a synthetic pending purchase (Ask to Buy) blocks a second purchase without the store reporting it. */
        const val SYNTHETIC_PENDING_MILLIS = 48L * 60 * 60 * 1000

        /** Recovery on foreground at most this often. */
        const val FOREGROUND_RECOVERY_MILLIS = 30_000L

        /** A store call that never answers gives up after this long (the purchase sheet itself has no bound). */
        const val STORE_TIMEOUT_MILLIS = 60_000L

        /**
         * The account token of [username] for the store (StoreKit `appAccountToken`, Play `obfuscatedAccountId`): a
         * name-based UUID (RFC 9562 version 8) of SHA-256 over a fixed prefix and the lowercased name, lowercase.
         * No personal data leaves in it; the app maps it back by hashing the accounts on the device.
         */
        fun accountToken(username: String): String {
            val b = org.kotlincrypto.hash.sha2.SHA256().digest("ecency-iap-account:${username.lowercase()}".encodeToByteArray()).copyOf(16)
            b[6] = ((b[6].toInt() and 0x0f) or 0x80).toByte()
            b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
            val hex = b.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
        }

        /** A text's identity in the preferences: SHA-256, the first 32 hex characters (never the receipt itself). */
        fun receiptHash(token: String): String =
            org.kotlincrypto.hash.sha2.SHA256().digest(token.encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }.take(32)
    }
}
