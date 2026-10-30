import EcencyCore
import SwiftUI

/// What the vote button draws, derived from the card and the shared VoteState. Pure, unit tested.
enum VoteButtonState: Equatable {
    case idle
    case upvoted
    case downvoted
    case inFlight
    /// The last attempt failed and was rolled back; tap offers retry.
    case failed(message: String, retryable: Bool)

    static func of(votedByMe: Bool, downvotedByMe: Bool, status: VoteStatus?) -> VoteButtonState {
        if let status {
            if status.inFlight { return .inFlight }
            if let error = status.error { return .failed(message: error.message, retryable: error.retryable) }
        }
        if votedByMe { return .upvoted }
        if downvotedByMe { return .downvoted }
        return .idle
    }

    var accessibilityValue: String {
        switch self {
        case .idle: return L.Native.Ios.Vote.notVoted
        case .upvoted: return UiCopy.shared.UPVOTED
        case .downvoted: return UiCopy.shared.DOWNVOTED
        case .inFlight: return L.Native.Ios.Vote.voting
        case let .failed(message, _): return L.Native.Ios.Vote.failedWith(message: message)
        }
    }
}

/// Vote weights in basis points for the shared VoteModel. Pure, unit tested.
enum VoteWeight {
    /// `percent` 1...100, `down` for a downvote.
    /// The sheet opens in downvote mode for a post the reader already downvoted (RN and Android).
    static func startsDown(votedByMe: Bool, downvotedByMe: Bool) -> Bool { downvotedByMe && !votedByMe }

    static func basisPoints(percent: Double, down: Bool) -> Int32 {
        let clamped = min(100, max(1, percent.rounded()))
        return Int32(clamped * 100) * (down ? -1 : 1)
    }
}

/// RN UpvoteButton: the circled chevron plus payout. Tap opens the weight sheet (RN's upvote popover);
/// signed out it shows the sign-in prompt; after a failure it offers retry.
struct VoteButton: View {
    let key: PostKey
    let votedByMe: Bool
    let downvotedByMe: Bool
    let payoutText: String
    let payoutDeclined: Bool
    var iconSize: CGFloat = 24
    /// How much the text size grew the body style (1 at the default size).
    @ScaledMetric(relativeTo: .body) private var iconScale: CGFloat = 1
    /// RN upvoteButton: the payout amount opens the payout breakdown of this post (no login needed).
    var payoutPost: (() -> Post?)? = nil
    @State private var payoutDetails: PayoutPostRef?
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var votes: Observed<VoteState>
    @State private var showWeights = false
    @State private var showFailure = false
    @Environment(\.appTheme) private var theme
    @Environment(\.motion) private var motion
    /// Each time the vote lands (filled), the symbol bounces once.
    @State private var bounces = 0
    @Environment(\.dynamicTypeSize) private var typeSize

    private var voteSheetDetents: Set<PresentationDetent> {
        typeSize > .xxxLarge ? [.large] : typeSize > .large ? [.medium, .large] : [.height(340), .large]
    }

    private var state: VoteButtonState {
        VoteButtonState.of(votedByMe: votedByMe, downvotedByMe: downvotedByMe,
                           status: votes.state.status(author: key.author, permlink: key.permlink))
    }

