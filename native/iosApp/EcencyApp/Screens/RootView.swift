import EcencyCore
import SwiftUI

/// Navigation root: the tabs with every pushed screen (RN's main stack covers the tab bar), the side menu,
/// app-wide sheets, alerts, toasts, banners and the offline strip.
struct RootView: View {
    @EnvironmentObject private var app: AppContainer
    @ObservedObject var connectivity: Observed<ConnectivityState>
    /// Zoom navigation sources and destinations (iOS 18; Motion.swift).
    @Namespace private var hero
    @Environment(\.motion) private var motion

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                // the system push and its interactive swipe back; a card image or an avatar zooms into its screen on iOS 18
                NavigationStack(path: $app.path) {
                    TabsRoot(badges: app.badges)
                        .navigationDestination(for: NavItem.self) { item in
                            DestinationView(screen: item.screen)
                                .heroDestination(app.heroSources[item.id], namespace: hero, motion: motion)
                        }
                }
                .environment(\.heroNamespace, hero)
                SideMenuDrawer()
                MessagesOverlay(ui: app.ui, onOpen: { app.push($0) }, onEntry: { app.open(entry: $0) })
                DigestSheetHost(env: app.env) // RN newsletterDigestSheet: the offer after an account's first post
                ShareSheetHost(env: app.env) // RN ShareIntentSheet: content another app shared (PlatformViews.swift)
            }
            if !connectivity.state.online { OfflineBanner() }
        }
        .environmentObject(app.session)
        .environmentObject(app.votes)
        .environmentObject(app.lock)
        .sheet(item: $app.sheet, onDismiss: {
            // a sheet asked for another one (accounts, then sign-in): show it once this one is gone
            if let next = app.nextSheet {
                app.nextSheet = nil
                app.sheet = next
            }
        }) { sheet in
            Group {
                switch sheet {
                case .login: LoginSheet()
                case .accounts: AccountSwitcherSheet()
                case .backupKeys: BackupKeysSheet(env: app.env)
                case let .register(referral): RegisterSheet(referral: referral, env: app.env)
                case let .browser(url): SafariView(url: url).ignoresSafeArea()
                case let .video(url): VideoPlayerSheet(url: url)
                case let .gallery(urls, index): GallerySheet(urls: urls, index: index)
                // ---- feed, posts and reading ----
                case let .postMenu(r): PostMenuSheet(request: r)
                case let .crossPost(r): CrossPostSheet(post: r.post, env: app.env)
                case let .translation(r): TranslationSheet(post: r.post, env: app.env, reader: app.readerLanguage)
                case let .modNotes(r):
                    // the one notes sheet (CommunityViews.swift): hivemind's rules on the reason live in ModNotes
                    ModNotesSheet(title: r.title, subtitle: r.description, placeholder: r.placeholder, confirmLabel: r.confirmLabel) { notes in
                        app.sheet = nil
                        app.env.postActions.confirm(item: r.item, shown: r.post, asUser: r.user, notes: notes)
                    }
                    // room for the notes at large text sizes
                    .presentationDetents([.medium, .large])
                case let .quickProfile(name): QuickProfileSheet(username: name, env: app.env)
                case let .reply(r): MenuReplySheet(request: r)
                case .customiseFilters: CustomiseFiltersSheet()
                case let .tip(key): TipSheet(author: key.author, permlink: key.permlink, env: app.env)
                }
            }
            .environmentObject(app)
            .environmentObject(app.session)
            .environmentObject(app.votes)
        }
        // ---- feed, posts and reading: menu confirmations and failures ----
        .alert(app.confirm?.title ?? "", isPresented: Binding(get: { app.confirm != nil }, set: { if !$0 { app.confirm = nil } }),
               presenting: app.confirm) { c in
            Button(UiCopy.shared.CANCEL, role: .cancel) {}
            Button(c.confirmLabel, role: c.item == .delete_ ? .destructive : nil) {
                app.env.postActions.confirm(item: c.item, shown: c.post, asUser: c.user, notes: nil)
            }
            .accessibilityIdentifier("menu-confirm")
        } message: { c in
            if let body = c.body { Text(body) }
        }
        .modifier(MenuFailureAlert(actions: app.postActions))
        .alert(SideMenu.shared.LOGOUT_TITLE, isPresented: $app.confirmLogout) {
            Button(UiCopy.shared.CANCEL, role: .cancel) {}.accessibilityIdentifier("logout-cancel")
            Button(UiCopy.shared.LOGOUT, role: .destructive) { app.signOut() }.accessibilityIdentifier("logout-confirm")
        }
        // ---- login and accounts: expired session, link toasts, first-run terms, auth links ----
        .alert(AccountCopy.shared.WARNING, isPresented: Binding(get: { app.session.state.expiredSession != nil },
                                                          set: { if !$0 { app.env.session.dismissExpiredSession() } })) {
            Button(UiCopy.shared.CANCEL, role: .cancel) { app.env.session.dismissExpiredSession() }
            Button(AccountCopy.shared.VERIFY) {
                app.env.session.requestLogin(username: app.session.state.expiredSession)
                app.sheet = .login
            }
        } message: { Text(AccountCopy.shared.AUTH_EXPIRED) }
        .fullScreenCover(isPresented: Binding(get: { !app.termsAccepted }, set: { _ in })) {
            WelcomeView(previouslyAccepted: app.termsPreviouslyAccepted) { app.acceptTerms() }
        }
        .onAppear {
            app.authOverlay.attach()
            app.toastOverlay.attach()
            app.applyThemeToLockWindow()
            // RN's RC offer opens its screen under whatever sheet was up: close the sheet first
            app.authOverlay.onOpenScreen = { [weak app] screen in
                app?.sheet = nil
                app?.push(screen)
            }
        }
        // ---- accounts and shell gaps: delete confirmation, update prompt, announcement (ShellPromptsViews.swift) ----
        .background(ShellPromptsHost(env: app.env))
        // RN postInteractionHandler's link sheet for external links in bodies
        .background(LinkSheetHost())
        .alert(app.alert?.title ?? "", isPresented: Binding(get: { app.alert != nil }, set: { if !$0 { app.alert = nil } }),
               presenting: app.alert) { alert in
            if alert.offerSignIn {
                Button(L.Native.Ios.SignIn.button) { app.sheet = .login }.accessibilityIdentifier("alert-sign-in")
            }
            Button(UiCopy.shared.OK, role: .cancel) {}
        } message: { alert in
            Text(alert.message)
        }
    }
}

