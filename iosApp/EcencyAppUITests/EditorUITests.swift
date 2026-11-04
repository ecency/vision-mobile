import XCTest

/// The editor and drafts screens against the debug fake transport, demo Ecency API and demo image host
/// (nothing leaves the device; the fake node refuses every broadcast).
final class EditorUITests: XCTestCase {
    private func launch(_ extra: [String]) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-seedAccounts", "YES"] + extra
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    private func body(_ app: XCUIApplication) -> XCUIElement { app.textViews["editor-body"] }

    /// Taps a toolbar button, scrolling the toolbar (it is wider than the screen) until the button can be hit.
    private func tapTool(_ app: XCUIApplication, _ id: String) {
        let button = app.buttons[id]
        XCTAssertTrue(button.waitForExistence(timeout: 20), app.debugDescription)
        // frames, not isHittable: asking that of a button outside the screen is itself a failure
        let width = app.windows.firstMatch.frame.width
        let origin = app.coordinate(withNormalizedOffset: .zero)
        var tries = 0
        while button.frame.maxX > width - 8 || button.frame.minX < 0, tries < 10 {
            // a drag along the toolbar's own row, at the height the button is at
            let y = button.frame.midY
            origin.withOffset(CGVector(dx: width * 0.85, dy: y)).press(forDuration: 0.1, thenDragTo: origin.withOffset(CGVector(dx: width * 0.25, dy: y)), withVelocity: .slow, thenHoldForDuration: 0.1)
            tries += 1
        }
        button.tap()
    }

    /// The image generator and dictation live in the AI Assist sheet (the editor's AI hub), not on the toolbar.
    private func openFromAiAssist(_ app: XCUIApplication, _ id: String) {
        tapTool(app, "tool-ai-assist")
        let card = app.buttons[id]
        XCTAssertTrue(card.waitForExistence(timeout: 10), app.debugDescription)
        card.tap()
    }

