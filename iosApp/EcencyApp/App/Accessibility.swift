import EcencyCore
import SwiftUI
import UIKit

/// Accessibility helpers (README "Accessibility", iOS): VoiceOver announcements, the large content viewer for
/// controls that cannot grow, rows that stack at accessibility text sizes, touch targets and Smart Invert.

/// VoiceOver announcements (toasts, errors, banners). Says nothing when VoiceOver is off.
enum Announce {
    /// Where announcements go; tests replace it to count them.
    @MainActor static var sink: (String) -> Void = { AccessibilityNotification.Announcement($0).post() }

    @MainActor static func post(_ text: String) {
        guard !text.isEmpty else { return }
        sink(text)
    }
}

/// The smallest touch target, Apple's 44 by 44 points.
let minTapTarget: CGFloat = 44

extension DynamicTypeSize {
    /// From here on rows of side by side text switch to stacked (the accessibility sizes).
    static let stacksRows: DynamicTypeSize = .accessibility1
}

/// An HStack that becomes a VStack at accessibility text sizes (or a smaller threshold), so a row of labels and
/// values never squeezes its text into clipped single letters.
struct AdaptiveStack<Content: View>: View {
    var threshold: DynamicTypeSize = .stacksRows
    var horizontalAlignment: HorizontalAlignment = .leading
    var verticalAlignment: VerticalAlignment = .center
    var spacing: CGFloat? = nil
    @ViewBuilder let content: Content
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let layout = typeSize >= threshold
            ? AnyLayout(VStackLayout(alignment: horizontalAlignment, spacing: spacing))
            : AnyLayout(HStackLayout(alignment: verticalAlignment, spacing: spacing))
        layout { content }
    }
}

extension View {
    /// An icon-only control that keeps its size (tab bar, toolbars, headers): long press shows the large content
    /// viewer with its icon and label at accessibility text sizes.
    func largeContent(_ title: String, appIcon: AppIcon? = nil) -> some View {
        accessibilityShowsLargeContentViewer {
            if let appIcon { Image(appIcon: appIcon) }
            Text(title)
        }
    }

    /// At least 44 by 44 points of touch, whatever the visible size; the whole frame takes the tap.
    func tapTarget(_ size: CGFloat = minTapTarget) -> some View {
        frame(minWidth: size, minHeight: size).contentShape(Rectangle())
    }

    /// Photos and avatars keep their colours under Smart Invert.
    func keepsColorsInverted() -> some View { accessibilityIgnoresInvertColors(true) }

    /// The VoiceOver escape gesture (two finger Z) runs [action]: custom overlays that are not system sheets.
    func onEscape(_ action: @escaping () -> Void) -> some View {
        accessibilityAction(.escape, action)
    }

    /// Announces [text] to VoiceOver when it appears or changes (errors and statuses shown in place).
    ///
    /// Per view: each time the view appears with the text (an error shown again later is said again), and not again
    /// while it stays (a re-render, a tab coming back). A new failure with the same text announces again when
    /// [attempt] changes (pass the failure's identity, `ObjectAttempt(error)`).
    /// [sticky]: rows of lazy lists, which unmount and mount again while scrolling; once per call site
    /// (`#fileID:#line`) and [attempt] until the text goes away, so scrolling never repeats it.
    /// [debounce]: validation messages that follow typing are announced only once the text stays for a second.
    func announces(_ text: String?, attempt: AnyHashable = 0, debounce: Bool = false, sticky: Bool = false,
                   file: String = #fileID, line: Int = #line) -> some View {
        modifier(AnnouncesModifier(text: text, attempt: attempt, debounce: debounce, sticky: sticky, site: "\(file):\(line)"))
    }
}

