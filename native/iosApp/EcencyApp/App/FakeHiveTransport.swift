#if DEBUG
import EcencyCore
import Foundation

/// Debug-only transport behind `-fakeData YES`: answers the read calls the slice makes from synthetic
/// fixtures (made-up accounts and text, no real user content) so UI tests run offline and
/// deterministically. Account lookups answer for the fake authors (throwaway keys); every broadcast fails, so
/// nothing can reach a chain.
final class FakeHiveTransport: NSObject, RpcTransport {
    static let authors = ["spike-alice", "spike-bob", "spike-carol", "spike-dave", "spike-erin", "spike-frank"]

    /// Markdown for every fake post: a heading, text, a mention, an external link on its own line and a video.
    static let body = """
    ## Spike fixture

    This post is synthetic test content for the native app. Hi @spike-bob, thanks for reading.

    [Example link](https://example.com/spike)

    A second paragraph with **bold** and *italic* text, long enough to wrap over a few lines on a phone screen.

    https://www.youtube.com/watch?v=aaaaaaaaaaa
    """

    func post(url: String, body: String, completionHandler: @escaping (HttpResult?, Error?) -> Void) {
        completionHandler(answerSync(body), nil)
    }

    /// The wallet's reads first (synthetic balances, history and market rows shared with Android's demo runs), then the rest.
    func answerSync(_ body: String) -> HttpResult {
        DemoWallet.shared.answer(requestBody: body) ?? Self.answer(body)
    }

