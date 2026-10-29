import AVFoundation
import Combine
import EcencyCore
import MediaPlayer
import Observation
import SwiftUI

// MARK: Engine

/// What Listen audio is about, for the lock screen and Control Center (RN `<Video source.metadata>`).
/// The shared `NowPlayingCard`: `artwork` is nil while the PIN lock is on (README "Background media").
struct ListenItem: Equatable {
    let url: String
    let title: String
    let artist: String
    var artwork: String? = nil
}

/// What a Listen stream reports back; mirrors react-native-video's callbacks in RN's player.
@MainActor
protocol ListenEvents: AnyObject {
    func loadStarted()
    func buffering(_ isBuffering: Bool)
    func loaded(durationSeconds: Double)
    /// Playing or not as the system decided it: the lock screen, Control Center, a headset button, an interruption.
    func playingChanged(_ isPlaying: Bool)
    func ended()
    func failed()
}

/// One loaded track. Lives from the first play on and stays while paused, so the lock-screen controls stay too.
@MainActor
protocol ListenStream: AnyObject {
    var positionSeconds: Double { get }
    func play()
    func pause()
    func seek(_ seconds: Double)
    func setSpeed(_ speed: Double)
    /// A new source for the same stream (a renewed URL, another language): loaded paused, at its start.
    func load(_ item: ListenItem)
    /// The same source with another lock screen card (the PIN lock was turned on or off): no reload, no pause.
    func retitle(_ item: ListenItem)
    func release()
}

/// Opens streams; tests pass a fake.
protocol ListenEngine {
    @MainActor func open(item: ListenItem, events: ListenEvents) -> ListenStream
}

/// AVPlayer with the `.playback` session (keeps playing in the background with the `audio` background mode and the
/// silent switch on, RN `playInBackground` and `ignoreSilentSwitch="ignore"`) and the lock screen's now playing card.
struct AVListenEngine: ListenEngine {
    @MainActor func open(item: ListenItem, events: ListenEvents) -> ListenStream { AVListenStream(item: item, events: events) }
}

/// The lock screen and Control Center show one stream: the last one that played. Its commands act on it.
@MainActor
final class ListenNowPlaying {
    static let shared = ListenNowPlaying()
    private weak var owner: AVListenStream?
    private var targets: [(MPRemoteCommand, Any)] = []

    func claim(_ stream: AVListenStream) {
        guard owner !== stream else { return }
        clearTargets()
        owner = stream
        let center = MPRemoteCommandCenter.shared()
        func add(_ command: MPRemoteCommand, _ handler: @escaping (MPRemoteCommandEvent) -> MPRemoteCommandHandlerStatus) {
            command.isEnabled = true
            targets.append((command, command.addTarget(handler: handler)))
        }
        add(center.playCommand) { [weak self] _ in MainActor.assumeIsolated { self?.owner?.remotePlay(true) } ?? .noActionableNowPlayingItem }
        add(center.pauseCommand) { [weak self] _ in MainActor.assumeIsolated { self?.owner?.remotePlay(false) } ?? .noActionableNowPlayingItem }
        add(center.togglePlayPauseCommand) { [weak self] _ in
            MainActor.assumeIsolated { self?.owner.map { $0.remotePlay(!$0.isPlaying) } } ?? .noActionableNowPlayingItem
        }
        add(center.changePlaybackPositionCommand) { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            return MainActor.assumeIsolated { self?.owner?.remoteSeek(event.positionTime) } ?? .noActionableNowPlayingItem
        }
        // skip back and forward 15 s (shared NowPlaying.SKIP_SECONDS); next and previous stay off (one track)
        let interval = NSNumber(value: NowPlaying.shared.SKIP_SECONDS)
        center.skipBackwardCommand.preferredIntervals = [interval]
        center.skipForwardCommand.preferredIntervals = [interval]
        add(center.skipBackwardCommand) { [weak self] _ in
            MainActor.assumeIsolated { self?.owner?.remoteSkip(-NowPlaying.shared.SKIP_SECONDS) } ?? .noActionableNowPlayingItem
        }
        add(center.skipForwardCommand) { [weak self] _ in
            MainActor.assumeIsolated { self?.owner?.remoteSkip(NowPlaying.shared.SKIP_SECONDS) } ?? .noActionableNowPlayingItem
        }
        center.nextTrackCommand.isEnabled = false
        center.previousTrackCommand.isEnabled = false
    }

