import Foundation
import UserNotifications

/// RN's ImageNotifi notification service extension: attaches the push's image (RichPush) before the notification shows.
final class NotificationService: UNNotificationServiceExtension {
    private var contentHandler: ((UNNotificationContent) -> Void)?
    private var bestAttempt: UNMutableNotificationContent?
    private var task: Task<Void, Never>?
    private let lock = NSLock()

    override func didReceive(_ request: UNNotificationRequest, withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void) {
        self.contentHandler = contentHandler
        guard let content = request.content.mutableCopy() as? UNMutableNotificationContent else { return contentHandler(request.content) }
        bestAttempt = content
        guard let url = RichPush.imageURL(content.userInfo) else { return deliver() }
        task = Task {
            if let attachment = await RichPush.attachment(from: url) { content.attachments = [attachment] }
            deliver()
        }
    }

    override func serviceExtensionTimeWillExpire() {
        // RN: deliver the best attempt (the text without the image)
        task?.cancel()
        deliver()
    }

    /// Once: the download and the expiry can both get here.
    private func deliver() {
        lock.lock()
        let handler = contentHandler
        contentHandler = nil
        lock.unlock()
        guard let handler, let content = bestAttempt else { return }
        handler(content)
    }
}
