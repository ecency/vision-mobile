package com.ecency.core.app

import com.ecency.core.l10n.L

/**
 * The device's app store, as RN's `src/providers/iap` sees it through expo-iap: Google Play Billing on Android,
 * StoreKit 2 on iOS. Each platform implements this over its own library and sets it as
 * [AppEnvironment.store] before [PurchaseService.start]; [StorePlatform.None] (tests, builds without a store)
 * sells nothing.
 *
 * Callbacks may arrive on any thread; [PurchaseService] moves them to the main thread. Every product sold here is a
 * consumable (RN `type: 'in-app'`, `finishTransaction({isConsumable: true})`); on the App Store [PurchaseSkus.PRO] may
 * also be a Non-Renewing Subscription, which is finished the same way.
 */
interface StorePlatform {
    /** The purchase-order `platform`: `play_store` or `app_store` (RN `Platform.OS`). */
    val platform: String

    /** Connects to the store (RN `initConnection`). A failure is a [StoreError] (RN shows "connection issues"). */
    fun connect(done: (StoreError?) -> Unit)

    /** RN `fetchProducts({skus, type: 'in-app'})`: the products the store knows; a missing one is left out. */
    fun products(ids: List<String>, done: (List<StoreProduct>?, StoreError?) -> Unit)

    /**
     * Every purchase the store still holds for this app and has not finished: Play's owned, unconsumed in-app purchases
     * (pending ones included), StoreKit's `Transaction.unfinished` (RN `getAvailablePurchases`, which on iOS missed
     * unfinished consumables).
     */
    fun purchases(done: (List<StorePurchase>?, StoreError?) -> Unit)

    /**
     * Starts the store's purchase sheet for [productId] (RN `requestPurchase`). The outcome arrives through
     * [StoreListener]; [done] only says whether the sheet could be started (an error here is reported once, RN's
     * `hasStoreCode` dedupe, see [IapErrors]). [accountToken] ([PurchaseService.accountToken] of the account the
     * purchase is for) goes into the transaction: StoreKit `appAccountToken`, Play `obfuscatedAccountId`, and comes
     * back as [StorePurchase.accountToken].
     */
    fun purchase(productId: String, accountToken: String, done: (StoreError?) -> Unit)

    /** RN `finishTransaction(isConsumable: true)`: Play consume, StoreKit `finish()`. Only after the order was accepted. */
    fun finish(purchase: StorePurchase, done: (StoreError?) -> Unit)

    /** Purchases and failures that arrive by themselves (RN `purchaseUpdatedListener`, `purchaseErrorListener`). */
    fun setListener(listener: StoreListener?)

    object None : StorePlatform {
        override val platform: String get() = "none"
        override fun connect(done: (StoreError?) -> Unit) = done(StoreError(StoreError.BILLING_UNAVAILABLE, L.native.store.inAppPurchasesAre))
        override fun products(ids: List<String>, done: (List<StoreProduct>?, StoreError?) -> Unit) = done(emptyList(), null)
        override fun purchases(done: (List<StorePurchase>?, StoreError?) -> Unit) = done(emptyList(), null)
        override fun purchase(productId: String, accountToken: String, done: (StoreError?) -> Unit) =
            done(StoreError(StoreError.BILLING_UNAVAILABLE, L.native.store.inAppPurchasesAre, productId))
        override fun finish(purchase: StorePurchase, done: (StoreError?) -> Unit) = done(null)
        override fun setListener(listener: StoreListener?) {}
    }
}

interface StoreListener {
    /** Purchases the store delivered (a completed sheet, an Ask to Buy approval, a payment that settled). */
    fun onPurchases(purchases: List<StorePurchase>)

    /** A purchase that failed or was cancelled after the sheet opened (RN `purchaseErrorListener`). */
    fun onError(error: StoreError)
}

/** The fields RN's screens render (providers/iap `IapProduct`). [price] is the plain number, [localizedPrice] the store's text. */
data class StoreProduct(
    val productId: String,
    val title: String,
    val description: String,
    val price: String,
    val currency: String,
    val localizedPrice: String,
)

enum class StorePurchaseState {
    /** Paid: post it to Ecency, then finish it. */
    PURCHASED,

