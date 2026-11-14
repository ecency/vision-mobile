import XCTest

/// The profile area against the debug fake transport and demo Ecency API (nothing leaves the device).
final class ProfileUITests: XCTestCase {
    private func launch(_ extra: [String]) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-seedAccounts", "YES"] + extra
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    func testOtherProfileShowsSummaryActionsAndFollowers() {
        let app = launch(["-openLink", "https://ecency.com/@spike-bob"])
        XCTAssertTrue(element(app, "profile-name").waitForExistence(timeout: 20))
        XCTAssertEqual(element(app, "profile-name").label, "Spike Bob")
        XCTAssertEqual(element(app, "profile-username").label, "@spike-bob", "no reputation (only the quick profile shows it)")
        XCTAssertFalse(app.staticTexts["(64)"].exists)
        XCTAssertTrue(app.buttons["follow"].exists)
        XCTAssertTrue(app.buttons["message"].exists)
        XCTAssertTrue(element(app, "profile-menu").exists)
        XCTAssertTrue(app.buttons["tab-comments"].exists)
        app.buttons["tab-waves"].tap()
        // the Waves tab lists the author's waves (no placeholder)
        XCTAssertTrue(element(app, "wave-spike-bob-wave-own").waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(element(app, "placeholder-tab").exists)
        app.buttons["tab-blog"].tap()
        app.buttons["mana-bars"].tap()
        XCTAssertTrue(element(app, "vp-text").waitForExistence(timeout: 5))
        XCTAssertTrue(element(app, "vp-text").label.hasPrefix("Voting power: 87.3%"))
        app.buttons["followers"].tap()
        XCTAssertTrue(element(app, "user-spike-carol").waitForExistence(timeout: 10))
        XCTAssertTrue(app.navigationBars["Follower (1234)"].exists)
        element(app, "user-spike-carol").tap()
        XCTAssertTrue(element(app, "profile-name").waitForExistence(timeout: 10))
    }

    /// Owner request: Delegate RC from another profile's menu, typed in billions, at least 5, confirmed first.
    func testDelegateRcFromTheMenuChecksTheAmountAndConfirms() {
        let app = launch(["-openLink", "https://ecency.com/@spike-bob"])
        let menu = element(app, "profile-menu")
        XCTAssertTrue(menu.waitForExistence(timeout: 20))
        menu.tap()
        let item = app.buttons["DELEGATE RC"]
        XCTAssertTrue(item.waitForExistence(timeout: 5), app.debugDescription)
        item.tap()
        let available = element(app, "rc-delegate-available")
        XCTAssertTrue(available.waitForExistence(timeout: 15), app.debugDescription)
        XCTAssertTrue(available.label.contains("64 billion RC"), available.label)
        let amount = element(app, "rc-delegate-amount")
        amount.tap()
        amount.typeText("4")
        XCTAssertTrue(element(app, "rc-delegate-problem").waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["rc-delegate-review"].isEnabled)
        amount.typeText("0")
        XCTAssertTrue(element(app, "rc-delegate-problem").waitForNonExistence(timeout: 5), "40 billion is within the 64")
        // the unit stays visible inside the field while typing (owner choice)
        XCTAssertEqual(element(app, "rc-delegate-amount-unit").label, "billion RC", "the field shows its unit")
        app.buttons["rc-delegate-review"].tap()
        XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 5))
        XCTAssertTrue(app.alerts.firstMatch.staticTexts["Delegate 40 billion RC to @spike-bob?"].exists, app.alerts.firstMatch.debugDescription)
        app.alerts.buttons["Cancel"].tap()
        XCTAssertTrue(element(app, "rc-delegate-sheet").exists, "cancel keeps the sheet and sends nothing")
    }

    /// RN profileView: the summary closes as the list scrolls down (the bar shows the user, the tabs stay) and opens
    /// again on a tap on the user in the bar or back at the top.
    func testSummaryClosesOnScrollAndOpensFromTheBarOrTheTop() {
        let app = launch(["-openLink", "https://ecency.com/@spike-bob"])
        XCTAssertTrue(element(app, "profile-name").waitForExistence(timeout: 20))
        XCTAssertFalse(element(app, "profile-compact-header").exists)
        let list = app.scrollViews["profile-list"]
        XCTAssertTrue(list.waitForExistence(timeout: 10))
        XCTAssertTrue(element(app, "post-options").waitForExistence(timeout: 15), "the blog has cards to scroll")
        list.swipeUp()
        XCTAssertTrue(element(app, "profile-compact-header").waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(element(app, "profile-summary").waitForNonExistence(timeout: 5))
        XCTAssertTrue(app.buttons["tab-blog"].exists, "the tabs stay")
        element(app, "profile-compact-header").tap()
        XCTAssertTrue(element(app, "profile-summary").waitForExistence(timeout: 5))
        XCTAssertTrue(element(app, "profile-compact-header").waitForNonExistence(timeout: 5))
        list.swipeUp()
        XCTAssertTrue(element(app, "profile-compact-header").waitForExistence(timeout: 5))
        list.swipeDown()
        list.swipeDown()
        XCTAssertTrue(element(app, "profile-summary").waitForExistence(timeout: 5))
        // closed, then another tab: it opens over that tab's list
        list.swipeUp()
        XCTAssertTrue(element(app, "profile-compact-header").waitForExistence(timeout: 5))
        app.buttons["tab-posts"].tap()
        XCTAssertTrue(element(app, "profile-summary").waitForExistence(timeout: 5))
        XCTAssertTrue(element(app, "profile-compact-header").waitForNonExistence(timeout: 5))
    }

    /// RN CommentView: a comment row of the profile's Comments tab has the gift, which opens the tipping sheet.
    func testACommentRowOfTheCommentsTabOffersTheTipSheet() {
        let app = launch(["-openLink", "https://ecency.com/@spike-bob"])
        XCTAssertTrue(app.buttons["tab-comments"].waitForExistence(timeout: 20))
        app.buttons["tab-comments"].tap()
        let tip = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'comment-tip-'")).firstMatch
        XCTAssertTrue(tip.waitForExistence(timeout: 15), app.debugDescription)
        XCTAssertEqual(tip.label, "Send tip")
        tip.tap()
        XCTAssertTrue(element(app, "tip-sheet").waitForExistence(timeout: 10), app.debugDescription)
    }

    func testOwnProfileEditShowsSaveOnChange() {
        let app = launch(["-openScreen", "editProfile"])
        let name = element(app, "field-NAME")
        XCTAssertTrue(name.waitForExistence(timeout: 20), app.debugDescription)
        XCTAssertFalse(element(app, "save-profile").exists)
        name.focusAndType(" Edited")
        XCTAssertTrue(element(app, "save-profile").waitForExistence(timeout: 5), app.debugDescription)
    }

    func testBookmarksTabsList() {
        let app = launch(["-openScreen", "bookmarks"])
        XCTAssertTrue(element(app, "user-spike-alice").waitForExistence(timeout: 20))
        app.buttons["Favorites"].tap()
        XCTAssertTrue(element(app, "user-spike-carol").waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["Tags"].tap()
        XCTAssertTrue(element(app, "tag-photography").waitForExistence(timeout: 5))
    }
}
