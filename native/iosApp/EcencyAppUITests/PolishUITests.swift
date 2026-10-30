import XCTest

/// The owner's polish list (README "Polish"): layout checks, and screenshots when POLISH_SHOT_DIR is set
/// (`TEST_RUNNER_POLISH_SHOT_DIR=<dir>` in the environment of xcodebuild).
final class PolishUITests: XCTestCase {
    private func launch(_ extra: [String]) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES"] + extra
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    private func shot(_ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["POLISH_SHOT_DIR"], !dir.isEmpty else { return }
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("polish-ios-\(name).png"))
    }

    /// Waves quick submit: the toolbar sits on the keyboard (or the sheet's bottom edge without one) and the text
    /// fills the space down to it, with no gap between the editing area and the toolbar.
    func testWaveComposerToolbarSitsOnTheKeyboardAndTheTextFillsToIt() {
        let app = launch(["-seedAccounts", "YES", "-startTab", "WAVES"])
        XCTAssertTrue(app.buttons["wave-fab"].waitForExistence(timeout: 20))
        app.buttons["wave-fab"].tap()
        let text = element(app, "wave-text")
        XCTAssertTrue(text.waitForExistence(timeout: 10))
        text.focusAndType("Polish check")
        let publish = app.buttons["wave-publish"]
        XCTAssertTrue(publish.waitForExistence(timeout: 5))
        // let the keyboard and the sheet settle
        RunLoop.current.run(until: Date().addingTimeInterval(1.5))
        shot("wave-composer-keyboard")
        let window = app.windows.firstMatch.frame
        XCTAssertGreaterThanOrEqual(element(app, "wave-close").frame.maxX, 0)
        XCTAssertLessThanOrEqual(publish.frame.maxX, window.maxX, "the toolbar fits the screen")
        XCTAssertGreaterThanOrEqual(app.buttons["wave-attach"].frame.minX, window.minX + 8, "the sheet keeps its side margin")
        let toolbarTop = app.buttons["wave-attach"].frame.minY
        // the text (or the short-content hint under it) runs down to the toolbar
        let hint = element(app, "wave-short-hint")
        let above = hint.exists ? hint.frame.maxY : text.frame.maxY
        let gap = toolbarTop - above
        XCTAssertLessThan(gap, 24, "the text fills down to the toolbar (gap \(gap))")
        if hint.exists { XCTAssertLessThan(hint.frame.minY - text.frame.maxY, 24, "the hint follows the text") }
        let keyboard = app.keyboards.firstMatch
        if keyboard.exists && keyboard.frame.height > 0 {
            let lift = keyboard.frame.minY - publish.frame.maxY
            // the keyboard element starts below its suggestion bar; before the fix the gap was about 130 pt
            XCTAssertLessThan(lift, 60, "the toolbar sits on the keyboard (\(lift) pt above it)")
            XCTAssertGreaterThanOrEqual(lift, 0, "the toolbar is not under the keyboard")
        } else {
            // a hardware keyboard (no software one): the toolbar is on the sheet's bottom edge
            XCTAssertGreaterThan(publish.frame.maxY, window.maxY - 90, "the toolbar is at the bottom")
        }
    }

    /// Screenshots of every fixed screen (only with POLISH_SHOT_DIR; the layout checks live in their own classes).
    func testScreenshotsOfThePolishedScreens() throws {
        guard let dir = ProcessInfo.processInfo.environment["POLISH_SHOT_DIR"], !dir.isEmpty else { throw XCTSkip("no POLISH_SHOT_DIR") }
        // 1. toasts, one line and several
        var app = launch(["-demoToast", "Tag exists already"])
        XCTAssertTrue(app.staticTexts["toast"].waitForExistence(timeout: 15))
        shot("toast-single")
        app.terminate()
        app = launch(["-demoToast", "Your request for deletion is sent. Your account and its data will be removed from Ecency within a few days."])
        XCTAssertTrue(app.staticTexts["toast"].waitForExistence(timeout: 15))
        shot("toast-multiline")
        app.terminate()
        // 2. tags typed into the composer: chips, an empty field
        app = launch(["-seedAccounts", "YES", "-openScreen", "editor"])
        let tags = element(app, "tag-input")
        XCTAssertTrue(tags.waitForExistence(timeout: 20))
        tags.focusAndType("photography travel hive ")
        XCTAssertTrue(element(app, "tag-hive").waitForExistence(timeout: 5))
        shot("editor-tags")
        app.terminate()
        // 3. feed cards and a profile without reputation
        app = launch(["-seedAccounts", "YES", "-homeFilter", "trending"])
        XCTAssertTrue(app.buttons.matching(identifier: "post-card").firstMatch.waitForExistence(timeout: 20))
        shot("feed-no-reputation")
        // 5. the side menu
        app.buttons["account-menu"].tap()
        XCTAssertTrue(app.buttons["menu-logout"].waitForExistence(timeout: 5))
        shot("side-menu")
        app.terminate()
        app = launch(["-seedAccounts", "YES", "-openLink", "https://ecency.com/@spike-bob"])
        XCTAssertTrue(element(app, "profile-username").waitForExistence(timeout: 20))
        shot("profile-no-reputation")
        app.terminate()
        app = launch(["-seedAccounts", "YES", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryXXXL"])
        XCTAssertTrue(app.buttons["account-menu"].waitForExistence(timeout: 20))
        app.buttons["account-menu"].tap()
        XCTAssertTrue(app.buttons["menu-logout"].waitForExistence(timeout: 5))
        shot("side-menu-large-text")
        app.terminate()
        // 4. the biometric offer when the PIN lock is turned on
        app = launch(["-seedAccounts", "YES", "-demoBiometrics", "YES"])
        XCTAssertTrue(app.buttons["account-menu"].waitForExistence(timeout: 20))
        app.buttons["account-menu"].tap()
        app.buttons["menu-settings"].tap()
        XCTAssertTrue(app.switches["menu-pin-lock"].waitForExistence(timeout: 10))
        app.switches["menu-pin-lock"].tap()
        XCTAssertTrue(app.buttons["biometric-offer-use"].waitForExistence(timeout: 10))
        shot("pin-biometric-offer")
        app.buttons["biometric-offer-use"].tap()
        XCTAssertTrue(app.staticTexts["pin-message"].waitForExistence(timeout: 10))
        shot("pin-backup-pin")
        app.terminate()
    }
}
