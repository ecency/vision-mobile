import EcencyCore
import XCTest
@testable import EcencyApp

/// RN Listen on iOS: the player around the shared state with a scripted engine (play, speeds, languages, the lock
/// screen, renewed URLs), one audio source app-wide with the 3Speak voice players, the screen owning the audio, and
/// the bar's confirmed unlock over the shared model (demo answers: nothing is charged).
@MainActor
final class ListenTests: XCTestCase {
    final class FakeStream: ListenStream {
        var item: ListenItem
        var loads: [String] = []
        weak var events: ListenEvents?
        var position = 0.0
        var playing = false
        var speed = 1.0
        var seeks: [Double] = []
        var released = false
        init(item: ListenItem, events: ListenEvents) { self.item = item; self.events = events }
        var positionSeconds: Double { position }
        func play() { playing = true }
        func pause() { playing = false }
        func seek(_ seconds: Double) { seeks.append(seconds); position = seconds }
        func setSpeed(_ speed: Double) { self.speed = speed }
        var retitles = 0
        func retitle(_ item: ListenItem) { self.item = item; retitles += 1 }
        func load(_ item: ListenItem) {
            self.item = item
            loads.append(item.url)
            playing = false
            position = 0
            seeks = []
            events?.loadStarted()
        }
        func release() { released = true; playing = false }
    }

    final class FakeEngine: ListenEngine {
        var streams: [FakeStream] = []
        @MainActor func open(item: ListenItem, events: ListenEvents) -> ListenStream {
            events.loadStarted()
            let stream = FakeStream(item: item, events: events)
            streams.append(stream)
            return stream
        }
    }

    private var env: AppEnvironment!

    override func setUp() async throws {
        let store = KeychainSecureStore(service: "app.esteem.mobile.ios.tests.listen")
        try store.wipe()
        let vault = KeyVault(store: store)
        try vault.save(account: VaultAccount(
            username: "spike-alice", authType: .postingKey, postingKey: PrivateKey.companion.fromSeed(seed: "ecency-native-listen-test-throwaway").toWif(), activeKey: nil,
            memoKey: nil, accessToken: "demo-access-spike-alice", hiveAuthKey: nil, hiveAuthToken: nil, hiveAuthExpiry: 0
        ))
        try vault.setCurrentUsername(username: "spike-alice")
        env = AppEnvironment(
            api: try EcencyApi(nodes: ["https://fake-hive.invalid"], transport: FakeHiveTransport()),
            vault: KeyVaultAsync(vault: vault),
            renderer: PostBodyRendererCompanion.shared.`default`(proxyBase: ImageProxy.companion.DEFAULT_BASE),
            dispatchers: AppDispatchers.companion.platform(),
            proxyBase: ImageProxy.companion.DEFAULT_BASE,
            debugKeyImport: true,
            nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
            security: SecurityConfig(pinIterations: 16, lockTimeoutMillis: 60_000, monotonicMillis: { KotlinLong(value: 0) }),
            legacy: nil,
            auth: DemoAuthServices.shared.create(nowMillis: { KotlinLong(value: 0) }, waitMillis: 1000)
        )
        env.session.start()
        waitUntil { self.env.session.current.username == "spike-alice" }
    }

    override func tearDown() async throws {
        // the coordinator is process-wide: whatever a test left playing is stopped and forgotten
        let last = SpeakPlayback.Token {}
        SpeakPlayback.shared.activate(token: last)
        SpeakPlayback.shared.deactivate(token: last)
    }

    private func waitUntil(_ timeout: TimeInterval = 10, _ condition: () -> Bool) {
        let end = Date().addingTimeInterval(timeout)
        while !condition() && Date() < end { RunLoop.main.run(until: Date().addingTimeInterval(0.05)) }
    }

    /// The post screen's Listen for a demo post, started with the post the screen loaded.
    private func screen(_ permlink: String, engine: FakeEngine) -> ListenScreen {
        let post = Observed(env.postModel(author: "spike-alice", permlink: permlink), ownsModel: true)
        (post.model as! PostModel).start()
        waitUntil { post.state.post != nil }
        let screen = ListenScreen(key: PostKey(author: "spike-alice", permlink: permlink), env: env, uiLanguage: "en-US", engine: engine)
        screen.model.start(post: post.state.post!)
        return screen
    }

