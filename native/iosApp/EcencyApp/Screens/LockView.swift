import EcencyCore
import LocalAuthentication
import SwiftUI
import UIKit

/// Face ID / Touch ID for the app lock. Biometrics only (no passcode fallback): a convenience next to the
/// PIN, never a second way around it. A failure or cancel leaves the PIN pad and its counter as they were.
/// Unlocking is bound to the enrolled biometrics: `evaluatedPolicyDomainState` is stored when biometric
/// unlock is turned on and compared at each unlock; a changed enrollment turns biometric unlock off.
enum Biometrics {
    /// Debug fake-data runs only (`-demoBiometrics YES`): biometrics count as enrolled and the offer's prompt
    /// succeeds, so UI tests reach the biometric offer on a simulator without enrolled Face ID.
    static var demo = false

    static var isAvailable: Bool {
        if demo { return true }
        var error: NSError?
        return LAContext().canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
    }

    /// "Face ID" or "Touch ID" (Apple's names, as iOS shows them), nil for any other kind: the offer's copy then
    /// says "biometrics" in the app language.
    static var name: String? {
        let context = LAContext()
        var error: NSError?
        _ = context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error)
        switch context.biometryType {
        case .faceID: return "Face ID"
        case .touchID: return "Touch ID"
        default: return nil
        }
    }

    /// The prompt of the offer when the PIN lock is turned on: biometrics only, no passcode. The binding to the
    /// enrolled biometrics is made after the backing PIN is confirmed (DomainStateBinder).
    @MainActor
    static func confirmForEnable(_ done: @escaping @MainActor (Bool) -> Void) {
        if demo { return done(true) }
        let context = LAContext()
        context.localizedCancelTitle = UiCopy.shared.CANCEL
        context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: L.Native.Ios.Lock.biometricReason) { success, _ in
            Task { @MainActor in done(success) }
        }
    }

    /// The enrolled biometrics right now (base64 domain state), nil when none are.
    static func currentBinding() -> String? {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else { return nil }
        return context.evaluatedPolicyDomainState?.base64EncodedString()
    }

    @MainActor
    static func authenticate(model: AppLockModel) {
        let context = LAContext()
        context.localizedCancelTitle = UiCopy.shared.USE_PIN
        // RN pincode.biometric_desc
        context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics,
                               localizedReason: UiCopy.shared.UNLOCK_ECENCY) { success, _ in
            guard success else { return }
            let binding = context.evaluatedPolicyDomainState?.base64EncodedString()
            Task { @MainActor in
                // the shared model compares it with the binding stored at enable; none at all fails closed
                if let binding { model.biometricSucceeded(binding: binding) } else { model.biometricInvalidated() }
            }
        }
    }
}

/// Hands the shared lock the domain state when biometric unlock is turned on (after the PIN was checked).
final class DomainStateBinder: NSObject, BiometricBinder {
    func bind() -> String? { Biometrics.demo ? "demo-binding" : Biometrics.currentBinding() }
}

/// Turning the PIN lock on where Face ID or Touch ID is set up: biometrics first, "Use PIN instead" as the way
/// to the plain PIN setup (a deliberate addition over RN). The PIN is set either way; it backs biometric unlock.
struct BiometricOfferView: View {
    let model: AppLockModel
    @Environment(\.appTheme) private var theme
    private let name = Biometrics.name

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button { model.cancel() } label: {
                    Image(appIcon: .close).scaledFont(18, weight: .semibold).foregroundStyle(theme.icon).frame(width: 44, height: 44)
                }
                .accessibilityLabel(UiCopy.shared.CLOSE)
                .accessibilityIdentifier("biometric-offer-close")
                Spacer()
            }
            Spacer()
            Image(appIcon: name == "Touch ID" ? .touchId : .faceId).font(.system(size: 80)).foregroundStyle(theme.accentText)
                .accessibilityHidden(true)
            Text(name.map { L.Native.Ios.Lock.offerTitle(method: $0) } ?? AppLockModel.companion.OFFER_TITLE)
                .scaledFont(20, weight: .bold).foregroundStyle(theme.text).multilineTextAlignment(.center).padding(.top, 24)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("biometric-offer-title")
            Text(AppLockModel.companion.OFFER_BODY)
                .scaledFont(15).foregroundStyle(theme.secondary).multilineTextAlignment(.center).padding(.top, 12)
            Spacer()
            Button {
                Biometrics.confirmForEnable { ok in if ok { model.acceptBiometricOffer() } }
            } label: {
                Text(name.map { L.Native.Ios.Lock.offerUse(method: $0) } ?? AppLockModel.companion.OFFER_USE)
                    .scaledFont(16, weight: .bold).foregroundStyle(.white)
                    .frame(maxWidth: .infinity).padding(.vertical, 14)
                    .background(theme.accent, in: Capsule())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("biometric-offer-use")
            Button { model.usePinInstead() } label: {
                Text(AppLockModel.companion.OFFER_USE_PIN).scaledFont(15).foregroundStyle(theme.accentText).padding(.vertical, 12)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("biometric-offer-pin")
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.background.ignoresSafeArea())
        .accessibilityElement(children: .contain)
        .onEscape { model.cancel() }
        .accessibilityIdentifier("biometric-offer")
    }
}

