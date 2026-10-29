import EcencyCore
import XCTest
@testable import EcencyApp

/// Perks and the spin game on iOS: the screen models over the demo answers (nothing is charged or claimed anywhere).
@MainActor
final class PerksTests: XCTestCase {
    private var env: AppEnvironment!

    override func setUp() async throws {
        let store = KeychainSecureStore(service: "app.esteem.mobile.ios.tests.perks")
        try store.wipe()
        let vault = KeyVault(store: store)
        try vault.save(account: VaultAccount(
            username: "spike-alice", authType: .postingKey, postingKey: PrivateKey.companion.fromSeed(seed: "ecency-native-perks-test-throwaway").toWif(), activeKey: nil,
            memoKey: nil, accessToken: "demo-access-spike-alice", hiveAuthKey: nil, hiveAuthToken: nil, hiveAuthExpiry: 0
        ))
        try vault.setCurrentUsername(username: "spike-alice")
        DemoAuthServices.shared.demoQuests = DemoAuthServices.shared.SAMPLE_QUESTS
        DemoAuthServices.shared.demoSpinUsed = false
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

    override func tearDown() async throws {
        DemoAuthServices.shared.demoQuests = #"{"daily":[{"id":"vote","progress":1},{"id":"post","progress":0}],"streak":{"current":0}}"#
        DemoAuthServices.shared.demoSpinUsed = false
    }

    private func waitUntil(_ timeout: TimeInterval = 10, _ condition: () -> Bool) {
        let end = Date().addingTimeInterval(timeout)
        while !condition() && Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.05)) }
    }

    func testTheQuestsCardReadsTheTiersAndTheStreak() {
        let perks = Observed(env.perksModel(), ownsModel: true)
        let model = perks.model as! PerksModel
        model.start()
        waitUntil { perks.state.quests != nil }
        XCTAssertEqual(perks.state.account, "spike-alice")
        XCTAssertEqual(perks.state.rows.map(\.id), ["checkin", "post", "comment", "vote", "reblog"])
        XCTAssertEqual(perks.state.rows.map(\.count), ["1/1", "0/1", "2/3", "6/10", "1/1"])
        XCTAssertEqual(PerksCopy.shared.streak(n: perks.state.streak), "5-day streak")
        XCTAssertTrue(perks.state.showFreezeOwned && perks.state.showFreezeBuy)
        model.selectTier(tier: .weekly)
        XCTAssertEqual(model.current.rows.map(\.count), ["3/5", "9/15", "31/50", "0/5"])
        model.selectTier(tier: .monthly)
        XCTAssertEqual(model.current.rows.first?.percent, 60)
    }

    func testTheFreezeIsConfirmedBeforeAnythingIsSpent() {
        let perks = Observed(env.perksModel(), ownsModel: true)
        let model = perks.model as! PerksModel
        model.start()
        waitUntil { perks.state.quests != nil }
        model.askFreeze()
        XCTAssertTrue(model.current.confirmingFreeze)
        XCTAssertNil(env.streakFreezeBuys.running(account: "spike-alice"), "nothing runs before the confirmation")
        model.cancelFreeze()
        XCTAssertFalse(model.current.confirmingFreeze)
        model.askFreeze()
        model.confirmFreeze()
        XCTAssertTrue(model.current.buyingFreeze)
        waitUntil { !model.current.buyingFreeze }
        XCTAssertNil(model.current.alert)
        XCTAssertNil(env.streakFreezeBuys.key(account: "spike-alice"), "delivered: the key is done")
    }

    func testASpinClaimsOnceThenShowsNoneLeft() {
        let spin = Observed(env.spinGameModel(), ownsModel: true)
        let model = spin.model as! SpinGameModel
        model.start()
        waitUntil { !spin.state.loading }
        XCTAssertEqual(spin.state.gameRight, 1)
        XCTAssertTrue(spin.state.showButton)
        model.spin()
        model.spin() // a second press while the first runs sends nothing
        XCTAssertTrue(model.current.spinning)
        waitUntil { model.current.score == 25 && model.current.gameRight == 0 && !model.current.claiming }
        XCTAssertEqual(model.current.score, 25)
        XCTAssertEqual(model.current.gameRight, 0)
        XCTAssertEqual(model.current.nextDate, "2099-01-01T05:04:03Z")
        XCTAssertTrue(DemoAuthServices.shared.demoSpinUsed)
    }

    func testTheCountdownMatchesMoment() {
        // moment.utc(moment(next).diff(now)).format('H:m:ss'), now = 2026-10-20T10:00:00Z
        XCTAssertEqual(SpinCopy.shared.timeLeft(nextDate: "2026-10-20T11:05:09Z", nowMillis: 1_792_490_400_000), "1:5:09")
        XCTAssertEqual(SpinCopy.shared.timeLeft(nextDate: "2026-10-20T09:59:59Z", nowMillis: 1_792_490_400_000), "23:59:59")
    }
}
