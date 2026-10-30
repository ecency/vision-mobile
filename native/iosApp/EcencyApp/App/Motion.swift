import EcencyCore
import SwiftUI
import UIKit

/// The motion system on iOS: the shared `MotionTokens` as SwiftUI animations, picked by the motion level the system
/// asks for (`accessibilityReduceMotion`). Screens read `@Environment(\.motion)`; nothing else holds a duration or
/// a curve. README "Motion".
struct Motion: Equatable {
    let level: MotionLevel

    static let full = Motion(level: .full)

    /// The level for the system's reduce motion switch. Debug builds also take `-reduceMotion YES` and
    /// `-disableAnimations YES` (screenshots and UI tests of the reduced path).
    static func of(reduceMotion: Bool, arguments: [String] = ProcessInfo.processInfo.arguments) -> Motion {
        #if DEBUG
        let forcedReduced = Self.flag("-reduceMotion", in: arguments)
        let off = Self.flag("-disableAnimations", in: arguments)
        #else
        let forcedReduced = false
        let off = false
        #endif
        return Motion(level: MotionPolicy.shared.level(reduceMotion: reduceMotion || forcedReduced, durationScale: off ? 0 : 1))
    }

    static func flag(_ name: String, in arguments: [String]) -> Bool {
        guard let i = arguments.firstIndex(of: name), i + 1 < arguments.count else { return false }
        return ["YES", "1", "true"].contains(arguments[i + 1])
    }

    var pops: Bool { MotionPolicy.shared.pops(level: level) }
    var shimmers: Bool { MotionPolicy.shared.shimmers(level: level) }
    var pressExpands: Bool { MotionPolicy.shared.pressExpands(level: level) }
    var isOff: Bool { level == MotionLevel.none }

    /// A colour, opacity or icon crossfade (kept under reduce motion; nil, so no animation, when off).
    func fade(_ ms: Int32 = MotionTokens.shared.SHORT_MS) -> Animation? {
        let millis = MotionPolicy.shared.fadeMillis(level: level, baseMs: ms)
        return millis == 0 ? nil : Self.curve(MotionTokens.shared.STANDARD, millis)
    }

    /// The vote and toggle pop: the overshooting spring at full motion, a plain fade otherwise.
    var pop: Animation? { pops ? Self.spring(MotionTokens.shared.POP) : fade() }

    /// Movement without a bounce.
    var settle: Animation? {
        switch level {
        case .full: return Self.spring(MotionTokens.shared.SETTLE)
        case .reduced: return fade()
        default: return nil
        }
    }

    /// A programmatic scroll (scroll to top, to a quoted message): animated at full motion, a jump otherwise.
    var scroll: Animation? { level == .full ? .default : nil }

    /// A page level change inside a screen (content replacing a skeleton, a segment's list).
    var content: Animation? { fade(MotionTokens.shared.MEDIUM_MS) }

    /// A header above a list closing and opening (profile summary, community Details): slides at full motion,
    /// a short fade when reduced, nothing when off.
    var reveal: Animation? { level == .full ? Self.curve(MotionTokens.shared.STANDARD, MotionTokens.shared.PAGE_MS) : fade(MotionTokens.shared.PAGE_FADE_MS) }

    /// The header's own transition for [reveal]: from the top edge at full motion, a fade otherwise.
    var revealTransition: AnyTransition {
        if isOff { return .identity }
        return level == .full ? .move(edge: .top).combined(with: .opacity) : .opacity
    }

    static func curve(_ e: CubicEasing, _ millis: Int32) -> Animation {
        .timingCurve(Double(e.x1), Double(e.y1), Double(e.x2), Double(e.y2), duration: Double(millis) / 1000)
    }

    static func spring(_ s: SpringToken) -> Animation {
        .spring(response: s.responseSeconds, dampingFraction: Double(s.dampingRatio))
    }

    /// The transition for content that swaps (icons, labels): scale and fade at full motion, fade when reduced.
    var swap: AnyTransition {
        if isOff { return .identity }
        return pops ? .scale(scale: 0.6).combined(with: .opacity) : .opacity
    }
}

private struct MotionKey: EnvironmentKey {
    static let defaultValue = Motion.full
}

extension EnvironmentValues {
    var motion: Motion {
        get { self[MotionKey.self] }
        set { self[MotionKey.self] = newValue }
    }
}

