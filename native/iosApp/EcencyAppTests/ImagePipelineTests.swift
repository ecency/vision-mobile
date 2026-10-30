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

    private func gif(frames: Int, side: Int) throws -> Data {
        let data = NSMutableData()
        let dest = try XCTUnwrap(CGImageDestinationCreateWithData(data, UTType.gif.identifier as CFString, frames, nil))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        for i in 0..<frames {
            let frame = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: format).image { ctx in
                UIColor(hue: CGFloat(i) / CGFloat(frames), saturation: 1, brightness: 1, alpha: 1).setFill()
                ctx.fill(CGRect(x: 0, y: 0, width: side, height: side))
            }
            let props = [kCGImagePropertyGIFDictionary: [kCGImagePropertyGIFDelayTime: 0.05]] as CFDictionary
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
}
