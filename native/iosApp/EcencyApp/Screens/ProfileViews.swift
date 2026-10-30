import EcencyCore
import SwiftUI

// MARK: - Shared pieces: Pro badge, user ribbon, user rows

/// RN ProBadge: the check next to an Ecency Pro member's name; nothing for others.
struct ProBadge: View {
    let username: String
    var size: CGFloat = 15
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    var body: some View {
        ProBadgeContent(members: app.proMembers, username: username, size: size, color: theme.accent)
    }
}

private struct ProBadgeContent: View {
    @ObservedObject var members: Observed<NSSet>
    let username: String
    let size: CGFloat
    let color: Color

    var body: some View {
        Group {
            if members.state.contains(username.lowercased()) {
                Image(appIcon: .verified, filled: true).scaledFont(size).foregroundStyle(color)
                    .accessibilityLabel(ProfileUiCopy.shared.ECENCY_PRO_MEMBER).accessibilityIdentifier("pro-badge")
            }
        }
        .onAppear { (members.model as? ProMembersModel)?.ensureLoaded() }
    }
}

/// RN userRibbon: avatar and @username.
struct UserRibbon: View {
    let username: String
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    var body: some View {
        HStack(spacing: 8) {
            AvatarView(url: app.env.avatars.url(username: username, size: "small", proxyBase: app.env.proxyBase), size: 24)
            Text("@\(username)").scaledFont(14).foregroundStyle(theme.text)
        }
    }
}

/// RN UserListItem: zebra row with avatar, name (or `text`), the Pro badge and an optional description.
struct UserRow: View {
    let index: Int
    let username: String
    var text: String?
    var description: String?
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    var body: some View {
        HStack(spacing: 12) {
            AvatarView(url: app.env.avatars.url(username: username, size: "small", proxyBase: app.env.proxyBase), size: 32)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(text ?? username).scaledFont(14, weight: .bold).foregroundStyle(theme.text).lineLimit(1)
                    ProBadge(username: username)
                }
                if let description, !description.isEmpty {
                    Text(description).scaledFont(12, relativeTo: .caption).foregroundStyle(theme.secondary)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(index % 2 == 1 ? theme.lightBackground : theme.background)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("user-\(username)")
    }
}

// MARK: - Profile

/// The tab feeds of one profile, observed on demand (the ProfileModel owns and closes the feed models).
@MainActor
final class ProfileFeeds: ObservableObject {
    private var feeds: [String: Observed<FeedState>] = [:]

    func feed(_ tab: ProfileTab, model: ProfileModel) -> Observed<FeedState>? {
        if let f = feeds[tab.key] { return f }
        guard let m = model.feed(tab: tab) else { return nil }
        let f = Observed(m, ownsModel: false)
        feeds[tab.key] = f
        return f
    }
}

/// RN profile: summary (cover, avatar, Follow / Message / email digest / menu or Edit, name, bio, date, website,
/// location, follower counts, voting power and RC), then the tabs over the list. The summary sits above the list and
/// closes as it scrolls down, opening near the top, after a drag up or on a tap on the user in the bar (RN profileView,
/// `SummaryCollapseDriver`); the tabs stay.
struct ProfileScreen: View {
    @StateObject private var profile: Observed<ProfileState>
    @StateObject private var feeds = ProfileFeeds()
    @StateObject private var collapse = SummaryCollapseDriver()
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @Environment(\.motion) private var motion
    @State private var confirmReport = false
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.verticalSizeClass) private var verticalSizeClass

    init(author: String, section: String, env: AppEnvironment) {
        _profile = StateObject(wrappedValue: Observed(env.profileModel(username: author, section: section), ownsModel: true))
    }

    private var model: ProfileModel { profile.model as! ProfileModel }

