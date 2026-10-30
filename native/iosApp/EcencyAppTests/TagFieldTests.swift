import EcencyCore
import UIKit
import XCTest
@testable import EcencyApp

/// The editor's UIKit tag field against a real editor model: UIKit applies every edit, the model's answer replaces
/// the text with the caret kept, and the field reports what it shows (a real duplicate still toasts).
@MainActor
final class TagFieldTests: XCTestCase {
    private var env: AppEnvironment!
    private var model: EditorModel!
    private var coordinator: TagField.Coordinator!
    private let field = UITextField()

    override func setUp() async throws {
        env = AppEnvironment(
            api: try EcencyApi(nodes: ["https://fake-hive.invalid"], transport: FakeHiveTransport()),
            vault: KeyVaultAsync(vault: KeyVault(store: KeychainSecureStore(service: "app.esteem.mobile.ios.tests.tagfield"))),
            renderer: PostBodyRendererCompanion.shared.`default`(proxyBase: ImageProxy.companion.DEFAULT_BASE),
            dispatchers: AppDispatchers.companion.platform(),
            proxyBase: ImageProxy.companion.DEFAULT_BASE,
            debugKeyImport: true,
            nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
            security: SecurityConfig(pinIterations: 16, lockTimeoutMillis: 60_000, monotonicMillis: { KotlinLong(value: 0) }),
            legacy: nil,
            auth: AuthServices.companion.offline()
        )
        model = env.editorModel(args: EditorArgs(kind: .theNewPost, author: "", permlink: "", draftId: "", templateId: "", community: "", tags: [], rootAuthor: "", rootPermlink: ""))
        coordinator = TagField.Coordinator(model: model)
    }

    /// What UIKit does for a keystroke: the field takes the text and caret, then sends editingChanged.
    private func edit(_ text: String, caretAt offset: Int? = nil) {
        field.text = text
        let position = field.position(from: field.beginningOfDocument, offset: offset ?? (text as NSString).length)!
        field.selectedTextRange = field.textRange(from: position, to: position)
        coordinator.changed(field)
    }

    private var caret: Int { field.offset(from: field.beginningOfDocument, to: field.selectedTextRange!.start) }

    func testAChipLeavesTheFieldAndTheCaretKeepsItsPlace() {
        edit("ab")
        // a space typed in the middle: "ab" becomes a chip, the caret stays before "c"
        edit("ab c", caretAt: 3)
        XCTAssertEqual(model.current.tags, ["ab"])
        XCTAssertEqual(field.text, "c")
        XCTAssertEqual(caret, 0)
        // lowercasing keeps the caret where it was too
        edit("cDe", caretAt: 2)
        XCTAssertEqual(field.text, "cde")
        XCTAssertEqual(caret, 2)
    }

    func testComposedCharactersAndEmojiAreNeverDropped() {
        edit("café 😀x")
        XCTAssertEqual(model.current.tags, ["café"])
        XCTAssertEqual(field.text, "😀x")
        XCTAssertEqual(field.text, model.current.tagInput)
    }

    func testADuplicatePastedIntoTheClearedFieldToasts() {
        edit("a ")
        XCTAssertEqual(field.text, "")
        XCTAssertNil(env.ui.current.toast)
        edit("a ")
        XCTAssertEqual(env.ui.current.toast?.text, EditorTags.shared.DUPLICATE)
        XCTAssertEqual(model.current.tags, ["a"])
    }

    func testTheFieldShowsTheModelsCurrentTextNotAStaleSnapshot() {
        edit("x ")
        edit("y")
        XCTAssertEqual(model.current.tags, ["x"])
        // a SwiftUI update rendered before the last keystrokes still carries "x ": it never puts it back
        coordinator.update(field, snapshot: "x ")
        XCTAssertEqual(field.text, "y")
        coordinator.update(field, snapshot: "")
        XCTAssertEqual(field.text, "y")
    }
}
