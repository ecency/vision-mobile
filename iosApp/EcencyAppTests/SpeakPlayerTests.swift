import EcencyCore
import XCTest
@testable import EcencyApp

/// RN SpeakAudioPlayer behaviour with a scripted engine: lazy stream, seek, resume, one player at a time, errors.
@MainActor
final class SpeakPlayerTests: XCTestCase {
    final class FakeStream: SpeakStream {
        let url: String
        weak var events: SpeakEvents?
        var position = 0.0
        var seeks: [Double] = []
        var released = false
        init(url: String, events: SpeakEvents) { self.url = url; self.events = events }
        var positionSeconds: Double { position }
        func seek(_ seconds: Double) { seeks.append(seconds); position = seconds }
        func release() { released = true }
    }

    final class FakeEngine: SpeakEngine {
        var streams: [FakeStream] = []
        @MainActor func open(url: String, events: SpeakEvents) -> SpeakStream {
            events.loadStarted()
            let stream = FakeStream(url: url, events: events)
            streams.append(stream)
            return stream
        }
    }

    private func clip(_ url: String = "https://ecency.com/api/speak-audio?author=a&permlink=p") -> BlockSpeakAudio {
        BlockSpeakAudio(streamUrl: url, durationMs: 90_000, peaks: [0.2, 1, 0.5].map { KotlinDouble(value: $0) })
    }

    func testPlayLoadsLazilyPausesAndResumesWhereItWas() {
        let engine = FakeEngine()
        let player = SpeakPlayerController(block: clip(), engine: engine)
        XCTAssertEqual(player.state.timeLabel, "1:30")
        XCTAssertTrue(engine.streams.isEmpty, "nothing fetched before the first tap")

        player.togglePlay()
        XCTAssertEqual(engine.streams.count, 1, "loadStarted during open must not open a second stream")
        XCTAssertTrue(player.state.showSpinner)
        player.loaded(durationSeconds: 88)
        XCTAssertEqual(engine.streams[0].seeks, [0])
        XCTAssertFalse(player.state.showSpinner)
        engine.streams[0].position = 30
        player.tick()
        XCTAssertEqual(player.state.timeLabel, "0:30")

        player.togglePlay()
        XCTAssertTrue(engine.streams[0].released)
        XCTAssertFalse(player.hasStream)
        XCTAssertFalse(SpeakPlayback.shared.hasActive, "a paused player gives up the audio")
        player.seek(fraction: 0.5)
        XCTAssertEqual(player.state.currentTime, 44)

        player.togglePlay()
        XCTAssertEqual(engine.streams.count, 2)
        player.loaded(durationSeconds: 88)
        XCTAssertEqual(engine.streams[1].seeks, [44])

        player.ended()
        XCTAssertTrue(engine.streams[1].released)
        XCTAssertTrue(player.state.paused)
        XCTAssertEqual(player.state.timeLabel, "0:00")
    }

    func testWaveformTapBeforePlayingStartsAtThatSpot() {
        let engine = FakeEngine()
        let player = SpeakPlayerController(block: clip(), engine: engine)
        player.seek(fraction: 0.25)
        XCTAssertEqual(engine.streams.count, 1)
        player.loaded(durationSeconds: 90)
        XCTAssertEqual(engine.streams[0].seeks, [22.5])
    }

    func testStartingAnotherPlayerPausesTheFirst() {
        let engine = FakeEngine()
        let first = SpeakPlayerController(block: clip(), engine: engine)
        let second = SpeakPlayerController(block: clip("https://ecency.com/api/speak-audio?author=b&permlink=q"), engine: engine)
        first.togglePlay()
        second.togglePlay()
        XCTAssertTrue(first.state.paused)
        XCTAssertTrue(engine.streams[0].released)
        XCTAssertFalse(engine.streams[1].released)
        second.stop()
        XCTAssertTrue(engine.streams[1].released)
    }

    func testStreamErrorReturnsToATappablePausedState() {
        let engine = FakeEngine()
        let player = SpeakPlayerController(block: clip(), engine: engine)
        player.togglePlay()
        player.failed()
        XCTAssertTrue(engine.streams[0].released)
        XCTAssertFalse(player.state.started)
        XCTAssertFalse(player.state.showSpinner)
        XCTAssertEqual(player.state.timeLabel, "1:30")
    }

