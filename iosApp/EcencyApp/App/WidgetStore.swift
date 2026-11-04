import EcencyCore
import Foundation
import WidgetKit

/// The app's side of the widgets (README "Widgets"): the shared `WidgetFeed` hands over the snapshot (no key, token,
/// session code or name), written as a file in the App Group container for the widget extension, left out of
/// backups (as Android's widget preferences are), and every widget redraws.
final class IosWidgetStore: NSObject, WidgetStore {
    static let group = "group.com.ecency.eshare"

    /// The same file the widget extension reads (`WidgetSnapshotData.fileURL`).
    static func fileURL() -> URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?
            .appendingPathComponent("Widgets", isDirectory: true).appendingPathComponent("snapshot.json")
    }

    /// The app language for the widget extension (`WidgetL10n.languageFileURL`), beside the snapshot.
    static func languageFileURL() -> URL? {
        fileURL()?.deletingLastPathComponent().appendingPathComponent("language")
    }

    /// Hands the app language ("de-DE") over to the widgets, which run in their own process (README "Localization"),
    /// and redraws them when it changed. Main thread.
    static func handOver(language: String) {
        guard let file = languageFileURL() else { return }
        if (try? Data(contentsOf: file)).flatMap({ String(data: $0, encoding: .utf8) }) == language { return }
        writeInFolder(Data(language.utf8), to: file)
        WidgetCenter.shared.reloadAllTimelines()
    }

    /// Writes into the widgets' folder of the App Group, which is left out of backups.
    private static func writeInFolder(_ data: Data, to file: URL) {
        var folder = file.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? folder.setResourceValues(values)
        try? data.write(to: file, options: [.atomic])
    }

    func write(snapshot: String) {
        if let file = IosWidgetStore.fileURL() { IosWidgetStore.writeInFolder(Data(snapshot.utf8), to: file) }
        // an earlier build kept it in the group's defaults, which are backed up
        UserDefaults(suiteName: IosWidgetStore.group)?.removeObject(forKey: WidgetSnapshot.companion.KEY)
        WidgetCenter.shared.reloadAllTimelines()
    }
}
