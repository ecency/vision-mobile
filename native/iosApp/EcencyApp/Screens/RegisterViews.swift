import EcencyCore
import SwiftUI
import UIKit
import WebKit

/// RN registerScreen over the shared `RegisterModel`: username (rules, then the availability lookup), email,
/// optional referral, Continue, and "Get Hive Account" with the free signup behind the Turnstile challenge.
/// The paid card says it is unavailable (no in-app purchases in this build) and has no button.
struct RegisterSheet: View {
    @EnvironmentObject private var app: AppContainer
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appTheme) private var theme
    @StateObject private var observed: Observed<RegisterState>

    init(referral: String, env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.registerModel(referral: referral), ownsModel: true))
    }

    private var model: RegisterModel { observed.model as! RegisterModel }
    private var copy: RegisterCopy { RegisterCopy.shared }

    var body: some View {
        let state = observed.state
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(copy.TITLE_DESCRIPTION).foregroundStyle(theme.secondary)
                    field(copy.USERNAME, text: Binding(get: { state.username }, set: { model.setUsername(value: $0) }), valid: state.usernameValid, id: "register-username", prefix: "@")
                    if !state.usernameError.isEmpty {
                        Text(state.usernameError).font(.footnote).foregroundStyle(theme.dangerText).announces(state.usernameError, debounce: true).accessibilityIdentifier("register-username-error")
                    }
                    field(copy.MAIL, text: Binding(get: { state.email }, set: { model.setEmail(value: $0) }), valid: state.emailValid, id: "register-email", keyboard: .emailAddress)
                    field(copy.REF_USER, text: Binding(get: { state.referral }, set: { model.setReferral(value: $0) }), valid: state.referralValid, id: "register-referral")
                    Button { app.sheetURL(URL(string: LoginCopy.shared.TERMS_URL)!) } label: {
                        Text(copy.FORM_DESCRIPTION).font(.footnote).foregroundStyle(theme.secondary).multilineTextAlignment(.leading)
                    }
                    Button { model.continueToOptions() } label: { Text(copy.CONTINUE).bold().frame(maxWidth: .infinity, minHeight: 44) }
                        .buttonStyle(.borderedProminent).tint(theme.accent).disabled(!state.canContinue)
                        .accessibilityIdentifier("register-continue")
                    AdaptiveStack(horizontalAlignment: .center, spacing: 4) {
                        Text(copy.HAVE_ACCOUNT).foregroundStyle(theme.secondary).multilineTextAlignment(.center)
                        // RN navigation.replace(LOGIN): the login sheet follows once this one is gone
                        Button { app.nextSheet = .login; dismiss() } label: { Text(copy.LOGIN_HERE).bold().frame(minHeight: minTapTarget) }.accessibilityIdentifier("register-login")
                    }
                    .font(.subheadline).frame(maxWidth: .infinity)
                }
                .padding(20)
            }
            .background(theme.background)
            .navigationTitle(copy.TITLE)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(UiCopy.shared.CANCEL) { dismiss() }.accessibilityIdentifier("register-cancel") } }
        }
        .accessibilityIdentifier("register")
        .fullScreenCover(isPresented: Binding(get: { state.optionsOpen }, set: { if !$0 { model.closeOptions() } })) {
            RegisterOptionsView(state: observed.state, model: model, standIn: app.env.captchaStandIn) {
                RegisterSheet.openInbox()
                dismiss()
            }
            .alert(state.alert?.title ?? "", isPresented: Binding(get: { observed.state.alert != nil }, set: { if !$0 { model.dismissAlert() } })) {
                Button(UiCopy.shared.OK, role: .cancel) {}
            } message: { Text(observed.state.alert?.body ?? "") }
        }
        .alert(state.alert?.title ?? "", isPresented: Binding(get: { state.alert != nil && !state.optionsOpen }, set: { if !$0 { model.dismissAlert() } })) {
            Button(UiCopy.shared.OK, role: .cancel) {}
        } message: { Text(state.alert?.body ?? "") }
    }

    private func field(_ placeholder: String, text: Binding<String>, valid: Bool, id: String, prefix: String? = nil, keyboard: UIKeyboardType = .default) -> some View {
        HStack {
            if let prefix { Text(prefix).foregroundStyle(theme.secondary).accessibilityHidden(true) }
            TextField(placeholder, text: text)
                .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(keyboard)
                .accessibilityIdentifier(id)
        }
        .leftToRightField()
        .padding(12).overlay(RoundedRectangle(cornerRadius: 8).stroke(valid ? theme.border : Color.red))
    }

    /// RN `openInbox` (react-native-email-link): the system mail app.
    static func openInbox() {
        if let url = URL(string: "message://") { UIApplication.shared.open(url, options: [:], completionHandler: nil) }
    }
}

