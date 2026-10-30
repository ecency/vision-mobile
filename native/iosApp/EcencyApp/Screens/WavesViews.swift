import AVFoundation
import EcencyCore
import SwiftUI

/// The Waves tab's models, kept for the app's lifetime so the lists keep their place while another tab or a
/// wave's thread shows (RN's TabView keeps its scenes). The lists themselves belong to the shared tabs model.
@MainActor
final class WavesStore: ObservableObject {
    let tabs: Observed<WavesTabsState>
    private var feeds: [String: Observed<WavesFeedState>] = [:]
    private var reels: Observed<ShortsState>?

    init(env: AppEnvironment) {
        tabs = Observed(env.wavesTabsModel(), ownsModel: true)
    }

    var model: WavesTabsModel { tabs.model as! WavesTabsModel }

    /// The list of a feed slot; a slot whose model was replaced (another hashtag, an unpinned tab) gets a new one.
    func feed(_ key: String) -> Observed<WavesFeedState>? {
        guard let model = model.feed(key: key) else {
            feeds[key] = nil
            return nil
        }
        if let known = feeds[key], known.model === model { return known }
        let observed = Observed<WavesFeedState>(model, ownsModel: false)
        feeds[key] = observed
        return observed
    }

    func shorts() -> Observed<ShortsState> {
        let model = model.shorts()
        if let known = reels, known.model === model { return known }
        let observed = Observed<ShortsState>(model, ownsModel: false)
        reels = observed
        return observed
    }
}

/// The composer a sheet shows, with the account it was opened by.
struct WaveComposerRef: Identifiable {
    let model: WaveComposeModel
    var id: String { model.owner ?? "" }
}

/// RN waves screen: pill tabs with the "+" picker, the selected list or the Shorts reels, the hashtag chip and
/// the compose button.
struct WavesTabView: View {
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var session: Observed<SessionState>
    @ObservedObject var store: WavesStore
    @ObservedObject private var tabs: Observed<WavesTabsState>
    @State private var picker = false
    @State private var composer: WaveComposerRef?
    @State private var scrollTop = 0
    @State private var events: Closeable?
    @Environment(\.appTheme) private var theme
    @Environment(\.scenePhase) private var scenePhase
    /// The Waves tab is the one on screen (RN `isScreenFocused`): a check-in is only recorded then.
    @State private var onScreen = false

    /// RN: browsing waves is reading too, so it records the daily check-in (at most once per 15 minutes and
    /// account, the shared recorder's rule) and the getting-started card catches up.
    private func recordVisit() {
        guard session.state.username != nil else { return }
        _ = app.env.checkIn.record()
        app.env.wavesOnboarding.start()
        app.env.wavesOnboarding.refresh()
    }

    init(store: WavesStore) {
        self.store = store
        tabs = store.tabs
    }

    var body: some View {
        let state = tabs.state
        VStack(spacing: 0) {
            // RN WavesOnboardingChecklist, above the tabs; it shows nothing for established accounts
            if let username = session.state.username {
                WavesOnboardingCard(model: app.env.wavesOnboarding, username: username, onWave: { compose(nil) },
                                    onCheckIn: { app.push(AppScreenCompanion.shared.of(route: .perks, arg: "")) })
            }
            FilterPills(idPrefix: "waves-tab", titles: state.tabs.map { $0.title.uppercased() }, selected: state.tabs.firstIndex { $0.key == state.selected } ?? 0,
                        onSelect: { select(state.tabs[$0].key) },
                        onCustomise: {
                            picker = true
                            store.model.loadTrending()
                        })
            ZStack(alignment: .bottomTrailing) {
                if state.showsShorts {
                    ShortsReelsView(shorts: store.shorts())
                } else {
                    VStack(spacing: 0) {
                        if let tag = state.activeTag, state.selected == WavesTab.companion.FOR_YOU {
                            HStack {
                                Button { store.model.clearTag() } label: {
                                    HStack(spacing: 6) {
                                        Text("#\(tag)").scaledFont(14, weight: .bold)
                                        Image(appIcon: .close).scaledFont(11, weight: .bold)
                                    }
                                    .foregroundStyle(.white).padding(.horizontal, 12).padding(.vertical, 6)
                                    .background(theme.accent, in: Capsule())
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(L.Native.Ios.Waves.clearTagFilter(tag: tag))
                                .accessibilityIdentifier("waves-tag-chip")
                                Spacer()
                            }
                            .padding(.horizontal, 16).padding(.vertical, 6)
                        }
                        if let feed = store.feed(state.feedKey) {
                            WavesListView(feed: feed, scrollTop: scrollTop, onTag: { store.model.filterByTag(tag: $0) })
                                .id("\(state.feedKey)|\(state.activeTag ?? "")|\(session.state.username ?? "")")
                        }
                    }
                }
                if state.showsCompose {
                    FabButton(appIcon: .edit, label: L.Native.Ios.Waves.newWave, id: "wave-fab") { compose(nil) }
                }
            }
            .frame(maxHeight: .infinity)
        }
        .background(theme.background)
        .sheet(isPresented: $picker) { WavesPickerSheet(tabs: tabs, model: store.model).environment(\.appTheme, theme) }
        .sheet(item: $composer) { ref in
            WaveComposerSheet(composer: Observed<WaveComposeState>(ref.model, ownsModel: false), proxyBase: app.env.proxyBase, attacher: app.env.waves.attacher as? WaveUploadAttacher, ui: app.env.ui, env: app.env) { composer = nil }
                .environment(\.appTheme, theme)
        }
        // a composer belongs to the account that opened it: on any account change the sheet goes
        .onChange(of: session.state.username) { _, username in
            composer = nil
            if username != nil { openShared(); recordVisit() }
        }
        .onChange(of: scenePhase) { _, phase in if phase == .active && onScreen { recordVisit() } }
        .onAppear {
            openShared()
            onScreen = true
            recordVisit()
            if events == nil {
                events = app.env.watchEvents { event in
                    MainActor.assumeIsolated { if event is AppEventWaveComposeRequested { openShared() } }
                }
            }
        }
        .onDisappear {
            onScreen = false
            events?.close()
            events = nil
        }
    }

    private func select(_ key: String) {
        switch store.model.select(key: key) {
        case .needsLogin: app.sheet = .login
        case .scrollToTop: scrollTop += 1
        default: break
        }
    }

    /// RN: ecency.com/waves?text=... opens the composer with the text, once. Signed out, the text waits through the
    /// login this sends the reader to; a draft already in the composer is kept (the prefill is ignored).
    private func openShared() {
        guard app.env.waves.pendingComposeText != nil else { return }
        if !session.state.isLoggedIn {
            app.sheet = .login
        } else if let text = app.env.waves.takeComposeText() {
            compose(text)
        }
    }

    private func compose(_ text: String?) {
        guard session.state.isLoggedIn else {
            app.sheet = .login
            return
        }
        let model = app.env.waves.composer()
        if let text, !text.isEmpty { _ = model.prefill(text: text) }
        // a share into the app: its first image goes with the text (the composer holds one image)
        if let handle = app.env.share.takeImagesForWave(owner: model.owner).first {
            Task {
                if let image = await ShareInbox.prepare(handle, index: 0) { model.attachPrepared(image: image) } else { _ = app.env.ui.toast(text: ShareCopy.shared.INVALID_IMAGE) }
            }
        }
        // an open sheet keeps its place: it takes a new prefill through the state's prefill counter
        if composer?.model !== model { composer = WaveComposerRef(model: model) }
    }
}

/// One waves list: lazy rows, pull to refresh, infinite scroll, the minute refetch while on screen.
struct WavesListView: View {
    @ObservedObject var feed: Observed<WavesFeedState>
    let scrollTop: Int
    let onTag: (String) -> Void
    @Environment(\.motion) private var motion
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    private var model: WavesFeedModel { feed.model as! WavesFeedModel }

