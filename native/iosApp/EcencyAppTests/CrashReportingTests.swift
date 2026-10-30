import XCTest
@testable import EcencyApp

/// Performance data (issue #3571) at a low sample rate, with nothing private in its spans.
final class CrashReportingTests: XCTestCase {
    func testTracesSampleFivePercent() {
        XCTAssertEqual(CrashReporting.tracesSampleRate, 0.05)
    }

    func testSpansWithUrlsPathsOrQueriesAreDroppedAndTheRestScrubbed() {
        // Bitcoin wiki example key, controls nothing
        let wif = "5JdeC9P7Pbd1uGdFVEsJ41EkEnADbbHGq6p1BwFxm6txNBsQnsw"
        XCTAssertNil(CrashReporting.spanDescription(op: "http.client", description: "GET https://images.example/upload/tester/signature"))
        XCTAssertNil(CrashReporting.spanDescription(op: "file.read", description: "/var/mobile/tester.json"))
        XCTAssertNil(CrashReporting.spanDescription(op: "db.sql.query", description: "select"))
        XCTAssertEqual(CrashReporting.spanDescription(op: "ui.load", description: "FeedView"), .some("FeedView"))
        let scrubbed = CrashReporting.spanDescription(op: "ui.load", description: "key \(wif)")
        XCTAssertNotNil(scrubbed)
        XCTAssertFalse((scrubbed ?? nil)?.contains(wif) ?? false)
    }
}