    static func answer(_ requestBody: String) -> HttpResult {
        guard let data = requestBody.data(using: .utf8),
              let request = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let method = request["method"] as? String else {
            return HttpResult(status: 400, body: "bad request")
        }
        let id = request["id"] ?? 0
        let params = request["params"] as? [String: Any] ?? [:]
        // broadcasts: refused as the chain refuses one (a JSON-RPC error), as DemoHiveTransport; a 5xx could mean the
        // transaction landed and would leave the send undecided
        if method.hasSuffix(".broadcast_transaction") || method.hasSuffix(".broadcast_transaction_synchronous") {
            let refusal: [String: Any] = ["jsonrpc": "2.0", "error": ["code": -32000, "message": "fake transport: broadcasts are not available offline"], "id": id]
            let json = (try? JSONSerialization.data(withJSONObject: refusal)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
            return HttpResult(status: 200, body: json)
        }
        let result: Any
        switch method {
        case "bridge.get_ranked_posts":
            result = (params["start_author"] as? String).map { $0.isEmpty } ?? true ? feed(tag: params["tag"] as? String) : []
        case "bridge.get_account_posts":
            let account = params["account"] as? String ?? "spike-alice"
            result = (params["start_author"] as? String).map { $0.isEmpty } ?? true ? feed(author: account) : []
        case "bridge.get_post":
            let author = params["author"] as? String ?? ""
            let permlink = params["permlink"] as? String ?? ""
            result = discussion(rootAuthor: author, rootPermlink: permlink)["\(author)/\(permlink)"]
                ?? entry(author: author, permlink: permlink)
        case "bridge.get_discussion":
            result = discussion(rootAuthor: params["author"] as? String ?? "", rootPermlink: params["permlink"] as? String ?? "")
        case "condenser_api.get_accounts":
            // the fake accounts for the login flows: every role derives from a public throwaway password
            let names = ((request["params"] as? [Any])?.first as? [String]) ?? []
            result = names.filter { authors.contains($0) }.map { account($0) }
        // ---- profile area: synthetic profiles, follows and resource credits for the fake accounts ----
        case "bridge.get_profile":
            let name = params["account"] as? String ?? "spike-alice"
            guard authors.contains(name) else { return HttpResult(status: 200, body: "{\"jsonrpc\":\"2.0\",\"result\":null,\"id\":\(id)}") }
            result = profile(name)
        case "bridge.get_profiles":
            result = ((params["accounts"] as? [String]) ?? []).filter { authors.contains($0) }.map { profile($0) }
        case "condenser_api.get_follow_count":
            result = ["account": "spike-alice", "follower_count": 1234, "following_count": 56]
        case "bridge.get_relationship_between_accounts":
            result = ["follows": false, "ignores": false, "blacklists": false, "follows_muted": false, "follows_blacklists": false]
        case "rc_api.find_rc_accounts":
            result = ["rc_accounts": [["account": "spike-alice", "max_rc": "1000",
                                       "rc_manabar": ["current_mana": "640", "last_update_time": Int(Date().timeIntervalSince1970)]]]]
        case "condenser_api.get_followers", "condenser_api.get_following":
            let args = request["params"] as? [Any] ?? []
            let target = args.first as? String ?? "spike-alice"
            let following = method.hasSuffix("following")
            result = (args.count > 1 && !(args[1] is NSNull)) ? [] : authors.filter { $0 != target }.map {
                following ? ["follower": target, "following": $0, "what": ["blog"]] : ["follower": $0, "following": target, "what": ["blog"]]
            }
        // ---- communities and search: a fake community with spike-alice as its admin ----
        case "bridge.get_community":
            result = community(params["name"] as? String ?? "hive-100001")
        case "bridge.list_communities":
            result = [community("hive-100001"), community("hive-100002", title: "Spike Photos")]
        case "bridge.list_all_subscriptions":
            result = [["hive-100001", "Spike Community", "admin", ""]]
        case "bridge.list_subscribers":
            result = params["last"] == nil ? authors.map { [$0, $0 == "spike-bob" ? "member" : "guest", NSNull(), "2026-01-01T00:00:00"] } : []
        case "bridge.account_notifications":
            result = params["last_id"] == nil
                ? (1...3).map { ["id": "\(100 - $0)", "type": "subscribe", "msg": "@\(authors[$0]) subscribed to Spike Community",
                                 "date": "2026-10-01T10:0\($0):00", "score": 50, "url": "c/hive-100001"] as [String: Any] }
                : []
        case "condenser_api.lookup_accounts":
            let prefix = ((request["params"] as? [Any])?.first as? String) ?? ""
            result = authors.filter { $0 >= prefix }
        // ---- feed, posts and reading: synthetic answers for the menu, voters, reblogs and quick profile ----
        case "condenser_api.get_active_votes":
            result = activeVotes()
        case "condenser_api.get_reblogged_by":
            result = ["spike-bob", "spike-carol", "spike-dave"]
        // the proposal card: one votable @ecency proposal nobody voted for (as DemoHiveTransport on Android)
        case "database_api.list_proposals":
            result = ["proposals": [["id": 1, "proposal_id": 342, "creator": "ecency", "status": "active"]]]
        case "database_api.list_proposal_votes":
            result = ["proposal_votes": [Any]()]
        default:
            // Account lookups and chain state: refused, like an unreachable node.
            return HttpResult(status: 503, body: "fake transport: \(method) is not available offline")
        }
        let reply: [String: Any] = ["jsonrpc": "2.0", "result": result, "id": id]
        let json = (try? JSONSerialization.data(withJSONObject: reply)).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        return HttpResult(status: 200, body: json)
    }

    static func community(_ name: String, title: String = "Spike Community") -> [String: Any] {
        ["id": 1, "name": name, "title": title, "about": "Synthetic community for the native app's tests.", "lang": "en",
         "description": "A made-up community: nothing here is real content.", "flag_text": "Be kind.", "is_nsfw": false, "type_id": 1,
         "team": [[name, "owner", ""], ["spike-alice", "admin", ""], ["spike-carol", "mod", "Helper"]], "context": [String: Any](),
         "subscribers": 1200, "num_authors": 40, "num_pending": 280, "created_at": "2020-01-01 00:00:00"]
    }

    /// Eight synthetic votes, one of them a downvote.
    static func activeVotes() -> [[String: Any]] {
        var out: [[String: Any]] = []
        for i in 0..<8 {
            let suffix: String = i >= authors.count ? "-\(i)" : ""
            let voter: String = authors[i % authors.count] + suffix
            let rshares: Int = i == 7 ? -200 : (8 - i) * 1000
            let percent: Int = i == 7 ? -10000 : 10000 - i * 1000
            let time: String = "2026-10-0\(1 + i % 5)T12:00:00"
            out.append(["voter": voter, "rshares": rshares, "percent": percent, "time": time])
        }
        return out
    }

    /// The throwaway master password of every fake account (UI tests sign in with it; it controls nothing).
    static let password = "P5UiTestThrowawayPasswordNotReal"

    static func key(_ user: String, _ role: KeyRole) -> String {
        PrivateKey.companion.fromLogin(username: user, password: password, role: role).publicKey().description()
    }

    static func account(_ user: String) -> [String: Any] {
        func auth(_ role: KeyRole) -> [String: Any] { ["weight_threshold": 1, "account_auths": [], "key_auths": [[key(user, role), 1]]] }
        var fields: [String: Any] = ["name": user, "owner": auth(.owner), "active": auth(.active), "posting": auth(.posting), "memo_key": key(user, .memo),
                "json_metadata": "", "posting_json_metadata": metadata(user), "reputation": 0, "post_count": 1,
                "created": "2021-03-14T09:00:00", "vesting_shares": "1000.000000 VESTS", "delegated_vesting_shares": "0.000000 VESTS",
                "received_vesting_shares": "0.000000 VESTS", "vesting_withdraw_rate": "0.000000 VESTS", "to_withdraw": 0, "withdrawn": 0,
                "voting_manabar": ["current_mana": "873000000", "last_update_time": Int(Date().timeIntervalSince1970)]]
        // wallet balances, rewards and savings of the demo accounts
        for (key, value) in DemoWallet.shared.accountFields() { fields[key] = value.content }
        return fields
    }

    /// Synthetic profile metadata of a fake account.
    static func metadata(_ user: String) -> String {
        let name = user.replacingOccurrences(of: "spike-", with: "Spike ").capitalized
        let profile: [String: Any] = ["profile": ["name": name, "about": "Synthetic test account for the native app.",
                                                  "location": "Testnet", "website": "example.com", "version": 2,
                                                  "tokens": [["symbol": "LEO", "type": "ENGINE", "meta": ["show": true]]]]]
        return (try? JSONSerialization.data(withJSONObject: profile)).flatMap { String(data: $0, encoding: .utf8) } ?? ""
    }

    static func profile(_ user: String) -> [String: Any] {
        ["name": user, "created": "2021-03-14T09:00:00", "active": "2026-10-01T10:00:00", "post_count": 6, "reputation": 64.5,
         "stats": ["followers": 1234, "following": 56], "metadata": ["profile": ["name": user]]]
    }

    static func feed(tag: String? = nil, author: String? = nil) -> [[String: Any]] {
        (1...6).map { i in
            let a = author ?? authors[(i - 1) % authors.count]
            return entry(author: a, permlink: "fake-post-\(i)", title: "Fixture post number \(i)", children: 4, votes: i * 3)
        }
    }

    /// Root plus comments: two top-level threads, one nested past level 3 (shows "more replies").
    static func discussion(rootAuthor: String, rootPermlink: String) -> [String: [String: Any]] {
        let root = (rootAuthor, rootPermlink)
        var all: [[String: Any]] = [
            entry(author: rootAuthor, permlink: rootPermlink, title: "Fixture post", children: 5, votes: 9,
                  replies: ["spike-bob/re-c1", "spike-carol/re-c2"]),
            entry(author: "spike-bob", permlink: "re-c1", parent: root, depth: 1, body: "First fixture comment.",
                  replies: ["spike-dave/re-r1"]),
            entry(author: "spike-dave", permlink: "re-r1", parent: ("spike-bob", "re-c1"), depth: 2, body: "A reply at level two.",
                  replies: ["spike-erin/re-r2"]),
            entry(author: "spike-erin", permlink: "re-r2", parent: ("spike-dave", "re-r1"), depth: 3, body: "A reply at level three.",
                  replies: ["spike-frank/re-r3"]),
            entry(author: "spike-frank", permlink: "re-r3", parent: ("spike-erin", "re-r2"), depth: 4, body: "Hidden below level three."),
            entry(author: "spike-carol", permlink: "re-c2", parent: root, depth: 1, body: "Second fixture comment.", votes: 2),
        ]
        if rootAuthor == "spike-bob" && rootPermlink == "re-c1" { all.removeFirst() }
        var map: [String: [String: Any]] = [:]
        for e in all { map["\(e["author"]!)/\(e["permlink"]!)"] = e }
        return map
    }

    static func entry(
        author: String, permlink: String, title: String = "", parent: (String, String)? = nil, depth: Int = 0,
        body: String = FakeHiveTransport.body, children: Int? = nil, votes: Int = 0, replies: [String] = []
    ) -> [String: Any] {
        var e: [String: Any] = [
            "author": author, "permlink": permlink, "category": "hive-100001", "title": title, "body": body,
            "json_metadata": ["tags": ["hive-100001", "spike"], "app": "ecency/4.0.0-mobile", "format": "markdown+html"],
            "created": "2026-10-01T10:00:00", "updated": "2026-10-01T10:00:00", "depth": depth,
            "children": children ?? replies.count, "net_rshares": 1000, "author_reputation": 60.0, "payout": 1.234,
            "is_paidout": false, "payout_at": "2026-10-08T10:00:00", "pending_payout_value": "1.234 HBD",
            "author_payout_value": "0.000 HBD", "curator_payout_value": "0.000 HBD", "promoted": "0.000 HBD",
            "max_accepted_payout": "1000000.000 HBD", "percent_hbd": 10000, "replies": replies,
            "active_votes": (0..<votes).map { ["voter": "spike-voter\($0)", "rshares": 100] },
            "community": "hive-100001", "community_title": "Spike Community", "author_role": "guest", "author_title": "",
            "stats": ["hide": false, "gray": false, "total_votes": votes, "flag_weight": 0.0],
            "reblogs": 0, "beneficiaries": [], "blacklists": [], "url": "/hive-100001/@\(author)/\(permlink)",
        ]
        if let parent {
            e["parent_author"] = parent.0
            e["parent_permlink"] = parent.1
        }
        return e
    }
}

/// Debug-only Ecency REST answers for fake-data launches (edit history, translation, polls, bots, follow state);
/// anything else goes to [fallback]: the shared demo services, which answer the token endpoint, search and the
/// account's private API calls (bookmarks, favourites, followed tags).
final class FakeRestHttp: NSObject, AuthHttp {
    private let fallback: AuthHttp

