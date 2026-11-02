import EcencyCore
import XCTest
@testable import EcencyApp

/// Owner (TestFlight): a vote moves the shown payout by its estimate at once, as RN did, and a failed vote puts it
/// back. Over the fake transport: its stake and reward fund make a full upvote worth $0.250; it refuses broadcasts.
@MainActor
final class VotePayoutTests: XCTestCase {
    private var env: AppEnvironment!
    private var watchers: [Closeable] = []

    override func setUp() async throws {
        let store = KeychainSecureStore(service: "app.esteem.mobile.ios.tests.votepayout")
        try store.wipe()
        let vault = KeyVault(store: store)
        let wif = PrivateKey.companion.fromLogin(username: "spike-alice", password: FakeHiveTransport.password, role: .posting).toWif()
        try vault.save(account: VaultAccount(
            username: "spike-alice", authType: .postingKey, postingKey: wif, activeKey: nil,
            memoKey: nil, accessToken: "demo-access-spike-alice", hiveAuthKey: nil, hiveAuthToken: nil, hiveAuthExpiry: 0
        ))
        try vault.setCurrentUsername(username: "spike-alice")
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
            auth: DemoAuthServices.shared.create(nowMillis: { KotlinLong(value: 0) }, waitMillis: 1000)
        )
        env.session.start()
        waitUntil { self.env.session.current.username == "spike-alice" }
    }

    override func tearDown() {
        watchers.forEach { $0.close() }
        watchers = []
    }

    private func waitUntil(_ timeout: TimeInterval = 10, _ condition: () -> Bool) {
        let end = Date().addingTimeInterval(timeout)
        while !condition() && Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.05)) }
    }

    func testAVoteMovesTheCardsPayoutAtOnceAndAFailurePutsItBack() {
        // the vote sheet reads the estimate when it opens; read again while it comes back empty (the first read
        // right after the session starts can, over the fake node)
        var preview: VotePreview?
        let env = self.env!
        let end = Date().addingTimeInterval(10)
        while preview == nil && Date() < end {
            var read = false
            Task { preview = try? await env.votePreview(); read = true }
            waitUntil { read }
            if preview == nil { waitUntil(0.5) { false } }
        }
        XCTAssertEqual(preview?.text(weightBasisPoints: 10000), "$0.250")

        let feed = env.feedModel(source: FeedSourceRanked(sort: .trending, tag: ""))
        var seen: [String: [String]] = [:]
        watchers.append(feed.watch { s in
            for card in s.items where seen[card.key.permlink]?.last != card.payoutText { seen[card.key.permlink, default: []].append(card.payoutText) }
        })
        feed.start()
        waitUntil { feed.current.loaded }
        guard let card = feed.current.items.first(where: { !$0.votedByMe && !$0.downvotedByMe && !$0.isPayoutDeclined }) else {
            return XCTFail("no card to vote on")
        }
        let before = card.payoutText
        let raised = PostCard.companion.formatPayout(value: card.payout + 0.25)

        env.votes.vote(author: card.key.author, permlink: card.key.permlink, weight: 10000)
        // optimistic: shown before the broadcast's answer
        waitUntil { feed.current.items.first { $0.key == card.key }?.votedByMe == true }
        let voted = feed.current.items.first { $0.key == card.key }
        XCTAssertEqual(voted?.votes, card.votes + 1)
        // the fake transport refuses the broadcast: rolled back
        waitUntil { self.env.votes.current.status(author: card.key.author, permlink: card.key.permlink)?.error != nil }
        waitUntil { feed.current.items.first { $0.key == card.key }?.payoutText == before }
        XCTAssertEqual(seen[card.key.permlink], [before, raised, before], "the payout rose by the estimate, then came back")
        feed.close()
    }
}
