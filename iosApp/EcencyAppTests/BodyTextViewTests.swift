import UIKit
import XCTest
@testable import EcencyApp

/// The editor body's layout rules (README "Editor and publishing", layout with the keyboard up).
@MainActor
final class BodyTextViewTests: XCTestCase {
    private func draft(words: Int) -> String {
        stride(from: 0, to: words, by: 12).map { n in (0..<12).map { "word\(($0 + n) % 97)" }.joined(separator: " ") }.joined(separator: "\n")
    }

    /// A body grows with its text up to 10,000 UTF-16 units, scrolls itself above, and grows again below 8,000.
    func testALongBodyScrollsItselfWithRoomBeforeItTurnsBack() {
        XCTAssertFalse(BodyTextView.scrollsItself(length: 10_000, scrolls: false))
        XCTAssertTrue(BodyTextView.scrollsItself(length: 10_001, scrolls: false))
        XCTAssertTrue(BodyTextView.scrollsItself(length: 9_000, scrolls: true), "no flip at every key around the limit")
        XCTAssertFalse(BodyTextView.scrollsItself(length: 8_000, scrolls: true))
    }

    /// Typing in the middle of a 50,000 word draft stays responsive: the body there is a scrolling text view, which
    /// lays out what is visible (a growing one took about 400 ms a key on the simulator).
    func testTypingInTheMiddleOfAFiftyThousandWordDraftIsFast() {
        let text = draft(words: 50_000)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 375, height: 667))
        window.isHidden = false
        defer { window.isHidden = true }
        let view = UITextView(frame: CGRect(x: 0, y: 0, width: 375, height: 300))
        view.font = UIFont.preferredFont(forTextStyle: .body)
        view.isScrollEnabled = BodyTextView.scrollsItself(length: (text as NSString).length, scrolls: false)
        XCTAssertTrue(view.isScrollEnabled)
        window.addSubview(view)
        view.text = text
        view.layoutIfNeeded()
        view.selectedRange = NSRange(location: (text as NSString).length / 2, length: 0)
        let start = Date()
        for _ in 0..<10 {
            view.insertText("a")
            view.layoutIfNeeded()
        }
        let perKey = Date().timeIntervalSince(start) / 10
        XCTAssertLessThan(perKey, 0.1, "a key took \(Int(perKey * 1000)) ms")
    }

    /// The keyboard counts when it covers the window; the hardware keyboard's bar and a hidden keyboard do not.
    func testOnlyAKeyboardThatCoversTheWindowCounts() {
        let window = CGRect(x: 0, y: 0, width: 375, height: 667)
        XCTAssertGreaterThan(BodyTextView.keyboardOverlap(CGRect(x: 0, y: 451, width: 375, height: 216), in: window), BodyTextView.keyboardMinimum)
        XCTAssertLessThan(BodyTextView.keyboardOverlap(CGRect(x: 0, y: 612, width: 375, height: 55), in: window), BodyTextView.keyboardMinimum, "hardware keyboard bar")
        XCTAssertEqual(BodyTextView.keyboardOverlap(CGRect(x: 0, y: 667, width: 375, height: 0), in: window), 0, "hidden")
        XCTAssertEqual(BodyTextView.keyboardOverlap(CGRect(x: 0, y: 900, width: 375, height: 216), in: window), 0, "below the window (another display)")
    }

    /// A long body scrolls itself: scrolling down moves the header away first, pulling past the text's top brings it back.
    func testADragOnALongBodyGoesOnInTheEditorScrollView() {
        let outer = UIScrollView(frame: CGRect(x: 0, y: 0, width: 375, height: 300))
        outer.contentSize = CGSize(width: 375, height: 450) // a 150 point header over a 300 point body
        let inner = UIScrollView(frame: CGRect(x: 0, y: 150, width: 375, height: 300))
        inner.contentSize = CGSize(width: 375, height: 5_000)
        outer.addSubview(inner)
        // down by 40 with the header showing: the header moves, the text does not
        inner.contentOffset.y = 40
        BodyTextView.Coordinator.chain(inner: inner, outer: outer, from: 0)
        XCTAssertEqual(outer.contentOffset.y, 40)
        XCTAssertEqual(inner.contentOffset.y, 0)
        // the header gone, the text scrolls
        outer.contentOffset.y = 150
        inner.contentOffset.y = 30
        BodyTextView.Coordinator.chain(inner: inner, outer: outer, from: 0)
        XCTAssertEqual(outer.contentOffset.y, 150)
        XCTAssertEqual(inner.contentOffset.y, 30)
        // pulled 25 past the text's top: the header comes back by 25
        inner.contentOffset.y = -25
        BodyTextView.Coordinator.chain(inner: inner, outer: outer, from: 0)
        XCTAssertEqual(outer.contentOffset.y, 125)
        XCTAssertEqual(inner.contentOffset.y, 0)
    }
}
