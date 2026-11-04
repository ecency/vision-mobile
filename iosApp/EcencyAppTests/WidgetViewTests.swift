import EcencyCore
import SwiftUI
import WidgetKit
import XCTest
@testable import EcencyApp

/// README "Widgets": the extension's snapshot reader against the shared encoder, what each widget shows signed out,
/// locked and unlocked, and a rendered sheet of them (written to ECENCY_SHOT_DIR when set, for the screenshots).
@MainActor
final class WidgetViewTests: XCTestCase {
    func testTheExtensionReadsWhatTheSharedFeedWrites() {
        let shared = WidgetSnapshot(signedIn: true, locked: false, unread: KotlinInt(value: 12), streak: KotlinInt(value: 5)).encode()
        XCTAssertEqual(WidgetSnapshotData.decode(shared), WidgetSnapshotData(signedIn: true, locked: false, unread: 12, streak: 5))
        XCTAssertEqual(WidgetSnapshotData.key, WidgetSnapshot.companion.KEY)
        XCTAssertEqual(WidgetSnapshotData.version, Int(WidgetSnapshot.companion.VERSION))
        XCTAssertEqual(WidgetSnapshotData.group, IosWidgetStore.group)
        let locked = WidgetSnapshot(signedIn: true, locked: true, unread: nil, streak: nil).encode()
        XCTAssertEqual(WidgetSnapshotData.decode(locked), WidgetSnapshotData(signedIn: true, locked: true))
        XCTAssertEqual(WidgetSnapshotData.decode(WidgetSnapshot.companion.EMPTY.encode()), .empty)
    }