    var body: some View {
        let state = feed.state
        GeometryReader { geo in
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 0) {
                        Color.clear.frame(height: 0).id("waves-top")
                        if state.needsLogin {
                            LoginPromptView(text: ReadingUiCopy.shared.LOGIN_TO_SEE) { app.sheet = .login }.padding(.top, 40)
                        } else if state.items.isEmpty {
                            if let error = state.error {
                                ErrorStateView(error: error) { model.retry() }.padding(.top, 80)
                            } else if state.loaded {
                                Text(UiCopy.shared.NOTHING_HERE).foregroundStyle(theme.secondary).accessibilityIdentifier("waves-empty").esieAbove(.empty).padding(.top, 80)
                            } else {
                                ProgressView().padding(.top, 80).accessibilityIdentifier("waves-loading")
                            }
                        }
                        ForEach(state.items, id: \.key) { item in
                            WaveRowView(item: item, width: geo.size.width, onTag: onTag)
                                .onAppear { if WavesPaging.shouldLoadMore(appearing: item.key, in: state) { model.loadMore() } }
                            Rectangle().fill(theme.lightBackground).frame(height: 1)
                        }
                        if !state.items.isEmpty {
                            Color.clear.frame(height: 1).id("page-\(state.pages)")
                                .onAppear { if WavesPaging.canLoadMore(state) { model.loadMore() } }
                            if let error = state.error {
                                InlineErrorRow(error: error) { model.retry() }
                            } else if state.isLoadingMore {
                                ProgressView().padding(24)
                            }
                            Color.clear.frame(height: 80)
                        }
                    }
                    .reportsScrollOffset("waves-scroll")
                }
                .refreshable { try? await model.refreshAndWait() }
                // RN wavesScreen's ScrollTopPopup: also hidden on scroll down
                .scrollTopPopup(space: "waves-scroll", hidesOnScrollDown: true, id: "waves-scroll-top") { withAnimation(motion.scroll) { proxy.scrollTo("waves-top", anchor: .top) } }
                .onChange(of: scrollTop) { _, _ in withAnimation(motion.scroll) { proxy.scrollTo("waves-top", anchor: .top) } }
                // a wave just published from here goes on top: show it
                .onChange(of: state.items.first?.isLocal == true ? state.items.first?.key : nil) { _, key in
                    if key != nil { proxy.scrollTo("waves-top", anchor: .top) }
                }
            }
        }
        .speakPlayersScope()
        .background(theme.background)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("waves-list")
        .onAppear {
            model.start()
            model.setActive(active: true)
        }
        .onDisappear { model.setActive(active: false) }
    }
}

/// The profile's Waves tab (RN wavesTabContent): the author's waves as rows of the profile's own lazy list.
struct ProfileWavesTab: View {
    @StateObject private var feed: Observed<WavesFeedState>
    let username: String
    let width: CGFloat
    @Environment(\.appTheme) private var theme

    init(model: WavesFeedModel, username: String, width: CGFloat) {
        _feed = StateObject(wrappedValue: Observed<WavesFeedState>(model, ownsModel: false))
        self.username = username
        self.width = width
    }

    private var model: WavesFeedModel { feed.model as! WavesFeedModel }

    var body: some View {
        let state = feed.state
        Group {
            if state.items.isEmpty {
                if let error = state.error {
                    ErrorStateView(error: error) { model.retry() }.padding(.top, 32)
                } else if state.loaded {
                    Text(UiCopy.shared.userHasNoPosts(username: username)).foregroundStyle(theme.secondary)
                        .accessibilityIdentifier("profile-waves-empty").esieAbove(.empty).padding(.top, 32)
                } else {
                    ProgressView().padding(.top, 32)
                }
            }
            ForEach(state.items, id: \.key) { item in
                WaveRowView(item: item, width: width)
                    .onAppear { if WavesPaging.shouldLoadMore(appearing: item.key, in: state) { model.loadMore() } }
                Rectangle().fill(theme.lightBackground).frame(height: 1)
            }
            if !state.items.isEmpty {
                Color.clear.frame(height: 1).id("waves-page-\(state.pages)")
                    .onAppear { if WavesPaging.canLoadMore(state) { model.loadMore() } }
                if let error = state.error {
                    InlineErrorRow(error: error) { model.retry() }
                } else if state.isLoadingMore {
                    ProgressView().padding(24)
                }
            }
        }
        .onAppear { model.start() }
    }
}

/// When a waves list asks for the next page. Pure, unit tested.
enum WavesPaging {
    static let threshold = 3

    static func canLoadMore(_ state: WavesFeedState) -> Bool {
        state.loaded && !state.endReached && !state.isLoadingMore && state.error == nil
    }

    static func shouldLoadMore(appearing key: PostKey, in state: WavesFeedState) -> Bool {
        guard canLoadMore(state), let index = state.items.lastIndex(where: { $0.key == key }) else { return false }
        return index >= state.items.count - threshold
    }
}

/// One wave (RN's comment view for a wave): avatar, name, age, the body, vote and payout, replies, reply, tip,
/// options. The reply count opens the thread.
struct WaveRowView: View {
    let item: WaveItem
    let width: CGFloat
    /// A hashtag tapped inside the wave (the Waves tab filters For you); nil lets it open like any tag link.
    var onTag: ((String) -> Void)?
    @Environment(\.dynamicTypeSize) private var typeSize
    @EnvironmentObject private var app: AppContainer
    @State private var revealed = false
    @Environment(\.appTheme) private var theme

