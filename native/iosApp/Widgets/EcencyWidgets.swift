import SwiftUI
import WidgetKit

/// The widget extension (README "Widgets"): lock screen (accessory circular, rectangular, inline) and home screen
/// (small) widgets. It reads only the snapshot the app writes into the App Group and opens `ecency://` shortcuts;
/// no Keychain group, no network, nothing of the app's settings but its language (`WidgetL10n`, handed over by the app).
@main
struct EcencyWidgetBundle: WidgetBundle {
    var body: some Widget {
        NotificationsWidget()
        StreakWidget()
        ComposeWidget()
    }
}

/// One timeline entry from the stored snapshot; the app asks WidgetKit to reload whenever it writes a new one.
struct SnapshotProvider: TimelineProvider {
    func placeholder(in context: Context) -> WidgetEntry {
        WidgetEntry(date: Date(), snapshot: WidgetSnapshotData(signedIn: true, locked: false, unread: 3, streak: 5))
    }

    func getSnapshot(in context: Context, completion: @escaping (WidgetEntry) -> Void) {
        completion(context.isPreview ? placeholder(in: context) : WidgetEntry(date: Date(), snapshot: .read()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<WidgetEntry>) -> Void) {
        completion(Timeline(entries: [WidgetEntry(date: Date(), snapshot: .read())], policy: .never))
    }
}

private let accessoryFamilies: [WidgetFamily] = [.accessoryCircular, .accessoryRectangular, .accessoryInline, .systemSmall]

struct NotificationsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "EcencyNotifications", provider: SnapshotProvider()) { entry in
            FamilyReader { family in NotificationsWidgetView(snapshot: entry.snapshot, family: family, l10n: entry.l10n) }
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName(WidgetL10n.current.notifications)
        .description(WidgetL10n.current.notificationsDescription)
        .supportedFamilies(accessoryFamilies)
    }
}

struct StreakWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "EcencyStreak", provider: SnapshotProvider()) { entry in
            FamilyReader { family in StreakWidgetView(snapshot: entry.snapshot, family: family, l10n: entry.l10n) }
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName(WidgetL10n.current.streakName)
        .description(WidgetL10n.current.streakDescription)
        .supportedFamilies(accessoryFamilies)
    }
}

struct ComposeWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "EcencyCompose", provider: SnapshotProvider()) { entry in
            FamilyReader { family in ComposeWidgetView(family: family, l10n: entry.l10n) }
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName(WidgetL10n.current.write)
        .description(WidgetL10n.current.composeDescription)
        .supportedFamilies(accessoryFamilies)
    }
}

/// The family the widget is drawn in.
private struct FamilyReader<Content: View>: View {
    @Environment(\.widgetFamily) private var family
    let content: (WidgetFamily) -> Content

    var body: some View { content(family) }
}
