import EcencyCore
import XCTest
@testable import EcencyApp

/// The iOS side of the shared navigation: AppContainer over NavState (path binding, gated screens, the login
/// sheet, side menu actions, restoration, theme).
@MainActor
final class ShellTests: XCTestCase {
    private var app: AppContainer!

    override func setUp() async throws {
        let store = KeychainSecureStore(service: "app.esteem.mobile.ios.tests.shell")
        try store.wipe()
        UserDefaults.standard.removeObject(forKey: "themeMode")
        UserDefaults.standard.removeObject(forKey: "prefs.settings.theme")
        AppContainer.forgetSavedNavigation()
        let env = AppEnvironment(
            api: try EcencyApi(nodes: ["https://fake-hive.invalid"], transport: FakeHiveTransport()),
            vault: KeyVaultAsync(vault: KeyVault(store: store)),
            renderer: PostBodyRendererCompanion.shared.`default`(proxyBase: ImageProxy.companion.DEFAULT_BASE),
            dispatchers: AppDispatchers.companion.platform(),
            proxyBase: ImageProxy.companion.DEFAULT_BASE,
            debugKeyImport: true,
            nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
            security: SecurityConfig(pinIterations: 16, lockTimeoutMillis: 60_000, monotonicMillis: { KotlinLong(value: 0) }),
            legacy: nil,
            auth: AuthServices.companion.offline()
        )
        self.env = env
        self.store = store
        app = AppContainer(env: env, options: LaunchOptions())
    }

