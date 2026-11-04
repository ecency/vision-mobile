import AVFoundation
import EcencyCore
import Observation
import SwiftUI

/// The `.playback` audio session, shared by every player that holds it (3Speak voice streams and Listen): active
/// while one holds it, given back to other apps (`notifyOthersOnDeactivation`) when the last one lets go.
enum PlaybackAudioSession {
    nonisolated(unsafe) private static var holders = 0
    private static let lock = NSLock()

    /// Players holding the session now (tests).
    static var users: Int { lock.withLock { holders } }

    static func acquire() {
        lock.withLock { holders += 1 }
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .spokenAudio)
        try? session.setActive(true)
    }

    static func release() {
        let last: Bool = lock.withLock {
            holders = max(0, holders - 1)
            return holders == 0
        }
        if last { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
    }
}

/// What a playing stream reports back; mirrors react-native-video's callbacks in RN's player.
@MainActor
protocol SpeakEvents: AnyObject {
    func loadStarted()
    func buffering(_ isBuffering: Bool)
    func loaded(durationSeconds: Double)
    func ended()
    func failed()
    /// The system paused playback itself (an interruption, headphones unplugged).
    func interrupted()
}

/// One playing clip. Exists only while its row is playing.
@MainActor
protocol SpeakStream: AnyObject {
    var positionSeconds: Double { get }
    func seek(_ seconds: Double)
    func release()
}

/// Opens streams; tests swap in a fake through the `speakEngine` environment value.
protocol SpeakEngine {
    @MainActor func open(url: String, events: SpeakEvents) -> SpeakStream
}

/// AVPlayer, audio only. The `.playback` session plays with the silent switch on (RN
/// `ignoreSilentSwitch="ignore"`) and is activated on every play, as RN's now-playing registration does.
struct AVSpeakEngine: SpeakEngine {
    @MainActor func open(url: String, events: SpeakEvents) -> SpeakStream { AVSpeakStream(url: url, events: events) }
}

@MainActor
final class AVSpeakStream: SpeakStream {

    private let player: AVPlayer
    private weak var events: SpeakEvents?
    private var observations: [NSKeyValueObservation] = []
    nonisolated(unsafe) private var endObserver: NSObjectProtocol?
    nonisolated(unsafe) private var sessionObservers: [NSObjectProtocol] = []
    private var ready = false
    nonisolated(unsafe) private var released = false

    init(url: String, events: SpeakEvents) {
        PlaybackAudioSession.acquire()
        self.events = events
        let item = URL(string: url).map { AVPlayerItem(url: $0) }
        player = AVPlayer(playerItem: item)
        events.loadStarted()
        guard let item else {
            events.failed()
            return
        }
        // KVO fires on any thread: hand plain values to the main actor (the closures capture only
        // `self`, which is main-actor isolated and so Sendable).
        observations.append(item.observe(\.status) { [weak self] item, _ in
            let failed = item.status == .failed
            let ready = item.status == .readyToPlay
            let duration = item.duration.seconds
            Task { @MainActor in self?.statusChanged(failed: failed, ready: ready, duration: duration) }
        })
        observations.append(player.observe(\.timeControlStatus) { [weak self] player, _ in
            let waiting = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
            Task { @MainActor in self?.waitingChanged(waiting) }
        })
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.events?.ended() }
        }
        // AVPlayer stops by itself on an interruption (a call, Siri) or when the headphones go away;
        // the row must show paused, as for any other stop.
        sessionObservers.append(NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] note in
            let began = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.began.rawValue
            Task { @MainActor in if began { self?.interruptedBySystem() } }
        })
        sessionObservers.append(NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main
        ) { [weak self] note in
            let lost = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt) == AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue
            Task { @MainActor in if lost { self?.interruptedBySystem() } }
        })
        player.play()
    }

    private func statusChanged(failed: Bool, ready: Bool, duration: Double) {
        guard !released, let events else { return }
        if failed {
            events.failed()
        } else if ready, !self.ready {
            self.ready = true
            events.loaded(durationSeconds: duration)
        }
    }

    private func interruptedBySystem() {
        guard !released else { return }
        events?.interrupted()
    }

    private func waitingChanged(_ waiting: Bool) {
        guard !released, ready else { return }
        events?.buffering(waiting)
    }

    var positionSeconds: Double { player.currentTime().seconds }

    func seek(_ seconds: Double) {
        player.seek(to: CMTime(seconds: seconds, preferredTimescale: 1000))
    }

    func release() {
        guard !released else { return }
        observations.forEach { $0.invalidate() }
        observations.removeAll()
        player.pause()
        player.replaceCurrentItem(with: nil)
        finish()
    }

    /// A stream dropped without `release()` (its owner went away) still gives the session back.
    deinit { finish() }

    nonisolated private func finish() {
        guard !released else { return }
        released = true
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = nil
        sessionObservers.forEach { NotificationCenter.default.removeObserver($0) }
        sessionObservers.removeAll()
        PlaybackAudioSession.release()
    }
}

