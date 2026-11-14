import EcencyCore
import ImageIO
import SwiftUI
import UniformTypeIdentifiers
import XCTest
@testable import EcencyApp

/// Counts network fetches made through `ImageLoader.shared` (installed with `setFetch`).
final class FetchCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var urls: [String] = []
    let png: Data

    init() {
        png = UIGraphicsImageRenderer(size: CGSize(width: 40, height: 30)).image { ctx in
            UIColor.orange.setFill()
            ctx.fill(CGRect(x: 0, y: 0, width: 40, height: 30))
        }.pngData()!
    }

    var all: [String] { lock.withLock { urls } }

    /// `example.invalid` hosts fail (nil), everything else answers with a small PNG.
    var fetch: ImageLoader.Fetch {
        { [self] url in
            lock.withLock { urls.append(url.absoluteString) }
            try? await Task.sleep(for: .milliseconds(20))
            return url.host == "example.invalid" ? nil : png
        }
    }
}

@MainActor
final class ImagePipelineTests: XCTestCase {
    private var counter = FetchCounter()
    private var window: UIWindow?

    override func setUp() async throws {
        counter = FetchCounter()
        ImageLoader.shared.setFetch(counter.fetch)
        RevealedImages.shared.clear()
        PostTextCache.shared.removeAll()
        ChipAttachment.images.removeAllObjects()
    }

    override func tearDown() async throws {
        window?.isHidden = true
        window = nil
        ImageLoader.shared.setFetch(ImageLoader.networkFetch)
        RevealedImages.shared.clear()
    }

    private func host<V: View>(_ view: V, height: CGFloat = 844) -> UIHostingController<V> {
        let host = UIHostingController(rootView: view)
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow()
        window.frame = CGRect(x: 0, y: 0, width: 390, height: height)
        window.rootViewController = host
        window.makeKeyAndVisible()
        self.window = window
        return host
    }

    private func spin(_ seconds: Double) { RunLoop.main.run(until: Date().addingTimeInterval(seconds)) }

    private func find<T: UIView>(_ type: T.Type, in view: UIView) -> T? {
        if let hit = view as? T { return hit }
        for sub in view.subviews { if let hit = find(type, in: sub) { return hit } }
        return nil
    }

    // MARK: H1: one fetch per URL however often rows scroll in and out

    /// The review's long post: 350 blocks, every 7th an image (failing host), paragraphs with five
    /// distinct mention avatars (two of them failing) and a tag chip each.
    private func longPost() -> [any Block] {
        (0..<350).map { i -> any Block in
            if i % 7 == 6 { return BlockImage(src: "https://example.invalid/img-\(i).png", alt: nil, link: nil) }
            return BlockParagraph(inlines: [
                InlineText(text: "Paragraph \(i) lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor ", marks: []),
                InlineMention(author: "user\(i % 5)"),
                InlineText(text: " and more text after the chip so the paragraph wraps to a few lines ", marks: []),
                InlineTag(tag: "tag\(i)", filter: nil),
            ], style: BlockStyle.companion.Default)
        }
    }

