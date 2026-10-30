import EcencyCore
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// The one media picking layer on iOS, over the shared allow-list (`MediaTypes`): PHPicker for the photo library
/// (no library permission; the app only sees what was picked) and the Files importer for media stored outside
/// Photos, both limited to the surface's kind. Camera capture stays its own action.
enum MediaPicking {
    /// The importer's and the picker's content types for a kind, from the shared list.
    static func contentTypes(_ kind: MediaKind) -> [UTType] {
        MediaTypes.shared.utTypes(kind: kind).compactMap { UTType($0) }
    }

    /// The first allowed type an item provider offers (iCloud items register theirs before they are downloaded).
    static func allowedType(_ kind: MediaKind, registered: [String]) -> String? {
        let allowed = contentTypes(kind)
        for id in registered {
            guard let type = UTType(id) else { continue }
            if let match = allowed.first(where: { type == $0 || type.conforms(to: $0) }) { return match.identifier }
        }
        return nil
    }

    /// Nil when a file of [type] and [size] bytes may be uploaded as [kind], otherwise the message that says why not.
    static func refusal(_ kind: MediaKind, type: UTType?, size: Int) -> String? {
        guard let type else { return refusalText(kind) }
        let id = allowedType(kind, registered: [type.identifier]) ?? type.identifier
        if let rejected = MediaTypes.shared.checkUtType(kind: kind, utType: id, sizeBytes: Int64(size)) as? MediaCheckRejected {
            return rejected.message
        }
        return nil
    }

    static func refusalText(_ kind: MediaKind) -> String {
        kind == .image ? MediaTypes.shared.IMAGE_TYPES_MESSAGE : MediaTypes.shared.VIDEO_TYPES_MESSAGE
    }

    /// At most the surface's limit, and RN's message when more were picked (Android says the same).
    static func limited(_ urls: [URL], _ surface: MediaSurface) -> ([URL], String?) {
        let max = Int(surface.maxItems)
        guard urls.count > max else { return (urls, nil) }
        return (Array(urls.prefix(max)), surface.kind == .image ? MediaTypes.shared.tooMany(surface: surface) : nil)
    }

    static var loadFailed: String { L.Native.Ios.Media.loadFailed }
}

/// Media that is still being loaded after a pick (an iCloud-only photo or video is downloaded first): its progress,
/// a cancel, and a message when it failed. One at a time; shown by `MediaLoadingOverlay` over the app.
@Observable
final class MediaLoading {
    static let shared = MediaLoading()
    private(set) var fraction: Double?
    private(set) var message: String?
    private var progress: Progress?
    private var observation: NSKeyValueObservation?
    /// The load shown now, and loads that finished before their progress was handed over (a local file answers at once).
    private var current: UUID?
    private var finishedEarly: Set<UUID> = []

    /// Follows [progress] of the load [token] until [finish] for it.
    func track(_ progress: Progress, _ token: UUID) {
        DispatchQueue.main.async {
            if self.finishedEarly.remove(token) != nil { return }
            self.current = token
            self.progress = progress
            self.fraction = progress.fractionCompleted
            self.observation = progress.observe(\.fractionCompleted) { p, _ in
                DispatchQueue.main.async { if self.progress === p { self.fraction = p.fractionCompleted } }
            }
        }
    }

    func finish(_ token: UUID?, error: String? = nil) {
        DispatchQueue.main.async {
            if let token, token != self.current {
                self.finishedEarly.insert(token)
            } else {
                self.current = nil
                self.observation = nil
                self.progress = nil
                self.fraction = nil
            }
            if let error { self.show(error) }
        }
    }

    /// The user stopped the download: the item is dropped, nothing is uploaded.
    func cancel() {
        progress?.cancel()
        finish(nil)
    }

    func show(_ text: String) {
        DispatchQueue.main.async {
            self.message = text
            DispatchQueue.main.asyncAfter(deadline: .now() + 4) { if self.message == text { self.message = nil } }
        }
    }
}

/// The loading bar with its cancel, and a failed load's message.
struct MediaLoadingOverlay: View {
    @State private var loading = MediaLoading.shared
    @Environment(\.appTheme) private var theme
    @Environment(\.motion) private var motion

    var body: some View {
        VStack(spacing: 8) {
            if let fraction = loading.fraction {
                AdaptiveStack(horizontalAlignment: .center, spacing: 12) {
                    ProgressView(value: fraction).frame(maxWidth: 160)
                    Text(L.Native.Ios.Media.loading).font(.footnote).foregroundStyle(theme.text)
                    Button(UiCopy.shared.CANCEL) { loading.cancel() }.font(.footnote.bold()).accessibilityIdentifier("media-loading-cancel")
                }
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(theme.lightBackground, in: Capsule())
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("media-loading")
            }
            if let message = loading.message {
                Text(message).font(.footnote).foregroundStyle(theme.text).multilineTextAlignment(.center)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 12))
                    .announces(message)
                    .accessibilityIdentifier("media-loading-message")
            }
        }
        .padding(.bottom, 90).padding(.horizontal, 16)
        .animation(motion.fade(), value: loading.fraction != nil)
    }
}

