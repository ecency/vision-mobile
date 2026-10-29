import EcencyCore
import SwiftUI

@main
struct EcencyApp: App {
    private let container: Result<AppContainer, AppStartError>?
    /// The APNs token, tapped notifications and pushes in front (Push.swift).
    @UIApplicationDelegateAdaptor(PushAppDelegate.self) private var pushDelegate

    init() {
        // APP_START: the first line of the app's own entry point, before the environment or any view
        // exists (Android marks it in Application.onCreate).
        _ = PerfMarks.shared.markOnce(event: PerfEvent.shared.APP_START)
        // Unit tests run inside this app: give them an idle host (no environment, no live feed, no
        // network, no image fetches that would count in their assertions). UI tests launch the app
        // normally, without this variable.
        // crash reports before anything else can crash (README "Crash reporting"): RN's Sentry project in Release unless the build names another or "off"
        if !UnitTestHost.isActive {
            let info = Bundle.main.infoDictionary
            CrashReporting.start(dsn: CrashDsn.shared.of(setting: BuildSecrets.sentryDsn, debug: AppContainer.isDebugBuild), bundleId: Bundle.main.bundleIdentifier ?? "app.esteem.mobile.ios",
                                 version: info?["CFBundleShortVersionString"] as? String ?? "", build: info?["CFBundleVersion"] as? String ?? "",
                                 debug: AppContainer.isDebugBuild)
        }
        container = UnitTestHost.isActive ? nil : AppContainer.make()
    }

    var body: some Scene {
        WindowGroup {
            ThemedRoot {
                switch container {
                case .none:
                    Color.clear.accessibilityIdentifier("unit-test-host")
                case let .success(app):
                    if app.options.rendererDemo {
                        RendererDemoView()
                    } else if EsieGallery.requested {
                        EsieGallery()
                    } else if app.options.iconGallery {
                        #if DEBUG
                        IconGalleryView()
                        #endif
                    } else {
                        ThemeChoice(app: app) {
                            // the app language, its locale and direction, live (AppLanguage.swift)
                            LanguageRoot(language: app.language) { language in
                                LockGate(lock: app.lock, overlay: app.lockOverlay) {
                                    RootView(connectivity: app.connectivity).environmentObject(app)
                                        // copy is read from the catalog: the screens are built again in the new language
                                        .id(language)
                                        // a picked iCloud item downloading, with its cancel (MediaPicking)
                                        .overlay(alignment: .bottom) { MediaLoadingOverlay() }
                                }
                            }
                        }
                        // the launch screen, faded into the first frame (LaunchSplash.swift)
                        .overlay { LaunchSplashCover() }
                    }
                case let .failure(error):
                    Text(error.message).padding()
                }
            }
            // FIRST_FRAME: the root's first appearance, marked after the run loop turn that commits it
            .onAppear { DispatchQueue.main.async { _ = PerfMarks.shared.markOnce(event: PerfEvent.shared.FIRST_FRAME) } }
        }
    }
}

/// Whether this process is the host of the unit test bundle (XCTest sets the configuration path).
enum UnitTestHost {
    static var isActive: Bool {
        #if DEBUG
        return ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
        #else
        return false
        #endif
    }
}

/// RN settings.theme: the saved choice over the system appearance (the lock window follows it too). Also the
/// scene's link entry point, since it is always in the hierarchy.
struct ThemeChoice<Content: View>: View {
    @ObservedObject var app: AppContainer
    @ViewBuilder let content: Content

    /// Debug `-forceDarkTheme YES`: dark whatever the setting (the accessibility audit's dark run).
    static var forcedDark: Bool {
        #if DEBUG
        return UserDefaults.standard.bool(forKey: "forceDarkTheme")
        #else
        return false
        #endif
    }

    var body: some View {
        content
            .preferredColorScheme(Self.forcedDark ? .dark : app.themeMode == .light ? .light : app.themeMode == .dark ? .dark : nil)
            .onAppear { app.applyThemeToLockWindow() }
            // Outside LockGate: links the system delivers while the lock state loads or the PIN pad shows are kept
            // (the link model waits for the unlock; content links open behind the lock).
            .onOpenURL { app.handle(url: $0) }
            // ecency.com universal links (associated domains): the same handling as any other link
            .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { activity in
                if let url = UniversalLinks.url(of: activity) { app.handle(url: url) }
            }
    }
}

/// The page a universal link opened: only an https link on a host of the associated-domains entitlement.
enum UniversalLinks {
    static let hosts: Set<String> = ["ecency.com", "alpha.ecency.com"]

    static func url(of activity: NSUserActivity) -> URL? {
        guard activity.activityType == NSUserActivityTypeBrowsingWeb, let url = activity.webpageURL,
              url.scheme?.lowercased() == "https", let host = url.host?.lowercased(), hosts.contains(host) else { return nil }
        return url
    }
}