    static let bodyInset: CGFloat = 46

    private var actions: PostActions {
        PostActions(
            onLink: { target in
                // a hashtag inside a wave filters For you (RN onTagPress); every other link goes where links go
                if let onTag, let feed = LinkRouter.shared.route(target: target) as? RouteFeed, let ranked = feed.source as? FeedSourceRanked,
                   !ranked.tag.isEmpty, !ranked.tag.hasPrefix("hive-") {
                    onTag(ranked.tag)
                } else {
                    app.handleBodyLink(target)
                }
            },
            onImage: { url, all in app.sheet = .gallery(all.isEmpty ? [url] : all, all.firstIndex(of: url) ?? 0) },
            showImages: app.notifications.state.showImages
        )
    }

    /// The body actions of a covered wave: every image and video thumbnail is a placeholder.
    private var covered: PostActions {
        var a = actions
        a.showImages = false
        return a
    }

    var body: some View {
        let card = item.card
        let hidden = card.isNsfw && !revealed && !app.env.mutedReveals.isRevealed(key: item.key)
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 10) {
                Button { app.openQuickProfile(card.author) } label: { AvatarView(url: card.avatarUrl, size: 36) }
                    .buttonStyle(.plain)
                    .accessibilityLabel(UiCopy.shared.profileOf(username: card.author))
                    .accessibilityIdentifier("wave-avatar")
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Button { app.openQuickProfile(card.author) } label: {
                            Text(card.author).scaledFont(15, weight: .bold).foregroundStyle(theme.text).lineLimit(1)
                                .frame(minHeight: minTapTarget).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        // VoiceOver: one stop for the author and age, the wave's actions in its actions rotor
                        .accessibilityLabel([card.author, CardText.age(card)].joined(separator: ", "))
                        .accessibilityAction(named: Text(L.Native.Ios.Waves.repliesOpenThread(count: Int(card.comments)))) { app.openPost(item.key) }
                        .accessibilityAction(named: Text(ReadingUiCopy.shared.REPLY)) {
                            if app.requireSignIn(to: .reply) { app.sheet = .reply(ReplyRequest(root: item.key, parent: item.post)) }
                        }
                        .accessibilityAction(named: Text(ReadingUiCopy.shared.POST_OPTIONS)) {
                            app.openMenu(item.post, context: PostMenuContext(translate: true, openThread: false, ownProfile: false), root: item.key)
                        }
                    }
                    HStack(spacing: 6) {
                        Text(CardText.age(card)).scaledFont(13, relativeTo: .footnote).foregroundStyle(theme.secondary).accessibilityIdentifier("wave-age")
                            .accessibilityHidden(true)
                        // RN commentView (inlineTime): the source badge follows the time
                        if item.isFromEcency { EcencySourceBadge() }
                        if item.isLocal {
                            Text(L.Schedules.pending).scaledFont(11, weight: .semibold, relativeTo: .caption).foregroundStyle(theme.secondary)
                                .padding(.horizontal, 6).padding(.vertical, 2).background(theme.lightBackground, in: Capsule())
                                .accessibilityIdentifier("wave-local")
                        }
                    }
                }
                Spacer(minLength: 0)
                if item.isPromoted {
                    Text(ReadingUiCopy.shared.PROMOTED).scaledFont(11, weight: .bold, relativeTo: .caption).foregroundStyle(theme.secondary)
                }
                MenuButton {
                    // RN PostOptionsModal isWave: translate offered; a wave is a comment, so no pin to blog
                    app.openMenu(item.post, context: PostMenuContext(translate: true, openThread: false, ownProfile: false), root: item.key)
                }
            }
            if hidden {
                // RN #3586: the text stays readable, the media is covered until this wave is revealed
                Button {
                    app.env.mutedReveals.reveal(key: item.key)
                    revealed = true
                } label: {
                    Text(UiCopy.shared.NSFW_TAP_TO_REVEAL).scaledFont(13, relativeTo: .footnote).foregroundStyle(theme.secondary)
                }
                .buttonStyle(.plain)
                .padding(.leading, Self.bodyInset)
                .accessibilityIdentifier("wave-nsfw")
            }
            PostBodyView(blocks: item.blocks, contentWidth: width - 32 - Self.bodyInset, actions: hidden ? covered : actions)
                .padding(.leading, Self.bodyInset)
                .contentShape(Rectangle())
                .onTapGesture { app.openPost(item.key) }
            footer(card)
            .padding(.leading, Self.bodyInset - 4)
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("wave-\(item.key.author)-\(item.key.permlink)")
    }

    /// The vote, counts, tip and reply: one row while it fits (tip and reply at the end), wrapping lines at large
    /// text sizes so the card never gets wider than the screen.
    private func footer(_ card: PostCard) -> some View {
        // chosen by the text size, the same views in either layout (the vote button keeps its state)
        let wraps = AccessibleFooter.wraps(typeSize, width: width)
        return (wraps ? AnyLayout(ChipFlow(spacing: 14)) : AnyLayout(HStackLayout(spacing: 14))) {
            footerCounts(card)
            if !wraps { Spacer() }
            footerActions(card)
        }
    }

    @ViewBuilder private func footerCounts(_ card: PostCard) -> some View {
        VoteButton(key: card.key, votedByMe: card.votedByMe, downvotedByMe: card.downvotedByMe,
                   payoutText: card.payoutText, payoutDeclined: card.isPayoutDeclined, iconSize: 18, payoutPost: { item.post })
        CountLabel(appIcon: .heart, count: card.votes)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(L.Post.a11yVotes(count: Int(card.votes)))
        Button { app.openPost(item.key) } label: { CountLabel(appIcon: .comment, count: card.comments).frame(minHeight: minTapTarget) }
            .buttonStyle(.plain)
            .accessibilityLabel(L.Native.Ios.Waves.repliesOpenThread(count: Int(card.comments)))
            .accessibilityIdentifier("wave-thread")
    }

    @ViewBuilder private func footerActions(_ card: PostCard) -> some View {
        TipButton(key: card.key)
        Button(ReadingUiCopy.shared.REPLY) {
            if app.requireSignIn(to: .reply) { app.sheet = .reply(ReplyRequest(root: item.key, parent: item.post)) }
        }
        .scaledFont(13, relativeTo: .footnote)
        .frame(minWidth: minTapTarget, minHeight: minTapTarget)
        .contentShape(Rectangle())
        .accessibilityLabel(L.Native.Ios.Reading.replyTo(username: card.author))
        .accessibilityIdentifier("wave-reply")
    }
}

