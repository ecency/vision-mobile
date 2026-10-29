import XCTest

/// Login and accounts against the fake transport and the offline auth services (`-fakeData YES`): throwaway
/// accounts, no network, nothing broadcast.
final class LoginUITests: XCTestCase {
    private let password = "P5UiTestThrowawayPasswordNotReal"

    private func launch(_ extra: [String] = []) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES"] + extra
        app.launch()
        return app
    }

    func testLoginFormValidatesShowsRnErrorsAndSignsIn() {
        let app = launch(["-openScreen", "login"])
        let user = app.textFields["login-username"]
        XCTAssertTrue(user.waitForExistence(timeout: 15))
        XCTAssertFalse(app.buttons["login-submit"].isEnabled)
        user.focusAndType("spike-alice")
        let key = app.secureTextFields["login-key"]
        key.focusAndType("wrong password")
        app.buttons["login-submit"].tap()
        let alert = app.alerts["Login Failed"]
        XCTAssertTrue(alert.waitForExistence(timeout: 10))
        XCTAssertTrue(alert.staticTexts.containing(NSPredicate(format: "label CONTAINS 'Invalid credentials'")).firstMatch.exists)
        alert.buttons["OK"].tap()

        key.tap(); key.press(forDuration: 1.0)
        if app.menuItems["Select All"].waitForExistence(timeout: 2) { app.menuItems["Select All"].tap() }
        key.focusAndType(XCUIKeyboardKey.delete.rawValue)
        key.focusAndType(password)
        app.buttons["login-submit"].tap()
        XCTAssertTrue(app.buttons["Account @spike-alice"].waitForExistence(timeout: 15) || app.otherElements["account-menu"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.textFields["login-username"].exists)
    }

    func testHiveAuthShowsAQrCode() {
        let app = launch(["-openScreen", "login"])
        XCTAssertTrue(app.buttons["login-hiveauth"].waitForExistence(timeout: 15))
        app.buttons["login-hiveauth"].tap()
        let user = app.textFields["hiveauth-username"]
        XCTAssertTrue(user.waitForExistence(timeout: 5))
        user.focusAndType("spike-alice")
        app.buttons["hiveauth-start"].tap()
        XCTAssertTrue(app.images["hiveauth-qr"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["hiveauth-open-wallet"].exists)
        app.buttons["hiveauth-cancel"].tap()
    }

    func testAccountSwitcherAndLogoutConfirm() {
        let app = launch(["-seedAccounts", "YES"])
        // RN: the accounts sheet from the side menu header
        let menu = app.buttons["account-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 15))
        menu.tap()
        XCTAssertTrue(app.buttons["drawer-accounts"].waitForExistence(timeout: 5))
        app.buttons["drawer-accounts"].tap()
        XCTAssertTrue(app.otherElements["account-spike-alice"].waitForExistence(timeout: 15) || app.cells.containing(.staticText, identifier: "@spike-alice").firstMatch.waitForExistence(timeout: 5))
        XCTAssertEqual(app.images.matching(identifier: "current-mark").count, 1)
        app.buttons["logout-spike-bob"].tap()
        XCTAssertTrue(app.alerts["Are you sure you want to logout?"].waitForExistence(timeout: 5))
        app.alerts.firstMatch.buttons["logout-cancel"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["@spike-bob"].exists)
        app.buttons["logout-spike-bob"].tap()
        app.alerts.firstMatch.buttons["logout-confirm"].firstMatch.tap()
        let gone = NSPredicate(format: "exists == false")
        expectation(for: gone, evaluatedWith: app.staticTexts["@spike-bob"])
        waitForExpectations(timeout: 10)
        XCTAssertTrue(app.staticTexts["@spike-alice"].exists)
    }

    func testAuthUpgradeSheetChecksTheKey() {
        let app = launch(["-seedAccounts", "YES", "-demoSigning", "upgrade"])
        let key = app.secureTextFields["upgrade-key"]
        XCTAssertTrue(key.waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["Authorization Required"].exists)
        key.focusAndType("5KnotTheActiveKey")
        app.buttons["upgrade-sign"].tap()
        XCTAssertTrue(app.staticTexts["upgrade-error"].waitForExistence(timeout: 10))
        app.buttons["upgrade-cancel"].tap()
        XCTAssertFalse(app.secureTextFields["upgrade-key"].waitForExistence(timeout: 2))
    }

    func testSignLinksAskFirstAndOwnerOperationsAreRefused() {
        // vote as the current account
        let vote = "hive://sign/op/" + b64u(#"["vote",{"voter":"__signer","author":"spike-bob","permlink":"p","weight":10000}]"#)
        let app = launch(["-seedAccounts", "YES", "-openLink", vote])
        XCTAssertTrue(app.buttons["link-approve"].waitForExistence(timeout: 15))
        XCTAssertEqual(app.staticTexts["sign-authority"].label, "POSTING")
        app.buttons["link-cancel"].tap()
        app.terminate()

        let owner = "hive://sign/op/" + b64u(#"["change_recovery_account",{"account_to_recover":"spike-alice","new_recovery_account":"spike-bob","extensions":[]}]"#)
        let app2 = launch(["-seedAccounts", "YES", "-openLink", owner])
        XCTAssertTrue(app2.staticTexts["link-refused"].waitForExistence(timeout: 15))
        XCTAssertFalse(app2.buttons["link-approve"].exists)
    }

    func testWelcomeNeedsTheTerms() {
        let app = launch(["-showWelcome", "YES"])
        let start = app.buttons["welcome-start"]
        XCTAssertTrue(start.waitForExistence(timeout: 15))
        XCTAssertFalse(start.isEnabled)
        app.buttons["welcome-terms"].tap()
        XCTAssertTrue(start.isEnabled)
        start.tap()
        XCTAssertFalse(start.waitForExistence(timeout: 2))
    }

    private func b64u(_ s: String) -> String {
        Data(s.utf8).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: ".")
    }

    func testDrawerLogoutSwitchesToTheNextAccount() {
        let app = launch(["-seedAccounts", "YES"])
        let menu = app.buttons["account-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 15))
        XCTAssertEqual(menu.label, "Account @spike-alice")
        menu.tap()
        let logout = app.buttons["menu-logout"]
        XCTAssertTrue(logout.waitForExistence(timeout: 5))
        logout.tap()
        XCTAssertTrue(app.alerts["Are you sure you want to logout?"].waitForExistence(timeout: 5))
        app.alerts.firstMatch.buttons["logout-confirm"].firstMatch.tap()
        let next = NSPredicate(format: "label == 'Account @spike-bob'")
        expectation(for: next, evaluatedWith: menu)
        waitForExpectations(timeout: 10)
    }

    func testManageAuthoritiesFromSettings() {
        let app = launch(["-seedAccounts", "YES"])
        let menu = app.buttons["account-menu"]
        XCTAssertTrue(menu.waitForExistence(timeout: 15))
        menu.tap()
        app.buttons["menu-settings"].tap()
        let keys = app.buttons["menu-backup-keys"]
        // RN keeps it near the end of Settings: scroll to it
        XCTAssertTrue(app.buttons["settings-currency"].waitForExistence(timeout: 5))
        for _ in 0..<15 where !(keys.exists && keys.isHittable) { app.swipeUp() }
        XCTAssertTrue(keys.waitForExistence(timeout: 5))
        keys.tap()
        XCTAssertTrue(app.navigationBars["Manage Authorities"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 5), "with the lock off it asks to set a PIN first")
    }
}
