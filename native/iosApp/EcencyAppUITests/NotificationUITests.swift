import XCTest

/// The notifications tab, push settings, the in-app banner and a tapped push behind the lock, against the debug
/// fake data (synthetic notifications, nothing on the network).
final class NotificationUITests: XCTestCase {
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

    func testSignedOutTheTabAsksToLoginAndTheLeaderboardShows() {
        let app = launch()
        XCTAssertTrue(app.buttons["tab-notification"].waitForExistence(timeout: 15))
        app.buttons["tab-notification"].tap()
        XCTAssertTrue(app.staticTexts["Login to see"].waitForExistence(timeout: 5))
        XCTAssertFalse(element(app, "notification-n-1").exists)
        app.buttons["notifications-page-Leaderboard"].tap()
        XCTAssertTrue(app.buttons["leader-spike-alice"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["Top Users"].exists)
        XCTAssertTrue(app.staticTexts["DAILY"].exists)
    }

    func testTheListShowsRnCopyAndMarkAllClearsTheBadge() {
        let app = launch(["-seedAccounts", "YES"])
        let tab = app.buttons["tab-notification"]
        XCTAssertTrue(tab.waitForExistence(timeout: 15))
        let unread = NSPredicate(format: "label == %@", "Notifications, 3 unread")
        expectation(for: unread, evaluatedWith: tab)
        waitForExpectations(timeout: 10)
        tab.tap()
        let first = element(app, "notification-n-1")
        XCTAssertTrue(first.waitForExistence(timeout: 10))
        // each row's text column is one VoiceOver button: the line, the post, the time, read or unread as its value
        let vote = app.buttons["notification-text-n-1"]
        XCTAssertEqual(vote.value as? String, "Unread")
        XCTAssertTrue(vote.label.hasPrefix("spike-bob voted 100%, A synthetic post"), vote.label)
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "spike-carol replied to")).firstMatch.exists)
        XCTAssertTrue(app.staticTexts["ALL"].exists)

        app.buttons["mark-all-read"].tap()
        let read = NSPredicate(format: "value == %@", "Read")
        expectation(for: read, evaluatedWith: vote)
        waitForExpectations(timeout: 10)
        expectation(for: NSPredicate(format: "label == %@", "Notifications"), evaluatedWith: tab)
        waitForExpectations(timeout: 10)
    }

    func testASwipeMarksOneRowRead() {
        let app = launch(["-seedAccounts", "YES", "-startTab", "NOTIFICATION"])
        let vote = app.buttons["notification-text-n-1"]
        XCTAssertTrue(vote.waitForExistence(timeout: 15))
        XCTAssertEqual(vote.value as? String, "Unread")
        // a fast, mostly vertical flick with some sideways drift is the scroll's: nothing is marked
        let from = vote.coordinate(withNormalizedOffset: CGVector(dx: 0.7, dy: 0.9))
        from.press(forDuration: 0.01, thenDragTo: vote.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: -3.5)), withVelocity: .fast, thenHoldForDuration: 0)
        sleep(1)
        element(app, "notification-list").swipeDown()
        XCTAssertTrue(vote.waitForExistence(timeout: 5))
        XCTAssertEqual(vote.value as? String, "Unread", "a vertical flick must not mark")
        // right to left marks it read and opens nothing
        vote.swipeLeft(velocity: .fast)
        expectation(for: NSPredicate(format: "value == %@", "Read"), evaluatedWith: vote)
        waitForExpectations(timeout: 10)
        XCTAssertTrue(element(app, "notification-list").exists)
        XCTAssertFalse(element(app, "post").exists)
        // the list still scrolls vertically over the rows
        element(app, "notification-list").swipeUp()
        element(app, "notification-list").swipeDown()
        XCTAssertFalse(element(app, "notification-read-filter").exists, "no read status menu any more")
    }

    func testTheQuickChipsSelectTheirFilterAndTheSelectedOneGoesBackToAll() {
        let app = launch(["-seedAccounts", "YES", "-startTab", "NOTIFICATION"])
        let replies = app.buttons["notification-chip-REPLIES"]
        let mentions = app.buttons["notification-chip-MENTIONS"]
        XCTAssertTrue(replies.waitForExistence(timeout: 15))
        XCTAssertEqual(replies.label, "Replies")
        XCTAssertFalse(replies.isSelected)
        XCTAssertFalse(mentions.isSelected)
        replies.tap()
        expectation(for: NSPredicate(format: "isSelected == true"), evaluatedWith: replies)
        waitForExpectations(timeout: 10)
        XCTAssertTrue(app.staticTexts["REPLIES"].exists, "the menu shows the chip's filter")
        XCTAssertFalse(mentions.isSelected)
        // the selected chip again: back to All
        replies.tap()
        expectation(for: NSPredicate(format: "isSelected == false"), evaluatedWith: replies)
        waitForExpectations(timeout: 10)
        XCTAssertTrue(app.staticTexts["ALL"].waitForExistence(timeout: 5))
        // chosen in the menu, Mentions selects its chip
        app.buttons["notification-filter"].tap()
        app.buttons["notification-filter-2"].tap()
        expectation(for: NSPredicate(format: "isSelected == true"), evaluatedWith: mentions)
        waitForExpectations(timeout: 10)
    }

    func testTheFilterBarFitsAtTheLargestTextSize() {
        let app = launch(["-seedAccounts", "YES", "-startTab", "NOTIFICATION", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"])
        let replies = app.buttons["notification-chip-REPLIES"]
        XCTAssertTrue(replies.waitForExistence(timeout: 15))
        let screen = app.windows.firstMatch.frame
        for id in ["notification-filter", "notification-chip-REPLIES", "notification-chip-MENTIONS", "mark-all-read"] {
            let frame = app.buttons[id].frame
            XCTAssertTrue(app.buttons[id].isHittable, id)
            XCTAssertTrue(frame.minX >= screen.minX - 1 && frame.maxX <= screen.maxX + 1, "\(id) \(frame) inside \(screen)")
        }
    }

    func testAFollowRowOpensTheProfileAndTheAvatarTheQuickProfile() {
        let app = launch(["-seedAccounts", "YES", "-startTab", "NOTIFICATION"])
        let follow = element(app, "notification-text-n-4")
        XCTAssertTrue(follow.waitForExistence(timeout: 15))
        app.buttons["notification-avatar-n-4"].tap()
        XCTAssertTrue(element(app, "quick-profile").waitForExistence(timeout: 10))
        // the quick profile is the one place that still shows reputation
        XCTAssertTrue(app.staticTexts["Reputation"].exists)
        app.swipeDown(velocity: .fast)
        XCTAssertTrue(element(app, "quick-profile").waitForNonExistence(timeout: 5))
        follow.tap()
        XCTAssertTrue(element(app, "profile").waitForExistence(timeout: 10))
    }

    func testPushSwitchesInSettings() {
        let app = launch(["-seedAccounts", "YES", "-openScreen", "settings"])
        let all = app.switches["push-all"]
        XCTAssertTrue(all.waitForExistence(timeout: 15))
        XCTAssertEqual(all.value as? String, "1")
        // the settings list is lazy and grows: scroll in small steps until the row is there
        let vote = app.switches["push-vote"]
        for _ in 0..<6 where !vote.exists { app.swipeUp(velocity: .slow) }
        XCTAssertTrue(vote.waitForExistence(timeout: 5))
        XCTAssertEqual(vote.value as? String, "1")
        vote.switches.firstMatch.tap()
        XCTAssertEqual(vote.value as? String, "0")
        // fake-data runs have no push service: the RN notice shows
        for _ in 0..<6 where !element(app, "push-unavailable").exists { app.swipeUp(velocity: .slow) }
        XCTAssertTrue(element(app, "push-unavailable").waitForExistence(timeout: 5))
    }

    func testATappedPushStaysBehindThePinAndOpensAfterTheUnlock() {
        let app = launch(["-lockDemo", "YES", "-demoPush", "tap"])
        XCTAssertTrue(app.buttons["pin-key-1"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.staticTexts["post-title"].exists, "nothing of the notification behind the lock")
        XCTAssertFalse(element(app, "banner").exists)
        XCTAssertFalse(app.staticTexts["spike-bob"].exists)
        for digit in "1234" { app.buttons["pin-key-\(digit)"].tap() }
        XCTAssertTrue(element(app, "post-title").waitForExistence(timeout: 15), "the tapped notification's post opens after the unlock")
    }

    func testAForegroundPushShowsTheBanner() {
        let app = launch(["-seedAccounts", "YES", "-demoPush", "banner"])
        let banner = element(app, "banner")
        XCTAssertTrue(banner.waitForExistence(timeout: 15))
        XCTAssertTrue(banner.label.contains("New reply on @spike-alice"), banner.label)
        banner.tap()
        XCTAssertTrue(element(app, "post-title").waitForExistence(timeout: 15))
    }
}