/// RN wavesTagPickerModal: "Custom feeds".
struct WavesPickerSheet: View {
    @ObservedObject var tabs: Observed<WavesTabsState>
    let model: WavesTabsModel
    @State private var input = ""
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appTheme) private var theme

    var body: some View {
        let state = tabs.state
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(ReadingUiCopy.shared.CUSTOM_FEEDS_SUBTITLE).scaledFont(14, relativeTo: .subheadline).foregroundStyle(theme.secondary)
                    HStack(spacing: 8) {
                        Text("#").scaledFont(18, weight: .bold).foregroundStyle(theme.secondary)
                        TextField(ReadingUiCopy.shared.ADD_A_TAG, text: $input)
                            .textInputAutocapitalization(.never).autocorrectionDisabled().submitLabel(.done)
                            .onSubmit(submit)
                            .accessibilityIdentifier("waves-tag-input")
                        Button(action: submit) {
                            Image(appIcon: .add).scaledFont(16, weight: .bold).foregroundStyle(.white)
                                .frame(width: 36, height: 36).background(model.canAdd(raw: input) ? theme.accent : theme.icon, in: Circle())
                                .tapTarget()
                        }
                        .disabled(!model.canAdd(raw: input))
                        .accessibilityLabel(ReadingUiCopy.shared.ADD_TAG)
                        .largeContent(ReadingUiCopy.shared.ADD_TAG, appIcon: .add)
                        .accessibilityIdentifier("waves-tag-add")
                    }
                    section(ReadingUiCopy.shared.SOURCES)
                    ChipFlow {
                        ForEach(Waves.shared.SOURCE_OPTIONS, id: \.host) { source in
                            chip(source.label, active: state.pinnedContainers.contains(source.host), id: "waves-source-\(source.host)") {
                                model.toggleContainer(host: source.host)
                            }
                        }
                    }
                    if !state.pinnedTags.isEmpty {
                        section(ReadingUiCopy.shared.YOUR_FEEDS)
                        ChipFlow {
                            ForEach(state.pinnedTags, id: \.self) { tag in
                                chip("#\(tag)", active: true, id: "waves-pinned-\(tag)") { model.removeTag(tag: tag) }
                            }
                        }
                    }
                    if !state.trendingTags.isEmpty {
                        section(ReadingUiCopy.shared.TRENDING_TAGS)
                        ChipFlow {
                            ForEach(state.trendingTags, id: \.self) { tag in
                                chip("#\(tag)", active: false, id: "waves-trending-\(tag)") { model.addTag(raw: tag) }
                            }
                        }
                    }
                }
                .padding(16)
            }
            .background(theme.background)
            .navigationTitle(ReadingUiCopy.shared.CUSTOM_FEEDS)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button(UiCopy.shared.DONE) { dismiss() }.accessibilityIdentifier("waves-picker-done") } }
        }
        .presentationDetents([.medium, .large])
    }

    private func submit() {
        model.addTag(raw: input)
        input = ""
    }

    private func section(_ title: String) -> some View {
        Text(title).scaledFont(14, weight: .bold, relativeTo: .subheadline).foregroundStyle(theme.secondary).padding(.top, 4)
            .accessibilityAddTraits(.isHeader)
    }

    private func chip(_ label: String, active: Bool, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Text(label).scaledFont(14, relativeTo: .subheadline)
                Image(appIcon: active ? .close : .add).scaledFont(10, weight: .bold)
            }
            .foregroundStyle(active ? .white : theme.text)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(active ? theme.accent : theme.lightBackground, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(active ? L.Native.Ios.Feed.tabPinned(label: label) : L.Native.Ios.Feed.tabAdd(label: label))
        .accessibilityIdentifier(id)
    }
}

/// Chips that wrap onto as many lines as they need. A chip wider than the row (a long tag at a large text size)
/// gets the row's width and wraps its own text instead of running off screen.
struct ChipFlow: Layout {
    var spacing: CGFloat = 8

    static func size(of view: LayoutSubview, in width: CGFloat) -> CGSize {
        let natural = view.sizeThatFits(.unspecified)
        guard width.isFinite, natural.width > width else { return natural }
        return view.sizeThatFits(ProposedViewSize(width: width, height: nil))
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0
        for view in subviews {
            let size = Self.size(of: view, in: width)
            if x > 0, x + size.width > width {
                x = 0
                y += row + spacing
                row = 0
            }
            x += size.width + spacing
            row = max(row, size.height)
        }
        return CGSize(width: proposal.width ?? x, height: y + row)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, row: CGFloat = 0
        for view in subviews {
            let size = Self.size(of: view, in: bounds.width)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += row + spacing
                row = 0
            }
            view.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            row = max(row, size.height)
        }
    }
}

/// RN quick post modal in wave mode: "Publishing in Waves", the counter, avatar and name, the text, the short
/// content hint, the attached image and PUBLISH. The sheet cannot be dismissed while a publish runs.
struct WaveComposerSheet: View {
    @StateObject var composer: Observed<WaveComposeState>
    let proxyBase: String
    let onClose: () -> Void
    @FocusState private var focused: Bool
    /// The editor's own text. Reading it back from the watched state loses keystrokes (the state arrives a
    /// main-queue turn after `setText`, and the editor would be reset to a stale value), as the reply sheet found.
    @State private var text: String
    @Environment(\.appTheme) private var theme
    @Environment(\.motion) private var motion
    /// The editor's upload behind the image buttons (nil: not wired, the model says so).
    let attacher: WaveUploadAttacher?
    let ui: UiMessages?
    /// The picker the shared attacher asked for; one image a wave, as RN.
    @State private var picker: WavePicker?
    /// A picked image is still being read and prepared: closing the picker sheet is not a cancel.
    @State private var readingPick = false
    /// The Files importer while it is open for the wave's image (RN #3561: waves had no files route).
    @State private var files: MediaSurface?
    @State private var pollSheet = false
    /// RN SpeakUploaderModal isShort: the editor's upload sheet over the composer's own upload model (which the
    /// composer keeps, so a failed upload can be continued after the sheet was closed).
    @State private var videoUpload: Observed<VideoUploadState>?

    private enum WavePicker: String, Identifiable {
        case photos, camera
        var id: String { rawValue }
    }

    /// RN quick post: dictation and AI assist, the editor's sheets over this composer.
    private enum AiSheet: String, Identifiable {
        case assist, dictation
        var id: String { rawValue }
    }

    @State private var aiSheet: AiSheet?
    /// For the dictation and AI assist models (nil: those buttons are not shown).
    let env: AppEnvironment?
    @Environment(\.scenePhase) private var scenePhase

