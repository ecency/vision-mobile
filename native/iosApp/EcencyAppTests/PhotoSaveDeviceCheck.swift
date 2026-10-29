import XCTest
@testable import EcencyApp

/// One-off device check of the image viewer's save (not part of the suite: it fetches real images and writes to the
/// simulator's library). Run with `TEST_RUNNER_GAPS2_PHOTO_CHECK=1`; the result is checked in the Photos app.
final class PhotoSaveDeviceCheck: XCTestCase {
    func testARealJpegAndPngAreSaved() async throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["GAPS2_PHOTO_CHECK"] == "1", "device check only")
        for url in ["https://upload.wikimedia.org/wikipedia/commons/3/3f/JPEG_example_flower.jpg",
                    "https://upload.wikimedia.org/wikipedia/commons/4/47/PNG_transparency_demonstration_1.png"] {
            let message = await PhotoSaver.save(URL(string: url)!)
            XCTAssertNil(message, url)
        }
    }
}