    /// The commands the lock screen offers now (tests).
    var hasOwner: Bool { owner != nil }

    func update(_ stream: AVListenStream, info: [String: Any]) {
        guard owner === stream else { return }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    func leave(_ stream: AVListenStream) {
        guard owner === stream else { return }
        clearTargets()
        owner = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    private func clearTargets() {
        targets.forEach { command, target in command.removeTarget(target) }
        targets.removeAll()
    }

    // artwork: the card's image, loaded once per URL through the app's image pipeline, off the main thread
    private var artworkURL: String?
    private var artwork: MPMediaItemArtwork?
    private var artworkTask: Task<Void, Never>?

    /// The artwork for `url` when it is loaded; starts loading it otherwise and republishes `stream` once it is in.
    func artwork(for url: String?, stream: AVListenStream) -> MPMediaItemArtwork? {
        guard let url else { return nil }
        if url == artworkURL { return artwork }
        artworkURL = url
        artwork = nil
        artworkTask?.cancel()
        artworkTask = Task { [weak self, weak stream] in
            guard let loaded = await ImageLoader.shared.load(url, maxPixel: 600), !Task.isCancelled else { return }
            let image = loaded.image
            guard let self, self.artworkURL == url else { return }
            self.artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            stream?.republish()
        }
        return nil
    }
}

@MainActor
final class AVListenStream: ListenStream {
    private let player: AVPlayer
    private var item: ListenItem
    private weak var events: ListenEvents?
    private var observations: [NSKeyValueObservation] = []
    private var observers: [NSObjectProtocol] = []
    /// The current item's own observers, replaced with it by `load`.
    private var itemObservations: [NSKeyValueObservation] = []
    private var itemObservers: [NSObjectProtocol] = []
    private var ready = false
    private var released = false
    /// The current item failed: the lock screen's play is refused until a new source loads.
    private var itemFailed = false
    private var speed = 1.0
    private(set) var isPlaying = false
    /// This stream holds the shared `.playback` session (from a play until the end of the track or its release).
    private var holdsSession = false

    init(item: ListenItem, events: ListenEvents) {
        self.item = item
        self.events = events
        player = AVPlayer(playerItem: nil)
        player.automaticallyWaitsToMinimizeStalling = true
        observations.append(player.observe(\.timeControlStatus) { [weak self] player, _ in
            let waiting = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
            Task { @MainActor in self?.waitingChanged(waiting) }
        })
        let center = NotificationCenter.default
        // a call, Siri, or the headphones going away: AVPlayer stops by itself, the player shows paused
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let type = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            let began = type == AVAudioSession.InterruptionType.began.rawValue
            let ended = type == AVAudioSession.InterruptionType.ended.rawValue
            let options = AVAudioSession.InterruptionOptions(rawValue: note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0)
            Task { @MainActor in
                if began { self?.interruptedBySystem() }
                // a call or Siri is over and the system says to resume: playback goes on where it was
                if ended, options.contains(.shouldResume) { self?.resumeAfterInterruption() }
            }
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            let lost = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt) == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue
            Task { @MainActor in if lost { self?.stoppedBySystem() } }
        })
        attach(item)
    }

    /// Puts [item]'s source into the player, with its own observers (a stale item's late callbacks are ignored).
    private func attach(_ item: ListenItem) {
        itemObservations.forEach { $0.invalidate() }
        itemObservations.removeAll()
        itemObservers.forEach { NotificationCenter.default.removeObserver($0) }
        itemObservers.removeAll()
        ready = false
        itemFailed = false
        let media = URL(string: item.url).map { AVPlayerItem(url: $0) }
        media?.audioTimePitchAlgorithm = .spectral
        player.replaceCurrentItem(with: media)
        events?.loadStarted()
        guard let media else {
            itemFailed = true
            events?.failed()
            return
        }
        itemObservations.append(media.observe(\.status) { [weak self, weak media] item, _ in
            let failed = item.status == .failed
            let ready = item.status == .readyToPlay
            let duration = item.duration.seconds
            Task { @MainActor in
                guard let self, let media, self.player.currentItem === media else { return }
                self.statusChanged(failed: failed, ready: ready, duration: duration)
            }
        })
        let center = NotificationCenter.default
        // as the status KVO: a replaced item's late notifications are ignored
        itemObservers.append(center.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: media, queue: .main) { [weak self, weak media] _ in
            Task { @MainActor in
                guard let self, let media, self.player.currentItem === media else { return }
                self.endReached()
            }
        })
        itemObservers.append(center.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: media, queue: .main) { [weak self, weak media] _ in
            Task { @MainActor in
                guard let self, let media, self.player.currentItem === media else { return }
                self.statusChanged(failed: true, ready: false, duration: .nan)
            }
        })
    }

    func retitle(_ item: ListenItem) {
        guard !released, item.url == self.item.url else { return }
        self.item = item
        publish()
    }

    /// A renewed URL or another language in the same player: loaded paused, the session kept (no hand-back mid-renewal).
    func load(_ item: ListenItem) {
        guard !released else { return }
        self.item = item
        isPlaying = false
        player.pause()
        attach(item)
        publish()
    }

    private func statusChanged(failed: Bool, ready: Bool, duration: Double) {
        guard !released, let events else { return }
        if failed {
            itemFailed = true
            events.failed()
        } else if ready, !self.ready {
            self.ready = true
            events.loaded(durationSeconds: duration)
            publish()
        }
    }

    private func waitingChanged(_ waiting: Bool) {
        guard !released, ready else { return }
        events?.buffering(waiting)
    }

    private func endReached() {
        guard !released else { return }
        isPlaying = false
        letSessionGo()
        events?.ended()
        publish()
    }

    /// Paused by an interruption (not by the user or a route change): the only case that resumes by itself.
    private var interrupted = false

    private func stoppedBySystem() {
        guard !released, isPlaying else { return }
        player.pause()
        isPlaying = false
        events?.playingChanged(false)
        publish()
    }

    private func interruptedBySystem() {
        let was = isPlaying
        stoppedBySystem()
        interrupted = was
    }

    private func resumeAfterInterruption() {
        guard !released, interrupted, !itemFailed else { return }
        interrupted = false
        play()
        events?.playingChanged(true)
    }

    /// The lock screen's skip back or forward.
    func remoteSkip(_ delta: Double) -> MPRemoteCommandHandlerStatus {
        guard !released else { return .noActionableNowPlayingItem }
        let duration = player.currentItem?.duration.seconds
        seek(NowPlaying.shared.skip(position: positionSeconds, delta: delta, duration: duration.map { KotlinDouble(value: $0) }))
        return .success
    }

    /// Published again (the artwork came in).
    func republish() { publish() }

    /// The lock screen asked for play or pause: done here, then reported like any other change.
    func remotePlay(_ play: Bool) -> MPRemoteCommandHandlerStatus {
        guard !released else { return .noActionableNowPlayingItem }
        // nothing to play until a new source loads ("Try again" in the app)
        if play && itemFailed { return .commandFailed }
        if play { self.play() } else { pause() }
        events?.playingChanged(play)
        return .success
    }

    func remoteSeek(_ seconds: Double) -> MPRemoteCommandHandlerStatus {
        guard !released else { return .noActionableNowPlayingItem }
        seek(seconds)
        return .success
    }

    var positionSeconds: Double { player.currentTime().seconds }

    func play() {
        guard !released else { return }
        if holdsSession {
            // an interruption may have taken it away: active again for this play
            try? AVAudioSession.sharedInstance().setActive(true)
        } else {
            holdsSession = true
            PlaybackAudioSession.acquire()
        }
        isPlaying = true
        player.playImmediately(atRate: Float(speed))
        ListenNowPlaying.shared.claim(self)
        publish()
    }

    func pause() {
        guard !released else { return }
        interrupted = false
        isPlaying = false
        player.pause()
        publish()
    }

    func seek(_ seconds: Double) {
        player.seek(to: CMTime(seconds: seconds, preferredTimescale: 1000)) { [weak self] _ in
            Task { @MainActor in self?.publish() }
        }
    }

    func setSpeed(_ speed: Double) {
        self.speed = speed
        player.defaultRate = Float(speed)
        if isPlaying { player.rate = Float(speed) }
        publish()
    }

    private func publish() {
        guard !released else { return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: item.title,
            MPMediaItemPropertyArtist: item.artist,
            MPMediaItemPropertyAlbumTitle: "Ecency",
            MPNowPlayingInfoPropertyElapsedPlaybackTime: max(0, player.currentTime().seconds.isFinite ? player.currentTime().seconds : 0),
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? speed : 0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: speed,
        ]
        if let d = player.currentItem?.duration.seconds, d.isFinite, d > 0 { info[MPMediaItemPropertyPlaybackDuration] = d }
        if let art = ListenNowPlaying.shared.artwork(for: item.artwork, stream: self) { info[MPMediaItemPropertyArtwork] = art }
        ListenNowPlaying.shared.update(self, info: info)
    }

    func release() {
        guard !released else { return }
        released = true
        observations.forEach { $0.invalidate() }
        observations.removeAll()
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers.removeAll()
        itemObservations.forEach { $0.invalidate() }
        itemObservations.removeAll()
        itemObservers.forEach { NotificationCenter.default.removeObserver($0) }
        itemObservers.removeAll()
        player.pause()
        player.replaceCurrentItem(with: nil)
        isPlaying = false
        letSessionGo()
        ListenNowPlaying.shared.leave(self)
    }

    private func letSessionGo() {
        guard holdsSession else { return }
        holdsSession = false
        PlaybackAudioSession.release()
    }
}

