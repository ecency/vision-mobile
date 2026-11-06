import UIKit
import XCTest

/// README "Accessibility" (iOS): Xcode's accessibility audit on the main screens, at the default text size and at
/// the largest accessibility size, plus the VoiceOver labels and traits of the shell. Runs on fake data.
final class AccessibilityUITests: XCTestCase {
    /// The largest Dynamic Type size, set for the app only (the simulator's own setting is left alone).
    static let ax5 = ["-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]

    private func launch(_ extra: [String]) -> XCUIApplication {
        continueAfterFailure = true
        let app = XCUIApplication()
        // what VoiceOver and Switch Control users get (screens that keep hidden content mounted unmount it then)
        app.launchArguments += ["-fakeData", "YES", "-homeFilter", "trending", "-assistiveTechnology", "YES"] + extra
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    /// Runs the audit and fails once per screen with every issue it did not excuse.
    private func audit(_ app: XCUIApplication, _ screen: String, ready id: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(element(app, id).waitForExistence(timeout: 25), "\(screen): \(id) never showed", file: file, line: line)
        // The audit takes the elements' frames and then reads pixels there, over several seconds. Text still fading
        // in, or moved by a late layout change (a feed card's image that loaded during the audit pushed its title
        // down onto the picture), reads as low contrast. So the audit runs on a still screen, and its findings count
        // only when the screen after it is the one it started on; otherwise it runs again on the settled screen.
        Thread.sleep(forTimeInterval: 1)
        var findings: (found: [String], unresolved: [String]) = ([], [])
        for attempt in 1...3 {
            guard let before = waitUntilStill(app, screen) else { return }
            findings = auditOnce(app, screen, file: file, line: line)
            guard let after = waitUntilStill(app, screen) else { return }
            let moved = Self.changedPixels(before.pixels, after.pixels, ignoring: before.ignored + after.ignored)
            if moved <= Self.stillTolerance { break }
            print("AccessibilityUITests \(screen): the screen changed during audit \(attempt) (\(moved) px), " + (attempt < 3 ? "audited again" : "kept"))
        }
        var found = findings.found
        let unresolved = findings.unresolved
        // every run lists them (test log), and only the descriptions known for this screen pass
        if !unresolved.isEmpty { print("AccessibilityUITests \(screen): unresolved: " + unresolved.joined(separator: "; ")) }
        // a bounded allowance per screen and description (the numbers measured on 2026-10-27); more fails
        let allowed = AuditExclusions.allowance(screen)
        for (description, count) in Dictionary(grouping: unresolved, by: { $0 }).mapValues(\.count) where count > allowed[description] ?? 0 {
            found.append("\(count) unresolved \"\(description)\", at most \(allowed[description] ?? 0) allowed here")
        }
        if !found.isEmpty {
            XCTFail("\(screen): \(found.count) accessibility issue(s):\n" + found.joined(separator: "\n"), file: file, line: line)
        }
    }

    /// One run of Xcode's audit: the issues it did not excuse, and the unresolved clipping and Dynamic Type readings.
    private func auditOnce(_ app: XCUIApplication, _ screen: String, file: StaticString, line: UInt) -> (found: [String], unresolved: [String]) {
        var found: [String] = []
        var unattributed = 0
        var unresolved: [String] = []
        // text the tab bar covers: the audit reads it through the bar's glass and cannot name it (#3651)
        let counted = AuditExclusions.textsUnderTheTabBar(app)
        let underTheBar = min(counted, AuditExclusions.underTheBarMeasured[String(screen.split(separator: " ").first ?? "")] ?? 0)
        if counted > 0 { print("AccessibilityUITests \(screen): \(counted) text(s) under the tab bar, \(underTheBar) allowed") }
        do {
            try app.performAccessibilityAudit(for: .all) { issue in
                if AuditExclusions.excuses(issue) { return true }
                // text scrolled out of its scroll view's visible part: the audit read the pixels drawn where it would be
                if issue.auditType == .contrast, let e = issue.element, let visible = AuditExclusions.visibleFraction(e, in: app) ?? AuditExclusions.outsideTheTabBar(e, in: app) {
                    let shown = String(format: "%.2f", visible)
                    if visible < AuditExclusions.mostlyScrolledOut {
                        print("AccessibilityUITests \(screen): contrast reading on '\(e.label)', \(shown) visible in its scroll view, not counted")
                        return true
                    }
                    print("AccessibilityUITests \(screen): contrast reading on '\(e.label)', \(shown) visible in its scroll view, counted")
                }
                // a contrast reading on a SwiftUI node the audit cannot resolve to an element (text inside a combined
                // element; its colors are checked in AccessibilityTests). Allowed up to a small budget per screen, plus
                // one per text the tab bar covers (`textsUnderTheTabBar`, at most `underTheBarMeasured`); any other unattributed issue (clipping,
                // Dynamic Type, hit region) fails.
                if issue.element == nil, issue.auditType == .contrast, unattributed < AuditExclusions.unattributedBudget + underTheBar {
                    unattributed += 1
                    return true
                }
                // clipping or Dynamic Type on an unresolvable node: counted against the number recorded for this
                // screen (capped controls with the large content viewer, system navigation items); a change fails
                if issue.element == nil, issue.auditType == .textClipped || issue.auditType == .dynamicType {
                    unresolved.append(issue.detailedDescription)
                    return true
                }
                // a clipping reading on a screen with an allowance counts against it whether or not the audit names
                // the element: on iOS 27 it names some of the nodes it left unnamed before, and which ones varies
                // from run to run (the vote sheet: its own lines one run, the feed card behind it the next). Before
                // iOS 27 a named clipping still fails at once.
                if #available(iOS 27, *), let e = issue.element, issue.auditType == .textClipped, AuditExclusions.allowance(screen)[AuditExclusions.clipped] != nil {
                    print("AccessibilityUITests \(screen): clipping reading on '\(e.identifier)' '\(e.label)', counted against the allowance")
                    // a named node's description names it, so it is counted under the generic one
                    unresolved.append(AuditExclusions.clipped)
                    return true
                }
                let who = issue.element.map { "\($0.elementType.rawValue) '\($0.identifier)' '\($0.label)' at \($0.frame)" } ?? "- \(issue.detailedDescription)"
                found.append("[\(issue.auditType)] \(issue.compactDescription) on \(who)")
                return true
            }
        } catch {
            XCTFail("\(screen): audit did not run: \(error)", file: file, line: line)
        }
        return (found, unresolved)
    }

    private typealias Pixels = (width: Int, height: Int, bytes: [UInt8])

    /// Changed pixels (of the sampled quarter, see `changedPixels`) two frames may differ by and still count as the
    /// same picture: a blinking caret changes about 100, one short fading word several hundred.
    private static let stillTolerance = 250

    /// The status bar's height on the test phones (points), over the clock.
    private static let statusBarHeight: CGFloat = 54

    /// Waits until two screenshots [interval] apart show the same picture (no fade, move or shimmer running), at most
    /// [timeout], and returns that picture with the parts left out of comparisons (nil, with a failure, if it never
    /// settles): Esie, who is decorative and keeps moving for 30 s (README "Esie"; she waves on login), and the status
    /// bar's clock.
    private func waitUntilStill(_ app: XCUIApplication, _ screen: String, interval: TimeInterval = 0.5,
                                timeout: TimeInterval = 20) -> (pixels: Pixels, ignored: [CGRect])? {
        let start = Date()
        var last = Self.pixels(app.screenshot().image)
        // Esie's place is looked up once per wait (she does not move about the screen)
        let scale = CGFloat(last.width) / max(app.windows.firstMatch.frame.width, 1)
        let esie = app.descendants(matching: .any).matching(identifier: "esie").allElementsBoundByIndex.map { e -> CGRect in
            let f = e.frame
            return CGRect(x: f.minX * scale, y: f.minY * scale, width: f.width * scale, height: f.height * scale)
        }
        let ignored = esie + [CGRect(x: 0, y: 0, width: CGFloat(last.width), height: Self.statusBarHeight * scale)]
        var changed = -1
        while Date().timeIntervalSince(start) < timeout {
            Thread.sleep(forTimeInterval: interval)
            let now = Self.pixels(app.screenshot().image)
            changed = Self.changedPixels(last, now, ignoring: ignored)
            if changed <= Self.stillTolerance {
                print("AccessibilityUITests \(screen): still after \(String(format: "%.1f", Date().timeIntervalSince(start))) s (\(changed) px changed)")
                return (now, ignored)
            }
            last = now
        }
        XCTFail("\(screen): the screen never stopped changing in \(Int(timeout)) s (\(changed) px changed between two frames)")
        return nil
    }

    /// The screenshot as RGBA bytes.
    private static func pixels(_ image: UIImage) -> Pixels {
        guard let cg = image.cgImage else { return (0, 0, []) }
        let width = cg.width, height = cg.height
        var bytes = [UInt8](repeating: 0, count: width * height * 4)
        bytes.withUnsafeMutableBytes { buffer in
            let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                    space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
            context?.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        return (width, height, bytes)
    }

    /// Pixels outside [ignoring] whose color moved by more than a rounding step, sampling every second pixel of every
    /// second row (a quarter of the screen, at 3x still several samples per glyph); Int.max when the sizes differ.
    private static func changedPixels(_ a: Pixels, _ b: Pixels, ignoring: [CGRect]) -> Int {
        guard a.width == b.width, a.height == b.height, a.bytes.count == b.bytes.count else { return Int.max }
        let width = a.width
        return a.bytes.withUnsafeBufferPointer { x in
            b.bytes.withUnsafeBufferPointer { y in
                var changed = 0
                for row in stride(from: 0, to: a.height, by: 2) {
                    // the ignored columns of this row
                    let skip = ignoring.filter { CGFloat(row) >= $0.minY && CGFloat(row) < $0.maxY }
                    for col in stride(from: 0, to: width, by: 2) {
                        let i = (row * width + col) * 4
                        if abs(Int(x[i]) - Int(y[i])) > 8 || abs(Int(x[i + 1]) - Int(y[i + 1])) > 8 || abs(Int(x[i + 2]) - Int(y[i + 2])) > 8,
                           !skip.contains(where: { CGFloat(col) >= $0.minX && CGFloat(col) < $0.maxX }) {
                            changed += 1
                        }
                    }
                }
                return changed
            }
        }
    }

    private func screens(_ size: [String]) -> [(String, [String], String)] {
        [
            ("feed", [], "post-card"),
            ("post", ["-openPost", "spike-alice/fake-post-1"], "post-title"),
            ("waves", ["-startTab", "WAVES"], "tab-waves"),
            ("wallet", ["-seedAccounts", "YES", "-startTab", "WALLET"], "wallet-total"),
            ("notifications", ["-seedAccounts", "YES", "-startTab", "NOTIFICATION"], "tab-notification"),
            ("chats", ["-seedAccounts", "YES", "-startTab", "CHATS"], "tab-chats"),
            // at AX5 the summary starts closed (SummarySizeRule); auditAll opens it and audits it as well
            ("profile", ["-openScreen", "route:PROFILE:spike-alice"], size.isEmpty ? "profile-summary" : "profile-compact-header"),
            ("communities", ["-openScreen", "route:COMMUNITIES"], "tab-Discover"),
            ("search", ["-openScreen", "route:SEARCH_RESULT"], "search-input"),
            ("settings", ["-openScreen", "settings"], "settings"),
            ("login", ["-openScreen", "login"], "login-username"),
            ("editor", ["-seedAccounts", "YES", "-openScreen", "editor"], "editor-title"),
        ].map { ($0.0, $0.1 + size, $0.2) }
    }

    private func auditAll(_ size: [String], bold: Bool = false) {
        let ax5 = !size.isEmpty
        let suffix = (ax5 ? " (AX5)" : "") + (bold ? " (bold)" : "")
        for (name, args, ready) in screens(size) {
            let app = launch(args + (bold ? Self.boldText : []))
            if name == "profile", ax5 {
                audit(app, "profile closed" + suffix, ready: ready)
                element(app, ready).tap()
                audit(app, "profile" + suffix, ready: "profile-summary")
            } else {
                audit(app, name + suffix, ready: ready)
            }
            app.terminate()
        }
    }

    func testAuditMainScreensAtTheDefaultTextSize() { auditAll([]) }

    func testAuditMainScreensAtTheLargestAccessibilityTextSize() { auditAll(Self.ax5) }

    /// Bold Text (#2569), set for the app only: the debug `-boldText YES` puts the bold legibility weight in the
    /// app's windows (UIKit) and its SwiftUI environment, as the system setting does.
    static let boldText = ["-boldText", "YES"]

    /// The audits below mean something only if the setting reaches the app's text: the same label draws wider.
    func testBoldTextReachesTheAppsText() {
        func width(_ extra: [String]) -> CGFloat {
            let app = launch(["-showWelcome", "YES", "-reduceMotion", "YES"] + extra)
            let label = app.staticTexts["Welcome to"].firstMatch
            XCTAssertTrue(label.waitForExistence(timeout: 25), app.debugDescription)
            let w = label.frame.width
            app.terminate()
            return w
        }
        let regular = width([]), bold = width(Self.boldText)
        XCTAssertGreaterThan(bold, regular * 1.02, "Bold Text draws the welcome label wider: \(regular) -> \(bold)")
    }

    func testAuditMainScreensWithBoldText() { auditAll([], bold: true) }

    func testAuditMainScreensWithBoldTextAtTheLargestAccessibilityTextSize() { auditAll(Self.ax5, bold: true) }

    /// Dark mode: the screens heaviest on blue text (text buttons, links, tabs, the settings list, the login form).
    func testAuditInDarkMode() {
        let dark = ["-forceDarkTheme", "YES"]
        for (name, args, ready) in screens(dark) where ["feed", "post", "settings", "login", "communities", "editor"].contains(name) {
            let app = launch(args)
            audit(app, name + " (dark)", ready: ready)
            app.terminate()
        }
    }

    /// The post footer's tags row (below the fold, so the screen audit above misses it) at the largest text. Only the
    /// pills' findings count: once scrolled, rows left under the translucent top bar read as low contrast whatever
    /// their colours, and the screen audit covers those unscrolled.
    func testPostTagsAuditAtTheLargestText() throws {
        let app = launch(["-openPost", "spike-alice/fake-post-1"] + Self.ax5)
        XCTAssertTrue(element(app, "post-title").waitForExistence(timeout: 25))
        let pill = app.buttons["post-tag-spike"]
        // short drags: a swipe at this text size can fling the row past the screen
        let window = app.windows.firstMatch
        for _ in 0..<40 where !(pill.exists && pill.isHittable) {
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7))
                .press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)))
        }
        XCTAssertTrue(pill.isHittable, app.debugDescription)
        XCTAssertGreaterThanOrEqual(pill.frame.height, 44)
        _ = waitUntilStill(app, "post tags (AX5)")
        var found: [String] = []
        try app.performAccessibilityAudit(for: .all) { issue in
            guard let e = issue.element, e.identifier.hasPrefix("post-tag") else { return true }
            if AuditExclusions.excuses(issue) { return true }
            found.append("[\(issue.auditType)] \(issue.compactDescription) on '\(e.identifier)' '\(e.label)'")
            return true
        }
        XCTAssertEqual(found, [], "post tags (AX5)")
    }

    /// The vote sheet at the largest text: the audit passes and the vote button stays reachable (the sheet scrolls).
    func testVoteSheetAuditAtTheLargestText() {
        let app = launch(["-seedAccounts", "YES"] + Self.ax5)
        let vote = app.buttons["vote-button"].firstMatch
        XCTAssertTrue(vote.waitForExistence(timeout: 25))
        vote.tap()
        audit(app, "vote sheet (AX5)", ready: "vote-dir-up")
        let submit = app.buttons["vote-submit"]
        for _ in 0..<4 where !submit.isHittable { app.swipeUp() }
        XCTAssertTrue(submit.isHittable, "the vote button is reachable")
        XCTAssertTrue(submit.label.hasPrefix("Upvote"), submit.label)
    }

    /// Tabs are buttons with their names, the selected one says so, and the bar never grows with the text.
    func testTabBarLabelsTraitsAndSizeAtTheLargestText() {
        let app = launch(Self.ax5)
        let feed = app.buttons["tab-feed"]
        XCTAssertTrue(feed.waitForExistence(timeout: 20))
        XCTAssertTrue(feed.isSelected)
        for id in ["tab-feed", "tab-waves", "tab-chats", "tab-wallet", "tab-notification"] {
            let tab = app.buttons[id]
            XCTAssertFalse(tab.label.isEmpty, id)
            XCTAssertNotEqual(tab.label, id, "\(id) reads a name, not its identifier")
            XCTAssertGreaterThanOrEqual(tab.frame.height, 44, "\(id) touch target")
            XCTAssertLessThanOrEqual(tab.frame.maxX, app.windows.firstMatch.frame.maxX + 0.5, "\(id) on screen")
        }
        XCTAssertFalse(app.buttons["tab-waves"].isSelected)
    }

    /// A feed card is one VoiceOver stop with its summary, and stays inside the screen at the largest text.
    func testFeedCardIsOneStopInsideTheScreenAtTheLargestText() {
        let app = launch(Self.ax5)
        let card = app.buttons["post-card"].firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 25))
        XCTAssertTrue(card.label.contains("spike-"), card.label)
        let screen = app.windows.firstMatch.frame
        XCTAssertGreaterThanOrEqual(card.frame.minX, screen.minX - 0.5, "card starts on screen")
        XCTAssertLessThanOrEqual(card.frame.maxX, screen.maxX + 0.5, "card ends on screen")
    }

    /// The Feed tab stays mounted under the other tabs, but not for VoiceOver: none of it is reachable there.
    func testTheHiddenFeedTabIsOutOfTheAccessibilityTree() {
        let app = launch(["-seedAccounts", "YES", "-startTab", "WALLET"])
        XCTAssertTrue(element(app, "wallet-total").waitForExistence(timeout: 25))
        Thread.sleep(forTimeInterval: 2)
        XCTAssertFalse(element(app, "feed-waves-onboarding-title").exists, app.debugDescription)
        XCTAssertFalse(app.buttons["post-card"].exists, "feed cards")
        app.buttons["tab-feed"].tap()
        XCTAssertTrue(app.buttons["post-card"].firstMatch.waitForExistence(timeout: 15), "back on the Feed tab")
    }

    /// The tag hint wraps at the largest text in a long language instead of being cut off.
    func testEditorTagHintWrapsInGermanAtTheLargestText() {
        let app = launch(["-seedAccounts", "YES", "-openScreen", "editor", "-AppleLanguages", "(de)", "-AppleLocale", "de_DE"] + Self.ax5)
        let field = element(app, "tag-input")
        XCTAssertTrue(field.waitForExistence(timeout: 25), app.debugDescription)
        let screen = app.windows.firstMatch.frame
        XCTAssertLessThanOrEqual(field.frame.maxX, screen.maxX + 0.5)
        XCTAssertGreaterThan(field.frame.height, 60, "the field grew with its wrapped hint: \(field.frame)")
    }
}

