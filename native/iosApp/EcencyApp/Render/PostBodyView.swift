import EcencyCore
import ImageIO
import Observation
import SwiftUI
import UIKit

/// What the host screen does on taps; mirrors PostActions on Android and the RN renderer's handler props.
struct PostActions {
    /// Avatar for a mention chip; nil draws the chip without one. Invalid account names get none.
    static let defaultAvatarURL: (String) -> String? = {
        PlainTextKt.isHiveAccountName(name: $0) ? "https://images.ecency.com/u/\($0)/avatar/128" : nil
    }

    var onLink: (LinkTarget) -> Void = { _ in }
    /// Image tap: the tapped URL and every body image in document order, for the gallery.
    var onImage: (_ url: String, _ all: [String]) -> Void = { _, _ in }
    /// Paragraph long-press with its plain text, for the copy sheet.
    var onParagraphLongPress: (_ plainText: String) -> Void = { _ in }
    var avatarURL: (String) -> String? = PostActions.defaultAvatarURL
    /// The "Show images" setting. When false each image is a same-size placeholder and its bytes are
    /// never requested until the user taps it; a revealed image stays revealed for the session.
    var showImages = true
}

/// Images revealed by a tap while "Show images" is off. Session scoped by URL, like RN's revealedImages.
@Observable
final class RevealedImages {
    static let shared = RevealedImages()
    private(set) var urls: Set<String> = []

    func isRevealed(_ url: String) -> Bool { urls.contains(url) }
    func reveal(_ url: String) { urls.insert(url) }
    func clear() { urls.removeAll() }
}

/// The body's gallery list, walked only when an image is tapped. The gallery pages through the images
/// the reader can see: hidden, unrevealed ones are left out (images inside links never enter it).
final class GalleryImages {
    let blocks: [any Block]
    private(set) lazy var all: [String] = collectImages(blocks)

    init(_ blocks: [any Block]) { self.blocks = blocks }

    func visible(showImages: Bool) -> [String] {
        ContentImages.shared.viewable(all: all, showImages: showImages) { KotlinBoolean(bool: RevealedImages.shared.isRevealed($0)) }
    }
}

extension PostActions {
    func openGallery(_ url: String, _ images: GalleryImages) { onImage(url, images.visible(showImages: showImages)) }
}

/// Rows are keyed by position and block identity, so state such as a measured image size or an open
/// spoiler does not carry over to a different block when the list changes.
struct BlockID: Hashable {
    let index: Int
    let block: ObjectIdentifier
}

func blockIDs(_ blocks: [any Block]) -> [BlockID] {
    blocks.enumerated().map { BlockID(index: $0.offset, block: ObjectIdentifier($0.element as AnyObject)) }
}

/// The direction a body reads in: its own, from its first strong letter (shared `isRtlContent`), not the app's. An
/// English post stays left to right in an Arabic UI and an Arabic post reads right to left in an English one.
func bodyDirection(_ blocks: [any Block]) -> LayoutDirection {
    PlainTextKt.isRtlContent(blocks) ? .rightToLeft : .leftToRight
}

/// Draws the shared block model natively. Top-level blocks sit in a LazyVStack so only what is on
/// screen is laid out, the same strategy as the Compose renderer's lazy items. The body takes its own direction
/// ([bodyDirection]); the caller's frame stays in the app's.
struct PostBodyView: View {
    let blocks: [any Block]
    let contentWidth: CGFloat
    var actions = PostActions()

    var body: some View {
        let images = GalleryImages(blocks)
        LazyVStack(alignment: .leading, spacing: 0) {
            ForEach(blockIDs(blocks), id: \.self) { id in
                BlockView(block: blocks[id.index], maxWidth: contentWidth, actions: actions, images: images)
            }
        }
        .frame(width: contentWidth, alignment: .leading)
        .environment(\.layoutDirection, bodyDirection(blocks))
    }
}

