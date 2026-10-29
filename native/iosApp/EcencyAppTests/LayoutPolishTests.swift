import EcencyCore
import SwiftUI
import XCTest
@testable import EcencyApp

/// Layout checks drawn for real (ImageRenderer, 1 px per point): the spin wheel on the screen's centre line and the
/// drawer header cut to the drawer, at the phone sizes and text sizes the app runs at (portrait only).
@MainActor
final class LayoutPolishTests: XCTestCase {
    /// The content area under the navigation bar: iPhone SE, 13 mini, 16, 16 Pro Max.
    private let screens: [CGSize] = [CGSize(width: 320, height: 460), CGSize(width: 375, height: 560), CGSize(width: 393, height: 700), CGSize(width: 440, height: 820)]
    private let textSizes: [DynamicTypeSize] = [.large, .xxxLarge, .accessibility5]

    private struct Bitmap {
        let width: Int
        let height: Int
        let bytes: [UInt8]

        init(_ image: CGImage) {
            width = image.width
            height = image.height
            var data = [UInt8](repeating: 0, count: width * height * 4)
            let ctx = CGContext(data: &data, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            bytes = data
        }

        /// Top-left origin.
        func pixel(_ x: Int, _ y: Int) -> (r: UInt8, g: UInt8, b: UInt8, a: UInt8) {
            let i = (y * width + x) * 4
            return (bytes[i], bytes[i + 1], bytes[i + 2], bytes[i + 3])
        }

        /// The bounding box of the pixels that match.
        func box(_ matches: ((r: UInt8, g: UInt8, b: UInt8, a: UInt8)) -> Bool) -> CGRect? {
            var minX = Int.max, minY = Int.max, maxX = -1, maxY = -1
            for y in 0..<height {
                for x in 0..<width where matches(pixel(x, y)) {
                    minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                }
            }
            guard maxX >= 0 else { return nil }
            return CGRect(x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1)
        }
    }

    private func render<V: View>(_ view: V, _ size: CGSize, typeSize: DynamicTypeSize = .large, direction: LayoutDirection = .leftToRight) throws -> Bitmap {
        let renderer = ImageRenderer(content: view.frame(width: size.width, height: size.height)
            .environment(\.dynamicTypeSize, typeSize).environment(\.layoutDirection, direction))
        renderer.scale = 1
        return Bitmap(try XCTUnwrap(renderer.cgImage))
    }

    /// Plain sRGB red (`Color.red` is the system red, drawn a little differently per color space).
    private static let pure = Color(.sRGB, red: 1, green: 0, blue: 0)

    private static let blue = Color(.sRGB, red: 0, green: 0, blue: 1)
    private static func isBlue(_ p: (r: UInt8, g: UInt8, b: UInt8, a: UInt8)) -> Bool { p.a > 120 && p.b > 120 && p.r < 90 && p.g < 90 }

    private static func red(_ p: (r: UInt8, g: UInt8, b: UInt8, a: UInt8)) -> Bool { p.a > 200 && p.r > 200 && p.g < 60 && p.b < 60 }

    /// The stage as the screen fills it: counters, a wheel, the button. [wheelWidth] narrower than the screen is the
    /// spinning state (no tags), where the wheel used to sit at the leading edge.
    private func stage(wheel: CGFloat) -> some View {
        SpinStage {
            VStack(spacing: 5) {
                Text("1").scaledFont(40, weight: .bold)
                Text("Spin left").scaledFont(16, weight: .bold)
            }
            .foregroundStyle(Self.blue)
        } wheel: { side in
            Self.pure.frame(width: min(wheel, side), height: min(wheel, side))
        } bottom: {
            VStack {
                Text("SPIN & WIN").bold().frame(minWidth: 150, minHeight: 50)
                Spacer(minLength: 0)
            }
        }
    }

    func testTheWheelSitsOnTheCentreLineOfEveryPhoneAndTextSize() throws {
        for size in screens {
            for typeSize in textSizes {
                for wheel in [CGFloat(100), 230, 400] {
                    let bitmap = try render(stage(wheel: wheel), size, typeSize: typeSize)
                    let box = try XCTUnwrap(bitmap.box(Self.red), "wheel drawn at \(size) \(typeSize)")
                    XCTAssertEqual(box.midX, size.width / 2, accuracy: 2, "wheel \(wheel) centred at \(size) \(typeSize)")
                    // the counters never run into the wheel's part (they once kept a 0.15 share and overflowed it)
                    let counters = try XCTUnwrap(bitmap.box(Self.isBlue), "counters drawn at \(size) \(typeSize)")
                    XCTAssertGreaterThanOrEqual(box.minY, counters.maxY, "wheel \(wheel) below the counters at \(size) \(typeSize)")
                }
            }
        }
    }