    private var env: AppEnvironment!
    private var store: KeychainSecureStore!

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: "themeMode")
        UserDefaults.standard.removeObject(forKey: "prefs.settings.theme")
        AppContainer.forgetSavedNavigation()
    }

    /// Waits on the main run loop until `condition` holds.
    private func waitUntil(_ timeout: TimeInterval = 10, _ condition: () -> Bool) {
        let end = Date().addingTimeInterval(timeout)
        while !condition() && Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.05)) }
    }

    func testDestinationsBecomeSharedScreens() {
        XCTAssertEqual(Destination.post(author: "a", permlink: "b").screen as? AppScreenPost, AppScreenPost(author: "a", permlink: "b"))
        XCTAssertEqual(Destination.profile(author: "a").screen as? AppScreenProfile, AppScreenProfile(author: "a"))
        // a community feed is the community page, a tag feed the tag screen
        let community = Destination.feed(tag: "hive-1", sort: "hot", title: "C").screen as? AppScreenCommunity
        XCTAssertEqual(community, AppScreenCommunity(name: "hive-1", filter: "hot"))
        XCTAssertEqual(Destination.feed(tag: "travel", sort: "created", title: "#travel").screen as? AppScreenTagResult, AppScreenTagResult(tag: "travel", filter: "created"))
        XCTAssertNil(Destination.rendererDemo.screen)
    }

    /// RN #2627: a cold start link under the welcome screen opens once the terms are accepted.
    func testALinkUnderTheWelcomeScreenWaitsForTheTerms() {
        app.termsAccepted = false
        app.handle(url: URL(string: "https://ecency.com/@alice/a-post")!)
        XCTAssertNotNil(app.linkAfterTerms)
        XCTAssertNil(app.nav.top, "nothing opens behind the welcome screen")
        XCTAssertNil(app.sheet)
        app.acceptTerms()
        XCTAssertNil(app.linkAfterTerms)
        waitUntil { app.nav.top is AppScreenPost }
        XCTAssertEqual(app.nav.top as? AppScreenPost, AppScreenPost(author: "alice", permlink: "a-post"))
    }

    /// The same for a gated screen, whose login sheet could not show over the welcome screen.
    func testAGatedLinkUnderTheWelcomeScreenAsksToLogInAfterTheTerms() {
        app.termsAccepted = false
        // a transfer naming only the recipient needs a signed-in account: logged out it asks to log in
        app.handle(url: URL(string: "ecency://transfer?to=alice")!)
        XCTAssertNil(app.sheet)
        XCTAssertNil(app.env.authLinks.current.prompt, "not even the link model saw it yet")
        app.acceptTerms()
        let isLogin = { [unowned self] () -> Bool in if case .login = self.app.sheet { return true } else { return false } }
        waitUntil { isLogin() }
        XCTAssertTrue(isLogin(), "the login sheet, once the terms are accepted")
    }

    func testPushPopAndThePathBinding() {
        app.open(.post(author: "a", permlink: "b"))
        app.open(.profile(author: "a"))
        XCTAssertEqual(app.path.count, 2)
        XCTAssertEqual(app.nav.tab, .feed)
        app.path = Array(app.path.prefix(1)) // a swipe back
        XCTAssertEqual(app.nav.screens.count, 1)
        XCTAssertTrue(app.nav.top is AppScreenPost)
        app.path = []
        XCTAssertTrue(app.nav.atRoot)
    }

    func testLoginIsTheSheetAndGatedScreensAskForIt() {
        app.openDrawer()
        app.push(AppScreenCompanion.shared.of(route: .editor, arg: ""))
        XCTAssertEqual(app.sheet?.id, "login", "signed out: the editor asks for sign-in")
        XCTAssertTrue(app.nav.atRoot, "the login screen is a sheet on iOS, not a pushed screen")
        XCTAssertFalse(app.nav.drawerOpen)
        app.sheet = nil
        app.push(AppScreenCompanion.shared.of(route: .voters, arg: ""))
        XCTAssertTrue(app.nav.top is AppScreenPending, "public placeholders open signed out")
    }

    func testTabsAndTheDrawer() {
        app.selectTab(.wallet)
        XCTAssertEqual(app.nav.tab, .wallet)
        app.openDrawer()
        XCTAssertTrue(app.nav.drawerOpen)
        app.closeDrawer()
        XCTAssertFalse(app.nav.drawerOpen)
        app.open(.post(author: "a", permlink: "b"))
        app.openDrawer()
        XCTAssertFalse(app.nav.drawerOpen, "the side menu belongs to the tabs")
    }

    /// The footer with "Ask Esie" (six rows of about 50 pt plus the version line, about 350 pt) stays pinned while the
    /// main group keeps three rows; on a shorter drawer (an SE at large text) the whole menu scrolls instead.
    func testSideMenuFooterPinsOnlyWithRoomForTheMainGroup() {
        XCTAssertEqual(SideMenu.shared.items(loggedIn: true, group: .footer).count, 6)
        let footer: CGFloat = 350
        XCTAssertTrue(MenuGroupsLayout.pins(available: footer + 16 + MenuGroupsLayout.minimumMainHeight, footer: footer))
        XCTAssertFalse(MenuGroupsLayout.pins(available: footer + 16 + MenuGroupsLayout.minimumMainHeight - 1, footer: footer))
        XCTAssertFalse(MenuGroupsLayout.pins(available: 300, footer: footer))
    }

    func testSideMenuActions() {
        app.openDrawer()
        app.menu(.docs, hasOtherAccounts: false)
        XCTAssertEqual(app.sheet?.id, "browser:\(SideMenu.shared.DOCS_URL)")
        XCTAssertFalse(app.nav.drawerOpen)
        app.sheet = nil
        app.menu(.addAccount, hasOtherAccounts: false)
        XCTAssertEqual(app.sheet?.id, "login")
        app.sheet = nil
        app.menu(.addAccount, hasOtherAccounts: true)
        XCTAssertEqual(app.sheet?.id, "accounts")
        app.sheet = nil
        app.menu(.logout, hasOtherAccounts: false)
        XCTAssertTrue(app.confirmLogout)
        app.menu(.settings, hasOtherAccounts: false)
        XCTAssertTrue(app.nav.top is AppScreenSettings)
        app.path = []
        app.menu(.qr, hasOtherAccounts: false)
        XCTAssertTrue(app.nav.top is AppScreenQrScan, "the scanner (platform wave)")
    }

    func testTheContainerSavesAndTheNextContainerRestores() {
        app.selectTab(.notification)
        app.open(.post(author: "a", permlink: "b"))
        let next = AppContainer(env: env, options: LaunchOptions())
        XCTAssertEqual(next.nav.tab, .notification, "saved by the container, not by a view")
        XCTAssertTrue(next.nav.top is AppScreenPost)
        next.restore("garbage")
        XCTAssertTrue(next.nav.top is AppScreenPost, "only an untouched root restores")
    }

    /// A saved editor or drafts screen comes back only for the account that had it open.
    func testARestoredGatedScreenIsOnlyForItsOwner() throws {
        let vault = KeyVault(store: store)
        for name in ["spike-shell", "spike-other"] {
            try vault.save(account: VaultAccount(
                username: name, authType: .postingKey, postingKey: "5Jdummy", activeKey: nil,
                memoKey: nil, accessToken: nil, hiveAuthKey: nil, hiveAuthToken: nil, hiveAuthExpiry: 0
            ))
        }
        try vault.setCurrentUsername(username: "spike-shell")
        app = AppContainer(env: env, options: LaunchOptions())
        waitUntil { app.session.state.isLoggedIn }
        app.open(.post(author: "a", permlink: "b"))
        app.push(AppScreenDrafts(tab: 1))
        app.push(AppScreenEditor.companion.of(arg: "draft:d1"))
        XCTAssertEqual(UserDefaults.standard.string(forKey: AppContainer.navigationKey + ".owner"), "spike-shell")

        let same = AppContainer(env: env, options: LaunchOptions())
        waitUntil { same.session.state.isLoggedIn }
        XCTAssertTrue(same.nav.top is AppScreenEditor, "the same account gets its editor back")
        XCTAssertEqual(same.nav.screens.count, 3)

        // each container starts the shared session again, which reads the vault once more: that load is awaited
        // before the vault changes, or it would land as "spike-other" in the first environment and rewrite the owner
        let loaded = expectation(description: "the session load finished")
        env.session.awaitStartFinished { _ in loaded.fulfill() }
        wait(for: [loaded], timeout: 10)
        // the next launch finds another account signed in
        try vault.setCurrentUsername(username: "spike-other")
        let otherEnv = AppEnvironment(
            api: try EcencyApi(nodes: ["https://fake-hive.invalid"], transport: FakeHiveTransport()),
            vault: KeyVaultAsync(vault: vault),
            renderer: PostBodyRendererCompanion.shared.`default`(proxyBase: ImageProxy.companion.DEFAULT_BASE),
            dispatchers: AppDispatchers.companion.platform(),
            proxyBase: ImageProxy.companion.DEFAULT_BASE,
            debugKeyImport: true,
            nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
            security: SecurityConfig(pinIterations: 16, lockTimeoutMillis: 60_000, monotonicMillis: { KotlinLong(value: 0) }),
            legacy: nil,
            auth: AuthServices.companion.offline()
        )
        let other = AppContainer(env: otherEnv, options: LaunchOptions())
        waitUntil { other.session.state.username == "spike-other" && other.nav.screens.count == 1 }
        XCTAssertEqual(other.session.state.username, "spike-other")
        XCTAssertEqual(other.nav.screens.count, 1, "the other account sees the public post only")
        XCTAssertTrue(other.nav.top is AppScreenPost)
    }

    /// A wipe from the lock screen while RootView is not in the hierarchy: the next launch must not bring the
    /// wiped account's screens back.
    func testAWipeWhileLockedClearsTheSavedNavigation() throws {
        let vault = KeyVault(store: store)
        try vault.save(account: VaultAccount(
            username: "spike-shell", authType: .postingKey, postingKey: "5Jdummy", activeKey: nil,
            memoKey: nil, accessToken: nil, hiveAuthKey: nil, hiveAuthToken: nil, hiveAuthExpiry: 0
        ))
        try vault.setCurrentUsername(username: "spike-shell")
        try PinLock(vault: vault, iterations: 16).setPin(pin: "1234")
        app = AppContainer(env: env, options: LaunchOptions())
        waitUntil { app.session.state.isLoggedIn && app.lock.state.isLocked }
        XCTAssertTrue(app.lock.state.isLocked)
        app.selectTab(.wallet)
        app.open(.post(author: "a", permlink: "b"))
        XCTAssertNotNil(UserDefaults.standard.string(forKey: AppContainer.navigationKey))
        env.appLock.forgotPin() // the lock screen's "forgot PIN": wipes every account
        waitUntil { app.nav.atRoot && app.nav.tab == .feed }
        XCTAssertTrue(app.nav.atRoot)
        let next = AppContainer(env: env, options: LaunchOptions())
        XCTAssertTrue(next.nav.atRoot, "nothing of the wiped account comes back")
        XCTAssertEqual(next.nav.tab, .feed)
    }

    func testSignOutKeepsTheTabAndDropsGatedScreens() throws {
        let vault = KeyVault(store: store)
        try vault.save(account: VaultAccount(
            username: "spike-shell", authType: .postingKey, postingKey: "5Jdummy", activeKey: nil,
            memoKey: nil, accessToken: nil, hiveAuthKey: nil, hiveAuthToken: nil, hiveAuthExpiry: 0
        ))
        try vault.setCurrentUsername(username: "spike-shell")
        app = AppContainer(env: env, options: LaunchOptions())
        waitUntil { app.session.state.isLoggedIn }
        app.selectTab(.wallet)
        app.open(.post(author: "a", permlink: "b"))
        app.push(AppScreenCompanion.shared.of(route: .transfer, arg: ""))
        XCTAssertEqual(app.nav.stack.count, 2)
        app.signOut()
        waitUntil { app.nav.stack.count == 1 }
        XCTAssertEqual(app.nav.tab, .wallet)
        XCTAssertTrue(app.nav.top is AppScreenPost)
    }

    func testGatedDeepLinksAskForSignIn() {
        app.open(entry: NavEntry(tab: .wallet, screens: [AppScreenCompanion.shared.of(route: .transfer, arg: "")]))
        XCTAssertEqual(app.nav.tab, .wallet)
        XCTAssertTrue(app.nav.atRoot)
        XCTAssertEqual(app.sheet?.id, "login")
    }

    func testHomeFeedsOutliveTheFeedTab() {
        let feed = app.homeFeeds.feed(.trending, username: nil, env: env)
        app.selectTab(.wallet)
        app.selectTab(.feed)
        XCTAssertTrue(app.homeFeeds.feed(.trending, username: nil, env: env) === feed)
        // another account gets its own feed; an account-only filter has none while signed out
        XCTAssertFalse(app.homeFeeds.feed(.trending, username: "spike-alice", env: env) === feed)
        XCTAssertNil(app.homeFeeds.feed(.friends, username: nil, env: env))
    }

    func testThemeChoiceIsSaved() {
        XCTAssertEqual(app.themeMode, .system)
        app.env.settings.setTheme(mode: .dark)
        // kept by the shared settings, under RN's choice
        XCTAssertEqual(app.env.prefs.getString(key: SettingsModel.companion.KEY_THEME), "DARK")
        let end = Date().addingTimeInterval(2)
        while app.themeMode != .dark && Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.02)) }
        XCTAssertEqual(app.themeMode, .dark, "the window follows")
    }

    func testToastsQueueThroughTheSharedChannel() {
        let id = app.env.ui.toast(text: "Copied")
        XCTAssertEqual(app.env.ui.current.toast?.text, "Copied")
        app.env.ui.dismiss(id: id)
        XCTAssertNil(app.env.ui.current.toast)
    }

    func testContentLinksOpenInTheNavigationAndOthersAreIgnored() {
        app.selectTab(.wallet)
        app.handle(url: URL(string: "https://ecency.com/@alice/hello")!)
        waitUntil { !app.nav.atRoot } // after the accounts were read
        XCTAssertEqual(app.nav.tab, .wallet, "on top of the open tab")
        XCTAssertEqual((app.nav.top as? AppScreenPost)?.permlink, "hello")
        app.handle(url: URL(string: "https://evil.example/@alice/x")!)
        XCTAssertEqual(app.nav.stack.count, 1)
    }

    func testAnUnclaimedEcencyPageOpensInTheBrowser() {
        app.handle(url: URL(string: "https://ecency.com/faq")!)
        guard case let .browser(url)? = app.sheet else { return XCTFail("the in-app browser opens") }
        XCTAssertEqual(url.absoluteString, "https://ecency.com/faq")
        app.sheet = nil
        app.handle(url: URL(string: "https://evil.example/faq")!)
        XCTAssertNil(app.sheet, "other hosts open nothing")
    }

    func testALinkWhileLockedOpensBehindTheLock() throws {
        let vault = KeyVault(store: store)
        try vault.save(account: VaultAccount(
            username: "spike-shell", authType: .postingKey, postingKey: "5Jdummy", activeKey: nil,
            memoKey: nil, accessToken: nil, hiveAuthKey: nil, hiveAuthToken: nil, hiveAuthExpiry: 0
        ))
        try vault.setCurrentUsername(username: "spike-shell")
        try PinLock(vault: vault, iterations: 16).setPin(pin: "1234")
        app = AppContainer(env: env, options: LaunchOptions())
        waitUntil { app.lock.state.isLocked }
        app.handle(url: URL(string: "https://ecency.com/@alice/hello")!)
        XCTAssertTrue(app.nav.top is AppScreenPost)
        XCTAssertTrue(app.lock.state.hidesContent, "the screen waits behind the lock (LockGate shows nothing of it)")
    }

    func testOnlyARecentNavigationIsRestored() {
        app.open(.post(author: "a", permlink: "b"))
        XCTAssertFalse(AppContainer.savedRecently().isEmpty)
        XCTAssertTrue(AppContainer.savedRecently(now: Date().addingTimeInterval(AppContainer.restoreWindow + 60)).isEmpty,
                      "an old navigation is not restored")
    }

    private func storeAccounts(_ names: [String]) throws {
        let vault = KeyVault(store: store)
        for name in names {
            try vault.save(account: VaultAccount(
                username: name, authType: .postingKey, postingKey: "5Jdummy", activeKey: nil,
                memoKey: nil, accessToken: nil, hiveAuthKey: nil, hiveAuthToken: nil, hiveAuthExpiry: 0
            ))
        }
        try vault.setCurrentUsername(username: names[0])
    }

    /// A link the system hands over at a cold start, before the accounts were read: it waits, then opens with
    /// the real session (signed in here, so the gated screen, not the login sheet).
    func testAColdStartLinkWaitsForTheAccounts() throws {
        try storeAccounts(["spike-shell"])
        app = AppContainer(env: env, options: LaunchOptions())
        XCTAssertTrue(app.session.state.isLoading, "the session is still reading the vault")
        let transfer = AppScreenCompanion.shared.of(route: .transfer, arg: "")
        app.openAfterAccountsLoaded(NavEntry(tab: .wallet, screens: [transfer]))
        XCTAssertTrue(app.nav.atRoot, "nothing opens before the accounts are known")
        waitUntil { !app.nav.atRoot }
        XCTAssertTrue(app.nav.top is AppScreenTransfer, "the wallet wave built the transfer screen")
        XCTAssertEqual(app.nav.tab, .wallet)
        XCTAssertNil(app.sheet)
    }

    func testSwitchingAccountsDropsScreensOfThePreviousOne() throws {
        try storeAccounts(["spike-shell", "spike-other"])
        app = AppContainer(env: env, options: LaunchOptions())
        waitUntil { app.session.state.accounts.count == 2 }
        app.open(.post(author: "a", permlink: "b"))
        app.push(AppScreenCompanion.shared.of(route: .transfer, arg: ""))
        XCTAssertEqual(app.nav.stack.count, 2)
        let env = self.env!
        Task { try? await env.session.switchTo(username: "spike-other") }
        waitUntil { app.session.state.username == "spike-other" && app.nav.stack.count == 1 }
        XCTAssertTrue(app.nav.top is AppScreenPost)
    }
}
