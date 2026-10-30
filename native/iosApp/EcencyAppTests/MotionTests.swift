import EcencyCore
import SwiftUI
import XCTest
@testable import EcencyApp

/// The iOS motion system: tokens as animations, the reduce motion choice, the send button's phases, the shake
/// keyframes, the swipe back rule and the vote symbols.
@MainActor
final class MotionTests: XCTestCase {
    func testLevelFollowsReduceMotionAndTheDebugFlags() {
        XCTAssertEqual(Motion.of(reduceMotion: false, arguments: []).level, MotionLevel.full)
        XCTAssertEqual(Motion.of(reduceMotion: true, arguments: []).level, MotionLevel.reduced)
        XCTAssertEqual(Motion.of(reduceMotion: false, arguments: ["-reduceMotion", "YES"]).level, MotionLevel.reduced)
        XCTAssertEqual(Motion.of(reduceMotion: false, arguments: ["-disableAnimations", "YES"]).level, MotionLevel.none)
        XCTAssertEqual(Motion.of(reduceMotion: false, arguments: ["-reduceMotion", "NO"]).level, MotionLevel.full)
        XCTAssertEqual(Motion.of(reduceMotion: false, arguments: ["-reduceMotion"]).level, MotionLevel.full, "a flag without a value")
    }

    func testReducedMotionKeepsFadesAndDropsPops() {
        let full = Motion(level: .full), reduced = Motion(level: .reduced), off = Motion(level: MotionLevel.none)
        XCTAssertTrue(full.pops && full.shimmers && full.pressExpands)
        XCTAssertFalse(reduced.pops || reduced.shimmers || reduced.pressExpands)
        XCTAssertNotNil(reduced.fade(), "fades stay under reduce motion")
        XCTAssertNotNil(reduced.pop, "a pop becomes a fade")
        XCTAssertNil(off.fade())
        XCTAssertNil(off.pop)
        XCTAssertNil(off.settle)
        XCTAssertTrue(off.isOff)
    }

    func testSpringsComeFromTheSharedTokens() {
        // SwiftUI's response for the POP spring is the shared 2 pi / sqrt(k)
        XCTAssertEqual(MotionTokens.shared.POP.responseSeconds, 2 * Double.pi / sqrt(500), accuracy: 1e-9)
        XCTAssertLessThan(MotionTokens.shared.SETTLE.responseSeconds, MotionTokens.shared.POP.responseSeconds)
    }

    func testSendGoesThroughProgressToDone() {
        // chat: the sent version was 2 when the screen opened, which is no success
        var t = SendPhaseTracker(sent: AnyHashable(2))
        XCTAssertFalse(t.update(sending: false, sent: AnyHashable(2), holdsDone: true))
        XCTAssertEqual(t.phase, .idle)
        XCTAssertFalse(t.update(sending: true, sent: AnyHashable(2), holdsDone: true))
        XCTAssertEqual(t.phase, .sending)
        XCTAssertTrue(t.update(sending: false, sent: AnyHashable(3), holdsDone: true), "the server took it")
        XCTAssertEqual(t.phase, .done)
        t.clearDone()
        XCTAssertEqual(t.phase, .idle)
        // a failed send, or the 30 s bound freeing the button: sending ends with nothing sent
        _ = t.update(sending: true, sent: AnyHashable(3), holdsDone: true)
        XCTAssertFalse(t.update(sending: false, sent: AnyHashable(3), holdsDone: true))
        XCTAssertEqual(t.phase, .idle)
        // the slow send's answer after the bound is the success
        XCTAssertTrue(t.update(sending: false, sent: AnyHashable(4), holdsDone: true))
        XCTAssertEqual(t.phase, .done)
        // a new send during the tick ends it
        _ = t.update(sending: true, sent: AnyHashable(4), holdsDone: true)
        XCTAssertEqual(t.phase, .sending)
    }

    func testASheetSendThatEndsWithoutBeingSentIsNoSuccess() {
        // a wave: cancelled signing, the authority prompt, the RC offer all end sending with no sent key
        var t = SendPhaseTracker()
        _ = t.update(sending: true, sent: nil, holdsDone: false)
        XCTAssertFalse(t.update(sending: false, sent: nil, holdsDone: false))
        XCTAssertEqual(t.phase, .idle)
        _ = t.update(sending: true, sent: nil, holdsDone: false)
        XCTAssertTrue(t.update(sending: false, sent: AnyHashable("spike-zed/wave-1"), holdsDone: false))
        XCTAssertEqual(t.phase, .idle, "the composer resets: no tick")
        // the next edit clears the key; the same key again later is a new success
        XCTAssertFalse(t.update(sending: false, sent: nil, holdsDone: false))
        XCTAssertTrue(t.update(sending: false, sent: AnyHashable("spike-zed/wave-1"), holdsDone: false))
    }

    func testShakeFollowsTheSharedKeyframesAndRestsOnWholeUnits() {
        let frames = MotionPolicy.shared.shakeKeyframes(cycles: 3).map { CGFloat(truncating: $0) }
        XCTAssertEqual(ShakeEffect.offset(progress: 0, frames: frames), 0)
        XCTAssertEqual(ShakeEffect.offset(progress: 1, frames: frames), 0, "at rest after one shake")
        XCTAssertEqual(ShakeEffect.offset(progress: 1.0 / 7, frames: frames), -1, accuracy: 1e-6)
        XCTAssertEqual(ShakeEffect.offset(progress: 2.0 / 7, frames: frames), 1, accuracy: 1e-6)
        XCTAssertEqual(ShakeEffect.offset(progress: 1.5 / 7, frames: frames), 0, accuracy: 1e-6)
        XCTAssertEqual(ShakeEffect.offset(progress: 0.5, frames: [0]), 0)
    }

