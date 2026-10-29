import EcencyCore
import SwiftUI
import XCTest
@testable import EcencyApp

/// Esie on iOS (README "Esie"): the shared mood tables as Swift sees them, the paths, and a real render.
@MainActor
final class EsieTests: XCTestCase {
    func testPlacementsUseTheApprovedMoods() {
        XCTAssertEqual(EsiePlacement.empty.mood, .rest)
        XCTAssertEqual(EsiePlacement.error.mood, .sad)
        XCTAssertEqual(EsiePlacement.success.mood, .happy)
        XCTAssertEqual(EsiePlacement.welcome.mood, .wave)
        XCTAssertEqual(EsiePlacement.login.mood, .wave)
        XCTAssertEqual(EsiePlacement.promo.mood, .thumbsUp)
        let view = EsieView(.welcome)
        XCTAssertEqual(view.mood, .wave)
        XCTAssertEqual(view.waveCycles, 3)
        XCTAssertEqual(view.height, 160)
    }

    /// Motion tests' style: the shared motion level decides, the `-reduceMotion YES` flag reaches her.
    func testReducedMotionHoldsAStillPose() {
        func spec(_ motion: Motion, talking: Bool = false) -> EsieSpec {
            EsieView.spec(mood: .rest, talking: talking, waveCycles: nil, looking: false, motion: motion)
        }
        XCTAssertTrue(spec(Motion(level: .full)).animates)
        XCTAssertFalse(spec(Motion(level: .reduced)).animates)
        XCTAssertFalse(spec(Motion(level: MotionLevel.none)).animates)
        XCTAssertFalse(spec(Motion.of(reduceMotion: false, arguments: ["-reduceMotion", "YES"])).animates)
        XCTAssertTrue(spec(Motion(level: .reduced), talking: true).animates, "a talking mouth still swaps")
        // a still pose asks for no frames after the first
        let still = EsieSchedule(spec: spec(Motion(level: .reduced)), origin: Date())
        XCTAssertEqual(Array(still.entries(from: Date(), mode: .normal).prefix(5)).count, 1)
    }

    func testFramesAreCappedAndStopOnceSettled() {
        let now = Date()
        let moving = Array(EsieSchedule(spec: EsieView.spec(mood: .rest, talking: false, waveCycles: nil, looking: false, motion: Motion(level: .full)),
                                        origin: now).entries(from: now, mode: .normal).prefix(3))
        XCTAssertEqual(moving[1].timeIntervalSince(moving[0]), 0.033, accuracy: 0.0005, "30 fps")
        // settled (40 s in, 0 into a blink cycle): the next frame waits for the blink
        let settled = Array(EsieSchedule(spec: EsieView.spec(mood: .rest, talking: false, waveCycles: nil, looking: false, motion: Motion(level: .full)),
                                         origin: now.addingTimeInterval(-40)).entries(from: now, mode: .normal).prefix(2))
        XCTAssertEqual(settled[1].timeIntervalSince(settled[0]), 3.6, accuracy: 0.01)
        // paused (no origin): one frame, then none
        let paused = EsieSchedule(spec: EsieView.spec(mood: .rest, talking: false, waveCycles: nil, looking: false, motion: Motion(level: .full)), origin: nil)
        XCTAssertEqual(Array(paused.entries(from: now, mode: .normal).prefix(3)).count, 1)
    }

    func testFrameCopiesInOneCall() {
        let holder = EsieFrameHolder()
        holder.fill(spec: EsieSpec(mood: .happy, talking: false, waveCycles: nil, looking: false, reduceMotion: false), elapsedMs: 240)
        let stride = Int(EsieFrameBuffer.companion.STRIDE)
        let body = EsieArt.shared.layers.firstIndex { $0.part == "body_fill" }!
        let eye = EsieArt.shared.layers.firstIndex { $0.part == "eye_l_white" }!
        XCTAssertEqual(holder.floats.count, EsieArt.shared.layers.count * stride)
        XCTAssertEqual(holder.floats[body * stride], 1)
        XCTAssertEqual(holder.floats[eye * stride], 0, "hidden while happy")
        XCTAssertLessThan(holder.floats[body * stride + Int(EsieFrameBuffer.companion.DRAW_MATRIX) + 5], -30, "mid hop")
    }

