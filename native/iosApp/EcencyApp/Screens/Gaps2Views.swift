import Charts
import EcencyCore
import SwiftUI

/// RN postReadingMetadata: Words | Reading N min | AI Recap (signed in, 50 words or more), then the summary box with
/// "Translate" / "Hide translation" and a language picker. Listen is its own bar (`ListenSection`).
struct ReadingBar: View {
    let info: ReadingInfo
    var recap: Observed<RecapState>?
    @Environment(\.appTheme) private var theme

    var body: some View {
        if info.visible {
            VStack(spacing: 0) {
                HStack(spacing: 0) {
                    metric(UiCopy.shared.WORDS, Fmt.int(info.words))
                    divider
                    metric(UiCopy.shared.READING, "\(Fmt.int(info.minutes)) \(UiCopy.shared.MIN)")
                    if let recap, recap.state.eligible {
                        divider
                        RecapButton(recap: recap)
                    }
                }
                if let recap { RecapDetails(recap: recap) }
            }
            .padding(.vertical, 12)
            .overlay(alignment: .top) { Rectangle().fill(theme.lightBackground).frame(height: 1) }
            .overlay(alignment: .bottom) { Rectangle().fill(theme.lightBackground).frame(height: 1) }
            .padding(.vertical, 8)
            .accessibilityIdentifier("reading-bar")
        }
    }

    private var divider: some View { Rectangle().fill(theme.lightBackground).frame(width: 1, height: 40).accessibilityHidden(true) }

    private func metric(_ label: String, _ value: String) -> some View {
        VStack(spacing: 4) {
            Text(label).scaledFont(12).foregroundStyle(theme.secondary)
            Text(value).scaledFont(16, weight: .semibold).foregroundStyle(theme.text)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }
}

/// The third column: the AI badge over "Recap" (or "Try again" once a summary shows), with what the tap costs.
private struct RecapButton: View {
    @ObservedObject var recap: Observed<RecapState>
    @Environment(\.appTheme) private var theme

    private var model: RecapModel { recap.model as! RecapModel }

    var body: some View {
        let s = recap.state
        VStack(spacing: 4) {
            Text(UiCopy.shared.AI_BADGE).scaledFont(8, weight: .heavy, relativeTo: .caption2).foregroundStyle(.white)
                .padding(.horizontal, 3).padding(.vertical, 1).background(theme.accent, in: RoundedRectangle(cornerRadius: 3))
            if s.running {
                ProgressView().controlSize(.small).accessibilityIdentifier("recap-running")
            } else if s.summary != nil {
                Button(UiCopy.shared.TRY_AGAIN) { model.tryAgain() }
                    .scaledFont(14, weight: .semibold).foregroundStyle(theme.accentText).accessibilityIdentifier("recap-try-again")
            } else {
                Button(s.label) { model.summarize() }
                    .scaledFont(14, weight: .semibold).foregroundStyle(theme.accentText).accessibilityIdentifier("recap")
            }
            if s.summary == nil, !s.unresolved, let cost = s.costText {
                Text(cost).scaledFont(10).foregroundStyle(theme.secondary).accessibilityIdentifier("recap-cost")
            }
        }
        .frame(maxWidth: .infinity)
    }
}

/// The notice for a run without an answer, the summary box and the "Fail!" alert.
private struct RecapDetails: View {
    @ObservedObject var recap: Observed<RecapState>
    @Environment(\.appTheme) private var theme

    private var model: RecapModel { recap.model as! RecapModel }