struct BlockView: View {
    let block: any Block
    let maxWidth: CGFloat
    let actions: PostActions
    let images: GalleryImages
    @Environment(\.postTheme) private var theme
    @Environment(\.speakEngine) private var speakEngine
    @Environment(\.layoutDirection) private var direction

    var body: some View {
        switch block {
        case let p as BlockParagraph:
            ParagraphView(inlines: p.inlines, style: p.style, size: theme.bodySize, bold: theme.bodyBold,
                          spacing: theme.paragraphSpacing, maxWidth: maxWidth, actions: actions)
        case let h as BlockHeading:
            let level = Int(h.level) - 1
            ParagraphView(inlines: h.inlines, style: h.style, size: theme.headingSizes[level], bold: true,
                          spacing: theme.headingSizes[level] * theme.headingMarginsEm[level], maxWidth: maxWidth, actions: actions)
        case let img as BlockImage:
            RemoteImageView(src: img.src, maxWidth: maxWidth, showImages: actions.showImages) {
                if let link = img.link { actions.onLink(link) } else { actions.openGallery(img.src, images) }
            }
            .id(img.src)
        case let grid as BlockImageGrid:
            ImageGridView(grid: grid, maxWidth: maxWidth, actions: actions, images: images)
        case let q as BlockQuote:
            // RN: 5 margin, 5 border in $darkIconColor, 5 padding; the user agent adds 1em top and
            // bottom and 30 on the right.
            VStack(alignment: .leading, spacing: 0) {
                ForEach(blockIDs(q.blocks), id: \.self) { id in let i = id.index
                    BlockView(block: q.blocks[i], maxWidth: maxWidth - 45, actions: actions, images: images)
                }
            }
            .padding(.leading, 10)
            .overlay(alignment: .leading) { Rectangle().fill(theme.muted).frame(width: 5) }
            .padding(.leading, 5)
            .padding(.trailing, 30)
            .padding(.vertical, 16)
        case let list as BlockListBlock:
            ListBlockView(list: list, maxWidth: maxWidth, actions: actions, images: images, depth: 0, trimTop: false, trimBottom: false)
        case let code as BlockCode:
            // code reads left to right in every body
            ScrollView(.horizontal, showsIndicators: false) {
                Text(code.text).font(.system(.subheadline, design: .monospaced)).foregroundStyle(theme.text).padding(8)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .environment(\.layoutDirection, .leftToRight)
            .background(theme.muted.opacity(0.35), in: RoundedRectangle(cornerRadius: 4))
            .padding(.vertical, 6)
        case is BlockRule:
            Rectangle().fill(theme.muted).frame(height: 1).padding(.vertical, 12)
        case let table as BlockTable:
            TableView(table: table, maxWidth: maxWidth, actions: actions, images: images)
        case let video as BlockVideo:
            // With "Show images" off the thumbnail is not fetched; the play button stays, as in RN.
            VideoThumbView(thumbnail: actions.showImages ? video.thumbnail : nil, maxWidth: maxWidth, threeSpeak: video.embedUrl.range(of: "3speak.tv", options: .caseInsensitive) != nil) {
                if let id = video.youtubeId {
                    actions.onLink(LinkTargetYoutube(videoId: id, startSeconds: video.startSeconds))
                } else {
                    actions.onLink(LinkTargetVideo(embedUrl: video.embedUrl))
                }
            }
        case let preview as BlockPostPreview:
            VStack(alignment: .leading, spacing: 2) {
                Text("@\(preview.author)").scaledFont(13, weight: .bold, relativeTo: .footnote).foregroundStyle(theme.link)
                Text(preview.permlink).scaledFont(14, relativeTo: .subheadline).foregroundStyle(theme.text).lineLimit(2)
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(theme.chipBorder))
            .contentShape(Rectangle())
            .onTapGesture { actions.onLink(LinkTargetPost(author: preview.author, permlink: preview.permlink)) }
            .padding(.vertical, 6)
        case let container as BlockContainer:
            VStack(alignment: container.style.horizontal(direction), spacing: 0) {
                ForEach(blockIDs(container.blocks), id: \.self) { id in let i = id.index
                    BlockView(block: inherit(container.blocks[i], container.style), maxWidth: maxWidth, actions: actions, images: images)
                }
            }
            .frame(maxWidth: .infinity, alignment: container.style.frameAlignment(direction))
        case let details as BlockDetails:
            DetailsView(details: details, maxWidth: maxWidth, actions: actions, images: images)
        case let speak as BlockSpeakAudio:
            SpeakAudioPlayerView(block: speak, maxWidth: maxWidth, engine: speakEngine)
        case is BlockAnchor:
            // a jump target (#2403): nothing to draw (the post screen scrolls to the block after it)
            EmptyView()
        default:
            EmptyView()
        }
    }
}

/// Paragraphs and headings go through `PostTextView` (TextKit), so chips show avatars, `text-justify`
/// justifies and a long-press hands the plain text to the copy sheet.
struct ParagraphView: View {
    let inlines: [any Inline]
    let style: BlockStyle
    let size: CGFloat
    let bold: Bool
    let spacing: CGFloat
    let maxWidth: CGFloat
    let actions: PostActions
    @Environment(\.layoutDirection) private var direction

