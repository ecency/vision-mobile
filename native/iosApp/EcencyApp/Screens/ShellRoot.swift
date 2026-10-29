import EcencyCore
import SwiftUI

/// The tabs: RN Header, the selected tab's screen, the create button on Feed, the bottom tab bar.
struct TabsRoot: View {
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var session: Observed<SessionState>
    @ObservedObject var badges: Observed<Badges>
    @Environment(\.appTheme) private var theme
    @Environment(\.assistiveTechnology) private var assistiveTechnology

    var body: some View {
        let tab = app.nav.tab
        VStack(spacing: 0) {
            AppHeaderBar(tab: tab)
            ZStack(alignment: .bottomTrailing) {
                // the Feed tab stays mounted (its lists keep their place) while another tab shows. With VoiceOver or
                // Switch Control on it is unmounted instead: a hidden SwiftUI tree still exposed its explicit
                // accessibility containers, scroll views and images under the other tabs (AccessibilityUITests)
                if tab == .feed || !assistiveTechnology.isRunning {
                    HomeView(env: app.env)
                        .opacity(tab == .feed ? 1 : 0)
                        .allowsHitTesting(tab == .feed)
                        .accessibilityHidden(tab != .feed)
                }
                if tab == .notification {
                    // RN notification screen: its own Notifications / Leaderboard tabs; only the first needs an account
                    NotificationsTabView().background(theme.background)
                } else if let gate = tab.loginText, !session.state.isLoggedIn {
                    LoginPromptView(text: gate) { app.push(AppScreenLogin.shared) }.background(theme.background)
                } else if tab == .feed {
                    // RN FabButton: the pencil at the bottom trailing corner
                    FabButton(appIcon: .edit, label: ShellUiCopy.shared.CREATE, id: "create-fab") {
                        app.push(AppScreenCompanion.shared.of(route: .editor, arg: ""))
                    }
                } else if tab == .waves {
                    WavesTabView(store: app.waves).background(theme.background)
                } else if tab == .chats {
                    ChatsTabView(observed: app.chats).background(theme.background)
                } else if tab == .wallet {
                    WalletTabView(env: app.env).background(theme.background)
                } else {
                    ComingSoonView(title: tab.title)
                }
            }
            .frame(maxHeight: .infinity)
            BottomTabBar(selected: tab, badges: badges.state) { app.selectTab($0) }
        }
        .background(theme.background)
        .toolbar(.hidden, for: .navigationBar)
        // custom headers above hide the bar: keep the edge swipe back for every pushed screen (Motion.swift)
        .background(SwipeBackEnabler().frame(width: 0, height: 0).accessibilityHidden(true))
        // the touch point pressed cards grow from, observed on the app window only (Motion.swift)
        .background(TouchPointAttacher().frame(width: 0, height: 0).accessibilityHidden(true))
    }
}

/// The side menu's two groups (main, then footer with the version line). Pinned (`SideMenu.pinsFooter`): the main
/// group gets the space above the footer and scrolls in it, the footer sits at the bottom of [available]. Otherwise
/// both stack at their natural heights inside the menu's one scroll.
struct MenuGroupsLayout: Layout {
    let available: CGFloat
    private var gap: CGFloat { CGFloat(SideMenu.shared.GROUP_GAP_DP) }
    /// Three rows of the main group at the default size.
    static let minimumMainHeight: CGFloat = 160

    /// Whether the footer stays pinned: the shared rule, and at large text room for a few main rows (the pinned footer
    /// left the main group a sliver of one row). Otherwise the whole menu scrolls as one.
    static func pins(available: CGFloat, footer: CGFloat) -> Bool {
        SideMenu.shared.pinsFooter(availableHeight: Float(available), footerHeight: Float(footer))
            && available - footer - CGFloat(SideMenu.shared.GROUP_GAP_DP) >= minimumMainHeight
    }

