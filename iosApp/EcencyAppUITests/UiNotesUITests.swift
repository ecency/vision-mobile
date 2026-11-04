import XCTest

/// The owner's UI notes against the debug fake transport: the scroll to top button as the FAB's twin, the post bar's
/// tip count and comments-as-reply, the profile cover right under the bar.
final class UiNotesUITests: XCTestCase {
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

    // MARK: 1. scroll to top

    /// `-demoScrollTop YES` shows the feed's scroll to top button at once (it otherwise needs 5000 points scrolled up).
    private func assertFabTwins(_ app: XCUIApplication, rtl: Bool) {
        let top = app.buttons["feed-scroll-top"]
        let fab = app.buttons["create-fab"]
        XCTAssertTrue(top.waitForExistence(timeout: 20), app.debugDescription)
        XCTAssertTrue(fab.exists)
        let window = app.windows.firstMatch.frame
        XCTAssertEqual(top.frame.width, fab.frame.width, accuracy: 0.5, "the FAB's size: \(top.frame) vs \(fab.frame)")
        XCTAssertEqual(top.frame.height, fab.frame.height, accuracy: 0.5)
        XCTAssertEqual(top.frame.width, 56, accuracy: 0.5)
        XCTAssertEqual(top.frame.maxY, fab.frame.maxY, accuracy: 0.5, "on the FAB's baseline")
        XCTAssertFalse(top.frame.intersects(fab.frame), "\(top.frame) apart from \(fab.frame)")
        if rtl {
            XCTAssertEqual(top.frame.maxX, window.maxX - 16, accuracy: 0.5, "16 pt from the start (right) edge")
        } else {
            XCTAssertEqual(top.frame.minX, window.minX + 16, accuracy: 0.5, "16 pt from the start edge")
        }
        // RN home.scroll_top (the pseudo locale wraps it)
        XCTAssertTrue(top.label.contains("Scroll To Top"), top.label)
    }

    func testTheScrollTopButtonIsTheFabsTwinAtTheBottomStart() {
        assertFabTwins(launch(["-demoScrollTop", "YES", "-homeFilter", "trending"]), rtl: false)
    }

    func testInRtlTheScrollTopButtonMirrors() {
        assertFabTwins(launch(["-demoScrollTop", "YES", "-homeFilter", "trending", "-pseudoLocale", "ar-XB"]), rtl: true)
    }

    // MARK: 2. the post bar

