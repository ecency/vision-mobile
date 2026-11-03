import XCTest

/// Two panes on a wide window (README "iPhone Duo", #3601): the user menu beside the list until something opens,
/// then the list and what it opened. Runs where the window has room for them (iPhone Duo's inner display held
/// sideways, NavState.PANES_MIN_WIDTH); a phone skips it, and the phone UI tests cover the phone's single column.
final class TwoPaneUITests: XCTestCase {
    private func launch(_ extra: [String]) throws -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-seedAccounts", "YES", "-homeFilter", "trending"] + extra
        app.launch()
        let window = app.windows.firstMatch
        XCTAssertTrue(window.waitForExistence(timeout: 20))
        try XCTSkipUnless(window.frame.width >= 840, "a phone-sized window (\(window.frame.width) pt) has one column")
        return app
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any)[id].firstMatch
    }

    func testTheUserMenuSitsBesideTheFeedUntilAPostOpensBesideIt() throws {
        let app = try launch([])
        let card = app.buttons.matching(identifier: "post-card").firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 20))
        XCTAssertTrue(element(app, "drawer-main").exists, "the user menu beside the feed")
        XCTAssertFalse(element(app, "drawer-scrim").exists, "a column, not the drawer over the tabs")
        XCTAssertFalse(element(app, "detail-pane").exists)

        card.tap()
        let pane = element(app, "detail-pane")
        XCTAssertTrue(pane.waitForExistence(timeout: 10))
        XCTAssertTrue(element(app, "post-views").waitForExistence(timeout: 15), "the post in the pane")
        XCTAssertTrue(card.exists, "the feed stays beside it")
        XCTAssertLessThan(card.frame.maxX, pane.frame.minX + 1, "the feed on the leading side")
        XCTAssertFalse(element(app, "drawer-main").exists, "the menu closed")
        // the post's own actions are in the pane's header
        XCTAssertTrue(pane.descendants(matching: .any)["post-options"].firstMatch.exists)

        // the pane's menu button: the menu and the feed again
        element(app, "pane-menu").tap()
        XCTAssertTrue(element(app, "drawer-main").waitForExistence(timeout: 5))
        XCTAssertTrue(element(app, "detail-pane").waitForNonExistence(timeout: 5))
    }

    func testWhatAPostOpensGoesOnInThePaneAndComesBack() throws {
        let app = try launch(["-openPost", "spike-alice/fake-post-1"])
        XCTAssertTrue(element(app, "post-views").waitForExistence(timeout: 20))
        XCTAssertTrue(element(app, "detail-pane").exists, "a link opens beside the feed as a tap does")
        XCTAssertFalse(element(app, "pane-back").exists, "nothing to go back to: the feed is beside it")
        // the post header's author name, inside the pane (the feed card beside it has one too)
        let author = element(app, "detail-pane").buttons.matching(identifier: "card-author-name").firstMatch
        XCTAssertTrue(author.waitForExistence(timeout: 10))
        author.tap()
        // the quick profile (RN), then the full profile: pushed in the pane, over the post
        let full = element(app, "quick-full-profile")
        XCTAssertTrue(full.waitForExistence(timeout: 10))
        full.tap()
        XCTAssertTrue(element(app, "profile").waitForExistence(timeout: 10))
        XCTAssertTrue(element(app, "detail-pane").descendants(matching: .any)["profile"].firstMatch.exists, "in the pane")
        XCTAssertTrue(element(app, "pane-back").waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.buttons.matching(identifier: "post-card").firstMatch.exists, "the feed stays")
        element(app, "pane-back").tap()
        XCTAssertTrue(element(app, "post-views").waitForExistence(timeout: 10))
    }

    func testAChatOpensBesideTheChatListWithoutABackButton() throws {
        let app = try launch(["-startTab", "CHATS"])
        let row = element(app, "chat-row-ch-ecency")
        XCTAssertTrue(row.waitForExistence(timeout: 20))
        XCTAssertTrue(element(app, "drawer-main").exists)
        row.tap()
        XCTAssertTrue(element(app, "chat-thread").waitForExistence(timeout: 10))
        XCTAssertTrue(row.exists, "the chat list stays")
        XCTAssertFalse(element(app, "chat-back").exists, "the pane's own buttons instead")
        XCTAssertTrue(element(app, "pane-menu").exists)
    }

    func testAWaveOpensItsThreadBesideTheWaves() throws {
        let app = try launch(["-startTab", "WAVES"])
        let thread = app.buttons.matching(identifier: "wave-thread").firstMatch
        XCTAssertTrue(thread.waitForExistence(timeout: 20))
        XCTAssertTrue(element(app, "drawer-main").exists, "the user menu beside the waves")
        thread.tap()
        XCTAssertTrue(element(app, "detail-pane").waitForExistence(timeout: 10))
        XCTAssertTrue(thread.exists, "the waves stay beside it")
        XCTAssertFalse(element(app, "drawer-main").exists)
    }

    func testAnAssetOpensItsDetailsBesideTheWalletAndSendCoversBoth() throws {
        let app = try launch(["-startTab", "WALLET"])
        let hive = app.buttons["asset-HIVE"]
        XCTAssertTrue(hive.waitForExistence(timeout: 30))
        XCTAssertTrue(element(app, "drawer-main").exists, "the user menu beside the wallet")
        hive.tap()
        let pane = element(app, "detail-pane")
        XCTAssertTrue(pane.waitForExistence(timeout: 10))
        XCTAssertTrue(pane.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'activity-'")).firstMatch.waitForExistence(timeout: 20),
                      "the history in the pane")
        XCTAssertTrue(hive.exists, "the wallet stays beside it")
        // a transfer is no reading screen: it covers the window as on a phone
        app.buttons["wallet-send"].tap()
        XCTAssertTrue(pane.waitForNonExistence(timeout: 10))
    }
}
