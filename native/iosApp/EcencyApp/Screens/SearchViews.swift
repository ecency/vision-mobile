import EcencyCore
import SwiftUI

// Search, tag result and explore (README "Communities and search").

/// RN SearchInput: rounded field with an optional prefix and a clear button.
struct SearchField: View {
    @Binding var text: String
    var prefix: String?
    var placeholder = SearchCopy.shared.PLACEHOLDER
    var onSubmit: () -> Void = {}
    @Environment(\.appTheme) private var theme

    var body: some View {
        HStack(spacing: 6) {
            Image(appIcon: .search).foregroundStyle(theme.icon).accessibilityHidden(true)
            if let prefix { Text(prefix).foregroundStyle(theme.text) }
            TextField(placeholder, text: $text)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .submitLabel(.search).onSubmit(onSubmit)
                .accessibilityIdentifier("search-input")
            if !text.isEmpty {
                Button { text = "" } label: { Image(appIcon: .clear, filled: true).foregroundStyle(theme.icon).frame(minWidth: 28, minHeight: 28).contentShape(Rectangle()) }
                    .accessibilityLabel(UiCopy.shared.CLEAR).largeContent(UiCopy.shared.CLEAR, appIcon: .clear)
                    .accessibilityIdentifier("search-clear")
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 8).fill(theme.lightBackground))
    }
}

struct SearchScreen: View {
    @EnvironmentObject private var app: AppContainer
    @StateObject private var model: Observed<SearchState>
    @State private var filtersOpen = false
    @Environment(\.appTheme) private var theme

    init(env: AppEnvironment) {
        _model = StateObject(wrappedValue: Observed(SearchModel(env: env, debounceMillis: SearchModel.companion.DEBOUNCE_MILLIS), ownsModel: true))
    }

    private var search: SearchModel { model.model as! SearchModel }

    var body: some View {
        let state = model.state
        VStack(spacing: 0) {
            HStack {
                SearchField(text: Binding(get: { model.state.input }, set: { search.setInput(text: $0) })) { search.submit(text: model.state.input) }
                Button { filtersOpen = true } label: {
                    // tune has no outline variant: an active filter is shown by colour, as RN
                    Image(appIcon: .tune).foregroundStyle(state.activeFilterCount > 0 ? theme.accent : theme.icon).tapTarget()
                }
                .accessibilityLabel(SearchCopy.shared.FILTERS)
                .accessibilityValue(state.activeFilterCount > 0 ? Fmt.int(state.activeFilterCount) : "")
                .largeContent(SearchCopy.shared.FILTERS, appIcon: .tune)
                .accessibilityIdentifier("search-filters")
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            UnderlineTabs(labels: SearchTab.entries.map { $0.label }, selected: Binding(get: { Int(model.state.tab.ordinal) }, set: { search.selectTab(tab: SearchTab.entries[$0]) }), idPrefix: "search-tab")
            List {
                switch state.tab {
                case .people: people(state.people)
                case .communities: communities(state)
                default: posts(state.posts, query: state.clipped)
                }
            }
            .listStyle(.plain)
        }
        .background(theme.background)
        .navigationTitle(SearchCopy.shared.PLACEHOLDER)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $filtersOpen) {
            SearchFiltersSheet(initial: state.filters, validate: { search.validateFilters(filters: $0) }) { filters in
                filtersOpen = false
                search.applyFilters(filters: filters)
            }
            .presentationDetents([.large])
        }
        .onAppear { search.start() }
    }

