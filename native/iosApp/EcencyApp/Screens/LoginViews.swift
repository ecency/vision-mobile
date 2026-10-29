import CoreImage.CIFilterBuiltins
import EcencyCore
import SwiftUI
import WebKit

/// The login screen (RN screens/login): username plus master password or private key, HiveSigner in an in-app
/// web view, HiveAuth / Keychain with a QR code. Errors show RN's "Login Failed" alert. Cancel keeps browsing.
struct LoginSheet: View {
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var session: Observed<SessionState>
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.appTheme) private var theme
    @State private var username = ""
    @State private var secret = ""
    @State private var failure: String?
    @State private var hiveSigner = false
    @State private var hiveAuth = false

    private var canSubmit: Bool {
        !session.state.isSigningIn && KeyLogin.shared.formFilled(usernameInput: username, secret: secret)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    // README "Esie": a wave hello, small so the form stays in view
                    EsieView(.login).frame(maxWidth: .infinity)
                    Text(LoginCopy.shared.SUBTITLE).foregroundStyle(theme.secondary)
                    HStack {
                        Text("@").foregroundStyle(theme.secondary).accessibilityHidden(true)
                        TextField(LoginCopy.shared.USERNAME, text: $username)
                            .textInputAutocapitalization(.never).autocorrectionDisabled().textContentType(.username)
                            .accessibilityIdentifier("login-username")
                    }
                    .leftToRightField()
                    .padding(12).overlay(RoundedRectangle(cornerRadius: 8).stroke(theme.border))
                    // at accessibility sizes the long hint ("password / private key") is a wrapped label above the
                    // field: a one line placeholder was clipped
                    if typeSize.isAccessibilitySize {
                        Text(LoginCopy.shared.PASSWORD).font(.subheadline).foregroundStyle(theme.secondary).accessibilityHidden(true)
                    }
                    SecureField(typeSize.isAccessibilitySize ? "" : LoginCopy.shared.PASSWORD, text: $secret)
                        .accessibilityLabel(LoginCopy.shared.PASSWORD)
                        // no .password content type: iCloud Keychain must not offer to save a master password or a key
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .leftToRightField()
                        .padding(12).overlay(RoundedRectangle(cornerRadius: 8).stroke(theme.border))
                        .accessibilityIdentifier("login-key")
                    Button { app.sheetURL(URL(string: LoginCopy.shared.TERMS_URL)!) } label: {
                        Text(LoginCopy.shared.TERMS).font(.footnote).foregroundStyle(theme.secondary).multilineTextAlignment(.leading)
                    }
                    Button(action: signIn) {
                        Group {
                            if session.state.isSigningIn { ProgressView().tint(.white) } else { Text(LoginCopy.shared.LOGIN).bold() }
                        }
                        .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent).tint(theme.accent).disabled(!canSubmit)
                    .accessibilityIdentifier("login-submit")
                    HStack { Rectangle().fill(theme.border).frame(height: 1).accessibilityHidden(true); Text(LoginCopy.shared.OR).font(.caption).foregroundStyle(theme.secondary); Rectangle().fill(theme.border).frame(height: 1).accessibilityHidden(true) }
                    Button { hiveSigner = true } label: { Text(LoginCopy.shared.HIVESIGNER).frame(maxWidth: .infinity, minHeight: 40) }
                        .buttonStyle(.subtleFill).accessibilityIdentifier("login-hivesigner")
                    Button { hiveAuth = true } label: { Text(LoginCopy.shared.HIVEAUTH).frame(maxWidth: .infinity, minHeight: 40) }
                        .buttonStyle(.subtleFill).accessibilityIdentifier("login-hiveauth")
                    AdaptiveStack(horizontalAlignment: .center, spacing: 4) {
                        Text(LoginCopy.shared.NO_ACCOUNT).foregroundStyle(theme.secondary).multilineTextAlignment(.center)
                        Button { app.nextSheet = .register(""); dismiss() } label: { Text(LoginCopy.shared.SIGN_UP).bold().frame(minHeight: minTapTarget) }.accessibilityIdentifier("login-signup")
                    }
                    .font(.subheadline).frame(maxWidth: .infinity)
                }
                .padding(20)
            }
            .background(theme.background)
            .navigationTitle(LoginCopy.shared.TITLE)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(UiCopy.shared.CANCEL) { dismiss() }.accessibilityIdentifier("login-cancel") }
                // secondary: a plain trailing item (a confirmation action is drawn bold and reads as a second
                // primary next to the filled LOGIN button)
                ToolbarItem(placement: .topBarTrailing) { Button(LoginCopy.shared.JOIN) { app.nextSheet = .register(""); dismiss() }.fontWeight(.regular).accessibilityIdentifier("login-join") }
            }
        }
        .onAppear {
            if let prefill = session.state.loginPrefill { username = prefill }
            // a HiveAuth sign-in already running (debug launch option) shows its sheet
            if session.state.hiveAuth != nil { hiveAuth = true }
            app.env.session.consumeLoginPrefill()
        }
        .alert(LoginCopy.shared.FAILED_TITLE, isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil; app.env.session.clearError() } })) {
            Button(UiCopy.shared.OK, role: .cancel) {}
        } message: {
            Text(" \(failure ?? "")\n\(LoginCopy.shared.FAILED_BODY)")
        }
        .fullScreenCover(isPresented: $hiveSigner) {
            HiveSignerLoginView { code in
                hiveSigner = false
                guard let code else { return }
                Task {
                    do { _ = try await app.env.session.loginWithHiveSignerCode(code: code); dismiss() } catch { failure = ErrorText.message(error) }
                }
            }
        }
        .sheet(isPresented: $hiveAuth) {
            HiveAuthLoginView(initialUsername: username) { hiveAuth = false; dismiss() }
                .environmentObject(app).environmentObject(session)
        }
    }

    private func signIn() {
        Task {
            do {
                _ = try await app.env.session.loginWithKey(username: username, secret: secret)
                secret = ""
                dismiss()
            } catch {
                failure = ErrorText.message(error)
            }
        }
    }
}

