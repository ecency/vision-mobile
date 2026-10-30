import Combine
import EcencyCore
import StoreKit
import Foundation
import SwiftUI

/// Sheets the root presents.
enum AppSheet: Identifiable {
    case login
    /// Login and accounts: the account switcher and the keys screen (AccountViews.swift).
    case accounts
    case backupKeys
    /// RN register screen with its saved referral (RegisterViews.swift).
    case register(String)
    case browser(URL)
    case video(URL)
    /// Body images in document order and the tapped index.
    case gallery([String], Int)
    // ---- feed, posts and reading (PostMenu.swift, ReadingViews.swift, QuickProfileView.swift) ----
    case postMenu(MenuRequest)
    case crossPost(PostRef)
    case translation(PostRef)
    case modNotes(NotesRequest)
    case quickProfile(String)
    case reply(ReplyRequest)
    case customiseFilters
    /// RN TIPPING_DIALOG (TransferViews.swift).
    case tip(PostKey)

    var id: String {
        switch self {
        case .login: return "login"
        case .accounts: return "accounts"
        case .backupKeys: return "backupKeys"
        case let .register(referral): return "register:\(referral)"
        case let .browser(url): return "browser:\(url.absoluteString)"
        case let .video(url): return "video:\(url.absoluteString)"
        case let .gallery(urls, index): return "gallery:\(index):\(urls.joined(separator: " "))"
        case let .postMenu(r): return "menu:\(r.post.key)"
        case let .crossPost(r): return "crossPost:\(r.post.key)"
        case let .translation(r): return "translation:\(r.post.key)"
        case let .modNotes(r): return "notes:\(r.post.key)"
        case let .quickProfile(name): return "profile:\(name)"
        case let .reply(r): return "reply:\(r.parent.key)"
        case .customiseFilters: return "customise"
        case let .tip(key): return "tip:\(key.author)/\(key.permlink)"
        }
    }
}

struct AppAlert: Identifiable {
    let id = UUID()
    let title: String
    let message: String
    /// Offer "Sign in" next to OK (vote or reply while signed out).
    var offerSignIn = false
}

/// Launch options (process arguments, `-name value`, read through UserDefaults).
struct LaunchOptions {
    /// Debug only: answer every Hive call from synthetic fixtures (UI tests, offline runs).
    var fakeData = false
    /// Open the renderer demo instead of the feed (debug only).
    var rendererDemo = false
    /// Debug only: open the icon gallery (every AppIcon) instead of the app.
    var iconGallery = false
    /// Debug only: "author/permlink" to push at launch (screenshots, perf runs).
    var openPost: String?
    /// Debug only: jump the opened post to its comments once they load (screenshots).
    var scrollToComments = false
    /// Debug only, with fakeData: a signed-in throwaway account with PIN 1234 and the lock on (UI tests, screenshots).
    var lockDemo = false
    /// Debug only, with fakeData: keep the fake store from the last launch (UI tests of a restart).
    var keepFakeStore = false
    /// Debug only, with fakeData: seconds in the background before the app locks again (UI tests; default 60).
    var lockTimeoutSeconds: Int?
    /// Debug only: start on this tab ("WALLET") and/or with the side menu open (screenshots).
    var startTab: String?
    var openDrawer = false
    /// Debug only, with fakeData: two signed-in fake accounts (spike-alice current, spike-bob), no PIN.
    var seedAccounts = false
    /// Debug only: show the first-run welcome screen even when the terms were accepted (UI tests).
    var showWelcome = false
    /// Debug only: open this screen at launch ("login", "hiveauth", "accounts", "backupKeys"), for UI tests and screenshots.
    var openScreen: String?
    /// Debug only: handle this auth link at launch, as if the system opened it.
    var openLink: String?
    /// Debug only, with fakeData: "upgrade" starts a transfer for the current account, which shows the auth
    /// upgrade sheet (UI tests, screenshots; the fake transport refuses every broadcast).
    var demoSigning: String?
    /// Debug only, with fakeData: "tap" behaves as if a reply notification was tapped (it waits behind the lock),
    /// "banner" as if one arrived while the app is open (UI tests, screenshots).
    var demoPush: String?

    static func current(_ defaults: UserDefaults = .standard) -> LaunchOptions {
        #if DEBUG
        return LaunchOptions(fakeData: defaults.bool(forKey: "fakeData"), rendererDemo: defaults.bool(forKey: "rendererDemo"),
                             iconGallery: defaults.bool(forKey: "iconGallery"),
                             openPost: defaults.string(forKey: "openPost"), scrollToComments: defaults.bool(forKey: "scrollToComments"),
                             lockDemo: defaults.bool(forKey: "lockDemo"),
                             keepFakeStore: defaults.bool(forKey: "keepFakeStore"),
                             lockTimeoutSeconds: defaults.string(forKey: "lockTimeoutSeconds").flatMap { Int($0) },
                             startTab: defaults.string(forKey: "startTab"), openDrawer: defaults.bool(forKey: "openDrawer"),
                             seedAccounts: defaults.bool(forKey: "seedAccounts"), showWelcome: defaults.bool(forKey: "showWelcome"),
                             openScreen: defaults.string(forKey: "openScreen"), openLink: defaults.string(forKey: "openLink"),
                             demoSigning: defaults.string(forKey: "demoSigning"), demoPush: defaults.string(forKey: "demoPush"))
        #else
        return LaunchOptions()
        #endif
    }
}

/// The process-wide object graph for the UI: the shared AppEnvironment, the session and vote
/// observers, and the navigation state every screen pushes onto.
@MainActor
final class AppContainer: ObservableObject {
    let env: AppEnvironment
    let session: Observed<SessionState>
    let votes: Observed<VoteState>
    let lock: Observed<AppLockState>
    let options: LaunchOptions
    /// The lock screen and app switcher cover, in a window above every sheet and alert.
    let lockOverlay: LockOverlay
    /// Signing and link prompts, in a window above every sheet and below the lock (AuthOverlay.swift).
    let authOverlay: AuthOverlay
    /// Toasts above every sheet (ToastOverlay.swift).
    let toastOverlay: ToastOverlay
    /// The first-run terms were accepted (RN welcome screen, `isTermsAccepted`).
    @Published var termsAccepted: Bool {
        // the shared prompt order needs it: no root prompt (and no rating prompt) before the welcome screen is done
        didSet { env.setWelcomeDone(done: termsAccepted) }
    }
    /// The terms were accepted on an earlier version: RN shows the welcome screen again with the box ticked.
    private(set) var termsPreviouslyAccepted = false
    private var linkWatcher: Closeable?
    /// The root reads session state (expired-session alert): forward its changes.
    private var sessionChanges: AnyCancellable?
    /// Activations seen; the first is the launch, whose points replay `shellStarted` asks for.
    private var pointsActivations = 0