/// Puts the system's motion level into the environment (at the app root, next to the theme).
struct MotionRoot: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        let motion = Motion.of(reduceMotion: reduceMotion)
        content
            .environment(\.motion, motion)
            .transaction { if motion.isOff { $0.disablesAnimations = true } }
    }
}

extension View {
    /// The motion level from the system (once, at the root).
    func motionRoot() -> some View { modifier(MotionRoot()) }

    /// Animates [value] changes with the motion system's [animation] (nil: none).
    func motion<V: Equatable>(_ animation: Animation?, value: V) -> some View {
        self.animation(animation, value: value)
    }
}

// MARK: - Pops and shakes

/// Pops (grows past full size and springs back) when [trigger] changes and [enabled] holds: a vote fill, a toggled
/// favourite, a selected tab. A transform only, so the row never moves; nothing under reduce motion.
struct PopOnChange<T: Equatable>: ViewModifier {
    let trigger: T
    var enabled = true
    @Environment(\.motion) private var motion
    @State private var scale: CGFloat = 1

    func body(content: Content) -> some View {
        content
            .scaleEffect(scale)
            .onChange(of: trigger) { _, _ in
                guard enabled, motion.pops else { return }
                withAnimation(.easeOut(duration: Double(MotionTokens.shared.PRESS_MS) / 1000)) { scale = CGFloat(MotionTokens.shared.POP_SCALE) }
                DispatchQueue.main.asyncAfter(deadline: .now() + Double(MotionTokens.shared.PRESS_MS) / 1000) {
                    withAnimation(Motion.spring(MotionTokens.shared.POP)) { scale = 1 }
                }
            }
    }
}

/// The wrong PIN shake: the shared keyframes over `SHAKE_MS`, a horizontal offset only.
struct ShakeEffect: GeometryEffect {
    /// 0 at rest; each whole unit is one full shake.
    var progress: CGFloat
    let frames: [CGFloat]
    let amplitude: CGFloat

    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    func effectValue(size: CGSize) -> ProjectionTransform {
        ProjectionTransform(CGAffineTransform(translationX: Self.offset(progress: progress, frames: frames) * amplitude, y: 0))
    }

    /// The offset (in units of the amplitude) at [progress]: the keyframes linearly, repeating per whole unit.
    static func offset(progress: CGFloat, frames: [CGFloat]) -> CGFloat {
        guard frames.count > 1 else { return 0 }
        let t = progress - progress.rounded(.down)
        if t == 0 { return 0 }
        let position = t * CGFloat(frames.count - 1)
        let i = min(Int(position), frames.count - 2)
        let f = position - CGFloat(i)
        return frames[i] + (frames[i + 1] - frames[i]) * f
    }
}

extension View {
    func popOnChange<T: Equatable>(_ trigger: T, enabled: Bool = true) -> some View {
        modifier(PopOnChange(trigger: trigger, enabled: enabled))
    }

    /// Shakes once per increase of [count] (full motion only).
    func shake(count: Int, motion: Motion) -> some View {
        modifier(ShakeEffect(
            progress: motion.pops ? CGFloat(count) : 0,
            frames: MotionPolicy.shared.shakeKeyframes(cycles: MotionTokens.shared.SHAKE_CYCLES).map { CGFloat(truncating: $0) },
            amplitude: CGFloat(MotionTokens.shared.SHAKE_DP)
        ))
        .animation(motion.pops ? .linear(duration: Double(MotionTokens.shared.SHAKE_MS) / 1000) : nil, value: count)
    }
}

// MARK: - Haptics

extension HapticEvent {
    /// The SwiftUI feedback for this event (light, system standard).
    var feedback: SensoryFeedback {
        switch MotionPolicy.shared.haptic(event: self) {
        case .success: return .success
        case .error: return .error
        case .selection: return .selection
        default: return .impact(weight: .light)
        }
    }
}

extension View {
    /// Plays the haptic for [event] when [trigger] changes and [condition] holds for the new value.
    func haptic<T: Equatable>(_ event: HapticEvent, trigger: T, when condition: @escaping (T) -> Bool = { _ in true }) -> some View {
        sensoryFeedback(event.feedback, trigger: trigger) { _, new in condition(new) }
    }
}

