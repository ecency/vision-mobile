import EcencyCore
import SwiftUI
import UIKit

/// Signing and link prompts, and RN's app-wide "Not enough Resource Credits" offer, in their own window at `.alert`, above every sheet of the app window (a vote from
/// the reply sheet can need the HiveSigner page) and below the lock window (`.alert + 1`), so a PIN confirmation
/// a link asks for comes up on top. The window is hidden, and lets touches through, while nothing is asked.
@MainActor
final class AuthOverlay {
    private let env: AppEnvironment
    private let hosting: UIHostingController<AuthOverlayView>
    private(set) var window: UIWindow?
    private var watchers: [Closeable] = []
    /// Opens a screen of the app window for the RC offer's choice (the container closes its sheet first).
    var onOpenScreen: ((AppScreen) -> Void)?

    init(env: AppEnvironment) {
        self.env = env
        hosting = UIHostingController(rootView: AuthOverlayView(signing: env.signing.current, links: env.authLinks.current, rcOffer: false, env: env, onOpen: { _ in }))
        hosting.view.backgroundColor = .clear
        watchers = [
            env.signing.watch { [weak self] _ in MainActor.assumeIsolated { self?.render() } },
            env.authLinks.watch { [weak self] _ in MainActor.assumeIsolated { self?.render() } },
            env.rcOffer.watch { [weak self] _ in MainActor.assumeIsolated { self?.render() } },
        ]
    }

    deinit { watchers.forEach { $0.close() } }

    var isShowing: Bool { window?.isHidden == false }

    func attach(to scene: UIWindowScene? = nil) {
        guard window == nil else { return }
        guard let scene = scene ?? UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return }
        let window = UIWindow(windowScene: scene)
        window.windowLevel = .alert
        window.backgroundColor = .clear
        window.rootViewController = hosting
        window.accessibilityIdentifier = "auth-window"
        self.window = window
        render()
    }

    private func render() {
        let signing = env.signing.current
        let links = env.authLinks.current
        let rcOffer = env.rcOffer.current.open
        hosting.rootView = AuthOverlayView(signing: signing, links: links, rcOffer: rcOffer, env: env, onOpen: { [weak self] in self?.onOpenScreen?($0) })
        let showing = signing.prompt != nil || links.prompt != nil || rcOffer
        window?.isHidden = !showing
    }
}

/// What the overlay window shows: one signing prompt (it wins: a broadcast waits on it), else one link prompt.
struct AuthOverlayView: View {
    let signing: SigningState
    let links: AuthLinkState
    /// The RC offer is up ([RcOfferModel]); it waits behind a signing or link prompt.
    let rcOffer: Bool
    let env: AppEnvironment
    let onOpen: (AppScreen) -> Void

    var body: some View {
        ThemedRoot {
            ZStack {
                if signing.prompt != nil || links.prompt != nil || rcOffer {
                    Color.black.opacity(0.4).ignoresSafeArea().accessibilityHidden(true)
                }
                switch signing.prompt {
                case let p as SigningPromptAuthUpgrade: Card { AuthUpgradeView(prompt: p, signing: env.signing) }
                case let p as SigningPromptHiveSignerHot: HotSignView(prompt: p, signing: env.signing)
                case let p as SigningPromptHiveAuthBroadcast: Card { HiveAuthWaitView(prompt: p, signing: env.signing) }
                default:
                    if let prompt = links.prompt {
                        Card { LinkPromptView(prompt: prompt, links: env.authLinks, working: links.isWorking) }
                    } else if rcOffer {
                        Card { RcOfferView(model: env.rcOffer, onOpen: onOpen) }
                    }
                }
            }
            // the prompt is modal for VoiceOver, and the escape gesture answers it as its cancel button would
            .accessibilityAddTraits(.isModal)
            .onEscape(escape)
        }
    }

    private func escape() {
        if signing.prompt != nil {
            env.signing.cancel()
        } else if links.prompt != nil {
            if links.prompt is LinkPromptRefused { env.authLinks.dismiss() } else if !links.isWorking { env.authLinks.decline() }
        } else if rcOffer {
            if let screen = env.rcOffer.choose(choice: .cancel) { onOpen(screen) }
        }
    }
}

/// RN applicationScreen's ACTION_MODAL for ui.rcOffer: top up with Points, boost, or cancel.
private struct RcOfferView: View {
    let model: RcOfferModel
    let onOpen: (AppScreen) -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        let copy = RcErrors.shared
        Text(copy.OFFER_TITLE).font(.headline).accessibilityAddTraits(.isHeader).accessibilityIdentifier("rc-offer-title")
        Text(copy.OFFER_BODY).foregroundStyle(theme.secondary)
        Button { choose(.topUp) } label: { Text(copy.TOP_UP).bold().frame(maxWidth: .infinity, minHeight: 40) }
            .buttonStyle(.borderedProminent).tint(theme.accent).accessibilityIdentifier("rc-offer-topup")
        Button(copy.BOOST) { choose(.boost) }.frame(maxWidth: .infinity).accessibilityIdentifier("rc-offer-boost")
        Button(copy.CANCEL, role: .cancel) { choose(.cancel) }.frame(maxWidth: .infinity).accessibilityIdentifier("rc-offer-cancel")
    }

    private func choose(_ choice: RcOfferChoice) {
        if let screen = model.choose(choice: choice) { onOpen(screen) }
    }
}

