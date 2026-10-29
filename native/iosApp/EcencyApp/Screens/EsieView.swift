import EcencyCore
import SwiftUI

/// Esie, drawn from the shared vector art (`EsieArt`) and animated by the shared rig (`EsieRig`); README "Esie".
///
/// The height sets the size; the width follows the mood's framing. Decorative by default (hidden from VoiceOver, the
/// text next to it carries the message); a `label` makes it an image with that description. Frames come at most 30 a
/// second, only while the view is on screen and the app is active, and stop once she settles (`EsieRig.SETTLE_MS`,
/// then only blinks). Under reduced motion (`\.motion`, which also takes the `-reduceMotion YES` debug flag) she holds
/// a still pose; a talking mouth still swaps on its own beat.
struct EsieView: View {
    let mood: EsieMood
    var height: CGFloat = 120
    var talking = false
    var waveCycles: Int?
    var looking = false
    var label: String?

    @Environment(\.motion) private var motion
    @Environment(\.scenePhase) private var scenePhase
    /// Time shown before the current run, and when the current run started (nil while paused).
    @State private var accumulated: TimeInterval = 0
    @State private var resumedAt: Date?
    @State private var onScreen = false
    /// Created once per view (not on every parent rebuild, as a @State default would be).
    @StateObject private var frame = EsieFrameHolder()

    init(mood: EsieMood, height: CGFloat = 120, talking: Bool = false, waveCycles: Int? = nil, looking: Bool = false, label: String? = nil) {
        self.mood = mood
        self.height = height
        self.talking = talking
        self.waveCycles = waveCycles
        self.looking = looking
        self.label = label
    }

    /// Esie at one of the app's placements: that placement's mood, size (`height` overrides it) and wave.
    init(_ placement: EsiePlacement, label: String? = nil, height: CGFloat? = nil) {
        self.init(mood: placement.mood, height: height ?? CGFloat(placement.height), waveCycles: placement.waveCycles.map { Int(truncating: $0) }, label: label)
    }

    static func spec(mood: EsieMood, talking: Bool, waveCycles: Int?, looking: Bool, motion: Motion) -> EsieSpec {
        EsieSpec(mood: mood, talking: talking, waveCycles: waveCycles.map { KotlinInt(int: Int32($0)) }, looking: looking,
                 reduceMotion: motion.level != MotionLevel.full)
    }

    private var spec: EsieSpec { Self.spec(mood: mood, talking: talking, waveCycles: waveCycles, looking: looking, motion: motion) }

    /// What restarts her from the beginning: a new mood or any other change of the spec.
    private var specKey: String { "\(mood.name) \(talking) \(waveCycles ?? -1) \(looking) \(motion.level.name)" }

    private var active: Bool { onScreen && scenePhase == .active }

    var body: some View {
        let spec = spec
        let viewport = EsieDrawing.viewport(mood)
        // the time zero of this run; paused, the elapsed time stays where it stopped
        let origin = resumedAt.map { $0.addingTimeInterval(-accumulated) }
        TimelineView(EsieSchedule(spec: spec, origin: origin)) { timeline in
            let elapsed = origin.map { max(0, timeline.date.timeIntervalSince($0)) } ?? accumulated
            Canvas { context, size in
                frame.fill(spec: spec, elapsedMs: Int64(elapsed * 1000))
                EsieDrawing.draw(frame, viewport: viewport, in: &context, size: size)
            }
        }
        .frame(width: height * CGFloat(viewport.width / viewport.height), height: height)
        .onAppear { onScreen = true; sync() }
        .onDisappear { onScreen = false; sync() }
        .onChange(of: scenePhase) { sync() }
        .onChange(of: specKey) {
            accumulated = 0
            resumedAt = active ? Date() : nil
        }
        .accessibilityElement()
        .accessibilityAddTraits(.isImage)
        .accessibilityLabel(label ?? "Esie")
        // the mood, and "still" when she does not animate (reduced motion), for VoiceOver and the UI tests
        .accessibilityValue(spec.animates ? mood.label : L.Native.Ios.Esie.still(mood: mood.label))
        .accessibilityIdentifier("esie")
        .accessibilityHidden(label == nil && !EsieView.exposedForUITests)
    }

    /// Pauses (keeping the elapsed time) off screen and in the background, resumes where it stopped.
    private func sync() {
        if active, resumedAt == nil {
            resumedAt = Date()
        } else if !active, let started = resumedAt {
            accumulated += Date().timeIntervalSince(started)
            resumedAt = nil
        }
    }