    func testAnythingOddReadsAsNothingToShow() {
        XCTAssertEqual(WidgetSnapshotData.decode(nil), .empty)
        XCTAssertEqual(WidgetSnapshotData.decode("{"), .empty)
        XCTAssertEqual(WidgetSnapshotData.decode(#"{"v":2,"signedIn":true,"locked":false,"unread":3}"#), .empty)
        XCTAssertNil(WidgetSnapshotData.decode(#"{"v":1,"signedIn":true,"locked":false,"unread":-3}"#).unread)
        XCTAssertNil(WidgetSnapshotData.decode(#"{"v":1,"signedIn":true,"locked":true,"unread":3}"#).unread, "locked hides it")
    }

    func testTheCopyMatchesTheSharedLabels() {
        let en = WidgetL10n(language: "en-US")
        XCTAssertEqual(en.unread(count: 1), WidgetSnapshot.companion.unreadLabel(n: 1))
        XCTAssertEqual(en.unread(count: 7), WidgetSnapshot.companion.unreadLabel(n: 7))
        XCTAssertEqual(en.streak(n: 4), WidgetSnapshot.companion.streakLabel(n: 4))
        XCTAssertEqual(en.locked, WidgetSnapshot.companion.LOCKED_TEXT)
        XCTAssertEqual(en.signedOut, WidgetSnapshot.companion.SIGNED_OUT_TEXT)
    }

    func testTheLinksAreTheSharedShortcuts() {
        XCTAssertEqual(WidgetLinks.notifications.absoluteString, AppShortcuts.shared.NOTIFICATIONS)
        XCTAssertEqual(WidgetLinks.perks.absoluteString, AppShortcuts.shared.PERKS)
        XCTAssertEqual(WidgetLinks.write.absoluteString, AppShortcuts.shared.WRITE)
        XCTAssertEqual(WidgetLinks.newWave.absoluteString, AppShortcuts.shared.NEW_WAVE)
        for link in [WidgetLinks.notifications, WidgetLinks.perks, WidgetLinks.write, WidgetLinks.newWave] {
            XCTAssertNotNil(AppShortcuts.shared.parse(url: link.absoluteString), link.absoluteString)
        }
    }

    func testTheStoreWritesAFileLeftOutOfBackupsInTheAppGroup() throws {
        let file = try XCTUnwrap(WidgetSnapshotData.fileURL(), "the app is in the App Group")
        XCTAssertEqual(file, IosWidgetStore.fileURL(), "the app writes where the widget reads")
        let before = try? Data(contentsOf: file)
        defer { if let before { try? before.write(to: file) } else { try? FileManager.default.removeItem(at: file) } }
        UserDefaults(suiteName: IosWidgetStore.group)?.set("old", forKey: WidgetSnapshotData.key)
        IosWidgetStore().write(snapshot: WidgetSnapshot(signedIn: true, locked: false, unread: KotlinInt(value: 2), streak: nil).encode())
        XCTAssertEqual(WidgetSnapshotData.read().unread, 2)
        let folder = file.deletingLastPathComponent()
        XCTAssertEqual(try folder.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
        XCTAssertNil(UserDefaults(suiteName: IosWidgetStore.group)?.string(forKey: WidgetSnapshotData.key), "nothing in the backed up defaults")
    }

    /// The lock screen families over a dark wallpaper and the home screen's small one, signed in and unlocked, locked,
    /// and signed out.
    func testRenderedSheet() throws {
        let open = WidgetSnapshotData(signedIn: true, locked: false, unread: 12, streak: 5)
        let locked = WidgetSnapshotData(signedIn: true, locked: true)
        let sheet = VStack(alignment: .leading, spacing: 18) {
            Text("Lock Screen").font(.headline).foregroundStyle(.white)
            HStack(spacing: 14) {
                circular { NotificationsWidgetView(snapshot: open, family: .accessoryCircular) }
                circular { StreakWidgetView(snapshot: open, family: .accessoryCircular) }
                circular { ComposeWidgetView(family: .accessoryCircular) }
                circular { NotificationsWidgetView(snapshot: locked, family: .accessoryCircular) }
            }
            rectangular { NotificationsWidgetView(snapshot: open, family: .accessoryRectangular) }
            rectangular { StreakWidgetView(snapshot: open, family: .accessoryRectangular) }
            rectangular { NotificationsWidgetView(snapshot: locked, family: .accessoryRectangular) }
            rectangular { ComposeWidgetView(family: .accessoryRectangular) }
            NotificationsWidgetView(snapshot: open, family: .accessoryInline).foregroundStyle(.white)
            Text("Home Screen").font(.headline).foregroundStyle(.white)
            HStack(spacing: 14) {
                small { NotificationsWidgetView(snapshot: open, family: .systemSmall) }
                small { StreakWidgetView(snapshot: .empty, family: .systemSmall) }
            }
            HStack(spacing: 14) {
                small { ComposeWidgetView(family: .systemSmall) }
                small { NotificationsWidgetView(snapshot: locked, family: .systemSmall) }
            }
        }
        .padding(24)
        .frame(width: 400, alignment: .leading)
        .background(LinearGradient(colors: [Color(red: 0.1, green: 0.15, blue: 0.3), Color(red: 0.3, green: 0.2, blue: 0.4)], startPoint: .top, endPoint: .bottom))
        let renderer = ImageRenderer(content: sheet)
        renderer.scale = 2
        let image = try XCTUnwrap(renderer.uiImage)
        XCTAssertGreaterThan(image.size.height, 400)
        if let dir = ProcessInfo.processInfo.environment["ECENCY_SHOT_DIR"], let png = image.pngData() {
            try png.write(to: URL(fileURLWithPath: dir).appendingPathComponent("bgmedia-ios-widgets.png"))
        }
    }

    private func circular<V: View>(@ViewBuilder _ v: () -> V) -> some View {
        v().frame(width: 72, height: 72).foregroundStyle(.white).environment(\.colorScheme, .dark)
    }

    private func rectangular<V: View>(@ViewBuilder _ v: () -> V) -> some View {
        v().frame(width: 170, height: 72).foregroundStyle(.white).environment(\.colorScheme, .dark)
    }

    private func small<V: View>(@ViewBuilder _ v: () -> V) -> some View {
        v().padding(14).frame(width: 158, height: 158).background(RoundedRectangle(cornerRadius: 22).fill(Color(white: 0.97))).foregroundStyle(.black)
            .environment(\.colorScheme, .light)
    }
}
