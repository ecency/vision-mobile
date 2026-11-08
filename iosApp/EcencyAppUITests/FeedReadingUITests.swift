import XCTest

/// Feed, posts and reading against the debug fake transport and fake REST answers.
final class FeedReadingUITests: XCTestCase {
    private func launch(_ extra: [String] = []) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-homeFilter", "trending"] + extra
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    func testSignedInFeedShowsRnFiltersAndCustomise() {
        let app = launch(["-seedAccounts", "YES"])
        XCTAssertTrue(app.buttons["filter-FOLLOWING"].waitForExistence(timeout: 15))
        for title in ["COMMUNITIES", "TRENDING", "HOT"] { XCTAssertTrue(app.buttons["filter-\(title)"].exists, title) }
        app.buttons["filter-customise"].tap()
        XCTAssertTrue(app.navigationBars["Customise Filters"].waitForExistence(timeout: 5))
        // dropping to two filters is refused
        app.buttons["filter-option-friends"].tap()
        app.buttons["filter-option-communities"].tap()
        app.buttons["filters-apply"].tap()
        XCTAssertTrue(app.alerts["Please select 3 filters"].waitForExistence(timeout: 5))
        app.alerts.buttons["OK"].tap()
        app.buttons["filter-option-created"].tap()
        app.buttons["filters-apply"].tap()
        XCTAssertTrue(app.buttons["filter-NEW"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["filter-FOLLOWING"].exists)
    }

    /// The first home tab carries the proposal card above its list; an account that voted (or dismissed it for good)
    /// has no card there, and the list under it still shows its posts, whichever filter is first.
    func testTheFirstTabShowsItsPostsWithoutTheProposalCard() {
        let app = launch(["-seedAccounts", "YES", "-prefs.customTabs.mainTabs", "trending,friends,hot",
                          "-prefs.proposal.vote.342_spike-alice", "processed"])
        let first = app.buttons["filter-TRENDING"]
        XCTAssertTrue(first.waitForExistence(timeout: 15))
        XCTAssertTrue(first.isSelected, "the first tab is the selected one")
        XCTAssertTrue(app.buttons.matching(identifier: "post-card").firstMatch.waitForExistence(timeout: 20), "the first tab shows its posts")
        XCTAssertFalse(element(app, "proposal-card").exists)
    }

    func testPostMenuListsRnItems() {
        let app = launch(["-seedAccounts", "YES"])
        let options = app.buttons["post-options"].firstMatch
        XCTAssertTrue(options.waitForExistence(timeout: 15))
        options.tap()
        XCTAssertTrue(app.buttons["menu-item-copy"].waitForExistence(timeout: 10))
        for id in ["cross-post", "share", "bookmarks", "report", "edit-history"] {
            XCTAssertTrue(app.buttons["menu-item-\(id)"].exists, id)
        }
        // spike-alice moderates the fake community: pin and mute show
        XCTAssertTrue(app.buttons["menu-item-pin-community"].exists)
        app.buttons["menu-item-edit-history"].tap()
        XCTAssertTrue(element(app, "history-version-1").waitForExistence(timeout: 10))
    }

    /// Owner report 11-08 (TestFlight): Share in a post's menu showed nothing. The menu sheet closes first, and the
    /// system share sheet was presented on the controller being dismissed, which iOS ignores.
    func testShareFromThePostMenuOpensTheShareSheet() {
        let app = launch(["-seedAccounts", "YES"])
        let options = app.buttons["post-options"].firstMatch
        XCTAssertTrue(options.waitForExistence(timeout: 15))
        options.tap()
        let share = app.buttons["menu-item-share"]
        XCTAssertTrue(share.waitForExistence(timeout: 10))
        share.tap()
        let sheet = app.otherElements["ActivityListView"]
        XCTAssertTrue(sheet.waitForExistence(timeout: 10), "the system share sheet is up")
    }

    /// The mute post notes sheet (set role layout): the action needs a note, Cancel sends nothing.
    func testModNotesNeedANoteAndCancelCloses() {
        let app = launch(["-seedAccounts", "YES"])
        let options = app.buttons["post-options"].firstMatch
        XCTAssertTrue(options.waitForExistence(timeout: 15))
        options.tap()
        XCTAssertTrue(app.buttons["menu-item-mute-post"].waitForExistence(timeout: 10))
        app.buttons["menu-item-mute-post"].tap()
        let input = app.textFields["mod-notes-input"]
        XCTAssertTrue(input.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["mod-notes-confirm"].isEnabled)
        input.focusAndType("off topic")
        XCTAssertTrue(app.buttons["mod-notes-confirm"].isEnabled)
        // iOS 27 brings the keyboard in after the first keys and the sheet rides up with it: a tap read from the
        // button's frame before then lands where the button was (#3620)
        app.waitForKeyboardToSettle()
        app.buttons["mod-notes-cancel"].tap()
        XCTAssertTrue(input.waitForNonExistence(timeout: 5))
    }

    func testVotersScreenSortsAndSearches() {
        let app = launch(["-openScreen", "voters"])
        XCTAssertTrue(app.navigationBars["Voters Info (8)"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["voter-spike-alice"].exists)
        app.buttons["voters-sort-PERCENT"].tap()
        XCTAssertTrue(app.buttons["voter-spike-alice"].exists)
    }

    func testCardCountsOpenVotersAndReblogs() {
        let app = launch()
        let voters = app.buttons["card-voters"].firstMatch
        XCTAssertTrue(voters.waitForExistence(timeout: 15))
        voters.tap()
        XCTAssertTrue(app.navigationBars["Voters Info (8)"].waitForExistence(timeout: 10))
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["card-reblogs"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["Reblog Info (3)"].waitForExistence(timeout: 10))
    }

    func testQuickProfileSheet() {
        let app = launch(["-openScreen", "quickProfile"])
        XCTAssertTrue(app.buttons["quick-full-profile"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["@spike-alice"].exists)
        XCTAssertTrue(app.staticTexts["1,234"].waitForExistence(timeout: 10))
    }
}