    /// Owner order (10-28): Aa, Image, Video, AI Assist, Snippets, Link, Poll, Translate, Clear. At the largest text
    /// the first four are on a 375 point wide phone (the narrowest iOS 26 runs on) without scrolling, and the AI tools
    /// have no buttons of their own: AI Assist lists them.
    func testTheToolbarLeadsWithTheWritingToolsAndAiAssistIsOnANarrowScreenAtTheLargestText() {
        let app = launch(["-openScreen", "editor", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"])
        XCTAssertTrue(app.buttons["tool-format"].waitForExistence(timeout: 20), app.debugDescription)
        let order = ["tool-format", "tool-image", "tool-video", "tool-ai-assist", "tool-snippets", "tool-link", "tool-poll", "tool-translate", "tool-clear"]
        let lefts = order.map { app.buttons[$0].frame.minX }
        XCTAssertEqual(lefts, lefts.sorted(), "toolbar order \(Array(zip(order, lefts)))")
        // on screen on this simulator: the toolbar spans the window, so its visible frame is the window's
        let toolbar = app.windows.firstMatch.frame
        let ai = app.buttons["tool-ai-assist"].frame
        XCTAssertLessThanOrEqual(ai.maxX, toolbar.maxX, "AI Assist inside the visible toolbar \(toolbar): \(ai)")
        XCTAssertTrue(app.buttons["tool-ai-assist"].isHittable)
        // and on the narrowest phone: the toolbar starts at the screen edge there too, so the same x must stay within 375
        XCTAssertLessThanOrEqual(ai.maxX, toolbar.minX + 375, "AI Assist within a 375 point screen: \(ai)")
        XCTAssertFalse(app.buttons["tool-ai-image"].exists)
        XCTAssertFalse(app.buttons["tool-dictate"].exists)
        app.buttons["tool-ai-assist"].tap()
        XCTAssertTrue(app.buttons["ai-tool-image"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.buttons["ai-tool-dictate"].isEnabled, "no run open: the tools can be opened")
        // the tools are a group above the actions, never between a chosen action and its text
        let improve = element(app, "ai-action-improve")
        XCTAssertTrue(improve.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertLessThan(app.buttons["ai-tool-dictate"].frame.maxY, improve.frame.minY)
    }

    private func write(_ app: XCUIApplication, title: String, body text: String) {
        let titleField = element(app, "editor-title")
        XCTAssertTrue(titleField.waitForExistence(timeout: 20), app.debugDescription)
        titleField.focusAndType(title)
        let tags = element(app, "tag-input")
        tags.focusAndType("spike test ")
        body(app).focusAndType(text)
    }

    /// A typed tag becomes a chip and leaves the field: typing on never parses it again ("Tag exists already").
    func testTypedTagsLeaveTheFieldAndNeverRaiseADuplicate() {
        let app = launch(["-openScreen", "editor"])
        let tags = element(app, "tag-input")
        XCTAssertTrue(tags.waitForExistence(timeout: 20), app.debugDescription)
        tags.focusAndType("a b c ")
        for tag in ["a", "b", "c"] { XCTAssertTrue(element(app, "tag-\(tag)").waitForExistence(timeout: 5), tag) }
        waitEmpty(tags)
        tags.typeText("de")
        tags.waitForValue("de")
        XCTAssertFalse(app.staticTexts["toast"].exists, "no duplicate toast for new tags")
        // a real duplicate: one toast, the field cleared, no second chip
        tags.typeText(" a ")
        XCTAssertTrue(app.staticTexts["toast"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["toast"].label, "Tag exists already")
        XCTAssertTrue(element(app, "tag-de").exists)
        XCTAssertEqual(app.buttons.matching(identifier: "tag-a").count, 1)
        waitEmpty(tags)
    }

    /// An empty text field reports its placeholder as its value.
    private func waitEmpty(_ field: XCUIElement) {
        let empty = NSPredicate(format: "value == '' OR value == placeholderValue")
        let wait = XCTNSPredicateExpectation(predicate: empty, object: field)
        XCTAssertEqual(XCTWaiter().wait(for: [wait], timeout: 10), .completed, "field is \(String(describing: field.value))")
    }

    func testWriteFormatPreviewAndAFailedPublishKeepsEverything() {
        let app = launch(["-openScreen", "editor"])
        write(app, title: "Native post", body: "hello")
        XCTAssertTrue(element(app, "tag-spike").exists)
        XCTAssertTrue(element(app, "words-count").waitForExistence(timeout: 5))
        // bold at the caret: the wrapper goes in and the caret sits between the stars
        app.buttons["tool-format"].tap()
        app.buttons["format-bold"].tap()
        XCTAssertEqual(body(app).value as? String, "hello****")
        body(app).focusAndType("bold")
        XCTAssertEqual(body(app).value as? String, "hello**bold**")
        app.buttons["preview-toggle"].tap()
        XCTAssertTrue(element(app, "editor-preview").waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Native post"].waitForExistence(timeout: 5))
        app.buttons["preview-toggle"].tap()
        XCTAssertEqual(body(app).value as? String, "hello**bold**", "the text is back after the preview")
        // publish: confirmed first; the fake node refuses, and nothing typed is lost
        app.buttons["editor-submit"].tap()
        XCTAssertTrue(app.alerts["Publishing new post"].waitForExistence(timeout: 5))
        app.alerts.buttons["Yes"].tap()
        XCTAssertTrue(app.buttons["editor-submit"].waitForExistence(timeout: 20), app.debugDescription)
        XCTAssertEqual(body(app).value as? String, "hello**bold**")
        XCTAssertEqual(element(app, "editor-title").value as? String, "Native post")
    }

    func testTranslateAddsTheTranslationToTheEndOfThePost() {
        let app = launch(["-openScreen", "editor"])
        let text = "This is the body of the post and it is long enough for the language to be guessed with the words that are in it."
        write(app, title: "Native post", body: text)
        tapTool(app, "tool-translate")
        XCTAssertTrue(app.buttons["translate-run"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(app.buttons["translate-apply"].isEnabled, "nothing to apply before a translation")
        app.buttons["translate-run"].tap()
        XCTAssertTrue(element(app, "translate-preview").waitForExistence(timeout: 10), app.debugDescription)
        let apply = app.buttons["translate-apply"]
        XCTAssertTrue(apply.isEnabled, "a translation is there to apply")
        apply.tap()
        // the sheet closes when the editor took the text
        let closed = NSPredicate(format: "exists == false")
        expectation(for: closed, evaluatedWith: apply)
        waitForExpectations(timeout: 10)
        // the keyboard may have corrected what was typed: the appendix's frame is what is checked, not the typed words
        let value = { (self.body(app).value as? String) ?? "" }
        let deadline = Date().addingTimeInterval(10)
        while !value().contains("## Spanish"), Date() < deadline { Thread.sleep(forTimeInterval: 0.3) }
        // the fake-data run answers every translation with its own fixture sentence
        XCTAssertTrue(value().hasPrefix(text.prefix(20)), "the text that was there stays: \(value())")
        XCTAssertTrue(value().contains("\n\n---\n\n## Spanish\n\nTranslated fixture text."), "body: \(value())")
        XCTAssertEqual(element(app, "editor-title").value as? String, "Native post [EN | ES]")
    }

    func testAiAssistShowsBalanceAndCostBeforeItRunsAndReplacesTheBody() {
        let app = launch(["-openScreen", "editor"])
        let text = "This is the body of the post and it is long enough for the writing assistant to accept it as its input text."
        write(app, title: "Native post", body: text)
        tapTool(app, "tool-ai-assist")
        XCTAssertTrue(element(app, "ai-action-improve").waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.staticTexts["Balance: 1,250.500 POINTS"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(app.buttons["ai-submit"].isEnabled, "no action chosen: nothing can be charged")
        element(app, "ai-action-check_grammar").tap()
        // the button that charges says what it costs
        XCTAssertTrue(app.buttons["Run AI Assist (3 POINTS)"].waitForExistence(timeout: 5), app.debugDescription)
        element(app, "ai-action-improve").tap()
        XCTAssertTrue(app.buttons["Run AI Assist (Free)"].waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["ai-submit"].tap()
        XCTAssertTrue(element(app, "ai-result").waitForExistence(timeout: 10), app.debugDescription)
        app.buttons["ai-apply"].tap()
        let replaced = NSPredicate(format: "value BEGINSWITH %@", "This is a synthetic answer of the demo assistant.")
        expectation(for: replaced, evaluatedWith: body(app))
        waitForExpectations(timeout: 10)
    }

    func testAnAiImageStatesItsCostAndIsInserted() {
        let app = launch(["-openScreen", "editor"])
        write(app, title: "Lighthouse", body: "start")
        openFromAiAssist(app, "ai-tool-image")
        XCTAssertTrue(app.buttons["ai-image-generate"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.staticTexts["Cost: 150 POINTS"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(app.buttons["ai-image-generate"].isEnabled, "no prompt yet")
        element(app, "ai-image-suggestion").tap()
        element(app, "ai-power-2").tap()
        XCTAssertTrue(app.buttons["Generate Image (300 POINTS)"].waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["ai-image-generate"].tap()
        XCTAssertTrue(app.buttons["ai-image-insert"].waitForExistence(timeout: 10), app.debugDescription)
        app.buttons["ai-image-insert"].tap()
        let inserted = NSPredicate(format: "value CONTAINS %@", "![](https://images.ecency.com/demo/generated.png)")
        expectation(for: inserted, evaluatedWith: body(app))
        waitForExpectations(timeout: 10)
    }

    func testDictationStatesItsCostOnStopAndInsertsTheTranscript() {
        let app = launch(["-openScreen", "editor"])
        write(app, title: "Native post", body: "Typed.")
        openFromAiAssist(app, "ai-tool-dictate")
        XCTAssertTrue(app.staticTexts["15 Points per 30 seconds"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.buttons["Record"].waitForExistence(timeout: 5))
        app.buttons["dictation-main"].tap()
        // the tap that spends points says how many
        XCTAssertTrue(app.buttons["Stop (15 Points)"].waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["dictation-main"].tap()
        XCTAssertTrue(app.buttons["Record more"].waitForExistence(timeout: 10), app.debugDescription)
        app.buttons["dictation-done"].tap()
        let inserted = NSPredicate(format: "value == %@", "Typed. This sentence stands in for a transcript in the demo.")
        expectation(for: inserted, evaluatedWith: body(app))
        waitForExpectations(timeout: 10)
    }

    func testTheVideoSheetOffersTheLibraryAndClosesWithoutAVideo() {
        let app = launch(["-openScreen", "editor"])
        XCTAssertTrue(body(app).waitForExistence(timeout: 20), app.debugDescription)
        tapTool(app, "tool-video")
        XCTAssertTrue(app.buttons["video-choose"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(app.buttons["video-start"].exists, "nothing to upload before a video was picked")
        // RN "Record Video": the simulator has no camera, which is said instead of opening one
        XCTAssertTrue(app.buttons["video-record"].exists)
        app.buttons["video-record"].tap()
        XCTAssertTrue(app.alerts["Notice"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.alerts["Notice"].staticTexts["The camera is not available on this device."].exists)
        app.alerts["Notice"].buttons["OK"].tap()
        app.buttons["video-close"].tap()
        XCTAssertTrue(body(app).waitForExistence(timeout: 5))
    }

    func testTheFirstPostDigestOfferAsksForAnAddressAndSaysToCheckTheInbox() {
        let app = launch(["-openScreen", "digest"])
        let title = app.staticTexts["digest-title"]
        XCTAssertTrue(title.waitForExistence(timeout: 20), app.debugDescription)
        XCTAssertEqual(title.label, "Congrats on your first post!")
        XCTAssertFalse(app.buttons["digest-submit"].isEnabled, "no address yet")
        let email = app.textFields["digest-email"]
        email.focusAndType("spike@example.com")
        app.buttons["digest-cadence-monthly"].tap()
        XCTAssertTrue(app.buttons["digest-submit"].isEnabled)
        app.buttons["digest-submit"].tap()
        // the demo relay answers "pending confirmation": RN's check-your-inbox state
        XCTAssertTrue(app.staticTexts["digest-check-inbox"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["digest-check-inbox"].label.contains("spike@example.com"))
        app.buttons["digest-ok"].tap()
        XCTAssertFalse(app.buttons["digest-ok"].waitForExistence(timeout: 3))
    }

    func testTheGeneratorRouteIsTheGeneratorWithoutInsert() {
        let app = launch(["-openScreen", "route:AI_IMAGE_GENERATOR"])
        XCTAssertTrue(app.buttons["ai-image-generate"].waitForExistence(timeout: 20), app.debugDescription)
        XCTAssertTrue(app.staticTexts["Cost: 150 POINTS"].waitForExistence(timeout: 10), app.debugDescription)
        let prompt = element(app, "ai-image-prompt")
        prompt.focusAndType("A lighthouse")
        XCTAssertTrue(app.buttons["Generate Image (150 POINTS)"].waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["ai-image-generate"].tap()
        XCTAssertTrue(app.buttons["ai-image-again"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(app.buttons["ai-image-insert"].exists, "nothing to insert into")
    }

    func testAShortDraftIsNotTranslated() {
        let app = launch(["-openScreen", "editor"])
        write(app, title: "Native post", body: "short")
        tapTool(app, "tool-translate")
        XCTAssertTrue(element(app, "translate-too-short").waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(app.buttons["translate-run"].exists)
    }

    func testTheDraftSurvivesTheAppBeingKilled() {
        let app = launch(["-openScreen", "editor"])
        write(app, title: "Do not lose", body: "typed before the crash")
        // the local draft is written 300 ms after the last change
        Thread.sleep(forTimeInterval: 1.5)
        app.terminate()
        let again = launch(["-keepFakeStore", "YES", "-openScreen", "editor"])
        XCTAssertTrue(body(again).waitForExistence(timeout: 20), again.debugDescription)
        let restored = NSPredicate(format: "value == %@", "typed before the crash")
        expectation(for: restored, evaluatedWith: body(again))
        waitForExpectations(timeout: 10)
        XCTAssertEqual(element(again, "editor-title").value as? String, "Do not lose")
        XCTAssertTrue(element(again, "tag-test").exists, again.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'tag-'")).allElementsBoundByIndex.map { "\($0.identifier)=\(String(describing: $0.value))" }.joined(separator: ", "))
    }

    func testSnippetsAndGalleryImagesGoInAtTheCaret() {
        let app = launch(["-openScreen", "editor"])
        XCTAssertTrue(body(app).waitForExistence(timeout: 20), app.debugDescription)
        body(app).focusAndType("start")
        app.buttons["tool-snippets"].tap()
        XCTAssertTrue(element(app, "snippet-Footer").waitForExistence(timeout: 10), app.debugDescription)
        element(app, "snippet-Footer").tap()
        let withSnippet = NSPredicate(format: "value CONTAINS %@", "Thanks for reading!")
        expectation(for: withSnippet, evaluatedWith: body(app))
        waitForExpectations(timeout: 5)
        app.buttons["tool-image"].tap()
        XCTAssertTrue(app.buttons["open-gallery"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.buttons["open-files"].exists)
        XCTAssertTrue(element(app, "gallery-image-demo-i1").waitForExistence(timeout: 10), app.debugDescription)
        element(app, "gallery-image-demo-i1").tap()
        let withImage = NSPredicate(format: "value CONTAINS %@", "![](https://images.ecency.com/demo/one.jpg)")
        expectation(for: withImage, evaluatedWith: body(app))
        waitForExpectations(timeout: 5)
    }

    func testDraftsScreenTabsAndOpeningADraft() {
        let app = launch(["-openScreen", "drafts"])
        XCTAssertTrue(element(app, "draft-row-Draft fixture").waitForExistence(timeout: 20), app.debugDescription)
        app.buttons["Schedules"].tap()
        XCTAssertTrue(element(app, "draft-row-Scheduled fixture").waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertEqual(element(app, "schedule-status-Scheduled fixture").label, "Pending")
        app.buttons["Templates"].tap()
        XCTAssertTrue(element(app, "draft-row-Weekly").waitForExistence(timeout: 5), app.debugDescription)
        app.buttons["Drafts"].tap()
        element(app, "draft-row-Draft fixture").tap()
        let title = element(app, "editor-title")
        XCTAssertTrue(title.waitForExistence(timeout: 10), app.debugDescription)
        let loaded = NSPredicate(format: "value == %@", "Draft fixture")
        expectation(for: loaded, evaluatedWith: title)
        waitForExpectations(timeout: 10)
        XCTAssertTrue(element(app, "tag-photography").exists)
        // post options show the draft's saved reward and the beneficiaries section
        app.buttons["post-options"].tap()
        XCTAssertTrue(element(app, "reward-sp").waitForExistence(timeout: 5), app.debugDescription)
        XCTAssertTrue(element(app, "thumb-hint").exists)
    }
}