// MARK: Controller

/// RN `listenAudio.tsx` around the shared `ListenPlayerState`: the stream exists from the first play on (paused or
/// not, so the lock screen keeps its controls), starting it stops any other player app-wide (`SpeakPlayback`, shared
/// with 3Speak voice posts), and an expired URL is renewed through `ListenModel.renewUrls`. Owned by the post screen:
/// it keeps playing behind pushed screens and in the background, and stops when the screen goes away.
@MainActor
@Observable
final class ListenAudioController: ListenEvents {
    private(set) var state: ListenPlayerState?
    private(set) var tracks: [ListenTrack] = []
    @ObservationIgnored let model: ListenModel
    @ObservationIgnored var engine: any ListenEngine
    @ObservationIgnored private let uiLanguage: String
    @ObservationIgnored private var stream: ListenStream?
    @ObservationIgnored private var streamUrl: String?
    /// What the stream was last told, so each command goes once.
    @ObservationIgnored private var commandedPlaying = false
    @ObservationIgnored nonisolated(unsafe) private var ticker: Timer?
    @ObservationIgnored private var renewal: Task<Void, Never>?
    @ObservationIgnored private var renewalId = 0
    @ObservationIgnored private var watcher: Closeable?
    @ObservationIgnored private lazy var token = SpeakPlayback.Token { [weak self] in
        guard let self, let s = self.state else { return }
        self.apply(s.stopped())
    }

