import EcencyCore
import SwiftUI
import XCTest
@testable import EcencyApp

/// README "Accessibility" (iOS): Dynamic Type helpers measured at the default and the largest accessibility size,
/// and a source check that readable text never uses a fixed point size.
@MainActor
final class AccessibilityTests: XCTestCase {
    /// The fitted size of [view] at [typeSize] in [width] points (unbounded height).
    private func size<V: View>(_ view: V, _ typeSize: DynamicTypeSize, width: CGFloat = 375) -> CGSize {
        let host = UIHostingController(rootView: view.environment(\.dynamicTypeSize, typeSize))
        return host.sizeThatFits(in: CGSize(width: width, height: .greatestFiniteMagnitude))
    }

    func testScaledFontGrowsWithDynamicType() {
        let text = Text(verbatim: "Ag").scaledFont(16)
        let base = size(text, .large), big = size(text, .accessibility5)
        XCTAssertGreaterThan(big.height, base.height * 2, "\(base) -> \(big)")
    }

    func testAdaptiveStackStacksOnlyAtAccessibilitySizes() {
        let row = AdaptiveStack(spacing: 0) {
            Text(verbatim: "Label").font(.body)
            Text(verbatim: "Value").font(.body)
        }
        let side = size(row, .xxxLarge), stacked = size(row, .accessibility1)
        XCTAssertGreaterThan(side.width, side.height * 2, "side by side below the accessibility sizes: \(side)")
        XCTAssertGreaterThan(stacked.height, size(Text(verbatim: "Label").font(.body), .accessibility1).height * 1.5,
                             "two lines at accessibility sizes: \(stacked)")
    }

    func testChipFlowKeepsALongChipInsideTheRow() {
        let flow = ChipFlow {
            Text(verbatim: "a-very-long-tag-name-that-never-fits").font(.footnote).padding(.horizontal, 10)
            Text(verbatim: "short").font(.footnote)
        }
        .frame(width: 200)
        let fitted = size(flow, .accessibility5, width: 200)
        XCTAssertLessThanOrEqual(fitted.width, 200.5)
        XCTAssertGreaterThan(fitted.height, size(Text(verbatim: "short").font(.footnote), .accessibility5).height * 2,
                             "the long chip wraps and the short one moves to its own line: \(fitted)")
    }

    func testTapTargetIsAtLeast44Points() {
        let small = Image(systemName: "xmark").font(.system(size: 10)).tapTarget()
        let fitted = size(small, .large)
        XCTAssertGreaterThanOrEqual(fitted.width, 44)
        XCTAssertGreaterThanOrEqual(fitted.height, 44)
    }

