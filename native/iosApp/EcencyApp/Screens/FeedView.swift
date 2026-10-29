import EcencyCore
import SwiftUI

/// One feed (ranked, tag, community or an account's blog) as a lazy list with pull to refresh and
/// infinite scroll. The FeedModel belongs to the `Observed` passed in.
struct FeedListView: View {
    @ObservedObject var feed: Observed<FeedState>
    /// Above the cards (the main feed's proposal card and spotlight).
    var header: AnyView? = nil
    /// A header above the list that closes as it scrolls (community Details).
    var collapse: SummaryCollapseDriver? = nil
    @Environment(\.appTheme) private var theme

    private var model: FeedModel { feed.model as! FeedModel }

    var body: some View {
        let state = feed.state
        GeometryReader { geo in
            ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 0) {
                    Color.clear.frame(height: 0).id("feed-top")
                    if let header { header }
                    if state.items.isEmpty {
                        if let error = state.error {
                            ErrorStateView(error: error) { model.retry() }.padding(.top, 80)
                        } else if state.loaded && state.endReached {
                            Text(ReadingUiCopy.shared.NOTHING_HERE_YET).foregroundStyle(theme.secondary).esieAbove(.empty).padding(.top, 80)
                        } else {
                            // the first page draws the cards' shape (Motion.swift skeleton)
                            FeedSkeleton().accessibilityIdentifier("feed-loading")
                        }
                    }
                    // Identity is the post key: a card whose votes changed is the same row, updated in place
                    // (hashing whole Kotlin data classes would walk every field on the main thread).
                    ForEach(state.items, id: \.key) { card in
                        PostCardView(card: card, width: geo.size.width, onAppearWithContent: {
                            // The first card drawn with content: the feed's first meaningful paint.
                            _ = PerfMarks.shared.markOnce(event: PerfEvent.shared.FEED_FIRST_CONTENT)
                        }, payoutPost: { model.post(key: card.key) })
                        .onAppear { if FeedPaging.shouldLoadMore(appearing: card, in: state) { model.loadMore() } }
                        Rectangle().fill(theme.lightBackground).frame(height: 4)
                    }
                    if !state.items.isEmpty {
                        // Re-created for every page (its id is the page counter), so when a page added no
                        // new cards (all duplicates) and the end is still on screen, the next one is asked for.
                        Color.clear.frame(height: 1)
                            .id("page-\(state.pages)")
                            .onAppear { if FeedPaging.shouldLoadMoreAtEnd(state) { model.loadMore() } }
                        if let error = state.error {
                            InlineErrorRow(error: error) { model.retry() }
                        } else if state.isLoadingMore {
                            ProgressView().padding(24)
                        }
                    }
                }
                .reportsScrollOffset("feed-scroll")
            }
            .refreshable { await refresh() }
            .collapsesSummary(collapse, space: "feed-scroll")
            // RN postsTabContent's ScrollTopPopup (feed, tag and community lists); the top opens a header above
            .scrollTopPopup(space: "feed-scroll", id: "feed-scroll-top") {
                collapse?.expand()
                proxy.scrollTo("feed-top", anchor: .top)
            }
            }
        }
        .background(theme.background)
        .onAppear { model.start() }
    }

    /// Pull to refresh: keeps the spinner until the model finishes (or is closed with the screen).
    @MainActor private func refresh() async {
        try? await model.refreshAndWait()
    }
}

/// When the list asks for the next page. Pure, unit tested.
enum FeedPaging {
    /// Prefetch distance: start loading when one of the last cards appears.
    static let threshold = 3

    static func shouldLoadMore(appearing card: PostCard, in state: FeedState) -> Bool {
        guard canLoadMore(state) else { return false }
        // compared by key: equality of whole cards would compare every field of every card
        let key = card.key
        guard let index = state.items.lastIndex(where: { $0.key == key }) else { return false }
        return index >= state.items.count - threshold
    }

    /// The end of the list is on screen: load on, whether or not the last page added cards.
    static func shouldLoadMoreAtEnd(_ state: FeedState) -> Bool { canLoadMore(state) }

    private static func canLoadMore(_ state: FeedState) -> Bool {
        state.loaded && !state.endReached && !state.isLoadingMore && state.error == nil
    }
}

/// Feed models for the home tabs, created on first use and kept while the app runs, so switching
/// tabs keeps each list and its scroll data. Keyed by filter and account (another account, another feed).
@MainActor
final class HomeFeeds: ObservableObject {
    private var feeds: [String: Observed<FeedState>] = [:]

