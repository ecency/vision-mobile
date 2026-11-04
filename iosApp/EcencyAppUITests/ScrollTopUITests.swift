import XCTest

/// Back to the top of a list: the scroll to top button while the list still decelerates, and a tap on the selected
/// tab or filter (RN TabbedPosts onTabPress). `-fakeFeedSize` makes the fake feeds long enough to scroll away.
final class ScrollTopUITests: XCTestCase {
    private func launch(_ extra: [String]) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-seedAccounts", "YES", "-fakeFeedSize", "40"] + extra
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    /// The list's first card (lazy rows far above are not in the tree at all).
    private func firstCard(_ app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "identifier == 'post-card' AND label BEGINSWITH 'Fixture post number 1,'")).firstMatch
    }

    /// The list on screen with [id] (the Home tab's list stays alive behind a pushed screen).
    private func list(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        let all = app.scrollViews.matching(identifier: id)
        XCTAssertTrue(all.firstMatch.waitForExistence(timeout: 20), app.debugDescription)
        for i in 0..<all.count where all.element(boundBy: i).isHittable { return all.element(boundBy: i) }
        return all.firstMatch
    }

    private func atTop(_ app: XCUIApplication) -> Bool { firstCard(app).exists && firstCard(app).isHittable }

    private func scrollAway(_ app: XCUIApplication, _ list: XCUIElement) {
        XCTAssertTrue(firstCard(app).waitForExistence(timeout: 20), app.debugDescription)
        for _ in 0..<8 where atTop(app) || firstCard(app).exists { list.swipeUp(velocity: .fast) }
        XCTAssertFalse(atTop(app), "scrolled away from the first card")
    }

    private func waitAtTop(_ app: XCUIApplication, _ what: String) {
        let deadline = Date().addingTimeInterval(5)
        while Date() < deadline, !atTop(app) { RunLoop.current.run(until: Date().addingTimeInterval(0.2)) }
        XCTAssertTrue(atTop(app), what)
    }

    func testTheScrollTopButtonStopsAFlingAndGoesToTheTop() {
        let app = launch(["-demoScrollTop", "YES", "-homeFilter", "trending"])
        let scroll = list(app, "feed-list")
        scrollAway(app, scroll)
        let top = app.buttons["feed-scroll-top"]
        XCTAssertTrue(top.exists)
        // a fast flick, then a tap on the button 0.3 s after the finger lifted, while the list still decelerates. One
        // synthesized record: XCUITest waits for the app to idle (the deceleration to end) between separate gestures.
        let frame = scroll.frame
        let x = frame.midX
        let button = CGPoint(x: top.frame.midX, y: top.frame.midY)
        let flick = TouchPath(at: CGPoint(x: x, y: frame.minY + frame.height * 0.75), offset: 0)
        flick.move(to: CGPoint(x: x, y: frame.minY + frame.height * 0.35), offset: 0.06)
        flick.lift(offset: 0.06)
        let tap = TouchPath(at: button, offset: 0.36)
        tap.lift(offset: 0.42)
        TouchPath.synthesize([flick, tap])
        waitAtTop(app, "the deceleration did not swallow the scroll to top")
        RunLoop.current.run(until: Date().addingTimeInterval(1.5))
        XCTAssertTrue(atTop(app), "and it stays there")
    }

    func testATapOnTheSelectedHomeFilterScrollsItsFeedToTheTop() {
        let app = launch(["-homeFilter", "trending"])
        scrollAway(app, list(app, "feed-list"))
        app.buttons["filter-TRENDING"].tap()
        waitAtTop(app, "the selected filter scrolls its feed to the top")
        XCTAssertTrue(app.buttons["filter-TRENDING"].isSelected)
    }

    func testATapOnTheSelectedCommunityTabScrollsToTheTop() {
        let app = launch(["-openScreen", "route:COMMUNITY:hive-125125"])
        scrollAway(app, list(app, "feed-list"))
        app.buttons["tab-TRENDING"].tap()
        waitAtTop(app, "the selected community tab scrolls its feed to the top")
    }

    func testATapOnTheSelectedProfileTabScrollsToTheTop() {
        let app = launch(["-openLink", "https://ecency.com/@spike-bob"])
        let scroll = list(app, "profile-list")
        scrollAway(app, scroll)
        app.buttons["tab-blog"].tap()
        waitAtTop(app, "the selected profile tab scrolls its list to the top")
    }

    /// The profile summary opens again only back at the top, not on a scroll up through the list.
    func testTheProfileSummaryStaysClosedOnAScrollUpMidList() {
        let app = launch(["-openLink", "https://ecency.com/@spike-bob"])
        let scroll = list(app, "profile-list")
        scrollAway(app, scroll)
        let compact = element(app, "profile-compact-header")
        XCTAssertTrue(compact.waitForExistence(timeout: 5), "closed after scrolling down")
        RunLoop.current.run(until: Date().addingTimeInterval(1))
        scroll.swipeDown(velocity: .slow)
        RunLoop.current.run(until: Date().addingTimeInterval(1))
        XCTAssertTrue(compact.exists, "a scroll up mid-list keeps it closed")
        XCTAssertFalse(atTop(app))
    }
}

// MARK: touch paths in one record (XCTest's event synthesis, as WebDriverAgent drives it)

@objc private protocol PointerEventPath {
    @objc(initForTouchAtPoint:offset:) func initForTouch(at point: CGPoint, offset: Double) -> NSObject
    @objc(moveToPoint:atOffset:) func move(to point: CGPoint, atOffset offset: Double)
    @objc(liftUpAtOffset:) func liftUp(atOffset offset: Double)
}

@objc private protocol SynthesizedEventRecord {
    @objc(initWithName:interfaceOrientation:) func initRecord(name: String, interfaceOrientation: Int) -> NSObject
    @objc(addPointerEventPath:) func add(_ path: NSObject)
}

@objc private protocol EventSynthesizer {
    @objc(synthesizeEvent:completion:) func synthesize(_ record: NSObject, completion: @escaping @convention(block) (Bool, Error?) -> Void)
}

/// One finger's touches (window points, seconds from the start of the record).
private final class TouchPath {
    let path: NSObject

    init(at point: CGPoint, offset: Double) {
        let type = NSClassFromString("XCPointerEventPath") as! NSObject.Type
        path = unsafeBitCast(type.init(), to: PointerEventPath.self).initForTouch(at: point, offset: offset)
    }

    func move(to point: CGPoint, offset: Double) { unsafeBitCast(path, to: PointerEventPath.self).move(to: point, atOffset: offset) }
    func lift(offset: Double) { unsafeBitCast(path, to: PointerEventPath.self).liftUp(atOffset: offset) }

    /// Plays [paths] together, without XCUITest's wait for the app to idle between them.
    static func synthesize(_ paths: [TouchPath]) {
        let type = NSClassFromString("XCSynthesizedEventRecord") as! NSObject.Type
        let record = unsafeBitCast(type.init(), to: SynthesizedEventRecord.self).initRecord(name: "touches", interfaceOrientation: 1)
        paths.forEach { unsafeBitCast(record, to: SynthesizedEventRecord.self).add($0.path) }
        let synthesizer = XCUIDevice.shared.perform(NSSelectorFromString("eventSynthesizer")).takeUnretainedValue()
        let done = XCTestExpectation(description: "synthesized")
        unsafeBitCast(synthesizer, to: EventSynthesizer.self).synthesize(record) { ok, error in
            XCTAssertTrue(ok, String(describing: error))
            done.fulfill()
        }
        XCTAssertEqual(XCTWaiter.wait(for: [done], timeout: 10), .completed)
    }
}