    init(composer: Observed<WaveComposeState>, proxyBase: String, attacher: WaveUploadAttacher? = nil, ui: UiMessages? = nil, env: AppEnvironment? = nil, onClose: @escaping () -> Void) {
        _composer = StateObject(wrappedValue: composer)
        self.proxyBase = proxyBase
        self.attacher = attacher
        self.ui = ui
        self.env = env
        self.onClose = onClose
        _text = State(initialValue: composer.state.text)
    }

    /// RN hasClipboardImage: `hasImages` reads no content, so iOS shows no paste notice for the check.
    private func checkClipboard() { model.setClipboardHasImage(has: UIPasteboard.general.hasImages) }

    private var model: WaveComposeModel { composer.model as! WaveComposeModel }

    var body: some View {
        let state = composer.state
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(ReadingUiCopy.shared.PUBLISHING_IN_WAVES).scaledFont(14, relativeTo: .subheadline).foregroundStyle(theme.secondary)
                Spacer()
                Text(state.counter).scaledFont(13, relativeTo: .footnote)
                    .foregroundStyle(state.lengthExceeded ? theme.dangerText : theme.secondary)
                    .accessibilityLabel(L.Native.Ios.Waves.characters(count: Fmt.int(state.length), max: Fmt.int(250)))
                    .accessibilityIdentifier("wave-counter")
                Button(action: onClose) { Image(appIcon: .close).scaledFont(15, weight: .semibold).foregroundStyle(theme.icon).tapTarget() }
                    .disabled(state.isSending)
                    .accessibilityLabel(UiCopy.shared.CLOSE)
                    .largeContent(UiCopy.shared.CLOSE, appIcon: .close)
                    .accessibilityIdentifier("wave-close")
            }
            HStack(spacing: 8) {
                AvatarView(url: PostCard.companion.avatarUrl(account: model.owner ?? "", proxyBase: proxyBase, size: "small"), size: 32)
                Text("@\(model.owner ?? "")").scaledFont(14, weight: .bold).foregroundStyle(theme.text)
            }
            ZStack(alignment: .topLeading) {
                TextEditor(text: $text)
                    .onChange(of: text) { model.setText(text: text) }
                    .focused($focused)
                    .disabled(state.isSending)
                    // fills the sheet down to the toolbar (which sits on the keyboard), no empty stripe between them
                    .frame(minHeight: 110, maxHeight: .infinity)
                    .scrollContentBackground(.hidden)
                    .accessibilityLabel(ReadingUiCopy.shared.WHATS_HAPPENING)
                    .accessibilityIdentifier("wave-text")
                if text.isEmpty {
                    // the secondary token instead of RN's light gray, for a readable hint (WCAG AA)
                    Text(ReadingUiCopy.shared.WHATS_HAPPENING).foregroundStyle(theme.secondary)
                        .padding(.top, 8).padding(.leading, 5).allowsHitTesting(false).accessibilityHidden(true)
                }
            }
            if state.showsShortHint {
                Text(ReadingUiCopy.shared.shortContentHint(n: WavePublish.shared.QUEST_MIN_CONTENT_LENGTH))
                    .scaledFont(12, relativeTo: .caption).foregroundStyle(theme.secondary)
                    .accessibilityIdentifier("wave-short-hint")
            }
            if state.isVideoUploading {
                ProgressView().frame(width: 72, height: 72).background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 8))
                    .accessibilityIdentifier("wave-video-uploading")
            }
            if state.videoEmbedUrl != nil {
                // RN _videoPreview: "Video attached" with the minus badge; a tap takes the video off. The image hides.
                Button { model.removeVideo() } label: {
                    VStack(spacing: 4) {
                        Image(appIcon: .videoAttached, filled: true).scaledFont(24).foregroundStyle(theme.accentText)
                        Text(ReadingUiCopy.shared.VIDEO_ATTACHED).scaledFont(10, relativeTo: .caption2).foregroundStyle(theme.secondary)
                    }
                    .frame(width: 72, height: 72).background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 8))
                    .overlay(alignment: .topTrailing) {
                        Image(appIcon: .remove, filled: true).foregroundStyle(.white, .red).padding(2)
                    }
                }
                .buttonStyle(.plain)
                .disabled(state.isSending || state.isVideoUploading)
                .accessibilityLabel(L.Native.Ios.Waves.videoAttachedRemove)
                .accessibilityIdentifier("wave-video")
            } else if state.isAttaching {
                ProgressView().frame(width: 72, height: 72).background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 8))
            } else if let image = state.imageUrl {
                Button { model.removeImage() } label: {
                    RemoteImage(url: image).frame(width: 72, height: 72).clipShape(RoundedRectangle(cornerRadius: 8))
                        .overlay(alignment: .topTrailing) {
                            Image(appIcon: .remove, filled: true).foregroundStyle(.white, .red).padding(2)
                        }
                }
                .buttonStyle(.plain)
                .disabled(state.isSending)
                .accessibilityLabel(L.Native.Ios.Waves.imageAttachedRemove)
                .accessibilityIdentifier("wave-image")
            }
            if state.clipboardChip {
                // RN clipboard chip: "Image in clipboard · Paste image" and its close button
                HStack(spacing: 0) {
                    Button { model.pasteClipboardImage() } label: {
                        Text(WaveComposeCopy.shared.CLIPBOARD_CHIP).scaledFont(13, relativeTo: .footnote).foregroundStyle(theme.text)
                            .padding(.leading, 12).padding(.trailing, 4).padding(.vertical, 6)
                    }
                    .buttonStyle(.plain)
                    .disabled(state.isSending || state.isAttaching)
                    .accessibilityIdentifier("wave-paste-image")
                    Button { model.dismissClipboardChip() } label: {
                        Image(appIcon: .close).scaledFont(13, weight: .semibold).foregroundStyle(theme.text).padding(.horizontal, 10).padding(.vertical, 6)
                            .frame(minWidth: minTapTarget, minHeight: minTapTarget).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(WaveComposeCopy.shared.CLIPBOARD_DISMISS)
                    .accessibilityIdentifier("wave-clipboard-close")
                }
                .background(theme.lightBackground, in: Capsule())
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("wave-clipboard-chip")
            }
            HStack(spacing: 8) {
              // the content buttons scroll sideways when they do not fit next to PUBLISH (as on Android)
              ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 0) {
                Button { model.attach(source: .gallery) } label: { Image(appIcon: .image).scaledFont(20).tapTarget() }
                    .accessibilityLabel(UiCopy.shared.ATTACH_IMAGE)
                    .accessibilityIdentifier("wave-attach")
                Button { model.attach(source: .camera) } label: { Image(appIcon: .camera).scaledFont(20).tapTarget() }
                    .accessibilityLabel(L.Native.Ios.Waves.takePhoto)
                Button { model.attach(source: .files) } label: { Image(appIcon: .folder).scaledFont(20).tapTarget() }
                    .accessibilityLabel(MediaPickCopy.shared.FILES)
                    .accessibilityIdentifier("wave-files")
                // RN's poll button (the editor's poll wizard); tinted once a poll is attached
                Button { pollSheet = true } label: {
                    Image(appIcon: .chartBar).scaledFont(20).foregroundStyle(state.poll != nil ? theme.accent : theme.text).tapTarget()
                }
                .accessibilityLabel(state.poll != nil ? L.Native.Ios.Waves.editPoll : L.Native.Ios.Waves.addPoll)
                .accessibilityIdentifier("wave-poll")
                // RN video-outline: tinted and off while a video is attached or on its way
                Button {
                    videoUpload = Observed(model.video(), ownsModel: false)
                } label: { Image(appIcon: .video).scaledFont(20).foregroundStyle(state.videoEmbedUrl != nil ? theme.accent : theme.text).tapTarget() }
                    .disabled(!state.canAddVideo)
                    .accessibilityLabel(L.Native.Ios.Waves.addVideo)
                    .accessibilityIdentifier("wave-video-button")
                    .sheet(isPresented: Binding(get: { videoUpload != nil }, set: { if !$0 { videoUpload = nil } })) {
                        if let videoUpload { VideoUploadSheet(video: videoUpload) }
                    }
                if env != nil {
                    // RN: dictation with the content buttons, then the AI pair
                    Button { aiSheet = .dictation } label: { Image(appIcon: .mic).scaledFont(20).tapTarget() }
                        .accessibilityLabel(WaveComposeCopy.shared.DICTATE)
                        .accessibilityIdentifier("wave-dictate")
                    Button { aiSheet = .assist } label: { Image(appIcon: .aiAssist).scaledFont(20).tapTarget() }
                        .accessibilityLabel(WaveComposeCopy.shared.AI_ASSIST)
                        .accessibilityIdentifier("wave-ai-assist")
                }
                }
                .padding(.vertical, 0)
              }
                // PUBLISH turns into its own progress while the wave goes out (Motion.swift)
                Button { model.send() } label: {
                    SendContent(sending: state.isSending, sent: state.sent.map { AnyHashable($0) }, holdsDone: false) {
                        Text(ReadingUiCopy.shared.PUBLISH).scaledFont(14, weight: .bold).foregroundStyle(.white)
                            .fixedSize()
                    }
                    .padding(.horizontal, 16).padding(.vertical, 9)
                    .background(state.publishEnabled || state.isSending ? theme.accent : theme.icon, in: Capsule())
                    .frame(minHeight: minTapTarget).contentShape(Rectangle())
                    .animation(motion.fade(), value: state.publishEnabled)
                }
                .buttonStyle(.plain)
                .disabled(!state.publishEnabled)
                .accessibilityIdentifier("wave-publish")
            }
            .foregroundStyle(theme.text)
            .disabled(state.isSending || state.isAttaching)
        }
        .padding(.horizontal, 16).padding(.top, 16).padding(.bottom, 8)
        // the sheet's own background, keyboard area included: no see-through stripe above the keyboard
        .background(theme.background.ignoresSafeArea())
        .presentationBackground(theme.background)
        .presentationDetents([.medium, .large])
        .interactiveDismissDisabled(state.isSending)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("wave-composer")
        .sheet(item: $aiSheet) { which in
            if let env {
                switch which {
                case .assist: AiAssistSheet(editor: model, env: env).environment(\.appTheme, theme)
                case .dictation: DictationSheet(editor: model, env: env).environment(\.appTheme, theme)
                }
            }
        }
        .onChange(of: scenePhase) { _, phase in if phase == .active { checkClipboard() } }
        .onAppear {
            focused = true
            checkClipboard()
            attacher?.onPick = { source in
                // the camera only where there is one (not on the simulator): otherwise the attach just ends
                if source == .clipboard {
                    // RN _handlePasteImageBtn: the clipboard's image, prepared like a picked one
                    guard let data = UIPasteboard.general.image?.jpegData(compressionQuality: 1) else {
                        _ = ui?.toast(text: WaveComposeCopy.shared.CLIPBOARD_EMPTY)
                        attacher?.picked(image: nil)
                        return
                    }
                    Task.detached(priority: .userInitiated) {
                        let image = EditorImages.prepare(data: data, name: "clipboard.jpg", index: 0)
                        await MainActor.run { attacher?.picked(image: image) }
                    }
                } else if source == .files {
                    files = .wave
                } else if source == .camera, !CameraPicker.isAvailable { attacher?.picked(image: nil) } else { picker = source == .camera ? .camera : .photos }
            }
        }
        .onDisappear {
            attacher?.onPick = nil
            attacher?.picked(image: nil)
        }
        .sheet(item: $picker, onDismiss: { if !readingPick { attacher?.picked(image: nil) } }) { which in
            let results: (Int) -> Void = { count in readingPick = count > 0 }
            let picked: ([PickedImage]) -> Void = { images in
                readingPick = false
                picker = nil
                if images.isEmpty { _ = ui?.toast(text: UiCopy.shared.INVALID_IMAGE) }
                attacher?.picked(image: images.first)
            }
            switch which {
            case .photos: PhotoPicker(limit: 1, onResults: results, onPicked: picked).ignoresSafeArea()
            case .camera: CameraPicker(onResults: results, onPicked: picked).ignoresSafeArea()
            }
        }
        // an unsupported file is said by the importer; nothing picked ends the attach
        .mediaFileImporter(surface: $files) { _, images in attacher?.picked(image: images.first) }
        .sheet(isPresented: $pollSheet) {
            PollSheet(current: state.poll, onAttach: { model.setPoll(poll: $0); pollSheet = false }, onReset: { model.setPoll(poll: nil); pollSheet = false })
                .environment(\.appTheme, theme)
        }
        .onChange(of: state.sent) { _, sent in if sent != nil { onClose() } }
        // a share link while the sheet is open: the model took the text, the editor follows
        .onChange(of: state.prefillSeq) { _, _ in text = composer.state.text }
        // RN: a publish HiveSigner refused for the missing ecency.app authority offers the grant; closing it is a skip
        .postingAuthorityPrompt(isPresented: state.authorityPrompt, onAnswer: { model.answerAuthorityPrompt(grant: $0) }, onDismiss: { model.answerAuthorityPrompt(grant: false) })
        .alert(state.errorTitle, isPresented: Binding(get: { composer.state.error != nil }, set: { if !$0 { model.clearError() } })) {
            Button(UiCopy.shared.OK) { model.clearError() }
        } message: {
            Text(state.error?.message ?? "")
        }
    }
}

