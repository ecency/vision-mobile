import EcencyCore
import UIKit

/// Where the app icon is set: the system (`UIApplication`), a recorder in tests.
@MainActor
protocol AlternateIconSetting: AnyObject {
    var currentName: String? { get }
    func setName(_ name: String?)
}

/// The system's alternate icon. iOS shows its own alert on every change; nothing hides it (README "App icons").
@MainActor
final class SystemAlternateIcon: AlternateIconSetting {
    var currentName: String? { UIApplication.shared.alternateIconName }

    func setName(_ name: String?) {
        guard UIApplication.shared.supportsAlternateIcons else { return }
        UIApplication.shared.setAlternateIconName(name) { _ in }
    }
}

/// Issue #3656 (README "App icons"): applies the shared rule's icon while the app is active. Settings and the one
/// question go through here, so a change shows at once; automatic switches only after the person said yes.
@MainActor
final class LauncherIconSwitcher {
    private let model: LauncherIconModel
    private let setting: AlternateIconSetting
    private let isActive: () -> Bool

    /// `AppIcon-<key>` (an icon set in Assets.xcassets), nil for the everyday icon.
    static func iconName(_ icon: LauncherIcon) -> String? { icon == .default_ ? nil : "AppIcon-\(icon.key)" }

    /// The phone's own region and the first preferred language, never its location.
    static func configure(_ model: LauncherIconModel, region: String?, language: String?) {
        model.configure(region: region, language: language, asksFirst: true)
    }

    static func today(_ date: Date = Date()) -> CalendarDay {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return CalendarDay(year: Int32(c.year ?? 1970), month: Int32(c.month ?? 1), day: Int32(c.day ?? 1))
    }

    init(model: LauncherIconModel, setting: AlternateIconSetting, isActive: @escaping () -> Bool) {
        self.model = model
        self.setting = setting
        self.isActive = isActive
    }

    /// The app became active: today's icon (asks first when a celebration is due and nobody answered yet).
    func foreground(today: CalendarDay) {
        model.refresh(today: today)
        apply()
    }

    func answerConsent(_ agree: Bool) {
        model.answerConsent(agree: agree)
        apply()
    }

    func pick(_ icon: LauncherIcon) {
        model.pick(icon: icon)
        apply()
    }

    func setSeasonal(_ on: Bool) {
        model.setSeasonal(on: on)
        apply()
    }

    private func apply() {
        // iOS refuses a change while the app is not in front
        guard isActive() else { return }
        let want = Self.iconName(model.current.current)
        if setting.currentName != want { setting.setName(want) }
    }
}
