import EcencyCore
import SwiftUI
import UIKit

/// A paragraph or heading drawn by TextKit 1 in a non-editable, non-selectable, non-scrolling
/// UITextView, so `@user` chips (with avatar) wrap like words, `text-justify` justifies, and taps and
/// long-presses are resolved here instead of by UIKit's link handling.
///
/// Rows in a LazyVStack are torn down and rebuilt while scrolling, so the attributed string and the
/// measured heights live in process-wide caches keyed by content, size, Dynamic Type and theme
/// (`PostTextCache`), not in the view's coordinator.
///
/// Accessibility: the paragraph reads as one text, and every link and chip is a VoiceOver custom
/// action (rotor "Actions") that fires the same `onLink` as a tap.
struct PostTextView: UIViewRepresentable {
    let inlines: [any Inline]
    let style: BlockStyle
    let size: CGFloat
    let bold: Bool
    let maxWidth: CGFloat
    let actions: PostActions
    @Environment(\.postTheme) private var theme
    @Environment(\.dynamicTypeSize) private var typeSize

    private var key: ContentKey {
        ContentKey(inlines: inlines as NSArray, style: style, size: size, bold: bold, dark: theme.isDark, typeSize: typeSize)
    }

    func makeUIView(context: Context) -> PostUITextView {
        let view = PostUITextView(usingTextLayoutManager: false)
        view.isEditable = false
        view.isSelectable = false
        view.isScrollEnabled = false
        view.backgroundColor = .clear
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.setContentHuggingPriority(.defaultLow, for: .horizontal)
        view.isAccessibilityElement = true
        view.accessibilityTraits = .staticText
        view.accessibilityIdentifier = "paragraph"
        view.addGestureRecognizer(UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.tap(_:))))
        view.addGestureRecognizer(UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.longPress(_:))))
        return view
    }

    func updateUIView(_ view: PostUITextView, context: Context) {
        let coordinator = context.coordinator
        coordinator.actions = actions
        let key = self.key
        guard coordinator.key != key else { return }
        coordinator.key = key
        let entry = PostTextCache.shared.entry(for: key) {
            let built = InlineTextBuilder(theme: theme, size: size, bold: bold, style: style, avatarURL: actions.avatarURL,
                                          contentSize: UIContentSizeCategory(typeSize)).build(inlines)
            return PostTextCache.Entry(built: built, plainText: PlainTextKt.plainText(inlines))
        }
        coordinator.entry = entry
        view.attributedText = entry.built.text
        view.accessibilityLabel = entry.plainText
        view.accessibilityCustomActions = entry.built.targets.indices.map { i in
            UIAccessibilityCustomAction(name: entry.built.targetNames[i]) { [weak coordinator] _ in
                guard let coordinator else { return false }
                coordinator.actions.onLink(entry.built.targets[i])
                return true
            }
        }
        coordinator.loadAvatars(for: entry.built, in: view)
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: PostUITextView, context: Context) -> CGSize? {
        var width = proposal.width ?? maxWidth
        if !width.isFinite || width <= 0 { width = maxWidth }
        let key = self.key
        if let cached = PostTextCache.shared.height(for: key, width: width) { return CGSize(width: width, height: cached) }
        // `sizeThatFits` can run before `updateUIView` has put this key's text on the view.
        guard context.coordinator.key == key else { return nil }
        let height = ceil(uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height)
        PostTextCache.shared.setHeight(height, for: key, width: width)
        return CGSize(width: width, height: height)
    }

    func makeCoordinator() -> Coordinator { Coordinator(actions: actions) }

    struct ContentKey: Hashable {
        let inlines: NSArray
        let style: BlockStyle
        let size: CGFloat
        let bold: Bool
        let dark: Bool
        let typeSize: DynamicTypeSize
    }

    final class Coordinator: NSObject {
        var actions: PostActions
        var key: ContentKey?
        var entry: PostTextCache.Entry?

        init(actions: PostActions) { self.actions = actions }

        @objc func tap(_ gesture: UITapGestureRecognizer) {
            guard let view = gesture.view as? PostUITextView, let built = entry?.built,
                  let index = view.characterIndex(at: gesture.location(in: view)),
                  let target = built.target(at: index) else { return }
            actions.onLink(target)
        }

        @objc func longPress(_ gesture: UILongPressGestureRecognizer) {
            guard gesture.state == .began, let text = entry?.plainText, !text.isEmpty else { return }
            actions.onParagraphLongPress(text)
        }

        /// Chips draw a placeholder disc at once; `ImageLoader` fetches each avatar once (shared
        /// in-flight task, failures remembered) and the paragraph is redrawn when it arrives.
        func loadAvatars(for built: InlineTextBuilder.Built, in view: PostUITextView) {
            let urls = Set(built.chips.filter(\.needsAvatar).compactMap(\.avatarURL))
            for url in urls {
                Task { @MainActor [weak view] in
                    guard await ImageLoader.shared.load(url, maxPixel: ChipAttachment.avatarPixels) != nil, let view else { return }
                    view.layoutManager.invalidateDisplay(forCharacterRange: NSRange(location: 0, length: view.textStorage.length))
                }
            }
        }
    }
}

