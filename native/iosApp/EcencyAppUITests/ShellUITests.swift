import XCTest

/// The app shell against the debug fake transport: tabs, side menu, back, and the lock above all of it.
final class ShellUITests: XCTestCase {
    private func launch(_ extra: [String] = []) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-homeFilter", "trending"] + extra
        app.launch()
        return app
    }

    private func element(_ app: XCUIApplication, _ id: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: id).firstMatch
    }

    func testTabsInRnOrderAndTheirSignedOutScreens() {
        let app = launch()
        let ids = ["tab-feed", "tab-waves", "tab-chats", "tab-wallet", "tab-notification"]
        XCTAssertTrue(app.buttons[ids[0]].waitForExistence(timeout: 15))
        let xs = ids.map { app.buttons[$0].frame.midX }
        XCTAssertEqual(xs, xs.sorted(), "left to right in RN order")
        XCTAssertTrue(app.buttons["tab-feed"].isSelected)
        XCTAssertTrue(app.buttons["create-fab"].exists)

        app.buttons["tab-waves"].tap()
        // the Waves tab is built (WavesUITests covers it): its own tabs and compose button, no placeholder
        XCTAssertTrue(app.buttons["waves-tab-FOR YOU"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["coming-soon-title"].exists)
        XCTAssertFalse(app.buttons["create-fab"].exists)

        app.buttons["tab-wallet"].tap()
        XCTAssertTrue(app.staticTexts["Login to see"].waitForExistence(timeout: 5))
        app.buttons["login-prompt-button"].tap()
        XCTAssertTrue(app.textFields["login-username"].waitForExistence(timeout: 5))
    }

    /// The shared toast host centres every toast at the bottom, one line or several (it once sat at the left edge).
    func testToastsAreCenteredAtTheBottom() {
        let long = "Your request for deletion is sent. " + String(repeating: "Your account will be removed in a few days. ", count: 4)
        for text in ["Tag already exists", long] {
            let app = launch(["-demoToast", text])
            let toast = app.staticTexts["toast"]
            XCTAssertTrue(toast.waitForExistence(timeout: 15))
            XCTAssertEqual(toast.label, text)
            let screen = app.windows.firstMatch.frame
            XCTAssertEqual(toast.frame.midX, screen.midX, accuracy: 3, "horizontally centered: \(text)")
            XCTAssertGreaterThan(toast.frame.minY, screen.midY, "in the bottom half")
            XCTAssertGreaterThanOrEqual(toast.frame.minX, screen.minX)
            XCTAssertLessThanOrEqual(toast.frame.maxX, screen.maxX)
            app.terminate()
        }
    }

    /// A toast posted while a sheet is open shows above the sheet, and touches outside the pill still reach the
    /// sheet (the toast window lets them through).
    func testAToastPostedWhileASheetIsOpenShowsOnTop() {
        let app = launch(["-seedAccounts", "YES", "-demoToast", "Above the sheet", "-demoToastDelay", "10"])
        let menu = app.buttons["account-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 20))
        menu.tap()
        let accounts = app.buttons["drawer-accounts"]
        XCTAssertTrue(accounts.waitForExistence(timeout: 5))
        accounts.tap()
        let addAccount = app.buttons["account-add"]
        XCTAssertTrue(addAccount.waitForExistence(timeout: 10), app.debugDescription)
        let toast = app.staticTexts["toast"]
        XCTAssertTrue(toast.waitForExistence(timeout: 20))
        XCTAssertTrue(toast.isHittable, "the toast is on top of the sheet")
        let screen = app.windows.firstMatch.frame
        XCTAssertEqual(toast.frame.midX, screen.midX, accuracy: 3)
        XCTAssertTrue(addAccount.isHittable, "the sheet still takes touches outside the pill")
    }

    /// The footer group sits at the bottom of the menu, always visible; the main group scrolls on its own when it
    /// does not fit (the largest standard text size here; the shared pinsFooter rule covers a landscape phone).
    func testSideMenuFooterIsPinnedAndTheMainGroupScrolls() {
        let app = launch(["-seedAccounts", "YES", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryXXXL"])
        let menu = app.buttons["account-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 20))
        menu.tap()
        let logout = app.buttons["menu-logout"]
        XCTAssertTrue(logout.waitForExistence(timeout: 5))
        let version = element(app, "drawer-version")
        let screen = app.windows.firstMatch.frame
        for id in ["menu-qr", "menu-settings", "menu-logout"] {
            let item = app.buttons[id]
            XCTAssertTrue(item.isHittable, "\(id) is visible")
            XCTAssertLessThan(item.frame.maxY, version.frame.minY + 1, "\(id) above the version line")
        }
        XCTAssertGreaterThan(version.frame.maxY, screen.maxY - 60, "the footer is at the bottom")
        // the main group ends above the footer and scrolls on its own; the footer stays put
        let footerTop = app.buttons["menu-qr"].frame.minY
        let explore = app.buttons["menu-explore"]
        let main = element(app, "drawer-main")
        XCTAssertLessThanOrEqual(main.frame.maxY, footerTop)
        var swipes = 0
        while !(explore.exists && explore.frame.maxY <= main.frame.maxY && explore.frame.minY >= main.frame.minY), swipes < 6 {
            main.swipeUp()
            swipes += 1
        }
        XCTAssertTrue(explore.isHittable, "the last main item scrolls into view")
        XCTAssertEqual(app.buttons["menu-qr"].frame.minY, footerTop, accuracy: 1, "the footer did not move")
    }

    /// Accessibility text sizes AX1 to AX5: every item stays reachable and the groups never overlap, whether the
    /// footer is pinned or the whole menu scrolls as one.
    func testSideMenuAtAccessibilityTextSizes() {
        for size in ["AccessibilityM", "AccessibilityL", "AccessibilityXL", "AccessibilityXXL", "AccessibilityXXXL"] {
            let app = launch(["-seedAccounts", "YES", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategory\(size)"])
            let menu = app.buttons["account-menu"]
            XCTAssertTrue(menu.waitForExistence(timeout: 20))
            menu.tap()
            XCTAssertTrue(app.buttons["menu-profile"].waitForExistence(timeout: 5), size)
            let screen = app.windows.firstMatch.frame
            for id in ["menu-explore", "menu-logout"] {
                let item = app.buttons[id]
                var swipes = 0
                // the main group scrolls on its own (pinned) or the whole menu does: either brings the item in
                while !item.isHittable, swipes < 8 {
                    element(app, "drawer-main").swipeUp()
                    if !item.isHittable { element(app, "drawer-menu").swipeUp() }
                    swipes += 1
                }
                XCTAssertTrue(item.isHittable, "\(id) is reachable at \(size)")
                XCTAssertLessThanOrEqual(item.frame.maxY, screen.maxY + 1, "\(id) on screen at \(size)")
            }
            let explore = app.buttons["menu-explore"]
            let qr = app.buttons["menu-qr"]
            if explore.isHittable && qr.isHittable {
                XCTAssertLessThanOrEqual(explore.frame.maxY, qr.frame.minY + 1, "the groups do not overlap at \(size)")
            }
            app.terminate()
        }
    }

    func testSideMenuItemsInRnOrderAndSettings() {
        let app = launch()
        let menu = app.buttons["account-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 15))
        XCTAssertEqual(menu.label, "Account, signed out")
        menu.tap()
        let ids = ["menu-add_account", "menu-qr", "menu-docs", "menu-settings"]
        XCTAssertTrue(app.buttons[ids[0]].waitForExistence(timeout: 5))
        let ys = ids.map { app.buttons[$0].frame.midY }
        XCTAssertEqual(ys, ys.sorted(), "top to bottom in RN order")
        XCTAssertFalse(app.buttons["menu-logout"].exists, "signed out: no logout")
        XCTAssertTrue(element(app, "drawer-version").label.hasPrefix("v4.0.0, "))
        app.buttons["menu-settings"].tap()
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["menu-settings"].exists, "the menu closed")
    }

    func testBackFromAPushedScreenAndScrimClosesTheMenu() {
        let app = launch()
        addUIInterruptionMonitor(withDescription: "camera") { alert in
            for label in ["Allow", "OK", "Don’t Allow"] where alert.buttons[label].exists {
                alert.buttons[label].tap()
                return true
            }
            return false
        }
        XCTAssertTrue(app.buttons["header-qr"].waitForExistence(timeout: 15))
        app.buttons["header-qr"].tap()
        // the QR scanner (platform wave) took the place of the placeholder this test used to open
        let close = app.buttons["qr-close"]
        XCTAssertTrue(close.waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["tab-feed"].isHittable, "pushed screens cover the tab bar")
        close.tap()
        XCTAssertTrue(app.buttons["tab-feed"].waitForExistence(timeout: 5))
        app.buttons["account-menu"].tap()
        XCTAssertTrue(app.buttons["menu-docs"].waitForExistence(timeout: 5))
        element(app, "drawer-scrim").coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.5)).tap()
        XCTAssertTrue(app.buttons["menu-docs"].waitForNonExistence(timeout: 5))
    }

    func testTheLockCoversTheShellAndAnOpenMenu() {
        let app = launch(["-lockDemo", "YES", "-lockTimeoutSeconds", "0"])
        XCTAssertTrue(app.buttons["pin-key-1"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.buttons["tab-feed"].exists, "nothing of the shell behind the lock")
        XCTAssertFalse(app.buttons["account-menu"].exists)
        for digit in "1234" { app.buttons["pin-key-\(digit)"].tap() }
        let menu = app.buttons["account-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 15))
        XCTAssertEqual(menu.label, "Account @spike-alice")
        menu.tap()
        XCTAssertTrue(app.buttons["menu-logout"].waitForExistence(timeout: 5))
        XCUIDevice.shared.press(.home)
        app.activate()
        XCTAssertTrue(app.buttons["pin-key-1"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["pin-key-1"].isHittable, "the PIN pad sits above the side menu")
        XCTAssertFalse(app.buttons["menu-logout"].exists, "the menu is gone from the hierarchy while locked")
    }

    func testAddAccountFromTheSwitcherOpensLogin() {
        let app = launch(["-seedAccounts", "YES"])
        let menu = app.buttons["account-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 15))
        menu.tap()
        XCTAssertTrue(app.buttons["drawer-accounts"].waitForExistence(timeout: 5))
        app.buttons["drawer-accounts"].tap()
        let add = app.buttons["account-add"]
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        add.tap()
        XCTAssertTrue(app.textFields["login-username"].waitForExistence(timeout: 10))
    }

    /// The system hands the app a link while the PIN pad shows (the main window's content is gone then): the
    /// scene-level handler keeps it and the link's confirmation shows after the unlock.
    func testALinkDeliveredWhileThePadShowsIsKept() {
        let app = launch(["-lockDemo", "YES"])
        XCTAssertTrue(app.buttons["pin-key-1"].waitForExistence(timeout: 15))
        let json = #"["vote",{"voter":"__signer","author":"spike-bob","permlink":"p","weight":10000}]"#
        let op = Data(json.utf8).base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: ".")
        XCUIDevice.shared.system.open(URL(string: "hive://sign/op/" + op)!)
        // iOS may ask "Open in ...?" first
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        let open = springboard.buttons["Open"]
        if open.waitForExistence(timeout: 3) { open.tap() }
        XCTAssertTrue(app.buttons["pin-key-1"].waitForExistence(timeout: 10), "still locked: nothing private yet")
        XCTAssertFalse(app.buttons["link-approve"].exists)
        for digit in "1234" { app.buttons["pin-key-\(digit)"].tap() }
        XCTAssertTrue(app.buttons["link-approve"].waitForExistence(timeout: 15), "the link was kept through the lock")
        app.buttons["link-cancel"].tap()
    }
}
