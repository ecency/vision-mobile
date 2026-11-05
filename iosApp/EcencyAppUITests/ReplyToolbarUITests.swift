import XCTest

/// RN quick reply's toolbar in the reply composer (quickPostModalContent in comment mode): image (library, camera,
/// files), dictation and AI assist under the text, expand in the bar. Poll and video are the wave composer's only.
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
        for id in ["reply-attach", "reply-camera", "reply-files", "reply-dictate", "reply-ai-assist"] {
            let button = app.buttons[id]
            XCTAssertTrue(button.waitForExistence(timeout: 10), "the reply composer has \(id)")
            XCTAssertGreaterThanOrEqual(button.frame.minY, text.frame.maxY - 1, "\(id) sits under the text")
        }
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
        // the photo picker is open: the attach is running, nothing is uploaded
        app.buttons["reply-attach"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["reply-image-uploading"].waitForExistence(timeout: 10))
        XCTAssertFalse(expand.isEnabled, "as Android: the editor would open without the image")
    }
}