/// Reads a short announcement to VoiceOver users (wrong PIN, digit count).
@MainActor
func announce(_ text: String) {
    guard UIAccessibility.isVoiceOverRunning, !text.isEmpty else { return }
    UIAccessibility.post(notification: .announcement, argument: text)
}

/// The RN PIN screen (src/screens/pinCode/children/pinCodeView.tsx and pinCodeStyles.ts, NumericKeyboard,
/// PinAnimatedInput, CircularButton): close button, avatar, @username in blue, the information text, four
/// dots, the circular keypad and "Oh, I forgot it...".
struct PinScreen: View {
    let lock: AppLockState
    let prompt: PinPrompt
    let model: AppLockModel
    let proxyBase: String
    let biometricAvailable: Bool
    @State private var confirmForgot = false
    @Environment(\.appTheme) private var theme
    @Environment(\.motion) private var motion
    /// Wrong PINs shown so far: each one shakes the dots, with the error haptic (Motion.swift).
    @State private var shakes = 0

    private var offersBiometric: Bool { lock.offersBiometric && biometricAvailable }

    var body: some View {
        GeometryReader { geo in
            let key = geo.size.width / 7 // RN: '$deviceWidth / 7'
            // RN pinCodeStyles flex: logo 2, title 1, information 1, dots 1, keyboard 6, forgot 2
            let top = geo.size.height / 15
            let unit = (geo.size.height - top - 40) / 13
            VStack(spacing: 0) {
                HStack {
                    if prompt.canCancel {
                        Button { model.cancel() } label: {
                            Image(appIcon: .close).scaledFont(20).foregroundStyle(theme.icon)
                                .frame(width: 40, height: 40).tapTarget()
                        }
                        .accessibilityLabel(UiCopy.shared.CLOSE)
                        .accessibilityIdentifier("pin-close")
                    }
                    Spacer()
                }
                .frame(height: minTapTarget)
                .padding(.horizontal, 16)
                Group {
                    if let name = lock.username {
                        AvatarView(url: PostCard.companion.avatarUrl(account: name, proxyBase: proxyBase, size: "medium"), size: 64)
                    }
                }
                .frame(height: unit * 2)
                Text(lock.username.map { "@\($0)" } ?? "")
                    .scaledFont(20, weight: .bold, relativeTo: .title3)
                    .minimumScaleFactor(0.5)
                    .lineLimit(1)
                    .foregroundStyle(Color(hex: 0x357CE6))
                    .frame(height: unit)
                    .accessibilityIdentifier("pin-username")
                VStack(spacing: 6) {
                    Text(prompt.message)
                        .scaledFont(14, relativeTo: .subheadline)
                        .minimumScaleFactor(0.6)
                        .foregroundStyle(theme.text)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("pin-message")
                    if let error = lock.promptError {
                        Text(error)
                            .scaledFont(13, relativeTo: .footnote)
                            .minimumScaleFactor(0.6)
                            .foregroundStyle(theme.dangerText)
                            .multilineTextAlignment(.center)
                            .accessibilityIdentifier("pin-error")
                    }
                }
                .padding(.horizontal, 24)
                .frame(height: unit, alignment: .top)
                PinDots(filled: Int(prompt.digits))
                    .shake(count: shakes, motion: motion)
                    .sensoryFeedback(.error, trigger: shakes)
                    .frame(height: unit)
                keyboard(key: key)
                    .frame(height: unit * 6)
                Group {
                    if prompt.showForgot {
                        Button(AuthUiCopy.shared.OH_I_FORGOT_IT) { confirmForgot = true }
                            .scaledFont(14, relativeTo: .subheadline)
                            .foregroundStyle(theme.secondary)
                            .accessibilityIdentifier("pin-forgot")
                    }
                }
                .frame(height: unit * 2)
            }
            .frame(maxWidth: .infinity)
            .padding(.top, top)
        }
        .background(theme.background.ignoresSafeArea())
        // .contain keeps the children's own identifiers (a plain container would hand them its own)
        .accessibilityElement(children: .contain)
        // the VoiceOver escape gesture closes a prompt that can be cancelled (never the lock itself)
        .accessibilityAction(.escape) { if prompt.canCancel { model.cancel() } }
        .accessibilityIdentifier("pin-screen")
        // once per lock (the model clears the flag): a wrong PIN or a cancel never starts Face ID again by itself
        .task(id: lock.autoStartsBiometric && biometricAvailable) {
            if lock.autoStartsBiometric && biometricAvailable {
                model.consumeAutoBiometric()
                startBiometric()
            }
        }
        .onChange(of: prompt.digits) { _, digits in announce(AuthUiCopy.shared.pinDigits(filled: digits, total: PinLock.companion.PIN_LENGTH)) }
        .onChange(of: prompt) { old, new in
            if MotionPolicy.shared.pinRejected(before: old, now: new) { shakes += 1 }
        }
        .onChange(of: (lock.promptError ?? "") + prompt.message) { _, _ in
            if let error = lock.promptError { announce("\(error) \(prompt.message)") }
        }
        .alert(AccountCopy.shared.WARNING, isPresented: $confirmForgot) {
            Button(UiCopy.shared.CLEAR, role: .destructive) { model.forgotPin() }.accessibilityIdentifier("pin-forgot-confirm")
            Button(UiCopy.shared.CANCEL, role: .cancel) {}
        } message: {
            Text(AuthUiCopy.shared.CLEAR_USER_DATA_CONFIRM)
        }
    }

