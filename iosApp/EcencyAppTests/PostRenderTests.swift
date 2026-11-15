import EcencyCore
import SwiftUI
import XCTest
@testable import EcencyApp

@MainActor
final class PostRenderTests: XCTestCase {
    private let mention: [any Inline] = [
        InlineText(text: "Hi ", marks: []),
        InlineMention(author: "ecency"),
        InlineLink(target: LinkTargetExternal(url: "https://ecency.com"), children: [InlineText(text: "site", marks: [])]),
        InlineTag(tag: "hive", filter: nil),
    ]

    private func index(of chip: String, in built: InlineTextBuilder.Built) -> Int? {
        var found: Int?
        built.text.enumerateAttribute(.attachment, in: NSRange(location: 0, length: built.text.length)) { value, range, stop in
            if (value as? ChipAttachment)?.label == chip { found = range.location; stop.pointee = true }
        }
        return found
    }

    func testLinksAndChipsMapBackToTheirTargets() {
        let built = InlineTextBuilder(theme: .light, size: 16).build(mention)

        XCTAssertEqual(built.targets.count, 3)
        let author = index(of: "@ecency", in: built)!
        let tag = index(of: "#hive", in: built)!
        let site = (built.text.string as NSString).range(of: "site").location
        XCTAssertEqual((built.target(at: author) as? LinkTargetAuthor)?.author, "ecency")
        XCTAssertEqual((built.target(at: site) as? LinkTargetExternal)?.url, "https://ecency.com")
        XCTAssertEqual((built.target(at: tag) as? LinkTargetTag)?.tag, "hive")
        XCTAssertNil(built.target(at: 0), "plain text is not a target")
        XCTAssertNil(built.target(at: 999))
    }

    /// #3699: a selection reads, and Copy writes, each chip as its `@user` / `#tag` label, never the attachment
    /// character that stands for it in the text.
    @MainActor
    func testASelectionAndCopyTakeChipsAsTheirLabels() {
        let built = InlineTextBuilder(theme: .light, size: 16).build(mention)
        let view = PostUITextView(usingTextLayoutManager: false)
        view.isEditable = false
        view.isSelectable = true
        view.attributedText = built.text
        let all = NSRange(location: 0, length: built.text.length)
        let text = view.plainText(in: all) ?? ""
        XCTAssertTrue(text.contains("@ecency"), text)
        XCTAssertTrue(text.contains("#hive"), text)
        XCTAssertFalse(text.contains("\u{FFFC}"), text)
        XCTAssertTrue(text.hasPrefix("Hi "), text)
        XCTAssertNil(view.plainText(in: NSRange(location: 0, length: 0)), "nothing selected")
        UIPasteboard.general.string = ""
        view.selectedRange = all
        view.copy(nil)
        XCTAssertEqual(UIPasteboard.general.string, text)
    }

    func testMentionChipCarriesTheSmallAvatarAndTagChipNone() {
        let built = InlineTextBuilder(theme: .light, size: 16).build(mention)
        let chips = built.chips
        XCTAssertEqual(chips.map(\.label), ["@ecency", "#hive"])
        XCTAssertEqual(chips[0].avatarURL, "https://images.ecency.com/u/ecency/avatar/small")
        XCTAssertNil(chips[1].avatarURL)
        XCTAssertEqual(chips[0].bounds.height, 18)
        // Avatar slot (2 + 14 + 4) makes the mention chip wider than a tag chip with the same label length.
        XCTAssertEqual(chips[0].bounds.width - ChipAttachment.width(label: "@ecency", avatar: false), 14, accuracy: 0.5)
        let image = chips[0].image(forBounds: chips[0].bounds, textContainer: nil, characterIndex: 0)
        XCTAssertEqual(image?.size.height, 18)
    }

    func testTappingAChipInTheTextViewResolvesItsTarget() {
        let built = InlineTextBuilder(theme: .light, size: 16).build(mention)
        let view = PostUITextView(usingTextLayoutManager: false)
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.isScrollEnabled = false
        view.attributedText = built.text
        view.frame = CGRect(x: 0, y: 0, width: 320, height: 200)
        view.layoutIfNeeded()

        let glyph = view.layoutManager.glyphIndexForCharacter(at: index(of: "@ecency", in: built)!)
        let rect = view.layoutManager.boundingRect(forGlyphRange: NSRange(location: glyph, length: 1), in: view.textContainer)
        let hit = view.characterIndex(at: CGPoint(x: rect.midX, y: rect.midY))
        XCTAssertEqual((hit.flatMap { built.target(at: $0) } as? LinkTargetAuthor)?.author, "ecency")
        XCTAssertNil(view.characterIndex(at: CGPoint(x: 310, y: 190)), "empty space below the text is no target")
    }

