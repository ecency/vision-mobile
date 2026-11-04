import EcencyCore
import SwiftUI
import XCTest
@testable import EcencyApp

/// README "Foreground refresh" on iOS: the app comes back from the background (the system's will-enter-foreground,
/// the app lock's background and foreground). After a long trip the feed on screen and the wallet read again quietly,
/// a reader scrolled down keeps the card they read, one at the top sees the new cards; after a short trip nothing is read.
@MainActor
final class ForegroundRefreshTests: XCTestCase {
    /// The app lock's monotonic clock and the wall clock, moved by the tests.
    private final class Clock {
        var monotonic: Int64 = 0
        var now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    }

    /// The fake chain, counting the ranked feed reads; [extraTop] new posts go on top of the first page.
    private final class CountingTransport: NSObject, RpcTransport {
        private let inner = FakeHiveTransport()
        private let lock = NSLock()
        private var feedReads = 0
        private var top = 0
        private var muted = false
        private var template: [String: Any]?

        var reads: Int { lock.withLock { feedReads } }
        var extraTop: Int {
            get { lock.withLock { top } }
            set { lock.withLock { top = newValue } }
        }
        /// Every ranked page is 10 posts by "spammer" ("s-[read]-0..9"), page after page.
        var allBySpammer: Bool {
            get { lock.withLock { muted } }
            set { lock.withLock { muted = newValue } }
        }

        func post(url: String, body: String, completionHandler: @escaping (HttpResult?, Error?) -> Void) {
            let answer = inner.answerSync(body)
            guard body.contains("bridge.get_ranked_posts") else { return completionHandler(answer, nil) }
            let firstPage = !body.contains("\"start_permlink\":\"") || body.contains("\"start_permlink\":\"\"")
            let (extra, read, spammer) = lock.withLock { () -> (Int, Int, Bool) in feedReads += 1; return (top, feedReads, muted) }
            if spammer { return completionHandler(spammerPage(read, from: answer), nil) }
            completionHandler(firstPage && extra > 0 ? Self.prepend(extra, to: answer) : answer, nil)
        }

        /// The fake's first post as 10 posts by "spammer"; the fake's later pages are empty, so the first one is kept.
        private func spammerPage(_ read: Int, from answer: HttpResult) -> HttpResult {
            guard var json = (try? JSONSerialization.jsonObject(with: Data(answer.body.utf8))) as? [String: Any] else { return answer }
            let first = lock.withLock { () -> [String: Any]? in
                if template == nil { template = (json["result"] as? [[String: Any]])?.first }
                return template
            }
            guard let first else { return answer }
            json["result"] = (0..<10).map { i -> [String: Any] in
                var post = first
                post["author"] = "spammer"
                post["permlink"] = "s-\(read)-\(i)"
                post["url"] = "/hive-100001/@spammer/s-\(read)-\(i)"
                return post
            }
            guard let data = try? JSONSerialization.data(withJSONObject: json), let body = String(data: data, encoding: .utf8) else { return answer }
            return HttpResult(status: answer.status, body: body)
        }

        /// [count] copies of the page's first post, as newer posts ("fresh-0"...), on top of the page.
        private static func prepend(_ count: Int, to answer: HttpResult) -> HttpResult {
            guard var json = (try? JSONSerialization.jsonObject(with: Data(answer.body.utf8))) as? [String: Any],
                  var posts = json["result"] as? [[String: Any]], let first = posts.first else { return answer }
            let fresh = (0..<count).map { i -> [String: Any] in
                var post = first
                post["permlink"] = "fresh-\(i)"
                post["url"] = "/hive-100001/@\(first["author"] ?? "")/fresh-\(i)"
                return post
            }
            posts.insert(contentsOf: fresh, at: 0)
            json["result"] = posts
            guard let data = try? JSONSerialization.data(withJSONObject: json), let body = String(data: data, encoding: .utf8) else { return answer }
            return HttpResult(status: answer.status, body: body)
        }
    }

