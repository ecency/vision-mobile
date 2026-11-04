import XCTest

/// Listen above the post body against the debug demo answers (nothing is charged, no audio is fetched).
final class ListenUITests: XCTestCase {
    private func launch(_ post: String, signedIn: Bool) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-openPost", "spike-alice/\(post)"] + (signedIn ? ["-seedAccounts", "YES"] : [])
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    func testAVisitorIsAskedToLogIn() {
        let app = launch("fake-post-3", signedIn: false)
        XCTAssertTrue(app.buttons["listen-login"].waitForExistence(timeout: 20))
        XCTAssertEqual(element(app, "listen-subtitle").label, "English + German · ~3 min")
        app.buttons["listen-login"].tap()
        XCTAssertTrue(element(app, "login-username").waitForExistence(timeout: 10))
    }

    func testTheUnlockAsksFirstThenShowsThePlaceInLine() {
        let app = launch("fake-post-3", signedIn: true)
        let unlock = app.buttons["listen-unlock"]
        XCTAssertTrue(unlock.waitForExistence(timeout: 20))
        unlock.tap()
        XCTAssertTrue(app.alerts["Listen to this post"].waitForExistence(timeout: 5))
        app.alerts.buttons["Cancel"].firstMatch.tap()
        XCTAssertTrue(unlock.waitForExistence(timeout: 5), "cancelled: still locked")
        unlock.tap()
        app.alerts.buttons["Confirm"].firstMatch.tap()
        XCTAssertTrue(element(app, "listen-preparing").waitForExistence(timeout: 10))
    }

    func testAnUnlockedPostShowsThePlayer() {
        let app = launch("fake-post-1", signedIn: true)
        XCTAssertTrue(app.buttons["listen-play"].waitForExistence(timeout: 20))
        XCTAssertEqual(app.buttons["listen-play"].label, "Play")
        XCTAssertEqual(element(app, "listen-duration").label, "2:48")
        let speed = app.buttons["listen-speed"]
        speed.tap()
        XCTAssertEqual(speed.value as? String, "1.25x")
        XCTAssertTrue(app.buttons["listen-lang-de"].exists)
    }
}