/// A bottom card over the dimmed app. It scrolls only when large text makes it taller than the screen (one card,
/// measured, so a typed key is never lost to a second copy of the content).
private struct Card<Content: View>: View {
    @ViewBuilder let content: Content
    @Environment(\.appTheme) private var theme
    @State private var height: CGFloat = 0

    var body: some View {
        GeometryReader { geo in
            VStack {
                Spacer(minLength: 0)
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) { content }
                        .padding(20)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
                }
                .scrollBounceBehavior(.basedOnSize)
                .frame(height: max(1, min(height, geo.size.height - 24)))
                .frame(maxWidth: 560)
                .background(theme.background, in: RoundedRectangle(cornerRadius: 16))
                .padding(12)
            }
            .frame(maxWidth: .infinity)
        }
    }
}

/// RN authUpgradeSheet: the active key or master password for one signature, or HiveSigner / HiveAuth.
struct AuthUpgradeView: View {
    let prompt: SigningPromptAuthUpgrade
    let signing: SigningModel
    @State private var key = ""
    @Environment(\.appTheme) private var theme

    var body: some View {
        HStack { Image(appIcon: .key, filled: true).foregroundStyle(theme.accentText).accessibilityHidden(true); Text(AccountCopy.shared.UPGRADE_TITLE).font(.headline).accessibilityAddTraits(.isHeader) }
        Text(AccountCopy.shared.upgradeDescription(authority: prompt.authority)).foregroundStyle(theme.secondary)
        if prompt.keyEntry {
            SecureField(AccountCopy.shared.UPGRADE_KEY_PLACEHOLDER, text: $key)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .padding(10).overlay(RoundedRectangle(cornerRadius: 8).stroke(prompt.error == nil ? theme.border : theme.danger))
                .accessibilityIdentifier("upgrade-key")
            if let error = prompt.error {
                Text(error).font(.footnote).foregroundStyle(theme.dangerText).announces(error).accessibilityIdentifier("upgrade-error")
            }
            Button { signing.submitUpgradeKey(input: key) } label: {
                Group { if prompt.validating { ProgressView().tint(.white) } else { Text(AccountCopy.shared.UPGRADE_SIGN).bold() } }.frame(maxWidth: .infinity, minHeight: 40)
            }
            .buttonStyle(.borderedProminent).tint(theme.accent).disabled(key.isEmpty || prompt.validating)
            .accessibilityIdentifier("upgrade-sign")
            Text(AccountCopy.shared.UPGRADE_OR).font(.footnote).foregroundStyle(theme.secondary).frame(maxWidth: .infinity)
        }
        Button(AccountCopy.shared.UPGRADE_HIVESIGNER) { signing.chooseUpgrade(choice: .hivesigner) }.frame(maxWidth: .infinity).accessibilityIdentifier("upgrade-hivesigner")
        Button(AccountCopy.shared.UPGRADE_HIVEAUTH) { signing.chooseUpgrade(choice: .hiveauth) }.frame(maxWidth: .infinity).accessibilityIdentifier("upgrade-hiveauth")
        Button(UiCopy.shared.CANCEL, role: .cancel) { signing.cancel() }.frame(maxWidth: .infinity).accessibilityIdentifier("upgrade-cancel")
    }
}

/// RN hiveSignerModal: HiveSigner's signing page; the redirect with the transaction id ends it.
private struct HotSignView: View {
    let prompt: SigningPromptHiveSignerHot
    let signing: SigningModel
    @Environment(\.appTheme) private var theme

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(AccountCopy.shared.CONFIRM_TX).font(.headline).accessibilityAddTraits(.isHeader)
                Spacer()
                Button(UiCopy.shared.CLOSE) { signing.cancel() }.accessibilityIdentifier("hot-sign-close")
            }
            .padding()
            HiveSignerWebView(url: prompt.url) { url in if !signing.hotSignNavigation(url: url) { signing.cancel() } }
        }
        .background(theme.background)
    }
}

/// RN hiveAuthBroadcastSheet: waiting for the wallet, which is brought up once by itself.
private struct HiveAuthWaitView: View {
    let prompt: SigningPromptHiveAuthBroadcast
    let signing: SigningModel
    @State private var walletMissing = false
    @Environment(\.appTheme) private var theme