    private let clock = Clock()
    private let transport = CountingTransport()
    private var env: AppEnvironment!
    private var app: AppContainer!
    private var window: UIWindow?
    private var watchers: [Closeable] = []
    private let long = Freshness.companion.STALE_AFTER_MILLIS

    private func make(signedIn: Bool = false) throws {
        let store = KeychainSecureStore(service: "app.esteem.mobile.ios.tests.foreground")
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
        let clock = self.clock
        env = AppEnvironment(
            api: try EcencyApi(nodes: ["https://fake-hive.invalid"], transport: transport),
            vault: KeyVaultAsync(vault: vault),
            renderer: PostBodyRendererCompanion.shared.`default`(proxyBase: ImageProxy.companion.DEFAULT_BASE),
            dispatchers: AppDispatchers.companion.platform(),
            proxyBase: ImageProxy.companion.DEFAULT_BASE,
            debugKeyImport: true,
            nowMillis: { KotlinLong(value: clock.now) },
            security: SecurityConfig(pinIterations: 16, lockTimeoutMillis: 60_000, monotonicMillis: { KotlinLong(value: clock.monotonic) }),
            legacy: nil,
            auth: DemoAuthServices.shared.create(nowMillis: { KotlinLong(value: clock.now) }, waitMillis: 1000)
        )
        var options = LaunchOptions()
        options.fakeData = true
        options.keepFakeStore = true
        app = AppContainer(env: env, options: options)
    }

    override func tearDown() {
        watchers.forEach { $0.close() }
        watchers = []
        window?.isHidden = true
        window = nil
        AppContainer.forgetSavedNavigation()
        app = nil
        env = nil
    }