/// Built text and measured heights per paragraph content, shared by every row that shows it.
final class PostTextCache: @unchecked Sendable {
    static let shared = PostTextCache()

    final class Entry {
        let built: InlineTextBuilder.Built
        let plainText: String
        init(built: InlineTextBuilder.Built, plainText: String) {
            self.built = built
            self.plainText = plainText
        }
    }

    private final class KeyBox: NSObject {
        let key: AnyHashable
        init(_ key: AnyHashable) { self.key = key }
        override var hash: Int { key.hashValue }
        override func isEqual(_ object: Any?) -> Bool { (object as? KeyBox)?.key == key }
    }

    private final class HeightBox {
        let value: CGFloat
        init(_ value: CGFloat) { self.value = value }
    }

    private struct HeightKey: Hashable {
        let content: PostTextView.ContentKey
        let width: CGFloat
    }

    private let entries: NSCache<KeyBox, Entry> = {
        let cache = NSCache<KeyBox, Entry>()
        cache.countLimit = 2_000
        return cache
    }()

    private let heights: NSCache<KeyBox, HeightBox> = {
        let cache = NSCache<KeyBox, HeightBox>()
        cache.countLimit = 4_000
        return cache
    }()

    /// Attributed strings built so far (tests check rows scrolling back in do not rebuild).
    private(set) var builds = 0
    /// Heights measured with TextKit so far.
    private(set) var measures = 0

    func entry(for key: PostTextView.ContentKey, build: () -> Entry) -> Entry {
        let box = KeyBox(key)
        if let hit = entries.object(forKey: box) { return hit }
        builds += 1
        let entry = build()
        entries.setObject(entry, forKey: box)
        return entry
    }

    func height(for key: PostTextView.ContentKey, width: CGFloat) -> CGFloat? {
        heights.object(forKey: KeyBox(HeightKey(content: key, width: width)))?.value
    }

    func setHeight(_ height: CGFloat, for key: PostTextView.ContentKey, width: CGFloat) {
        measures += 1
        heights.setObject(HeightBox(height), forKey: KeyBox(HeightKey(content: key, width: width)))
    }

    func removeAll() {
        entries.removeAllObjects()
        heights.removeAllObjects()
        builds = 0
        measures = 0
    }
}

final class PostUITextView: UITextView {
    /// Character under `point`, or nil when the point is past the end of a line or below the text.
    func characterIndex(at point: CGPoint) -> Int? {
        let container = textContainer
        let location = CGPoint(x: point.x - textContainerInset.left, y: point.y - textContainerInset.top)
        let glyph = layoutManager.glyphIndex(for: location, in: container)
        let rect = layoutManager.boundingRect(forGlyphRange: NSRange(location: glyph, length: 1), in: container)
        guard rect.insetBy(dx: -2, dy: -2).contains(location) else { return nil }
        return layoutManager.characterIndexForGlyph(at: glyph)
    }
}
