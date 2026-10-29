import EcencyCore
import XCTest
@testable import EcencyApp

/// Navigation decisions and view-state mapping of the SwiftUI layer (pure Swift over shared types).
@MainActor
final class NavigationDecisionTests: XCTestCase {
    func testPostProfileAndFeedTargetsPushScreens() {
        XCTAssertEqual(NavDecision.from(LinkTargetPost(author: "alice", permlink: "hello-world")),
                       .push(.post(author: "alice", permlink: "hello-world")))
        XCTAssertEqual(NavDecision.from(LinkTargetAuthor(author: "alice")), .push(.profile(author: "alice")))
        XCTAssertEqual(NavDecision.from(LinkTargetTag(tag: "photography", filter: "hot")),
                       .push(.feed(tag: "photography", sort: "hot", title: "#photography")))
        XCTAssertEqual(NavDecision.from(LinkTargetCommunity(community: "hive-125125", filter: nil)),
                       .push(.feed(tag: "hive-125125", sort: "trending", title: "hive-125125")))
    }

    func testAnInPostJumpPushesNothingAndAnotherPostsFragmentOpensThatPost() {
        XCTAssertEqual(NavDecision.from(LinkTargetAnchor(id: "deutsch", post: nil)), .nothing)
        XCTAssertEqual(NavDecision.from(LinkTargetAnchor(id: "sec", post: LinkTargetPost(author: "bob", permlink: "other"))),
                       .push(.post(author: "bob", permlink: "other")))
        // the post screen's resolution: the block that holds the id, on this post only
        let blocks = BlockBuilder.shared.build(html: "<p>intro</p><p><a id=\"far\"></a>there</p><h2>Third part</h2>")
        let here = LinkTargetPost(author: "alice", permlink: "p")
        XCTAssertEqual(BlockAnchors.shared.jump(blocks: blocks, target: LinkTargetAnchor(id: "far", post: nil), current: here)?.intValue, 1)
        XCTAssertTrue(blocks[1] is BlockAnchor)
        XCTAssertEqual(BlockAnchors.shared.jump(blocks: blocks, target: LinkTargetAnchor(id: "third-part", post: here), current: here)?.intValue, 3)
        XCTAssertNil(BlockAnchors.shared.jump(blocks: blocks, target: LinkTargetAnchor(id: "far", post: LinkTargetPost(author: "bob", permlink: "x")), current: here))
    }

    func testWebLinksOpenTheBrowserOnlyForHttpAndHttps() {
        XCTAssertEqual(NavDecision.from(LinkTargetExternal(url: "https://example.com/a?b=1")),
                       .browser(URL(string: "https://example.com/a?b=1")!))
        XCTAssertEqual(NavDecision.from(LinkTargetExternal(url: "http://example.com")), .browser(URL(string: "http://example.com")!))
        for bad in ["javascript:alert(1)", "data:text/html,hi", "//example.com", "file:///etc/passwd", "ftp://example.com"] {
            guard case .unsupported = NavDecision.from(LinkTargetExternal(url: bad)) else {
                return XCTFail("\(bad) must not open")
            }
        }
        // Defence in depth on the Swift side too.
        XCTAssertNil(NavDecision.webURL("javascript:alert(1)"))
        XCTAssertNil(NavDecision.webURL("https://"))
    }

    func testVideosOpenTheSheetOnAllowlistedHttpsEmbedsOnly() {
        guard case let .video(url) = NavDecision.from(LinkTargetYoutube(videoId: "aaaaaaaaaaa", startSeconds: 30)) else {
            return XCTFail("youtube must open the player")
        }
        XCTAssertEqual(url.host, "www.youtube.com")
        XCTAssertTrue(url.absoluteString.contains("start=30"))
        guard case .unsupported = NavDecision.from(LinkTargetVideo(embedUrl: "https://evil.example/embed")) else {
            return XCTFail("off-list embed must not play")
        }
        guard case .unsupported = NavDecision.from(LinkTargetYoutube(videoId: "bad id", startSeconds: 0)) else {
            return XCTFail("invalid id must not play")
        }
    }

