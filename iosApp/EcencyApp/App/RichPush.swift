import Foundation
import UserNotifications

/// RN ImageNotifi (FIRMessaging extensionHelper `populateNotificationContent`): a push whose payload names an image
/// (`fcm_options.image`, the FCM notification image) shows it as an attachment. Compiled into the notification
/// service extension and into the app (for its tests). The payload is untrusted: only an https URL of bounded length
/// is fetched, with a short timeout and a size cap, as an image type the system can attach; anything else leaves the
/// notification as it came.
enum RichPush {
    /// Below the extension's ~30 s budget, so the plain notification still goes out in time.
    static let timeout: TimeInterval = 20
    /// Not `let`: the tests lower it.
    nonisolated(unsafe) static var maxBytes = 10 * 1024 * 1024
    static let maxUrlLength = 2048

    /// The image URL of a push payload, or nil.
    static func imageURL(_ userInfo: [AnyHashable: Any]) -> URL? {
        guard let options = userInfo["fcm_options"] as? [String: Any], let text = options["image"] as? String,
              text.count <= maxUrlLength, let url = URL(string: text), url.scheme?.lowercased() == "https", url.host?.isEmpty == false
        else { return nil }
        return url
    }

    /// The file extension for an image MIME type the system attaches; nil for anything else.
    static func fileExtension(mimeType: String?) -> String? {
        switch mimeType?.lowercased() {
        case "image/jpeg", "image/jpg": return "jpg"
        case "image/png": return "png"
        case "image/gif": return "gif"
        case "image/webp": return "webp"
        case "image/heic": return "heic"
        default: return nil
        }
    }

    /// Downloads [url] and writes it as an attachment file; nil on any failure. The body is streamed with a hard cap:
    /// an announced length over [maxBytes] is refused at once, and reading stops (cancelling the request) past it.
    static func attachment(from url: URL, session: URLSession = .shared) async -> UNNotificationAttachment? {
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: timeout)
        request.httpShouldHandleCookies = false
        guard let (bytes, response) = try? await session.bytes(for: request),
              let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
              http.expectedContentLength <= Int64(maxBytes), let ext = fileExtension(mimeType: http.mimeType)
        else { return nil }
        var data = Data()
        do {
            for try await byte in bytes {
                data.append(byte)
                if data.count > maxBytes {
                    bytes.task.cancel()
                    return nil
                }
            }
        } catch {
            return nil
        }
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).\(ext)")
        guard (try? data.write(to: file)) != nil else { return nil }
        return try? UNNotificationAttachment(identifier: "image", url: file, options: nil)
    }
}