/// An image through the app's image pipeline, cropped to fill.
private struct RemoteImage: View {
    let url: String

    var body: some View {
        AsyncImage(url: URL(string: url)) { phase in
            if let image = phase.image { image.resizable().scaledToFill() } else { Color.gray.opacity(0.2) }
        }
    }
}

// MARK: - Shorts

/// How a reel's stream is drawn. Debug fake-data runs (UI tests, screenshots) swap the AVPlayer view for a
/// labelled stand-in, so nothing reaches the network and tests can see which reel has a player.
enum ReelPlayers {
    static var useStandIn: Bool {
        #if DEBUG
        return UserDefaults.standard.bool(forKey: "fakeData")
        #else
        return false
        #endif
    }
}

/// RN wavesReelsView: full-height reels, one page each. Only `playingKey` has a player: the reel in view while
/// this tab is on screen and the scene is active. Leaving the tab, a screen on top, the background or the app
/// lock take the view (or the focus) away, which tears the player down.
struct ShortsReelsView: View {
    @ObservedObject var shorts: Observed<ShortsState>
    @EnvironmentObject private var app: AppContainer
    @Environment(\.scenePhase) private var scenePhase
    @State private var position: PostKey?
    @State private var visible = false

    private var model: ShortsModel { shorts.model as! ShortsModel }

