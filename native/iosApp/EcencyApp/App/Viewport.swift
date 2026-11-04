import SwiftUI
import UIKit

// The app's own size and window state, read from the view tree instead of `UIScreen.main` (README "iPhone Duo"). On
// iPhone Duo the window changes size while the app runs (folding, unfolding, the inner display's poses), and on any
// iPhone the window need not match the screen (resizable iPhone Mirroring), so nothing sizes itself from the screen.

private struct ViewportSizeKey: EnvironmentKey {
    static let defaultValue: CGSize? = nil
}

extension EnvironmentValues {
    /// The app window's content size now (inside the safe area), updated on every resize. Nil until the root has been
    /// laid out once, and outside the app root (unit tests, previews): callers then leave their size uncapped.
    var viewportSize: CGSize? {
        get { self[ViewportSizeKey.self] }
        set { self[ViewportSizeKey.self] = newValue }
    }
}

/// Measures the root and hands its size to every screen below it as `\.viewportSize`.
private struct ViewportReader: ViewModifier {
    @State private var size: CGSize?

    func body(content: Content) -> some View {
        content
            .environment(\.viewportSize, size)
            // the window, not the space the keyboard leaves: a keyboard showing or hiding is no resize (it re-rendered
            // the whole app mid-dismiss, and a sheet's Cancel then kept the sheet up)
            .background {
                Color.clear
                    .ignoresSafeArea(.keyboard)
                    .onGeometryChange(for: CGSize.self) { $0.size } action: { if $0.width > 0, $0.height > 0 { size = $0 } }
            }
    }
}

extension View {
    /// Applied once, at the app's root.
    func readsViewport() -> some View { modifier(ViewportReader()) }

    /// Whether this view's window is being recorded, mirrored or AirPlayed (the window scene's capture state, which
    /// replaced `UIScreen.main.isCaptured`), reported at once and on every change.
    func onSceneCaptureChange(_ action: @escaping (Bool) -> Void) -> some View {
        background(SceneCaptureReader(onChange: action).frame(width: 0, height: 0).accessibilityHidden(true))
    }
}

/// Which orientations the app takes, from its display's shape: portrait on a tall display (every iPhone, about 2:1),
/// any orientation but upside down on a near-square one (iPhone Duo's outer and inner displays, about 1.4:1), where
/// the device is held either way. Not from the display's size: while the app is portrait only, iOS boxes it on the
/// unfolded inner display and reports that display as a phone-sized 469 x 669 pt screen, so a size rule never got out
/// of the box. Read from the window's display at that moment, so it changes as the device folds; nil (no display
/// yet) is portrait.
enum OrientationPolicy {
    /// Long side over short side at or above which a display is a phone's (iPhone SE: 1.78; iPhone Duo: 1.42 to 1.46).
    static let phoneAspect: CGFloat = 1.7

    static func mask(screen: CGSize?) -> UIInterfaceOrientationMask {
        guard let screen, screen.width > 0, screen.height > 0 else { return .portrait }
        let aspect = max(screen.width, screen.height) / min(screen.width, screen.height)
        return aspect >= phoneAspect ? .portrait : .allButUpsideDown
    }
}

extension View {
    /// Asks UIKit to read `OrientationPolicy` again when the window moves to another display. A fold or unfold
    /// changes the window scene's geometry (Apple documents `effectiveGeometry` as observable); the size classes need
    /// not change, since a portrait-only app unfolded sideways is boxed in a phone-sized window. Applied once, at the
    /// app's root.
    func followsDisplayOrientationPolicy() -> some View {
        background(OrientationRefresher().frame(width: 0, height: 0).accessibilityHidden(true))
    }
}

private struct OrientationRefresher: UIViewRepresentable {
    func makeUIView(context: Context) -> RefreshView { RefreshView() }
    func updateUIView(_ view: RefreshView, context: Context) {}

    final class RefreshView: UIView {
        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            registerForTraitChanges([UITraitHorizontalSizeClass.self, UITraitVerticalSizeClass.self]) { (view: RefreshView, _: UITraitCollection) in
                view.refresh()
            }
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { fatalError("not coded") }

        private var geometry: NSKeyValueObservation?

        override func didMoveToWindow() {
            super.didMoveToWindow()
            geometry = window?.windowScene?.observe(\.effectiveGeometry) { [weak self] _, _ in
                DispatchQueue.main.async { self?.refresh() }
            }
            refresh()
        }

        private func refresh() {
            window?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        }
    }
}

/// A zero-size view that follows its window scene's `sceneCaptureState` trait.
private struct SceneCaptureReader: UIViewRepresentable {
    let onChange: (Bool) -> Void

    func makeUIView(context: Context) -> CaptureStateView {
        let view = CaptureStateView()
        view.onChange = onChange
        return view
    }

    func updateUIView(_ view: CaptureStateView, context: Context) {
        view.onChange = onChange
    }

    final class CaptureStateView: UIView {
        var onChange: ((Bool) -> Void)?
        private var reported: Bool?

        override init(frame: CGRect) {
            super.init(frame: frame)
            isUserInteractionEnabled = false
            registerForTraitChanges([UITraitSceneCaptureState.self]) { (view: CaptureStateView, _: UITraitCollection) in
                view.report()
            }
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { fatalError("not coded") }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            if window != nil { report() }
        }

        private func report() {
            let captured = traitCollection.sceneCaptureState == .active
            guard captured != reported else { return }
            reported = captured
            onChange?(captured)
        }
    }
}
