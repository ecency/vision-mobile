import EcencyCore
import SwiftUI

/// The shared cubic paths of RN's launch animation (start point, then c1, c2, end per segment) as SwiftUI paths.
private enum RevealPaths {
    static func path(_ values: KotlinFloatArray, close: Bool) -> Path {
        var p = Path()
        let v = (0..<values.size).map { CGFloat(values.get(index: $0)) }
        p.move(to: CGPoint(x: v[0], y: v[1]))
        var i = 2
        while i + 5 < v.count {
            p.addCurve(to: CGPoint(x: v[i + 4], y: v[i + 5]), control1: CGPoint(x: v[i], y: v[i + 1]), control2: CGPoint(x: v[i + 2], y: v[i + 3]))
            i += 6
        }
        if close { p.closeSubpath() }
        return p
    }

    static let mark: Path = {
        var p = Path()
        for outline in LaunchReveal.shared.mark { p.addPath(path(outline, close: true)) }
        return p
    }()
    static let upper = path(LaunchReveal.shared.upperArea, close: true)
    static let lower = path(LaunchReveal.shared.lowerArea, close: true)
    static let sweeps: [Path] = LaunchReveal.shared.sweeps.map { path($0.path, close: false) }
}

/// RN's welcome launch animation (`screens/launch`, README "Welcome and terms, register"): the Ecency mark drawn on
/// the page colour over the welcome screen for `LaunchReveal.DURATION_MS`, then [onDone]. It takes the touches under
/// it while it shows (RN's overlay did); VoiceOver skips it, except in `-fakeData` runs, where UI tests find it as
/// `welcome-launch`. Shown only at full motion (the caller checks `LaunchReveal.shows`).
struct LaunchRevealOverlay: View {
    let onDone: () -> Void
    @Environment(\.appTheme) private var theme
    @State private var start = Date()

    var body: some View {
        TimelineView(.animation) { timeline in
            let elapsed = Int64(max(0, timeline.date.timeIntervalSince(start)) * 1000)
            Canvas { context, size in Self.draw(&context, size: size, elapsedMs: elapsed) }
                .frame(width: CGFloat(LaunchReveal.shared.SIZE), height: CGFloat(LaunchReveal.shared.SIZE))
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.background)
        .contentShape(Rectangle())
        .onTapGesture {}
        .accessibilityElement()
        .accessibilityAddTraits(.isImage)
        .accessibilityIdentifier("welcome-launch")
        .accessibilityHidden(!EsieView.exposedForUITests)
        .task {
            try? await Task.sleep(nanoseconds: UInt64(LaunchReveal.shared.DURATION_MS) * 1_000_000)
            onDone()
        }
    }

    static func draw(_ context: inout GraphicsContext, size: CGSize, elapsedMs: Int64) {
        let k = size.width / CGFloat(LaunchReveal.shared.VIEW_BOX)
        context.scaleBy(x: k, y: k)
        let blue = Color(hex: UInt32(truncatingIfNeeded: LaunchReveal.shared.COLOR) & 0xFFFFFF)
        let strokes = LaunchReveal.shared.frame(elapsedMs: elapsedMs)
        for (i, sweep) in LaunchReveal.shared.sweeps.enumerated() {
            let stroke = strokes[i]
            guard stroke.trimEnd > 0, stroke.alpha > 0 else { continue }
            var layer = context
            switch sweep.area {
            case .outsideMark: layer.clip(to: RevealPaths.mark, options: .inverse)
            case .lower: layer.clip(to: RevealPaths.mark); layer.clip(to: RevealPaths.lower)
            default: layer.clip(to: RevealPaths.mark); layer.clip(to: RevealPaths.upper)
            }
            layer.opacity = Double(stroke.alpha)
            layer.stroke(RevealPaths.sweeps[i].trimmedPath(from: 0, to: CGFloat(stroke.trimEnd)), with: .color(blue),
                         style: StrokeStyle(lineWidth: CGFloat(sweep.width), lineCap: .round, lineJoin: .miter))
        }
    }
}
