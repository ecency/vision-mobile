import EcencyCore
import SwiftUI

/// The account switcher (RN accountsBottomSheet): avatars, the sign-in method, a check on the current account;
/// tap to switch, the logout button signs that account out after a confirm. The shell can present it from its
/// side menu with `app.sheet = .accounts`.
struct AccountSwitcherSheet: View {
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var session: Observed<SessionState>
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appTheme) private var theme
    @State private var confirmLogout: String?
    @State private var confirmAll = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(session.state.accounts, id: \.username) { account in
                        // the current mark and log out go under the name at accessibility text sizes
                        AdaptiveStack(horizontalAlignment: .leading, spacing: 12) {
                            HStack(spacing: 12) {
                                AvatarView(url: PostCard.companion.avatarUrl(account: account.username, proxyBase: app.env.proxyBase, size: "small"), size: 40)
                                VStack(alignment: .leading) {
                                    Text("@\(account.username)").bold()
                                    Text(session.state.switchingTo == account.username ? AccountCopy.shared.SWITCHING : AccountCopy.shared.method(m: account.method))
                                        .font(.caption).foregroundStyle(theme.secondary)
                                }
                                // VoiceOver: the name is the row's button (a tap anywhere on the row switches to it)
                                .accessibilityElement(children: .combine)
                                .accessibilityAddTraits(.isButton)
                                .accessibilityAction { switchTo(account) }
                            }
                            Spacer(minLength: 0)
                            HStack(spacing: 12) {
                            if account.isCurrent {
                                Image(appIcon: .checkCircle, filled: true).foregroundStyle(theme.accentText)
                                    .accessibilityLabel(AuthUiCopy.shared.CURRENT_ACCOUNT).accessibilityIdentifier("current-mark")
                            }
                            Button { confirmLogout = account.username } label: { Image(appIcon: .logout).tapTarget() }
                                .buttonStyle(.borderless).accessibilityLabel(L.Native.Ios.Account.logoutUser(username: account.username))
                                .largeContent(L.Native.Ios.Account.logoutUser(username: account.username), appIcon: .logout)
                                .accessibilityIdentifier("logout-\(account.username)")
                            }
                        }
                        .contentShape(Rectangle())
                        .onTapGesture { switchTo(account) }
                        .accessibilityElement(children: .contain)
                        .accessibilityIdentifier("account-\(account.username)")
                    }
                }
                Section {
                    Button(AccountCopy.shared.ADD_EXISTING) {
                        // a sheet cannot present over itself: the login sheet follows once this one is gone
                        app.nextSheet = .login
                        dismiss()
                    }
                    .accessibilityIdentifier("account-add")
                    Button(AccountCopy.shared.CREATE_NEW) { app.nextSheet = .register(""); dismiss() }.accessibilityIdentifier("account-create")
                    if !session.state.accounts.isEmpty {
                        Button(AccountCopy.shared.LOGOUT_ALL, role: .destructive) { confirmAll = true }.accessibilityIdentifier("account-logout-all")
                    }
                }
            }
            .navigationTitle(UiCopy.shared.ACCOUNTS)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(UiCopy.shared.DONE) { dismiss() } } }
        }
        .alert(AccountCopy.shared.LOGOUT_TITLE, isPresented: Binding(get: { confirmLogout != nil }, set: { if !$0 { confirmLogout = nil } }), presenting: confirmLogout) { name in
            Button(UiCopy.shared.CANCEL, role: .cancel) {}.accessibilityIdentifier("logout-cancel")
            Button(AccountCopy.shared.LOGOUT, role: .destructive) { app.logout(name) }.accessibilityIdentifier("logout-confirm")
        } message: { name in Text("@\(name)") }
        .alert(AccountCopy.shared.LOGOUT_ALL_TITLE, isPresented: $confirmAll) {
            Button(UiCopy.shared.CANCEL, role: .cancel) {}
            Button(AccountCopy.shared.LOGOUT, role: .destructive) { app.logoutAll(); dismiss() }
        }
    }

    private func switchTo(_ account: AccountSummary) {
        guard !account.isCurrent else { return }
        Task {
            do { try await app.env.session.switchTo(username: account.username); dismiss() } catch {
                app.alert = AppAlert(title: UiCopy.shared.FAIL, message: ErrorText.message(error))
            }
        }
    }
}

/// RN welcome screen: three lines, the terms checkbox, "Get started!" once accepted.
struct WelcomeView: View {
    var previouslyAccepted = false
    let onAccepted: () -> Void
    @State private var accepted = false
    @State private var terms = false
    @Environment(\.appTheme) private var theme
    @Environment(\.dynamicTypeSize) private var typeSize

    /// At accessibility text sizes the title needs the whole width: Esie stays out (README "Esie").
    static func showsEsie(_ size: DynamicTypeSize) -> Bool { !size.isAccessibilitySize }

    /// Read when drawn, so a language switch reaches it (README "Localization").
    private var lines: [(String, String)] {
        WelcomeCopy.shared.LINES.map { (($0.first as String?) ?? "", ($0.second as String?) ?? "") }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(WelcomeCopy.shared.LABEL).font(.title3).foregroundStyle(theme.secondary)
                        Text(WelcomeCopy.shared.TITLE).font(.largeTitle.bold()).foregroundStyle(theme.accentText).accessibilityAddTraits(.isHeader)
                    }
                    .padding(.top, 40)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    // tall enough for Esie, so she never covers the lines below
                    .frame(minHeight: Self.showsEsie(typeSize) ? CGFloat(EsiePlacement.welcome.height) + 16 : 0, alignment: .topLeading)
                    // where RN's welcome mascot (assets/love_mascot.png) stood: Esie waves a few times, then rests (README "Esie")
                    // at most 40 % of the width, so she never covers "Ecency" on narrow phones (as Android)
                    .overlay(alignment: .topTrailing) {
                        if Self.showsEsie(typeSize) {
                        GeometryReader { g in
                            EsieView(.welcome, height: EsieView.fittedHeight(.welcome, width: g.size.width))
                                .padding(.top, 16).padding(.trailing, 8)
                                .frame(maxWidth: .infinity, alignment: .trailing)
                        }
                        }
                    }
                    ForEach(lines, id: \.0) { heading, body in
                        Text(heading).bold().padding(.top, 12)
                        Text(body).foregroundStyle(theme.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            Button { accepted.toggle() } label: {
                HStack(alignment: .top) {
                    Image(appIcon: .checkbox, filled: accepted).foregroundStyle(theme.accentText).accessibilityHidden(true)
                    Text(WelcomeCopy.shared.TERMS).foregroundStyle(theme.text).multilineTextAlignment(.leading)
                }
            }
            .accessibilityAddTraits(accepted ? .isSelected : [])
            .accessibilityIdentifier("welcome-terms")
            Button(WelcomeCopy.shared.READ) { terms = true }.font(.footnote)
            Button(action: onAccepted) { Text(WelcomeCopy.shared.GET_STARTED).bold().frame(maxWidth: .infinity, minHeight: 44) }
                .buttonStyle(.borderedProminent).tint(theme.accent).disabled(!accepted)
                .accessibilityIdentifier("welcome-start")
        }
        .padding(24)
        .background(theme.background)
        .sheet(isPresented: $terms) { SafariView(url: URL(string: LoginCopy.shared.TERMS_URL)!).ignoresSafeArea() }
        .onAppear { if previouslyAccepted { accepted = true } }
    }
}
