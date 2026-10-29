import EcencyCore
import SwiftUI

/// RN votersScreen: "Voters Info (N)", REWARDS / PERCENT / TIME, search, one row per vote.
struct VotersView: View {
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @StateObject private var model: Observed<VotersState>
    @State private var query = ""

    init(author: String, permlink: String, env: AppEnvironment) {
        _model = StateObject(wrappedValue: Observed(env.votersModel(author: author, permlink: permlink), ownsModel: true))
    }

    private var voters: VotersModel { model.model as! VotersModel }

    var body: some View {
        let s = model.state
        List {
            Section {
                ChipFlow(spacing: 8) {
                    ForEach(VoterSort.entries, id: \.self) { sort in
                        let selected = s.sorted && s.sort == sort
                        Button(sort.label) { voters.selectSort(sort: sort) }
                            .scaledFont(12, weight: .bold)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .foregroundStyle(selected ? .white : theme.secondary)
                            .background(selected ? theme.accent : theme.lightBackground, in: Capsule())
                            .frame(minHeight: minTapTarget).contentShape(Rectangle())
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(selected ? .isSelected : [])
                            .accessibilityIdentifier("voters-sort-\(sort.name)")
                    }
                }
                .listRowSeparator(.hidden)
            }
            if s.isLoading && s.rows.isEmpty {
                ProgressView().frame(maxWidth: .infinity)
            } else if let error = s.error, s.rows.isEmpty {
                ErrorStateView(error: error) { voters.refresh() }
            } else if s.rows.isEmpty {
                Text(s.query.isEmpty ? UiCopy.shared.NOTHING_HERE : ReadingUiCopy.shared.USER_IS_NOT_FOUND).foregroundStyle(theme.secondary).esieAbove(.empty)
            }
            ForEach(s.rows, id: \.voter) { row in
                Button { app.open(.profile(author: row.voter)) } label: {
                    HStack(spacing: 10) {
                        AvatarView(url: row.avatarUrl, size: 40)
                        // name and amounts side by side; stacked at accessibility text sizes
                        AdaptiveStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.voter).scaledFont(15, weight: .bold).foregroundStyle(theme.text)
                            if let time = row.timeAgo { Text(time).scaledFont(12).foregroundStyle(theme.secondary) }
                        }
                        Spacer(minLength: 0)
                        VStack(alignment: .trailing, spacing: 2) {
                            Text("$ \(row.rewardText)").scaledFont(14, weight: .semibold)
                                .foregroundStyle(row.isDownVote ? theme.dangerText : theme.accentText)
                            Text(row.percentText).scaledFont(12).foregroundStyle(theme.secondary)
                        }
                        }
                    }
                }
                .buttonStyle(.plain)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("voter-\(row.voter)")
            }
        }
        .listStyle(.plain)
        .searchable(text: $query)
        .onChange(of: query) { _, q in voters.search(query: q) }
        .refreshable { voters.refresh() }
        .navigationTitle(s.title)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { voters.start() }
        .accessibilityIdentifier("voters-screen")
    }
}

/// RN reblogScreen: "Reblog Info (N)", search, and the Reblog Post / Undo Reblog button.
struct ReblogsView: View {
    let key: PostKey
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var session: Observed<SessionState>
    @Environment(\.appTheme) private var theme
    @StateObject private var model: Observed<ReblogsState>
    @State private var query = ""
    @State private var post: Post?

    init(author: String, permlink: String, env: AppEnvironment) {
        key = PostKey(author: author, permlink: permlink)
        _model = StateObject(wrappedValue: Observed(env.reblogsModel(author: author, permlink: permlink), ownsModel: true))
    }

    private var reblogs: ReblogsModel { model.model as! ReblogsModel }