    func testJustifyAndAlignmentReachTheParagraphStyle() {
        func alignment(_ style: BlockStyle) -> NSTextAlignment? {
            let built = InlineTextBuilder(theme: .light, size: 16, style: style).build(mention)
            return (built.text.attribute(.paragraphStyle, at: 0, effectiveRange: nil) as? NSParagraphStyle)?.alignment
        }
        XCTAssertEqual(alignment(BlockStyle(align: .start, justify: true, phishy: false)), .justified)
        XCTAssertEqual(alignment(BlockStyle(align: .center, justify: false, phishy: false)), .center)
        XCTAssertEqual(alignment(BlockStyle.companion.Default), .natural)
    }

    func testLongPressTextIsThePlainTextOfTheParagraph() {
        // Chips read as @user and #tag; no spaces are invented between adjacent inlines.
        XCTAssertEqual(PlainTextKt.plainText(mention), "Hi @ecencysite#hive")
    }

    func testParagraphSelfSizesForTheProposedWidth() {
        let long = (0..<40).map { _ in "word" }.joined(separator: " ")
        func height(_ text: String, width: CGFloat) -> CGFloat {
            let view = ParagraphView(inlines: [InlineText(text: text, marks: [])], style: BlockStyle.companion.Default,
                                     size: 16, bold: false, spacing: 0, maxWidth: width, actions: PostActions())
            return UIHostingController(rootView: view).sizeThatFits(in: CGSize(width: width, height: .infinity)).height
        }
        let short = height("one line", width: 320)
        XCTAssertGreaterThan(short, 10)
        XCTAssertGreaterThan(height(long, width: 320), short * 3)
        XCTAssertGreaterThan(height(long, width: 160), height(long, width: 320))
    }

    func testGalleryCollectsEveryImageOnceInDocumentOrder() {
        func img(_ n: Int) -> BlockImage { BlockImage(src: "https://example.com/\(n).png", alt: nil, link: nil) }
        let blocks: [any Block] = [
            img(1),
            BlockQuote(blocks: [img(2)]),
            BlockListBlock(ordered: false, start: 1, items: [[img(3)], [img(1)]]),
            BlockImageGrid(images: [img(4), img(5)]),
            BlockImage(src: "https://example.com/linked.png", alt: nil, link: LinkTargetExternal(url: "https://example.com")),
        ]
        XCTAssertEqual(collectImages(blocks), (1...5).map { "https://example.com/\($0).png" })
    }

    func testSamplePostGoesThroughThePipeline() {
        let blocks = BlockBuilder.shared.build(html: SampleHtml.shared.POST)
        let images = collectImages(blocks)
        XCTAssertEqual(images, ["https://images.ecency.com/u/ecency/avatar/large"])
        XCTAssertTrue(blocks.contains { $0 is BlockVideo })
        XCTAssertTrue(blocks.contains { $0 is BlockPostPreview })
    }

    func testDynamicTypeScalesTextAndGrowsChips() {
        func built(_ category: UIContentSizeCategory) -> InlineTextBuilder.Built {
            InlineTextBuilder(theme: .light, size: 16, contentSize: category).build(mention)
        }
        let normal = built(.large)
        let huge = built(.accessibilityExtraExtraLarge)
        let font = { (b: InlineTextBuilder.Built) in (b.text.attribute(.font, at: 0, effectiveRange: nil) as! UIFont).pointSize }
        XCTAssertEqual(font(normal), 16, accuracy: 0.01)
        XCTAssertGreaterThan(font(huge), 30)
        XCTAssertEqual(normal.chips[0].bounds.height, 18)
        XCTAssertGreaterThan(huge.chips[0].bounds.height, 18)
        XCTAssertGreaterThan(huge.chips[0].bounds.width, normal.chips[0].bounds.width)
    }

    func testInvalidAccountNamesGetNoAvatar() {
        XCTAssertEqual(PostActions.defaultAvatarURL("hive.blog"), "https://images.ecency.com/u/hive.blog/avatar/small")
        XCTAssertNil(PostActions.defaultAvatarURL("../evil"))
        XCTAssertNil(PostActions.defaultAvatarURL("a b"))
        let built = InlineTextBuilder(theme: .light, size: 16).build([InlineMention(author: "Bad/Name")])
        XCTAssertNil(built.chips[0].avatarURL)
    }

