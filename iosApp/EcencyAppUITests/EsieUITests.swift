import XCTest

/// Esie at her placements on iOS (README "Esie"), against the debug fake data. With `-fakeData` the decorative Esie is
/// exposed as an image (identifier `esie`, value = mood) so these tests can find her.
final class EsieUITests: XCTestCase {
    /// Saves the screen when the run asks for it (`TEST_RUNNER_ESIE_SHOT_DIR=<folder>` in xcodebuild's environment).
    private func shot(_ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["ESIE_SHOT_DIR"], !dir.isEmpty else { return }
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("esie-app-ios-\(name).png"))
    }

    private func launch(_ extra: [String]) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES"] + extra
        app.launch()
        return app
    }

    func testTheGalleryShowsEveryMood() {
        let app = launch(["-esieGallery", "YES"])
        let esie = app.images.matching(identifier: "esie")
        XCTAssertTrue(esie.firstMatch.waitForExistence(timeout: 15))
        let moods = Set((0..<esie.count).map { esie.element(boundBy: $0).value as? String ?? "" })
        XCTAssertEqual(moods, ["rest", "thumbs up", "wave", "happy", "sad", "thinking", "surprised"])
        shot("gallery")
    }

    func testTheReduceMotionFlagStillsEveryEsie() {
        let app = launch(["-esieGallery", "YES", "-reduceMotion", "YES"])
        let esie = app.images.matching(identifier: "esie")
        XCTAssertTrue(esie.firstMatch.waitForExistence(timeout: 15))
        let values = (0..<esie.count).map { esie.element(boundBy: $0).value as? String ?? "" }
        XCTAssertEqual(values.count, 7)
        XCTAssertTrue(values.allSatisfy { $0.hasSuffix(", still") }, "\(values)")
    }

    func testWelcomeWaves() {
        let app = launch(["-showWelcome", "YES"])
        let esie = app.images["esie"]
        XCTAssertTrue(esie.waitForExistence(timeout: 15))
        XCTAssertEqual(esie.value as? String, "wave")
        XCTAssertTrue(app.buttons["welcome-start"].isHittable, "the start button stays on screen")
        let title = app.staticTexts["Ecency"].firstMatch
        XCTAssertLessThanOrEqual(title.frame.maxX, esie.frame.minX, "Esie clear of the title")
        shot("welcome")
    }

    func testWelcomeLeavesEsieOutAtAccessibilityTextSizes() {
        let app = launch(["-showWelcome", "YES", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXL"])
        XCTAssertTrue(app.buttons["welcome-start"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.images["esie"].exists)
    }

    func testLoginWaves() {
        let app = launch(["-openScreen", "login"])
        XCTAssertTrue(app.textFields["login-username"].waitForExistence(timeout: 15))
        let esie = app.images["esie"]
        XCTAssertTrue(esie.exists)
        XCTAssertEqual(esie.value as? String, "wave")
        XCTAssertTrue(app.buttons["login-submit"].isHittable, "the form stays on screen")
        shot("login")
    }

    func testAProfileThatFailsToLoadIsSad() {
        let app = launch(["-openLink", "https://ecency.com/@zz-nobody-x"])
        let esie = app.images["esie"]
        XCTAssertTrue(esie.waitForExistence(timeout: 15))
        XCTAssertEqual(esie.value as? String, "sad")
        shot("error")
    }

    func testAWonSpinIsHappy() {
        let app = launch(["-seedAccounts", "YES", "-openScreen", "perks"])
        let spinRow = app.buttons["spend-spin"]
        XCTAssertTrue(spinRow.waitForExistence(timeout: 15))
        spinRow.tap()
        let button = app.buttons["spin-button"]
        XCTAssertTrue(button.waitForExistence(timeout: 10))
        XCTAssertFalse(app.images["esie"].exists, "Esie cheers only a result")
        button.tap()
        XCTAssertTrue(app.staticTexts["spin-timer"].waitForExistence(timeout: 20))
        let esie = app.images["esie"]
        XCTAssertTrue(esie.waitForExistence(timeout: 5))
        XCTAssertEqual(esie.value as? String, "happy")
        XCTAssertTrue(app.staticTexts["spin-timer"].isHittable, "the countdown stays on screen")
        XCTAssertTrue(app.descendants(matching: .any)["spin-product"].firstMatch.isHittable, "the 5 spins pack stays on screen")
        shot("success")
    }
}