/// RN hiveSigner.tsx: HiveSigner's authorize page; the loopback redirect's code ends it (nil: closed or error).
struct HiveSignerLoginView: View {
    let onFinish: (String?) -> Void
    @State private var error: String?

    var body: some View {
        NavigationStack {
            HiveSignerWebView(url: HiveSigner.shared.authorizeUrl(config: HiveSignerConfig.companion.RN)) { url in
                switch HiveSigner.shared.parseCallback(url: url, config: HiveSignerConfig.companion.RN) {
                case let code as HiveSignerCallbackCode: onFinish(code.code)
                case let failure as HiveSignerCallbackError: error = failure.description_ ?? failure.error
                default: onFinish(nil)
                }
            }
            .ignoresSafeArea(edges: .bottom)
            .navigationTitle(LoginCopy.shared.TITLE)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(UiCopy.shared.CLOSE) { onFinish(nil) }.accessibilityIdentifier("hivesigner-close") } }
            .alert(UiCopy.shared.FAIL, isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil; onFinish(nil) } })) {
                Button(UiCopy.shared.OK, role: .cancel) {}
            } message: { Text(error ?? "") }
        }
    }
}

/// HiveSigner's pages in a WKWebView: a non-persistent data store (no session outlives it), no script
/// handlers, no new windows; only https on HiveSigner's host loads, the redirect URI goes to `onRedirect` and is
/// never loaded, everything else is cancelled (`HiveSignerPage`).
struct HiveSignerWebView: UIViewRepresentable {
    let url: String
    let onRedirect: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onRedirect: onRedirect) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: config)
        view.navigationDelegate = context.coordinator
        view.uiDelegate = context.coordinator
        view.accessibilityIdentifier = "hivesigner-web"
        if HiveSignerPage.shared.decide(url: url, config: HiveSignerConfig.companion.RN) == .load_, let target = URL(string: url) {
            view.load(URLRequest(url: target))
        }
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let onRedirect: (String) -> Void
        private var answered = false
        init(onRedirect: @escaping (String) -> Void) { self.onRedirect = onRedirect }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = action.request.url?.absoluteString else { return decisionHandler(.cancel) }
            switch HiveSignerPage.shared.decide(url: url, config: HiveSignerConfig.companion.RN) {
            case .load_: decisionHandler(.allow)
            case .redirect:
                decisionHandler(.cancel)
                if !answered { answered = true; onRedirect(url) }
            default: decisionHandler(.cancel)
            }
        }

        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                     for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? { nil }
    }
}

/// RN hiveAuthModal: username, the wallet request as a QR code plus "Open wallet app", the relay's countdown and
/// the result. Closing cancels a waiting request.
struct HiveAuthLoginView: View {
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var session: Observed<SessionState>
    @Environment(\.appTheme) private var theme
    @State var username: String
    @State private var walletMissing = false
    let onSignedIn: () -> Void
    @Environment(\.dismiss) private var dismiss

    init(initialUsername: String, onSignedIn: @escaping () -> Void) {
        _username = State(initialValue: initialUsername)
        self.onSignedIn = onSignedIn
    }