    func testAnUnlockedPostPlaysAndTheStreamFollowsSpeedsAndLanguages() {
        let engine = FakeEngine()
        let listen = screen("fake-post-1", engine: engine)
        waitUntil { listen.player.state != nil }
        let player = listen.player
        XCTAssertEqual(player.tracks.map(\.lang), ["en", "de"])
        XCTAssertEqual(player.state?.durationLabel, "2:48")
        XCTAssertTrue(engine.streams.isEmpty, "nothing loads before the first play")

        player.toggle()
        XCTAssertEqual(engine.streams.count, 1)
        let stream = engine.streams[0]
        XCTAssertEqual(stream.item.url, "https://audio.invalid/demo/fake-post-1-en.mp3")
        XCTAssertEqual(stream.item.artist, "@spike-alice")
        XCTAssertTrue(stream.playing)
        XCTAssertTrue(SpeakPlayback.shared.hasActive)
        player.loaded(durationSeconds: 170)
        XCTAssertEqual(stream.seeks, [], "no place to go back to yet")
        stream.position = 42
        player.tick()
        XCTAssertEqual(player.state?.timeLabel, "0:42")

        player.nextSpeed()
        XCTAssertEqual(player.state?.speedLabel, "1.25x")
        XCTAssertEqual(stream.speed, 1.25)

        // the lock screen paused it, then played it again (the system player does it, then reports it)
        stream.playing = false
        player.playingChanged(false)
        XCTAssertEqual(player.state?.paused, true)
        XCTAssertFalse(SpeakPlayback.shared.hasActive)
        player.playingChanged(true)
        XCTAssertEqual(player.state?.paused, false)
        XCTAssertFalse(stream.playing, "the player already plays: nothing is sent back to it")
        XCTAssertTrue(SpeakPlayback.shared.hasActive)

        player.switchTrack("de")
        XCTAssertEqual(engine.streams.count, 1, "the same player takes the other language")
        XCTAssertFalse(stream.released)
        XCTAssertEqual(stream.loads, ["https://audio.invalid/demo/fake-post-1-de.mp3"])
        XCTAssertEqual(stream.speed, 1.25)
        player.loaded(durationSeconds: 44)
        XCTAssertEqual(stream.seeks, [0])
    }

    func testAnExpiredUrlIsRenewedAndPickedUpWhereItFailed() {
        let engine = FakeEngine()
        let listen = screen("fake-post-1", engine: engine)
        waitUntil { listen.player.state != nil }
        let player = listen.player
        player.toggle()
        player.loaded(durationSeconds: 168)
        engine.streams[0].position = 30
        player.tick()
        player.failed()
        let stream = engine.streams[0]
        XCTAssertFalse(stream.released, "the stream and its session stay through the renewal")
        waitUntil { stream.loads.count == 1 }
        XCTAssertEqual(stream.loads.count, 1, "renewed through the status, into the same player")
        XCTAssertEqual(engine.streams.count, 1)
        XCTAssertTrue(stream.playing)
        player.loaded(durationSeconds: 168)
        XCTAssertEqual(stream.seeks, [30])
    }

    func testOneAudioSourceAppWideWithTheVoicePlayers() {
        let engine = FakeEngine()
        let listen = screen("fake-post-1", engine: engine)
        waitUntil { listen.player.state != nil }
        listen.player.toggle()
        XCTAssertTrue(engine.streams[0].playing)

        let speakEngine = SpeakPlayerTests.FakeEngine()
        let voice = SpeakPlayerController(
            block: BlockSpeakAudio(streamUrl: "https://ecency.com/api/speak-audio?author=a&permlink=p", durationMs: 90_000, peaks: []),
            engine: speakEngine
        )
        voice.togglePlay()
        XCTAssertFalse(engine.streams[0].playing, "the voice post took over")
        XCTAssertEqual(listen.player.state?.paused, true)
        listen.player.toggle()
        XCTAssertTrue(engine.streams[0].playing)
        XCTAssertTrue(voice.state.paused, "and Listen takes it back")
        XCTAssertTrue(speakEngine.streams[0].released)
    }

