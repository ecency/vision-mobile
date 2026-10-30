import XCTest

/// The keys sheet ("Manage Authorities") with `-lockDemo YES`: a throwaway account, PIN 1234, lock on.
final class KeysUITests: XCTestCase {
    private func launch() -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-lockDemo", "YES", "-homeFilter", "trending"]
        app.launch()
        return app
    }

    private func type(_ app: XCUIApplication, _ pin: String) {
        for digit in pin { app.buttons["pin-key-\(digit)"].tap() }
        let busy = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier == 'pin-dots' AND NOT (label BEGINSWITH '0 of')"))
        let deadline = Date().addingTimeInterval(15)
        while busy.count > 0 && Date() < deadline { RunLoop.current.run(until: Date().addingTimeInterval(0.2)) }
    }

    /// Unlocks, then opens the keys sheet from Settings; the PIN pad for the keys is up when this returns.
    private func openKeys(_ app: XCUIApplication) {
        XCTAssertTrue(app.staticTexts["pin-message"].waitForExistence(timeout: 15))
        type(app, "1234")
        let menu = app.buttons["account-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 15))
        menu.tap()
        app.buttons["menu-settings"].tap()
        let keys = app.buttons["menu-backup-keys"]
        // RN keeps it near the end of Settings: scroll to it
        XCTAssertTrue(app.buttons["settings-currency"].waitForExistence(timeout: 5))
        for _ in 0..<15 where !(keys.exists && keys.isHittable) { app.swipeUp() }
        XCTAssertTrue(keys.waitForExistence(timeout: 5))
        keys.tap()
        XCTAssertTrue(app.staticTexts["pin-message"].waitForExistence(timeout: 10), "the PIN is asked again before any key shows")
        Shots.save("keys-pin")
    }

    private func label(_ app: XCUIApplication, _ role: String) -> XCUIElement { app.staticTexts["key-label-\(role)"] }

    func testKeysShowOnlyAfterThePinAndRevealTogglesPrivate() {
        let app = launch()
        openKeys(app)
        XCTAssertFalse(label(app, "POSTING").exists, "nothing of the keys before the PIN")
        type(app, "1234")
        XCTAssertTrue(label(app, "POSTING").waitForExistence(timeout: 10))
        XCTAssertEqual(label(app, "POSTING").label, "Posting Key (Public)")
        XCTAssertEqual(app.buttons["key-button-POSTING"].label, "Reveal Private")
        XCTAssertEqual(app.buttons["key-button-ACTIVE"].label, "Import Private")
        XCTAssertFalse(app.buttons["key-button-OWNER"].exists, "the owner row has no import")
        XCTAssertEqual(app.staticTexts["key-note-OWNER"].label, "Public only. Ecency never stores the owner key.")
        // public keys only: no private key is ever in a screenshot
        Shots.save("keys")
        app.buttons["key-button-POSTING"].tap()
        let shown = NSPredicate(format: "label == 'Posting Key (Private)'")
        expectation(for: shown, evaluatedWith: label(app, "POSTING"))
        waitForExpectations(timeout: 5)
        XCTAssertEqual(app.buttons["key-button-POSTING"].label, "Reveal Public")
        // the private key is not exposed as accessibility text
        XCTAssertEqual(app.otherElements["key-value-POSTING"].label, "Posting Key, private key shown")
    }

    func testCancellingThePinClosesTheSheet() {
        let app = launch()
        openKeys(app)
        app.buttons["pin-close"].tap()
        XCTAssertTrue(app.buttons["menu-backup-keys"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.navigationBars["Manage Authorities"].exists)
        XCTAssertFalse(label(app, "POSTING").exists)
    }

    func testCopyingAPrivateKeyWarnsFirst() {
        let app = launch()
        openKeys(app)
        type(app, "1234")
        XCTAssertTrue(label(app, "POSTING").waitForExistence(timeout: 10))
        app.buttons["key-button-POSTING"].tap()
        app.buttons["key-copy-POSTING"].tap()
        XCTAssertTrue(app.alerts["Warning"].waitForExistence(timeout: 5))
        app.alerts["Warning"].buttons["Cancel"].tap()
        XCTAssertFalse(app.staticTexts["key-copied"].exists)
    }

    func testTheSheetIsGoneAfterTheAppWasInTheBackground() {
        let app = launch()
        openKeys(app)
        type(app, "1234")
        XCTAssertTrue(label(app, "POSTING").waitForExistence(timeout: 10))
        app.buttons["key-button-POSTING"].tap()
        XCUIDevice.shared.press(.home)
        RunLoop.current.run(until: Date().addingTimeInterval(1))
        app.activate()
        XCTAssertTrue(app.buttons["menu-backup-keys"].waitForExistence(timeout: 10), "back on Settings, under the 60 s relock")
        XCTAssertFalse(label(app, "POSTING").exists)
    }

    /// `-keysNoShield YES`: the build behaves as on an iOS without the secure canvas.
    func testWithoutTheShieldAKeyOnlyShowsWhileHeld() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-lockDemo", "YES", "-homeFilter", "trending", "-keysNoShield", "YES"]
        app.launch()
        openKeys(app)
        type(app, "1234")
        XCTAssertTrue(label(app, "POSTING").waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["keys-hold-hint"].exists)
        // revealing changes what a hold would show, not what sits on screen: the box stays a placeholder,
        // and accessibility is told only that a private key is there
        app.buttons["key-button-POSTING"].tap()
        expectation(for: NSPredicate(format: "label == 'Posting Key (Private)'"), evaluatedWith: label(app, "POSTING"))
        waitForExpectations(timeout: 5)
        XCTAssertEqual(app.otherElements["key-value-POSTING"].label, "Posting Key, private key shown")
        Shots.save("keys-hold-to-show")
    }
}