    func testAppUrisGoToTheLinkModelOnly() {
        // RN postInteractionHandler: hive:// and ecency:// go to the link processor, which asks before signing
        XCTAssertEqual(NavDecision.from(LinkTargetAppUri(uri: "hive://sign/op/abc")), .appLink("hive://sign/op/abc"))
        XCTAssertEqual(NavDecision.from(LinkTargetAppUri(uri: "ecency://transfer?to=x")), .appLink("ecency://transfer?to=x"))
        guard case .unsupported = NavDecision.from(LinkTargetAppUri(uri: "javascript://alert(1)")) else {
            return XCTFail("only hive:// and ecency:// reach the link model")
        }
    }

    func testDestinationsMapToFeedSources() {
        let tag = Destination.feed(tag: "hive-125125", sort: "created", title: "x").feedSource as? FeedSourceRanked
        XCTAssertEqual(tag?.tag, "hive-125125")
        XCTAssertEqual(tag?.sort, RankedSort.created)
        let blog = Destination.profile(author: "alice").feedSource as? FeedSourceAccount
        XCTAssertEqual(blog?.account, "alice")
        XCTAssertEqual(blog?.sort, AccountPostsSort.blog)
        XCTAssertNil(Destination.post(author: "a", permlink: "b").feedSource)
    }

    func testVideoSheetAllowsOnlyTheEmbedHost() {
        let embed = URL(string: "https://www.youtube.com/embed/aaaaaaaaaaa?autoplay=1")!
        XCTAssertTrue(VideoEmbedPolicy.allows(URL(string: "https://www.youtube.com/embed/other"), embed: embed))
        XCTAssertTrue(VideoEmbedPolicy.allows(URL(string: "https://WWW.YOUTUBE.COM/x"), embed: embed))
        XCTAssertTrue(VideoEmbedPolicy.allows(URL(string: "about:blank"), embed: embed))
        XCTAssertTrue(VideoEmbedPolicy.allows(URL(string: "https://youtube.com/embed/x"), embed: embed), "same site without www (redirects)")
        XCTAssertFalse(VideoEmbedPolicy.allows(URL(string: "https://m.youtube.com/watch?v=x"), embed: embed), "other host")
        XCTAssertFalse(VideoEmbedPolicy.allows(URL(string: "https://evilyoutube.com/x"), embed: embed), "other host")
        XCTAssertFalse(VideoEmbedPolicy.allows(URL(string: "http://www.youtube.com/embed/x"), embed: embed), "not https")
        XCTAssertFalse(VideoEmbedPolicy.allows(URL(string: "https://evil.example/"), embed: embed))
        XCTAssertFalse(VideoEmbedPolicy.allows(URL(string: "javascript:alert(1)"), embed: embed))
        XCTAssertFalse(VideoEmbedPolicy.allows(URL(string: "about:srcdoc#x"), embed: embed))
        XCTAssertFalse(VideoEmbedPolicy.allows(nil, embed: embed))
    }
}

@MainActor
final class ViewStateMappingTests: XCTestCase {
    private func card(votes: Int32 = 3, comments: Int32 = 1, community: String? = "Test Community",
                      voted: Bool = false, declined: Bool = false, reblogged: [String] = []) -> PostCard {
        PostCard(key: PostKey(author: "alice", permlink: "p"), author: "alice", avatarUrl: "", reputation: 60, title: "Title",
                 summary: "Summary", image: nil, created: "2026-10-01T10:00:00", createdEpochMillis: 0, category: "photography",
                 community: community == nil ? nil : "hive-1", communityTitle: community, payout: 1.5, payoutText: "$1.500",
                 isPayoutDeclined: declined, votes: votes, comments: comments, votedByMe: voted, downvotedByMe: false,
                 isPinned: false, isMuted: false, rebloggedBy: reblogged, crossPostAuthor: nil)
    }

