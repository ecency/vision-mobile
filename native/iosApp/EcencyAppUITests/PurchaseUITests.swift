import XCTest

/// The purchase screens against the debug fake data and the offline demo store: nothing is bought or charged.
final class PurchaseUITests: XCTestCase {
    /// Saves the screen when the run asks for it (`TEST_RUNNER_IAP_SHOT_DIR=<folder>` in xcodebuild's environment).
    private func shot(_ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["IAP_SHOT_DIR"], !dir.isEmpty else { return }
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("iap-ios-\(name).png"))
    }

    private func launch(_ extra: [String]) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-seedAccounts", "YES"] + extra
        app.launch()
        return app
    }

    func testGetPointsListsThePacksAndBuysOne() {
        let app = launch(["-openScreen", "getPoints"])
        let best = app.buttons["buy-9999points"]
        XCTAssertTrue(best.waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["BEST DEAL!"].exists)
        XCTAssertTrue(app.staticTexts["POPULAR!"].exists)
        XCTAssertEqual(app.staticTexts["price-9999points"].label, "$99.99")
        XCTAssertTrue(app.buttons["buy-freePoints"].exists)
        shot("get-points")
        app.buttons["buy-999points"].tap()
        let toast = app.staticTexts["toast"]
        XCTAssertTrue(toast.waitForExistence(timeout: 10))
        XCTAssertEqual(toast.label, "Points purchase successful")
    }

    func testAPurchaseLinkConfirmsForTheLinkedAccount() {
        let app = launch(["-openScreen", "getPoints:spike-bob:999points"])
        let confirm = app.alerts.buttons["Confirm"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 20))
        XCTAssertTrue(app.alerts["Confirm Purchase @spike-bob"].exists)
        XCTAssertTrue(app.alerts.staticTexts["Buying 1200 POINTS for spike-bob at USD 9.99"].exists)
        shot("confirm")
        confirm.tap()
        let toast = app.staticTexts["toast"]
        XCTAssertTrue(toast.waitForExistence(timeout: 10))
    }

    func testAccountBoostAndTheFreePointsRow() {
        let app = launch(["-openScreen", "accountBoost"])
        let boost = app.buttons["buy-999boosts"]
        XCTAssertTrue(boost.waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["Alternative, In-app Purchase: 30 days delegation to boost your account."].exists)
        // the drawn Esie, not RN's old mascot picture
        XCTAssertEqual(app.images["esie"].value as? String, "thumbs up")
        shot("account-boost")
    }

    func testThePaidAccountLinkSellsOnlyThePaidAccount() {
        let app = launch(["-openLink", "https://ecency.com/purchase?type=account&username=spike-newbie&email=new@example.com"])
        let buy = app.buttons["register-buy"]
        XCTAssertTrue(buy.waitForExistence(timeout: 20))
        XCTAssertEqual(buy.label, "Buy Account - $9.99")
        XCTAssertFalse(app.buttons["register-free"].exists, "RN disableFree")
        shot("register-paid")
        buy.tap()
        XCTAssertTrue(app.buttons["register-done"].waitForExistence(timeout: 15))
    }
}
