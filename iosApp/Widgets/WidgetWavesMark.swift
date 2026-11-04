import SwiftUI

/// The Waves mark (RN's wavy dash), as the app draws it (`WavyDash`, from the shared `WavesMark` path). The widget
/// extension does not link the shared core, so the path's numbers are repeated here; AppIconSourceTest checks they
/// equal `WavesMark`. A stroked path, so `.fill(tint)` paints it.
struct WidgetWavesMark: Shape {
    static let grid: CGFloat = 36
    static let stroke: CGFloat = 3
    static let start: [CGFloat] = [1, 13]
    static let curves: [[CGFloat]] = [
        [5, 13, 5, 22, 9, 22],
        [13, 22, 14, 13, 18, 13],
        [22, 13, 23, 22, 27, 22],
        [31, 22, 31, 13, 35, 13],
    ]

    func path(in rect: CGRect) -> Path {
        let s = min(rect.width, rect.height) / Self.grid
        func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: rect.minX + x * s, y: rect.minY + y * s) }
        var p = Path()
        p.move(to: pt(Self.start[0], Self.start[1]))
        for c in Self.curves { p.addCurve(to: pt(c[4], c[5]), control1: pt(c[0], c[1]), control2: pt(c[2], c[3])) }
        return p.strokedPath(StrokeStyle(lineWidth: Self.stroke * s, lineCap: .round))
    }
}

/// A label with the Waves mark as its icon, sized with the label's text.
struct WavesLabel: View {
    let title: String
    @ScaledMetric(relativeTo: .headline) private var size: CGFloat = 20

    var body: some View {
        Label {
            Text(title)
        } icon: {
            WidgetWavesMark().fill(.foreground).frame(width: size, height: size)
        }
    }
}
