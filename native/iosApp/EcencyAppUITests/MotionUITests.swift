import XCTest

/// Page transitions keep the system push and the edge swipe back, also on screens with their own header (the
/// navigation bar hidden), at full motion and under reduce motion.
final class MotionUITests: XCTestCase {
    private func launch(_ extra: [String], signedIn: Bool = true) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES"] + (signedIn ? ["-seedAccounts", "YES"] : ["-homeFilter", "trending"]) + extra
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any)[id].firstMatch
    }

    /// The system's edge swipe: from the left edge across most of the screen.
    private func swipeBack(_ app: XCUIApplication) {
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.5))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5))
        start.press(forDuration: 0.05, thenDragTo: end)
    }

    func testSwipeBackLeavesAChatThreadWithItsOwnHeader() {
        let app = launch(["-startTab", "CHATS"])
        let row = element(app, "chat-row-ch-ecency")
        XCTAssertTrue(row.waitForExistence(timeout: 20))
        row.tap()
        XCTAssertTrue(element(app, "chat-thread").waitForExistence(timeout: 10))
        swipeBack(app)
        XCTAssertTrue(row.waitForExistence(timeout: 10), "back on the chat list")
        XCTAssertFalse(element(app, "chat-thread").exists)
    }

    /// The editor has its own guarded back (it asks before dropping changes): the edge swipe never leaves it.
    func testTheEditorIsNeverSwipedAway() {
        let app = launch(["-openScreen", "editor"])
        let body = app.textViews["editor-body"]
        XCTAssertTrue(body.waitForExistence(timeout: 20), app.debugDescription)
        body.focusAndType("Unsaved words")
        swipeBack(app)
        sleep(1)
        XCTAssertTrue(body.exists, "still in the editor")
        XCTAssertTrue(element(app, "editor-title").exists)
        XCTAssertFalse(element(app, "tab-bar").isHittable, "the tabs did not come back")
    }

    func testAPostOpensAndSwipesBackAtFullMotion() { openAndSwipeBack([]) }

    func testAPostOpensAndSwipesBackUnderReduceMotion() { openAndSwipeBack(["-reduceMotion", "YES"]) }

    private func openAndSwipeBack(_ extra: [String]) {
        // signed out: no onboarding card loading above the feed and moving the cards while the test taps
        let app = launch(extra, signedIn: false)
        let card = app.buttons.matching(identifier: "post-card").firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 20))
        card.tap()
        if !element(app, "post-views").waitForExistence(timeout: 8) { card.tap() } // the feed was still settling
        XCTAssertTrue(element(app, "post-views").waitForExistence(timeout: 15), app.debugDescription)
        swipeBack(app)
        XCTAssertTrue(card.waitForExistence(timeout: 10), "back on the feed")
        XCTAssertTrue(element(app, "tab-bar").exists)
    }
}