private struct SpeakEngineKey: EnvironmentKey {
    static let defaultValue: any SpeakEngine = AVSpeakEngine()
}

extension EnvironmentValues {
    var speakEngine: any SpeakEngine {
        get { self[SpeakEngineKey.self] }
        set { self[SpeakEngineKey.self] = newValue }
    }
}

/// RN `SpeakAudioPlayer`'s logic around the shared `SpeakPlayerState`: the stream exists only while
/// playing (pausing releases it, resuming seeks back), and starting one player stops any other.
@MainActor
@Observable
final class SpeakPlayerController: SpeakEvents {
    private(set) var state: SpeakPlayerState
    private(set) var block: BlockSpeakAudio
    @ObservationIgnored var engine: any SpeakEngine
    @ObservationIgnored private var stream: SpeakStream?
    @ObservationIgnored nonisolated(unsafe) private var timer: Timer?
    /// `open` reports `loadStarted` before it returns; this keeps that from opening a second stream.
    @ObservationIgnored private var opening = false
    @ObservationIgnored private lazy var token = SpeakPlayback.Token { [weak self] in
        guard let self else { return }
        self.apply(self.state.stopped())
    }

    init(block: BlockSpeakAudio, engine: any SpeakEngine) {
        self.block = block
        self.engine = engine
        state = SpeakPlayerState.companion.initial(block: block)
    }

    deinit { timer?.invalidate() }

    var hasStream: Bool { stream != nil }

    func togglePlay() { apply(state.togglePlay()) }

    func seek(fraction: Double) {
        let seek = state.seekToFraction(fraction: fraction)
        apply(seek.state)
        if let to = seek.to?.doubleValue { stream?.seek(to) }
    }

    /// The row left the screen or the app went to the background (RN `playInBackground={false}`).
    func stop() { apply(state.stopped()) }

    func loadStarted() { apply(state.loadStarted()) }
    func buffering(_ isBuffering: Bool) { apply(state.buffering(isBuffering: isBuffering)) }

    func loaded(durationSeconds: Double) {
        let seek = state.loaded(streamDuration: durationSeconds)
        apply(seek.state)
        if let to = seek.to?.doubleValue { stream?.seek(to) }
    }

    func ended() {
        stream?.seek(0)
        apply(state.ended())
    }

    func failed() { apply(state.failed()) }
    func interrupted() { stop() }

    /// The same clip rendered again with newer data (an edited post): take its peaks and duration.
    func refresh(_ next: BlockSpeakAudio) {
        guard !next.isEqual(block) else { return }
        block = next
        if !state.started { state = SpeakPlayerState.companion.initial(block: next) }
    }

    /// Polled progress, as react-native-video's 250 ms `onProgress`.
    func tick() {
        if let stream { apply(state.progressed(time: stream.positionSeconds)) }
    }

