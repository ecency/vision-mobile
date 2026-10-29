import AVFoundation
import EcencyCore
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// A picked video as the tus upload reads it: one piece at a time from the app's own temporary copy (the system
/// only lends the picked file for the length of a callback), never whole in memory.
final class FileVideoSource: NSObject, TusSource {
    let url: URL
    let name: String
    let size: Int64

    init?(url: URL, name: String) {
        guard let bytes = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value, bytes > 0 else { return nil }
        self.url = url
        self.name = name
        size = bytes
    }

    func read(offset: Int64, length: Int32) -> KotlinByteArray? {
        guard offset >= 0, length > 0, let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        guard (try? handle.seek(toOffset: UInt64(offset))) != nil, let data = try? handle.read(upToCount: Int(length)), data.count == Int(length) else { return nil }
        return EditorVideos.kotlinBytes(data)
    }
}

/// What the editor's video upload needs from the platform: the picked file, its length and a few frames of it.
enum EditorVideos {
    /// Picked videos are copied here for the time of the upload; nothing else lives in it.
    static var directory: URL { FileManager.default.temporaryDirectory.appendingPathComponent("video-upload", isDirectory: true) }

    /// Removes every copied video: at the start of the app, when no upload can be holding one. While the app runs
    /// each copy is deleted by the upload model that holds it (`PickedVideo.letGo`: uploaded, replaced, sheet
    /// closed, editor or composer gone), so the editor and the wave composer never delete each other's file.
    static func purge() { try? FileManager.default.removeItem(at: directory) }

    /// A picked or recorded video into `model`: its facts, then its frames. One that cannot be read leaves no
    /// copy behind. Returns the frames for the strip.
    @MainActor
    static func adopt(_ url: URL, name: String?, into model: VideoUploadModel) async -> [UIImage]? {
        guard let described = await describe(url, name: name) else {
            try? FileManager.default.removeItem(at: url)
            model.pickFailed()
            return nil
        }
        guard let pick = model.pick(picked: described) else { return nil }
        let cut = await frames(url, durationMillis: described.durationMillis)
        model.setThumbs(forPick: pick.int32Value, frames: cut)
        return cut.compactMap { UIImage(data: data($0.bytes)) }
    }

    static func kotlinBytes(_ data: Data) -> KotlinByteArray {
        let bytes = KotlinByteArray(size: Int32(data.count))
        data.withUnsafeBytes { raw in
            let source = raw.bindMemory(to: Int8.self)
            for i in 0 ..< data.count { bytes.set(index: Int32(i), value: source[i]) }
        }
        return bytes
    }

    static func data(_ bytes: KotlinByteArray) -> Data {
        var data = Data(count: Int(bytes.size))
        for i in 0 ..< Int(bytes.size) { data[i] = UInt8(bitPattern: bytes.get(index: Int32(i))) }
        return data
    }

    /// Copies a file the system lends (inside its callback) into the upload folder.
    static func keep(_ lent: URL, suggestedName: String?) -> URL? {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let ext = lent.pathExtension.isEmpty ? "mp4" : lent.pathExtension
        let target = directory.appendingPathComponent(UUID().uuidString + "." + ext)
        guard (try? FileManager.default.copyItem(at: lent, to: target)) != nil else { return nil }
        return target
    }

    static func describe(_ url: URL, name: String?) async -> PickedVideo? {
        let safe = UploadNames.shared.safe(name: name ?? url.lastPathComponent)
        guard let source = FileVideoSource(url: url, name: safe) else { return nil }
        let seconds = (try? await AVURLAsset(url: url).load(.duration).seconds) ?? 0
        let millis: KotlinLong? = seconds.isFinite && seconds > 0 ? KotlinLong(value: Int64(seconds * 1000)) : nil
        // the upload model lets go of the video when it is done with it: the app's own copy is deleted then
        return PickedVideo(source: source, durationMillis: millis, ref: url.path) { try? FileManager.default.removeItem(at: url) }
    }

    /// RN _generateThumbs: frames from the middle of equal slices, upright, scaled down, as JPEGs without
    /// metadata (through the same preparation as any uploaded image). A frame that fails is left out.
    static func frames(_ url: URL, durationMillis: KotlinLong?) async -> [PickedImage] {
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 1280, height: 1280)
        var images: [PickedImage] = []
        for (index, millis) in VideoUploadModel.companion.thumbTimesMillis(durationMillis: durationMillis).enumerated() {
            let time = CMTime(value: millis.int64Value, timescale: 1000)
            guard let frame = try? await generator.image(at: time).image,
                  let jpeg = UIImage(cgImage: frame).jpegData(compressionQuality: 0.85),
                  let image = EditorImages.prepare(data: jpeg, name: "thumbnail-\(index + 1).jpg", index: index) else { continue }
            images.append(image)
        }
        return images
    }
}

