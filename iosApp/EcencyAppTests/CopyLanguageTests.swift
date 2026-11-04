import EcencyCore
import XCTest
@testable import EcencyApp

/// The iOS screens' own copy and numbers follow the app language whenever they are read again (README
/// "Localization"): lists of labels are computed, iOS only texts come from the catalog, counts and percents go
/// through LocaleFormat rather than the device locale.
@MainActor
final class CopyLanguageTests: XCTestCase {
    override func tearDown() {
        L10n.shared.setLanguage(id: "en-US")
        super.tearDown()
    }

    private func card(votes: Int32, comments: Int32 = 2) -> PostCard {
        PostCard(key: PostKey(author: "alice", permlink: "p"), author: "alice", avatarUrl: "", reputation: 60, title: "Title",
                 summary: "Summary", image: nil, created: "2026-10-01T10:00:00", createdEpochMillis: 0, category: "photography",
                 community: nil, communityTitle: nil, payout: 1.5, payoutText: "$1.500",
                 isPayoutDeclined: false, votes: votes, comments: comments, votedByMe: false, downvotedByMe: false,
                 isPinned: true, isMuted: false, rebloggedBy: [], crossPostAuthor: nil)
    }

    func testListsOfLabelsAreReadAgainAfterASwitch() {
        L10n.shared.setLanguage(id: "en-US")
        XCTAssertEqual(CommentSortLabel.title(.reputation), "Reputation")
        L10n.shared.setLanguage(id: "ru-RU")
        XCTAssertEqual(CommentSortLabel.title(.reputation), "\u{0420}\u{0435}\u{043F}\u{0443}\u{0442}\u{0430}\u{0446}\u{0438}\u{044F}")
        XCTAssertEqual(CommentSortLabel.all.map(\.1), [ReadingUiCopy.shared.TOP, UiCopy.shared.REPUTATION, ReadingUiCopy.shared.VOTES, ReadingUiCopy.shared.AGE])
    }

    func testCountsAndPercentsFollowTheAppLanguage() {
        XCTAssertEqual(Fmt.int(1234), "1,234")
        XCTAssertEqual(Fmt.percent(-12), "-12%")
        L10n.shared.setLanguage(id: "de-DE")
        XCTAssertEqual(Fmt.int(1234), "1.234")
        XCTAssertEqual(Fmt.percent(12), "12\u{a0}%")
        let label = CardText.accessibilityLabel(card(votes: 1234))
        XCTAssertTrue(label.contains(L.Post.a11yVotes(count: 1234)), label)
        XCTAssertTrue(L.Post.a11yVotes(count: 1234).hasPrefix(Fmt.int(1234)), label)
        L10n.shared.setLanguage(id: "ar-SA")
        XCTAssertEqual(Fmt.int(12), "\u{0661}\u{0662}")
    }

    func testIosOnlyCopyKeepsNativesEnglishWithRealPlurals() {
        XCTAssertEqual(AppContainer.SignInAction.vote.message, "Sign in to vote.")
        XCTAssertEqual(AppContainer.SignInAction.reply.message, "Sign in to reply.")
        XCTAssertEqual(L.Native.Ios.Waves.repliesOpenThread(count: 1), "1 reply, open thread")
        XCTAssertEqual(L.Native.Ios.Waves.repliesOpenThread(count: 3), "3 replies, open thread")
        XCTAssertEqual(L.Native.Ios.Editor.words(count: 1), "1 word")
        let label = CardText.accessibilityLabel(card(votes: 1, comments: 1))
        XCTAssertTrue(label.hasPrefix("Pinned, Title, by alice, #photography"), label)
        XCTAssertTrue(label.contains("1 vote, 1 comment, payout $1.500"), label)
        XCTAssertEqual(DraftsScreen.shownDate("2026-10-01T10:00:00.000Z"), LocaleFormat.shared.dateTime(epochMillis: 1_790_848_800_000))
        XCTAssertNil(DraftsScreen.shownDate(""))
        // the server's zone-less ISO is UTC
        XCTAssertEqual(DraftsScreen.shownDate("2026-10-01T10:00:00"), LocaleFormat.shared.dateTime(epochMillis: 1_790_848_800_000))
    }
}
