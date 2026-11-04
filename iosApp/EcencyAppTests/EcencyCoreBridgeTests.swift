import EcencyCore
import XCTest

final class EcencyCoreBridgeTests: XCTestCase {
    func testAppAndCoreShareTheVersion() {
        let appVersion = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String
        XCTAssertEqual(appVersion, EcencyCore.shared.VERSION)
    }

    func testGreetingNamesThePlatform() {
        XCTAssertTrue(EcencyCore.shared.greeting().contains("iOS"))
    }
}
