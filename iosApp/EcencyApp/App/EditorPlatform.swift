import EcencyCore
import ImageIO
import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Picked images made ready for upload, the way RN's gallery does it. Nothing here runs on the main thread, and an
/// image is never decoded at full size: ImageIO reads the header first and makes the upload version straight
/// from the file.
enum EditorImages {
    static let maxDimension = Int(ImageUploader.companion.MAX_DIMENSION)
    static let maxBytes = Int(ImageUploader.companion.MAX_BYTES)
    /// A picked file larger than this is refused before anything is read (a 48 MP ProRAW is about 75 MB).
    static let maxSourceBytes = 150 * 1024 * 1024
    /// More pixels than this is refused before decoding (a 200 MP sensor image is still accepted).
    static let maxSourcePixels = 250_000_000

    /// From a file: the size on disk and the pixel size are checked before any pixel is decoded.
    static func prepare(file url: URL, name: String?, index: Int) -> PickedImage? {
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        guard size > 0, size <= maxSourceBytes,
              let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        return prepare(source: source, name: name ?? url.lastPathComponent, index: index) { try? Data(contentsOf: url, options: .mappedIfSafe) }
    }

    /// From bytes already in memory (a pasted image, a camera capture, tests).
    static func prepare(data: Data, name: String?, index: Int) -> PickedImage? {
        guard data.count <= maxSourceBytes,
              let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary) else { return nil }
        return prepare(source: source, name: name, index: index) { data }
    }

    /// Every still image is drawn again, at 1920 px on its long side at most, and written without any of the
    /// file's metadata: no EXIF (GPS position, device, time), no XMP, no IPTC; the EXIF orientation is applied
    /// to the pixels. A PNG stays a PNG, everything else (HEIC included) becomes a JPEG at quality 0.85 (RN
    /// MAX_IMAGE_DIMENSION, COMPRESS_QUALITY). A GIF goes as it is, since re-encoding would drop its animation:
    /// GIFs carry no EXIF, and only its size (30 MB) is checked.
    private static func prepare(source: CGImageSource, name: String?, index: Int, original: () -> Data?) -> PickedImage? {
        guard let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = props[kCGImagePropertyPixelWidth] as? Int, let height = props[kCGImagePropertyPixelHeight] as? Int,
              width > 0, height > 0, width * height <= maxSourcePixels else { return nil }
        let type = (CGImageSourceGetType(source) as String?).flatMap { UTType($0) }
        let base = (name.flatMap { $0.contains(".") ? $0 : nil }) ?? "img_\(Int(Date().timeIntervalSince1970 * 1000))_\(index).jpg"
        if type == .gif {
            guard let data = original(), data.count <= maxBytes else { return nil }
            return PickedImages.shared.from(data: data, name: base, mime: "image/gif", width: Int32(width), height: Int32(height))
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: min(maxDimension, max(width, height)), kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let scaled = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let png = type == .png
        let out = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(out, (png ? UTType.png : UTType.jpeg).identifier as CFString, 1, nil) else { return nil }
        // only the pixels and the quality are handed over: none of the source's properties
        CGImageDestinationAddImage(destination, scaled, [kCGImageDestinationLossyCompressionQuality: Double(ImageUploader.companion.JPEG_QUALITY) / 100] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        let newName = (base as NSString).deletingPathExtension + (png ? ".png" : ".jpg")
        return PickedImages.shared.from(data: out as Data, name: newName, mime: png ? "image/png" : "image/jpeg", width: Int32(scaled.width), height: Int32(scaled.height))
    }

    /// The avatar (RN IMAGE_PICKER_AVATAR_OPTIONS, `cropping: true`, 512 x 512): the centred square
    /// ([AvatarCrop]) of an image already [prepare]d (upright, no metadata), scaled to at most 512 px, as a JPEG.
    static func square(_ image: PickedImage) -> PickedImage? {
        let data = PickedImages.shared.data(image: image) as Data
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let full = CGImageSourceCreateImageAtIndex(source, 0, nil), full.width > 0, full.height > 0 else { return nil }
        let crop = AvatarCrop.shared.square(width: Int32(full.width), height: Int32(full.height))
        let side = Int(crop.third!.intValue)
        guard let cropped = full.cropping(to: CGRect(x: Int(crop.first!.intValue), y: Int(crop.second!.intValue), width: side, height: side)) else { return nil }
        let target = Int(AvatarCrop.shared.target(side: Int32(side)))
        guard let context = CGContext(data: nil, width: target, height: target, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        context.interpolationQuality = .high
        context.draw(cropped, in: CGRect(x: 0, y: 0, width: target, height: target))
        guard let scaled = context.makeImage() else { return nil }
        let out = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, scaled, [kCGImageDestinationLossyCompressionQuality: Double(ImageUploader.companion.JPEG_QUALITY) / 100] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        let name = (image.name as NSString).deletingPathExtension + ".jpg"
        return PickedImages.shared.from(data: out as Data, name: name, mime: "image/jpeg", width: Int32(target), height: Int32(target))
    }

    /// The drafts folder: Application Support. The storage marks it excluded from backups when it creates it.
    static func draftsDirectory(fakeData: Bool) -> String {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent(fakeData ? "drafts-fake" : "drafts", isDirectory: true).path
    }
}

/// PHPicker for up to five images: no photo library permission is needed, the app only sees what was picked. Only the
/// shared allow-list's image types are taken (`MediaPicking`).
struct PhotoPicker: UIViewControllerRepresentable {
    /// How many images may be picked (the editor: five; a wave: one).
    var limit: Int = Int(ImageUploader.companion.MAX_IMAGES_PER_PICK)
    /// Called at once when the picker finishes, with the number of picked items (0: cancelled), before they are read.
    var onResults: ((Int) -> Void)?
    let onPicked: ([PickedImage]) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onPicked: onPicked, onResults: onResults) }

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.filter = .images
        config.selectionLimit = limit
        // the original file, so a HEIC is converted by our pipeline (not handed over as an already lossy JPEG)
        config.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_: PHPickerViewController, context _: Context) {}

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let onPicked: ([PickedImage]) -> Void
        let onResults: ((Int) -> Void)?
        init(onPicked: @escaping ([PickedImage]) -> Void, onResults: ((Int) -> Void)? = nil) {
            self.onPicked = onPicked
            self.onResults = onResults
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            onResults?(results.count)
            picker.dismiss(animated: true)
            guard !results.isEmpty else { return }
            let done = onPicked
            // file data is loaded and resized on background queues
            Task.detached(priority: .userInitiated) {
                var images: [PickedImage] = []
                for (index, result) in results.enumerated() {
                    let provider = result.itemProvider
                    // only the shared allow-list's types (MediaPicking); an iCloud-only photo shows its download with
                    // a cancel. The system hands over a temporary file that only lives inside the callback: its size
                    // and pixel size are checked before anything is decoded.
                    let image: PickedImage? = await provider.loadMedia(.image) { file in
                        EditorImages.prepare(file: file, name: provider.suggestedName.map { $0 + "." + file.pathExtension }, index: index)
                    }
                    if let image { images.append(image) }
                }
                let picked = images
                await MainActor.run { done(picked) }
            }
        }
    }
}