    var body: some View {
        let s = model.state
        List {
            if s.isLoading && s.accounts.isEmpty { ProgressView().frame(maxWidth: .infinity) }
            if let error = s.error, s.accounts.isEmpty { ErrorStateView(error: error) { reblogs.refresh() } }
            ForEach(s.accounts, id: \.self) { name in
                Button { app.open(.profile(author: name)) } label: {
                    HStack(spacing: 10) {
                        AvatarView(url: PostCard.companion.avatarUrl(account: name, proxyBase: app.env.proxyBase, size: "small"), size: 40)
                        Text(name).scaledFont(15, weight: .bold).foregroundStyle(theme.text)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel(UiCopy.shared.profileOf(username: name))
            }
        }
        .listStyle(.plain)
        .searchable(text: $query)
        .onChange(of: query) { _, q in reblogs.search(query: q) }
        .refreshable { reblogs.refresh() }
        .safeAreaInset(edge: .bottom) {
            if session.state.isLoggedIn, let post {
                Button(s.buttonLabel) { reblogs.toggle(post: post) }
                    .buttonStyle(.prominentFill)
                    .padding(16)
                    .accessibilityIdentifier("reblog-toggle")
            }
        }
        .navigationTitle(s.title)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { reblogs.start() }
        .task { post = try? await app.env.api.posts.getPost(author: key.author, permlink: key.permlink, observer: session.state.username, refresh: false) }
        .accessibilityIdentifier("reblogs-screen")
    }
}

/// RN editHistoryScreen: version chips, plain view or the diff against the previous version.
struct EditHistoryView: View {
    @Environment(\.appTheme) private var theme
    @StateObject private var model: Observed<EditHistoryState>

    init(author: String, permlink: String, env: AppEnvironment) {
        _model = StateObject(wrappedValue: Observed(env.editHistoryModel(author: author, permlink: permlink), ownsModel: true))
    }

    private var history: EditHistoryModel { model.model as! EditHistoryModel }

    var body: some View {
        let s = model.state
        VStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(Array(s.versions.enumerated()), id: \.offset) { index, v in
                        let selected = index == Int(s.selected)
                        Button { history.select(index: Int32(index)) } label: {
                            VStack(spacing: 2) {
                                Text(EditHistoryModel.companion.versionLabel(v: v)).scaledFont(13, weight: .bold)
                                Text(HistoryText.date(v.timestamp)).scaledFont(11)
                            }
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .foregroundStyle(selected ? .white : theme.text)
                            .background(selected ? theme.accent : theme.lightBackground, in: RoundedRectangle(cornerRadius: 8))
                        }
                        .buttonStyle(.plain)
                        .accessibilityElement(children: .combine)
                        .accessibilityAddTraits(selected ? .isSelected : [])
                        .accessibilityIdentifier("history-version-\(index)")
                    }
                }
                .padding(12)
            }
            Divider()
            if s.isLoading {
                ProgressView().frame(maxHeight: .infinity)
            } else if let message = s.message {
                Text(message).foregroundStyle(theme.secondary).padding(24).frame(maxHeight: .infinity)
            } else if Int(s.selected) < s.versions.count {
                let v = s.versions[Int(s.selected)]
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        if s.showDiff {
                            if let diff = s.diff {
                                DiffText(spans: diff.title).scaledFont(20, weight: .bold)
                                DiffText(spans: diff.tags).scaledFont(13)
                                DiffText(spans: diff.body).scaledFont(15)
                            } else {
                                ProgressView()
                            }
                        } else {
                            Text(v.title).scaledFont(20, weight: .bold).foregroundStyle(theme.text)
                            Text(EditHistory.shared.tagsText(v: v)).scaledFont(13).foregroundStyle(theme.secondary)
                            Text(v.body).scaledFont(15).foregroundStyle(theme.text).textSelection(.enabled)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                }
            }
        }
        .navigationTitle(ReadingUiCopy.shared.EDIT_HISTORY)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { history.toggleDiff() } label: { Image(appIcon: .swap) }
                    .accessibilityLabel(s.showDiff ? L.Native.Ios.Reading.showVersion : L.Native.Ios.Reading.showChanges)
                    .largeContent(s.showDiff ? L.Native.Ios.Reading.showVersion : L.Native.Ios.Reading.showChanges, appIcon: .swap)
                    .accessibilityIdentifier("history-diff")
            }
        }
        .onAppear { history.start() }
        .accessibilityIdentifier("history-screen")
    }
}

/// Diff runs as one text: inserted on green, deleted struck through on red (RN diff_prettyHtml colours).
struct DiffText: View {
    let spans: [DiffSpan]
    @Environment(\.appTheme) private var theme

    var body: some View {
        Text(HistoryText.attributed(spans, text: theme.text)).frame(maxWidth: .infinity, alignment: .leading)
    }
}

enum HistoryText {
    static let inserted = Color(red: 0xE6 / 255.0, green: 1, blue: 0xE6 / 255.0)
    static let deleted = Color(red: 1, green: 0xE6 / 255.0, blue: 0xE6 / 255.0)

    static func attributed(_ spans: [DiffSpan], text: Color) -> AttributedString {
        var out = AttributedString()
        for span in spans {
            var part = AttributedString(span.text)
            switch span.kind {
            case 1:
                part.backgroundColor = inserted
                part.foregroundColor = .black
            case -1:
                part.backgroundColor = deleted
                part.foregroundColor = .black
                part.strikethroughStyle = .single
            default:
                part.foregroundColor = text
            }
            out += part
        }
        return out
    }

    /// RN moment 'LL' ("October 6, 2026") in the app language for the API's timestamp; the raw text when it does not parse.
    static func date(_ timestamp: String) -> String {
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let plain = ISO8601DateFormatter()
        let chain = DateFormatter()
        chain.locale = Locale(identifier: "en_US_POSIX")
        chain.timeZone = TimeZone(identifier: "UTC")
        chain.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        guard let date = iso.date(from: timestamp) ?? plain.date(from: timestamp) ?? chain.date(from: timestamp) else { return timestamp }
        return LocaleFormat.shared.dateLong(epochMillis: Int64(date.timeIntervalSince1970 * 1000))
    }
}

/// RN botComments modal: "Comments" with the post's bot replies.
struct BotCommentsView: View {
    let key: PostKey
    @EnvironmentObject private var app: AppContainer
    @StateObject private var post: Observed<PostState>

    init(author: String, permlink: String, env: AppEnvironment) {
        key = PostKey(author: author, permlink: permlink)
        _post = StateObject(wrappedValue: Observed(env.postModel(author: author, permlink: permlink), ownsModel: true))
    }

    var body: some View {
        let state = post.state
        GeometryReader { geo in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if state.commentsLoading && state.botComments.isEmpty { ProgressView().frame(maxWidth: .infinity).padding(24) }
                    ForEach(state.botComments, id: \.key) { item in
                        CommentRow(item: item, width: geo.size.width,
                                   actions: PostActions(onLink: { app.handleBodyLink($0) }, onImage: { url, all in
                                       app.sheet = .gallery(all.isEmpty ? [url] : all, all.firstIndex(of: url) ?? 0)
                                   }, showImages: app.notifications.state.showImages), root: key) {
                            if app.requireSignIn(to: .reply) { app.sheet = .reply(ReplyRequest(root: key, parent: item.post)) }
                        }
                    }
                }
            }
        }
        .navigationTitle(ReadingUiCopy.shared.COMMENTS)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { (post.model as! PostModel).start() }
        .accessibilityIdentifier("bot-comments")
    }
}
