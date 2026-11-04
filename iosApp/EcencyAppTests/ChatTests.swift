import EcencyCore
import SwiftUI
import XCTest
@testable import EcencyApp

/// The chat bridge pieces in Swift: message spans to AttributedString, where a tapped link goes, the names the
/// Objective-C export gives the chat types, and the chat models made from an environment.
@MainActor
final class ChatTests: XCTestCase {
    private func makeEnv() throws -> AppEnvironment {
        AppEnvironment(
            api: try EcencyApi(nodes: ["https://fake-hive.invalid"], transport: FakeHiveTransport()),
            vault: KeyVaultAsync(vault: KeyVault(store: KeychainSecureStore(service: "app.esteem.mobile.ios.tests.chat"))),
            renderer: PostBodyRendererCompanion.shared.`default`(proxyBase: ImageProxy.companion.DEFAULT_BASE),
            dispatchers: AppDispatchers.companion.platform(),
            proxyBase: ImageProxy.companion.DEFAULT_BASE,
            debugKeyImport: true,
            nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
            security: SecurityConfig(pinIterations: 16, lockTimeoutMillis: 60_000, monotonicMillis: { KotlinLong(value: 0) }),
            legacy: nil,
            auth: AuthServices.companion.offline()
        )
    }

    func testSpansBecomeStyledTextWithTappableLinks() {
        let spans = ChatText.shared.spans(text: ChatText.shared.content(raw: "hi @alice **big** _it_ `code` ~~old~~", parseMentionUrl: true).text)
        let text = ChatAttributed.make(spans, linkColor: .blue)
        XCTAssertEqual(String(text.characters), "hi @alice big it code old")
        let runs = Array(text.runs)
        let link = runs.first { $0.link != nil }
        XCTAssertEqual(link?.link?.absoluteString, "https://ecency.com/@alice")
        XCTAssertEqual(String(text[link!.range].characters), "@alice")
        func intent(_ word: String) -> InlinePresentationIntent? {
            runs.first { String(text[$0.range].characters) == word }?.inlinePresentationIntent
        }
        XCTAssertEqual(intent("big"), .stronglyEmphasized)
        XCTAssertEqual(intent("it"), .emphasized)
        XCTAssertEqual(intent("code"), .code)
        XCTAssertNotNil(runs.first { String(text[$0.range].characters) == "old" }?.strikethroughStyle)
    }

    func testMarksReachLinksAndCodeIsShadedAndAngleBracketsStay() {
        let spans = ChatText.shared.spans(text: "**see https://x.com/a** `x_y` !ban <user> [30d]")
        let text = ChatAttributed.make(spans, linkColor: .blue)
        XCTAssertEqual(String(text.characters), "see https://x.com/a x_y !ban <user> [30d]")
        let runs = Array(text.runs)
        let link = runs.first { $0.link != nil }
        XCTAssertEqual(link?.link?.absoluteString, "https://x.com/a")
        XCTAssertEqual(link?.inlinePresentationIntent, .stronglyEmphasized)
        let code = runs.first { String(text[$0.range].characters) == "x_y" }
        XCTAssertEqual(code?.inlinePresentationIntent, .code)
        XCTAssertNotNil(code?.backgroundColor)
    }

    func testLinksGoToTheirFlow() {
        XCTAssertEqual(ChatLinkKind.of("hive://sign/op/abc.."), .appLink)
        XCTAssertEqual(ChatLinkKind.of("ECENCY://login"), .appLink)
        XCTAssertEqual(ChatLinkKind.of("https://ecency.com/@alice/post"), .web)
        XCTAssertEqual(ChatLinkKind.of("javascript:alert(1)"), .ignored)
        XCTAssertEqual(ChatLinkKind.of("file:///etc/passwd"), .ignored)
        // a hive link in a message is a link of its own, padding included
        let spans = ChatText.shared.spans(text: "open hive://sign/op/WyJ9.. now")
        XCTAssertEqual(spans.first { $0.url != nil }?.url, "hive://sign/op/WyJ9..")
    }

    func testExportedNamesTheAppReadsStayStable() {
        // ChatItem's id is exported as rowId so UiMessage keeps `id_` (ShellViews reads it)
        let toast = UiMessageToast(id: 7, text: "t", durationMillis: 1000)
        XCTAssertEqual(toast.id_, 7)
        let notice = ChatNoticeItem(id: "n1", text: "x", timestamp: 5)
        XCTAssertEqual(notice.rowId, "n1")
        XCTAssertEqual(ChatMessageAction.theCopy.label, "Copy")
        XCTAssertEqual(DmPrivacy.entries.map(\.label), ["Everyone", "Only people I follow", "No one (disable DMs)"])
    }

    /// The shared list through the iOS build (Foundation's NFKD and character sets behind the name check).
    func testOfficialAccountsAndLookalikesOnIos() {
        XCTAssertTrue(OfficialAccounts.shared.isOfficial(username: "esie"))
        XCTAssertTrue(OfficialAccounts.shared.isOfficial(username: "@Ecency"))
        for name in ["esie1", "ecency-official", "\u{0435}sie", "\u{FF45}\u{FF53}\u{FF49}\u{FF45}", "es\u{200B}ie"] {
            XCTAssertFalse(OfficialAccounts.shared.isOfficial(username: name), name)
        }
        for name in ["E s i e", "\u{00C9}sie", "Es\u{200B}ie", "\u{FF25}\u{FF53}\u{FF49}\u{FF45}", "\u{1D404}\u{1D42C}\u{1D422}\u{1D41E}", "Ecen\u{00A2}y"] {
            XCTAssertTrue(OfficialAccounts.shared.impersonates(displayName: name, username: "scammer"), name)
        }
        for name in ["Jessie", "Decency", "\u{738B}\u{5C0F}\u{660E}"] {
            XCTAssertFalse(OfficialAccounts.shared.impersonates(displayName: name, username: "someone"), name)
        }
        XCTAssertEqual(OfficialAccounts.shared.BADGE, "Official")
        XCTAssertEqual(OfficialAccounts.shared.BADGE_LABEL, "Official Ecency account")
        XCTAssertEqual(SideMenuItem.askEsie.title, "Ask Esie")
    }

    func testAvatarsOnlyForValidAccounts() {
        XCTAssertTrue(ChatAvatarCompanion.shared.account(name: "spike-alice", fallbackTitle: "x") is ChatAvatarAccount)
        let bad = ChatAvatarCompanion.shared.account(name: "../evil", fallbackTitle: "Book club")
        XCTAssertEqual((bad as? ChatAvatarInitial)?.letter, "B")
    }

    func testSignedOutChatModelsAskNothing() throws {
        let env = try makeEnv()
        let list = env.chat.chatsModel()
        list.show()
        XCTAssertNil(list.current.account)
        XCTAssertTrue(list.current.rows.isEmpty)
        XCTAssertFalse(list.current.canStartGroup)
        let privacy = env.chat.privacyModel()
        privacy.start()
        XCTAssertNil(privacy.current.account)
        XCTAssertEqual(privacy.current.value, .all)
        XCTAssertEqual(ChatPrivacyModel.companion.TITLE, "Chat Privacy")
        list.close()
        privacy.close()
    }
}
