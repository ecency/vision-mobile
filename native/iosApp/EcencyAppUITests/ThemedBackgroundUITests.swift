import UIKit
import XCTest

/// Loading, empty and error states and list rows sit on the theme background, not the system list colours (black
/// in dark mode), in dark and light. Reads pixels from screenshots of the debug fake data. Saves screens when
/// `TEST_RUNNER_BG_SHOT_DIR` is set.
final class ThemedBackgroundUITests: XCTestCase {
    /// AppTheme.background
    private static let darkBackground = (0x1E, 0x28, 0x35)
    private static let lightBackground = (0xFF, 0xFF, 0xFF)

    private func launch(dark: Bool, _ extra: [String]) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES"] + (dark ? ["-forceDarkTheme", "YES"] : ["-AppleInterfaceStyle", "Light"]) + extra
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    /// A screenshot once the screen stopped changing (the launch cover fades out over the first frames).
    private func shot(_ name: String, _ app: XCUIApplication) -> XCUIScreenshot {
        let w = app.windows.firstMatch.frame
        let probes = [CGPoint(x: w.midX, y: w.midY), CGPoint(x: 6, y: w.midY), CGPoint(x: w.midX, y: w.maxY - 60), CGPoint(x: 6, y: w.minY + 300)]
        var screenshot = XCUIScreen.main.screenshot()
        let deadline = Date().addingTimeInterval(15)
        while Date() < deadline {
            Thread.sleep(forTimeInterval: 0.7)
            let next = XCUIScreen.main.screenshot()
            let same = probes.allSatisfy { p in
                let a = pixel(screenshot, app, p), b = pixel(next, app, p)
                return abs(a.0 - b.0) <= 1 && abs(a.1 - b.1) <= 1 && abs(a.2 - b.2) <= 1
            }
            screenshot = next
            if same { break }
        }
        save(screenshot, name)
        return screenshot
    }

