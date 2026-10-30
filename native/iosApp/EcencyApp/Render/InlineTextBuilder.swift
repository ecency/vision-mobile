import EcencyCore
import SwiftUI
import UIKit

extension NSAttributedString.Key {
    /// Index into `InlineTextBuilder.Built.targets` for a link or chip run. A custom key instead of
    /// `.link`, so UIKit never styles or opens it and taps always go through `PostActions`.
    static let ecencyTarget = NSAttributedString.Key("ecencyTarget")
}

/// One NSAttributedString per paragraph for `PostTextView`: marks become fonts and colors, links and
/// chips carry `.ecencyTarget`, and `@user` / `#tag` become `ChipAttachment`s drawn like the RN chips.
struct InlineTextBuilder {
    let theme: PostTheme
    let size: CGFloat
    var bold = false
    var style: BlockStyle = BlockStyle.companion.Default
    var avatarURL: (String) -> String? = PostActions.defaultAvatarURL
    /// Dynamic Type: sizes scale like RN Text does with the system text size.
    var contentSize: UIContentSizeCategory = .large

    struct Built {
        let text: NSAttributedString
        let targets: [any LinkTarget]
        /// Spoken name of each target (link text, `@user`, `#tag`), for VoiceOver actions.
        let targetNames: [String]

        func target(at characterIndex: Int) -> (any LinkTarget)? {
            guard characterIndex >= 0, characterIndex < text.length,
                  let index = text.attribute(.ecencyTarget, at: characterIndex, effectiveRange: nil) as? Int,
                  targets.indices.contains(index) else { return nil }
            return targets[index]
        }

        var chips: [ChipAttachment] {
            var out: [ChipAttachment] = []
            text.enumerateAttribute(.attachment, in: NSRange(location: 0, length: text.length)) { value, _, _ in
                if let chip = value as? ChipAttachment { out.append(chip) }
            }
            return out
        }
    }

    func build(_ inlines: [any Inline]) -> Built {
        var targets: [any LinkTarget] = []
        var names: [String] = []
        let out = NSMutableAttributedString()
        let base = baseAttributes()

        func register(_ target: any LinkTarget, name: String) -> Int {
            targets.append(target)
            names.append(name)
            return targets.count - 1
        }

        // RN wraps each chip in spaces: `{' '}<chip/>{' '}`.
        func chip(_ label: String, avatar: String?, target: Int) {
            let attachment = ChipAttachment(label: label, avatarURL: avatar, theme: theme, font: base[.font] as! UIFont, contentSize: contentSize)
            out.append(NSAttributedString(string: " ", attributes: base))
            var attrs = base
            attrs[.ecencyTarget] = target
            let piece = NSMutableAttributedString(attachment: attachment)
            piece.addAttributes(attrs, range: NSRange(location: 0, length: piece.length))
            out.append(piece)
            out.append(NSAttributedString(string: " ", attributes: base))
        }

        func emit(_ list: [any Inline], link: Int?) {
            for inline in list {
                switch inline {
                case let t as InlineText:
                    var attrs = base
                    apply(t.marks, to: &attrs)
                    if let link {
                        attrs[.ecencyTarget] = link
                        attrs[.foregroundColor] = UIColor(theme.link)
                    }
                    out.append(NSAttributedString(string: t.text, attributes: attrs))
                case let l as InlineLink:
                    emit(l.children, link: register(l.target, name: PlainTextKt.plainText(l.children)))
                case let m as InlineMention:
                    let label = "@\(m.author)"
                    chip(label, avatar: avatarURL(m.author), target: register(LinkTargetAuthor(author: m.author), name: label))
                case let tag as InlineTag:
                    let label = "#\(tag.tag)"
                    chip(label, avatar: nil, target: register(LinkTargetTag(tag: tag.tag, filter: tag.filter), name: label))
                case is InlineLineBreak:
                    out.append(NSAttributedString(string: "\n", attributes: base))
                default:
                    break
                }
            }
        }

        emit(inlines, link: nil)
        return Built(text: out, targets: targets, targetNames: names)
    }

    private func scaled(_ value: CGFloat) -> CGFloat { InlineTextBuilder.scaled(value, contentSize) }

    static func scaled(_ value: CGFloat, _ contentSize: UIContentSizeCategory) -> CGFloat {
        UIFontMetrics.default.scaledValue(for: value, compatibleWith: UITraitCollection(preferredContentSizeCategory: contentSize))
    }

    private func baseAttributes() -> [NSAttributedString.Key: Any] {
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = style.nsAlignment
        paragraph.baseWritingDirection = .natural
        return [
            .font: UIFont.systemFont(ofSize: scaled(size), weight: bold ? .bold : .regular),
            .foregroundColor: UIColor(style.phishy ? theme.danger : theme.text),
            .paragraphStyle: paragraph,
        ]
    }

