import AVFoundation
import EcencyCore
import SwiftUI
import UIKit
import WebKit

// MARK: - RN screens/dappBrowser: the browser with the Hive signing bridge

/// Answers one bridge request into the frame that asked: only while the web view still shows that origin, through
/// the bridge script's receiver in the page's world (the answer is a JSON string literal, never script).
final class BridgeReplier: NSObject, DappBrowserModelBridgeReply {
    private weak var webView: WKWebView?
    private let origin: String

    init(webView: WKWebView, origin: String) {
        self.webView = webView
        self.origin = origin
    }

    func send(answer: String) {
        DispatchQueue.main.async { [weak webView, origin] in
            guard let webView, BrowserPolicy.shared.httpsOrigin(url: webView.url?.absoluteString ?? "") == BrowserPolicy.shared.httpsOrigin(url: origin) else { return }
            guard let data = try? JSONEncoder().encode(answer), let literal = String(data: data, encoding: .utf8) else { return }
            webView.evaluateJavaScript("window.\(DappBridgeScript.shared.RECEIVER)(\(literal)); true", in: nil, in: .page) { _ in }
        }
    }
}

/// The browser's web views, one per tab, owned by the screen: they keep their pages while other tabs show.
/// Explore has its own persistent website data store, apart from HiveSigner's pages (non-persistent) and the players.
@MainActor
final class DappWebViews: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate {
    let model: DappBrowserModel
    private var views: [Int32: WKWebView] = [:]
    private var loaded: [Int32: Int32] = [:]
    private var observers: [Int32: [NSKeyValueObservation]] = [:]
    private let handler = WeakMessageHandler()
    /// Debug runs only (`-openScreen dapp`): the synthetic dApp loads in place of its URL.
    static var demo = false

    /// One identifier for Explore's store, so it persists across launches (iOS 17).
    static let storeId = UUID(uuidString: "4E1C5B9A-2E7D-4C55-9A0B-7D6E3C1F8A42")!
    static var dataStore: WKWebsiteDataStore { WKWebsiteDataStore(forIdentifier: storeId) }

    init(model: DappBrowserModel) {
        self.model = model
        super.init()
        handler.owner = self
    }

    func view(for tab: BrowserTab) -> WKWebView {
        if let existing = views[tab.id] { return existing }
        let config = WKWebViewConfiguration()
        config.websiteDataStore = Self.dataStore
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        let controller = WKUserContentController()
        // the bridge: top frame, https only (the script checks too), in the page's world where dApps look for it
        controller.addUserScript(WKUserScript(source: DappBridgeScript.shared.source(platform: .ios), injectionTime: .atDocumentStart,
                                              forMainFrameOnly: true, in: .page))
        controller.add(handler, contentWorld: .page, name: DappBridgeScript.shared.CHANNEL)
        config.userContentController = controller
        let view = WKWebView(frame: .zero, configuration: config)
        view.navigationDelegate = self
        view.uiDelegate = self
        view.allowsBackForwardNavigationGestures = true
        view.accessibilityIdentifier = "dapp-page"
        let id = tab.id
        observers[id] = [
            view.observe(\.url) { [weak self] v, _ in MainActor.assumeIsolated { self?.report(id, v) } },
            view.observe(\.title) { [weak self] v, _ in MainActor.assumeIsolated { self?.report(id, v) } },
            view.observe(\.canGoBack) { [weak self] v, _ in MainActor.assumeIsolated { self?.report(id, v) } },
            view.observe(\.canGoForward) { [weak self] v, _ in MainActor.assumeIsolated { self?.report(id, v) } },
            view.observe(\.estimatedProgress) { [weak self] v, _ in
                MainActor.assumeIsolated { self?.model.progress(tabId: id, value: Float(v.estimatedProgress)) }
            },
        ]
        views[id] = view
        return view
    }

    func webView(_ id: Int32) -> WKWebView? { views[id] }

    /// Loads [tab]'s URL when the app asked for it ([BrowserTab.load] changed since the last load).
    func loadIfAsked(_ tab: BrowserTab) {
        guard let url = tab.url, let view = views[tab.id], loaded[tab.id] != tab.load else { return }
        loaded[tab.id] = tab.load
        #if DEBUG
        if Self.demo && url == DemoDapp.shared.URL {
            view.loadHTMLString(DemoDapp.shared.HTML, baseURL: URL(string: url))
            return
        }
        #endif
        if let target = URL(string: url) { view.load(URLRequest(url: target)) }
    }