    func testEveryLinkAndChipIsAVoiceOverAction() throws {
        var tapped: [String] = []
        let actions = PostActions(onLink: { target in
            switch target {
            case let a as LinkTargetAuthor: tapped.append("@" + a.author)
            case let e as LinkTargetExternal: tapped.append(e.url)
            case let t as LinkTargetTag: tapped.append("#" + t.tag)
            default: break
            }
        })
        let host = UIHostingController(rootView: PostTextView(inlines: mention, style: BlockStyle.companion.Default, size: 16,
                                                              bold: false, maxWidth: 320, actions: actions))
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 400))
        window.rootViewController = host
        window.makeKeyAndVisible()
        host.view.layoutIfNeeded()
        defer { window.isHidden = true }
        let view = try XCTUnwrap(find(PostUITextView.self, in: host.view))
        XCTAssertEqual(view.accessibilityLabel, "Hi @ecencysite#hive")
        let custom = try XCTUnwrap(view.accessibilityCustomActions)
        XCTAssertEqual(custom.map(\.name), ["@ecency", "site", "#hive"])
        for action in custom { _ = action.actionHandler?(action) }
        XCTAssertEqual(tapped, ["@ecency", "https://ecency.com", "#hive"])
    }

    /// Review fix (#3699): a code block selects in place with the body's menu where the body does, and stays plain text elsewhere.
    func testACodeBlockSelectsWithTheBodyMenu() throws {
        func hosted(_ actions: PostActions) -> (UIWindow, UIView) {
            let host = UIHostingController(rootView: BlockView(block: BlockCode(text: "  val x = 1"), maxWidth: 320, actions: actions,
                                                               images: GalleryImages([])))
            let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 400))
            window.rootViewController = host
            window.makeKeyAndVisible()
            host.view.layoutIfNeeded()
            return (window, host.view)
        }
        let (window, root) = hosted(PostActions(onSelection: { _, _ in }))
        defer { window.isHidden = true }
        let view = try XCTUnwrap(find(UITextView.self, in: root))
        XCTAssertEqual(view.text, "  val x = 1")
        XCTAssertTrue(view.isSelectable)
        XCTAssertGreaterThan(view.bounds.width, 0)
        let menu = try XCTUnwrap(view.delegate?.textView?(view, editMenuForTextIn: NSRange(location: 0, length: 5), suggestedActions: []))
        func titles(_ element: UIMenuElement) -> [String] {
            if let action = element as? UIAction { return [action.title] }
            return (element as? UIMenu)?.children.flatMap(titles) ?? []
        }
        let titles = titles(menu)
        XCTAssertTrue(titles.contains(BodySelectionAction.quote.title), "\(titles)")
        let (plainWindow, plain) = hosted(PostActions())
        defer { plainWindow.isHidden = true }
        XCTAssertNil(find(UITextView.self, in: plain), "no selection where the body has none")
        // drawn as before: a two line block with a long line keeps its lines (scrolls sideways) and its height
        let code = BlockCode(text: "fn main() {\n    let long = \"" + String(repeating: "x", count: 80) + "\";\n}")
        func height(_ actions: PostActions) -> CGFloat {
            UIHostingController(rootView: BlockView(block: code, maxWidth: 320, actions: actions, images: GalleryImages([])))
                .sizeThatFits(in: CGSize(width: 320, height: CGFloat.infinity)).height
        }
        // (SwiftUI's line spacing is a point or so taller a line; a wrapped line would add a whole line, about 20)
        XCTAssertEqual(height(PostActions(onSelection: { _, _ in })), height(PostActions()), accuracy: 6)
    }

    private func find<T: UIView>(_ type: T.Type, in view: UIView) -> T? {
        if let hit = view as? T { return hit }
        for sub in view.subviews { if let hit = find(type, in: sub) { return hit } }
        return nil
    }
}

/// Review fix (#3699): a reply sheet shows the environment's current composer for its parent, not one replaced since.
@MainActor
final class ReplyDraftsTests: XCTestCase {
    func testADraftFollowsTheComposerTheEnvironmentHasNow() async throws {
        let env = AppEnvironment(
            api: try EcencyApi(nodes: ["https://fake-hive.invalid"], transport: FakeHiveTransport()),
            vault: KeyVaultAsync(vault: KeyVault(store: KeychainSecureStore(service: "app.esteem.mobile.ios.tests.replydrafts"))),
            renderer: PostBodyRendererCompanion.shared.`default`(proxyBase: ImageProxy.companion.DEFAULT_BASE),
            dispatchers: AppDispatchers.companion.platform(),
            proxyBase: ImageProxy.companion.DEFAULT_BASE,
            debugKeyImport: true,
            nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
            security: SecurityConfig(pinIterations: 16, lockTimeoutMillis: 60_000, monotonicMillis: { KotlinLong(value: 0) }),
            legacy: nil,
            auth: AuthServices.companion.offline()
        )
        let root = PostKey(author: "spike-alice", permlink: "a-post")
        // two composers (two parents from the fake data) stand for a composer and the one that replaced it
        let post = try await env.api.posts.getPost(author: "spike-alice", permlink: "a-post", observer: nil, refresh: false)
        let comment = try await env.api.posts.getPost(author: "spike-bob", permlink: "re-c1", observer: nil, refresh: false)
        let first = env.replyModel(root: root, parent: try XCTUnwrap(post))
        let second = env.replyModel(root: root, parent: try XCTUnwrap(comment))
        let drafts = ReplyDrafts()
        let a = drafts.draft(id: "x", current: first)
        XCTAssertTrue(drafts.draft(id: "x", current: first) === a, "the same composer: the same draft")
        let b = drafts.draft(id: "x", current: second)
        XCTAssertTrue(b.model === second, "a replaced composer: the draft over the new one")
        XCTAssertFalse(b === a)
    }
}