    var body: some View {
        let state = shorts.state
        GeometryReader { geo in
            ZStack {
                Color.black
                if state.items.isEmpty {
                    if let error = state.error {
                        Button(L.Native.Ios.Waves.retry(message: error.message)) { model.retry() }.foregroundStyle(.white)
                    } else if state.loaded {
                        Text(ShortsModel.companion.EMPTY).foregroundStyle(.white).accessibilityIdentifier("shorts-empty")
                    } else {
                        ProgressView().tint(.white)
                    }
                } else {
                    ScrollView(.vertical, showsIndicators: false) {
                        LazyVStack(spacing: 0) {
                            ForEach(state.items, id: \.key) { item in
                                ReelView(item: item, state: state, model: model)
                                    .frame(width: geo.size.width, height: geo.size.height)
                                    .id(item.key)
                                    .onAppear { if item.key == state.items.suffix(2).first?.key { model.loadMore() } }
                            }
                        }
                        .scrollTargetLayout()
                    }
                    .scrollTargetBehavior(.paging)
                    .scrollPosition(id: $position)
                    .refreshable { try? await model.refreshAndWait() }
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("shorts")
        .onAppear {
            visible = true
            model.start()
            focus()
            activate(state)
        }
        .onDisappear {
            visible = false
            model.setFocused(focused: false)
        }
        .onChange(of: scenePhase) { _, _ in focus() }
        .onChange(of: position) { _, _ in activate(shorts.state) }
        .onChange(of: state.items.count) { _, _ in activate(shorts.state) }
    }

    private func focus() { model.setFocused(focused: visible && scenePhase == .active) }

    /// The reel the pager settled on; before any scroll, the first one.
    private func activate(_ state: ShortsState) {
        let key = position.flatMap { p in state.items.first { $0.key == p }?.key } ?? state.items.first?.key
        model.setActive(key: key)
    }
}

/// One reel (RN wavesReelItem): the video or its poster, author and caption bottom left, the upvote, reply and
/// tip rail bottom right.
struct ReelView: View {
    let item: ShortItem
    let state: ShortsState
    let model: ShortsModel
    @EnvironmentObject private var app: AppContainer

    var body: some View {
        let playing = state.playingKey == item.key
        let playback = state.playbackOf(key: item.key)
        ZStack {
            Color.black
            if playing, let stream = playback as? ShortPlaybackStream, let url = URL(string: stream.url) {
                if ReelPlayers.useStandIn {
                    Text(L.Native.Ios.Waves.playing(permlink: item.key.permlink)).foregroundStyle(.white).accessibilityIdentifier("reel-player")
                } else {
                    ReelPlayerView(url: url, muted: state.muted) { model.playbackFailed(key: item.key) }
                        .accessibilityIdentifier("reel-player")
                }
            } else if playing, let web = playback as? ShortPlaybackWeb, let url = URL(string: web.url) {
                EmbedWebView(url: url).accessibilityIdentifier("reel-web")
            } else {
                if let thumb = item.thumbnailUrl, let url = URL(string: thumb) {
                    AsyncImage(url: url) { phase in
                        if let image = phase.image { image.resizable().scaledToFit() } else { Color.black }
                    }
                }
                if !playing {
                    Image(appIcon: .playCircle).resizable().scaledToFit().frame(width: 56, height: 56).foregroundStyle(.white.opacity(0.9)).accessibilityHidden(true)
                }
            }
        }
        .overlay(alignment: .topTrailing) {
            if playing, playback is ShortPlaybackStream {
                Button { model.toggleMute() } label: {
                    Image(appIcon: state.muted ? .muted : .soundOn, filled: true).foregroundStyle(.white)
                        .frame(width: 40, height: 40).background(.black.opacity(0.4), in: Circle())
                }
                .padding(12)
                .accessibilityLabel(state.muted ? ReadingUiCopy.shared.UNMUTE : ReadingUiCopy.shared.MUTE)
                .accessibilityIdentifier("reel-mute")
            }
        }
        .overlay(alignment: .bottomLeading) {
            VStack(alignment: .leading, spacing: 6) {
                Button { app.openQuickProfile(item.card.author) } label: {
                    HStack(spacing: 8) {
                        AvatarView(url: item.card.avatarUrl, size: 36)
                        Text("@\(item.card.author)").scaledFont(15, weight: .bold).foregroundStyle(.white)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("reel-author")
                if !item.caption.isEmpty {
                    Text(item.caption).scaledFont(14, relativeTo: .subheadline).foregroundStyle(.white).lineLimit(2)
                        .accessibilityIdentifier("reel-caption")
                }
            }
            .padding(.leading, 16).padding(.bottom, 24).padding(.trailing, 88)
        }
        .overlay(alignment: .bottomTrailing) {
            VStack(spacing: 18) {
                VoteButton(key: item.key, votedByMe: item.card.votedByMe, downvotedByMe: item.card.downvotedByMe,
                           payoutText: item.card.payoutText, payoutDeclined: item.card.isPayoutDeclined, iconSize: 26)
                Button {
                    if app.requireSignIn(to: .reply) { app.sheet = .reply(ReplyRequest(root: item.key, parent: item.post)) }
                } label: {
                    VStack(spacing: 2) {
                        Image(appIcon: .comment).scaledFont(24)
                        Text(Fmt.int(item.card.comments)).scaledFont(12, relativeTo: .caption)
                    }
                    .foregroundStyle(.white)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(ReadingUiCopy.shared.REPLY)
                .accessibilityValue(L.Post.a11yComments(count: Int(item.card.comments)))
                .accessibilityIdentifier("reel-reply")
                TipButton(key: item.card.key)
            }
            .padding(.trailing, 12).padding(.bottom, 24)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("reel-\(item.key.author)-\(item.key.permlink)")
    }
}

/// AVPlayer over the reel's HLS stream: looping, filling the frame for portrait clips and fitting landscape ones
/// (RN resizeMode cover / contain). The player lives exactly as long as this view.
struct ReelPlayerView: UIViewRepresentable {
    let url: URL
    let muted: Bool
    let onError: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onError: onError) }

    func makeUIView(context: Context) -> PlayerLayerView {
        let view = PlayerLayerView()
        view.backgroundColor = .black
        context.coordinator.start(url: url, muted: muted, in: view)
        return view
    }

    func updateUIView(_ view: PlayerLayerView, context: Context) {
        context.coordinator.player?.isMuted = muted
    }

    static func dismantleUIView(_ view: PlayerLayerView, coordinator: Coordinator) {
        coordinator.stop()
        view.playerLayer.player = nil
    }

    final class PlayerLayerView: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }

    @MainActor
    final class Coordinator {
        private(set) var player: AVPlayer?
        private let onError: () -> Void
        private var status: NSKeyValueObservation?
        private var size: NSKeyValueObservation?
        private var ended: NSObjectProtocol?
        private var failed: NSObjectProtocol?

        init(onError: @escaping () -> Void) { self.onError = onError }

        func start(url: URL, muted: Bool, in view: PlayerLayerView) {
            let item = AVPlayerItem(url: url)
            let player = AVPlayer(playerItem: item)
            player.isMuted = muted
            view.playerLayer.player = player
            view.playerLayer.videoGravity = .resizeAspect
            status = item.observe(\.status) { [weak self] item, _ in
                if item.status == .failed { Task { @MainActor in self?.onError() } }
            }
            size = item.observe(\.presentationSize) { [weak view] item, _ in
                let portrait = item.presentationSize.height > item.presentationSize.width
                Task { @MainActor in view?.playerLayer.videoGravity = portrait ? .resizeAspectFill : .resizeAspect }
            }
            ended = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak player] _ in
                player?.seek(to: .zero)
                player?.play()
            }
            failed = NotificationCenter.default.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.onError() }
            }
            self.player = player
            player.play()
        }

        func stop() {
            player?.pause()
            player?.replaceCurrentItem(with: nil)
            player = nil
            status = nil
            size = nil
            [ended, failed].compactMap { $0 }.forEach(NotificationCenter.default.removeObserver)
            ended = nil
            failed = nil
        }
    }
}

