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
        Reports.handled(CocoaError(.fileNoSuchFile), where: "image.save")
        Reports.quiet(CancellationError(), where: "image.save")
        // a lost connection: nothing when quiet, a warning when the screen shows it
        Reports.quiet(URLError(.notConnectedToInternet), where: "image.download")
        Reports.handled(URLError(.timedOut), where: "image.download")
        XCTAssertEqual(recorder.reports.count, 2)
        let file = recorder.reports[0]
        XCTAssertEqual(file.tags["where"], "image.save")
        // grouped by domain and code, never the description in the device language
        XCTAssertEqual(file.fingerprint, ["handled", "image.save", "unknown", "CocoaError", "NSCocoaErrorDomain 4"])
        let offline = recorder.reports[1]
        XCTAssertEqual(offline.level, "warning")
        XCTAssertEqual(offline.tags["error.kind"], "network")
    }

    func testMechanismTextsAreScrubbed() {
        let wif = "5JdeC9P7Pbd1uGdFVEsJ41EkEnADbbHGq6p1BwFxm6txNBsQnsw"
        let password = "P5" + String(repeating: "K", count: 50)
        let event = Event(level: .error)
        let e = Exception(value: "pass \(password)", type: "NSError")
        let mechanism = Mechanism(type: "NSError")
        mechanism.desc = "key \(wif)"
        mechanism.data = ["KotlinException": "JSON input: {\"postingKey\":\"\(wif)\""]
        e.mechanism = mechanism
        event.exceptions = [e]
        let sent = CrashReporting.beforeSend(event)
        let out = sent?.exceptions?.first
        XCTAssertFalse(out?.value.contains(password) ?? true)
        XCTAssertFalse(out?.mechanism?.desc?.contains(wif) ?? false)
        XCTAssertFalse(String(describing: out?.mechanism?.data ?? [:]).contains(wif))

        // a start failure goes as an event built from the type, domain and code, never the bridged NSError
        let start = CrashReporting.startFailureEvent(NSError(domain: "Start", code: 7, userInfo: ["detail": "key \(wif)"]))
        XCTAssertEqual(start.exceptions?.first?.value, "Start 7")
        XCTAssertNil(start.exceptions?.first?.mechanism)
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