    @ViewBuilder private func posts(_ posts: SearchPostsState, query: String) -> some View {
        if posts.loading { ProgressView().frame(maxWidth: .infinity).padding(.top, 48) }
        if let empty = posts.emptyText {
            VStack(spacing: 8) {
                EsieView(posts.error != nil ? .error : .empty)
                Text(empty).multilineTextAlignment(.center).foregroundStyle(theme.secondary)
                    .announces(posts.error != nil ? empty : nil)
                if posts.error != nil { Button(UiCopy.shared.RETRY) { search.retry() } }
            }
            .frame(maxWidth: .infinity).padding(.top, 48)
            .accessibilityIdentifier("search-empty")
        }
        ForEach(Array(posts.items.enumerated()), id: \.element.key) { index, row in
            Button { app.push(AppScreenPost(author: row.author, permlink: row.permlink)) } label: {
                SearchPostItem(row: row, highlight: posts.mode == .search ? query : "")
            }
            .buttonStyle(.plain)
            .listRowBackground(index % 2 != 0 ? theme.lightBackground : theme.background)
            .accessibilityIdentifier("result-\(row.key)")
            .onAppear { if index >= posts.items.count - 3 { search.loadMorePosts() } }
        }
        if posts.loadingMore { ProgressView().frame(maxWidth: .infinity) }
        if let more = posts.moreError {
            AdaptiveStack { Text(more).foregroundStyle(theme.secondary).announces(more, sticky: true); Spacer(minLength: 0); Button(UiCopy.shared.RETRY) { search.retry() } }
        }
    }

    @ViewBuilder private func people(_ people: SearchPeopleState) -> some View {
        if people.loading { ProgressView().frame(maxWidth: .infinity).padding(.top, 48) }
        if let empty = people.emptyText { Text(empty).foregroundStyle(theme.secondary).esieAbove(.empty).padding(.top, 48) }
        ForEach(Array(people.items.enumerated()), id: \.element) { index, name in
            Button { app.push(AppScreenProfile(author: name)) } label: {
                HStack(spacing: 12) {
                    AvatarView(url: PostCard.companion.avatarUrl(account: name, proxyBase: app.env.proxyBase, size: "small"), size: 36)
                    Text("@\(name)").scaledFont(15, weight: .bold).foregroundStyle(theme.text)
                    Spacer()
                }
            }
            .buttonStyle(.plain)
            .listRowBackground(index % 2 != 0 ? theme.lightBackground : theme.background)
            .accessibilityIdentifier("person-\(name)")
        }
    }

    @ViewBuilder private func communities(_ state: SearchState) -> some View {
        let c = state.communities
        if c.loading && c.rows.isEmpty { ProgressView().frame(maxWidth: .infinity).padding(.top, 48) }
        if let empty = c.emptyText { Text(empty).foregroundStyle(theme.secondary).esieAbove(c.error != nil ? .error : .empty).padding(.top, 48) }
        ForEach(Array(c.rows.enumerated()), id: \.element.name) { index, row in
            CommunityListRow(row: row, index: index, showJoin: state.loggedIn, onOpen: { app.push(AppScreenCommunity(name: row.name, filter: "")) }) {
                if !search.toggle(row: row) { app.push(AppScreenLogin.shared) }
            }
            .listRowInsets(EdgeInsets())
        }
    }
}

/// RN postsResults item: author line, title, highlighted snippet, payout, votes, comments.
struct SearchPostItem: View {
    let row: SearchPostRow
    let highlight: String
    @Environment(\.appTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                AvatarView(url: row.avatarUrl, size: 36)
                Text(row.author).scaledFont(14, weight: .bold).foregroundStyle(theme.text)
                if row.createdEpochMillis > 0 {
                    Text(TimeAgo.shared.short(epochMillis: row.createdEpochMillis, nowMillis: Int64(Date().timeIntervalSince1970 * 1000)))
                        .scaledFont(13).foregroundStyle(theme.secondary)
                }
            }
            Text(row.title).scaledFont(16, weight: .bold).foregroundStyle(theme.text)
            if !row.snippet.isEmpty {
                Text(SearchText.highlighted(row.snippet, highlight, mark: theme.secondary)).scaledFont(14).foregroundStyle(theme.secondary).lineLimit(3)
            }
            ChipFlow(spacing: 12) {
                if let payout = row.payoutText { Text(payout).scaledFont(13).foregroundStyle(theme.secondary) }
                HStack(spacing: 4) { Image(appIcon: .heart); Text(Fmt.int(row.votes)) }.scaledFont(13).foregroundStyle(theme.secondary)
                HStack(spacing: 4) { Image(appIcon: .comment); Text(Fmt.int(row.comments)) }.scaledFont(13).foregroundStyle(theme.secondary)
            }
        }
        .padding(.vertical, 8)
        // one VoiceOver stop: the counts with their nouns instead of bare numbers next to icons
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilitySummary)
    }

    private var accessibilitySummary: String {
        var parts = [row.title, L.Native.Ios.Card.byAuthor(username: row.author)]
        if row.createdEpochMillis > 0 {
            parts.append(TimeAgo.shared.short(epochMillis: row.createdEpochMillis, nowMillis: Int64(Date().timeIntervalSince1970 * 1000)))
        }
        if !row.snippet.isEmpty { parts.append(row.snippet) }
        if let payout = row.payoutText { parts.append(L.Native.Ios.Card.payout(payout: payout)) }
        parts.append(L.Post.a11yVotes(count: Int(row.votes)))
        parts.append(L.Post.a11yComments(count: Int(row.comments)))
        return parts.joined(separator: ", ")
    }
}