/// A failure's identity as an announcement attempt: the object itself, held while the attempt is remembered, so
/// its identity cannot be reused by a later failure (a bare `ObjectIdentifier` can, once the object is freed).
struct ObjectAttempt: Hashable {
    let object: AnyObject
    init(_ object: AnyObject) { self.object = object }
    static func == (a: ObjectAttempt, b: ObjectAttempt) -> Bool { a.object === b.object }
    func hash(into hasher: inout Hasher) { hasher.combine(ObjectIdentifier(object)) }
}

/// What sticky call sites of `.announces` said (process wide, main actor): per site, every text and attempt said
/// recently, so several rows at one site (journal rows, error rows) each speak once. An entry lasts while it keeps
/// showing up (each time it is seen again its clock restarts) and lapses after `lifetime` unseen: a row removed while
/// showing the error never reports the text going away, so the same failure coming back later is said again.
@MainActor
enum AnnouncementLog {
    static var seen: [String: [AnyHashable: Date]] = [:]
    static var lifetime: TimeInterval = 120

    /// Whether [text] with [attempt] is new at [site]; records it. A nil text clears the site.
    static func shouldAnnounce(site: String, text: String?, attempt: AnyHashable, now: Date = Date()) -> Bool {
        guard let text, !text.isEmpty else {
            seen[site] = nil
            return false
        }
        var entries = (seen[site] ?? [:]).filter { now.timeIntervalSince($0.value) < lifetime }
        let key = AnyHashable([AnyHashable(text), attempt])
        let isNew = entries[key] == nil
        entries[key] = now
        seen[site] = entries
        return isNew
    }
}

struct AnnouncesModifier: ViewModifier {
    let text: String?
    let attempt: AnyHashable
    let debounce: Bool
    let sticky: Bool
    let site: String
    /// What this view said (per view: a view mounted again starts empty and speaks again).
    @State private var said: AnyHashable?

    private var key: AnyHashable { AnyHashable([AnyHashable(text), attempt]) }

    func body(content: Content) -> some View {
        content.task(id: key) {
            guard let text, !text.isEmpty else {
                said = nil
                if sticky { _ = AnnouncementLog.shouldAnnounce(site: site, text: nil, attempt: attempt) }
                return
            }
            if debounce {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                if Task.isCancelled { return }
            }
            if sticky {
                if AnnouncementLog.shouldAnnounce(site: site, text: text, attempt: attempt) { Announce.post(text) }
            } else if said != key {
                said = key
                Announce.post(text)
            }
        }
    }
}

/// Whether a screen reader or switch control is reading the accessibility tree (screens that keep hidden content
/// mounted unmount it then). Debug builds take `-assistiveTechnology YES` so UI tests see what those users get.
struct AssistiveTechnology {
    var voiceOver = false
    var switchControl = false
    var forced = false
    var isRunning: Bool { voiceOver || switchControl || forced }

    static let forcedByLaunchArgument: Bool = {
        #if DEBUG
        return UserDefaults.standard.bool(forKey: "assistiveTechnology")
        #else
        return false
        #endif
    }()
}

private struct AssistiveTechnologyKey: EnvironmentKey {
    static let defaultValue = AssistiveTechnology(forced: AssistiveTechnology.forcedByLaunchArgument)
}

extension EnvironmentValues {
    var assistiveTechnology: AssistiveTechnology {
        get { self[AssistiveTechnologyKey.self] }
        set { self[AssistiveTechnologyKey.self] = newValue }
    }
}

/// Puts the live VoiceOver and Switch Control state into `\.assistiveTechnology` (ThemedRoot applies it).
struct AssistiveTechnologyRoot: ViewModifier {
    @Environment(\.accessibilityVoiceOverEnabled) private var voiceOver
    @Environment(\.accessibilitySwitchControlEnabled) private var switchControl

    func body(content: Content) -> some View {
        content.environment(\.assistiveTechnology, AssistiveTechnology(voiceOver: voiceOver, switchControl: switchControl,
                                                                     forced: AssistiveTechnology.forcedByLaunchArgument))
    }
}
