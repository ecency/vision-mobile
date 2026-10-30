import EcencyCore
import SwiftUI

/// RN PostCard: header (avatar, author, community, age), image, title, summary, then
/// the actions row (vote + payout, votes, comments). Tapping the content opens the post.
struct PostCardView: View {
    let card: PostCard
    let width: CGFloat
    var onAppearWithContent: () -> Void = {}
    /// The card's post for the payout popover (the list resolves it).
    var payoutPost: (() -> Post?)? = nil
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var session: Observed<SessionState>
    @Environment(\.appTheme) private var theme
    @Environment(\.dynamicTypeSize) private var typeSize
    @ScaledMetric(relativeTo: .footnote) private var mutedBadge: CGFloat = 18
    /// RN useMutedReveal: revealed this session.
    @State private var revealed = false
    /// This card's zoom source (unique per card on screen, so the same post in two lists never shares one).
    @State private var heroID = UUID().uuidString

    private var dimmed: Bool { card.isMuted && !revealed && !app.env.mutedReveals.isRevealed(key: card.key) }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let label = card.crossPostLabel {
                Label(label, appIcon: .crossPost)
                    .scaledFont(11, weight: .bold, relativeTo: .caption)
                    .foregroundStyle(theme.secondary)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 6)
            }
            if let reblogger = card.rebloggedBy.first {
                Label(ReadingUiCopy.shared.rebloggedBy(username: reblogger), appIcon: .reblog)
                    .scaledFont(11, weight: .bold, relativeTo: .caption)
                    .foregroundStyle(theme.secondary)
                    .padding(.horizontal, 12)
                    .padding(.bottom, 6)
            }
            HStack(alignment: .top, spacing: 4) {
                AuthorLine(card: card, avatarSize: 44, nameSize: 16,
                           onAuthor: { app.openQuickProfile(card.author) },
                           onCategory: { app.open(.feed(tag: card.category, sort: RankedSort.trending.apiValue, title: CardText.category(card))) })
                if card.hasPoll { Image(appIcon: .chartBar).foregroundStyle(theme.icon).accessibilityLabel(UiCopy.shared.POLL) }
                MenuButton { app.openMenu(key: card.key) }
            }
                .padding(.horizontal, 12)
                .padding(.top, 4)
                .padding(.bottom, 12)

            Button {
                app.openPost(card.key, hero: heroID)
            } label: {
                VStack(alignment: .leading, spacing: 0) {
                    if dimmed {
                        // RN muted hint: "!" badge, the reason, "Tap to reveal"
                        Button {
                            app.env.mutedReveals.reveal(key: card.key)
                            revealed = true
                        } label: {
                            HStack(spacing: 6) {
                                // the badge grows with the text (a fixed 18 pt circle clipped its "!"); the reason says it all
                                Text("!").scaledFont(12, weight: .bold).foregroundStyle(.white)
                                    .frame(width: mutedBadge, height: mutedBadge).background(theme.danger, in: Circle())
                                    .accessibilityHidden(true)
                                Text("\(card.mutedText ?? "") \(MutedReveals.companion.REVEAL)")
                                    .scaledFont(13, relativeTo: .footnote).foregroundStyle(theme.secondary)
                            }
                            .padding(.horizontal, 8)
                            .padding(.bottom, 8)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("muted-reveal")
                    }
                    Group {
                        // the post zooms out of the card image (iOS 18; Motion.swift)
                        if card.isNsfw {
                            CardImageView(src: NsfwMode.companion.NSFW_IMAGE, width: width - 18).heroSource(heroID)
                        } else if let image = card.image {
                            GatedCardImage(card: card, src: image, width: width - 18, notifications: app.notifications).heroSource(heroID)
                        }
                        VStack(alignment: .leading, spacing: 0) {
                            if let featured = card.featuredText {
                                Text(featured).scaledFont(11, weight: .bold).foregroundStyle(theme.accentText).padding(.top, 10)
                                    .accessibilityIdentifier("card-featured")
                            }
                            Text(card.title)
                                .scaledFont(18, weight: .bold, relativeTo: .headline)
                                .foregroundStyle(theme.text)
                                .padding(.vertical, 12)
                                .fixedSize(horizontal: false, vertical: true)
                            if !card.summary.isEmpty {
                                Text(card.summary)
                                    .scaledFont(16)
                                    .lineSpacing(3)
                                    .foregroundStyle(theme.secondary)
                                    .lineLimit(5)
                                    .fixedSize(horizontal: false, vertical: true)
                                    // a five line preview on purpose (RN numberOfLines); the post has it all
                                    .accessibilityIdentifier("card-summary")
                            }
                        }
                        .padding(.horizontal, 8)
                    }
                    .opacity(dimmed ? 0.5 : 1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            // pressed: a soft tint grows from the touch point (Motion.swift)
            .buttonStyle(PressableStyle())
            .padding(.horizontal, 9)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(CardText.accessibilityLabel(card))
            .accessibilityHint(L.Native.Ios.Reading.opensPost)
            .accessibilityAddTraits(.isButton)
            // the card's other controls as actions on the one stop (the vote button stays its own stop: it opens
            // the weight sheet it owns)
            .accessibilityAction(named: L.Post.a11yCommentsHint) { app.openPost(card.key) }
            .accessibilityAction(named: L.Post.a11yVotersHint) { app.push(AppScreenVoters(author: card.key.author, permlink: card.key.permlink)) }
            .accessibilityAction(named: L.Post.a11yReblogsHint) { app.push(AppScreenReblogs(author: card.key.author, permlink: card.key.permlink)) }
            .accessibilityAction(named: UiCopy.shared.profileOf(username: card.author)) { app.openQuickProfile(card.author) }
            .accessibilityAction(named: L.Post.a11yTip) { app.sheet = session.state.isLoggedIn ? .tip(card.key) : .login }
            .accessibilityAction(named: L.Post.a11yPostOptions) { app.openMenu(key: card.key) }
            .accessibilityIdentifier("post-card")
            .onAppear(perform: onAppearWithContent)

            // one row; at large text sizes the controls wrap onto lines, so the card never grows wider than the
            // screen. Chosen by the text size (not ViewThatFits, which built every candidate per lazy row); AnyLayout
            // keeps the same views, so the vote button keeps its state when the size changes
            let wraps = AccessibleFooter.wraps(typeSize, width: width)
            (wraps ? AnyLayout(ChipFlow(spacing: 16)) : AnyLayout(HStackLayout(spacing: 16))) {
                vote
                voters
                if !wraps { Spacer() }
                reblogs
                comments
                TipButton(key: card.key)
            }
            .padding(16)
        }
        .padding(.top, 10)
        .background(theme.background)
    }

    private var vote: some View {
        VoteButton(key: card.key, votedByMe: card.votedByMe, downvotedByMe: card.downvotedByMe,
                   payoutText: card.payoutText, payoutDeclined: card.isPayoutDeclined, payoutPost: payoutPost)
    }

    private var voters: some View {
        Button { app.push(AppScreenVoters(author: card.key.author, permlink: card.key.permlink)) } label: {
            CountLabel(appIcon: .heart, count: card.votes)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(L.Post.a11yVotes(count: Int(card.votes)))
        .accessibilityHint(L.Post.a11yVotersHint)
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("card-voters")
    }

    private var reblogs: some View {
        Button { app.push(AppScreenReblogs(author: card.key.author, permlink: card.key.permlink)) } label: {
            CountLabel(appIcon: .reblog, count: card.reblogs)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L.Post.a11yReblogs(count: Int(card.reblogs)))
        .accessibilityIdentifier("card-reblogs")
    }

    private var comments: some View {
        Button { app.openPost(card.key) } label: { CountLabel(appIcon: .comment, count: card.comments) }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(L.Post.a11yComments(count: Int(card.comments)))
            .accessibilityAddTraits(.isButton)
    }
}

/// Card image: full card width, rounded 8, `$primaryLightBackground` until loaded. Starts at 16:9 like
/// RN and takes the bitmap's ratio once known, never taller than the screen.
struct CardImageView: View {
    let src: String
    let width: CGFloat
    @State private var ratio: CGFloat = 16.0 / 9.0
    @Environment(\.appTheme) private var theme

    static func height(width: CGFloat, ratio: CGFloat, screenHeight: CGFloat) -> CGFloat {
        let safe = ratio.isFinite && ratio > 0 ? ratio : 16.0 / 9.0
        return min(width / safe, screenHeight)
    }

    var body: some View {
        LoadedImageView(src: src, maxWidth: width, contentMode: .scaleAspectFill) { loaded in
            let r = loaded.naturalSize.width / max(1, loaded.naturalSize.height)
            if r.isFinite, r > 0, abs(r - ratio) > 0.01 { ratio = r }
        }
        .frame(width: width, height: Self.height(width: width, ratio: ratio, screenHeight: UIScreen.main.bounds.height))
        .background(theme.lightBackground)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

/// RN tip (gift) button: the tipping sheet for the post or comment (signed out: the login sheet). On the post screen
/// it carries the post's tip count ([tips], RN tipsQuery): the compact number beside the gift, none at zero, and the
/// count said as the button's value.
struct TipButton: View {
    let key: PostKey
    var tips: Observed<PostTipsState>? = nil
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var session: Observed<SessionState>
    @Environment(\.appTheme) private var theme

    var body: some View {
        if let tips { Counted(tips: tips) { button(count: $0) } } else { button(count: nil) }
    }

    private func button(count state: PostTipsState?) -> some View {
        Button { app.sheet = session.state.isLoggedIn ? .tip(key) : .login } label: {
            HStack(spacing: 4) {
                Image(appIcon: .gift).scaledFont(18).foregroundStyle(theme.icon)
                if let text = state?.countText {
                    Text(text).font(.caption).foregroundStyle(CountLabel.numberColor(theme)).fixedSize()
                        .accessibilityIdentifier("post-tip-count")
                }
            }
            .tapTarget()
        }
        .buttonStyle(.plain)
        .accessibilityLabel(UiCopy.shared.SEND_TIP)
        .accessibilityValue(state?.a11yCount ?? "")
        .largeContent(UiCopy.shared.SEND_TIP, appIcon: .gift)
        .accessibilityIdentifier("tip")
    }

    /// Redraws the button as the count arrives.
    private struct Counted<Content: View>: View {
        @ObservedObject var tips: Observed<PostTipsState>
        @ViewBuilder let content: (PostTipsState) -> Content
        var body: some View { content(tips.state) }
    }
}

/// When a card's footer (vote, counts, tip, reply) wraps onto lines instead of one row.
enum AccessibleFooter {
    static func wraps(_ typeSize: DynamicTypeSize, width: CGFloat) -> Bool {
        typeSize >= .xxxLarge || (typeSize >= .xLarge && width < 380)
    }
}