    /// The height that keeps Esie at [placement] within [fraction] of [width] (the welcome screen's 40 %), at most its own.
    static func fittedHeight(_ placement: EsiePlacement, width: CGFloat, fraction: CGFloat = 0.4) -> CGFloat {
        let viewport = EsieDrawing.viewport(placement.mood)
        let aspect = CGFloat(viewport.width / viewport.height)
        return min(CGFloat(placement.height), width * fraction / aspect)
    }

    /// Debug builds launched with `-fakeData YES` (the UI tests) expose the decorative Esie so tests can find it.
    static let exposedForUITests: Bool = {
        #if DEBUG
        return UserDefaults.standard.bool(forKey: "fakeData")
        #else
        return false
        #endif
    }()
}

/// The frame dates the shared rig asks for: at most 30 a second while she moves, a burst per blink once settled,
/// none for a still pose or while paused (no origin).
struct EsieSchedule: TimelineSchedule {
    let spec: EsieSpec
    let origin: Date?

    func entries(from startDate: Date, mode: Mode) -> AnyIterator<Date> {
        var next: Date? = startDate
        return AnyIterator {
            guard let date = next else { return nil }
            if let origin {
                let elapsedMs = Int64(max(0, date.timeIntervalSince(origin)) * 1000)
                let wait = EsieRig.shared.nextFrameDelayMs(spec: spec, elapsedMs: elapsedMs)
                next = wait < 0 ? nil : date.addingTimeInterval(Double(wait) / 1000)
            } else {
                next = nil
            }
            return date
        }
    }
}

/// One view's frame: the shared buffer and a Swift copy of its floats, refilled every frame with two calls into
/// the core (fill, then one bulk copy) instead of one call per value.
final class EsieFrameHolder: ObservableObject {
    private let buffer = EsieFrameBuffer()
    private let copier = EsieFrameCopier()
    private(set) var floats: [Float]

    init() { floats = [Float](repeating: 0, count: Int(copier.floatCount)) }

    func fill(spec: EsieSpec, elapsedMs: Int64) {
        EsieRig.shared.fill(spec: spec, elapsedMs: elapsedMs, out: buffer)
        floats.withUnsafeMutableBufferPointer { copier.write(buffer: buffer, target: $0.baseAddress!) }
    }
}

/// Paths and framings built once from the shared art (immutable after that) and reused by every EsieView.
enum EsieDrawing {
    private struct Layer {
        let path: Path
        let clip: Path?
        let fill: GraphicsContext.Shading?
        let gradient: (shading: GraphicsContext.Shading, matrix: CGAffineTransform, alpha: CGFloat)?
        let stroke: (color: Color, width: CGFloat)?
    }

    private static let layers: [Layer] = EsieArt.shared.layers.map { layer in
        var fill: GraphicsContext.Shading?
        var gradient: (shading: GraphicsContext.Shading, matrix: CGAffineTransform, alpha: CGFloat)?
        if let solid = layer.fill as? EsieSolid {
            fill = .color(color(solid.argb))
        } else if let g = layer.fill as? EsieGradient {
            let stops = Gradient(stops: (0..<Int(g.offsets.size)).map {
                Gradient.Stop(color: color(g.colors.get(index: Int32($0))), location: CGFloat(g.offsets.get(index: Int32($0))))
            })
            let k = g.coords
            let shading: GraphicsContext.Shading = g.radial
                ? .radialGradient(stops, center: CGPoint(x: CGFloat(k.get(index: 0)), y: CGFloat(k.get(index: 1))), startRadius: 0, endRadius: CGFloat(k.get(index: 2)))
                : .linearGradient(stops, startPoint: CGPoint(x: CGFloat(k.get(index: 0)), y: CGFloat(k.get(index: 1))),
                                  endPoint: CGPoint(x: CGFloat(k.get(index: 2)), y: CGFloat(k.get(index: 3))))
            let m = g.matrix
            gradient = (shading, CGAffineTransform(a: CGFloat(m.get(index: 0)), b: CGFloat(m.get(index: 1)), c: CGFloat(m.get(index: 2)),
                                                   d: CGFloat(m.get(index: 3)), tx: CGFloat(m.get(index: 4)), ty: CGFloat(m.get(index: 5))), CGFloat(g.alpha))
        }
        return Layer(path: path(layer.path), clip: layer.clips.first.map { path(EsieArt.shared.clip(id: $0.id)) }, fill: fill, gradient: gradient,
                     stroke: layer.stroke.map { (color($0.argb), CGFloat($0.width)) })
    }
    private static let viewports: [EsieMood: EsieRect] = Dictionary(
        uniqueKeysWithValues: EsieMood.entries.map { ($0, EsieRig.shared.viewport(mood: $0)) })
    private static let stride = Int(EsieFrameBuffer.companion.STRIDE)
    private static let clipAt = Int(EsieFrameBuffer.companion.CLIP_MATRIX)
    private static let drawAt = Int(EsieFrameBuffer.companion.DRAW_MATRIX)
    /// Gradient units: the shape fits in 0...1 (its bounding box); the fill pads beyond the ends.
    private static let cover = Path(CGRect(x: -4, y: -4, width: 9, height: 9))