    /// Tabs that closed: their views go.
    func retain(_ ids: Set<Int32>) {
        for (id, view) in views where !ids.contains(id) { destroy(id, view) }
    }

    func closeAll() {
        for (id, view) in views { destroy(id, view) }
    }

    private func destroy(_ id: Int32, _ view: WKWebView) {
        view.stopLoading()
        view.configuration.userContentController.removeAllScriptMessageHandlers()
        view.navigationDelegate = nil
        view.uiDelegate = nil
        observers[id] = nil
        views[id] = nil
        loaded[id] = nil
    }

    private func report(_ id: Int32, _ view: WKWebView) {
        model.pageChanged(tabId: id, url: view.url?.absoluteString, title: view.title, canGoBack: view.canGoBack, canGoForward: view.canGoForward)
    }

    private func tabId(of view: WKWebView?) -> Int32? { views.first { $0.value === view }?.key }

    /// A bridge message: the frame's origin as WebKit reports it (never what the page says), main frame or not.
    fileprivate func received(_ message: WKScriptMessage) {
        guard let body = Self.mainFrameBody(isMainFrame: message.frameInfo.isMainFrame, body: message.body),
              let webView = message.webView, let id = tabId(of: webView) else { return }
        let o = message.frameInfo.securityOrigin
        let origin = "\(o.protocol)://\(o.host)" + (o.port == 0 || o.port == 443 ? "" : ":\(o.port)")
        model.bridgeMessage(tabId: id, frameOrigin: origin, isMainFrame: message.frameInfo.isMainFrame, message: body,
                            reply: BridgeReplier(webView: webView, origin: origin))
    }

    // MARK: navigation

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
        guard let id = tabId(of: webView), let url = navigationAction.request.url?.absoluteString else { return decisionHandler(.cancel) }
        let main = navigationAction.targetFrame?.isMainFrame ?? true
        // the page that asked is the frame that started this navigation, as WebKit reports it (never the tab's URL,
        // which already follows a navigation that only started)
        let source = Self.sourceHost(navigationAction)
        let allowed = model.shouldLoad(tabId: id, url: url, isMainFrame: main, userGesture: navigationAction.navigationType == .linkActivated, sourceHost: source)
        decisionHandler(allowed ? .allow : .cancel)
    }

    /// A page's new window (target=_blank, window.open after a tap) opens in the same tab, as RN's WebView does.
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let id = tabId(of: webView), let url = navigationAction.request.url?.absoluteString,
           model.shouldLoad(tabId: id, url: url, isMainFrame: true, userGesture: navigationAction.navigationType == .linkActivated,
                            sourceHost: Self.sourceHost(navigationAction)) {
            webView.load(navigationAction.request)
        }
        return nil
    }

    /// The host of the frame that started [action]; nil when WebKit gives no source frame (it can be absent at run
    /// time although the Swift type is not optional, so it is read without trusting that).
    static func sourceHost(_ action: WKNavigationAction) -> String? {
        (action.value(forKey: "sourceFrame") as? WKFrameInfo)?.securityOrigin.host
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        if let id = tabId(of: webView) { model.pageCommitted(tabId: id, url: webView.url?.absoluteString) }
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        if (error as NSError).code == NSURLErrorCancelled { return }
        if let id = tabId(of: webView) { model.pageFailed(tabId: id) }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        if (error as NSError).code == NSURLErrorCancelled { return }
        if let id = tabId(of: webView) { model.pageFailed(tabId: id) }
    }

    /// No camera or microphone for pages.
    func webView(_ webView: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin, initiatedByFrame frame: WKFrameInfo,
                 type: WKMediaCaptureType, decisionHandler: @escaping @MainActor (WKPermissionDecision) -> Void) {
        decisionHandler(.deny)
    }

    /// A frame's message never reaches the model: this app-side check stands on its own, next to the shared
    /// model's (BrowserPolicy.bridgeOrigin), so neither depends on how the other was compiled.
    nonisolated static func mainFrameBody(isMainFrame: Bool, body: Any) -> String? {
        guard isMainFrame else { return nil }
        return body as? String
    }

    /// What dApps stored goes when no account is left or the data was wiped.
    static func clearData() {
        let store = dataStore
        store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast) {}
    }
}

