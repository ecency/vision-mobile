import EcencyCore
import SwiftUI
import UIKit

/// RN backupKeysScreen ("Manage Authorities") over the shared `BackupKeysModel`, which holds the gate (PIN or
/// biometrics, or RN's "set a PIN first" warning), the rows and the import. The sheet only draws and leaves
/// when the model says closed (cancelled PIN, lock, background, another account).
///
/// Screen capture: key values are drawn in `ScreenShield` (left out of screenshots and recordings). When iOS
/// does not offer the secure container the shield relies on, no value is drawn in a box at all; it shows only
/// while the box is pressed and held (`HoldToShowValue`). In both cases values are hidden while the screen is
/// recorded or mirrored, a screenshot hides every revealed private key and says so, and the whole sheet is
/// covered from the moment the app stops being active (app switcher, control centre, a call). A private key
/// reaches the pasteboard only after the warning, local to this device and for two minutes (`KeyPasteboard`).
struct BackupKeysSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appTheme) private var theme
    @StateObject private var observed: Observed<BackupKeysState>
    @State private var secret = ""
    @State private var copied = false
    @State private var captured = UIScreen.main.isCaptured
    /// The app is not active (app switcher, control centre, a system prompt): the sheet shows its cover.
    @State private var covered = false
    /// A screenshot was taken while keys were on screen: revealed keys were hidden, and the sheet says so.
    @State private var screenshotNotice = false

    init(env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.backupKeysModel(), ownsModel: true))
    }

    private var model: BackupKeysModel { observed.model as! BackupKeysModel }
    private var copy: BackupKeysCopy { BackupKeysCopy.shared }

    var body: some View {
        let state = observed.state
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    if state.isShown {
                        // the account uses its owner key for another role too: said every time the keys show
                        if let warning = state.sharedOwnerWarning {
                            Text(warning).font(.footnote).foregroundStyle(theme.dangerText).accessibilityIdentifier("keys-shared-owner-warning")
                        }
                        ForEach(state.rows, id: \.role) { row in
                            KeyBoxView(row: row, hidden: captured, onButton: { model.toggle(role: row.role) }, onCopy: { copyKey(row) })
                        }
                        if captured {
                            Text(L.Native.Ios.Keys.hiddenWhileCaptured).font(.footnote).foregroundStyle(theme.secondary)
                                .accessibilityIdentifier("keys-captured")
                        }
                        if screenshotNotice {
                            Text(L.Native.Ios.Keys.screenshotNotice)
                                .font(.footnote).foregroundStyle(theme.dangerText).accessibilityIdentifier("keys-screenshot-notice")
                        }
                        if !ScreenShield.isAvailable {
                            Text(L.Native.Ios.Keys.holdHint).font(.footnote).foregroundStyle(theme.secondary)
                                .accessibilityIdentifier("keys-hold-hint")
                        }
                        if copied {
                            Text(copy.KEY_COPIED).font(.footnote).foregroundStyle(theme.secondary).accessibilityIdentifier("key-copied")
                        }
                    }
                }
                .padding(16)
            }
            .background(theme.background)
            .navigationTitle(copy.TITLE)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(UiCopy.shared.CLOSE) { model.dismiss() }.accessibilityIdentifier("keys-close") } }
        }
        .overlay { if covered { KeysPrivacyCover() } }
        .accessibilityIdentifier("backup-keys")
        .onAppear { model.start() }
        // the cover goes up when the app stops being active, before the system takes its app switcher snapshot;
        // the lock window's cover only exists once the app is locked, which is later
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.willResignActiveNotification)) { _ in covered = true }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in covered = false }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.userDidTakeScreenshotNotification)) { _ in
            let shown = observed.state.rows.contains { $0.revealed }
            model.hideAll()
            if shown || !ScreenShield.isAvailable { screenshotNotice = true }
        }
        .onChange(of: state.phase) { _, phase in if phase == .closed { dismiss() } }
        .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
            captured = UIScreen.main.isCaptured
            if captured { model.hideAll() }
        }
        .interactiveDismissDisabled(state.isImporting)
        // RN settings.keys_warning: Cancel leaves, Set PIN opens the new PIN pad
        .alert(copy.WARNING, isPresented: .constant(state.phase == .needsPin)) {
            Button(copy.CANCEL, role: .cancel) { model.dismiss() }
            Button(copy.SET_PIN) { model.setPin(biometricAvailable: Biometrics.isAvailable) }
        } message: { Text(copy.KEYS_WARNING).accessibilityIdentifier("keys-warning") }
        .alert(copy.WARNING, isPresented: .constant(state.copyAsks != nil)) {
            Button(copy.CANCEL, role: .cancel) { model.cancelCopy() }
            Button(copy.COPY) {
                if let key = model.confirmCopy() { KeyPasteboard.copy(key.text, sensitive: true); flashCopied() }
            }
        } message: { Text(copy.COPY_WARNING) }
        // RN importPrivateKeyModal; the typed secret is dropped whenever the dialog goes
        .alert(copy.ENTER_PASS, isPresented: .constant(state.importOpen && state.importError == nil)) {
            SecureField(copy.SIGN_PLACEHOLDER, text: $secret).textInputAutocapitalization(.never).autocorrectionDisabled().leftToRightField()
                .accessibilityIdentifier("key-import-field")
            Button(copy.CANCEL, role: .cancel) { secret = ""; model.closeImport() }
            Button(copy.SIGN) { let typed = secret; secret = ""; model.importKey(secret: typed) }
        } message: { Text(copy.IMPORT_DESC) }
        .alert(copy.IMPORT_FAILED, isPresented: .constant(state.importError != nil)) {
            Button(UiCopy.shared.OK, role: .cancel) { model.clearImportError() }
        } message: { Text(state.importError ?? "") }
    }

    private func copyKey(_ row: KeyRow) {
        // a public key comes back at once; a revealed private key asks first (the model sets copyAsks)
        if let key = model.keyToCopy(role: row.role) { KeyPasteboard.copy(key.text, sensitive: key.sensitive); flashCopied() }
    }

    private func flashCopied() {
        copied = true
        Task { try? await Task.sleep(nanoseconds: 2_000_000_000); copied = false }
    }
}