    var body: some View {
        PostTextView(inlines: inlines, style: style, size: size, bold: bold, maxWidth: maxWidth, actions: actions)
            .frame(maxWidth: .infinity, alignment: style.frameAlignment(direction))
            .padding(.vertical, spacing)
    }
}

/// RN lists: 1em top and bottom margin (none at the edge of a parent item), a 30 wide marker box,
/// items 12 apart and no paragraph margins inside items. Bullets go disc, circle, square by depth.
struct ListBlockView: View {
    let list: BlockListBlock
    let maxWidth: CGFloat
    let actions: PostActions
    let images: GalleryImages
    let depth: Int
    let trimTop: Bool
    let trimBottom: Bool
    @Environment(\.postTheme) private var theme

    private static let bullets = ["\u{2022}", "\u{25E6}", "\u{25AA}"]
    private static let markerBox: CGFloat = 30
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(list.items.indices, id: \.self) { i in
                let item = list.items[i]
                HStack(alignment: .firstTextBaseline, spacing: 0) {
                    Text(list.ordered ? "\(Int(list.start) + i)." : ListBlockView.bullets[min(depth, 2)])
                        .font(.system(size: InlineTextBuilder.scaled(theme.bodySize, UIContentSizeCategory(typeSize))))
                        .foregroundStyle(theme.text)
                        .padding(.trailing, 6)
                        .frame(minWidth: ListBlockView.markerBox, alignment: .trailing)
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(item.indices, id: \.self) { j in
                            Group {
                                if let nested = item[j] as? BlockListBlock {
                                    ListBlockView(list: nested, maxWidth: maxWidth - ListBlockView.markerBox, actions: actions, images: images,
                                                  depth: depth + 1, trimTop: j == 0, trimBottom: j == item.count - 1)
                                } else {
                                    BlockView(block: item[j], maxWidth: maxWidth - ListBlockView.markerBox, actions: actions, images: images)
                                }
                            }
                            .environment(\.postTheme, noSpacing(theme))
                        }
                    }
                }
                .padding(.bottom, theme.listItemSpacing)
            }
        }
        .padding(.top, trimTop ? 0 : theme.bodySize)
        .padding(.bottom, trimBottom ? 0 : theme.bodySize)
    }
}

struct TableView: View {
    let table: BlockTable
    let maxWidth: CGFloat
    let actions: PostActions
    let images: GalleryImages
    @Environment(\.postTheme) private var theme