/// WKUserContentController keeps its handlers strongly; this one points back weakly, so the views can go.
private final class WeakMessageHandler: NSObject, WKScriptMessageHandler {
    weak var owner: DappWebViews?

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        MainActor.assumeIsolated { owner?.received(message) }
    }
}

private struct WebPageView: UIViewRepresentable {
    let view: WKWebView

    func makeUIView(context: Context) -> UIView {
        let host = UIView()
        attach(host)
        return host
    }

    func updateUIView(_ host: UIView, context: Context) { attach(host) }

    private func attach(_ host: UIView) {
        guard view.superview !== host else { return }
        view.removeFromSuperview()
        view.frame = host.bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        host.addSubview(view)
    }
}

/// RN DappBrowser: header, progress, the page or the home screen, the tab bar; the bridge's confirmation sheet.
struct DappBrowserScreen: View {
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @StateObject private var observed: Observed<DappBrowserState>
    @StateObject private var views: DappWebViews
    @FocusState private var urlFocused: Bool

    init(initialUrl: String?, env: AppEnvironment) {
        let model = env.dappBrowserModel(url: initialUrl)
        _observed = StateObject(wrappedValue: Observed(model, ownsModel: true))
        _views = StateObject(wrappedValue: DappWebViews(model: model))
    }

    private var model: DappBrowserModel { observed.model as! DappBrowserModel }

    var body: some View {
        let state = observed.state
        let tab = state.activeTab
        VStack(spacing: 0) {
            header(state, tab)
            if !state.showsHome {
                GeometryReader { geo in
                    Rectangle().fill(theme.accentText).frame(width: geo.size.width * CGFloat(tab.progress), height: 2)
                }
                .frame(height: 2)
                .accessibilityHidden(true)
            }
            ZStack {
                if state.showsHome {
                    DappHomeView(text: state.urlText, model: model)
                } else if tab.hasError {
                    VStack(spacing: 12) {
                        Image(appIcon: .offline).font(.system(size: 56)).foregroundStyle(theme.icon).accessibilityHidden(true)
                        Text(BrowserCopy.shared.PAGE_LOAD_FAILED).scaledFont(16).foregroundStyle(theme.secondary).multilineTextAlignment(.center)
                            .announces(BrowserCopy.shared.PAGE_LOAD_FAILED)
                        Button(BrowserCopy.shared.RETRY) { model.retry() }.buttonStyle(.prominentFill).accessibilityIdentifier("browser-retry")
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    WebPageView(view: views.view(for: tab))
                        .id(tab.id)
                        .onAppear { views.loadIfAsked(tab) }
                        .onChange(of: tab.load) { _, _ in views.loadIfAsked(tab) }
                }
            }
            .frame(maxHeight: .infinity)
            tabBar(state)
        }
        .background(theme.background)
        .toolbar(.hidden, for: .navigationBar)
        .onChange(of: state.tabs.map { $0.id }) { _, ids in views.retain(Set(ids)) }
        .onChange(of: state.notice) { _, notice in
            if let notice { _ = app.env.ui.toast(text: notice); model.consumeNotice() }
        }
        .sheet(item: Binding(get: { state.prompt.map(PromptItem.init) }, set: { if $0 == nil { model.reject() } })) { item in
            BridgeConfirmSheet(prompt: item.prompt, onConfirm: { model.confirm() }, onReject: { model.reject() })
                .presentationDetents([.medium, .large])
        }
        .overlay {
            if state.working {
                ZStack { Color.black.opacity(0.2); ProgressView() }.ignoresSafeArea().accessibilityIdentifier("bridge-working")
            }
        }
    }

    private struct PromptItem: Identifiable {
        let prompt: BridgePrompt
        var id: Int64 { prompt.id }
    }

    @ViewBuilder
    private func header(_ state: DappBrowserState, _ tab: BrowserTab) -> some View {
        HStack(spacing: 2) {
            headerButton(state.showsHome ? .close : .home, state.showsHome ? UiCopy.shared.CLOSE : ShellUiCopy.shared.HOME, "browser-home") {
                if !model.home() { app.path = Array(app.path.dropLast()) }
            }
            if !state.showsHome {
                headerButton(.chevronLeft, UiCopy.shared.BACK, "browser-back", enabled: tab.canGoBack) { views.webView(tab.id)?.goBack() }
                headerButton(.chevronRight, ShellUiCopy.shared.FORWARD, "browser-forward", enabled: tab.canGoForward) { views.webView(tab.id)?.goForward() }
                HStack(spacing: 4) {
                    if !urlFocused && state.isSecure {
                        Image(appIcon: .lock, filled: true).scaledFont(12).foregroundStyle(Color(hex: 0x4FD688)).accessibilityHidden(true).accessibilityIdentifier("browser-lock")
                    }
                    TextField(Dapps.shared.SEARCH_PLACEHOLDER, text: Binding(get: { urlFocused ? state.urlText : state.displayUrl },
                                                                            set: { model.setUrlText(text: $0) }))
                        .focused($urlFocused)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .submitLabel(.go).onSubmit { model.submitUrl() }
                        .scaledFont(14)
                        .leftToRightField()
                        .accessibilityIdentifier("browser-url")
                    if urlFocused && !state.urlText.isEmpty {
                        Button { model.setUrlText(text: "") } label: { Image(appIcon: .clear, filled: true).foregroundStyle(theme.icon).tapTarget() }
                            .accessibilityLabel(UiCopy.shared.CLEAR)
                            .accessibilityIdentifier("browser-clear")
                    }
                }
                .padding(.horizontal, 10).frame(minHeight: 36)
                .background(RoundedRectangle(cornerRadius: 18).fill(theme.lightBackground))
                .onChange(of: urlFocused) { _, focused in model.setUrlFocused(focused: focused) }
                headerButton(.refresh, ShellUiCopy.shared.RELOAD, "browser-reload") { views.webView(tab.id)?.reload() }
            } else {
                Spacer()
            }
        }
        .padding(.horizontal, 4)
        .frame(minHeight: 52)
    }

    private func headerButton(_ icon: AppIcon, _ label: String, _ id: String, enabled: Bool = true, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(appIcon: icon).scaledFont(18, weight: .medium).foregroundStyle(enabled ? theme.text : theme.icon).frame(minWidth: minTapTarget, minHeight: minTapTarget)
                .dynamicTypeSize(...DynamicTypeSize.xxLarge)
        }
        .disabled(!enabled)
        .accessibilityLabel(label)
        .largeContent(label, appIcon: icon)
        .accessibilityIdentifier(id)
    }

