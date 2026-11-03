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

    /// RN postDisplayView's footer tags: above "Posted by", the community first (pinned) by its title, then the tags;
    /// each pill a button with its label and kind and a 44 point target; a tag opens its results, a community its screen.
    func testThePostFooterShowsItsTagsAndATapOpensTheTagOrTheCommunity() {
        let app = launch(["-openPost", "spike-alice/fake-post-1"])
        XCTAssertTrue(element(app, "post-title").waitForExistence(timeout: 20), app.debugDescription)
        let community = app.buttons["post-tag-hive-100001"]
        let spike = app.buttons["post-tag-spike"]
        let footer = element(app, "posted-by")
        for _ in 0..<8 where !(spike.exists && spike.isHittable && footer.exists) { app.swipeUp() }
        XCTAssertTrue(community.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(spike.exists)
        XCTAssertEqual(community.label, "Spike Community, community")
        XCTAssertEqual(spike.label, "spike, tag")
        XCTAssertLessThan(community.frame.minX, spike.frame.minX, "the community first")
        XCTAssertGreaterThanOrEqual(community.frame.height, 44)
        XCTAssertGreaterThanOrEqual(spike.frame.height, 44)
        XCTAssertGreaterThanOrEqual(spike.frame.width, 44)
        XCTAssertTrue(footer.exists)
        XCTAssertLessThanOrEqual(spike.frame.maxY, footer.frame.minY + 0.5, "above the posted by line")
        spike.tap()
        XCTAssertTrue(app.navigationBars["#spike"].waitForExistence(timeout: 10), app.debugDescription)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(community.waitForExistence(timeout: 10))
        community.tap()
        XCTAssertTrue(element(app, "community-details").waitForExistence(timeout: 15), app.debugDescription)
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
