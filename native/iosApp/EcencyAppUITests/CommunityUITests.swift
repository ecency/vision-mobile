import XCTest

/// Communities, search and explore against the debug fake transport (fake accounts, synthetic community).
final class CommunityUITests: XCTestCase {
    private func launch(_ extra: [String] = []) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-homeSort", "trending"] + extra
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    /// Waits until `element` is there and enabled. A role chip is drawn disabled until the signed-in account's own
    /// role is known, so reading `isEnabled` the moment the row shows is a race.
    private func waitEnabled(_ element: XCUIElement, timeout: TimeInterval = 15) -> Bool {
        let enabled = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == true AND isEnabled == true"), object: element)
        return XCTWaiter().wait(for: [enabled], timeout: timeout) == .completed
    }

    /// Every step waits for what it needs next (a row, a text, an enabled button), never for the screen around it:
    /// the containers ("community-details", a member's row) show before their content has loaded.
    func testCommunitiesFromTheSideMenuThenACommunityAndItsMembers() {
        let app = launch(["-seedAccounts", "YES"])
        XCTAssertTrue(app.buttons["account-menu"].waitForExistence(timeout: 30))
        app.buttons["account-menu"].tap()
        XCTAssertTrue(app.buttons["menu-communities"].waitForExistence(timeout: 15))
        app.buttons["menu-communities"].tap()
        XCTAssertTrue(element(app, "joined-hive-100001").waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["tab-Discover"].waitForExistence(timeout: 15))
        app.buttons["tab-Discover"].tap()
        let row = element(app, "community-hive-100002")
        XCTAssertTrue(row.waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["1,200 Members • 40 Posters • 280 Posts"].waitForExistence(timeout: 15))
        // the title is the row's button for VoiceOver (README "Accessibility")
        XCTAssertTrue(row.buttons["Spike Photos"].waitForExistence(timeout: 15))
        row.buttons["Spike Photos"].tap()

        // the details card shows at once with a spinner; its text comes with the community
        XCTAssertTrue(element(app, "community-details").waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["A made-up community: nothing here is real content."].waitForExistence(timeout: 15))
        // the title closes and opens the card
        app.buttons["community-details-toggle"].tap()
        XCTAssertTrue(element(app, "community-description").waitForNonExistence(timeout: 5))
        app.buttons["community-details-toggle"].tap()
        XCTAssertTrue(element(app, "community-description").waitForExistence(timeout: 5))
        // spike-alice is the fake community's admin: the manage button shows; its sheet has the set role layout
        XCTAssertTrue(app.buttons["community-manage"].waitForExistence(timeout: 15))
        app.buttons["community-manage"].tap()
        XCTAssertTrue(app.buttons["manage-cancel"].waitForExistence(timeout: 10))
        app.buttons["manage-cancel"].tap()
        XCTAssertTrue(app.buttons["manage-cancel"].waitForNonExistence(timeout: 5))
        XCTAssertTrue(element(app, "community-details").exists, "Cancel goes nowhere")
        app.buttons["community-manage"].tap()
        XCTAssertTrue(app.buttons["Members and roles"].firstMatch.waitForExistence(timeout: 15))
        app.buttons["Members and roles"].firstMatch.tap()
        XCTAssertTrue(element(app, "member-spike-bob").waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["assign-role"].waitForExistence(timeout: 15))
        XCTAssertTrue(waitEnabled(app.buttons["role-spike-bob"]), "an admin can change a member's role")
        app.buttons["role-spike-bob"].tap()
        XCTAssertTrue(app.buttons["role-option-mod"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["role-option-member"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.buttons["role-option-member"].isEnabled, "the current role is checked and disabled")
        XCTAssertTrue(app.buttons["role-cancel"].exists)
    }

    func testHeaderSearchShowsResultsPeopleAndGroups() {
        let app = launch()
        XCTAssertTrue(app.buttons["header-search"].waitForExistence(timeout: 15))
        app.buttons["header-search"].tap()
        let field = app.textFields["search-input"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.focusAndType("result")
        XCTAssertTrue(element(app, "result-spike-bob/search-hit-1").waitForExistence(timeout: 10))
        app.buttons["search-tab-People"].tap()
        XCTAssertTrue(element(app, "person-spike-alice").waitForExistence(timeout: 10))
        app.buttons["search-tab-Communities"].tap()
        XCTAssertTrue(element(app, "community-hive-100001").waitForExistence(timeout: 10))

        app.buttons["search-tab-Best"].tap()
        app.buttons["search-filters"].tap()
        let tags = app.textFields["filter-tags"]
        XCTAssertTrue(tags.waitForExistence(timeout: 5))
        tags.focusAndType("a b c d e f")
        app.buttons["apply-filters"].tap()
        XCTAssertTrue(element(app, "filter-error").waitForExistence(timeout: 5))
        XCTAssertEqual(element(app, "filter-error").label, "Use at most 5 tags")
    }

    func testExploreOpensADappInTheBrowser() {
        let app = launch(["-seedAccounts", "YES"])
        XCTAssertTrue(app.buttons["account-menu"].waitForExistence(timeout: 15))
        app.buttons["account-menu"].tap()
        XCTAssertTrue(app.buttons["menu-explore"].waitForExistence(timeout: 5))
        app.buttons["menu-explore"].tap()
        XCTAssertTrue(app.staticTexts["Explore Ecosystem"].waitForExistence(timeout: 5))
        app.buttons["dapp-PeakD"].tap()
        // the browser wave: the dApp opens in the browser's tab (PlatformUITests)
        XCTAssertTrue(app.buttons["browser-reload"].waitForExistence(timeout: 10))
    }
}
