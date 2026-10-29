import XCTest

/// RN chats on iOS against the offline demo chat (DemoChat): the list, a conversation, sending, reactions, the DM
/// notice and the Chat Privacy setting. Nothing reaches a server.
final class ChatUITests: XCTestCase {
    private func launch(_ extra: [String]) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-seedAccounts", "YES"] + extra
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any)[id].firstMatch
    }

    private func any(_ app: XCUIApplication, prefix: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix)).firstMatch
    }

    func testTheListShowsTheDemoChannelsInRnOrder() {
        let app = launch(["-startTab", "CHATS"])
        XCTAssertTrue(element(app, "chat-row-ch-photo").waitForExistence(timeout: 20))
        for id in ["ch-group", "ch-dm-alice", "ch-ecency", "ch-muted"] {
            XCTAssertTrue(element(app, "chat-row-\(id)").exists, id)
        }
        XCTAssertTrue(app.staticTexts["Book club"].exists)
        XCTAssertTrue(app.staticTexts["Photography"].exists)
        XCTAssertTrue(element(app, "chat-unread-ch-ecency").exists, "the unread badge")
        XCTAssertTrue(app.staticTexts["Connected"].waitForExistence(timeout: 10))
        // the options of a channel
        element(app, "chat-options-ch-ecency").tap()
        XCTAssertTrue(app.buttons["Mark as read"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Leave channel"].exists)
        app.buttons["Mark as read"].firstMatch.tap()
        let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: element(app, "chat-unread-ch-ecency"))
        wait(for: [gone], timeout: 10)
    }

    func testAConversationShowsNoticesTheMarkerSendsAndReacts() {
        let app = launch(["-startTab", "CHATS"])
        let row = element(app, "chat-row-ch-ecency")
        XCTAssertTrue(row.waitForExistence(timeout: 20))
        row.tap()
        XCTAssertTrue(element(app, "chat-thread").waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["chat-title"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["chat-title"].label, "Ecency")
        XCTAssertTrue(element(app, "chat-unread-marker").waitForExistence(timeout: 10), "New messages")
        XCTAssertTrue(app.buttons["2 people joined"].exists || app.staticTexts["2 people joined"].exists)

        let composer = element(app, "chat-composer")
        composer.focusAndType("hello from ios")
        app.buttons["chat-send"].tap()
        // a message is one VoiceOver element: its text is part of the label (README "Accessibility")
        let sent = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "hello from ios")).firstMatch
        XCTAssertTrue(sent.waitForExistence(timeout: 10), "the sent message shows")

        // the options sheet of a message: a quick reaction
        let mine = any(app, prefix: "chat-message-actions-")
        XCTAssertTrue(mine.waitForExistence(timeout: 5))
        mine.tap()
        XCTAssertTrue(element(app, "chat-options-sheet").waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["chat-option-EDIT"].exists)
        app.buttons["chat-quick-+1"].tap()
        XCTAssertTrue(any(app, prefix: "chat-reaction-").waitForExistence(timeout: 10), "the reaction shows under the message")
    }

    /// The author's name on someone else's message: a long press opens the message options only, a tap the
    /// quick profile only (the long press's release never also taps).
    func testTheAuthorNameLongPressesToOptionsAndTapsToTheProfile() {
        let app = launch(["-startTab", "CHATS"])
        let row = element(app, "chat-row-ch-ecency")
        XCTAssertTrue(row.waitForExistence(timeout: 20))
        row.tap()
        let message = element(app, "chat-message-p3")
        XCTAssertTrue(message.waitForExistence(timeout: 10), app.debugDescription)
        // on screen before touching it (the thread may open at the unread marker further down)
        let list = element(app, "chat-messages")
        for _ in 0..<5 where !message.isHittable { list.swipeDown() }
        XCTAssertTrue(message.isHittable, "the message is on screen")
        // the name sits at the bubble's top left, after the 32 pt avatar and its 8 pt gap, inside 12 by 8 of padding
        let name = message.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: 32 + 8 + 12 + 6, dy: 4 + 8 + 6))
        name.press(forDuration: 0.8)
        XCTAssertTrue(element(app, "chat-options-sheet").waitForExistence(timeout: 5), "long press: the options")
        XCTAssertFalse(element(app, "quick-profile").exists, "and no profile")
        app.swipeDown(velocity: .fast)
        XCTAssertTrue(element(app, "chat-options-sheet").waitForNonExistence(timeout: 5))
        sleep(1)
        name.tap()
        XCTAssertTrue(element(app, "quick-profile").waitForExistence(timeout: 10), "tap: the profile " + app.debugDescription)
        XCTAssertFalse(element(app, "chat-options-sheet").exists, "and no options")
    }

    /// #3602 and #3603: "Ask Esie" in the side menu opens her conversation; the header, her messages and her list row
    /// carry the official badge, read as "Official Ecency account"; other conversations do not.
    func testAskEsieOpensHerConversationMarkedOfficial() {
        let app = launch([])
        let menu = app.buttons["account-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 20))
        menu.tap()
        let ask = app.buttons["menu-ask_esie"]
        XCTAssertTrue(ask.waitForExistence(timeout: 5))
        XCTAssertEqual(ask.label, "Ask Esie")
        ask.tap()
        XCTAssertTrue(element(app, "chat-title").waitForExistence(timeout: 20))
        XCTAssertEqual(element(app, "chat-title").label, "esie")
        let badge = element(app, "official-badge")
        XCTAssertTrue(badge.waitForExistence(timeout: 10))
        XCTAssertEqual(badge.label, "Official Ecency account")
        let message = any(app, prefix: "chat-message-")
        XCTAssertTrue(message.waitForExistence(timeout: 10))
        let bubble = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "esie, Official Ecency account, ")).firstMatch
        XCTAssertTrue(bubble.waitForExistence(timeout: 5), "her message says the badge")
        element(app, "chat-back").tap()
        app.buttons["tab-chats"].firstMatch.tap()
        let row = element(app, "chat-row-ch-dm-esie")
        XCTAssertTrue(row.waitForExistence(timeout: 20))
        let official = NSPredicate(format: "label CONTAINS %@", "Official Ecency account")
        XCTAssertGreaterThan(row.descendants(matching: .any).matching(official).count, 0, "her row says the badge")
        let alice = element(app, "chat-row-ch-dm-alice")
        XCTAssertTrue(alice.exists)
        XCTAssertEqual(alice.descendants(matching: .any).matching(official).count, 0)
    }

    func testADirectMessageWarnsUntilClosed() {
        let app = launch(["-startTab", "CHATS"])
        let row = element(app, "chat-row-ch-dm-alice")
        XCTAssertTrue(row.waitForExistence(timeout: 20))
        row.tap()
        let warning = element(app, "dm-warning")
        XCTAssertTrue(warning.waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["New conversation - Stay safe"].exists)
        app.buttons["dm-warning-close"].tap()
        let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: warning)
        wait(for: [gone], timeout: 5)
    }

    func testAGroupOpensByIdWithItsName() {
        let app = launch(["-openScreen", "chat:ch-group:G"])
        XCTAssertTrue(app.staticTexts["chat-title"].waitForExistence(timeout: 20))
        let named = expectation(for: NSPredicate(format: "label == 'Book club'"), evaluatedWith: app.staticTexts["chat-title"])
        wait(for: [named], timeout: 10)
        XCTAssertTrue(app.buttons["chat-group-options"].exists)
    }

    func testSettingsHasChatPrivacySignedIn() {
        let app = launch(["-openScreen", "settings"])
        XCTAssertTrue(app.buttons["settings-dm-privacy"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["settings-dm-privacy"].label.contains("Everyone"))
    }
}
