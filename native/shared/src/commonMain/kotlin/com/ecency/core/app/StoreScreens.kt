package com.ecency.core.app

import kotlinx.coroutines.launch

/** The purchase screens of RN and their products. */
enum class StoreScreenKind(val skus: List<String>) {
    /** RN boostScreen ("Get Points"): the points packs, then the free points row. */
    POINTS(PurchaseSkus.POINTS),

    /** RN accountBoostScreen. */
    ACCOUNT_BOOST(listOf(PurchaseSkus.BOOST)),

    /** The 5 spins pack on RN's spin game. */
    SPINS(listOf(PurchaseSkus.SPINS)),
}

/** One RN ProductItemLine: [title] on the button, [deal] ribbon above it, [price] at the right. */
data class StoreRow(val productId: String, val title: String, val price: String, val deal: String?) {
    val isFreePoints: Boolean get() = productId == PurchaseSkus.FREE_POINTS
}

/** RN `Alert.alert(title, body)`. */
data class StoreAlert(val title: String, val body: String)

/** RN `_handleQrPurchase`'s confirmation sheet. */
data class StoreConfirm(val productId: String, val username: String, val title: String, val body: String)

data class StoreScreenState(
    /** The account the screen buys for (the screen's username, else the signed-in account). */
    val username: String = "",
    /** RN `isLoading` (BoostPlaceHolder). */
    val loading: Boolean = true,
    val rows: List<StoreRow> = emptyList(),
    /** RN `isProcessing`: the buttons and the header's back are disabled. */
    val processing: Boolean = false,
    val alert: StoreAlert? = null,
    val confirm: StoreConfirm? = null,
    /** The free points row was tapped: the platform opens the spin game and calls [StoreScreenModel.consumeOpenSpinGame]. */
    val openSpinGame: Boolean = false,
)

/**
 * RN boostScreen, accountBoostScreen and the spin game's pack, over [PurchaseService]. [username] is the screen's
 * parameter (a link or the QR flow buys for someone else); [productId] with it opens RN's confirmation sheet once the
 * products are in.
 */