    @ViewBuilder
    private func tabBar(_ state: DappBrowserState) -> some View {
        HStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(state.tabs, id: \.id) { t in
                        HStack(spacing: 6) {
                            // a button for VoiceOver (the whole tab also takes the tap)
                            Button { model.switchTab(id: t.id) } label: {
                                Text(t.title).scaledFont(12).foregroundStyle(theme.text).lineLimit(1).frame(maxWidth: 130)
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(t.id == state.activeTabId ? .isSelected : [])
                            if state.canCloseTabs {
                                Button { model.closeTab(id: t.id) } label: { Image(appIcon: .close).scaledFont(11).foregroundStyle(theme.icon).frame(minWidth: 32, minHeight: 32).contentShape(Rectangle()) }
                                    .accessibilityLabel(ShellUiCopy.shared.CLOSE_TAB)
                                    .accessibilityIdentifier("browser-close-tab-\(t.id)")
                            }
                        }
                        .padding(.horizontal, 10).padding(.vertical, 6)
                        .background(RoundedRectangle(cornerRadius: 8).fill(t.id == state.activeTabId ? theme.background : Color.clear))
                        .contentShape(Rectangle())
                        .onTapGesture { model.switchTab(id: t.id) }
                        .accessibilityElement(children: .contain)
                        .accessibilityIdentifier("browser-tab-\(t.id)")
                    }
                }
                .padding(.horizontal, 8)
            }
            Button { model.addTab() } label: { Image(appIcon: .add).scaledFont(20).foregroundStyle(theme.text).frame(width: 44, height: 44) }
                .accessibilityLabel(ShellUiCopy.shared.NEW_TAB)
                .largeContent(ShellUiCopy.shared.NEW_TAB, appIcon: .add)
                .accessibilityIdentifier("browser-add-tab")
        }
        .frame(minHeight: 48)
        .background(theme.lightBackground)
    }
}

