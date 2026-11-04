import XCTest
import Sentry
@testable import EcencyApp

/// Performance data (issue #3571) at a low sample rate, with nothing private in its spans.
final class CrashReportingTests: XCTestCase {
    func testTracesSampleFivePercent() {
        XCTAssertEqual(CrashReporting.tracesSampleRate, 0.05)
    }

    func testSpansWithUrlsPathsOrQueriesAreDroppedAndTheRestScrubbed() {
        // Bitcoin wiki example key, controls nothing
        let wif = "5JdeC9P7Pbd1uGdFVEsJ41EkEnADbbHGq6p1BwFxm6txNBsQnsw"
        // the outer nil drops the span; an inner nil would keep it without a description
        XCTAssertTrue(CrashReporting.spanDescription(op: "http.client", description: "GET https://images.example/upload/tester/signature") == nil)
        XCTAssertTrue(CrashReporting.spanDescription(op: "file.read", description: "/var/mobile/tester.json") == nil)
        XCTAssertTrue(CrashReporting.spanDescription(op: "db.sql.query", description: "select") == nil)
        XCTAssertEqual(CrashReporting.spanDescription(op: "ui.load", description: "FeedView"), .some("FeedView"))
        let scrubbed = CrashReporting.spanDescription(op: "ui.load", description: "key \(wif)")
        XCTAssertTrue(scrubbed != nil)
        XCTAssertFalse((scrubbed ?? nil)?.contains(wif) ?? false)
    }

    func testRequestBodyHeadersAndCookiesAreNotSent() {
        let event = Event()
        let request = SentryRequest()
        request.url = "https://api.example/path"
        request.bodySize = 42
        request.headers = ["Authorization": "x"]
        request.cookies = "session=x"
        event.request = request
        let sent = CrashReporting.beforeSend(event)
        XCTAssertNil(sent?.request?.bodySize)
        XCTAssertNil(sent?.request?.headers)
        XCTAssertNil(sent?.request?.cookies)
    }
}
