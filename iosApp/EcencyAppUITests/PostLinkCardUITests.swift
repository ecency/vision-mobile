import XCTest

/// #3705: a link to another post in a body is a card with the linked post's title and summary. Fake data: the demo
/// post `fake-post-links` links two fixture posts.
final class PostLinkCardUITests: XCTestCase {
    func testAPostLinkShowsTheLinkedPostsTitleAndSummary() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["-fakeData", "YES", "-openLink", "https://ecency.com/@spike-alice/fake-post-links"]
        app.launch()
        let card = app.descendants(matching: .any).matching(identifier: "post-link-card").firstMatch
        XCTAssertTrue(card.waitForExistence(timeout: 20), app.debugDescription)
        // the linked post is read once the card shows: its title, then the start of its text
        let read = NSPredicate(format: "label CONTAINS %@ AND label CONTAINS %@", "Fixture post", "Spike fixture")
        expectation(for: read, evaluatedWith: card)
        waitForExpectations(timeout: 15)
    }
}