/// The system camera for one photo.
struct CameraPicker: UIViewControllerRepresentable {
    /// Called at once when the camera closes: 1 with a photo, 0 when cancelled, before the photo is prepared.
    var onResults: ((Int) -> Void)?
    let onPicked: ([PickedImage]) -> Void
    @Environment(\.dismiss) private var dismiss

    static var isAvailable: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }

    func makeCoordinator() -> Coordinator { Coordinator(onPicked: onPicked, onResults: onResults) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_: UIImagePickerController, context _: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onPicked: ([PickedImage]) -> Void
        let onResults: ((Int) -> Void)?
        init(onPicked: @escaping ([PickedImage]) -> Void, onResults: ((Int) -> Void)? = nil) {
            self.onPicked = onPicked
            self.onResults = onResults
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onResults?(0)
            picker.dismiss(animated: true)
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            let photo = info[.originalImage] as? UIImage
            onResults?(photo == nil ? 0 : 1)
            picker.dismiss(animated: true)
            guard let image = photo else { return }
            let done = onPicked
            Task.detached(priority: .userInitiated) {
                // encode once, then the same path as a picked file (resize to 1920 px)
                let picked = image.jpegData(compressionQuality: 0.95).flatMap { EditorImages.prepare(data: $0, name: nil, index: 0) }
                await MainActor.run { done(picked.map { [$0] } ?? []) }
            }
        }
    }
}