/// The pasteboard for keys. A private key stays on this device (no Universal Clipboard) and expires after two minutes.
enum KeyPasteboard {
    static let expiry: TimeInterval = 120

    static func options(sensitive: Bool, now: Date = Date()) -> [UIPasteboard.OptionsKey: Any] {
        sensitive ? [.localOnly: true, .expirationDate: now.addingTimeInterval(expiry)] : [:]
    }

    static func copy(_ text: String, sensitive: Bool, to pasteboard: UIPasteboard = .general) {
        pasteboard.setItems([[UIPasteboard.typeAutomatic: text]], options: options(sensitive: sensitive))
    }
}

/// RN TextBoxWithCopy: label and the reveal or import button above, the value and the copy button below.
private struct KeyBoxView: View {
    let row: KeyRow
    let hidden: Bool
    let onButton: () -> Void
    let onCopy: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            AdaptiveStack {
                Text(row.label).font(.subheadline.bold()).accessibilityAddTraits(.isHeader).foregroundStyle(theme.text).accessibilityIdentifier("key-label-\(row.role.name)")
                FormRowGap()
                if let note = row.note {
                    // the owner row: public only, nothing to reveal and nothing to import (RN offers "Import Private")
                    Text(note).font(.caption).foregroundStyle(theme.secondary).accessibilityIdentifier("key-note-\(row.role.name)")
                } else {
                    Button(row.button, action: onButton).font(.footnote).buttonStyle(.borderless).accessibilityIdentifier("key-button-\(row.role.name)")
                }
            }
            HStack(spacing: 0) {
                Group {
                    if ScreenShield.isAvailable {
                        ScreenShield(text: hidden ? "" : row.value, color: UIColor(theme.text))
                    } else {
                        // fail closed: without the secure container no value sits on screen by itself
                        HoldToShowValue(value: hidden ? "" : row.value)
                    }
                }
                    .frame(minHeight: 44).frame(maxWidth: .infinity).padding(.horizontal, 12)
                    .background(theme.secondary.opacity(0.12))
                    // VoiceOver reads a public key; a private key is announced only as present
                    .accessibilityElement()
                    .accessibilityLabel(row.isSensitive ? AuthUiCopy.shared.privateKeyShown(title: row.title) : row.value)
                    .accessibilityIdentifier("key-value-\(row.role.name)")
                Button(action: onCopy) {
                    Image(appIcon: .clipboard).foregroundStyle(.white).frame(minWidth: 44, maxHeight: .infinity).frame(minHeight: 44).background(theme.accent)
                }
                .buttonStyle(.borderless).disabled(hidden)
                .accessibilityLabel(AuthUiCopy.shared.keyCopyLabel(title: row.title)).accessibilityIdentifier("key-copy-\(row.role.name)")
            }
            // the row is as tall as the value box; the copy button fills that height and no more
            .fixedSize(horizontal: false, vertical: true)
            .clipShape(RoundedRectangle(cornerRadius: 8))
        }
    }
}

