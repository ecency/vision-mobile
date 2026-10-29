import EcencyCore
import SwiftUI
import UIKit

/// The launch screen (README "Launch screen"). The system draws Info.plist's `UILaunchScreen` (the `LaunchLogo` image
/// centred on the `LaunchBackground` colour); this cover draws the same over the first frame and fades it out, like
/// RN's bootsplash `hide({ fade: true })`.
enum LaunchSplash {
    static let backgroundName = "LaunchBackground"
    static let logoName = "LaunchLogo"

    /// The launch colour as the system drew it: in the system's appearance, not the in-app theme choice (which
    /// applies from the first frame on), so the cover starts where the launch screen left off.
    @MainActor static var background: Color {
        let color = UIColor(named: backgroundName) ?? .systemBackground
        return Color(uiColor: color.resolvedColor(with: UITraitCollection(userInterfaceStyle: systemStyle)))
    }

    /// The system's appearance: a window scene's screen traits (no window override reaches them), else the current
    /// traits.
    @MainActor static var systemStyle: UIUserInterfaceStyle {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        return (scene?.screen.traitCollection ?? UITraitCollection.current).userInterfaceStyle
    }

    /// The fade: RN bootsplash's length; nil (gone at once) when motion is off.
    static func fade(_ motion: Motion) -> Animation? { motion.fade(MotionTokens.shared.SPLASH_FADE_MS) }
}

/// The launch screen again over the first frame, faded out once that frame is on screen. It never takes touches and
/// VoiceOver never reads it.
struct LaunchSplashCover: View {
    @Environment(\.motion) private var motion
    @State private var visible = true

    var body: some View {
        ZStack {
            if visible {
                ZStack {
                    LaunchSplash.background
                    Image(LaunchSplash.logoName)
                }
                .transition(.opacity)
                .onAppear {
                    // after the run loop turn that commits the first frame
                    DispatchQueue.main.async { withAnimation(LaunchSplash.fade(motion)) { visible = false } }
                }
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