struct DestinationView: View {
    let screen: AppScreen
    @EnvironmentObject private var app: AppContainer

    var body: some View {
        switch screen {
        case let s as AppScreenPost:
            PostScreen(author: s.author, permlink: s.permlink, env: app.env)
        // ---- profile, social graph and bookmarks (ProfileViews.swift) ----
        case let s as AppScreenProfile:
            ProfileScreen(author: s.author, section: s.section, env: app.env)
        case let s as AppScreenFollows:
            FollowsScreen(username: s.username, following: s.following, count: s.count, env: app.env)
        case is AppScreenProfileEdit:
            ProfileEditScreen(env: app.env)
        case let s as AppScreenBookmarks:
            BookmarksScreen(tab: s.tab, env: app.env)
        case let s as AppScreenAccountList:
            AccountListScreen(title: s.title, users: s.users)
        case let s as AppScreenFeed:
            if let ranked = s.source as? FeedSourceRanked, !ranked.tag.isEmpty, !ranked.tag.hasPrefix("hive-"), ranked.tag != "my" {
                TagFeedScreen(tag: ranked.tag, sort: ranked.sort, title: s.title, env: app.env)
            } else {
                FeedScreen(source: s.source, title: s.title, env: app.env)
            }
        case is AppScreenSettings:
            SettingsView(observed: app.settings)
        case is AppScreenRefer: ReferScreen(env: app.env)
        // ---- points and perks (PerksViews.swift) ----
        case is AppScreenPerks: PerksScreen(env: app.env)
        case is AppScreenSpinGame: SpinGameScreen(env: app.env)
        // ---- in-app purchases (StoreViews.swift) ----
        case let s as AppScreenGetPoints: GetPointsScreen(screen: s, env: app.env)
        case let s as AppScreenAccountBoost: AccountBoostScreen(screen: s, env: app.env)
        case is AppScreenEmailDigests: EmailDigestsScreen(env: app.env)
        // ---- communities and search (CommunityViews.swift, SearchViews.swift) ----
        case is AppScreenCommunities: CommunitiesScreen(env: app.env)
        case let s as AppScreenCommunity: CommunityScreen(name: s.name, filter: s.filter, env: app.env)
        case let s as AppScreenCommunityMembers: CommunityMembersScreen(name: s.name, title: s.title, env: app.env)
        case let s as AppScreenCommunitySettings: CommunitySettingsScreen(name: s.name, env: app.env)
        case let s as AppScreenCommunityActivities: CommunityActivitiesScreen(name: s.name, title: s.title, env: app.env)
        case is AppScreenSearch: SearchScreen(env: app.env)
        case let s as AppScreenTagResult: TagResultScreen(tag: s.tag, filter: s.filter, env: app.env)
        // ---- platform: the dApp browser and its bridge, the QR scanner (PlatformViews.swift) ----
        case is AppScreenExplore: DappBrowserScreen(initialUrl: nil, env: app.env)
        case let s as AppScreenDappBrowser: DappBrowserScreen(initialUrl: s.url, env: app.env)
        case is AppScreenQrScan: QrScanScreen(env: app.env)
        // ---- feed, posts and reading (PostListsViews.swift) ----
        case let s as AppScreenVoters:
            VotersView(author: s.author, permlink: s.permlink, env: app.env)
        case let s as AppScreenReblogs:
            ReblogsView(author: s.author, permlink: s.permlink, env: app.env)
        case let s as AppScreenEditHistory:
            EditHistoryView(author: s.author, permlink: s.permlink, env: app.env)
        case let s as AppScreenBotComments:
            BotCommentsView(author: s.author, permlink: s.permlink, env: app.env)
        // ---- editor and publishing (EditorViews.swift) ----
        case let s as AppScreenEditor: EditorScreen(args: s.args, env: app.env)
        case let s as AppScreenDrafts: DraftsScreen(tab: s.tab, env: app.env)
        case is AppScreenAiImageGenerator: AiImageSheet(editor: nil, env: app.env)
        // ---- wallet (WalletViews.swift, TransferViews.swift) ----
        case let s as AppScreenAssetDetails: AssetDetailsView(symbol: s.symbol, env: app.env)
        case let s as AppScreenTransfer: TransferRouteView(screen: s)
        case is AppScreenAssetsSelect: ManageAssetsView(env: app.env)
        case let s as AppScreenTrade: TradeView(fund: s.fund, env: app.env)
        case let s as AppScreenRedeem: RedeemView(screen: s, env: app.env)
        // ---- chats (ChatViews.swift) ----
        case let s as AppScreenChatThread: ChatThreadView(screen: s, env: app.env)
        case let s as AppScreenPending:
            ComingSoonView(title: s.title).navigationTitle(s.title).navigationBarTitleDisplayMode(.inline)
        default:
            ShellErrorView { app.path = Array(app.path.dropLast()) }
                // RN's ErrorBoundary reports what it caught
                .onAppear { app.env.crashReporter.capture(report: CrashReport.companion.of(e: KotlinIllegalStateException(message: "No view for \(type(of: screen))"), context: "error-boundary")) }
        }
    }
}

/// RN Alert.alert("Fail!", message) for menu actions (PostActionsState.alert).
struct MenuFailureAlert: ViewModifier {
    @ObservedObject var actions: Observed<PostActionsState>
    @EnvironmentObject private var app: AppContainer

    func body(content: Content) -> some View {
        content.alert(actions.state.alert?.title ?? "", isPresented: Binding(get: { actions.state.alert != nil },
                                                                            set: { if !$0 { app.env.postActions.dismissAlert() } })) {
            Button(UiCopy.shared.OK, role: .cancel) { app.env.postActions.dismissAlert() }
        } message: {
            Text(actions.state.alert?.message ?? "")
        }
    }
}