/// RN WavesOnboardingChecklist: "Getting started" for new accounts. Drawn only while the card's account is the
/// signed-in one; the shared model decides whether it shows at all.
struct WavesOnboardingCard: View {
    @StateObject private var observed: Observed<WavesOnboardingState>
    private let model: WavesOnboardingModel
    let username: String
    let onWave: () -> Void
    let onCheckIn: () -> Void
    @Environment(\.appTheme) private var theme

    /// Accessibility identifiers' prefix: the Feed tab's copy has its own, as both tabs stay in the hierarchy.
    let idPrefix: String

    init(model: WavesOnboardingModel, username: String, idPrefix: String = "waves-onboarding", onWave: @escaping () -> Void, onCheckIn: @escaping () -> Void) {
        self.idPrefix = idPrefix
        self.model = model
        self.username = username
        self.onWave = onWave
        self.onCheckIn = onCheckIn
        _observed = StateObject(wrappedValue: Observed(model, ownsModel: false))
    }

    var body: some View {
        let state = observed.state
        let copy = WavesOnboardingCopy.shared
        if state.visible, state.account == username {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(state.celebrating ? copy.ALL_DONE_TITLE : copy.TITLE).scaledFont(16, weight: .bold).foregroundStyle(theme.text)
                        .accessibilityAddTraits(.isHeader)
                        .accessibilityIdentifier("\(idPrefix)-title")
                    Spacer()
                    if !state.celebrating {
                        Text(state.progressText).font(.footnote).foregroundStyle(theme.secondary).accessibilityIdentifier("\(idPrefix)-progress")
                    }
                    Button { model.dismiss() } label: { Image(appIcon: .close).scaledFont(13, weight: .semibold).foregroundStyle(theme.secondary).tapTarget() }
                        .buttonStyle(.plain).accessibilityLabel(copy.DISMISS).accessibilityIdentifier("\(idPrefix)-dismiss")
                }
                Text(state.celebrating ? copy.ALL_DONE_SUBTITLE : copy.SUBTITLE).font(.footnote).foregroundStyle(theme.secondary)
                if !state.celebrating {
                    ProgressView(value: Double(state.percent), total: 100).tint(theme.accentText)
                    ForEach(state.items, id: \.id) { item in
                        let row = HStack(spacing: 8) {
                            Image(appIcon: .task, filled: item.completed).foregroundStyle(item.completed ? Color.green : theme.icon).accessibilityHidden(true)
                            Text(item.label).font(.subheadline).strikethrough(item.completed)
                                .foregroundStyle(item.completed ? theme.secondary : (item.actionable ? theme.accentText : theme.text))
                            Spacer()
                        }
                        if item.actionable {
                            Button { if item.id == .wave { onWave() } else { onCheckIn() } } label: { row }
                                .buttonStyle(.plain).accessibilityValue(item.completed ? UiCopy.shared.DONE : "")
                                .accessibilityIdentifier("\(idPrefix)-item-\(item.id.id)")
                        } else {
                            // the strike-through is not spoken: a done item says so in its value
                            row.accessibilityElement(children: .combine).accessibilityValue(item.completed ? UiCopy.shared.DONE : "")
                                .accessibilityIdentifier("\(idPrefix)-item-\(item.id.id)")
                        }
                    }
                }
            }
            .padding(14)
            .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 12))
            .padding(.horizontal, 12).padding(.vertical, 8)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier(idPrefix)
        }
    }
}
