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
                Button(action: onAuthor) {
                    Text(card.author).scaledFont(nameSize, weight: .bold).foregroundStyle(Self.nameColor(theme)).lineLimit(2)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("card-author-name")
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
