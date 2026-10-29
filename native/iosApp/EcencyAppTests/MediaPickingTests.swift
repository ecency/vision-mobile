import EcencyCore
import ImageIO
import UniformTypeIdentifiers
import XCTest
@testable import EcencyApp

/// The iOS media picking layer over the shared allow-list (RN #3564 / #3561): which types the picker and the Files
/// importer take, which a provider's item resolves to, and that what is uploaded carries no location.
final class MediaPickingTests: XCTestCase {
    func testThePickersAskForTheSharedTypesOnly() {
        XCTAssertEqual(MediaPicking.contentTypes(.image), [.jpeg, .png, .gif, .webP, .heic, .heif])
        XCTAssertEqual(MediaPicking.contentTypes(.video), [.mpeg4Movie, .quickTimeMovie])
    }

    func testAProvidersItemResolvesToAnAllowedTypeOrNothing() {
        XCTAssertEqual(MediaPicking.allowedType(.image, registered: ["public.heic"]), UTType.heic.identifier)
        XCTAssertEqual(MediaPicking.allowedType(.image, registered: ["public.tiff", "public.jpeg"]), UTType.jpeg.identifier, "the first allowed one")
        XCTAssertNil(MediaPicking.allowedType(.image, registered: ["public.tiff"]))
        XCTAssertNil(MediaPicking.allowedType(.image, registered: ["com.adobe.raw-image", "public.svg-image"]))
        XCTAssertNil(MediaPicking.allowedType(.image, registered: ["com.apple.quicktime-movie"]), "never a video for an image surface")
        XCTAssertEqual(MediaPicking.allowedType(.video, registered: ["com.apple.quicktime-movie"]), UTType.quickTimeMovie.identifier)
        XCTAssertEqual(MediaPicking.allowedType(.video, registered: ["public.mpeg-4"]), UTType.mpeg4Movie.identifier)
        XCTAssertNil(MediaPicking.allowedType(.video, registered: ["public.avi", "org.webmproject.webm"]))
        XCTAssertNil(MediaPicking.allowedType(.video, registered: ["public.jpeg"]))
    }

    func testFilesAreRefusedWithAMessageThatNamesWhatIsAccepted() {
        let images = MediaTypes.shared.IMAGE_TYPES_MESSAGE
        XCTAssertNil(MediaPicking.refusal(.image, type: .jpeg, size: 80_000_000), "a large still image is made smaller on the device")
        XCTAssertNil(MediaPicking.refusal(.image, type: UTType(filenameExtension: "HEIC"), size: 1000))
        XCTAssertEqual(MediaPicking.refusal(.image, type: .tiff, size: 1000), images)
        XCTAssertEqual(MediaPicking.refusal(.image, type: .pdf, size: 1000), images)
        XCTAssertEqual(MediaPicking.refusal(.image, type: nil, size: 1000), images)
        let limit = Int(ImageUploader.companion.MAX_BYTES)
        XCTAssertNil(MediaPicking.refusal(.image, type: .gif, size: limit))
        XCTAssertEqual(MediaPicking.refusal(.image, type: .gif, size: limit + 1), MediaTypes.shared.GIF_TOO_LARGE)
        XCTAssertNil(MediaPicking.refusal(.video, type: UTType(filenameExtension: "mov"), size: 5_000_000_000))
        XCTAssertEqual(MediaPicking.refusal(.video, type: UTType(filenameExtension: "webm"), size: 1000), MediaTypes.shared.VIDEO_TYPES_MESSAGE)
    }

    func testEverySurfaceTakesOneKind() {
        XCTAssertTrue(MediaSurface.editor.allowsMultiple)
        XCTAssertFalse(MediaSurface.wave.allowsMultiple)
        XCTAssertFalse(MediaSurface.chat.allowsMultiple)
        XCTAssertEqual(MediaSurface.video.kind, .video)
        XCTAssertEqual(MediaSurface.videoThumbnail.kind, .image)
        XCTAssertEqual(MediaSurface.avatar.kind, .image)
    }