/// What covers the keys sheet while the app is not active.
private struct KeysPrivacyCover: View {
    @Environment(\.appTheme) private var theme

    var body: some View {
        ZStack {
            theme.background.ignoresSafeArea()
            Image(appIcon: .lock, filled: true).font(.system(size: 40)).foregroundStyle(theme.secondary).accessibilityHidden(true)
        }
        .accessibilityIdentifier("keys-cover")
    }
}

/// The fallback when `ScreenShield` is not available: the value is on screen only while the box is pressed, so
/// a screenshot or a glance needs a finger held down. Nothing of the value is exposed to accessibility here.
struct HoldToShowValue: View {
    let value: String
    @State private var held = false
    @Environment(\.appTheme) private var theme

    var body: some View {
        Text(held ? value : (value.isEmpty ? "" : L.Native.Ios.Keys.holdToShow))
            .font(.system(.footnote, design: .monospaced)).lineLimit(1).truncationMode(.tail)
            .foregroundStyle(held ? theme.text : theme.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onLongPressGesture(minimumDuration: 0.15, maximumDistance: 40, perform: {}, onPressingChanged: { pressing in held = pressing })
            .accessibilityIdentifier("key-hold-to-show")
    }
}

/// One line of text that screenshots, screen recordings and mirroring leave out: the label lives in the canvas
/// view UIKit draws secure text entry in. iOS has no public switch for this, so it fails closed: when a secure
/// field has no subview of that class (`isShielding` false) the label is put nowhere and nothing is drawn, and
/// the sheet falls back to `HoldToShowValue`. A unit test checks the class on the running iOS.
struct ScreenShield: UIViewRepresentable {
    let text: String
    let color: UIColor

    /// The part of the class name of the view a secure `UITextField` draws its text in (`_UITextLayoutCanvasView`).
    static let secureCanvasMarker = "LayoutCanvasView"

    /// Debug builds can run without the shield (`-keysNoShield YES`), to exercise the fallback.
    static var forceUnavailable: Bool = {
        #if DEBUG
        return UserDefaults.standard.bool(forKey: "keysNoShield")
        #else
        return false
        #endif
    }()

    /// Whether this iOS offers the secure container; found once.
    static let isAvailable: Bool = !forceUnavailable && Holder(frame: .zero).isShielding

    final class Holder: UIView {
        let label = UILabel()
        /// Kept alive: the container belongs to it.
        let field = UITextField()
        private(set) var isShielding = false

        override convenience init(frame: CGRect) { self.init(frame: frame, canvasMarker: ScreenShield.secureCanvasMarker) }

        init(frame: CGRect, canvasMarker: String) {
            super.init(frame: frame)
            // 13 pt, following Dynamic Type
            label.font = UIFontMetrics(forTextStyle: .footnote).scaledFont(for: .monospacedSystemFont(ofSize: 13, weight: .regular))
            label.adjustsFontForContentSizeCategory = true
            label.lineBreakMode = .byTruncatingTail
            label.numberOfLines = 1
            label.translatesAutoresizingMaskIntoConstraints = false
            field.isSecureTextEntry = true
            field.isUserInteractionEnabled = false
            isUserInteractionEnabled = false
            // only the secure canvas itself will do: any other subview would draw the key into captures
            guard let container = field.subviews.first(where: { String(describing: type(of: $0)).contains(canvasMarker) }) else { return }
            container.subviews.forEach { $0.removeFromSuperview() }
            container.translatesAutoresizingMaskIntoConstraints = false
            addSubview(container)
            container.addSubview(label)
            NSLayoutConstraint.activate([
                container.leadingAnchor.constraint(equalTo: leadingAnchor), container.trailingAnchor.constraint(equalTo: trailingAnchor),
                container.topAnchor.constraint(equalTo: topAnchor), container.bottomAnchor.constraint(equalTo: bottomAnchor),
                label.leadingAnchor.constraint(equalTo: container.leadingAnchor), label.trailingAnchor.constraint(equalTo: container.trailingAnchor),
                label.centerYAnchor.constraint(equalTo: container.centerYAnchor),
            ])
            isShielding = true
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { fatalError("not used") }
    }

    func makeUIView(context: Context) -> Holder { Holder(frame: .zero) }

    func updateUIView(_ view: Holder, context: Context) {
        view.label.text = text
        view.label.textColor = color
    }
}
