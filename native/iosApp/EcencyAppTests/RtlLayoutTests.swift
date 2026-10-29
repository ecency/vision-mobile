import EcencyCore
import SwiftUI
import XCTest
@testable import EcencyApp

/// The RTL pass (README "Localization", RTL): post bodies read in their own direction, HTML left and right stay
/// absolute, values beside catalog text are isolated, and the debug pseudo locale mirrors the root.
@MainActor
final class RtlLayoutTests: XCTestCase {
    override func tearDown() {
        L10n.shared.usePseudo(id: nil)
        L10n.shared.setLanguage(id: "en-US")
        super.tearDown()
    }

    private func paragraph(_ text: String) -> any Block {
        BlockParagraph(inlines: [InlineText(text: text, marks: [])], style: BlockStyle.companion.Default)
    }

    func testTheBodyDirectionFollowsTheContentNotTheApp() {
        L10n.shared.setLanguage(id: "ar-SA")
        XCTAssertEqual(bodyDirection([paragraph("Hello")]), .leftToRight, "an English post in an Arabic UI")
        L10n.shared.setLanguage(id: "en-US")
        XCTAssertEqual(bodyDirection([paragraph("مرحبا بالعالم")]), .rightToLeft, "an Arabic post in an English UI")
        XCTAssertEqual(bodyDirection([BlockParagraph(inlines: [InlineMention(author: "alice"), InlineText(text: " שלום", marks: [])],
                                                     style: BlockStyle.companion.Default)]), .rightToLeft, "a mention decides nothing")
    }

    func testHtmlLeftAndRightStayAbsoluteInEitherDirection() {
        func style(_ a: BlockStyle.Align) -> BlockStyle { BlockStyle(align: a, justify: false, phishy: false) }
        XCTAssertEqual(style(.end).horizontal(.leftToRight), .trailing)
        XCTAssertEqual(style(.end).horizontal(.rightToLeft), .leading, "right is the leading edge of an RTL body")
        XCTAssertEqual(style(.left).horizontal(.leftToRight), .leading)
        XCTAssertEqual(style(.left).horizontal(.rightToLeft), .trailing)
        XCTAssertEqual(style(.start).horizontal(.rightToLeft), .leading, "no alignment is the body's start")
        XCTAssertEqual(style(.center).horizontal(.rightToLeft), .center)
        XCTAssertEqual(style(.end).nsAlignment, .right)
        XCTAssertEqual(style(.left).nsAlignment, .left)
        XCTAssertEqual(style(.start).nsAlignment, .natural, "each paragraph's own direction")
    }

    func testValuesBesideCatalogTextAreIsolatedOnlyInRtl() {
        XCTAssertEqual(bidiValue("@alice"), "@alice")
        L10n.shared.setLanguage(id: "fa-IR")
        XCTAssertEqual(bidiValue("@alice"), "\u{2068}@alice\u{2069}")
        L10n.shared.setLanguage(id: "ku-TR")
        XCTAssertEqual(bidiValue("1.000 HIVE"), "1.000 HIVE", "Kurmanji is left to right")
    }

    /// Reads the direction a subtree gets.
    final class Probe { var direction: LayoutDirection? }
    struct DirectionProbe: View {
        let probe: Probe
        @Environment(\.layoutDirection) private var direction
        var body: some View {
            probe.direction = direction
            return Color.clear.frame(width: 1, height: 1)
        }
    }

    /// Shows [view] in a window until [done] holds (at most 3 s), as the app draws it.
    private func render(_ view: some View, until done: () -> Bool) {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return XCTFail("no scene") }
        let window = UIWindow(windowScene: scene)
        window.rootViewController = UIHostingController(rootView: view)
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        let end = Date().addingTimeInterval(3)
        while !done() && Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
    }

    func testMachineFieldsStayLeftToRightInAMirroredLayout() {
        let probe = Probe()
        render(DirectionProbe(probe: probe).leftToRightField().environment(\.layoutDirection, .rightToLeft)) { probe.direction != nil }
        XCTAssertEqual(probe.direction, .leftToRight)
    }

    func testTheRtlPseudoLocaleMirrorsTheRoot() {
        L10n.shared.usePseudo(id: "ar-XB")
        XCTAssertTrue(L10n.shared.isRtl)
        let probe = Probe()
        let id = L10n.shared.language.value as? String ?? ""
        XCTAssertEqual(id, "ar-XB")
        render(DirectionProbe(probe: probe).appLanguage(id)) { probe.direction != nil }
        XCTAssertEqual(probe.direction, .rightToLeft)
        L10n.shared.setLanguage(id: "de-DE")
        XCTAssertEqual(L10n.shared.language.value as? String, "ar-XB", "the settings language does not end it")
    }

    /// Sheets, covers and the lock window read the scene's traits, not SwiftUI's environment.
    func testPresentedControllersFollowTheAppDirectionThroughTheScene() throws {
        XCTAssertEqual(UIKitDirection.direction("he-IL"), .rightToLeft)
        XCTAssertEqual(UIKitDirection.direction("ku-TR"), .leftToRight)
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return XCTFail("no scene") }
        defer { UIKitDirection.apply("en-US") }
        UIKitDirection.apply("ar-SA")
        let window = UIWindow(windowScene: scene)
        let presented = UIViewController()
        window.rootViewController = presented
        XCTAssertEqual(presented.traitCollection.layoutDirection, .rightToLeft)
        UIKitDirection.apply("en-US")
        XCTAssertEqual(presented.traitCollection.layoutDirection, .leftToRight, "live, without a restart")
    }
}
