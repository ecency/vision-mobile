import EcencyCore
import SwiftUI

// Where an image for an upload comes from, one look for every surface (README "Media picking"): the shared option
// sets (`MediaSourceMenus`) as rows with an SF Symbol tile, the surface's title and a short line under it. A compact
// sheet sized to its rows for the surfaces that ask first (profile images, chat attachment, video thumbnail), and
// the same options inline in the editor's uploads sheet. The library route stays the system photo picker (PHPicker:
// no library permission, the item provider the allow-list and the iCloud progress need).

extension MediaSourceOption: Identifiable {
    public var id: String { source.name }
}

/// One option: the icon tile, the title and what it does. VoiceOver reads the title, then the line as a hint.
struct MediaSourceRow: View {
    let option: MediaSourceOption
    let identifier: String
    let action: () -> Void
    @Environment(\.appTheme) private var theme
    @Environment(\.dynamicTypeSize) private var typeSize
    @ScaledMetric(relativeTo: .body) private var tile: CGFloat = 40

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                // at accessibility text sizes the words get the row's width (the icon only repeats the title)
                if typeSize < .stacksRows {
                    Image(appIcon: option.source.icon).scaledFont(18, weight: .semibold).foregroundStyle(theme.accentText)
                        .frame(width: tile, height: tile)
                        .background(theme.accent.opacity(theme.isDark ? 0.22 : 0.12), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                        .accessibilityHidden(true)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(option.title).font(.body.weight(.semibold)).foregroundStyle(theme.text)
                    Text(option.source.subtitle).font(.footnote).foregroundStyle(theme.secondary)
                }
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .frame(minHeight: minTapTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(option.title)
        .accessibilityHint(option.source.subtitle)
        .accessibilityIdentifier(identifier)
    }
}

/// The options as one grouped card of rows.
struct MediaSourceList: View {
    let options: [MediaSourceOption]
    let identifier: (MediaSource) -> String
    let onPick: (MediaSource) -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(options.enumerated()), id: \.element.id) { index, option in
                if index > 0 { Divider().overlay(theme.border).padding(.leading, 14) }
                MediaSourceRow(option: option, identifier: identifier(option.source)) { onPick(option.source) }
            }
        }
        .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .contain)
    }
}

/// The options inline (the editor's uploads sheet): a row of tiles that scrolls sideways, the rows at accessibility
/// text sizes (a tile's title would break mid-word there).
struct MediaSourceTiles: View {
    let options: [MediaSourceOption]
    let identifier: (MediaSource) -> String
    let onPick: (MediaSource) -> Void
    @Environment(\.appTheme) private var theme
    @Environment(\.dynamicTypeSize) private var typeSize
    @ScaledMetric(relativeTo: .footnote) private var tileWidth: CGFloat = 84
    @ScaledMetric(relativeTo: .footnote) private var iconSize: CGFloat = 44

    var body: some View {
        if typeSize >= .stacksRows {
            MediaSourceList(options: options, identifier: identifier, onPick: onPick)
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 10) {
                    ForEach(options) { option in
                        Button { onPick(option.source) } label: {
                            VStack(spacing: 6) {
                                Image(appIcon: option.source.icon).scaledFont(20, weight: .semibold).foregroundStyle(theme.accentText)
                                    .frame(width: iconSize, height: iconSize)
                                    .background(theme.accent.opacity(theme.isDark ? 0.22 : 0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                    .accessibilityHidden(true)
                                Text(option.title).font(.footnote.weight(.semibold)).foregroundStyle(theme.text)
                                    .multilineTextAlignment(.center).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                            }
                            .frame(width: tileWidth)
                            .padding(.vertical, 10)
                            .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(option.title)
                        .accessibilityHint(option.source.subtitle)
                        .accessibilityIdentifier(identifier(option.source))
                    }
                }
            }
        }
    }
}

/// The chooser sheet: an optional title, the rows and Cancel, as tall as its content (scrolling at the largest text
/// sizes, where it may need more than the screen).
struct MediaSourceSheet: View {
    let title: String?
    let options: [MediaSourceOption]
    let identifier: (MediaSource) -> String
    let onPick: (MediaSource) -> Void
    let onCancel: () -> Void
    /// The content's measured height, kept by the presenting view across presentations: the sheet opens at it, so it
    /// never starts at a guess and jumps. Before the first measurement the detent is an estimate from the rows and the
    /// content stays hidden until it is measured.
    @Binding var measured: CGFloat?
    @Environment(\.appTheme) private var theme
    @ScaledMetric(relativeTo: .body) private var rowEstimate: CGFloat = 62
    @ScaledMetric(relativeTo: .headline) private var titleEstimate: CGFloat = 22