/// RN _renderHome: the directory; a pick opens in this tab.
private struct DappHomeView: View {
    let text: String
    let model: DappBrowserModel
    @Environment(\.appTheme) private var theme
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text(Dapps.shared.TITLE).scaledFont(24, weight: .bold).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
                Text(Dapps.shared.SUBTITLE).scaledFont(14).foregroundStyle(theme.secondary).padding(.top, 4).padding(.bottom, 16)
                HStack {
                    Image(appIcon: .search).foregroundStyle(theme.icon).accessibilityHidden(true)
                    TextField(Dapps.shared.SEARCH_PLACEHOLDER, text: Binding(get: { text }, set: { model.setUrlText(text: $0) }))
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        .submitLabel(.go).onSubmit { model.submitUrl() }
                        .leftToRightField()
                        .accessibilityIdentifier("dapp-url")
                }
                .padding(12).background(RoundedRectangle(cornerRadius: 12).fill(theme.lightBackground))
                HStack(alignment: .top, spacing: 8) {
                    Image(appIcon: .info).foregroundStyle(theme.accentText).accessibilityHidden(true)
                    Text(Dapps.shared.SIGNING_INFO).scaledFont(13).foregroundStyle(theme.text)
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 12).fill(theme.accent.opacity(0.08)))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(theme.accent.opacity(0.3)))
                .padding(.top, 12)
                Text(Dapps.shared.POPULAR).scaledFont(17, weight: .bold).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader).padding(.top, 20).padding(.bottom, 12)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: typeSize.isAccessibilitySize ? 2 : 4), spacing: 20) {
                    ForEach(Dapps.shared.DIRECTORY, id: \.url) { dapp in
                        Button { model.navigate(input: dapp.url) } label: {
                            VStack(spacing: 4) {
                                ZStack {
                                    RoundedRectangle(cornerRadius: 16).fill(theme.lightBackground)
                                    Text(dapp.initial).scaledFont(18, weight: .bold).foregroundStyle(Color(hexString: dapp.color))
                                    LoadedImageView(src: dapp.icon, maxWidth: 36) { _ in }.frame(width: 36, height: 36)
                                }
                                .frame(width: 56, height: 56)
                                Text(dapp.name).scaledFont(11, weight: .medium).foregroundStyle(theme.text).lineLimit(1)
                                Text(dapp.category).scaledFont(9).foregroundStyle(theme.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(dapp.name)
                        .accessibilityValue(dapp.category)
                        .accessibilityAddTraits(.isButton)
                        .accessibilityIdentifier("dapp-\(dapp.name)")
                    }
                }
            }
            .padding(16)
        }
    }
}

