import XCTest

final class RendererDemoLaunchTests: XCTestCase {
    func testRendererDemoShowsTheSharedCoreGreeting() {
        let app = XCUIApplication()
        app.launchArguments += ["-rendererDemo", "YES"]
        app.launch()

        let greeting = app.staticTexts["greeting"]
        XCTAssertTrue(greeting.waitForExistence(timeout: 10))
        XCTAssertTrue(greeting.label.contains("4.0.0"))
    }
}