/// Audit findings that are not ours to fix, each with the reason (README "Accessibility", iOS).
enum AuditExclusions {
    /// Text whose colors compute at or above 4.5:1 but that the audit's pixel reading of anti-aliased glyphs puts just
    /// under: secondary 0x67717A on white 4.98:1 (tabs, card counts: `CountLabel.numberColor`, checked in
    /// AccessibilityTests), on 0xF6F6F6 4.6:1 (feed pills, wallet asset names and fiat values); accentText 0x2E6BD4 on the grouped list background
    /// 0xF2F2F7 4.51:1 (settings section titles); `text` 0x3C4449 on white 10:1 beside an avatar the reading samples
    /// (the editor's community row) or under the create button's shadow (`AuthorLine.nameColor`, a card author at the
    /// bottom of the feed); the notification line on the unread tint 4.63:1 (`NotificationRowColors.line`); chat
    /// times, secondary on white, under the status line at AX5. Each color is checked in AccessibilityTests.
    static let computedAA: Set<String> = ["tab-Membership", "tab-Discover", "filter-NEW", "filter-HOT", "filter-TRENDING",
                                          "settings-section-general", "count-text", "community-row-title", "card-author-name",
                                          "notification-line", "chat-row-time", "asset-name", "asset-fiat"]
    /// Contrast readings on SwiftUI nodes the audit cannot resolve to an element, allowed per screen (see `audit`).
    static let unattributedBudget = 3
    /// The most texts under the tab bar each screen may count on top of [unattributedBudget], measured on 2026-11-06
    /// (#3651). The audit names no node for these readings, so the bar's allowance cannot be tied to the bar itself:
    /// capped at what was measured, more text scrolled under the bar never makes room for a new reading elsewhere.
    /// A screen not listed gets none.
    static let underTheBarMeasured: [String: Int] = ["feed": 1, "waves": 2, "wallet": 5, "notifications": 2]
    /// A bounded allowance, per screen and description, for clipping and Dynamic Type issues on nodes the audit
    /// cannot resolve to an element. This is not identification: the audit names no node and no text and every such
    /// issue shares one generic description. The numbers are those measured on 2026-10-27 on screens with the tab bar
    /// (fixed size icons with the large content viewer), the feed's cards (summaries cut at five lines on purpose) or a
    /// system navigation bar at AX5 (back button, title, toolbar items); the screenshots show no clipped text of ours.
    /// A clipping reading the audit does name counts against the same number on a listed screen (iOS 27 names some).
    /// One more than allowed fails. Screens not listed allow none; a dark run takes its screen's numbers (the dark feed
    /// sometimes reports the same 2 clipped and 3 fixed size nodes as the light one, sometimes none).
    static let clipped = "Text of this element may be clipped at larger Dynamic Type sizes."
    static let fixedSize = "User will not be able to change the font size of this element"
    static let unresolvedAllowance: [String: [String: Int]] = [
        "feed": [clipped: 2, fixedSize: 3], "feed (AX5)": [clipped: 1],
        "profile (AX5)": [clipped: 3], "communities (AX5)": [clipped: 2], "settings (AX5)": [clipped: 3],
        // 2026-10-29: the closed profile's compact header, one line in the navigation bar
        "profile closed (AX5)": [clipped: 1],
        // 2026-11-02: the vote sheet over the feed, whose header the sheet dims but does not hide (the feed's one), and
        // the sheet's estimate and voting power lines, shown whole at AX5 (checked on screenshots) since the demo data
        // answers the reward fund. 2026-11-05 (iOS 27): it names them, with the vote button (whole on the screenshot)
        "vote sheet (AX5)": [clipped: 3],
        // 2026-11-05 (iOS 27): the post at AX5, one node; the screenshot shows every line whole but the listen card,
        // which runs on under the action bar until the post is scrolled
        "post (AX5)": [clipped: 1],
        // 2026-11-03: the system tab bar (README "iPhone Duo"): the list scrolls on under its glass, and at AX5 the
        // row it cuts reads as clipped (checked on a screenshot; the old solid bar ended the list above it).
        // 2026-11-06 (#2686): the Unread chip joins Replies and Mentions; at AX5 the three stack, all whole on the
        // screenshot, and push the list down, so one more line runs under the bar
        "notifications (AX5)": [clipped: 4],
    ]

