import XCTest

/// The parity gaps wave (gaps2) on the debug fake data: payout popover, post footer, view count.
final class Gaps2UITests: XCTestCase {
    private func launch(_ extra: [String] = []) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-homeFilter", "trending"] + extra
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    func testTappingAPayoutShowsTheBreakdownWithoutSigningIn() {
        let app = launch()
        XCTAssertTrue(element(app, "payout").waitForExistence(timeout: 15))
        // the first payout on screen (cards further down are laid out below the fold)
        let all = app.descendants(matching: .any).matching(identifier: "payout")
        var payout = all.firstMatch
        for i in 0..<all.count where all.element(boundBy: i).isHittable {
            payout = all.element(boundBy: i)
            break
        }
        payout.tap()
        XCTAssertTrue(element(app, "payout-popover").waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(app.alerts["Sign in required"].exists)
    }

    func testThePostShowsItsViewCountAndPostedViaFooter() {
        let app = launch()
        let card = app.buttons["post-card"].firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 15))
        card.tap()
        XCTAssertTrue(element(app, "post-views").waitForExistence(timeout: 15), app.debugDescription)
        let footer = element(app, "posted-by")
        for _ in 0..<8 where !footer.exists { app.swipeUp() }
        XCTAssertTrue(footer.waitForExistence(timeout: 10), app.debugDescription)
        // the fake post is from Ecency: the footer's source badge is the labelled logo mark, an image with no text
        let badge = app.images.matching(identifier: "ecency-source").firstMatch
        XCTAssertTrue(badge.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertEqual(badge.label, "Published with Ecency")
        XCTAssertFalse(app.staticTexts["ecency"].exists, "no text stands in for the mark")
    }

    /// One-off device check (with `TEST_RUNNER_GAPS2_PHOTO_CHECK=1` after PhotoSaveDeviceCheck): the Photos app shows
    /// what was saved; the screenshot is kept in the result bundle.
    func testPhotosShowsTheSavedImages() throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["GAPS2_PHOTO_CHECK"] == "1", "device check only")
        let photos = XCUIApplication(bundleIdentifier: "com.apple.mobileslideshow")
        photos.launch()
        let proceed = photos.buttons["Continue"]
        if proceed.waitForExistence(timeout: 5) { proceed.tap() }
        sleep(3)
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.lifetime = .keepAlways
        add(shot)
    }
}