/// RN SignConfirmSheet: what the page asks, from where (the web view's origin), with what key. Dismissing rejects.
struct BridgeConfirmSheet: View {
    let prompt: BridgePrompt
    let onConfirm: () -> Void
    let onReject: () -> Void
    @Environment(\.appTheme) private var theme
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        // the details scroll; Confirm and Cancel stay pinned below them with the phishing warning, so the answer and
        // the warning are always on screen, whatever the text size
        ScrollView {
        VStack(spacing: 0) {
            Image(appIcon: .verifiedUser, filled: true).font(.system(size: 40)).foregroundStyle(theme.accentText).padding(.top, 20).accessibilityHidden(true)
            Text(prompt.title).scaledFont(18, weight: .bold).foregroundStyle(theme.text).padding(.top, 12).accessibilityAddTraits(.isHeader).accessibilityIdentifier("bridge-title")
            HStack(spacing: 6) {
                Image(appIcon: .globe).foregroundStyle(theme.icon).accessibilityHidden(true)
                Text(prompt.host).scaledFont(13).foregroundStyle(theme.secondary).lineLimit(2).truncationMode(.middle).accessibilityIdentifier("bridge-host")
            }
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(Capsule().fill(theme.lightBackground))
            .padding(.top, 8).padding(.bottom, 16)
            VStack(spacing: 0) {
                    row(BrowserCopy.shared.ACCOUNT, "@\(prompt.username)")
                    row(BrowserCopy.shared.AUTHORITY, prompt.authority, id: "bridge-authority")
                    ForEach(Array(prompt.details.enumerated()), id: \.offset) { _, f in row(f.name, f.value) }
                    ForEach(Array(prompt.operations.enumerated()), id: \.offset) { _, op in
                        Text(op.title).scaledFont(14, weight: .bold).foregroundStyle(theme.text).frame(maxWidth: .infinity, alignment: .leading).padding(.top, 10)
                        ForEach(Array(op.fields.enumerated()), id: \.offset) { _, f in row(f.name, f.value, mono: true) }
                    }
            }
            .padding(.bottom, 16)
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 16)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 0) {
            if prompt.blacklisted != nil {
                HStack(alignment: .top, spacing: 8) {
                    Image(appIcon: .warning, filled: true).foregroundStyle(Color(hex: 0xE74C3C)).accessibilityHidden(true)
                    // RN's dark text on the fixed pink banner (its red text was 3.4:1 there)
                    Text(BrowserCopy.shared.PHISHING).scaledFont(13, weight: .semibold).foregroundStyle(Color(hex: 0x3C4449))
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 10).fill(Color(hex: 0xFDEDED)))
                .padding(.bottom, 12)
                .announces(BrowserCopy.shared.PHISHING)
                .accessibilityIdentifier("bridge-phishing")
            }
            Button(action: onConfirm) {
                Text(BrowserCopy.shared.CONFIRM).scaledFont(16, weight: .bold).frame(maxWidth: .infinity).frame(minHeight: 44)
            }
            .buttonStyle(.prominentFill)
            .accessibilityIdentifier("bridge-confirm")
            Button(BrowserCopy.shared.CANCEL, action: onReject).padding(.top, 10).accessibilityIdentifier("bridge-cancel")
            }
            .padding(.horizontal, 24).padding(.top, 12).padding(.bottom, 16)
            .background(theme.background)
        }
        .scrollBounceBehavior(.basedOnSize)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("bridge-sheet")
    }

    private func row(_ label: String, _ value: String, mono: Bool = false, id: String? = nil) -> some View {
        // every value shows whole (it is what gets signed); label above value at accessibility sizes and for long values
        let long = value.count > 80
        let stacked = typeSize.isAccessibilitySize || long
        return AdaptiveStack(threshold: long ? .xSmall : .stacksRows, verticalAlignment: .firstTextBaseline) {
            Text(label).scaledFont(14, weight: .medium).foregroundStyle(theme.secondary)
            FormRowGap(minLength: 12)
            Text(value).font(mono ? .system(.subheadline, design: .monospaced) : .subheadline.weight(.semibold)).foregroundStyle(theme.text)
                .fixedSize(horizontal: false, vertical: true).multilineTextAlignment(stacked ? .leading : .trailing)
                .accessibilityIdentifier(id ?? "bridge-row-\(label)")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 8)
    }
}

// MARK: - RN QR_SCAN

/// RN qrModalView: the back camera, QR codes only, one code per open; the code goes through the app's link
/// handling and the screen closes on the outcome. The permission is asked on open; refused, RN's alert offers
/// the system settings.
struct QrScanScreen: View {
    @EnvironmentObject private var app: AppContainer
    @StateObject private var observed: Observed<QrScanState>

    init(env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.qrScanModel(purpose: .links), ownsModel: true))
    }

    private var model: QrScanModel { observed.model as! QrScanModel }

    private func close() { app.path = Array(app.path.dropLast()) }

    var body: some View {
        ZStack(alignment: .topLeading) {
            QrScannerView(observed: observed, onDenied: close).ignoresSafeArea()
            Button(action: close) {
                Image(appIcon: .close).scaledFont(18, weight: .semibold).foregroundStyle(.white)
                    .frame(width: 44, height: 44).background(Circle().fill(Color.black.opacity(0.4)))
            }
            .accessibilityLabel(UiCopy.shared.CLOSE)
            .accessibilityIdentifier("qr-close")
            .padding(12)
        }
        .background(Color.black)
        .toolbar(.hidden, for: .navigationBar)
        .accessibilityElement(children: .contain)
        .onEscape(close)
        .accessibilityIdentifier("qr-scan")
        .onChange(of: observed.state.outcome as? NSObject) { _, _ in act(observed.state.outcome) }
        .alert((observed.state.outcome as? QrOutcomeUnsupported)?.title ?? "",
               isPresented: Binding(get: { observed.state.outcome is QrOutcomeUnsupported }, set: { if !$0 { model.consumeOutcome(); close() } })) {
            Button(UiCopy.shared.CLOSE, role: .cancel) { model.consumeOutcome(); close() }
        } message: {
            Text((observed.state.outcome as? QrOutcomeUnsupported)?.message ?? "")
        }
        #if DEBUG
        // -qrScan <value> (UI tests, screenshots: the simulator has no camera): as if that code was read
        .task {
            guard let value = UserDefaults.standard.string(forKey: "qrScan") else { return }
            try? await Task.sleep(nanoseconds: 800_000_000)
            model.scanned(value: value)
        }
        #endif
    }

    private func act(_ outcome: QrOutcome?) {
        switch outcome {
        case is QrOutcomeLinkHandled:
            model.consumeOutcome()
            close()
        case let open as QrOutcomeOpen:
            model.consumeOutcome()
            close()
            app.open(entry: open.entry)
        case let web as QrOutcomeBrowser:
            model.consumeOutcome()
            close()
            if let url = URL(string: web.url) { app.sheetURL(url) }
        default: break
        }
    }
}