    var body: some View {
        let s = recap.state
        VStack(alignment: .leading, spacing: 0) {
            if s.unresolved || s.notice != nil {
                UnresolvedNotice(notice: s.notice, unresolved: s.unresolved, running: s.running, id: "recap") { model.giveUp() }
                    .padding(.horizontal, 16).padding(.top, 8)
            }
            if let text = s.shownText {
                VStack(alignment: .leading, spacing: 10) {
                    Text(text).scaledFont(14).foregroundStyle(theme.text).accessibilityIdentifier("recap-text")
                    if s.showTranslation, s.translating { ProgressView().controlSize(.small) }
                    Rectangle().fill(theme.icon).frame(height: 1)
                    HStack(spacing: 12) {
                        Button(s.showTranslation ? UiCopy.shared.HIDE_TRANSLATION : UiCopy.shared.TRANSLATE) { model.toggleTranslation() }
                            .scaledFont(13, weight: .semibold).foregroundStyle(theme.accentText).accessibilityIdentifier("recap-translate")
                        if s.showTranslation {
                            Menu {
                                ForEach(s.languages, id: \.first) { pair in
                                    Button(pair.second as String? ?? "") { model.setTarget(code: pair.first as String? ?? "") }
                                }
                            } label: {
                                HStack(spacing: 2) { Text(Languages.shared.name(code: s.target)); Image(appIcon: .dropdown) }.scaledFont(12).foregroundStyle(theme.text)
                                    .padding(.horizontal, 8).padding(.vertical, 4)
                                    .overlay(RoundedRectangle(cornerRadius: 6).stroke(theme.icon, lineWidth: 1))
                            }
                            .accessibilityIdentifier("recap-language")
                        }
                    }
                }
                .padding(.horizontal, 16).padding(.vertical, 12)
                .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 8))
                .padding(.horizontal, 16).padding(.top, 8)
                .accessibilityIdentifier("recap-box")
            }
        }
        .alert(UiCopy.shared.FAIL, isPresented: Binding(get: { s.alert != nil }, set: { if !$0 { model.clearAlert() } })) {
            Button(UiCopy.shared.OK, role: .cancel) {}
        } message: { Text(s.alert ?? "") }
    }
}

// MARK: Wallet balance analytics


/// RN balanceAnalyticsSheet: Balance History (a pannable line chart opened at the newest end) and the yearly /
/// monthly / daily summary.
struct BalanceAnalyticsSheet: View {
    @StateObject private var observed: Observed<BalanceAnalyticsState>
    @Environment(\.appTheme) private var theme

    init(env: AppEnvironment, username: String, coinType: String) {
        _observed = StateObject(wrappedValue: Observed(env.balanceAnalyticsModel(username: username, coinType: coinType), ownsModel: true))
    }

    private var model: BalanceAnalyticsModel { observed.model as! BalanceAnalyticsModel }

    var body: some View {
        let s = observed.state
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                tab(UiCopy.shared.BALANCE_HISTORY, s.tab == .history, "analytics-history-tab") { model.selectTab(tab: .history) }
                tab(s.summaryTitle, s.tab == .summary, "analytics-summary-tab") { model.selectTab(tab: .summary) }
            }
            .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 20))
            .padding(.horizontal, 16).padding(.top, 16).padding(.bottom, 16)
            if s.tab == .history { history(s) } else { summary(s) }
            Spacer(minLength: 0)
        }
        .frame(minHeight: 350)
        .background(theme.background)
        .presentationDetents([.medium, .large])
        .onAppear { model.start() }
        .accessibilityIdentifier("balance-analytics")
    }

    private func tab(_ label: String, _ on: Bool, _ id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).scaledFont(14, weight: .semibold).foregroundStyle(on ? .white : theme.secondary)
                .frame(maxWidth: .infinity).padding(.vertical, 10)
                .background(on ? theme.accent : .clear, in: RoundedRectangle(cornerRadius: 20))
        }
        .buttonStyle(.plain).accessibilityIdentifier(id)
    }

    private func centered(_ text: String?) -> some View {
        Group {
            if let text { Text(text).scaledFont(14).foregroundStyle(theme.secondary) } else { ProgressView() }
        }
        .frame(maxWidth: .infinity, minHeight: 200)
    }

    @ViewBuilder
    private func history(_ s: BalanceAnalyticsState) -> some View {
        if s.historyLoading { centered(nil) }
        else if let e = s.historyError { centered(e) }
        else if s.points.isEmpty { centered(UiCopy.shared.NO_ACTIVITY).accessibilityIdentifier("analytics-empty") }
        else {
            BalanceChartView(points: s.points, loadingOlder: s.loadingOlder) { model.loadOlder() }
                .padding(.horizontal, 16)
        }
    }

    @ViewBuilder
    private func summary(_ s: BalanceAnalyticsState) -> some View {
        HStack(spacing: 8) {
            ForEach(BalanceAnalyticsModel.companion.GRANULARITIES, id: \.self) { g in
                let on = g == s.granularity
                Button { model.selectGranularity(granularity: g) } label: {
                    Text(UiCopy.shared.granularity(g: g)).scaledFont(12, weight: .semibold).foregroundStyle(on ? .white : theme.secondary)
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(on ? theme.accent : theme.lightBackground, in: RoundedRectangle(cornerRadius: 16))
                }
                .buttonStyle(.plain).accessibilityIdentifier("granularity-\(g)")
            }
            Spacer()
        }
        .padding(.horizontal, 16).padding(.bottom, 12)
        if s.summaryLoading { centered(nil) }
        else if let e = s.summaryError { centered(e) }
        else if let sum = s.summary {
            let changeColor: Color = sum.positive == nil ? theme.text : (sum.positive!.boolValue ? theme.successText : theme.dangerText)
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible())], spacing: 8) {
                card(UiCopy.shared.CURRENT_BALANCE, sum.current, theme.text, "summary-current")
                card(s.changeLabel, sum.change ?? UiCopy.shared.NO_CHANGE, changeColor, "summary-change")
                card(s.minLabel, sum.min, theme.text, "summary-min")
                card(s.maxLabel, sum.max, theme.text, "summary-max")
            }
            .padding(.horizontal, 16)
        } else { centered(UiCopy.shared.NO_ACTIVITY) }
    }

    private func card(_ label: String, _ value: String, _ color: Color, _ id: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).scaledFont(12).foregroundStyle(theme.secondary)
            Text(value).scaledFont(16, weight: .bold).foregroundStyle(color).accessibilityIdentifier(id)
        }
        .frame(maxWidth: .infinity, alignment: .leading).padding(12)
        .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 12))
    }
}