    func testScrollingALongPostTwiceFetchesEachURLOnceAndReusesText() throws {
        let actions = PostActions(avatarURL: { name in
            // user3 and user4 avatars fail, the rest load: both paths must stay at one fetch.
            name == "user3" || name == "user4" ? "https://example.invalid/u/\(name)" : "https://example.com/u/\(name)"
        })
        let host = host(ScrollView { PostBodyView(blocks: longPost(), contentWidth: 358, actions: actions) })
        spin(0.3)
        let scroll = try XCTUnwrap(find(UIScrollView.self, in: host.view))

        var buildsAfterFirstPass = 0
        for pass in 0..<2 {
            var y: CGFloat = 0
            while y < scroll.contentSize.height - 844 {
                y += 120
                scroll.setContentOffset(CGPoint(x: 0, y: y), animated: false)
                scroll.layoutIfNeeded()
                spin(0.002)
            }
            while y > 0 {
                y -= 120
                scroll.setContentOffset(CGPoint(x: 0, y: max(0, y)), animated: false)
                scroll.layoutIfNeeded()
                spin(0.002)
            }
            if pass == 0 {
                spin(0.5)
                buildsAfterFirstPass = PostTextCache.shared.builds
            }
        }
        spin(0.5)

        let fetched = counter.all
        let unique = Set(fetched)
        // Review baseline: 991 requests for 56 unique URLs (848 for 5 avatars).
        XCTAssertEqual(fetched.count, unique.count, "every URL fetched once, got \(fetched.count) for \(unique.count)")
        XCTAssertEqual(unique.filter { $0.contains("/u/") }.count, 5)
        XCTAssertEqual(unique.filter { $0.contains("/img-") }.count, 50)
        // Review baseline: about 274 sizeThatFits and 565 chip renders per pass, every pass. Text is
        // built and measured once per distinct paragraph (300 here, one width), however often it scrolls in.
        let paragraphs = 300
        XCTAssertGreaterThan(buildsAfterFirstPass, 250)
        XCTAssertLessThanOrEqual(PostTextCache.shared.builds, paragraphs, "rows scrolling back in rebuilt their text")
        XCTAssertLessThanOrEqual(PostTextCache.shared.measures, paragraphs, "rows scrolling back in were re-measured")
    }

    func testConcurrentLoadsShareOneFetchAndFailuresAreRemembered() async {
        async let a = ImageLoader.shared.load("https://example.com/same.png", maxPixel: 100)
        async let b = ImageLoader.shared.load("https://example.com/same.png", maxPixel: 100)
        async let c = ImageLoader.shared.load("https://example.invalid/dead.png", maxPixel: 100)
        let results = await [a, b, c]
        XCTAssertNotNil(results[0])
        XCTAssertNotNil(results[1])
        XCTAssertNil(results[2])
        _ = await ImageLoader.shared.load("https://example.com/same.png", maxPixel: 100)
        _ = await ImageLoader.shared.load("https://example.invalid/dead.png", maxPixel: 100)
        XCTAssertEqual(counter.all.sorted(), ["https://example.com/same.png", "https://example.invalid/dead.png"])
    }

    // MARK: Decoding

