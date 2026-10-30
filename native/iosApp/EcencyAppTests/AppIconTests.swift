import EcencyCore
import SwiftUI
import UIKit
import XCTest
@testable import EcencyApp

/// Every shared AppIcon maps to an SF Symbol this iOS draws, outlined and filled; directional icons mirror; the
/// widgets' symbols draw too. (AppIconSourceTest in the Android unit suite checks the names exist on iOS 17.)
final class AppIconTests: XCTestCase {
    func testEveryIconIsMappedAndDraws() {
        XCTAssertGreaterThan(AppIcon.entries.count, 100)
        for icon in AppIcon.entries {
            let symbol = icon.mappedSymbol
            XCTAssertNotNil(symbol, "\(icon.name) has no SF Symbol")
            XCTAssertNotNil(UIImage(systemName: icon.symbol()), "\(icon.name): \(icon.symbol()) does not draw")
            if let filled = icon.filledSymbol {
                XCTAssertNotNil(UIImage(systemName: filled), "\(icon.name): \(filled) does not draw")
                XCTAssertNotEqual(filled, symbol, "\(icon.name)'s filled form differs from the outline")
            }
            XCTAssertEqual(icon.symbol(filled: true), icon.filledSymbol ?? icon.symbol(), "no filled form draws the outline")
        }
    }

    func testDirectionalIconsMirrorInRightToLeft() {
        for icon in AppIcon.entries where icon.isDirectional && icon != .send {
            let name = icon.symbol()
            XCTAssertTrue(name.contains("backward") || name.contains("forward"), "\(icon.name) (\(name)) mirrors on its own")
        }
    }

    func testTheModelsIconsAndTheVoteSymbolsComeFromTheMap() {
        XCTAssertEqual(AppTab.entries.map { $0.icon.symbol() }, ["house", "water.waves", "bubble.left", "creditcard", "bell"])
        XCTAssertEqual(AppTab.notification.icon.symbol(filled: true), "bell.fill", "the selected tab is filled")
        XCTAssertEqual(SideMenuItem.logout.icon.symbol(), "power")
        XCTAssertEqual(VoteButton.symbol(.upvoted), AppIcon.voteUp.symbol(filled: true))
    }

    func testWidgetSymbolsDraw() {
        for symbol in [WidgetSymbol.notifications, .streak, .compose, .waves] {
            XCTAssertNotNil(UIImage(systemName: symbol.rawValue), symbol.rawValue)
        }
    }

    func testWavesMarkIsTheSharedPath() {
        let path = WavyDash().path(in: CGRect(x: 0, y: 0, width: 36, height: 36))
        XCTAssertFalse(path.isEmpty)
        // the stroke spans the grid from x = 1 to 35, half a stroke wider each side
        let half = CGFloat(WavesMark.shared.STROKE) / 2
        XCTAssertEqual(path.boundingRect.minX, 1 - half, accuracy: 0.01)
        XCTAssertEqual(path.boundingRect.maxX, 35 + half, accuracy: 0.01)
    }

    func testTheEcencyLogoMarkFillsRnsViewBox() {
        let rect = CGRect(x: 0, y: 0, width: 80.4, height: 80.4)
        let box = EcencyLogoMarkShape().path(in: rect).boundingRect
        XCTAssertTrue(rect.insetBy(dx: -0.01, dy: -0.01).contains(box), "\(box)")
        XCTAssertGreaterThan(box.width, 70, "the logo spans the view box it was cropped to")
        XCTAssertGreaterThan(box.height, 70)
    }
}