    /// The allowance of [screen] (a dark run's is its screen's).
    static func allowance(_ screen: String) -> [String: Int] {
        // a dark or Bold Text run takes its screen's numbers
        let base = screen.replacingOccurrences(of: " (dark)", with: "").replacingOccurrences(of: " (bold)", with: "")
        return unresolvedAllowance[screen] ?? unresolvedAllowance[base] ?? [:]
    }

    /// Deliberate previews that end in an ellipsis (RN numberOfLines); the full text is one tap away.
    static let truncatedOnPurpose: Set<String> = ["card-summary"]
    /// Empty search fields whose placeholder shows whole in the screenshots at every size; the audit reads the
    /// field's own empty text container.
    static let emptyFieldsReadAsClipped: Set<String> = ["chats-search"]

    /// Text with less than this share of it inside its scroll view's visible frame is not checked for contrast.
    static let mostlyScrolledOut: CGFloat = 0.5

    /// The share of the element inside the visible frame of a scroll view that holds it and clips it (the smaller of
    /// the visible height and width shares); nil when no scroll view clips it. VoiceOver scrolls to such text, but the
    /// audit reads the pixels drawn where it would be (at AX5 the profile summary is capped to 70 % of the screen and
    /// scrolls inside, so its location and follower counts sit under the tabs and the first post; the reading then
    /// depended on which card had loaded there).
    static func visibleFraction(_ element: XCUIElement, in app: XCUIApplication) -> CGFloat? {
        let frame = element.frame
        guard !frame.isEmpty else { return nil }
        for scroll in app.scrollViews.allElementsBoundByIndex where !scroll.frame.insetBy(dx: -0.5, dy: -0.5).contains(frame) {
            let same = scroll.descendants(matching: element.elementType).matching(NSPredicate(format: "label == %@", element.label))
            guard same.allElementsBoundByIndex.contains(where: { $0.frame == frame }) else { continue }
            let visible = frame.intersection(scroll.frame)
            if visible.isNull || visible.isEmpty { return 0 }
            return min(visible.height / frame.height, visible.width / frame.width)
        }
        return nil
    }

