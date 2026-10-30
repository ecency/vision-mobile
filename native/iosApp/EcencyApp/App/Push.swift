import EcencyCore
import UIKit
import UserNotifications
#if canImport(FirebaseMessaging)
import FirebaseCore
import FirebaseMessaging
#endif

/// Push for iOS, as the RN app does it: APNs through Firebase Cloud Messaging, so the Ecency backend gets an FCM
/// token (system `fcm-ios`).
///
/// Firebase is only part of builds that were given a `GoogleService-Info.plist` (see README, "Notifications and
/// push"): without it the Firebase package is not linked, `canImport` is false and push is inactive. On the
/// simulator push is inactive too, as in RN (no APNs token there).
final class IOSPushPlatform: NSObject, PushPlatform {
    var system: String { "fcm-ios" }

    var isConfigured: Bool { PushBridge.firebaseReady }

    func permission(request: Bool, done: @escaping (KotlinBoolean) -> Void) {
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { settings in
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral:
                Self.finish(true, done)
            case .notDetermined where request:
                center.requestAuthorization(options: [.alert, .badge, .sound]) { granted, _ in Self.finish(granted, done) }
            default:
                Self.finish(false, done)
            }
        }
    }

    private static func finish(_ granted: Bool, _ done: @escaping (KotlinBoolean) -> Void) {
        DispatchQueue.main.async {
            if granted {
                // Firebase's own start-up token is off (FirebaseMessagingAutoInitEnabled = NO in the push include), so no
                // token exists before the user allowed notifications. Allowed, now or earlier: turn it on (Firebase
                // remembers the switch). APNs hands its token to the app delegate, which gives it to Firebase.
                #if canImport(FirebaseMessaging)
                if PushBridge.firebaseReady, !Messaging.messaging().isAutoInitEnabled { Messaging.messaging().isAutoInitEnabled = true }
                #endif
                UIApplication.shared.registerForRemoteNotifications()
            }
            done(KotlinBoolean(bool: granted))
        }
    }

    func token(done: @escaping (String?) -> Void) {
        #if canImport(FirebaseMessaging)
        guard isConfigured else { return done(nil) }
        // never before consent: asking Firebase for a token mints one
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral:
                Messaging.messaging().token { token, _ in DispatchQueue.main.async { done(token) } }
            default:
                DispatchQueue.main.async { done(nil) }
            }
        }
        #else
        done(nil)
        #endif
    }

    func deleteToken(done: @escaping (KotlinBoolean) -> Void) {
        #if canImport(FirebaseMessaging)
        guard isConfigured else { return done(false) }
        Messaging.messaging().deleteToken { error in DispatchQueue.main.async { done(KotlinBoolean(bool: error == nil)) } }
        #else
        done(false)
        #endif
    }

    func clearDelivered() {
        let center = UNUserNotificationCenter.current()
        center.removeAllDeliveredNotifications()
        center.setBadgeCount(0) { _ in }
    }
}

/// Between the app delegate (system callbacks) and the shared push model. Main thread only.
enum PushBridge {
    /// Firebase was configured for this process (a plist in the bundle, a real device).
    private(set) static var firebaseReady = false
    private static weak var env: AppEnvironment?
    /// A tap delivered before the environment existed.
    private static var pendingOpen: [String: String]?

    private static let keys = ["id", "type", "source", "target", "permlink1", "permlink2", "permlink3", "amount", "tag"]

    /// The Ecency fields of a notification's `userInfo`, as strings; nothing else is read from it. Every value
    /// is validated again by the shared routing before it can become a destination.
    static func data(_ userInfo: [AnyHashable: Any]) -> [String: String]? {
        guard let type = userInfo["type"] as? String, !type.isEmpty else { return nil }
        var data = ["type": type]
        for key in keys where key != "type" {
            if let value = userInfo[key] as? String { data[key] = value }
        }
        return data
    }

    static func attach(_ env: AppEnvironment) {
        self.env = env
        if let data = pendingOpen {
            pendingOpen = nil
            env.push.opened(data: data)
        }
    }

    /// The user tapped a system notification.
    static func opened(_ userInfo: [AnyHashable: Any]) {
        guard let data = data(userInfo) else { return }
        if let env { env.push.opened(data: data) } else { pendingOpen = data }
    }

    /// A push arrived while the app is in front.
    static func received(_ userInfo: [AnyHashable: Any], title: String?, body: String?) {
        guard let data = data(userInfo) else { return }
        env?.push.received(data: data, title: title, body: body)
    }

    static func tokenRefreshed() { env?.push.onTokenRefreshed() }

    static func configureFirebase() {
        #if canImport(FirebaseMessaging) && !targetEnvironment(simulator)
        guard !firebaseReady, Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil else { return }
        FirebaseApp.configure()
        firebaseReady = true
        #endif
    }
}

/// System callbacks SwiftUI's App has no hooks for: the APNs token, tapped notifications and pushes in front.
final class PushAppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // the unit test host stays idle: no Firebase, no notification delegate
        guard !UnitTestHost.isActive else { return true }
        PushBridge.configureFirebase()
        UNUserNotificationCenter.current().delegate = self
        #if canImport(FirebaseMessaging)
        if PushBridge.firebaseReady { Messaging.messaging().delegate = self }
        #endif
        return true
    }

    func application(_ application: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        #if canImport(FirebaseMessaging)
        if PushBridge.firebaseReady { Messaging.messaging().apnsToken = deviceToken }
        #endif
    }

    /// In front: no system banner (the app shows its own, as RN), the shared model decides what to do.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        let content = notification.request.content
        DispatchQueue.main.async { PushBridge.received(content.userInfo, title: content.title, body: content.body) }
        completionHandler([])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let userInfo = response.notification.request.content.userInfo
        DispatchQueue.main.async { PushBridge.opened(userInfo) }
        completionHandler()
    }
}

#if canImport(FirebaseMessaging)
extension PushAppDelegate: MessagingDelegate {
    func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        DispatchQueue.main.async { PushBridge.tokenRefreshed() }
    }
}
#endif