    func feed(_ filter: FeedFilter, username: String?, env: AppEnvironment) -> Observed<FeedState>? {
        let key = "\(filter.key)|\(username ?? "")"
        if let feed = feeds[key] { return feed }
        guard let source = filter.source(username: username) else { return nil }
        // RN injects promoted entries on the home feed
        let feed = Observed(env.feedModel(source: source, withPromoted: true), ownsModel: true)
        feeds[key] = feed
        return feed
    }
}

/// Home: RN's filter pills (FeedTabs, saved per device) with the "+" customise button, over the feed list.
struct HomeView: View {
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var session: Observed<SessionState>
    @StateObject private var walkthrough: Observed<WalkthroughState>
    /// Whether there is a spotlight: an empty header row would leave a zero-height row at the top of the lazy list.
    @StateObject private var spotlight: Observed<SpotlightState>
    private var feeds: HomeFeeds { app.homeFeeds }
    @AppStorage("homeFilter") private var selectedKey = ""
    /// Bumped when the customise sheet saved, so the pills re-read the tabs.
    @AppStorage("homeTabsVersion") private var tabsVersion = 0
    @Environment(\.appTheme) private var theme

    init(env: AppEnvironment) {
        _walkthrough = StateObject(wrappedValue: Observed(env.walkthrough, ownsModel: false))
        _spotlight = StateObject(wrappedValue: Observed(env.spotlight, ownsModel: false))
    }

    var body: some View {
        let tabs = FeedTabs.shared.tabs(prefs: app.env.prefs, loggedIn: session.state.isLoggedIn)
        let step = WalkthroughStep.customTabsBtn
        let signedOut = !session.state.isLoggedIn
        let selected = tabs.first { $0.key == selectedKey } ?? tabs.first ?? .trending
        VStack(spacing: 0) {
            // RN feedScreen: the WavesOnboardingChecklist above the feed for signed-in readers
            if let username = session.state.username { FeedWavesCard(username: username) }
            FilterPills(titles: tabs.map(\.title), selected: tabs.firstIndex(of: selected) ?? 0,
                        onSelect: { selectedKey = tabs[$0].key },
                        onCustomise: {
                            app.env.walkthrough.intercept(step: step, hidden: signedOut)
                            if session.state.isLoggedIn { app.sheet = .customiseFilters } else { app.sheet = .login }
                        },
                        customiseMarker: !signedOut && !walkthrough.state.shown.contains(step))
                .id(tabsVersion)
            if let feed = feeds.feed(selected, username: session.state.username, env: app.env) {
                // A session change reloads each feed in place (the shared models refresh themselves).
                // RN postsTabContent: on the main feed's first tab, the proposal card (signed in) then the spotlight
                let first = tabs.first == selected
                let proposal = first && session.state.isLoggedIn
                let spotlit = first && spotlight.state.card != nil
                // no header row at all when there is nothing to head the list with
                FeedListView(feed: feed, header: proposal || spotlit ? AnyView(VStack(spacing: 0) {
                    if proposal { ProposalCardView(env: app.env) }
                    if spotlit { SpotlightCardView(env: app.env) }
                }) : nil)
                    .id("\(selected.key)|\(session.state.username ?? "")")
            } else {
                LoginPromptView(text: ReadingUiCopy.shared.LOGIN_TO_SEE) { app.sheet = .login }
            }
        }
        .background(theme.background)
        .onAppear { app.env.spotlight.start() }
    }
}

/// RN FeatureSpotlightCard: "Did you know?", the spotlight, its button and "Dismiss" (SpotlightModel).
struct SpotlightCardView: View {
    @EnvironmentObject private var app: AppContainer
    @StateObject private var spotlight: Observed<SpotlightState>
    private let env: AppEnvironment
    @Environment(\.appTheme) private var theme

    init(env: AppEnvironment) {
        self.env = env
        _spotlight = StateObject(wrappedValue: Observed(env.spotlight, ownsModel: false))
    }

