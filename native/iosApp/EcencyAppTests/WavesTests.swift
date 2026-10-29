import EcencyCore
import XCTest
@testable import EcencyApp

/// Pure helpers of the Waves screens and the shared waves pieces as Swift sees them.
@MainActor
final class WavesTests: XCTestCase {
    func testSourcesAndLinksBridge() {
        XCTAssertEqual(Waves.shared.SOURCE_OPTIONS.map(\.label), ["Shorts", "Waves", "Threads", "Snaps", "Moments", "Speaks"])
        XCTAssertEqual(Waves.shared.composeText(url: "https://ecency.com/waves?text=Hello%20world"), "Hello world")
        XCTAssertNil(Waves.shared.composeText(url: "https://example.com/waves?text=x"))
        XCTAssertEqual(ContentLinks.shared.entry(url: "https://ecency.com/waves")?.tab, .waves)
        let thread = ContentLinks.shared.entry(url: "https://ecency.com/waves/spike-alice/wave-1")?.screens.first as? AppScreenPost
        XCTAssertEqual(thread?.author, "spike-alice")
        XCTAssertEqual(thread?.permlink, "wave-1")
    }

    func testComposerStateDrivesTheSheet() {
        let empty = WaveComposeState(text: "", imageUrl: nil, isAttaching: false, isSending: false, error: nil, errorTitle: "", sent: nil, username: "spike-alice", prefillSeq: 0, clipboardChip: false, poll: nil, videoEmbedUrl: nil, videoThumbUrl: nil, isVideoUploading: false, authorityPrompt: false)
        XCTAssertEqual(empty.counter, "0/250")
        XCTAssertFalse(empty.showsShortHint)
        XCTAssertTrue(empty.canPublish)
        XCTAssertFalse(empty.publishEnabled, "nothing to publish: PUBLISH is off and looks it")
        let long = empty.doCopy(text: String(repeating: "x", count: 251), imageUrl: nil, isAttaching: false, isSending: false, error: nil, errorTitle: "", sent: nil, username: "spike-alice", prefillSeq: 0, clipboardChip: false, poll: nil, videoEmbedUrl: nil, videoThumbUrl: nil, isVideoUploading: false, authorityPrompt: false)
        XCTAssertTrue(long.lengthExceeded)
        XCTAssertFalse(long.canPublish)
        let short = empty.doCopy(text: "Thank you", imageUrl: nil, isAttaching: false, isSending: false, error: nil, errorTitle: "", sent: nil, username: "spike-alice", prefillSeq: 0, clipboardChip: false, poll: nil, videoEmbedUrl: nil, videoThumbUrl: nil, isVideoUploading: false, authorityPrompt: false)
        XCTAssertTrue(short.showsShortHint)
        XCTAssertTrue(short.publishEnabled)
        // RN quick post video: PUBLISH waits for the upload, and the video button is off with a video attached
        XCTAssertTrue(empty.canAddVideo)
        let uploading = empty.doCopy(text: "A short", imageUrl: nil, isAttaching: false, isSending: false, error: nil, errorTitle: "", sent: nil, username: "spike-alice", prefillSeq: 0, clipboardChip: false, poll: nil,
                                     videoEmbedUrl: nil, videoThumbUrl: nil, isVideoUploading: true, authorityPrompt: false)
        XCTAssertFalse(uploading.canPublish)
        XCTAssertFalse(uploading.canAddVideo)
        let attached = empty.doCopy(text: "A short", imageUrl: nil, isAttaching: false, isSending: false, error: nil, errorTitle: "", sent: nil, username: "spike-alice", prefillSeq: 0, clipboardChip: false, poll: nil,
                                    videoEmbedUrl: "https://play.3speak.tv/embed?v=spike-alice/vid123", videoThumbUrl: nil, isVideoUploading: false, authorityPrompt: false)
        XCTAssertTrue(attached.canPublish)
        XCTAssertFalse(attached.canAddVideo)
        XCTAssertEqual(WavePublish.shared.body(text: "A short", imageUrl: "https://images.ecency.com/a.png", videoEmbedUrl: attached.videoEmbedUrl),
                       "A short\n\nhttps://play.3speak.tv/embed?v=spike-alice/vid123")
        XCTAssertEqual(WavePublish.shared.videoLinkPermlink(body: "x https://play.3speak.tv/embed?v=spike-alice/vid123"), "vid123")
    }

    func testPagingAsksForMoreNearTheEndOnly() {
        let idle = WavesFeedState(items: [], isLoading: false, isRefreshing: false, isLoadingMore: false, endReached: false, error: nil, loaded: true, pages: 1, needsLogin: false)
        XCTAssertTrue(WavesPaging.canLoadMore(idle))
        let ended = WavesFeedState(items: [], isLoading: false, isRefreshing: false, isLoadingMore: false, endReached: true, error: nil, loaded: true, pages: 1, needsLogin: false)
        XCTAssertFalse(WavesPaging.canLoadMore(ended))
        XCTAssertFalse(WavesPaging.shouldLoadMore(appearing: PostKey(author: "a", permlink: "b"), in: idle))
    }

    func testReelCaptionAndPlaybackBridge() {
        XCTAssertEqual(ShortCaption.shared.of(title: "", body: "Clip ▶️ https://play.3speak.tv/embed?v=a/b"), "Clip")
        let state = ShortsState(items: [], isLoading: false, isRefreshing: false, isLoadingMore: false, endReached: false, error: nil, loaded: true, pages: 1,
                                activeKey: PostKey(author: "a", permlink: "b"), focused: false, playback: [:], muted: false)
        XCTAssertNil(state.playingKey, "nothing plays while the tab is not focused")
        XCTAssertTrue(state.playbackOf(key: PostKey(author: "a", permlink: "b")) is ShortPlaybackLoading)
    }
}