    var body: some View {
        NavigationStack {
            // scrolls when large text needs more than the screen (the QR code is 220 pt)
            ScrollView {
            VStack(spacing: 14) {
                let progress = session.state.hiveAuth
                switch progress?.step {
                case .none:
                    TextField(LoginCopy.shared.USERNAME, text: $username)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .leftToRightField()
                        .padding(12).overlay(RoundedRectangle(cornerRadius: 8).stroke(theme.border))
                        .accessibilityIdentifier("hiveauth-username")
                    Button { app.env.session.startHiveAuth(usernameInput: username) } label: {
                        Text(LoginCopy.shared.HIVEAUTH).bold().frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent).tint(theme.accent).disabled(KeyInput.shared.edges(input: username).count < 3)
                    .accessibilityIdentifier("hiveauth-start")
                case .waiting?:
                    let link = progress?.link ?? ""
                    Text(HiveAuthCopy.shared.AUTHENTICATING).bold()
                    QrCodeView(text: link).frame(width: 220, height: 220).padding(8).background(Color.white)
                    Text(HiveAuthCopy.shared.SCAN).font(.footnote).foregroundStyle(theme.secondary).multilineTextAlignment(.center)
                    CountdownText(expiresAtMillis: progress?.expiresAtMillis ?? 0)
                    if walletMissing { Text(HiveAuthCopy.shared.NOT_INSTALLED).font(.footnote).foregroundStyle(theme.dangerText).announces(HiveAuthCopy.shared.NOT_INSTALLED).accessibilityIdentifier("hiveauth-no-wallet") }
                    Button { open(link) } label: { Text(HiveAuthCopy.shared.OPEN_WALLET).bold().frame(maxWidth: .infinity, minHeight: 44) }
                        .buttonStyle(.borderedProminent).tint(theme.accent).accessibilityIdentifier("hiveauth-open-wallet")
                        // once per relay request (model state): a remount or relock never brings the wallet up again
                        .task(id: progress?.requestId) {
                            if progress?.openWallet == true {
                                app.env.session.walletOpened()
                                open(link)
                            }
                        }
                case .success?:
                    Image(appIcon: .checkCircle, filled: true).font(.system(size: 52)).foregroundStyle(.green).accessibilityHidden(true)
                    Text(HiveAuthCopy.shared.SUCCESS).announces(HiveAuthCopy.shared.SUCCESS).accessibilityIdentifier("hiveauth-success")
                        .task {
                            try? await Task.sleep(nanoseconds: 1_500_000_000)
                            app.env.session.cancelHiveAuth()
                            onSignedIn()
                        }
                case .failed?:
                    Text(progress?.error?.message ?? AuthUiCopy.shared.AUTHENTICATION_FAILED).foregroundStyle(theme.dangerText).multilineTextAlignment(.center)
                        .announces(progress?.error?.message ?? AuthUiCopy.shared.AUTHENTICATION_FAILED)
                        .accessibilityIdentifier("hiveauth-error")
                    Button(UiCopy.shared.TRY_AGAIN) { app.env.session.startHiveAuth(usernameInput: progress?.username ?? username) }
                default:
                    ProgressView()
                    Text(progress?.step == .loggingIn ? HiveAuthCopy.shared.LOGGING_IN : HiveAuthCopy.shared.INITIATING).foregroundStyle(theme.secondary)
                }
            }
            .padding(20)
            }
            .scrollBounceBehavior(.basedOnSize)
            .background(theme.background)
            .navigationTitle(HiveAuthCopy.shared.TITLE)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(UiCopy.shared.CANCEL) { app.env.session.cancelHiveAuth(); dismiss() }.accessibilityIdentifier("hiveauth-cancel") } }
        }
        .interactiveDismissDisabled()
    }

    private func open(_ link: String) {
        guard let url = URL(string: link) else { return }
        UIApplication.shared.open(url) { ok in walletMissing = !ok }
    }
}

/// A QR code of `text` (CoreImage), drawn without smoothing.
struct QrCodeView: View {
    let text: String

    var body: some View {
        if let image = QrCodeView.image(text) {
            Image(uiImage: image).interpolation(.none).resizable().scaledToFit()
                .accessibilityLabel(AuthUiCopy.shared.QR_CODE).keepsColorsInverted().accessibilityIdentifier("hiveauth-qr")
        }
    }

    static func image(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// "Expires in m:ss" until `expiresAtMillis`.
struct CountdownText: View {
    let expiresAtMillis: Int64
    @Environment(\.appTheme) private var theme

    var body: some View {
        if expiresAtMillis > 0 {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                let left = max(0, Int64(Double(expiresAtMillis) - context.date.timeIntervalSince1970 * 1000) / 1000)
                Text(UiCopy.shared.expiresIn(secondsLeft: left))
                    .font(.caption).foregroundStyle(theme.secondary).accessibilityIdentifier("hiveauth-expiry")
            }
        }
    }
}
