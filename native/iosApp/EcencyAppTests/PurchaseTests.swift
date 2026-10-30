import EcencyCore
import Sentry
import StoreKit
import StoreKitTest
import UserNotifications
import XCTest
@testable import EcencyApp

/// In-app purchases on iOS against a local StoreKit test session (Products.storekit: synthetic products, nothing is
/// charged), the Sentry scrubbing, analytics and rich push. Orders go to the offline demo answers.
@MainActor
final class PurchaseTests: XCTestCase {
    private var session: SKTestSession!

    override func setUp() async throws {
        session = try SKTestSession(configurationFileNamed: "Products")
        session.disableDialogs = true
        session.askToBuyEnabled = false
        session.clearTransactions()
    }

    override func tearDown() async throws {
        PurchaseTests.detachServiceStores()
        try await session.setSimulatedError(nil, forAPI: .verification)
        DemoAuthServices.shared.demoOrderLost = 0
        DemoAuthServices.shared.demoOrderStatus = 201
        DemoAuthServices.shared.demoOrderBody = "{}"
        session.clearTransactions()
    }

    /// Stores handed to a purchase service. A service outlives its test (its coroutines hold it) and would go on
    /// finishing whatever StoreKit pushes in later tests, so every test detaches them at its end.
    static var serviceStores: [StoreKitStore] = []

    static func serviceStore() -> StoreKitStore {
        let store = StoreKitStore()
        serviceStores.append(store)
        return store
    }

    static func detachServiceStores() {
        serviceStores.forEach { $0.setListener(listener: nil) }
        serviceStores.removeAll()
    }