/// Haptics from code that is not a view (a menu action, a copy): a UIKit generator, as `sensoryFeedback` plays them.
enum Haptics {
    /// Test hook: every haptic asked for, in order.
    static var probe: ((HapticEvent) -> Void)?

    @MainActor static func play(_ event: HapticEvent) {
        probe?(event)
        switch MotionPolicy.shared.haptic(event: event) {
        case .success: UINotificationFeedbackGenerator().notificationOccurred(.success)
        case .error: UINotificationFeedbackGenerator().notificationOccurred(.error)
        case .selection: UISelectionFeedbackGenerator().selectionChanged()
        default: UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
    }
}

// MARK: - Pressed state from the touch point

/// Where the last touch went down, in window coordinates (every window of the app fills the screen, so these are
/// the global coordinates SwiftUI reports): one observing recognizer on the key window that never
/// takes a touch (it fails at once and cancels nothing), so scrolling and every control behave as before.
@MainActor final class TouchPoint: NSObject, UIGestureRecognizerDelegate {
    static let shared = TouchPoint()
    private(set) var lastDown: CGPoint?
    /// Every window with an observer (the app, the lock and the overlay windows), held weakly.
    private let windows = NSHashTable<UIWindow>.weakObjects()

    func attach(to window: UIWindow?) {
        guard let window, !windows.contains(window) else { return }
        windows.add(window)
        let recognizer = Observer(target: nil, action: nil)
        recognizer.onDown = { [weak self] point in self?.lastDown = point }
        recognizer.cancelsTouchesInView = false
        recognizer.delaysTouchesBegan = false
        recognizer.delaysTouchesEnded = false
        recognizer.delegate = self
        window.addGestureRecognizer(recognizer)
    }

    nonisolated func gestureRecognizer(_ g: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool { true }

    private final class Observer: UIGestureRecognizer {
        var onDown: ((CGPoint) -> Void)?

        override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent) {
            if let touch = touches.first { onDown?(touch.location(in: nil)) }
            state = .failed
        }
    }
}

/// Attaches [TouchPoint] to the window this view lives in.
struct TouchPointAttacher: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView { Probe() }
    func updateUIView(_ uiView: UIView, context: Context) {}

    private final class Probe: UIView {
        override func didMoveToWindow() {
            super.didMoveToWindow()
            isUserInteractionEnabled = false
            MainActor.assumeIsolated { TouchPoint.shared.attach(to: window) }
        }
    }
}

/// Cards and list rows: while pressed, a soft tint grows from the touch point and the row sinks a little; on
/// release it fades. Reduce motion keeps a plain dim; animations off, a dim without animation. Only the label's
/// transform and an overlay change: the layout never moves, and the action runs on release as with `.plain`.
struct PressableStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        PressableBody(configuration: configuration)
    }

    private struct PressableBody: View {
        let configuration: Configuration
        @Environment(\.motion) private var motion
        @Environment(\.appTheme) private var theme
        @State private var origin: CGPoint?

        var body: some View {
            let pressed = configuration.isPressed
            configuration.label
                .scaleEffect(pressed && motion.pops ? CGFloat(MotionTokens.shared.PRESS_SCALE) : 1)
                .overlay {
                    GeometryReader { geo in
                        let frame = geo.frame(in: .global)
                        let radius = hypot(geo.size.width, geo.size.height)
                        let local = origin.map { CGPoint(x: $0.x - frame.minX, y: $0.y - frame.minY) }
                            ?? CGPoint(x: geo.size.width / 2, y: geo.size.height / 2)
                        Circle()
                            .fill(theme.text.opacity(Double(MotionTokens.shared.PRESS_DIM)))
                            .frame(width: radius * 2, height: radius * 2)
                            .scaleEffect(pressed || !motion.pressExpands ? 1 : 0.05)
                            .position(local)
                            .opacity(pressed ? 1 : 0)
                            .onChange(of: pressed) { _, now in
                                if now { origin = TouchPoint.shared.lastDown.flatMap { frame.contains($0) ? $0 : nil } }
                            }
                    }
                    .clipped()
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
                }
                .animation(pressed ? motion.fade(MotionTokens.shared.MEDIUM_MS) : motion.fade(MotionTokens.shared.SHORT_MS), value: pressed)
        }
    }
}

