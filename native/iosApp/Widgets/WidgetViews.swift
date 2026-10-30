import SwiftUI
import WidgetKit

/// The widgets' snapshot (shared `WidgetSnapshot`, README "Widgets"), read from a file in the App Group container. The app
/// writes it; the widget only reads it and never reaches the network. It holds no key, token, session code or name.
/// Untrusted like any stored value: anything unreadable or of another version reads as nothing to show.
struct WidgetSnapshotData: Equatable {
    static let group = "group.com.ecency.eshare"
    static let key = "widget.snapshot"
    static let version = 1
    static let maxCount = 100_000

    var signedIn = false
    var locked = true
    var unread: Int?
    var streak: Int?

    static let empty = WidgetSnapshotData()

    static func decode(_ text: String?) -> WidgetSnapshotData {
        guard let data = text?.data(using: .utf8),
              let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              (o["v"] as? Int) == version else { return .empty }
        let signedIn = o["signedIn"] as? Bool ?? false
        let locked = o["locked"] as? Bool ?? true
        guard signedIn, !locked else { return WidgetSnapshotData(signedIn: signedIn, locked: true) }
        func count(_ k: String) -> Int? { (o[k] as? Int).flatMap { (0...maxCount).contains($0) ? $0 : nil } }
        return WidgetSnapshotData(signedIn: true, locked: false, unread: count("unread"), streak: count("streak"))
    }

    /// The snapshot file in the App Group container (a folder left out of backups); nil without the group.
    static func fileURL() -> URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?
            .appendingPathComponent("Widgets", isDirectory: true).appendingPathComponent("snapshot.json")
    }

    static func read() -> WidgetSnapshotData {
        guard let file = fileURL(), let data = try? Data(contentsOf: file), data.count < 4096 else { return .empty }
        return decode(String(data: data, encoding: .utf8))
    }
}

/// The shortcut links (shared `AppShortcuts`): the app routes them like any link (behind the lock, login gated).
enum WidgetLinks {
    static let notifications = URL(string: "ecency://notifications")!
    static let perks = URL(string: "ecency://perks")!
    static let write = URL(string: "ecency://compose/post")!
    static let newWave = URL(string: "ecency://compose/wave")!
}

struct WidgetEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshotData
    /// The app language when the entry was made (the app reloads the timelines when it changes).
    var l10n = WidgetL10n.current
}

extension View {
    /// The widget's locale and direction follow the app language, as the app's own root does.
    func widgetLanguage(_ l10n: WidgetL10n) -> some View {
        environment(\.locale, Locale(identifier: l10n.systemTag))
            .environment(\.layoutDirection, l10n.isRtl ? .rightToLeft : .leftToRight)
    }
}

/// Unread notifications: lock screen (circular, rectangular, inline) and home screen (small).
struct NotificationsWidgetView: View {
    let snapshot: WidgetSnapshotData
    let family: WidgetFamily
    var l10n = WidgetL10n.current

    private var hint: String? {
        if !snapshot.signedIn { return l10n.signedOut }
        if snapshot.locked { return l10n.locked }
        return nil
    }

    private var line: String { hint ?? snapshot.unread.map { l10n.unread(count: $0) } ?? l10n.notifications }

