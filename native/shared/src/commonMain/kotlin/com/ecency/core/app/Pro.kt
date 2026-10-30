package com.ecency.core.app

import com.ecency.core.l10n.L
import kotlinx.coroutines.launch

/** Ecency Pro copy (web `pro.*` wording where it fits, issue #3555). No text points to buying Pro anywhere else. */
object ProCopy {
    val TITLE: String get() = L.native.pro.title
    val SUBTITLE: String get() = L.native.pro.subtitle
    val MEMBER: String get() = L.native.pro.member
    val CARD_PLAIN: String get() = L.native.pro.cardDescriptionPlain
    fun card(price: String) = L.native.pro.cardDescription(price = price)
    val BENEFITS: List<String> get() = listOf(L.native.pro.benefitBadge, L.native.pro.benefitBlog, L.native.pro.benefitSupport, L.native.pro.benefitEarly)
    fun buy(price: String) = L.native.pro.buy(price = price)
    fun extend(price: String) = L.native.pro.extend(price = price)
    val EXTEND_NOTE: String get() = L.native.pro.extendNote
    val UNAVAILABLE: String get() = L.native.pro.unavailable
    val ACTIVATING: String get() = L.native.pro.activating
    val SUCCESS_TITLE: String get() = L.native.pro.successTitle
    val SUCCESS_BODY: String get() = L.native.pro.successBody
    val EXTENDING: String get() = L.native.pro.extending
    val OPEN: String get() = L.native.pro.openA11y
    val CLOSE: String get() = UiCopy.CLOSE
}

data class ProState(
    /** The signed-in account: the only one a purchase here is for (no gifting). */
    val account: String? = null,
    /** On the Pro roster (the badge's list, [ProMembersModel]). */
    val member: Boolean = false,
    /** The store's localized price of [PurchaseSkus.PRO]; null until known or when the store does not sell it. */
    val price: String? = null,
    val loadingPrice: Boolean = false,
    /** The store answered without the product. */
    val unavailable: Boolean = false,
    /** The Pro sheet is open. */
    val open: Boolean = false,
    /** The store sheet or the order is under way ([PurchaseServiceState.busy]). */
    val buying: Boolean = false,
    /** Ecency accepted this account's Pro purchase ([ProPurchases]; also one that settled or was recovered). */
    val purchased: Boolean = false,
    /** The account was a member already when it bought: the purchase extends the membership. */
    val extending: Boolean = false,
    /** A pending payment or an order still being processed. */
    val notice: String? = null,
    val alert: StoreAlert? = null,
) {
    /** Paid and accepted, not on the roster yet. */
    val activating: Boolean get() = purchased && !member
    val succeeded: Boolean get() = purchased && member
    val cardText: String get() = if (member) ProCopy.MEMBER else price?.let { ProCopy.card(it) } ?: ProCopy.CARD_PLAIN
    val buyLabel: String? get() = price?.let { if (member) ProCopy.extend(it) else ProCopy.buy(it) }
    val showBuy: Boolean get() = account != null && price != null && !purchased
    val canBuy: Boolean get() = showBuy && !buying
    val successTitle: String get() = if (extending) ProCopy.MEMBER else ProCopy.SUCCESS_TITLE
    val successBody: String get() = if (extending) ProCopy.EXTENDING else ProCopy.SUCCESS_BODY
}

/** Where an account's latest Pro purchase stands, app-wide ([ProPurchases]). */
enum class ProProgress { PENDING, PROCESSING, DELIVERED }

/** An account's latest Pro purchase: [progress] null while the store sheet is open or after a failure. */
data class ProPurchase(val progress: ProProgress?, val extending: Boolean)

/**
 * The latest Pro purchase per account, app-wide (one per app, [AppEnvironment.proPurchases]): [PurchaseService]
 * records what happened to each `1999pro` purchase for the account it is credited to, so a payment that settles
 * later, a recovered purchase, or an outcome that landed while Perks was closed shows when the sheet opens again.
 */
class ProPurchases internal constructor(
    dispatchers: AppDispatchers,
    /** Whether an account is on the roster now: an extension, for a purchase this run never saw start (a recovery). */
    private val isPro: (String) -> Boolean,
) : StateModel<Map<String, ProPurchase>>(emptyMap(), dispatchers) {
    /** A buy for [user] starts; [extending]: the account is a member already. A pending or processing one stays so. */
    internal fun started(user: String, extending: Boolean) = setState { m ->
        val key = user.lowercase()
        m + (key to ProPurchase(m[key]?.progress?.takeIf { it != ProProgress.DELIVERED }, extending))
    }

    internal fun update(user: String, progress: ProProgress?) = setState { m ->
        val key = user.lowercase()
        m + (key to ProPurchase(progress, m[key]?.extending ?: isPro(key)))
    }

    /** A pending entry the store no longer holds: of [user], or of every account (null). */
    internal fun dropPending(user: String?) = setState { m ->
        m.mapValues { (k, v) -> if (v.progress == ProProgress.PENDING && (user == null || k == user.lowercase())) v.copy(progress = null) else v }
    }

    /** The account saw its finished purchase. */
    internal fun clear(user: String) = setState { it - user.lowercase() }

    fun of(user: String?): ProPurchase? = user?.let { current[it.lowercase()] }
}