/// A control that sinks while pressed and springs back (the create button). Full motion only; the action runs on
/// release as with `.plain`.
struct PressScaleStyle: ButtonStyle {
    var scale: CGFloat = CGFloat(MotionTokens.shared.PRESS_SCALE)

    func makeBody(configuration: Configuration) -> some View {
        ScaleBody(configuration: configuration, scale: scale)
    }

    private struct ScaleBody: View {
        let configuration: Configuration
        let scale: CGFloat
        @Environment(\.motion) private var motion

        var body: some View {
            configuration.label
                .scaleEffect(configuration.isPressed && motion.pops ? scale : 1)
                .opacity(configuration.isPressed && !motion.pops ? 0.8 : 1)
                .animation(configuration.isPressed ? motion.fade(MotionTokens.shared.PRESS_MS) : motion.settle, value: configuration.isPressed)
        }
    }
}

// MARK: - Shimmer

/// The one skeleton (moving gradient) for loading states whose layout is known. The bones draw the placeholder
/// colour; at full motion a highlight band sweeps across, driven by a `TimelineView` that pauses off screen (the
/// container tracks its own appearance) and under reduce motion. Spinners stay for unknown sizes and blocking work.
struct Skeleton<Content: View>: View {
    var identifier = "skeleton"
    var label = ShellUiCopy.shared.LOADING
    @ViewBuilder let content: () -> Content
    @Environment(\.motion) private var motion
    @State private var visible = false

    var body: some View {
        let active = motion.shimmers && visible
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !active)) { context in
            let phase = active ? Self.phase(at: context.date) : nil
            content().environment(\.shimmerPhase, phase)
        }
        .onAppear { visible = true }
        .onDisappear { visible = false }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityIdentifier(identifier)
    }

    /// 0...1 through one sweep of `SHIMMER_MS`.
    static func phase(at date: Date) -> CGFloat {
        let period = Double(MotionTokens.shared.SHIMMER_MS) / 1000
        return CGFloat(date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: period) / period)
    }
}

private struct ShimmerPhaseKey: EnvironmentKey {
    static let defaultValue: CGFloat? = nil
}

extension EnvironmentValues {
    /// The shimmer's sweep position for bones (nil: still).
    var shimmerPhase: CGFloat? {
        get { self[ShimmerPhaseKey.self] }
        set { self[ShimmerPhaseKey.self] = newValue }
    }
}

/// One placeholder shape of a [Skeleton].
struct Bone: View {
    var width: CGFloat? = nil
    var height: CGFloat = 12
    var corner: CGFloat = 4
    var circle = false
    @Environment(\.appTheme) private var theme
    @Environment(\.shimmerPhase) private var phase
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: circle ? height / 2 : corner)
        shape
            .fill(theme.lightBackground)
            .overlay {
                if let phase {
                    GeometryReader { geo in
                        let band = max(geo.size.width, 120)
                        let x = -band + (geo.size.width + 2 * band) * phase
                        LinearGradient(colors: [.clear, .white.opacity(scheme == .dark ? 0.08 : 0.9), .clear],
                                       startPoint: .leading, endPoint: .trailing)
                            .frame(width: band)
                            .offset(x: x - band / 2)
                    }
                    .clipShape(shape)
                }
            }
            .frame(width: circle ? height : width, height: height)
            .frame(maxWidth: width == nil && !circle ? .infinity : nil, alignment: .leading)
    }
}

/// A feed card while the first page loads: header, 16:9 image, title, two summary lines, actions.
struct FeedCardSkeleton: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                Bone(height: 44, circle: true)
                VStack(alignment: .leading, spacing: 6) {
                    Bone(width: 110, height: 14)
                    Bone(width: 160, height: 12)
                }
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 12)
            RoundedRectangle(cornerRadius: 8).fill(.clear)
                .aspectRatio(16 / 9, contentMode: .fit)
                .overlay { GeometryReader { geo in Bone(height: geo.size.height, corner: 8) } }
                .padding(.horizontal, 9)
            VStack(alignment: .leading, spacing: 8) {
                Bone(width: 260, height: 18)
                Bone(height: 12)
                Bone(width: 200, height: 12)
            }
            .padding(.horizontal, 17)
            .padding(.top, 12)
            HStack(spacing: 12) {
                Bone(height: 26, circle: true)
                Bone(width: 48, height: 12)
            }
            .padding(16)
        }
        .padding(.top, 10)
    }
}