    /** Not paid yet (cash, a bank transfer, Ask to Buy): never posted, never shown as paid. */
    PENDING,

    /** Refunded or revoked by the store before it was finished: nothing to deliver. */
    REVOKED,
}

/**
 * One purchase. [token] is the purchase-order `receipt`: Play's purchase token, or StoreKit 2's signed transaction
 * (`jwsRepresentation`). [acknowledged] is Play's flag (true once the purchase was acknowledged or consumed, by the
 * app or by Ecency's server after delivery); StoreKit has none and reports false. [ref] is the platform's own handle
 * for [StorePlatform.finish] (the StoreKit transaction id). The token is never printed.
 */
data class StorePurchase(
    val productId: String,
    val token: String,
    val state: StorePurchaseState,
    val acknowledged: Boolean = false,
    val ref: String = "",
    /** The store's purchase date (Play purchaseTime, StoreKit purchaseDate); 0 when unknown. */
    val purchasedAtMillis: Long = 0,
    /** The account token the purchase was started with (lowercase UUID), empty when the store carries none. */
    val accountToken: String = "",
) {
    /**
     * A pending purchase the store answered without a transaction (StoreKit's `.pending`, Ask to Buy): its token is
     * made up ([SYNTHETIC_PENDING_PREFIX]) and the store's lists never show it.
     */
    val isSyntheticPending: Boolean get() = state == StorePurchaseState.PENDING && token.startsWith(SYNTHETIC_PENDING_PREFIX)

    companion object {
        const val SYNTHETIC_PENDING_PREFIX = "pending:"
    }

    override fun toString(): String = "StorePurchase(productId=$productId, state=$state, acknowledged=$acknowledged, token=<redacted>)"
}

/**
 * A store failure with OpenIAP's string [code] (RN `PurchaseError.code`), the store's own [responseCode] when it has
 * one (Play's BillingResponseCode) and its [debugMessage]. [productId] when the failure was about one.
 */
data class StoreError(
    val code: String,
    val message: String,
    val productId: String? = null,
    val responseCode: Int? = null,
    val debugMessage: String? = null,
) {
    /** RN isUserCancelledError: OpenIAP `user-cancelled` or Play USER_CANCELED. */
    val isUserCancelled: Boolean get() = code == USER_CANCELLED || responseCode == PLAY_USER_CANCELED

    /** RN isBillingUnavailableError: OpenIAP `billing-unavailable` or Play BILLING_UNAVAILABLE. */
    val isBillingUnavailable: Boolean get() = code == BILLING_UNAVAILABLE || responseCode == PLAY_BILLING_UNAVAILABLE

    /** A failure that says nothing about the payment (the network, the store's service): the purchase may still arrive. */
    val isTransient: Boolean get() = code == NETWORK_ERROR || code == SERVICE_ERROR

    companion object {
        const val USER_CANCELLED = "user-cancelled"
        const val BILLING_UNAVAILABLE = "billing-unavailable"
        const val ITEM_UNAVAILABLE = "item-unavailable"
        const val ALREADY_OWNED = "already-owned"
        const val NETWORK_ERROR = "network-error"
        const val SERVICE_ERROR = "service-error"
        const val DEVELOPER_ERROR = "developer-error"
        const val NOT_PREPARED = "not-prepared"
        const val VERIFICATION_FAILED = "transaction-validation-failed"
        const val UNKNOWN = "unknown"

        /** Play BillingResponseCode values RN reads as `responseCode`. */
        const val PLAY_USER_CANCELED = 1
        const val PLAY_BILLING_UNAVAILABLE = 3

        /**
         * Play BillingResponseCode to OpenIAP's code (openiap-google's mapping for the codes Billing 9 returns).
         * Unknown values are [UNKNOWN].
         */
        fun playCode(responseCode: Int): String = when (responseCode) {
            1 -> USER_CANCELLED
            3 -> BILLING_UNAVAILABLE
            4 -> ITEM_UNAVAILABLE
            5 -> DEVELOPER_ERROR
            7 -> ALREADY_OWNED
            2, 12 -> NETWORK_ERROR
            -1, -2, 6 -> SERVICE_ERROR
            else -> UNKNOWN
        }
    }
}