/**
 * The Pro card on Perks and its sheet (issue #3555): membership from the Pro roster, the store's price of
 * [PurchaseSkus.PRO], and the purchase through [PurchaseService] (the money rules, recovery and the order are the
 * same as for Points) for the signed-in account. What happened to the account's purchase comes from
 * [ProPurchases]; once Ecency accepted the order the roster is read again ([ProMembersModel.activate]) and until
 * the account shows on it the sheet says the membership is activating. Alerts belong to this screen's own buy.
 */
class ProModel internal constructor(private val env: AppEnvironment) : StateModel<ProState>(ProState(), env.dispatchers) {
    private var started = false

    /** The account this screen's buy is for, from the tap to its outcome (alerts only). */
    private var buyingFor: String? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            env.session.awaitLoaded()
            env.session.state.collect { s ->
                if (s.username != current.account) {
                    buyingFor = null
                    setState { ProState(account = s.username, price = it.price, unavailable = it.unavailable, open = it.open) }
                    sync()
                }
            }
        }
        scope.launch { env.proMembers.state.collect { sync() } }
        scope.launch { env.purchases.state.collect { sync() } }
        scope.launch { env.proPurchases.state.collect { sync() } }
        scope.launch { env.purchases.outcomes.collect { if (it.productId == PurchaseSkus.PRO) onOutcome(it) } }
        env.proMembers.ensureLoaded()
        loadPrice()
    }

    /** Everything that is not this screen's own: membership, a buy under way (any account's), the purchase's progress. */
    private fun sync() = setState { s ->
        val p = env.proPurchases.of(s.account)
        s.copy(
            member = s.account != null && env.proMembers.isPro(s.account),
            buying = PurchaseSkus.PRO in env.purchases.current.busy,
            purchased = p?.progress == ProProgress.DELIVERED,
            extending = p?.extending == true,
            notice = when (p?.progress) {
                ProProgress.PENDING -> PurchaseCopy.PENDING
                ProProgress.PROCESSING -> PurchaseCopy.PROCESSING
                else -> null
            },
        )
    }

    /** RN `getItems` for the one product. */
    fun loadPrice() {
        if (current.loadingPrice) return
        setState { it.copy(loadingPrice = true) }
        scope.launch {
            val (products, _) = env.purchases.products(listOf(PurchaseSkus.PRO))
            val product = products.firstOrNull { it.productId == PurchaseSkus.PRO }
            setState { it.copy(loadingPrice = false, price = product?.localizedPrice, unavailable = product == null) }
        }
    }

    fun open() {
        setState { it.copy(open = true) }
        if (current.price == null) loadPrice()
    }

    /** Closing keeps a purchase under way (its outcome is kept app-wide); a finished one starts over next time. */
    fun dismiss() {
        val s = current
        if (s.succeeded) s.account?.let { env.proPurchases.clear(it) }
        setState { it.copy(open = false) }
        sync()
    }

    fun dismissAlert() = setState { it.copy(alert = null) }

    /**
     * Buys (or, for a member, extends) Pro for the signed-in account. Refused while any Pro buy is under way (another
     * account's included: its outcome is not this account's).
     */
    fun buy() {
        val user = env.session.current.username
            ?: return setState { it.copy(alert = StoreAlert(PurchaseCopy.NOT_LOGGED_IN, PurchaseCopy.NOT_LOGGED_IN_DESC)) }
        val s = current
        if (!s.canBuy || s.account != user || PurchaseSkus.PRO in env.purchases.current.busy) return
        buyingFor = user
        env.proPurchases.started(user, extending = s.member)
        env.purchases.buy(PurchaseSkus.PRO, PurchaseTarget(user = user))
    }

    private fun onOutcome(o: PurchaseOutcome) {
        if (!o.fromBuy) return
        // an outcome of another account's buy (the account changed meanwhile) is not this screen's
        if (buyingFor == null || buyingFor != current.account) return
        when (o) {
            is PurchaseOutcome.Delivered, is PurchaseOutcome.Pending, is PurchaseOutcome.Processing -> buyingFor = null
            // another account's earlier purchase goes first: told here, its delivery is not this buy's
            is PurchaseOutcome.Earlier -> {
                if (!o.pending) buyingFor = null
                setState {
                    it.copy(alert = StoreAlert(PurchaseCopy.WARNING, if (o.pending) PurchaseCopy.earlierPending(o.target) else PurchaseCopy.earlierPurchase(o.target)))
                }
            }
            is PurchaseOutcome.Failed -> setState { it.copy(alert = StoreAlert(PurchaseCopy.WARNING, o.message)) }
            // a cancel is told nothing (IapReport); the rest as the other purchase screens
            is PurchaseOutcome.StoreFailed -> when {
                o.report != IapReport.REPORTED -> Unit
                o.error.isBillingUnavailable && env.store.platform == PurchaseService.PLAY ->
                    setState { it.copy(alert = StoreAlert(PurchaseCopy.WARNING, PurchaseCopy.GOOGLE_PLAY_VERSION)) }
                else -> setState { it.copy(alert = StoreAlert(PurchaseCopy.WARNING, o.error.message)) }
            }
        }
    }
}
