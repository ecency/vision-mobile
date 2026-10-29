import EcencyCore
import Foundation

/// Shared non-secret preferences (`Prefs` in the core) over UserDefaults. Fake-data runs use their own suite,
/// cleared at launch unless the fake store is kept, so UI tests start from defaults.
final class UserDefaultsPrefs: NSObject, Prefs {
    private let defaults: UserDefaults

    init(defaults: UserDefaults) { self.defaults = defaults }

    static func make(fakeData: Bool, keep: Bool) -> UserDefaultsPrefs {
        guard fakeData, let suite = UserDefaults(suiteName: "app.esteem.mobile.ios.fake.prefs") else {
            return UserDefaultsPrefs(defaults: .standard)
        }
        if !keep { suite.removePersistentDomain(forName: "app.esteem.mobile.ios.fake.prefs") }
        return UserDefaultsPrefs(defaults: suite)
    }

    func getString(key: String) -> String? { defaults.string(forKey: "prefs." + key) }

    func putString(key: String, value: String?) {
        if let value { defaults.set(value, forKey: "prefs." + key) } else { defaults.removeObject(forKey: "prefs." + key) }
    }

    func keys() -> Set<String> {
        Set(defaults.dictionaryRepresentation().keys.filter { $0.hasPrefix("prefs.") }.map { String($0.dropFirst("prefs.".count)) })
    }
}