    private func apply(_ next: SpeakPlayerState) {
        state = next
        if next.active {
            SpeakPlayback.shared.activate(token: token)
            if stream == nil, !opening, !block.streamUrl.isEmpty {
                opening = true
                let opened = engine.open(url: block.streamUrl, events: self)
                opening = false
                stream = opened
                // A failure reported while opening already stopped this player.
                guard state.active else {
                    releaseStream()
                    return
                }
                // .common: progress keeps moving while the list is being scrolled.
                let tick = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in
                    MainActor.assumeIsolated { self?.tick() }
                }
                RunLoop.main.add(tick, forMode: .common)
                timer = tick
            }
        } else {
            SpeakPlayback.shared.deactivate(token: token)
            releaseStream()
        }
    }

    private func releaseStream() {
        timer?.invalidate()
        timer = nil
        if let open = stream {
            stream = nil
            open.release()
        }
    }
}

/// The screen's voice players, keyed by stream: a playing row keeps playing while it scrolls off and
/// back (RN keeps the post body mounted). Everything stops when the app leaves the foreground (RN
/// `playInBackground={false}`) and when the screen goes away.
@MainActor
final class SpeakPlayers {
    private var players: [AnyHashable: SpeakPlayerController] = [:]

    func controller(for block: BlockSpeakAudio, engine: any SpeakEngine) -> SpeakPlayerController {
        let key: AnyHashable = block.streamUrl.isEmpty ? AnyHashable(ObjectIdentifier(block)) : AnyHashable(block.streamUrl)
        if let existing = players[key] {
            existing.refresh(block)
            return existing
        }
        let made = SpeakPlayerController(block: block, engine: engine)
        players[key] = made
        return made
    }

    func stopAll() { players.values.forEach { $0.stop() } }
}

private struct SpeakPlayersKey: EnvironmentKey {
    static let defaultValue: SpeakPlayers? = nil
}

extension EnvironmentValues {
    var speakPlayers: SpeakPlayers? {
        get { self[SpeakPlayersKey.self] }
        set { self[SpeakPlayersKey.self] = newValue }
    }
}

private struct SpeakPlayersScope: ViewModifier {
    @State private var players = SpeakPlayers()
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content
            .environment(\.speakPlayers, players)
            .onDisappear { players.stopAll() }
            .onChange(of: scenePhase) { _, phase in if phase != .active { players.stopAll() } }
    }
}

extension View {
    /// Gives the post bodies inside one set of voice players that outlives their rows.
    func speakPlayersScope() -> some View { modifier(SpeakPlayersScope()) }
}

/// `$borderColor`, the same in both RN themes.
private let speakUnplayed = Color(hex: 0xC5C5C5)

struct SpeakAudioPlayerView: View {
    let block: BlockSpeakAudio
    let maxWidth: CGFloat
    let engine: any SpeakEngine
    @Environment(\.speakPlayers) private var players

    var body: some View {
        if let players {
            SpeakAudioRow(controller: players.controller(for: block, engine: engine), maxWidth: maxWidth)
        } else {
            // No screen scope: the row owns its player and stops it when it goes away.
            OwnedSpeakAudioRow(block: block, maxWidth: maxWidth, engine: engine)
        }
    }
}

private struct OwnedSpeakAudioRow: View {
    let maxWidth: CGFloat
    @State private var controller: SpeakPlayerController
    @Environment(\.scenePhase) private var scenePhase

    init(block: BlockSpeakAudio, maxWidth: CGFloat, engine: any SpeakEngine) {
        self.maxWidth = maxWidth
        _controller = State(initialValue: SpeakPlayerController(block: block, engine: engine))
    }

    var body: some View {
        SpeakAudioRow(controller: controller, maxWidth: maxWidth)
            .onDisappear { controller.stop() }
            .onChange(of: scenePhase) { _, phase in if phase != .active { controller.stop() } }
    }
}

/// RN `SpeakAudioPlayer`'s row: play button, waveform that seeks on tap, time.
struct SpeakAudioRow: View {
    let controller: SpeakPlayerController
    let maxWidth: CGFloat
    @Environment(\.postTheme) private var theme