/// RN SimpleChart for the balance history: a line with a fading fill, RN's compact y labels pinned by the chart, about
/// 12 pt per point, opened at the newest end; panning near the oldest loaded point asks for the next older page. The x
/// value counts back from the newest point, so older pages arriving on the left never move what is on screen. Left to
/// right in RTL too (README "Localization", RTL).
struct BalanceChartView: View {
    let points: [ChartPoint]
    let loadingOlder: Bool
    let onNearStart: () -> Void
    @State private var position: Int = 0
    private let blue = Color(hex: 0x357CE6)

    var body: some View {
        GeometryReader { geo in
            let visible = max(2, Int((geo.size.width - 60) / 12))
            let n = points.count
            let first = -(n - 1)
            Chart {
                ForEach(Array(points.enumerated()), id: \.offset) { i, p in
                    AreaMark(x: .value("i", i - (n - 1)), y: .value("v", p.value))
                        .foregroundStyle(LinearGradient(colors: [blue.opacity(0.8), blue.opacity(0)], startPoint: .top, endPoint: .bottom))
                    LineMark(x: .value("i", i - (n - 1)), y: .value("v", p.value)).foregroundStyle(blue)
                }
            }
            .chartYScale(domain: .automatic(includesZero: false))
            .chartYAxis {
                AxisMarks(position: .leading, values: .automatic(desiredCount: 5)) { v in
                    AxisValueLabel { Text(BalanceAnalyticsModel.companion.compact(value: v.as(Double.self) ?? 0)).scaledFont(10, relativeTo: .caption2) }
                }
            }
            .chartXAxis {
                AxisMarks(values: points.enumerated().filter { !$0.element.label.isEmpty }.map { $0.offset - (n - 1) }) { v in
                    AxisValueLabel {
                        if let i = v.as(Int.self), i + n - 1 >= 0, i + n - 1 < n { Text(points[i + n - 1].label).scaledFont(10, relativeTo: .caption2) }
                    }
                }
            }
            // axis labels grow with the text size up to a point the chart can still draw
            .dynamicTypeSize(...DynamicTypeSize.accessibility1)
            .chartScrollableAxes(.horizontal)
            .chartXVisibleDomain(length: min(visible, max(n - 1, 1)))
            .chartScrollPosition(x: $position)
            .onChange(of: position) { _, x in
                // within about 200 pt of the oldest loaded point
                if x - first <= 200 / 12, x != 0 { onNearStart() }
            }
            .onAppear { position = -min(visible, max(n - 1, 1)) }
            .overlay(alignment: .topLeading) { if loadingOlder { ProgressView().controlSize(.small).padding(8) } }
        }
        .frame(height: 220)
        // time runs left to right in every language: the axis, the pinned labels and the scroll position stay as in LTR
        .environment(\.layoutDirection, .leftToRight)
        .accessibilityIdentifier("balance-chart")
    }
}

// MARK: Show Images

/// RN useImageReveal on cards: with "Show Images" off the content image is not loaded; the placeholder's tap loads
/// that one image for the session (`ImageReveals`).
struct GatedCardImage: View {
    let card: PostCard
    let src: String
    let width: CGFloat
    @ObservedObject var notifications: Observed<NotificationsState>
    @EnvironmentObject private var app: AppContainer
    @State private var revealed = false