    var body: some View {
        Text(HiveAuthCopy.shared.TITLE).font(.headline).accessibilityAddTraits(.isHeader)
        HStack { ProgressView(); Text(prompt.step == .waiting ? AuthUiCopy.shared.APPROVE_THE_REQUEST_IN : HiveAuthCopy.shared.INITIATING).foregroundStyle(theme.secondary) }
        CountdownText(expiresAtMillis: prompt.expiresAtMillis)
        if walletMissing { Text(HiveAuthCopy.shared.NOT_INSTALLED).font(.footnote).foregroundStyle(theme.dangerText).announces(HiveAuthCopy.shared.NOT_INSTALLED) }
        if let link = prompt.link, let url = URL(string: link) {
            Button(HiveAuthCopy.shared.OPEN_WALLET) { UIApplication.shared.open(url) { walletMissing = !$0 } }
                .frame(maxWidth: .infinity)
                .task(id: prompt.requestId) {
                    if prompt.openWallet {
                        signing.walletOpened()
                        UIApplication.shared.open(url) { walletMissing = !$0 }
                    }
                }
        }
        Button(UiCopy.shared.CANCEL, role: .cancel) { signing.cancel() }.frame(maxWidth: .infinity)
    }
}

/// Link questions (RN useLinkProcessor): sign confirmation, refusals, key and proof hand-offs, link logins.
private struct LinkPromptView: View {
    let prompt: LinkPrompt
    let links: AuthLinkModel
    let working: Bool
    @Environment(\.appTheme) private var theme

    var body: some View {
        switch prompt {
        case let p as LinkPromptSignConfirm:
            Text(AccountCopy.shared.CONFIRM_TX).font(.headline).accessibilityAddTraits(.isHeader)
            Text(AuthLinkModel.companion.signWarning(authority: p.authority)).font(.footnote).foregroundStyle(theme.secondary)
            AdaptiveStack {
                Text(p.title).bold()
                FormRowGap()
                Text(p.authority.uppercased()).font(.footnote.bold()).foregroundStyle(theme.accentText).accessibilityIdentifier("sign-authority")
            }
            Text(AuthUiCopy.shared.accountLabel(username: p.username)).font(.footnote).foregroundStyle(theme.secondary)
            if let requester = p.requester { Text(AuthUiCopy.shared.requestedBy(requester: requester)).font(.footnote).foregroundStyle(theme.secondary) }
            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(p.fields, id: \.name) { field in
                        AdaptiveStack(verticalAlignment: .top) {
                            Text(field.name).font(.footnote).foregroundStyle(theme.secondary).frame(minWidth: 110, alignment: .leading)
                            Text(field.value).font(.footnote.monospaced())
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
            }
            .frame(maxHeight: 220)
            buttons(cancel: UiCopy.shared.CANCEL, approve: AccountCopy.shared.APPROVE)
        case let p as LinkPromptRefused:
            Text(p.title).font(.headline).accessibilityAddTraits(.isHeader)
            Text(p.message).accessibilityIdentifier("link-refused")
            Button(UiCopy.shared.CLOSE) { links.dismiss() }.frame(maxWidth: .infinity)
        case let p as LinkPromptPostingKeyRequest:
            confirm(AccountCopy.shared.postingKeyRequest(requester: p.requester, username: p.username), id: "posting-key-request")
        case let p as LinkPromptAuthRequest:
            confirm(AccountCopy.shared.authRequest(requester: p.requester, username: p.username), id: "auth-request")
        case let p as LinkPromptAuthTransfer:
            confirm(AccountCopy.shared.authTransfer(username: p.username)
                + AccountCopy.shared.authTransferStored(username: p.username, method: p.accountStored ? p.storedMethod : nil), id: "auth-transfer")
        case let p as LinkPromptCodeLogin:
            confirm(AuthUiCopy.shared.VERIFY_DIRECT_LOGIN, id: "code-login", title: AuthUiCopy.shared.easyLogin(username: p.username))
        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private func confirm(_ message: String, id: String, title: String = UiCopy.shared.CONFIRM) -> some View {
        Text(title).font(.headline).accessibilityAddTraits(.isHeader)
        Text(message).accessibilityIdentifier(id)
        buttons(cancel: UiCopy.shared.CANCEL, approve: AccountCopy.shared.APPROVE)
    }

    private func buttons(cancel: String, approve: String) -> some View {
        AdaptiveStack {
            Button(cancel) { links.decline() }.frame(maxWidth: .infinity).accessibilityIdentifier("link-cancel")
            Button { links.confirm() } label: {
                Group { if working { ProgressView().tint(.white) } else { Text(approve).bold() } }.frame(maxWidth: .infinity, minHeight: 36)
            }
            .buttonStyle(.borderedProminent).tint(theme.accent).disabled(working)
            .accessibilityIdentifier("link-approve")
        }
    }
}