    /// The real rings: centred, and shrunk to their part where 230 pt do not fit (short phone, largest text).
    func testTheRingsAreCentredAndShrinkToTheirPart() throws {
        for size in screens + [CGSize(width: 320, height: 380)] {
            for typeSize in [DynamicTypeSize.large, .accessibility5] {
                let view = SpinStage {
                    Text("1").scaledFont(40, weight: .bold).foregroundStyle(Color.clear)
                } wheel: { side in
                    SpinRings(spinning: false).scaleEffect(min(1, side / SpinRings.size))
                } bottom: {
                    Text("SPIN & WIN").bold().foregroundStyle(Color.clear).frame(minWidth: 150, minHeight: 50)
                }
                let bitmap = try render(view, size, typeSize: typeSize)
                let box = try XCTUnwrap(bitmap.box { $0.a > 100 }, "rings drawn at \(size) \(typeSize)")
                XCTAssertEqual(box.midX, size.width / 2, accuracy: 2, "rings centred at \(size) \(typeSize)")
                XCTAssertLessThanOrEqual(box.height, size.height / 2 + 1, "rings within their part at \(size) \(typeSize)")
                XCTAssertLessThanOrEqual(box.height, SpinRings.size + 1)
            }
        }
    }

    /// The Refer button's label and share icon sit centred, with no empty icon slot before the text (owner note
    /// 10-28), both ways round. A UI test cannot see inside the button (one element), so it is drawn here.
    func testTheReferLabelAndIconAreCentredWithNoGap() throws {
        for direction in [LayoutDirection.leftToRight, .rightToLeft] {
            let size = CGSize(width: 300, height: 60)
            let bitmap = try render(ReferShareLabel(title: "Refer").foregroundStyle(Self.pure), size, direction: direction)
            let box = try XCTUnwrap(bitmap.box(Self.red), "drawn \(direction)")
            XCTAssertEqual(box.midX, size.width / 2, accuracy: 2, "centred \(direction)")
            // the label and the icon only: about 5 letters plus 12 pt and an icon, nowhere near the 160 pt minimum
            XCTAssertLessThan(box.width, 110, "no empty slot \(direction)")
        }
    }

    /// The same inside a real List row (as on the Refer screen), where a Label's icon column made the gap. ImageRenderer
    /// does not draw a List, so it is hosted in a window and snapshotted.
    func testTheReferLabelIsCentredInAListRow() throws {
        let size = CGSize(width: 390, height: 300)
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow()
        window.frame = CGRect(origin: .zero, size: size)
        window.rootViewController = UIHostingController(rootView: List {
            VStack { ReferShareLabel(title: "Refer").foregroundStyle(Self.pure) }.frame(maxWidth: .infinity)
        })
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        RunLoop.main.run(until: Date().addingTimeInterval(0.5))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            window.drawHierarchy(in: window.bounds, afterScreenUpdates: true)
        }
        let bitmap = Bitmap(try XCTUnwrap(image.cgImage))
        let box = try XCTUnwrap(bitmap.box(Self.red), "drawn in the row")
        XCTAssertEqual(box.midX, size.width / 2, accuracy: 3, "centred in the row")
        XCTAssertLessThan(box.width, 110, "no empty icon slot")
    }

    /// The header picture stays inside the drawer: the red behind it, past the drawer's trailing edge, still shows.
    func testTheDrawerHeaderIsCutToTheDrawerWidth() throws {
        // RN's 280 pt with its 164 pt header, and the drawer widened at the largest text with a taller header
        for (drawer, header) in [(CGFloat(280), CGFloat(164)), (280, 220), (360, 300)] {
            for direction in [LayoutDirection.leftToRight, .rightToLeft] {
                let size = CGSize(width: 440, height: header)
                let view = ZStack(alignment: .leading) {
                    Self.pure
                    DrawerHeaderBackground().frame(width: drawer, height: header)
                }
                let bitmap = try render(view, size, direction: direction)
                let box = try XCTUnwrap(bitmap.box { !Self.red($0) }, "header drawn")
                XCTAssertLessThanOrEqual(box.width, drawer, "header \(drawer)x\(header) \(direction) no wider than the drawer")
                let edge = direction == .leftToRight ? box.minX : size.width - box.maxX
                XCTAssertEqual(edge, 0, accuracy: 1, "header on the drawer's side \(direction)")
                XCTAssertEqual(box.height, header, accuracy: 1)
            }
        }
    }
}
