import XCTest

/// Sheet heights (#3728): a sheet opens at its content's height (`FittedSheetScroll` with `.fittedDetents()` in
/// Components.swift), never at half the screen with an empty band under its last row. A source scan of
/// `EcencyApp/**/*.swift` fails on a `.medium` presentation detent outside that mechanism.
final class SheetDetentLintTests: XCTestCase {
    /// The fitted detents' own fallback while a sheet is not measured yet.
    nonisolated static let allowedFile = "Components.swift"

    /// A `.presentationDetents(...)` naming `.medium`, also through a `selection:` set built in place.
    nonisolated static func usesMedium(_ line: String) -> Bool {
        let code = line.components(separatedBy: "//").first ?? line
        guard let at = code.range(of: "presentationDetents(") else { return false }
        return code[at.upperBound...].contains(".medium")
    }

    func testTheRuleSeesAMediumDetent() {
        XCTAssertTrue(Self.usesMedium("        .presentationDetents([.medium, .large])"))
        XCTAssertTrue(Self.usesMedium(".presentationDetents(big ? [.large] : [.medium, .large])"))
        XCTAssertFalse(Self.usesMedium("        .presentationDetents([.large])"))
        XCTAssertFalse(Self.usesMedium("        .fittedDetents() // not .presentationDetents([.medium])"))
        XCTAssertFalse(Self.usesMedium("Text(x).fontWeight(.medium)"))
    }

    func testNoSheetOpensAtHalfTheScreen() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("EcencyApp")
        var offenders: [String] = []
        var count = 0
        guard let files = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil) else { return XCTFail("no sources") }
        for case let url as URL in files where url.pathExtension == "swift" && url.lastPathComponent != Self.allowedFile {
            count += 1
            let lines = try String(contentsOf: url, encoding: .utf8).components(separatedBy: "\n")
            for (i, line) in lines.enumerated() where Self.usesMedium(line) {
                offenders.append("\(url.lastPathComponent):\(i + 1): \(line.trimmingCharacters(in: .whitespaces))")
            }
        }
        XCTAssertGreaterThan(count, 40, "the app sources were found")
        XCTAssertTrue(offenders.isEmpty, "a sheet at half the screen; use FittedSheetScroll with .fittedDetents():\n" + offenders.joined(separator: "\n"))
    }
}