    /// The share of an element not under the system tab bar (nil when the bar does not cover it): list rows scroll on
    /// under the bar's glass, as in Apple's own apps, and the audit reads them through it. Works for the bottom bar and
    /// iPhone Duo's trailing column alike.
    static func outsideTheTabBar(_ element: XCUIElement, in app: XCUIApplication) -> CGFloat? {
        let frame = element.frame, bar = app.tabBars.firstMatch
        guard !frame.isEmpty, bar.exists else { return nil }
        // the bar's own buttons sit inside it and are checked as any control (content wholly under the bar is not)
        guard !bar.buttons.allElementsBoundByIndex.contains(where: { $0.frame == frame }) else { return nil }
        let covered = frame.intersection(bar.frame)
        guard !covered.isNull, !covered.isEmpty else { return nil }
        return 1 - (covered.width * covered.height) / (frame.width * frame.height)
    }

    /// The bottom tabs' identifiers (`AppTab.identifier`).
    static let tabIds: Set<String> = ["tab-feed", "tab-waves", "tab-chats", "tab-wallet", "tab-notification"]

    /// Static texts the system tab bar covers, from one snapshot of the screen (#3651). A list scrolls under the iOS 26
    /// glass tab bar; the audit reads the text there through the glass as low contrast and cannot resolve it to an
    /// element (its point lands on the bar). On the wallet the fourth asset card sits under the bar at launch: its five
    /// texts (symbol, name, balance, fiat value, claim) were the five unattributed readings, and they went away when
    /// that card was hidden. The bar is the union of the tab buttons (XCUITest shows no TabBar element for SwiftUI's
    /// TabView on iOS 27).
    static func textsUnderTheTabBar(_ app: XCUIApplication) -> Int {
        guard let root = try? app.snapshot() else { return 0 }
        var tabs: [CGRect] = [], texts: [CGRect] = []
        func walk(_ node: XCUIElementSnapshot) {
            // a tab's own label is the bar's, not content under it
            if node.elementType == .button, tabIds.contains(node.identifier) { return tabs.append(node.frame) }
            if node.elementType == .staticText { texts.append(node.frame) }
            node.children.forEach(walk)
        }
        walk(root)
        guard let first = tabs.first else { return 0 }
        let bar = tabs.dropFirst().reduce(first) { $0.union($1) }
        return texts.filter { !$0.isEmpty && $0.intersects(bar) }.count
    }