    var body: some View {
        let state = profile.state
        GeometryReader { geo in
            VStack(spacing: 0) {
                if let header = state.header {
                    if collapse.open {
                        CappedHeader(maxHeight: geo.size.height * 0.7) {
                            VStack(spacing: 0) {
                                ProfileSummaryView(state: state, header: header, model: model, onReport: { confirmReport = true })
                                // RN NewsletterSenderInfo: the own creator list's subscribers, link and "Manage"
                                if state.isOwn { DigestSenderRow(username: state.username, env: app.env) }
                            }
                        }
                        .clipped()
                        .transition(motion.revealTransition)
                    }
                    if !state.isMuted {
                        // another tab's list: the summary opens over it (the three ways in agree)
                        ProfileTabsView(state: state) { i in
                            if i != Int(state.selectedTab) { collapse.resetForSize(SummarySizeRule.holds(typeSize, verticalSizeClass)) }
                            model.selectTab(index: Int32(i))
                        }
                    }
                    list(state, width: geo.size.width)
                } else if let error = state.error {
                    ErrorStateView(error: error) { model.retry() }.padding(.top, 80)
                    Spacer(minLength: 0)
                } else {
                    ProfileHeaderSkeleton().accessibilityIdentifier("profile-loading")
                    Spacer(minLength: 0)
                }
            }
            .animation(motion.reveal, value: collapse.open)
        }
        .background(theme.background)
        .navigationTitle(state.header?.displayName ?? "@\(state.username)")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // RN Header with the selected user while the summary is closed; a tap opens it again
            if !collapse.open, let header = state.header {
                ToolbarItem(placement: .principal) {
                    Button { collapse.expand() } label: {
                        HStack(spacing: 8) {
                            AvatarView(url: header.avatarUrl, size: 28)
                            Text(header.displayName).scaledFont(16, weight: .bold).foregroundStyle(theme.text).lineLimit(1)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityValue(L.Native.Ios.A11yShell.collapsed)
                    .accessibilityIdentifier("profile-compact-header")
                }
            }
        }
        .onAppear { model.start() }
        // at accessibility text sizes the open summary and the tabs would leave the list no room (SummarySizeRule)
        .closesSummaryForSize(collapse)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("profile")
        .alert(ProfileUiCopy.shared.CONFIRM_REPORT, isPresented: $confirmReport) {
            Button(UiCopy.shared.CANCEL, role: .cancel) {}
            Button(UiCopy.shared.CONFIRM) { model.report() }.accessibilityIdentifier("confirm-report")
        } message: { Text(ProfileUiCopy.shared.REPORT_CONFIRM) }
        .alert(UiCopy.shared.FAIL, isPresented: Binding(get: { profile.state.alert != nil }, set: { if !$0 { model.dismissAlert() } })) {
            Button(UiCopy.shared.OK, role: .cancel) { model.dismissAlert() }
        } message: { Text(profile.state.alert?.message ?? ProfileUiCopy.shared.AN_ERROR_OCCURRED) }
    }

    /// The selected tab's list (or the muted view), lazy, under the tabs.
    private func list(_ state: ProfileState, width: CGFloat) -> some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 0) {
                    Color.clear.frame(height: 0).id("profile-top")
                    if state.isMuted {
                        Image(appIcon: .muted).scaledFont(96, relativeTo: .largeTitle).foregroundStyle(theme.icon)
                            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                            .padding(.top, 48).accessibilityLabel(UiCopy.shared.MUTED).accessibilityIdentifier("muted-view")
                    } else {
                        tabContent(state, width: width)
                    }
                }
                .reportsScrollOffset("profile-scroll")
            }
            .refreshable { model.refresh() }
            .collapsesSummary(collapse, space: "profile-scroll")
            // RN postsTabContent's ScrollTopPopup (the profile's post tabs); the top opens the summary
            .scrollTopPopup(space: "profile-scroll", id: "profile-scroll-top") {
                collapse.expand()
                proxy.scrollTo("profile-top", anchor: .top)
            }
            .accessibilityIdentifier("profile-list")
        }
    }

    @ViewBuilder private func tabContent(_ state: ProfileState, width: CGFloat) -> some View {
        let tab = state.tab
        if tab == .waves {
            ProfileWavesTab(model: model.waves(), username: state.username, width: width).id("waves")
        } else if tab == .wallet {
            if state.isOwn {
                PlaceholderTab(title: ProfileUiCopy.shared.WALLET, text: ProfileUiCopy.shared.YOUR_WALLET_IS_ON, action: ProfileUiCopy.shared.OPEN_WALLET) { app.selectTab(.wallet) }
            } else {
                // RN profile wallet tab of another account: unclaimed rewards and Wallet Details, read only
                ProfileWalletTab(username: state.username, env: app.env).id("wallet-\(state.username)")
            }
        } else if let feed = feeds.feed(tab, model: model) {
            ProfileTabFeed(feed: feed, tab: tab, username: state.username, width: width).id(tab.key)
        }
    }
}

private struct PlaceholderTab: View {
    let title: String
    let text: String
    let action: String?
    let onAction: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        VStack(spacing: 8) {
            Text(title).scaledFont(18, weight: .bold).foregroundStyle(theme.text)
            Text(text).scaledFont(15).foregroundStyle(theme.secondary).multilineTextAlignment(.center)
            if let action { Button(action, action: onAction).accessibilityIdentifier("placeholder-action") }
        }
        .padding(32)
        .frame(maxWidth: .infinity)
        .accessibilityIdentifier("placeholder-tab")
    }
}

