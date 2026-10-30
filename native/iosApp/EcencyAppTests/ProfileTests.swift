import EcencyCore
import XCTest
@testable import EcencyApp

/// The iOS side of the profile area: shared types as Swift sees them, the date formatting and the fake profile data.
@MainActor
final class ProfileTests: XCTestCase {
    func testJoinDateFollowsTheLocale() {
        let date = CalendarDate(year: 2018, month: 5, day: 1)
        XCTAssertEqual(ProfileDates.local(date, language: "en-US"), "May 1, 2018")
        XCTAssertEqual(ProfileDates.local(date, language: "de-DE"), "1. Mai 2018")
        XCTAssertEqual(ProfileDates.local(date, language: "ar-SA"), "١ مايو ٢٠١٨")
    }

    func testProfileScreensAreTypedAndRoundTrip() {
        let screens: [AppScreen] = [
            AppScreenProfile(author: "alice"), AppScreenProfile(author: "alice", section: "wallet"),
            AppScreenFollows(username: "alice", following: true, count: 3), AppScreenProfileEdit.shared, AppScreenBookmarks(tab: 2),
            AppScreenAccountList(title: "Voters", users: [AccountListEntry(account: "bob", timestampMillis: 5)]),
        ]
        var nav = NavState.companion.decodeJoined(saved: "")
        for s in screens { nav = nav.push(screen: s) }
        let decoded = NavState.companion.decodeJoined(saved: NavState.companion.encodeJoined(state: nav)).screens
        XCTAssertEqual(decoded.count, screens.count)
        for (a, b) in zip(decoded, screens) { XCTAssertTrue((a as AnyObject).isEqual(b as AnyObject), "\(a) != \(b)") }
        XCTAssertTrue(RouteName.bookmarks.built)
        XCTAssertTrue(RouteName.profileEdit.requiresLogin)
        XCTAssertTrue(nav.pushGated(screen: AppScreenProfileEdit.shared, loggedIn: false).top is AppScreenLogin)
    }

    func testProfileLinksOpenTheirSection() {
        let wallet = ContentLinks.shared.entry(url: "https://ecency.com/@alice/points")?.screens.first as? AppScreenProfile
        XCTAssertEqual(wallet?.section, "wallet")
        let follows = ContentLinks.shared.entry(url: "https://ecency.com/@alice/following")?.screens.first as? AppScreenFollows
        XCTAssertEqual(follows?.following, true)
    }

    func testFakeTransportServesProfilesWithMetadata() {
        let body = #"{"jsonrpc":"2.0","id":1,"method":"condenser_api.get_accounts","params":[["spike-alice"]]}"#
        let reply = FakeHiveTransport.answer(body)
        XCTAssertEqual(reply.status, 200)
        XCTAssertTrue(reply.body.contains("Synthetic test account"))
        let followers = FakeHiveTransport.answer(#"{"jsonrpc":"2.0","id":1,"method":"condenser_api.get_followers","params":["spike-alice",null,"blog",100]}"#)
        XCTAssertTrue(followers.body.contains("spike-bob"))
        XCTAssertFalse(followers.body.contains("\"follower\":\"spike-alice\""))
    }

    func testProfileFormatHelpers() {
        XCTAssertEqual(ProfileFormat.shared.count(value: 1234), "1.2K")
        XCTAssertEqual(ProfileFormat.shared.websiteUrl(website: "ecency.com"), "https://ecency.com")
        XCTAssertNil(ProfileFormat.shared.hoursToFull(percent: "100.0"))
    }

    func testBookmarksTabSelectionReachesSwift() {
        let env = AppEnvironment(
            api: try! EcencyApi(nodes: ["https://fake-hive.invalid"], transport: FakeHiveTransport()),
            vault: KeyVaultAsync(vault: KeyVault(store: KeychainSecureStore(service: "app.esteem.mobile.ios.tests.profile"))),
            renderer: PostBodyRendererCompanion.shared.`default`(proxyBase: ImageProxy.companion.DEFAULT_BASE),
            dispatchers: AppDispatchers.companion.platform(),
            proxyBase: ImageProxy.companion.DEFAULT_BASE,
            debugKeyImport: true,
            nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
            security: SecurityConfig(pinIterations: 16, lockTimeoutMillis: 60_000, monotonicMillis: { KotlinLong(value: 0) }),
            legacy: nil,
            auth: AuthServices.companion.offline()
        )
        let observed = Observed(env.bookmarksModel(tab: 0), ownsModel: true)
        (observed.model as! BookmarksModel).select(index: 1)
        let end = Date().addingTimeInterval(5)
        while observed.state.selected != 1 && Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.05)) }
        XCTAssertEqual(observed.state.selected, 1)
        XCTAssertEqual(observed.state.tab, .favorites)
        let edit = Observed(env.profileEditModel(), ownsModel: true)
        (edit.model as! ProfileEditModel).setValue(field: .name, value: "x")
        let end2 = Date().addingTimeInterval(5)
        while edit.state.value(field: .name) != "x" && Date() < end2 { RunLoop.main.run(until: Date().addingTimeInterval(0.05)) }
        XCTAssertEqual(edit.state.value(field: .name), "x")
    }
}