    private func plan(_ width: CGFloat, _ subviews: Subviews) -> (pinned: Bool, main: CGFloat, footer: CGFloat) {
        let footer = subviews[1].sizeThatFits(ProposedViewSize(width: width, height: nil)).height
        if Self.pins(available: available, footer: footer) {
            return (true, max(available - footer - gap, 0), footer)
        }
        // the main group's own height (a scroll view proposes its content's)
        return (false, subviews[0].sizeThatFits(ProposedViewSize(width: width, height: nil)).height, footer)
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? 280
        let p = plan(width, subviews)
        return CGSize(width: width, height: p.pinned ? available : p.main + gap + p.footer)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let p = plan(bounds.width, subviews)
        subviews[0].place(at: bounds.origin, proposal: ProposedViewSize(width: bounds.width, height: p.main))
        let footerY = p.pinned ? bounds.minY + available - p.footer : bounds.minY + p.main + gap
        subviews[1].place(at: CGPoint(x: bounds.minX, y: footerY), proposal: ProposedViewSize(width: bounds.width, height: p.footer))
    }
}

/// The drawer header's picture, filling the header (and the status bar above it) and cut to it. The image is wider
/// than the header is high (2.3 : 1), so filled on its own it ran past the drawer's trailing edge over the scrim:
/// `clipped()` on a `scaledToFill` image clips to the image's own, larger frame, so the image is an overlay of a
/// view that takes the header's frame, and that view clips.
struct DrawerHeaderBackground: View {
    @Environment(\.appTheme) private var theme

    var body: some View {
        theme.accent
            .overlay { Image("SideMenuBackground").resizable().scaledToFill() }
            .clipped()
            .ignoresSafeArea(edges: .top)
            .accessibilityHidden(true)
    }
}

/// react-navigation's drawer on phones: 280 pt over a half-black scrim, the RN side menu inside.
struct SideMenuDrawer: View {
    @EnvironmentObject private var app: AppContainer
    @Environment(\.motion) private var motion
    @Environment(\.dynamicTypeSize) private var typeSize
    /// RN's 280 pt, wider with larger text (never more than 85% of the screen)
    @ScaledMetric(relativeTo: .body) private var scaledWidth: CGFloat = 280

    private var width: CGFloat { min(max(280, scaledWidth), UIScreen.main.bounds.width * (stacked ? 0.92 : 0.85)) }
    private var stacked: Bool { typeSize >= .stacksRows }
    /// The icon column: one width for every row so the titles line up, growing with the icons
    @ScaledMetric(relativeTo: .body) private var iconColumn: CGFloat = 24
    @EnvironmentObject private var session: Observed<SessionState>
    @Environment(\.appTheme) private var theme
    @State private var header: DrawerHeader?

    var body: some View {
        let open = app.nav.drawerOpen && app.nav.atRoot
        ZStack(alignment: .leading) {
            if open {
                Color.black.opacity(0.5).ignoresSafeArea()
                    .onTapGesture { app.closeDrawer() }
                    .accessibilityLabel(L.Native.Ios.Shell.closeMenu)
                    .accessibilityAddTraits(.isButton)
                    .accessibilityIdentifier("drawer-scrim")
                    .transition(.opacity)
                panel
                    .transition(motion.pops ? .move(edge: .leading) : .opacity)
                    .gesture(DragGesture().onEnded { if $0.translation.width < -60 { app.closeDrawer() } })
            }
        }
        .animation(motion.isOff ? nil : .easeOut(duration: 0.25), value: open)
        .task(id: open) {
            guard open, let username = session.state.username else { return }
            header = try? await app.env.drawerHeader(username: username)
        }
    }