    static func viewport(_ mood: EsieMood) -> EsieRect { viewports[mood]! }

    static func draw(_ frame: EsieFrameHolder, viewport: EsieRect, in context: inout GraphicsContext, size: CGSize) {
        let f = frame.floats
        let scale = size.height / CGFloat(viewport.height)
        context.scaleBy(x: scale, y: scale)
        context.translateBy(x: -CGFloat(viewport.left), y: -CGFloat(viewport.top))
        func matrix(_ o: Int) -> CGAffineTransform {
            CGAffineTransform(a: CGFloat(f[o]), b: CGFloat(f[o + 1]), c: CGFloat(f[o + 2]), d: CGFloat(f[o + 3]), tx: CGFloat(f[o + 4]), ty: CGFloat(f[o + 5]))
        }
        for (i, layer) in layers.enumerated() {
            let base = i * stride
            let alpha = CGFloat(f[base])
            if alpha <= 0 { continue }
            var c = context
            if let clip = layer.clip {
                c.concatenate(matrix(base + clipAt))
                c.clip(to: clip)
            }
            c.concatenate(matrix(base + drawAt))
            c.opacity = alpha
            if let fill = layer.fill {
                c.fill(layer.path, with: fill)
            } else if let g = layer.gradient {
                var gc = c
                gc.clip(to: layer.path)
                gc.concatenate(g.matrix)
                gc.opacity = alpha * g.alpha
                gc.fill(cover, with: g.shading)
            }
            if let stroke = layer.stroke {
                c.stroke(layer.path, with: .color(stroke.color), style: StrokeStyle(lineWidth: stroke.width, lineCap: .round))
            }
        }
    }

    /// The shared path commands as a SwiftUI path.
    static func path(_ p: EsiePath) -> Path {
        var path = Path()
        var c: Int32 = 0
        let k = p.coords
        func v(_ i: Int32) -> CGFloat { CGFloat(k.get(index: c + i)) }
        for i in 0..<p.ops.size {
            switch p.ops.get(index: i) {
            case EsiePath.companion.MOVE: path.move(to: CGPoint(x: v(0), y: v(1))); c += 2
            case EsiePath.companion.LINE: path.addLine(to: CGPoint(x: v(0), y: v(1))); c += 2
            case EsiePath.companion.CUBIC:
                path.addCurve(to: CGPoint(x: v(4), y: v(5)), control1: CGPoint(x: v(0), y: v(1)), control2: CGPoint(x: v(2), y: v(3))); c += 6
            case EsiePath.companion.QUAD: path.addQuadCurve(to: CGPoint(x: v(2), y: v(3)), control: CGPoint(x: v(0), y: v(1))); c += 4
            default: path.closeSubpath()
            }
        }
        return path
    }

    private static func color(_ argb: Int64) -> Color {
        Color(.sRGB, red: Double((argb >> 16) & 0xFF) / 255, green: Double((argb >> 8) & 0xFF) / 255,
              blue: Double(argb & 0xFF) / 255, opacity: Double((argb >> 24) & 0xFF) / 255)
    }
}

extension View {
    /// Esie above this message (README "Esie"): resting for empty lists, sad for failures.
    func esieAbove(_ placement: EsiePlacement) -> some View {
        VStack(spacing: 12) {
            EsieView(placement)
            self
        }
        .frame(maxWidth: .infinity)
    }
}

/// Debug only (`-esieGallery YES`): every mood side by side, for comparing with the approved renders.
struct EsieGallery: View {
    static let requested: Bool = {
        #if DEBUG
        return UserDefaults.standard.bool(forKey: "esieGallery")
        #else
        return false
        #endif
    }()

    var body: some View {
        ScrollView {
            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 16) {
                ForEach(EsieMood.entries, id: \.self) { mood in
                    VStack {
                        EsieView(mood: mood, height: 150)
                        Text(mood.label).foregroundStyle(.secondary)
                    }
                }
            }
            .padding()
        }
        .accessibilityIdentifier("esie-gallery")
    }
}
