import XCTest

/// #3699: text of a post or comment body selects; the edit menu offers Copy, Quote, Translate and Share, and the
/// post menu's Select text shows the whole body for selections across paragraphs. Fake data, signed in.
final class SelectTextUITests: XCTestCase {
    private func launch(signedIn: Bool = true) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES"] + (signedIn ? ["-seedAccounts", "YES"] : [])
            + ["-openLink", "https://ecency.com/@spike-alice/fake-post-1"]
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, labelPrefix: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", labelPrefix)).firstMatch
    }

    /// An edit menu item by its title (iOS lists them as menu items).
    private func menuItem(_ app: XCUIApplication, _ title: String) -> XCUIElement {
        app.menuItems[title].firstMatch
    }

    /// A long press on the first word of [paragraph]: the system selects that word and shows the edit menu.
    private func selectFirstWord(_ app: XCUIApplication, _ paragraph: XCUIElement) {
        XCTAssertTrue(paragraph.waitForExistence(timeout: 15), app.debugDescription)
        paragraph.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0)).withOffset(CGVector(dx: 12, dy: 10)).press(forDuration: 1.2)
        XCTAssertTrue(menuItem(app, "Copy").waitForExistence(timeout: 5), app.debugDescription)
    }

    func testALongPressSelectsAWordAndQuoteStartsTheReplyWithIt() {
        let app = launch()
        let paragraph = element(app, labelPrefix: "This post is synthetic")
        selectFirstWord(app, paragraph)
        for title in ["Copy", "Quote", "Translate", "Share"] {
            XCTAssertTrue(menuItem(app, title).exists, "\(title) in the menu: \(app.debugDescription)")
        }
        XCTAssertEqual(app.menuItems.matching(NSPredicate(format: "label == 'Translate'")).count, 1, "one Translate (the system's is left out)")
        menuItem(app, "Quote").tap()
        let field = app.textViews["reply-text"]
        XCTAssertTrue(field.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(waitFor(field, valuePrefix: "> This\n"), String(describing: field.value))
    }

    func testALinkStillOpensOnATap() {
        let app = launch()
        let link = app.descendants(matching: .any).matching(NSPredicate(format: "label == 'Example link'")).firstMatch
        XCTAssertTrue(link.waitForExistence(timeout: 15))
        link.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.5)).tap()
        XCTAssertTrue(app.sheets["Link"].buttons["link-copy"].firstMatch.waitForExistence(timeout: 10), app.debugDescription)
    }

    /// Review fix: a long press on a link selects; letting go does not also open the link (nor a chip's profile).
    func testALongPressOnALinkSelectsWithoutOpeningIt() {
        let app = launch()
        let link = app.descendants(matching: .any).matching(NSPredicate(format: "label == 'Example link'")).firstMatch
        XCTAssertTrue(link.waitForExistence(timeout: 15))
        link.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.5)).press(forDuration: 1.2)
        XCTAssertTrue(menuItem(app, "Copy").waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertFalse(app.sheets["Link"].waitForExistence(timeout: 2), "the link did not open")
    }

    func testSelectTextShowsTheWholeBodyAndTranslatesASelection() {
        let app = launch()
        let options = app.buttons["post-options"].firstMatch
        XCTAssertTrue(options.waitForExistence(timeout: 15))
        options.tap()
        let item = app.buttons["SELECT TEXT"]
        XCTAssertTrue(item.waitForExistence(timeout: 5), app.debugDescription)
        item.tap()
        let body = app.textViews["select-text-body"]
        XCTAssertTrue(body.waitForExistence(timeout: 10), app.debugDescription)
        let text = body.value as? String ?? ""
        XCTAssertTrue(text.hasPrefix("Spike fixture\n\nThis post is synthetic"), text)
        XCTAssertTrue(text.contains("@spike-bob"), text)
        XCTAssertTrue(text.contains("\n\n"), "paragraphs on their own: \(text)")
        selectFirstWord(app, body)
        menuItem(app, "Translate").tap()
        XCTAssertTrue(app.navigationBars["Translate"].waitForExistence(timeout: 10), app.debugDescription)
    }

    func testACommentSelectsAndQuoteRepliesToThatComment() {
        let app = launch()
        let comment = element(app, labelPrefix: "First fixture comment")
        for _ in 0..<6 where !comment.exists { app.swipeUp() }
        selectFirstWord(app, comment)
        menuItem(app, "Quote").tap()
        let field = app.textViews["reply-text"]
        XCTAssertTrue(field.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(waitFor(field, valuePrefix: "> First\n"), String(describing: field.value))
        // the sheet replies to the comment's author, not the post's (spike-alice)
        XCTAssertTrue(app.staticTexts["Replying to @spike-bob"].exists, app.debugDescription)
        XCTAssertFalse(app.staticTexts["Replying to @spike-alice"].exists)
    }

    func testQuoteSignedOutAsksToSignIn() {
        let app = launch(signedIn: false)
        selectFirstWord(app, element(app, labelPrefix: "This post is synthetic"))
        menuItem(app, "Quote").tap()
        XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertFalse(app.textViews["reply-text"].exists)
    }

    /// Review fix: signed out, Quote inside the Select text sheet opens the login sheet (a root alert would not show).
    func testQuoteSignedOutInSelectTextOpensLogin() {
        let app = launch(signedIn: false)
        let options = app.buttons["post-options"].firstMatch
        XCTAssertTrue(options.waitForExistence(timeout: 15))
        options.tap()
        let item = app.buttons["SELECT TEXT"]
        XCTAssertTrue(item.waitForExistence(timeout: 5), app.debugDescription)
        item.tap()
        let body = app.textViews["select-text-body"]
        XCTAssertTrue(body.waitForExistence(timeout: 10), app.debugDescription)
        selectFirstWord(app, body)
        menuItem(app, "Quote").tap()
        XCTAssertTrue(app.textFields["login-username"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(app.textViews["reply-text"].exists)
    }

    private func waitFor(_ field: XCUIElement, valuePrefix: String) -> Bool {
        let deadline = Date().addingTimeInterval(8)
        while Date() < deadline {
            if (field.value as? String ?? "").hasPrefix(valuePrefix) { return true }
            RunLoop.current.run(until: Date().addingTimeInterval(0.2))
        }
        return false
    }
}
