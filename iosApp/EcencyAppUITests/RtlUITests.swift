import XCTest

/// Right to left at launch, from launch arguments only (the simulator's own language and the app's per-app setting are
/// left alone): a device in Arabic starts the app in Arabic, mirrored; the debug `ar-XB` pseudo locale mirrors too.
final class RtlUITests: XCTestCase {
    private func launch(_ extra: [String]) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-seedAccounts", "YES"] + extra
        app.launch()
        return app
    }

    private func assertMirroredTabBar(_ app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let feed = app.buttons["tab-feed"], notifications = app.buttons["tab-notification"]
        XCTAssertTrue(feed.waitForExistence(timeout: 20), file: file, line: line)
        XCTAssertGreaterThan(feed.frame.midX, notifications.frame.midX, "the first tab is on the right", file: file, line: line)
    }

    func testAnArabicDeviceStartsMirrored() {
        assertMirroredTabBar(launch(["-AppleLanguages", "(ar)", "-AppleLocale", "ar_SA"]))
    }

    func testTheRtlPseudoLocaleMirrors() {
        assertMirroredTabBar(launch(["-pseudoLocale", "ar-XB"]))
    }
}