    var body: some View {
        Group {
            switch family {
            case .accessoryCircular:
                ZStack {
                    AccessoryWidgetBackground()
                    VStack(spacing: 0) {
                        Image(widgetSymbol: .notifications).font(.system(size: 13)).accessibilityHidden(true)
                        if let n = snapshot.unread, hint == nil {
                            Text(l10n.number(min(n, 999))).font(.system(size: 17, weight: .bold)).minimumScaleFactor(0.6).privacySensitive() // fixed size: a circular lock screen widget has a fixed frame; it shrinks instead
                        }
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(line)
            case .accessoryInline:
                Label(line, widgetSymbol: .notifications)
            case .accessoryRectangular:
                VStack(alignment: .leading, spacing: 2) {
                    // the brand, the same in every language
                    Label("Ecency", widgetSymbol: .notifications).font(.headline).widgetAccentable()
                    Text(line).font(.body).privacySensitive()
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
            default:
                SmallValueView(
                    icon: .notifications, title: l10n.notifications, value: hint == nil ? snapshot.unread.map(l10n.number) : nil,
                    caption: hint ?? snapshot.unread.map { l10n.unreadCaption(count: $0) } ?? l10n.openApp
                )
            }
        }
        .widgetLanguage(l10n)
        .widgetURL(WidgetLinks.notifications)
    }
}

/// The daily quest streak, as Perks last read it.
struct StreakWidgetView: View {
    let snapshot: WidgetSnapshotData
    let family: WidgetFamily
    var l10n = WidgetL10n.current

    private var hint: String? {
        if !snapshot.signedIn { return l10n.signedOut }
        if snapshot.locked { return l10n.locked }
        return nil
    }

    private var streakText: String? { snapshot.streak.map { l10n.streak(n: $0) } }

    var body: some View {
        Group {
            switch family {
            case .accessoryCircular:
                ZStack {
                    AccessoryWidgetBackground()
                    VStack(spacing: 0) {
                        Image(widgetSymbol: .streak).font(.system(size: 13)).accessibilityHidden(true)
                        if let n = snapshot.streak, hint == nil {
                            Text(l10n.number(min(n, 999))).font(.system(size: 17, weight: .bold)).minimumScaleFactor(0.6).privacySensitive() // fixed size: a circular lock screen widget has a fixed frame; it shrinks instead
                        }
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(hint ?? streakText ?? l10n.dailyQuests)
            case .accessoryInline:
                Label(hint ?? streakText ?? l10n.dailyQuests, widgetSymbol: .streak)
            case .accessoryRectangular:
                VStack(alignment: .leading, spacing: 2) {
                    Label(l10n.dailyQuests, widgetSymbol: .streak).font(.headline).widgetAccentable()
                    Text(hint ?? streakText ?? l10n.streakHint).font(.body).privacySensitive()
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
            default:
                SmallValueView(
                    icon: .streak, title: l10n.streakTitle, value: hint == nil ? snapshot.streak.map(l10n.number) : nil,
                    caption: hint ?? snapshot.streak.map { l10n.streakCaption(count: $0) } ?? l10n.openPerks
                )
            }
        }
        .widgetLanguage(l10n)
        .widgetURL(WidgetLinks.perks)
    }
}

/// Quick actions: a new wave (circular and inline) or both Write and New wave (rectangular, small). No data.
struct ComposeWidgetView: View {
    let family: WidgetFamily
    var l10n = WidgetL10n.current

    var body: some View {
        content.widgetLanguage(l10n)
    }

    @ViewBuilder private var content: some View {
        switch family {
        case .accessoryCircular:
            ZStack {
                AccessoryWidgetBackground()
                Image(widgetSymbol: .compose).font(.system(size: 20, weight: .semibold))
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(l10n.newWave)
            .accessibilityAddTraits(.isButton)
            .widgetURL(WidgetLinks.newWave)
        case .accessoryInline:
            Label(l10n.newWave, widgetSymbol: .compose).widgetURL(WidgetLinks.newWave)
        case .accessoryRectangular:
            HStack(spacing: 8) {
                Link(destination: WidgetLinks.newWave) { Label(l10n.wave, widgetSymbol: .waves) }
                Link(destination: WidgetLinks.write) { Label(l10n.write, widgetSymbol: .compose) }
            }
            .font(.headline)
            .frame(maxWidth: .infinity, alignment: .leading)
        default:
            VStack(spacing: 10) {
                Link(destination: WidgetLinks.newWave) { ComposeButton(icon: .waves, label: l10n.newWave) }
                Link(destination: WidgetLinks.write) { ComposeButton(icon: .compose, label: l10n.write) }
            }
        }
    }
}

private struct ComposeButton: View {
    let icon: WidgetSymbol
    let label: String

    var body: some View {
        // a text style that follows Dynamic Type, shrinking rather than clipping in the widget's fixed size
        Label(label, widgetSymbol: icon)
            .font(.subheadline.weight(.semibold))
            .lineLimit(1).minimumScaleFactor(0.6)
            .foregroundStyle(.white)
            .padding(.horizontal, 6)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(RoundedRectangle(cornerRadius: 12).fill(WidgetColors.accentFill))
    }
}

/// The app's accent at WCAG AA (README "Accessibility", `ContrastPalette`): text in it on the widget background, and
/// the button fill under white text.
enum WidgetColors {
    static let accent = Color(UIColor { $0.userInterfaceStyle == .dark ? hex(ContrastPalette.darkAccentText) : hex(ContrastPalette.lightAccent) })
    static let accentFill = Color(hex(ContrastPalette.lightAccent))

    private static func hex(_ v: UInt32) -> UIColor {
        UIColor(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: 1)
    }
}

/// The home screen's small value widget.
struct SmallValueView: View {
    let icon: WidgetSymbol
    let title: String
    let value: String?
    let caption: String

    var body: some View {
        // text styles (they follow Dynamic Type); the widget keeps its size, so the text shrinks rather than clips
        VStack(alignment: .leading, spacing: 4) {
            Label(title, widgetSymbol: icon).font(.footnote.weight(.medium)).foregroundStyle(WidgetColors.accent)
                .lineLimit(1).minimumScaleFactor(0.6)
            Spacer(minLength: 0)
            if let value { Text(value).font(.largeTitle.bold()).lineLimit(1).minimumScaleFactor(0.4).privacySensitive() }
            Text(caption).font(.footnote).foregroundStyle(.secondary).lineLimit(2).minimumScaleFactor(0.6)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}
