import Foundation

/// RN build secrets, only needed for the one-time import of 3.x accounts. Written at build time by
/// scripts/build-secrets.sh from native/local.properties; see native/README.md, "Secure storage".
enum BuildSecrets {
    static var pinKey: String { String(decoding: GeneratedSecrets.pinKey, as: UTF8.self) }
    static var defaultPin: String { String(decoding: GeneratedSecrets.defaultPin, as: UTF8.self) }
    /// How this build names itself to Ecency's APIs; empty when not configured (the shared default applies).
    static var userAgent: String { String(decoding: GeneratedSecrets.userAgent, as: UTF8.self) }
    /// RN ACTIVITY_WEBSOCKET_URL: the notification websocket used where push is unavailable; empty when not configured.
    static var activityWebsocketUrl: String { String(decoding: GeneratedSecrets.activityWebsocketUrl, as: UTF8.self) }
    /// RN ECENCY_BACKEND_API for chats: the chat API and its websocket; empty: the Ecency host.
    static var chatBackendUrl: String { String(decoding: GeneratedSecrets.chatBackendUrl, as: UTF8.self) }
    /// Where the update check looks (README "App shell: update prompt"); empty: RN's repository and plain X.Y.Z tags.
    static var updateReleasesUrl: String { String(decoding: GeneratedSecrets.updateReleasesUrl, as: UTF8.self) }
    static var updateTagPrefix: String { String(decoding: GeneratedSecrets.updateTagPrefix, as: UTF8.self) }
    /// Sentry's DSN (README "Crash reporting"); empty: no crash reporting.
    static var sentryDsn: String { String(decoding: GeneratedSecrets.sentryDsn, as: UTF8.self) }
    /// Plausible's host setting (README "Analytics"); empty: RN's instance in Release, off in Debug.
    static var plausibleHost: String { String(decoding: GeneratedSecrets.plausibleHost, as: UTF8.self) }
}
