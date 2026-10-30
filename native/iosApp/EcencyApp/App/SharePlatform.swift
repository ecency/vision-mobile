import EcencyCore
import Foundation

/// The app's side of the share extension (ShareExtension/ShareViewController.swift): reads a share's folder from the
/// App Group container and hands its texts and image files to the shared ShareModel. What the folder holds is
/// treated as untrusted: the id must be a UUID, image names plain file names, and every file must resolve inside
/// that folder (no symlinks out of it); anything else is ignored.
enum ShareInbox {
    static let group = "group.com.ecency.eshare"
    static let scheme = "app.esteem.mobile.ios.share"
    /// Folders older than this are removed at launch (a share that was never opened).
    static let maxAge: TimeInterval = 24 * 3600

    /// The container's share root; tests pass their own.
    static var root: URL? = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)?
        .appendingPathComponent("Share", isDirectory: true)

    struct Shared: Equatable {
        let texts: [String]
        let images: [URL]
    }

    /// `app.esteem.mobile.ios.share://inbox/<uuid>`: true when [url] is a share link (handled or refused).
    static func isShareLink(_ url: URL) -> Bool { url.scheme?.lowercased() == scheme }

    /// The share [url] names, or nil when it is malformed or its folder is missing or unreadable.
    static func read(_ url: URL, root: URL? = ShareInbox.root) -> Shared? {
        guard isShareLink(url), url.host?.lowercased() == "inbox", let root else { return nil }
        let id = url.lastPathComponent
        guard UUID(uuidString: id) != nil, url.pathComponents.count == 2 else { return nil }
        let folder = root.appendingPathComponent(id, isDirectory: true).standardizedFileURL.resolvingSymlinksInPath()
        guard folder.path.hasPrefix(root.standardizedFileURL.resolvingSymlinksInPath().path + "/") else { return nil }
        guard let data = try? Data(contentsOf: folder.appendingPathComponent("share.json")),
              data.count < 1_000_000,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        let texts = (json["texts"] as? [String] ?? []).filter { !$0.isEmpty }
        let images = (json["images"] as? [String] ?? []).compactMap { name -> URL? in
            guard !name.isEmpty, !name.contains("/"), !name.contains(".."), name != "share.json" else { return nil }
            let file = folder.appendingPathComponent(name).resolvingSymlinksInPath()
            guard file.deletingLastPathComponent().path == folder.path,
                  let values = try? file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey]),
                  values.isRegularFile == true, values.isSymbolicLink != true else { return nil }
            return file
        }
        if texts.isEmpty && images.isEmpty { return nil }
        return Shared(texts: texts, images: Array(images.prefix(Int(ImageUploader.companion.MAX_IMAGES_PER_PICK))))
    }

    /// A file the shared model handed back is read only when it lies inside the share root.
    static func contains(_ file: URL, root: URL? = ShareInbox.root) -> Bool {
        guard let root, file.isFileURL else { return false }
        return file.standardizedFileURL.resolvingSymlinksInPath().path.hasPrefix(root.standardizedFileURL.resolvingSymlinksInPath().path + "/")
    }

    /// Removes a share's file once it was read (and its folder when that was the last one).
    static func consumed(_ file: URL) {
        guard contains(file) else { return }
        try? FileManager.default.removeItem(at: file)
        let folder = file.deletingLastPathComponent()
        let left = (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []
        if left.allSatisfy({ $0 == "share.json" }) { try? FileManager.default.removeItem(at: folder) }
    }

    /// Folders of shares nobody opened (older than [maxAge]) go at launch; a wipe removes all.
    static func purge(all: Bool = false, now: Date = Date(), root: URL? = ShareInbox.root) {
        guard let root, let folders = try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.creationDateKey]) else { return }
        for folder in folders {
            let created = (try? folder.resourceValues(forKeys: [.creationDateKey]))?.creationDate ?? .distantPast
            if all || now.timeIntervalSince(created) > maxAge { try? FileManager.default.removeItem(at: folder) }
        }
    }

    /// Prepares a shared image like a picked one (metadata stripped, resized; EditorPlatform.swift), off the main
    /// thread, and removes the file afterwards. Nil when it is not an image of ours to read.
    static func prepare(_ handle: String, index: Int) async -> PickedImage? {
        guard let url = URL(string: handle), contains(url) else { return nil }
        let image = await Task.detached(priority: .userInitiated) { EditorImages.prepare(file: url, name: url.lastPathComponent, index: index) }.value
        consumed(url)
        return image
    }
}
