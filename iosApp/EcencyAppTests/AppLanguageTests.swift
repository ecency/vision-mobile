import EcencyCore
import SwiftUI
import XCTest
@testable import EcencyApp

/// The SwiftUI language root (live switch, direction), the bundle's localizations for Settings > Ecency > Language,
/// and the app's own AppleLanguages default.
@MainActor
final class AppLanguageTests: XCTestCase {
    override func tearDown() {
        L10n.shared.setLanguage(id: "en-US")
        UIKitDirection.apply("en-US")
        super.tearDown()
    }

    private func makeEnv() throws -> AppEnvironment {
        AppEnvironment(
            api: try EcencyApi(nodes: ["https://fake-hive.invalid"], transport: FakeHiveTransport()),
            vault: KeyVaultAsync(vault: KeyVault(store: KeychainSecureStore(service: "app.esteem.mobile.ios.tests.language"))),
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

    /// What the sample screen last drew.
    final class Rendered {
        var title = ""
        var direction = LayoutDirection.leftToRight
        var locale = ""
    }

    /// A screen that reads its copy straight from the catalog, as the app's screens do.
    struct SampleScreen: View {
        let rendered: Rendered
        @Environment(\.layoutDirection) private var direction
        @Environment(\.locale) private var locale

        var body: some View {
            rendered.title = L.Settings.language
            rendered.direction = direction
            rendered.locale = locale.identifier
            return Text(L.Settings.language)
        }
    }

    private func host(_ language: AppLanguage, _ rendered: Rendered) -> UIWindow? {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return nil }
        let window = UIWindow(windowScene: scene)
        window.rootViewController = UIHostingController(rootView: LanguageRoot(language: language) { id in
            SampleScreen(rendered: rendered).id(id)
        })
        window.makeKeyAndVisible()
        return window
    }

    private func spin(_ condition: () -> Bool) -> Bool {
        let end = Date().addingTimeInterval(3)
        while !condition() && Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
        return condition()
    }

    func testSwitchingTheLanguageRedrawsTheScreen() throws {
        let rendered = Rendered()
        guard let window = host(AppLanguage(env: try makeEnv()), rendered) else { return XCTFail("no scene") }
        defer { window.isHidden = true }
        XCTAssertTrue(spin { rendered.title == "Language" })
        L10n.shared.setLanguage(id: "ru-RU")
        XCTAssertTrue(spin { rendered.title == "Язык" }, "live, without a restart: \(rendered.title)")
        XCTAssertEqual(rendered.locale, "ru")
        L10n.shared.setLanguage(id: "de-DE")
        XCTAssertTrue(spin { rendered.title == "Sprache" }, rendered.title)
    }

    func testArabicPersianAndHebrewMirrorTheRootKurdishDoesNot() throws {
        let rendered = Rendered()
        guard let window = host(AppLanguage(env: try makeEnv()), rendered) else { return XCTFail("no scene") }
        defer { window.isHidden = true }
        XCTAssertTrue(spin { rendered.title == "Language" && rendered.direction == .leftToRight })
        for id in ["ar-SA", "fa-IR", "he-IL"] {
            L10n.shared.setLanguage(id: id)
            XCTAssertTrue(spin { rendered.direction == .rightToLeft }, id)
        }
        L10n.shared.setLanguage(id: "ku-TR")
        XCTAssertTrue(spin { rendered.direction == .leftToRight }, "ku-TR is Latin script Kurmanji")
        let scene = window.windowScene
        L10n.shared.setLanguage(id: "fa-IR")
        XCTAssertTrue(spin { scene.flatMap(UIKitDirection.current) == .rightToLeft }, "sheets and covers follow through the scene")
    }

    /// Settings > Ecency > Language lists the app's languages: each has its lproj and its CFBundleLocalizations entry.
    func testTheBundleDeclaresEveryLanguage() {
        let tags = Set(L10n.shared.supported.map { L10n.shared.systemTag(languageId: $0) })
        XCTAssertEqual(tags.count, 39)
        let declared = Bundle.main.object(forInfoDictionaryKey: "CFBundleLocalizations") as? [String] ?? []
        XCTAssertEqual(Set(declared), tags)
        XCTAssertEqual(declared.count, 39)
        let lprojs = Set(Bundle.main.localizations)
        XCTAssertTrue(tags.isSubset(of: lprojs), "missing lproj: \(tags.subtracting(lprojs).sorted())")
        for tag in tags { XCTAssertEqual(LocaleMatch.shared.registered(locale: tag, ids: L10n.shared.supported).map { L10n.shared.systemTag(languageId: $0) }, tag) }
    }

    /// Only the app's own AppleLanguages counts: the device's languages are not a per-app choice.
    func testTheSystemLanguageReadsOnlyTheAppsOwnValue() {
        let suite = "app.esteem.mobile.ios.tests.language.system"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }
        let system = IosSystemLanguage(defaults: defaults, domain: suite)
        XCTAssertEqual(system.read(), "", "the device's languages are not the app's own")
        system.write(tag: "zh-Hant")
        XCTAssertEqual(system.read(), "zh-Hant")
    }

    /// The app's root holds its first frame until the settings' language is final (or the timeout).
    func testTheRootWaitsForTheSettledLanguage() throws {
        let started = try makeEnv()
        let suite = "app.esteem.mobile.ios.tests.language.settled"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }
        started.prefs = UserDefaultsPrefs(defaults: defaults)
        started.settings.start(deviceLocale: "en-US")
        XCTAssertTrue(AppLanguage(env: started, holdUntilSettled: true).settled, "no RN import to wait for")
        let waiting = AppLanguage(env: try makeEnv(), holdUntilSettled: true)
        XCTAssertFalse(waiting.settled)
        XCTAssertTrue(spin { waiting.settled }, "never longer than the timeout")
        XCTAssertTrue(AppLanguage(env: try makeEnv()).settled, "screens that do not hold show at once")
    }

    func testTheDeviceLanguageIsTheDevicesNotTheAppsOwn() {
        let device = IosSystemLanguage().deviceLanguage()
        XCTAssertNotNil(device)
        XCTAssertFalse(device?.isEmpty ?? true)
    }

    /// The settings model from Swift: the picker drives the catalog and writes the per-app language for the next launch.
    func testThePickerSwitchesTheCatalogAndWritesAppleLanguages() throws {
        let env = try makeEnv()
        let suite = "app.esteem.mobile.ios.tests.language.prefs"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }
        env.prefs = UserDefaultsPrefs(defaults: defaults)
        env.drivesLanguageCatalog = true
        env.systemLanguage = IosSystemLanguage(defaults: defaults, domain: suite)
        env.settings.start(deviceLocale: "fr-CA")
        XCTAssertEqual(L10n.shared.language.value as? String, "fr-FR")
        XCTAssertEqual(defaults.array(forKey: "AppleLanguages") as? [String], [LanguageSync.shared.tagToWrite(language: "fr-FR", device: env.systemLanguage?.deviceLanguage(), ids: L10n.shared.supported)])
        env.settings.setLanguage(code: "he-IL")
        XCTAssertEqual(L10n.shared.language.value as? String, "he-IL")
        XCTAssertTrue(L10n.shared.isRtl)
        XCTAssertEqual(LocaleMatch.shared.registered(locale: (defaults.array(forKey: "AppleLanguages") as? [String])?.first, ids: L10n.shared.supported), "he-IL")
    }
}