    var body: some View {
        let columns = Int(table.maxHeaderColumns)
        let minCol = maxWidth / 3 - 12
        let scroll = columns > 3
        let width = scroll ? minCol * CGFloat(columns) : maxWidth
        let grid = Grid(horizontalSpacing: 0, verticalSpacing: 0) {
            ForEach(table.rows.indices, id: \.self) { r in
                GridRow {
                    let cells = table.rows[r].cells
                    ForEach(cells.indices, id: \.self) { c in
                        let cell = cells[c]
                        VStack(spacing: 0) {
                            ForEach(cell.blocks.indices, id: \.self) { b in
                                BlockView(
                                    block: inherit(cell.blocks[b], BlockStyle(align: .center, justify: false, phishy: false)),
                                    maxWidth: width / CGFloat(max(1, cells.count)) - 20,
                                    actions: actions,
                                    images: images
                                )
                            }
                        }
                        .environment(\.postTheme, cellTheme(header: cell.header))
                        .padding(10)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(cell.header ? theme.muted : theme.tableRow)
                        .border(theme.tableBorder, width: 0.5)
                    }
                }
            }
        }
        .frame(width: width)
        Group {
            if scroll {
                ScrollView(.horizontal, showsIndicators: false) { grid }.accessibilityIdentifier("table-scroll")
            } else {
                grid
            }
        }
        .padding(.vertical, 6)
    }

    /// RN th: bold 14 on $darkIconColor.
    private func cellTheme(header: Bool) -> PostTheme {
        var t = noSpacing(theme)
        if header {
            t.bodySize = 14
            t.bodyBold = true
        }
        return t
    }
}

struct VideoThumbView: View {
    let thumbnail: String?
    let maxWidth: CGFloat
    let threeSpeak: Bool
    let onTap: () -> Void
    @Environment(\.postTheme) private var theme

    var body: some View {
        ZStack {
            Rectangle().fill(threeSpeak ? Color.clear : theme.muted)
            if let thumbnail {
                LoadedImageView(src: thumbnail, maxWidth: maxWidth, contentMode: threeSpeak ? .scaleAspectFit : .scaleAspectFill) { _ in }
                    .frame(width: maxWidth, height: maxWidth / (threeSpeak ? 4.0 / 3.0 : 16.0 / 9.0))
                    .clipped()
            }
            Circle().fill(theme.text).frame(width: 60, height: 60)
                .overlay(Image(appIcon: .play, filled: true).scaledFont(24).foregroundStyle(theme.background))
        }
        .frame(width: maxWidth, height: maxWidth / (threeSpeak ? 4.0 / 3.0 : 16.0 / 9.0))
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(ReadingUiCopy.shared.PLAY)
        .accessibilityAddTraits([.isButton, .startsMediaSession])
        .accessibilityAction { onTap() }
        .accessibilityIdentifier("video")
        .padding(.vertical, 6)
    }
}

struct ImageGridView: View {
    let grid: BlockImageGrid
    let maxWidth: CGFloat
    let actions: PostActions
    let images: GalleryImages
    @Environment(\.postTheme) private var theme