    func testTheAudioStopsWhenThePostScreenGoesAway() {
        let engine = FakeEngine()
        var listen: ListenScreen? = screen("fake-post-1", engine: engine)
        waitUntil { listen?.player.state != nil }
        listen?.player.toggle()
        XCTAssertTrue(engine.streams[0].playing)
        listen = nil
        XCTAssertTrue(engine.streams[0].released)
        XCTAssertFalse(SpeakPlayback.shared.hasActive)
    }

    // README "Background media": the lock screen card, the teardown with the account and the 15 s skips

    func testTheLockScreenCardIsTheSharedCardWithTheTitleAuthorAndArtwork() {
        let engine = FakeEngine()
        let listen = screen("fake-post-1", engine: engine)
        waitUntil { listen.player.state != nil }
        listen.player.toggle()
        let item = engine.streams[0].item
        let card = env.nowPlaying(model: listen.model)
        XCTAssertEqual(item.title, card.title)
        XCTAssertEqual(item.artist, "@spike-alice")
        XCTAssertEqual(item.artwork, card.artworkUrl)
        XCTAssertNotNil(item.artwork, "no PIN lock here: the post image or the avatar")
        XCTAssertTrue(item.artwork?.hasPrefix("https://") ?? false)
    }

    func testTurningThePinLockOnDropsTheArtworkWhilePlaying() {
        env.appLock.start()
        waitUntil { self.env.appLock.current.loaded }
        let engine = FakeEngine()
        let listen = screen("fake-post-1", engine: engine)
        waitUntil { listen.player.state != nil }
        listen.player.toggle()
        let stream = engine.streams[0]
        XCTAssertNotNil(stream.item.artwork)
        env.appLock.startEnable(biometricAvailable: false)
        waitUntil { self.env.appLock.current.prompt?.step.name == "NEW" }
        for d in [1, 2, 3, 4] { env.appLock.enterDigit(digit: Int32(d)) }
        waitUntil { self.env.appLock.current.prompt?.step.name == "CONFIRM" }
        for d in [1, 2, 3, 4] { env.appLock.enterDigit(digit: Int32(d)) }
        waitUntil { self.env.appLock.current.enabled }
        waitUntil { stream.item.artwork == nil }
        XCTAssertNil(stream.item.artwork, "a locked phone shows only the title and the author")
        XCTAssertEqual(stream.item.artist, "@spike-alice")
        XCTAssertTrue(stream.playing, "the audio goes on")
        XCTAssertTrue(stream.loads.isEmpty, "the same source, not reloaded")
    }

    func testAccountsLeavingStopTheAudioAndTheLockScreenCard() {
        let engine = FakeEngine()
        let listen = screen("fake-post-1", engine: engine)
        waitUntil { listen.player.state != nil }
        listen.player.toggle()
        XCTAssertTrue(engine.streams[0].playing)
        XCTAssertEqual(env.backgroundSessions.count, 1, "the playing stream registered its teardown")
        env.backgroundSessions.stopAll()
        XCTAssertTrue(engine.streams[0].released, "the stream and its lock screen card went")
        XCTAssertEqual(listen.player.state?.paused, true)
        XCTAssertFalse(SpeakPlayback.shared.hasActive)
        // the player survived: its next stream is torn down by the next logout too
        listen.player.toggle()
        XCTAssertEqual(engine.streams.count, 2)
        XCTAssertEqual(env.backgroundSessions.count, 1)
        env.backgroundSessions.stopAll()
        XCTAssertTrue(engine.streams[1].released, "a second logout stops it too")
    }

    func testTheScreenClosingUnregistersItsTeardown() {
        let engine = FakeEngine()
        var listen: ListenScreen? = screen("fake-post-1", engine: engine)
        waitUntil { listen?.player.state != nil }
        XCTAssertEqual(env.backgroundSessions.count, 0, "nothing to tear down before the first play")
        listen?.player.toggle()
        XCTAssertEqual(env.backgroundSessions.count, 1)
        listen = nil
        XCTAssertEqual(env.backgroundSessions.count, 0)
    }