    /// The lock screen card for a source (title, @author, artwork unless the PIN lock is on); tests leave it out.
    @ObservationIgnored private let card: ((ListenModel) -> NowPlayingCard)?
    /// Registered for each open stream (a teardown empties the registry, a later stream registers again).
    @ObservationIgnored private var teardown: BackgroundSessions.Entry?
    @ObservationIgnored private let sessions: BackgroundSessions?

    init(model: ListenModel, engine: any ListenEngine, uiLanguage: String, sessions: BackgroundSessions? = nil, card: ((ListenModel) -> NowPlayingCard)? = nil) {
        self.model = model
        self.engine = engine
        self.uiLanguage = uiLanguage
        self.card = card
        self.sessions = sessions
        watcher = model.watch { [weak self] bar in MainActor.assumeIsolated { self?.onTracks(bar) } }
    }

    private func item(_ url: String) -> ListenItem {
        if let c = card?(model) { return ListenItem(url: url, title: c.title, artist: c.artist, artwork: c.artworkUrl) }
        let post = model.post
        return ListenItem(url: url, title: post?.title ?? "", artist: "@\(post?.author ?? model.key.author)")
    }

    /// The PIN lock was turned on or off: the lock screen card is read again (no artwork while it is on).
    func refreshCard() {
        guard let s = state, let current = stream, let track = s.track(tracks: tracks), track.url == streamUrl else { return }
        current.retitle(item(track.url))
    }

    private func tearDown() {
        renewal?.cancel()
        renewal = nil
        state = state?.stopped()
        stopStream()
    }

    deinit {
        ticker?.invalidate()
    }

    var hasStream: Bool { stream != nil }

    private func onTracks(_ bar: ListenBarState) {
        let next = bar.showsPlayer ? bar.tracks : []
        if next.isEmpty {
            tracks = []
            state = nil
            stopStream()
            return
        }
        tracks = next
        guard let s = state else {
            state = ListenPlayerState.companion.initial(tracks: next, uiLanguage: uiLanguage)
            return
        }
        if renewal == nil, stream != nil, s.track(tracks: next)?.url != streamUrl { reopen() }
    }

    // what the row does

    func toggle() {
        guard let s = state else { return }
        if s.failed && s.paused {
            state = s.retry().toggle()
            renew()
            return
        }
        apply(s.toggle())
    }