/// Search text helpers, pure so they are unit tested.
enum SearchText {
    /// The snippet with the searched word marked (RN Highlighter), from the shared ranges.
    static func highlighted(_ text: String, _ word: String, mark: Color) -> AttributedString {
        var out = AttributedString(text)
        let utf16 = Array(text.utf16)
        for range in SearchModel.companion.highlights(text: text, word: word) {
            let from = Int(range.first), to = Int(range.last) + 1
            guard from >= 0, to <= utf16.count,
                  let lo = String.Index(utf16Offset: from, in: text).samePosition(in: text.unicodeScalars).flatMap({ AttributedString.Index($0, within: out) }),
                  let hi = String.Index(utf16Offset: to, in: text).samePosition(in: text.unicodeScalars).flatMap({ AttributedString.Index($0, within: out) })
            else { continue }
            out[lo..<hi].backgroundColor = mark
            out[lo..<hi].foregroundColor = .white
        }
        return out
    }
}

/// RN searchFiltersSheet: author, category, tags, then type, date and sort; checked before it closes.
struct SearchFiltersSheet: View {
    let initial: SearchFilters
    let validate: (SearchFilters) -> String?
    let onApply: (SearchFilters) -> Void
    @State private var author: String
    @State private var category: String
    @State private var tags: String
    @State private var type: SearchType
    @State private var date: SearchDate
    @State private var sort: SearchSort
    @State private var error: String?
    @Environment(\.appTheme) private var theme

    init(initial: SearchFilters, validate: @escaping (SearchFilters) -> String?, onApply: @escaping (SearchFilters) -> Void) {
        self.initial = initial
        self.validate = validate
        self.onApply = onApply
        _author = State(initialValue: initial.author)
        _category = State(initialValue: initial.category)
        _tags = State(initialValue: initial.tags)
        _type = State(initialValue: initial.type)
        _date = State(initialValue: initial.date)
        _sort = State(initialValue: initial.sort)
    }

