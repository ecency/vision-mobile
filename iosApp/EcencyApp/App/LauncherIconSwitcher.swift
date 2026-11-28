import EcencyCore
import UIKit

/// Where the app icon is set: the system (`UIApplication`), a recorder in tests.
@MainActor
protocol AlternateIconSetting: AnyObject {
    /// A synchronous LaunchServices read that can block the main thread (Sentry 3S5): the switcher reads it once per run.
    var currentName: String? { get }
    /// [refused] runs on main when the system kept the old icon.
    func setName(_ name: String?, refused: @escaping @MainActor () -> Void)
}

/// The system's alternate icon. iOS shows its own alert on every change; nothing hides it (README "App icons").
@MainActor
final class SystemAlternateIcon: AlternateIconSetting {
    var currentName: String? { UIApplication.shared.alternateIconName }

    func setName(_ name: String?, refused: @escaping @MainActor () -> Void) {
        // a device without alternate icons never changes: nothing to read again
        guard UIApplication.shared.supportsAlternateIcons else { return }
        // a refusal changes nothing the person sees (the old icon stays); it reaches Sentry as a warning
        UIApplication.shared.setAlternateIconName(name) { error in
            guard let error else { return }
            Reports.quiet(error, where: "launcherIcon.set")
            DispatchQueue.main.async { MainActor.assumeIsolated { refused() } }
        }
    }
}

/// Issue #3656 (README "App icons"): applies the shared rule's icon while the app is active. Settings go through here,
/// so a change shows at once. Seasonal icons are on by default, as on Android; Settings turns them off.
@MainActor
final class LauncherIconSwitcher {
    private let model: LauncherIconModel
    private let setting: AlternateIconSetting
    private let isActive: () -> Bool
    /// The icon the system shows, nil until first read (`.some(nil)`: the everyday icon). Only the app sets its icon,
    /// so after one read per run this is what it set since (or is setting: an apply while a change is in flight skips
    /// it); a refusal forgets it and the next apply reads again.
    private var known: String??
    /// Whether a refusal in this foreground already applied again: a refusal that repeats waits for the next one.
    private var retried = false

    /// `AppIcon-<key>` (an icon set in Assets.xcassets), nil for the everyday icon.
    static func iconName(_ icon: LauncherIcon) -> String? { icon == .default_ ? nil : "AppIcon-\(icon.key)" }

    /// The phone's own region and the first preferred language, never its location.
    static func configure(_ model: LauncherIconModel, region: String?, language: String?) {
        model.configure(region: region, language: language, asksFirst: false)
    }

    /// Today in the phone's time zone, as a Gregorian date: the celebration table is Gregorian, and the phone's own
    /// calendar ([phone], `Calendar.current`) follows its calendar setting (Persian, Islamic, Buddhist, Japanese...).
    static func today(_ date: Date = Date(), phone: Calendar = .current) -> CalendarDay {
        var gregorian = Calendar(identifier: .gregorian)
        gregorian.timeZone = phone.timeZone
        let c = gregorian.dateComponents([.year, .month, .day], from: date)
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
        retried = false
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
        let shown: String?
        if let known { shown = known } else { shown = setting.currentName }
        known = .some(want)
        guard shown != want else { return }
        request += 1
        let sent = request
        setting.setName(want) { [weak self] in self?.refused(sent) }
    }

    /// The last change asked of the system: a refusal is about that request only, not any earlier one for the same icon.
    private var request = 0

    /// The system kept the old icon for request [sent]. An apply that came while it was in flight skipped (Settings
    /// shows the requested icon), so this applies again, once per foreground. A refusal of an earlier request is moot.
    private func refused(_ sent: Int) {
        guard sent == request else { return }
        known = nil
        guard !retried else { return }
        retried = true
        apply()
    }
}
