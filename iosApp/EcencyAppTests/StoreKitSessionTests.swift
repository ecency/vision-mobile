import StoreKit
import StoreKitTest
import XCTest

/// Opens the local StoreKit test session (Products.storekit) and reads its products, with no purchase (#3638).
///
/// A purchase reaches the test session only in a test process that started after the session's configuration was
/// installed on the simulator, which happens when a test first opens a session there (`PurchaseTests.requireSessionPurchases`).
/// `scripts/ios-sim.sh --test` runs this class on its own before the full run, so the purchase tests start in a process
/// the session serves, also on a freshly erased simulator.
@MainActor
final class StoreKitSessionTests: XCTestCase {
    func testTheSessionInstallsTheLocalProducts() async throws {
        let session = try SKTestSession(configurationFileNamed: "Products")
        session.disableDialogs = true
        session.clearTransactions()
        let products = try await Product.products(for: ["199points", "999points", "1999pro"])
        XCTAssertEqual(Set(products.map(\.id)), ["199points", "999points", "1999pro"])
    }
}