    func nextSpeed() {
        guard let next = state?.nextSpeed() else { return }
        apply(next)
        stream?.setSpeed(next.speed)
    }

    func switchTrack(_ lang: String) {
        guard let s = state, let next = s.switchTrack(next: lang, current: s.track(tracks: tracks)) else { return }
        state = next
        if stream != nil { reopen() }
    }

    func drag(_ value: Double) { state = state?.drag(value: value) }

    func dragEnded(_ value: Double) {
        guard let step = state?.dragEnded(value: value) else { return }
        state = step.state
        if let to = step.seekTo?.doubleValue { stream?.seek(to) }
    }

    /// "Try again" after the URL could not be renewed.
    func retry() {
        guard let s = state else { return }
        state = s.retry()
        renew()
    }

    // what the stream reports

    func loadStarted() { state = state?.loadStarted() }
    func buffering(_ isBuffering: Bool) { state = state?.buffering(isBuffering: isBuffering) }

    func loaded(durationSeconds: Double) {
        guard let step = state?.loaded(streamDuration: durationSeconds) else { return }
        state = step.state
        if let to = step.seekTo?.doubleValue { stream?.seek(to) }
    }

    func playingChanged(_ isPlaying: Bool) {
        guard let s = state else { return }
        if isPlaying && s.failed {
            // the lock screen asked to play a source that could not be had: refused, "Try again" stays
            stream?.pause()
            return
        }
        let next = s.playbackChanged(isPlaying: isPlaying, isSeeking: false)
        if next.paused == !isPlaying { commandedPlaying = isPlaying }
        apply(next)
    }

    func ended() {
        guard let step = state?.ended() else { return }
        stream?.seek(0)
        apply(step.state)
    }

    func failed() {
        guard let s = state else { return }
        let result = s.error()
        // the stream (and the session it holds) stays: new URLs load into it
        stopTicker()
        let next = result.first!
        state = next
        if result.second!.boolValue { renew() } else { apply(next) }
    }

    /// Progress, as react-native-video's 500 ms `onProgress`; also in the background (the renewal count needs it).
    func tick() {
        guard let s = state, !s.loading, let stream else { return }
        state = s.progressed(currentTime: stream.positionSeconds)
    }

    private func renew() {
        renewal?.cancel()
        renewalId += 1
        let id = renewalId
        // set before it runs, so its end can tell it is still the current one
        renewal = Task { [weak self] in
            var ok = true
            do { try await self?.model.renewUrls() } catch { ok = false }
            guard let self, !Task.isCancelled, self.renewalId == id else { return }
            self.renewal = nil
            guard let s = self.state else { return }
            let next = s.renewed(ok: ok)
            self.state = next
            if ok {
                if next.started { self.reopen() }
            } else {
                self.apply(next)
            }
        }
    }

    // the stream

    private func apply(_ next: ListenPlayerState) {
        state = next
        // no source while new URLs are asked for, nor after they could not be had (until "Try again")
        if next.started, stream == nil, renewal == nil, !next.failed { open(next) }
        let playing = !next.paused
        if playing { SpeakPlayback.shared.activate(token: token) } else { SpeakPlayback.shared.deactivate(token: token) }
        if playing != commandedPlaying {
            commandedPlaying = playing
            if playing { stream?.play() } else { stream?.pause() }
        }
        if playing { startTicker() } else { stopTicker() }
    }

    private func open(_ s: ListenPlayerState) {
        guard let track = s.track(tracks: tracks) else { return }
        streamUrl = track.url
        commandedPlaying = false
        let opened = engine.open(item: item(track.url), events: self)
        stream = opened
        // accounts left the device (logout, wipe): the audio stops and the lock screen card goes
        teardown?.close()
        teardown = sessions?.register { [weak self] in MainActor.assumeIsolated { self?.tearDown() } }
        opened.setSpeed(s.speed)
    }

    private func reopen() {
        guard let s = state else { return }
        guard s.started else {
            releaseStream()
            return
        }
        if let current = stream, let track = s.track(tracks: tracks) {
            // the same player: the session and the lock screen card stay across a new URL
            streamUrl = track.url
            commandedPlaying = false
            current.load(item(track.url))
            current.setSpeed(s.speed)
        } else {
            releaseStream()
            open(s)
        }
        if let now = state { apply(now) }
    }

