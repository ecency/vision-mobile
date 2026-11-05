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

    /// RN: with the app lock on, a wallet operation asks for the PIN first. Cancel signs nothing and keeps the form.
    func testWithTheLockOnATransferAsksForThePinBeforeSigning() {
        let app = launch(["-lockDemo", "YES", "-openScreen", "wallet:transfer,transfer,HIVE,hive,spike-bob"])
        XCTAssertTrue(app.staticTexts["pin-message"].waitForExistence(timeout: 30)) // cold start locks
        for digit in "1234" { app.buttons["pin-key-\(digit)"].tap() }
        let amount = app.textFields["transfer-amount"]
        XCTAssertTrue(amount.waitForExistence(timeout: 30))
        expectation(for: NSPredicate(format: "label CONTAINS '1234.567'"), evaluatedWith: app.buttons["transfer-balance"])
        waitForExpectations(timeout: 20)
        amount.focusAndType("1.5")
        let next = app.buttons["transfer-next"]
        expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: next)
        waitForExpectations(timeout: 20)
        next.tap()
        let confirm = app.buttons["transfer-confirm"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 10))
        confirm.tap()
        XCTAssertTrue(app.staticTexts["pin-message"].waitForExistence(timeout: 10), "the PIN is asked before signing")
        WalletShots.save("transfer-pin")
        app.buttons["pin-close"].tap()
        expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: app.staticTexts["pin-message"])
        waitForExpectations(timeout: 10)
        XCTAssertTrue(amount.exists, "cancel keeps the transfer form")
    }

    /// Tester report "unable to send Points gifts on comments": the gift under a comment tips that comment's author in
    /// POINTS, through the same signing as a transfer (a posting-key login is asked for the active key).
    func testAPointsTipOnACommentReachesSigning() {
        let app = launch(["-homeFilter", "trending"])
        let card = app.buttons.matching(identifier: "post-card").firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 30))
        card.tap()
        XCTAssertTrue(app.staticTexts["post-title"].waitForExistence(timeout: 15))
        let comment = app.staticTexts["First fixture comment."]
        for _ in 0..<12 where !(comment.exists && comment.isHittable) { app.swipeUp() }
        XCTAssertTrue(comment.isHittable, app.debugDescription)
        // the comment's own gift: below its text, never the pinned post footer's (scrolled into view if needed)
        let footer = app.descendants(matching: .any)["post-footer"]
        func commentTips() -> [XCUIElement] {
            app.buttons.matching(identifier: "tip").allElementsBoundByIndex.filter {
                $0.isHittable && $0.frame.minY > comment.frame.maxY && !(footer.exists && footer.frame.intersects($0.frame))
            }
        }
        for _ in 0..<3 where commentTips().isEmpty { app.swipeUp() }
        let tip = try! XCTUnwrap(commentTips().min { $0.frame.minY < $1.frame.minY }, app.debugDescription)
        tip.tap()
        XCTAssertTrue(app.staticTexts["Support @spike-bob"].waitForExistence(timeout: 15), app.debugDescription)
        let points = app.buttons["tip-currency-POINTS"]
        XCTAssertTrue(points.waitForExistence(timeout: 10))
        points.tap()
        expectation(for: NSPredicate(format: "isSelected == true"), evaluatedWith: points)
        waitForExpectations(timeout: 10)
        app.textFields["tip-amount"].focusAndType("1")
        let send = app.buttons["tip-send"]
        expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: send)
        waitForExpectations(timeout: 10)
        send.tap()
        let confirm = app.alerts.firstMatch.buttons["tip-confirm"].firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.alerts.firstMatch.staticTexts["Send 1 POINTS to @spike-bob?"].exists, app.debugDescription)
        confirm.tap()
        XCTAssertTrue(app.secureTextFields["upgrade-key"].waitForExistence(timeout: 15), "the active key is asked: \(app.debugDescription)")
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