    private var filters: SearchFilters { SearchFilters(author: author, category: category, tags: tags, type: type, date: date, sort: sort) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                Text(SearchCopy.shared.FILTERS).scaledFont(18, weight: .bold).foregroundStyle(theme.text).frame(maxWidth: .infinity)
                    .accessibilityAddTraits(.isHeader)
                field(SearchCopy.shared.AUTHOR, SearchCopy.shared.AUTHOR_PLACEHOLDER, $author, "author")
                field(SearchCopy.shared.CATEGORY, SearchCopy.shared.CATEGORY_PLACEHOLDER, $category, "category")
                field(SearchCopy.shared.TAGS, SearchCopy.shared.TAGS_PLACEHOLDER, $tags, "tags")
                chips(SearchCopy.shared.TYPE, [SearchType.all, .post, .comment], type, { SearchCopy.shared.typeLabel(type: $0) }) { type = $0 }
                chips(SearchCopy.shared.DATE, [SearchDate.all, .week, .month, .year], date, { $0.label }) { date = $0 }
                chips(SearchCopy.shared.SORT, SearchSort.entries, sort, { $0.label }) { sort = $0 }
                if let error { Text(error).scaledFont(13).foregroundStyle(theme.dangerText).announces(error, debounce: true).accessibilityIdentifier("filter-error") }
                Button {
                    if let e = validate(filters) { error = e } else { onApply(filters) }
                } label: { Text(SearchCopy.shared.APPLY).bold().frame(maxWidth: .infinity) }
                .buttonStyle(.prominentFill)
                .padding(.top, 12)
                .accessibilityIdentifier("apply-filters")
            }
            .padding(16)
        }
        .background(theme.background)
    }

    @ViewBuilder private func field(_ label: String, _ placeholder: String, _ text: Binding<String>, _ id: String) -> some View {
        // the visible label names the field for VoiceOver; the field's own stop reads it
        Text(label).scaledFont(14, weight: .bold).foregroundStyle(theme.text).padding(.top, 8).accessibilityHidden(true)
        TextField(placeholder, text: Binding(get: { text.wrappedValue }, set: { error = nil; text.wrappedValue = $0 }))
            .accessibilityLabel(label)
            .textInputAutocapitalization(.never).autocorrectionDisabled()
            .padding(10).background(RoundedRectangle(cornerRadius: 8).fill(theme.lightBackground))
            .accessibilityIdentifier("filter-\(id)")
    }

    @ViewBuilder private func chips<T: Hashable>(_ label: String, _ options: [T], _ selected: T, _ name: @escaping (T) -> String, _ set: @escaping (T) -> Void) -> some View {
        Text(label).scaledFont(14, weight: .bold).foregroundStyle(theme.text).padding(.top, 12).accessibilityAddTraits(.isHeader)
        ChipFlow(spacing: 8) {
            ForEach(options, id: \.self) { o in
                Button { error = nil; set(o) } label: {
                    Text(name(o)).scaledFont(13).foregroundStyle(o == selected ? Color.white : theme.text)
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(Capsule().fill(o == selected ? theme.accent : theme.lightBackground))
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(o == selected ? .isSelected : [])
                .accessibilityIdentifier("chip-\(name(o))")
            }
        }
    }
}

// MARK: Tag result

struct TagResultScreen: View {
    @EnvironmentObject private var app: AppContainer
    @StateObject private var model: Observed<TagResultState>
    @StateObject private var feeds = TabFeeds()
    @Environment(\.appTheme) private var theme

    init(tag: String, filter: String, env: AppEnvironment) {
        _model = StateObject(wrappedValue: Observed(TagResultModel(env: env, tag: tag, filter: filter, debounceMillis: 1000), ownsModel: true))
    }

    private var tagModel: TagResultModel { model.model as! TagResultModel }

    var body: some View {
        let state = model.state
        let tabs = TagTab.entries
        VStack(spacing: 0) {
            SearchField(text: Binding(get: { model.state.input }, set: { tagModel.setInput(text: $0) }), prefix: "#", placeholder: "")
                .padding(.horizontal, 12).padding(.vertical, 8)
            PillTabs(labels: tabs.map { $0.label }, selected: Binding(get: { Int(model.state.tab.ordinal) }, set: { tagModel.selectTab(tab: tabs[$0]) }))
            let key = "\(state.tab.name):\(state.tag)"
            FeedListView(feed: feeds.feed(key, source: tagModel.feedSource(tab: state.tab, tag: state.tag), env: app.env)).id(key)
        }
        .background(theme.background)
        .navigationTitle("#\(state.tag)")
        .navigationBarTitleDisplayMode(.inline)
    }
}

extension Color {
    /// "#RRGGBB" from the shared dApp directory; gray when malformed.
    init(hexString: String) {
        let hex = hexString.hasPrefix("#") ? String(hexString.dropFirst()) : hexString
        self.init(hex: UInt32(hex, radix: 16) ?? 0x788187)
    }
}
