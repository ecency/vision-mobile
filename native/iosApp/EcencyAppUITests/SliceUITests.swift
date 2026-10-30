import XCTest

/// The vertical slice against the debug fake transport (`-fakeData YES`): synthetic fixtures, no
/// network for Hive data, signed out, nothing can broadcast.
final class SliceUITests: XCTestCase {
    private func launch() -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-homeFilter", "trending"]
        app.launch()
        return app
    }

    private func firstCard(_ app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(identifier: "post-card").firstMatch
    }

    private func openFirstPost(_ app: XCUIApplication) {
        let card = firstCard(app)
        XCTAssertTrue(card.waitForExistence(timeout: 15))
        card.tap()
        XCTAssertTrue(app.staticTexts["post-title"].waitForExistence(timeout: 10))
    }

    func testLaunchShowsTheFeed() {
        let app = launch()
        XCTAssertTrue(firstCard(app).waitForExistence(timeout: 15))
        XCTAssertTrue(firstCard(app).label.hasPrefix("Fixture post number 1, by spike-alice, Spike Community"), firstCard(app).label)
        XCTAssertTrue(app.buttons["vote-button"].firstMatch.exists)
        // RN guest filters as pills
        XCTAssertTrue(app.buttons["filter-HOT"].exists)
        app.buttons["filter-NEW"].tap()
        XCTAssertTrue(firstCard(app).waitForExistence(timeout: 15))
    }

    func testOpenPostShowsBodyAndNestedComments() {
        let app = launch()
        openFirstPost(app)
        XCTAssertTrue(app.staticTexts["post-title"].label.hasPrefix("Fixture post"))
        let paragraph = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH 'This post is synthetic'")).firstMatch
        XCTAssertTrue(paragraph.waitForExistence(timeout: 10))
        // Comments are in the same scroll view, below the body; replies start collapsed under their top-level
        // comment (RN commentsSection), so the thread is opened first.
        let toggle = app.buttons["thread-toggle"].firstMatch
        for _ in 0..<6 where !toggle.exists { app.swipeUp() }
        XCTAssertTrue(toggle.waitForExistence(timeout: 10), "the top-level comment offers its replies")
        XCTAssertEqual(toggle.label, "Show replies")
        // the comments icon with its count is the reply button, on the caret's line; no "Reply" text button
        let replies = app.buttons.matching(identifier: "reply-comment").allElementsBoundByIndex
        let line = replies.first { $0.frame.minY < toggle.frame.maxY && $0.frame.maxY > toggle.frame.minY }
        XCTAssertNotNil(line, "the caret \(toggle.frame) on the line of a reply button \(replies.map(\.frame))")
        guard let reply = line else { return }
        XCTAssertTrue(reply.frame.maxX <= toggle.frame.minX + 1, "the count before the caret")
        XCTAssertTrue(reply.label.hasPrefix("Reply to "), reply.label)
        // the icon's count is the button's value (the old "Reply" text button had none)
        XCTAssertTrue((reply.value as? String)?.contains("comment") == true, String(describing: reply.value))
        toggle.tap()
        let more = app.buttons["more-replies"]
        for _ in 0..<6 where !more.exists { app.swipeUp() }
        XCTAssertTrue(more.waitForExistence(timeout: 10), "level 4 reply sits behind more replies")
        XCTAssertTrue(app.buttons["comment-sort"].exists)
        more.tap()
        // The thread opens as its own post screen with the hidden reply.
        let hidden = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH 'Hidden below level three'")).firstMatch
        for _ in 0..<4 where !hidden.exists { app.swipeUp() }
        XCTAssertTrue(hidden.waitForExistence(timeout: 10))
    }

    func testExternalLinkAsksWhatToDoFirst() {
        let app = launch()
        openFirstPost(app)
        let link = app.descendants(matching: .any).matching(NSPredicate(format: "label == 'Example link'")).firstMatch
        XCTAssertTrue(link.waitForExistence(timeout: 10))
        link.coordinate(withNormalizedOffset: CGVector(dx: 0.05, dy: 0.5)).tap()
        // RN postInteractionHandler: the link sheet, not the browser at once
        // the dialog's button (its label and identifier are reported on two nested elements)
        let copy = app.sheets["Link"].buttons["link-copy"].firstMatch
        XCTAssertTrue(copy.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(app.sheets["Link"].buttons["link-system"].firstMatch.exists)
        copy.tap()
        XCTAssertFalse(copy.waitForExistence(timeout: 2))
        XCTAssertTrue(app.staticTexts["post-title"].exists, "nothing left the post")
    }

    func testVideoOpensThePlayerSheet() {
        let app = launch()
        openFirstPost(app)
        let video = app.descendants(matching: .any).matching(identifier: "video").firstMatch
        for _ in 0..<3 where !video.exists { app.swipeUp() }
        XCTAssertTrue(video.waitForExistence(timeout: 10))
        video.tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "video-player").firstMatch.waitForExistence(timeout: 10))
        app.buttons["Done"].tap()
    }

    func testVoteWhileSignedOutAsksToSignIn() {
        let app = launch()
        let vote = app.buttons["vote-button"].firstMatch
        XCTAssertTrue(vote.waitForExistence(timeout: 15))
        XCTAssertEqual(vote.value as? String, "Not voted")
        vote.tap()
        let alert = app.alerts["Sign in required"]
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        XCTAssertTrue(alert.staticTexts["Sign in to vote."].exists)
        alert.buttons["Sign in"].firstMatch.tap()
        XCTAssertTrue(app.textFields["login-username"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.secureTextFields["login-key"].exists)
        XCTAssertFalse(app.buttons["login-submit"].isEnabled, "nothing to submit yet")
    }

    func testReplyWhileSignedOutAsksToSignIn() {
        let app = launch()
        openFirstPost(app)
        // the bar's comments icon takes the reader to the comments, whose header holds the Reply to the post
        app.buttons["post-comments"].tap()
        let reply = app.buttons["comments-reply"]
        XCTAssertTrue(reply.waitForExistence(timeout: 10))
        for _ in 0..<10 where !reply.isHittable { RunLoop.current.run(until: Date().addingTimeInterval(0.3)) }
        reply.tap()
        XCTAssertTrue(app.alerts["Sign in required"].waitForExistence(timeout: 5))
    }

    /// Typing in the reply composer keeps every character. The editor used to read its text back from the
    /// model's watched state, which arrives a main-queue turn later, so fast typing lost characters (a live
    /// run sent "NS" for "Native app test reply iOS").
    func testReplyComposerKeepsFastTyping() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-homeSort", "trending", "-seedAccounts", "YES"]
        app.launch()
        openFirstPost(app)
        // the bar's comments icon takes the reader to the comments, whose header holds the Reply to the post
        app.buttons["post-comments"].tap()
        let reply = app.buttons["comments-reply"]
        XCTAssertTrue(reply.waitForExistence(timeout: 10))
        for _ in 0..<10 where !reply.isHittable { RunLoop.current.run(until: Date().addingTimeInterval(0.3)) }
        reply.tap()
        let text = app.textViews["reply-text"]
        XCTAssertTrue(text.waitForExistence(timeout: 10))
        let typed = "Native app test reply, typed fast: the quick brown fox jumps over the lazy dog 0123456789"
        text.focusAndType(typed)
        // the value follows the keystrokes a run loop turn later (much later on a loaded machine): wait for all of
        // them; a lost character never arrives, so this still fails on the old bug
        text.waitForValue(typed)
        app.buttons["Close"].tap()
        reply.tap()
        XCTAssertTrue(text.waitForExistence(timeout: 10))
        text.waitForValue(typed) // the draft survives closing the sheet
    }

    /// The comment's reply button opens the reply composer; at the largest text size the row wraps and nothing
    /// leaves the screen.
    func testTheCommentsIconRepliesAndTheRowWrapsAtTheLargestText() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-seedAccounts", "YES", "-openPost", "spike-alice/fake-post-1",
                                "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        XCTAssertTrue(app.staticTexts["post-title"].waitForExistence(timeout: 20))
        let toggle = app.buttons["thread-toggle"].firstMatch
        // the commented line's reply button, fully above the post's action bar once the list holds still. The bar
        // covers the list's bottom (XCUI still calls a covered button hittable), and rows above that settle late at
        // this text size move the list after a swipe, so a tap would go to where the button was.
        XCTAssertTrue(app.buttons["post-voters"].firstMatch.waitForExistence(timeout: 10))
        let barTop = app.buttons["post-voters"].firstMatch.frame.minY
        func lineOfToggle() -> XCUIElement? {
            let replies = app.buttons.matching(identifier: "reply-comment").allElementsBoundByIndex
            return replies.first { $0.frame.minY < toggle.frame.maxY && $0.frame.maxY > toggle.frame.minY }
        }
        func clear(_ line: XCUIElement?) -> Bool {
            guard let line, toggle.exists else { return false }
            return line.frame.maxY <= barTop && line.frame.minY >= app.navigationBars.firstMatch.frame.maxY
        }
        func settledLine() -> XCUIElement? {
            for _ in 0..<16 {
                // the comments are lazy rows: the caret is not in the tree until the list comes near it
                guard toggle.exists else { app.swipeUp(velocity: .slow); continue }
                var last = toggle.frame
                for _ in 0..<20 {
                    Thread.sleep(forTimeInterval: 0.3)
                    if toggle.frame == last { break }
                    last = toggle.frame
                }
                if clear(lineOfToggle()) { break }
                // too low: up a little; too high: down a little
                if toggle.frame.minY < barTop / 2 { app.swipeDown(velocity: .slow) } else { app.swipeUp(velocity: .slow) }
            }
            XCTAssertTrue(toggle.exists, "the caret never came into the list")
            let line = lineOfToggle()
            XCTAssertTrue(clear(line), "the caret \(toggle.frame) on a reply button's line above the bar at \(barTop)")
            return line
        }
        guard let reply = settledLine() else { return }
        XCTAssertTrue(toggle.isHittable)
        // count and caret stay together on one line, the count first, inside the screen
        XCTAssertTrue(reply.frame.maxX <= toggle.frame.minX + 1, "the count before the caret")
        XCTAssertTrue(reply.isHittable)
        let screen = app.windows.firstMatch.frame
        for element in [reply, toggle] {
            XCTAssertTrue(element.frame.minX >= screen.minX - 1 && element.frame.maxX <= screen.maxX + 1, "\(element.frame) inside \(screen)")
        }
        reply.tap()
        // a tap the list moved away from gets one more try on the settled line; the composer must open either way
        if !app.textViews["reply-text"].waitForExistence(timeout: 5), let again = settledLine() { again.tap() }
        XCTAssertTrue(app.textViews["reply-text"].waitForExistence(timeout: 10))
    }
}