    func testVoteButtonStatePrefersInFlightThenErrorThenServerVote() {
        let key = PostKey(author: "a", permlink: "b")
        XCTAssertEqual(VoteButtonState.of(votedByMe: false, downvotedByMe: false, status: nil), .idle)
        XCTAssertEqual(VoteButtonState.of(votedByMe: true, downvotedByMe: false, status: nil), .upvoted)
        XCTAssertEqual(VoteButtonState.of(votedByMe: false, downvotedByMe: true, status: nil), .downvoted)
        XCTAssertEqual(VoteButtonState.of(votedByMe: true, downvotedByMe: false,
                                          status: VoteStatus(key: key, weight: 10000, inFlight: true, error: nil)), .inFlight)
        let error = AppError(kind: .network, message: "Network error", retryable: true)
        XCTAssertEqual(VoteButtonState.of(votedByMe: false, downvotedByMe: false,
                                          status: VoteStatus(key: key, weight: 10000, inFlight: false, error: error)),
                       .failed(message: "Network error", retryable: true))
    }

    func testVoteWeightsAreBasisPointsClampedToOneToHundredPercent() {
        XCTAssertEqual(VoteWeight.basisPoints(percent: 100, down: false), 10000)
        XCTAssertEqual(VoteWeight.basisPoints(percent: 37.4, down: false), 3700)
        XCTAssertEqual(VoteWeight.basisPoints(percent: 50, down: true), -5000)
        XCTAssertEqual(VoteWeight.basisPoints(percent: 0, down: false), 100)
        XCTAssertEqual(VoteWeight.basisPoints(percent: 250, down: true), -10000)
    }

    func testCardTextUsesCommunityTitleElseTagAndSpeaksTheWholeCard() {
        XCTAssertEqual(CardText.category(card()), "Test Community")
        XCTAssertEqual(CardText.category(card(community: nil)), "#photography")
        let label = CardText.accessibilityLabel(card(votes: 1, comments: 2, declined: true, reblogged: ["bob"]))
        XCTAssertTrue(label.hasPrefix("Reblogged by bob, Title, by alice"), label)
        XCTAssertTrue(label.contains("1 vote,"), label)
        XCTAssertTrue(label.contains("2 comments"), label)
        XCTAssertTrue(label.contains("payout $1.500, declined"), label)
    }

    func testFeedLoadsMoreNearTheEndOnlyWhenIdle() {
        let cards = (0..<10).map { i in
            PostCard(key: PostKey(author: "a", permlink: "p\(i)"), author: "a", avatarUrl: "", reputation: 25, title: "\(i)",
                     summary: "", image: nil, created: "", createdEpochMillis: 0, category: "c", community: nil, communityTitle: nil,
                     payout: 0, payoutText: "$0.000", isPayoutDeclined: false, votes: 0, comments: 0, votedByMe: false,
                     downvotedByMe: false, isPinned: false, isMuted: false, rebloggedBy: [], crossPostAuthor: nil)
        }
        let idle = FeedState(items: cards, isLoading: false, isRefreshing: false, isLoadingMore: false, endReached: false, error: nil, loaded: true, pages: 1)
        XCTAssertFalse(FeedPaging.shouldLoadMore(appearing: cards[5], in: idle))
        XCTAssertTrue(FeedPaging.shouldLoadMore(appearing: cards[7], in: idle))
        XCTAssertTrue(FeedPaging.shouldLoadMore(appearing: cards[9], in: idle))
        let loading = idle.doCopy(items: cards, isLoading: false, isRefreshing: false, isLoadingMore: true, endReached: false, error: nil, loaded: true, pages: 1)
        XCTAssertFalse(FeedPaging.shouldLoadMore(appearing: cards[9], in: loading))
        let end = idle.doCopy(items: cards, isLoading: false, isRefreshing: false, isLoadingMore: false, endReached: true, error: nil, loaded: true, pages: 1)
        XCTAssertFalse(FeedPaging.shouldLoadMore(appearing: cards[9], in: end))
        let failed = idle.doCopy(items: cards, isLoading: false, isRefreshing: false, isLoadingMore: false, endReached: false,
                                 error: AppError(kind: .network, message: "x", retryable: true), loaded: true, pages: 1)
        XCTAssertFalse(FeedPaging.shouldLoadMore(appearing: cards[9], in: failed), "errors wait for an explicit retry")
        XCTAssertFalse(FeedPaging.shouldLoadMoreAtEnd(failed))
        XCTAssertTrue(FeedPaging.shouldLoadMoreAtEnd(idle), "the end on screen asks again even when the last page added nothing")

        // The row on screen can be an older copy of the card (a vote changed it since): matched by key.
        let voted = PostCard(key: cards[8].key, author: "a", avatarUrl: "", reputation: 25, title: "8", summary: "", image: nil,
                            created: "", createdEpochMillis: 0, category: "c", community: nil, communityTitle: nil, payout: 0,
                                    payoutText: "$0.000", isPayoutDeclined: false, votes: 1, comments: 0, votedByMe: true,
                                    downvotedByMe: false, isPinned: false, isMuted: false, rebloggedBy: [], crossPostAuthor: nil)
        XCTAssertNotEqual(voted, cards[8])
        XCTAssertTrue(FeedPaging.shouldLoadMore(appearing: voted, in: idle))
    }

