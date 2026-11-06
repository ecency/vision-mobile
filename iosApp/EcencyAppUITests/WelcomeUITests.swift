import XCTest

/// RN's welcome art (#3636, README "Welcome and terms, register"): the line icons beside each point and the 3.55 s
/// launch animation over the screen, skipped under Reduce Motion. With `-fakeData` the decorative art is exposed
/// (`welcome-icon-<n>`, `welcome-launch`) so these tests can find it.
final class WelcomeUITests: XCTestCase {
    private func launch(_ extra: [String]) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-showWelcome", "YES"] + extra
        app.launch()
        return app
    }

    func testTheLaunchAnimationCoversTheWelcomeScreenThenGoes() {
        let app = launch([])
        let reveal = app.images["welcome-launch"]
        XCTAssertTrue(reveal.waitForExistence(timeout: 15), "the launch animation covers the welcome screen")
        // a further query waits for the app to idle, which a running animation delays to its end: so only its
        // existence is read while it draws (it is an overlay of the whole welcome view, AccountViews.swift)
        XCTAssertTrue(reveal.waitForNonExistence(timeout: 8), "gone after RN's 3.55 s")
        XCTAssertTrue(app.buttons["welcome-terms"].isHittable)
    }

    func testReduceMotionSkipsTheLaunchAnimation() {
        let app = launch(["-reduceMotion", "YES"])
        XCTAssertTrue(app.buttons["welcome-start"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.images["welcome-launch"].exists)
        XCTAssertTrue(app.buttons["welcome-terms"].isHittable)
    }

    func testEachPointHasRnsLineIconBesideIt() {
        let app = launch(["-reduceMotion", "YES"])
        XCTAssertTrue(app.buttons["welcome-start"].waitForExistence(timeout: 15))
        for i in 0..<3 {
            let icon = app.images["welcome-icon-\(i)"]
            XCTAssertTrue(icon.exists, "icon \(i)")
            XCTAssertGreaterThanOrEqual(icon.frame.width, 24, "RN's 30 pt icon: \(icon.frame)")
        }
        let first = app.images["welcome-icon-0"].frame, second = app.images["welcome-icon-1"].frame
        XCTAssertLessThan(first.maxY, second.minY, "one icon per point, top to bottom")
    }
}