    var body: some View {
        if !revealed, app.env.imageReveals.hides(card: card, showImages: notifications.state.showImages) {
            HiddenImagePlaceholder(width: width) {
                app.env.imageReveals.reveal(url: src)
                revealed = true
            }
            .accessibilityIdentifier("hidden-image-\(card.key.author)/\(card.key.permlink)")
        } else {
            CardImageView(src: src, width: width)
        }
    }
}

/// RN HiddenImagePlaceholder: dashed frame, image icon and "Tap to load image" in the image's 16:9 frame.
struct HiddenImagePlaceholder: View {
    let width: CGFloat
    let onReveal: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        Button(action: onReveal) {
            VStack(spacing: 4) {
                Image(appIcon: .image).scaledFont(26).foregroundStyle(theme.icon)
                Text(UiCopy.shared.TAP_TO_LOAD_IMAGE).scaledFont(12).foregroundStyle(theme.secondary)
            }
            .frame(width: width, height: width * 9 / 16)
            .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(theme.border, style: StrokeStyle(lineWidth: 1, dash: [4, 4])))
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity)
    }
}

// MARK: Scroll to top

/// The scroll offset of a lazy stack inside a `ScrollView` named `space` (points from the top). Optional, so a
/// sibling that reports nothing (an overlay) never resets it.
struct ScrollOffsetKey: PreferenceKey {
    static var defaultValue: CGFloat? = nil
    static func reduce(value: inout CGFloat?, nextValue: () -> CGFloat?) { value = nextValue() ?? value }
}

/// The height of that stack (its content), next to `ScrollOffsetKey`: whether the list can scroll at all.
struct ScrollContentHeightKey: PreferenceKey {
    static var defaultValue: CGFloat? = nil
    static func reduce(value: inout CGFloat?, nextValue: () -> CGFloat?) { value = nextValue() ?? value }
}

extension View {
    /// Put on the lazy stack: reports how far its ScrollView (coordinate space [space]) is scrolled.
    func reportsScrollOffset(_ space: String) -> some View {
        background(GeometryReader { g in
            Color.clear
                .preference(key: ScrollOffsetKey.self, value: -g.frame(in: .named(space)).minY)
                .preference(key: ScrollContentHeightKey.self, value: g.size.height)
        })
    }

    /// RN ScrollTopPopup on a ScrollView whose stack calls `reportsScrollOffset(space)`: the arrow pill at the bottom
    /// left, by `ScrollTopRule` (5000 points scrolled up; post lists hide it on tap only, waves also on scroll down).
    func scrollTopPopup(space: String, hidesOnScrollDown: Bool = false, id: String = "scroll-top", onTap: @escaping () -> Void) -> some View {
        modifier(ScrollTopPopupModifier(space: space, onTap: onTap, id: id, rule: ScrollTopRule(hidesOnScrollDown: hidesOnScrollDown, nowMillis: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) })))
    }
}

private struct ScrollTopPopupModifier: ViewModifier {
    let space: String
    let onTap: () -> Void
    let id: String
    @State var rule: ScrollTopRule
    @State private var visible = Self.demoVisible
    @Environment(\.motion) private var motion

    /// Debug `-demoScrollTop YES`: shown at once (layout UI tests and recordings; it otherwise needs 5000 points).
    private static var demoVisible: Bool {
        #if DEBUG
        UserDefaults.standard.bool(forKey: "demoScrollTop")
        #else
        false
        #endif
    }

    func body(content: Content) -> some View {
        content
            .coordinateSpace(name: space)
            .onPreferenceChange(ScrollOffsetKey.self) { offset in
                guard let offset, !Self.demoVisible else { return }
                let v = rule.onScroll(offset: Double(offset))
                if v != visible { visible = v }
            }
            // the FAB's twin at the bottom leading corner (mirrored in RTL), opposite the compose FAB
            .overlay(alignment: .bottomLeading) {
                // the fade belongs to the button only: the list's own scroll to the top and the summary's opening
                // keep their animations
                ZStack {
                    if visible {
                        FabButton(appIcon: .arrowUp, label: UiCopy.shared.SCROLL_TOP, id: id) {
                            rule.onTap()
                            visible = Self.demoVisible
                            onTap()
                        }
                        .transition(motion.pops ? .scale.combined(with: .opacity) : .opacity)
                    }
                }
                .animation(motion.fade(), value: visible)
            }
    }
}

