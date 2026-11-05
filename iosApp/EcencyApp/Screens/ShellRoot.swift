import EcencyCore
import SwiftUI

/// The tabs (README "iPhone Duo"): the system TabView and the root's navigation bar, so iOS places them. A bottom tab
/// bar and a top bar on a phone; on iPhone Duo's outer display (and the inner one held sideways) both move to a column
/// on the trailing edge under the status bar, which hand-built bars never do. The header's buttons (RN Header: the
/// account pill opening the side menu, QR, search, perks) are toolbar items with a title and a symbol each, as the
/// vertical bar needs. Screens pushed on the stack still cover the tabs (RN's main stack).
struct TabsRoot: View {
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var session: Observed<SessionState>
    @ObservedObject var badges: Observed<Badges>
    @Environment(\.appTheme) private var theme
    @Environment(\.displayScale) private var displayScale
    /// The account's avatar as a round image for the toolbar (ShellToolbar).
    @State private var avatar: UIImage?

    var body: some View {
        TabView(selection: selection) {
            ForEach(AppTab.entries, id: \.self) { tab in tabItem(tab) }
        }
        .tint(theme.accent)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ShellToolbar(tab: app.nav.tab, avatar: avatar) }
        .task(id: "\(session.state.username ?? "")@\(displayScale)") {
            avatar = nil
            guard let username = session.state.username else { return }
            let url = PostCard.companion.avatarUrl(account: username, proxyBase: app.env.proxyBase, size: "small")
            guard let loaded = await ImageLoader.shared.load(url, maxPixel: ShellToolbar.avatarSize * displayScale) else { return }
            avatar = ShellToolbar.round(loaded.image, scale: displayScale)
        }
        // custom headers above hide the bar: keep the edge swipe back for every pushed screen (Motion.swift)
        .background(SwipeBackEnabler().frame(width: 0, height: 0).accessibilityHidden(true))
        // the touch point pressed cards grow from, observed on the app window only (Motion.swift)
        .background(TouchPointAttacher().frame(width: 0, height: 0).accessibilityHidden(true))
        .background(TabBarTagger(ids: AppTab.entries.map(\.identifier)).frame(width: 0, height: 0).accessibilityHidden(true))
    }

    private var selection: Binding<AppTab> { Binding(get: { app.nav.tab }, set: { app.selectTab($0) }) }

    // the iOS 17 form of a tab (Tab(value:) needs iOS 18); the system bar places it all the same. Icons only, as RN:
    // the title is what VoiceOver reads (the vertical bar on iPhone Duo shows icons only anyway), set by TabBarTagger
    private func tabItem(_ tab: AppTab) -> some View {
        page(tab)
            .tabItem { tabIcon(tab) }
            .tag(tab)
            .badge(badgeText(tab))
    }

    /// RN's icons; Waves is RN's wavy dash, drawn into a template image (a tab item takes an image, not a shape). The
    /// system bar's button reads its id and name from this image (a symbol's id is its name, "bell"), never from the
    /// tab item's modifiers: so the image carries the tab's id and title. The bar reads a badge itself ("3 items").
    private func tabIcon(_ tab: AppTab) -> SwiftUI.Image {
        let base = tab == .waves ? WavyDash.tabImage(scale: displayScale)
            : UIImage(systemName: tab.icon.symbol(filled: tab == app.nav.tab)) ?? UIImage()
        // a fresh image (a change of rendering mode copies it): symbols are shared, the id must not reach every "bell"
        let image = base.withRenderingMode(.alwaysOriginal).withRenderingMode(.alwaysTemplate)
        image.accessibilityIdentifier = tab.identifier
        image.accessibilityLabel = tab.title
        return SwiftUI.Image(uiImage: image)
    }

    private func badgeText(_ tab: AppTab) -> SwiftUI.Text? { badges.state.text(kind: tab.badge).map { SwiftUI.Text($0) } }

    private func page(_ tab: AppTab) -> some View {
        Group {
            // a wide window: the list with what it opened beside it (PaneViews.swift); a phone never gets here
            if app.wideWindow {
                PanedTab(tab: tab) { content(tab) }
            } else {
                content(tab)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.background)
    }

    @ViewBuilder private func content(_ tab: AppTab) -> some View {
        if tab == .notification {
            // RN notification screen: its own Notifications / Leaderboard tabs; only the first needs an account
            NotificationsTabView()
        } else if let gate = tab.loginText, !session.state.isLoggedIn {
            LoginPromptView(text: gate) { app.push(AppScreenLogin.shared) }
        } else if tab == .feed {
            HomeView(env: app.env)
                // RN FabButton: the pencil at the bottom trailing corner
                .overlay(alignment: .bottomTrailing) {
                    FabButton(appIcon: .edit, label: ShellUiCopy.shared.CREATE, id: "create-fab") {
                        app.push(AppScreenCompanion.shared.of(route: .editor, arg: ""))
                    }
                }
        } else if tab == .waves {
            WavesTabView(store: app.waves)
        } else if tab == .chats {
            ChatsTabView(observed: app.chats)
        } else if tab == .wallet {
            WalletTabView(env: app.env)
        } else {
            ComingSoonView(title: tab.title)
        }
    }
}

