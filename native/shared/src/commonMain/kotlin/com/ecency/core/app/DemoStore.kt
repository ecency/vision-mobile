package com.ecency.core.app

/**
 * Debug fake-data runs only: a store that sells RN's products at synthetic prices and completes a purchase at once
 * with a made-up token, so the purchase screens can be driven and captured without Google Play or the App Store.
 * Nothing is charged; the order goes to [DemoAuthServices], which answers offline. Never set in a real build.
 */
class DemoStore(override val platform: String) : StorePlatform {
    private var listener: StoreListener? = null
    private var n = 0

    /** What the next purchase does: complete (default), stay pending, or be cancelled. */
    var mode: Mode = Mode.COMPLETE

    enum class Mode { COMPLETE, PENDING, CANCEL }

    override fun connect(done: (StoreError?) -> Unit) = done(null)

    override fun products(ids: List<String>, done: (List<StoreProduct>?, StoreError?) -> Unit) =
        done(CATALOG.filter { it.productId in ids }, null)

    override fun purchases(done: (List<StorePurchase>?, StoreError?) -> Unit) = done(emptyList(), null)

    override fun purchase(productId: String, accountToken: String, done: (StoreError?) -> Unit) {
        done(null)
        val l = listener ?: return
        when (mode) {
            Mode.CANCEL -> l.onError(StoreError(StoreError.USER_CANCELLED, "Purchase cancelled", productId, StoreError.PLAY_USER_CANCELED))
            Mode.PENDING -> l.onPurchases(listOf(StorePurchase(productId, "demo-$productId-${++n}", StorePurchaseState.PENDING, accountToken = accountToken)))
            Mode.COMPLETE -> l.onPurchases(listOf(StorePurchase(productId, "demo-$productId-${++n}", StorePurchaseState.PURCHASED, accountToken = accountToken)))
        }
    }

    override fun finish(purchase: StorePurchase, done: (StoreError?) -> Unit) = done(null)

    override fun setListener(listener: StoreListener?) {
        this.listener = listener
    }

    companion object {
        val CATALOG = listOf(
            StoreProduct("099points", "100 Points", "", "0.99", "USD", "$0.99"),
            StoreProduct("199points", "200 Points", "", "1.99", "USD", "$1.99"),
            StoreProduct("499points", "550 Points", "", "4.99", "USD", "$4.99"),
            StoreProduct("999points", "1200 Points", "", "9.99", "USD", "$9.99"),
            StoreProduct("4999points", "6500 Points", "", "49.99", "USD", "$49.99"),
            StoreProduct("9999points", "14000 Points", "", "99.99", "USD", "$99.99"),
            StoreProduct(PurchaseSkus.BOOST, "Account Boost", "", "9.99", "USD", "$9.99"),
            StoreProduct(PurchaseSkus.SPINS, "5 Spins", "", "4.99", "USD", "$4.99"),
            StoreProduct(PurchaseSkus.ACCOUNT, "Premium Account", "", "9.99", "USD", "$9.99"),
            StoreProduct(PurchaseSkus.PRO, "Ecency Pro", "", "19.99", "USD", "$19.99"),
        )
    }
}
