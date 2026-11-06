import EcencyCore
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

    func testAHandledReportIsAnEventWithItsFingerprintTagsAndAScrubbedContext() {
        // Bitcoin wiki example key, controls nothing
        let wif = "5JdeC9P7Pbd1uGdFVEsJ41EkEnADbbHGq6p1BwFxm6txNBsQnsw"
        let report = CrashReport(
            type: "Rejected",
            message: "refused for key \(wif)",
            tags: ["handled": "true", "where": "editor.publish", "error.kind": "rejected", "exception": "Rejected"],
            fingerprint: ["handled", "editor.publish", "rejected", "Rejected", "refused for key \(wif)"],
            contextName: "handled",
            context: ["where": "editor.publish", "stack": "Rejected: refused for key \(wif)\n\tat Editor.submit", "memo": "secret"],
            isMessage: false,
            level: "warning"
        )
        let sent = CrashReporting.beforeSend(CrashReporting.Reporter.event(report))
        XCTAssertEqual(sent?.level, .warning)
        XCTAssertEqual(sent?.tags?["where"], "editor.publish")
        XCTAssertEqual(sent?.tags?["handled"], "true")
        XCTAssertEqual(Array((sent?.fingerprint ?? []).prefix(4)), ["handled", "editor.publish", "rejected", "Rejected"])
        XCTAssertFalse((sent?.fingerprint ?? []).contains { $0.contains(wif) })
        XCTAssertFalse(sent?.exceptions?.first?.value.contains(wif) ?? true)
        let context = sent?.context?["handled"]
        let stack = context?["stack"] as? String ?? ""
        XCTAssertFalse(stack.contains(wif))
        XCTAssertTrue(stack.contains("Editor.submit"))
        XCTAssertEqual(context?["memo"] as? String, "<redacted>")
    }

    func testASwiftErrorGoesThroughTheSharedReports() {
        let recorder = RecordingCrashReporter()
        let store = KeychainSecureStore(service: "app.esteem.mobile.ios.tests.reports")
        let env = AppEnvironment(
            api: try! EcencyApi(nodes: ["https://fake-hive.invalid"], transport: FakeHiveTransport()),
            vault: KeyVaultAsync(vault: KeyVault(store: store)),
            renderer: PostBodyRendererCompanion.shared.`default`(proxyBase: ImageProxy.companion.DEFAULT_BASE),
            dispatchers: AppDispatchers.companion.platform(),
            proxyBase: ImageProxy.companion.DEFAULT_BASE,
            debugKeyImport: true,
            nowMillis: { KotlinLong(value: 0) },
            security: SecurityConfig(pinIterations: 16, lockTimeoutMillis: 60_000, monotonicMillis: { KotlinLong(value: 0) }),
            legacy: nil,
            auth: DemoAuthServices.shared.create(nowMillis: { KotlinLong(value: 0) }, waitMillis: 1000)
        )
        env.crashReporter = recorder
        Reports.handled(URLError(.timedOut), where: "image.save")
        Reports.quiet(CancellationError(), where: "image.save")
        XCTAssertEqual(recorder.reports.count, 1)
        XCTAssertEqual(recorder.reports.first?.tags["where"], "image.save")
        XCTAssertEqual(recorder.reports.first?.fingerprint.first, "handled")
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

/// Keeps what the shared code reports, in order.
final class RecordingCrashReporter: NSObject, CrashReporter {
    private(set) var reports: [CrashReport] = []

    func capture(report: CrashReport) { reports.append(report) }

    func breadcrumb(category: String, message: String, data: [String: String]) {}
}
