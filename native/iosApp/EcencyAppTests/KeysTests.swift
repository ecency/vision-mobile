import EcencyCore
import XCTest
@testable import EcencyApp

/// The keys sheet's platform pieces: the pasteboard rules for a private key and the screenshot shield.
@MainActor
final class KeysTests: XCTestCase {
    func testAPrivateKeyStaysOnThisDeviceAndExpiresFromThePasteboard() throws {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let options = KeyPasteboard.options(sensitive: true, now: now)
        XCTAssertEqual(options[.localOnly] as? Bool, true)
        XCTAssertEqual(options[.expirationDate] as? Date, now.addingTimeInterval(120))
        XCTAssertTrue(KeyPasteboard.options(sensitive: false, now: now).isEmpty, "a public key is an ordinary copy")
    }

    func testCopyingPutsTheTextOnTheGivenPasteboard() throws {
        let board = UIPasteboard.withUniqueName()
        defer { UIPasteboard.remove(withName: board.name) }
        KeyPasteboard.copy("STM-public-test-value", sensitive: false, to: board)
        XCTAssertEqual(board.string, "STM-public-test-value")
    }

    /// The shield relies on the canvas view of UIKit's secure text entry; this fails when an iOS release no longer
    /// has that class, which is when the app falls back to press-and-hold.
    func testTheShieldDrawsItsLabelInsideTheSecureCanvasItself() throws {
        let holder = ScreenShield.Holder(frame: .zero)
        XCTAssertTrue(holder.isShielding, "this iOS has no secure canvas: the keys sheet runs on its press-and-hold fallback")
        let container = try XCTUnwrap(holder.label.superview)
        XCTAssertFalse(container === holder, "the label must not sit directly in the plain view")
        XCTAssertTrue(String(describing: type(of: container)).contains(ScreenShield.secureCanvasMarker), "the container is the secure canvas, not any subview")
        XCTAssertTrue(holder.field.isSecureTextEntry)
        XCTAssertTrue(ScreenShield.isAvailable)
    }

    /// Fail closed: when no subview of the secure field is the canvas, the label is put nowhere and draws nothing.
    func testWithoutTheSecureCanvasNothingIsDrawn() throws {
        let holder = ScreenShield.Holder(frame: CGRect(x: 0, y: 0, width: 300, height: 44), canvasMarker: "NoSuchCanvasClass")
        holder.label.text = "5JthrowawayValueThatMustNotBeDrawn"
        holder.backgroundColor = .white
        XCTAssertFalse(holder.isShielding)
        XCTAssertNil(holder.label.superview, "not in the secure container, not in the plain view, not in some other subview")
        XCTAssertTrue(holder.subviews.isEmpty)
        let image = UIGraphicsImageRenderer(bounds: holder.bounds).image { holder.layer.render(in: $0.cgContext) }
        let dark = try Self.darkPixels(image)
        XCTAssertEqual(dark, 0, "no text is drawn")
    }

    /// The shield must still draw: the label is laid out with a size inside a window, and drawing the layer tree
    /// in-process (which is not a screen capture) shows its text.
    func testTheShieldedLabelIsLaidOutAndDrawn() throws {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 300, height: 44))
        let holder = ScreenShield.Holder(frame: window.bounds)
        holder.label.text = "STM7throwawayPublicKeyForTheLayoutTest"
        holder.label.textColor = .black
        holder.backgroundColor = .white
        window.addSubview(holder)
        window.isHidden = false
        holder.setNeedsLayout()
        holder.layoutIfNeeded()
        XCTAssertGreaterThan(holder.label.frame.width, 100)
        XCTAssertGreaterThan(holder.label.frame.height, 8)
        XCTAssertNotNil(holder.label.window)
        let labelInHolder = holder.label.convert(holder.label.bounds, to: holder)
        XCTAssertTrue(holder.bounds.insetBy(dx: -1, dy: -1).contains(labelInHolder), "the label sits inside the box")

        let renderer = UIGraphicsImageRenderer(bounds: holder.bounds)
        let image = renderer.image { context in holder.layer.render(in: context.cgContext) }
        let dark = try Self.darkPixels(image)
        XCTAssertGreaterThan(dark, 50, "the key's text is drawn")
        window.isHidden = true
    }

    /// Dark pixels of [image], row by row (rows are padded: the padding is not part of the picture).
    private static func darkPixels(_ image: UIImage) throws -> Int {
        let cg = try XCTUnwrap(image.cgImage)
        let data = try XCTUnwrap(cg.dataProvider?.data as Data?)
        let step = cg.bitsPerPixel / 8
        var dark = 0
        for y in 0..<cg.height {
            for x in 0..<cg.width {
                let i = y * cg.bytesPerRow + x * step
                if Int(data[i]) + Int(data[i + 1]) + Int(data[i + 2]) < 300 { dark += 1 }
            }
        }
        return dark
    }

    func testTheSharedCopyIsRns() throws {
        let copy = BackupKeysCopy.shared
        XCTAssertEqual(copy.TITLE, "Manage Authorities")
        XCTAssertEqual(copy.KEYS_WARNING, "Sensitive account information!\nEcency must be secured with PIN code before viewing private keys.")
        XCTAssertEqual(copy.IMPORT_FAILED, "Keys Import Failed")
    }
}

/// The signup challenge's rules as Swift sees them (the web view only follows these answers).
final class TurnstileRulesTests: XCTestCase {
    func testOnlyTheTwoHostsLoadAsTheTopDocumentAndMessagesNeedTheEmbedPage() {
        let page = TurnstilePage.shared
        XCTAssertTrue(page.allowsLoad(url: "https://ecency.com/embed/turnstile", isTopFrame: KotlinBoolean(bool: true)))
        XCTAssertFalse(page.allowsLoad(url: "https://evil.example/", isTopFrame: KotlinBoolean(bool: true)))
        XCTAssertTrue(page.allowsLoad(url: "about:srcdoc", isTopFrame: KotlinBoolean(bool: false)))
        XCTAssertTrue(page.acceptsMessagesFrom(topUrl: "https://ecency.com/embed/turnstile"))
        XCTAssertFalse(page.acceptsMessagesFrom(topUrl: "https://challenges.cloudflare.com/x"))
        XCTAssertEqual((page.parse(message: #"{"type":"verify","token":"tok"}"#) as? TurnstilePageEventVerified)?.token, "tok")
        XCTAssertNil(page.parse(message: #"{"type":"navigate"}"#))
    }

    func testVersionsCompareLikeRn() {
        XCTAssertEqual(AppVersions.shared.parse(version: "4.0.1"), 4_000_001)
        XCTAssertEqual(AppVersions.shared.parse(version: nil), 0)
    }
}