    init(fallback: AuthHttp) { self.fallback = fallback }

    func send(request: AuthHttpRequest, completionHandler: @escaping (HttpResult?, Error?) -> Void) {
        if let body = Self.answer(request.url) {
            completionHandler(HttpResult(status: 200, body: body), nil)
        } else {
            fallback.send(request: request, completionHandler: completionHandler)
        }
    }

    private static func wave(author: String, permlink: String, body: String, extra: String = "") -> String {
        #"{"id":1,"author":"\#(author)","permlink":"\#(permlink)","title":"","body":"\#(body)","json_metadata":{"app":"ecency/4.0.0-mobile","tags":["waves"]},"timestamp":"2026-10-06T11:00:00+00:00","pending_payout_value":"0.250 HBD","net_votes":3,"children":2,"tip_count":0,"host":"ecency.waves","_cursor":"c-\#(permlink)"\#(extra)}"#
    }

    static func answer(_ url: String) -> String? {
        if url.hasSuffix("/private-api/comment-history") {
            return #"{"meta":{"count":2},"list":[{"v":1,"title":"Fixture post","body":"First version of the fixture body.","tags":["spike"],"timestamp":"2026-10-01T10:00:00"},{"v":2,"title":"Fixture post, edited","body":"Second version of the fixture body, now longer.","tags":["spike","native"],"timestamp":"2026-10-02T10:00:00"}]}"#
        }
        if url.hasSuffix("/private-api/public/bots") { return #"["spike-bot"]"# }
        if url.hasSuffix("/private-api/promoted-entries") { return "[]" }
        // waves and shorts (synthetic rows, shaped like the combined feed's)
        if url.contains("/private-api/promoted-entries?short_content") { return "[]" }
        if url.contains("/private-api/waves/trending/tags") { return #"[{"tag":"hive","posts":12},{"tag":"art","posts":7},{"tag":"photography","posts":3}]"# }
        if url.contains("/private-api/waves/shorts") {
            if url.contains("cursor=") { return "[]" }
            return "[" + ["clip-one", "clip-two", "clip-three"].enumerated().map { i, p in
                wave(author: i == 1 ? "spike-bob" : "spike-carol", permlink: p, body: "Short number \(i + 1) https://play.3speak.tv/embed?v=spike-carol/\(p)",
                     extra: #","video":{"platform":"3speak","author":"spike-carol","permlink":"\#(p)","embed_url":"https://play.3speak.tv/embed?v=spike-carol/\#(p)","thumbnail_url":null,"duration_secs":20}"#)
            }.joined(separator: ",") + "]"
        }
        if url.contains("/private-api/waves/feed") {
            if url.contains("cursor=") { return "[]" }
            if let range = url.range(of: "author=") {
                let author = String(url[range.upperBound...].prefix { $0 != "&" })
                return "[" + wave(author: author, permlink: "wave-own", body: "A wave on the author's own profile") + "]"
            }
            if url.contains("following=") { return "[" + wave(author: "spike-bob", permlink: "wave-following", body: "A wave from someone you follow") + "]" }
            if url.contains("tag=hive") { return "[" + wave(author: "spike-carol", permlink: "wave-tagged", body: "Only waves tagged #hive show here") + "]" }
            return "[" + [
                wave(author: "spike-carol", permlink: "wave-one", body: "Good morning from the waves feed #hive"),
                wave(author: "spike-bob", permlink: "wave-two", body: "Second wave with a [link](https://ecency.com/@spike-alice/fake-post-1) inside"),
                wave(author: "spike-carol", permlink: "wave-three", body: "Third wave, plain text only."),
            ].joined(separator: ",") + "]"
        }
        if url.contains("play.3speak.tv/api/embed") { return #"{"videoUrl":"https://play.3speak.tv/hls?u=fixture"}"# }
        if url.hasSuffix("/private-api/favorite-tags-check") { return "false" }
        if url.hasSuffix("/detect") { return #"[{"confidence":90,"language":"de"}]"# }
        if url.hasSuffix("/translate") { return #"{"translatedText":"Translated fixture text.","detectedLanguage":{"language":"de"}}"# }
        if url.contains("poll.ecency.com") { return "[]" }
        if url.contains("/search-api/similar") { return #"{"results":[]}"# }
        return nil
    }
}
#endif
