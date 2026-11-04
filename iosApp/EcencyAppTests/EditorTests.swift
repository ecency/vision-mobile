import EcencyCore
import ImageIO
import UniformTypeIdentifiers
import XCTest
@testable import EcencyApp

/// The iOS side of the editor: image preparation, draft files, dates, and the shared types as Swift sees them.
@MainActor
final class EditorTests: XCTestCase {
    private func image(_ width: CGFloat, _ height: CGFloat) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).image { ctx in
            UIColor.systemTeal.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        }
    }

    /// A JPEG with GPS, camera and orientation metadata, made here (never a real photo).
    private func jpegWithMetadata(_ width: CGFloat, _ height: CGFloat, orientation: Int = 6) throws -> Data {
        let cg = try XCTUnwrap(image(width, height).cgImage)
        let out = NSMutableData()
        let dest = try XCTUnwrap(CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil))
        let props: [CFString: Any] = [
            kCGImagePropertyOrientation: orientation,
            kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 54.6872, kCGImagePropertyGPSLatitudeRef: "N", kCGImagePropertyGPSLongitude: 25.2797, kCGImagePropertyGPSLongitudeRef: "E"],
            kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFMake: "SpikeCam", kCGImagePropertyTIFFModel: "Fixture 1"],
            kCGImagePropertyExifDictionary: [kCGImagePropertyExifDateTimeOriginal: "2026:10:06 12:00:00", kCGImagePropertyExifUserComment: "secret note"],
        ]
        CGImageDestinationAddImage(dest, cg, props as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(dest))
        return out as Data
    }

    private func properties(_ picked: PickedImage) throws -> [CFString: Any] {
        let bytes = picked.bytes
        var data = Data(count: Int(bytes.size))
        for i in 0 ..< Int(bytes.size) { data[i] = UInt8(bitPattern: bytes.get(index: Int32(i))) }
        let source = try XCTUnwrap(CGImageSourceCreateWithData(data as CFData, nil))
        return try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
    }

    func testGpsAndCameraMetadataNeverLeaveTheDeviceSmallOrLarge() throws {
        for size in [(800.0, 600.0), (4000.0, 3000.0)] {
            let source = try jpegWithMetadata(size.0, size.1)
            let before = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(try XCTUnwrap(CGImageSourceCreateWithData(source as CFData, nil)), 0, nil) as? [CFString: Any])
            XCTAssertNotNil(before[kCGImagePropertyGPSDictionary], "the fixture really carries a position")
            let prepared = try XCTUnwrap(EditorImages.prepare(data: source, name: "IMG_1.jpg", index: 0))
            let after = try properties(prepared)
            XCTAssertNil(after[kCGImagePropertyGPSDictionary], "no position in what is uploaded (\(size.0) px)")
            XCTAssertNil((after[kCGImagePropertyTIFFDictionary] as? [CFString: Any])?[kCGImagePropertyTIFFMake])
            let exif = after[kCGImagePropertyExifDictionary] as? [CFString: Any]
            XCTAssertNil(exif?[kCGImagePropertyExifDateTimeOriginal])
            XCTAssertNil(exif?[kCGImagePropertyExifUserComment])
            // orientation 6 (rotated) is applied to the pixels: the upright image is portrait
            XCTAssertLessThan(prepared.width, prepared.height)
            XCTAssertTrue((after[kCGImagePropertyOrientation] as? Int ?? 1) == 1)
        }
    }

    func testTheAvatarIsTheUprightCentredSquareAt512WithoutMetadata() throws {
        // 4000 x 3000 turned by orientation 6: upright 1440 x 1920 after prepare, its centred square scaled to 512
        let prepared = try XCTUnwrap(EditorImages.prepare(data: try jpegWithMetadata(4000, 3000), name: "IMG_2.heic", index: 0))
        let avatar = try XCTUnwrap(EditorImages.square(prepared))
        XCTAssertEqual(avatar.width, 512)
        XCTAssertEqual(avatar.height, 512)
        XCTAssertEqual(avatar.mime, "image/jpeg")
        XCTAssertEqual(avatar.name, "IMG_2.jpg")
        let props = try properties(avatar)
        XCTAssertEqual(props[kCGImagePropertyPixelWidth] as? Int, 512)
        XCTAssertNil(props[kCGImagePropertyGPSDictionary])
        // a small picture is cropped but never upscaled
        let small = try XCTUnwrap(EditorImages.prepare(data: try jpegWithMetadata(300, 200, orientation: 1), name: "s.jpg", index: 0))
        let smallAvatar = try XCTUnwrap(EditorImages.square(small))
        XCTAssertEqual(smallAvatar.width, 200)
        XCTAssertEqual(smallAvatar.height, 200)
    }

    func testLargeImagesAreScaledTo1920AndOversizedSourcesAreRefusedBeforeDecoding() throws {
        let large = try XCTUnwrap(image(4000, 3000).jpegData(compressionQuality: 0.9))
        let prepared = try XCTUnwrap(EditorImages.prepare(data: large, name: "IMG_0001.HEIC", index: 0))
        XCTAssertEqual(prepared.width, 1920)
        XCTAssertEqual(prepared.height, 1440)
        XCTAssertEqual(prepared.mime, "image/jpeg")
        XCTAssertEqual(prepared.name, "IMG_0001.jpg")
        XCTAssertLessThan(Int(prepared.bytes.size), large.count)

        let small = try XCTUnwrap(image(100, 50).pngData())
        let kept = try XCTUnwrap(EditorImages.prepare(data: small, name: "tiny.png", index: 0))
        XCTAssertEqual(kept.mime, "image/png")
        XCTAssertEqual(kept.name, "tiny.png")
        XCTAssertEqual(kept.width, 100)

        XCTAssertNil(EditorImages.prepare(data: Data("not an image".utf8), name: "x.jpg", index: 0))
        XCTAssertTrue(try XCTUnwrap(EditorImages.prepare(data: small, name: nil, index: 3)).name.hasSuffix("_3.png"))

        // from a file: the same result, and a file over the source limit is refused by its size alone
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("img-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let file = dir.appendingPathComponent("photo.jpg")
        try large.write(to: file)
        XCTAssertEqual(try XCTUnwrap(EditorImages.prepare(file: file, name: nil, index: 0)).width, 1920)
        let huge = dir.appendingPathComponent("huge.jpg")
        FileManager.default.createFile(atPath: huge.path, contents: nil)
        let handle = try FileHandle(forWritingTo: huge)
        try handle.truncate(atOffset: UInt64(EditorImages.maxSourceBytes + 1)) // sparse: no real 150 MB written
        try handle.close()
        XCTAssertNil(EditorImages.prepare(file: huge, name: nil, index: 0))
        // the bulk copy into the core keeps every byte
        let copied = PickedImages.shared.from(data: Data([1, 2, 3, 250]), name: "b", mime: "m", width: 1, height: 1)
        XCTAssertEqual((0 ..< 4).map { copied.bytes.get(index: Int32($0)) }, [1, 2, 3, -6])
    }

    func testDraftFilesRoundTripHideTheirKeysClearPerAccountAndStayOutOfBackups() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("drafts-\(UUID().uuidString)").path
        defer { try? FileManager.default.removeItem(atPath: dir) }
        let storage = FileDraftStorage(directory: dir)
        let slot = LocalDrafts.companion.NEW_POST
        XCTAssertNil(storage.read(account: "spike-alice", slot: slot))
        storage.write(account: "spike-alice", slot: slot, value: "{\"title\":\"Hello 🐝\"}")
        storage.write(account: "spike-alice", slot: "reply:a/b", value: "r")
        storage.write(account: "spike-bob", slot: slot, value: "bob")
        XCTAssertEqual(storage.read(account: "spike-alice", slot: slot), "{\"title\":\"Hello 🐝\"}")
        XCTAssertTrue(storage.isExcludedFromBackup, "unpublished text is never part of a backup")
        let all = try FileManager.default.subpathsOfDirectory(atPath: dir)
        XCTAssertFalse(all.contains { $0.contains("spike") || $0.contains("reply") }, "account and post names never appear in a path")
        storage.clear(account: "spike-alice")
        XCTAssertNil(storage.read(account: "spike-alice", slot: slot))
        XCTAssertNil(storage.read(account: "spike-alice", slot: "reply:a/b"))
        XCTAssertEqual(storage.read(account: "spike-bob", slot: slot), "bob", "another account keeps its drafts")
        storage.clearAll()
        XCTAssertNil(storage.read(account: "spike-bob", slot: slot))
        // a folder recreated after a wipe is excluded again
        storage.write(account: "spike-bob", slot: slot, value: "again")
        XCTAssertTrue(storage.isExcludedFromBackup)
    }

    func testScheduleAndPollDatesAreWrittenLikeRn() throws {
        let date = Date(timeIntervalSince1970: 1_791_288_000.783)
        XCTAssertEqual(scheduleString(date, zone: try XCTUnwrap(TimeZone(secondsFromGMT: 7200))), "2026-10-06T14:00:00+02:00")
        XCTAssertEqual(scheduleString(date, zone: try XCTUnwrap(TimeZone(secondsFromGMT: 0))), "2026-10-06T12:00:00+00:00")
        XCTAssertEqual(isoString(date), "2026-10-06T12:00:00.783Z")
        XCTAssertEqual(EditorMeta.shared.isoMillis(iso: "2026-10-06T14:00:00+02:00")?.int64Value, 1_791_288_000_000)
    }

    func testFormatsCountInUtf16LikeTheTextView() {
        // the text view reports NSRange (UTF-16); an emoji is two units
        let text = "a😀b"
        XCTAssertEqual((text as NSString).length, 4)
        let bold = EditorFormats.shared.wrap(text: text, selection: CoreTextSelection(start: 1, end: 3), wrapper: EditorFormats.shared.BOLD)
        XCTAssertEqual(bold.text, "a**😀**b")
        XCTAssertEqual(bold.selection.start, 7)
        let media = EditorFormats.shared.media(
            text: text, selection: CoreTextSelection(start: 4, end: 4),
            items: [MediaInsert(url: "", filename: "p.jpg", text: "", status: .uploading, isVideo: false)], otherPending: []
        )
        XCTAssertEqual(media?.text, "a😀b\n![](Uploading... p.jpg)\n")
    }

    func testEditorScreensRoundTripAndAreGated() {
        let screens: [AppScreen] = [
            AppScreenEditor(), AppScreenEditor.companion.of(arg: "hive-125125"), AppScreenEditor.companion.of(arg: "alice/post"),
            AppScreenEditor.companion.of(arg: "reply:alice/post"), AppScreenEditor.companion.of(arg: "draft:d1"), AppScreenDrafts(tab: 1),
        ]
        var nav = NavState.companion.decodeJoined(saved: "")
        for s in screens { nav = nav.push(screen: s) }
        let decoded = NavState.companion.decodeJoined(saved: NavState.companion.encodeJoined(state: nav)).screens
        XCTAssertEqual(decoded.count, screens.count)
        for (a, b) in zip(decoded, screens) { XCTAssertTrue((a as AnyObject).isEqual(b as AnyObject), "\(a) != \(b)") }
        XCTAssertTrue(RouteName.editor.built)
        XCTAssertTrue(RouteName.drafts.built)
        XCTAssertTrue(nav.pushGated(screen: AppScreenEditor(), loggedIn: false).top is AppScreenLogin)
        XCTAssertTrue(nav.afterAccountChange().screens.isEmpty, "editors and drafts belong to the account that opened them")
        XCTAssertEqual((AppScreenEditor.companion.of(arg: "alice/post")).args.kind, .editPost)
    }

    func testTagRulesAndRewardLabelsFromSwift() {
        let typed = EditorTags.shared.input(tags: ["photo"], raw: "Bees hive-125125 photo ", consumed: "")
        // text that already became chips and is still in the field is never parsed again
        XCTAssertEqual(EditorTags.shared.input(tags: ["a"], raw: "a b", consumed: "a ").rest, "b")
        XCTAssertEqual(typed.tags, ["hive-125125", "photo", "bees"])
        XCTAssertEqual(typed.toasts, ["Community Selected", "Tag exists already"])
        XCTAssertEqual(EditorTags.shared.warning(tags: ["a-b-c"]), "Use one dash in each tag")
        XCTAssertEqual(RewardType.entries.map(\.label), ["Stake 50%", "Stake 100%", "Decline Reward"])
    }
}
