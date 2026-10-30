package com.ecency.app.iap

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.ecency.core.app.PurchaseService
import com.ecency.core.app.StoreError
import com.ecency.core.app.StoreListener
import com.ecency.core.app.StorePlatform
import com.ecency.core.app.StoreProduct
import com.ecency.core.app.StorePurchase
import com.ecency.core.app.StorePurchaseState
import java.util.concurrent.ConcurrentHashMap
import com.ecency.core.app.WalletUiCopy

/**
 * Google Play Billing 9 for the shared [PurchaseService] (RN expo-iap / openiap-google): one-time in-app products,
 * pending purchases enabled for them (Play requires it; the shared rules never post a pending one), automatic service
 * reconnection. Every callback is handed to the shared code as is; it moves to the main thread itself.
 *
 * [activity] is the activity the purchase sheet opens over (the one in front, null while none is).
 */
class PlayStore(context: Context, private val activity: () -> Activity?) : StorePlatform, com.android.billingclient.api.PurchasesUpdatedListener {
    override val platform: String get() = PurchaseService.PLAY

    @Volatile private var listener: StoreListener? = null

    /** The product details of the last query, by id: launching a sheet needs them. */
    private val details = ConcurrentHashMap<String, ProductDetails>()

    /** The product whose sheet is open: Play's purchase errors do not name it. */
    @Volatile private var launching: String? = null

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    override fun setListener(listener: StoreListener?) {
        this.listener = listener
    }

    override fun connect(done: (StoreError?) -> Unit) {
        if (client.isReady) return done(null)
        client.startConnection(object : BillingClientStateListener {
            private var answered = false

            override fun onBillingSetupFinished(result: BillingResult) {
                if (answered) return
                answered = true
                done(errorOf(result))
            }

            override fun onBillingServiceDisconnected() {
                // auto reconnection takes care of later calls; a setup that never finished is a failure
                if (answered) return
                answered = true
                done(StoreError(StoreError.SERVICE_ERROR, com.ecency.app.AndroidCopy.BILLING_DISCONNECTED))
            }
        })
    }

    override fun products(ids: List<String>, done: (List<StoreProduct>?, StoreError?) -> Unit) {
        if (ids.isEmpty()) return done(emptyList(), null)
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            ids.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.INAPP).build() },
        ).build()
        client.queryProductDetailsAsync(params) { result, found ->
            errorOf(result)?.let { return@queryProductDetailsAsync done(null, it) }
            val list = found.productDetailsList.orEmpty()
            list.forEach { details[it.productId] = it }
            done(list.mapNotNull(::product), null)
        }
    }

    override fun purchases(done: (List<StorePurchase>?, StoreError?) -> Unit) {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, list ->
            errorOf(result)?.let { return@queryPurchasesAsync done(null, it) }
            done(list.flatMap(::purchasesOf), null)
        }
    }

    override fun purchase(productId: String, accountToken: String, done: (StoreError?) -> Unit) {
        val product = details[productId] ?: return done(StoreError(StoreError.ITEM_UNAVAILABLE, WalletUiCopy.PRODUCT_NOT_AVAILABLE, productId))
        val host = activity() ?: return done(StoreError(StoreError.NOT_PREPARED, com.ecency.app.AndroidCopy.BILLING_NO_SCREEN, productId))
        val offer = product.oneTimePurchaseOfferDetails?.offerToken
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).apply { offer?.let(::setOfferToken) }.build()),
        ).apply { if (accountToken.isNotEmpty()) setObfuscatedAccountId(accountToken) }.build()
        launching = productId
        val result = client.launchBillingFlow(host, params)
        val error = errorOf(result)?.copy(productId = productId)
        if (error != null) launching = null
        done(error)
    }

    override fun finish(purchase: StorePurchase, done: (StoreError?) -> Unit) {
        client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(purchase.token).build()) { result, _ ->
            done(errorOf(result)?.copy(productId = purchase.productId))
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        val l = listener ?: return
        val error = errorOf(result)
        if (error == null) {
            launching = null
            l.onPurchases(purchases.orEmpty().flatMap(::purchasesOf))
        } else {
            val product = launching
            launching = null
            l.onError(error.copy(productId = error.productId ?: product))
        }
    }

    companion object {
        /** A Play answer as the shared code's error; null for OK. */
        fun errorOf(result: BillingResult): StoreError? {
            val code = result.responseCode
            if (code == BillingClient.BillingResponseCode.OK) return null
            val message = result.debugMessage.takeIf { it.isNotBlank() } ?: com.ecency.app.AndroidCopy.billingError(code)
            return StoreError(StoreError.playCode(code), message, null, code, result.debugMessage)
        }

        /** RN providers/iap `_normalizeProduct`; products without a one-time price are not for sale. */
        fun product(d: ProductDetails): StoreProduct? {
            val offer = d.oneTimePurchaseOfferDetails ?: d.oneTimePurchaseOfferDetailsList?.firstOrNull() ?: return null
            return StoreProduct(
                productId = d.productId,
                title = d.title,
                description = d.description,
                price = (offer.priceAmountMicros / 1_000_000.0).toString(),
                currency = offer.priceCurrencyCode,
                localizedPrice = offer.formattedPrice,
            )
        }

        /**
         * One [StorePurchase] per product of a Play purchase. PURCHASED is paid; PENDING and an unspecified state
         * are not (they are never posted).
         */
        fun purchasesOf(p: Purchase): List<StorePurchase> {
            val state = when (p.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> StorePurchaseState.PURCHASED
                else -> StorePurchaseState.PENDING
            }
            val token = p.accountIdentifiers?.obfuscatedAccountId.orEmpty().lowercase()
            return p.products.map { StorePurchase(it, p.purchaseToken, state, p.isAcknowledged, p.orderId.orEmpty(), p.purchaseTime, token) }
        }
    }
}