    func testSwipeBackRule() {
        XCTAssertTrue(SwipeBackPolicy.allows(depth: 2, transitioning: false, backHidden: false))
        XCTAssertFalse(SwipeBackPolicy.allows(depth: 1, transitioning: false, backHidden: false), "nothing to pop at the root")
        XCTAssertFalse(SwipeBackPolicy.allows(depth: 3, transitioning: true, backHidden: false))
        XCTAssertFalse(SwipeBackPolicy.allows(depth: 2, transitioning: false, backHidden: true), "a screen that hid back on purpose")
    }

    func testABlockedTopScreenRefusesTheSwipeBack() {
        let below = UIViewController(), top = UIViewController()
        let nav = UINavigationController()
        nav.setViewControllers([below, top], animated: false)
        nav.loadViewIfNeeded()
        let policy = SwipeBackPolicy.shared
        policy.attach(nav)
        guard let pop = nav.interactivePopGestureRecognizer else { return XCTFail("no pop gesture") }
        XCTAssertTrue(policy.gestureRecognizerShouldBegin(pop))
        policy.setBlocked(top, true)
        XCTAssertFalse(policy.gestureRecognizerShouldBegin(pop), "the editor, a busy transfer or purchase")
        policy.setBlocked(below, true)
        policy.setBlocked(top, false)
        XCTAssertTrue(policy.gestureRecognizerShouldBegin(pop), "only the top screen's block counts")
        policy.setBlocked(below, false)
    }

    func testBlocksBackMarksThePushedScreenItIsOn() {
        let flag = BlockFlag()
        let host = UIHostingController(rootView: BlockingScreen(flag: flag))
        let nav = UINavigationController(rootViewController: UIViewController())
        // a window on the test host's scene: one without a scene never draws, and SwiftUI never makes the probe
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return XCTFail("no scene") }
        let window = UIWindow(windowScene: scene)
        window.rootViewController = nav
        window.makeKeyAndVisible()
        defer { window.isHidden = true }
        nav.pushViewController(host, animated: false)
        let policy = SwipeBackPolicy.shared
        // the explicit flag on the stack controller (not only the hidden back button SwiftUI maps today)
        XCTAssertTrue(spin { policy.marks(host) }, "the pushed screen with .blocksBack(true) is marked: \(host.children)")
        XCTAssertTrue(policy.blocks(host))
        flag.blocked = false
        XCTAssertTrue(spin { !policy.marks(host) && !policy.blocks(host) }, ".blocksBack(false) lets it go")
        flag.blocked = true
        XCTAssertTrue(spin { policy.marks(host) })
        nav.popViewController(animated: false)
        // (holds through the stack check in marks/blocks; the Probe's viewDidDisappear unmark is not what this exercises)
        XCTAssertTrue(spin { !policy.marks(host) && !policy.blocks(host) }, "a screen that left the stack blocks nothing")
    }

    /// Runs the main loop until [condition] holds (or 3 s).
    private func spin(_ condition: () -> Bool) -> Bool {
        let end = Date().addingTimeInterval(3)
        while !condition() && Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
        return condition()
    }

    func testVoteSymbolsSwapInOneImage() {
        XCTAssertEqual(VoteButton.symbol(.idle), "chevron.up.circle")
        XCTAssertEqual(VoteButton.symbol(.inFlight), "chevron.up.circle")
        XCTAssertEqual(VoteButton.symbol(.upvoted), "chevron.up.circle.fill")
        XCTAssertEqual(VoteButton.symbol(.downvoted), "chevron.down.circle.fill")
        XCTAssertEqual(VoteButton.symbol(.failed(message: "x", retryable: true)), "exclamationmark.circle")
    }

    func testShimmerPhaseStaysInOneSweep() {
        let period = Double(MotionTokens.shared.SHIMMER_MS) / 1000
        for offset in [0.0, 0.3, period - 0.001, period, period * 2.5] {
            let p = Skeleton<EmptyView>.phase(at: Date(timeIntervalSinceReferenceDate: offset))
            XCTAssertGreaterThanOrEqual(p, 0)
            XCTAssertLessThan(p, 1)
        }
    }

    func testMenuTogglesAreTheTwoStateItems() {
        XCTAssertTrue(MenuToggles.contains(.bookmarks))
        XCTAssertTrue(MenuToggles.contains(.reblog))
        XCTAssertTrue(MenuToggles.contains(.undoReblog))
        XCTAssertFalse(MenuToggles.contains(.theCopy))
    }

    func testHapticsMapToLightSystemFeedback() {
        XCTAssertEqual(HapticEvent.success.feedback, .success)
        XCTAssertEqual(HapticEvent.error.feedback, .error)
        XCTAssertEqual(HapticEvent.selection.feedback, .selection)
        XCTAssertEqual(HapticEvent.vote.feedback, .impact(weight: .light))
        var played: [HapticEvent] = []
        Haptics.probe = { played.append($0) }
        defer { Haptics.probe = nil }
        Haptics.play(.toggle)
        XCTAssertEqual(played, [.toggle])
    }
}

private final class BlockFlag: ObservableObject {
    @Published var blocked = true
}

private struct BlockingScreen: View {
    @ObservedObject var flag: BlockFlag
    var body: some View { Text("guarded").blocksBack(flag.blocked) }
}
