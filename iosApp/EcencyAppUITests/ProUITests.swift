import XCTest

/// Ecency Pro on Perks (issue #3555) against the debug fake data and its offline store: nothing is bought or charged.
final class ProUITests: XCTestCase {
    /// Saves the screen when the run asks for it (`TEST_RUNNER_PRO_SHOT_DIR=<folder>` in xcodebuild's environment).
    private func shot(_ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["PRO_SHOT_DIR"], !dir.isEmpty else { return }
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("pro-ios-\(name).png"))
    }

    private func launch(_ extra: [String]) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-seedAccounts", "YES", "-openScreen", "perks"] + extra
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement { app.descendants(matching: .any)[id].firstMatch }

    func testANonMemberBuysProFromTheCard() {
        let app = launch(["-demoNoPro", "YES"])
        let card = app.buttons["pro-card"]
        XCTAssertTrue(card.waitForExistence(timeout: 20))
        XCTAssertTrue(card.label.contains("$19.99"), card.label)
        shot("card")
        card.tap()
        XCTAssertTrue(element(app, "pro-benefit-0").waitForExistence(timeout: 10))
        XCTAssertTrue(element(app, "pro-benefit-3").exists)
        XCTAssertTrue(app.staticTexts["A free blog at yourname.blogs.ecency.com."].exists)
        let buy = app.buttons["pro-buy"]
        XCTAssertTrue(buy.waitForExistence(timeout: 10))
        XCTAssertEqual(buy.label, "Get Pro for $19.99 a year")
        shot("sheet")
        buy.tap()
        XCTAssertTrue(element(app, "pro-success").waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["Welcome to Ecency Pro"].exists)
        XCTAssertFalse(app.buttons["pro-buy"].exists)
        shot("success")
        app.buttons["pro-close"].tap()
        XCTAssertTrue(element(app, "pro-card-member").waitForExistence(timeout: 10))
        shot("member")
    }

    func testAMemberSeesTheMemberStateAndCanExtend() {
        let app = launch([])
        XCTAssertTrue(element(app, "pro-card-member").waitForExistence(timeout: 20))
        app.buttons["pro-card"].tap()
        XCTAssertTrue(element(app, "pro-member").waitForExistence(timeout: 10))
        let buy = app.buttons["pro-buy"]
        XCTAssertTrue(buy.waitForExistence(timeout: 10))
        XCTAssertEqual(buy.label, "Extend by 12 months for $19.99")
        shot("member-sheet")
    }

    /// #3636: a member sees when the membership ends (the Points API's `pro.expires`; the demo's is 27 July 2027).
    func testAMemberSeesWhenTheMembershipEnds() {
        let app = launch([])
        let card = element(app, "pro-card-expiry")
        XCTAssertTrue(card.waitForExistence(timeout: 20))
        XCTAssertTrue(card.label.hasPrefix("Your membership runs until "), card.label)
        XCTAssertTrue(card.label.contains("2027"), card.label)
        app.buttons["pro-card"].tap()
        let sheet = element(app, "pro-expiry")
        XCTAssertTrue(sheet.waitForExistence(timeout: 10))
        XCTAssertEqual(sheet.label, card.label)
    }

    /// #3636: a member claims the free blog (the demo's offline claim) and sees its address with Open.
    func testAMemberClaimsTheFreeBlog() {
        let app = launch([])
        XCTAssertTrue(element(app, "pro-card-member").waitForExistence(timeout: 20))
        app.buttons["pro-card"].tap()
        let claim = app.buttons["pro-blog-claim"]
        XCTAssertTrue(claim.waitForExistence(timeout: 10))
        XCTAssertEqual(claim.label, "Claim your free blog")
        claim.tap()
        XCTAssertTrue(app.staticTexts["Ecency Pro includes a free blog at spike-alice.blogs.ecency.com. It starts with the default look, which you can change later."].waitForExistence(timeout: 10))
        let title = app.textFields["pro-blog-title"]
        XCTAssertTrue(title.waitForExistence(timeout: 10))
        let confirm = app.buttons["pro-blog-confirm"]
        let deadline = Date().addingTimeInterval(10)
        while !confirm.isEnabled && Date() < deadline { RunLoop.current.run(until: Date().addingTimeInterval(0.2)) }
        XCTAssertTrue(confirm.isEnabled)
        shot("blog-form")
        confirm.tap()
        let address = element(app, "pro-blog-address")
        XCTAssertTrue(address.waitForExistence(timeout: 10))
        XCTAssertEqual(address.label, "spike-alice.blogs.ecency.com")
        XCTAssertEqual(element(app, "pro-blog-heading").label, "Your blog is ready")
        XCTAssertTrue(app.buttons["pro-blog-open"].exists)
        XCTAssertFalse(app.buttons["pro-blog-claim"].exists)
        shot("blog-claimed")
    }

    func testANonMemberHasNoBlogClaim() {
        let app = launch(["-demoNoPro", "YES"])
        XCTAssertTrue(element(app, "pro-card-text").waitForExistence(timeout: 20))
        app.buttons["pro-card"].tap()
        XCTAssertTrue(element(app, "pro-benefit-0").waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["pro-blog-claim"].exists)
    }

    func testANonMemberSeesNoEndDate() {
        let app = launch(["-demoNoPro", "YES"])
        XCTAssertTrue(element(app, "pro-card-text").waitForExistence(timeout: 20))
        XCTAssertFalse(element(app, "pro-card-expiry").exists)
    }

    // MARK: - the badge beside a member's name (RN ProBadge)

    /** The fake feed with spike-alice (the demo roster) and spike-bob (the first comment's author) on the roster. */
    private func launchFeed(_ extra: [String] = []) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-homeFilter", "trending", "-demoProMember", "spike-bob"] + extra
        app.launch()
        return app
    }

    private func labelled(_ app: XCUIApplication, _ text: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    func testTheBadgeIsPartOfAMembersAuthorLineOnTheCardThePostAndAComment() {
        let app = launchFeed()
        let card = app.buttons.matching(identifier: "post-card").firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 20))
        XCTAssertTrue(labelled(app, "spike-alice, Ecency Pro member").waitForExistence(timeout: 10), "the card's author line says it")
        XCTAssertFalse(labelled(app, "spike-carol, Ecency Pro member").exists, "spike-carol is no member")
        XCTAssertTrue(card.label.hasPrefix("Fixture post number 1, by spike-alice"), "the card stays one stop: \(card.label)")
        card.tap()
        XCTAssertTrue(app.staticTexts["post-title"].waitForExistence(timeout: 10))
        let header = labelled(app, "spike-alice, Ecency Pro member")
        XCTAssertTrue(header.waitForExistence(timeout: 10) && header.isHittable, "the post header's author line")
        let comment = labelled(app, "spike-bob, Ecency Pro member")
        for _ in 0..<6 where !(comment.exists && comment.isHittable) { app.swipeUp() }
        XCTAssertTrue(comment.exists && comment.isHittable, "the comment's author line")
    }

    func testAMemberVoterShowsTheBadge() {
        let app = launchFeed(["-openScreen", "voters"])
        let alice = app.buttons["voter-spike-alice"]
        XCTAssertTrue(alice.waitForExistence(timeout: 20))
        let deadline = Date().addingTimeInterval(10)
        while !alice.label.contains("Ecency Pro member") && Date() < deadline { RunLoop.current.run(until: Date().addingTimeInterval(0.2)) }
        XCTAssertTrue(alice.label.contains("spike-alice, Ecency Pro member"), alice.label)
        XCTAssertFalse(app.buttons["voter-spike-carol"].label.contains("Ecency Pro member"))
    }
}