extension NSItemProvider {
    /// The provider's file for one of [kind]'s allowed types, tracked by `MediaLoading` (an iCloud download shows its
    /// progress and can be cancelled). [use] runs inside the callback, while the lent file exists; nil when the item
    /// is not an allowed type (the refusal is shown) or could not be loaded.
    func loadMedia<T>(_ kind: MediaKind, _ use: @escaping (URL) -> T?) async -> T? {
        guard let type = MediaPicking.allowedType(kind, registered: registeredTypeIdentifiers) else {
            MediaLoading.shared.show(MediaPicking.refusalText(kind))
            return nil
        }
        let token = UUID()
        return await withCheckedContinuation { continuation in
            let progress = loadFileRepresentation(forTypeIdentifier: type) { url, error in
                let value = url.flatMap(use)
                // a download that failed (offline iCloud item) says so; a cancel says nothing, and an unreadable file
                // is said by the caller ("Invalid image")
                MediaLoading.shared.finish(token, error: url == nil && (error as NSError?)?.code != NSUserCancelledError ? MediaPicking.loadFailed : nil)
                continuation.resume(returning: value)
            }
            MediaLoading.shared.track(progress, token)
        }
    }
}

/// "Choose from Files": the Files importer limited to the surface's types, each file checked against the allow-list
/// and read inside its security scope. Images arrive prepared (upright, no metadata); a video arrives as the app's
/// own copy (`EditorVideos.keep`), so a file that moves later cannot break its upload. One importer per view: a
/// screen with several surfaces sets [surface] to the one that asked (SwiftUI keeps only one file importer per view).
struct MediaFileImporter: ViewModifier {
    @Binding var surface: MediaSurface?
    var onImages: (MediaSurface, [PickedImage]) -> Void
    var onVideo: (URL?, String?) -> Void
    /// The surface that opened the importer: the binding is cleared when it closes, before its answer arrives.
    @State private var asking: MediaSurface?

    func body(content: Content) -> some View {
        content
            .onChange(of: surface) { _, now in if let now { asking = now } }
            .fileImporter(
                isPresented: Binding(get: { surface != nil }, set: { if !$0 { surface = nil } }),
                allowedContentTypes: MediaPicking.contentTypes(surface?.kind ?? asking?.kind ?? .image),
                allowsMultipleSelection: (surface ?? asking)?.allowsMultiple ?? false
            ) { result in
                guard let target = asking else { return }
                asking = nil
                Self.finish(target, urls: try? result.get(), onImages: onImages, onVideo: onVideo)
            } onCancellation: {
                // a cancelled importer ends the request it was opened for (the wave attach waits on it, RN #3561)
                guard let target = asking else { return }
                asking = nil
                Self.finish(target, urls: nil, onImages: onImages, onVideo: onVideo)
            }
    }

    /// The importer's answer for [target]: nil or no urls (cancelled, failed) ends the request with nothing; otherwise
    /// the files are checked, read and handed over off the main thread. More than the surface takes: the first ones,
    /// and the same message as Android.
    static func finish(
        _ target: MediaSurface, urls: [URL]?, onImages: @escaping (MediaSurface, [PickedImage]) -> Void,
        onVideo: @escaping (URL?, String?) -> Void
    ) {
        guard let urls, !urls.isEmpty else {
            if target.kind == .video { onVideo(nil, nil) } else { onImages(target, []) }
            return
        }
        let (taken, tooMany) = MediaPicking.limited(urls, target)
        if let tooMany { MediaLoading.shared.show(tooMany) }
        Task.detached(priority: .userInitiated) {
            var images: [PickedImage] = []
            var video: URL?
            var refused: String?
            for (index, url) in taken.enumerated() {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? -1
                if let why = MediaPicking.refusal(target.kind, type: UTType(filenameExtension: url.pathExtension), size: size) {
                    refused = refused ?? why
                    continue
                }
                if target.kind == .video {
                    video = EditorVideos.keep(url, suggestedName: url.deletingPathExtension().lastPathComponent)
                } else if let image = EditorImages.prepare(file: url, name: url.lastPathComponent, index: index) {
                    images.append(image)
                }
            }
            if let refused { MediaLoading.shared.show(refused) }
            let picked = images
            let kept = video
            let name = taken.first?.lastPathComponent
            await MainActor.run { if target.kind == .video { onVideo(kept, name) } else { onImages(target, picked) } }
        }
    }
}

extension View {
    /// The Files importer of the screen; set [surface] to open it for that surface.
    func mediaFileImporter(
        surface: Binding<MediaSurface?>, onImages: @escaping (MediaSurface, [PickedImage]) -> Void = { _, _ in },
        onVideo: @escaping (URL?, String?) -> Void = { _, _ in }
    ) -> some View {
        modifier(MediaFileImporter(surface: surface, onImages: onImages, onVideo: onVideo))
    }
}
