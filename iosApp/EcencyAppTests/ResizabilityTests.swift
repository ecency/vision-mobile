import EcencyCore
import SwiftUI
import XCTest
@testable import EcencyApp

/// iPhone Duo readiness (README "iPhone Duo", issue #3601): the app sizes itself from its window and its display's
/// scale, which change while it runs (fold, unfold, the inner display's poses), never from the main screen.
@MainActor
final class ResizabilityTests: XCTestCase {
    private var window: UIWindow?

    override func tearDown() async throws {
        window?.isHidden = true
        window = nil
    }

    // MARK: no screen-wide sizing in the app

    /// What Apple's iPhone Duo guide says to search for and remove: the main screen (size, scale, capture), the
    /// device's orientation and the idiom as a layout switch.
    nonisolated static let forbidden = [
        "UIScreen.main", "UIScreen.capturedDidChangeNotification", "UIDevice.current.orientation",
        "statusBarOrientation", "interfaceOrientation", "userInterfaceIdiom",
    ]

    func testTheAppNeverSizesFromTheScreenOrTheDeviceOrientation() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("EcencyApp")
        var files = 0
        var offenders: [String] = []
        let enumerator = try XCTUnwrap(FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil))
        for case let url as URL in enumerator where url.pathExtension == "swift" {
            files += 1
            let lines = try String(contentsOf: url, encoding: .utf8).components(separatedBy: "\n")
            for (i, line) in lines.enumerated() {
                let code = line.components(separatedBy: "//").first ?? line
                for token in Self.forbidden where code.contains(token) {
                    offenders.append("\(url.lastPathComponent):\(i + 1): \(token)")
                }
            }
        }
        XCTAssertGreaterThan(files, 40, "the app sources were found")
        XCTAssertTrue(offenders.isEmpty, "size from the window or the trait collection instead:\n" + offenders.joined(separator: "\n"))
    }

    // MARK: sizes follow the window

    func testCardImagesAreCappedAtTheWindowNotTheScreen() {
        XCTAssertEqual(CardImageView.height(width: 384, ratio: 16.0 / 9.0, maxHeight: 800), 216)
        XCTAssertEqual(CardImageView.height(width: 384, ratio: 0.1, maxHeight: 800), 800)
        XCTAssertEqual(CardImageView.height(width: 384, ratio: .nan, maxHeight: 800), 216)
        // the folded outer display is shorter than the inner one: the cap follows the window
        XCTAssertEqual(CardImageView.height(width: 384, ratio: 0.1, maxHeight: 640), 640)
        // before the window is measured only the ratio counts
        XCTAssertEqual(CardImageView.height(width: 384, ratio: 0.1, maxHeight: nil), 3840)
    }

    func testTheDrawerIsAShareOfTheWindow() {
        // RN's 280 pt on a phone-sized window
        XCTAssertEqual(SideMenuDrawer.width(scaled: 280, windowWidth: 402, stacked: false), 280)
        // larger text grows it, up to 85% of the window
        XCTAssertEqual(SideMenuDrawer.width(scaled: 400, windowWidth: 402, stacked: false), 402 * 0.85, accuracy: 0.001)
        // the unfolded inner display leaves room: the text size decides, not the screen
        XCTAssertEqual(SideMenuDrawer.width(scaled: 400, windowWidth: 669, stacked: false), 400)
        XCTAssertEqual(SideMenuDrawer.width(scaled: 400, windowWidth: 402, stacked: true), 402 * 0.92, accuracy: 0.001)
        XCTAssertEqual(SideMenuDrawer.width(scaled: 280, windowWidth: nil, stacked: false), 280)
    }

    func testTheViewportFollowsTheWindowThroughAFold() throws {
        let probe = ViewportProbe()
        let window = host(ProbeView(probe: probe).readsViewport(), size: CGSize(width: 669, height: 951))
        // what the window got (a landscape scene, the unfolded iPhone Duo held sideways, keeps its own size)
        let unfolded = try XCTUnwrap(probe.size)
        XCTAssertEqual(unfolded.width, window.bounds.width, accuracy: 1)
        XCTAssertEqual(unfolded.height, window.bounds.height, accuracy: 1)

        // folded: the same window, resized
        window.frame = CGRect(x: 0, y: 0, width: 466, height: 678)
        window.layoutIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.2))
        let folded = try XCTUnwrap(probe.size)
        XCTAssertEqual(folded.width, window.bounds.width, accuracy: 1)
        XCTAssertEqual(folded.height, window.bounds.height, accuracy: 1)
    }

    // MARK: orientation follows the display's size, never the device

    func testPhonesStayInPortraitAndIPhoneDuoTakesAnyOrientation() {
        // every iPhone keeps today's portrait lock (tall displays, about 2:1): the smallest and the largest, either way round
        for size in [CGSize(width: 375, height: 667), CGSize(width: 402, height: 874), CGSize(width: 440, height: 956), CGSize(width: 956, height: 440)] {
            XCTAssertEqual(OrientationPolicy.mask(screen: size), .portrait, "\(size)")
        }
        // iPhone Duo's displays are near square (about 1.4:1) and follow how it is held: the outer one (1398 x 2034 px
        // at 3x), the inner one (2007 x 2853 px) either way round, and the phone-sized box iOS reports for the inner
        // display while an app is portrait only (469 x 669 pt): a size rule never got out of that box
        for size in [CGSize(width: 466, height: 678), CGSize(width: 669, height: 951), CGSize(width: 951, height: 669), CGSize(width: 469, height: 669)] {
            XCTAssertEqual(OrientationPolicy.mask(screen: size), .allButUpsideDown, "\(size)")
        }
        XCTAssertEqual(OrientationPolicy.mask(screen: nil), .portrait, "no display yet")
    }

    // MARK: pixels follow the display's scale

    func testThePinKeysFitAWideShortWindow() {
        // the PIN pad's rows as PinScreen lays them out: a 13th of what is left under the top 15th, six of them
        func keyboard(_ height: CGFloat) -> CGFloat { (height - height / 15 - 40) / 13 * 6 }
        // a phone: RN's seventh of the width, as before
        XCTAssertEqual(PinScreen.keySize(width: 402, keyboardHeight: keyboard(874)), 402 / 7, accuracy: 0.01)
        // iPhone Duo's inner display held sideways and its outer display: four rows fit their share of the height
        for (w, h) in [(951.0, 669.0), (466.0, 678.0)] {
            let key = PinScreen.keySize(width: w, keyboardHeight: keyboard(h))
            XCTAssertLessThanOrEqual(key * 4 + 30, keyboard(h) + 0.01, "\(w) x \(h)")
        }
    }

    func testImagesDecodeInWidthStepsSoAResizeAsksForFew() {
        XCTAssertEqual(LoadedImageView.pixelStep(1206), 1280)
        XCTAssertEqual(LoadedImageView.pixelStep(1280), 1280)
        XCTAssertEqual(LoadedImageView.pixelStep(1), 256)
        // a fold's in-between widths share one decode
        XCTAssertEqual(Set([1030, 1100, 1180, 1270].map { LoadedImageView.pixelStep($0) }), [1280])
    }

    func testBodyImagesAreRequestedAtTheDisplaysScale() {
        // a proxied image: the proxy serves 320, 600, 800, 1024 and 1280 px wide variants
        let src = ImageProxy(base: ImageProxy.companion.DEFAULT_BASE)
            .proxify(url: "https://files.peakd.com/file/peakd-hive/alice/photo.jpg", width: 0, height: 0, blur: false, forceProxy: true)
        XCTAssertTrue(bodyImageURL(src, points: 320, scale: 2).contains("width=800"), "640 px")
        XCTAssertTrue(bodyImageURL(src, points: 320, scale: 3).contains("width=1024"), "960 px")
        XCTAssertNotEqual(bodyImageURL(src, points: 320, scale: 2), bodyImageURL(src, points: 320, scale: 3))
        XCTAssertEqual(bodyImageURL(src, points: 320, scale: 3), bodyImageURL(src, points: 480, scale: 2))
    }

    func testChipsAreDrawnAtTheDisplaysScale() throws {
        let font = UIFont.systemFont(ofSize: 16)
        for scale in [CGFloat(2), 3] {
            let chip = ChipAttachment(label: "@alice", avatarURL: "https://images.example/u/alice/avatar", theme: .light, font: font, displayScale: scale)
            XCTAssertEqual(chip.avatarPixels, ChipAttachment.avatarSize * scale)
            let image = try XCTUnwrap(chip.image(forBounds: chip.bounds, textContainer: nil, characterIndex: 0))
            XCTAssertEqual(image.scale, scale)
        }
        // the builder passes its display scale to every chip
        let built = InlineTextBuilder(theme: .light, size: 16, displayScale: 3).build([InlineMention(author: "alice")])
        XCTAssertEqual(built.chips.map(\.displayScale), [3])
    }

    private func host<V: View>(_ view: V, size: CGSize) -> UIWindow {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow()
        window.frame = CGRect(origin: .zero, size: size)
        window.rootViewController = UIHostingController(rootView: view.ignoresSafeArea())
        window.makeKeyAndVisible()
        window.layoutIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.2))
        self.window = window
        return window
    }
}

@MainActor
private final class ViewportProbe {
    var size: CGSize?
}

/// Reports the `\.viewportSize` it is given.
private struct ProbeView: View {
    let probe: ViewportProbe
    @Environment(\.viewportSize) private var viewport

    var body: some View {
        Color.clear
            .onAppear { probe.size = viewport }
            .onChange(of: viewport) { _, size in probe.size = size }
    }
}
