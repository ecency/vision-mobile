import AVFoundation
import EcencyCore
import XCTest
@testable import EcencyApp

/// The iOS side of the editor's paid extras: the dictation recorder's files and the permission text.
@MainActor
final class EditorExtrasTests: XCTestCase {
    private func temp() throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("dictation-test-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: dir) }
        return dir
    }

    func testClipsLiveInApplicationSupportByNameAndOnlyTheyAreReadOrDeleted() throws {
        XCTAssertTrue(IosDictationRecorder.defaultDirectory().path.contains("/Library/Application Support/"), "not a folder the system purges")
        let dir = try temp()
        let recorder = IosDictationRecorder(directory: dir)
        let clip = dir.appendingPathComponent("a.m4a")
        let other = dir.appendingPathComponent("b.m4a")
        try Data([1, 2, 3]).write(to: clip)
        try Data([4]).write(to: other)
        let outside = FileManager.default.temporaryDirectory.appendingPathComponent("outside-\(UUID().uuidString).m4a")
        try Data([9]).write(to: outside)
        addTeardownBlock { try? FileManager.default.removeItem(at: outside) }
        let bytes = try XCTUnwrap(recorder.read(path: "a.m4a"))
        XCTAssertEqual(bytes.size, 3)
        XCTAssertEqual(bytes.get(index: 2), 3)
        XCTAssertNil(recorder.read(path: outside.path), "a path outside the folder is never read")
        XCTAssertNil(recorder.read(path: "../" + outside.lastPathComponent))
        XCTAssertNil(recorder.read(path: clip.path), "an absolute path is not a clip name")
        recorder.delete(path: outside.path)
        XCTAssertTrue(FileManager.default.fileExists(atPath: outside.path))
        // the clips are listed by name; which of them go is the shared rule (DictationClips)
        XCTAssertEqual(Set(recorder.clipNames()), ["a.m4a", "b.m4a"])
        let clips = DictationClips.shared
        let alices = dir.appendingPathComponent(clips.tag(owner: "spike-alice") + "_one.m4a")
        let bobs = dir.appendingPathComponent(clips.tag(owner: "spike-bob") + "_two.m4a")
        try Data([5]).write(to: alices)
        try Data([6]).write(to: bobs)
        // bob leaves: his clip and the untagged ones of an older build go, alice's waiting clip stays
        clips.purgeLeft(recorder: recorder, owners: ["spike-bob"])
        XCTAssertTrue(FileManager.default.fileExists(atPath: alices.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: bobs.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: other.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: clip.path))
        try Data([1, 2, 3]).write(to: clip)
        XCTAssertFalse(recorder.start(ownerTag: "../evil"), "a name that is not a tag never becomes part of a path")
        recorder.delete(path: "a.m4a")
        XCTAssertNil(recorder.read(path: "a.m4a"))
    }

