import XCTest

/// Perks and the spin game against the debug fake data: nothing is bought, claimed or charged anywhere.
final class PerksUITests: XCTestCase {
    /// Saves the screen when the run asks for it (`TEST_RUNNER_POINTS_SHOT_DIR=<folder>` in xcodebuild's environment).
    private func shot(_ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["POINTS_SHOT_DIR"], !dir.isEmpty else { return }
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("points-ios-\(name).png"))
    }

    private func launch(_ extra: [String]) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-seedAccounts", "YES", "-demoStreak", "YES"] + extra
        app.launch()
        return app
    }

    func testPerksShowsTheQuestsAndAsksBeforeTheFreeze() {
        let app = launch(["-openScreen", "perks"])
        let vote = app.staticTexts["quest-count-vote"]
        XCTAssertTrue(vote.waitForExistence(timeout: 15))
        XCTAssertEqual(vote.label, "6/10")
        XCTAssertTrue(app.descendants(matching: .any)["perks-streak"].exists)
        shot("perks")
        app.buttons["tier-weekly"].tap()
        let weekly = app.staticTexts["quest-count-vote"]
        expectation(for: NSPredicate(format: "label == '31/50'"), evaluatedWith: weekly)
        waitForExpectations(timeout: 5)
        app.buttons["perks-freeze-buy"].tap()
        let confirm = app.alerts.buttons["Confirm"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        shot("freeze-confirm")
        app.alerts.buttons["Cancel"].firstMatch.tap()
        XCTAssertFalse(app.alerts.firstMatch.exists)
    }

    func testTheDailySpinOpensFromPerksAndClaims() {
        let app = launch(["-openScreen", "perks"])
        let spinRow = app.buttons["spend-spin"]
        XCTAssertTrue(spinRow.waitForExistence(timeout: 15))
        spinRow.tap()
        let button = app.buttons["spin-button"]
        XCTAssertTrue(button.waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["spin-count"].label, "1")
        shot("spin")
        button.tap()
        Thread.sleep(forTimeInterval: 1)
        shot("spinning")
        // the rings turn for 8 s, then the score and the 5 spins pack with the countdown
        XCTAssertTrue(app.staticTexts["spin-timer"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["25 Points"].exists)
        XCTAssertEqual(app.staticTexts["spin-count"].label, "0")
        shot("spin-none-left")
    }
}