    func testCommentIndentStopsAtLevelThreeAndCardImagesNeverExceedTheScreen() {
        XCTAssertEqual(CommentRow.indent(level: 1), 0)
        XCTAssertEqual(CommentRow.indent(level: 2), 22)
        XCTAssertEqual(CommentRow.indent(level: 3), 44)
        XCTAssertEqual(CommentRow.indent(level: 7), 44)
        XCTAssertEqual(CardImageView.height(width: 384, ratio: 16.0 / 9.0, screenHeight: 800), 216)
        XCTAssertEqual(CardImageView.height(width: 384, ratio: 0.1, screenHeight: 800), 800)
        XCTAssertEqual(CardImageView.height(width: 384, ratio: .nan, screenHeight: 800), 216)
        XCTAssertEqual(CommentSortLabel.title(.votes), "Votes")
    }
}

/// The shared models end to end over the debug fake transport (offline, synthetic fixtures).
@MainActor
final class FakeDataModelTests: XCTestCase {
    private var env: AppEnvironment!
    private var watchers: [Closeable] = []

    override func setUp() async throws {
        let store = KeychainSecureStore(service: "app.esteem.mobile.ios.tests.applayer")
        try store.wipe()
        env = AppEnvironment(
            api: try EcencyApi(nodes: ["https://fake-hive.invalid"], transport: FakeHiveTransport()),
            vault: KeyVaultAsync(vault: KeyVault(store: store)),
            renderer: PostBodyRendererCompanion.shared.`default`(proxyBase: ImageProxy.companion.DEFAULT_BASE),
            dispatchers: AppDispatchers.companion.platform(),
            proxyBase: ImageProxy.companion.DEFAULT_BASE,
            debugKeyImport: true,
            nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
            security: SecurityConfig.companion.DEFAULT
        )
    }

    override func tearDown() {
        watchers.forEach { $0.close() }
        watchers = []
    }

    /// Waits until the model's state passes `condition`.
    private func wait<S: AnyObject>(_ model: StateModel<S>, timeout: TimeInterval = 10, _ condition: @escaping (S) -> Bool) -> S? {
        let done = expectation(description: "state")
        var result: S?
        watchers.append(model.watch { s in
            if result == nil, condition(s) { result = s; done.fulfill() }
        })
        wait(for: [done], timeout: timeout)
        return result
    }

    func testFeedLoadsCardsAndEndsAfterTheFixturePage() {
        let feed = env.feedModel(source: FeedSourceRanked(sort: .trending, tag: ""))
        feed.start()
        let state = wait(feed) { $0.loaded }
        XCTAssertEqual(state?.items.count, 6)
        XCTAssertEqual(state?.items.first?.title, "Fixture post number 1")
        XCTAssertEqual(state?.items.first?.communityTitle, "Spike Community")
        XCTAssertFalse(state?.items.first?.summary.isEmpty ?? true)
        feed.loadMore()
        XCTAssertNotNil(wait(feed) { $0.endReached })
        feed.close()
    }

