import XCTest

/// RN settings and email digests on iOS, against the offline demo services only.
final class SettingsUITests: XCTestCase {
    private func launch(_ extra: [String]) -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES"] + extra
        app.launch()
        return app
    }

    private func scrollTo(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<10 where !element.isHittable { app.swipeUp(velocity: .slow) }
    }

    func testSignedOutShowsTheDeviceSettingsOnly() {
        let app = launch(["-openScreen", "settings"])
        XCTAssertTrue(app.buttons["settings-currency"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["settings-language"].exists)
        XCTAssertTrue(app.buttons["settings-nsfw"].exists)
        XCTAssertFalse(app.switches["menu-pin-lock"].exists)
        let feedback = app.buttons["settings-feedback"]
        scrollTo(feedback, in: app)
        XCTAssertTrue(feedback.exists)
        XCTAssertFalse(app.buttons["settings-email-digests"].exists)
    }

    func testCurrencyPickSavesAndTheSupportCardSaves() {
        let app = launch(["-seedAccounts", "YES", "-openScreen", "settings"])
        let currency = app.buttons["settings-currency"]
        XCTAssertTrue(currency.waitForExistence(timeout: 15))
        currency.tap()
        let eur = app.buttons["EUR"].firstMatch
        XCTAssertTrue(eur.waitForExistence(timeout: 5))
        eur.tap()
        let picked = expectation(for: NSPredicate(format: "label CONTAINS 'EUR'"), evaluatedWith: app.buttons["settings-currency"])
        wait(for: [picked], timeout: 5)
        let support = app.switches["support-beneficiary"]
        scrollTo(support, in: app)
        XCTAssertTrue(support.waitForExistence(timeout: 10), "the card shows once the demo settings loaded")
        support.switches.firstMatch.tap()
        XCTAssertTrue(app.buttons["support-beneficiary-percent"].waitForExistence(timeout: 5), "5% on, the percent picker shows")
    }

    func testEmailDigestsListsAndStopsAll() {
        let app = launch(["-seedAccounts", "YES", "-demoDigests", "YES", "-openScreen", "settings"])
        let row = app.buttons["settings-email-digests"]
        XCTAssertTrue(app.buttons["settings-currency"].waitForExistence(timeout: 15))
        scrollTo(row, in: app)
        row.tap()
        XCTAssertTrue(app.navigationBars["Email digests"].waitForExistence(timeout: 10), "the screen opened")
        XCTAssertTrue(app.buttons["digest-d1"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["digest-d2"].exists)
        XCTAssertTrue(app.buttons["digest-offer-site"].exists)
        XCTAssertFalse(app.buttons["digest-offer-own"].exists, "own is subscribed")
        app.buttons["stop-all-reader@example.com"].tap()
        let alert = app.alerts["Stop all email?"]
        XCTAssertTrue(alert.waitForExistence(timeout: 5))
        alert.buttons["Stop all"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["digests-empty"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["digest-offer-own"].exists)
    }
}
