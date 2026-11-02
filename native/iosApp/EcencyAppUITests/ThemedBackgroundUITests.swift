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
        let env = ProcessInfo.processInfo.environment
        if let dir = env["BG_SHOT_DIR"], !dir.isEmpty {
            try? screenshot.pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent("bg-\(name).png"))
        }
        return screenshot
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
}
