import XCTest

/// Typing that does not depend on how fast the simulator is.
///
/// `tap()` returns once the touch is delivered, not once the field is first responder: on a loaded
/// machine the keyboard focus can still sit on the previous field (or nowhere) when `typeText` runs,
/// which fails with "Neither element nor any descendant has keyboard focus" or types into the wrong
/// field. Here the field is tapped until it reports keyboard focus, and only then typed into.
extension XCUIElement {
    /// Taps until this element holds the keyboard focus (tapping again if a tap was lost), then types [text].
    func focusAndType(_ text: String, timeout: TimeInterval = 20, file: StaticString = #filePath, line: UInt = #line) {
        guard focus(timeout: timeout, file: file, line: line) else { return }
        typeText(text)
    }

    /// Taps until this element holds the keyboard focus; false (with a test failure) if it never does.
    @discardableResult
    func focus(timeout: TimeInterval = 20, file: StaticString = #filePath, line: UInt = #line) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        let focused = NSPredicate(format: "hasKeyboardFocus == true")
        while true {
            if focused.evaluate(with: self) { return true }
            tap()
            let wait = XCTNSPredicateExpectation(predicate: focused, object: self)
            if XCTWaiter().wait(for: [wait], timeout: 3) == .completed { return true }
            if Date() > deadline {
                XCTFail("never took the keyboard focus: \(debugDescription)", file: file, line: line)
                return false
            }
        }
    }

    /// Waits until this element's value is [expected] (a field's value follows the keystrokes a run loop turn later).
    func waitForValue(_ expected: String, timeout: TimeInterval = 20, file: StaticString = #filePath, line: UInt = #line) {
        let wait = XCTNSPredicateExpectation(predicate: NSPredicate(format: "value == %@", expected), object: self)
        if XCTWaiter().wait(for: [wait], timeout: timeout) != .completed {
            XCTFail("value is \(String(describing: value)), expected \(expected)", file: file, line: line)
        }
    }
}
