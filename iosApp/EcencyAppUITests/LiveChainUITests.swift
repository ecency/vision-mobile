import XCTest

/// Exploratory checks against the REAL Hive chain (no `-fakeData`). Skipped unless the run passes the accounts as
/// `TEST_RUNNER_LIVE_A_USER` / `LIVE_A_PASS` / `LIVE_B_USER` / `LIVE_B_PASS`. Each test is one step run by hand;
/// state (signed in accounts) stays in the simulator's Keychain between steps. Screenshots and the element tree go to
/// `LIVE_SHOT_DIR`. Never commit credentials: they come from the environment only.
final class LiveChainUITests: XCTestCase {
    private var env: [String: String] { ProcessInfo.processInfo.environment }

    /** The value [name] the step needs; the step is skipped, naming it, when the run did not pass it. */
    private func need(_ name: String) throws -> String {
        guard let value = env[name], !value.isEmpty else { throw XCTSkip("\(name) not given for this step") }
        return value
    }
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        guard env["LIVE_A_USER"]?.isEmpty == false else { throw XCTSkip("live chain accounts not given") }
    }

    private func launch(_ extra: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += extra
        app.launch()
        self.app = app
        return app
    }

    private func shot(_ name: String) {
        guard let dir = env["LIVE_SHOT_DIR"], !dir.isEmpty else { return }
        let base = URL(fileURLWithPath: dir)
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: base.appendingPathComponent("\(name).png"))
        if let app { try? app.debugDescription.write(to: base.appendingPathComponent("\(name).txt"), atomically: true, encoding: .utf8) }
    }

    private func el(_ id: String) -> XCUIElement { app.descendants(matching: .any).matching(identifier: id).firstMatch }

    private func wait(_ e: XCUIElement, _ t: TimeInterval = 20) -> Bool { e.waitForExistence(timeout: t) }

    private func pause(_ s: TimeInterval) { Thread.sleep(forTimeInterval: s) }

    // MARK: steps

    func test00Probe() throws {
        _ = launch()
        pause(8)
        shot("00-probe")
    }

    func test01LoginA() throws {
        login(try need("LIVE_A_USER"), try need("LIVE_A_PASS"), shotPrefix: "01")
    }

    func test02LoginB() throws {
        login(try need("LIVE_B_USER"), try need("LIVE_B_PASS"), shotPrefix: "02")
    }

    private func login(_ user: String, _ pass: String, shotPrefix: String) {
        _ = launch(["-openScreen", "login"])
        let field = app.textFields["login-username"]
        XCTAssertTrue(wait(field, 30), app.debugDescription)
        field.focusAndType(user)
        app.secureTextFields["login-key"].focusAndType(pass)
        shot("\(shotPrefix)-login-filled")
        app.buttons["login-submit"].tap()
        let start = Date()
        while Date().timeIntervalSince(start) < 60 {
            if !app.textFields["login-username"].exists { break }
            if app.alerts.firstMatch.exists { break }
            pause(1)
        }
        pause(3)
        shot("\(shotPrefix)-login-after")
        XCTAssertFalse(app.alerts.firstMatch.exists, "alert: \(app.alerts.firstMatch.debugDescription)")
        XCTAssertFalse(app.textFields["login-username"].exists)
        let menu = app.buttons["account-menu"]
        XCTAssertTrue(wait(menu, 20))
        XCTAssertEqual(menu.label, "Account @\(user)")
    }

    func test03Post() throws {
        _ = launch()
        let fab = app.buttons["create-fab"]
        XCTAssertTrue(wait(fab, 30), app.debugDescription)
        fab.tap()
        let title = el("editor-title")
        if !wait(title, 15) {
            shot("03-after-fab")
            // the FAB may open a chooser first
            XCTFail("editor did not open: \(app.debugDescription)")
            return
        }
        let stamp = env["LIVE_STAMP"] ?? "x"
        title.focusAndType("Native iOS live test \(stamp)")
        el("tag-input").focusAndType("test ")
        app.textViews["editor-body"].focusAndType("This is an automated test post from the native iOS app test run \(stamp). Please ignore.")
        shot("03-editor-filled")
        app.buttons["editor-submit"].tap()
        pause(2)
        shot("03-after-submit")
        if app.alerts.firstMatch.waitForExistence(timeout: 10) {
            let alert = app.alerts.firstMatch
            print("ALERT: \(alert.label)")
            if alert.buttons["Yes"].exists { alert.buttons["Yes"].tap() } else { XCTFail("unexpected alert \(alert.debugDescription)"); return }
        }
        let start = Date()
        var seen = false
        while Date().timeIntervalSince(start) < 90 {
            if app.staticTexts["post-title"].exists { seen = true; break }
            if app.alerts.firstMatch.exists { break }
            if el("editor-error").exists { break }
            pause(1)
        }
        pause(3)
        shot("03-after-publish")
        XCTAssertTrue(seen, "post did not open: \(app.debugDescription)")
        if seen { print("POST-TITLE: \(app.staticTexts["post-title"].label)") }
    }

    private func openPost(_ author: String, _ permlink: String, prefix: String) -> Bool {
        _ = launch(["-openLink", "https://ecency.com/@\(author)/\(permlink)"])
        let title = app.staticTexts["post-title"]
        let ok = wait(title, 40)
        pause(3)
        shot("\(prefix)-post-open")
        if ok { print("POST-TITLE: \(title.label)") }
        print("INFO account: \(app.buttons["account-menu"].exists ? app.buttons["account-menu"].label : "(no menu on this screen)")")
        return ok
    }

    /// Opens the vote sheet from [button], moves the weight to 1% and submits only if the button says so.
    private func voteOnePercent(_ button: XCUIElement, prefix: String) {
        XCTAssertTrue(wait(button, 20))
        print("INFO vote value before: \(String(describing: button.value))")
        button.tap()
        let submit = app.buttons["vote-submit"]
        XCTAssertTrue(wait(submit, 10), app.debugDescription)
        app.sliders["vote-weight"].adjust(toNormalizedSliderPosition: 0)
        pause(1)
        shot("\(prefix)-vote-sheet")
        print("INFO vote submit label: \(submit.label)")
        guard submit.label == "Upvote 1%" else { XCTFail("weight not 1%: \(submit.label)"); return }
        submit.tap()
        pause(8)
        shot("\(prefix)-vote-after")
        print("INFO vote value after: \(String(describing: button.value))")
    }

    func test04ReplyAsB() throws {
        let permlink = try need("LIVE_POST")
        guard openPost(try need("LIVE_A_USER"), permlink, prefix: "04") else { XCTFail("post did not open \(app.debugDescription)"); return }
        let field = app.buttons["write-reply"]
        for _ in 0..<12 where !field.exists || !field.isHittable { app.swipeUp(velocity: .slow) }
        shot("04-reply-row")
        print("INFO write-reply label: \(field.label)")
        field.tap()
        let text = app.textViews["reply-text"]
        XCTAssertTrue(wait(text, 10), app.debugDescription)
        let body = "Automated reply from the native iOS live test \(env["LIVE_STAMP"] ?? ""). Please ignore."
        text.focusAndType(body)
        text.waitForValue(body)
        shot("04-reply-filled")
        app.buttons["reply-send"].tap()
        let start = Date()
        while Date().timeIntervalSince(start) < 60 {
            if !text.exists { break }
            if el("reply-error").exists || app.alerts.firstMatch.exists { break }
            pause(1)
        }
        pause(4)
        shot("04-reply-after")
        XCTAssertFalse(el("reply-error").exists, "reply error: \(el("reply-error").debugDescription)")
        let mine = app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "Automated reply from the native iOS live test")).firstMatch
        print("INFO reply shown in list: \(mine.exists)")
    }

    func test05VotePostAsB() throws {
        guard openPost(try need("LIVE_A_USER"), try need("LIVE_POST"), prefix: "05") else { XCTFail("post did not open"); return }
        voteOnePercent(app.buttons.matching(identifier: "vote-button").firstMatch, prefix: "05")
    }

    func test06Profile() throws {
        _ = launch()
        let menu = app.buttons["account-menu"]
        XCTAssertTrue(wait(menu, 30))
        print("INFO account: \(menu.label)")
        menu.tap()
        pause(2)
        shot("06-drawer")
        if el("drawer-user").exists { el("drawer-user").tap() }
        pause(8)
        shot("06-profile")
    }

    private func dumpWallet(_ prefix: String) {
        let balances = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'asset-balance-' OR identifier == 'wallet-total' OR identifier BEGINSWITH 'asset-fiat'"))
        for e in balances.allElementsBoundByIndex { print("INFO \(prefix) \(e.identifier) = \(e.label)") }
    }

    private func openWallet(_ prefix: String) {
        _ = launch()
        let tab = el("tab-wallet")
        goRoot(tab)
        tab.tap()
        let ok = wait(el("asset-balance-HIVE"), 40)
        if !ok { shot("\(prefix)-wallet-fail") }
        XCTAssertTrue(ok)
        pause(6)
        shot("\(prefix)-wallet")
        print("INFO account: \(app.buttons["account-menu"].exists ? app.buttons["account-menu"].label : "?")")
        dumpWallet(prefix)
    }

    /// Saved navigation can bring back a pushed screen: go back until [tab] shows.
    private func goRoot(_ tab: XCUIElement) {
        for i in 0..<6 {
            if tab.waitForExistence(timeout: 8) { return }
            shot("goRoot-\(i)")
            let back = app.buttons.matching(NSPredicate(format: "label == 'Back' OR identifier == 'BackButton' OR identifier == 'editor-back'")).firstMatch
            if back.exists { back.tap() } else {
                let w = app.windows.firstMatch
                w.coordinate(withNormalizedOffset: CGVector(dx: 0.01, dy: 0.5)).press(forDuration: 0.05, thenDragTo: w.coordinate(withNormalizedOffset: CGVector(dx: 0.8, dy: 0.5)))
            }
        }
        XCTAssertTrue(tab.exists, app.debugDescription)
    }

    func test07WalletAndTransfer() throws {
        openWallet("07")
        app.buttons["asset-HIVE"].tap()
        let action = app.buttons["action-transfer"]
        XCTAssertTrue(wait(action, 20), app.debugDescription)
        pause(2)
        shot("07-hive-details")
        action.tap()
        let to = el("transfer-to")
        XCTAssertTrue(wait(to, 20), app.debugDescription)
        let target = try need("LIVE_TO")
        to.focusAndType(target)
        el("transfer-amount").focusAndType("0.001")
        if el("transfer-memo").exists { el("transfer-memo").focusAndType("native iOS live test") }
        pause(3)
        shot("07-transfer-filled")
        print("INFO transfer-balance: \(el("transfer-balance").label)")
        let next = app.buttons["transfer-next"]
        for _ in 0..<4 where !next.isHittable { app.swipeUp() }
        guard next.isEnabled else { XCTFail("next disabled \(app.debugDescription)"); return }
        next.tap()
        let confirm = app.buttons["transfer-confirm"].firstMatch
        XCTAssertTrue(wait(confirm, 10), app.debugDescription)
        pause(1)
        shot("07-transfer-confirm")
        for t in app.staticTexts.allElementsBoundByIndex.prefix(40) { print("INFO confirm text: \(t.label)") }
        guard env["LIVE_SEND"] == "1" else { return }
        confirm.tap()
        pause(10)
        shot("07-transfer-after")
        if app.alerts.firstMatch.exists { print("ALERT: \(app.alerts.firstMatch.label)") }
        for t in app.staticTexts.allElementsBoundByIndex.prefix(30) { print("INFO after text: \(t.label)") }
    }

    func test08Wallet() throws { openWallet("08-\(env["LIVE_TAG"] ?? "x")") }

    private func switchTo(_ user: String, prefix: String) {
        _ = launch()
        goRoot(el("tab-feed"))
        el("tab-feed").tap()
        let menu = app.buttons["account-menu"]
        XCTAssertTrue(wait(menu, 20))
        print("INFO before switch: \(menu.label)")
        menu.tap()
        XCTAssertTrue(wait(app.buttons["drawer-accounts"], 10), app.debugDescription)
        app.buttons["drawer-accounts"].tap()
        pause(3)
        shot("\(prefix)-accounts")
        let row = el("account-\(user)")
        XCTAssertTrue(wait(row, 15), app.debugDescription)
        row.tap()
        pause(5)
        shot("\(prefix)-switched")
        let start = Date()
        while Date().timeIntervalSince(start) < 15, !(menu.exists && menu.label == "Account @\(user)") { pause(1) }
        print("INFO after switch: \(menu.exists ? menu.label : "(menu gone)")")
        XCTAssertEqual(menu.label, "Account @\(user)")
    }

    func test09SwitchToA() throws { switchTo(try need("LIVE_A_USER"), prefix: "09") }
    func test09SwitchToB() throws { switchTo(try need("LIVE_B_USER"), prefix: "09b") }

    func test10Notifications() throws {
        _ = launch()
        goRoot(el("tab-notification"))
        print("INFO account: \(app.buttons["account-menu"].exists ? app.buttons["account-menu"].label : "?") badge: \(String(describing: el("tab-notification").value))")
        el("tab-notification").tap()
        pause(10)
        shot("10-notifications")
        let rows = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'notification-text-'"))
        for r in rows.allElementsBoundByIndex.prefix(10) { print("INFO notif: \(r.label)") }
        print("INFO badge after: \(String(describing: el("tab-notification").value))")
    }

    func test11ReplyToReplyAsA() throws {
        guard openPost(try need("LIVE_B_USER"), try need("LIVE_REPLY"), prefix: "11") else { XCTFail("reply page did not open \(app.debugDescription)"); return }
        let field = app.buttons["write-reply"]
        for _ in 0..<12 where !field.exists || !field.isHittable { app.swipeUp(velocity: .slow) }
        shot("11-reply-row")
        field.tap()
        let text = app.textViews["reply-text"]
        XCTAssertTrue(wait(text, 10), app.debugDescription)
        let body = "Second automated reply from the native iOS live test. Please ignore."
        text.focusAndType(body)
        text.waitForValue(body)
        app.buttons["reply-send"].tap()
        let start = Date()
        while Date().timeIntervalSince(start) < 60 {
            if !text.exists { break }
            if el("reply-error").exists || app.alerts.firstMatch.exists { break }
            pause(1)
        }
        pause(4)
        shot("11-reply-after")
        XCTAssertFalse(el("reply-error").exists)
    }

    func test12VoteReplyAsA() throws {
        guard openPost(try need("LIVE_B_USER"), try need("LIVE_REPLY"), prefix: "12") else { XCTFail("reply page did not open"); return }
        for b in app.buttons.matching(identifier: "vote-button").allElementsBoundByIndex { print("INFO vote btn \(b.frame) \(String(describing: b.value))") }
        voteOnePercent(app.buttons.matching(identifier: "vote-button").firstMatch, prefix: "12")
    }

    func test13Points() throws {
        openWallet("13")
        app.buttons["asset-POINTS"].tap()
        pause(10)
        shot("13-points")
        for t in app.staticTexts.allElementsBoundByIndex.prefix(40) { print("INFO points text: \(t.label)") }
    }

    func test14Logout() throws {
        _ = launch()
        goRoot(el("tab-feed"))
        el("tab-feed").tap()
        let menu = app.buttons["account-menu"]
        XCTAssertTrue(wait(menu, 20))
        print("INFO before logout: \(menu.label)")
        menu.tap()
        let logout = app.buttons["menu-logout"]
        for _ in 0..<6 where !(logout.exists && logout.isHittable) { el("drawer").swipeUp() }
        XCTAssertTrue(wait(logout, 10), app.debugDescription)
        logout.tap()
        XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 5))
        print("ALERT: \(app.alerts.firstMatch.label)")
        app.alerts.firstMatch.buttons["logout-confirm"].firstMatch.tap()
        pause(6)
        shot("14-after-logout")
        print("INFO after logout: \(menu.exists ? menu.label : "(menu gone)")")
        menu.tap()
        pause(2)
        if app.buttons["drawer-accounts"].exists { app.buttons["drawer-accounts"].tap(); pause(3) }
        shot("14-accounts-after")
        let accts = app.descendants(matching: .any).matching(NSPredicate(format: "identifier BEGINSWITH 'account-'"))
        for a in accts.allElementsBoundByIndex { print("INFO acct row: \(a.identifier)") }
    }
}