/// The camera and the decoder over a QrScanModel: asks for the camera first, shows RN's alert when refused, and
/// hands every code to the model (which keeps only the first).
struct QrScannerView: View {
    @ObservedObject var observed: Observed<QrScanState>
    var onDenied: () -> Void = {}
    @State private var authorized = AVCaptureDevice.authorizationStatus(for: .video) == .authorized

    private var model: QrScanModel { observed.model as! QrScanModel }

    var body: some View {
        Group {
            if authorized && !observed.state.handled {
                CameraPreview { model.scanned(value: $0) }
            } else {
                Color.black
            }
        }
        .accessibilityIdentifier("qr-preview")
        .task {
            model.reset()
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized: authorized = true
            case .notDetermined:
                let granted = await AVCaptureDevice.requestAccess(for: .video)
                authorized = granted
                if !granted { model.setPermissionDenied(denied: true) }
            default: model.setPermissionDenied(denied: true)
            }
        }
        .alert(QrScanModel.companion.PERMISSION_TITLE, isPresented: Binding(get: { observed.state.permissionDenied }, set: { if !$0 { model.setPermissionDenied(denied: false) } })) {
            Button(QrScanModel.companion.PERMISSION_CLOSE, role: .cancel) { model.setPermissionDenied(denied: false); onDenied() }
            Button(QrScanModel.companion.PERMISSION_ALLOW) {
                model.setPermissionDenied(denied: false)
                if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
            }
        } message: {
            Text(QrScanModel.companion.PERMISSION_MESSAGE)
        }
    }
}

/// AVFoundation's back camera with a QR metadata output; codes go to [onCode] on the main thread.
private struct CameraPreview: UIViewRepresentable {
    let onCode: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    func makeUIView(context: Context) -> PreviewHost {
        let view = PreviewHost()
        context.coordinator.start(in: view)
        return view
    }

    func updateUIView(_ view: PreviewHost, context: Context) { context.coordinator.onCode = onCode }

    static func dismantleUIView(_ view: PreviewHost, coordinator: Coordinator) { coordinator.stop() }

    final class PreviewHost: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        var onCode: (String) -> Void
        private let session = AVCaptureSession()
        private let queue = DispatchQueue(label: "com.ecency.qr")

        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }

        func start(in view: PreviewHost) {
            view.previewLayer.session = session
            view.previewLayer.videoGravity = .resizeAspectFill
            queue.async { [session] in
                guard let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                      let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else { return }
                session.beginConfiguration()
                session.addInput(input)
                let output = AVCaptureMetadataOutput()
                if session.canAddOutput(output) {
                    session.addOutput(output)
                    output.setMetadataObjectsDelegate(self, queue: .main)
                    output.metadataObjectTypes = output.availableMetadataObjectTypes.contains(.qr) ? [.qr] : []
                }
                session.commitConfiguration()
                session.startRunning()
            }
        }

        func stop() {
            queue.async { [session] in session.stopRunning() }
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard let code = objects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject }).first(where: { $0.type == .qr })?.stringValue else { return }
            onCode(code)
        }
    }
}

// MARK: - RN ShareIntentSheet

/// The share flow at the root: "Share to..." (Blog Post, Wave; closing it picks Blog Post), then the navigation.
struct ShareSheetHost: View {
    @StateObject private var share: Observed<ShareState>
    @EnvironmentObject private var app: AppContainer
    @Environment(\.dynamicTypeSize) private var typeSize

    init(env: AppEnvironment) {
        _share = StateObject(wrappedValue: Observed(env.share, ownsModel: false))
    }

    private var model: ShareModel { share.model as! ShareModel }

