import ImageIO
import UIKit

/// A decoded image ready for display, plus the source's natural size for layout.
final class LoadedImage {
    let image: UIImage
    /// Source pixel size. Like RN's AutoHeightImage, source pixels count as points for layout.
    let naturalSize: CGSize
    let animated: Bool
    /// Decoded bytes, the NSCache cost.
    let cost: Int

    init(image: UIImage, naturalSize: CGSize, animated: Bool, cost: Int) {
        self.image = image
        self.naturalSize = naturalSize
        self.animated = animated
        self.cost = cost
    }
}

/// Shared image pipeline for post bodies and chip avatars.
///
/// - One network fetch per URL at a time (in-flight tasks are shared), one decode per URL and size.
/// - Decoded images stay in an NSCache keyed by URL and target pixel size, so rows that scroll back
///   in are drawn from memory; URLCache keeps the bytes on disk.
/// - Failures are remembered for `retryAfter`, so a dead URL is not refetched on every pass.
/// - Decoding runs off the main thread and downsamples to the display size; animated images are
///   capped by total decoded frame bytes.
final class ImageLoader: @unchecked Sendable {
    typealias Fetch = @Sendable (URL) async -> Data?

    static let shared = ImageLoader()
    static let retryAfter: TimeInterval = 60
    /// Upper bound for one animated image's decoded frames.
    static let maxAnimationBytes = 24 << 20

    private static let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.urlCache = URLCache(memoryCapacity: 16 << 20, diskCapacity: 256 << 20)
        return URLSession(configuration: config)
    }()

    static let networkFetch: Fetch = { url in
        guard let (data, response) = try? await session.data(from: url) else { return nil }
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) { return nil }
        return data
    }

    private let lock = NSLock()
    private var fetchImpl: Fetch
    private var dataTasks: [String: Task<Data?, Never>] = [:]
    private var decodeTasks: [String: Task<LoadedImage?, Never>] = [:]
    private var failures: [String: Date] = [:]
    private let decoded = NSCache<NSString, LoadedImage>()

    init(fetch: @escaping Fetch = ImageLoader.networkFetch) {
        fetchImpl = fetch
        decoded.totalCostLimit = 96 << 20
    }

    /// Replaces the network fetch (tests count requests with this) and drops every cache.
    func setFetch(_ fetch: @escaping Fetch) {
        lock.withLock {
            fetchImpl = fetch
            dataTasks.removeAll()
            decodeTasks.removeAll()
            failures.removeAll()
        }
        decoded.removeAllObjects()
    }

    private static func key(_ src: String, _ maxPixel: CGFloat) -> String { "\(src)#\(Int(maxPixel.rounded()))" }

    /// The decoded image if it is already in memory, without starting a load.
    func cached(_ src: String, maxPixel: CGFloat) -> LoadedImage? {
        decoded.object(forKey: ImageLoader.key(src, maxPixel) as NSString)
    }

    /// `maxPixel` is the widest the image is drawn, in pixels (display width x screen scale).
    func load(_ src: String, maxPixel: CGFloat) async -> LoadedImage? {
        let key = ImageLoader.key(src, maxPixel)
        if let hit = decoded.object(forKey: key as NSString) { return hit }
        let task: Task<LoadedImage?, Never> = lock.withLock {
            if let running = decodeTasks[key] { return running }
            let task = Task.detached(priority: .userInitiated) { [self] () -> LoadedImage? in
                guard let data = await data(for: src) else { return nil }
                return ImageLoader.decode(data, maxPixel: maxPixel)
            }
            decodeTasks[key] = task
            return task
        }
        let result = await task.value
        lock.withLock { decodeTasks[key] = nil }
        if let result { decoded.setObject(result, forKey: key as NSString, cost: result.cost) }
        return result
    }

    private func data(for src: String) async -> Data? {
        let task: Task<Data?, Never>? = lock.withLock {
            if let failed = failures[src], Date().timeIntervalSince(failed) < ImageLoader.retryAfter { return nil }
            if let running = dataTasks[src] { return running }
            guard let url = URL(string: src) else { return nil }
            let fetch = fetchImpl
            let task = Task.detached(priority: .userInitiated) { await fetch(url) }
            dataTasks[src] = task
            return task
        }
        guard let task else { return nil }
        let data = await task.value
        lock.withLock {
            dataTasks[src] = nil
            if data == nil { failures[src] = Date() } else { failures[src] = nil }
        }
        return data
    }

    /// Decodes at no more than `maxPixel` wide (never upscaled). Animated images keep frames until
    /// `maxAnimationBytes` is reached; a single surviving frame shows as a still image.
    static func decode(_ data: Data, maxPixel: CGFloat) -> LoadedImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
              CGImageSourceGetCount(source) > 0 else { return nil }
        let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        let width = (props?[kCGImagePropertyPixelWidth] as? CGFloat) ?? 0
        let height = (props?[kCGImagePropertyPixelHeight] as? CGFloat) ?? 0
        guard width > 0, height > 0 else { return nil }
        // Thumbnail size limits the longer side; scale it so the width lands on maxPixel.
        let maxSide = max(1, maxPixel) * max(1, height / width)
        let options = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: min(maxSide, max(width, height)),
        ] as CFDictionary
        let natural = CGSize(width: width, height: height)

        let count = CGImageSourceGetCount(source)
        if count > 1 {
            var frames: [UIImage] = []
            var duration: Double = 0
            var bytes = 0
            for i in 0..<count {
                guard let cg = CGImageSourceCreateThumbnailAtIndex(source, i, options) else { continue }
                let frameBytes = cg.bytesPerRow * cg.height
                if !frames.isEmpty, bytes + frameBytes > maxAnimationBytes { break }
                bytes += frameBytes
                frames.append(UIImage(cgImage: cg))
                duration += frameDuration(source, i)
            }
            if frames.count > 1, let animated = UIImage.animatedImage(with: frames, duration: duration) {
                return LoadedImage(image: animated, naturalSize: natural, animated: true, cost: bytes)
            }
            if let first = frames.first {
                return LoadedImage(image: first, naturalSize: natural, animated: false, cost: bytes)
            }
            return nil
        }
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options) else { return nil }
        return LoadedImage(image: UIImage(cgImage: cg), naturalSize: natural, animated: false, cost: cg.bytesPerRow * cg.height)
    }

    private static func frameDuration(_ source: CGImageSource, _ index: Int) -> Double {
        let props = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any]
        let gif = props?[kCGImagePropertyGIFDictionary] as? [CFString: Any]
        let delay = (gif?[kCGImagePropertyGIFUnclampedDelayTime] as? Double)
            ?? (gif?[kCGImagePropertyGIFDelayTime] as? Double)
            ?? 0.1
        // Browsers treat delays under 20 ms as 100 ms.
        return delay < 0.02 ? 0.1 : delay
    }
}
