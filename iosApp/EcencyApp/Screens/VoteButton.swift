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
        .themedSheet(isPresented: $showWeights) {
            // RN keeps the last weight for waves apart from posts and comments (waveUpvotePercent)
            let wave = app.env.waves.isWave(key: key)
            let start = VoteSheetState.companion.open(rememberedPercent: wave ? app.env.waves.voteWeightPercent : app.voteWeightPercent,
                                                     votedByMe: votedByMe, downvotedByMe: downvotedByMe)
            VoteWeightSheet(isVoted: votedByMe || downvotedByMe, start: start) { choice in
                showWeights = false
                if let choice {
                    Haptics.play(.vote)
                    // one remembered weight for both directions (RN)
                    if wave { app.env.waves.voteWeightPercent = Double(choice.percent) } else { app.voteWeightPercent = Double(choice.percent) }
                    app.env.votes.vote(author: key.author, permlink: key.permlink, weight: choice.weight)
                } else {
                    app.env.votes.vote(author: key.author, permlink: key.permlink, weight: 0)
                }
            }
            .environmentObject(app)
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

/// The compact vote sheet: an Up / Down toggle with the `$` estimate, the weight, a slider with 25/50/75 ticks, four
/// presets and one vote button whose label follows the direction (shared VoteSheetState); "Remove vote" when voted.
/// The sheet is as tall as its content and scrolls at the largest text sizes.
struct VoteWeightSheet: View {
    let isVoted: Bool
    /// The choice to vote with, nil to remove the vote.
    let onDone: (_ choice: VoteSheetState?) -> Void
    @EnvironmentObject private var app: AppContainer
    @State private var sheet: VoteSheetState
    @State private var preview: VotePreview?
    /// The content's measured height: the sheet's detent.
    @State private var measured: CGFloat?
    @ScaledMetric(relativeTo: .body) private var estimate: CGFloat = 400
    @Environment(\.appTheme) private var theme
    @Environment(\.layoutDirection) private var layout

    init(isVoted: Bool, start: VoteSheetState, onDone: @escaping (VoteSheetState?) -> Void) {
        self.isVoted = isVoted
        self.onDone = onDone
        _sheet = State(initialValue: start)
    }

    private var fill: Color { sheet.down ? theme.danger : theme.accent }
    private var tint: Color { sheet.down ? theme.dangerText : theme.accentText }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                AdaptiveStack(spacing: 12) {
                    toggle
                    Spacer(minLength: 0)
                    if let preview {
                        VStack(alignment: .trailing, spacing: 2) {
                            Text(sheet.valueText(preview: preview)).scaledFont(24, weight: .bold, relativeTo: .title2)
                                .monospacedDigit().foregroundStyle(theme.text)
                                .accessibilityIdentifier("vote-value")
                            Text(ReadingUiCopy.shared.ESTIMATED_VALUE).font(.caption).foregroundStyle(theme.secondary)
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
                VStack(alignment: .leading, spacing: 0) {
                    Text(sheet.percentText).scaledFont(40, weight: .bold, relativeTo: .largeTitle).monospacedDigit()
                        .foregroundStyle(tint).accessibilityIdentifier("vote-percent")
                    Text(ReadingUiCopy.shared.VOTE_WEIGHT).font(.caption).foregroundStyle(theme.secondary)
                }
                // the slider reads the weight
                .accessibilityHidden(true)
                slider
                presets
                Button { onDone(sheet) } label: {
                    Text(sheet.buttonLabel).font(.body.weight(.semibold)).foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity, minHeight: 50)
                        .background(fill, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("vote-submit")
                if isVoted {
                    Button(ReadingUiCopy.shared.REMOVE_VOTE, role: .destructive) { onDone(nil) }
                        .frame(maxWidth: .infinity, minHeight: minTapTarget)
                        .accessibilityIdentifier("vote-remove")
                }
                if let power = preview?.votingPowerText {
                    Text(power).font(.caption).foregroundStyle(theme.secondary).frame(maxWidth: .infinity)
                        .multilineTextAlignment(.center).accessibilityIdentifier("vote-power")
                }
            }
            .padding(.horizontal, 20).padding(.top, 24).padding(.bottom, 12)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { if $0 > 0, abs($0 - (measured ?? 0)) > 0.5 { measured = $0 } }
            .opacity(measured == nil ? 0 : 1)
        }
        .scrollBounceBehavior(.basedOnSize)
        .presentationDetents([.height(measured ?? estimate)])
        .presentationDragIndicator(.visible)
        .task { preview = try? await app.env.votePreview() }
    }

    /// Up / Down, the selected half filled in the direction's colour.
    private var toggle: some View {
        // side by side, stacked when the text is too large for one row
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 2) { directions }
            VStack(alignment: .leading, spacing: 2) { directions }
        }
        .padding(3)
        .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }

    @ViewBuilder private var directions: some View {
        direction(ReadingUiCopy.shared.VOTE_UP, a11y: UiCopy.shared.UPVOTE, icon: .collapse, down: false, id: "vote-dir-up")
        direction(ReadingUiCopy.shared.VOTE_DOWN, a11y: ReadingUiCopy.shared.DOWNVOTE, icon: .expand, down: true, id: "vote-dir-down")
    }

    private func direction(_ title: String, a11y: String, icon: AppIcon, down: Bool, id: String) -> some View {
        let selected = sheet.down == down
        return Button { sheet = sheet.withDirection(down: down) } label: {
            Label(title, appIcon: icon).font(.subheadline.weight(.semibold))
                .foregroundStyle(selected ? Color.white : theme.text)
                .padding(.horizontal, 14).frame(minHeight: minTapTarget)
                .background(selected ? (down ? theme.danger : theme.accent) : Color.clear, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(a11y)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(id)
    }

    private var slider: some View {
        Slider(value: Binding(get: { Double(sheet.percent) }, set: { sheet = sheet.withPercent(value: $0) }), in: 1...100, step: 1)
            .tint(tint)
            .accessibilityLabel(ReadingUiCopy.shared.VOTE_WEIGHT)
            .accessibilityValue(sheet.percentText)
            .accessibilityIdentifier("vote-weight")
            .padding(.bottom, 6)
            .background(alignment: .bottom) { ticks.frame(height: 6) }
    }

    /// Marks under the track at 25, 50 and 75 percent, mirrored right to left.
    private var ticks: some View {
        GeometryReader { g in
            // the thumb's half width insets the track at both ends
            let inset: CGFloat = 14
            let track = g.size.width - 2 * inset
            ForEach([25, 50, 75], id: \.self) { p in
                let f = CGFloat(p - 1) / 99
                Capsule().fill(theme.secondary).frame(width: 2, height: 6)
                    .position(x: inset + track * (layout == .rightToLeft ? 1 - f : f), y: 3)
            }
        }
        .accessibilityHidden(true)
    }

    /// 25 / 50 / 75 / 100 in a row, two rows when the text is too large for one.
    private var presets: some View {
        let items = VoteSheetState.companion.PRESETS.map { $0.int32Value }
        return ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) { ForEach(items, id: \.self) { preset($0) } }
            Grid(horizontalSpacing: 8, verticalSpacing: 8) {
                GridRow { preset(items[0]); preset(items[1]) }
                GridRow { preset(items[2]); preset(items[3]) }
            }
        }
    }

    private func preset(_ p: Int32) -> some View {
        let selected = sheet.selectedPreset?.int32Value == p
        return Button { sheet = sheet.withPreset(preset: p) } label: {
            Text(Fmt.percent(Int(p))).font(.subheadline.weight(selected ? .bold : .regular)).monospacedDigit()
                .lineLimit(1).fixedSize()
                .foregroundStyle(selected ? tint : theme.text)
                .frame(maxWidth: .infinity, minHeight: minTapTarget)
                .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
                .overlay { if selected { RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(tint, lineWidth: 2) } }
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("vote-preset-\(p)")
    }
}
