import EcencyCore
import Foundation
import StoreKit

/// StoreKit 2 for the shared `PurchaseService` (RN expo-iap / openiap-apple): consumables, and Ecency Pro (`sells`). The purchase-order
/// receipt is the signed transaction (`jwsRepresentation`), which Ecency verifies with Apple's library, exactly what
/// RN sends. `StoreKit.Transaction.updates` is listened to from the start (an Ask to Buy approval, a purchase made while the
/// app was killed, a refund); `StoreKit.Transaction.unfinished` is the recovery list (RN's `getAvailablePurchases` missed
/// unfinished consumables). An unverified transaction (StoreKit could not check its signature) is handed over as UNVERIFIED:
/// the shared service never orders it, finishes it so it stops coming back at every launch, and reports it.
final class StoreKitStore: NSObject, StorePlatform {
    let platform: String = PurchaseService.companion.APP_STORE

    private let lock = NSLock()
    private var listener: StoreListener?
    private var catalog: [String: Product] = [:]
    private var transactions: [String: StoreKit.Transaction] = [:]
    private var updates: Task<Void, Never>?

    override init() {
        super.init()
        updates = Task.detached { [weak self] in
            for await result in StoreKit.Transaction.updates {
                guard let self else { return }
                self.currentListener()?.onPurchases(purchases: [self.storePurchase(of: result)])
            }
        }
    }

    deinit { updates?.cancel() }

    private func currentListener() -> StoreListener? {
        lock.lock(); defer { lock.unlock() }
        return listener
    }

    func setListener(listener: StoreListener?) {
        lock.lock(); self.listener = listener; lock.unlock()
    }

    func connect(done: @escaping (StoreError?) -> Void) {
        // StoreKit needs no connection; a device that cannot pay is RN's billing-unavailable
        done(AppStore.canMakePayments ? nil : StoreError(code: StoreError.companion.BILLING_UNAVAILABLE, message: L.Native.Ios.Store.billingUnavailable,
                                                          productId: nil, responseCode: nil, debugMessage: nil))
    }

    func products(ids: [String], done: @escaping ([StoreProduct]?, StoreError?) -> Void) {
        Task {
            do {
                let found = try await Product.products(for: ids)
                lock.lock(); found.forEach { catalog[$0.id] = $0 }; lock.unlock()
                done(found.filter(StoreKitStore.sells).map(StoreKitStore.product), nil)
            } catch {
                done(nil, StoreKitStore.error(error, productId: nil))
            }
        }
    }

    func purchases(done: @escaping ([StorePurchase]?, StoreError?) -> Void) {
        Task {
            var list: [StorePurchase] = []
            for await result in StoreKit.Transaction.unfinished {
                list.append(storePurchase(of: result))
            }
            done(list, nil)
        }
    }

    func purchase(productId: String, accountToken: String, done: @escaping (StoreError?) -> Void) {
        lock.lock(); let product = catalog[productId]; lock.unlock()
        guard let product else {
            return done(StoreError(code: StoreError.companion.ITEM_UNAVAILABLE, message: WalletUiCopy.shared.PRODUCT_NOT_AVAILABLE, productId: productId, responseCode: nil, debugMessage: nil))
        }
        done(nil)
        Task {
            let l = currentListener()
            do {
                // the account the purchase is for travels with the transaction (appAccountToken, back in storePurchase)
                let options: Set<Product.PurchaseOption> = UUID(uuidString: accountToken).map { [.appAccountToken($0)] } ?? []
                switch try await product.purchase(options: options) {
                case let .success(result):
                    l?.onPurchases(purchases: [self.storePurchase(of: result)])
                case .pending:
                    // Ask to Buy or a payment that needs action: nothing to post until StoreKit.Transaction.updates delivers it
                    l?.onPurchases(purchases: [StorePurchase(productId: productId, token: "pending:\(productId):\(UUID().uuidString)", state: .pending, acknowledged: false, ref: "",
                                                                 purchasedAtMillis: Int64(Date().timeIntervalSince1970 * 1000), accountToken: accountToken.lowercased())])
                case .userCancelled:
                    l?.onError(error: StoreError(code: StoreError.companion.USER_CANCELLED, message: L.Native.Ios.Store.cancelled, productId: productId, responseCode: nil, debugMessage: nil))
                @unknown default:
                    l?.onError(error: StoreError(code: StoreError.companion.UNKNOWN, message: L.Native.Ios.Store.unknownResult, productId: productId, responseCode: nil, debugMessage: nil))
                }
            } catch {
                l?.onError(error: StoreKitStore.error(error, productId: productId))
            }
        }
    }