/// RN Header as toolbar items: the account avatar opens the side menu (its header shows the name; the bar keeps the
/// height for content); then QR, search and perks (signed in), the same on every tab. Every item has a title and a symbol for iPhone Duo's vertical bar.
struct ShellToolbar: ToolbarContent {
    let tab: AppTab
    /// Round, at its final size (`round`), or nil until it loaded: the account symbol meanwhile.
    let avatar: UIImage?
    static let avatarSize: CGFloat = 30
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var session: Observed<SessionState>

    var body: some ToolbarContent {
        let username = session.state.username
        ToolbarItem(placement: .topBarLeading) {
            // an image, not a Label: a bar draws a Label's icon as a template, which turned the avatar into a flat
            // tinted circle. The name is the accessibility label and the large content title. An image joins iPhone
            // Duo's vertical bar; beside the list on a wide window the same button takes the menu column away again
            Button { if app.wideWindow && app.nav.drawerOpen { app.closeDrawer() } else { app.openDrawer() } } label: {
                if username != nil, let avatar {
                    Image(uiImage: avatar).renderingMode(.original)
                } else {
                    Image(appIcon: .account, filled: true)
                }
            }
            .accessibilityLabel(username.map { ShellUiCopy.shared.accountOf(username: $0) } ?? ShellUiCopy.shared.ACCOUNT_SIGNED_OUT)
            .largeContent(username.map { ShellUiCopy.shared.accountOf(username: $0) } ?? ShellUiCopy.shared.ACCOUNT_SIGNED_OUT, appIcon: .account)
            .accessibilityIdentifier("account-menu")
        }
        // the same buttons in the same order on every tab (AppTab.headerButtons)
        ToolbarItemGroup(placement: .topBarTrailing) {
            ForEach(tab.headerButtons(loggedIn: username != nil), id: \.self) { button in
                Button { app.push(AppScreenCompanion.shared.of(route: button.route, arg: "")) } label: { Label(button.label, appIcon: button.icon) }
                    .accessibilityIdentifier(Self.identifier(button))
            }
        }
    }

    static func identifier(_ button: TabHeaderButton) -> String {
        switch button {
        case .qrScan: "header-qr"
        case .search: "header-search"
        default: "header-boost"
        }
    }

    /// The avatar cut to a circle at the toolbar's size: a toolbar keeps an image's own size, and only a plain image
    /// (not a view) moves into iPhone Duo's vertical bar.
    static func round(_ image: UIImage, scale: CGFloat) -> UIImage {
        let side = avatarSize
        let format = UIGraphicsImageRendererFormat.preferred()
        format.scale = scale
        return UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { _ in
            let rect = CGRect(x: 0, y: 0, width: side, height: side)
            UIBezierPath(ovalIn: rect).addClip()
            // aspect fill into the circle
            let ratio = max(side / max(image.size.width, 1), side / max(image.size.height, 1))
            let drawn = CGSize(width: image.size.width * ratio, height: image.size.height * ratio)
            image.draw(in: CGRect(x: (side - drawn.width) / 2, y: (side - drawn.height) / 2, width: drawn.width, height: drawn.height))
        }
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
            .overlay { Image(decorative: "SideMenuBackground").resizable().scaledToFill() }
            .clipped()
            .ignoresSafeArea(edges: .top)
            .accessibilityHidden(true)
    }
}