/// Feed skeleton: [count] cards under one shimmer clock.
struct FeedSkeleton: View {
    var count = 3
    var body: some View {
        Skeleton(identifier: "skeleton-feed") { VStack(spacing: 0) { ForEach(0..<count, id: \.self) { _ in FeedCardSkeleton() } } }
    }
}

/// The post body while it renders: lines, an image, lines.
struct PostBodySkeleton: View {
    var body: some View {
        Skeleton(identifier: "skeleton-body") {
            VStack(alignment: .leading, spacing: 10) {
                Bone(height: 14)
                Bone(height: 14)
                Bone(width: 280, height: 14)
                Bone(width: 180, height: 14)
                RoundedRectangle(cornerRadius: 8).fill(.clear)
                    .aspectRatio(16 / 9, contentMode: .fit)
                    .overlay { GeometryReader { geo in Bone(height: geo.size.height, corner: 8) } }
                Bone(height: 14)
                Bone(width: 240, height: 14)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
    }
}

/// The profile summary while the account loads: cover, avatar, name, bio lines, counts.
struct ProfileHeaderSkeleton: View {
    var body: some View {
        Skeleton(identifier: "skeleton-profile") {
            VStack(alignment: .leading, spacing: 10) {
                ZStack(alignment: .bottomLeading) {
                    Bone(height: 160, corner: 0)
                    Bone(height: 80, circle: true).padding(.leading, 16).offset(y: 40)
                }
                .padding(.bottom, 40)
                VStack(alignment: .leading, spacing: 10) {
                    Bone(width: 160, height: 18)
                    Bone(width: 110, height: 12)
                    Bone(height: 12)
                    Bone(width: 240, height: 12)
                    HStack(spacing: 24) { Bone(width: 70, height: 14); Bone(width: 70, height: 14) }
                }
                .padding(.horizontal, 16)
            }
        }
    }
}

/// List rows while a list loads (notifications, chats, wallet): a leading circle (or tile), two lines, a value.
struct RowsSkeleton: View {
    var count = 6
    var avatar = true
    var identifier = "skeleton-rows"

    var body: some View {
        Skeleton(identifier: identifier) {
            VStack(spacing: 0) {
                ForEach(0..<count, id: \.self) { _ in
                    HStack(spacing: 12) {
                        if avatar { Bone(height: 44, circle: true) } else { Bone(width: 36, height: 36, corner: 8) }
                        VStack(alignment: .leading, spacing: 6) {
                            Bone(width: 150, height: 14)
                            Bone(height: 12)
                        }
                        Bone(width: 48, height: 14)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                }
            }
        }
    }
}

// MARK: - Send buttons

/// What a send button shows: its label, a spinner while sending, a tick once sent.
enum SendButtonPhase: Equatable { case idle, sending, done }

/// The send button's phase: a spinner while sending, and a success only when the model's sent signal changes to a
/// new non-nil value (chat's sent version, a wave's sent key). The end of sending alone means nothing: a failed send,
/// a cancelled signing, an authority prompt or the chat send bound all end it too. Where the composer stays open
/// (chat) a success shows a tick for `DONE_HOLD_MS`. Pure, unit tested.
struct SendPhaseTracker: Equatable {
    private(set) var phase: SendButtonPhase = .idle
    private var lastSent: AnyHashable?

    /// What the sent signal is when the button appears: a value already there is no new success.
    init(sent: AnyHashable? = nil) { lastSent = sent }

    /// The model changed; returns true when a send just succeeded.
    mutating func update(sending: Bool, sent: AnyHashable?, holdsDone: Bool) -> Bool {
        let succeeded = sent != nil && sent != lastSent
        lastSent = sent
        if sending {
            phase = .sending
        } else if succeeded {
            phase = holdsDone ? .done : .idle
        } else if phase == .sending {
            phase = .idle
        }
        return succeeded
    }

    /// The tick's hold ended (or the user started typing a new message).
    mutating func clearDone() { if phase == .done { phase = .idle } }
}

/// A send button's content: [label], a spinner, or a tick, swapped with the motion system's transition.
struct SendContent<Label: View>: View {
    let sending: Bool
    /// The model's positive success signal; see `SendPhaseTracker`.
    let sent: AnyHashable?
    var holdsDone = true
    var tint: Color = .white
    @ViewBuilder let label: () -> Label
    @Environment(\.motion) private var motion
    @State private var tracker: SendPhaseTracker
    @State private var successes = 0

    init(sending: Bool, sent: AnyHashable?, holdsDone: Bool = true, tint: Color = .white, @ViewBuilder label: @escaping () -> Label) {
        self.sending = sending
        self.sent = sent
        self.holdsDone = holdsDone
        self.tint = tint
        self.label = label
        _tracker = State(initialValue: SendPhaseTracker(sent: sent))
    }

    var body: some View {
        ZStack {
            switch tracker.phase {
            case .sending: ProgressView().tint(tint).controlSize(.small).transition(motion.swap)
            case .done:
                Image(appIcon: .check).scaledFont(15, weight: .bold).foregroundStyle(tint)
                    .accessibilityLabel(ShellUiCopy.shared.SENT).transition(motion.swap)
            case .idle: label().transition(motion.swap)
            }
        }
        .animation(motion.pop, value: tracker.phase)
        .onAppear { _ = tracker.update(sending: sending, sent: sent, holdsDone: holdsDone) }
        .onChange(of: sending) { _, _ in changed() }
        .onChange(of: sent) { _, _ in changed() }
        .haptic(.success, trigger: successes)
    }

    private func changed() {
        guard tracker.update(sending: sending, sent: sent, holdsDone: holdsDone) else { return }
        successes += 1
        if holdsDone {
            DispatchQueue.main.asyncAfter(deadline: .now() + Double(MotionTokens.shared.DONE_HOLD_MS) / 1000) { tracker.clearDone() }
        }
    }
}

// MARK: - Zoom navigation (iOS 18), the system push on iOS 17

private struct HeroNamespaceKey: EnvironmentKey {
    static let defaultValue: Namespace.ID? = nil
}

extension EnvironmentValues {
    /// The navigation stack's namespace for zoom transitions (set by the root).
    var heroNamespace: Namespace.ID? {
        get { self[HeroNamespaceKey.self] }
        set { self[HeroNamespaceKey.self] = newValue }
    }
}

extension View {
    /// Marks this view as where a zoom navigation starts (iOS 18; nothing on iOS 17 or without a namespace).
    @ViewBuilder func heroSource(_ id: String) -> some View {
        modifier(HeroSourceModifier(id: id))
    }

    /// The pushed screen zooms out of its source (iOS 18) when one was recorded for it; otherwise, and on iOS 17
    /// or under reduce motion, the system push with its interactive swipe back.
    @ViewBuilder func heroDestination(_ id: String?, namespace: Namespace.ID, motion: Motion) -> some View {
        if #available(iOS 18.0, *), let id, motion.pops {
            self.navigationTransition(.zoom(sourceID: id, in: namespace))
        } else {
            self
        }
    }
}

private struct HeroSourceModifier: ViewModifier {
    let id: String
    @Environment(\.heroNamespace) private var namespace

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *), let namespace {
            content.matchedTransitionSource(id: id, in: namespace)
        } else {
            content
        }
    }
}