    /// The shared navigation (tab, pushed screens, side menu); see NavState in the core.
    /// Saved by the container on every change (not by a view, which is gone while the app is locked), restored once
    /// when the container is built, and reset on sign-out and wipe like everything else.
    @Published private(set) var nav: NavState = NavState.companion.decodeJoined(saved: "") {
        didSet {
            guard nav != oldValue else { return }
            UserDefaults.standard.set(savedNavigation, forKey: AppContainer.navigationKey)
            UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: AppContainer.navigationKey + ".at")
        }
    }
    static let navigationKey = "ecency.navigation"
    /// Like Android's saved state after process death, not a permanent memory: a navigation older than this is
    /// not restored (the user left the app, or the system ended it long ago). RN restores nothing.
    static let restoreWindow: TimeInterval = 30 * 60

    /// The saved navigation if it is recent enough to restore; empty otherwise.
    static func savedRecently(_ defaults: UserDefaults = .standard, now: Date = Date()) -> String {
        let at = defaults.double(forKey: navigationKey + ".at")
        guard at > 0, now.timeIntervalSince1970 - at <= restoreWindow else { return "" }
        return defaults.string(forKey: navigationKey) ?? ""
    }

    /// Debug fake-data launches start from a fresh install unless they keep the fake store (UI tests).
    static func forgetSavedNavigation(_ defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: navigationKey)
        defaults.removeObject(forKey: navigationKey + ".at")
        defaults.removeObject(forKey: navigationKey + ".owner")
    }
    /// The home feeds outlive the Feed tab (switching tabs keeps their data, as on Android and in RN).
    let homeFeeds = HomeFeeds()
    /// The Waves tab's tabs and lists (WavesViews.swift).
    lazy var waves = WavesStore(env: env)
    /// The Chats tab's list (ChatViews.swift): kept while other tabs show, like the RN tab screen.
    lazy var chats = Observed(env.chat.chatsModel(), ownsModel: false)
    /// A sheet to show once the current one is gone (a sheet cannot present over itself).
    var nextSheet: AppSheet?
    let ui: Observed<UiMessagesState>
    let connectivity: Observed<ConnectivityState>
    let badges: Observed<Badges>
    /// Notifications and push (NotificationViews.swift, Push.swift).
    let notifications: Observed<NotificationsState>
    let push: Observed<PushState>
    private var pushOpens: AnyCancellable?
    /// Hands the app language over to the widget extension (IosWidgetStore.handOver).
    private var widgetLanguage: Closeable?
    private var foregroundObservers: [NSObjectProtocol] = []
    /// Ecency Pro members for the badge (ProfileViews.swift).
    let proMembers: Observed<NSSet>
    /// The post menu's running actions, failure alert and deletions (PostMenu.swift).
    let postActions: Observed<PostActionsState>
    /// A menu action RN confirms first (delete, report).
    @Published var confirm: PostMenuEffectConfirm?
    /// Reply drafts opened from a menu (the post screen keeps its own observers).
    let menuReplies = ReplyDrafts()
    private let network = NetworkMonitor()
    /// RN settings.theme, applied to the main window and the lock window.
    /// RN settings (SettingsViews.swift): device settings, support percents, the RN settings import.
    let settings: Observed<SettingsState>
    /// The app language for the SwiftUI root (AppLanguage.swift), driven by the settings.
    let language: AppLanguage
    private var settingsChanges: AnyCancellable?
    /// RN settings.theme (kept by the shared settings), applied to the main window and the lock window.
    @Published private(set) var themeMode: ThemeMode = .system {
        didSet { applyThemeToLockWindow() }
    }
    @Published var sheet: AppSheet? {
        didSet {
            // the login sheet closed and nobody signed in: a share link's text that waited for that login goes
            if oldValue?.id == "login", sheet?.id != "login", !env.session.current.isLoggedIn { env.waves.dropComposeText() }
        }
    }
    @Published var alert: AppAlert?

    /// Default vote weight in percent, remembered like the RN app's upvote slider.
    var voteWeightPercent: Double {
        get { (UserDefaults.standard.object(forKey: "voteWeightPercent") as? Double) ?? 100 }
        set { UserDefaults.standard.set(newValue, forKey: "voteWeightPercent") }
    }

    init(env: AppEnvironment, options: LaunchOptions) {
        self.env = env
        self.options = options
        session = Observed(env.session, ownsModel: false)
        votes = Observed(env.votes, ownsModel: false)
        lock = Observed(env.appLock, ownsModel: false)
        ui = Observed(env.ui, ownsModel: false)
        connectivity = Observed(env.connectivity, ownsModel: false)
        badges = Observed(env.badges, ownsModel: false)
        notifications = Observed(env.notifications, ownsModel: false)
        push = Observed(env.push, ownsModel: false)
        proMembers = Observed(env.proMembers, ownsModel: false)
        postActions = Observed(env.postActions, ownsModel: false)
        // -demoBiometrics YES (fake-data runs only): biometrics count as enrolled, for the PIN enable offer UI tests
        Biometrics.demo = options.fakeData && UserDefaults.standard.bool(forKey: "demoBiometrics")
        env.appLock.biometricBinder = DomainStateBinder()
        env.prefs = UserDefaultsPrefs.make(fakeData: options.fakeData, keep: options.keepFakeStore)
        // the UI copy follows the language setting, synced with Settings > Ecency > Language (README "Localization");
        // fake-data runs (UI tests) leave the app's AppleLanguages alone
        env.drivesLanguageCatalog = true
        if !options.fakeData { env.systemLanguage = IosSystemLanguage() }
        #if DEBUG
        // -pseudoLocale ar-XB|en-XA (debug only): the mirrored or the long accented pseudo locale over whatever
        // language the settings hold, for the RTL and long text screenshot runs (README "Localization")
        if let pseudo = UserDefaults.standard.string(forKey: "pseudoLocale") { L10n.shared.usePseudo(id: pseudo) }
        #endif
        // the saved settings apply before the first frame (theme, image server, language); the RN import follows in
        // the background
        env.settings.start(deviceLocale: Locale.preferredLanguages.first)
        language = AppLanguage(env: env, holdUntilSettled: true)
        if !options.fakeData {
            // earlier preview builds kept the theme in their own key; it moves to the shared settings once
            env.settings.adoptPlatformTheme(name: UserDefaults.standard.string(forKey: "themeMode"))
            UserDefaults.standard.removeObject(forKey: "themeMode")
        }
        settings = Observed(env.settings, ownsModel: false)
        // local drafts (editor): private files; fake-data runs use their own folder and never reach the image host
        let draftsDirectory = EditorImages.draftsDirectory(fakeData: options.fakeData)
        if options.fakeData, !options.keepFakeStore { try? FileManager.default.removeItem(atPath: draftsDirectory) }
        env.draftStorage = FileDraftStorage(directory: draftsDirectory)
        // copies of picked or recorded videos never outlive the run that made them
        EditorVideos.purge()
        // RN maybeRequestReview: the system rating prompt, asked for by the shared rules after a publish (never in fake-data runs)
        if !options.fakeData {
            env.requestReview = {
                guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }) else { return KotlinBoolean(bool: false) }
                SKStoreReviewController.requestReview(in: scene)
                return KotlinBoolean(bool: true)
            }
        }
        if options.fakeData { env.imageTransport = ImageUploadTransportCompanion.shared.demo() }
        // dictation: the microphone, or in fake-data runs a recorder without one (no audio exists then)
        env.dictationRecorder = options.fakeData ? DemoDictationRecorder() : IosDictationRecorder()
        // a recording goes on in the background (audio background mode, the system's recording indicator); a call
        // taking the microphone holds the clip unsent (README "Background media")
        if !options.fakeData { env.dictationSessions.host = IosDictationHost(sessions: env.dictationSessions) }
        // the wave composer attaches images through the editor's upload
        _ = WaveUploadAttacher.companion.install(env: env)
        lockOverlay = LockOverlay(model: env.appLock, proxyBase: env.proxyBase)
        authOverlay = AuthOverlay(env: env)
        toastOverlay = ToastOverlay(ui: env.ui)
        // fake-data runs (UI tests) count the terms as accepted unless a test asks for the welcome screen
        if let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String { env.appVersion = version }
        env.updateSource = UpdateSource.companion.of(releasesUrl: BuildSecrets.updateReleasesUrl, tagPrefix: BuildSecrets.updateTagPrefix)
        // debug fake-data runs never load the hosted signup challenge, and count this version's welcome as passed
        if options.fakeData {
            env.captchaStandIn = "demo-captcha"
            if !options.showWelcome { _ = env.welcome.accept(consent: true) }
            // -demoSpotlight YES: a feature spotlight on the home feed
            DemoAuthServices.shared.demoSpotlights = UserDefaults.standard.bool(forKey: "demoSpotlight") ? DemoAuthServices.shared.SAMPLE_SPOTLIGHT : "[]"
            // -demoRcOffer YES: the app-wide "Not enough Resource Credits" offer, as a refused broadcast raises it
            if UserDefaults.standard.bool(forKey: "demoRcOffer") { env.rcOffer.raise() }
            // -demoAnnouncement YES / -demoUpdate YES: the prompts, from synthetic answers
            DemoAuthServices.shared.demoAnnouncements = UserDefaults.standard.bool(forKey: "demoAnnouncement") ? DemoAuthServices.shared.SAMPLE_ANNOUNCEMENT : "[]"
            // -demoDigests YES: two synthetic email digest subscriptions (settings screenshots, UI tests)
            DemoAuthServices.shared.demoDigests = UserDefaults.standard.bool(forKey: "demoDigests") ? DemoAuthServices.shared.SAMPLE_DIGESTS : "[]"
            // -demoStreak YES: quests with a streak and a freeze (perks screenshots, UI tests)
            if UserDefaults.standard.bool(forKey: "demoStreak") { DemoAuthServices.shared.demoQuests = DemoAuthServices.shared.SAMPLE_QUESTS }
            // -demoNoPro YES: nobody is on the Pro roster until a demo Pro purchase (Pro screenshots, UI tests)
            if UserDefaults.standard.bool(forKey: "demoNoPro") { DemoAuthServices.shared.clearDemoPro() }
            DemoAuthServices.shared.demoReleaseTag = UserDefaults.standard.bool(forKey: "demoUpdate") ? "99.1.0" : nil
            // -demoToast "<text>": a toast through the shared channel at launch (toast layout UI tests)
            if let text = UserDefaults.standard.string(forKey: "demoToast") {
                // -demoToastDelay <s>: later, so a test can open a sheet first
                let delay = UserDefaults.standard.double(forKey: "demoToastDelay")
                if delay > 0 {
                    let ui = env.ui
                    DispatchQueue.main.asyncAfter(deadline: .now() + delay) { _ = ui.toast(text: text) }
                } else {
                    _ = env.ui.toast(text: text)
                }
            }
        }
        // RN: the welcome screen shows on the first launch and again after each update
        termsAccepted = (!env.welcome.shouldShow() || options.fakeData) && !options.showWelcome
        env.setWelcomeDone(done: termsAccepted)
        // (an earlier preview build kept the acceptance under its own key; fake-data runs start clean)
        termsPreviouslyAccepted = env.welcome.termsAccepted() || (!options.fakeData && UserDefaults.standard.bool(forKey: "termsAccepted"))
        // the Hive node pool: the saved server at once, then the published list (RN initSdkConfig)
        Task { try? await env.hiveNodes.start() }
        // in-app purchases (README "In-app purchases"): StoreKit 2, or the offline demo store in fake-data runs; the
        // transaction listener runs from the start and whatever an earlier run left is delivered
        env.store = options.fakeData ? DemoStore(platform: PurchaseService.companion.APP_STORE) : StoreKitStore()
        if CrashReporting.started && !options.fakeData { env.crashReporter = CrashReporting.Reporter() }
        env.analytics.config = AnalyticsConfig(host: AppContainer.analyticsHost(BuildSecrets.plausibleHost, debug: AppContainer.isDebugBuild || options.fakeData),
                                               os: "ios", userAgent: AppContainer.analyticsUserAgent())
        env.purchases.start()
        // the restart journal: sends an earlier run left undecided are asked about now (README "Wallet")
        env.sendJournal.start()
        env.session.start()
        // runs the RN migration first (when there is RN data), then decides whether to lock
        env.appLock.start()
        startNotifications()
        restore(AppContainer.savedRecently())
        if let target = options.openPost?.split(separator: "/"), target.count == 2 {
            nav = nav.push(screen: AppScreenPost(author: String(target[0]), permlink: String(target[1])))
        }
        if let name = options.startTab, let tab = AppTab.entries.first(where: { $0.name == name }) { nav = nav.selectTab(tab: tab) }
        if options.openDrawer { nav = nav.openDrawer() }
        network.start { [weak env] online in env?.connectivity.setOnline(online: online) }
        // RN ProposalVoteRequest: the main feed's proposal card follows the session from now on
        env.proposalVote.start()
        // shares the extension wrote and nobody opened (older than a day)
        ShareInbox.purge()
        #if DEBUG
        // -demoShare <text>: a share of that text, as if the share extension sent it (UI tests, screenshots)
        if let text = UserDefaults.standard.string(forKey: "demoShare") { env.share.receive(texts: [text], images: []) }
        #endif
        // a wipe returns to the start; a sign-out keeps the tab and drops screens that need the account (RN)
        events = env.watchEvents { [weak self] event in
            MainActor.assumeIsolated {
                guard let self else { return }
                if event is AppEventDataWiped {
                    self.resetNavigation()
                    ShareInbox.purge(all: true)
                }
                // what dApps stored in Explore's own website data goes when no account is left
                if event is AppEventDataWiped || (event as? AppEventSessionChanged).map({ $0.username == nil }) == true { DappWebViews.clearData() }
            }
        }
        // @Published delivers the new state before it is stored: use the value handed in, not session.state
        sessionLoaded = session.$state.sink { [weak self] state in
            guard let self, !state.isLoading else { return }
            // a restored navigation keeps its gated screens only for the account that had them open
            if !self.ownerChecked {
                self.ownerChecked = true
                if self.restoredNavigation {
                    self.nav = self.nav.restoredFor(owner: UserDefaults.standard.string(forKey: AppContainer.navigationKey + ".owner"), current: state.username)
                }
            }
            UserDefaults.standard.set(state.username, forKey: AppContainer.navigationKey + ".owner")
            if let entry = self.pendingEntry {
                self.pendingEntry = nil
                self.open(entry: entry, loggedIn: state.isLoggedIn)
            }
        }
        // any account change (sign-out, switch, a logout that moved to the next account, a link login as someone
        // else) keeps the tab and public screens; screens that need the account belonged to the previous one (RN)
        accountChanges = session.$state.filter { !$0.isLoading }.map(\.username).removeDuplicates().dropFirst()
            .sink { [weak self] username in
                guard let self else { return }
                // the reply editor's account tile asked for this switch: the reply opens again for the new account
                self.nav = self.env.replyHandoff.reopen(afterChange: self.nav.afterAccountChange(), username: username)
                // what the post menu had open belongs to the account that opened it
                self.confirm = nil
                // also a sheet still queued behind the one that is closing
                switch self.nextSheet {
                case .postMenu, .modNotes, .crossPost, .reply: self.nextSheet = nil
                default: break
                }
                switch self.sheet {
                case .postMenu, .modNotes, .crossPost, .reply: self.sheet = nil
                default: break
                }
            }
        sessionChanges = session.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
        themeMode = settings.state.theme
        settingsChanges = settings.$state.sink { [weak self] s in
            guard let self, self.themeMode != s.theme else { return }
            self.themeMode = s.theme
        }
        linkWatcher = env.authLinks.watch { [weak self] state in MainActor.assumeIsolated { self?.linkEffects(state) } }
        switch options.openScreen {
        case "login": sheet = .login
        case "accounts": sheet = .accounts
        case "backupKeys": sheet = .backupKeys
        case "register": sheet = .register("")
        case "refer": openAfterAccountsLoaded(NavEntry.companion.screen(screen: AppScreenRefer.shared))
        case "perks": openAfterAccountsLoaded(NavEntry.companion.screen(screen: AppScreenPerks.shared))
        case "spinGame": openAfterAccountsLoaded(NavEntry.companion.screen(screen: AppScreenSpinGame.shared))
        // purchase screenshots and UI tests: -openScreen getPoints | accountBoost | getPoints:<user>:<product>
        case "accountBoost": openAfterAccountsLoaded(NavEntry.companion.screen(screen: AppScreenAccountBoost(username: "")))
        case let points? where points == "getPoints" || points.hasPrefix("getPoints:"):
            let parts = points.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
            openAfterAccountsLoaded(NavEntry.companion.screen(screen: AppScreenGetPoints(username: parts.count > 1 ? parts[1] : "", productId: parts.count > 2 ? parts[2] : "")))
        case "settings": nav = nav.push(screen: AppScreenSettings.shared)
        // platform screenshots and UI tests: the browser home, the scanner (-qrScan <value> reads a code), the synthetic dApp
        case "explore": openAfterAccountsLoaded(NavEntry.companion.screen(screen: AppScreenExplore.shared))
        case "qr": openAfterAccountsLoaded(NavEntry.companion.screen(screen: AppScreenQrScan.shared))
        case "dapp":
            DappWebViews.demo = true
            openAfterAccountsLoaded(NavEntry.companion.screen(screen: AppScreenDappBrowser(url: DemoDapp.shared.URL)))
        case "emailDigests": openAfterAccountsLoaded(NavEntry.companion.screen(screen: AppScreenEmailDigests.shared))
        // profile area screenshots and UI tests (gated screens wait for the accounts like links do)
        case "bookmarks": openAfterAccountsLoaded(NavEntry.companion.screen(screen: AppScreenBookmarks(tab: 0)))
        case "editProfile": openAfterAccountsLoaded(NavEntry.companion.screen(screen: AppScreenProfileEdit.shared))
        // editor area (gated: they wait for the accounts); "editor:<arg>" takes AppScreen.Editor.of's argument
        case "drafts": openAfterAccountsLoaded(NavEntry.companion.screen(screen: AppScreenDrafts(tab: 0)))
        case let editor? where editor == "editor" || editor.hasPrefix("editor:"):
            openAfterAccountsLoaded(NavEntry.companion.screen(screen: AppScreenEditor.companion.of(arg: String(editor.dropFirst("editor:".count)))))
        // wallet screenshots and UI tests: -openScreen "wallet:transfer,transfer,HIVE,hive,spike-bob" (a saved-navigation line, ',' separated)
        case let line? where line.hasPrefix("wallet:"):
            let encoded = String(line.dropFirst("wallet:".count)).replacingOccurrences(of: ",", with: "\u{1F}")
            if let screen = NavState.companion.decodeScreen(line: encoded) { openAfterAccountsLoaded(NavEntry.companion.screen(screen: screen)) }
        // chats screenshots and UI tests: -openScreen chat:<channelId>[:<type>]
        case let chat? where chat.hasPrefix("chat:"):
            let parts = chat.split(separator: ":").map(String.init)
            openAfterAccountsLoaded(NavEntry.companion.screen(screen: AppScreenChatThread(channelId: parts[1], type: parts.count > 2 ? parts[2] : "")))
            #if DEBUG
            // -demoChat typing: someone types in that conversation a moment after it opened (fake data only)
            if options.fakeData, UserDefaults.standard.string(forKey: "demoChat") == "typing" {
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) { DemoAuthServices.shared.chat?.typing(channelId: parts[1], who: "spike-bob") }
            }
            #endif
        case let route? where route.hasPrefix("route:"):
            // -openScreen route:COMMUNITY:hive-125125 (screenshots): any RN route by name, with its argument
            let parts = route.split(separator: ":", maxSplits: 2).map(String.init)
            if let name = RouteName.entries.first(where: { $0.name == parts[1] }) {
                nav = nav.push(screen: AppScreenCompanion.shared.of(route: name, arg: parts.count > 2 ? parts[2] : ""))
            }
        // ---- feed, posts and reading (screenshots, UI tests) ----
        case "voters": nav = nav.push(screen: AppScreenVoters(author: "spike-alice", permlink: "fake-post-1"))
        case "reblogs": nav = nav.push(screen: AppScreenReblogs(author: "spike-alice", permlink: "fake-post-1"))
        case "editHistory": nav = nav.push(screen: AppScreenEditHistory(author: "spike-alice", permlink: "fake-post-1"))
        case "quickProfile": sheet = .quickProfile("spike-alice")
        case "customise": sheet = .customiseFilters
        case "tag": nav = nav.push(screen: AppScreenFeed(source: FeedSourceRanked(sort: .trending, tag: "photography"), title: "#photography"))
        case "menu": openMenu(key: PostKey(author: "spike-alice", permlink: "fake-post-1"))
        case "hiveauth":
            sheet = .login
            env.session.startHiveAuth(usernameInput: "spike-alice")
        case "digest":
            // the email digest offer of a first post, in fake-data runs only (the demo relay answers; screenshots, UI tests)
            if options.fakeData {
                Task { @MainActor [env] in
                    while env.session.current.isLoading { try? await Task.sleep(nanoseconds: 200_000_000) }
                    if let user = env.session.current.username { env.digest.show(type: "own", target: user, firstPublish: true) }
                }
            }
        default: break
        }
        if let link = options.openLink, let url = URL(string: link) { handle(url: url) }
        #if DEBUG
        if options.fakeData, options.demoSigning == "upgrade" { demoUpgrade() }
        #endif
    }

    // MARK: Notifications and push

    /// The unread count and list model, push registration, tapped pushes and the foreground refreshes.
    private func startNotifications() {
        // fake-data runs (UI tests, screenshots) never ask for the permission or talk to Firebase
        if !options.fakeData {
            env.pushPlatform = IOSPushPlatform()
            env.activityWebsocketUrl = BuildSecrets.activityWebsocketUrl.isEmpty ? nil : BuildSecrets.activityWebsocketUrl
            env.setChatHost(host: BuildSecrets.chatBackendUrl.isEmpty ? nil : BuildSecrets.chatBackendUrl)
        }
        env.notifications.start()
        // the widgets' snapshot follows the account, the lock setting and the unread count (README "Widgets");
        // fake-data runs and tests leave the App Group alone
        if !options.fakeData {
            env.widgets.store = IosWidgetStore()
            // the widgets run in their own process: they read the app language from the App Group (README "Localization")
            widgetLanguage = env.watchLanguage { language in
                DispatchQueue.main.async { IosWidgetStore.handOver(language: language) }
            }
        }
        env.widgets.start()
        // the chat badge (README "Chats"): read at start, unlock and foreground
        env.chat.start()
        env.push.start()
        PushBridge.attach(env)
        // a tapped notification, released by the shared model once the accounts were read and the lock is open
        pushOpens = push.$state.sink { [weak self] state in
            guard let self, let entry = state.open else { return }
            // the sink runs before the new state is stored: act after it settled
            DispatchQueue.main.async {
                self.env.push.consumeOpen()
                self.open(entry: entry, loggedIn: self.env.session.current.isLoggedIn)
            }
        }
        let center = NotificationCenter.default
        foregroundObservers = [
            center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated {
                    self?.env.isForeground = true
                    self?.env.notifications.onForeground()
                    self?.env.chat.onForeground()
                    self?.env.push.onForeground()
                    self?.env.purchases.onForeground()
                    // RN lazyMutatePendingActivities on every return to the foreground (the launch's own replay is
                    // shellStarted's: the first activation is skipped)
                    if let self {
                        if self.pointsActivations > 0 { self.env.pointActivities.replayPending() }
                        self.pointsActivations += 1
                    }
                }
            },
            center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated {
                    self?.env.isForeground = false
                    self?.env.push.onBackground()
                }
            },
        ]
        #if DEBUG
        let reply = ["type": "reply", "source": "spike-bob", "target": "spike-alice", "permlink1": "fake-post-2", "id": "demo-push-1"]
        if options.fakeData, options.demoPush == "tap" { env.push.opened(data: reply) }
        if options.fakeData, options.demoPush == "banner" {
            Task {
                while env.session.current.isLoading || env.appLock.current.hidesContent { try? await Task.sleep(nanoseconds: 200_000_000) }
                env.push.received(data: reply, title: nil, body: nil)
            }
        }
        #endif
    }

    // MARK: Login and accounts

    #if DEBUG
    /// A 0.001 HIVE transfer from the current fake account to spike-bob: needs the active key, so the upgrade sheet shows.
    private func demoUpgrade() {
        Task {
            while env.session.current.isLoading { try? await Task.sleep(nanoseconds: 100_000_000) }
            guard let user = env.session.current.username else { return }
            _ = try? await DemoAuthServices.shared.broadcastTransfer(signing: env.signing, from: user, to: "spike-bob", actionId: env.idempotencyKey())
        }
    }
    #endif

    /// ecency://, hive:// and the ecency.com /auth and /signup links; the shared model asks before acting.
    /// Content links (ecency.com posts, profiles, feeds) open in the navigation, gated like every entry; while
    /// locked they open behind the lock.
    func handle(url incoming: URL) {
        let url = URL(string: ContentLinks.shared.legacyScheme(url: incoming.absoluteString)) ?? incoming
        // RN #2627: a first launch from a link shows the welcome screen (a full screen cover); a sheet the link would
        // open (login for a gated screen, the in-app browser, a link prompt) cannot show over it and was lost. The
        // link waits for the terms and is handled once they are accepted; the newest wins.
        guard termsAccepted else {
            linkAfterTerms = url
            return
        }
        // the share extension's hand-over (README "Share into the app"): only a folder id travels in the link
        if ShareInbox.isShareLink(url) {
            if let shared = ShareInbox.read(url) { env.share.receive(texts: shared.texts, images: shared.images.map { $0.absoluteString }) }
            return
        }
        if env.authLinks.handle(url: url.absoluteString) { return }
        // the widgets' shortcuts (README "Widgets"): a place in the app, gated like every entry
        if let entry = env.shortcutEntry(url: url.absoluteString) {
            openAfterAccountsLoaded(entry)
            return
        }
        env.waves.openLink(url: url.absoluteString) // ecency.com/waves?text=...: the composer opens with the text
        if let entry = ContentLinks.shared.entry(url: url.absoluteString) {
            openAfterAccountsLoaded(entry)
        } else if let page = ContentLinks.shared.browserFallback(url: url.absoluteString), let pageURL = URL(string: page) {
            // a first-party page no screen claims (universal links hand over every path the site lists)
            sheetURL(pageURL)
        }
    }

    /// The entry waiting for the accounts (a cold start link); the newest wins.
    private(set) var pendingEntry: NavEntry?

    /// Opens [entry] once the accounts were read: before that, gating on the session would send a signed-in
    /// reader to the login sheet.
    func openAfterAccountsLoaded(_ entry: NavEntry) {
        if session.state.isLoading { pendingEntry = entry } else { open(entry: entry) }
    }

    func acceptTerms() {
        _ = env.acceptWelcome(consent: true)
        termsAccepted = true
        if let url = linkAfterTerms {
            linkAfterTerms = nil
            handle(url: url)
        }
    }

    /// A link that arrived while the welcome screen showed ([handle]).
    private(set) var linkAfterTerms: URL?

    /// An https page in the in-app browser.
    func sheetURL(_ url: URL) { sheet = .browser(url) }

    /// "Update Now" (RN picks by installer): an App Store install opens the store page Apple's lookup names for
    /// this bundle id, anything else (TestFlight, debug) the releases page.
    func openStoreForUpdate() {
        let releases = URL(string: UpdateLinks.shared.RELEASES_PAGE)!
        guard InstallSource.current == UpdateLinks.shared.APP_STORE_INSTALLER else {
            UIApplication.shared.open(releases, options: [:], completionHandler: nil)
            return
        }
        Task {
            let page = try? await env.onboardingApi.appStorePage(lookupUrl: UpdateLinks.shared.APP_STORE_LOOKUP)
            let url = page.flatMap { UpdateLinks.shared.isAppStorePage(url: $0) ? URL(string: $0) : nil } ?? releases
            await UIApplication.shared.open(url)
        }
    }

    /// RN openStoreListing ("Rate Ecency"): this app's App Store page (Apple's lookup by bundle id) on its review
    /// form; offline or without a page, RN's web listing.
    func openStoreListing() {
        Task {
            // the lookup is capped (SettingsModel.RATE_LOOKUP_MILLIS), so offline the web listing opens quickly
            let review = (try? await env.settings.iosRatePage()) ?? StoreListing.shared.IOS_WEB
            if let url = URL(string: review) { await UIApplication.shared.open(url) }
        }
    }

    /// RN `_handleSendFeedback`: a mail to bug@ecency.com with the account, app version and device, then RN's toast.
    func sendFeedback() {
        let info = Bundle.main.infoDictionary
        let version = "\(info?["CFBundleShortVersionString"] as? String ?? "") (\(info?["CFBundleVersion"] as? String ?? ""))"
        let platform = "\(UIDevice.current.name) - iOS \(UIDevice.current.systemVersion)"
        let mail = SettingsOptions.shared.feedbackMailto(username: env.session.current.username, appVersion: version, platform: platform)
        guard let url = URL(string: mail) else { return }
        UIApplication.shared.open(url, options: [:]) { [weak self] opened in
            self?.env.ui.toast(text: opened ? SettingsCopy.shared.FEEDBACK_SUCCESS : SettingsCopy.shared.FEEDBACK_FAIL)
        }
    }

    /// The announcement's primary button: an in-app screen or a web page, never an auth link.
    func openAnnouncementTarget(_ target: AnnouncementTarget?) {
        switch target {
        case let inApp as AnnouncementTargetInApp: open(entry: inApp.entry)
        case let web as AnnouncementTargetWeb: if let url = URL(string: web.url) { sheet = .browser(url) }
        default: break
        }
    }

    func logout(_ username: String) {
        Task {
            do { try await env.session.logout(username: username) } catch {
                alert = AppAlert(title: UiCopy.shared.FAIL, message: ErrorText.message(error))
            }
        }
    }

    private var events: Closeable?
    private var sessionLoaded: AnyCancellable?
    private var accountChanges: AnyCancellable?

    deinit { events?.close() }

    /// The lock window follows the theme choice too (it is a separate window).
    func applyThemeToLockWindow() {
        let style: UIUserInterfaceStyle = themeMode == .light ? .light : themeMode == .dark ? .dark : .unspecified
        lockOverlay.window?.overrideUserInterfaceStyle = style
        toastOverlay.window?.overrideUserInterfaceStyle = style
    }

    func logoutAll() {
        Task {
            do { try await env.session.logoutAll() } catch {
                alert = AppAlert(title: UiCopy.shared.FAIL, message: ErrorText.message(error))
            }
        }
    }

    /// Callbacks (only ever the parser-validated callback of the link), toasts and navigation of the link model.
    private func linkEffects(_ state: AuthLinkState) {
        if let callback = state.callback {
            env.authLinks.consumeCallback()
            if let url = URL(string: callback) { UIApplication.shared.open(url) }
        }
        if let text = state.notice {
            env.authLinks.consumeNotice()
            _ = env.ui.toast(text: text) // the one toast channel (MessagesOverlay)
        }
        if let nav = state.navigation {
            env.authLinks.consumeNavigation()
            switch nav {
            case let login as LinkNavigationLogin:
                env.session.requestLogin(username: login.username)
                sheet = .login
            case let signup as LinkNavigationSignup:
                // RN deepLinkParser: the register screen with the link's values (env.signupPrefill)
                sheet = .register(signup.referral)
            case let screen as LinkNavigationScreen:
                // an ecency://transfer naming only the recipient: the transfer screen for it
                push(screen.screen)
            default: break
            }
        }
    }

    /// Builds the environment for this launch; nil plus a message if the platform pieces fail.
    static func make(options: LaunchOptions = .current()) -> Result<AppContainer, AppStartError> {
        #if DEBUG
        let debugKeyImport = true
        #else
        let debugKeyImport = false
        #endif
        do {
            let env: AppEnvironment
            #if DEBUG
            if options.fakeData {
                let store = KeychainSecureStore(service: "app.esteem.mobile.ios.fake.secure")
                if !options.keepFakeStore {
                    try store.wipe()
                    forgetSavedNavigation()
                    if options.lockDemo { try seedLockDemo(store) }
                    if options.seedAccounts { try seedAccounts(store) }
                }
                env = AppEnvironment(
                    api: try EcencyApi(nodes: ["https://fake-hive.invalid"], transport: FakeHiveTransport()),
                    vault: KeyVaultAsync(vault: KeyVault(store: store)),
                    renderer: PostBodyRendererCompanion.shared.`default`(proxyBase: ImageProxy.companion.DEFAULT_BASE),
                    dispatchers: AppDispatchers.companion.platform(),
                    proxyBase: ImageProxy.companion.DEFAULT_BASE,
                    debugKeyImport: true,
                    nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
                    security: options.lockTimeoutSeconds.map {
                        SecurityConfig(pinIterations: PinLock.companion.DEFAULT_ITERATIONS, lockTimeoutMillis: Int64($0) * 1000,
                                       monotonicMillis: SecurityConfig.companion.DEFAULT.monotonicMillis)
                    } ?? SecurityConfig.companion.DEFAULT,
                    legacy: nil,
                    // offline token endpoint and HiveAuth relay: login flows run without any network
                    auth: fakeAuth()
                )
                return .success(AppContainer(env: env, options: options))
            }
            #endif
            let store = KeychainSecureStore()
            // Keychain items survive an uninstall; the vault must not hand them to a fresh install.
            _ = try store.wipeIfFreshInstall(defaults: .standard)
            // the RN app's accounts and PIN, imported once by the app lock's start before it decides anything
            let legacy = LegacyMigrationSource(storage: AsyncStorageReader.companion.forApp(),
                                               pinKey: BuildSecrets.pinKey, defaultPin: BuildSecrets.defaultPin)
            env = try AppEnvironment.companion.create(store: store, debugKeyImport: debugKeyImport, legacy: legacy, userAgent: BuildSecrets.userAgent)
            return .success(AppContainer(env: env, options: options))
        } catch {
            return .failure(AppStartError(message: ErrorText.message(error)))
        }
    }

    #if DEBUG
    /// The demo token endpoint and relay, with Ecency's REST answered offline too (FakeRestHttp).
    private static func fakeAuth() -> AuthServices {
        let demo = DemoAuthServices.shared.create(nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) }, waitMillis: 300_000)
        return AuthServices(http: FakeRestHttp(fallback: demo.http), sockets: demo.sockets, hiveSigner: demo.hiveSigner,
                            tokenHost: demo.tokenHost, hiveAuthHost: demo.hiveAuthHost)
    }

    /// Two fake accounts signed in with the fake transport's throwaway keys (spike-alice current).
    private static func seedAccounts(_ store: KeychainSecureStore) throws {
        let vault = KeyVault(store: store)
        for user in ["spike-bob", "spike-alice"] {
            let wif = PrivateKey.companion.fromLogin(username: user, password: FakeHiveTransport.password, role: .posting).toWif()
            // -seedActive YES: the active key too (UI tests of active operations)
            let active = UserDefaults.standard.bool(forKey: "seedActive")
                ? PrivateKey.companion.fromLogin(username: user, password: FakeHiveTransport.password, role: .active).toWif() : nil
            try vault.save(account: VaultAccount(
                username: user, authType: active == nil ? .postingKey : .masterPassword, postingKey: wif, activeKey: active,
                memoKey: nil, accessToken: "demo-access-\(user)", hiveAuthKey: nil, hiveAuthToken: nil, hiveAuthExpiry: 0
            ))
        }
        try vault.setCurrentUsername(username: "spike-alice")
    }

    /// A throwaway key (no funds, no chain account needed with fakeData) and PIN 1234.
    private static func seedLockDemo(_ store: KeychainSecureStore) throws {
        let vault = KeyVault(store: store)
        let wif = PrivateKey.companion.fromSeed(seed: "ecency-native-spike-lock-demo-throwaway").toWif()
        try vault.save(account: VaultAccount(
            username: "spike-alice", authType: .postingKey, postingKey: wif, activeKey: nil,
            memoKey: nil, accessToken: nil, hiveAuthKey: nil, hiveAuthToken: nil, hiveAuthExpiry: 0
        ))
        try vault.setCurrentUsername(username: "spike-alice")
        try PinLock(vault: vault, iterations: PinLock.companion.DEFAULT_ITERATIONS).setPin(pin: "1234")
    }
    #endif

    // MARK: Analytics

    static var isDebugBuild: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }

    /// The analytics host of this build: the build setting, else RN's instance in Release; Debug sends nothing unless
    /// the setting names a host.
    static func analyticsHost(_ setting: String, debug: Bool) -> String {
        if debug && setting.trimmingCharacters(in: .whitespaces).isEmpty { return "" }
        return AnalyticsConfig.companion.hostOf(setting: setting)
    }

    /// RN getEcencyUserAgent.
    static func analyticsUserAgent() -> String {
        let version = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? ""
        return PlausibleAnalytics.companion.userAgent(appName: "Ecency", appVersion: version, system: "iOS",
                                                      systemVersion: UIDevice.current.systemVersion, model: UIDevice.current.model)
    }

    // MARK: Navigation

    func open(_ destination: Destination) {
        if let screen = destination.screen { push(screen) }
    }

    /// The zoom source each pushed screen opened from (Motion.swift `heroDestination`), by stack item id. Not
    /// published: a destination reads it once as it is built.
    private(set) var heroSources: [Int64: String] = [:]

    /// Pushes [screen] and, when it lands on the stack, records [hero] as the view it zooms out of (iOS 18).
    func push(_ screen: AppScreen, hero: String?) {
        let before = nav.stack.last?.id
        push(screen)
        if let hero, let top = nav.stack.last, top.id != before { heroSources[top.id] = hero }
        let live = Set(nav.stack.map { $0.id })
        heroSources = heroSources.filter { live.contains($0.key) }
    }

    /// Pushes [screen]; the login screen is the sign-in sheet on iOS, and gated screens ask for it first.
    func push(_ screen: AppScreen) {
        let next = nav.pushGated(screen: screen, loggedIn: session.state.isLoggedIn)
        if next.top is AppScreenLogin {
            nav = nav.closeDrawer()
            sheet = .login
            return
        }
        // the register screen is a sheet on iOS, like login
        if let register = next.top as? AppScreenRegister {
            nav = nav.closeDrawer()
            sheet = .register(register.referral)
            return
        }
        // the keys screen is a sheet on iOS (it asks for the PIN first); never part of the saved navigation
        if next.top is AppScreenBackupKeys {
            nav = nav.closeDrawer()
            sheet = .backupKeys
            return
        }
        nav = next
    }

    /// The navigation stack's path; a swipe back or the back button sets a shorter one.
    var path: [NavItem] {
        get { nav.stack }
        set { nav = nav.popTo(size: Int32(newValue.count)) }
    }

    func selectTab(_ tab: AppTab) { nav = nav.selectTab(tab: tab) }
    func openDrawer() { nav = nav.openDrawer() }
    func closeDrawer() { nav = nav.closeDrawer() }
    func open(entry: NavEntry, loggedIn: Bool? = nil) {
        let next = nav.open(entry: entry, loggedIn: loggedIn ?? session.state.isLoggedIn)
        if next.top is AppScreenLogin {
            nav = next.popTo(size: Int32(next.stack.count - 1))
            sheet = .login
        } else {
            nav = next
        }
        // a New wave shortcut asks for its composer only now (README "Widgets")
        env.entryOpened()
    }
    func resetNavigation() { nav = nav.reset() }

    /// Restoration: the saved navigation (tab and screens), applied when the container is built (once per process).
    func restore(_ saved: String) {
        guard !saved.isEmpty, options.openPost == nil, nav.atRoot, nav.tab == .feed else { return }
        nav = NavState.companion.decodeJoined(saved: saved)
        restoredNavigation = true
    }

    /// Whether the navigation came from a saved one, and whether its owner was compared with the signed-in account yet.
    private var restoredNavigation = false
    private var ownerChecked = false

    var savedNavigation: String { NavState.companion.encodeJoined(state: nav) }

    /// A side menu tap.
    func menu(_ item: SideMenuItem, hasOtherAccounts: Bool) {
        switch SideMenu.shared.action(item: item, username: session.state.username, hasOtherAccounts: hasOtherAccounts) {
        case let open as MenuActionOpen: push(open.screen)
        case let browser as MenuActionBrowser:
            closeDrawer()
            if let url = NavDecision.webURL(browser.url) { sheet = .browser(url) }
        case is MenuActionConfirmLogout: confirmLogout = true
        case is MenuActionAccountsSheet:
            closeDrawer()
            sheet = .accounts
        case let dm as MenuActionDirectMessage:
            // web "Ask Esie": the conversation opens once the chat answers (or its toast shows)
            closeDrawer()
            openDirectMessage(dm.username)
        default: break
        }
    }

    @Published var confirmLogout = false

    func handle(_ target: LinkTarget) { apply(NavDecision.from(target)) }

    /// An external link in a post body (RN postInteractionHandler): the link sheet asks first.
    @Published var linkSheet: URL?

    /// Links tapped in post, comment and wave bodies: an external page opens the link sheet, the rest as `handle`.
    func handleBodyLink(_ target: LinkTarget) {
        let decision = NavDecision.from(target)
        if case let .browser(url) = decision { linkSheet = url } else { apply(decision) }
    }

    func apply(_ decision: NavDecision) {
        switch decision {
        case let .push(destination): open(destination)
        case let .browser(url): sheet = .browser(url)
        case let .video(url): sheet = .video(url)
        // RN postInteractionHandler: hive:// and ecency:// go to the link processor (a sign link asks first)
        case let .appLink(uri):
            if !env.authLinks.handleFromContent(url: uri) { alert = AppAlert(title: LinkCopy.shared.CANNOT_OPEN, message: LinkCopy.shared.UNSUPPORTED_VERSION) }
        case let .unsupported(message): alert = AppAlert(title: LinkCopy.shared.CANNOT_OPEN, message: message)
        case .nothing: break
        }
    }

    /// Opens a post from a card tap (marks the perf event first).
    func openPost(_ key: PostKey, hero: String? = nil) {
        _ = PerfMarks.shared.mark(event: PerfEvent.shared.POST_OPEN_TAP)
        if let screen = Destination.post(author: key.author, permlink: key.permlink).screen { push(screen, hero: hero) }
    }

    /// What a sign-in prompt asks the user to sign in for: one whole message per action (README "Localization").
    enum SignInAction {
        case vote, reply

        var message: String {
            switch self {
            case .vote: return L.Native.Ios.SignIn.toVote
            case .reply: return L.Native.Ios.SignIn.toReply
            }
        }
    }

    /// True when signed in; otherwise shows the sign-in prompt for `action`.
    func requireSignIn(to action: SignInAction) -> Bool {
        if session.state.isLoggedIn { return true }
        alert = AppAlert(
            title: L.Native.Ios.SignIn.required,
            message: action.message,
            offerSignIn: true
        )
        return false
    }

    func signOut() {
        closeDrawer()
        guard let name = session.state.username else { return }
        logout(name)
    }
}

struct AppStartError: Error {
    let message: String
}

/// User-facing text for errors thrown across the Kotlin bridge.
enum ErrorText {
    static func appError(_ error: Error) -> AppError? {
        let kotlin = (error as NSError).userInfo["KotlinException"]
        if let app = kotlin as? AppException { return app.error }
        if let throwable = kotlin as? KotlinThrowable { return AppError.companion.from(error: throwable) }
        return nil
    }

    static func message(_ error: Error) -> String {
        appError(error)?.message ?? error.localizedDescription
    }
}

/// How this build was installed, as RN's `DeviceInfo.getInstallerPackageName` names it on iOS.
enum InstallSource {
    static var current: String {
        #if targetEnvironment(simulator)
        return "Other"
        #else
        if Bundle.main.appStoreReceiptURL?.lastPathComponent == "sandboxReceipt" { return "TestFlight" }
        if Bundle.main.path(forResource: "embedded", ofType: "mobileprovision") != nil { return "Other" }
        return "AppStore"
        #endif
    }
}
