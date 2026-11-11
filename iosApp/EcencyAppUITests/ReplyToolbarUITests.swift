import XCTest

/// RN quick reply's toolbar in the reply composer (quickPostModalContent in comment mode): image (the editor's image
/// sheet: library, files, camera, paste and the uploads gallery), dictation and AI assist under the text, expand in the bar. Poll and video are the wave composer's only.
/// Fake data, a seeded account: nothing is uploaded or broadcast.
final class ReplyToolbarUITests: XCTestCase {
    private func openReplyComposer() -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-seedAccounts", "YES", "-openPost", "spike-alice/fake-post-1"]
        app.launch()
        XCTAssertTrue(app.staticTexts["post-title"].waitForExistence(timeout: 30))
        app.buttons["post-comments"].tap()
        let reply = app.buttons["comments-reply"]
        XCTAssertTrue(reply.waitForExistence(timeout: 10))
        for _ in 0..<10 where !reply.isHittable { RunLoop.current.run(until: Date().addingTimeInterval(0.3)) }
        reply.tap()
        XCTAssertTrue(app.textViews["reply-text"].waitForExistence(timeout: 10))
        return app
    }

    func testTheReplyComposerShowsRnsQuickReplyToolbar() {
        let app = openReplyComposer()
        let text = app.textViews["reply-text"]
        for id in ["reply-attach", "reply-dictate", "reply-ai-assist"] {
            let button = app.buttons[id]
            XCTAssertTrue(button.waitForExistence(timeout: 10), "the reply composer has \(id)")
            XCTAssertGreaterThanOrEqual(button.frame.minY, text.frame.maxY - 1, "\(id) sits under the text")
        }
        // one image button, as the blog editor: the other routes are tiles of its sheet
        for id in ["reply-camera", "reply-files", "reply-uploads"] { XCTAssertFalse(app.buttons[id].exists, "no \(id) button") }
        XCTAssertTrue(app.buttons["reply-expand"].exists, "expand to the full editor stays")
        XCTAssertFalse(app.buttons["reply-poll"].exists)
        XCTAssertFalse(app.buttons["reply-video-button"].exists)
        Shots.save("reply-toolbar")
    }

    /// Tester report: return on the keyboard starts a new line in a reply.
    func testReturnStartsANewLineInAReply() {
        let app = openReplyComposer()
        let text = app.textViews["reply-text"]
        text.focusAndType("first line\nsecond line")
        XCTAssertEqual(text.value as? String, "first line\nsecond line")
    }

    func testTheReplysAiAssistOffersRnsThreeActions() {
        let app = openReplyComposer()
        app.textViews["reply-text"].focusAndType("A reply worth improving")
        app.buttons["reply-ai-assist"].tap()
        XCTAssertTrue(app.buttons["ai-action-improve"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["ai-action-summarize"].exists)
        XCTAssertTrue(app.buttons["ai-action-check_grammar"].exists)
        XCTAssertFalse(app.buttons["ai-action-generate_title"].exists)
        XCTAssertFalse(app.buttons["ai-action-suggest_tags"].exists)
    }

    func testOpenEditorWaitsWhileAnImageIsBeingPicked() {
        let app = openReplyComposer()
        let expand = app.buttons["reply-expand"]
        XCTAssertTrue(expand.waitForExistence(timeout: 10))
        XCTAssertTrue(expand.isEnabled)
        // the image sheet's gallery tile: the sheet closes, the photo picker opens and the attach runs, nothing is uploaded
        app.buttons["reply-attach"].tap()
        let gallery = app.buttons["open-gallery"]
        XCTAssertTrue(gallery.waitForExistence(timeout: 10), app.debugDescription)
        gallery.tap()
        XCTAssertTrue(app.descendants(matching: .any)["reply-image-uploading"].waitForExistence(timeout: 10))
        XCTAssertFalse(expand.isEnabled, "as Android: the editor would open without the image")
    }

    /// The image button opens the editor's image sheet: the source tiles, then the account's uploaded images (RN quick
    /// reply's UploadsGalleryModal, `allowMultiple={false}`), and one tap is the reply's image (the fake gallery's first).
    func testAnImageFromTheUploadsGalleryIsTheRepliesImage() {
        let app = openReplyComposer()
        app.buttons["reply-attach"].tap()
        let image = app.descendants(matching: .any)["gallery-image-demo-i1"]
        XCTAssertTrue(image.waitForExistence(timeout: 10), app.debugDescription)
        // one flow with the blog editor: the tiles sit over the gallery; no "+", a pick is the reply's image
        XCTAssertTrue(app.buttons["open-gallery"].exists)
        XCTAssertTrue(app.buttons["open-files"].exists)
        XCTAssertFalse(app.buttons["add-to-gallery"].exists)
        image.tap()
        XCTAssertTrue(app.descendants(matching: .any)["reply-image"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertFalse(app.descendants(matching: .any)["uploads-sheet"].exists, "the gallery closed")
        XCTAssertTrue(app.buttons["reply-send"].isEnabled, "an image alone is a reply")
    }
}