class StoreScreenModel internal constructor(
    private val env: AppEnvironment,
    val kind: StoreScreenKind,
    private val username: String,
    private val productId: String,
) : StateModel<StoreScreenState>(StoreScreenState(), env.dispatchers) {
    private var started = false
    private var confirmShown = false
    private var hp: String = ""

    /** RN boostScreen shows the user ribbon only for a username parameter. */
    val forSomeoneElse: Boolean get() = username.isNotEmpty()

    private val target: String get() = username.ifEmpty { env.session.current.username.orEmpty() }

    fun start() {
        if (started) return
        started = true
        setState { it.copy(username = target) }
        scope.launch { env.purchases.state.collect { s -> setState { it.copy(processing = kind.skus.any { id -> id in s.busy }) } } }
        scope.launch { env.purchases.outcomes.collect { if (it.fromBuy && it.productId in kind.skus) onOutcome(it) } }
        if (kind == StoreScreenKind.ACCOUNT_BOOST) {
            scope.launch {
                val perMVests = env.wallet.current.hivePerMVests.takeIf { it > 0 }
                    ?: runCatching { env.walletApi.hivePerMVests() }.getOrNull()?.takeIf { it > 0 }
                hp = perMVests?.let { PurchaseSkus.boostHp(it) }.orEmpty()
                setState { s -> s.copy(rows = s.rows.map { r -> r.copy(title = PurchaseCopy.boostRow(hp)) }) }
            }
        }
        load()
    }

    /** RN `getItems`. */
    fun load() {
        setState { it.copy(loading = true) }
        scope.launch {
            val (products, error) = env.purchases.products(kind.skus)
            if (error != null && !error.isUserCancelled) setState { it.copy(alert = StoreAlert(PurchaseCopy.CONNECTION_ISSUES, error.message)) }
            val rows = rows(products)
            setState { it.copy(loading = false, rows = rows) }
            val product = products.firstOrNull { it.productId == productId }
            if (!confirmShown && product != null && username.isNotEmpty()) {
                confirmShown = true
                setState {
                    it.copy(
                        confirm = StoreConfirm(
                            product.productId, username, PurchaseCopy.confirmTitle(username),
                            PurchaseCopy.confirmBody(PurchaseSkus.title(product.title), username, "${product.currency} ${product.price}"),
                        ),
                    )
                }
            }
        }
    }

    private fun rows(products: List<StoreProduct>): List<StoreRow> {
        val sorted = PurchaseSkus.sortByPriceDescending(products)
        return when (kind) {
            StoreScreenKind.POINTS ->
                sorted.filterNot { it.productId.contains("spins") }.map { StoreRow(it.productId, PurchaseSkus.title(it.title), it.localizedPrice, PurchaseSkus.DEALS[it.productId]) } +
                    StoreRow(PurchaseSkus.FREE_POINTS, PurchaseSkus.title("free points"), "", null)
            StoreScreenKind.ACCOUNT_BOOST -> sorted.map { StoreRow(it.productId, PurchaseCopy.boostRow(hp), it.localizedPrice, PurchaseSkus.DEALS[it.productId]) }
            StoreScreenKind.SPINS -> sorted.filter { it.productId.contains("spins") }.map { StoreRow(it.productId, SpinCopy.GET_SPIN, it.localizedPrice, null) }
        }
    }

    /** RN `_buyItem`. */
    fun buy(productId: String) {
        if (current.processing) return
        if (productId == PurchaseSkus.FREE_POINTS) return setState { it.copy(openSpinGame = true) }
        if (env.session.current.username == null) return setState { it.copy(alert = StoreAlert(PurchaseCopy.NOT_LOGGED_IN, PurchaseCopy.NOT_LOGGED_IN_DESC)) }
        if (productId !in kind.skus) return
        env.purchases.buy(productId, PurchaseTarget(user = target))
    }

    fun confirm() {
        val c = current.confirm ?: return
        setState { it.copy(confirm = null) }
        buy(c.productId)
    }

    fun dismissConfirm() = setState { it.copy(confirm = null) }

    fun dismissAlert() = setState { it.copy(alert = null) }

    fun consumeOpenSpinGame() = setState { it.copy(openSpinGame = false) }

    /** RN's success and failure handlers of each screen, and the container's alerts. */
    private fun onOutcome(o: PurchaseOutcome) {
        val points = kind == StoreScreenKind.POINTS
        when (o) {
            is PurchaseOutcome.Delivered -> if (points) env.ui.toast(PurchaseCopy.POINTS_SUCCESS)
            is PurchaseOutcome.Stuck -> setState { it.copy(alert = StoreAlert(PurchaseCopy.WARNING, PurchaseCopy.STUCK)) }
            is PurchaseOutcome.Earlier -> setState {
                it.copy(alert = StoreAlert(PurchaseCopy.WARNING, if (o.pending) PurchaseCopy.earlierPending(o.target) else PurchaseCopy.earlierPurchase(o.target)))
            }
            is PurchaseOutcome.Pending -> setState { it.copy(alert = StoreAlert(PurchaseCopy.WARNING, PurchaseCopy.PENDING)) }
            is PurchaseOutcome.Processing -> env.ui.toast(PurchaseCopy.PROCESSING)
            is PurchaseOutcome.Failed ->
                if (points) env.ui.toast(PurchaseCopy.pointsFailed(o.message)) else setState { it.copy(alert = StoreAlert(PurchaseCopy.WARNING, o.message)) }
            is PurchaseOutcome.StoreFailed -> when {
                o.report != IapReport.REPORTED -> Unit
                o.error.isBillingUnavailable && env.store.platform == PurchaseService.PLAY ->
                    setState { it.copy(alert = StoreAlert(PurchaseCopy.WARNING, PurchaseCopy.GOOGLE_PLAY_VERSION)) }
                points -> env.ui.toast(PurchaseCopy.pointsFailed(o.error.message))
                else -> setState { it.copy(alert = StoreAlert(PurchaseCopy.WARNING, o.error.message)) }
            }
        }
    }
}