// MARK: Waves card on the Feed tab

/// RN feedScreen's `{isLoggedIn && <WavesOnboardingChecklist />}`: the waves "Getting started" card (the shared model
/// the Waves tab uses); "Post your first Wave" opens the wave composer here.
struct FeedWavesCard: View {
    let username: String
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @State private var composer: WaveComposerRef?

    var body: some View {
        VStack(spacing: 0) {
            // the card draws nothing until its model knows the account: the load hangs off a view that is always there
            Color.clear.frame(height: 0)
                .task(id: username) {
                    app.env.wavesOnboarding.start()
                    app.env.wavesOnboarding.refresh()
                }
            WavesOnboardingCard(model: app.env.wavesOnboarding, username: username, idPrefix: "feed-waves-onboarding", onWave: {
                composer = WaveComposerRef(model: app.env.waves.composer())
            }, onCheckIn: { app.push(AppScreenCompanion.shared.of(route: .perks, arg: "")) })
        }
        .sheet(item: $composer) { ref in
            WaveComposerSheet(composer: Observed<WaveComposeState>(ref.model, ownsModel: false), proxyBase: app.env.proxyBase,
                              attacher: app.env.waves.attacher as? WaveUploadAttacher, ui: app.env.ui) { composer = nil }
                .environment(\.appTheme, theme)
        }
        .onChange(of: username) { _, _ in composer = nil }
    }
}

// MARK: Digest entry points

/// RN NewsletterSenderInfo: "{weekly} weekly • {monthly} monthly email subscribers", copy link and "Manage".
struct DigestSenderRow: View {
    @StateObject private var observed: Observed<DigestSenderState>
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    init(username: String, env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.digestSenderModel(username: username), ownsModel: true))
    }

    private var model: DigestSenderModel { observed.model as! DigestSenderModel }

    var body: some View {
        Group {
            if observed.state.loaded {
                HStack(spacing: 8) {
                    Text(observed.state.text).scaledFont(13).foregroundStyle(theme.secondary).lineLimit(1)
                        .accessibilityIdentifier("digest-sender-count")
                    Button {
                        UIPasteboard.general.string = model.link
                        _ = app.env.ui.toast(text: DigestEntryCopy.shared.COPIED)
                    } label: { Image(appIcon: .link).foregroundStyle(theme.secondary) }
                        .buttonStyle(.borderless).accessibilityLabel(DigestEntryCopy.shared.COPIED).accessibilityIdentifier("digest-sender-copy")
                    Button(DigestEntryCopy.shared.MANAGE) { app.push(AppScreenEmailDigests.shared) }
                        .scaledFont(13).foregroundStyle(theme.accentText).buttonStyle(.borderless).accessibilityIdentifier("digest-sender-manage")
                }
                .frame(maxWidth: .infinity).padding(.horizontal, 16).padding(.bottom, 4)
            }
        }
        .onAppear { model.start() }
    }
}

/// RN NewsletterPostPrompt: the end-of-post card offering the digest that would carry this post.
struct DigestPostPrompt: View {
    @ObservedObject var model: Observed<DigestPromptState>
    let post: Post
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    private var prompt: DigestPromptModel { model.model as! DigestPromptModel }

    var body: some View {
        Group {
            if model.state.visible, let target = model.state.target {
                HStack(spacing: 8) {
                    Text(target.body).scaledFont(13).foregroundStyle(theme.secondary).frame(maxWidth: .infinity, alignment: .leading)
                    Button { prompt.subscribe() } label: {
                        Text(DigestCopy.shared.SUBSCRIBE).scaledFont(13, weight: .semibold).foregroundStyle(.white)
                            .padding(.horizontal, 14).padding(.vertical, 7).background(theme.accent, in: Capsule())
                    }
                    .buttonStyle(.plain).accessibilityIdentifier("digest-prompt-subscribe")
                    Button { prompt.dismiss() } label: { Image(appIcon: .close).foregroundStyle(theme.secondary).padding(6) }
                        .buttonStyle(.plain).accessibilityLabel(DigestEntryCopy.shared.CLOSE).accessibilityIdentifier("digest-prompt-dismiss")
                }
                .padding(.leading, 12).padding(.trailing, 4).padding(.vertical, 10)
                .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 12))
                .padding(.horizontal, 16).padding(.top, 12)
                .accessibilityIdentifier("digest-prompt")
            }
        }
        .task(id: "\(post.author)/\(post.permlink)|\(app.session.state.username ?? "")") {
            prompt.start(post: post, viewer: app.session.state.username)
        }
    }
}

