import EcencyCore
import Foundation
import Photos
import UniformTypeIdentifiers

/// RN imageViewer _onSavePress (`CameraRoll.saveAsset(file, {album: 'Ecency'})`): downloads the image (https only, at
/// most 50 MB, streamed into a temporary file with a byte budget) and adds it to the photo library. The prompt asked
/// for is add-only; the "Ecency" album is used only when full library access was already granted (best effort).
/// Returns nil on success, else the message to show.
enum PhotoSaver {
    static let album = "Ecency"
    static let maxBytes = 50 * 1024 * 1024

    enum SaveError: Error, Equatable { case tooLarge, status(Int) }

    static func save(_ url: URL) async -> String? {
        guard url.scheme == "https" else { return UiCopy.shared.IMAGE_SAVE_ERROR }
        let download: (file: URL, type: String)
        do {
            download = try await ChunkedDownload(url: url, max: maxBytes).run()
        } catch SaveError.tooLarge {
            return UiCopy.shared.IMAGE_SAVE_ERROR
        } catch let SaveError.status(code) {
            return L.Native.Ios.Media.downloadFailed(status: String(code))
        } catch {
            return error.localizedDescription
        }
        defer { try? FileManager.default.removeItem(at: download.file) }
        var add = PHPhotoLibrary.authorizationStatus(for: .addOnly)
        if add == .notDetermined { add = await PHPhotoLibrary.requestAuthorization(for: .addOnly) }
        guard add == .authorized || add == .limited else { return UiCopy.shared.IMAGE_SAVE_ERROR }
        let withAlbum = albumAllowed(PHPhotoLibrary.authorizationStatus(for: .readWrite))
        do {
            let collection = withAlbum ? try await findOrCreateAlbum() : nil
            try await PHPhotoLibrary.shared().performChanges {
                let request = PHAssetCreationRequest.forAsset()
                let options = PHAssetResourceCreationOptions()
                // Photos reads the type from these: the file's extension and the explicit type identifier
                options.uniformTypeIdentifier = uti(forExtension: download.file.pathExtension)
                options.originalFilename = download.file.lastPathComponent
                request.addResource(with: .photo, fileURL: download.file, options: options)
                if let collection, let placeholder = request.placeholderForCreatedAsset,
                   let change = PHAssetCollectionChangeRequest(for: collection) {
                    change.addAssets([placeholder] as NSArray)
                }
            }
            return nil
        } catch {
            return error.localizedDescription
        }
    }

    /// The album needs full access that was already granted; nothing is asked for it.
    static func albumAllowed(_ readWrite: PHAuthorizationStatus) -> Bool { readWrite == .authorized }

    /// As Android's ImageSaver.extension (RN imageViewer): from the Content-Type (jpeg is jpg, svg+xml is svg), else the
    /// URL path, else jpg.
    static func fileExtension(contentType: String?, url: URL) -> String {
        if let type = contentType?.split(separator: ";").first?.trimmingCharacters(in: .whitespaces).lowercased(), type.hasPrefix("image/") {
            let sub = String(type.dropFirst("image/".count))
            switch sub {
            case "jpeg": return "jpg"
            case "svg+xml": return "svg"
            default: if !sub.isEmpty, sub.allSatisfy({ $0.isLetter || $0.isNumber }) { return sub }
            }
        }
        let ext = url.pathExtension.lowercased()
        return (2...5).contains(ext.count) && ext.allSatisfy({ $0.isLetter || $0.isNumber }) ? ext : "jpg"
    }

    /// The uniform type for a file extension (public.jpeg for jpg), public.image when unknown.
    static func uti(forExtension ext: String) -> String {
        guard let type = UTType(filenameExtension: ext), type.conforms(to: .image) else { return UTType.image.identifier }
        return type.identifier
    }

    /// Appends `chunk` to `handle` within a running total: past `max` it throws (the caller removes the file).
    static func append(_ chunk: Data, to handle: FileHandle, total: inout Int, max: Int) throws {
        total += chunk.count
        if total > max { throw SaveError.tooLarge }
        try handle.write(contentsOf: chunk)
    }

    /// Streams `chunks` into `file`, refusing a declared length over `max` before reading and stopping past `max`
    /// bytes (the partial file is removed).
    static func write<S: AsyncSequence>(_ chunks: S, expected: Int64, to file: URL, max: Int) async throws where S.Element == Data {
        if expected > Int64(max) { throw SaveError.tooLarge }
        FileManager.default.createFile(atPath: file.path, contents: nil)
        let handle = try FileHandle(forWritingTo: file)
        var ok = false
        defer {
            try? handle.close()
            if !ok { try? FileManager.default.removeItem(at: file) }
        }
        var total = 0
        for try await chunk in chunks { try append(chunk, to: handle, total: &total, max: max) }
        ok = true
    }

    private static func findOrCreateAlbum() async throws -> PHAssetCollection? {
        let options = PHFetchOptions()
        options.predicate = NSPredicate(format: "title = %@", album)
        if let found = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: options).firstObject { return found }
        var id: String?
        try await PHPhotoLibrary.shared().performChanges {
            id = PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: album).placeholderForCreatedAssetCollection.localIdentifier
        }
        guard let id else { return nil }
        return PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [id], options: nil).firstObject
    }
}

/// One download in the chunks URLSession delivers (no per-byte loop): the response decides the file's extension,
/// a declared length over the budget is refused at once, and the body stops past the budget (the file is removed).
final class ChunkedDownload: NSObject, URLSessionDataDelegate {
    private let url: URL
    private let max: Int
    private var file: URL?
    private var handle: FileHandle?
    private var total = 0
    private var failure: Error?
    private var continuation: CheckedContinuation<(file: URL, type: String), Error>?

    init(url: URL, max: Int) {
        self.url = url
        self.max = max
    }

    func run() async throws -> (file: URL, type: String) {
        let session = URLSession(configuration: .ephemeral, delegate: self, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        var request = URLRequest(url: url)
        request.timeoutInterval = 30
        return try await withCheckedThrowingContinuation { c in
            continuation = c
            session.dataTask(with: request).resume()
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        if let http = response as? HTTPURLResponse, http.statusCode != 200 {
            failure = PhotoSaver.SaveError.status(http.statusCode)
            return completionHandler(.cancel)
        }
        if response.expectedContentLength > Int64(max) {
            failure = PhotoSaver.SaveError.tooLarge
            return completionHandler(.cancel)
        }
        let ext = PhotoSaver.fileExtension(contentType: response.mimeType, url: url)
        let target = FileManager.default.temporaryDirectory.appendingPathComponent("ecency-save-\(UUID().uuidString).\(ext)")
        FileManager.default.createFile(atPath: target.path, contents: nil)
        file = target
        handle = try? FileHandle(forWritingTo: target)
        completionHandler(handle == nil ? .cancel : .allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard let handle, failure == nil else { return }
        do {
            try PhotoSaver.append(data, to: handle, total: &total, max: max)
        } catch {
            failure = error
            dataTask.cancel()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        try? handle?.close()
        if let error = failure ?? error {
            if let file { try? FileManager.default.removeItem(at: file) }
            continuation?.resume(throwing: error)
        } else if let file {
            continuation?.resume(returning: (file, PhotoSaver.uti(forExtension: file.pathExtension)))
        } else {
            continuation?.resume(throwing: PhotoSaver.SaveError.status(0))
        }
        continuation = nil
    }
}
