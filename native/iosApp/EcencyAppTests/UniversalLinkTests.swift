@testable import EcencyApp
import EcencyCore
import XCTest

/// ecency.com universal links (README "Login and accounts"): only https pages on the entitled hosts reach the link
/// handling, which then treats them like any other ecency.com link.
final class UniversalLinkTests: XCTestCase {
    private func activity(_ url: String?, type: String = NSUserActivityTypeBrowsingWeb) -> NSUserActivity {
        let activity = NSUserActivity(activityType: type)
        activity.webpageURL = url.flatMap(URL.init(string:))
        return activity
    }

    func testEntitledHttpsPagesAreTaken() {
        XCTAssertEqual(UniversalLinks.url(of: activity("https://ecency.com/@good-karma"))?.absoluteString, "https://ecency.com/@good-karma")
        XCTAssertNotNil(UniversalLinks.url(of: activity("https://alpha.ecency.com/hive-125125/@a/p")))
        XCTAssertNotNil(UniversalLinks.url(of: activity("https://ECENCY.com/trending")))
    }

    func testOtherActivitiesHostsAndSchemesAreIgnored() {
        XCTAssertNil(UniversalLinks.url(of: activity("https://ecency.com/@a", type: "com.example.other")))
        XCTAssertNil(UniversalLinks.url(of: activity("http://ecency.com/@a")))
        XCTAssertNil(UniversalLinks.url(of: activity("https://ecency.com.evil.example/@a")))
        XCTAssertNil(UniversalLinks.url(of: activity("https://ecency.page.link/x")))
        XCTAssertNil(UniversalLinks.url(of: activity(nil)))
    }

    func testATakenLinkIsAContentLink() {
        let url = UniversalLinks.url(of: activity("https://ecency.com/hive-125125/@author/permlink"))!
        XCTAssertNotNil(ContentLinks.shared.entry(url: url.absoluteString))
    }
}