    var body: some View {
        let tile = (maxWidth - 2) / 2
        LazyVGrid(columns: [GridItem(.fixed(tile), spacing: 2), GridItem(.fixed(tile), spacing: 2)], spacing: 2) {
            ForEach(grid.images.indices, id: \.self) { i in
                let img = grid.images[i]
                if !actions.showImages && !RevealedImages.shared.isRevealed(img.src) {
                    HiddenImageView(width: tile, height: tile * 3 / 4) { RevealedImages.shared.reveal(img.src) }
                } else {
                    LoadedImageView(src: img.src, maxWidth: tile) { _ in }
                        .frame(width: tile, height: tile * 3 / 4)
                        .clipped()
                        .contentShape(Rectangle())
                        .onTapGesture { actions.openGallery(img.src, images) }
                        .accessibilityAddTraits([.isImage, .isButton])
                        .accessibilityAction { actions.openGallery(img.src, images) }
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .padding(.top, 4)
    }
}

struct DetailsView: View {
    let details: BlockDetails
    let maxWidth: CGFloat
    let actions: PostActions
    let images: GalleryImages
    @State private var open = false
    @Environment(\.postTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 4) {
                Image(appIcon: open ? .expand : .chevronRight).imageScale(.small).accessibilityHidden(true)
                // Summary as plain text: the whole row is the toggle, so its chips are not separate targets.
                Text(PlainTextKt.plainText(details.summary)).bold()
            }
            .foregroundStyle(theme.text)
            .padding(.vertical, 4)
            .frame(minHeight: minTapTarget)
            .contentShape(Rectangle())
            .onTapGesture { open.toggle() }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { open.toggle() }
            if open {
                ForEach(blockIDs(details.blocks), id: \.self) { id in let i = id.index
                    BlockView(block: details.blocks[i], maxWidth: maxWidth, actions: actions, images: images)
                }
            }
        }
        .padding(.vertical, 6)
    }
}

/// Image at its natural size, capped at the column width and never upscaled; full width at 16:9 until
/// the bitmap is known. Same rules as the RN AutoHeightImage and the Compose ImageView. Rounded 8,
/// `$primaryLightBackground` until loaded, GIF badge on animated images, and with "Show images" off a
/// same-size placeholder that loads only this image on tap.
struct RemoteImageView: View {
    let src: String
    let maxWidth: CGFloat
    let showImages: Bool
    let onTap: () -> Void
    @State private var natural: CGSize?
    @State private var animated = false
    @Environment(\.postTheme) private var theme

    var body: some View {
        let size: CGSize = {
            guard let natural, natural.width > 0 else { return CGSize(width: maxWidth, height: maxWidth * 9 / 16) }
            let w = min(maxWidth, natural.width)
            return CGSize(width: w, height: w * natural.height / natural.width)
        }()
        if !showImages && !RevealedImages.shared.isRevealed(src) {
            // No LoadedImageView exists yet, so nothing is requested until the user asks.
            HiddenImageView(width: size.width, height: size.height) { RevealedImages.shared.reveal(src) }
                .padding(.vertical, 4)
        } else {
            LoadedImageView(src: src, maxWidth: maxWidth) { loaded in
                natural = loaded.naturalSize
                animated = loaded.animated
            }
            .frame(width: size.width, height: size.height)
            .background(natural == nil ? theme.placeholder : .clear)
            .overlay(alignment: .bottomLeading) {
                if animated { GifBadge() }
            }
            .clipShape(RoundedRectangle(cornerRadius: theme.imageRadius))
            .contentShape(Rectangle())
            .onTapGesture(perform: onTap)
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits([.isImage, .isButton])
            .accessibilityIdentifier("image")
            .padding(.vertical, 4)
        }
    }
}

/// RN AutoHeightImage's badge on animated images.
struct GifBadge: View {
    var body: some View {
        Text("GIF")
            .scaledFont(9, weight: .bold, relativeTo: .caption2)
            .foregroundStyle(.white)
            .padding(.horizontal, 3)
            .padding(.vertical, 1)
            .background(Color.black.opacity(0.5), in: RoundedRectangle(cornerRadius: 3))
            .padding(6)
            .accessibilityIdentifier("gif")
    }
}

/// RN HiddenImagePlaceholder: same box as the image, dashed `$borderTopColor` outline, icon and label.
struct HiddenImageView: View {
    let width: CGFloat
    let height: CGFloat
    let onReveal: () -> Void
    @Environment(\.postTheme) private var theme

