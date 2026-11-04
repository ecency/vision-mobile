import XCTest

/// The Waves tab's getting-started card and the poll in the wave composer, from the debug fake data.
final class WavesGapsUITests: XCTestCase {
    private func launch() -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-seedAccounts", "YES", "-startTab", "WAVES"]
        app.launch()
        return app
    }

    func testGettingStartedShowsProgressAndDismisses() {
        let app = launch()
        let title = app.staticTexts["waves-onboarding-title"]
        XCTAssertTrue(title.waitForExistence(timeout: 20))
        XCTAssertEqual(title.label, "Getting started")
        XCTAssertEqual(app.staticTexts["waves-onboarding-progress"].label, "1/4", "the demo quests say one vote was cast today")
        XCTAssertTrue(app.buttons["waves-onboarding-item-wave"].exists)
        XCTAssertTrue(app.buttons["waves-onboarding-item-checkin"].exists)
        app.buttons["waves-onboarding-dismiss"].tap()
        XCTAssertFalse(title.waitForExistence(timeout: 2))
    }

    func testTheFeedTabShowsTheCardUnderItsOwnIdentifiers() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-seedAccounts", "YES"]
        app.launch()
        let title = app.staticTexts["feed-waves-onboarding-title"]
        XCTAssertTrue(title.waitForExistence(timeout: 20))
        XCTAssertEqual(title.label, "Getting started")
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "feed-waves-onboarding-item-wave").firstMatch.exists)
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "feed-waves-onboarding-progress").firstMatch.exists)
    }

    func testTheFirstWaveRowOpensTheComposerAndAPollCanBeAttached() {
        let app = launch()
        let row = app.buttons["waves-onboarding-item-wave"]
        XCTAssertTrue(row.waitForExistence(timeout: 20))
        row.tap()
        let poll = app.buttons["wave-poll"]
        XCTAssertTrue(poll.waitForExistence(timeout: 10))
        XCTAssertEqual(poll.label, "Add poll")
        poll.tap()
        let question = app.textFields["poll-question"].exists ? app.textFields["poll-question"] : app.textViews["poll-question"]
        XCTAssertTrue(question.waitForExistence(timeout: 10))
        question.focusAndType("Tea or coffee?")
        let first = app.textFields["poll-choice-0"]
        first.focusAndType("Tea")
        let second = app.textFields["poll-choice-1"]
        second.focusAndType("Coffee")
        let attach = app.buttons["poll-attach"]
        XCTAssertTrue(attach.isEnabled)
        Shots.save("wave-poll")
        attach.tap()
        expectation(for: NSPredicate(format: "label == 'Edit poll'"), evaluatedWith: poll)
        waitForExpectations(timeout: 10)
    }
}