/// RN registerAccountModal ("Get Hive Account").
private struct RegisterOptionsView: View {
    let state: RegisterState
    let model: RegisterModel
    let standIn: String?
    let onContinue: () -> Void
    @Environment(\.appTheme) private var theme

    private var copy: RegisterCopy { RegisterCopy.shared }

    var body: some View {
        NavigationStack {
            Group {
                if state.isRegistering || state.isRegistered {
                    VStack(spacing: 16) {
                        if state.isRegistered {
                            Image(appIcon: .checkCircle, filled: true).font(.system(size: 56)).foregroundStyle(.green).accessibilityHidden(true)
                        } else {
                            ProgressView().controlSize(.large)
                        }
                        Text(state.isRegistered ? copy.REGISTERED : copy.REGISTERING).multilineTextAlignment(.center)
                            .announces(state.isRegistered ? copy.REGISTERED : copy.REGISTERING)
                            .accessibilityIdentifier("register-progress")
                        if state.isRegistered {
                            Button(copy.CONTINUE, action: onContinue).buttonStyle(.borderedProminent).tint(theme.accent).accessibilityIdentifier("register-done")
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity).padding(24)
                } else if state.productsLoading {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            VStack(alignment: .leading) {
                                Text(state.username).font(.headline).lineLimit(1)
                                Text(state.email).font(.subheadline).foregroundStyle(theme.secondary).lineLimit(1)
                            }
                            // RN `disableFree`: a purchase link sells only the paid account
                            if !state.purchaseOnly { card(copy.FREE_TITLE, copy.FREE_DESC) {
                                if let standIn {
                                    // debug fake-data runs: the hosted challenge is never loaded
                                    Text("Demo run: the verification step is skipped.").font(.footnote).foregroundStyle(theme.secondary)
                                        .accessibilityIdentifier("captcha-stand-in")
                                        .task(id: state.captchaKey) { model.captchaVerified(token: standIn) }
                                } else {
                                    TurnstileView { event in
                                        switch event {
                                        case let verified as TurnstilePageEventVerified: model.captchaVerified(token: verified.token)
                                        case is TurnstilePageEventExpired: model.captchaExpired()
                                        default: model.captchaFailed()
                                        }
                                    }
                                    .id(state.captchaKey)
                                    .frame(height: 76)
                                }
                                Button(copy.FREE_BUTTON) { model.registerFree() }.bold().disabled(!state.canRegisterFree)
                                    .accessibilityIdentifier("register-free")
                            } }
                            // RN maps the store's products: no card when the store does not sell the paid account here
                            if let label = state.paidButton {
                                card(copy.BUY_TITLE, copy.BUY_DESC) {
                                    Button(label) { model.buyAccount() }.bold().disabled(state.isRegistering).accessibilityIdentifier("register-buy")
                                }
                            }
                        }
                        .padding(20)
                    }
                }
            }
            .background(theme.background)
            .navigationTitle(copy.MODAL_TITLE)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(UiCopy.shared.CLOSE) { model.closeOptions() }.disabled(state.isRegistering).accessibilityIdentifier("register-options-close")
                }
            }
        }
        .accessibilityIdentifier("register-options")
    }

    private func card<Content: View>(_ title: String, _ description: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.title3.bold()).accessibilityAddTraits(.isHeader)
            Text(description).font(.subheadline).foregroundStyle(theme.secondary)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16).overlay(RoundedRectangle(cornerRadius: 12).stroke(theme.border))
    }
}

/// RN turnstileWebView: Ecency's hosted challenge page in a web view that may only show that page and
/// Cloudflare's challenge (`TurnstilePage.allowsLoad`). The page posts to `window.ReactNativeWebView`, which a
/// document-start script maps onto one message handler; a message counts only from the main frame while the
/// top document is the embed page. A failed load shows a manual retry and asks for no new challenge by itself.
struct TurnstileView: View {
    let onEvent: (TurnstilePageEvent) -> Void
    @State private var failed = false
    @Environment(\.appTheme) private var theme