    /// Paddings, the title, the rows and Cancel, as laid out below.
    private var estimate: CGFloat {
        let hasTitle = !(title ?? "").isEmpty
        return 28 + (hasTitle ? titleEstimate + 12 : 0) + CGFloat(options.count) * rowEstimate + 12 + minTapTarget + 12
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                if let title, !title.isEmpty {
                    Text(title).font(.headline).foregroundStyle(theme.text).multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity).accessibilityAddTraits(.isHeader)
                }
                MediaSourceList(options: options, identifier: identifier, onPick: onPick)
                Button(action: onCancel) {
                    Text(MediaPickCopy.shared.CANCEL).font(.body.weight(.semibold)).foregroundStyle(theme.accentText)
                        .frame(maxWidth: .infinity, minHeight: minTapTarget)
                        .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("media-source-cancel")
            }
            .padding(.horizontal, 16).padding(.top, 28).padding(.bottom, 12)
            .background(GeometryReader { Color.clear.preference(key: MediaSourceHeightKey.self, value: $0.size.height) })
            .opacity(measured == nil ? 0 : 1)
        }
        .scrollBounceBehavior(.basedOnSize)
        .onPreferenceChange(MediaSourceHeightKey.self) { if $0 > 0, abs($0 - (measured ?? 0)) > 0.5 { measured = $0 } }
        .presentationDetents([.height(measured ?? estimate)])
        .presentationDragIndicator(.visible)
        .presentationBackground(theme.background)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("media-source-sheet")
    }
}

private struct MediaSourceHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

/// Presents the chooser while [item] is set; the picked source runs once the sheet is gone, so the picker, camera or
/// importer it opens is never presented over a sheet that is still leaving.
private struct MediaSourceChooser<Item>: ViewModifier {
    @Binding var item: Item?
    let title: (Item) -> String?
    let options: (Item) -> [MediaSourceOption]
    let identifier: (MediaSource) -> String
    let onPick: (Item, MediaSource) -> Void
    @Environment(\.appTheme) private var theme
    @State private var pending: (Item, MediaSource)?
    /// The item shown, kept while the sheet slides away (the binding is cleared first), so it never goes blank.
    @State private var shown: Item?
    /// The sheet's measured height, kept across presentations (see `MediaSourceSheet.measured`).
    @State private var measured: CGFloat?

    func body(content: Content) -> some View {
        content.sheet(
            isPresented: Binding(get: { item != nil }, set: { if !$0 { item = nil } }),
            onDismiss: {
                shown = nil
                guard let (chosen, source) = pending else { return }
                pending = nil
                onPick(chosen, source)
            }
        ) {
            if let current = item ?? shown {
                MediaSourceSheet(title: title(current), options: options(current), identifier: identifier,
                                 onPick: { source in pending = (current, source); item = nil },
                                 onCancel: { pending = nil; item = nil }, measured: $measured)
                    .environment(\.appTheme, theme)
                    // a pick left from an earlier presentation never runs for this one
                    .onAppear { shown = current; pending = nil }
            }
        }
    }
}

extension View {
    /// The image source chooser for [item] (a field, a request), with the surface's options and test ids.
    func mediaSourceChooser<Item>(
        item: Binding<Item?>, title: @escaping (Item) -> String? = { _ in nil }, options: @escaping (Item) -> [MediaSourceOption],
        identifier: @escaping (MediaSource) -> String, onPick: @escaping (Item, MediaSource) -> Void
    ) -> some View {
        modifier(MediaSourceChooser(item: item, title: title, options: options, identifier: identifier, onPick: onPick))
    }

    /// The chooser while [isPresented] is true.
    func mediaSourceChooser(
        isPresented: Binding<Bool>, title: String? = nil, options: [MediaSourceOption],
        identifier: @escaping (MediaSource) -> String, onPick: @escaping (MediaSource) -> Void
    ) -> some View {
        mediaSourceChooser(
            item: Binding<Bool?>(get: { isPresented.wrappedValue ? true : nil }, set: { isPresented.wrappedValue = $0 != nil }),
            title: { _ in title }, options: { _ in options }, identifier: identifier, onPick: { _, source in onPick(source) }
        )
    }
}