    private func startTicker() {
        guard ticker == nil else { return }
        let t = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in MainActor.assumeIsolated { self?.tick() } }
        RunLoop.main.add(t, forMode: .common)
        ticker = t
    }

    private func stopTicker() {
        ticker?.invalidate()
        ticker = nil
    }

    private func releaseStream() {
        teardown?.close()
        teardown = nil
        stopTicker()
        if let open = stream {
            stream = nil
            streamUrl = nil
            open.release()
        }
        commandedPlaying = false
    }

    private func stopStream() {
        SpeakPlayback.shared.deactivate(token: token)
        releaseStream()
    }

    /// The screen went away: the audio stops and the lock screen card goes.
    func close() {
        renewal?.cancel()
        renewal = nil
        watcher?.close()
        watcher = nil
        stopStream()
    }
}

/// The post screen's Listen: the shared bar model and its player, owned by the screen (not by the lazy row).
@MainActor
final class ListenScreen: ObservableObject {
    let bar: Observed<ListenBarState>
    let player: ListenAudioController

    private var appState: [NSObjectProtocol] = []
    private var lockWatch: Closeable?

    init(key: PostKey, env: AppEnvironment, uiLanguage: String, engine: any ListenEngine) {
        let model = env.listenModel(author: key.author, permlink: key.permlink)
        bar = Observed(model, ownsModel: true)
        player = ListenAudioController(model: model, engine: engine, uiLanguage: uiLanguage, sessions: env.backgroundSessions) { [weak env] in
            env?.nowPlaying(model: $0) ?? NowPlaying.shared.card(post: $0.post, fallbackAuthor: $0.key.author, lockEnabled: true, image: nil, proxyBase: "")
        }
        // the app in the background or back, followed by the screen's owner (not the bar's row, which leaves the
        // lazy stack as the post scrolls)
        let center = NotificationCenter.default
        appState.append(center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { model.onForeground() }
        })
        appState.append(center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { model.onBackground() }
        })
        // the lock screen card follows the PIN lock setting while it plays (no artwork while the lock is on)
        var lockEnabled: Bool?
        lockWatch = env.appLock.watch { [weak player] state in
            MainActor.assumeIsolated {
                let enabled = (state as! AppLockState).enabled
                defer { lockEnabled = enabled }
                if let before = lockEnabled, before != enabled { player?.refreshCard() }
            }
        }
    }

    var model: ListenModel { bar.model as! ListenModel }

    deinit {
        lockWatch?.close()
        appState.forEach { NotificationCenter.default.removeObserver($0) }
        let player = player
        MainActor.assumeIsolated { player.close() }
    }
}

// MARK: Views

/// RN `ListenBar` above the post body: log in, unlock for Points (confirmed first), the audio being made, or the player.
struct ListenSection: View {
    @ObservedObject var screen: ListenScreen
    @ObservedObject var bar: Observed<ListenBarState>
    let onLogin: () -> Void
    let onGetPoints: () -> Void
    @Environment(\.appTheme) private var theme

    init(screen: ListenScreen, onLogin: @escaping () -> Void, onGetPoints: @escaping () -> Void) {
        self.screen = screen
        bar = screen.bar
        self.onLogin = onLogin
        self.onGetPoints = onGetPoints
    }

    var body: some View {
        let s = bar.state
        Group {
            if !s.hidden {
                if s.showsPlayer, let player = screen.player.state {
                    ListenPlayerRow(controller: screen.player, state: player)
                } else {
                    ListenBarRow(state: s, model: screen.model, onLogin: onLogin)
                }
            }
        }
        .alert(ReadingUiCopy.shared.LISTEN_TO_THIS_POST, isPresented: Binding(get: { bar.state.confirmCost != nil }, set: { if !$0 { screen.model.cancelUnlock() } })) {
            Button(UiCopy.shared.CANCEL, role: .cancel) { screen.model.cancelUnlock() }
            Button(UiCopy.shared.CONFIRM) { screen.model.confirmUnlock() }.accessibilityIdentifier("listen-confirm")
        } message: { Text(s.confirmText ?? "") }
        .alert(ReadingUiCopy.shared.NOT_ENOUGH_POINTS, isPresented: Binding(get: { bar.state.alert is ListenAlertNotEnoughPoints }, set: { if !$0 { screen.model.dismissAlert() } })) {
            Button(UiCopy.shared.CANCEL, role: .cancel) { screen.model.dismissAlert() }
            Button(ReadingUiCopy.shared.GET_POINTS) { screen.model.dismissAlert(); onGetPoints() }.accessibilityIdentifier("listen-get-points")
        } message: { Text((s.alert as? ListenAlertNotEnoughPoints)?.message ?? "") }
        .alert(UiCopy.shared.FAIL, isPresented: Binding(get: { bar.state.alert is ListenAlertUnlockFailed }, set: { if !$0 { screen.model.dismissAlert() } })) {
            Button(UiCopy.shared.OK, role: .cancel) { screen.model.dismissAlert() }
        } message: { Text(ReadingUiCopy.shared.COULD_NOT_UNLOCK_THE) }
    }
}

