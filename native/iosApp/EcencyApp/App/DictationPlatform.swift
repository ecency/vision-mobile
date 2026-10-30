import AVFoundation
import EcencyCore
import Foundation

/// The microphone for dictation: AAC in an .m4a file (RN's expo-audio HIGH_QUALITY preset), written to a folder of
/// its own in Application Support, which is private and never backed up. The shared model decides when to record
/// (started in the foreground only, it goes on in the background) and deletes each clip once its transcript arrived.
final class IosDictationRecorder: NSObject, DictationRecorder {
    /// The server's `max_bytes` for a clip.
    static let maxBytes = 25 * 1024 * 1024

    let directory: URL
    private var recorder: AVAudioRecorder?
    private var file: URL?

    init(directory: URL = IosDictationRecorder.defaultDirectory()) {
        self.directory = directory
    }

    static func defaultDirectory() -> URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("dictation", isDirectory: true)
    }

    /// Creates the folder (Application Support: not purged by the system) and keeps it out of backups.
    private func prepareDirectory() throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var folder = directory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? folder.setResourceValues(values)
    }

    /// What the audio session was before a recording, put back afterwards.
    private var previous: (category: AVAudioSession.Category, mode: AVAudioSession.Mode, options: AVAudioSession.CategoryOptions)?

    func hasPermission() -> Bool {
        if #available(iOS 17.0, *) { return AVAudioApplication.shared.recordPermission == .granted }
        return AVAudioSession.sharedInstance().recordPermission == .granted
    }

    /// The system's microphone prompt; `answer` runs on the main thread.
    static func requestPermission(_ answer: @escaping (Bool) -> Void) {
        let done: (Bool) -> Void = { granted in DispatchQueue.main.async { answer(granted) } }
        if #available(iOS 17.0, *) {
            AVAudioApplication.requestRecordPermission(completionHandler: done)
        } else {
            AVAudioSession.sharedInstance().requestRecordPermission(done)
        }
    }

    func start(ownerTag: String) -> Bool {
        cancel()
        // the tag names the account without spelling it (shared DictationClips.tag): only that form reaches a file name
        guard hasPermission(), DictationClips.shared.isTag(text: ownerTag) else { return false }
        do {
            try prepareDirectory()
            let session = AVAudioSession.sharedInstance()
            if previous == nil { previous = (session.category, session.mode, session.categoryOptions) }
            // iOS records nothing unless the session allows it
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try session.setActive(true)
            let target = directory.appendingPathComponent(ownerTag + "_" + UUID().uuidString + ".m4a")
            let settings: [String: Any] = [
                AVFormatIDKey: Int(kAudioFormatMPEG4AAC), AVSampleRateKey: 44_100, AVNumberOfChannelsKey: 1,
                AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
            ]
            let r = try AVAudioRecorder(url: target, settings: settings)
            guard r.record() else {
                r.deleteRecording()
                releaseSession()
                return false
            }
            recorder = r
            file = target
            return true
        } catch {
            cancel()
            return false
        }
    }

    func stop() -> String? {
        guard let r = recorder, let target = file else { return nil }
        recorder = nil
        file = nil
        r.stop()
        releaseSession()
        let size = (try? FileManager.default.attributesOfItem(atPath: target.path)[.size] as? Int) ?? 0
        guard size > 0 else {
            try? FileManager.default.removeItem(at: target)
            return nil
        }
        return target.lastPathComponent
    }

    func cancel() {
        if let r = recorder {
            r.stop()
            r.deleteRecording()
            releaseSession()
        }
        if let target = file { try? FileManager.default.removeItem(at: target) }
        recorder = nil
        file = nil
    }

    private func releaseSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setActive(false, options: [.notifyOthersOnDeactivation])
        // the app's audio (players) gets the session back as it was, not left in the recording category
        if let before = previous { try? session.setCategory(before.category, mode: before.mode, options: before.options) }
        previous = nil
    }

    /// Only files of this recorder's own folder are ever touched, whatever `path` says.
    private func own(_ name: String) -> URL? {
        guard name.hasSuffix(".m4a"), name != ".m4a", !name.contains("/"), !name.contains("\\") else { return nil }
        let url = directory.appendingPathComponent(name).standardizedFileURL.resolvingSymlinksInPath()
        let home = directory.standardizedFileURL.resolvingSymlinksInPath()
        return url.deletingLastPathComponent().path == home.path ? url : nil
    }

    func read(path: String) -> KotlinByteArray? {
        guard let url = own(path), let size = try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int,
              size > 0, size <= IosDictationRecorder.maxBytes, let data = try? Data(contentsOf: url) else { return nil }
        let bytes = KotlinByteArray(size: Int32(data.count))
        data.withUnsafeBytes { raw in
            let source = raw.bindMemory(to: Int8.self)
            for i in 0 ..< data.count { bytes.set(index: Int32(i), value: source[i]) }
        }
        return bytes
    }

    func delete(path: String) {
        if let url = own(path) { try? FileManager.default.removeItem(at: url) }
    }

    func clipNames() -> [String] {
        let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        let current = file?.resolvingSymlinksInPath().path
        return files.filter { $0.resolvingSymlinksInPath().path != current }.map { $0.lastPathComponent }
    }
}

/// The background side of a recording (README "Background media"): the `.playAndRecord` session stays active in the
/// background with the `audio` background mode, and the system shows its recording indicator; nothing else is needed
/// to keep it. A call or Siri taking the microphone stops the recording, which then waits unsent for the user's yes.
final class IosDictationHost: NSObject, DictationHost {
    private weak var sessions: DictationSessions?
    private var observer: NSObjectProtocol?

    init(sessions: DictationSessions) {
        self.sessions = sessions
    }

    func recordingStarted() -> Bool {
        guard observer == nil else { return true }
        observer = NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            let began = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) == AVAudioSession.InterruptionType.began.rawValue
            guard began else { return }
            MainActor.assumeIsolated { self?.sessions?.systemInterrupted() }
        }
        // the audio background mode and the system's recording indicator need nothing that can be refused
        return true
    }

    func recordingEnded() {
        if let observer { NotificationCenter.default.removeObserver(observer) }
        observer = nil
    }
}

/// Debug fake-data runs and UI tests: a recorder without a microphone, so no audio exists anywhere.
final class DemoDictationRecorder: NSObject, DictationRecorder {
    private var clip: String?
    private var recording = false

    func hasPermission() -> Bool { true }
    private var tag = ""
    func start(ownerTag: String) -> Bool { tag = ownerTag; recording = true; return true }
    func stop() -> String? {
        guard recording else { return nil }
        recording = false
        clip = tag + "_demo-clip.m4a"
        return clip
    }
    func cancel() { recording = false }
    func read(path: String) -> KotlinByteArray? { path == clip ? KotlinByteArray(size: 8) : nil }
    func delete(path: String) { if path == clip { clip = nil } }
    func clipNames() -> [String] { clip.map { [$0] } ?? [] }
}