    private func openFirstPost(_ app: XCUIApplication) {
        let card = app.buttons.matching(identifier: "post-card").firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 20))
        card.tap()
        XCTAssertTrue(app.staticTexts["post-title"].waitForExistence(timeout: 10))
    }

    func testThePostBarShowsTheTipCountAndItsCommentsIconLeadsToTheReply() {
        let app = launch(["-homeFilter", "trending"])
        openFirstPost(app)
        let bar = element(app, "post-footer")
        XCTAssertTrue(bar.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["reply-post"].exists, "no Reply button in the bar")
        // RN tipsQuery: the count beside the gift (the demo API has 2 tips), said with the gift
        let tip = bar.buttons["tip"]
        XCTAssertTrue(tip.exists)
        let count = element(app, "post-tip-count")
        XCTAssertTrue(count.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertEqual(count.label, "2")
        XCTAssertEqual(tip.label, "Send tip")
        XCTAssertEqual(tip.value as? String, "2 tips")
        // the comments icon with its count: says what it does and how many, and leads to the comments' Reply
        let comments = bar.buttons["post-comments"]
        XCTAssertTrue(comments.exists)
        XCTAssertTrue(comments.label.hasSuffix("comment") || comments.label.hasSuffix("comments"), comments.label)
        XCTAssertGreaterThanOrEqual(comments.frame.height, 44)
        let reply = app.buttons["comments-reply"]
        XCTAssertFalse(reply.exists && reply.isHittable, "the comments' Reply is below the fold at first")
        comments.tap()
        XCTAssertTrue(reply.waitForExistence(timeout: 10))
        let hittable = expectation(for: NSPredicate(format: "isHittable == true"), evaluatedWith: reply)
        wait(for: [hittable], timeout: 10)
        reply.tap()
        XCTAssertTrue(app.textViews["reply-text"].waitForExistence(timeout: 10))
    }

    // MARK: 3. profile cover

    private func assertCoverUnderTheBar(_ app: XCUIApplication, _ who: String) {
        let cover = element(app, "profile-cover")
        XCTAssertTrue(cover.waitForExistence(timeout: 20), app.debugDescription)
        let bar = app.navigationBars.firstMatch
        XCTAssertTrue(bar.exists)
        XCTAssertEqual(cover.frame.minY, bar.frame.maxY, accuracy: 1, "\(who): the cover \(cover.frame) right under the bar \(bar.frame)")
    }

    func testTheCoverSitsRightUnderTheBarOnAnotherProfileAndTheTabsUnderTheSummary() {
        let app = launch(["-openLink", "https://ecency.com/@spike-bob"])
        assertCoverUnderTheBar(app, "spike-bob")
        let summary = element(app, "profile-summary")
        let tabs = app.buttons["tab-blog"]
        XCTAssertTrue(tabs.waitForExistence(timeout: 10))
        XCTAssertLessThan(tabs.frame.minY - summary.frame.maxY, 2, "no gap between \(summary.frame) and the tabs \(tabs.frame)")
    }

    func testTheCoverSitsRightUnderTheBarOnTheOwnProfileInDark() {
        let app = launch(["-openLink", "https://ecency.com/@spike-alice", "-forceDarkTheme", "YES"])
        assertCoverUnderTheBar(app, "spike-alice")
    }

    // MARK: review round

    func testOnWavesTheScrollTopIsTheComposeFabsTwin() {
        let app = launch(["-demoScrollTop", "YES"])
        XCTAssertTrue(app.buttons["tab-waves"].waitForExistence(timeout: 20))
        app.buttons["tab-waves"].tap()
        let top = app.buttons["waves-scroll-top"]
        let fab = app.buttons["wave-fab"]
        XCTAssertTrue(top.waitForExistence(timeout: 20), app.debugDescription)
        XCTAssertTrue(fab.exists)
        XCTAssertEqual(top.frame.size.width, fab.frame.size.width, accuracy: 0.5)
        XCTAssertEqual(top.frame.size.height, fab.frame.size.height, accuracy: 0.5)
        XCTAssertEqual(top.frame.maxY, fab.frame.maxY, accuracy: 0.5, "on the FAB's baseline")
        XCTAssertEqual(top.frame.minX, app.windows.firstMatch.frame.minX + 16, accuracy: 0.5)
        XCTAssertFalse(top.frame.intersects(fab.frame))
    }

    /// The community's Details card shares the profile's capped header: the pills follow the card, no gap.
    func testTheCommunityDetailsHugTheirContent() {
        let app = launch(["-openScreen", "route:COMMUNITY:hive-125125"])
        let body = element(app, "community-details-body")
        XCTAssertTrue(body.waitForExistence(timeout: 20), app.debugDescription)
        let pill = app.buttons["tab-TRENDING"]
        XCTAssertTrue(pill.waitForExistence(timeout: 10))
        // only the paddings: the body's 16 pt bottom (outside its accessibility frame), the card's 12 pt and the pill row's
        // 4 pt (the old header added its spare height here: 166 pt)
        XCTAssertEqual(pill.frame.minY - body.frame.maxY, 32, accuracy: 1.5, "\(body.frame) then \(pill.frame)")
    }

    /// At AX3 the summary and the tabs would leave the list no room, so the summary starts closed.
    func testTheProfileListKeepsRoomAtLargeText() {
        let app = launch(["-openLink", "https://ecency.com/@spike-bob", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXL"])
        let list = app.scrollViews["profile-list"]
        XCTAssertTrue(list.waitForExistence(timeout: 20), app.debugDescription)
        RunLoop.current.run(until: Date().addingTimeInterval(1.5))
        XCTAssertGreaterThan(list.frame.height, 120, "the list keeps room: \(list.frame)")
        XCTAssertTrue(element(app, "profile-compact-header").exists, "the summary starts closed")
        // it still opens on demand
        element(app, "profile-compact-header").tap()
        XCTAssertTrue(element(app, "profile-summary").waitForExistence(timeout: 5))
    }

    // MARK: the size rule holds across tab switches

    /// At accessibility text sizes the summary starts closed and a tab switch keeps it closed (a switch otherwise opens
    /// it over the new list). The app is portrait only on iPhone (project.yml), so the compact height branch of the
    /// rule cannot be reached here.
    func testAtLargeTextATabSwitchKeepsTheSummaryClosed() {
        let app = launch(["-openLink", "https://ecency.com/@spike-bob", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityM"])
        XCTAssertTrue(element(app, "profile-compact-header").waitForExistence(timeout: 20), app.debugDescription)
        let posts = app.buttons["tab-posts"]
        XCTAssertTrue(posts.waitForExistence(timeout: 10))
        posts.tap()
        RunLoop.current.run(until: Date().addingTimeInterval(1.5))
        XCTAssertTrue(element(app, "profile-compact-header").exists, "a tab switch keeps it closed at large text")
        XCTAssertFalse(element(app, "profile-summary").exists)
        // it still opens on demand
        element(app, "profile-compact-header").tap()
        XCTAssertTrue(element(app, "profile-summary").waitForExistence(timeout: 5))
    }

    /// The community card keeps the same rule on a tab switch.
    func testAtLargeTextACommunityTabSwitchKeepsTheCardClosed() {
        let app = launch(["-openScreen", "route:COMMUNITY:hive-125125", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityM"])
        XCTAssertTrue(element(app, "community-details-toggle").waitForExistence(timeout: 20), app.debugDescription)
        XCTAssertFalse(element(app, "community-details-body").exists, "starts closed at large text")
        let hot = app.buttons["tab-HOT"]
        XCTAssertTrue(hot.waitForExistence(timeout: 10))
        hot.tap()
        RunLoop.current.run(until: Date().addingTimeInterval(1.5))
        XCTAssertFalse(element(app, "community-details-body").exists, "a tab switch keeps it closed at large text")
    }

    // MARK: 6. comment menu

    /// RN commentView: the comment's dots sit on its author line at the trailing edge (PostHeaderDescription
    /// showDotMenuButton), not in the actions under the body.
    func testTheCommentsMenuSitsOnItsAuthorLine() {
        let app = launch(["-openPost", "spike-alice/fake-post-1"])
        XCTAssertTrue(app.staticTexts["post-title"].waitForExistence(timeout: 20))
        let author = app.buttons.matching(NSPredicate(format: "identifier == 'card-author-name' AND label == 'spike-carol'")).firstMatch
        let reply = app.buttons.matching(NSPredicate(format: "identifier == 'reply-comment' AND label CONTAINS 'spike-carol'")).firstMatch
        for _ in 0..<10 where !(author.exists && reply.exists && reply.isHittable) { app.swipeUp(velocity: .slow) }
        XCTAssertTrue(author.exists && reply.exists, "the first comment came into the list")
        // the post's own dots are in the navigation bar; the comment's are below it
        let below = app.navigationBars.firstMatch.frame.maxY
        let menus = app.buttons.matching(identifier: "post-options").allElementsBoundByIndex.filter { $0.frame.minY >= below }
        let onAuthorLine = menus.filter { $0.frame.minY < author.frame.maxY && $0.frame.maxY > author.frame.minY }
        XCTAssertEqual(onAuthorLine.count, 1, "the dots on the author line \(author.frame): \(menus.map(\.frame))")
        let onActionLine = menus.filter { $0.frame.minY < reply.frame.maxY && $0.frame.maxY > reply.frame.minY }
        XCTAssertTrue(onActionLine.isEmpty, "no dots in the actions \(reply.frame): \(onActionLine.map(\.frame))")
        if let dots = onAuthorLine.first {
            XCTAssertEqual(dots.frame.maxX, app.windows.firstMatch.frame.maxX - 16, accuracy: 8, "at the trailing edge: \(dots.frame)")
            dots.tap()
            // RN comment menu: translate and open thread
            XCTAssertTrue(app.buttons["menu-item-translate"].waitForExistence(timeout: 10), app.debugDescription)
            XCTAssertTrue(app.buttons["menu-item-open-thread"].exists)
        }
    }
}