/// The system picker for one video (no photo library permission: only the picked file is handed over).
struct VideoPicker: UIViewControllerRepresentable {
    /// The app's own copy of the picked video and its suggested name; nil when nothing was picked.
    let onPicked: (URL?, String?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onPicked: onPicked) }

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var config = PHPickerConfiguration()
        config.filter = .videos
        config.selectionLimit = 1
        config.preferredAssetRepresentationMode = .current
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_: PHPickerViewController, context _: Context) {}

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let onPicked: (URL?, String?) -> Void
        init(onPicked: @escaping (URL?, String?) -> Void) { self.onPicked = onPicked }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            let done = onPicked
            guard let provider = results.first?.itemProvider else { done(nil, nil); return }
            let name = provider.suggestedName
            Task.detached(priority: .userInitiated) {
                // MP4 or MOV only (MediaPicking); an iCloud-only video shows its download with a cancel. The lent
                // file only lives inside the callback: it is copied before it returns.
                let kept: (URL, String)? = await provider.loadMedia(.video) { lent in
                    EditorVideos.keep(lent, suggestedName: name).map { ($0, lent.pathExtension) }
                }
                await MainActor.run { done(kept?.0, kept.map { pair in name.map { $0 + "." + pair.1 } } ?? nil) }
            }
        }
    }
}

/// "Record Video" (RN `ImagePicker.openCamera({mediaType: 'video'})`): the system camera in video mode. The
/// recording is moved into the app's upload folder at once; the system's own temporary file does not stay.
struct VideoCameraPicker: UIViewControllerRepresentable {
    /// The app's own copy of the recording; nil when the camera was closed without one.
    let onRecorded: (URL?) -> Void

    static var isAvailable: Bool {
        UIImagePickerController.isSourceTypeAvailable(.camera) &&
            (UIImagePickerController.availableMediaTypes(for: .camera) ?? []).contains(UTType.movie.identifier)
    }

    func makeCoordinator() -> Coordinator { Coordinator(onRecorded: onRecorded) }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.mediaTypes = [UTType.movie.identifier]
        picker.cameraCaptureMode = .video
        picker.videoQuality = .typeHigh
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_: UIImagePickerController, context _: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onRecorded: (URL?) -> Void
        init(onRecorded: @escaping (URL?) -> Void) { self.onRecorded = onRecorded }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            picker.dismiss(animated: true)
            onRecorded(nil)
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            picker.dismiss(animated: true)
            guard let recorded = info[.mediaURL] as? URL else { onRecorded(nil); return }
            let kept = EditorVideos.keep(recorded, suggestedName: nil)
            try? FileManager.default.removeItem(at: recorded)
            onRecorded(kept)
        }
    }
}

/// RN `<Video repeat resizeMode="contain" paused={isUploading} volume={0}>`: one player over the picked file,
/// looping, without sound, fitted into its frame. It lives exactly as long as this view and plays only while
/// `playing` (no upload runs, the app is in front and not locked).
struct LoopingVideoView: UIViewRepresentable {
    let url: URL
    let playing: Bool

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> PlayerView {
        let view = PlayerView()
        view.backgroundColor = .black
        view.playerLayer.videoGravity = .resizeAspect
        context.coordinator.start(url: url, in: view)
        context.coordinator.set(playing: playing)
        return view
    }

    func updateUIView(_: PlayerView, context: Context) { context.coordinator.set(playing: playing) }

    static func dismantleUIView(_ view: PlayerView, coordinator: Coordinator) {
        coordinator.stop()
        view.playerLayer.player = nil
    }

    final class PlayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    @MainActor
    final class Coordinator {
        private(set) var player: AVQueuePlayer?
        private var looper: AVPlayerLooper?

        func start(url: URL, in view: PlayerView) {
            let player = AVQueuePlayer()
            player.isMuted = true
            // the preview must not take the audio session from whatever the user is listening to
            player.preventsDisplaySleepDuringVideoPlayback = false
            looper = AVPlayerLooper(player: player, templateItem: AVPlayerItem(url: url))
            view.playerLayer.player = player
            self.player = player
        }

        func set(playing: Bool) {
            if playing { player?.play() } else { player?.pause() }
        }

        func stop() {
            player?.pause()
            looper?.disableLooping()
            looper = nil
            player?.removeAllItems()
            player = nil
        }
    }
}