    private func waitUntil(_ timeout: TimeInterval = 10, _ condition: () -> Bool) {
        let end = Date().addingTimeInterval(timeout)
        while !condition() && Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.05)) }
    }

    private func spin(_ seconds: TimeInterval) { RunLoop.main.run(until: Date().addingTimeInterval(seconds)) }

    /// The app goes to the background for [millis] and comes back, in the order the system tells it.
    private func away(_ millis: Int64) {
        env.appLock.onBackground()
        clock.monotonic += millis
        clock.now += millis
        NotificationCenter.default.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        env.appLock.onForeground()
    }

    private func host<V: View>(_ view: V) {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow(frame: UIScreen.main.bounds)
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        window.rootViewController = UIHostingController(rootView: view.environmentObject(app).environmentObject(app.session).environmentObject(app.votes))
        window.makeKeyAndVisible()
        self.window = window
    }

    private func scrollView() -> UIScrollView? {
        func find(_ view: UIView) -> UIScrollView? {
            if let scroll = view as? UIScrollView, scroll.contentSize.height > scroll.bounds.height { return scroll }
            for sub in view.subviews { if let found = find(sub) { return found } }
            return nil
        }
        return window.flatMap { find($0) }
    }

    private func feedOnScreen() throws -> Observed<FeedState> {
        try make()
        let feed = Observed(env.feedModel(source: FeedSourceRanked(sort: .trending, tag: "")), ownsModel: true)
        host(FeedListView(feed: feed))
        waitUntil { feed.state.loaded }
        XCTAssertFalse(feed.state.items.isEmpty)
        return feed
    }

    func testTheFeedOnScreenReadsAgainQuietlyAfterALongTripOnly() throws {
        let feed = try feedOnScreen()
        let reads = transport.reads
        away(long - 1)
        spin(0.5)
        XCTAssertEqual(transport.reads, reads, "a short trip does not refetch")

        var spinner = false
        watchers.append(feed.model.watch { s in
            if s.isRefreshing || s.isLoading || s.items.isEmpty { spinner = true }
        })
        transport.extraTop = 1
        away(long)
        waitUntil { feed.state.items.first?.key.permlink == "fresh-0" }
        XCTAssertEqual(feed.state.items.first?.key.permlink, "fresh-0")
        XCTAssertGreaterThan(transport.reads, reads)
        XCTAssertFalse(spinner, "no spinner, no skeleton, the list stayed")
    }

    /// Review of #3617: a feed whose pages are all filtered out stops after the model's cap and shows its empty state
    /// with "Load more" (no skeleton for good); the list does not read on by itself, the button reads on from the pager.
    func testAFeedFilteredAwayStopsAtTheCapAndLoadsOnWhenAsked() throws {
        try make()
        transport.allBySpammer = true
        env.setAuthorMuted(author: "spammer", muted: true)
        let feed = Observed(env.feedModel(source: FeedSourceRanked(sort: .trending, tag: "")), ownsModel: true)
        host(FeedListView(feed: feed))
        waitUntil { feed.state.loaded }
        spin(1)
        let cap = Int(FeedModel.companion.MAX_AUTO_ADVANCE)
        XCTAssertEqual(transport.reads, 1 + cap, "the page asked for and the cap, then the list waits")
        XCTAssertTrue(feed.state.items.isEmpty)
        XCTAssertFalse(feed.state.endReached)
        XCTAssertTrue(FeedPaging.shouldLoadMoreAtEnd(feed.state), "the empty state offers Load more")
        (feed.model as! FeedModel).loadMore()
        waitUntil { transport.reads == 2 * (1 + cap) && !feed.state.isLoadingMore }
        spin(0.5)
        XCTAssertEqual(transport.reads, 2 * (1 + cap))
        XCTAssertEqual(Int(feed.state.pages), 2 * (1 + cap))
    }

    func testAReaderScrolledDownKeepsTheCardTheyRead() throws {
        let feed = try feedOnScreen()
        waitUntil { self.scrollView() != nil }
        guard let scroll = scrollView() else { return XCTFail("no scroll view") }
        // in steps, as a finger moves it: the list draws each position
        for y in stride(from: 100, through: 900, by: 100) {
            scroll.setContentOffset(CGPoint(x: 0, y: CGFloat(y)), animated: false)
            spin(0.1)
        }
        spin(0.5)
        let before = scroll.contentOffset.y
        transport.extraTop = 2
        away(long)
        waitUntil { feed.state.items.first?.key.permlink == "fresh-0" }
        XCTAssertEqual(feed.state.items.first?.key.permlink, "fresh-0")
        spin(0.8)
        // two cards went on top: the list moved down with them, so the card being read stays where it was
        XCTAssertGreaterThan(scroll.contentOffset.y, before + 200, "kept \(before) -> \(scroll.contentOffset.y)")
    }

    func testAFeedAtItsTopShowsTheNewCards() throws {
        let feed = try feedOnScreen()
        transport.extraTop = 2
        away(long)
        waitUntil { feed.state.items.first?.key.permlink == "fresh-0" }
        XCTAssertEqual(feed.state.items.first?.key.permlink, "fresh-0")
        spin(0.8)
        XCTAssertLessThanOrEqual(scrollView()?.contentOffset.y ?? 0, 1, "still at the top, the new cards in view")
    }

    func testTheWalletOnScreenRefreshesQuietlyAfterALongTripOnly() throws {
        try make(signedIn: true)
        env.session.start()
        let wallet = Observed(env.wallet, ownsModel: false)
        let model = wallet.model as! WalletModel
        model.start()
        model.visible(shown: true)
        defer { model.visible(shown: false) }
        waitUntil { wallet.state.loaded }
        let first = wallet.state.updatedAtMillis
        XCTAssertNotNil(first)

        away(long - 1)
        spin(0.5)
        XCTAssertEqual(wallet.state.updatedAtMillis, first, "a short trip does not refetch")

        var spinner = false
        watchers.append(model.watch { s in
            if s.isRefreshing || s.isLoading { spinner = true }
        })
        away(long)
        waitUntil { wallet.state.updatedAtMillis != first }
        XCTAssertNotEqual(wallet.state.updatedAtMillis, first)
        XCTAssertFalse(spinner, "no spinner")
        XCTAssertFalse(wallet.state.rows.isEmpty)
    }
}
