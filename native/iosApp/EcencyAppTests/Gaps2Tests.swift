import EcencyCore
import Photos
import XCTest
@testable import EcencyApp

/// The parity gaps wave (gaps2) from Swift: the shared rules the views call, through the bridge (the rules themselves
/// are covered by the shared tests: CommentThreadsTest, PayoutDetailsTest, PostStatsTest...).
@MainActor
final class Gaps2Tests: XCTestCase {
    private func card(_ permlink: String) -> PostCard {
        PostCard(key: PostKey(author: "a", permlink: permlink), author: "a", avatarUrl: "", reputation: 50, title: "", summary: "", image: "https://img/\(permlink).png",
                 created: "2026-10-06T10:00:00", createdEpochMillis: 0, category: "", community: nil, communityTitle: nil, payout: 0, payoutText: "",
                 isPayoutDeclined: false, votes: 0, comments: 0, votedByMe: false, downvotedByMe: false, isPinned: false, isMuted: false,
                 rebloggedBy: [], crossPostAuthor: nil)
    }

    func testShowImagesOffHoldsBackOneImageAtATime() {
        let reveals = ImageReveals()
        XCTAssertTrue(reveals.hides(card: card("one"), showImages: false))
        XCTAssertFalse(reveals.hides(card: card("one"), showImages: true))
        reveals.reveal(url: "https://img/one.png")
        XCTAssertFalse(reveals.hides(card: card("one"), showImages: false))
        XCTAssertTrue(reveals.hides(card: card("two"), showImages: false))
    }

    func testScrollTopRuleShowsPastTheThresholdWhenScrollingUp() {
        var now: Int64 = 0
        let rule = ScrollTopRule(hidesOnScrollDown: false) { KotlinLong(value: now) }
        _ = rule.onScroll(offset: 9000)
        XCTAssertTrue(rule.onScroll(offset: 8000))
        rule.onTap()
        now = 500
        XCTAssertFalse(rule.onScroll(offset: 7000))
    }

    func testCopyAndFormatsTheViewsShow() {
        XCTAssertEqual(UiCopy.shared.preview(index: 2, count: 5), "Preview (2/5)")
        XCTAssertEqual(PostStatsModel.companion.abbreviated(n: 1534), "1.5K")
        XCTAssertEqual(BalanceAnalyticsModel.companion.compact(value: 1_030_000), "1.03M")
        XCTAssertEqual(ProfileWalletModel.companion.round3(v: 12.3456), "12.346")
        XCTAssertEqual(StatsCopy.shared.views(count: 1), "1 view")
        XCTAssertTrue(PostBadges.shared.headerFromEcency(app: "ecency/4.0.0"))
        XCTAssertFalse(PostBadges.shared.headerFromEcency(app: "notecency"))
        XCTAssertNil(LinkSheet.shared.exploreScreen(url: "mailto:a@b.co"))
        XCTAssertEqual([1, 2][safe: 5], nil)
    }

    /// `count` bytes delivered in 64 KB chunks (the last one shorter).
    private func stream(_ count: Int) -> AsyncStream<Data> {
        AsyncStream { c in
            var left = count
            while left > 0 {
                let n = min(left, 64 * 1024)
                c.yield(Data(repeating: 7, count: n))
                left -= n
            }
            c.finish()
        }
    }

    private func tempFile() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent("gaps2-\(UUID().uuidString)") }

    func testSavedImagesStreamUnderTheCapAndAPartialFileIsRemoved() async throws {
        let ok = tempFile()
        try await PhotoSaver.write(stream(100), expected: 100, to: ok, max: 100)
        XCTAssertEqual(try Data(contentsOf: ok).count, 100)
        try? FileManager.default.removeItem(at: ok)

        let over = tempFile()
        do {
            try await PhotoSaver.write(stream(101), expected: -1, to: over, max: 100)
            XCTFail("past the budget")
        } catch let e as PhotoSaver.SaveError {
            XCTAssertEqual(e, .tooLarge)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: over.path), "no partial file is left")

        let declared = tempFile()
        do {
            try await PhotoSaver.write(stream(1), expected: 101, to: declared, max: 100)
            XCTFail("declared length over the cap")
        } catch let e as PhotoSaver.SaveError {
            XCTAssertEqual(e, .tooLarge)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: declared.path), "refused before anything is written")
    }

    func testTheEcencyAlbumNeedsFullAccessAlreadyGranted() {
        XCTAssertTrue(PhotoSaver.albumAllowed(.authorized))
        XCTAssertFalse(PhotoSaver.albumAllowed(.limited))
        XCTAssertFalse(PhotoSaver.albumAllowed(.notDetermined), "never prompts for full access")
    }

    func testOnlyHttpsImagesAreSaved() async {
        let message = await PhotoSaver.save(URL(string: "http://example.com/a.png")!)
        XCTAssertEqual(message, UiCopy.shared.IMAGE_SAVE_ERROR)
    }

    func testSavedFilesAreNamedAndTypedSoPhotosAcceptsThem() {
        let url = URL(string: "https://images.ecency.com/p/abc?format=match")!
        XCTAssertEqual(PhotoSaver.fileExtension(contentType: "image/jpeg", url: url), "jpg")
        XCTAssertEqual(PhotoSaver.fileExtension(contentType: "image/png; charset=binary", url: url), "png")
        XCTAssertEqual(PhotoSaver.fileExtension(contentType: "image/svg+xml", url: url), "svg")
        XCTAssertEqual(PhotoSaver.fileExtension(contentType: "application/octet-stream", url: URL(string: "https://x.example/a/b.GIF")!), "gif")
        XCTAssertEqual(PhotoSaver.fileExtension(contentType: nil, url: URL(string: "https://x.example/noext")!), "jpg")
        XCTAssertEqual(PhotoSaver.uti(forExtension: "jpg"), "public.jpeg")
        XCTAssertEqual(PhotoSaver.uti(forExtension: "png"), "public.png")
        XCTAssertEqual(PhotoSaver.uti(forExtension: "gif"), "com.compuserve.gif")
        XCTAssertEqual(PhotoSaver.uti(forExtension: "zzzz"), "public.image")
    }
}