    private func menuRow(_ item: SideMenuItem, username: String?) -> some View {
        let main = item.group == .main
        return Button { app.menu(item, hasOtherAccounts: session.state.accounts.contains { $0.username != username }) } label: {
            HStack(spacing: 0) {
                // the icon gives its room to the word at accessibility text sizes (long words broke mid-word)
                if !stacked {
                    Image(appIcon: item.icon).scaledFont(18).foregroundStyle(theme.icon).frame(width: iconColumn).accessibilityHidden(true)
                        .padding(.trailing, 12)
                }
                Text(item.title)
                    .scaledFont(main ? 15 : 14, weight: main ? .bold : .medium)
                    .foregroundStyle(main ? theme.text : theme.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
            }
            // RN's 55 pt indent leaves too little room at accessibility text sizes
            .padding(.leading, stacked ? 16 : 55)
            .padding(.trailing, 8)
            .padding(.vertical, 15)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("menu-\(item.id)")
    }

    private var panel: some View {
        let username = session.state.username
        let loggedIn = username != nil
        let version = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? EcencyCore.shared.VERSION
        let build = (Bundle.main.infoDictionary?["CFBundleVersion"] as? String) ?? "0"
        return VStack(spacing: 0) {
            ZStack(alignment: .bottomLeading) {
                Color.clear.frame(width: width, height: 0)
                if let username {
                    HStack(spacing: 0) {
                        AvatarView(url: PostCard.companion.avatarUrl(account: username, proxyBase: app.env.proxyBase, size: "medium"), size: 64)
                            .padding(.leading, 20)
                        VStack(alignment: .leading, spacing: 2) {
                            if let name = header?.displayName, header?.username == username {
                                Text(name).scaledFont(14, weight: .bold).foregroundStyle(.white).lineLimit(2)
                            }
                            Text("@\(username)").scaledFont(14, weight: .medium).foregroundStyle(.white.opacity(0.8)).lineLimit(2)
                                .accessibilityIdentifier("drawer-user")
                            HStack(spacing: 0) {
                                Image(appIcon: .collapse).scaledFont(12).foregroundStyle(theme.icon).accessibilityHidden(true)
                                Text(header?.username == username ? header?.votingPower ?? "0" : "0")
                                    .scaledFont(12, weight: .medium).foregroundStyle(.white.opacity(0.8))
                                    .accessibilityIdentifier("drawer-vp")
                            }
                        }
                        .padding(.leading, 12)
                        Spacer(minLength: 0)
                        Button { app.menu(.addAccount, hasOtherAccounts: true) } label: {
                            Image(appIcon: .more).scaledFont(12, weight: .bold).foregroundStyle(.white)
                                .frame(width: 28, height: 28).overlay(Circle().stroke(.white, lineWidth: 1)).tapTarget()
                        }
                        .padding(.leading, 8).padding(.trailing, 16)
                        .accessibilityLabel(UiCopy.shared.ACCOUNTS)
                        .largeContent(UiCopy.shared.ACCOUNTS, appIcon: .more)
                        .accessibilityIdentifier("drawer-accounts")
                    }
                    .padding(.bottom, 20)
                }
            }
            // RN's 164 pt header with the status bar; it grows with the name at large text, below the status bar
            .frame(minHeight: 110, alignment: .bottomLeading)
            .background { DrawerHeaderBackground() }
            // the main group from the top, scrolling on its own when it does not fit (small screens, large text);
            // a gap instead of RN's divider, then the footer group pinned to the bottom and always visible. Only
            // where the footer would leave the main group no room (landscape) does the whole menu scroll as one.
            // The footer is measured in the same layout pass that places it, so the choice never flips a frame later.
            GeometryReader { geo in
                ScrollView {
                    MenuGroupsLayout(available: geo.size.height) {
                        ScrollView {
                            VStack(alignment: .leading, spacing: 0) {
                                ForEach(SideMenu.shared.items(loggedIn: loggedIn, group: .main), id: \.self) { menuRow($0, username: username) }
                            }
                        }
                        .scrollBounceBehavior(.basedOnSize)
                        .accessibilityIdentifier("drawer-main")
                        VStack(spacing: 0) {
                            VStack(alignment: .leading, spacing: 0) {
                                ForEach(SideMenu.shared.items(loggedIn: loggedIn, group: .footer), id: \.self) { menuRow($0, username: username) }
                            }
                            Text(SideMenu.shared.versionText(version: version, build: build))
                                .scaledFont(14).foregroundStyle(theme.secondary).padding(.vertical, 8)
                                .accessibilityIdentifier("drawer-version")
                        }
                    }
                }
                // scrolls only when the footer is not pinned (the content is then taller than the space)
                .scrollBounceBehavior(.basedOnSize)
                .accessibilityIdentifier("drawer-menu")
            }
        }
        .frame(width: width)
        .frame(maxHeight: .infinity)
        .background(theme.background.ignoresSafeArea())
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("drawer")
        .accessibilityAddTraits(.isModal)
        .onEscape { app.closeDrawer() }
    }
}

