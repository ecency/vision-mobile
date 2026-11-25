import XCTest

/// #3739: the Advent Calendar against the offline games API (DemoAdvent), on 9 Dec 2026 (`-fakeNow`).
final class AdventUITests: XCTestCase {
    /// 2026-12-09T10:00:00Z: door 9 is open (a written answer), door 3 drawn (a quiz the seeded account won).
    private let dec9 = "1796810400000"

    private func launch(_ extra: [String], now: String? = nil) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-seedAccounts", "YES", "-fakeNow", now ?? dec9] + extra
        app.launch()
        return app
    }

    /// `TEST_RUNNER_ADVENT_SHOT_DIR=<folder>`: screenshots for the design review.
    private func shot(_ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["ADVENT_SHOT_DIR"], !dir.isEmpty else { return }
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("advent-ios-\(name).png"))
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    /// 25 Nov 2026, 10:00 UTC: the teaser counts down to the first door under its headline.
    func testTheTeaserCountsDownToTheFirstDoor() {
        let app = launch(["-openScreen", "route:ADVENT"], now: "1795600800000")
        let countdown = element(app, "advent-countdown")
        XCTAssertTrue(countdown.waitForExistence(timeout: 20), app.debugDescription)
        XCTAssertTrue(countdown.label.hasPrefix("5 days 1"), countdown.label)
    }

    func testTodaysDoorTakesAWrittenAnswer() {
        let app = launch(["-openScreen", "route:ADVENT"])
        let door = element(app, "advent-door-9")
        XCTAssertTrue(door.waitForExistence(timeout: 20), app.debugDescription)
        XCTAssertTrue(element(app, "advent-answered").waitForExistence(timeout: 10), "signed in: the answered count")
        shot("calendar")
        door.tap()
        let answer = element(app, "advent-answer")
        XCTAssertTrue(answer.waitForExistence(timeout: 10))
        answer.tap()
        answer.typeText("Snow on the roofs and tea by the window.")
        shot("door")
        element(app, "advent-submit").tap()
        XCTAssertTrue(element(app, "advent-done").waitForExistence(timeout: 15), "the answer was taken")
    }

    func testADrawnDoorShowsItsWinners() {
        let app = launch(["-openScreen", "route:ADVENT"])
        let door = element(app, "advent-door-3")
        XCTAssertTrue(door.waitForExistence(timeout: 20))
        XCTAssertTrue(element(app, "advent-answered").waitForExistence(timeout: 10))
        door.tap()
        XCTAssertTrue(element(app, "advent-winner-1").waitForExistence(timeout: 10), "the winners")
        XCTAssertTrue(element(app, "advent-you-won").exists, "the seeded account won door 3")
        shot("result")
    }

    func testTheFeedBannerShowsInSeasonAndClosesForTheDay() {
        let app = launch([])
        let banner = element(app, "advent-banner")
        XCTAssertTrue(banner.waitForExistence(timeout: 20), app.debugDescription)
        shot("banner")
        element(app, "advent-banner-close").tap()
        XCTAssertTrue(banner.waitForNonExistence(timeout: 5), "closed for today")
    }

    func testNoBannerAfterTheSeason() {
        // 2 Jan 2027: the calendar has hidden itself
        let app = launch([], now: "1798848000000")
        XCTAssertTrue(app.tabBars.firstMatch.waitForExistence(timeout: 20) || app.buttons.firstMatch.waitForExistence(timeout: 20))
        XCTAssertFalse(element(app, "advent-banner").waitForExistence(timeout: 4), "no banner after the season")
    }
}
