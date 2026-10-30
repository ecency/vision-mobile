import XCTest

/// The Waves tab against the debug fake transport and fake REST answers (no network).
final class WavesUITests: XCTestCase {
    private func launch(_ extra: [String] = []) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-startTab", "WAVES"] + extra
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    func testTheTabListsWavesAndTheReplyCountOpensTheThread() {
        let app = launch()
        XCTAssertTrue(element(app, "wave-spike-carol-wave-one").waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["waves-tab-FOR YOU"].exists)
        XCTAssertTrue(app.buttons["waves-tab-FOLLOWING"].exists)
        XCTAssertFalse(element(app, "coming-soon-title").exists)
        app.buttons["wave-thread"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars.firstMatch.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["wave-fab"].exists)
    }

    func testSignedOutFollowingAndComposeAskToSignIn() {
        let app = launch()
        XCTAssertTrue(app.buttons["waves-tab-FOLLOWING"].waitForExistence(timeout: 20))
        app.buttons["waves-tab-FOLLOWING"].tap()
        XCTAssertTrue(element(app, "login-username").waitForExistence(timeout: 10))
        app.buttons["Cancel"].firstMatch.tap()
        app.buttons["wave-fab"].tap()
        XCTAssertTrue(element(app, "login-username").waitForExistence(timeout: 10))
        XCTAssertFalse(element(app, "wave-text").exists)
    }

    func testComposerValidatesAndKeepsTheDraftWhenPublishingFails() {
        let app = launch(["-seedAccounts", "YES"])
        XCTAssertTrue(app.buttons["wave-fab"].waitForExistence(timeout: 20))
        app.buttons["waves-tab-FOLLOWING"].tap()
        XCTAssertTrue(element(app, "wave-spike-bob-wave-following").waitForExistence(timeout: 10))
        app.buttons["waves-tab-FOR YOU"].tap()
        app.buttons["wave-fab"].tap()
        let text = element(app, "wave-text")
        XCTAssertTrue(text.waitForExistence(timeout: 10))
        text.focusAndType("Hi")
        XCTAssertTrue(element(app, "wave-short-hint").waitForExistence(timeout: 5))
        text.focusAndType(" there, this wave is long enough to earn points")
        // the hint follows the text as it is typed: wait for the last keystroke to land
        let noHint = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: element(app, "wave-short-hint"))
        wait(for: [noHint], timeout: 10)
        XCTAssertTrue(app.buttons["wave-publish"].isEnabled)
        app.buttons["wave-publish"].tap()
        // the fake node takes no broadcasts: RN's failure alert shows and the draft stays in the sheet
        // (a publish that goes through is covered by the shared tests, which run on iOS too)
        XCTAssertTrue(app.alerts["Something went wrong"].waitForExistence(timeout: 20))
        app.alerts.buttons["OK"].tap()
        XCTAssertTrue(text.exists)
        XCTAssertTrue((text.value as? String ?? "").contains("long enough to earn points"))
        XCTAssertFalse(element(app, "wave-local").exists)
    }

    func testTheComposersVideoButtonOpensTheShortUploader() {
        let app = launch(["-seedAccounts", "YES"])
        XCTAssertTrue(app.buttons["wave-fab"].waitForExistence(timeout: 20))
        app.buttons["wave-fab"].tap()
        XCTAssertTrue(element(app, "wave-text").waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["wave-video-button"].isEnabled)
        app.buttons["wave-video-button"].tap()
        // RN: "Record Video" and "Choose from Library"
        XCTAssertTrue(app.buttons["video-choose"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["video-record"].exists)
        app.buttons["video-close"].tap()
        XCTAssertTrue(element(app, "wave-text").waitForExistence(timeout: 10))
        XCTAssertFalse(element(app, "wave-video").exists)
    }

    func testPinnedShortsTabPlaysOnlyTheReelInView() {
        let app = launch()
        XCTAssertTrue(app.buttons["waves-tab-customise"].waitForExistence(timeout: 20))
        app.buttons["waves-tab-customise"].tap()
        XCTAssertTrue(app.buttons["waves-source-shorts"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["waves-trending-hive"].waitForExistence(timeout: 10))
        app.buttons["waves-source-shorts"].tap()
        app.buttons["waves-picker-done"].tap()
        XCTAssertTrue(app.buttons["waves-tab-SHORTS"].waitForExistence(timeout: 10))
        app.buttons["waves-tab-SHORTS"].tap()
        XCTAssertTrue(element(app, "reel-spike-carol-clip-one").waitForExistence(timeout: 15))
        XCTAssertTrue(element(app, "reel-player").waitForExistence(timeout: 10))
        XCTAssertEqual(app.descendants(matching: .any).matching(identifier: "reel-player").count, 1)
        XCTAssertFalse(app.buttons["wave-fab"].exists, "no compose button over the reels")
        // another waves tab: the reels and their player are gone
        app.buttons["waves-tab-FOR YOU"].tap()
        XCTAssertTrue(element(app, "wave-spike-carol-wave-one").waitForExistence(timeout: 10))
        XCTAssertFalse(element(app, "reel-player").exists)
    }
}