// MARK: Payout popover

struct PayoutPostRef: Identifiable {
    let post: Post
    var id: String { "\(post.author)/\(post.permlink)" }
}

/// RN upvotePopover with payoutDetailsContent: the rows of `PayoutDetails` (label 120 wide, bold, 12; value right aligned).
struct PayoutPopoverView: View {
    @StateObject private var observed: Observed<PayoutDetailsState>
    @Environment(\.appTheme) private var theme

    init(post: Post, env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.payoutDetailsModel(post: post), ownsModel: true))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(observed.state.rows, id: \.label) { row in
                // label and value side by side; stacked at accessibility text sizes
                AdaptiveStack(verticalAlignment: .top) {
                    Text(row.label).scaledFont(12, weight: .bold).foregroundStyle(theme.secondary).frame(minWidth: 120, alignment: .leading)
                    Text(row.value).scaledFont(12).foregroundStyle(theme.secondary).multilineTextAlignment(.trailing)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("payout-row-\(row.label)")
            }
        }
        .padding(.horizontal, 26).padding(.vertical, 12)
        .frame(minWidth: 260, maxWidth: 320)
        .background(theme.background)
        .onAppear { (observed.model as! PayoutDetailsModel).start() }
        .accessibilityIdentifier("payout-popover")
    }
}

// MARK: Post views and stats

/// RN postDisplayView's view count: eye icon and the abbreviated visits; a tap opens the stats sheet.
struct PostViewCountView: View {
    @ObservedObject var model: Observed<PostStatsState>
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var open = false

    private var stats: PostStatsModel { model.model as! PostStatsModel }

    var body: some View {
        Button {
            open = true
            stats.loadDevices()
        } label: {
            HStack(spacing: 4) {
                Image(appIcon: .preview).scaledFont(12).foregroundStyle(theme.secondary)
                if model.state.loading { ProgressView().controlSize(.mini) } else {
                    Text(model.state.viewsText).scaledFont(12).foregroundStyle(theme.secondary)
                }
            }
            .padding(.top, 8).padding(.leading, 8)
            .frame(minHeight: minTapTarget, alignment: .top).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(StatsCopy.shared.views(count: model.state.stats.visits))
        .accessibilityHint(StatsCopy.shared.STATS_HINT)
        .accessibilityIdentifier("post-views")
        .sheet(isPresented: $open) {
            PostStatsSheet(model: model) {
                open = false
                app.push(stats.promoteScreen)
            }
            .presentationDetents(typeSize >= .stacksRows ? [.large] : [.medium, .large])
        }
    }
}

/// RN postStatsModal: Visitors, Views, Minutes; the device breakdown; the promote pitch and PROMOTE POST.
struct PostStatsSheet: View {
    @ObservedObject var model: Observed<PostStatsState>
    let onPromote: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        let s = model.state
        ScrollView {
        VStack(alignment: .leading, spacing: 12) {
            AdaptiveStack {
                stat(StatsCopy.shared.VISITORS, s.stats.visitors)
                stat(StatsCopy.shared.VIEWS, s.stats.visits)
                stat(StatsCopy.shared.MINUTES, s.minutes)
            }
            ForEach(s.devices, id: \.device) { d in
                VStack(alignment: .leading, spacing: 4) {
                    Text(d.device).scaledFont(13).foregroundStyle(theme.text)
                    ProgressView(value: d.share).tint(theme.icon).accessibilityIdentifier("device-\(d.device)")
                }
            }
            (Text(StatsCopy.shared.PROMOTE_TITLE).bold().foregroundColor(theme.text) + Text(StatsCopy.shared.PROMOTE_MESSAGE).foregroundColor(theme.secondary))
                .scaledFont(14).padding(.top, 8)
            Button(action: onPromote) { Text(StatsCopy.shared.PROMOTE).bold().frame(maxWidth: .infinity, minHeight: 44) }
                .buttonStyle(.borderedProminent).tint(theme.accent).accessibilityIdentifier("stats-promote")
        }
        .padding(16)
        }
        .scrollBounceBehavior(.basedOnSize)
        .background(theme.background)
        .accessibilityIdentifier("post-stats")
    }