    func testTheLockScreenSkipsFifteenSecondsInsideTheTrack() {
        let stream = AVListenStream(item: ListenItem(url: "https://audio.invalid/demo/a.mp3", title: "t", artist: "@a"), events: Silent())
        XCTAssertEqual(stream.remoteSkip(NowPlaying.shared.SKIP_SECONDS), .success)
        XCTAssertEqual(stream.remoteSkip(-NowPlaying.shared.SKIP_SECONDS), .success)
        stream.release()
        XCTAssertEqual(stream.remoteSkip(15), .noActionableNowPlayingItem, "nothing after the release")
        XCTAssertEqual(NowPlaying.shared.skip(position: 10, delta: -15, duration: KotlinDouble(value: 100)), 0)
        XCTAssertEqual(NowPlaying.shared.skip(position: 95, delta: 15, duration: KotlinDouble(value: 100)), 100)
    }

    final class Silent: ListenEvents, SpeakEvents {
        func loadStarted() {}
        func buffering(_ isBuffering: Bool) {}
        func loaded(durationSeconds: Double) {}
        func playingChanged(_ isPlaying: Bool) {}
        func ended() {}
        func failed() {}
        func interrupted() {}
    }

    func testTheAudioSessionIsSharedWithTheVoicePlayersAndGivenBackByTheLast() {
        let events = Silent()
        let base = PlaybackAudioSession.users
        let listen = AVListenStream(item: ListenItem(url: "https://audio.invalid/demo/a.mp3", title: "t", artist: "@a"), events: events)
        XCTAssertEqual(PlaybackAudioSession.users, base, "nothing is held before a play")
        listen.play()
        listen.play()
        XCTAssertEqual(PlaybackAudioSession.users, base + 1, "one hold per stream, however many plays")
        let voice = AVSpeakStream(url: "https://audio.invalid/demo/b.mp3", events: events)
        XCTAssertEqual(PlaybackAudioSession.users, base + 2)
        listen.load(ListenItem(url: "https://audio.invalid/demo/a-renewed.mp3", title: "t", artist: "@a"))
        XCTAssertEqual(PlaybackAudioSession.users, base + 2, "a renewal keeps the session")
        listen.release()
        listen.release()
        XCTAssertEqual(PlaybackAudioSession.users, base + 1, "the voice stream still holds it")
        voice.release()
        XCTAssertEqual(PlaybackAudioSession.users, base, "the last one gave it back")
    }

    func testTheLockScreenCannotPlayAFailedSource() {
        let engine = FakeEngine()
        let listen = screen("fake-post-1", engine: engine)
        waitUntil { listen.player.state != nil }
        let player = listen.player
        player.toggle()
        let stream = engine.streams[0]
        // three renewals that load but fail at the same spot, then the failure stays
        for i in 1...3 {
            player.failed()
            waitUntil { stream.loads.count == i }
        }
        player.failed()
        XCTAssertEqual(player.state?.failed, true)
        stream.playing = true
        player.playingChanged(true)
        XCTAssertFalse(stream.playing, "refused: paused again")
        XCTAssertEqual(player.state?.paused, true)
        // and the system stream refuses it at the source
        let broken = AVListenStream(item: ListenItem(url: "", title: "t", artist: "@a"), events: Silent())
        XCTAssertEqual(broken.remotePlay(true), .commandFailed)
        broken.release()
    }

    func testTheUnlockWaitsForTheConfirmationThenShowsThePlaceInLine() {
        let engine = FakeEngine()
        let listen = screen("fake-post-3", engine: engine)
        let bar = listen.bar
        waitUntil { bar.state.button == .unlock }
        XCTAssertEqual(bar.state.unlockLabel, "Unlock · 50 Points")
        XCTAssertEqual(bar.state.subtitle, "English + German · ~3 min")
        listen.model.confirmUnlock()
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
        XCTAssertFalse(bar.state.view.unlocked, "no confirmation was showing")
        listen.model.requestUnlock()
        waitUntil { bar.state.confirmCost != nil }
        XCTAssertEqual(bar.state.confirmCost?.doubleValue, 50)
        listen.model.confirmUnlock()
        waitUntil { bar.state.preparing }
        XCTAssertEqual(bar.state.preparingText, "Waiting in line, 1 ahead…")
        XCTAssertNil(listen.player.state, "no player while the audio is made")
    }
}