    /// Text people read follows Dynamic Type: a literal point size is allowed only for pictures (an `Image`, a widget
    /// symbol or an emoji glyph alone on the line), or with a `fixed size:` comment saying why on the same line.
    func testNoFixedSizeFontOnReadableText() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        var offenders: [String] = []
        for dir in ["EcencyApp", "Widgets", "ShareExtension"] {
            let base = root.appendingPathComponent(dir)
            guard let files = FileManager.default.enumerator(at: base, includingPropertiesForKeys: nil) else { continue }
            for case let url as URL in files where url.pathExtension == "swift" {
                let lines = try String(contentsOf: url, encoding: .utf8).components(separatedBy: "\n")
                for (i, line) in lines.enumerated() where Self.isFixedReadableFont(line) {
                    offenders.append("\(dir)/\(url.lastPathComponent):\(i + 1): \(line.trimmingCharacters(in: .whitespaces))")
                }
            }
        }
        XCTAssertTrue(offenders.isEmpty, "fixed size fonts on text:\n" + offenders.joined(separator: "\n"))
    }

    /// A literal point size in any spelling: SwiftUI `.font(.system(size:))` and `Font.system(size:)`, UIKit
    /// `UIFont.systemFont(ofSize:)`, and `.custom(_, size:)` without `relativeTo:`. A computed size (an @ScaledMetric,
    /// a size scaled from the type size) follows Dynamic Type and passes.
    nonisolated static let fixedSizePatterns = [#"\.font\(\.system\(size: *[0-9]"#, #"Font\.system\(size: *[0-9]"#,
                                                #"UIFont\.(bold)?[sS]ystemFont\(ofSize: *[0-9]"#, #"\.custom\([^)]*size: *[0-9]"#]

    nonisolated static func matches(_ line: String, _ pattern: String) -> Bool {
        line.range(of: pattern, options: .regularExpression) != nil
    }

    nonisolated static func isFixedReadableFont(_ line: String) -> Bool {
        guard fixedSizePatterns.contains(where: { matches(line, $0) }) else { return false }
        if matches(line, #"\.custom\("#), line.contains("relativeTo"), !matches(line, fixedSizePatterns[0]) { return false }
        if line.contains("fixed size:") { return false }
        let texts = line.components(separatedBy: "Text(").count - 1
        let otherText = matches(line, #"\b(Label|TextField|TextEditor|SecureField)\("#)
        // an emoji glyph is a picture
        if texts == 1, !otherText, matches(line, #"Text\([\w.]*emoji\)"#) { return false }
        // an icon, unless the same line also draws text
        if texts == 0, !otherText, matches(line, #"Image\(|widgetSymbol|UIImage"#) { return false }
        return true
    }

    func testTheLintRuleItself() {
        XCTAssertTrue(Self.isFixedReadableFont(#"Text(x).font(.system(size: 12))"#))
        XCTAssertFalse(Self.isFixedReadableFont(#"Image(appIcon: .x).font(.system(size: 56))"#))
        XCTAssertTrue(Self.isFixedReadableFont(#"HStack { Image(appIcon: .x); Text(t) }.font(.system(size: 13))"#), "text beside an icon")
        XCTAssertFalse(Self.isFixedReadableFont(#"Text(x).scaledFont(12)"#))
        XCTAssertFalse(Self.isFixedReadableFont(#"Text(x).font(.system(size: clockSize, weight: .bold))"#), "a scaled metric")
        XCTAssertFalse(Self.isFixedReadableFont(#"Text(n).font(.system(size: 17)) // fixed size: a widget family has a fixed frame"#))
        XCTAssertFalse(Self.isFixedReadableFont(#"Text(e.emoji).font(.system(size: 28))"#))
        XCTAssertTrue(Self.isFixedReadableFont(#"Text(e.name + e.emoji).font(.system(size: 28))"#), "text with an emoji in it")
        XCTAssertTrue(Self.isFixedReadableFont(#"let f = Font.system(size: 14)"#))
        XCTAssertTrue(Self.isFixedReadableFont(#"label.font = UIFont.systemFont(ofSize: 14)"#))
        XCTAssertTrue(Self.isFixedReadableFont(#"field.font = UIFont.boldSystemFont(ofSize: 14)"#))
        XCTAssertTrue(Self.isFixedReadableFont(#"Text(x).font(.custom("Inter", size: 14))"#))
        XCTAssertFalse(Self.isFixedReadableFont(#"Text(x).font(.custom("Inter", size: 14, relativeTo: .body))"#))
    }

    // MARK: contrast (WCAG 2 relative luminance)

    nonisolated static func luminance(_ color: Color) -> Double {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        UIColor(color).getRed(&r, green: &g, blue: &b, alpha: &a)
        func lin(_ c: CGFloat) -> Double { let c = Double(c); return c <= 0.03928 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
    }

    nonisolated static func contrast(_ a: Color, _ b: Color) -> Double {
        let (x, y) = (luminance(a), luminance(b))
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    func testContrastHelperMatchesKnownRatios() {
        XCTAssertEqual(Self.contrast(.white, .black), 21, accuracy: 0.01)
        XCTAssertEqual(Self.contrast(Color(hex: 0x767676), .white), 4.54, accuracy: 0.02)
    }

    /// Text tokens on the screen background meet WCAG AA (4.5:1) in both themes and with Increase Contrast, the root
    /// tint (which colors text buttons and links) included, and white text on the filled buttons.
    func testTextTokensMeetAAInLightAndDark() {
        let themes = [("light", AppTheme.light), ("dark", AppTheme.dark),
                      ("light high contrast", AppTheme.light.highContrast), ("dark high contrast", AppTheme.dark.highContrast)]
        for (name, theme) in themes {
            let text = [("text", theme.text), ("secondary", theme.secondary), ("accentText", theme.accentText),
                        ("dangerText", theme.dangerText), ("rootTint", theme.rootTint)]
            for (token, color) in text {
                XCTAssertGreaterThanOrEqual(Self.contrast(color, theme.background), 4.5, "\(name) \(token)")
            }
            XCTAssertGreaterThanOrEqual(Self.contrast(.white, theme.accent), 4.5, "\(name) white on the accent fill")
            XCTAssertGreaterThanOrEqual(Self.contrast(.white, theme.danger), 4.5, "\(name) white on the danger fill")
        }
    }

    /// The root tint is the text blue, not the dark fill blue (2.96:1 on the dark background).
    func testTheRootTintIsTheTextBlue() {
        XCTAssertEqual(Self.luminance(AppTheme.dark.rootTint), Self.luminance(AppTheme.dark.accentText), accuracy: 0.0001)
        XCTAssertLessThan(Self.contrast(AppTheme.dark.accent, AppTheme.dark.background), 4.5, "the fill would fail as text")
    }

    // MARK: announcements

    /// One announcement per failure: a lazy row coming back or a second row with the same text says nothing again;
    /// a new failure (another identity) or the text coming back after it went away announces again.
    func testAnnouncementsAreOncePerFailureAndSite() {
        AnnouncementLog.seen = [:]
        XCTAssertTrue(AnnouncementLog.shouldAnnounce(site: "a", text: "Failed", attempt: 1))
        XCTAssertFalse(AnnouncementLog.shouldAnnounce(site: "a", text: "Failed", attempt: 1), "a row scrolled back in")
        XCTAssertTrue(AnnouncementLog.shouldAnnounce(site: "a", text: "Failed", attempt: 2), "a retry failed again")
        XCTAssertTrue(AnnouncementLog.shouldAnnounce(site: "b", text: "Failed", attempt: 2), "another place")
        XCTAssertFalse(AnnouncementLog.shouldAnnounce(site: "a", text: nil, attempt: 0))
        XCTAssertTrue(AnnouncementLog.shouldAnnounce(site: "a", text: "Failed", attempt: 2), "the message went away in between")
        XCTAssertFalse(AnnouncementLog.shouldAnnounce(site: "a", text: "", attempt: 3), "nothing to say")
        // a row removed while showing it never says the text went away: the entry lapses
        let start = Date()
        XCTAssertTrue(AnnouncementLog.shouldAnnounce(site: "c", text: "Failed", attempt: 1, now: start))
        XCTAssertFalse(AnnouncementLog.shouldAnnounce(site: "c", text: "Failed", attempt: 1, now: start.addingTimeInterval(60)))
        XCTAssertTrue(AnnouncementLog.shouldAnnounce(site: "c", text: "Failed", attempt: 1,
                                                     now: start.addingTimeInterval(60 + AnnouncementLog.lifetime + 1)), "later")
        // seen again within the lifetime restarts its clock (a row that keeps scrolling in stays quiet)
        let slide = Date()
        XCTAssertTrue(AnnouncementLog.shouldAnnounce(site: "d", text: "Failed", attempt: 1, now: slide))
        XCTAssertFalse(AnnouncementLog.shouldAnnounce(site: "d", text: "Failed", attempt: 1, now: slide.addingTimeInterval(100)))
        XCTAssertFalse(AnnouncementLog.shouldAnnounce(site: "d", text: "Failed", attempt: 1, now: slide.addingTimeInterval(200)),
                       "seen at 100 s, so not lapsed at 200 s")
    }

    /// Two rows at one site (two journal notices), scrolled out and back: each spoke once, two announcements in all.
    func testTwoRowsAtOneSiteEachSpeakOnce() {
        AnnouncementLog.seen = [:]
        var said = 0
        for _ in 0..<3 {
            for row in ["first", "second"] where AnnouncementLog.shouldAnnounce(site: "rows", text: "Undecided", attempt: row) {
                said += 1
            }
        }
        XCTAssertEqual(said, 2)
    }

    /// A failure object's identity as the attempt: equal only to itself, and held, so it is never reused.
    func testObjectAttemptsCompareByIdentity() {
        final class Failure {}
        let a = Failure(), b = Failure()
        XCTAssertEqual(ObjectAttempt(a), ObjectAttempt(a))
        XCTAssertNotEqual(ObjectAttempt(a), ObjectAttempt(b))
        weak var held: Failure?
        var attempt: ObjectAttempt?
        do {
            let c = Failure()
            held = c
            attempt = ObjectAttempt(c)
        }
        XCTAssertNotNil(held, "the attempt keeps its failure alive")
        _ = attempt
    }

    /// The real modifier, mounted, unmounted and mounted again with the same text: a view says it each time it
    /// appears; a sticky (lazy row) site only once.
    func testTheModifierSpeaksPerMountUnlessSticky() {
        for (sticky, expected) in [(false, 2), (true, 1)] {
            AnnouncementLog.seen = [:]
            var said: [String] = []
            let saved = Announce.sink
            Announce.sink = { said.append($0) }
            defer { Announce.sink = saved }
            let shown = AnnounceProbe.Shown()
            let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 320, height: 480))
            window.rootViewController = UIHostingController(rootView: AnnounceProbe(shown: shown, sticky: sticky))
            window.makeKeyAndVisible()
            func settle() { RunLoop.main.run(until: Date().addingTimeInterval(0.4)) }
            settle()
            shown.value = false
            settle()
            shown.value = true
            settle()
            XCTAssertEqual(said, Array(repeating: "Failed", count: expected), "sticky \(sticky)")
            window.isHidden = true
        }
    }

    /// A chat message's links as VoiceOver actions: named by their text, or the host when the text is empty, opened
    /// with the parsed URL as a tap opens it; text without a link is no action.
    func testChatLinkActions() {
        let links = ChatBubbleText.links([
            ChatSpan(text: "see ", bold: false, italic: false, strike: false, code: false, url: nil),
            ChatSpan(text: "the post", bold: false, italic: false, strike: false, code: false, url: "https://ecency.com/@a/p"),
            ChatSpan(text: " ", bold: false, italic: false, strike: false, code: false, url: "https://example.com/x"),
            ChatSpan(text: "", bold: false, italic: false, strike: false, code: false, url: "hive://sign/op/abc"),
        ])
        XCTAssertEqual(links.map(\.name), ["the post", "example.com", "hive://sign/op/abc"])
        XCTAssertEqual(links.map(\.url), ["https://ecency.com/@a/p", "https://example.com/x", "hive://sign/op/abc"])
    }

    /// The pairings drawn on other backgrounds, in every theme: blue and red text on the gray fill (secondary buttons,
    /// the downvote), card counts and author names on the card, the notification line and time on the unread tint,
    /// RC text on white, the phishing warning's text on its pink banner.
    func testTextOnOtherBackgroundsMeetsAA() {
        let themes = [("light", AppTheme.light), ("dark", AppTheme.dark),
                      ("light high contrast", AppTheme.light.highContrast), ("dark high contrast", AppTheme.dark.highContrast)]
        for (name, theme) in themes {
            XCTAssertGreaterThanOrEqual(Self.contrast(theme.accentText, theme.lightBackground), 4.5, "\(name) accentText on lightBackground")
            XCTAssertGreaterThanOrEqual(Self.contrast(theme.secondary, theme.lightBackground), 4.5, "\(name) secondary on lightBackground")
            XCTAssertGreaterThanOrEqual(Self.contrast(theme.dangerText, theme.lightBackground), 4.5, "\(name) dangerText on lightBackground")
            XCTAssertGreaterThanOrEqual(Self.contrast(theme.successText, theme.background), 4.5, "\(name) successText")
            XCTAssertGreaterThanOrEqual(Self.contrast(theme.successText, theme.lightBackground), 4.5, "\(name) successText on lightBackground")
            XCTAssertGreaterThanOrEqual(Self.contrast(.white, OfficialBadge.fill(theme)), 4.5, "\(name) official badge")
            XCTAssertGreaterThanOrEqual(Self.contrast(CountLabel.numberColor(theme), theme.background), 4.5, "\(name) card count")
            XCTAssertGreaterThanOrEqual(Self.contrast(AuthorLine.nameColor(theme), theme.background), 4.5, "\(name) author name")
            let unread = NotificationRowColors.unread(dark: theme.isDark)
            XCTAssertGreaterThanOrEqual(Self.contrast(NotificationRowColors.line(theme), unread), 4.5, "\(name) notification line on unread")
        }
        XCTAssertGreaterThanOrEqual(Self.contrast(Color(hex: ContrastPalette.lightRcText), .white), 4.5, "RC text")
        XCTAssertGreaterThanOrEqual(Self.contrast(Color(hex: 0x3C4449), Color(hex: 0xFDEDED)), 4.5, "phishing warning")
    }
}

/// A view that shows an announced error while `shown` is true (AccessibilityTests).
struct AnnounceProbe: View {
    final class Shown: ObservableObject { @Published var value = true }
    @ObservedObject var shown: Shown
    let sticky: Bool

    var body: some View {
        VStack {
            if shown.value { Text(verbatim: "Failed").announces("Failed", sticky: sticky) }
        }
    }
}