private struct ListenBarRow: View {
    let state: ListenBarState
    let model: ListenModel
    let onLogin: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        // side by side; the button under the text at accessibility text sizes
        AdaptiveStack(spacing: 10) {
            HStack(spacing: 10) {
            Image(appIcon: .headphones).scaledFont(20).foregroundStyle(theme.accentText).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(ReadingUiCopy.shared.LISTEN_TO_THIS_POST).scaledFont(14, weight: .semibold, relativeTo: .subheadline).foregroundStyle(theme.text)
                Text(state.subtitle).scaledFont(12, relativeTo: .caption).foregroundStyle(theme.secondary).lineLimit(3)
                    .accessibilityIdentifier("listen-subtitle")
            }
            .frame(minWidth: 120, maxWidth: .infinity, alignment: .leading)
            }
            if state.preparing { ListenPreparing(state: state) }
            switch state.button {
            case .login:
                ListenPill(label: ReadingUiCopy.shared.LOG_IN, id: "listen-login", busy: false, action: onLogin)
            case .loading:
                ProgressView().accessibilityIdentifier("listen-loading")
            case .unlock:
                ListenPill(label: state.unlockLabel, id: "listen-unlock", busy: state.busy) { model.requestUnlock() }
            default:
                EmptyView()
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(theme.background)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(theme.lightBackground, lineWidth: 1))
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("listen-bar")
    }
}

/// RN `PreparingState`: the server's percentage once the worker has the job, else the place in line or an estimate.
private struct ListenPreparing: View {
    let state: ListenBarState
    @Environment(\.appTheme) private var theme

