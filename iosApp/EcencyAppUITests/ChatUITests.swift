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
        // connected says nothing: no "Connected" pill, no offline notice
        XCTAssertFalse(app.staticTexts["Connected"].exists)
        XCTAssertFalse(element(app, "chats-offline").exists)
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
        // RN's four, then a wave and a party; More (every emoji) at the row's end
        XCTAssertTrue(app.buttons["chat-quick-wave"].exists && app.buttons["chat-quick-tada"].exists)
        let more = app.buttons["chat-quick-more"]
        XCTAssertTrue(more.exists)
        XCTAssertGreaterThanOrEqual(more.frame.minX, app.buttons["chat-quick-tada"].frame.maxX, "More after the reactions")
        let mineRow = String(mine.identifier.dropFirst("chat-message-actions-".count))
        app.buttons["chat-quick-+1"].tap()
        // the reaction on the message just reacted to (other messages may carry reactions of their own)
        let pill = any(app, prefix: "chat-reaction-\(mineRow)-")
        XCTAssertTrue(pill.waitForExistence(timeout: 10), "the reaction shows under the message")
        // the pill sits right under its message (tester report: too far from it). Measured from the bubble's last
        // row (the time and the options button) on this own message: the bubble's padding and half the pill's 44
        // point touch area lie between them; without the row's 8 point lift the gap is 34
        let lastRow = mine.frame.maxY
        XCTAssertGreaterThan(lastRow, 0)
        XCTAssertLessThan(pill.frame.midY - lastRow, 30, "the pill sits right under its message")
        // an own message's reaction sits on its side, under the bubble's trailing edge (tester report: on the left)
        XCTAssertGreaterThan(pill.frame.midX, app.windows.firstMatch.frame.midX, "the own message's reaction on the trailing side")
    }

    /// The author's name on someone else's message: a long press opens the message options only, a tap the
    /// quick profile only (the long press's release never also taps).
    /// The chat server refuses the sign-in: a notice in the list instead of the floating pill, and no Esie button.
    func testARefusedConnectionShowsTheOfflineNoticeAndNoEsieButton() {
        let app = launch(["-startTab", "CHATS", "-demoChat", "offline"])
        XCTAssertTrue(element(app, "chats-offline").waitForExistence(timeout: 20), "the offline notice")
        XCTAssertTrue(element(app, "chats-offline").label.contains("Preparing your chat session"), "the connecting copy")
        XCTAssertFalse(app.buttons["chats-esie-fab"].exists)
    }

    /// The round Esie button over the list opens her direct message, as "Ask Esie" does.
    func testTheEsieButtonOpensHerConversation() {
        let app = launch(["-startTab", "CHATS"])
        XCTAssertTrue(element(app, "chat-row-ch-ecency").waitForExistence(timeout: 20))
        let fab = app.buttons["chats-esie-fab"]
        XCTAssertTrue(fab.waitForExistence(timeout: 10))
        XCTAssertEqual(fab.label, "Chat with Esie")
        let env = ProcessInfo.processInfo.environment
        if let dir = env["BG_SHOT_DIR"], !dir.isEmpty {
            try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("esie-list.png"))
        }
        fab.tap()
        XCTAssertTrue(app.staticTexts["chat-title"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["chat-title"].label, "esie")
    }

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

    private func message(_ app: XCUIApplication, containing text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    /// A long public channel (-demoChatHistory: 60 older messages) opens where its unread messages start, as RN does,
    /// without paging back on its own; scrolling up loads the older pages above, down to "No more messages".
    func testALongChannelOpensAtItsUnreadMessagesAndPagesBackOnScroll() {
        let app = launch(["-startTab", "CHATS", "-demoChatHistory", "60"])
        let row = element(app, "chat-row-ch-ecency")
        XCTAssertTrue(row.waitForExistence(timeout: 20))
        row.tap()
        let marker = element(app, "chat-unread-marker")
        XCTAssertTrue(marker.waitForExistence(timeout: 10))
        sleep(2)
        XCTAssertTrue(marker.isHittable, "the first unread message is on screen without scrolling")
        XCTAssertTrue(message(app, containing: "Yes, works well for me").isHittable, "the first unread message")
        XCTAssertFalse(message(app, containing: "Older message 1:").exists, "the oldest page waits for a scroll up")
        let list = element(app, "chat-messages")
        for _ in 0..<30 where !element(app, "chat-no-more").exists { list.swipeDown(velocity: .fast) }
        XCTAssertTrue(element(app, "chat-no-more").exists, "the older pages loaded above")
        XCTAssertTrue(message(app, containing: "Older message 1:").exists)
    }

    /// A direct message with a long history opens at its newest message, and a message of many lines shows whole.
    func testADirectMessageOpensAtItsNewestMessageAndShowsLongOnesWhole() {
        // the large text size the owner's device showed it at
        let app = launch(["-demoChatHistory", "60", "-openScreen", "chat:ch-dm-alice:D", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryL"])
        let newest = message(app, containing: "Do you have a minute?")
        XCTAssertTrue(newest.waitForExistence(timeout: 20))
        sleep(2)
        XCTAssertTrue(newest.isHittable, "the newest message is on screen without scrolling")
        XCTAssertLessThanOrEqual(newest.frame.maxY, element(app, "chat-messages").frame.maxY + 1)
        XCTAssertFalse(message(app, containing: "Older message 1:").exists, "the oldest page waits for a scroll up")
        // a reply from someone else: the author, a two line quote and a body of about ten lines (cut after about four
        // lines on a device, owner report); then the message it quotes, about thirteen lines
        let list = element(app, "chat-messages")
        let reply = message(app, containing: "the final words of the long reply")
        for _ in 0..<4 where !reply.isHittable { list.swipeDown() }
        XCTAssertTrue(reply.isHittable)
        XCTAssertGreaterThan(reply.frame.height, 240, "the long reply shows whole under its quote")
        // cut, the body gave its height to the quote above it: the quote's bar ran down past the quote's two lines
        XCTAssertLessThan(quoteBarHeight(app, in: reply.frame), 90, "the quote keeps its two lines and the body the rest")
        let long = message(app, containing: "the last line of the long message")
        for _ in 0..<4 where !long.isHittable { list.swipeDown() }
        XCTAssertTrue(long.isHittable)
        XCTAssertGreaterThan(long.frame.height, 200, "the long message shows whole")
    }

    /// The tallest run of the quote's accent bar (strong blue) in the first 60 pt of [frame], in points.
    private func quoteBarHeight(_ app: XCUIApplication, in frame: CGRect) -> CGFloat {
        let image = app.screenshot().image
        guard let cg = image.cgImage, let data = cg.dataProvider?.data, let bytes = CFDataGetBytePtr(data) else { return .infinity }
        let scale = CGFloat(cg.width) / image.size.width
        let perPixel = cg.bitsPerPixel / 8
        func blue(_ x: Int, _ y: Int) -> Bool {
            let i = y * cg.bytesPerRow + x * perPixel
            // BGRA or RGBA: the bar is the only pixel run with one channel high and the other two low
            let a = Int(bytes[i]), c = Int(bytes[i + 2])
            let (r, b) = cg.bitmapInfo.contains(.byteOrder32Little) ? (c, a) : (a, c)
            return b > 170 && r < 90 && Int(bytes[i + 1]) < 140
        }
        var best = 0
        let top = Int(frame.minY * scale), bottom = min(Int(frame.maxY * scale), cg.height - 1)
        for x in Int(frame.minX * scale)..<min(Int((frame.minX + 60) * scale), cg.width) {
            var run = 0
            for y in top...bottom {
                run = blue(x, y) ? run + 1 : 0
                best = max(best, run)
            }
        }
        return CGFloat(best) / scale
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

    /// Tester report (Android, checked here too): with the keyboard up the composer row sits right on top of it, no
    /// gap. Needs the on screen keyboard (see EditorUITests.requireSoftwareKeyboard).
    func testTheComposerSitsRightOnTopOfTheKeyboard() throws {
        let app = launch(["-openScreen", "chat:ch-group:G"])
        let composer = element(app, "chat-composer")
        XCTAssertTrue(composer.waitForExistence(timeout: 20), app.debugDescription)
        composer.tap()
        let keyboard = app.keyboards.firstMatch
        _ = keyboard.waitForExistence(timeout: 10)
        let window = app.windows.firstMatch.frame
        try XCTSkipUnless(keyboard.exists && keyboard.frame.height > 150 && keyboard.frame.minY < window.maxY - 150, "no on screen keyboard on this simulator: \(keyboard.frame)")
        sleep(1) // the keyboard settles
        let send = app.buttons["chat-send"].frame
        // the predictions bar is above the Keyboard element and part of what the keyboard covers
        let bar = app.otherElements["SystemInputAssistantView"]
        let top = bar.exists && bar.frame.height > 0 ? min(bar.frame.minY, keyboard.frame.minY) : keyboard.frame.minY
        // the row's bottom padding is 8 points
        XCTAssertLessThanOrEqual(send.maxY, top + 1, "above the keyboard: send \(send), keyboard top \(top)")
        XCTAssertGreaterThanOrEqual(send.maxY, top - 16, "right on top of the keyboard: send \(send), keyboard top \(top)")
    }

    func testWithAReplyQuoteTheFieldAndSendStayAboveTheKeyboard() throws {
        // the tester's screenshot: replying to a long message, the quote showed above the keyboard and the field under
        // it, so the typed words were hidden until a few new lines grew the field into view
        // opened from the Chats tab, as the tester did (the tab bar hides under the conversation)
        let app = launch(["-startTab", "CHATS", "-demoChatHistory", "3"])
        let row = element(app, "chat-row-ch-ecency")
        XCTAssertTrue(row.waitForExistence(timeout: 20))
        row.tap()
        // the original by its author and body: the reply under it quotes the same text in its label
        let msg = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "spike-carol, A long synthetic message")).firstMatch
        XCTAssertTrue(msg.waitForExistence(timeout: 20))
        msg.press(forDuration: 0.8)
        XCTAssertTrue(app.buttons["chat-option-REPLY"].waitForExistence(timeout: 5))
        app.buttons["chat-option-REPLY"].tap()
        XCTAssertTrue(app.buttons["chat-cancel-reply"].waitForExistence(timeout: 5))
        let composer = element(app, "chat-composer")
        composer.focus()
        let keyboard = app.keyboards.firstMatch
        _ = keyboard.waitForExistence(timeout: 10)
        let window = app.windows.firstMatch.frame
        try XCTSkipUnless(keyboard.exists && keyboard.frame.height > 150 && keyboard.frame.minY < window.maxY - 150, "no on screen keyboard on this simulator: \(keyboard.frame)")
        composer.typeText("hello")
        sleep(1) // the keyboard settles
        let bar = app.otherElements["SystemInputAssistantView"]
        let top = bar.exists && bar.frame.height > 0 ? min(bar.frame.minY, keyboard.frame.minY) : keyboard.frame.minY
        XCTAssertLessThanOrEqual(composer.frame.maxY, top + 1, "the field above the keyboard: field \(composer.frame), keyboard top \(top)")
        XCTAssertLessThanOrEqual(app.buttons["chat-send"].frame.maxY, top + 1, "send above the keyboard: \(app.buttons["chat-send"].frame), keyboard top \(top)")
    }

    func testReplyingWithTheKeyboardAlreadyUpKeepsTheFieldAboveIt() throws {
        // the field focused first, then Reply from the message menu: the keyboard comes back as the menu closes
        let app = launch(["-startTab", "CHATS", "-demoChatHistory", "3"])
        let row = element(app, "chat-row-ch-ecency")
        XCTAssertTrue(row.waitForExistence(timeout: 20))
        row.tap()
        let composer = element(app, "chat-composer")
        XCTAssertTrue(composer.waitForExistence(timeout: 20))
        composer.focus()
        let keyboard = app.keyboards.firstMatch
        _ = keyboard.waitForExistence(timeout: 10)
        let window = app.windows.firstMatch.frame
        try XCTSkipUnless(keyboard.exists && keyboard.frame.height > 150 && keyboard.frame.minY < window.maxY - 150, "no on screen keyboard on this simulator: \(keyboard.frame)")
        let msg = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "spike-carol, A long synthetic message")).firstMatch
        // the keyboard took the lower half: scroll back up to the long message, as a person would
        let list = element(app, "chat-messages")
        for _ in 0..<6 where !(msg.exists && msg.isHittable) { list.swipeDown() }
        XCTAssertTrue(msg.waitForExistence(timeout: 5))
        msg.press(forDuration: 0.8)
        XCTAssertTrue(app.buttons["chat-option-REPLY"].waitForExistence(timeout: 5))
        app.buttons["chat-option-REPLY"].tap()
        XCTAssertTrue(app.buttons["chat-cancel-reply"].waitForExistence(timeout: 5))
        composer.focus()
        composer.typeText("hello")
        sleep(1) // the keyboard settles
        let bar = app.otherElements["SystemInputAssistantView"]
        let top = bar.exists && bar.frame.height > 0 ? min(bar.frame.minY, keyboard.frame.minY) : keyboard.frame.minY
        XCTAssertLessThanOrEqual(composer.frame.maxY, top + 1, "the field above the keyboard: field \(composer.frame), keyboard top \(top)")
        XCTAssertLessThanOrEqual(app.buttons["chat-send"].frame.maxY, top + 1, "send above the keyboard: \(app.buttons["chat-send"].frame), keyboard top \(top)")
    }

    func testTheFieldFollowsTheKeyboardWhenItChangesHeight() throws {
        // a globe or emoji switch while typing changes the keyboard's height without hiding it (founder's report:
        // the typed words sat under the keyboard after replying, until new lines grew the field into view)
        let app = launch(["-startTab", "CHATS", "-demoChatHistory", "3"])
        let row = element(app, "chat-row-ch-ecency")
        XCTAssertTrue(row.waitForExistence(timeout: 20))
        row.tap()
        let msg = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "spike-carol, A long synthetic message")).firstMatch
        XCTAssertTrue(msg.waitForExistence(timeout: 20))
        msg.press(forDuration: 0.8)
        XCTAssertTrue(app.buttons["chat-option-REPLY"].waitForExistence(timeout: 5))
        app.buttons["chat-option-REPLY"].tap()
        let composer = element(app, "chat-composer")
        composer.focus()
        let keyboard = app.keyboards.firstMatch
        _ = keyboard.waitForExistence(timeout: 10)
        let window = app.windows.firstMatch.frame
        try XCTSkipUnless(keyboard.exists && keyboard.frame.height > 150 && keyboard.frame.minY < window.maxY - 150, "no on screen keyboard on this simulator: \(keyboard.frame)")
        composer.typeText("hello")
        func assertAbove(_ when: String) {
            sleep(1) // the keyboard settles
            let bar = app.otherElements["SystemInputAssistantView"]
            let top = bar.exists && bar.frame.height > 0 ? min(bar.frame.minY, keyboard.frame.minY) : keyboard.frame.minY
            XCTAssertLessThanOrEqual(composer.frame.maxY, top + 1, "\(when): field \(composer.frame), keyboard \(keyboard.frame), bar \(bar.frame)")
        }
        assertAbove("letters")
        // on Face ID phones the globe and emoji keys sit in the strip under the keys, outside the keyboard element
        // the emoji keyboard: taller than the letters (a globe key may land on a keyboard of the same height)
        let emojiKey = app.descendants(matching: .any).matching(NSPredicate(format: "label ==[c] %@", "Emoji")).firstMatch
        try XCTSkipUnless(emojiKey.exists, "no emoji key: \(app.keys.allElementsBoundByIndex.prefix(40).map(\.label))")
        let before = keyboard.frame
        emojiKey.tap()
        let changed = NSPredicate { _, _ in keyboard.frame.height != before.height }
        XCTAssertEqual(XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: changed, object: nil)], timeout: 5), .completed, "the keyboard changed height: \(before) -> \(keyboard.frame)")
        assertAbove("on the emoji keyboard (letters were \(before))")
        let back = app.descendants(matching: .any).matching(NSPredicate(format: "label ==[c] %@", "ABC")).firstMatch
        if back.exists {
            back.tap()
            let restored = NSPredicate { _, _ in keyboard.frame.height == before.height }
            XCTAssertEqual(XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: restored, object: nil)], timeout: 5), .completed, "back to the letters: \(keyboard.frame)")
            assertAbove("back on the letters")
        }
    }

    func testAGroupOpensByIdWithItsName() {
        let app = launch(["-openScreen", "chat:ch-group:G"])
        XCTAssertTrue(app.staticTexts["chat-title"].waitForExistence(timeout: 20))
        let named = expectation(for: NSPredicate(format: "label == 'Book club'"), evaluatedWith: app.staticTexts["chat-title"])
        wait(for: [named], timeout: 10)
        XCTAssertTrue(app.buttons["chat-group-options"].exists)
    }

    func testTheOptionsSheetFitsItsRowsAndTheReplyQuoteItsLines() {
        // the tester's screenshots: the options sheet opened at half the screen with a gap under its rows, and the
        // reply quote above the composer took most of its height
        let app = launch(["-openScreen", "chat:ch-ecency"])
        let msg = message(app, containing: "Has anyone tried")
        XCTAssertTrue(msg.waitForExistence(timeout: 20))
        msg.press(forDuration: 0.8)
        let sheet = element(app, "chat-options-sheet")
        XCTAssertTrue(sheet.waitForExistence(timeout: 5))
        let last = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'chat-option-'")).allElementsBoundByIndex.last!
        XCTAssertLessThan(app.windows.firstMatch.frame.maxY - last.frame.maxY, 120, "no gap under the last row")
        app.buttons["chat-option-REPLY"].tap()
        let quote = element(app, "chat-quote")
        XCTAssertTrue(app.buttons["chat-cancel-reply"].waitForExistence(timeout: 5))
        let composerQuote = app.descendants(matching: .any).matching(identifier: "chat-quote").allElementsBoundByIndex.first { $0.descendants(matching: .any)["chat-cancel-reply"].exists } ?? quote
        XCTAssertLessThan(composerQuote.frame.height, 90, "the quote is as tall as its two lines")
    }

    /// #3468: the demo notes message shows web's blocks; VoiceOver reads it as one message, each item after its marker
    /// and no markdown left in it.
    func testAMessageWithBlocksReadsItsItemsInOrder() {
        let app = launch(["-openScreen", "chat:ch-ecency"])
        let msg = message(app, containing: "Club notes")
        XCTAssertTrue(msg.waitForExistence(timeout: 20))
        for _ in 0..<4 where !msg.isHittable { element(app, "chat-messages").swipeDown() }
        XCTAssertTrue(msg.isHittable)
        let label = msg.label
        for part in ["Club notes", "\u{2022} read the guide first", "\u{2022} say hi to @spike-alice", "Bring your questions", "1. pick a book", "2. share a quote"] {
            XCTAssertTrue(label.contains(part), "\(part) in \(label)")
        }
        XCTAssertLessThan(label.range(of: "Club notes")!.lowerBound, label.range(of: "2. share a quote")!.lowerBound)
        for typed in ["# Club", "- read", "> Bring", "**guide**"] {
            XCTAssertFalse(label.contains(typed), "\(typed) in \(label)")
        }
    }

    func testAPostLinkCardOpensThePostInTheAppNotTheBrowser() {
        let app = launch(["-openScreen", "chat:ch-ecency"])
        let card = element(app, "chat-link-card")
        XCTAssertTrue(card.waitForExistence(timeout: 20))
        for _ in 0..<4 where !card.isHittable { app.swipeDown() }
        card.tap()
        // the post screen of the app, never the in-app browser (SFSafariViewController's Done button)
        XCTAssertTrue(element(app, "post-views").waitForExistence(timeout: 15), app.debugDescription)
        XCTAssertFalse(app.buttons["Done"].exists)
    }

    func testSettingsHasChatPrivacySignedIn() {
        let app = launch(["-openScreen", "settings"])
        XCTAssertTrue(app.buttons["settings-dm-privacy"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["settings-dm-privacy"].label.contains("Everyone"))
    }
}