    func testNoStreamUrlNeverOpens() {
        let engine = FakeEngine()
        let player = SpeakPlayerController(block: clip(""), engine: engine)
        player.togglePlay()
        XCTAssertTrue(engine.streams.isEmpty)
        player.togglePlay()
    }

    func testWaveformTapWhilePlayingSeeksTheLiveStream() {
        let engine = FakeEngine()
        let player = SpeakPlayerController(block: clip(), engine: engine)
        player.togglePlay()
        player.loaded(durationSeconds: 90)
        player.seek(fraction: 0.5)
        XCTAssertEqual(engine.streams[0].seeks, [0, 45])
        XCTAssertEqual(engine.streams.count, 1)
        player.stop()
    }

    func testFailureWhileOpeningLeavesNoStreamBehind() {
        final class FailingEngine: SpeakEngine {
            var streams: [FakeStream] = []
            @MainActor func open(url: String, events: SpeakEvents) -> SpeakStream {
                let stream = FakeStream(url: url, events: events)
                streams.append(stream)
                events.loadStarted()
                events.failed()
                return stream
            }
        }
        let engine = FailingEngine()
        let player = SpeakPlayerController(block: clip(), engine: engine)
        player.togglePlay()
        XCTAssertEqual(engine.streams.count, 1)
        XCTAssertTrue(engine.streams[0].released)
        XCTAssertFalse(player.hasStream)
        XCTAssertFalse(player.state.started)
        XCTAssertFalse(SpeakPlayback.shared.hasActive)
    }

    func testScreenPlayersOutliveRowsAndStopTogether() {
        let engine = FakeEngine()
        let players = SpeakPlayers()
        let first = players.controller(for: clip(), engine: engine)
        // The row scrolls away and comes back: the same player, still playing.
        first.togglePlay()
        let again = players.controller(for: clip(), engine: engine)
        XCTAssertTrue(first === again)
        XCTAssertTrue(again.state.active)
        XCTAssertFalse(engine.streams[0].released)
        let other = players.controller(for: clip("https://ecency.com/api/speak-audio?author=b&permlink=q"), engine: engine)
        XCTAssertFalse(first === other)
        // Background or screen gone.
        players.stopAll()
        XCTAssertTrue(engine.streams[0].released)
        XCTAssertFalse(first.state.active)
        XCTAssertFalse(SpeakPlayback.shared.hasActive)
    }

    func testSystemInterruptionPausesThePlayer() {
        let engine = FakeEngine()
        let player = SpeakPlayerController(block: clip(), engine: engine)
        player.togglePlay()
        player.loaded(durationSeconds: 90)
        player.interrupted()
        XCTAssertTrue(player.state.paused)
        XCTAssertTrue(engine.streams[0].released)
        XCTAssertFalse(SpeakPlayback.shared.hasActive)
    }

    func testTheSameClipRenderedAgainTakesTheNewerData() {
        let engine = FakeEngine()
        let players = SpeakPlayers()
        let first = players.controller(for: clip(), engine: engine)
        XCTAssertEqual(first.state.timeLabel, "1:30")
        let newer = BlockSpeakAudio(streamUrl: clip().streamUrl, durationMs: 61_000, peaks: [KotlinDouble(value: 1)])
        let again = players.controller(for: newer, engine: engine)
        XCTAssertTrue(first === again)
        XCTAssertEqual(again.block.peaks.count, 1)
        XCTAssertEqual(again.state.timeLabel, "1:01")
        // While playing, newer data does not reset the clip.
        again.togglePlay()
        _ = players.controller(for: clip(), engine: engine)
        XCTAssertTrue(again.state.active)
        XCTAssertEqual(engine.streams.count, 1)
        again.stop()
    }

    func testProgressStopsWithTheStream() {
        let engine = FakeEngine()
        let player = SpeakPlayerController(block: clip(), engine: engine)
        player.togglePlay()
        player.loaded(durationSeconds: 90)
        engine.streams[0].position = 10
        player.tick()
        player.togglePlay()
        engine.streams[0].position = 50
        player.tick()
        XCTAssertEqual(player.state.timeLabel, "0:10")
    }
}