    private func apply(_ marks: Set<InlineMark>, to attrs: inout [NSAttributedString.Key: Any]) {
        if marks.isEmpty { return }
        let smaller = marks.contains(.sup) || marks.contains(.sub)
        let fontSize = scaled(smaller ? 12 : size)
        var font = marks.contains(.code)
            ? UIFont.monospacedSystemFont(ofSize: fontSize, weight: bold ? .bold : .regular)
            : UIFont.systemFont(ofSize: fontSize, weight: bold ? .bold : .regular)
        var traits = font.fontDescriptor.symbolicTraits
        if marks.contains(.bold) { traits.insert(.traitBold) }
        if marks.contains(.italic) { traits.insert(.traitItalic) }
        if let descriptor = font.fontDescriptor.withSymbolicTraits(traits) { font = UIFont(descriptor: descriptor, size: fontSize) }
        attrs[.font] = font
        if marks.contains(.strike) { attrs[.strikethroughStyle] = NSUnderlineStyle.single.rawValue }
        if marks.contains(.underline) { attrs[.underlineStyle] = NSUnderlineStyle.single.rawValue }
        if marks.contains(.code) { attrs[.backgroundColor] = UIColor(theme.muted).withAlphaComponent(0.5) }
        if marks.contains(.highlight) { attrs[.backgroundColor] = UIColor(theme.link).withAlphaComponent(0.2) }
        if marks.contains(.sup) { attrs[.baselineOffset] = scaled(6) }
        if marks.contains(.sub) { attrs[.baselineOffset] = -scaled(3) }
    }
}

/// RN `tagWrapper` + `tagText` (+ `UserAvatar size="small"` for mentions): hairline `$iconColor` pill,
/// 18 high (more when Dynamic Type grows the label), 12pt link-colored label. Drawn into an image, so
/// TextKit wraps it like a glyph. Images are shared through `ChipAttachment.images`, keyed by label,
/// avatar, theme and size; the avatar version replaces the placeholder one once `ImageLoader` has it.
final class ChipAttachment: NSTextAttachment {
    static let minHeight: CGFloat = 18
    static let avatarSize: CGFloat = 14
    static var avatarPixels: CGFloat { avatarSize * UIScreen.main.scale }
    static let images: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 500
        return cache
    }()

    let label: String
    let avatarURL: String?
    private let theme: PostTheme
    private let labelFont: UIFont

    init(label: String, avatarURL: String?, theme: PostTheme, font: UIFont, contentSize: UIContentSizeCategory = .large) {
        self.label = label
        self.avatarURL = avatarURL
        self.theme = theme
        labelFont = UIFont.systemFont(ofSize: InlineTextBuilder.scaled(12, contentSize))
        super.init(data: nil, ofType: nil)
        // RN's 18 high pill fits a 12pt label; larger Dynamic Type sizes grow it so the label is never clipped.
        let height = max(ChipAttachment.minHeight, ceil(labelFont.lineHeight) + 2)
        let width = ChipAttachment.width(label: label, font: labelFont, avatar: avatarURL != nil)
        // Centre the pill on the capital letters, as `PlaceholderVerticalAlign.TextCenter` does on Android.
        bounds = CGRect(x: 0, y: (font.capHeight - height) / 2, width: width, height: height)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("not coded") }

    static func width(label: String, font: UIFont = .systemFont(ofSize: 12), avatar: Bool) -> CGFloat {
        let text = ceil((label as NSString).size(withAttributes: [.font: font]).width)
        // Label margins 6 and 6, or 4 after the avatar (2 inset + 14 avatar). +1 so it never clips.
        return text + 6 + (avatar ? 2 + avatarSize + 4 : 6) + 1
    }

    private var avatar: UIImage? {
        avatarURL.flatMap { ImageLoader.shared.cached($0, maxPixel: ChipAttachment.avatarPixels)?.image }
    }

    var needsAvatar: Bool { avatarURL != nil && avatar == nil }

    override func image(forBounds imageBounds: CGRect, textContainer: NSTextContainer?, characterIndex charIndex: Int) -> UIImage? {
        let avatar = self.avatar
        let key = "\(label)|\(avatarURL ?? "")|\(avatar != nil)|\(theme.isDark)|\(bounds.width)x\(bounds.height)" as NSString
        if let hit = ChipAttachment.images.object(forKey: key) { return hit }
        let image = render(avatar: avatar)
        ChipAttachment.images.setObject(image, forKey: key)
        return image
    }

    private func render(avatar: UIImage?) -> UIImage {
        let size = bounds.size
        let link = UIColor(theme.link)
        let border = UIColor(theme.chipBorder)
        let placeholder = UIColor(theme.placeholder)
        let label = self.label
        let font = labelFont
        let hasAvatarSlot = avatarURL != nil
        return UIGraphicsImageRenderer(size: size).image { ctx in
            let hairline = 1 / UIScreen.main.scale
            let pill = UIBezierPath(
                roundedRect: CGRect(origin: .zero, size: size).insetBy(dx: hairline / 2, dy: hairline / 2),
                cornerRadius: min(20, size.height / 2)
            )
            pill.lineWidth = hairline
            border.setStroke()
            pill.stroke()
            var x: CGFloat = 6
            if hasAvatarSlot {
                let side = ChipAttachment.avatarSize
                let rect = CGRect(x: 2, y: (size.height - side) / 2, width: side, height: side)
                let circle = UIBezierPath(ovalIn: rect)
                placeholder.setFill()
                circle.fill()
                if let avatar {
                    ctx.cgContext.saveGState()
                    circle.addClip()
                    avatar.draw(in: rect)
                    ctx.cgContext.restoreGState()
                }
                x = rect.maxX + 4
            }
            let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: link]
            let textHeight = (label as NSString).size(withAttributes: attrs).height
            (label as NSString).draw(at: CGPoint(x: x, y: (size.height - textHeight) / 2), withAttributes: attrs)
        }
    }
}

extension BlockStyle {
    var nsAlignment: NSTextAlignment {
        if justify { return .justified }
        if align == .center { return .center }
        // HTML left and right are absolute; no alignment follows each paragraph's own direction
        if align == .end { return .right }
        if align == .left { return .left }
        return .natural
    }
}
