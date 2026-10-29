import XCTest

/// Drives the preview app: the host's stand-in toast shows what the renderer handed to PostActions.
final class RendererDemoUITests: XCTestCase {
    private func toast(_ app: XCUIApplication) -> XCUIElement { app.staticTexts["toast"] }

    func testParagraphLongPressHandsOverPlainText() {
        let app = XCUIApplication()
        app.launchArguments += ["-rendererDemo", "YES", "-demoToastSeconds", "60"]
        app.launch()
        let paragraph = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH 'Hi @ecency'")).firstMatch
        XCTAssertTrue(paragraph.waitForExistence(timeout: 15))
        paragraph.press(forDuration: 1.0)
        XCTAssertTrue(toast(app).waitForExistence(timeout: 5))
        XCTAssertTrue(toast(app).label.hasPrefix("Hi @ecency, this is bold"), toast(app).label)
    }

    func testImageTapOpensTheGalleryAtThatImage() {
        let app = XCUIApplication()
        app.launchArguments += ["-rendererDemo", "YES", "-demoToastSeconds", "60"]
        app.launch()
        let image = app.descendants(matching: .any).matching(identifier: "image").firstMatch
        XCTAssertTrue(image.waitForExistence(timeout: 15))
        image.tap()
        XCTAssertTrue(toast(app).waitForExistence(timeout: 5))
        XCTAssertEqual(toast(app).label, "Image 1 of 2")
    }

    func testHiddenImageRevealsOnTapWithoutOpeningTheGallery() {
        let app = XCUIApplication()
        app.launchArguments += ["-rendererDemo", "YES", "-demoToastSeconds", "60"]
        app.launchArguments += ["-showImages", "NO"]
        app.launch()
        let hidden = app.buttons["image-hidden"].firstMatch
        XCTAssertTrue(hidden.waitForExistence(timeout: 15))
        hidden.tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "image").firstMatch.waitForExistence(timeout: 10))
        XCTAssertFalse(toast(app).exists)
    }

    func testSpeakPlayerTogglesAndShowsItsDuration() {
        let app = XCUIApplication()
        app.launchArguments += ["-rendererDemo", "YES", "-demoToastSeconds", "60"]
        app.launch()
        let play = app.buttons["speak:play"]
        let post = app.scrollViews["post"]
        XCTAssertTrue(post.waitForExistence(timeout: 15))
        for _ in 0..<20 where !(play.exists && play.isHittable) { post.swipeUp() }
        XCTAssertTrue(play.isHittable)
        XCTAssertEqual(play.label, "Play voice")
        XCTAssertEqual(app.staticTexts["speak:time"].label, "1:23")
        play.tap()
        XCTAssertEqual(app.buttons["speak:play"].label, "Pause voice")
        app.buttons["speak:play"].tap()
        XCTAssertEqual(app.buttons["speak:play"].label, "Play voice")
    }
}