    private func startBiometric() {
        Biometrics.authenticate(model: model)
    }

    /// RN NumericKeyboard: 1 to 9 ($deviceWidth / 1.8 wide), then 0 and backspace aligned right; the empty
    /// bottom-left slot holds the biometric key when it is on.
    private func keyboard(key: CGFloat) -> some View {
        let enabled = !prompt.isChecking
        return VStack(spacing: 10) {
            ForEach(0..<3, id: \.self) { row in
                HStack {
                    ForEach(1...3, id: \.self) { col in
                        let digit = row * 3 + col
                        KeypadButton(label: "\(digit)", size: key) { model.enterDigit(digit: Int32(digit)) }
                        if col < 3 { Spacer(minLength: 0) }
                    }
                }
            }
            HStack {
                if offersBiometric {
                    Button(action: startBiometric) {
                        Image(appIcon: .faceId).scaledFont(26).foregroundStyle(Color(hex: 0x357CE6))
                            .frame(width: key, height: key)
                    }
                    .accessibilityLabel(AuthUiCopy.shared.UNLOCK_WITH_BIOMETRICS)
                    .accessibilityIdentifier("pin-biometric")
                } else {
                    Color.clear.frame(width: key, height: key)
                }
                Spacer(minLength: 0)
                KeypadButton(label: "0", size: key) { model.enterDigit(digit: 0) }
                Spacer(minLength: 0)
                Button { model.deleteDigit() } label: {
                    Image(appIcon: .backspace, filled: true).scaledFont(24).foregroundStyle(Color(hex: 0x357CE6))
                        .frame(width: key, height: key)
                }
                .accessibilityLabel(UiCopy.shared.DELETE)
                .accessibilityIdentifier("pin-delete")
            }
        }
        .frame(width: key * 7 / 1.8)
        // a keypad is not mirrored (as the system passcode pad): 1 to 3 left to right, backspace on the right
        .environment(\.layoutDirection, .leftToRight)
        .disabled(!enabled)
    }
}

/// RN CircularButton: a ring of rgba(53,124,230,0.2), the digit in #357ce6, 24 pt, weight 500.
private struct KeypadButton: View {
    let label: String
    let size: CGFloat
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .scaledFont(24, weight: .medium, relativeTo: .title2)
                .minimumScaleFactor(0.5)
                .foregroundStyle(Color(hex: 0x357CE6))
                .frame(width: size, height: size)
                .overlay(Circle().stroke(Color(hex: 0x357CE6).opacity(0.2), lineWidth: 1))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("pin-key-\(label)")
    }
}