    var body: some View {
        if let percent = state.preparingPercent?.intValue {
            VStack(alignment: .trailing, spacing: 4) {
                ZStack(alignment: .leading) {
                    Capsule().fill(theme.lightBackground).frame(width: 96, height: 6)
                    Capsule().fill(theme.accent).frame(width: 96 * CGFloat(percent) / 100, height: 6)
                }
                Text(state.preparingText).scaledFont(12, relativeTo: .caption).foregroundStyle(theme.secondary)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(state.preparingText)
            .accessibilityValue(Fmt.percent(percent))
            .accessibilityIdentifier("listen-progress")
        } else {
            HStack(spacing: 6) {
                ProgressView().controlSize(.small)
                Text(state.preparingText).scaledFont(12, relativeTo: .caption).foregroundStyle(theme.secondary)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("listen-preparing")
        }
    }
}

private struct ListenPill: View {
    let label: String
    let id: String
    let busy: Bool
    let action: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        Button(action: action) {
            Group {
                if busy { ProgressView().tint(.white) } else { Text(label).scaledFont(13, weight: .semibold, relativeTo: .footnote).foregroundStyle(.white) }
            }
            .frame(minWidth: 72)
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .background(Capsule().fill(theme.accent))
            .frame(minHeight: minTapTarget).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(busy)
        .accessibilityIdentifier(id)
    }
}

private struct ListenPlayerRow: View {
    let controller: ListenAudioController
    let state: ListenPlayerState
    @Environment(\.appTheme) private var theme
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let tracks = controller.tracks
        let current = state.track(tracks: tracks)
        VStack(alignment: .leading, spacing: 8) {
            // one row; at accessibility text sizes the slider gets its own full-width line
            if typeSize >= .stacksRows {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Button { controller.toggle() } label: {
                            ZStack {
                                Circle().fill(theme.accent).frame(width: 40, height: 40).padding(2) // 44 pt of touch
                                if state.showSpinner {
                                    ProgressView().tint(.white)
                                } else {
                                    Image(appIcon: state.paused ? .play : .pause, filled: true).scaledFont(18).foregroundStyle(.white)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(state.paused ? ReadingUiCopy.shared.PLAY : ReadingUiCopy.shared.PAUSE)
                        .accessibilityIdentifier("listen-play")
                        Text(state.timeLabel).font(.caption.monospacedDigit()).foregroundStyle(theme.secondary)
                            .accessibilityIdentifier("listen-time")
                        Spacer(minLength: 0)
                        Text(state.durationLabel).font(.caption.monospacedDigit()).foregroundStyle(theme.secondary)
                            .accessibilityIdentifier("listen-duration")
                        ListenChip(label: state.speedLabel, active: false) { controller.nextSpeed() }
                            .accessibilityLabel(ReadingUiCopy.shared.PLAYBACK_SPEED)
                            .accessibilityValue(state.speedLabel)
                            .accessibilityIdentifier("listen-speed")
                    }
                    Slider(
                        value: Binding(get: { min(state.shownTime, state.sliderMax) }, set: { controller.drag($0) }),
                        in: 0...state.sliderMax,
                        onEditingChanged: { editing in
                            if !editing, let v = controller.state?.seeking?.doubleValue { controller.dragEnded(v) }
                        }
                    )
                    .tint(theme.accentText)
                    .accessibilityLabel(ReadingUiCopy.shared.SEEK)
                }
            } else {
                HStack(spacing: 8) {
                    Button { controller.toggle() } label: {
                        ZStack {
                            Circle().fill(theme.accent).frame(width: 40, height: 40).padding(2) // 44 pt of touch
                            if state.showSpinner {
                                ProgressView().tint(.white)
                            } else {
                                Image(appIcon: state.paused ? .play : .pause, filled: true).scaledFont(18).foregroundStyle(.white)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(state.paused ? ReadingUiCopy.shared.PLAY : ReadingUiCopy.shared.PAUSE)
                    .accessibilityIdentifier("listen-play")
                    Text(state.timeLabel).font(.caption.monospacedDigit()).foregroundStyle(theme.secondary)
                        .accessibilityIdentifier("listen-time")
                    Slider(
                        value: Binding(get: { min(state.shownTime, state.sliderMax) }, set: { controller.drag($0) }),
                        in: 0...state.sliderMax,
                        onEditingChanged: { editing in
                            if !editing, let v = controller.state?.seeking?.doubleValue { controller.dragEnded(v) }
                        }
                    )
                    .tint(theme.accentText)
                    .accessibilityLabel(ReadingUiCopy.shared.SEEK)
                    Text(state.durationLabel).font(.caption.monospacedDigit()).foregroundStyle(theme.secondary)
                        .accessibilityIdentifier("listen-duration")
                    ListenChip(label: state.speedLabel, active: false) { controller.nextSpeed() }
                        .accessibilityLabel(ReadingUiCopy.shared.PLAYBACK_SPEED)
                        .accessibilityValue(state.speedLabel)
                        .accessibilityIdentifier("listen-speed")
                }
            }
            if state.failed {
                AdaptiveStack {
                    Text(ReadingUiCopy.shared.THE_AUDIO_COULD_NOT).scaledFont(12, relativeTo: .caption).foregroundStyle(theme.secondary)
                        .announces(ReadingUiCopy.shared.THE_AUDIO_COULD_NOT)
                    Spacer(minLength: 0)
                    Button(UiCopy.shared.TRY_AGAIN) { controller.retry() }.scaledFont(13, weight: .semibold, relativeTo: .footnote).accessibilityIdentifier("listen-retry")
                }
            }
            if tracks.count > 1 {
                ChipFlow(spacing: 6) {
                    ForEach(tracks, id: \.lang) { t in
                        ListenChip(label: Listen.shared.languageName(lang: t.lang), active: t.lang == current?.lang) { controller.switchTrack(t.lang) }
                            .accessibilityIdentifier("listen-lang-\(t.lang)")
                            .accessibilityAddTraits(t.lang == current?.lang ? .isSelected : [])
                    }
                }
                .accessibilityElement(children: .contain)
                .accessibilityLabel(ReadingUiCopy.shared.AUDIO_LANGUAGE)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(theme.background)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(theme.lightBackground, lineWidth: 1))
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("listen-player")
    }
}

private struct ListenChip: View {
    let label: String
    let active: Bool
    let action: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        Button(action: action) {
            Text(label).font(.caption.monospacedDigit()).foregroundStyle(active ? theme.accentText : theme.text)
                .padding(.horizontal, 8).padding(.vertical, 3)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(active ? theme.accent : theme.lightBackground, lineWidth: 1))
                .frame(minHeight: minTapTarget).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
