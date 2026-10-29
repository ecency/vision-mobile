import XCTest

/// The PIN lock against the debug fake transport, with `-lockDemo YES`: a signed-in throwaway account,
/// PIN 1234, lock on. Nothing reaches a real node or a real account.
final class LockUITests: XCTestCase {
    private func launch(keepStore: Bool = false, lockTimeoutSeconds: Int? = nil, extra: [String] = []) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-lockDemo", "YES", "-homeFilter", "trending"]
        if keepStore { app.launchArguments += ["-keepFakeStore", "YES"] }
        if let lockTimeoutSeconds { app.launchArguments += ["-lockTimeoutSeconds", "\(lockTimeoutSeconds)"] }
        app.launchArguments += extra
        app.launch()
        return app
    }

    private func type(_ app: XCUIApplication, _ pin: String) {
        for digit in pin { app.buttons["pin-key-\(digit)"].tap() }
        // wait until the pad takes input again (the check runs off the main thread)
        // a count query never fails on an element that just went away (unlocked)
        let busy = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == 'pin-dots' AND NOT (label BEGINSWITH '0 of')"))
        let deadline = Date().addingTimeInterval(15)
        while busy.count > 0 && Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
    }

    /// Side menu, then Settings, where the RN app keeps the PIN settings.
    private func openSettings(_ app: XCUIApplication, _ menu: XCUIElement) {
        menu.tap()
        let settings = app.buttons["menu-settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 5))
        settings.tap()
        XCTAssertTrue(app.switches["menu-pin-lock"].waitForExistence(timeout: 5))
    }

    private func message(_ app: XCUIApplication) -> String { app.staticTexts["pin-message"].label }

    private func card(_ app: XCUIApplication) -> XCUIElement { app.buttons.matching(identifier: "post-card").firstMatch }

    private func waitForMessage(_ app: XCUIApplication, containing text: String) {
        let predicate = NSPredicate(format: "label CONTAINS %@", text)
        expectation(for: predicate, evaluatedWith: app.staticTexts["pin-message"])
        waitForExpectations(timeout: 15)
    }

    func testLockedAtLaunchAndTheRightPinShowsTheFeed() {
        let app = launch()
        XCTAssertTrue(app.staticTexts["pin-message"].waitForExistence(timeout: 15))
        XCTAssertEqual(message(app), "Enter PIN to unlock")
        XCTAssertEqual(app.staticTexts["pin-username"].label, "@spike-alice")
        XCTAssertFalse(app.buttons["pin-close"].exists, "the lock screen cannot be closed")
        XCTAssertFalse(card(app).exists, "nothing private behind the lock")
        type(app, "1234")
        XCTAssertTrue(card(app).waitForExistence(timeout: 15))
        XCTAssertFalse(app.staticTexts["pin-message"].exists)
    }

    func testTwoWrongPinsWarnAndTheThirdWipesAndSignsOut() {
        let app = launch()
        XCTAssertTrue(app.staticTexts["pin-message"].waitForExistence(timeout: 15))
        type(app, "0000")
        waitForMessage(app, containing: "1 failed attempt")
        XCTAssertFalse(message(app).contains("wiped"))
        XCTAssertEqual(app.staticTexts["pin-error"].label, "Invalid PIN code, please check and try again.")
        type(app, "1111")
        waitForMessage(app, containing: "2 failed attempts")
        XCTAssertTrue(message(app).contains("User data will be wiped on next failed attempt"))
        type(app, "2222")
        let notice = app.alerts.staticTexts["All accounts, keys and credentials were removed from this device."]
        XCTAssertTrue(notice.waitForExistence(timeout: 15))
        app.alerts.buttons["OK"].firstMatch.tap()
        let menu = app.buttons["account-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 15))
        XCTAssertEqual(menu.label, "Account, signed out")
        XCTAssertTrue(card(app).waitForExistence(timeout: 15))
    }

    func testTheAttemptCountSurvivesARelaunch() {
        var app = launch()
        XCTAssertTrue(app.staticTexts["pin-message"].waitForExistence(timeout: 15))
        type(app, "0000")
        type(app, "0000")
        waitForMessage(app, containing: "2 failed attempts")
        app.terminate()
        app = launch(keepStore: true)
        XCTAssertTrue(app.staticTexts["pin-message"].waitForExistence(timeout: 15))
        waitForMessage(app, containing: "2 failed attempts")
        XCTAssertTrue(message(app).contains("User data will be wiped"))
        type(app, "9999")
        XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 15))
        app.alerts.buttons["OK"].firstMatch.tap()
        XCTAssertEqual(app.buttons["account-menu"].label, "Account, signed out")
    }

    func testForgotPinClearsEverythingAfterConfirming() {
        let app = launch()
        let forgot = app.buttons["pin-forgot"]
        XCTAssertTrue(forgot.waitForExistence(timeout: 15))
        forgot.tap()
        XCTAssertTrue(app.alerts.staticTexts["Are you sure you want to clear all user data?"].waitForExistence(timeout: 5))
        app.alerts.buttons["Clear"].firstMatch.tap()
        XCTAssertTrue(app.alerts.staticTexts["All accounts, keys and credentials were removed from this device."].waitForExistence(timeout: 15))
        app.alerts.buttons["OK"].firstMatch.tap()
        XCTAssertEqual(app.buttons["account-menu"].label, "Account, signed out")
    }

    func testChangePinFromTheAccountMenu() {
        let app = launch()
        XCTAssertTrue(app.staticTexts["pin-message"].waitForExistence(timeout: 15))
        type(app, "1234")
        let menu = app.buttons["account-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 15))
        openSettings(app, menu)
        app.buttons["menu-change-pin"].tap()
        XCTAssertTrue(app.buttons["pin-close"].waitForExistence(timeout: 10))
        type(app, "1234")
        waitForMessage(app, containing: "Set new PIN")
        type(app, "5678")
        waitForMessage(app, containing: "Write again")
        type(app, "5678")
        XCTAssertTrue(app.alerts.staticTexts["PIN changed."].waitForExistence(timeout: 15))
        app.alerts.buttons["OK"].firstMatch.tap()
        // the new PIN is the one that unlocks after a relaunch
        app.terminate()
        let again = launch(keepStore: true)
        XCTAssertTrue(again.staticTexts["pin-message"].waitForExistence(timeout: 15))
        type(again, "5678")
        // unlocked, back on the screen it was on (Settings, restored as Android restores after process death)
        XCTAssertTrue(again.navigationBars["Settings"].waitForExistence(timeout: 15))
    }

    /// With biometrics set up, turning the lock on offers them first; the PIN set next backs them.
    func testTurningTheLockOnOffersBiometricsFirst() {
        let app = launch(extra: ["-demoBiometrics", "YES"])
        XCTAssertTrue(app.staticTexts["pin-message"].waitForExistence(timeout: 15))
        type(app, "1234")
        let menu = app.buttons["account-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 15))
        openSettings(app, menu)
        app.switches["menu-pin-lock"].tap()
        type(app, "1234")
        XCTAssertTrue(app.alerts.staticTexts["PIN lock is off."].waitForExistence(timeout: 15))
        app.alerts.buttons["OK"].firstMatch.tap()
        app.switches["menu-pin-lock"].tap()
        let use = app.buttons["biometric-offer-use"]
        XCTAssertTrue(use.waitForExistence(timeout: 10), "the offer comes first")
        XCTAssertTrue(app.buttons["biometric-offer-pin"].exists, "Use PIN instead")
        XCTAssertFalse(app.staticTexts["pin-message"].exists)
        use.tap()
        waitForMessage(app, containing: "back up biometric unlock")
        type(app, "4321")
        waitForMessage(app, containing: "Write again")
        type(app, "4321")
        XCTAssertTrue(app.alerts.staticTexts["PIN lock is on. Unlock with biometrics, or with your PIN."].waitForExistence(timeout: 15))
        app.alerts.buttons["OK"].firstMatch.tap()
        XCTAssertEqual(app.switches["menu-biometric"].value as? String, "On", "biometric unlock is on")
    }

    func testTurningTheLockOffNeedsThePin() {
        let app = launch()
        XCTAssertTrue(app.staticTexts["pin-message"].waitForExistence(timeout: 15))
        type(app, "1234")
        let menu = app.buttons["account-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 15))
        openSettings(app, menu)
        app.switches["menu-pin-lock"].tap()
        XCTAssertTrue(app.buttons["pin-close"].waitForExistence(timeout: 10))
        type(app, "1234")
        XCTAssertTrue(app.alerts.staticTexts["PIN lock is off."].waitForExistence(timeout: 15))
        app.alerts.buttons["OK"].firstMatch.tap()
        app.terminate()
        let again = launch(keepStore: true)
        XCTAssertTrue(again.navigationBars["Settings"].waitForExistence(timeout: 15), "no lock after it was turned off")
        XCTAssertFalse(again.staticTexts["pin-message"].exists)
    }

    /// A sheet (the reply composer) is open when the app goes away: the lock comes back above it.
    func testARelockCoversAnOpenSheet() {
        let app = launch(lockTimeoutSeconds: 0)
        XCTAssertTrue(app.staticTexts["pin-message"].waitForExistence(timeout: 15))
        type(app, "1234")
        let first = card(app)
        XCTAssertTrue(first.waitForExistence(timeout: 15))
        first.tap()
        XCTAssertTrue(app.staticTexts["post-title"].waitForExistence(timeout: 10))
        // the bar's comments icon takes the reader to the comments, whose header holds the Reply to the post
        app.buttons["post-comments"].tap()
        let reply = app.buttons["comments-reply"]
        XCTAssertTrue(reply.waitForExistence(timeout: 10))
        for _ in 0..<10 where !reply.isHittable { RunLoop.current.run(until: Date().addingTimeInterval(0.3)) }
        reply.tap()
        XCTAssertTrue(app.descendants(matching: .any)["reply-text"].waitForExistence(timeout: 10))
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(app.buttons["pin-key-1"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["pin-key-1"].isHittable, "the PIN pad must sit above the sheet")
        type(app, "1234")
        XCTAssertTrue(app.staticTexts["post-title"].waitForExistence(timeout: 15) || card(app).waitForExistence(timeout: 5))
    }
}
