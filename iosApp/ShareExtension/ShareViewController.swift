import UIKit
import UniformTypeIdentifiers

/// The share extension (RN ios/eshare): images, a web URL and text from the system share sheet go to the app, which
/// asks where they go (Blog Post or Wave) once it is unlocked.
///
/// - What it hands over: one folder per share in the App Group container (`Share/<uuid>/`) with the image files and a
///   `share.json` naming them and carrying the texts. Nothing else is written, and nothing is read from the app:
///   no account, key, token or setting is visible to the extension (it has no Keychain access group).
/// - How it opens the app: `app.esteem.mobile.ios.share://inbox/<uuid>` (a scheme of its own, so the RN store app,
///   which also registers ecency://, never receives it). The URL carries only the folder's id.
/// - Limits: at most 5 images (the app uploads 5 per pick) and 60,000 characters of text.
final class ShareViewController: UIViewController {
    private static let group = "group.com.ecency.eshare"
    private static let scheme = "app.esteem.mobile.ios.share"
    private static let maxImages = 5
    private static let maxText = 60_000

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .clear
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        collect()
    }

    private func collect() {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? []).flatMap { $0.attachments ?? [] }
        guard !providers.isEmpty, let folder = Self.newFolder() else { return finish(nil) }
        var texts: [String] = []
        var images: [String] = []
        let lock = NSLock()
        let group = DispatchGroup()
        for provider in providers {
            if provider.hasItemConformingToTypeIdentifier(UTType.image.identifier) {
                group.enter()
                provider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { url, _ in
                    defer { group.leave() }
                    lock.lock(); let full = images.count >= Self.maxImages; lock.unlock()
                    guard !full, let url else { return }
                    let name = "image-\(UUID().uuidString).\(url.pathExtension.isEmpty ? "img" : url.pathExtension.lowercased())"
                    guard (try? FileManager.default.copyItem(at: url, to: folder.appendingPathComponent(name))) != nil else { return }
                    lock.lock(); images.append(name); lock.unlock()
                }
            } else if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                group.enter()
                _ = provider.loadObject(ofClass: URL.self) { url, _ in
                    defer { group.leave() }
                    // only web links; a file URL from another app is not followed
                    guard let url, let scheme = url.scheme?.lowercased(), scheme == "https" || scheme == "http" else { return }
                    lock.lock(); texts.append(url.absoluteString); lock.unlock()
                }
            } else if provider.hasItemConformingToTypeIdentifier(UTType.text.identifier) {
                group.enter()
                _ = provider.loadObject(ofClass: String.self) { text, _ in
                    defer { group.leave() }
                    guard let text, !text.isEmpty else { return }
                    lock.lock(); texts.append(text); lock.unlock()
                }
            }
        }
        group.notify(queue: .main) { [weak self] in
            let text = String(texts.joined(separator: "\n").prefix(Self.maxText))
            guard !(text.isEmpty && images.isEmpty) else {
                try? FileManager.default.removeItem(at: folder)
                self?.finish(nil)
                return
            }
            let manifest: [String: Any] = ["texts": text.isEmpty ? [] : [text], "images": images]
            guard let data = try? JSONSerialization.data(withJSONObject: manifest),
                  (try? data.write(to: folder.appendingPathComponent("share.json"), options: .atomic)) != nil else {
                try? FileManager.default.removeItem(at: folder)
                self?.finish(nil)
                return
            }
            self?.finish(URL(string: "\(Self.scheme)://inbox/\(folder.lastPathComponent)"))
        }
    }

    private static func newFolder() -> URL? {
        guard let container = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else { return nil }
        let folder = container.appendingPathComponent("Share", isDirectory: true).appendingPathComponent(UUID().uuidString, isDirectory: true)
        return (try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)) != nil ? folder : nil
    }

    /// Opens the app with [url] (the responder chain's UIApplication, as share extensions must), then closes the sheet.
    private func finish(_ url: URL?) {
        if let url { open(url) }
        extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
    }

    private func open(_ url: URL) {
        var responder: UIResponder? = self
        let selector = sel_registerName("openURL:options:completionHandler:")
        while let current = responder {
            if let app = current as? UIApplication, app.responds(to: selector) {
                typealias Open = @convention(c) (NSObject, Selector, NSURL, NSDictionary, Any?) -> Void
                let method = unsafeBitCast(app.method(for: selector), to: Open.self)
                method(app, selector, url as NSURL, NSDictionary(), nil)
                return
            }
            responder = current.next
        }
    }
}