    var body: some View {
        let state = controller.state
        HStack(spacing: 0) {
            Button { controller.togglePlay() } label: {
                ZStack {
                    Circle().fill(theme.link)
                    if state.showSpinner {
                        ProgressView().tint(.white).accessibilityIdentifier("speak:loading")
                    } else {
                        PlayPauseGlyph(playing: !state.paused).frame(width: 24, height: 24)
                    }
                }
                .frame(width: 40, height: 40)
                .padding(2) // 44 pt of touch
            }
            .buttonStyle(.plain)
            .accessibilityLabel(state.paused ? ReadingUiCopy.shared.PLAY_VOICE : ReadingUiCopy.shared.PAUSE_VOICE)
            .accessibilityAddTraits(.isButton)
            .accessibilityIdentifier("speak:play")
            .padding(.trailing, 10)

            waveform(state)
                .frame(maxWidth: .infinity)
                .frame(height: CGFloat(SpeakAudio.shared.WAVE_HEIGHT))

            Text(state.timeLabel)
                .scaledFont(12, weight: .semibold, relativeTo: .caption)
                .foregroundStyle(theme.text)
                .frame(minWidth: 34, alignment: .trailing)
                .padding(.leading, 10)
                .accessibilityIdentifier("speak:time")
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 10)
        .frame(width: maxWidth)
        .background(theme.placeholder, in: RoundedRectangle(cornerRadius: 14))
        .padding(.top, 8)
        .padding(.bottom, 4)
    }

    private func waveform(_ state: SpeakPlayerState) -> some View {
        GeometryReader { geo in
            let width = geo.size.width
            Canvas { context, size in
                guard width > 0 else { return }
                if controller.block.peaks.isEmpty {
                    let top = (size.height - 4) / 2
                    context.fill(Path(roundedRect: CGRect(x: 0, y: top, width: width, height: 4), cornerRadius: 2), with: .color(speakUnplayed))
                    context.fill(Path(roundedRect: CGRect(x: 0, y: top, width: width * CGFloat(state.progress), height: 4), cornerRadius: 2),
                                 with: .color(theme.link))
                } else {
                    for bar in SpeakAudio.shared.bars(peaks: controller.block.peaks, width: Double(width), progress: state.progress) {
                        let rect = CGRect(x: bar.x, y: bar.y, width: bar.width, height: bar.height)
                        context.fill(Path(roundedRect: rect, cornerRadius: bar.width / 2), with: .color(bar.played ? theme.link : speakUnplayed))
                    }
                }
            }
            .contentShape(Rectangle())
            .onTapGesture(coordinateSpace: .local) { location in
                if width > 0 { controller.seek(fraction: Double(location.x / width)) }
            }
        }
        .accessibilityElement()
        .accessibilityLabel(ReadingUiCopy.shared.VOICE_POSITION)
        .accessibilityValue(state.timeLabel)
        .accessibilityAddTraits(.allowsDirectInteraction)
        .accessibilityIdentifier("speak:wave")
    }
}

/// MaterialIcons `play-arrow` / `pause` at 24, drawn so the body renderer needs no icon font.
private struct PlayPauseGlyph: View {
    let playing: Bool

    var body: some View {
        Canvas { context, size in
            let u = size.width / 24
            if playing {
                context.fill(Path(CGRect(x: 6 * u, y: 5 * u, width: 4 * u, height: 14 * u)), with: .color(.white))
                context.fill(Path(CGRect(x: 14 * u, y: 5 * u, width: 4 * u, height: 14 * u)), with: .color(.white))
            } else {
                var path = Path()
                path.move(to: CGPoint(x: 8 * u, y: 5 * u))
                path.addLine(to: CGPoint(x: 8 * u, y: 19 * u))
                path.addLine(to: CGPoint(x: 19 * u, y: 12 * u))
                path.closeSubpath()
                context.fill(path, with: .color(.white))
            }
        }
    }
}