    var body: some View {
        HStack(spacing: 8) {
            Button(action: tap) { icon.frame(width: iconSize * min(iconScale, 1.6) + 8, height: iconSize * min(iconScale, 1.6) + 8).tapTarget() }
                .buttonStyle(.plain)
                .accessibilityLabel(UiCopy.shared.UPVOTE)
                .accessibilityValue(state.accessibilityValue)
                .largeContent(UiCopy.shared.UPVOTE, appIcon: .voteUp)
                .accessibilityIdentifier("vote-button")
            Text(payoutText)
                .scaledFont(10, relativeTo: .caption2)
                // an amount is never broken over two lines (largest text sizes)
                .fixedSize()
                .strikethrough(payoutDeclined)
                .foregroundStyle(theme.secondary)
                .frame(minHeight: minTapTarget)
                .accessibilityLabel(payoutDeclined ? L.Native.Ios.Vote.payoutDeclined(payout: payoutText) : L.Native.Ios.Vote.payout(payout: payoutText))
                .contentShape(Rectangle())
                .onTapGesture { if let p = payoutPost?() { payoutDetails = PayoutPostRef(post: p) } }
                .accessibilityAddTraits(payoutPost == nil ? [] : .isButton)
                .accessibilityHint(payoutPost == nil ? "" : PayoutCopy.shared.DETAILS)
                .accessibilityIdentifier("payout")
                .popover(item: $payoutDetails) { ref in
                    PayoutPopoverView(post: ref.post, env: app.env).presentationCompactAdaptation(.popover)
                }
        }
        .sheet(isPresented: $showWeights) {
            // RN keeps the last weight for waves apart from posts and comments (waveUpvotePercent)
            let wave = app.env.waves.isWave(key: key)
            VoteWeightSheet(isVoted: votedByMe || downvotedByMe, initialPercent: wave ? app.env.waves.voteWeightPercent : app.voteWeightPercent, startsDown: VoteWeight.startsDown(votedByMe: votedByMe, downvotedByMe: downvotedByMe)) { percent, down in
                showWeights = false
                if let percent {
                    Haptics.play(.vote)
                    if wave { app.env.waves.voteWeightPercent = percent } else { app.voteWeightPercent = percent }
                    app.env.votes.vote(author: key.author, permlink: key.permlink, weight: VoteWeight.basisPoints(percent: percent, down: down))
                } else {
                    app.env.votes.vote(author: key.author, permlink: key.permlink, weight: 0)
                }
            }
            .environmentObject(app)
            // RN's compact popover height; large text gets the larger detent and the sheet scrolls
            .presentationDetents(voteSheetDetents)
        }
        .alert(L.Native.Ios.Vote.failed, isPresented: $showFailure) {
            if case let .failed(_, retryable) = state, retryable {
                Button(UiCopy.shared.RETRY) { app.env.votes.retry(author: key.author, permlink: key.permlink) }
            }
            Button(UiCopy.shared.DISMISS, role: .cancel) { app.env.votes.dismissError(author: key.author, permlink: key.permlink) }
        } message: {
            if case let .failed(message, _) = state { Text(message) }
        }
    }

    /// The symbol for each state; one image whose name changes, so the change can animate (Motion.swift).
    static func symbol(_ state: VoteButtonState) -> String {
        switch state {
        case .failed: return AppIcon.error.symbol()
        case .upvoted: return AppIcon.voteUp.symbol(filled: true)
        case .downvoted: return AppIcon.voteDown.symbol(filled: true)
        case .idle, .inFlight: return AppIcon.voteUp.symbol()
        }
    }

    @ViewBuilder private var icon: some View {
        ZStack {
            if state == .inFlight {
                ProgressView().controlSize(.small).transition(motion.swap)
            } else {
                Image(systemName: Self.symbol(state))
                    // grows with the body text style, at most 1.6 times (the long press shows the large content viewer)
                    .font(.system(size: iconSize * min(iconScale, 1.6), weight: state == .idle ? .light : .regular))
                    .foregroundStyle(state == .downvoted || isFailed ? theme.dangerText : theme.accentText)
                    // SF Symbols replace their glyph and bounce on a landed vote; other images just crossfade
                    .contentTransition(motion.isOff ? .identity : .symbolEffect(.replace))
                    .symbolEffect(.bounce, options: .nonRepeating, value: bounces)
                    .transition(motion.swap)
            }
        }
        .animation(motion.pop, value: state)
        .onChange(of: state) { old, new in
            if motion.pops, new == .upvoted || new == .downvoted, old != new { bounces += 1 }
        }
    }

    private var isFailed: Bool { if case .failed = state { return true } else { return false } }

