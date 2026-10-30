import EcencyCore
import StoreKit
import StoreKitTest
import XCTest
@testable import EcencyApp

/// Ecency Pro on iOS (issue #3555): the `1999pro` Non-Renewing Subscription of the local StoreKit test session
/// (Products.storekit: synthetic, nothing is charged) through the Pro card's model; orders go to the demo answers.
@MainActor
final class ProTests: XCTestCase {
    private var session: SKTestSession!

    override func setUp() async throws {
        session = try SKTestSession(configurationFileNamed: "Products")
        session.disableDialogs = true
        session.askToBuyEnabled = false
        session.clearTransactions()
        DemoAuthServices.shared.clearDemoPro()
    }

    override func tearDown() async throws {
        PurchaseTests.detachServiceStores()
        session.clearTransactions()
        DemoAuthServices.shared.clearDemoPro()
        DemoAuthServices.shared.demoProMembers.add("spike-alice")
    }

    private func waitUntil(_ timeout: TimeInterval = 20, _ condition: () -> Bool) {
        let end = Date().addingTimeInterval(timeout)
        while !condition() && Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.05)) }
    }

    private func unfinished(_ store: StoreKitStore) -> [StorePurchase] {
        var result: [StorePurchase]?
        store.purchases { list, _ in DispatchQueue.main.async { result = list ?? [] } }
        waitUntil { result != nil }
        return result ?? []
    }

    func testStoreKitSellsProAsANonRenewingSubscription() {
        let store = StoreKitStore()
        var list: [StoreProduct]?
        store.products(ids: [PurchaseSkus.shared.PRO]) { l, _ in DispatchQueue.main.async { list = l ?? [] } }
        waitUntil { list != nil }
        XCTAssertEqual(list?.map(\.productId), ["1999pro"])
        XCTAssertEqual(list?.first?.localizedPrice, "$19.99")
    }

    func testANonMemberBuysProWhichIsOrderedFinishedAndActivates() throws {
        let env = try PurchaseTests.environment()
        let store = PurchaseTests.serviceStore()
        env.store = store
        env.purchases.start()
        let pro = Observed(env.proModel(), ownsModel: true)
        let model = pro.model as! ProModel
        model.start()
        waitUntil { pro.state.price != nil }
        XCTAssertFalse(pro.state.member)
        XCTAssertEqual(pro.state.cardText, "Go Pro for $19.99 a year. Verified badge, a free blog, and support Ecency.")
        XCTAssertEqual(pro.state.buyLabel, "Get Pro for $19.99 a year")
        model.open()
        model.buy()
        waitUntil { pro.state.purchased }
        XCTAssertTrue(pro.state.purchased)
        // the demo order put the account on the roster, which the model reads again past its cache
        waitUntil { pro.state.succeeded }
        XCTAssertTrue(pro.state.succeeded)
        XCTAssertEqual(pro.state.successTitle, ProCopy.shared.SUCCESS_TITLE)
        XCTAssertTrue(env.proMembers.isPro(username: "spike-alice"))
        waitUntil { self.unfinished(store).isEmpty }
        XCTAssertTrue(unfinished(store).isEmpty, "finished after Ecency accepted the order")
    }

    func testAMemberSeesTheMemberStateAndTheExtension() throws {
        DemoAuthServices.shared.demoProMembers.add("spike-alice")
        let env = try PurchaseTests.environment()
        env.store = PurchaseTests.serviceStore()
        env.purchases.start()
        let pro = Observed(env.proModel(), ownsModel: true)
        let model = pro.model as! ProModel
        model.start()
        waitUntil { pro.state.member && pro.state.price != nil }
        XCTAssertEqual(pro.state.cardText, ProCopy.shared.MEMBER)
        XCTAssertEqual(pro.state.buyLabel, "Extend by 12 months for $19.99")
    }
}