    var body: some View {
        let compact = height < 72
        Button(action: onReveal) {
            VStack(spacing: 6) {
                Image(appIcon: .image).font(.system(size: compact ? 18 : 24)).accessibilityHidden(true)
                if !compact {
                    // the box is the image's size: large text shrinks to fit it rather than spilling out
                    Text(UiCopy.shared.TAP_TO_LOAD_IMAGE).scaledFont(12, relativeTo: .caption).multilineTextAlignment(.center)
                        .minimumScaleFactor(0.5).padding(.horizontal, 8)
                }
            }
            .foregroundStyle(theme.chipBorder)
            .frame(width: width, height: height)
            .background(theme.placeholder, in: RoundedRectangle(cornerRadius: theme.imageRadius))
            .overlay(RoundedRectangle(cornerRadius: theme.imageRadius).stroke(theme.hiddenBorder, style: StrokeStyle(lineWidth: 1, dash: [3, 3])))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(UiCopy.shared.TAP_TO_LOAD_IMAGE)
        .accessibilityIdentifier("image-hidden")
    }
}

/// UIImageView host, so animated GIFs play (SwiftUI's Image shows only the first frame).
struct LoadedImageView: UIViewRepresentable {
    let src: String
    /// Widest the image is drawn, in points; it is decoded at this width times the screen scale.
    let maxWidth: CGFloat
    var contentMode: UIView.ContentMode = .scaleAspectFill
    let onLoad: (LoadedImage) -> Void

    func makeUIView(context: Context) -> UIImageView {
        let view = UIImageView()
        view.contentMode = contentMode
        view.clipsToBounds = true
        // photos keep their colours under Smart Invert
        view.accessibilityIgnoresInvertColors = true
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        view.setContentHuggingPriority(.defaultLow, for: .horizontal)
        view.setContentHuggingPriority(.defaultLow, for: .vertical)
        return view
    }

    func updateUIView(_ view: UIImageView, context: Context) {
        let coordinator = context.coordinator
        coordinator.onLoad = onLoad
        guard coordinator.src != src else { return }
        coordinator.src = src
        let src = self.src
        let maxPixel = maxWidth * UIScreen.main.scale
        // Rows scrolling back in are drawn from the decoded cache at once, without a flash.
        if let hit = ImageLoader.shared.cached(src, maxPixel: maxPixel) {
            view.image = hit.image
            DispatchQueue.main.async { coordinator.onLoad?(hit) }
            return
        }
        view.image = nil
        Task { @MainActor [weak view, weak coordinator] in
            guard let loaded = await ImageLoader.shared.load(src, maxPixel: maxPixel),
                  let view, let coordinator, coordinator.src == src else { return }
            view.image = loaded.image
            coordinator.onLoad?(loaded)
        }
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UIImageView, context: Context) -> CGSize? {
        CGSize(width: proposal.width ?? 0, height: proposal.height ?? 0)
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        var src: String?
        var onLoad: ((LoadedImage) -> Void)?
    }
}

private func noSpacing(_ theme: PostTheme) -> PostTheme {
    var t = theme
    t.paragraphSpacing = 0
    return t
}

/// Containers pass their alignment down to text children that do not set their own.
private func inherit(_ block: any Block, _ style: BlockStyle) -> any Block {
    let base = BlockStyle.companion.Default
    if let p = block as? BlockParagraph, p.style == base { return p.doCopy(inlines: p.inlines, style: style) }
    if let h = block as? BlockHeading, h.style == base { return h.doCopy(level: h.level, inlines: h.inlines, style: style) }
    return block
}

/// Every image in document order, for the gallery viewer (the shared core's walk, as on Android).
func collectImages(_ blocks: [any Block]) -> [String] {
    PlainTextKt.imageUrls(blocks)
}

extension BlockStyle {
    /// Resolved in the body's direction ([direction], the environment's): no alignment is the body's start, `End`
    /// (HTML right) the right edge and `Left` the left edge whatever the body's direction is.
    func frameAlignment(_ direction: LayoutDirection) -> Alignment {
        Alignment(horizontal: horizontal(direction), vertical: .center)
    }

    func horizontal(_ direction: LayoutDirection) -> HorizontalAlignment {
        let rtl = direction == .rightToLeft
        if align == .center { return .center }
        if align == .end { return rtl ? .leading : .trailing }
        if align == .left { return rtl ? .trailing : .leading }
        return .leading
    }
}
