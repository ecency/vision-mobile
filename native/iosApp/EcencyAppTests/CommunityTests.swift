import EcencyCore
import SwiftUI
import XCTest
@testable import EcencyApp

/// The community and search views' Swift helpers, and the shared models through the Swift bridge.
@MainActor
final class CommunityTests: XCTestCase {
    private func env() throws -> AppEnvironment {
        let store = KeychainSecureStore(service: "app.esteem.mobile.ios.tests.communities")
        try store.wipe()
        return AppEnvironment(
            api: try EcencyApi(nodes: ["https://fake-hive.invalid"], transport: FakeHiveTransport()),
            vault: KeyVaultAsync(vault: KeyVault(store: store)),
            renderer: PostBodyRendererCompanion.shared.`default`(proxyBase: ImageProxy.companion.DEFAULT_BASE),
            dispatchers: AppDispatchers.companion.platform(),
            proxyBase: ImageProxy.companion.DEFAULT_BASE,
            debugKeyImport: true,
            nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
            security: SecurityConfig(pinIterations: 16, lockTimeoutMillis: 60_000, monotonicMillis: { KotlinLong(value: 0) }),
            legacy: nil,
            auth: DemoAuthServices.shared.create(nowMillis: { KotlinLong(value: 0) }, waitMillis: 1000)
        )
    }

