import EcencyCore
import SwiftUI
import WidgetKit
import XCTest
@testable import EcencyApp

/// README "Localization": the strings iOS reads outside the app's catalog. The widget extension's generated table
/// against the shared catalog in all 39 languages (messages, plural forms, digits, direction), the App Group hand-off
/// of the app language that the extension's code path reads, and the permission prompts in the app bundle.
@MainActor
final class WidgetL10nTests: XCTestCase {
    override func tearDown() {
        L10n.shared.setLanguage(id: "en-US")
        super.tearDown()
    }

    private let counts = [0, 1, 2, 3, 4, 5, 7, 11, 12, 14, 21, 22, 25, 100, 101, 102, 111, 1000, 1001, 1234, 12345, 100_000]

    func testTheTableHasTheCatalogsLanguagesAndTags() {
        let ids = L10n.shared.supported
        XCTAssertEqual(ids.count, 39)
        XCTAssertEqual(WidgetL10nData.languages, ids)
        XCTAssertEqual(WidgetL10nData.systemTags, ids.map { L10n.shared.systemTag(languageId: $0) })
        XCTAssertEqual(Set(WidgetL10nData.numbers.keys), Set(ids))
    }

    func testEveryLanguageFormatsAsTheSharedCatalog() {
        for id in L10n.shared.supported {
            L10n.shared.setLanguage(id: id)
            let w = WidgetL10n(language: id)
            XCTAssertEqual(w.language, id)
            XCTAssertEqual(w.isRtl, L10n.shared.isRtl(languageId: id), id)
            XCTAssertEqual(w.notifications, L.Notification.notification, id)
            XCTAssertEqual(w.notificationsDescription, L.Native.Widget.notificationsDescription, id)
            XCTAssertEqual(w.streakName, L.Native.Widget.streakName, id)
            XCTAssertEqual(w.streakDescription, L.Native.Widget.streakDescription, id)
            XCTAssertEqual(w.write, L.Native.Widget.write, id)
            XCTAssertEqual(w.composeDescription, L.Native.Widget.composeDescription, id)
            XCTAssertEqual(w.newWave, L.Native.Widget.newWave, id)
            XCTAssertEqual(w.wave, L.ShareIntent.wave, id)
            XCTAssertEqual(w.streakTitle, L.Native.Widget.streakTitle, id)
            XCTAssertEqual(w.dailyQuests, L.Native.Widget.dailyQuests, id)
            XCTAssertEqual(w.streakHint, L.Native.Widget.streakHint, id)
            XCTAssertEqual(w.openPerks, L.Native.Widget.openPerks, id)
            XCTAssertEqual(w.openApp, L.Native.Widget.openApp, id)
            XCTAssertEqual(w.locked, L.Native.Widgets.lockedText, id)
            XCTAssertEqual(w.signedOut, L.Native.Widgets.signedOutText, id)
            for n in counts {
                XCTAssertEqual(w.number(n), LocaleFormat.shared.integer(value: Int64(n)), "\(id) \(n)")
                XCTAssertEqual(w.unread(count: n), L.Native.Widgets.unread(count: n), "\(id) \(n)")
                XCTAssertEqual(w.unreadCaption(count: n), L.Native.Widget.unreadCaption(count: n), "\(id) \(n)")
                XCTAssertEqual(w.streakCaption(count: n), L.Native.Widget.streakCaption(count: n), "\(id) \(n)")
                XCTAssertEqual(w.streak(n: n), L.Native.Plurals.Perks.streak(n: n), "\(id) \(n)")
            }
        }
    }

    func testATranslationShowsAndRightToLeftTextIsIsolated() {
        XCTAssertEqual(WidgetL10n(language: "de-DE").notifications, "Benachrichtigungen")
        let ar = WidgetL10n(language: "ar-SA")
        XCTAssertEqual(ar.number(1234), "١٬٢٣٤")
        XCTAssertTrue(ar.streak(n: 5).contains("\u{2068}\(ar.number(5))\u{2069}"), ar.streak(n: 5))
        XCTAssertFalse(WidgetL10n(language: "ku-TR").isRtl)
        XCTAssertEqual(WidgetL10n(language: "zz-ZZ").language, "en-US", "an unknown language is English")
    }

