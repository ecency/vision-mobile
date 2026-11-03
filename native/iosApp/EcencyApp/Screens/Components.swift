import EcencyCore
import SwiftUI

/// Round avatar. No reputation badge (a deliberate change from RN): reputation shows only in the quick profile.
struct AvatarView: View {
    let url: String
    let size: CGFloat
    @Environment(\.appTheme) private var theme

    var body: some View {
        ZStack {
            Circle().fill(theme.lightBackground)
            if !url.isEmpty {
                LoadedImageView(src: url, maxWidth: size) { _ in }
                    .frame(width: size, height: size)
                    .clipShape(Circle())
            }
        }
        .frame(width: size, height: size)
        .overlay(Circle().stroke(theme.border, lineWidth: 1))
        .keepsColorsInverted()
        .accessibilityHidden(true)
    }
}

/// An asset's logo (RN AssetIcon): contained in a square box, no disc or crop (logos have their own shapes).
struct AssetIconView: View {
    let url: String?
    let size: CGFloat

    var body: some View {
        Group {
            if let url, !url.isEmpty {
                LoadedImageView(src: url, maxWidth: size, contentMode: .scaleAspectFit) { _ in }
            } else {
                Color.clear
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

/// Author line used by cards, the post header and comments: name, community or tag and age.
struct AuthorLine: View {
    /// The author's name color (AccessibilityTests checks it against the card background).
    static func nameColor(_ theme: AppTheme) -> Color { theme.text }
    let card: PostCard
    let avatarSize: CGFloat
    let nameSize: CGFloat
    var showCategory = true
    var onAuthor: () -> Void = {}
    var onCategory: () -> Void = {}
    @Environment(\.appTheme) private var theme

    var body: some View {
        HStack(spacing: 10) {
            Button(action: onAuthor) {
                AvatarView(url: card.avatarUrl, size: avatarSize)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(UiCopy.shared.profileOf(username: card.author))
            VStack(alignment: .leading, spacing: 2) {
                // RN postHeaderDescription: the Pro badge after the name; the name button says it ("author, Ecency Pro member")
                ProMemberReader(username: card.author) { pro in
                    HStack(spacing: 4) {
                        Button(action: onAuthor) {
                            Text(card.author).scaledFont(nameSize, weight: .bold).foregroundStyle(Self.nameColor(theme)).lineLimit(2)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(proLabel(card.author, pro))
                        .accessibilityIdentifier("card-author-name")
                        ProBadge(username: card.author).accessibilityHidden(true)
                    }
                }
                // category and age on one line while they fit, else the category takes its own line(s)
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 4) {
                        category(lineLimit: 1).fixedSize()
                        ageAndBadges
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        category(lineLimit: 3)
                        HStack(spacing: 4) { ageAndBadges }
                    }
                }
            }
            Spacer(minLength: 0)
            if card.isPinned {
                Image(appIcon: .pin, filled: true).rotationEffect(.degrees(45)).foregroundStyle(theme.dangerText)
                    .accessibilityLabel(L.Native.Ios.Card.pinned)
            }
        }
        .accessibilityElement(children: .combine)
        // the combined element opens the author; the community or tag is its own action
        .accessibilityActions {
            if showCategory {
                Button(L.Native.Ios.Card.inCategory(category: CardText.category(card)), action: onCategory)
            }
        }
    }

    @ViewBuilder private func category(lineLimit: Int) -> some View {
        if showCategory {
            Button(action: onCategory) {
                Text(L.Native.Ios.Card.inCategory(category: CardText.category(card)))
                    .scaledFont(14, weight: .semibold, relativeTo: .subheadline)
                    .foregroundStyle(theme.secondary)
                    .lineLimit(lineLimit)
                    .multilineTextAlignment(.leading)
            }
            .buttonStyle(.plain)
        }
    }

    @ViewBuilder private var ageAndBadges: some View {
        Text(CardText.age(card))
            .scaledFont(14, relativeTo: .subheadline)
            .foregroundStyle(theme.secondary)
            .fixedSize()
        // RN postHeaderDescription: the source badge, then the AI disclosure, after the date
        if card.isFromEcency { EcencySourceBadge().padding(.leading, 2) } // RN marginLeft 6
        if card.aiDisclosed { AiDisclosedBadge() }
    }
}

/// Card text helpers, pure so they are unit tested.
enum CardText {
    static func category(_ card: PostCard) -> String {
        if let title = card.communityTitle, !title.isEmpty { return title }
        return "#\(card.category)"
    }

    static func age(_ card: PostCard, now: Date = Date()) -> String {
        TimeAgo.shared.short(epochMillis: card.createdEpochMillis, nowMillis: Int64(now.timeIntervalSince1970 * 1000))
    }

    /// VoiceOver label for a whole feed card.
    static func accessibilityLabel(_ card: PostCard) -> String {
        var parts = [card.title, L.Native.Ios.Card.byAuthor(username: card.author), category(card), age(card)]
        parts.append(L.Post.a11yVotes(count: Int(card.votes)))
        parts.append(L.Post.a11yComments(count: Int(card.comments)))
        parts.append(card.isPayoutDeclined ? L.Native.Ios.Card.payoutDeclined(payout: card.payoutText) : L.Native.Ios.Card.payout(payout: card.payoutText))
        if card.isPinned { parts.insert(L.Native.Ios.Card.pinned, at: 0) }
        if let reblog = card.rebloggedBy.first { parts.insert(L.Native.Ios.Card.rebloggedBy(username: reblog), at: 0) }
        return parts.joined(separator: ", ")
    }
}

/// Icon plus count, the RN TextWithIcon used in card footers.
struct CountLabel: View {
    let appIcon: AppIcon
    let count: Int32
    /// The count's color (AccessibilityTests checks it against the card background).
    static func numberColor(_ theme: AppTheme) -> Color { theme.secondary }
    @Environment(\.appTheme) private var theme
    @Environment(\.motion) private var motion

    var body: some View {
        HStack(spacing: 4) {
            Image(appIcon: appIcon).font(.body).foregroundStyle(theme.icon)
            // the number rolls to its new value (a crossfade under reduce motion)
            Text(Fmt.int(count)).font(.caption).foregroundStyle(Self.numberColor(theme))
                .fixedSize()
                .contentTransition(motion.pops ? .numericText(value: Double(count)) : .opacity)
                .animation(motion.fade(), value: count)
                .accessibilityIdentifier("count-text")
        }
        .frame(minHeight: minTapTarget)
        .contentShape(Rectangle())
    }
}

/// Full-width message with an optional retry, for screens whose content failed to load.
struct ErrorStateView: View {
    let error: AppError
    /// README "Esie": sad Esie above a failed load; nil for rows under content (a load-more error, a form)
    var esie: EsiePlacement? = .error
    /// Announced once per failure even when the view is a lazy row that scrolls out and back (`.announces` sticky);
    /// by default for the row form (no Esie), which sits under lists.
    var sticky: Bool? = nil
    let retry: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        VStack(spacing: 12) {
            if let esie {
                EsieView(esie)
            } else {
                Image(appIcon: error.kind == .network ? .offline : .warning)
                    .scaledFont(32, relativeTo: .title).foregroundStyle(theme.icon).accessibilityHidden(true)
            }
            Text(error.message).multilineTextAlignment(.center).foregroundStyle(theme.secondary)
                .announces(error.message, attempt: ObjectAttempt(error), sticky: sticky ?? (esie == nil))
            if error.retryable {
                Button(UiCopy.shared.RETRY, action: retry).buttonStyle(.prominentFill).accessibilityIdentifier("retry")
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity)
        // as a List row: on the list's background, no separator under it
        .listStateRow()
    }
}

/// Inline error row with retry, under a list.
struct InlineErrorRow: View {
    let error: AppError
    let retry: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        AdaptiveStack {
            Text(error.message).scaledFont(13, relativeTo: .footnote).foregroundStyle(theme.secondary)
                .announces(error.message, attempt: ObjectAttempt(error), sticky: true)
            Spacer(minLength: 0)
            if error.retryable { Button(UiCopy.shared.RETRY, action: retry).frame(minHeight: minTapTarget).accessibilityIdentifier("retry") }
        }
        .padding(16)
    }
}

/// RN FabButton's look, shared by the compose pen and the lists' scroll to top.
enum FabMetrics {
    /// The circle: fixed, a text size never grows it (the glyph inside follows Dynamic Type up to xxxLarge).
    static let size: CGFloat = 56
    /// From the screen edges.
    static let margin: CGFloat = 16
    static let icon: CGFloat = 22
}

/// A floating round button in the FAB's look ([FabMetrics]): an accent circle with a soft shadow and a white glyph,
/// its margin included; it sinks while pressed (Motion.swift). The long press shows the large content viewer.
struct FabButton: View {
    let appIcon: AppIcon
    let label: String
    let id: String
    let action: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        Button(action: action) {
            Image(appIcon: appIcon).scaledFont(FabMetrics.icon, weight: .semibold).foregroundStyle(.white)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .frame(width: FabMetrics.size, height: FabMetrics.size)
                .background(Circle().fill(theme.accent).shadow(color: .black.opacity(0.3), radius: 8, y: 4))
        }
        .buttonStyle(PressScaleStyle(scale: 0.92))
        .padding(FabMetrics.margin)
        .accessibilityLabel(label)
        .largeContent(label, appIcon: appIcon)
        .accessibilityIdentifier(id)
    }
}

// MARK: Themed lists

/// Lists and forms on the theme background. Without it the system list colours show: black rows and black
/// grouped backgrounds in dark mode, grey in light.
private struct ThemedListModifier: ViewModifier {
    @Environment(\.appTheme) private var theme

    func body(content: Content) -> some View {
        content.scrollContentBackground(.hidden).background(theme.background)
    }
}

/// A plain list row on the theme background (the system row colour is black in dark mode).
private struct ThemedRowModifier: ViewModifier {
    @Environment(\.appTheme) private var theme

    func body(content: Content) -> some View { content.listRowBackground(theme.background) }
}

/// A grouped list (form) row on the theme's card colour.
private struct ThemedCardRowModifier: ViewModifier {
    @Environment(\.appTheme) private var theme

    func body(content: Content) -> some View { content.listRowBackground(theme.lightBackground) }
}

extension View {
    /// On every List and Form: the theme background behind and between the rows.
    func themedList() -> some View { modifier(ThemedListModifier()) }
    /// Plain list rows (or a ForEach of them) that set no row colour of their own.
    func themedRow() -> some View { modifier(ThemedRowModifier()) }
    /// Grouped list rows, or a Section of them: the card colour.
    func themedCardRow() -> some View { modifier(ThemedCardRowModifier()) }
    /// A loading, empty or error state shown as a List row: full width, no row colour and no separator under it,
    /// so it sits on the list's theme background like the space around it. No effect outside a List.
    func listStateRow() -> some View {
        frame(maxWidth: .infinity).listRowBackground(Color.clear).listRowSeparator(.hidden)
    }
}

// MARK: Themed sheets

/// The presented content on the theme: the sheet (or cover) background, also under its grabber, its corners and the
/// keyboard area, with the presenter's theme. The system sheet colour is black or dark grey in dark mode. Every sheet
/// and full screen cover goes through `themedSheet` / `themedFullScreenCover` (ThemedBackgroundLintTests forbids
/// the raw modifiers).
private struct ThemedPresentation<Item: Identifiable, Presented: View>: ViewModifier {
    let isPresented: Binding<Bool>?
    let item: Binding<Item?>?
    let fullScreen: Bool
    let onDismiss: (() -> Void)?
    let presented: (Item?) -> Presented
    @Environment(\.appTheme) private var theme

    private func themed(_ item: Item?) -> some View {
        presented(item).environment(\.appTheme, theme).presentationBackground(theme.background)
    }

    func body(content: Content) -> some View {
        if let isPresented {
            if fullScreen {
                content.fullScreenCover(isPresented: isPresented, onDismiss: onDismiss) { themed(nil) }
            } else {
                content.sheet(isPresented: isPresented, onDismiss: onDismiss) { themed(nil) }
            }
        } else if let item {
            if fullScreen {
                content.fullScreenCover(item: item, onDismiss: onDismiss) { themed($0) }
            } else {
                content.sheet(item: item, onDismiss: onDismiss) { themed($0) }
            }
        }
    }
}

/// `isPresented` presentations carry no item.
private struct NoItem: Identifiable { var id: Int { 0 } }

extension View {
    /// `.sheet(isPresented:)` on the theme background.
    func themedSheet<C: View>(isPresented: Binding<Bool>, onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping () -> C) -> some View {
        modifier(ThemedPresentation<NoItem, C>(isPresented: isPresented, item: nil, fullScreen: false, onDismiss: onDismiss) { _ in content() })
    }

    /// `.sheet(item:)` on the theme background.
    func themedSheet<Item: Identifiable, C: View>(item: Binding<Item?>, onDismiss: (() -> Void)? = nil,
                                                  @ViewBuilder content: @escaping (Item) -> C) -> some View {
        modifier(ThemedPresentation<Item, C?>(isPresented: nil, item: item, fullScreen: false, onDismiss: onDismiss) { $0.map(content) })
    }

    /// `.fullScreenCover(isPresented:)` on the theme background.
    func themedFullScreenCover<C: View>(isPresented: Binding<Bool>, onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping () -> C) -> some View {
        modifier(ThemedPresentation<NoItem, C>(isPresented: isPresented, item: nil, fullScreen: true, onDismiss: onDismiss) { _ in content() })
    }

    /// `.fullScreenCover(item:)` on the theme background.
    func themedFullScreenCover<Item: Identifiable, C: View>(item: Binding<Item?>, onDismiss: (() -> Void)? = nil,
                                                            @ViewBuilder content: @escaping (Item) -> C) -> some View {
        modifier(ThemedPresentation<Item, C?>(isPresented: nil, item: item, fullScreen: true, onDismiss: onDismiss) { $0.map(content) })
    }
}

/// A Form whose rows are theme cards on the theme background (the system grouped colours are black in dark mode).
struct ThemedForm<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        Form { Group { content }.themedCardRow() }.themedList()
    }
}

/// The spinner of a list still loading its first page, as a List row (`listStateRow`).
struct ListLoadingRow: View {
    var topPadding: CGFloat = 48

    var body: some View {
        ProgressView().frame(maxWidth: .infinity).padding(.top, topPadding).listStateRow()
    }
}