    func testDecodeDownsamplesToTheDisplayWidthButKeepsTheNaturalSize() throws {
        let big = UIGraphicsImageRenderer(size: CGSize(width: 1200, height: 600), format: { let f = UIGraphicsImageRendererFormat(); f.scale = 1; return f }())
            .image { ctx in UIColor.blue.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 1200, height: 600)) }.pngData()!
        let loaded = try XCTUnwrap(ImageLoader.decode(big, maxPixel: 300))
        XCTAssertEqual(loaded.naturalSize, CGSize(width: 1200, height: 600))
        XCTAssertEqual(loaded.image.size.width, 300, accuracy: 1)
        let small = try XCTUnwrap(ImageLoader.decode(big, maxPixel: 5000))
        XCTAssertEqual(small.image.size.width, 1200, accuracy: 1, "never upscaled")
    }

    private func gif(frames: Int, side: Int, delays: [Double]? = nil) throws -> Data {
        let data = NSMutableData()
        let dest = try XCTUnwrap(CGImageDestinationCreateWithData(data, UTType.gif.identifier as CFString, frames, nil))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        for i in 0..<frames {
            let frame = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { ctx in
                UIColor(hue: CGFloat(i) / CGFloat(frames), saturation: 1, brightness: 1, alpha: 1).setFill()
                ctx.fill(CGRect(x: 0, y: 0, width: side, height: side))
            }
            let props = [kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFDelayTime: delays?[i] ?? 0.05]] as CFDictionary
            CGImageDestinationAddImage(dest, try XCTUnwrap(frame.cgImage), props)
        }
        XCTAssertTrue(CGImageDestinationFinalize(dest))
        return data as Data
    }

    func testGifDecodesAsAnimatedAndStillsDoNot() throws {
        let loaded = try XCTUnwrap(ImageLoader.decode(try gif(frames: 2, side: 4), maxPixel: 100))
        XCTAssertTrue(loaded.animated)
        let still = try XCTUnwrap(ImageLoader.decode(UIImage(systemName: "photo")!.pngData()!, maxPixel: 100))
        XCTAssertFalse(still.animated)
    }

    /// Two 1x1 frames of 250 and 500 ms: the proxy answers an animated source in WebP to an app that names it (#3686).
    private static let animatedWebP = Data(base64Encoded: "UklGRoAAAABXRUJQVlA4WAoAAAASAAAAAAAAAAAAQU5JTQYAAAAAAAAAAABBTk1GJgAAAAAAAAAAAAAAAAAAAPoAAABWUDhMDgAAAC8AAAAQBxAREYiI/gcAQU5NRiYAAAAAAAAAAAAAAAAAAAD0AQAAVlA4TA4AAAAvAAAAEAcQERGIiP4HAA==")!

    func testAnimatedWebPPlaysAtItsOwnFrameTimes() throws {
        let loaded = try XCTUnwrap(ImageLoader.decode(Self.animatedWebP, maxPixel: 100))
        XCTAssertTrue(loaded.animated)
        // UIImage shows its frames for equal times: three slots of 250 ms, the second frame in two of them
        let slots = try XCTUnwrap(loaded.image.images)
        XCTAssertEqual(slots.count, 3)
        guard slots.count == 3 else { return }
        XCTAssertFalse(slots[0] === slots[1])
        XCTAssertTrue(slots[1] === slots[2])
        XCTAssertEqual(loaded.image.duration, 0.75, accuracy: 0.001)
    }

    /// GIF delays are kept per frame the same way (10, 30 and 20 hundredths of a second).
    func testGifFramesKeepTheirOwnDelays() throws {
        let loaded = try XCTUnwrap(ImageLoader.decode(try gif(frames: 3, side: 4, delays: [0.1, 0.3, 0.2]), maxPixel: 100))
        let slots = try XCTUnwrap(loaded.image.images)
        XCTAssertEqual(slots.count, 6)
        guard slots.count == 6 else { return }
        XCTAssertEqual(Set(slots[1...3].map { ObjectIdentifier($0) }).count, 1)
        XCTAssertEqual(Set(slots[4...5].map { ObjectIdentifier($0) }).count, 1)
        XCTAssertEqual(loaded.image.duration, 0.6, accuracy: 0.001)
    }

    /// Past the slot limit an animation still plays for its own length (700 frames of 100 ms: 70 s).
    func testALongAnimationKeepsItsLength() throws {
        let loaded = try XCTUnwrap(ImageLoader.decode(try gif(frames: 700, side: 1, delays: Array(repeating: 0.1, count: 700)), maxPixel: 100))
        XCTAssertEqual(loaded.image.images?.count, 700)
        XCTAssertEqual(loaded.image.duration, 70, accuracy: 0.001)
    }

    /// A long frame among many short ones keeps its own time: 598 frames of 100 ms and one of 10 s.
    func testALongFrameAmongManyKeepsItsTime() throws {
        let delays = Array(repeating: 0.1, count: 598) + [10]
        let loaded = try XCTUnwrap(ImageLoader.decode(try gif(frames: 599, side: 1, delays: delays), maxPixel: 100))
        let slots = try XCTUnwrap(loaded.image.images)
        XCTAssertEqual(slots.count, 698)
        XCTAssertEqual(slots.suffix(100).map { ObjectIdentifier($0) }.uniqueCount, 1, "the 10 s frame fills 100 slots of 100 ms")
        XCTAssertFalse(slots[597] === slots[598])
        XCTAssertEqual(loaded.image.duration, 69.8, accuracy: 0.001)
    }

    /// Past the slot limit, frames too short for a slot get one each and the rest share the others by their delays:
    /// 1000 frames of 20 ms and one of 100 s get 1000 slots and 2000, within the 3000 slots.
    func testPastTheSlotLimitShortFramesKeepOneSlot() throws {
        let delays = Array(repeating: 0.02, count: 1000) + [100]
        let loaded = try XCTUnwrap(ImageLoader.decode(try gif(frames: 1001, side: 1, delays: delays), maxPixel: 100))
        let slots = try XCTUnwrap(loaded.image.images)
        XCTAssertEqual(slots.count, 3000)
        XCTAssertEqual(slots.prefix(1000).map { ObjectIdentifier($0) }.uniqueCount, 1000, "each short frame once")
        XCTAssertEqual(slots.suffix(2000).map { ObjectIdentifier($0) }.uniqueCount, 1)
        XCTAssertEqual(loaded.image.duration, 120, accuracy: 0.001)
    }

    /// Past the slot limit the slots left after rounding down go to the frames rounding cut most, so frames of
    /// different lengths stay different: 1000 of 30 ms and 1000 of 50 ms get one slot and two of the 3000.
    func testPastTheSlotLimitTheWholeBudgetKeepsFramesApart() throws {
        let delays = Array(repeating: 0.03, count: 1000) + Array(repeating: 0.05, count: 1000)
        let loaded = try XCTUnwrap(ImageLoader.decode(try gif(frames: 2000, side: 1, delays: delays), maxPixel: 100))
        let slots = try XCTUnwrap(loaded.image.images)
        XCTAssertEqual(slots.count, 3000)
        guard slots.count == 3000 else { return }
        XCTAssertEqual(slots.filter { $0 === slots[0] }.count, 1, "a 30 ms frame in one slot")
        XCTAssertEqual(slots.filter { $0 === slots[2999] }.count, 2, "a 50 ms frame in two")
        XCTAssertEqual(loaded.image.duration, 80, accuracy: 0.001)
    }

    /// Issue #3686: the proxy answers AVIF or WebP only to a request that names it; other hosts get no Accept.
    func testImageProxyRequestsNameAvifAndWebP() throws {
        for src in ["https://i.ecency.com/p/abc?format=match&mode=fit", "https://images.ecency.com/u/someone/avatar/small"] {
            let request = ImageLoader.request(for: try XCTUnwrap(URL(string: src)))
            XCTAssertEqual(request.value(forHTTPHeaderField: "Accept"), "image/avif,image/webp,*/*", src)
        }
        let other = ImageLoader.request(for: try XCTUnwrap(URL(string: "https://images.hive.blog/p/abc")))
        XCTAssertNil(other.value(forHTTPHeaderField: "Accept"))
    }

    /// The loader's network fetch sends that request.
    func testTheNetworkFetchSendsTheAcceptHeader() async throws {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [RecordingProtocol.self]
        RecordingProtocol.accepts = []
        let fetch = ImageLoader.fetch(over: URLSession(configuration: config))
        _ = await fetch(try XCTUnwrap(URL(string: "https://i.ecency.com/p/abc?format=match&mode=fit")))
        XCTAssertEqual(RecordingProtocol.accepts, ["image/avif,image/webp,*/*"])
    }

    func testAnimatedFramesAreCappedByDecodedBytes() throws {
        // 60 frames of 600x600 would be about 86 MB decoded at full size.
        let loaded = try XCTUnwrap(ImageLoader.decode(try gif(frames: 60, side: 600), maxPixel: 600))
        XCTAssertLessThanOrEqual(loaded.cost, ImageLoader.maxAnimationBytes)
        XCTAssertTrue(loaded.animated)
        XCTAssertLessThan(loaded.image.images?.count ?? 0, 60)
    }

    // MARK: H3, M2, M3: "Show images" off

    func testHiddenImageIsNotFetchedUntilRevealed() throws {
        let url = "https://example.com/hidden.png"
        _ = host(PostBodyView(blocks: [BlockImage(src: url, alt: nil, link: nil)], contentWidth: 320, actions: PostActions(showImages: false)))
        spin(0.5)
        XCTAssertEqual(counter.all, [])
        RevealedImages.shared.reveal(url)
        for _ in 0..<20 where counter.all.isEmpty { spin(0.1) }
        XCTAssertEqual(counter.all, [url])
    }

    func testHiddenModeDoesNotFetchVideoThumbnails() throws {
        let video = BlockVideo(embedUrl: "https://www.youtube.com/embed/abc", thumbnail: "https://example.com/thumb.jpg", youtubeId: "abc", startSeconds: 0)
        _ = host(PostBodyView(blocks: [video], contentWidth: 320, actions: PostActions(showImages: false)))
        spin(0.5)
        XCTAssertEqual(counter.all, [], "video thumbnail fetched with Show images off")
        window?.isHidden = true
        _ = host(PostBodyView(blocks: [video], contentWidth: 320, actions: PostActions(showImages: true)))
        for _ in 0..<20 where counter.all.isEmpty { spin(0.1) }
        XCTAssertEqual(counter.all, ["https://example.com/thumb.jpg"])
    }

    func testHiddenGridTilesAreNotFetchedUntilRevealed() throws {
        let grid = BlockImageGrid(images: [
            BlockImage(src: "https://example.com/g1.png", alt: nil, link: nil),
            BlockImage(src: "https://example.com/g2.png", alt: nil, link: nil),
        ])
        _ = host(PostBodyView(blocks: [grid], contentWidth: 320, actions: PostActions(showImages: false)))
        spin(0.5)
        XCTAssertEqual(counter.all, [])
        RevealedImages.shared.reveal("https://example.com/g2.png")
        for _ in 0..<20 where counter.all.isEmpty { spin(0.1) }
        spin(0.2)
        XCTAssertEqual(counter.all, ["https://example.com/g2.png"])
    }

    func testGalleryLeavesOutHiddenAndLinkedImages() {
        let blocks: [any Block] = [
            BlockImage(src: "https://example.com/1.png", alt: nil, link: nil),
            BlockImage(src: "https://example.com/2.png", alt: nil, link: nil),
            BlockImage(src: "https://example.com/linked.png", alt: nil, link: LinkTargetExternal(url: "https://example.com")),
        ]
        let gallery = GalleryImages(blocks)
        XCTAssertEqual(gallery.visible(showImages: true), ["https://example.com/1.png", "https://example.com/2.png"])
        XCTAssertEqual(gallery.visible(showImages: false), [])
        RevealedImages.shared.reveal("https://example.com/2.png")
        XCTAssertEqual(gallery.visible(showImages: false), ["https://example.com/2.png"])
    }

    // MARK: body images load the proxy size web's srcset picks (README "Body images")

    private let raw = "https://files.peakd.com/file/peakd-hive/alice/photo.jpg"
    private var src: String { ImageProxy(base: ImageProxy.companion.DEFAULT_BASE).proxify(url: raw, width: 0, height: 0, blur: false, forceProxy: true) }
    private func sized(_ src: String, _ format: String, _ width: Int) -> String {
        src.replacingOccurrences(of: "?format=match&mode=fit", with: "?format=\(format)&mode=fit&width=\(width)")
    }
    private func pick(_ px: CGFloat) -> Int { [320, 600, 800, 1024, 1280].first { CGFloat($0) >= px } ?? 1280 }

    func testBodyImageRequestsWebsAvifSizeForItsWidthInPixels() throws {
        _ = host(PostBodyView(blocks: [BlockImage(src: src, alt: nil, link: nil)], contentWidth: 320, actions: PostActions()))
        let expected = sized(src, "avif", pick(320 * UIScreen.main.scale))
        for _ in 0..<30 where counter.all.isEmpty { spin(0.1) }
        XCTAssertEqual(counter.all, [expected])
    }

    func testGridTilesRequestTheirOwnSize() throws {
        let grid = BlockImageGrid(images: [BlockImage(src: src, alt: nil, link: nil), BlockImage(src: src, alt: nil, link: nil)])
        _ = host(PostBodyView(blocks: [grid], contentWidth: 320, actions: PostActions()))
        for _ in 0..<30 where counter.all.isEmpty { spin(0.1) }
        XCTAssertEqual(Set(counter.all), [sized(src, "avif", pick(159 * UIScreen.main.scale))])
    }

    func testAnimatedSourceKeepsWebsMatchSize() throws {
        let gif = ImageProxy(base: ImageProxy.companion.DEFAULT_BASE).proxify(url: "https://media.giphy.com/media/abc/giphy.gif", width: 0, height: 0, blur: false, forceProxy: true)
        _ = host(PostBodyView(blocks: [BlockImage(src: gif, alt: nil, link: nil)], contentWidth: 320, actions: PostActions()))
        for _ in 0..<30 where counter.all.isEmpty { spin(0.1) }
        XCTAssertEqual(counter.all, [sized(gif, "match", pick(320 * UIScreen.main.scale))])
    }

    func testAnAvifThatFailsFallsBackToWebp() throws {
        let png = counter.png
        let failAvif: ImageLoader.Fetch = { [counter] url in
            _ = await counter.fetch(url)
            return url.absoluteString.contains("format=avif") ? nil : png
        }
        ImageLoader.shared.setFetch(failAvif)
        let width = pick(320 * UIScreen.main.scale)
        let page = host(PostBodyView(blocks: [BlockImage(src: src, alt: nil, link: nil)], contentWidth: 320, actions: PostActions()))
        for _ in 0..<30 where counter.all.count < 2 { spin(0.1) }
        XCTAssertEqual(counter.all, [sized(src, "avif", width), sized(src, "webp", width)])
        for _ in 0..<30 where find(UIImageView.self, in: page.view)?.image == nil { spin(0.1) }
        XCTAssertNotNil(find(UIImageView.self, in: page.view)?.image, "the WebP is shown")
    }

    /// The viewer zooms up to 5x, so a capped width would blur: it loads the full file, still through the proxy.
    func testGalleryImageLoadsTheFullResolutionProxyUrlForZoom() throws {
        _ = host(ZoomableImageView(src: src, size: CGSize(width: 390, height: 520)) { _ in })
        for _ in 0..<30 where counter.all.isEmpty { spin(0.1) }
        XCTAssertEqual(counter.all, [src])
    }

    /// Decoded at only the screen width, a 5x zoom scales a thumbnail up: the viewer decodes for its widest zoom.
    func testGalleryImageDecodesForItsZoomNotTheScreenWidth() throws {
        let photo = UIGraphicsImageRenderer(size: CGSize(width: 2400, height: 1800), format: { let f = UIGraphicsImageRendererFormat(); f.scale = 1; return f }())
            .image { ctx in UIColor.blue.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 2400, height: 1800)) }.pngData()!
        ImageLoader.shared.setFetch { _ in photo }
        let page = host(ZoomableImageView(src: src, size: CGSize(width: 390, height: 520)) { _ in })
        for _ in 0..<50 where find(UIImageView.self, in: page.view)?.image == nil { spin(0.1) }
        let image = try XCTUnwrap(find(UIImageView.self, in: page.view)?.image)
        XCTAssertEqual(image.size.width * image.scale, 2400, accuracy: 1, "the whole file, not \(390 * UIScreen.main.scale) px")
    }

    func testDecodeKeepsAHugeImageUnderThePixelBudget() throws {
        let big = UIGraphicsImageRenderer(size: CGSize(width: 1200, height: 600), format: { let f = UIGraphicsImageRendererFormat(); f.scale = 1; return f }())
            .image { ctx in UIColor.blue.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 1200, height: 600)) }.pngData()!
        let loaded = try XCTUnwrap(ImageLoader.decode(big, maxPixel: 5000, maxArea: 20_000))
        XCTAssertLessThanOrEqual(loaded.image.size.width * loaded.image.size.height, 20_000 * 1.05)
        XCTAssertEqual(loaded.image.size.width, 200, accuracy: 2, "the aspect is kept")
    }
}

private extension Sequence where Element: Hashable {
    var uniqueCount: Int { Set(self).count }
}

/// Answers every request with a 404 and keeps its Accept header.
private final class RecordingProtocol: URLProtocol {
    nonisolated(unsafe) static var accepts: [String?] = []
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.accepts.append(request.value(forHTTPHeaderField: "Accept"))
        let response = HTTPURLResponse(url: request.url!, statusCode: 404, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