    private func save(_ screenshot: XCUIScreenshot, _ name: String) {
        if let dir = ProcessInfo.processInfo.environment["BG_SHOT_DIR"], !dir.isEmpty {
            try? screenshot.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("bg-\(name).png"))
        }
    }

    /// The sRGB colour at [point] (window points) of [screenshot].
    private func pixel(_ screenshot: XCUIScreenshot, _ app: XCUIApplication, _ point: CGPoint) -> (Int, Int, Int) {
        guard let image = screenshot.image.cgImage else { return (-1, -1, -1) }
        let scale = CGFloat(image.width) / app.windows.firstMatch.frame.width
        var data = [UInt8](repeating: 0, count: 4)
        let ctx = CGContext(data: &data, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.draw(image, in: CGRect(x: -floor(point.x * scale), y: floor(point.y * scale) - CGFloat(image.height) + 1,
                                   width: CGFloat(image.width), height: CGFloat(image.height)))
        return (Int(data[0]), Int(data[1]), Int(data[2]))
    }

    private func assertTheme(_ screenshot: XCUIScreenshot, _ app: XCUIApplication, dark: Bool, at point: CGPoint, _ what: String,
                             file: StaticString = #filePath, line: UInt = #line) {
        let want = dark ? Self.darkBackground : Self.lightBackground
        let got = pixel(screenshot, app, point)
        let close = abs(got.0 - want.0) <= 6 && abs(got.1 - want.1) <= 6 && abs(got.2 - want.2) <= 6
        XCTAssertTrue(close, "\(what) (\(dark ? "dark" : "light")) at \(point): got \(hex(got)), want the theme background \(hex(want))",
                      file: file, line: line)
    }

    /// No separator line under a state row: every pixel of a short column under [el] is the theme background.
    private func assertNoLineUnder(_ s: XCUIScreenshot, _ app: XCUIApplication, dark: Bool, _ el: XCUIElement, _ what: String,
                                   file: StaticString = #filePath, line: UInt = #line) {
        let x = min(el.frame.maxX - 4, app.windows.firstMatch.frame.maxX - 30)
        var y = el.frame.maxY + 1
        while y < el.frame.maxY + 40 {
            assertTheme(s, app, dark: dark, at: CGPoint(x: x, y: y), "\(what): no line under it", file: file, line: line)
            y += 0.5
        }
    }

    private func hex(_ c: (Int, Int, Int)) -> String { String(format: "#%02X%02X%02X", c.0, c.1, c.2) }

    /// A point at the left edge of [element]'s row (the list inset, so row background) and one near the bottom of the window.
    private func rowEdge(_ app: XCUIApplication, _ el: XCUIElement) -> CGPoint { CGPoint(x: 6, y: el.frame.midY) }
    private func below(_ app: XCUIApplication) -> CGPoint {
        let w = app.windows.firstMatch.frame
        return CGPoint(x: w.midX, y: w.maxY - 60)
    }

    private func openSearch(_ app: XCUIApplication) {
        XCTAssertTrue(app.textFields["search-input"].waitForExistence(timeout: 30), "search")
    }

    func testSearchPeopleEmpty() {
        for dark in [true, false] {
            let app = launch(dark: dark, ["-openScreen", "route:SEARCH_RESULT"])
            openSearch(app)
            app.buttons["search-tab-People"].tap()
            let empty = app.staticTexts["Nothing here"].firstMatch
            XCTAssertTrue(empty.waitForExistence(timeout: 10))
            let s = shot("search-people-empty-\(dark ? "dark" : "light")", app)
            assertTheme(s, app, dark: dark, at: rowEdge(app, empty), "beside the empty state")
            assertTheme(s, app, dark: dark, at: below(app), "below the empty state")
            assertNoLineUnder(s, app, dark: dark, empty, "the empty state")
            app.terminate()
        }
    }

    func testSearchBestNoHits() {
        for dark in [true, false] {
            let app = launch(dark: dark, ["-openScreen", "route:SEARCH_RESULT"])
            openSearch(app)
            app.textFields["search-input"].focusAndType("nohits")
            let empty = element(app, "search-empty")
            XCTAssertTrue(empty.waitForExistence(timeout: 15))
            let s = shot("search-best-empty-\(dark ? "dark" : "light")", app)
            assertTheme(s, app, dark: dark, at: rowEdge(app, empty), "beside the empty state")
            // the keyboard is up: under the state, above the keyboard
            // the message is the state's last line (the identifier's frame can stop at Esie)
            let message = app.staticTexts["Nothing here"].firstMatch
            assertTheme(s, app, dark: dark, at: CGPoint(x: message.frame.midX, y: message.frame.maxY + 60), "below the empty state")
            assertNoLineUnder(s, app, dark: dark, message, "the empty state")
            app.terminate()
        }
    }

    func testSearchBestLoading() {
        for dark in [true, false] {
            let app = launch(dark: dark, ["-openScreen", "route:SEARCH_RESULT"])
            openSearch(app)
            app.textFields["search-input"].focusAndType("slowload")
            let spinner = app.activityIndicators.firstMatch
            XCTAssertTrue(spinner.waitForExistence(timeout: 15))
            let s = shot("search-best-loading-\(dark ? "dark" : "light")", app)
            assertTheme(s, app, dark: dark, at: rowEdge(app, spinner), "beside the spinner")
            assertTheme(s, app, dark: dark, at: CGPoint(x: spinner.frame.midX, y: spinner.frame.maxY + 60), "below the spinner")
            app.terminate()
        }
    }

    /// The voters list: content rows and the "not found" state.
    func testVotersRowsAndEmpty() {
        for dark in [true, false] {
            let app = launch(dark: dark, ["-openScreen", "voters"])
            let row = element(app, "voter-spike-alice")
            XCTAssertTrue(row.waitForExistence(timeout: 30))
            var s = shot("voters-\(dark ? "dark" : "light")", app)
            assertTheme(s, app, dark: dark, at: rowEdge(app, row), "a voter row")
            let field = app.searchFields.firstMatch
            XCTAssertTrue(field.waitForExistence(timeout: 10))
            field.tap()
            field.typeText("zzz")
            let empty = app.staticTexts["User is not found."].firstMatch
            XCTAssertTrue(empty.waitForExistence(timeout: 10))
            s = shot("voters-empty-\(dark ? "dark" : "light")", app)
            assertTheme(s, app, dark: dark, at: rowEdge(app, empty), "beside the empty state")
            assertNoLineUnder(s, app, dark: dark, empty, "the empty state")
            app.terminate()
        }
    }

    /// Communities, Joined with no account: the empty state.
    func testCommunitiesJoinedEmpty() {
        for dark in [true, false] {
            let app = launch(dark: dark, ["-openScreen", "route:COMMUNITIES"])
            let discover = app.buttons["discover-communities"]
            XCTAssertTrue(discover.waitForExistence(timeout: 30))
            let s = shot("communities-empty-\(dark ? "dark" : "light")", app)
            assertTheme(s, app, dark: dark, at: rowEdge(app, discover), "beside the empty state")
            assertTheme(s, app, dark: dark, at: below(app), "below the empty state")
            app.terminate()
        }
    }

    /// The wallet tab's asset cards, then an asset's details and history: the space around the cards and rows.
    func testWalletAndAssetDetails() {
        for dark in [true, false] {
            let app = launch(dark: dark, ["-seedAccounts", "YES", "-startTab", "WALLET"])
            let hp = app.buttons["asset-HP"]
            XCTAssertTrue(hp.waitForExistence(timeout: 30))
            var s = shot("wallet-\(dark ? "dark" : "light")", app)
            assertTheme(s, app, dark: dark, at: rowEdge(app, hp), "beside an asset card")
            app.buttons["asset-HIVE"].tap()
            let rows = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'activity-'"))
            XCTAssertTrue(rows.firstMatch.waitForExistence(timeout: 20))
            s = shot("asset-details-\(dark ? "dark" : "light")", app)
            assertTheme(s, app, dark: dark, at: CGPoint(x: 4, y: rows.firstMatch.frame.midY), "beside a history row")
            app.terminate()
        }
    }

    /// Settings (a grouped list): the space around the groups is the theme background.
    func testSettingsBackground() {
        for dark in [true, false] {
            let app = launch(dark: dark, ["-openScreen", "settings"])
            let row = element(app, "settings-currency")
            XCTAssertTrue(row.waitForExistence(timeout: 30))
            let s = shot("settings-\(dark ? "dark" : "light")", app)
            assertTheme(s, app, dark: dark, at: CGPoint(x: 4, y: row.frame.midY), "beside a settings group")
            app.terminate()
        }
    }

    /// The Chats tab signed in: beside a channel row, beside the search bar, and below the rows.
    func testChatsListBackground() {
        for dark in [true, false] {
            let app = launch(dark: dark, ["-seedAccounts", "YES", "-startTab", "CHATS"])
            let row = element(app, "chat-row-ch-ecency")
            XCTAssertTrue(row.waitForExistence(timeout: 30))
            let s = shot("chats-list-\(dark ? "dark" : "light")", app)
            assertTheme(s, app, dark: dark, at: rowEdge(app, row), "beside a channel row")
            let search = app.textFields.firstMatch
            XCTAssertTrue(search.exists, "the chats search field")
            assertTheme(s, app, dark: dark, at: CGPoint(x: 4, y: search.frame.midY), "beside the chats search bar")
            assertTheme(s, app, dark: dark, at: CGPoint(x: app.windows.firstMatch.frame.maxX - 6, y: below(app).y - 40), "below the channel rows")
            app.terminate()
        }
    }

    /// The Chats tab on its first load: beside the skeleton rows and in the empty list area under them, down to the
    /// tab bar (TestFlight showed a black band there).
    func testChatsLoadingBackground() {
        for dark in [true, false] {
            let app = launch(dark: dark, ["-seedAccounts", "YES", "-startTab", "CHATS", "-demoChat", "loading"])
            let skeleton = element(app, "chats-loading")
            XCTAssertTrue(skeleton.waitForExistence(timeout: 30))
            let s = shot("chats-loading-\(dark ? "dark" : "light")", app)
            assertTheme(s, app, dark: dark, at: rowEdge(app, skeleton), "beside the skeleton rows")
            let tabBar = app.tabBars.firstMatch
            let bottom = min(below(app).y, tabBar.exists ? tabBar.frame.minY - 20 : .infinity)
            let top = skeleton.frame.maxY
            for f in [0.15, 0.5, 0.85] where bottom - top > 40 {
                assertTheme(s, app, dark: dark, at: CGPoint(x: app.windows.firstMatch.frame.midX, y: top + (bottom - top) * f),
                            "under the skeleton rows (\(Int(f * 100))% down)")
            }
            app.terminate()
        }
    }

    /// A channel: the message area, beside the header and beside the composer bar.
    func testChatThreadBackground() {
        for dark in [true, false] {
            let app = launch(dark: dark, ["-seedAccounts", "YES", "-openScreen", "chat:ch-ecency"])
            let composer = element(app, "chat-composer")
            XCTAssertTrue(composer.waitForExistence(timeout: 30))
            XCTAssertTrue(app.staticTexts["chat-title"].waitForExistence(timeout: 10))
            let s = shot("chat-thread-\(dark ? "dark" : "light")", app)
            let title = app.staticTexts["chat-title"]
            assertTheme(s, app, dark: dark, at: CGPoint(x: app.windows.firstMatch.frame.maxX - 4, y: title.frame.midY), "beside the chat header")
            assertTheme(s, app, dark: dark, at: CGPoint(x: 4, y: composer.frame.midY), "beside the composer")
            assertTheme(s, app, dark: dark, at: CGPoint(x: 4, y: composer.frame.minY - 80), "the message area")
            app.terminate()
        }
    }

    /// A direct message's warning box takes the theme's warning fill (AppTheme.warningBackground), not RN's light
    /// cream in dark mode.
    func testDmWarningBox() {
        for dark in [true, false] {
            let app = launch(dark: dark, ["-seedAccounts", "YES", "-openScreen", "chat:ch-dm-alice:D"])
            let warning = element(app, "dm-warning")
            XCTAssertTrue(warning.waitForExistence(timeout: 30))
            let s = shot("dm-warning-\(dark ? "dark" : "light")", app)
            // inside the box's bottom padding, under the text
            let point = CGPoint(x: warning.frame.midX, y: warning.frame.maxY - 4)
            let want = dark ? (0x3A, 0x32, 0x23) : (0xFF, 0xF6, 0xDF)
            let got = pixel(s, app, point)
            let close = abs(got.0 - want.0) <= 6 && abs(got.1 - want.1) <= 6 && abs(got.2 - want.2) <= 6
            XCTAssertTrue(close, "the warning box (\(dark ? "dark" : "light")) at \(point): got \(hex(got)), want \(hex(want))")
            app.terminate()
        }
    }

    // MARK: Every screen

    /// One screen of the sweep: how to open it, and the content that is not page background (a picture, the header's
    /// avatar pill) and is left out of the scan.
    private struct Page {
        let name: String
        let args: [String]
        /// Identifiers of elements drawn over the page with their own fill (pictures, the header pill)
        var skip: [String] = []
        /// The scan starts this far down
        var top: CGFloat = 0
        /// A sheet: the scan starts under its top edge (`sheetTop`), below the dimmed screen behind it
        var sheet = false
        /// Scan the leading edge only (the side menu: the trailing part is its scrim)
        var leadingOnly = false
        /// A button that opens the sheet under test
        var tap: String? = nil
    }

    /// Identifier prefixes of elements whose rows are left out (see `pageScan`).
    private static let stripPrefixes = ["notifications-page-", "editor-toolbar"]

    /// Content drawn edge to edge with its own fill: the profile cover and the skeleton's cover bone.
    private static let contentIds = ["skeleton-profile", "profile-cover"]

    /// The theme's card colour (AppTheme.lightBackground): RN's filter strips, zebra rows and card separators.
    private static let darkCard = (0x2E, 0x3D, 0x51)
    private static let lightCard = (0xF6, 0xF6, 0xF6)

    /// The header's avatar pill (a brand gradient) on every tab.
    private static let tabSkip = ["account-menu"]

    /// The scan: the leading and trailing 2 pt of the window from the top (status bar) to the bottom (home indicator),
    /// every 4 pt, minus [skip] frames and the tab bar's shadow; returns the runs that were neither of the theme's two
    /// surfaces (the page background, and the card colour of RN's strips, zebra rows and separators): the system
    /// black, greys and whites show up here.
    private func pageScan(_ s: XCUIScreenshot, _ app: XCUIApplication, dark: Bool, _ page: Page) -> [String] {
        let w = app.windows.firstMatch.frame
        let want = dark ? Self.darkBackground : Self.lightBackground, card = dark ? Self.darkCard : Self.lightCard
        var skips = (page.skip + Self.contentIds).map { element(app, $0) }.filter { $0.exists }.map { $0.frame.insetBy(dx: -2, dy: -2) }
        // the notifications page chips (the selected one is an accent fill at the edge) and the editor's toolbar
        // (its labels run under the edge): their rows are left out
        for prefix in Self.stripPrefixes {
            let chips = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH %@", prefix)).allElementsBoundByIndex
            for chip in chips where chip.exists && chip.frame.height < 80 {
                skips.append(CGRect(x: w.minX, y: chip.frame.minY - 8, width: w.width, height: chip.frame.height + 16))
            }
        }
        let tabBar = element(app, "tab-bar")
        if tabBar.exists {
            // the bar is on the theme too; its soft shadow darkens the 10 pt above it
            skips.append(CGRect(x: w.minX, y: tabBar.frame.minY - 12, width: w.width, height: 13))
        }
        var runs: [String] = []
        let top = page.sheet ? sheetTop(s, app) : page.top
        // a sheet floats inset from the window's edges and bottom (iOS 26): its columns are inside it
        let inset: CGFloat = page.sheet ? 12 : 2, bottom = w.maxY - (page.sheet ? 44 : 1)
        for x in page.leadingOnly ? [w.minX + inset] : [w.minX + inset, w.maxX - inset] {
            var start: CGFloat?
            var first = (0, 0, 0)
            var y = w.minY + top + 2
            func close(_ end: CGFloat) {
                // one sample alone is a hairline divider (under a header, between rows), not a background
                if let st = start, end - st > 4 { runs.append("x \(Int(x)) y \(Int(st))...\(Int(end)) \(hex(first))") }
                start = nil
            }
            while y < bottom {
                let p = CGPoint(x: x, y: y)
                if skips.contains(where: { $0.contains(p) }) { close(y); y += 4; continue }
                let got = pixel(s, app, p)
                let ok = [want, card].contains { abs(got.0 - $0.0) <= 6 && abs(got.1 - $0.1) <= 6 && abs(got.2 - $0.2) <= 6 }
                if ok { close(y) } else if start == nil { start = y; first = got }
                y += 4
            }
            close(y)
        }
        return runs
    }

    /// Where a sheet starts: down the middle of the window, the first point that is not the dimmed screen above it
    /// (the colour at the very top), plus room for its rounded corners. Whatever colour the sheet is, it differs from the dimming.
    private func sheetTop(_ s: XCUIScreenshot, _ app: XCUIApplication) -> CGFloat {
        let w = app.windows.firstMatch.frame
        let dim = pixel(s, app, CGPoint(x: w.midX, y: 30))
        var y: CGFloat = 40
        while y < w.maxY {
            let c = pixel(s, app, CGPoint(x: w.midX, y: y))
            if abs(c.0 - dim.0) > 6 || abs(c.1 - dim.1) > 6 || abs(c.2 - dim.2) > 6 { return y + 40 }
            y += 2
        }
        return w.maxY
    }

    /// Opens each page in dark and light and scans its edges; one failure per page and theme, naming the runs.
    private func sweep(_ pages: [Page], file: StaticString = #filePath, line: UInt = #line) {
        for page in pages {
            for dark in [true, false] {
                let app = launch(dark: dark, page.args)
                continueAfterFailure = true
                // the screen's first frames (the launch cover fades, the push slides in), then a still screen
                Thread.sleep(forTimeInterval: 2.5)
                var page = page
                if let id = page.tap {
                    let button = element(app, id)
                    XCTAssertTrue(button.waitForExistence(timeout: 20), "\(page.name): \(id)")
                    button.tap()
                    Thread.sleep(forTimeInterval: 1.5)
                    page.sheet = true
                }
                // one screenshot: the edges are still by now, while Esie, shimmers and spinners in the middle never settle
                Thread.sleep(forTimeInterval: 1)
                let s = XCUIScreen.main.screenshot()
                save(s, "page-\(page.name)-\(dark ? "dark" : "light")")
                let runs = pageScan(s, app, dark: dark, page)
                XCTAssertTrue(runs.isEmpty, "\(page.name) (\(dark ? "dark" : "light")): not the theme background \(hex(dark ? Self.darkBackground : Self.lightBackground)) (or its card colour) at \(runs.joined(separator: ", "))",
                              file: file, line: line)
                app.terminate()
            }
        }
    }

    private static let signedIn = ["-seedAccounts", "YES"]

    /// The tabs signed in and signed out, the side menu and the welcome screen.
    func testEveryTab() {
        let tabs = ["FEED", "WAVES", "NOTIFICATION", "CHATS", "WALLET"]
        sweep(tabs.map { Page(name: "tab-\($0.lowercased())", args: Self.signedIn + ["-startTab", $0], skip: Self.tabSkip) }
            + ["NOTIFICATION", "CHATS", "WALLET"].map { Page(name: "tab-\($0.lowercased())-signed-out", args: ["-startTab", $0], skip: Self.tabSkip) }
            // the drawer under its header picture
            + [Page(name: "side-menu", args: Self.signedIn + ["-openDrawer", "YES"], top: 200, leadingOnly: true),
               Page(name: "welcome", args: ["-showWelcome", "YES"])])
    }

    /// Every pushed screen with the demo data.
    func testEveryPushedScreen() {
        let screens: [(String, String)] = [
            ("settings", "settings"), ("refer", "refer"), ("perks", "perks"), ("spin", "spinGame"), ("get-points", "getPoints"),
            ("account-boost", "accountBoost"), ("email-digests", "emailDigests"), ("explore", "explore"), ("bookmarks", "bookmarks"),
            ("edit-profile", "editProfile"), ("drafts", "drafts"), ("editor", "editor"), ("voters", "voters"), ("reblogs", "reblogs"),
            ("edit-history", "editHistory"), ("tag", "tag"), ("search", "route:SEARCH_RESULT"), ("communities", "route:COMMUNITIES"),
            ("community", "route:COMMUNITY:hive-125125"), ("community-members", "route:COMMUNITY_MEMBERS:hive-125125"),
            ("community-activities", "route:COMMUNITY_ACTIVITIES:hive-125125"), ("community-settings", "route:COMMUNITY_SETTINGS:hive-125125"),
            ("profile", "route:PROFILE:spike-alice"), ("follows", "route:FOLLOWS:spike-alice"), ("tag-result", "route:TAG_RESULT:photography"),
            ("ai-image", "route:AI_IMAGE_GENERATOR"), ("assets-select", "route:ASSETS_SELECT"), ("trade", "route:TRADE:HIVE"),
            ("redeem-boost", "route:REDEEM:boost_plus"), ("redeem-promote", "route:REDEEM"), ("asset-details", "route:ASSET_DETAILS:HIVE"),
            ("bot-comments", "route:BOT_COMMENTS:spike-alice/fake-post-1"), ("transfer", "wallet:transfer,transfer,HIVE,hive,spike-bob"),
            ("chat-thread", "chat:ch-ecency"),
        ]
        sweep(screens.map { Page(name: $0.0, args: Self.signedIn + ["-openScreen", $0.1], ) }
            + [Page(name: "post", args: Self.signedIn + ["-openLink", "https://ecency.com/@spike-alice/fake-post-1"])])
    }

    /// The app's sheets (they sit below the dimmed screen: the scan starts under the sheet's top edge).
    func testEverySheet() {
        let sheets = ["login", "accounts", "backupKeys", "register", "quickProfile", "customise", "menu", "digest"]
        sweep(sheets.map { Page(name: "sheet-\($0)", args: Self.signedIn + ["-openScreen", $0], sheet: true) })
    }

    /// Sheets opened from a screen (not the app's sheet host).
    func testNestedSheets() {
        sweep([Page(name: "wallet-receive", args: Self.signedIn + ["-startTab", "WALLET"], tap: "wallet-receive"),
               Page(name: "search-filters", args: Self.signedIn + ["-openScreen", "route:SEARCH_RESULT"], tap: "search-filters"),
               Page(name: "history-filter", args: Self.signedIn + ["-openScreen", "route:ASSET_DETAILS:HIVE"], tap: "history-filter"),
               Page(name: "chats-new-group", args: Self.signedIn + ["-startTab", "CHATS"], tap: "chats-new-group")])
    }

    /// The first load of every screen that loads, held in its loading state (`-demoNetwork hold`): skeletons and
    /// spinners sit on the theme background.
    func testEveryLoadingState() {
        sweep(loadingPages.map { Page(name: "\($0.name)-loading", args: $0.args + ["-demoNetwork", "hold"], skip: $0.skip) })
    }

    /// The same screens when every load fails (`-demoNetwork fail`): the error states.
    func testEveryErrorState() {
        sweep(loadingPages.map { Page(name: "\($0.name)-error", args: $0.args + ["-demoNetwork", "fail"], skip: $0.skip) })
    }

    private var loadingPages: [Page] {
        ["FEED", "WAVES", "NOTIFICATION", "CHATS", "WALLET"].map { Page(name: "tab-\($0.lowercased())", args: Self.signedIn + ["-startTab", $0], skip: Self.tabSkip) }
            + [("refer", "refer"), ("bookmarks", "bookmarks"), ("drafts", "drafts"), ("voters", "voters"), ("reblogs", "reblogs"),
               ("edit-history", "editHistory"), ("tag", "tag"), ("communities", "route:COMMUNITIES"), ("community", "route:COMMUNITY:hive-125125"),
               ("community-members", "route:COMMUNITY_MEMBERS:hive-125125"), ("profile", "route:PROFILE:spike-alice"),
               ("follows", "route:FOLLOWS:spike-alice"), ("asset-details", "route:ASSET_DETAILS:HIVE"), ("perks", "perks"),
               ("get-points", "getPoints"), ("chat-thread", "chat:ch-ecency")].map { Page(name: $0.0, args: Self.signedIn + ["-openScreen", $0.1]) }
            + [Page(name: "post", args: Self.signedIn + ["-openLink", "https://ecency.com/@spike-alice/fake-post-1"])]
    }
}