/// react-navigation's drawer on phones: 280 pt over a half-black scrim, the RN side menu inside.
struct SideMenuDrawer: View {
    /// True for the user menu as the first column on a wide window (PaneViews.swift): no scrim, always laid out.
    var asColumn = false
    @EnvironmentObject private var app: AppContainer
    @Environment(\.motion) private var motion
    @Environment(\.dynamicTypeSize) private var typeSize
    /// RN's 280 pt, wider with larger text (never more than 85% of the screen)
    @ScaledMetric(relativeTo: .body) private var scaledWidth: CGFloat = 280
    @Environment(\.viewportSize) private var viewport

    private var width: CGFloat { Self.width(scaled: scaledWidth, windowWidth: viewport?.width, stacked: stacked) }

    /// RN's 280 pt grown with the text size, never more than 85% of the app's window (92% at accessibility sizes);
    /// the window, not the screen, since iPhone Duo resizes it on every fold. Uncapped until the window is measured.
    static func width(scaled: CGFloat, windowWidth: CGFloat?, stacked: Bool) -> CGFloat {
        min(max(280, scaled), (windowWidth ?? .infinity) * (stacked ? 0.92 : 0.85))
    }
    private var stacked: Bool { typeSize >= .stacksRows }
    /// The icon column: one width for every row so the titles line up, growing with the icons
    @ScaledMetric(relativeTo: .body) private var iconColumn: CGFloat = 24
    @EnvironmentObject private var session: Observed<SessionState>
    @Environment(\.appTheme) private var theme
    @State private var header: DrawerHeader?

    var body: some View {
        if asColumn {
            panel
                .task(id: session.state.username) {
                    guard let username = session.state.username else { return }
                    header = try? await app.env.drawerHeader(username: username)
                }
        } else {
            drawer
        }
    }

    private var drawer: some View {
        // on a wide window the menu is a column beside the list instead (NavState.menuBesideList)
        let open = app.nav.drawerOpen && app.nav.atRoot && !app.wideWindow
        return ZStack(alignment: .leading) {
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
        // the drawer covers the tabs; the column sits beside the list, which VoiceOver must still reach
        .accessibilityAddTraits(asColumn ? [] : .isModal)
        .onEscape { app.closeDrawer() }
    }
}


/// Names the system tab bar ("tab-bar") and its items (`AppTab.identifier`) for the UI tests, as the hand-built bar
/// was: UIKit's tab bar controller under the TabView, found from the window. SwiftUI rewrites the items (clearing the
/// ids) whenever a badge or an icon changes, so the items are watched and named again after each update; the icon
/// image carries the same id (ShellRoot.tabIcon), which the bar's button reads otherwise.
private struct TabBarTagger: UIViewControllerRepresentable {
    let ids: [String]

    func makeUIViewController(context: Context) -> Probe { Probe() }

    func updateUIViewController(_ probe: Probe, context: Context) {
        probe.ids = ids
        probe.apply()
    }

    final class Probe: UIViewController {
        var ids: [String] = []
        private var observations: [NSKeyValueObservation] = []

        override func viewDidLoad() {
            super.viewDidLoad()
            view.isUserInteractionEnabled = false
        }

        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            apply()
        }

        func apply() {
            guard let bar = Self.tabBarController(view.window?.rootViewController)?.tabBar else { return }
            bar.accessibilityIdentifier = "tab-bar"
            let items = bar.items ?? []
            for (item, id) in zip(items, ids) where item.accessibilityIdentifier != id { item.accessibilityIdentifier = id }
            observations.removeAll()
            let later: () -> Void = { [weak self] in DispatchQueue.main.async { self?.apply() } }
            observations.append(bar.observe(\.items) { _, _ in later() })
            for (item, id) in zip(items, ids) {
                observations.append(item.observe(\.badgeValue) { _, _ in later() })
                observations.append(item.observe(\.image) { _, _ in later() })
                observations.append(item.observe(\.accessibilityIdentifier) { item, _ in
                    if item.accessibilityIdentifier != id { later() }
                })
            }
        }

        private static func tabBarController(_ root: UIViewController?) -> UITabBarController? {
            guard let root else { return nil }
            if let tabs = root as? UITabBarController { return tabs }
            for child in root.children + [root.presentedViewController].compactMap({ $0 }) {
                if let found = tabBarController(child) { return found }
            }
            return nil
        }
    }
}