    func finish(purchase: StorePurchase, done: @escaping (StoreError?) -> Void) {
        Task {
            lock.lock(); var transaction = transactions[purchase.ref]; lock.unlock()
            if transaction == nil {
                for await result in StoreKit.Transaction.unfinished {
                    // an unverified one is finished too (it is never delivered)
                    if String(result.unsafePayloadValue.id) == purchase.ref { transaction = result.unsafePayloadValue; break }
                }
            }
            guard let transaction else { return done(nil) }
            await transaction.finish()
            lock.lock(); transactions[purchase.ref] = nil; lock.unlock()
            done(nil)
        }
    }

    /// A transaction as the shared model's purchase: revoked ones as REVOKED, unverified ones as UNVERIFIED.
    private func storePurchase(of result: VerificationResult<StoreKit.Transaction>) -> StorePurchase {
        let t = result.unsafePayloadValue
        lock.lock(); transactions[String(t.id)] = t; lock.unlock()
        return StorePurchase(productId: t.productID, token: result.jwsRepresentation,
                             state: StoreKitStore.state(of: result), acknowledged: false, ref: String(t.id),
                             purchasedAtMillis: Int64(t.purchaseDate.timeIntervalSince1970 * 1000),
                             accountToken: t.appAccountToken?.uuidString.lowercased() ?? "")
    }

    /// Unverified wins over revoked: neither is delivered, both are finished.
    static func state(of result: VerificationResult<StoreKit.Transaction>) -> StorePurchaseState {
        guard case let .verified(t) = result else { return .unverified }
        return t.revocationDate == nil ? .purchased : .revoked
    }

    /// The product types the shared service handles: consumables, and Ecency Pro (`1999pro`, issue #3555) as a
    /// Non-Renewing Subscription or a Consumable. Both are posted, then finished, the same way; a non-renewing
    /// transaction also waits in `StoreKit.Transaction.unfinished` until it is finished, so recovery is the same.
    static func sells(_ p: Product) -> Bool {
        p.type == .consumable || (p.type == .nonRenewable && p.id == PurchaseSkus.shared.PRO)
    }

    /// RN providers/iap `_normalizeProduct`.
    static func product(_ p: Product) -> StoreProduct {
        StoreProduct(productId: p.id, title: p.displayName, description: p.description,
                     price: NSDecimalNumber(decimal: p.price).stringValue, currency: p.priceFormatStyle.currencyCode, localizedPrice: p.displayPrice)
    }

    /// StoreKit's errors as OpenIAP codes.
    static func error(_ error: Error, productId: String?) -> StoreError {
        let code: String
        switch error {
        case let e as StoreKitError:
            switch e {
            case .userCancelled: code = StoreError.companion.USER_CANCELLED
            case .networkError: code = StoreError.companion.NETWORK_ERROR
            case .notAvailableInStorefront: code = StoreError.companion.ITEM_UNAVAILABLE
            case .notEntitled: code = StoreError.companion.DEVELOPER_ERROR
            case .systemError: code = StoreError.companion.SERVICE_ERROR
            default: code = StoreError.companion.UNKNOWN
            }
        case let e as Product.PurchaseError:
            switch e {
            case .purchaseNotAllowed: code = StoreError.companion.BILLING_UNAVAILABLE
            case .productUnavailable: code = StoreError.companion.ITEM_UNAVAILABLE
            default: code = StoreError.companion.DEVELOPER_ERROR
            }
        default: code = StoreError.companion.UNKNOWN
        }
        return StoreError(code: code, message: error.localizedDescription, productId: productId, responseCode: nil, debugMessage: String(describing: error))
    }
}
