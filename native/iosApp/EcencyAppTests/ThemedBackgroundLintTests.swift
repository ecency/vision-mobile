import SwiftUI
import XCTest
@testable import EcencyApp

/// Every screen, sheet and state draws the app theme's background (README "Polish", themed backgrounds). A cheap
/// source scan of `EcencyApp/**/*.swift` that fails when new code reaches for a system colour instead:
/// - a `List` or `Form` without `.themedList()` (the system list colours: black rows and grouped grey in dark mode);
/// - a raw `.sheet(` or `.fullScreenCover(` instead of `.themedSheet(` / `.themedFullScreenCover(` (the system sheet
///   colour, black or dark grey);
/// - the system background colours, grouped list styles or a UIKit appearance proxy;
/// - black as a page background, allowed only behind pictures and video with a `// media:` comment saying so.
/// The pixel sweep in `ThemedBackgroundUITests` checks the result on screen.
final class ThemedBackgroundLintTests: XCTestCase {
    /// Files and the token they may use: the sheet wrappers themselves, the launch screen's asset fallback.
    nonisolated static let allowed: [(file: String, token: String)] = [
        ("Components.swift", ".sheet("), ("Components.swift", ".fullScreenCover("),
        ("LaunchSplash.swift", ".systemBackground"),
    ]

    nonisolated static let forbidden = [
        "systemBackground", "systemGroupedBackground", "secondarySystem", "tertiarySystem", "insetGrouped",
        ".listStyle(.grouped)", "UITableView.appearance", "UICollectionView.appearance", "UINavigationBar.appearance",
        ".sheet(", ".fullScreenCover(",
    ]

    /// A black fill used as a background, not a dimming scrim (those carry an opacity).
    nonisolated static let blackBackground = [#"\.background\((Color)?\.black\)"#, #"backgroundColor = \.black\b"#, #"^\s*(else \{ )?Color\.black( \})?\s*(//.*)?$"#]

    private func sources() throws -> [(name: String, lines: [String])] {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("EcencyApp")
        var out: [(String, [String])] = []
        guard let files = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil) else { return [] }
        for case let url as URL in files where url.pathExtension == "swift" {
            out.append((url.lastPathComponent, try String(contentsOf: url, encoding: .utf8).components(separatedBy: "\n")))
        }
        XCTAssertGreaterThan(out.count, 40, "the app sources were found")
        return out
    }

    func testNoSystemBackgrounds() throws {
        var offenders: [String] = []
        for (name, lines) in try sources() {
            for (i, line) in lines.enumerated() {
                let code = line.components(separatedBy: "//").first ?? line
                for token in Self.forbidden where code.contains(token) && !Self.allowed.contains(where: { $0.file == name && $0.token.contains(token) }) {
                    offenders.append("\(name):\(i + 1): \(token): \(line.trimmingCharacters(in: .whitespaces))")
                }
            }
        }
        XCTAssertTrue(offenders.isEmpty, "system colours instead of the theme background:\n" + offenders.joined(separator: "\n"))
    }

    func testBlackOnlyBehindMedia() throws {
        var offenders: [String] = []
        for (name, lines) in try sources() {
            for (i, line) in lines.enumerated() where Self.isBlackBackground(line) {
                let marked = line.contains("// media:") || (i > 0 && lines[i - 1].contains("// media:"))
                if !marked { offenders.append("\(name):\(i + 1): \(line.trimmingCharacters(in: .whitespaces))") }
            }
        }
        XCTAssertTrue(offenders.isEmpty, "black backgrounds without a `// media:` reason:\n" + offenders.joined(separator: "\n"))
    }

    func testEveryListIsThemed() throws {
        var offenders: [String] = []
        for (name, lines) in try sources() {
            for (i, line) in lines.enumerated() where Self.opensList(line) {
                if !Self.themedAfter(lines, from: i) { offenders.append("\(name):\(i + 1): \(line.trimmingCharacters(in: .whitespaces))") }
            }
        }
        XCTAssertTrue(offenders.isEmpty, "a List or Form without .themedList():\n" + offenders.joined(separator: "\n"))
    }

