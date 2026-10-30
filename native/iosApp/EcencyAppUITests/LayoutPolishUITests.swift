import XCTest

/// The image source chooser, the spin wheel and the side menu header against the debug fake data (nothing is
/// uploaded, spun for real or bought). Saves screens when `TEST_RUNNER_POLISH3_SHOT_DIR` is set.
final class LayoutPolishUITests: XCTestCase {
    private func shot(_ name: String) {
        let env = ProcessInfo.processInfo.environment
        guard let dir = env["POLISH3_SHOT_DIR"], !dir.isEmpty else { return }
        let prefix = env["POLISH3_SHOT_PREFIX"] ?? "after"
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("polish3-ios-\(prefix)-\(name).png"))
    }

    private func launch(_ extra: [String] = []) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-seedAccounts", "YES"] + extra
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    /// Waits until [element] can be tapped and has stopped moving (a sheet or drawer at the end of its slide).
    private func waitSettled(_ element: XCUIElement, file: StaticString = #filePath, line: UInt = #line) {
        var last = CGRect.null
        let settled = NSPredicate { _, _ in
            let now = element.frame
            defer { last = now }
            return element.isHittable && now == last
        }
        let result = XCTWaiter.wait(for: [XCTNSPredicateExpectation(predicate: settled, object: nil)], timeout: 15)
        XCTAssertEqual(result, .completed, "\(element) settled", file: file, line: line)
    }

    private func openChooser(_ app: XCUIApplication) {
        let avatar = app.buttons["edit-avatar"]
        XCTAssertTrue(avatar.waitForExistence(timeout: 30), "profile edit")
        avatar.tap()
        XCTAssertTrue(app.buttons["source-gallery"].waitForExistence(timeout: 10))
        waitSettled(app.buttons["source-gallery"])
    }

    /// The profile image chooser: the four routes as rows, each with what it does, and Cancel closes it.
    func testTheProfileChooserIsASheetOfRows() {
        let app = launch(["-openScreen", "editProfile"])
        openChooser(app)
        shot("chooser-profile")
        for id in ["source-files", "source-link"] { XCTAssertTrue(app.buttons[id].exists, id) }
        XCTAssertEqual(app.buttons["source-files"].label, "Choose from Files")
        XCTAssertTrue(element(app, "media-source-sheet").exists)
        let window = app.windows.firstMatch.frame
        let gallery = app.buttons["source-gallery"].frame
        XCTAssertGreaterThanOrEqual(gallery.height, 44, "a full touch target")
        XCTAssertLessThanOrEqual(gallery.maxX, window.maxX + 0.5)
        app.buttons["media-source-cancel"].tap()
        let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: app.buttons["source-gallery"])
        wait(for: [gone], timeout: 5)
        // the link row still opens the link field once the sheet is gone
        app.buttons["edit-cover"].tap()
        XCTAssertTrue(app.buttons["source-link"].waitForExistence(timeout: 10))
        waitSettled(app.buttons["source-link"])
        app.buttons["source-link"].tap()
        XCTAssertTrue(app.alerts.textFields.firstMatch.waitForExistence(timeout: 10), "the link field")
    }

    func testTheChooserAtTheLargestTextAndInDark() {
        let app = launch(["-openScreen", "editProfile", "-forceDarkTheme", "YES", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"])
        let avatar = app.buttons["edit-avatar"]
        XCTAssertTrue(avatar.waitForExistence(timeout: 30))
        avatar.tap()
        XCTAssertTrue(app.buttons["source-gallery"].waitForExistence(timeout: 10))
        waitSettled(app.buttons["source-gallery"])
        shot("chooser-ax5-dark")
        XCTAssertLessThanOrEqual(app.buttons["source-gallery"].frame.maxX, app.windows.firstMatch.frame.maxX + 0.5)
    }

    func testTheChatAttachmentChooser() {
        let app = launch(["-openScreen", "chat:ch-group:G"])
        let attach = app.buttons["chat-attach"]
        XCTAssertTrue(attach.waitForExistence(timeout: 20))
        attach.tap()
        XCTAssertTrue(app.buttons["chat-attach-library"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["chat-attach-files"].exists)
        waitSettled(app.buttons["chat-attach-files"])
        shot("chooser-chat")
    }

    func testTheEditorUploadsShowTheSourceTiles() {
        let app = launch(["-openScreen", "editor"])
        let tool = app.buttons["tool-image"]
        XCTAssertTrue(tool.waitForExistence(timeout: 20))
        tool.tap()
        XCTAssertTrue(app.buttons["open-gallery"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["open-files"].exists)
        waitSettled(app.buttons["open-files"])
        shot("chooser-editor")
    }

    /// The spin screen at rest and while the rings turn: the button (whose column is the wheel's) stays centred.
    func testTheSpinWheelIsCentred() {
        for (name, extra) in [("spin", [String]()), ("spin-ax5", ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"])] {
            let app = launch(["-openScreen", "perks"] + extra)
            let row = app.buttons["spend-spin"]
            XCTAssertTrue(row.waitForExistence(timeout: 20))
            row.tap()
            let button = app.buttons["spin-button"]
            XCTAssertTrue(button.waitForExistence(timeout: 10))
            let window = app.windows.firstMatch.frame
            XCTAssertEqual(button.frame.midX, window.midX, accuracy: 2, "\(name): the button on the centre line")
            shot(name)
            button.tap()
            expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: button)
            waitForExpectations(timeout: 10)
            shot("\(name)-turning")
            app.terminate()
        }
    }

    func testTheSideMenuHeader() {
        for (name, extra) in [("drawer", [String]()), ("drawer-ax5", ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]),
                              ("drawer-rtl", ["-AppleLanguages", "(ar)", "-AppleLocale", "ar_SA"])] {
            let app = launch(extra)
            let menu = app.buttons["account-menu"]
            XCTAssertTrue(menu.waitForExistence(timeout: 20))
            menu.tap()
            XCTAssertTrue(app.buttons["drawer-accounts"].waitForExistence(timeout: 10))
            waitSettled(app.buttons["drawer-accounts"])
            let drawer = element(app, "drawer").frame
            let accounts = app.buttons["drawer-accounts"].frame
            XCTAssertTrue(drawer.insetBy(dx: -0.5, dy: -0.5).contains(accounts), "\(name): the header's controls inside the drawer")
            shot(name)
            app.terminate()
        }
    }
}
