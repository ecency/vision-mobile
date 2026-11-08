import EcencyCore
import UIKit
import XCTest
@testable import EcencyApp

/// Issue #3656 on iOS (README "App icons"): no automatic switch before the person agrees, the Settings picker sets
/// and clears the alternate icon, and every icon ships in the bundle.
@MainActor
final class LauncherIconTests: XCTestCase {
    private final class Recorder: AlternateIconSetting {
        var currentName: String?
        var calls: [String?] = []
        func setName(_ name: String?) {
            calls.append(name)
            currentName = name
        }
    }

    private var suite = "app.esteem.mobile.ios.tests.launcher-icons"

    private func makeEnv() throws -> AppEnvironment {
        let env = AppEnvironment(
            api: try EcencyApi(nodes: ["https://fake-hive.invalid"], transport: FakeHiveTransport()),
            vault: KeyVaultAsync(vault: KeyVault(store: KeychainSecureStore(service: "app.esteem.mobile.ios.tests.launcher-icons"))),
            renderer: PostBodyRendererCompanion.shared.`default`(proxyBase: ImageProxy.companion.DEFAULT_BASE),
            dispatchers: AppDispatchers.companion.platform(),
            proxyBase: ImageProxy.companion.DEFAULT_BASE,
            debugKeyImport: true,
            nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
            security: SecurityConfig(pinIterations: 16, lockTimeoutMillis: 60_000, monotonicMillis: { KotlinLong(value: 0) }),
            legacy: nil,
            auth: AuthServices.companion.offline()
        )
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        env.prefs = UserDefaultsPrefs(defaults: defaults)
        return env
    }

    /// Seasonal icons are on by default on iOS as on Android: a due celebration switches without a question.
    func testSeasonalIconsAreOnByDefault() throws {
        let env = try makeEnv()
        let icons = Recorder()
        LauncherIconSwitcher.configure(env.launcherIcons, region: "DE", language: "de")
        let switcher = LauncherIconSwitcher(model: env.launcherIcons, setting: icons, isActive: { true })
        switcher.foreground(today: CalendarDay(year: 2027, month: 3, day: 20))
        XCTAssertTrue(env.launcherIcons.current.seasonal)
        XCTAssertFalse(env.launcherIcons.current.askConsent, "no question")
        XCTAssertEqual(icons.calls, ["AppIcon-HiveDay"])
    }

    func testNoAutomaticSwitchBeforeThePersonAgrees() throws {
        let env = try makeEnv()
        let icons = Recorder()
        env.launcherIcons.configure(region: "DE", language: "de", asksFirst: true)
        let switcher = LauncherIconSwitcher(model: env.launcherIcons, setting: icons, isActive: { true })
        switcher.foreground(today: CalendarDay(year: 2027, month: 3, day: 20))
        XCTAssertTrue(env.launcherIcons.current.askConsent, "Hive Day is due: the one question shows")
        XCTAssertEqual(icons.calls, [], "nothing switches before the answer")
        switcher.answerConsent(true)
        XCTAssertEqual(icons.calls, ["AppIcon-HiveDay"])
        switcher.foreground(today: CalendarDay(year: 2027, month: 3, day: 21))
        XCTAssertEqual(icons.calls, ["AppIcon-HiveDay", nil], "back to the everyday icon after the day")
    }

    func testADeclinedQuestionNeverSwitchesAndIsNotAskedAgain() throws {
        let env = try makeEnv()
        let icons = Recorder()
        env.launcherIcons.configure(region: "DE", language: "de", asksFirst: true)
        let switcher = LauncherIconSwitcher(model: env.launcherIcons, setting: icons, isActive: { true })
        switcher.foreground(today: CalendarDay(year: 2027, month: 3, day: 20))
        switcher.answerConsent(false)
        switcher.foreground(today: CalendarDay(year: 2027, month: 4, day: 22))
        XCTAssertFalse(env.launcherIcons.current.askConsent)
        XCTAssertEqual(icons.calls, [])
    }

    func testTheSettingsPickerSetsAndClearsTheAlternateIcon() throws {
        let env = try makeEnv()
        let icons = Recorder()
        env.launcherIcons.configure(region: "DE", language: "de", asksFirst: true)
        let switcher = LauncherIconSwitcher(model: env.launcherIcons, setting: icons, isActive: { true })
        switcher.foreground(today: CalendarDay(year: 2027, month: 6, day: 1))
        switcher.pick(.christmas)
        XCTAssertEqual(icons.currentName, "AppIcon-Christmas")
        switcher.pick(.default_)
        XCTAssertEqual(icons.calls, ["AppIcon-Christmas", nil])
    }

    func testNothingSwitchesWhileTheAppIsNotActive() throws {
        let env = try makeEnv()
        let icons = Recorder()
        var active = false
        env.launcherIcons.configure(region: "DE", language: "de", asksFirst: true)
        let switcher = LauncherIconSwitcher(model: env.launcherIcons, setting: icons, isActive: { active })
        switcher.pick(.holi)
        XCTAssertEqual(icons.calls, [], "iOS refuses a change in the background")
        active = true
        switcher.foreground(today: CalendarDay(year: 2027, month: 6, day: 1))
        XCTAssertEqual(icons.calls, ["AppIcon-Holi"])
    }

    /// Review of #3659: a phone on the Persian, Islamic or Buddhist calendar still gets the Gregorian day the
    /// celebration table uses, in its own time zone.
    func testTodayIsTheGregorianDayWhateverThePhonesCalendar() throws {
        // 2027-03-20 22:30 UTC is already Mar 21 in Tehran (UTC+3:30)
        let instant = Date(timeIntervalSince1970: 1805581800)
        let tehran = try XCTUnwrap(TimeZone(identifier: "Asia/Tehran"))
        for id in [Calendar.Identifier.persian, .islamicUmmAlQura, .buddhist, .japanese, .gregorian] {
            var phone = Calendar(identifier: id)
            phone.timeZone = tehran
            XCTAssertEqual(LauncherIconSwitcher.today(instant, phone: phone), CalendarDay(year: 2027, month: 3, day: 21), "\(id)")
        }
    }

    func testEveryIconShipsWithItsPreview() {
        let icons = (Bundle.main.infoDictionary?["CFBundleIcons"] as? [String: Any])?["CFBundleAlternateIcons"] as? [String: Any] ?? [:]
        for icon in LauncherIcon.entries {
            XCTAssertNotNil(UIImage(named: "IconPreview-\(icon.key)"), "preview of \(icon.key)")
            guard let name = LauncherIconSwitcher.iconName(icon) else {
                XCTAssertEqual(icon, .default_)
                continue
            }
            XCTAssertNotNil(icons[name], "\(name) is an alternate icon")
        }
    }
}
