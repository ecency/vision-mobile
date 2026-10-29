import XCTest

/// Screenshots of the wallet when the run asks for them (`TEST_RUNNER_WALLET_SHOT_DIR=<folder>`).
enum WalletShots {
    static func save(_ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["WALLET_SHOT_DIR"], !dir.isEmpty else { return }
        let url = URL(fileURLWithPath: dir).appendingPathComponent("wallet-ios-\(name).png")
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: url)
    }
}

/// The wallet against the debug fake data: synthetic balances and history, nothing reaches a chain.
final class WalletUITests: XCTestCase {
    private func launch(_ extra: [String]) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-seedAccounts", "YES"] + extra
        app.launch()
        return app
    }

    func testTheWalletTabShowsTheAssetsAndTheirDetails() {
        let app = launch(["-startTab", "WALLET"])
        let points = app.buttons["asset-POINTS"]
        XCTAssertTrue(points.waitForExistence(timeout: 30))
        XCTAssertTrue(app.staticTexts["wallet-total"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["claim-HP"].exists)
        WalletShots.save("tab")
        app.buttons["asset-HIVE"].tap()
        XCTAssertTrue(app.buttons["action-transfer"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["action-swap_token"].exists)
        // the pending open order first, then the history newest first (the fixture's pages come ascending)
        let rows = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'activity-'"))
        XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 20))
        let ids = rows.allElementsBoundByIndex.map(\.identifier).filter { !$0.hasPrefix("activity-912345678") }
        XCTAssertTrue(ids.first?.hasPrefix("activity-1005_") == true, "\(ids)")
        WalletShots.save("details-hive")
        // #2747: web's categories, All first and selected; a category asks the chain again
        let all = app.buttons["history-chip-ALL"]
        XCTAssertTrue(all.waitForExistence(timeout: 10))
        XCTAssertTrue(all.isSelected)
        app.buttons["history-chip-STAKE"].tap()
        expectation(for: NSPredicate(format: "isSelected == true"), evaluatedWith: app.buttons["history-chip-STAKE"])
        waitForExpectations(timeout: 10)
        XCTAssertFalse(app.buttons["history-chip-INTEREST"].exists, "HIVE has no interest")
    }

    /// RN ClaimButton: the pill keeps its own width and sits centred in its asset card, in both directions.
    func testTheClaimPillSitsCentredInItsCard() {
        assertClaimPillsCentred(launch(["-startTab", "WALLET"]), label: "ltr")
    }

    func testTheClaimPillSitsCentredInItsCardRightToLeft() {
        let app = launch(["-startTab", "WALLET", "-AppleLanguages", "(ar)", "-AppleLocale", "ar_SA"])
        assertClaimPillsCentred(app, label: "rtl")
        // the card really is mirrored: the balance sits left of the card's centre
        let card = app.buttons["asset-HP"].frame
        XCTAssertLessThan(app.staticTexts["asset-balance-HP"].frame.midX, card.midX)
    }

    private func assertClaimPillsCentred(_ app: XCUIApplication, label: String) {
        let claim = app.buttons["claim-HP"]
        XCTAssertTrue(claim.waitForExistence(timeout: 30))
        for symbol in ["HP", "POINTS"] {
            let pill = app.buttons["claim-\(symbol)"]
            XCTAssertTrue(pill.waitForExistence(timeout: 10), "\(label) \(symbol)")
            // the card's header button spans the card's width inside its padding: its centre is the card's centre
            let card = app.buttons["asset-\(symbol)"].frame
            let frame = pill.frame
            XCTAssertEqual(frame.midX, card.midX, accuracy: 1, "\(label) \(symbol): pill \(frame) card \(card)")
            XCTAssertLessThan(frame.width, card.width * 0.8, "\(label) \(symbol): the pill keeps its own width")
            XCTAssertGreaterThanOrEqual(frame.height, 44, "\(label) \(symbol): touch target")
        }
        WalletShots.save("claim-\(label)")
    }

    func testATransferFormChecksTheRecipientAndAsksBeforeSending() {
        let app = launch(["-openScreen", "wallet:transfer,transfer,HIVE,hive,spike-bob"])
        let amount = app.textFields["transfer-amount"]
        XCTAssertTrue(amount.waitForExistence(timeout: 30))
        let balance = app.buttons["transfer-balance"]
        expectation(for: NSPredicate(format: "label CONTAINS '1234.567'"), evaluatedWith: balance)
        waitForExpectations(timeout: 20)
        amount.focusAndType("1.5")
        let next = app.buttons["transfer-next"]
        expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: next)
        waitForExpectations(timeout: 20)
        WalletShots.save("transfer")
        next.tap()
        XCTAssertTrue(app.buttons["transfer-confirm"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Continue transaction?"].exists)
    }

    func testOtherWalletScreensOpen() {
        let cases: [(String, String, String)] = [
            ("wallet:transfer,delegate_vesting_shares,HP,hive,spike-bob", "delegate-hp", "delegate"),
            ("wallet:transfer,withdraw_vesting,HP,hive,", "power-down-hp", "powerdown"),
            ("wallet:trade,HIVE", "trade-from", "trade"),
            ("wallet:redeem,boost_plus,", "redeem-next", "redeem"),
            ("wallet:assetDetails,HP", "action-delegate_vesting_shares", "details-hp"),
        ]
        for (screen, element, shot) in cases {
            let app = launch(["-openScreen", screen])
            let found = app.descendants(matching: .any)[element]
            XCTAssertTrue(found.waitForExistence(timeout: 30), screen)
            sleep(2)
            WalletShots.save(shot)
            app.terminate()
        }
    }
}