    private func stat(_ label: String, _ value: Int64) -> some View {
        VStack(spacing: 2) {
            Text(model.state.loading ? "..." : Fmt.int(value)).scaledFont(18, weight: .bold).foregroundStyle(theme.text)
            Text(label).scaledFont(12).foregroundStyle(theme.secondary)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }
}

// MARK: Source and AI badges

/// RN EcencySourceBadge: the Ecency logo mark, 12 pt, in a soft gray so it stays a faint hint beside the Pro badge.
/// Shared by cards, headers and the post footer; an image that says "Published with Ecency" and shows no text.
struct EcencySourceBadge: View {
    @Environment(\.colorScheme) private var scheme
    @ScaledMetric(relativeTo: .caption2) private var size: CGFloat = 12

    var body: some View {
        EcencyLogoMarkShape()
            .fill(Color(hex: UInt32(truncatingIfNeeded: scheme == .dark ? EcencyLogoMark.shared.COLOR_DARK : EcencyLogoMark.shared.COLOR_LIGHT) & 0xFFFFFF))
            .frame(width: size, height: size)
            .accessibilityElement()
            .accessibilityAddTraits(.isImage)
            .accessibilityLabel(PostBadges.shared.SOURCE_ECENCY)
            .accessibilityIdentifier("ecency-source")
    }
}

/// The Ecency logo mark (RN EcencySourceBadge, without the round blue background), drawn from the shared
/// EcencyLogoMark contours that Android draws too, scaled from RN's view box into the rect.
struct EcencyLogoMarkShape: Shape {
    func path(in rect: CGRect) -> Path {
        let mark = EcencyLogoMark.shared
        let s = min(rect.width, rect.height) / CGFloat(mark.VIEW_SIZE)
        func pt(_ c: KotlinFloatArray, _ i: Int32) -> CGPoint {
            CGPoint(x: rect.minX + (CGFloat(c.get(index: i)) - CGFloat(mark.VIEW_X)) * s,
                    y: rect.minY + (CGFloat(c.get(index: i + 1)) - CGFloat(mark.VIEW_Y)) * s)
        }
        var p = Path()
        for c in mark.CONTOURS {
            p.move(to: pt(c, 0))
            for i in stride(from: Int32(2), to: c.size, by: 6) {
                p.addCurve(to: pt(c, i + 4), control1: pt(c, i), control2: pt(c, i + 2))
            }
            p.closeSubpath()
        }
        return p
    }
}

/// RN's robot-outline icon: "Author disclosed AI usage" (json_metadata.ai_tools).
struct AiDisclosedBadge: View {
    @Environment(\.appTheme) private var theme

    var body: some View {
        Image(appIcon: .ai).scaledFont(12).foregroundStyle(theme.icon)
            .accessibilityLabel(PostBadges.shared.AI_DISCLOSED)
            .accessibilityIdentifier("ai-disclosed")
    }
}

/// RN postDisplayView's footer source row: "Posted by {author} via {App} {age}", then the badges.
struct PostedByRow: View {
    let post: Post
    @Environment(\.appTheme) private var theme

    var body: some View {
        HStack(spacing: 3) {
            Text(PostBadges.shared.postedBy(post: post, nowMillis: Int64(Date().timeIntervalSince1970 * 1000)))
                .scaledFont(12).foregroundStyle(theme.secondary)
            if PostBadges.shared.footerFromEcency(app: post.metadata.app) { EcencySourceBadge() }
            if post.metadata.aiDisclosed { AiDisclosedBadge() }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16).padding(.top, 12)
        .accessibilityIdentifier("posted-by")
    }
}

// MARK: Link sheet

/// RN postInteractionHandler's OptionsModal for an external body link: Copy, Open in Explore (only once the dApp
/// browser opens URLs, after its warning), Open in System browser, Cancel.
struct LinkSheetHost: View {
    @EnvironmentObject private var app: AppContainer
    @State private var explore: (url: URL, screen: AppScreen)?