    var body: some View {
        Group {
            if let card = spotlight.state.card {
                VStack(alignment: .leading, spacing: 0) {
                    Text(SpotlightCopy.shared.LABEL.uppercased()).scaledFont(12, weight: .semibold, relativeTo: .caption).foregroundStyle(theme.accentText).padding(.bottom, 4)
                    Text(card.title).scaledFont(18, weight: .semibold, relativeTo: .headline).foregroundStyle(theme.text)
                        .accessibilityAddTraits(.isHeader).accessibilityIdentifier("spotlight-title")
                    Text(card.description_).foregroundStyle(theme.text).padding(.vertical, 8)
                    AdaptiveStack(spacing: 8) {
                        Button { app.openAnnouncementTarget(env.spotlight.act()) } label: {
                            Text(card.buttonText).bold().foregroundStyle(.white).padding(.horizontal, 16).padding(.vertical, 8)
                                .frame(minHeight: minTapTarget)
                                .background(theme.accent, in: Capsule())
                        }
                        .accessibilityIdentifier("spotlight-action")
                        Button(SpotlightCopy.shared.DISMISS) { env.spotlight.dismiss() }
                            .foregroundStyle(theme.secondary).frame(minHeight: minTapTarget).accessibilityIdentifier("spotlight-dismiss")
                    }
                    .padding(.top, 8)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .background(theme.lightBackground)
                .padding(.top, 16).padding(.bottom, 8)
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("spotlight-\(card.id)")
            }
        }
    }
}

/// RN pill tab bar: upper-case labels, the selected one on blue; "+" opens the customise sheet when given.
struct FilterPills: View {
    /// Identifier prefix of the pills (two pill bars can be mounted at once: Home stays alive behind the other tabs).
    var idPrefix = "filter"
    let titles: [String]
    let selected: Int
    let onSelect: (Int) -> Void
    var onCustomise: (() -> Void)?
    /// RN WalkthroughMarker: a red dot on "+" until its first use.
    var customiseMarker = false
    @Environment(\.appTheme) private var theme
    /// The dot's outward nudge follows the trailing corner (an offset does not mirror on its own).
    @Environment(\.layoutDirection) private var layoutDirection

    var body: some View {
        HStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(Array(titles.enumerated()), id: \.offset) { index, title in
                        Button { onSelect(index) } label: {
                            Text(title).scaledFont(13, weight: .bold)
                                .padding(.horizontal, 14).padding(.vertical, 5)
                                .foregroundStyle(index == selected ? .white : theme.secondary)
                                .background(index == selected ? theme.accent : .clear, in: Capsule())
                                // 44 pt of touch around the pill (the bar keeps its RN height)
                                .frame(minHeight: minTapTarget).contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(index == selected ? .isSelected : [])
                        .accessibilityIdentifier("\(idPrefix)-\(title)")
                    }
                }
                .padding(.horizontal, 12)
            }
            if let onCustomise {
                Button(action: onCustomise) {
                    Image(appIcon: .add).scaledFont(16, weight: .semibold).foregroundStyle(theme.icon)
                        // on the glyph's top trailing corner, inside the bar: offset from the 44 pt tap target it
                        // hung above the bar, over a "Getting started" card on top of the feed
                        .overlay(alignment: .topTrailing) {
                            if customiseMarker { Circle().fill(theme.danger).frame(width: 8, height: 8).offset(x: layoutDirection == .rightToLeft ? -4 : 4, y: -3) }
                        }
                        .tapTarget()
                }
                    .padding(.horizontal, 2)
                    .accessibilityLabel(ReadingUiCopy.shared.CUSTOMISE_FILTERS)
                    // the red dot, for VoiceOver and the UI tests
                    .accessibilityValue(customiseMarker ? WalkthroughCopy.shared.MARKER : "")
                    .largeContent(ReadingUiCopy.shared.CUSTOMISE_FILTERS, appIcon: .add)
                    .accessibilityIdentifier("\(idPrefix)-customise")
            }
        }
        .padding(.vertical, 0)
        .background(theme.lightBackground)
    }
}

/// RN customiseFiltersModal: selected filters in order, then the available ones; APPLY needs three.
struct CustomiseFiltersSheet: View {
    @EnvironmentObject private var app: AppContainer
    @Environment(\.dismiss) private var dismiss
    @AppStorage("homeTabsVersion") private var tabsVersion = 0
    @State private var selected: [FeedFilter] = []
    @State private var tooFew = false

