import XCTest

/// The dApp browser and its bridge, the QR scanner, the share flow and the proposal card against the debug fake
/// data: nothing is signed or broadcast anywhere (the fake transport refuses every broadcast).
final class PlatformUITests: XCTestCase {
    private func launch(_ extra: [String]) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-seedAccounts", "YES"] + extra
        app.launch()
        return app
    }

    private func any(_ app: XCUIApplication, _ id: String) -> XCUIElement { app.descendants(matching: .any)[id].firstMatch }

    func testExploreOpensADappInItsTabAndTabsAddAndClose() {
        let app = launch(["-openScreen", "explore"])
        let peakd = app.buttons["dapp-PeakD"]
        XCTAssertTrue(peakd.waitForExistence(timeout: 15))
        peakd.tap()
        XCTAssertTrue(app.buttons["browser-reload"].waitForExistence(timeout: 10), "a page shows the browser header")
        XCTAssertTrue(app.textFields["browser-url"].exists)
        app.buttons["browser-add-tab"].tap()
        XCTAssertTrue(app.buttons["dapp-Ecency"].waitForExistence(timeout: 10), "a new tab starts on the directory")
        XCTAssertTrue(any(app, "browser-close-tab-2").waitForExistence(timeout: 5))
        app.buttons["browser-close-tab-2"].tap()
        XCTAssertTrue(app.buttons["browser-reload"].waitForExistence(timeout: 10), "back on the first tab's page")
    }

    /// Owner request: Explore closed and opened again in the session shows the tabs it had.
    func testExploreOpenedAgainShowsItsTabs() {
        let app = launch(["-openScreen", "explore"])
        let peakd = app.buttons["dapp-PeakD"]
        XCTAssertTrue(peakd.waitForExistence(timeout: 15))
        peakd.tap()
        XCTAssertTrue(app.buttons["browser-reload"].waitForExistence(timeout: 10))
        app.buttons["browser-add-tab"].tap()
        XCTAssertTrue(app.buttons["dapp-Ecency"].waitForExistence(timeout: 10))
        // the new tab is on the directory: its first header button closes Explore
        app.buttons["browser-home"].tap()
        XCTAssertTrue(app.buttons["dapp-Ecency"].waitForNonExistence(timeout: 10), "Explore closed")

        let menu = app.buttons["account-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 15))
        menu.tap()
        let explore = app.buttons["menu-explore"]
        XCTAssertTrue(explore.waitForExistence(timeout: 10))
        for _ in 0..<6 where !explore.isHittable { any(app, "drawer-main").swipeUp() }
        explore.tap()
        XCTAssertTrue(any(app, "browser-tab-1").waitForExistence(timeout: 15), app.debugDescription)
        XCTAssertTrue(any(app, "browser-tab-2").exists, "both tabs are back")
        XCTAssertTrue(app.buttons["dapp-PeakD"].exists, "the tab that was active, the directory, is active again")
        any(app, "browser-tab-1").tap()
        XCTAssertTrue(app.buttons["browser-reload"].waitForExistence(timeout: 10), "the kept page shows in its tab")
        XCTAssertTrue(app.webViews.firstMatch.waitForExistence(timeout: 10))
    }

    func testABridgeRequestShowsTheWebViewsOriginAndCancelRejectsIt() {
        let app = launch(["-openScreen", "dapp"])
        let sheet = any(app, "bridge-sheet")
        XCTAssertTrue(sheet.waitForExistence(timeout: 20), "the synthetic dApp asks for a vote on load")
        XCTAssertEqual(app.staticTexts["bridge-host"].label, "dapp.example")
        XCTAssertEqual(app.staticTexts["bridge-authority"].label, "POSTING")
        XCTAssertEqual(app.staticTexts["bridge-title"].label, "Vote")
        app.buttons["bridge-cancel"].tap()
        // the page hears the rejection through the bridge
        let rejected = app.webViews.staticTexts.containing(NSPredicate(format: "label CONTAINS 'User rejected the request.'")).firstMatch
        XCTAssertTrue(rejected.waitForExistence(timeout: 10))
    }

    func testALongFieldIsShownWholeOnTheSheet() {
        let app = launch(["-openScreen", "dapp"])
        XCTAssertTrue(any(app, "bridge-sheet").waitForExistence(timeout: 20))
        app.buttons["bridge-cancel"].tap()
        // the demo page pads a custom_json with 2,001 spaces and a 1,500 character note before the transfer
        let padded = app.webViews.buttons["Padded custom json"].firstMatch
        XCTAssertTrue(padded.waitForExistence(timeout: 10))
        padded.tap()
        XCTAssertTrue(any(app, "bridge-sheet").waitForExistence(timeout: 20))
        let json = app.staticTexts["bridge-row-json"]
        XCTAssertTrue(json.waitForExistence(timeout: 5))
        XCTAssertTrue(json.label.hasSuffix("\"to\":\"spike-mallory\",\"quantity\":\"1000\"}"), json.label)
        let line = app.staticTexts["bridge-authority"].frame.height
        XCTAssertGreaterThan(json.frame.height, line * 8, "the value is not clipped to four lines")
    }

    func testAConfirmedRequestThatFailsAnswersThePageWithTheError() {
        let app = launch(["-openScreen", "dapp"])
        XCTAssertTrue(any(app, "bridge-sheet").waitForExistence(timeout: 20))
        app.buttons["bridge-confirm"].tap()
        // the fake transport refuses every broadcast: the page gets an error, never a success
        let answered = app.webViews.staticTexts.containing(NSPredicate(format: "label CONTAINS '\"success\":false'")).firstMatch
        XCTAssertTrue(answered.waitForExistence(timeout: 30))
    }

    func testASharedTextAsksWhereItGoesThenOpensTheEditorWithIt() {
        let app = launch(["-demoShare", "Shared from another app"])
        let blog = app.buttons["share-blog"]
        XCTAssertTrue(blog.waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["share-wave"].exists)
        blog.tap()
        let body = app.textViews["editor-body"]
        XCTAssertTrue(body.waitForExistence(timeout: 15))
        expectation(for: NSPredicate(format: "value CONTAINS 'Shared from another app'"), evaluatedWith: body)
        waitForExpectations(timeout: 15)
    }

    func testTheProposalCardIsDismissedForever() {
        let app = launch([])
        let card = any(app, "proposal-card")
        XCTAssertTrue(card.waitForExistence(timeout: 20))
        XCTAssertEqual(app.staticTexts["proposal-title"].label, "Enjoying Ecency!")
        app.buttons["proposal-dismiss"].tap()
        let forever = app.buttons["Forever"].firstMatch
        XCTAssertTrue(forever.waitForExistence(timeout: 5))
        forever.tap()
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: card)
        waitForExpectations(timeout: 10)
    }

    func testAScannedProfileLinkClosesTheScannerAndOpensTheProfile() {
        // the simulator has no camera: -qrScan reads that value as if a code was scanned
        let app = launch(["-openScreen", "qr", "-qrScan", "https://ecency.com/@spike-bob"])
        addUIInterruptionMonitor(withDescription: "camera") { alert in
            for label in ["Allow", "OK", "Don’t Allow"] where alert.buttons[label].exists {
                alert.buttons[label].firstMatch.tap()
                return true
            }
            return false
        }
        XCTAssertTrue(any(app, "profile").waitForExistence(timeout: 20))
        XCTAssertFalse(any(app, "qr-scan").exists)
    }

    func testTheTransferRecipientHasAScanButton() {
        let app = launch(["-openScreen", "wallet:transfer,transfer,HIVE,hive,"])
        XCTAssertTrue(app.buttons["transfer-scan"].waitForExistence(timeout: 15))
    }
}