// MARK: - Interactive swipe back with custom headers

/// Screens with their own header hide the navigation bar (`.toolbar(.hidden, for: .navigationBar)`: the chat thread,
/// the dApp browser, the QR scanner), and UIKit then refuses the edge swipe back. This keeps the system push and
/// its interactive pop for them: the pop gesture begins whenever a screen is above the root, no transition is
/// running and the top screen did not block it on purpose with `.blocksBack(_:)` (a purchase or transfer in
/// progress, the editor with its own back). The block is an explicit flag on the screen's own controller: SwiftUI
/// does set `hidesBackButton` for a hidden back button today (and UIKit then refuses the swipe), but that is not a
/// documented contract, so the rule does not rely on it alone.
@MainActor final class SwipeBackPolicy: NSObject, UIGestureRecognizerDelegate {
    static let shared = SwipeBackPolicy()
    private weak var navigation: UINavigationController?
    /// The stack screens (the navigation controller's children) that block the swipe now; weak, so a popped screen leaves.
    private let blocked = NSHashTable<UIViewController>.weakObjects()

    func setBlocked(_ screen: UIViewController, _ isBlocked: Bool) {
        if isBlocked { blocked.add(screen) } else { blocked.remove(screen) }
    }

    /// The screen's own explicit block (`.blocksBack`), while it is on the stack.
    func marks(_ screen: UIViewController) -> Bool {
        blocked.contains(screen) && screen.parent is UINavigationController
    }