    var body: some View {
        if failed {
            VStack(spacing: 6) {
                Text(RegisterCopy.shared.CAPTCHA_LOAD_FAILED).font(.footnote).foregroundStyle(theme.secondary)
                Button(RegisterCopy.shared.RETRY) { failed = false }.font(.subheadline.weight(.semibold)).accessibilityIdentifier("captcha-retry")
            }
            .frame(maxWidth: .infinity)
        } else {
            TurnstileWeb(onEvent: onEvent, onLoadFailed: { failed = true }).accessibilityIdentifier("captcha")
        }
    }
}

private struct TurnstileWeb: UIViewRepresentable {
    let onEvent: (TurnstilePageEvent) -> Void
    let onLoadFailed: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onEvent: onEvent, onLoadFailed: onLoadFailed) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        // nothing of the challenge outlives it, and it shares no cookies with the app's other web views
        config.websiteDataStore = .nonPersistent()
        let bridge = "window.\(TurnstilePage.shared.BRIDGE) = { postMessage: function (d) { window.webkit.messageHandlers.turnstile.postMessage(String(d)); } };"
        config.userContentController.addUserScript(WKUserScript(source: bridge, injectionTime: .atDocumentStart, forMainFrameOnly: true))
        config.userContentController.add(WeakHandler(context.coordinator), name: "turnstile")
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        let view = WKWebView(frame: .zero, configuration: config)
        view.navigationDelegate = context.coordinator
        view.isOpaque = false
        view.backgroundColor = .clear
        view.scrollView.isScrollEnabled = false
        if let url = URL(string: TurnstilePage.shared.URL) { view.load(URLRequest(url: url)) }
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}

    static func dismantleUIView(_ view: WKWebView, coordinator: Coordinator) {
        view.stopLoading()
        view.configuration.userContentController.removeScriptMessageHandler(forName: "turnstile")
    }

    /// The content controller keeps its handler strongly; this breaks the cycle.
    final class WeakHandler: NSObject, WKScriptMessageHandler {
        weak var target: Coordinator?
        init(_ target: Coordinator) { self.target = target }
        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            target?.received(message)
        }
    }

    final class Coordinator: NSObject, WKNavigationDelegate {
        let onEvent: (TurnstilePageEvent) -> Void
        let onLoadFailed: () -> Void

        init(onEvent: @escaping (TurnstilePageEvent) -> Void, onLoadFailed: @escaping () -> Void) {
            self.onEvent = onEvent
            self.onLoadFailed = onLoadFailed
        }

        func received(_ message: WKScriptMessage) {
            guard message.frameInfo.isMainFrame, TurnstilePage.shared.acceptsMessagesFrom(topUrl: message.webView?.url?.absoluteString),
                  let event = TurnstilePage.shared.parse(message: message.body as? String) else { return }
            onEvent(event)
        }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            let top = action.targetFrame?.isMainFrame ?? true
            let allowed = TurnstilePage.shared.allowsLoad(url: action.request.url?.absoluteString ?? "", isTopFrame: KotlinBoolean(bool: top))
            decisionHandler(allowed ? .allow : .cancel)
        }

        func webView(_ webView: WKWebView, decidePolicyFor response: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
            if response.isForMainFrame, let http = response.response as? HTTPURLResponse, http.statusCode >= 400 {
                decisionHandler(.cancel)
                onLoadFailed()
            } else {
                decisionHandler(.allow)
            }
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { onLoadFailed() }
        func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { onLoadFailed() }
        // iOS kills the web content process under memory pressure, leaving a blank widget (RN): offer the retry
        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) { onLoadFailed() }
    }
}

/// RN referScreen's button content: the label, then the share icon, centred. Not a Label: inside a List row it lays
/// its icon out in the row's icon column, which left an empty gap before the text.
struct ReferShareLabel: View {
    let title: String

    var body: some View {
        HStack(spacing: 12) {
            Text(title).bold()
            Image(appIcon: .share).font(.body.weight(.semibold)).accessibilityHidden(true)
        }
        .padding(.horizontal, 12).frame(minWidth: 160, minHeight: 44)
    }
}

