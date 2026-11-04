import EcencyCore
import SwiftUI
import UIKit

/// A window that takes only the touches on its toast; everything else goes to the windows below.
final class PassthroughWindow: UIWindow {
    /// The toast pill in window coordinates (null: nothing to touch).
    var touchFrame = CGRect.null

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        touchFrame.contains(point) ? super.hitTest(point, with: event) : nil
    }
}

/// RN ToastNotification in a window of its own, above every sheet of the app window and the signing prompts
/// (`.alert`), below the lock window (`.alert + 1`), so a toast posted while a sheet is open shows on top and
/// nothing shows over the lock. It never becomes the key window and lets every touch outside the pill through.
@MainActor
final class ToastOverlay {
    static let level = UIWindow.Level(rawValue: UIWindow.Level.alert.rawValue + 0.5)

    private let ui: UiMessages
    private let hosting: UIHostingController<ToastOverlayView>
    private(set) var window: PassthroughWindow?
    private var watcher: Closeable?

    init(ui: UiMessages) {
        self.ui = ui
        hosting = UIHostingController(rootView: ToastOverlayView(toast: nil, model: ui, onFrame: { _ in }))
        hosting.view.backgroundColor = .clear
        watcher = ui.watch { [weak self] _ in MainActor.assumeIsolated { self?.render() } }
    }

    deinit { watcher?.close() }

    var isShowing: Bool { window?.isHidden == false }

    func attach(to scene: UIWindowScene? = nil) {
        guard window == nil else { return }
        guard let scene = scene ?? UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return }
        let window = PassthroughWindow(windowScene: scene)
        window.windowLevel = Self.level
        window.backgroundColor = .clear
        window.rootViewController = hosting
        window.accessibilityIdentifier = "toast-window"
        self.window = window
        render()
    }

    private func render() {
        let toast = ui.current.toast
        hosting.rootView = ToastOverlayView(toast: toast, model: ui, onFrame: { [weak self] in self?.window?.touchFrame = $0 })
        if toast == nil { window?.touchFrame = .null }
        window?.isHidden = toast == nil
    }
}

private struct ToastFrame: PreferenceKey {
    static let defaultValue = CGRect.null
    static func reduce(value: inout CGRect, nextValue: () -> CGRect) { value = value.union(nextValue()) }
}

/// The toast: centred at the bottom, 100 pt up (above the keyboard when it is up), RN's 22 pt corners.
struct ToastOverlayView: View {
    let toast: UiMessageToast?
    let model: UiMessages
    let onFrame: (CGRect) -> Void

    var body: some View {
        ThemedRoot { ToastPill(toast: toast, model: model, onFrame: onFrame) }
    }
}

private struct ToastPill: View {
    let toast: UiMessageToast?
    let model: UiMessages
    let onFrame: (CGRect) -> Void
    @Environment(\.colorScheme) private var scheme

    /// RN's toast grays; light is `ContrastPalette.lightMessageGray` (RN 0x788187 was 3.97:1 with white text), dark 0x526D91
    /// is 5.31:1 (README "Accessibility").
    private var gray: Color { scheme == .dark ? Color(hex: 0x526D91) : Color(hex: ContrastPalette.lightMessageGray) }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                if let toast {
                    Text(toast.text)
                        .scaledFont(14, weight: .bold)
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 10).padding(.vertical, 12)
                        .frame(minWidth: geo.size.width / 1.9)
                        // RN's 22 pt radius: a pill on one line, a rounded card when the text wraps
                        .background(RoundedRectangle(cornerRadius: 22).fill(gray).shadow(color: .black.opacity(0.3), radius: 3, y: 5))
                        .contentShape(RoundedRectangle(cornerRadius: 22))
                        .background(GeometryReader { Color.clear.preference(key: ToastFrame.self, value: $0.frame(in: .global)) })
                        .onTapGesture { model.dismiss(id: toast.id_) }
                        // VoiceOver: announced when posted (below); the escape gesture dismisses it
                        .accessibilityAction(.escape) { model.dismiss(id: toast.id_) }
                        .padding(.horizontal, 5)
                        .padding(.bottom, 100)
                        // full width as well as full height: GeometryReader places its content top leading
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
                        .task(id: toast.id_) {
                            UIAccessibility.post(notification: .announcement, argument: toast.text)
                            try? await Task.sleep(nanoseconds: UInt64(toast.durationMillis) * 1_000_000)
                            model.dismiss(id: toast.id_)
                        }
                        .accessibilityIdentifier("toast")
                }
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .animation(.easeInOut(duration: 0.2), value: toast?.id_)
        }
        .onPreferenceChange(ToastFrame.self) { onFrame($0) }
    }
}
