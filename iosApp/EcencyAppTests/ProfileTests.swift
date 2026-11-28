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

    /// Opening a listed comment reads it again (get_post, get_discussion): the same comment and its replies, not a made-up root post.
    func testFakeTransportOpensAListedCommentAsTheSameComment() throws {
        func call(_ method: String, _ params: [String: String]) throws -> Any {
            let request = try JSONSerialization.data(withJSONObject: ["jsonrpc": "2.0", "id": 1, "method": method, "params": params])
            let reply = FakeHiveTransport.answer(String(data: request, encoding: .utf8)!)
            let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(reply.body.utf8)) as? [String: Any])
            return try XCTUnwrap(json["result"], reply.body)
        }
        func fields(_ e: [String: Any]) -> [String] {
            ["body", "parent_author", "parent_permlink", "depth", "children"].map { "\(e[$0] ?? "nil")" }
        }
        var listed: [[String: Any]] = []
        for (account, sort) in [("spike-bob", "comments"), ("spike-alice", "replies")] {
            listed += try XCTUnwrap(call("bridge.get_account_posts", ["account": account, "sort": sort]) as? [[String: Any]])
        }
        XCTAssertEqual(listed.count, 6)
        for c in listed {
            let author = c["author"] as! String, permlink = c["permlink"] as! String, key = "\(author)/\(permlink)"
            let post = try XCTUnwrap(call("bridge.get_post", ["author": author, "permlink": permlink]) as? [String: Any])
            XCTAssertEqual(fields(post), fields(c), key)
            let thread = try XCTUnwrap(call("bridge.get_discussion", ["author": author, "permlink": permlink]) as? [String: [String: Any]])
            XCTAssertEqual(fields(try XCTUnwrap(thread[key], key)), fields(c), key)
            let below = thread.filter { $0.key != key }.values
            XCTAssertEqual(below.count, c["children"] as? Int, "\(key): its replies, and only them")
            XCTAssertTrue(below.allSatisfy { $0["parent_author"] as? String == author && $0["parent_permlink"] as? String == permlink && $0["depth"] as? Int == 2 }, key)
            // a reply opens as itself too
            for r in below {
                let ra = r["author"] as! String, rp = r["permlink"] as! String
                let opened = try XCTUnwrap(call("bridge.get_post", ["author": ra, "permlink": rp]) as? [String: Any])
                XCTAssertEqual(fields(opened), fields(r), "\(ra)/\(rp)")
                let rthread = try XCTUnwrap(call("bridge.get_discussion", ["author": ra, "permlink": rp]) as? [String: [String: Any]])
                XCTAssertEqual(Array(rthread.keys), ["\(ra)/\(rp)"])
            }
        }
    }

    /// Review fix: keys that only look like a demo comment's are not one (no parent name, a signed number), and never trap.
    func testFakeTransportTakesOnlyDemoCommentKeys() {
        for permlink in ["re-fake-post-1", "re-spike-alice-fake-post-+1", "re-spike-alice-fake-post-4", "re-spike-bob-fake-post-1"] {
            XCTAssertNil(FakeHiveTransport.commentThread(author: "spike-bob", permlink: permlink), permlink)
        }
        XCTAssertNotNil(FakeHiveTransport.commentThread(author: "spike-bob", permlink: "re-spike-alice-fake-post-1"))
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

    /// #3754: a wide cover on a phone window is decoded tall enough to fill the 160 pt header at 3x, not scaled up.
    func testAWideCoverIsDecodedTallEnoughForTheHeader() throws {
        let cover = UIGraphicsImageRenderer(size: CGSize(width: 2688, height: 480), format: { let f = UIGraphicsImageRendererFormat(); f.scale = 1; return f }())
            .image { ctx in UIColor.blue.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 2688, height: 480)) }.pngData()!
        let header = try XCTUnwrap(ImageLoader.decode(cover, maxPixel: CoverDecode.width(window: 390, boxHeight: 160) * 3))
        XCTAssertGreaterThanOrEqual(header.image.size.height, 480 - 1, "160 pt at 3x")
        let preview = try XCTUnwrap(ImageLoader.decode(cover, maxPixel: CoverDecode.width(window: nil, boxHeight: 140) * 3))
        XCTAssertGreaterThanOrEqual(preview.image.size.height, 420 - 1, "140 pt at 3x")
        XCTAssertEqual(CoverDecode.width(window: 1400, boxHeight: 160), 1400, "a wide window still decodes for its width")
    }
}