    private func tap() {
        switch state {
        case .inFlight: return
        case .failed: showFailure = true
        default:
            if app.requireSignIn(to: .vote) { showWeights = true }
        }
    }
}

/// RN upvote popover as a sheet: weight slider, the vote's value ("$x"), Upvote; Downvote switches to the red
/// downvote mode on the first tap and votes on the second (RN has no other confirmation); "Remove vote" when voted.
struct VoteWeightSheet: View {
    let isVoted: Bool
    let onDone: (_ percent: Double?, _ down: Bool) -> Void
    @EnvironmentObject private var app: AppContainer
    @State private var percent: Double
    @State private var down = false
    @State private var preview: VotePreview?
    @Environment(\.appTheme) private var theme

    /// RN and Android: a post the reader already downvoted opens in downvote mode.
    init(isVoted: Bool, initialPercent: Double, startsDown: Bool = false, onDone: @escaping (Double?, Bool) -> Void) {
        self.isVoted = isVoted
        _down = State(initialValue: startsDown)
        self.onDone = onDone
        _percent = State(initialValue: min(100, max(1, initialPercent)))
    }

    /// RN downvote colour.
    static let downColor = Color(red: 0xEC / 255.0, green: 0x8B / 255.0, blue: 0x88 / 255.0)

    var body: some View {
        ScrollView {
        VStack(spacing: 16) {
            AdaptiveStack {
                Text(down ? ReadingUiCopy.shared.DOWNVOTE : UiCopy.shared.UPVOTE).font(.headline).foregroundStyle(down ? theme.dangerText : theme.accentText)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 0)
                if let preview {
                    Text(VoteSheetText.amount(preview.text(weightBasisPoints: VoteWeight.basisPoints(percent: percent, down: false)), down: down))
                        .monospacedDigit().foregroundStyle(theme.secondary)
                        .accessibilityIdentifier("vote-value")
                }
            }
            HStack {
                Slider(value: $percent, in: 1...100, step: 1)
                    .tint(down ? Self.downColor : theme.accent)
                    .accessibilityLabel(ReadingUiCopy.shared.VOTE_WEIGHT)
                    .accessibilityValue(L.Native.Ios.Vote.percent(percent: Fmt.int(Int(percent))))
                    .accessibilityIdentifier("vote-weight")
                Text(Fmt.percent(down ? -Int(percent) : Int(percent))).monospacedDigit().fixedSize()
                    .frame(minWidth: 56, alignment: .trailing).accessibilityHidden(true)
            }
            AdaptiveStack(spacing: 12) {
                Button {
                    if down { down = false } else { onDone(percent, false) }
                } label: {
                    Label(down ? UiCopy.shared.UPVOTE : L.Native.Ios.Vote.upvotePercent(percent: Fmt.int(Int(percent))), appIcon: .voteUp).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(theme.accent)
                .accessibilityIdentifier("vote-confirm")
                Button {
                    if down { onDone(percent, true) } else { down = true }
                } label: {
                    Label(down ? ReadingUiCopy.shared.downvotePercent(percent: Int32(percent)) : ReadingUiCopy.shared.DOWNVOTE, appIcon: .voteDown).frame(maxWidth: .infinity)
                }
                // the downvote's red, as text: the AA red on the gray fill
                .buttonStyle(.subtleFill(\.dangerText))
                .accessibilityIdentifier("vote-down")
            }
            if isVoted {
                Button(ReadingUiCopy.shared.REMOVE_VOTE, role: .destructive) { onDone(nil, false) }
                    .accessibilityIdentifier("vote-remove")
            }
        }
        .padding(20)
        }
        .scrollBounceBehavior(.basedOnSize)
        .task { preview = try? await app.env.votePreview() }
    }
}

/// The vote sheet's value text, pure for tests: RN prefixes "-" in downvote mode.
enum VoteSheetText {
    static func amount(_ text: String, down: Bool) -> String { down ? "-" + text : text }
}