    func testWelcomeLeavesEsieOutAtAccessibilityTextSizes() {
        XCTAssertTrue(WelcomeView.showsEsie(.large))
        XCTAssertTrue(WelcomeView.showsEsie(.xxxLarge))
        XCTAssertFalse(WelcomeView.showsEsie(.accessibility1))
        XCTAssertFalse(WelcomeView.showsEsie(.accessibility5))
    }

    func testWelcomeEsieKeepsTo40PercentOfTheWidth() {
        let viewport = EsieRig.shared.viewport(mood: .wave)
        let aspect = CGFloat(viewport.width / viewport.height)
        let narrow = EsieView.fittedHeight(.welcome, width: 272)
        XCTAssertLessThan(narrow, 160)
        XCTAssertLessThanOrEqual(narrow * aspect, 0.4 * 272 + 0.01)
        XCTAssertEqual(EsieView.fittedHeight(.welcome, width: 2000), 160, "never above the placement's size")
        XCTAssertEqual(EsieMood.thumbsUp.label, "thumbs up")
    }

    func testMoodTables() {
        func shown(_ mood: EsieMood) -> Set<String> { EsieRig.shared.shownParts(spec: EsieSpec(mood: mood, talking: false, waveCycles: nil, looking: false, reduceMotion: false), elapsedMs: 0) }
        XCTAssertTrue(shown(.rest).isSuperset(of: ["fin_rest", "mouth_smile", "eye_l"]))
        XCTAssertTrue(shown(.sad).isSuperset(of: ["lids_sad", "brows_sad", "tear", "mouth_frown"]))
        XCTAssertTrue(shown(.happy).isSuperset(of: ["eyes_happy", "mouth_big", "fin_wave"]))
        XCTAssertFalse(shown(.happy).contains("eye_l"))
        XCTAssertTrue(shown(.thinking).isSuperset(of: ["thought", "brows_think", "mouth_hmm"]))
        XCTAssertTrue(shown(.surprised).isSuperset(of: ["brows_surprised", "mouth_o"]))
        XCTAssertTrue(shown(.thumbsUp).isSuperset(of: ["fin_front", "fin_back"]))
    }

    func testEveryPathConverts() {
        for layer in EsieArt.shared.layers {
            let path = EsieDrawing.path(layer.path)
            XCTAssertFalse(path.isEmpty, layer.part)
            let hull = layer.path.hull
            let box = CGRect(x: CGFloat(hull.left), y: CGFloat(hull.top), width: CGFloat(hull.width), height: CGFloat(hull.height))
            XCTAssertTrue(box.insetBy(dx: -1, dy: -1).contains(path.boundingRect), layer.part)
        }
    }

    /// Draws Esie for real: the muzzle is light, the head is blue, the corners stay clear.
    func testRendersTheArt() throws {
        let renderer = ImageRenderer(content: EsieView(mood: .rest, height: 200))
        renderer.scale = 1
        let image = try XCTUnwrap(renderer.cgImage)
        func pixel(_ fx: Double, _ fy: Double) -> (r: UInt8, g: UInt8, b: UInt8, a: UInt8) {
            let ctx = CGContext(data: nil, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4, space: CGColorSpaceCreateDeviceRGB(),
                                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
            ctx.draw(image, in: CGRect(x: -fx * Double(image.width), y: -(1 - fy) * Double(image.height), width: Double(image.width), height: Double(image.height)))
            let p = ctx.data!.assumingMemoryBound(to: UInt8.self)
            return (p[0], p[1], p[2], p[3])
        }
        let viewport = EsieRig.shared.viewport(mood: .rest)
        func at(_ x: Float, _ y: Float) -> (r: UInt8, g: UInt8, b: UInt8, a: UInt8) {
            pixel(Double((x - viewport.left) / viewport.width), Double((y - viewport.top) / viewport.height))
        }
        let head = at(1150, 420)
        XCTAssertGreaterThan(head.a, 200)
        XCTAssertGreaterThan(head.b, head.r &+ 60, "blue head")
        XCTAssertEqual(pixel(0.01, 0.01).a, 0, "clear corner")
        let muzzle = at(1200, 1150)
        XCTAssertGreaterThan(muzzle.r, 180, "light muzzle")
    }
}
