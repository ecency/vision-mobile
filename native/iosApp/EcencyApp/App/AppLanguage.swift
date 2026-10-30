import Combine
import EcencyCore
import SwiftUI

/// The app language (shared `L10n.language`, which the settings drive) for SwiftUI: changes on the main thread.
@MainActor
final class AppLanguage: ObservableObject {
    @Published private(set) var id: String
    /// The language is final for this start (`SettingsState.languageSettled`): the root shows its screens from then.
    @Published private(set) var settled: Bool
    private var watcher: Closeable?
    private var settingsWatcher: Closeable?

    /// The longest the first frame waits for the RN import's language (it reads one local file).
    static let settleTimeout: TimeInterval = 1.5

    /// [holdUntilSettled]: the app's root waits for the settings (first native start: the RN import's language), so
    /// no screen is built in one language and rebuilt a moment later (which would reset an opened sheet).
    init(env: AppEnvironment, holdUntilSettled: Bool = false) {
        id = L10n.shared.language.value as? String ?? L10n.shared.SOURCE
        settled = !holdUntilSettled || env.settings.current.languageSettled
        watcher = env.watchLanguage { [weak self] language in
            MainActor.assumeIsolated {
                if self?.id != language { self?.id = language }
            }
        }
        guard !settled else { return }
        settingsWatcher = env.settings.watch { [weak self] state in
            MainActor.assumeIsolated {
                if state.languageSettled { self?.settle() }
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.settleTimeout) { [weak self] in self?.settle() }
    }

    private func settle() {
        guard !settled else { return }
        settled = true
        settingsWatcher?.close()
        settingsWatcher = nil
    }

    deinit {
        watcher?.close()
        settingsWatcher?.close()
    }
}

private struct AppLanguageKey: EnvironmentKey {
    static let defaultValue = "en-US"
}

extension EnvironmentValues {
    /// The app language id ("de-DE"), set by the language root.
    var appLanguage: String {
        get { self[AppLanguageKey.self] }
        set { self[AppLanguageKey.self] = newValue }
    }
}

extension View {
    /// The language root's environment: the app language, its locale and its direction (right to left for ar, fa and
    /// he; ku-TR is Latin script and stays left to right), whatever the device's language is.
    func appLanguage(_ id: String) -> some View {
        environment(\.appLanguage, id)
            .environment(\.locale, Locale(identifier: L10n.shared.systemTag(languageId: id)))
            .environment(\.layoutDirection, L10n.shared.isRtl(languageId: id) ? .rightToLeft : .leftToRight)
    }
}

/// A value (account, amount, URL) put next to catalog text outside a message (a "label: value" row): wrapped in bidi
/// isolates in a right to left language, as the catalog's formatter does for placeholders, so "@alice" keeps its "@"
/// in front and the colon stays with the label. Unchanged in left to right languages.
func bidiValue(_ value: String) -> String {
    L10n.shared.isRtl ? Bidi.shared.isolate(text: value) : value
}

extension View {
    /// Machine text (usernames, keys, amounts, URLs, emails) is typed and shown left to right in every language: in an
    /// RTL layout the `@` prefix stays before the name and an amount reads as written. The placeholder follows.
    func leftToRightField() -> some View {
        environment(\.layoutDirection, .leftToRight)
    }
}

/// Root of the localized UI. Copy reads the catalog directly (`L.Settings.language`), which SwiftUI does not track,
/// so [content] gets the language to rebuild its screens with on a change (`.id(language)` on the navigation root):
/// a live switch without restarting the app. Navigation survives (the path lives in AppContainer); screen state is
/// built again.
struct LanguageRoot<Content: View>: View {
    @ObservedObject var language: AppLanguage
    @ViewBuilder let content: (String) -> Content

    var body: some View {
        if language.settled {
            content(language.id).appLanguage(language.id)
                .onAppear { UIKitDirection.apply(language.id) }
                .onChange(of: language.id) { _, id in UIKitDirection.apply(id) }
        } else {
            // the first native start waits (at most AppLanguage.settleTimeout) for the RN import's language
            Color.clear
        }
    }
}

/// The app language's direction for what UIKit lays out and presents: sheets, full screen covers, popovers, alerts,
/// menus and the lock and auth windows are hosted apart from the root, and SwiftUI does not pass `\.layoutDirection`
/// into them; they read the window scene's traits. The scene's trait override reaches every window and presented
/// controller, live, without a restart (README "Localization", RTL).
enum UIKitDirection {
    static func direction(_ languageId: String) -> UITraitEnvironmentLayoutDirection {
        L10n.shared.isRtl(languageId: languageId) ? .rightToLeft : .leftToRight
    }

    @MainActor
    static func apply(_ languageId: String, scenes: Set<UIScene> = UIApplication.shared.connectedScenes) {
        let direction = direction(languageId)
        for case let scene as UIWindowScene in scenes where current(scene) != direction {
            scene.traitOverrides.layoutDirection = direction
        }
    }

    /// The scene's effective direction (its override once [apply] set one, else the device's). Read from the trait
    /// collection: reading an override that was never set throws.
    @MainActor
    static func current(_ scene: UIWindowScene) -> UITraitEnvironmentLayoutDirection? {
        scene.traitCollection.layoutDirection
    }
}

/// iOS's per-app language (Settings > Ecency > Language): the app's own `AppleLanguages` default, which iOS writes
/// there and reads at launch to pick the bundle's localization. Synced by the shared settings (README "Localization").
final class IosSystemLanguage: NSObject, SystemLanguage {
    static let key = "AppleLanguages"
    private let defaults: UserDefaults
    private let domain: String

    init(defaults: UserDefaults = .standard, domain: String = Bundle.main.bundleIdentifier ?? "app.esteem.mobile.ios") {
        self.defaults = defaults
        self.domain = domain
    }

    /// Only the app's own value (the one key, this domain): `defaults.array(forKey:)` would fall through to the
    /// device's languages.
    func read() -> String {
        (CFPreferencesCopyValue(Self.key as CFString, domain as CFString, kCFPreferencesCurrentUser, kCFPreferencesAnyHost) as? [String])?.first ?? ""
    }

    func write(tag: String) {
        defaults.set([tag], forKey: Self.key)
    }

    /// The device's first language with its region (the global AppleLanguages, which the app's own value does not
    /// hide); `Locale.preferredLanguages` when that cannot be read.
    func deviceLanguage() -> String? {
        let global = CFPreferencesCopyValue(Self.key as CFString, kCFPreferencesAnyApplication, kCFPreferencesCurrentUser, kCFPreferencesAnyHost) as? [String]
        return global?.first ?? Locale.preferredLanguages.first
    }
}
