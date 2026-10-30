import EcencyCore
import SwiftUI
import UIKit
import XCTest
@testable import EcencyApp

/// The launch screen (README "Launch screen"): Info.plist names assets that exist, the colour follows the appearance,
/// and the cover fades like RN's bootsplash.
final class LaunchSplashTests: XCTestCase {
    func testTheLaunchScreenIsTheLogoOnTheLaunchColour() throws {
        let launch = try XCTUnwrap(Bundle.main.object(forInfoDictionaryKey: "UILaunchScreen") as? [String: Any])
        XCTAssertEqual(launch["UIColorName"] as? String, LaunchSplash.backgroundName)
        XCTAssertEqual(launch["UIImageName"] as? String, LaunchSplash.logoName)
        // centred on the whole screen, where the cover draws it again
        XCTAssertEqual(launch["UIImageRespectsSafeAreaInsets"] as? Bool, false)
        let logo = try XCTUnwrap(UIImage(named: LaunchSplash.logoName))
        XCTAssertEqual(logo.size.width, 100, accuracy: 1, "RN bootsplash's 100 pt logo")
    }

    func testTheLaunchColourIsThePageColourInBothAppearances() throws {
        let color = try XCTUnwrap(UIColor(named: LaunchSplash.backgroundName))
        func hex(_ style: UIUserInterfaceStyle) -> UInt32 {
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            color.resolvedColor(with: UITraitCollection(userInterfaceStyle: style)).getRed(&r, green: &g, blue: &b, alpha: &a)
            return UInt32((r * 255).rounded()) << 16 | UInt32((g * 255).rounded()) << 8 | UInt32((b * 255).rounded())
        }
        XCTAssertEqual(hex(.light), 0xFFFFFF)
        XCTAssertEqual(hex(.dark), 0x1E2835)
    }

    func testTheCoverFadesExceptWhenMotionIsOff() {
        XCTAssertEqual(MotionTokens.shared.SPLASH_FADE_MS, 220)
        XCTAssertNotNil(LaunchSplash.fade(Motion(level: .full)))
        XCTAssertNotNil(LaunchSplash.fade(Motion(level: .reduced)), "a fade is kept under reduce motion")
        XCTAssertNil(LaunchSplash.fade(Motion(level: .none)))
    }
}
