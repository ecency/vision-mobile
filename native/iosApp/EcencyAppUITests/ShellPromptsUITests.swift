import XCTest

/// Delete Account, the update prompt and the announcement, from synthetic answers only.
final class ShellPromptsUITests: XCTestCase {
    private func launch(_ extra: [String]) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES"] + extra
        app.launch()
        return app
    }

    func testDeleteAccountAsksFirstAndCancelKeepsTheAccount() {
        let app = launch(["-seedAccounts", "YES", "-openScreen", "settings"])
        XCTAssertTrue(app.switches["menu-pin-lock"].waitForExistence(timeout: 15))
        // RN keeps it last in Settings; the list is lazy, so scroll until the row is there
        let row = app.buttons["menu-delete-account"]
        for _ in 0..<8 where !row.exists { app.swipeUp(velocity: .slow) }
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()
        let alert = app.alerts["Confirm Delete"]
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        XCTAssertTrue(alert.staticTexts["Are you sure you want to delete account? It will erase all of your data"].exists)
        Shots.save("delete-confirm")
        alert.buttons["Cancel"].firstMatch.tap()
        XCTAssertTrue(row.waitForExistence(timeout: 5), "still signed in: the row is still there")
    }

    func testConfirmingTheDeletionSignsThatAccountOut() {
        let app = launch(["-seedAccounts", "YES", "-openScreen", "settings"])
        XCTAssertTrue(app.switches["menu-pin-lock"].waitForExistence(timeout: 15))
        // RN keeps it last in Settings; the list is lazy, so scroll until the row is there
        let row = app.buttons["menu-delete-account"]
        for _ in 0..<8 where !row.exists { app.swipeUp(velocity: .slow) }
        XCTAssertTrue(row.waitForExistence(timeout: 5))
        row.tap()
        XCTAssertTrue(app.alerts["Confirm Delete"].waitForExistence(timeout: 5))
        app.alerts["Confirm Delete"].buttons["Delete"].firstMatch.tap()
        // spike-alice left; spike-bob is current and the settings stay
        let gone = NSPredicate(format: "exists == false")
        expectation(for: gone, evaluatedWith: app.alerts["Confirm Delete"])
        waitForExpectations(timeout: 10)
        app.navigationBars.buttons.firstMatch.tap()
        let menu = app.buttons["account-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 10))
        expectation(for: NSPredicate(format: "label == 'Account @spike-bob'"), evaluatedWith: menu)
        waitForExpectations(timeout: 10)
    }

    func testTheUpdatePromptShowsRnsCopy() {
        let app = launch(["-demoUpdate", "YES"])
        let alert = app.alerts["Update available v99.1.0"]
        XCTAssertTrue(alert.waitForExistence(timeout: 15))
        XCTAssertTrue(alert.staticTexts["Using latest version of Ecency gives you the best experience"].exists)
        Shots.save("update-prompt")
        // alert buttons are found by their titles
        alert.buttons["Remind Later"].firstMatch.tap()
        XCTAssertFalse(alert.waitForExistence(timeout: 2))
    }

    func testAnAnnouncementOpensItsLinkedPostInTheApp() {
        let app = launch(["-demoAnnouncement", "YES"])
        let alert = app.alerts["Community call this Friday"]
        XCTAssertTrue(alert.waitForExistence(timeout: 20), "three seconds after the app is up")
        Shots.save("announcement")
        alert.buttons["Read more"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["post-title"].waitForExistence(timeout: 15), "the linked post opened in the app")
    }
}