/// The selected tab's list: post cards (blog, posts) or comment rows (comments, replies), paged at the end.
private struct ProfileTabFeed: View {
    @ObservedObject var feed: Observed<FeedState>
    let tab: ProfileTab
    let username: String
    let width: CGFloat
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    private var model: FeedModel { feed.model as! FeedModel }

    var body: some View {
        let state = feed.state
        Group {
            if state.items.isEmpty {
                if let error = state.error {
                    ErrorStateView(error: error) { model.retry() }.padding(.top, 32)
                } else if state.loaded {
                    Text(tab.isComments ? ProfileUiCopy.shared.userHasNoComments(username: username) : UiCopy.shared.userHasNoPosts(username: username))
                        .foregroundStyle(theme.secondary).esieAbove(.empty).padding(.top, 32)
                } else {
                    ProgressView().padding(.top, 32)
                }
            }
            ForEach(state.items, id: \.key) { card in
                Group {
                    if tab.isComments {
                        ProfileCommentRow(card: card)
                    } else {
                        PostCardView(card: card, width: width)
                        Rectangle().fill(theme.lightBackground).frame(height: 4)
                    }
                }
                .onAppear { if FeedPaging.shouldLoadMore(appearing: card, in: state) { model.loadMore() } }
            }
            if !state.items.isEmpty {
                Color.clear.frame(height: 1).id("page-\(state.pages)")
                    .onAppear { if FeedPaging.shouldLoadMoreAtEnd(state) { model.loadMore() } }
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

/// A comment of the comments / replies tab: author, age, text and counts; a tap opens it.
private struct ProfileCommentRow: View {
    let card: PostCard
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    var body: some View {
        Button { app.openPost(card.key) } label: {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    AvatarView(url: card.avatarUrl, size: 24)
                    Text(card.author).scaledFont(14, weight: .bold).foregroundStyle(theme.text)
                    Text(CardText.age(card)).scaledFont(12, relativeTo: .caption).foregroundStyle(theme.secondary)
                }
                if !card.title.isEmpty {
                    Text(card.title).scaledFont(13).foregroundStyle(theme.secondary).lineLimit(1)
                }
                Text(card.summary).scaledFont(15).foregroundStyle(theme.text).lineLimit(4).multilineTextAlignment(.leading)
                Text(L.Native.Ios.Profile.commentStats(payout: card.payoutText, votes: Int(card.votes), replies: Int(card.comments)))
                    .scaledFont(12, relativeTo: .caption).foregroundStyle(theme.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("comment-\(card.key.author)/\(card.key.permlink)")
        Rectangle().fill(theme.lightBackground).frame(height: 1)
    }
}

private struct ProfileTabsView: View {
    let state: ProfileState
    let onSelect: (Int) -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        FittingSegments {
            ForEach(Array(state.tabs.enumerated()), id: \.offset) { i, tab in
                let selected = i == Int(state.selectedTab)
                Button { onSelect(i) } label: {
                    VStack(spacing: 0) {
                        Text(tab.label).font(.subheadline.weight(selected ? .bold : .regular))
                            .foregroundStyle(selected ? theme.accentText : theme.secondary).fixedSize()
                            .padding(.vertical, 12).padding(.horizontal, 10)
                        Rectangle().fill(selected ? theme.accent : Color.clear).frame(height: 2)
                    }
                    .frame(maxWidth: .infinity, minHeight: minTapTarget)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("tab-\(tab.key)")
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .background(theme.background)
        .overlay(alignment: .bottom) { Rectangle().fill(theme.lightBackground).frame(height: 1) }
    }
}

private struct ProfileSummaryView: View {
    let state: ProfileState
    let header: ProfileHeader
    let model: ProfileModel
    let onReport: () -> Void
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @Environment(\.motion) private var motion
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.colorScheme) private var scheme
    @State private var showBarText = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ZStack {
                Rectangle().fill(scheme == .dark ? Color(hex: 0x2E3D51) : Color(hex: 0xE6EEF9))
                if !header.coverUrl.isEmpty {
                    LoadedImageView(src: header.coverUrl, maxWidth: UIScreen.main.bounds.width, contentMode: .scaleAspectFill) { _ in }
                        .accessibilityIdentifier("cover")
                }
            }
            .frame(height: 160)
            .clipped()
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("profile-cover")

            // the action pills go under the avatar at accessibility text sizes
            AdaptiveStack(horizontalAlignment: .leading, verticalAlignment: .bottom) {
                AvatarView(url: header.avatarUrl, size: 74)
                    .padding(3)
                    .background(theme.background, in: Circle())
                Spacer(minLength: 0)
                actions.padding(.bottom, 4)
            }
            .padding(.horizontal, 16)
            .padding(.top, -40)

            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 4) {
                    Text(header.displayName).scaledFont(20, weight: .bold).foregroundStyle(theme.text).lineLimit(2)
                        .accessibilityAddTraits(.isHeader)
                        .accessibilityIdentifier("profile-name")
                    if state.isPro {
                        Image(appIcon: .verified, filled: true).foregroundStyle(theme.accentText).accessibilityLabel(ProfileUiCopy.shared.ECENCY_PRO_MEMBER)
                            .accessibilityIdentifier("pro-badge")
                    }
                    if OfficialAccounts.shared.isOfficial(username: header.username) { OfficialBadge() }
                }
                Text("@\(header.username)").scaledFont(14).foregroundStyle(theme.secondary)
                    .accessibilityIdentifier("profile-username")
                if let about = header.about {
                    Text(about).scaledFont(14).foregroundStyle(theme.text).lineLimit(3).padding(.top, 6)
                        .accessibilityIdentifier("profile-about")
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)

            VStack(alignment: .leading, spacing: 2) {
                if let joined = header.joined { meta(.calendar, ProfileDates.local(joined)) }
                if let website = header.website {
                    if let url = header.websiteUrl.flatMap(URL.init(string:)) {
                        Button { app.sheetURL(url) } label: { meta(.globe, website, link: true).frame(minHeight: minTapTarget).contentShape(Rectangle()) }
                            .buttonStyle(.plain)
                            .accessibilityLabel("\(L.Profile.Edit.website): \(website)")
                            .accessibilityIdentifier("website")
                    } else {
                        meta(.globe, website).accessibilityElement(children: .ignore).accessibilityLabel("\(L.Profile.Edit.website): \(website)")
                    }
                }
                if let location = header.location {
                    meta(.location, location).accessibilityElement(children: .ignore).accessibilityLabel("\(L.Profile.Edit.location): \(location)").accessibilityAddTraits(.isStaticText)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)

            // side by side, or one above the other when they do not fit (largest text sizes, long translations)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 16) { followStats(header) }
                VStack(alignment: .leading, spacing: 4) { followStats(header) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 12)

            Button { showBarText.toggle() } label: {
                VStack(spacing: 2) {
                    if showBarText {
                        Text(header.votingPowerText).scaledFont(11, relativeTo: .caption2).foregroundStyle(theme.accentText).accessibilityIdentifier("vp-text")
                    }
                    bar(header.votingPower, track: theme.accentText.opacity(0.2), fill: theme.accentText)
                    bar(header.resourceCredits, track: scheme == .dark ? Color(hex: 0x333E47) : Color(hex: 0xEAFCEF), fill: Color(hex: 0x11C28B))
                    if showBarText {
                        Text(header.resourceCreditsText).scaledFont(11, relativeTo: .caption2).foregroundStyle(Color(hex: scheme == .dark ? 0x11C28B : ContrastPalette.lightRcText)).accessibilityIdentifier("rc-text")
                    }
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("mana-bars")
            .accessibilityLabel("\(header.votingPowerText), \(header.resourceCreditsText)")
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("profile-summary")
    }

    @ViewBuilder private var actions: some View {
        HStack(spacing: 8) {
            if state.showsActions {
                pill(state.followLabel, id: "follow") { Haptics.play(.toggle); model.toggleFollow() }
                pill(UiCopy.shared.MESSAGE, id: "message") { app.openDirectMessage(state.username) }
                // RN profileSummary: the creator digest sheet of that account
                Button { app.env.digest.show(type: "creator", target: state.username, firstPublish: false) } label: {
                    Image(appIcon: .email).scaledFont(22).foregroundStyle(theme.accentText).tapTarget()
                }
                .disabled(state.busy).accessibilityLabel(ProfileUiCopy.shared.EMAIL_DIGEST)
                .largeContent(ProfileUiCopy.shared.EMAIL_DIGEST, appIcon: .email)
                .accessibilityIdentifier("email-digest")
                if state.busy {
                    ProgressView().accessibilityIdentifier("profile-busy")
                } else {
                    Menu {
                        ForEach(state.menu, id: \.self) { item in
                            Button(state.menuLabel(item: item)) { select(item) }
                        }
                    } label: {
                        Image(appIcon: .more).scaledFont(20).foregroundStyle(theme.secondary).tapTarget()
                    }
                    .accessibilityLabel(ProfileUiCopy.shared.MENU)
                    .largeContent(ProfileUiCopy.shared.MENU, appIcon: .more)
                    .accessibilityIdentifier("profile-menu")
                }
            }
            if state.isOwn {
                pill(UiCopy.shared.EDIT, id: "edit-profile") { app.push(AppScreenProfileEdit.shared) }
            }
        }
        .disabled(state.busy)
    }

    private func select(_ item: ProfileMenuItem) {
        switch item {
        case .favorite: model.toggleFavorite()
        case .delegate: app.push(AppScreenCompanion.shared.of(route: .transfer, arg: state.username))
        case .mute: model.toggleMute()
        case .report: onReport()
        default: break
        }
    }

    private func pill(_ text: String, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            // a new label (Follow, Unfollow) crossfades in place; busy dims (Motion.swift)
            Text(text).scaledFont(13, weight: .bold).foregroundStyle(theme.text)
                .contentTransition(.opacity)
                .padding(.horizontal, 16).padding(.vertical, 6)
                .overlay(Capsule().stroke(theme.icon, lineWidth: 1))
                .frame(minHeight: minTapTarget).contentShape(Rectangle())
                .popOnChange(text)
        }
        .buttonStyle(.plain)
        .opacity(state.busy ? 0.5 : 1)
        .animation(motion.fade(), value: text)
        .animation(motion.fade(), value: state.busy)
        .accessibilityIdentifier(id)
    }

    private func meta(_ icon: AppIcon, _ text: String, link: Bool = false) -> some View {
        HStack(spacing: 4) {
            Image(appIcon: icon).scaledFont(12).foregroundStyle(theme.icon).accessibilityHidden(true)
            Text(text).scaledFont(13).foregroundStyle(link ? theme.accentText : theme.secondary).lineLimit(typeSize.isAccessibilitySize ? nil : 2)
        }
    }

    @ViewBuilder
    private func followStats(_ header: ProfileHeader) -> some View {
        Button { app.push(AppScreenFollows(username: state.username, following: false, count: header.followers)) } label: {
            stat(header.followersText, UiCopy.shared.FOLLOWER)
        }.buttonStyle(.plain).accessibilityIdentifier("followers")
        Button { app.push(AppScreenFollows(username: state.username, following: true, count: header.following)) } label: {
            stat(header.followingText, UiCopy.shared.FOLLOWING)
        }.buttonStyle(.plain).accessibilityIdentifier("following")
    }

    private func stat(_ count: String, _ label: String) -> some View {
        HStack(spacing: 4) {
            Text(count).font(.subheadline.bold()).foregroundStyle(theme.text).fixedSize()
            Text(label).font(.subheadline).foregroundStyle(theme.secondary).fixedSize()
        }
        .frame(minHeight: minTapTarget)
        .contentShape(Rectangle())
    }

    private func bar(_ percent: String, track: Color, fill: Color) -> some View {
        let fraction = min(max((Double(percent) ?? 0) / 100, 0), 1)
        return GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(track)
                Capsule().fill(fill).frame(width: g.size.width * fraction)
            }
        }
        .frame(height: 2)
    }
}

/// The join date in the device's locale (RN toLocaleDateString). Pure, unit tested.
enum ProfileDates {
    /// The join date in the app language ("May 1, 2018", "1. Mai 2018"), or in [language] (an app language id).
    static func local(_ d: CalendarDate, language: String? = nil) -> String {
        let f = language.map { LocaleFormatter.companion.of(languageId: $0) } ?? LocaleFormat.shared.formatter
        return f.calendarDate(year: d.year, month: d.month, day: d.day, pattern: .date)
    }
}

// MARK: - Followers / following

/// RN followsScreen: "Follower (12)", a search over the loaded rows, paging at the end; a row opens the profile.
struct FollowsScreen: View {
    @StateObject private var follows: Observed<FollowsState>
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @State private var query = ""

    init(username: String, following: Bool, count: Int32, env: AppEnvironment) {
        _follows = StateObject(wrappedValue: Observed(env.followsModel(username: username, following: following, count: count), ownsModel: true))
    }

    private var model: FollowsModel { follows.model as! FollowsModel }

    var body: some View {
        let state = follows.state
        ScrollView {
            LazyVStack(spacing: 0) {
                if state.isLoading { ProgressView().padding(.top, 32) }
                if let empty = state.emptyText {
                    VStack(spacing: 8) {
                        EsieView(state.error != nil && state.rows.isEmpty ? .error : .empty)
                        Text(empty).foregroundStyle(theme.secondary)
                        if state.error != nil, state.rows.isEmpty { Button(UiCopy.shared.RETRY) { model.retry() }.accessibilityIdentifier("retry") }
                    }
                    .padding(.top, 32)
                    .accessibilityIdentifier("follows-empty")
                }
                ForEach(Array(state.visible.enumerated()), id: \.element.name) { i, row in
                    Button { app.open(.profile(author: row.name)) } label: { UserRow(index: i, username: row.name) }
                        .buttonStyle(.plain)
                        .onAppear { if state.query.isEmpty, i >= state.visible.count - 5 { model.loadMore() } }
                }
                if !state.rows.isEmpty {
                    if state.isLoadingMore {
                        ProgressView().padding(24)
                    } else if let error = state.error {
                        InlineErrorRow(error: error) { model.retry() }
                    }
                }
            }
        }
        .background(theme.background)
        .searchable(text: $query)
        .onChange(of: query) { _, q in model.search(query: q) }
        .navigationTitle(state.title)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.start() }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("follows")
    }
}

// MARK: - Profile edit

/// RN profileEdit: avatar and cover (image sources; a link works until uploads come with the editor wave),
/// Display Name, About, Location, Website, and SAVE once something changed.
struct ProfileEditScreen: View {
    @StateObject private var edit: Observed<ProfileEditState>
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var source: ProfileField?
    @State private var urlFor: ProfileField?
    @State private var urlText = ""
    /// The picker open for an image field (RN OptionsModal: gallery or camera; Files below, which RN had on Android only).
    @State private var picker: ImagePick?
    /// The Files importer while it is open, and the image field it is for.
    @State private var files: MediaSurface?
    @State private var filesFor: ProfileField?

    private struct ImagePick: Identifiable {
        let field: ProfileField
        let camera: Bool
        var id: String { "\(field.name)-\(camera)" }
    }

    init(env: AppEnvironment) {
        _edit = StateObject(wrappedValue: Observed(env.profileEditModel(), ownsModel: true))
    }

    private var model: ProfileEditModel { edit.model as! ProfileEditModel }

    var body: some View {
        let state = edit.state
        ZStack(alignment: .bottomTrailing) {
            if state.isLoading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if let error = state.loadError {
                ErrorStateView(error: error) { model.retry() }
            } else if state.loaded {
                Form {
                    Section {
                        HStack {
                            Spacer()
                            Button { source = .avatar } label: {
                                AvatarView(url: state.avatarPreview, size: 128)
                                    .overlay { if state.uploading == .avatar { uploadingCover.clipShape(Circle()) } }
                                    .overlay(alignment: .bottomTrailing) { editBadge }
                            }
                            .buttonStyle(.plain)
                            .disabled(state.uploading != nil || state.isSaving)
                            .accessibilityLabel(L.Profile.Edit.profileImg).accessibilityIdentifier("edit-avatar")
                            Spacer()
                        }
                    }
                    .listRowBackground(Color.clear)
                    Section(ProfileUiCopy.shared.COVER_IMAGE) {
                        Button { source = .cover } label: {
                            ZStack(alignment: .bottomTrailing) {
                                RoundedRectangle(cornerRadius: 8).fill(theme.lightBackground)
                                if !state.coverPreview.isEmpty {
                                    LoadedImageView(src: state.coverPreview, maxWidth: 400, contentMode: .scaleAspectFill) { _ in }
                                        .clipShape(RoundedRectangle(cornerRadius: 8))
                                }
                                if state.uploading == .cover { uploadingCover.clipShape(RoundedRectangle(cornerRadius: 8)) }
                                editBadge.padding(8)
                            }
                            .frame(height: 140)
                        }
                        .buttonStyle(.plain)
                        .disabled(state.uploading != nil || state.isSaving)
                        .accessibilityLabel(ProfileUiCopy.shared.COVER_IMAGE).accessibilityIdentifier("edit-cover")
                    }
                    ForEach(ProfileField.companion.form, id: \.self) { field in
                        Section(field.label) {
                            TextField(field.label, text: Binding(get: { edit.state.value(field: field) }, set: { model.setValue(field: field, value: $0) }),
                                      axis: field == .about ? .vertical : .horizontal)
                                .disabled(state.isSaving)
                                .accessibilityIdentifier("field-\(field.name)")
                        }
                    }
                }
                .scrollContentBackground(.hidden)
            }
            if state.saveEnabled || state.isSaving {
                Button { model.save(close: true) } label: {
                    Group {
                        if state.isSaving { ProgressView().tint(.white) } else { Label(UiCopy.shared.SAVE_CAPS, appIcon: .download).bold() }
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 24).padding(.vertical, 12)
                    .background(theme.accent, in: Capsule())
                }
                .disabled(state.isSaving)
                .padding(24)
                .accessibilityIdentifier("save-profile")
            }
        }
        .background(theme.background)
        .navigationTitle(ProfileUiCopy.shared.EDIT_PROFILE)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.start() }
        .onChange(of: state.saved) { _, saved in if saved { dismiss() } }
        // RN OptionsModal (gallery, camera) plus Files (RN #3561) and the link entry, as the shared chooser sheet
        .mediaSourceChooser(
            item: $source, title: { $0.label }, options: { _ in MediaSourceMenus.shared.profileImage(camera: CameraPicker.isAvailable) },
            identifier: { source in
                switch source {
                case .camera: return "source-camera"
                case .files: return "source-files"
                case .link: return "source-link"
                default: return "source-gallery"
                }
            }
        ) { field, from in
            switch from {
            case .camera: picker = ImagePick(field: field, camera: true)
            case .files: filesFor = field; files = field == .avatar ? .avatar : .cover
            case .link: urlFor = field; urlText = edit.state.value(field: field)
            default: picker = ImagePick(field: field, camera: false)
            }
        }
        .mediaFileImporter(surface: $files) { _, images in
            guard let field = filesFor, let first = images.first else { return }
            filesFor = nil
            Task.detached(priority: .userInitiated) {
                let image = field == .avatar ? EditorImages.square(first) : first
                await MainActor.run {
                    if let image { model.uploadImage(field: field, image: image) } else { _ = app.env.ui.toast(text: ProfileEditCopy.shared.INVALID_IMAGE) }
                }
            }
        }
        .sheet(item: $picker) { pick in
            let picked: ([PickedImage]) -> Void = { images in
                picker = nil
                guard let first = images.first else {
                    _ = app.env.ui.toast(text: ProfileEditCopy.shared.INVALID_IMAGE)
                    return
                }
                let field = pick.field
                // the avatar is cropped square off the main thread, then uploaded by the model
                Task.detached(priority: .userInitiated) {
                    let image = field == .avatar ? EditorImages.square(first) : first
                    await MainActor.run {
                        if let image { model.uploadImage(field: field, image: image) } else { _ = app.env.ui.toast(text: ProfileEditCopy.shared.INVALID_IMAGE) }
                    }
                }
            }
            if pick.camera {
                CameraPicker(onPicked: picked).ignoresSafeArea()
            } else {
                PhotoPicker(limit: 1, onPicked: picked).ignoresSafeArea()
            }
        }
        .alert(urlFor?.label ?? "", isPresented: Binding(get: { urlFor != nil }, set: { if !$0 { urlFor = nil } })) {
            TextField("https://", text: $urlText).textInputAutocapitalization(.never).autocorrectionDisabled().leftToRightField().accessibilityIdentifier("image-url")
            Button(UiCopy.shared.CANCEL, role: .cancel) { urlFor = nil }
            Button(UiCopy.shared.OK) {
                if let field = urlFor { _ = model.setImageUrl(field: field, url: urlText) }
                urlFor = nil
            }
        }
        .alert(UiCopy.shared.FAIL, isPresented: Binding(get: { edit.state.alert != nil }, set: { if !$0 { model.dismissAlert() } })) {
            Button(UiCopy.shared.OK, role: .cancel) { model.dismissAlert() }
        } message: { Text(edit.state.alert?.message ?? "") }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("profile-edit")
    }

    private var editBadge: some View {
        Image(appIcon: .edit).scaledFont(14, weight: .bold).foregroundStyle(.white)
            .frame(width: 32, height: 32).background(theme.accent, in: Circle())
            // a ring of the page colour keeps the pen apart from the picture under it (as Android)
            .overlay(Circle().stroke(theme.background, lineWidth: 2))
    }

    /// RN editAvatar / profileEditForm while uploading: the image dimmed under a spinner.
    private var uploadingCover: some View {
        ZStack {
            Color.black.opacity(0.4)
            ProgressView().tint(.white)
        }
        .accessibilityIdentifier("image-uploading")
    }
}

// MARK: - Bookmarks

/// RN bookmarks: Bookmarks, Favorites and Tags; a long press asks to remove.
struct BookmarksScreen: View {
    @StateObject private var bookmarks: Observed<BookmarksState>
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @State private var removal: AnyObject?

    init(tab: Int32, env: AppEnvironment) {
        _bookmarks = StateObject(wrappedValue: Observed(env.bookmarksModel(tab: tab), ownsModel: true))
    }

    private var model: BookmarksModel { bookmarks.model as! BookmarksModel }

    var body: some View {
        let state = bookmarks.state
        let tab = state.tab
        VStack(spacing: 0) {
            Picker(ProfileUiCopy.shared.BOOKMARKS, selection: Binding(get: { Int(bookmarks.state.selected) }, set: { model.select(index: Int32($0)) })) {
                ForEach(Array(BookmarksTab.entries.enumerated()), id: \.offset) { i, t in Text(t.label).tag(i) }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16).padding(.vertical, 8)
            .accessibilityIdentifier("bookmarks-tabs")
            ScrollView {
                LazyVStack(spacing: 0) {
                    if state.isLoading(tab: tab) && count(state, tab) == 0 { ProgressView().padding(.top, 32) }
                    if let error = state.error(tab: tab), count(state, tab) == 0 {
                        ErrorStateView(error: error) { model.retry(tab: tab) }.padding(.top, 32)
                    }
                    if state.isEmpty(tab: tab) {
                        Text(UiCopy.shared.NOTHING_HERE).foregroundStyle(theme.secondary).accessibilityIdentifier("bookmarks-empty").esieAbove(.empty).padding(.top, 32)
                    }
                    rows(state, tab)
                }
            }
            .refreshable { model.refresh() }
            // one list per tab: rows of the three lists share offsets, so a shared stack would keep the old rows
            .id(tab.name)
        }
        .background(theme.background)
        .navigationTitle(ProfileUiCopy.shared.BOOKMARKS)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.start() }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("bookmarks")
        .alert(UiCopy.shared.REMOVE_CONFIRM, isPresented: Binding(get: { removal != nil }, set: { if !$0 { removal = nil } })) {
            Button(UiCopy.shared.CANCEL, role: .cancel) { removal = nil }
            Button(UiCopy.shared.DELETE, role: .destructive) { remove() }.accessibilityIdentifier("confirm-remove")
        }
    }

    private func count(_ s: BookmarksState, _ tab: BookmarksTab) -> Int {
        switch tab {
        case .bookmarks: return s.bookmarks.items.count
        case .favorites: return s.favorites.items.count
        default: return s.tags.items.count
        }
    }

    @ViewBuilder private func rows(_ state: BookmarksState, _ tab: BookmarksTab) -> some View {
        switch tab {
        case .bookmarks:
            let items = state.bookmarks.items as? [Bookmark] ?? []
            ForEach(Array(items.enumerated()), id: \.offset) { i, b in
                UserRow(index: i, username: b.author, text: "\(b.author)/\(b.permlink)")
                    .onTapGesture { app.open(.post(author: b.author, permlink: b.permlink)) }
                    .onLongPressGesture { removal = b }
                    // VoiceOver: the row opens on a double tap; the long press removal is a custom action
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAction(named: Text(L.Native.Ui.remove)) { removal = b }
                    .onAppear { if i == items.count - 1 { model.loadMore(tab: tab) } }
            }
        case .favorites:
            let items = state.favorites.items as? [Favorite] ?? []
            ForEach(Array(items.enumerated()), id: \.offset) { i, f in
                UserRow(index: i, username: f.account)
                    .onTapGesture { app.open(.profile(author: f.account)) }
                    .onLongPressGesture { removal = f }
                    // VoiceOver: the row opens on a double tap; the long press removal is a custom action
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAction(named: Text(L.Native.Ui.remove)) { removal = f }
                    .onAppear { if i == items.count - 1 { model.loadMore(tab: tab) } }
            }
        default:
            let items = state.tags.items as? [FavoriteTag] ?? []
            ForEach(items, id: \.tag) { t in
                Text("#\(t.tag)").scaledFont(16).foregroundStyle(theme.text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16).padding(.vertical, 14)
                    .contentShape(Rectangle())
                    .onTapGesture { app.open(.feed(tag: t.tag, sort: RankedSort.trending.apiValue, title: "#\(t.tag)")) }
                    .onLongPressGesture { removal = t }
                    // VoiceOver: the row opens on a double tap; the long press removal is a custom action
                    .accessibilityAddTraits(.isButton)
                    .accessibilityAction(named: Text(L.Native.Ui.remove)) { removal = t }
                    .accessibilityIdentifier("tag-\(t.tag)")
                Rectangle().fill(theme.lightBackground).frame(height: 1)
            }
        }
    }

    private func remove() {
        switch removal {
        case let b as Bookmark: model.removeBookmark(bookmark: b)
        case let f as Favorite: model.removeFavorite(favorite: f)
        case let t as FavoriteTag: model.removeTag(tag: t)
        default: break
        }
        removal = nil
    }
}

// MARK: - Account list

/// RN accountList: a search over the given users ("Users (3)"), rows with how long ago.
struct AccountListScreen: View {
    let title: String
    let users: [AccountListEntry]
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @State private var query = ""

    var body: some View {
        let header = title.isEmpty ? ProfileUiCopy.shared.USERS : title
        let visible = AccountListFilter.shared.filter(entries: users, query: query)
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(Array(visible.enumerated()), id: \.element.account) { i, u in
                    Button { app.open(.profile(author: u.account)) } label: {
                        UserRow(index: i, username: u.account, description: u.timestampMillis > 0
                            ? TimeAgo.shared.short(epochMillis: u.timestampMillis, nowMillis: Int64(Date().timeIntervalSince1970 * 1000)) : nil)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.top, 16)
        }
        .background(theme.background)
        .searchable(text: $query, prompt: "\(header) (\(users.count))")
        .navigationTitle(header)
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("account-list")
    }
}
