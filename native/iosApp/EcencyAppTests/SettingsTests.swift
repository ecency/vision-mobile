import EcencyCore
import XCTest
@testable import EcencyApp

/// The settings model from Swift: RN's options, the feedback mail as a URL iOS opens, the review link, and the
/// theme moving from the old platform key.
@MainActor
final class SettingsTests: XCTestCase {
    private func makeEnv() throws -> AppEnvironment {
        AppEnvironment(
            api: try EcencyApi(nodes: ["https://fake-hive.invalid"], transport: FakeHiveTransport()),
            vault: KeyVaultAsync(vault: KeyVault(store: KeychainSecureStore(service: "app.esteem.mobile.ios.tests.settings"))),
            renderer: PostBodyRendererCompanion.shared.`default`(proxyBase: ImageProxy.companion.DEFAULT_BASE),
            dispatchers: AppDispatchers.companion.platform(),
            proxyBase: ImageProxy.companion.DEFAULT_BASE,
            debugKeyImport: true,
            nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
            security: SecurityConfig(pinIterations: 16, lockTimeoutMillis: 60_000, monotonicMillis: { KotlinLong(value: 0) }),
            legacy: nil,
            auth: AuthServices.companion.offline()
        )
    }

    func testFeedbackMailIsAUrlIOSOpens() {
        let mail = SettingsOptions.shared.feedbackMailto(username: "spike-alice", appVersion: "4.0.0 (12)", platform: "iPhone - iOS 26.0")
        let url = URL(string: mail)
        XCTAssertNotNil(url)
        XCTAssertEqual(url?.scheme, "mailto")
        let items = URLComponents(string: mail)?.queryItems ?? []
        XCTAssertEqual(items.first(where: { $0.name == "subject" })?.value, "Feedback/Bug report")
        XCTAssertEqual(items.first(where: { $0.name == "body" })?.value,
                       "Write your message here!\n\nHive username: spike-alice\nApp version: 4.0.0 (12)\nPlatform: iPhone - iOS 26.0\n")
    }

    /// The l10n core's end to end proof from Swift: the generated Swift catalog (the Kotlin tree is not exported),
    /// a live language switch, plural arguments as Swift Int.
    func testLanguageRowTitleComesFromTheCatalog() {
        defer { L10n.shared.setLanguage(id: "en-US") }
        XCTAssertEqual(L.Settings.language, "Language")
        L10n.shared.setLanguage(id: "ru-RU")
        XCTAssertEqual(L10n.shared.language.value as? String, "ru-RU")
        XCTAssertEqual(L.Settings.language, "Язык")
        L10n.shared.setLanguage(id: "pt_BR")
        XCTAssertEqual(L10n.shared.language.value as? String, "pt-PT", "aliases map like RN mapToRegisteredLocale")
        L10n.shared.setLanguage(id: "en-US")
        XCTAssertEqual(L.Post.a11yVotes(count: 3), "3 votes")
        XCTAssertEqual(L.Post.a11yVotes(count: 1), "1 vote")
    }

    func testReviewLinkOnlyForTheAppStore() {
        XCTAssertEqual(StoreListing.shared.iosReview(page: "https://apps.apple.com/app/ecency/id1"), "https://apps.apple.com/app/ecency/id1?action=write-review")
        XCTAssertNil(StoreListing.shared.iosReview(page: "https://evil.example/x"))
    }

    func testSettingsSaveIntoThePrefsAndTheThemeMovesOnce() throws {
        let env = try makeEnv()
        let defaults = UserDefaults(suiteName: "app.esteem.mobile.ios.tests.settings.prefs")!
        defaults.removePersistentDomain(forName: "app.esteem.mobile.ios.tests.settings.prefs")
        env.prefs = UserDefaultsPrefs(defaults: defaults)
        env.settings.start(deviceLocale: "pt_BR")
        XCTAssertEqual(env.settings.contentLanguage, "pt-PT", "RN autoLocale maps the device language")
        env.settings.adoptPlatformTheme(name: "DARK")
        env.settings.adoptPlatformTheme(name: "LIGHT")
        XCTAssertEqual(env.settings.current.theme, .dark)
        env.settings.setCurrency(code: "GBP")
        XCTAssertEqual(defaults.string(forKey: "prefs.settings.currency"), "gbp", "the key the wallet reads")
        env.settings.setNsfw(mode: .hide)
        XCTAssertEqual(defaults.string(forKey: "prefs.nsfw"), "2")
        XCTAssertEqual(SettingsOptions.shared.CURRENCIES.count, 24)
        XCTAssertEqual(SettingsOptions.shared.LANGUAGES.count, 39)
    }
}