    /// The explicit block, or a back button hidden on the screen's item (SwiftUI's `.navigationBarBackButtonHidden`
    /// sets it on the hosting controller, seen in MotionTests; the explicit flag keeps the rule from relying on that).
    func blocks(_ screen: UIViewController?) -> Bool {
        guard let screen, screen.parent is UINavigationController else { return false }
        return marks(screen) || screen.navigationItem.hidesBackButton
    }

    func attach(_ navigation: UINavigationController?) {
        guard let navigation, navigation !== self.navigation else { return }
        self.navigation = navigation
        navigation.interactivePopGestureRecognizer?.delegate = self
    }

    /// The rule, pure for tests.
    nonisolated static func allows(depth: Int, transitioning: Bool, backHidden: Bool) -> Bool {
        depth > 1 && !transitioning && !backHidden
    }

    nonisolated func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        MainActor.assumeIsolated {
            guard let nav = navigation else { return false }
            return Self.allows(depth: nav.viewControllers.count, transitioning: nav.transitionCoordinator != nil,
                               backHidden: blocks(nav.topViewController))
        }
    }
}

extension View {
    /// Hides the back button and blocks the edge swipe back while [blocked] holds (a screen with its own guarded
    /// back: the editor, a transfer or purchase in progress).
    func blocksBack(_ blocked: Bool) -> some View {
        navigationBarBackButtonHidden(blocked)
            .background(SwipeBackBlocker(blocked: blocked).frame(width: 0, height: 0).accessibilityHidden(true))
    }
}

/// Marks the stack screen this view is on as blocking the swipe back ([SwipeBackPolicy]).
struct SwipeBackBlocker: UIViewControllerRepresentable {
    let blocked: Bool

    func makeUIViewController(context: Context) -> Probe { Probe() }
    func updateUIViewController(_ controller: Probe, context: Context) {
        controller.blocked = blocked
        controller.apply()
    }

    final class Probe: UIViewController {
        var blocked = false
        private weak var marked: UIViewController?

        override func viewDidLoad() {
            super.viewDidLoad()
            view.isUserInteractionEnabled = false
        }

        override func didMove(toParent parent: UIViewController?) {
            super.didMove(toParent: parent)
            apply()
        }

        override func viewWillAppear(_ animated: Bool) {
            super.viewWillAppear(animated)
            apply()
        }

        override func viewDidDisappear(_ animated: Bool) {
            super.viewDidDisappear(animated)
            // the screen left the stack: it blocks nothing any more
            if let screen = marked, !(screen.parent is UINavigationController) {
                SwipeBackPolicy.shared.setBlocked(screen, false)
                marked = nil
            }
        }

        /// The ancestor that is a child of the navigation controller: the screen as the stack knows it.
        private var stackScreen: UIViewController? {
            var current: UIViewController = self
            while let parent = current.parent {
                if parent is UINavigationController { return current }
                current = parent
            }
            return nil
        }

        func apply() {
            let screen = stackScreen ?? marked
            if let old = marked, old !== screen { SwipeBackPolicy.shared.setBlocked(old, false) }
            marked = screen
            if let screen { SwipeBackPolicy.shared.setBlocked(screen, blocked) }
        }
    }
}

/// Finds the navigation controller of the stack this view is in and applies [SwipeBackPolicy].
struct SwipeBackEnabler: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { Probe() }
    func updateUIViewController(_ controller: UIViewController, context: Context) {
        SwipeBackPolicy.shared.attach(controller.navigationController)
    }

    private final class Probe: UIViewController {
        override func viewDidLoad() {
            super.viewDidLoad()
            view.isUserInteractionEnabled = false
        }

        override func didMove(toParent parent: UIViewController?) {
            super.didMove(toParent: parent)
            SwipeBackPolicy.shared.attach(navigationController)
        }

        override func viewWillAppear(_ animated: Bool) {
            super.viewWillAppear(animated)
            SwipeBackPolicy.shared.attach(navigationController)
        }
    }
}
