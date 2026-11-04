import XCTest

/// Saves a screenshot of the simulator's screen when the run asks for it (`TEST_RUNNER_GAPS_SHOT_DIR=<folder>` in
/// the environment of xcodebuild): states that need taps, such as a PIN, cannot be reached with launch arguments.
enum Shots {
    static func save(_ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["GAPS_SHOT_DIR"], !dir.isEmpty else { return }
        let url = URL(fileURLWithPath: dir).appendingPathComponent("gaps-accounts-ios-\(name).png")
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: url)
    }
}

/// Register and Refer & Earn against the debug fake data: nothing reaches a real server and no account is created.
final class OnboardingUITests: XCTestCase {
    private func launch(_ extra: [String]) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES"] + extra
        app.launch()
        return app
    }

    private func waitEnabled(_ element: XCUIElement, timeout: TimeInterval = 10) {
        expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: element)
        waitForExpectations(timeout: timeout)
    }

    func testSignUpFromLoginRegistersThroughTheFreePath() {
        let app = launch(["-openScreen", "login"])
        let signUp = app.buttons["login-signup"]
        XCTAssertTrue(signUp.waitForExistence(timeout: 15))
        // the header's secondary way in reads "Sign up", not RN's "JOIN NOW"
        XCTAssertEqual(app.buttons["login-join"].label, "Sign up")
        signUp.tap()
        let username = app.textFields["register-username"]
        XCTAssertTrue(username.waitForExistence(timeout: 10), "the register screen, not the web")
        XCTAssertFalse(app.buttons["register-continue"].isEnabled)
        username.focusAndType("spike-new")
        let email = app.textFields["register-email"]
        email.focusAndType("new@example.com")
        waitEnabled(app.buttons["register-continue"])
        app.buttons["register-continue"].tap()
        XCTAssertTrue(app.staticTexts["captcha-stand-in"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.buttons["register-buy"].label, "Buy Account - $9.99", "the paid account from the demo store")
        waitEnabled(app.buttons["register-free"])
        Shots.save("register-options")
        app.buttons["register-free"].tap()
        let done = app.buttons["register-done"]
        XCTAssertTrue(done.waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["register-progress"].label, "Registration Successful\nCheck your email for instruction")
        Shots.save("register-done")
    }

    func testATakenOrBadUsernameShowsRnsReason() {
        let app = launch(["-openScreen", "register"])
        let username = app.textFields["register-username"]
        XCTAssertTrue(username.waitForExistence(timeout: 15))
        username.focusAndType("ab")
        let error = app.staticTexts["register-username-error"]
        XCTAssertTrue(error.waitForExistence(timeout: 10))
        XCTAssertEqual(error.label, "Length should be between 3 to 16 symbols")
        username.focusAndType("c-_")
        expectation(for: NSPredicate(format: "label == 'Should not contain underscore'"), evaluatedWith: error)
        waitForExpectations(timeout: 10)
        XCTAssertFalse(app.buttons["register-continue"].isEnabled)
    }

    func testReferShowsPointsRowsAndTheShareButton() {
        let app = launch(["-seedAccounts", "YES", "-openScreen", "refer"])
        // each total is one stop: its name as the label, the number as the value
        let earned = app.descendants(matching: .any)["refer-earned"]
        XCTAssertTrue(earned.waitForExistence(timeout: 15))
        expectation(for: NSPredicate(format: "value == '100'"), evaluatedWith: earned)
        waitForExpectations(timeout: 10)
        XCTAssertEqual(app.descendants(matching: .any)["refer-pending"].value as? String, "200")
        XCTAssertTrue(app.buttons["referral-spike-carol"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["referral-delegate-spike-dave"].exists)
        XCTAssertTrue(app.buttons["refer-share"].isEnabled)
        // the label then the share icon (hidden from VoiceOver); the centring is drawn in LayoutPolishTests
        XCTAssertEqual(app.buttons["refer-share"].label, "Refer")
    }
}