    var body: some View {
        NavigationStack {
            List {
                Section(ReadingUiCopy.shared.SELECTED_FILTERS) {
                    if selected.isEmpty { Text(ReadingUiCopy.shared.PLEASE_ADD_FROM_AVAILABLE).foregroundStyle(.secondary) }
                    ForEach(selected, id: \.self) { f in row(f, on: true) }
                        .onMove { selected.move(fromOffsets: $0, toOffset: $1) }
                }
                Section(ReadingUiCopy.shared.AVAILABLE_FILTERS) {
                    ForEach(FeedFilter.entries.filter { !selected.contains($0) }, id: \.self) { f in row(f, on: false) }
                }
            }
            .environment(\.editMode, .constant(.active))
            .navigationTitle(ReadingUiCopy.shared.CUSTOMISE_FILTERS)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(UiCopy.shared.CANCEL) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button(ReadingUiCopy.shared.APPLY_CAPS) {
                        if FeedTabs.shared.save(prefs: app.env.prefs, tabs: selected) != nil {
                            tooFew = true
                        } else {
                            tabsVersion += 1
                            dismiss()
                        }
                    }
                    .accessibilityIdentifier("filters-apply")
                }
            }
            .alert(FeedTabs.shared.TOO_FEW, isPresented: $tooFew) { Button(UiCopy.shared.OK, role: .cancel) {} }
        }
        .onAppear { selected = FeedTabs.shared.saved(prefs: app.env.prefs) }
    }

    private func row(_ f: FeedFilter, on: Bool) -> some View {
        Button {
            if on { selected.removeAll { $0 == f } } else { selected.append(f) }
        } label: {
            HStack {
                Image(appIcon: .checkbox, filled: on).accessibilityHidden(true)
                Text(f.label)
            }
        }
        .accessibilityAddTraits(on ? .isSelected : [])
        .accessibilityIdentifier("filter-option-\(f.key)")
    }
}

/// A tag, community or profile feed pushed on the stack.
struct FeedScreen: View {
    let title: String
    @StateObject private var feed: Observed<FeedState>

    init(source: FeedSource, title: String, env: AppEnvironment) {
        self.title = title
        _feed = StateObject(wrappedValue: Observed(env.feedModel(source: source), ownsModel: true))
    }

    var body: some View {
        FeedListView(feed: feed)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
    }
}

/// RN tagResult: TOP / HOT / NEW over the tag (promoted entries injected) and, signed in, the follow row.
struct TagFeedScreen: View {
    let tag: String
    let title: String
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @State private var selected: Int
    @StateObject private var feeds = TagFeeds()
    @StateObject private var follow: Observed<TagFollowState>

    static var sorts: [(RankedSort, String)] { [(.trending, ReadingUiCopy.shared.TOP_CAPS), (.hot, UiCopy.shared.HOT), (.created, UiCopy.shared.NEW)] }

    init(tag: String, sort: RankedSort, title: String, env: AppEnvironment) {
        self.tag = tag
        self.title = title
        _selected = State(initialValue: Self.sorts.firstIndex { $0.0 == sort } ?? 0)
        _follow = StateObject(wrappedValue: Observed(env.tagFollowModel(tag: tag), ownsModel: true))
    }

    private var followModel: TagFollowModel { follow.model as! TagFollowModel }

    var body: some View {
        VStack(spacing: 0) {
            if follow.state.visible {
                HStack {
                    Text(follow.state.hint).scaledFont(12).foregroundStyle(theme.secondary)
                    Spacer()
                    Button(follow.state.buttonLabel) { followModel.toggle() }
                        .buttonStyle(.subtleFill)
                        .disabled(follow.state.busy)
                        .accessibilityIdentifier("tag-follow")
                }
                .padding(.horizontal, 16).padding(.vertical, 8)
            }
            FilterPills(titles: Self.sorts.map(\.1), selected: selected, onSelect: { selected = $0 })
            FeedListView(feed: feed(selected)).id(selected)
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { followModel.start() }
    }

    private func feed(_ index: Int) -> Observed<FeedState> {
        feeds.feed(index) { Observed(app.env.feedModel(source: FeedSourceRanked(sort: Self.sorts[index].0, tag: tag), withPromoted: true), ownsModel: true) }
    }
}

/// The tag screen's three feeds, kept while the screen lives.
@MainActor
final class TagFeeds: ObservableObject {
    private var feeds: [Int: Observed<FeedState>] = [:]

    func feed(_ index: Int, make: () -> Observed<FeedState>) -> Observed<FeedState> {
        if let f = feeds[index] { return f }
        let f = make()
        feeds[index] = f
        return f
    }
}