/// RN referScreen ("Refer & Earn"): points, the Refer button (the signup link through the share sheet) and
/// the referred accounts. Rows are drawn only while the list's account is the signed-in one.
struct ReferScreen: View {
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var session: Observed<SessionState>
    @Environment(\.appTheme) private var theme
    @StateObject private var observed: Observed<ReferState>

    init(env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.referModel(), ownsModel: true))
    }

    private var model: ReferModel { observed.model as! ReferModel }
    private var copy: ReferCopy { ReferCopy.shared }

    var body: some View {
        let state = observed.state
        let mine = state.account != nil && state.account == session.state.username
        let rows = mine ? state.rows : []
        List {
            VStack(spacing: 20) {
                AdaptiveStack(horizontalAlignment: .center) {
                    points(mine ? state.earnedPoints : 0, copy.EARNED, id: "refer-earned")
                    points(mine ? state.pendingPoints : 0, copy.PENDING, id: "refer-pending")
                }
                Button {
                    if mine, let url = state.shareUrl { SystemShare.present(url) }
                } label: {
                    ReferShareLabel(title: copy.REFER)
                }
                .buttonStyle(.borderedProminent).tint(theme.accent).disabled(!mine).accessibilityIdentifier("refer-share")
            }
            .frame(maxWidth: .infinity).padding(.vertical, 12).listRowSeparator(.hidden)

            ForEach(rows, id: \.id) { row in
                AdaptiveStack(spacing: 10) {
                    Button("$$") { _ = app.env.ui.toast(text: row.rewardHint) }
                        .buttonStyle(.borderless).bold().foregroundStyle(row.isRewarded ? theme.accentText : theme.secondary)
                        .frame(minWidth: minTapTarget, minHeight: minTapTarget)
                        .accessibilityLabel(row.rewardHint).accessibilityIdentifier("referral-mark-\(row.username)")
                    Button { app.sheet = .quickProfile(row.username) } label: {
                        HStack {
                            AvatarView(url: PostCard.companion.avatarUrl(account: row.username, proxyBase: app.env.proxyBase, size: "small"), size: 36)
                            VStack(alignment: .leading) {
                                Text(row.username).bold().foregroundStyle(theme.text)
                                if let millis = row.createdMillis?.int64Value {
                                    Text(LocaleFormat.shared.date(epochMillis: millis)).font(.caption).foregroundStyle(theme.secondary)
                                }
                            }
                            Spacer()
                        }
                    }
                    .buttonStyle(.borderless).accessibilityIdentifier("referral-\(row.username)")
                    Button(copy.DELEGATE_HP) { app.push(AppScreenCompanion.shared.of(route: .transfer, arg: row.username)) }
                        .buttonStyle(.borderless).font(.footnote.bold()).accessibilityIdentifier("referral-delegate-\(row.username)")
                }
                .onAppear { if row.id == rows.last?.id { model.loadMore() } }
            }

            Group {
                if state.isLoading || state.isLoadingMore {
                    ProgressView().frame(maxWidth: .infinity)
                } else if let error = state.error {
                    Button(UiCopy.shared.errorTryAgain(message: error.message)) { if state.loaded { model.loadMore() } else { model.retry() } }
                        .frame(maxWidth: .infinity).accessibilityIdentifier("refer-retry")
                } else if mine && state.isEmpty {
                    Text(copy.EMPTY).foregroundStyle(theme.secondary).multilineTextAlignment(.center).frame(maxWidth: .infinity)
                        .accessibilityIdentifier("refer-empty")
                }
            }
            .frame(minHeight: 72).listRowSeparator(.hidden)
        }
        .listStyle(.plain)
        .refreshable { model.refresh() }
        .navigationTitle(copy.TITLE)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.start() }
        .accessibilityIdentifier("refer")
    }

    private func points(_ value: Int32, _ label: String, id: String) -> some View {
        VStack {
            Text(Fmt.int(value)).font(.largeTitle.bold()).foregroundStyle(theme.accentText)
            Text(label).font(.subheadline).foregroundStyle(theme.secondary)
        }
        .frame(maxWidth: .infinity)
        // one stop: the label, then the number as its value; the identifier stays on that stop
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityValue(Fmt.int(value))
        .accessibilityIdentifier(id)
    }
}