    /// A HEIC with a GPS position (made here, never a real photo) comes out as a JPEG without it.
    func testAHeicIsConvertedToAJpegWithoutItsLocation() throws {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 64, height: 48))
        let cg = try XCTUnwrap(renderer.image { ctx in UIColor.systemPink.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 64, height: 48)) }.cgImage)
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.heic.identifier as CFString, 1, nil) else {
            throw XCTSkip("no HEIC encoder on this simulator")
        }
        let gps: [CFString: Any] = [kCGImagePropertyGPSLatitude: 54.6872, kCGImagePropertyGPSLatitudeRef: "N", kCGImagePropertyGPSLongitude: 25.2797, kCGImagePropertyGPSLongitudeRef: "E"]
        CGImageDestinationAddImage(dest, cg, [kCGImagePropertyGPSDictionary: gps] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { throw XCTSkip("no HEIC encoder on this simulator") }
        let picked = try XCTUnwrap(EditorImages.prepare(data: out as Data, name: "photo.heic", index: 0))
        XCTAssertEqual(picked.mime, "image/jpeg")
        XCTAssertTrue(picked.name.hasSuffix(".jpg"))
        let source = try XCTUnwrap(CGImageSourceCreateWithData(PickedImages.shared.data(image: picked) as CFData, nil))
        let props = try XCTUnwrap(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
        XCTAssertNil(props[kCGImagePropertyGPSDictionary])
    }

    /// A load that finishes before its progress is handed over (a local file answers at once) leaves no bar behind.
    func testTheLoadingBarNeverOutlivesItsLoad() {
        let loading = MediaLoading()
        func settle() { RunLoop.main.run(until: Date().addingTimeInterval(0.1)) }
        let early = UUID()
        loading.finish(early)
        loading.track(Progress(totalUnitCount: 10), early)
        settle()
        XCTAssertNil(loading.fraction, "finished first: no bar")
        let slow = UUID()
        let progress = Progress(totalUnitCount: 10)
        loading.track(progress, slow)
        settle()
        XCTAssertEqual(loading.fraction, 0)
        progress.completedUnitCount = 5
        settle()
        XCTAssertEqual(loading.fraction ?? 0, 0.5, accuracy: 0.001)
        loading.finish(slow, error: MediaPicking.loadFailed)
        settle()
        XCTAssertNil(loading.fraction)
        XCTAssertEqual(loading.message, MediaPicking.loadFailed)
        // cancel stops the download and the bar
        let next = Progress(totalUnitCount: 10)
        loading.track(next, UUID())
        settle()
        loading.cancel()
        settle()
        XCTAssertTrue(next.isCancelled)
        XCTAssertNil(loading.fraction)
    }

    /// A cancelled or failed Files importer ends its request (the wave attach waits on it).
    func testACancelledImporterEndsTheRequestForEveryKind() {
        var images: [PickedImage]?
        MediaFileImporter.finish(.wave, urls: nil, onImages: { _, picked in images = picked }, onVideo: { _, _ in XCTFail("an image surface") })
        XCTAssertEqual(images?.count, 0)
        var video: (URL?, String?)?
        MediaFileImporter.finish(.video, urls: [], onImages: { _, _ in XCTFail("a video surface") }, onVideo: { url, name in video = (url, name) })
        XCTAssertNotNil(video)
        XCTAssertNil(video?.0)
    }

    func testMoreFilesThanTheSurfaceTakesAreCutWithAMessage() {
        let urls = (1...7).map { URL(fileURLWithPath: "/tmp/\($0).jpg") }
        let (taken, said) = MediaPicking.limited(urls, .editor)
        XCTAssertEqual(taken, Array(urls.prefix(5)))
        XCTAssertEqual(said, "You can select up to 5 images at a time")
        XCTAssertNil(MediaPicking.limited(Array(urls.prefix(5)), .editor).1)
        XCTAssertEqual(MediaPicking.limited(urls, .wave).0.count, 1)
    }
}