    private func waitUntil(_ timeout: TimeInterval = 10, _ condition: () -> Bool) {
        let end = Date().addingTimeInterval(timeout)
        while !condition() && Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.05)) }
    }

    func testCommunityPageLoadsOverTheBridge() throws {
        let observed = Observed(CommunityModel(env: try env(), name: "hive-100001"), ownsModel: true)
        (observed.model as! CommunityModel).start()
        waitUntil { observed.state.community != nil }
        XCTAssertEqual(observed.state.headerTitle, "Spike Community community")
        XCTAssertEqual(observed.state.stats, "1,200 Members • 40 Posters • 280 Posts")
        XCTAssertFalse(observed.state.isModerator, "signed out: no manage button")
        XCTAssertEqual(observed.state.community?.description_, "A made-up community: nothing here is real content.")
    }

    func testMembersMergeTheTeamAndSubscribers() throws {
        let observed = Observed(CommunityMembersModel(env: try env(), name: "hive-100001", title: ""), ownsModel: true)
        (observed.model as! CommunityMembersModel).start()
        waitUntil { !observed.state.members.isEmpty && !observed.state.loading }
        let members = observed.state.members
        XCTAssertEqual(members.first?.account, "spike-alice")
        XCTAssertEqual(members.first?.role, .admin)
        XCTAssertFalse(members.contains { $0.account == "hive-100001" }, "the community account is not a member")
        XCTAssertTrue(observed.state.assignableRoles.isEmpty, "signed out: read only")
    }

    func testSearchReachesTheSearchApiThroughTheBridge() throws {
        let observed = Observed(SearchModel(env: try env(), debounceMillis: 10), ownsModel: true)
        let search = observed.model as! SearchModel
        search.submit(text: "result")
        waitUntil { !observed.state.posts.items.isEmpty }
        XCTAssertEqual(observed.state.posts.items.map { $0.key }, ["spike-bob/search-hit-1", "spike-bob/search-hit-2", "spike-bob/search-hit-3"])
        XCTAssertEqual(observed.state.posts.items.first?.snippet, "A synthetic result number 1.")
    }

    func testActivityMentionsBecomeInAppLinks() {
        let row = ActivityRow(id: "1", actor: "alice", parts: CommunityActivitiesModel.companion.parts(msg: "@alice pinned @bob/post-1"),
                              date: "", epochMillis: 0)
        let text = ActivityText.attributed(row, link: .blue)
        let links = text.runs.compactMap { $0.link }
        XCTAssertEqual(links.map(\.absoluteString), ["ecency-activity://alice", "ecency-activity://bob/post-1"])
        XCTAssertEqual(String(text.characters), "@alice pinned @bob/post-1")
    }

    func testSnippetHighlightsTheSearchedWord() {
        let text = SearchText.highlighted("Hive and hive 🐝 hive", "hive", mark: .gray)
        let marked = text.runs.filter { $0.backgroundColor != nil }.map { String(text[$0.range].characters) }
        XCTAssertEqual(marked, ["Hive", "hive", "hive"])
        XCTAssertEqual(SearchText.highlighted("plain", "", mark: .gray).runs.count, 1)
    }

    func testDappColorsAndDirectory() {
        XCTAssertEqual(Dapps.shared.DIRECTORY.count, 13)
        XCTAssertEqual(Color(hexString: "#357CE6"), Color(hex: 0x357CE6))
        XCTAssertEqual(Color(hexString: "nope"), Color(hex: 0x788187))
        XCTAssertEqual(Dapps.shared.screen(url: "https://peakd.com") as? AppScreenDappBrowser, AppScreenDappBrowser(url: "https://peakd.com"))
    }

    /// The profile summary and community Details driver: the shared rule through the Swift bridge, and the cooldown
    /// waited out by its task (RN's recheck timer) without another scroll event.
    func testSummaryCollapseDriverClosesOnScrollAndReopensAtTheTopAfterTheCooldown() {
        let driver = SummaryCollapseDriver()
        for y in stride(from: 10, through: 200, by: 10) { driver.scrolled(to: CGFloat(y)) }
        XCTAssertFalse(driver.open)
        // straight back to the top inside the cooldown: no flip yet, the task settles it
        for y in stride(from: 190, through: 0, by: -10) { driver.scrolled(to: CGFloat(y)) }
        XCTAssertFalse(driver.open)
        waitUntil(3) { driver.open }
        XCTAssertTrue(driver.open)
        // a tap close at the top stays closed there
        driver.collapse()
        XCTAssertFalse(driver.open)
        RunLoop.main.run(until: Date().addingTimeInterval(0.7))
        driver.scrolled(to: 2)
        driver.scrolled(to: 0)
        XCTAssertFalse(driver.open)
        driver.toggle()
        XCTAssertTrue(driver.open)
    }

    func testLayoutHoldsAClosedSummaryAndATabSwitchOpensIt() {
        let driver = SummaryCollapseDriver()
        for y in stride(from: 10, through: 150, by: 10) { driver.scrolled(to: CGFloat(y)) }
        XCTAssertFalse(driver.open)
        // the short list clamps to its top once it got the header's room: layout, not the reader
        driver.scrolled(to: 0, byReader: false, scrollable: false)
        RunLoop.main.run(until: Date().addingTimeInterval(0.7))
        XCTAssertFalse(driver.open, "no open, shrink, close loop")
        driver.scrolled(to: -70)
        XCTAssertTrue(driver.open, "a pull down opens it")
        for y in stride(from: 10, through: 150, by: 10) { driver.scrolled(to: CGFloat(y)) }
        RunLoop.main.run(until: Date().addingTimeInterval(0.7))
        for y in stride(from: 160, through: 300, by: 10) { driver.scrolled(to: CGFloat(y)) }
        XCTAssertFalse(driver.open)
        driver.reset()
        XCTAssertTrue(driver.open)
    }

    func testOnlyTheReadersDragOrFlingOfAScrollableListOfStableSizeCounts() {
        let list = ScrollMetrics(offset: 300, scrollable: true, viewport: 500)
        XCTAssertTrue(CollapsesSummary.isReader(list, scrolling: true, resized: false), "a drag or fling")
        XCTAssertFalse(CollapsesSummary.isReader(list, scrolling: false, resized: false), "idle or .animating (status bar tap, scroll to top): code")
        XCTAssertFalse(CollapsesSummary.isReader(list, scrolling: true, resized: true), "a clamp when the viewport grew")
        let short = ScrollMetrics(offset: 0, scrollable: false, viewport: 700)
        XCTAssertFalse(CollapsesSummary.isReader(short, scrolling: true, resized: false), "a list that cannot scroll")
        let pulled = ScrollMetrics(offset: -40, scrollable: false, viewport: 700)
        XCTAssertTrue(CollapsesSummary.isReader(pulled, scrolling: true, resized: false), "a pull past the top")
        XCTAssertFalse(CollapsesSummary.isReader(pulled, scrolling: false, resized: false))
    }

    func testCodeThatScrollsToTheTopOpensItAndAViewportClampDoesNot() {
        let driver = SummaryCollapseDriver()
        for y in stride(from: 10, through: 2000, by: 10) { driver.scrolled(to: CGFloat(y)) }
        XCTAssertFalse(driver.open)
        RunLoop.main.run(until: Date().addingTimeInterval(0.7))
        driver.scrolled(to: 1700, byReader: false, scrollable: true)
        driver.scrolled(to: 1690)
        XCTAssertFalse(driver.open, "the clamp is no upward run")
        // a status bar tap: .animating to the top of a list that scrolls
        driver.scrolled(to: 0, byReader: false, scrollable: true)
        XCTAssertTrue(driver.open)
    }

    func testTheScrollOffsetPreferenceIsNotResetBySiblingsThatReportNothing() {
        var value: CGFloat? = 120
        ScrollOffsetKey.reduce(value: &value) { nil }
        XCTAssertEqual(value, 120, "an overlay without an offset keeps the list's")
        ScrollOffsetKey.reduce(value: &value) { 40 }
        XCTAssertEqual(value, 40)
        var height: CGFloat? = 900
        ScrollContentHeightKey.reduce(value: &height) { nil }
        XCTAssertEqual(height, 900)
    }

    func testModerationSheetsShareTheSetRoleLayout() {
        // they build with the shared rows (a render smoke test; the flows are in CommunityUITests and FeedReadingUITests)
        let manage = UIHostingController(rootView: CommunityManageSheet { _ in })
        manage.view.layoutIfNeeded()
        let notes = UIHostingController(rootView: ModNotesSheet(subtitle: "@alice/post") { _ in })
        notes.view.layoutIfNeeded()
        XCTAssertEqual(CommunityManageAction.entries.count, 3)
    }
}
