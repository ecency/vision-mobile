import XCTest

/// RN parity gaps (wave gaps3), from synthetic answers only.
final class Gaps3UITests: XCTestCase {
    private func launch(_ extra: [String]) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-seedAccounts", "YES"] + extra
        app.launch()
        return app
    }

    func testTheRcOfferTopUpOpensThePointsTopUp() {
        let app = launch(["-demoRcOffer", "YES"])
        let title = app.staticTexts["rc-offer-title"]
        XCTAssertTrue(title.waitForExistence(timeout: 20))
        XCTAssertEqual(title.label, "Not enough Resource Credits")
        Shots.save("gaps3-rc-offer")
        app.buttons["rc-offer-topup"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["redeem-next"].waitForExistence(timeout: 20), "the Points RC top-up opened")
        XCTAssertFalse(title.exists)
    }

    func testCancellingTheRcOfferOpensNothing() {
        let app = launch(["-demoRcOffer", "YES"])
        XCTAssertTrue(app.buttons["rc-offer-cancel"].waitForExistence(timeout: 20))
        app.buttons["rc-offer-cancel"].tap()
        let gone = NSPredicate(format: "exists == false")
        expectation(for: gone, evaluatedWith: app.staticTexts["rc-offer-title"])
        waitForExpectations(timeout: 10)
        XCTAssertFalse(app.descendants(matching: .any)["redeem-next"].exists)
    }

    func testTheSpotlightHeadsTheHomeFeedUntilDismissed() {
        let app = launch(["-demoSpotlight", "YES"])
        let title = app.staticTexts["spotlight-title"]
        XCTAssertTrue(title.waitForExistence(timeout: 30))
        XCTAssertEqual(title.label, "Say it in a wave")
        Shots.save("gaps3-spotlight")
        app.buttons["spotlight-dismiss"].tap()
        let gone = NSPredicate(format: "exists == false")
        expectation(for: gone, evaluatedWith: title)
        waitForExpectations(timeout: 10)
    }

    func testTheCustomiseButtonCarriesARedDotUntilItsFirstUse() {
        let app = launch([])
        let button = app.buttons["filter-customise"]
        XCTAssertTrue(button.waitForExistence(timeout: 30))
        XCTAssertEqual(button.value as? String, "New")
        Shots.save("gaps3-walkthrough")
        button.tap()
        // the customise sheet opened; the dot is gone for good
        app.swipeDown(velocity: .fast)
        expectation(for: NSPredicate(format: "value == ''"), evaluatedWith: button)
        waitForExpectations(timeout: 10)
    }

    func testTheWaveComposerOffersDictationAndRnsThreeAiActions() {
        let app = launch(["-startTab", "WAVES"])
        XCTAssertTrue(app.buttons["wave-fab"].waitForExistence(timeout: 30))
        app.buttons["wave-fab"].tap()
        XCTAssertTrue(app.buttons["wave-dictate"].waitForExistence(timeout: 10))
        app.buttons["wave-ai-assist"].tap()
        let improve = app.buttons["ai-action-improve"]
        XCTAssertTrue(improve.waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["ai-action-summarize"].exists)
        XCTAssertTrue(app.buttons["ai-action-check_grammar"].exists)
        XCTAssertFalse(app.buttons["ai-action-generate_title"].exists)
        XCTAssertFalse(app.buttons["ai-action-suggest_tags"].exists)
        Shots.save("gaps3-wave-ai")
    }

    func testTheProfileEditOffersGalleryAndALink() {
        let app = launch(["-openScreen", "editProfile"])
        let avatar = app.buttons["edit-avatar"]
        guard avatar.waitForExistence(timeout: 30) else { return XCTFail("profile edit did not open") }
        avatar.tap()
        XCTAssertTrue(app.buttons["source-gallery"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["source-link"].exists)
        Shots.save("gaps3-profile-sources")
    }

    func testTheReplyEditorsAccountTileOpensTheAccounts() {
        let app = launch(["-openScreen", "editor:reply:spike-alice/fake-post-1"])
        let tile = app.buttons["reply-account-tile"]
        XCTAssertTrue(tile.waitForExistence(timeout: 30))
        Shots.save("gaps3-reply-tile")
        tile.tap()
        XCTAssertTrue(app.descendants(matching: .any)["current-mark"].waitForExistence(timeout: 10), "the accounts sheet")
    }
}