    static func excuses(_ issue: XCUIAccessibilityAuditIssue) -> Bool {
        // an issue without an element is reported (it cannot be checked against anything here)
        guard let element = issue.element else { return false }
        // system views: the status bar and the keyboard are Apple's
        if element.elementType == .statusBar || element.elementType == .keyboard || element.elementType == .key { return true }
        // "partially unsupported": the audit's heuristic flags text that grows through @ScaledMetric sizes (RN's point
        // sizes kept, scaled relative to a text style) and text deliberately capped with a dynamicTypeSize range
        // (tab badges, toolbar icons, which show the large content viewer instead). Text that does not grow at all
        // ("unsupported") still fails, and the AX5 run catches clipping and overflow.
        if issue.auditType == .dynamicType, issue.compactDescription.contains("partially") { return true }
        if issue.auditType == .contrast {
            // WCAG 1.4.3 exempts inactive controls (a disabled LOGIN or Publish before anything is filled in)
            if !element.isEnabled { return true }
            if computedAA.contains(element.identifier) { return true }
        }
        if issue.auditType == .textClipped, truncatedOnPurpose.contains(element.identifier) { return true }
        if issue.auditType == .textClipped, emptyFieldsReadAsClipped.contains(element.identifier),
           (element.value as? String).map({ $0.isEmpty || $0 == element.placeholderValue }) ?? true { return true }
        // a paragraph with a link is as tall as its line; the link is reachable through the rotor and its own action
        if issue.auditType == .hitRegion, element.identifier == "paragraph" { return true }
        return false
    }
}
