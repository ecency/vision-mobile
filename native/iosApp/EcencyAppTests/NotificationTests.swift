import EcencyCore
import XCTest
@testable import EcencyApp

/// The iOS side of notifications and push: the container over the shared models (badge, list, row taps, a tapped
/// push behind the lock, the in-app banner) and the system bridge (what is read from a notification, Firebase
/// absent from this build).
@MainActor
final class NotificationTests: XCTestCase {
    private var env: AppEnvironment!
    private var app: AppContainer!
    private var store: KeychainSecureStore!

    private func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// A container over the offline demo services (synthetic notifications, nothing on the network).
    private func make(pin: String? = nil, signedIn: Bool = true) throws {
        store = KeychainSecureStore(service: "app.esteem.mobile.ios.tests.notifications")
        try store.wipe()
        AppContainer.forgetSavedNavigation()
        let vault = KeyVault(store: store)
        if signedIn {
            let wif = PrivateKey.companion.fromLogin(username: "spike-alice", password: FakeHiveTransport.password, role: .posting).toWif()
            try vault.save(account: VaultAccount(
                username: "spike-alice", authType: .postingKey, postingKey: wif, activeKey: nil,
                memoKey: nil, accessToken: "demo-access-spike-alice", hiveAuthKey: nil, hiveAuthToken: nil, hiveAuthExpiry: 0
            ))
            try vault.setCurrentUsername(username: "spike-alice")
        }
        if let pin { try PinLock(vault: vault, iterations: 16).setPin(pin: pin) }
        let demo = DemoAuthServices.shared.create(nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) }, waitMillis: 300_000)
        env = AppEnvironment(
            api: try EcencyApi(nodes: ["https://fake-hive.invalid"], transport: FakeHiveTransport()),
            vault: KeyVaultAsync(vault: vault),
            renderer: PostBodyRendererCompanion.shared.`default`(proxyBase: ImageProxy.companion.DEFAULT_BASE),
            dispatchers: AppDispatchers.companion.platform(),
            proxyBase: ImageProxy.companion.DEFAULT_BASE,
            debugKeyImport: true,
            nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
            security: SecurityConfig(pinIterations: 16, lockTimeoutMillis: 60_000, monotonicMillis: { KotlinLong(value: 0) }),
            legacy: nil,
            auth: demo
        )
        var options = LaunchOptions()
        options.fakeData = true
        options.keepFakeStore = true
        app = AppContainer(env: env, options: options)
    }

    override func tearDown() {
        AppContainer.forgetSavedNavigation()
        app = nil
        env = nil
    }

    private func waitUntil(_ timeout: TimeInterval = 10, _ condition: () -> Bool) {
        let end = Date().addingTimeInterval(timeout)
        while !condition() && Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.05)) }
    }

    func testTheBadgeAndTheListComeFromTheSharedModel() throws {
        try make()
        waitUntil { self.app.badges.state.notifications == 3 }
        XCTAssertEqual(app.badges.state.text(kind: .notification), "3")
        XCTAssertTrue(app.notifications.state.items.isEmpty, "nothing is listed before the tab is shown")

        env.notifications.show()
        waitUntil { self.app.notifications.state.items.count == 10 }
        let state = app.notifications.state
        XCTAssertEqual(state.account, "spike-alice")
        let first = state.items[0]
        XCTAssertEqual(first.source, "spike-bob")
        XCTAssertEqual(first.text, "voted 100%")
        XCTAssertEqual(first.moreInfo, "A synthetic post")
        XCTAssertFalse(first.read)
        XCTAssertEqual(state.items.first { $0.type == "delegations" }?.text, "delegated 5B RC")
        XCTAssertEqual(state.items.first { $0.type == "weekly_earnings" }?.text, "You earned $4.20 this week - $3.00 from posts, $1.20 from curation.")
    }

    func testARowTapMarksItReadAndOpensItsTarget() throws {
        try make()
        env.notifications.show()
        waitUntil { self.app.notifications.state.items.count == 10 }
        let entry = env.notifications.open(id: "n-1")
        XCTAssertNotNil(entry)
        app.open(entry: entry!)
        XCTAssertEqual(app.nav.top as? AppScreenPost, AppScreenPost(author: "spike-alice", permlink: "fake-post-1"))
        XCTAssertTrue(env.notifications.current.items[0].read, "read at once")
        waitUntil { self.app.badges.state.notifications == 0 } // the demo server answers 0 unread

        // a transfer goes to the wallet tab
        app.resetNavigation()
        app.open(entry: env.notifications.open(id: "n-5")!)
        XCTAssertEqual(app.nav.tab, .wallet)
        XCTAssertNil(env.notifications.open(id: "n-10"), "an account update opens nothing")
    }

    func testASwipeMarksReadWithoutNavigatingAndTheQuickChipsToggle() throws {
        try make()
        env.notifications.show()
        waitUntil { self.app.notifications.state.items.count == 10 }
        XCTAssertTrue(env.notifications.markRead(id: "n-1"))
        XCTAssertNil(app.nav.top, "a swipe opens nothing")
        XCTAssertFalse(env.notifications.markRead(id: "n-1"), "already read")
        XCTAssertEqual(NotificationFilter.companion.quick, [.replies, .mentions])
        env.notifications.toggleQuickFilter(filter: .replies)
        XCTAssertEqual(env.notifications.current.filter, .replies)
        env.notifications.toggleQuickFilter(filter: .replies)
        XCTAssertEqual(env.notifications.current.filter, .activities, "the selected chip again is All")
    }

    func testATappedPushWaitsBehindThePinThenOpens() throws {
        try make(pin: "1234")
        waitUntil { self.app.lock.state.isLocked }
        PushBridge.opened(["type": "reply", "source": "spike-bob", "target": "spike-alice", "permlink1": "fake-post-2", "id": "r-1",
                           "aps": ["alert": "ignored"]])
        waitUntil(1) { !self.app.nav.atRoot }
        XCTAssertTrue(app.nav.atRoot, "nothing is pushed while locked")
        XCTAssertNil(app.push.state.open)
        XCTAssertNil(app.ui.state.banner)

        for digit in [1, 2, 3, 4] { env.appLock.enterDigit(digit: Int32(digit)) }
        waitUntil { !self.app.nav.atRoot }
        XCTAssertEqual(app.nav.top as? AppScreenPost, AppScreenPost(author: "spike-bob", permlink: "fake-post-2"))
        waitUntil { self.app.push.state.open == nil }
        XCTAssertNil(app.push.state.open, "consumed: it opens once")
    }

    func testAHostileTappedPushOpensNothing() throws {
        try make()
        waitUntil { !self.app.session.state.isLoading }
        PushBridge.opened(["type": "reply", "source": "https://evil.example", "permlink1": "x", "url": "https://evil.example"])
        PushBridge.opened(["type": "open", "screen": "BackupKeysScreen"])
        PushBridge.opened(["type": "hiveuri", "hiveUri": "https://evil.example/sign"])
        waitUntil(1) { !self.app.nav.atRoot }
        XCTAssertTrue(app.nav.atRoot)
        XCTAssertNil(app.sheet)
        XCTAssertEqual(app.nav.tab, .feed)
    }

    func testOnlyTheEcencyFieldsAreReadFromANotification() {
        XCTAssertNil(PushBridge.data([:]))
        XCTAssertNil(PushBridge.data(["source": "spike-bob"]), "no type")
        XCTAssertNil(PushBridge.data(["type": 5]), "not a string")
        let data = PushBridge.data(["type": "reply", "source": "spike-bob", "permlink1": "a", "permlink2": "b", "id": "r-1", "amount": 5,
                                    "url": "https://evil.example", "screen": "settings", "aps": ["alert": "x"], "gcm.message_id": "1"])
        XCTAssertEqual(data, ["type": "reply", "source": "spike-bob", "permlink1": "a", "permlink2": "b", "id": "r-1"])
    }

    func testAForegroundPushShowsTheBannerAndItsTapOpensThePost() throws {
        try make()
        waitUntil { self.app.badges.state.notifications == 3 }
        PushBridge.received(["type": "reply", "source": "spike-bob", "target": "spike-alice", "permlink1": "fake-post-2", "id": "r-2"], title: "x", body: "y")
        waitUntil { self.app.ui.state.banner != nil }
        let banner = try XCTUnwrap(app.ui.state.banner)
        XCTAssertEqual(banner.title, "New reply on @spike-alice")
        XCTAssertEqual(banner.body, "Tap to continue conversation...")
        XCTAssertEqual(banner.avatarUser, "spike-bob")
        app.open(entry: try XCTUnwrap(banner.entry))
        XCTAssertEqual(app.nav.top as? AppScreenPost, AppScreenPost(author: "spike-bob", permlink: "fake-post-2"))
    }

    func testSignedOutNothingIsLoadedAndTheLeaderboardWorks() throws {
        try make(signedIn: false)
        waitUntil { !self.app.session.state.isLoading }
        env.notifications.show()
        waitUntil(1) { self.app.notifications.state.loaded }
        XCTAssertFalse(app.notifications.state.loaded)
        XCTAssertEqual(app.badges.state.notifications, 0)
        let board = Observed(env.leaderboardModel(), ownsModel: true)
        (board.model as! LeaderboardModel).start()
        waitUntil { board.state.users.count == 3 }
        XCTAssertEqual(board.state.users.first?.username, "spike-alice")
        XCTAssertEqual(board.state.users.first?.questsDone, true)
        // 2026-10-29T18:48:00Z: the daily board says when it resets, the others do not
        let now: Int64 = 1_793_299_680_000
        XCTAssertEqual(board.state.resetLine(nowMillis: now), "Today (UTC), resets in 5h 12m")
        (board.model as! LeaderboardModel).select(duration: .week)
        waitUntil { board.state.duration == .week }
        XCTAssertNil(board.state.resetLine(nowMillis: now))
    }

    func testWithoutFirebasePushIsInactive() throws {
        let platform = IOSPushPlatform()
        XCTAssertFalse(platform.isConfigured, "no GoogleService-Info.plist in this build, and the simulator has no APNs")
        XCTAssertEqual(platform.system, "fcm-ios")
        var token: String? = "unset"
        platform.token { token = $0 }
        XCTAssertNil(token)
        var deleted = true
        platform.deleteToken { deleted = $0.boolValue }
        XCTAssertFalse(deleted)

        try make()
        env.pushPlatform = platform
        env.push.onForeground()
        waitUntil { self.app.push.state.available?.boolValue == false }
        XCTAssertNotNil(app.push.state.unavailableNotice)
    }

    func testPushSwitchesAreKeptOnTheDevice() throws {
        try make()
        env.push.setType(type: .vote, on: false)
        XCTAssertFalse(env.push.current.settings.isOn(type: .vote))
        XCTAssertTrue(env.push.current.settings.isOn(type: .follow))
        env.push.setEnabled(enabled: false)
        XCTAssertFalse(env.push.current.settings.enabled)
        waitUntil { !self.app.push.state.settings.enabled }
        XCTAssertFalse(app.push.state.settings.isOn(type: .vote), "the observed state follows")
        XCTAssertEqual(env.prefs.getString(key: "push.enabled"), "0")
        XCTAssertEqual(env.prefs.getString(key: "push.typesOff"), "vote")
        env.push.setEnabled(enabled: true)
        env.push.setType(type: .vote, on: true)
    }

    func testShowImagesIsKeptOnTheDeviceAndTheEarnHintIsAToast() throws {
        try make()
        XCTAssertTrue(env.notifications.current.showImages)
        env.notifications.setShowImages(show: false)
        XCTAssertFalse(env.notifications.current.showImages)
        XCTAssertEqual(env.prefs.getString(key: "content.showImages"), "0")
        waitUntil { !self.app.notifications.state.showImages }
        XCTAssertFalse(app.notifications.state.showImages)
        env.notifications.setShowImages(show: true)
        XCTAssertNil(env.prefs.getString(key: "content.showImages"))

        env.leaderboardModel().showEarnHint()
        waitUntil { self.app.ui.state.toast != nil }
        XCTAssertEqual(app.ui.state.toast?.text, "Earn points by actively participating in Ecency.")
    }

    func testAPayloadWithASigningLinkOpensNoPrompt() throws {
        try make()
        waitUntil { !self.app.session.state.isLoading }
        PushBridge.opened(["type": "hiveuri", "hiveUri": "hive://sign/op/WyJ2b3RlIix7fV0.", "id": "guessed", "target": "spike-alice"])
        waitUntil(1) { self.env.authLinks.current.prompt != nil }
        XCTAssertNil(env.authLinks.current.prompt)
        XCTAssertTrue(app.nav.atRoot)
        XCTAssertNil(PushBridge.data(["type": "reply", "hiveUri": "hive://sign/op/x"])?["hiveUri"])
    }

    func testOlderNotificationsShowADate() {
        let now = Date(timeIntervalSince1970: 1_791_288_000)
        XCTAssertEqual(NotificationClock.text(1_791_288_000_000 - 5 * 60_000, now: now), "5 minutes ago")
        XCTAssertEqual(NotificationClock.text(1_791_288_000_000 - 86_400_000, now: now), "1 day ago")
        XCTAssertNil(NotificationClock.text(0, now: now))
        let old = try! XCTUnwrap(NotificationClock.text(1_791_288_000_000 - 8 * 86_400_000, now: now))
        XCTAssertFalse(old.hasSuffix("ago"))
        XCTAssertTrue(old.contains("2026"), old)
    }
}