    func testNothingRecordsWithoutThePermissionAndTheUsageTextIsThere() throws {
        let usage = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "NSMicrophoneUsageDescription") as? String)
        XCTAssertTrue(usage.contains("dictate"))
        let recorder = IosDictationRecorder(directory: try temp())
        // the test host was never granted the microphone
        if !recorder.hasPermission() {
            XCTAssertFalse(recorder.start(ownerTag: DictationClips.shared.tag(owner: "spike-alice")))
            XCTAssertNil(recorder.stop())
        }
    }

    func testAVideoFileIsReadPieceByPiece() throws {
        let dir = try temp()
        let url = dir.appendingPathComponent("clip.mp4")
        let bytes = Data((0 ..< 10_000).map { UInt8($0 % 127) })
        try bytes.write(to: url)
        let source = try XCTUnwrap(FileVideoSource(url: url, name: "clip.mp4"))
        XCTAssertEqual(source.size, 10_000)
        let piece = try XCTUnwrap(source.read(offset: 4000, length: 4000))
        XCTAssertEqual(EditorVideos.data(piece), bytes.subdata(in: 4000 ..< 8000))
        XCTAssertNil(source.read(offset: 9000, length: 4000), "a piece that is not all there is no piece")
        try FileManager.default.removeItem(at: url)
        XCTAssertNil(source.read(offset: 0, length: 10))
        XCTAssertNil(FileVideoSource(url: url, name: "gone.mp4"))
    }

    func testPickedVideosAreCopiedToTheirOwnFolderAndPurged() throws {
        let lent = try temp().appendingPathComponent("lent.mov")
        try Data([1, 2, 3]).write(to: lent)
        let kept = try XCTUnwrap(EditorVideos.keep(lent, suggestedName: "IMG_1"))
        XCTAssertEqual(kept.deletingLastPathComponent().lastPathComponent, "video-upload")
        XCTAssertTrue(kept.path.contains(NSTemporaryDirectory().trimmingCharacters(in: CharacterSet(charactersIn: "/"))))
        XCTAssertEqual(kept.pathExtension, "mov")
        EditorVideos.purge()
        XCTAssertFalse(FileManager.default.fileExists(atPath: kept.path))
    }

    func testAVideosCopyGoesWhenItsUploadLetsGoAndOnlyThen() async throws {
        let lent = try temp().appendingPathComponent("recorded.mov")
        try Data(repeating: 7, count: 2000).write(to: lent)
        let kept = try XCTUnwrap(EditorVideos.keep(lent, suggestedName: nil))
        let other = try XCTUnwrap(EditorVideos.keep(lent, suggestedName: nil))
        addTeardownBlock { try? FileManager.default.removeItem(at: other) }
        let found = await EditorVideos.describe(kept, name: "My clip (1).mov")
        let video = try XCTUnwrap(found)
        XCTAssertEqual(video.ref, kept.path, "the preview plays the app's own copy")
        XCTAssertEqual(video.source.size, 2000)
        XCTAssertFalse(video.source.name.contains(" "))
        XCTAssertTrue(FileManager.default.fileExists(atPath: kept.path))
        video.letGo()
        XCTAssertFalse(FileManager.default.fileExists(atPath: kept.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: other.path), "another upload's copy is not touched")
        video.letGo()
        // a file that cannot be described leaves nothing behind
        let empty = EditorVideos.directory.appendingPathComponent("empty.mov")
        try Data().write(to: empty)
        let described = await EditorVideos.describe(empty, name: nil)
        XCTAssertNil(described)
    }

    func testThePreviewIsMutedPausesAndIsReleased() throws {
        let url = try temp().appendingPathComponent("clip.mp4")
        try Data(repeating: 1, count: 100).write(to: url)
        let coordinator = LoopingVideoView.Coordinator()
        let view = LoopingVideoView.PlayerView()
        coordinator.start(url: url, in: view)
        let player = try XCTUnwrap(coordinator.player)
        XCTAssertTrue(player.isMuted, "RN volume={0}")
        XCTAssertTrue(view.playerLayer.player === player)
        coordinator.set(playing: true)
        XCTAssertNotEqual(player.timeControlStatus, .paused)
        // RN paused={isUploading}; also the lock and the background
        coordinator.set(playing: false)
        XCTAssertEqual(player.timeControlStatus, .paused)
        LoopingVideoView.dismantleUIView(view, coordinator: coordinator)
        XCTAssertNil(coordinator.player)
        XCTAssertNil(view.playerLayer.player)
    }

    func testRecordingAVideoHasItsUsageTexts() throws {
        let camera = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "NSCameraUsageDescription") as? String)
        XCTAssertTrue(camera.contains("video"))
        let microphone = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "NSMicrophoneUsageDescription") as? String)
        XCTAssertTrue(microphone.contains("video"))
        XCTAssertEqual(VideoUploadModel.companion.RECORD, "Record Video")
        XCTAssertEqual(VideoUploadModel.companion.CHOOSE, "Choose from Library")
    }

    func testFrameTimesFollowRn() {
        let times = VideoUploadModel.companion.thumbTimesMillis(durationMillis: KotlinLong(value: 20_000)).map { $0.int64Value }
        XCTAssertEqual(times, [2000, 6000, 10000, 14000, 18000])
    }

    func testTheDemoRecorderHoldsNoAudio() throws {
        let demo = DemoDictationRecorder()
        XCTAssertNil(demo.stop())
        let tag = DictationClips.shared.tag(owner: "spike-alice")
        XCTAssertTrue(demo.start(ownerTag: tag))
        let clip = try XCTUnwrap(demo.stop())
        XCTAssertEqual(clip, tag + "_demo-clip.m4a")
        XCTAssertNotNil(demo.read(path: clip))
        DictationClips.shared.purgeLeft(recorder: demo, owners: ["spike-alice"])
        XCTAssertNil(demo.read(path: clip))
    }
}
