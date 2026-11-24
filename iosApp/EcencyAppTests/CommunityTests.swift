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
        XCTAssertEqual(Dapps.shared.DIRECTORY.count, 14)
        XCTAssertEqual(Dapps.shared.DIRECTORY.first { $0.name == "HiveGrove" }?.url, "https://playhivegrove.com")
        XCTAssertEqual(Color(hexString: "#357CE6"), Color(hex: 0x357CE6))
        XCTAssertEqual(Color(hexString: "nope"), Color(hex: 0x788187))
        XCTAssertEqual(Dapps.shared.screen(url: "https://peakd.com") as? AppScreenDappBrowser, AppScreenDappBrowser(url: "https://peakd.com"))
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