    var body: some View {
        Color.clear
            .confirmationDialog(LinkSheet.shared.TITLE, isPresented: Binding(get: { app.linkSheet != nil }, set: { if !$0 { app.linkSheet = nil } }),
                                titleVisibility: .visible, presenting: app.linkSheet) { url in
                Button(LinkSheet.shared.COPY) {
                    UIPasteboard.general.string = url.absoluteString
                    Haptics.play(.success)
                    _ = app.env.ui.toast(text: LinkSheet.shared.COPIED)
                }
                .accessibilityIdentifier("link-copy")
                if let screen = LinkSheet.shared.exploreScreen(url: url.absoluteString) {
                    Button(LinkSheet.shared.OPEN_EXPLORE) { explore = (url, screen) }.accessibilityIdentifier("link-explore")
                }
                Button(LinkSheet.shared.OPEN_SYSTEM) { UIApplication.shared.open(url) }.accessibilityIdentifier("link-system")
                Button(LinkSheet.shared.CANCEL, role: .cancel) {}
            } message: { url in Text(url.absoluteString) }
            .alert(LinkSheet.shared.EXPLORE_WARNING_TITLE, isPresented: Binding(get: { explore != nil }, set: { if !$0 { explore = nil } }), presenting: explore) { target in
                Button(LinkSheet.shared.CONTINUE) { app.push(target.screen) }
                Button(LinkSheet.shared.CANCEL, role: .cancel) {}
            } message: { target in Text(LinkSheet.shared.exploreWarning(url: target.url.absoluteString)) }
    }
}

// MARK: Reply threads

/// The collapse toggle of a top-level comment's replies: whether they show, and the tap.
struct ThreadToggleState {
    let open: Bool
    let onToggle: () -> Void
}

/// RN commentView's rightButton: a 22 high pill in `$iconColor` with a white chevron, down while collapsed.
struct ThreadTogglePill: View {
    let toggle: ThreadToggleState
    @Environment(\.appTheme) private var theme

    var body: some View {
        Button(action: toggle.onToggle) {
            Image(appIcon: toggle.open ? .collapse : .expand).scaledFont(12, weight: .bold).foregroundStyle(.white)
                .frame(minWidth: 40, minHeight: 22).background(theme.icon, in: Capsule())
                .frame(minHeight: minTapTarget).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(toggle.open ? CommentThreadCopy.shared.HIDE_REPLIES : CommentThreadCopy.shared.SHOW_REPLIES)
        .accessibilityIdentifier("thread-toggle")
    }
}

// MARK: Another account's wallet

/// RN profile's wallet tab for another account (walletView, no dropdowns): "Unclaimed Rewards" as text and "Wallet
/// Details" (HIVE, staked HIVE with delegated / received / net, HBD, savings, next power down). Read only.
struct ProfileWalletTab: View {
    @StateObject private var observed: Observed<ProfileWalletState>
    @Environment(\.appTheme) private var theme

    init(username: String, env: AppEnvironment) {
        _observed = StateObject(wrappedValue: Observed(env.profileWalletModel(username: username), ownsModel: true))
    }

    private var model: ProfileWalletModel { observed.model as! ProfileWalletModel }

    var body: some View {
        let s = observed.state
        VStack(alignment: .leading, spacing: 12) {
            if s.loading {
                ProgressView().frame(maxWidth: .infinity).padding(24)
            } else if let error = s.error {
                ErrorStateView(error: error) { model.refresh() }
            } else {
                if let unclaimed = s.unclaimed {
                    card(ProfileWalletCopy.shared.UNCLAIMED_REWARDS) {
                        Text(unclaimed).scaledFont(14).foregroundStyle(theme.secondary).accessibilityIdentifier("profile-wallet-unclaimed")
                    }
                }
                card(ProfileWalletCopy.shared.WALLET_DETAILS) {
                    ForEach(Array(s.lines.enumerated()), id: \.offset) { _, line in
                        AdaptiveStack {
                            Text(line.label).scaledFont(14).foregroundStyle(theme.text)
                            Spacer(minLength: 0)
                            Text(line.value).scaledFont(line.detail ? 13 : 14, weight: line.detail ? .regular : .bold)
                                .foregroundStyle(line.net ? theme.accent : (line.detail ? theme.secondary : theme.text))
                        }
                        .padding(.vertical, line.detail ? 2 : 6)
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("wallet-line-\(line.label.isEmpty ? line.value : line.label)")
                    }
                    if let powerDown = s.powerDown { Text(powerDown).scaledFont(13).foregroundStyle(theme.secondary).padding(.top, 6) }
                }
            }
        }
        .padding(16)
        .onAppear { model.start() }
        .accessibilityIdentifier("profile-wallet")
    }

    private func card<C: View>(_ title: String, @ViewBuilder content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).scaledFont(16, weight: .bold).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
            content()
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 8))
    }
}