    var body: some View {
        Color.clear.frame(width: 0, height: 0).allowsHitTesting(false)
            .sheet(isPresented: Binding(get: { share.state.choosing }, set: { if !$0 && share.state.choosing { model.choose(target: nil) } })) {
                // RN's short sheet at the usual text sizes; it grows (and scrolls) with large text
                ShareChooserSheet { model.choose(target: $0) }.presentationDetents(typeSize > .large ? [.medium, .large] : [.height(260), .large])
            }
            .onChange(of: share.state.navigation as? NSObject) { _, _ in follow(share.state.navigation) }
    }

    private func follow(_ navigation: ShareNavigation?) {
        guard let navigation else { return }
        model.consumeNavigation()
        switch navigation {
        case is ShareNavigationLogin: app.sheet = .login
        case let editor as ShareNavigationEditor: app.push(editor.screen)
        case is ShareNavigationWave: app.selectTab(.waves)
        default: break
        }
    }
}

struct ShareChooserSheet: View {
    let onChoose: (ShareTarget) -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        ScrollView {
        VStack(alignment: .leading, spacing: 8) {
            Text(ShareCopy.shared.TITLE).scaledFont(18, weight: .semibold).foregroundStyle(theme.text).padding(.bottom, 12).accessibilityAddTraits(.isHeader)
            option(.compose, ShareCopy.shared.BLOG, ShareCopy.shared.BLOG_DESC, "share-blog") { onChoose(.blog) }
            option(.waves, ShareCopy.shared.WAVE, ShareCopy.shared.WAVE_DESC, "share-wave") { onChoose(.wave) }
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
        }
        .scrollBounceBehavior(.basedOnSize)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("share-chooser")
    }

    private func option(_ icon: AppIcon, _ title: String, _ description: String, _ id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Group {
                    if icon == .waves { WavyDash().fill(theme.accentText).frame(width: 28, height: 28) } else { Image(appIcon: icon).scaledFont(24) }
                }
                .foregroundStyle(theme.accentText).frame(width: 32).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).scaledFont(16, weight: .medium).foregroundStyle(theme.text)
                    Text(description).scaledFont(13).foregroundStyle(theme.secondary)
                }
                Spacer()
            }
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(id)
    }
}

// MARK: - RN ProposalVoteRequest

/// The proposal card above the main feed's first tab (signed in only).
struct ProposalCardView: View {
    @StateObject private var observed: Observed<ProposalCardState>
    @Environment(\.appTheme) private var theme

    init(env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.proposalVote, ownsModel: false))
    }

    private var model: ProposalVoteModel { observed.model as! ProposalVoteModel }

    var body: some View {
        let state = observed.state
        Group {
            if state.visible {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .center) {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(state.title).scaledFont(18, weight: .semibold).foregroundStyle(theme.secondary).accessibilityAddTraits(.isHeader).accessibilityIdentifier("proposal-title")
                            Text(state.body).scaledFont(14).foregroundStyle(theme.secondary)
                        }
                        Spacer(minLength: 12)
                        Image("EcencyLogo").resizable().scaledToFit().frame(width: 56).accessibilityHidden(true)
                    }
                    if !state.voted {
                        AdaptiveStack(spacing: 8) {
                            Button { model.vote() } label: {
                                if state.voting { ProgressView().tint(.white).frame(width: 110) } else { Text(ProposalCopy.shared.VOTE).scaledFont(15, weight: .bold) }
                            }
                            .buttonStyle(.prominentFill)
                            .disabled(state.voting)
                            .accessibilityIdentifier("proposal-vote")
                            Button(ProposalCopy.shared.DISMISS) { model.askDismiss() }.accessibilityIdentifier("proposal-dismiss")
                        }
                        .padding(.top, 8)
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(theme.lightBackground)
                .padding(.top, 16).padding(.bottom, 8)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("proposal-card")
            }
        }
        .confirmationDialog(ProposalCopy.shared.DISMISS_TITLE, isPresented: Binding(get: { observed.state.askingDismiss }, set: { if !$0 && observed.state.askingDismiss { model.dismiss(choice: nil) } }),
                            titleVisibility: .visible) {
            Button(ProposalCopy.shared.FOREVER) { model.dismiss(choice: .forever) }.accessibilityIdentifier("proposal-forever")
            Button(ProposalCopy.shared.LATER) { model.dismiss(choice: .later) }.accessibilityIdentifier("proposal-later")
            Button(UiCopy.shared.CANCEL, role: .cancel) { model.dismiss(choice: nil) }
        }
    }
}
