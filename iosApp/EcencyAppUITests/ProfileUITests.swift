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
        XCTAssertTrue(app.navigationBars["Followers (1234)"].exists)
        element(app, "user-spike-carol").tap()
        XCTAssertTrue(element(app, "profile-name").waitForExistence(timeout: 10))
    }

    /// Owner report: under the avatar (the pills do not fit beside it) the actions start at the left and the more
    /// menu ends at the right edge; beside the avatar the menu is the last thing on the right, as before.
    func testTheProfileMenuSitsAtTheRightOfTheActions() {
        var sawStacked = false
        var phoneWidth = false
        for size in ["", "UICTContentSizeCategoryXXXL"] {
            let app = launch(["-openLink", "https://ecency.com/@spike-bob"] + (size.isEmpty ? [] : ["-UIPreferredContentSizeCategoryName", size]))
            let menu = element(app, "profile-menu")
            XCTAssertTrue(menu.waitForExistence(timeout: 20))
            let follow = app.buttons["follow"].frame
            let window = app.windows.firstMatch.frame
            phoneWidth = window.width <= 440
            // either layout: the menu ends at the trailing edge of the row
            XCTAssertGreaterThan(menu.frame.maxX, window.maxX - 32, "\(size): menu at \(menu.frame), window \(window)")
            XCTAssertLessThanOrEqual(menu.frame.maxX, window.maxX, size)
            // the layout SwiftUI chose (ViewThatFits), read from the screen, not assumed from the text size: under the
            // avatar the pills start at the leading gutter, beside it they start after the avatar
            if follow.minX < window.minX + 40 {
                sawStacked = true
                XCTAssertEqual(follow.minX, window.minX + 16, accuracy: 2, "\(size): Follow at \(follow)")
                XCTAssertLessThan(element(app, "email-digest").frame.maxX, menu.frame.minX - 20, "\(size): the room is between the actions and the menu")
            }
            app.terminate()
        }
        // on a phone the largest standard text size does not fit beside the avatar: the stacked case was checked
        if phoneWidth { XCTAssertTrue(sawStacked, "no run put the pills under the avatar") }
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
        // review fix: a long amount keeps clear of the unit (the text area ends where the unit starts)
        amount.tap()
        amount.typeText("123456.123456789")
        let unit = element(app, "rc-delegate-amount-unit")
        XCTAssertLessThanOrEqual(amount.frame.maxX, unit.frame.minX, "field \(amount.frame), unit \(unit.frame)")
        XCTAssertTrue(element(app, "rc-delegate-sheet").exists, "cancel kept the sheet and sent nothing")
    }

    /// #3734: the summary is the list's first row and moves with the finger (no jump, nothing closes by itself); the
    /// tabs pin under the bar, which then shows the user; a tab switch keeps them pinned; a tap on the user goes back.
    func testTheSummaryScrollsWithTheListAndTheTabsPinUnderTheBar() {
        let app = launch(["-openLink", "https://ecency.com/@spike-bob"])
        XCTAssertTrue(element(app, "profile-name").waitForExistence(timeout: 20))
        XCTAssertFalse(element(app, "profile-compact-header").exists)
        let list = app.scrollViews["profile"]
        XCTAssertTrue(list.waitForExistence(timeout: 10))
        XCTAssertTrue(element(app, "post-options").waitForExistence(timeout: 15), "the blog has cards to scroll")
        let summary = element(app, "profile-summary")
        let tabs = app.buttons["tab-blog"]
        let summaryTop = summary.frame.minY
        let tabsTop = tabs.frame.minY
        // a slow drag held at its end: no fling, the content follows the finger
        let start = list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.8))
        start.press(forDuration: 0.1, thenDragTo: start.withOffset(CGVector(dx: 0, dy: -120)), withVelocity: .slow, thenHoldForDuration: 0.5)
        let moved = summaryTop - summary.frame.minY
        XCTAssertGreaterThan(moved, 60, "the summary moved with the finger")
        XCTAssertEqual(tabsTop - tabs.frame.minY, moved, accuracy: 1, "the tabs moved with it: one scroll, no jump")
        XCTAssertTrue(summary.exists, "nothing closed")
        let bar = app.navigationBars.firstMatch
        for _ in 0..<3 { list.swipeUp() }
        XCTAssertTrue(element(app, "profile-compact-header").waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertEqual(tabs.frame.minY, bar.frame.maxY, accuracy: 1, "the tabs pinned under the bar")
        app.buttons["tab-posts"].tap()
        RunLoop.current.run(until: Date().addingTimeInterval(1))
        XCTAssertTrue(element(app, "profile-compact-header").exists, "a tab switch keeps the summary away: summary \(element(app, "profile-summary").frame) tabs \(app.buttons["tab-posts"].frame) bar \(bar.frame)")
        XCTAssertEqual(app.buttons["tab-posts"].frame.minY, bar.frame.maxY, accuracy: 1, "and the tabs pinned")
        element(app, "profile-compact-header").tap()
        XCTAssertTrue(element(app, "profile-compact-header").waitForNonExistence(timeout: 5))
        XCTAssertTrue(element(app, "profile-name").isHittable, "back at the summary")
    }

    /// Review of #3734: far down a tab (the summary row long unloaded by the lazy stack), a tab switch still starts
    /// the new tab at its top with the tabs pinned under the bar.
    func testATabSwitchFarDownKeepsTheTabsPinnedUnderTheBar() {
        let app = launch(["-openLink", "https://ecency.com/@spike-bob"])
        XCTAssertTrue(element(app, "profile-name").waitForExistence(timeout: 20))
        let list = app.scrollViews["profile"]
        XCTAssertTrue(element(app, "post-options").waitForExistence(timeout: 15), "the blog has cards to scroll")
        for _ in 0..<12 { list.swipeUp(velocity: .fast) }
        RunLoop.current.run(until: Date().addingTimeInterval(1.5))
        XCTAssertTrue(element(app, "profile-compact-header").waitForExistence(timeout: 5))
        let bar = app.navigationBars.firstMatch
        app.buttons["tab-posts"].tap()
        RunLoop.current.run(until: Date().addingTimeInterval(1.5))
        XCTAssertTrue(element(app, "profile-compact-header").exists, "the summary still away: summary \(element(app, "profile-summary").frame) tabs \(app.buttons["tab-posts"].frame) bar \(bar.frame)")
        XCTAssertEqual(app.buttons["tab-posts"].frame.minY, bar.frame.maxY, accuracy: 1, "the tabs pinned: tabs \(app.buttons["tab-posts"].frame) bar \(bar.frame)")
        let first = app.buttons.matching(identifier: "post-card").allElementsBoundByIndex.filter { $0.isHittable }.map { $0.frame.minY }.min() ?? 0
        XCTAssertEqual(first, app.buttons["tab-posts"].frame.maxY, accuracy: 24, "the new tab at its top, right under the tabs")
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