    private func waitUntil(_ timeout: TimeInterval = 15, _ condition: () -> Bool) {
        let end = Date().addingTimeInterval(timeout)
        while !condition() && Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.05)) }
    }

    /// Records what the store hands over.
    private final class Recorder: NSObject, StoreListener {
        var purchases: [StorePurchase] = []
        var errors: [StoreError] = []
        let lock = NSLock()
        func onPurchases(purchases: [StorePurchase]) { lock.lock(); self.purchases += purchases; lock.unlock() }
        func onError(error: StoreError) { lock.lock(); errors.append(error); lock.unlock() }
        var snapshot: ([StorePurchase], [StoreError]) { lock.lock(); defer { lock.unlock() }; return (purchases, errors) }
    }

    private func products(_ store: StoreKitStore, _ ids: [String]) -> [StoreProduct] {
        var result: [StoreProduct]?
        store.products(ids: ids) { list, _ in DispatchQueue.main.async { result = list ?? [] } }
        waitUntil { result != nil }
        return result ?? []
    }

    private func unfinished(_ store: StoreKitStore) -> [StorePurchase] {
        var result: [StorePurchase]?
        store.purchases { list, _ in DispatchQueue.main.async { result = list ?? [] } }
        waitUntil { result != nil }
        return result ?? []
    }

    func testStoreKitSellsRnsProductsAndHandsOverTheSignedTransaction() {
        let store = StoreKitStore()
        let recorder = Recorder()
        store.setListener(listener: recorder)
        let list = products(store, PurchaseSkus.shared.ALL)
        XCTAssertEqual(Set(list.map(\.productId)), Set(PurchaseSkus.shared.ALL))
        let points = list.first { $0.productId == "999points" }!
        XCTAssertEqual(points.price, "9.99")
        XCTAssertEqual(points.currency, "USD")
        XCTAssertEqual(points.localizedPrice, "$9.99")

        var started: StoreError?? = .none
        store.purchase(productId: "999points", accountToken: PurchaseService.companion.accountToken(username: "spike-alice")) { e in DispatchQueue.main.async { started = .some(e) } }
        waitUntil { !recorder.snapshot.0.isEmpty }
        XCTAssertNil(started ?? nil)
        let bought = recorder.snapshot.0.single
        XCTAssertEqual(bought?.productId, "999points")
        XCTAssertEqual(bought?.state, .purchased)
        XCTAssertEqual(bought?.accountToken, PurchaseService.companion.accountToken(username: "spike-alice"), "the buyer travels as appAccountToken")
        XCTAssertEqual(bought?.token.split(separator: ".").count, 3, "the receipt is the JWS (jwsRepresentation)")
        // unfinished until Ecency took the order: RN's getAvailablePurchases missed exactly this on iOS
        XCTAssertEqual(unfinished(store).map(\.ref), [bought!.ref])
        var finished = false
        store.finish(purchase: bought!) { _ in DispatchQueue.main.async { finished = true } }
        waitUntil { finished }
        XCTAssertTrue(unfinished(store).isEmpty)
    }

    func testAskToBuyIsPendingUntilApprovedAndThenArrivesByItself() throws {
        session.askToBuyEnabled = true
        let store = StoreKitStore()
        let recorder = Recorder()
        store.setListener(listener: recorder)
        _ = products(store, ["199points"])
        store.purchase(productId: "199points", accountToken: "") { _ in }
        waitUntil { !recorder.snapshot.0.isEmpty }
        XCTAssertEqual(recorder.snapshot.0.single?.state, .pending, "a pending payment is never paid")
        let pending = try XCTUnwrap(session.allTransactions().first { $0.productIdentifier == "199points" })
        try session.approveAskToBuyTransaction(identifier: pending.identifier)
        waitUntil { recorder.snapshot.0.count >= 2 }
        XCTAssertEqual(recorder.snapshot.0.last?.state, .purchased)
        XCTAssertEqual(recorder.snapshot.0.last?.productId, "199points")
    }

    func testTheServiceOrdersThenFinishesAndARefundIsFinishedWithoutAnOrder() throws {
        let env = try PurchaseTests.environment()
        let store = PurchaseTests.serviceStore()
        env.store = store
        let outcomes = OutcomeRecorder()
        let watch = env.purchases.watchOutcomes { outcomes.add($0) }
        defer { watch.close() }
        env.purchases.start()
        let screen = Observed(env.storeScreenModel(kind: .points, username: "", productId: ""), ownsModel: true)
        let model = screen.model as! StoreScreenModel
        model.start()
        waitUntil { !screen.state.rows.isEmpty }
        XCTAssertEqual(screen.state.rows.first?.productId, "9999points")
        XCTAssertEqual(screen.state.rows.last?.productId, PurchaseSkus.shared.FREE_POINTS)
        model.buy(productId: "999points")
        waitUntil { outcomes.all.contains { $0 is PurchaseOutcomeDelivered } }
        XCTAssertTrue(outcomes.all.last is PurchaseOutcomeDelivered)
        XCTAssertEqual(env.ui.current.toast?.text, PurchaseCopy.shared.POINTS_SUCCESS)
        waitUntil { self.unfinished(store).isEmpty }
        XCTAssertTrue(unfinished(store).isEmpty, "finished after the order")

        // a purchase refunded before it was finished: nothing to deliver. The first service is detached, so only the
        // restarted one below can finish it.
        store.setListener(listener: nil)
        let refunded = StoreKitStore()
        let recorder = Recorder()
        refunded.setListener(listener: recorder)
        _ = products(refunded, ["499points"])
        refunded.purchase(productId: "499points", accountToken: "") { _ in }
        waitUntil { !recorder.snapshot.0.isEmpty }
        let refundedRef = try XCTUnwrap(recorder.snapshot.0.first?.ref)
        // the session's list has no documented order (the 999points purchase above is in it too): refund this one by id
        let all = session.allTransactions()
        let tx = try XCTUnwrap(all.first { String($0.identifier) == refundedRef }, "\(all.map { "\($0.identifier) \($0.productIdentifier)" })")
        XCTAssertEqual(tx.productIdentifier, "499points")
        try session.refundTransaction(identifier: tx.identifier)
        // StoreKit also pushes the refund through Transaction.updates, but the test session does not always deliver
        // that push (in a loaded full suite nothing came in 120 s), so it is not asserted (the updates loop is covered by
        // the Ask to Buy test). The app does not depend on it: recovery at the next start or foreground reads
        // Transaction.unfinished, where this store maps the refund to REVOKED, and the service finishes it without an
        // order. That path is checked here.
        var held: StorePurchase?
        waitUntil(30) {
            held = self.unfinished(refunded).first { $0.ref == refundedRef }
            return held?.state == .revoked
        }
        let revoked = try XCTUnwrap(held, "unfinished until a service finishes it")
        XCTAssertEqual(revoked.state, .revoked, "the store reads the refund as REVOKED")
        // the next start of the app (a new service on a new store) recovers it
        let restarted = try PurchaseTests.environment()
        restarted.store = PurchaseTests.serviceStore()
        let later = OutcomeRecorder()
        let watchLater = restarted.purchases.watchOutcomes { later.add($0) }
        defer { watchLater.close() }
        restarted.purchases.start()
        waitUntil(30) { !self.unfinished(refunded).contains { $0.ref == refundedRef } }
        XCTAssertFalse(unfinished(refunded).contains { $0.ref == refundedRef }, "finished")
        let delivered = (outcomes.all + later.all).filter { $0.productId == "499points" && $0 is PurchaseOutcomeDelivered }
        XCTAssertTrue(delivered.isEmpty, "a refunded purchase is never delivered")
    }

    func testAnOrderWhoseAcceptWasLostIsFinishedOnThe409AsDelivered() throws {
        // Ecency stored the order but its 201 never arrived; the retry is answered 409 (the receipt is recorded)
        DemoAuthServices.shared.demoOrderLost = 1
        DemoAuthServices.shared.demoOrderStatus = 409
        let env = try PurchaseTests.environment()
        let store = PurchaseTests.serviceStore()
        env.store = store
        let outcomes = OutcomeRecorder()
        let watch = env.purchases.watchOutcomes { outcomes.add($0) }
        defer { watch.close() }
        env.purchases.start()
        let screen = Observed(env.storeScreenModel(kind: .points, username: "", productId: ""), ownsModel: true)
        let model = screen.model as! StoreScreenModel
        model.start()
        waitUntil { !screen.state.rows.isEmpty }
        model.buy(productId: "999points")
        waitUntil { !outcomes.all.isEmpty && self.unfinished(store).isEmpty }
        XCTAssertTrue(outcomes.all.first is PurchaseOutcomeDelivered, "\(outcomes.all)")
        XCTAssertEqual(env.ui.current.toast?.text, PurchaseCopy.shared.POINTS_SUCCESS)
        XCTAssertTrue(unfinished(store).isEmpty, "the transaction is finished on 409")
        XCTAssertEqual(DemoAuthServices.shared.demoOrderLost, 0, "the lost answer was retried")
    }

    func testAnUnverifiedTransactionIsOrderedAndFinishedOnlyOnEcencysAnswer() throws {
        // StoreKit could not check the signature: the signed transaction still goes to Ecency, which verifies it itself;
        // it is finished only on Ecency's definite answer
        var simulated = false
        let session = self.session!
        Task { try? await session.setSimulatedError(.verification(.invalidSignature), forAPI: .verification); simulated = true }
        waitUntil { simulated }
        let raw = StoreKitStore()
        let recorder = Recorder()
        raw.setListener(listener: recorder)
        _ = products(raw, ["199points"])
        raw.purchase(productId: "199points", accountToken: "") { _ in }
        waitUntil { !recorder.snapshot.0.isEmpty || !recorder.snapshot.1.isEmpty }
        let handed = try XCTUnwrap(recorder.snapshot.0.single, "handed over as unverified, errors: \(recorder.snapshot.1.map(\.code))")
        XCTAssertEqual(handed.state, .unverified)
        XCTAssertEqual(handed.token.split(separator: ".").count, 3, "the receipt is the JWS (jwsRepresentation)")
        XCTAssertEqual(unfinished(raw).map(\.state), [.unverified])
        raw.setListener(listener: nil)

        // Ecency cannot answer: posted, kept unfinished
        DemoAuthServices.shared.demoOrderStatus = 503
        let env = try PurchaseTests.environment()
        env.store = PurchaseTests.serviceStore()
        let outcomes = OutcomeRecorder()
        let watch = env.purchases.watchOutcomes { outcomes.add($0) }
        defer { watch.close() }
        env.purchases.start()
        waitUntil(30) { outcomes.all.contains { $0 is PurchaseOutcomeFailed } }
        XCTAssertTrue(outcomes.all.contains { $0 is PurchaseOutcomeFailed }, "posted: \(outcomes.all)")
        XCTAssertFalse(outcomes.all.contains { $0 is PurchaseOutcomeDelivered })
        XCTAssertEqual(unfinished(raw).map(\.state), [.unverified], "not finished before a definite answer")

        // the next start, Ecency accepts it: finished
        PurchaseTests.detachServiceStores()
        DemoAuthServices.shared.demoOrderStatus = 201
        let restarted = try PurchaseTests.environment()
        restarted.store = PurchaseTests.serviceStore()
        let later = OutcomeRecorder()
        let watchLater = restarted.purchases.watchOutcomes { later.add($0) }
        defer { watchLater.close() }
        restarted.purchases.start()
        waitUntil(30) { self.unfinished(raw).isEmpty }
        XCTAssertTrue(unfinished(raw).isEmpty, "finished after Ecency accepted it")
        XCTAssertTrue(later.all.contains { $0 is PurchaseOutcomeDelivered })
    }

    func testAReceiptHeldForReviewStaysUnfinishedAndShowsTheOrderNumber() throws {
        DemoAuthServices.shared.demoOrderStatus = 422
        DemoAuthServices.shared.demoOrderBody = #"{"reason":"receipt_held","order":55}"#
        let env = try PurchaseTests.environment()
        let store = PurchaseTests.serviceStore()
        env.store = store
        let outcomes = OutcomeRecorder()
        let watch = env.purchases.watchOutcomes { outcomes.add($0) }
        defer { watch.close() }
        env.purchases.start()
        let screen = Observed(env.storeScreenModel(kind: .points, username: "", productId: ""), ownsModel: true)
        let model = screen.model as! StoreScreenModel
        model.start()
        waitUntil { !screen.state.rows.isEmpty }
        model.buy(productId: "999points")
        waitUntil { screen.state.alert != nil }
        XCTAssertEqual(screen.state.alert?.body, PurchaseCopy.shared.held(order: "55"), "\(outcomes.all)")
        XCTAssertFalse(outcomes.all.contains { $0 is PurchaseOutcomeDelivered })
        XCTAssertEqual(unfinished(store).map(\.productId), ["999points"], "kept unfinished while support reviews it")
    }

    func testSentryEventsAreScrubbedAndBenignErrorsDropped() {
        let wif = "5JdeC9P7Pbd1uGdFVEsJ41EkEnADbbHGq6p1BwFxm6txNBsQnsw"
        let event = Event(level: .error)
        event.message = SentryMessage(formatted: "key \(wif) and Bearer abcdefghijklmnop123")
        event.exceptions = [Exception(value: #"{"memo":"secret"}"#, type: "NSError")]
        let crumb = Breadcrumb(level: .info, category: "http")
        crumb.message = "GET https://ecency.com/x?code=abc"
        crumb.data = ["code": "abc", "nested": ["pin": "1234", "ok": "fine"]]
        event.breadcrumbs = [crumb]
        event.extra = ["email": "someone@example.com"]
        event.tags = ["productId": "999points"]
        event.context = ["iap": ["message": "failed for someone@example.com with Bearer abcdefghijklmnop123", "code": "secret-code", "productId": "999points"]]
        let out = CrashReporting.beforeSend(event)!
        XCTAssertEqual(out.message?.formatted, "key <redacted-key> and Bearer <redacted>")
        XCTAssertEqual(out.exceptions?.first?.value, #"{"memo":"<redacted>"}"#)
        XCTAssertEqual(out.breadcrumbs?.first?.message, "GET https://ecency.com/x?code=<redacted>")
        XCTAssertEqual(out.breadcrumbs?.first?.data?["code"] as? String, "<redacted>")
        XCTAssertEqual((out.breadcrumbs?.first?.data?["nested"] as? [String: Any])?["pin"] as? String, "<redacted>")
        XCTAssertEqual(out.extra?["email"] as? String, "<redacted>")
        XCTAssertEqual(out.tags?["productId"], "999points")
        XCTAssertNil(out.user)
        let iap = out.context?["iap"]
        XCTAssertEqual(iap?["message"] as? String, "failed for <redacted-email> with Bearer <redacted>")
        XCTAssertEqual(iap?["code"] as? String, "<redacted>")
        XCTAssertEqual(iap?["productId"] as? String, "999points")

        let cancelled = Event(level: .error)
        cancelled.exceptions = [Exception(value: "Job was cancelled", type: "kotlinx.coroutines.JobCancellationException")]
        XCTAssertNil(CrashReporting.beforeSend(cancelled))
        let pin = Event(level: .error)
        pin.exceptions = [Exception(value: "Please check your PIN", type: "Error")]
        XCTAssertNil(CrashReporting.beforeSend(pin))

        XCTAssertEqual(CrashReporting.release(bundleId: "app.esteem.mobile.ios", version: "4.0.0", build: "57"), "app.esteem.mobile.ios@4.0.0+57")
        let mapped = CrashReporting.Reporter.event(CrashReport(type: "IapError", message: "IAP x: y", tags: ["iap.stage": "purchase"], fingerprint: ["iap", "x"],
                                                               contextName: "iap", context: ["code": "x"], isMessage: false, level: "error"))
        XCTAssertEqual(mapped.fingerprint, ["iap", "x"])
        XCTAssertEqual(mapped.tags?["iap.stage"], "purchase")
        XCTAssertEqual(mapped.exceptions?.first?.type, "IapError")
        XCTAssertNotNil(mapped.context?["iap"])
    }

    func testAnalyticsAreOffInDebugUnlessAHostIsNamed() {
        XCTAssertEqual(AppContainer.analyticsHost("", debug: true), "")
        XCTAssertEqual(AppContainer.analyticsHost("", debug: false), "https://pl.ecency.com")
        XCTAssertEqual(AppContainer.analyticsHost("off", debug: false), "")
        XCTAssertTrue(AppContainer.analyticsUserAgent().hasSuffix("Mobile"))
        XCTAssertTrue(AppContainer.analyticsUserAgent().contains("(iOS "))
    }

    func testRichPushTakesOnlyAnHttpsImageAndAttachesIt() async throws {
        XCTAssertEqual(RichPush.imageURL(["fcm_options": ["image": "https://images.ecency.com/a.png"]])?.absoluteString, "https://images.ecency.com/a.png")
        XCTAssertNil(RichPush.imageURL(["fcm_options": ["image": "http://images.ecency.com/a.png"]]), "https only")
        XCTAssertNil(RichPush.imageURL(["fcm_options": ["image": "file:///etc/passwd"]]))
        XCTAssertNil(RichPush.imageURL(["fcm_options": ["image": "https://a.b/" + String(repeating: "x", count: 3000)]]))
        XCTAssertNil(RichPush.imageURL(["image": "https://images.ecency.com/a.png"]))
        XCTAssertEqual(RichPush.fileExtension(mimeType: "image/jpeg"), "jpg")
        XCTAssertNil(RichPush.fileExtension(mimeType: "text/html"))

        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubImageProtocol.self]
        let session = URLSession(configuration: config)
        StubImageProtocol.mime = "image/png"
        let attachment = await RichPush.attachment(from: URL(string: "https://images.ecency.com/a.png")!, session: session)
        XCTAssertNotNil(attachment)
        StubImageProtocol.mime = "text/html"
        let refused = await RichPush.attachment(from: URL(string: "https://images.ecency.com/a.png")!, session: session)
        XCTAssertNil(refused, "not an image: the notification stays as it came")

        // a body over the cap: refused on its announced length, and while streaming when it announces none
        let cap = RichPush.maxBytes
        defer { RichPush.maxBytes = cap; StubImageProtocol.size = nil; StubImageProtocol.announce = true }
        RichPush.maxBytes = 1_000
        StubImageProtocol.mime = "image/png"
        StubImageProtocol.size = 5_000
        StubImageProtocol.announce = true
        let announced = await RichPush.attachment(from: URL(string: "https://images.ecency.com/big.png")!, session: session)
        XCTAssertNil(announced, "Content-Length over the cap")
        StubImageProtocol.announce = false
        let streamed = await RichPush.attachment(from: URL(string: "https://images.ecency.com/big2.png")!, session: session)
        XCTAssertNil(streamed, "no length announced: stopped past the cap")
        StubImageProtocol.size = 500
        let small = await RichPush.attachment(from: URL(string: "https://images.ecency.com/small.png")!, session: session)
        XCTAssertNotNil(small, "under the cap it attaches")
    }

    static func environment() throws -> AppEnvironment {
        let store = KeychainSecureStore(service: "app.esteem.mobile.ios.tests.purchases")
        try store.wipe()
        let vault = KeyVault(store: store)
        try vault.save(account: VaultAccount(
            username: "spike-alice", authType: .postingKey, postingKey: PrivateKey.companion.fromSeed(seed: "ecency-native-iap-test-throwaway").toWif(), activeKey: nil,
            memoKey: nil, accessToken: "demo-access-spike-alice", hiveAuthKey: nil, hiveAuthToken: nil, hiveAuthExpiry: 0
        ))
        try vault.setCurrentUsername(username: "spike-alice")
        let env = AppEnvironment(
            api: try EcencyApi(nodes: ["https://fake-hive.invalid"], transport: FakeHiveTransport()),
            vault: KeyVaultAsync(vault: vault),
            renderer: PostBodyRendererCompanion.shared.`default`(proxyBase: ImageProxy.companion.DEFAULT_BASE),
            dispatchers: AppDispatchers.companion.platform(),
            proxyBase: ImageProxy.companion.DEFAULT_BASE,
            debugKeyImport: true,
            nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
            security: SecurityConfig(pinIterations: 16, lockTimeoutMillis: 60_000, monotonicMillis: { KotlinLong(value: 0) }),
            legacy: nil,
            auth: DemoAuthServices.shared.create(nowMillis: { KotlinLong(value: 0) }, waitMillis: 1000)
        )
        env.session.start()
        let end = Date().addingTimeInterval(10)
        while env.session.current.username != "spike-alice" && Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.05)) }
        return env
    }
}

private final class OutcomeRecorder {
    private(set) var all: [PurchaseOutcome] = []
    func add(_ o: PurchaseOutcome) { all.append(o) }
}

private extension Array {
    var single: Element? { count == 1 ? first : nil }
}

/// Answers every request with a tiny body of the MIME type the test sets.
private final class StubImageProtocol: URLProtocol {
    nonisolated(unsafe) static var mime = "image/png"
    /// A body of this many bytes instead of the tiny PNG; [announce] sends its Content-Length.
    nonisolated(unsafe) static var size: Int?
    nonisolated(unsafe) static var announce = true
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        // a 1x1 PNG
        let png = Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==")!
        let body = StubImageProtocol.size.map { Data(count: $0) } ?? png
        var headers = ["Content-Type": StubImageProtocol.mime]
        if StubImageProtocol.announce { headers["Content-Length"] = String(body.count) }
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: body)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