    /// Rows are identified by key (ForEach(id: \.key)): a vote changes the card's value but not its row,
    /// and the other cards keep their instances, so SwiftUI updates one row in place.
    func testAVoteKeepsRowIdentity() {
        let feed = env.feedModel(source: FeedSourceRanked(sort: .trending, tag: ""))
        feed.start()
        guard let before = wait(feed, { $0.loaded })?.items, before.count > 1 else { return XCTFail("no cards") }
        let target = before[0]
        env.localVotes.set(key: target.key, vote: LocalVote(weight: 10000, pending: true))
        guard let after = wait(feed, { $0.items.first?.votedByMe == true })?.items else { return XCTFail("vote not shown") }
        XCTAssertNotEqual(after[0], target, "the card's value changed: identity by value would replace the row")
        XCTAssertEqual(after.map(\.key), before.map(\.key), "row identities are unchanged")
        XCTAssertTrue(after[1] === before[1], "untouched cards keep their instances")
        feed.close()
    }

    func testPostRendersItsBodyAndNestsCommentsToLevelThree() {
        let post = env.postModel(author: "spike-alice", permlink: "fake-post-1")
        post.start()
        let state = wait(post) { $0.body != nil && !$0.comments.isEmpty }
        XCTAssertFalse(state?.body?.isEmpty ?? true)
        let levels = state?.comments.map { $0.level } ?? []
        XCTAssertEqual(levels.max(), 3)
        XCTAssertTrue(state?.comments.contains { $0.hasHiddenReplies } ?? false, "the level 4 reply is behind more replies")
        // Every body link resolves to a decision: the fixture's external link opens the browser.
        let decisions = (state?.body ?? []).flatMap(links).map(NavDecision.from)
        XCTAssertTrue(decisions.contains(.browser(URL(string: "https://example.com/spike")!)), "\(decisions)")
        post.close()
    }

    func testVoteWhileSignedOutRollsBackWithAnAuthError() {
        let votes = env.votes
        votes.vote(author: "spike-alice", permlink: "fake-post-1", weight: 10000)
        let state = wait(votes) { $0.status(author: "spike-alice", permlink: "fake-post-1")?.error != nil }
        XCTAssertEqual(state?.status(author: "spike-alice", permlink: "fake-post-1")?.error?.kind, .auth)
        XCTAssertNil(env.localVotes.get(key: PostKey(author: "spike-alice", permlink: "fake-post-1")), "optimistic vote rolled back")
        XCTAssertEqual(VoteButtonState.of(votedByMe: false, downvotedByMe: false,
                                          status: state?.status(author: "spike-alice", permlink: "fake-post-1")),
                       .failed(message: "Please sign in first.", retryable: false))
    }

    func testFailedReplyKeepsTheDraftText() {
        let post = env.postModel(author: "spike-alice", permlink: "fake-post-1")
        post.start()
        guard let parent = wait(post, { $0.post != nil })?.post else { return XCTFail("no post") }
        let reply = env.replyModel(root: post.key, parent: parent)
        reply.setText(text: "Nice post")
        reply.send()
        let state = wait(reply) { $0.error != nil }
        XCTAssertEqual(state?.error?.kind, .auth)
        XCTAssertEqual(state?.text, "Nice post", "the draft stays until a success")
        reply.close()
        post.close()
    }

    func testKeyLoginShowsRnCopyForAWrongPassword() async {
        do {
            _ = try await env.session.loginWithKey(username: "spike-alice", secret: "not the password")
            XCTFail("must throw")
        } catch {
            XCTAssertEqual(ErrorText.message(error), AppError.companion.INVALID_CREDENTIALS)
        }
        XCTAssertNil(env.session.current.username)
    }

    func testOfflineTokenEndpointFailsTheLoginWithoutStoring() async throws {
        do {
            _ = try await env.session.loginWithKey(username: "spike-alice", secret: FakeHiveTransport.password)
            XCTFail("must throw: the default auth services are offline")
        } catch {
            XCTAssertEqual(ErrorText.appError(error)?.kind, .network)
        }
        let remaining = try await env.accounts.accounts().count
        XCTAssertEqual(remaining, 0)
    }

    private func links(_ block: Block) -> [LinkTarget] {
        guard let p = block as? BlockParagraph else { return [] }
        return p.inlines.compactMap { ($0 as? InlineLink)?.target }
    }
}