    func testTheParserFollowsTheSharedQuotingAndPlurals() {
        XCTAssertEqual(WidgetIcu.parse("l'ami"), [.text("l'ami")])
        XCTAssertEqual(WidgetIcu.parse("it''s '{x}'"), [.text("it's {x}")])
        XCTAssertNil(WidgetIcu.parse("{x"))
        XCTAssertNil(WidgetIcu.parse("{n, plural, one {#}}"), "a plural without other")
        XCTAssertNil(WidgetIcu.parse("{n, date}"))
        XCTAssertEqual(WidgetIcu.parse("{n, plural, offset:1 =0 {none} other {# more}}"),
                       [.plural("n", 1, ["=0": [.text("none")], "other": [.pound, .text(" more")]])])
    }

    func testDeviceLanguagesMatchAsTheSharedLocaleMatch() {
        for tag in ["de_DE", "de-AT", "pt-BR", "pt", "zh", "zh-Hant-TW", "zh-HK", "zh-Hans-CN", "iw", "in-ID", "sr-Latn", "en", "EN-us", "ku", "fil", "xx", ""] {
            XCTAssertEqual(WidgetL10n.match(tag), LocaleMatch.shared.registered(locale: tag, ids: L10n.shared.supported), tag)
        }
    }

    func testTheExtensionReadsTheLanguageTheAppHandsOver() throws {
        let file = try XCTUnwrap(WidgetL10n.languageFileURL(), "the app is in the App Group")
        XCTAssertEqual(file, IosWidgetStore.languageFileURL(), "the app writes where the widget reads")
        XCTAssertEqual(file.deletingLastPathComponent(), WidgetSnapshotData.fileURL()?.deletingLastPathComponent(), "beside the snapshot, out of backups")
        let before = try? Data(contentsOf: file)
        defer { if let before { try? before.write(to: file) } else { try? FileManager.default.removeItem(at: file) } }
        IosWidgetStore.handOver(language: "de-DE")
        XCTAssertEqual(WidgetL10n.handedOver(), "de-DE")
        XCTAssertEqual(WidgetL10n.current.language, "de-DE")
        XCTAssertEqual(WidgetEntry(date: Date(), snapshot: .empty).l10n.language, "de-DE", "each timeline entry takes it")
        XCTAssertEqual(StreakWidgetView(snapshot: .empty, family: .systemSmall).l10n.signedOut, L.Native.Widgets.signedOutText)
        IosWidgetStore.handOver(language: "he-IL")
        XCTAssertTrue(WidgetL10n.current.isRtl)
        // untrusted: anything that is not one of the app's languages reads as not handed over (the device's then)
        try Data("../../etc".utf8).write(to: file)
        XCTAssertNil(WidgetL10n.handedOver())
        XCTAssertEqual(WidgetL10n.current.language, WidgetL10n.match(Locale.preferredLanguages.first) ?? "en-US")
    }

    func testTheBundleHasThePermissionPromptsInEveryLanguage() throws {
        let keys = ["NSFaceIDUsageDescription", "NSCameraUsageDescription", "NSPhotoLibraryAddUsageDescription", "NSPhotoLibraryUsageDescription", "NSMicrophoneUsageDescription"]
        for id in L10n.shared.supported {
            let tag = L10n.shared.systemTag(languageId: id)
            let path = try XCTUnwrap(Bundle.main.path(forResource: "InfoPlist", ofType: "strings", inDirectory: nil, forLocalization: tag), tag)
            let table = try XCTUnwrap(NSDictionary(contentsOfFile: path) as? [String: String], tag)
            XCTAssertEqual(Set(table.keys), Set(keys), tag)
            L10n.shared.setLanguage(id: id)
            XCTAssertEqual(table["NSCameraUsageDescription"], L.Native.Permission.camera, tag)
            XCTAssertEqual(table["NSMicrophoneUsageDescription"], L.Native.Permission.microphone, tag)
        }
        // the base Info.plist holds the English texts
        L10n.shared.setLanguage(id: "en-US")
        XCTAssertEqual(Bundle.main.object(forInfoDictionaryKey: "NSCameraUsageDescription") as? String, L.Native.Permission.camera)
        XCTAssertEqual(Bundle.main.object(forInfoDictionaryKey: "NSFaceIDUsageDescription") as? String, L.Native.Permission.faceId)
    }
}
