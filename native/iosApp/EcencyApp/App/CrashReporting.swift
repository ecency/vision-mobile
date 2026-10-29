import EcencyCore
import Foundation
import Sentry

/// Sentry as RN App.tsx sets it up, with the DSN the shared `CrashDsn` picks (RN's project unless `ecency.sentryDsn`,
/// written by the "Build secrets" phase, names another or "off"): no default PII, no tracing, no session replay, errors at
/// Sentry's default sample rate (all), the release `<bundle id>@<version>+<build>` with the build number as dist (RN's
/// CI naming). Every event and breadcrumb goes through the shared `CrashScrubber`; RN's benign errors are dropped.
/// Without a DSN nothing starts.
enum CrashReporting {
    private(set) static var started = false

    static func start(dsn: String, bundleId: String, version: String, build: String, debug: Bool) {
        guard !dsn.isEmpty, !started else { return }
        SentrySDK.start { o in
            o.dsn = dsn
            o.releaseName = release(bundleId: bundleId, version: version, build: build)
            o.dist = build
            o.environment = debug ? "debug" : "production"
            // RN sendDefaultPii: false; RN sets no tracesSampleRate and replays only in development builds
            o.sendDefaultPii = false
            o.tracesSampleRate = nil
            o.sessionReplay.sessionSampleRate = 0
            o.sessionReplay.onErrorSampleRate = 0
            o.attachScreenshot = false
            o.attachViewHierarchy = false
            // RN does not turn failed HTTP answers into events
            o.enableCaptureFailedRequests = false
            o.beforeSend = { event in beforeSend(event) }
            o.beforeBreadcrumb = { crumb in scrub(crumb) }
        }
        started = true
    }

    static func release(bundleId: String, version: String, build: String) -> String { "\(bundleId)@\(version)+\(build)" }

    private static var scrubber: CrashScrubber { CrashScrubber.shared }

    /// RN `beforeSend`: benign errors dropped, then every text scrubbed. Nothing here can throw (the shared scrubber
    /// is total and returns its placeholder when it fails).
    static func beforeSend(_ event: Event) -> Event? {
        if (event.exceptions ?? []).contains(where: { scrubber.shouldDrop(type: $0.type, message: $0.value) }) { return nil }
        if let m = event.message {
            let scrubbed = SentryMessage(formatted: scrubber.scrub(text: m.formatted) ?? "")
            scrubbed.message = scrubber.scrub(text: m.message)
            event.message = scrubbed
        }
        event.exceptions?.forEach { $0.value = scrubber.scrub(text: $0.value) ?? "" }
        event.breadcrumbs = event.breadcrumbs?.map(scrub)
        if let extra = event.extra { event.extra = deep(extra, depth: 0) }
        // named contexts (the iap context carries the store's own messages)
        if let context = event.context { event.context = context.mapValues { deep($0, depth: 0) } }
        if let tags = event.tags { event.tags = Dictionary(uniqueKeysWithValues: tags.map { ($0.key, scrubber.scrubValue(key: $0.key, value: $0.value) ?? "") }) }
        if let request = event.request {
            request.url = scrubber.scrub(text: request.url)
            request.queryString = scrubber.scrub(text: request.queryString)
            request.headers = nil
            request.cookies = nil
        }
        event.user = nil
        return event
    }

    /// RN `redactBreadcrumb`.
    static func scrub(_ crumb: Breadcrumb) -> Breadcrumb {
        crumb.message = scrubber.scrub(text: crumb.message)
        if let data = crumb.data { crumb.data = deep(data, depth: 0) }
        return crumb
    }

    /// RN `redactDeep` (depth 6).
    static func deep(_ dict: [String: Any], depth: Int) -> [String: Any] {
        var out: [String: Any] = [:]
        for (k, v) in dict { if let value = deep(key: k, value: v, depth: depth) { out[k] = value } }
        return out
    }

    private static func deep(key: String, value: Any, depth: Int) -> Any? {
        if depth > Int(CrashScrubber.shared.MAX_DEPTH) { return nil }
        switch value {
        case let s as String: return scrubber.scrubValue(key: key, value: s) ?? ""
        case let d as [String: Any]: return deep(d, depth: depth + 1)
        case let a as [Any]: return a.compactMap { deep(key: key, value: $0, depth: depth + 1) }
        case is NSNumber, is Bool, is Int, is Double: return value
        default: return scrubber.scrubValue(key: key, value: String(describing: value)) ?? ""
        }
    }

    /// What the shared code reports, as Sentry events (RN captureException / captureMessage / addBreadcrumb).
    final class Reporter: NSObject, CrashReporter {
        func capture(report: CrashReport) {
            guard CrashReporting.started else { return }
            SentrySDK.capture(event: Reporter.event(report))
        }

        func breadcrumb(category: String, message: String, data: [String: String]) {
            guard CrashReporting.started else { return }
            let crumb = Breadcrumb(level: .info, category: category)
            crumb.message = message
            crumb.data = data
            SentrySDK.addBreadcrumb(crumb)
        }

        static func event(_ report: CrashReport) -> Event {
            let event = Event(level: report.level == "warning" ? .warning : .error)
            if report.isMessage {
                event.message = SentryMessage(formatted: report.message)
            } else {
                let e = Exception(value: report.message, type: report.type)
                e.module = "com.ecency.core"
                event.exceptions = [e]
            }
            if !report.fingerprint.isEmpty { event.fingerprint = report.fingerprint }
            event.tags = report.tags
            if !report.contextName.isEmpty { event.context = [report.contextName: report.context] }
            return event
        }
    }
}