/// RN PinAnimatedInput: 18 pt circles (scaled with Dynamic Type), blue outline, filled blue per typed digit.
private struct PinDots: View {
    let filled: Int
    @Environment(\.appTheme) private var theme
    @Environment(\.motion) private var motion
    @ScaledMetric(relativeTo: .body) private var dot: CGFloat = 18

    var body: some View {
        HStack(spacing: 20) {
            ForEach(0..<Int(PinLock.companion.PIN_LENGTH), id: \.self) { i in
                // each dot fills with a short fade and pops as its digit lands
                Circle()
                    .fill(i < filled ? Color(hex: 0x357CE6) : theme.background)
                    .overlay(Circle().stroke(Color(hex: 0x357CE6), lineWidth: 1))
                    .frame(width: dot, height: dot)
                    .popOnChange(i < filled, enabled: i < filled)
                    .animation(motion.fade(MotionTokens.shared.PRESS_MS), value: filled)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(AuthUiCopy.shared.pinDigits(filled: Int32(filled), total: PinLock.companion.PIN_LENGTH))
        .accessibilityIdentifier("pin-dots")
    }
}

/// Covers the app while the lock state loads, after a storage error, and over the app switcher snapshot.
struct LockCover: View {
    var error: String?
    var onRetry: () -> Void = {}
    @Environment(\.appTheme) private var theme

    var body: some View {
        ZStack {
            theme.background.ignoresSafeArea()
            if let error {
                VStack(spacing: 12) {
                    Text(error).multilineTextAlignment(.center).foregroundStyle(theme.text)
                    Button(UiCopy.shared.RETRY, action: onRetry)
                }
                .padding(24)
            } else {
                Image(appIcon: .lock, filled: true).scaledFont(32).foregroundStyle(theme.icon)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("lock-cover")
    }
}

/// Errors go to the PIN pad while one shows (`promptError`), so no alert is needed for them then.
private func alertText(_ state: AppLockState) -> String? {
    state.notice ?? (state.hidesContent || state.prompt != nil ? nil : state.error?.message)
}

/// The main window's side of the lock: nothing private is in its hierarchy while the lock state is unknown
/// or locked. The lock screen, the settings PIN flows, notices and the app switcher cover live in
/// [LockOverlay]'s window, above every sheet and alert of this one.
struct LockGate<Content: View>: View {
    @ObservedObject var lock: Observed<AppLockState>
    let overlay: LockOverlay
    @ViewBuilder let content: Content
    @Environment(\.appTheme) private var theme

    var body: some View {
        ZStack {
            if lock.state.hidesContent {
                theme.background.ignoresSafeArea()
            } else {
                content
            }
        }
        .onAppear { overlay.attach() }
    }
}

/// What the overlay window shows: the PIN pad (lock screen or a settings flow), the loading/error cover, and
/// lock notices.
private struct LockOverlayView: View {
    let state: AppLockState
    let model: AppLockModel
    let proxyBase: String
    let biometricAvailable: Bool

    var body: some View {
        ThemedRoot {
            ZStack {
                if let prompt = state.prompt {
                    PinScreen(lock: state, prompt: prompt, model: model, proxyBase: proxyBase, biometricAvailable: biometricAvailable)
                } else if state.biometricOffer {
                    BiometricOfferView(model: model)
                } else if state.hidesContent {
                    LockCover(error: state.error?.message) { model.clearError(); model.start() }
                } else {
                    Color.clear
                }
            }
            .alert(alertText(state) ?? "", isPresented: Binding(get: { alertText(state) != nil },
                                                              set: { if !$0 { model.clearNotice(); model.clearError() } })) {
                Button(UiCopy.shared.OK, role: .cancel) {}.accessibilityIdentifier("lock-notice-ok")
            }
        }
    }
}

/// A window above everything else in the scene (sheets, Safari, video, alerts) for the lock screen and the
/// privacy cover. Scene notifications drive it directly, without waiting for SwiftUI: the cover is up before
/// the app switcher snapshot when the scene deactivates, and on reactivation the model decides (a relock sets
/// its prompt synchronously) before the cover comes down, so content never shows in between.
@MainActor
final class LockOverlay {
    private let model: AppLockModel
    private let proxyBase: String
    private let hosting: UIHostingController<LockOverlayView>
    private(set) var window: UIWindow?
    /// The app switcher cover, plain UIKit so it is in place the moment the scene deactivates.
    let cover = UIView()
    private var inactive = false
    /// The window that was key before the overlay took it; it gets it back when the overlay hides.
    private weak var previousKeyWindow: UIWindow?
    private var biometricAvailable = Biometrics.isAvailable
    private var watcher: Closeable?
    private var observers: [NSObjectProtocol] = []

    init(model: AppLockModel, proxyBase: String) {
        self.model = model
        self.proxyBase = proxyBase
        hosting = UIHostingController(rootView: LockOverlayView(state: model.current, model: model, proxyBase: proxyBase,
                                                                biometricAvailable: Biometrics.isAvailable))
        hosting.view.backgroundColor = .clear
        cover.backgroundColor = .systemBackground
        cover.accessibilityIdentifier = "privacy-cover"
        let icon = UIImageView(image: UIImage(appIcon: .lock, filled: true))
        icon.tintColor = .secondaryLabel
        icon.translatesAutoresizingMaskIntoConstraints = false
        cover.addSubview(icon)
        NSLayoutConstraint.activate([icon.centerXAnchor.constraint(equalTo: cover.centerXAnchor),
                                     icon.centerYAnchor.constraint(equalTo: cover.centerYAnchor)])
        watcher = model.watch { [weak self] _ in
            MainActor.assumeIsolated { self?.render() }
        }
    }

    deinit {
        watcher?.close()
        observers.forEach(NotificationCenter.default.removeObserver)
    }

    /// The overlay is showing the PIN pad or the loading/error cover (content is hidden).
    var isShowingLock: Bool { window?.isHidden == false && model.current.hidesContent }

    var isCoverShowing: Bool { window?.isHidden == false && !cover.isHidden }

    /// VoiceOver is held on the overlay (a PIN pad shows).
    var isAccessibilityModal: Bool { hosting.view.accessibilityViewIsModal }

    /// Creates the window in the app's scene (once).
    func attach(to scene: UIWindowScene? = nil) {
        guard window == nil else { return }
        guard let scene = scene ?? UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return }
        let window = UIWindow(windowScene: scene)
        window.windowLevel = .alert + 1
        window.backgroundColor = .clear
        window.rootViewController = hosting
        window.accessibilityIdentifier = "lock-window"
        cover.frame = window.bounds
        cover.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        window.addSubview(cover)
        self.window = window
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: UIScene.willDeactivateNotification, object: scene, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.sceneDeactivated() }
            },
            center.addObserver(forName: UIScene.didEnterBackgroundNotification, object: scene, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.sceneEnteredBackground() }
            },
            center.addObserver(forName: UIScene.didActivateNotification, object: scene, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.sceneActivated() }
            },
        ]
        render()
    }

    func sceneDeactivated() {
        inactive = true
        render()
    }

    func sceneEnteredBackground() {
        inactive = true
        model.onBackground()
        render()
    }

    func sceneActivated() {
        // decide first: a relock sets its prompt synchronously, so the cover gives way to the PIN pad directly
        model.onForeground()
        biometricAvailable = Biometrics.isAvailable
        inactive = false
        render()
    }

    /// Lays the window out from the model's current state (not a delayed copy of it).
    func render() {
        guard let window else { return }
        let state = model.current
        hosting.rootView = LockOverlayView(state: state, model: model, proxyBase: proxyBase, biometricAvailable: biometricAvailable)
        let privacy = inactive && (state.enabled || state.hidesContent)
        let needed = privacy || state.prompt != nil || state.biometricOffer || state.hidesContent || alertText(state) != nil
        cover.isHidden = !privacy
        window.bringSubviewToFront(cover)
        if needed {
            if window.isHidden { window.isHidden = false }
            if !privacy && !window.isKeyWindow {
                previousKeyWindow = window.windowScene?.windows.first { $0.isKeyWindow && $0 !== window } ?? previousKeyWindow
                window.makeKey()
            }
            // VoiceOver stays on the PIN pad: nothing of the app window behind it is reachable while it shows
            hosting.view.accessibilityViewIsModal = state.prompt != nil || state.biometricOffer
            hosting.view.setNeedsLayout()
            hosting.view.layoutIfNeeded()
        } else if !window.isHidden {
            window.isHidden = true
            hosting.view.accessibilityViewIsModal = false
            let previous = previousKeyWindow.flatMap { $0.isHidden || $0.windowScene !== window.windowScene ? nil : $0 }
            previousKeyWindow = nil
            (previous ?? window.windowScene?.windows.first { $0 !== window && !$0.isHidden })?.makeKey()
        }
    }
}