    nonisolated static func isBlackBackground(_ line: String) -> Bool {
        blackBackground.contains { line.range(of: $0, options: .regularExpression) != nil }
    }

    nonisolated static func opensList(_ line: String) -> Bool {
        let code = line.components(separatedBy: "//").first ?? line
        return code.range(of: #"(^|[^\w.])(List|Form) *(\{|\()"#, options: .regularExpression) != nil
    }

    /// The modifiers chained after the List's closing brace (same line or the `.` lines that follow) include
    /// `.themedList()`.
    nonisolated static func themedAfter(_ lines: [String], from start: Int) -> Bool {
        var depth = 0
        var opened = false
        var i = start
        while i < lines.count {
            let code = lines[i].components(separatedBy: "//").first ?? lines[i]
            var tail = ""
            for (k, ch) in code.enumerated() {
                if ch == "{" || ch == "(" { depth += 1; opened = true }
                if ch == "}" || ch == ")" { depth -= 1 }
                if opened && depth == 0 {
                    tail = String(code.dropFirst(k + 1))
                    break
                }
            }
            if opened && depth == 0 {
                if tail.contains(".themedList()") { return true }
                var j = i + 1
                while j < lines.count {
                    let t = lines[j].trimmingCharacters(in: .whitespaces)
                    if t.hasPrefix("//") || t.isEmpty { j += 1; continue }
                    guard t.hasPrefix(".") else { return false }
                    if t.contains(".themedList()") { return true }
                    // a modifier spanning several lines: skip to its end
                    var d = 0
                    repeat {
                        let c = lines[j].components(separatedBy: "//").first ?? lines[j]
                        d += c.filter { $0 == "{" || $0 == "(" }.count - c.filter { $0 == "}" || $0 == ")" }.count
                        if lines[j].contains(".themedList()") { return true }
                        j += 1
                    } while d > 0 && j < lines.count
                }
                return false
            }
            i += 1
        }
        return false
    }

    /// Increase Contrast changes text and icon colours only: pages, cards and skeleton bones keep their colours, so a
    /// contrast switch never shows two shades of page.
    func testIncreaseContrastKeepsThePageColours() {
        for scheme in [ColorScheme.light, .dark] {
            let standard = AppTheme.of(scheme), high = AppTheme.of(scheme, contrast: .increased)
            XCTAssertEqual(UIColor(standard.background), UIColor(high.background), "\(scheme) background")
            XCTAssertEqual(UIColor(standard.lightBackground), UIColor(high.lightBackground), "\(scheme) cards")
        }
    }

    func testTheScanRulesThemselves() {
        XCTAssertTrue(Self.opensList("        List {"))
        XCTAssertTrue(Self.opensList("            List(s.visible, id: \\.self) { name in"))
        XCTAssertTrue(Self.opensList("                Form {"))
        XCTAssertFalse(Self.opensList("    ThemedForm {"))
        XCTAssertFalse(Self.opensList("struct ThemedForm<Content: View>: View {"))
        XCTAssertTrue(Self.themedAfter(["List {", "  Text(a)", "}", ".listStyle(.plain)", ".themedList()"], from: 0))
        XCTAssertTrue(Self.themedAfter(["Form { Group { content }.themedCardRow() }.themedList()"], from: 0))
        XCTAssertTrue(Self.themedAfter(["List {", "}", ".refreshable {", "  model.refresh()", "}", ".themedList()"], from: 0))
        XCTAssertFalse(Self.themedAfter(["List {", "  Text(a)", "}", ".listStyle(.plain)", "Text(b)", ".themedList()"], from: 0))
        XCTAssertTrue(Self.isBlackBackground("        .background(Color.black)"))
        XCTAssertTrue(Self.isBlackBackground("            Color.black"))
        XCTAssertTrue(Self.isBlackBackground("        view.backgroundColor = .black"))
        XCTAssertFalse(Self.isBlackBackground("Color.black.opacity(0.4).ignoresSafeArea()"))
        XCTAssertFalse(Self.isBlackBackground(".background(.black.opacity(0.4), in: Circle())"))
    }
}
