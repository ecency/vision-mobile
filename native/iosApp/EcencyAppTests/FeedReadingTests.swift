import EcencyCore
import XCTest
@testable import EcencyApp

/// Pure helpers of the feed, posts and reading screens: menu effect mapping, vote sheet text, edit history
/// formatting, the feed tabs over UserDefaults and the destinations of the new screens.
@MainActor
final class FeedReadingTests: XCTestCase {
    func testMenuEffectsMapToUiActions() {
        XCTAssertEqual(MenuEffectAction.of(PostMenuEffectCopy(text: "https://ecency.com/@alice/one", toast: "Copied!")),
                       .copy(text: "https://ecency.com/@alice/one", toast: "Copied!"))
        XCTAssertEqual(MenuEffectAction.of(PostMenuEffectShare(message: "Title url")), .share("Title url"))
        XCTAssertEqual(MenuEffectAction.of(PostMenuEffectLogin.shared), .login)
        XCTAssertEqual(MenuEffectAction.of(PostMenuEffectStarted.shared), .nothing)
        XCTAssertEqual(MenuEffectAction.of(PostMenuEffectOpen(screen: AppScreenEditHistory(author: "alice", permlink: "one"))),
                       .push(NavState.companion.encodeScreen(screen: AppScreenEditHistory(author: "alice", permlink: "one"))))
    }

    func testMenuItemTitlesAreUpperCasedAndDeleteIsRed() {
        XCTAssertEqual(PostMenuItem.theCopy.title, "COPY LINK")
        XCTAssertEqual(PostMenuItem.muteAuthorTitle, "MUTE / BLOCK")
        XCTAssertTrue(PostMenuItem.delete_.destructive)
        XCTAssertFalse(PostMenuItem.share.destructive)
    }

    func testVoteSheetValueText() {
        XCTAssertEqual(VoteSheetText.amount("$0.123", down: false), "$0.123")
        XCTAssertEqual(VoteSheetText.amount("$0.123", down: true), "-$0.123")
        XCTAssertEqual(VoteWeight.basisPoints(percent: 50, down: true), -5000)
    }

    func testHistoryDatesAndDiffRuns() {
        XCTAssertEqual(HistoryText.date("2026-10-02T10:00:00"), "October 2, 2026")
        XCTAssertEqual(HistoryText.date("2026-10-02T10:00:00.000Z"), "October 2, 2026")
        XCTAssertEqual(HistoryText.date("not a date"), "not a date")
        let runs = HistoryText.attributed([DiffSpan(kind: 0, text: "a "), DiffSpan(kind: -1, text: "old"), DiffSpan(kind: 1, text: "new")], text: .black)
        XCTAssertEqual(String(runs.characters), "a oldnew")
        XCTAssertEqual(runs.runs.count, 3)
    }

    func testFeedTabsPersistInUserDefaults() {
        let defaults = UserDefaults(suiteName: "feedx-tests")!
        defaults.removePersistentDomain(forName: "feedx-tests")
        let prefs = UserDefaultsPrefs(defaults: defaults)
        XCTAssertEqual(FeedTabs.shared.tabs(prefs: prefs, loggedIn: true).map(\.key), ["friends", "communities", "trending", "hot"])
        XCTAssertEqual(FeedTabs.shared.tabs(prefs: prefs, loggedIn: false).map(\.title), ["TRENDING", "HOT", "NEW"])
        XCTAssertEqual(FeedTabs.shared.save(prefs: prefs, tabs: [.hot]), "Please select 3 filters")
        XCTAssertNil(FeedTabs.shared.save(prefs: prefs, tabs: [.created, .hot, .blog]))
        XCTAssertEqual(FeedTabs.shared.tabs(prefs: prefs, loggedIn: true).map(\.key), ["created", "hot", "blog"])
    }

    func testQuickProfilePercent() {
        XCTAssertEqual(QuickProfileText.percent(87.26), "87.3%")
        XCTAssertEqual(QuickProfileText.percent(nil), "---")
    }

    func testNewScreensRoundTripThroughSavedNavigation() {
        for screen: AppScreen in [AppScreenVoters(author: "a", permlink: "p"), AppScreenReblogs(author: "a", permlink: "p"),
                                  AppScreenEditHistory(author: "a", permlink: "p"), AppScreenBotComments(author: "a", permlink: "p")] {
            let line = NavState.companion.encodeScreen(screen: screen)
            XCTAssertTrue((NavState.companion.decodeScreen(line: line) as? NSObject)?.isEqual(screen) == true, line)
            XCTAssertTrue(screen.route.built)
        }
    }

    func testVoteSheetStartsInDownvoteModeForADownvotedPost() {
        XCTAssertTrue(VoteWeight.startsDown(votedByMe: false, downvotedByMe: true))
        XCTAssertFalse(VoteWeight.startsDown(votedByMe: true, downvotedByMe: false))
        XCTAssertFalse(VoteWeight.startsDown(votedByMe: false, downvotedByMe: false))
    }

    func testFakeRestAnswersTheReadingServices() {
        XCTAssertNotNil(FakeRestHttp.answer("https://ecency.com/private-api/comment-history"))
        XCTAssertNotNil(FakeRestHttp.answer("https://translate.ecency.com/detect"))
        XCTAssertNil(FakeRestHttp.answer("https://ecency.com/auth-api/hs-token-refresh"))
    }
}

private extension PostMenuItem {
    static var muteAuthorTitle: String { PostMenuItem.mute.title }
}
