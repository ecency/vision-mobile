import EcencyCore
import SwiftUI

// Communities, community page, members and roles, settings and activity log (README "Communities and search").
// The models are shared (CommunityModels.kt); these views only draw them and forward taps.

/// Feed models of a screen's tabs (community, tag), created on first use and closed with the screen.
@MainActor
final class TabFeeds: ObservableObject {
    private var feeds: [String: Observed<FeedState>] = [:]

    func feed(_ key: String, source: FeedSource, env: AppEnvironment) -> Observed<FeedState> {
        if let feed = feeds[key] { return feed }
        let feed = Observed(env.feedModel(source: source), ownsModel: true)
        feeds[key] = feed
        return feed
    }
}

/// RN's filter Tag: a pill, blue filled or blue outlined.
struct PillButton: View {
    let label: String
    let filled: Bool
    let action: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        Button(action: action) {
            Text(label).scaledFont(13, weight: .bold)
                .foregroundStyle(filled ? Color.white : theme.accentText)
                .padding(.horizontal, 14).padding(.vertical, 5)
                .background(Capsule().fill(filled ? theme.accent : Color.clear))
                .overlay(Capsule().stroke(theme.accent, lineWidth: 1))
                .frame(minHeight: minTapTarget).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Join (filled) or Leave (outlined), a spinner while the change runs.
struct JoinButton: View {
    let subscribed: Bool
    let pending: Bool
    let action: () -> Void

    var body: some View {
        if pending {
            ProgressView().frame(width: 65).accessibilityIdentifier("join-pending")
        } else {
            PillButton(label: subscribed ? CommunityCopy.shared.LEAVE : CommunityCopy.shared.JOIN, filled: !subscribed, action: action)
                .accessibilityIdentifier("join")
        }
    }
}

/// Tabs side by side at equal widths while every label fits on one line; otherwise (long languages, large text)
/// each tab takes its own width in a row that scrolls sideways. Labels never break inside a word.
struct FittingSegments<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        ViewThatFits(in: .horizontal) {
            EqualSegmentsLayout { content }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 0) { content }
            }
        }
    }
}

/// Every child as wide as the widest one: its ideal width is that times the count, so ViewThatFits picks it only
/// when all labels fit; placed, the children share the offered width equally.
struct EqualSegmentsLayout: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let sizes = subviews.map { $0.sizeThatFits(.unspecified) }
        let widest = sizes.map(\.width).max() ?? 0
        let height = sizes.map(\.height).max() ?? 0
        let ideal = widest * CGFloat(subviews.count)
        return CGSize(width: max(ideal, proposal.width.map { $0.isFinite ? $0 : ideal } ?? ideal), height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard !subviews.isEmpty else { return }
        let width = bounds.width / CGFloat(subviews.count)
        let height = subviews.map { $0.sizeThatFits(.unspecified).height }.max() ?? bounds.height
        for (i, view) in subviews.enumerated() {
            view.place(at: CGPoint(x: bounds.minX + CGFloat(i) * width, y: bounds.minY), proposal: ProposedViewSize(width: width, height: height))
        }
    }
}

/// RN TabBar: equal tabs, the selected one underlined.
struct UnderlineTabs: View {
    let labels: [String]
    @Binding var selected: Int
    var idPrefix = "tab"
    @Environment(\.appTheme) private var theme

    var body: some View {
        FittingSegments {
            ForEach(Array(labels.enumerated()), id: \.offset) { i, label in
                Button { selected = i } label: {
                    VStack(spacing: 8) {
                        // a text style (not a scaled point size): the label follows every text size, the audit's check included
                        Text(label).font(.subheadline.bold()).foregroundStyle(i == selected ? theme.accentText : theme.secondary)
                            .fixedSize().padding(.horizontal, 12)
                        Rectangle().fill(i == selected ? theme.accent : Color.clear).frame(height: 2).padding(.horizontal, 12)
                    }
                    .padding(.top, 10)
                    .frame(maxWidth: .infinity, minHeight: minTapTarget)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(i == selected ? .isSelected : [])
                .accessibilityIdentifier("\(idPrefix)-\(label)")
            }
        }
        .background(theme.background)
    }
}

/// The home feed's pill tabs (TRENDING, HOT, NEW) for the community and tag screens.
struct PillTabs: View {
    let labels: [String]
    @Binding var selected: Int
    @Environment(\.appTheme) private var theme

    var body: some View {
        FittingSegments {
            ForEach(Array(labels.enumerated()), id: \.offset) { i, label in
                Button { selected = i } label: {
                    Text(label.uppercased()).font(.subheadline.bold())
                        .foregroundStyle(i == selected ? Color.white : theme.secondary)
                        .fixedSize()
                        .padding(.horizontal, 20).padding(.vertical, 4)
                        .background(Capsule().fill(i == selected ? theme.accent : Color.clear))
                        .frame(maxWidth: .infinity, minHeight: minTapTarget)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(i == selected ? .isSelected : [])
                .accessibilityIdentifier("tab-\(label.uppercased())")
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 4)
        .background(theme.lightBackground)
    }
}

/// RN's discover row: avatar, title with the join button, about, separator, stats.
struct CommunityListRow: View {
    let row: CommunityRow
    let index: Int
    var showJoin = true
    let onOpen: () -> Void
    let onJoin: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            AvatarView(url: PostCard.companion.avatarUrl(account: row.name, proxyBase: ImageProxy.companion.DEFAULT_BASE, size: "small"), size: 40)
            VStack(alignment: .leading, spacing: 0) {
                AdaptiveStack(horizontalAlignment: .leading) {
                    // VoiceOver: the title is the row's button (a double tap lands on it and opens the community)
                    Text(row.title).scaledFont(17, weight: .bold).foregroundStyle(theme.accentText).accessibilityAddTraits(.isButton)
                    Spacer(minLength: 0)
                    if showJoin { JoinButton(subscribed: row.subscribed, pending: row.pending, action: onJoin) }
                }
                if !row.about.isEmpty {
                    Text(row.about).scaledFont(14).foregroundStyle(theme.text).padding(.top, 10)
                }
                Rectangle().fill(theme.secondary).frame(width: 100, height: 1).frame(maxWidth: .infinity).padding(.vertical, 10)
                Text(row.stats).scaledFont(14).foregroundStyle(theme.secondary)
            }
        }
        .padding(16)
        .background(index % 2 != 0 ? theme.lightBackground : theme.background)
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("community-\(row.name)")
    }
}

// MARK: Communities

struct CommunitiesScreen: View {
    @EnvironmentObject private var app: AppContainer
    @StateObject private var model: Observed<CommunitiesState>
    @State private var tab = 0
    @Environment(\.appTheme) private var theme

    init(env: AppEnvironment) {
        _model = StateObject(wrappedValue: Observed(CommunitiesModel(env: env), ownsModel: true))
    }

    private var communities: CommunitiesModel { model.model as! CommunitiesModel }

    var body: some View {
        let state = model.state
        VStack(spacing: 0) {
            UnderlineTabs(labels: [CommunityCopy.shared.JOINED_TAB, CommunityCopy.shared.DISCOVER_TAB], selected: $tab)
            List {
                if tab == 0 {
                    if state.joined.isEmpty {
                        if state.joinedLoading { ProgressView().frame(maxWidth: .infinity).padding(.top, 48) }
                        else if let error = state.joinedError { ErrorStateView(error: error, sticky: true) { communities.refresh() } }
                        else {
                            VStack(spacing: 12) {
                                Text(CommunityCopy.shared.NO_COMMUNITIES).multilineTextAlignment(.center).foregroundStyle(theme.secondary)
                                Button(CommunityCopy.shared.DISCOVER_COMMUNITIES) { tab = 1 }.accessibilityIdentifier("discover-communities")
                            }
                            .frame(maxWidth: .infinity).padding(.top, 48)
                        }
                    }
                    ForEach(Array(state.joined.enumerated()), id: \.element.name) { index, row in
                        AdaptiveStack(horizontalAlignment: .leading) {
                            Button { app.push(AppScreenCommunity(name: row.name, filter: "")) } label: {
                                HStack(spacing: 12) {
                                    AvatarView(url: PostCard.companion.avatarUrl(account: row.name, proxyBase: app.env.proxyBase, size: "small"), size: 36)
                                    Text(row.title).scaledFont(15, weight: .bold).foregroundStyle(theme.text)
                                }
                            }
                            .buttonStyle(.plain)
                            Spacer(minLength: 0)
                            JoinButton(subscribed: row.subscribed, pending: row.pending) {
                                if !communities.toggleJoined(row: row) { app.push(AppScreenLogin.shared) }
                            }
                        }
                        .listRowBackground(index % 2 != 0 ? theme.lightBackground : theme.background)
                        .accessibilityIdentifier("joined-\(row.name)")
                    }
                } else {
                    if state.discover.isEmpty {
                        if state.discoverLoading { ProgressView().frame(maxWidth: .infinity).padding(.top, 48) }
                        else if let error = state.discoverError { ErrorStateView(error: error, sticky: true) { communities.refresh() } }
                    }
                    ForEach(Array(state.discover.enumerated()), id: \.element.name) { index, row in
                        CommunityListRow(row: row, index: index, onOpen: { app.push(AppScreenCommunity(name: row.name, filter: "")) }) {
                            if !communities.toggle(row: row) { app.push(AppScreenLogin.shared) }
                        }
                        .listRowInsets(EdgeInsets())
                    }
                }
            }
            .listStyle(.plain)
            .refreshable { communities.refresh() }
        }
        .background(theme.background)
        .navigationTitle(CommunityCopy.shared.TITLE)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { communities.start() }
    }
}

// MARK: Community page

struct CommunityScreen: View {
    @EnvironmentObject private var app: AppContainer
    @StateObject private var model: Observed<CommunityState>
    @StateObject private var feeds = TabFeeds()
    @State private var tab: Int
    @State private var manage = false
    @State private var managePick: CommunityManageAction?
    @StateObject private var collapse = SummaryCollapseDriver()
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Environment(\.appTheme) private var theme
    @Environment(\.motion) private var motion
    @Environment(\.dynamicTypeSize) private var typeSize
    /// RN's 120 pt description box, taller with larger text
    @ScaledMetric(relativeTo: .body) private var descriptionHeight: CGFloat = 120

    init(name: String, filter: String, env: AppEnvironment) {
        _model = StateObject(wrappedValue: Observed(CommunityModel(env: env, name: name), ownsModel: true))
        _tab = State(initialValue: Int(CommunityTab.companion.of(filter: filter).ordinal))
    }

    private var community: CommunityModel { model.model as! CommunityModel }

    var body: some View {
        let state = model.state
        let tabs = CommunityTab.entries
        GeometryReader { geo in
            VStack(spacing: 0) {
                details(state, maxHeight: geo.size.height * 0.7)
                PillTabs(labels: tabs.map { $0.label }, selected: $tab)
                let selected = tabs[tab]
                // RN CollapsibleCard "Details": closes as the feed scrolls down, opens near the top, after a drag up
                // or on a tap on its title, the profile summary's rule (RN closed it on any drag)
                FeedListView(feed: feeds.feed(selected.name, source: community.feedSource(tab: selected), env: app.env), collapse: collapse)
                    .id(selected.name)
            }
            .animation(motion.reveal, value: collapse.open)
        }
        // another tab's list: the card opens over it (the three ways in agree)
        .onChange(of: tab) { collapse.resetForSize(SummarySizeRule.holds(typeSize, verticalSizeClass)) }
        .background(theme.background)
        .navigationTitle(state.headerTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if state.isModerator {
                Button { manage = true } label: { Image(appIcon: .moderator) }
                    .accessibilityLabel(CommunityCopy.shared.MANAGE)
                    .accessibilityIdentifier("community-manage")
            }
        }
        // RN communityManageSheet, in the set role sheet's layout; the pick opens once the sheet is gone
        .sheet(isPresented: $manage, onDismiss: {
            if let action = managePick {
                managePick = nil
                app.push(community.screenFor(action: action))
            }
        }) {
            CommunityManageSheet { action in
                managePick = action
                manage = false
            }
            .presentationDetents([.medium, .large])
        }
        .onAppear {
            community.start()
        }
        // at accessibility text sizes the open card would leave the feed no room (SummarySizeRule)
        .closesSummaryForSize(collapse)
    }

    /// RN CollapsibleCard "Details": description, stats, Join / New Post / Newsletter; the title opens and closes it.
    @ViewBuilder private func details(_ state: CommunityState, maxHeight: CGFloat) -> some View {
        VStack(spacing: 0) {
            Button { collapse.toggle() } label: {
                Text(CommunityCopy.shared.DETAILS).scaledFont(15, weight: .bold).foregroundStyle(theme.text).frame(maxWidth: .infinity).padding(12)
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(.isHeader)
            .accessibilityValue(collapse.open ? L.Native.Ios.A11yShell.expanded : L.Native.Ios.A11yShell.collapsed)
            .accessibilityIdentifier("community-details-toggle")
            if let c = state.community {
                if collapse.open {
                    CappedHeader(maxHeight: maxHeight) {
                    VStack(alignment: .leading, spacing: 10) {
                        ScrollView { Text(c.description_).scaledFont(14).foregroundStyle(theme.text).frame(maxWidth: .infinity, alignment: .leading) }
                            .frame(maxHeight: descriptionHeight)
                            .accessibilityIdentifier("community-description")
                        Divider()
                        Text(state.stats ?? "").scaledFont(14).foregroundStyle(theme.secondary)
                        Divider()
                        ChipFlow(spacing: 8) {
                            if state.loggedIn {
                                JoinButton(subscribed: state.subscribed, pending: state.pending) {
                                    if !community.toggleSubscription() { app.push(AppScreenLogin.shared) }
                                }
                            }
                            PillButton(label: CommunityCopy.shared.NEW_POST, filled: true) { app.push(community.newPostScreen) }
                                .accessibilityIdentifier("community-new-post")
                            if state.loggedIn {
                                PillButton(label: CommunityCopy.shared.NEWSLETTER, filled: false) { app.env.digest.show(type: "community", target: state.name, firstPublish: false) }
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.horizontal, 16).padding(.bottom, 16)
                    }
                    .clipped()
                    .transition(motion.revealTransition)
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("community-details-body")
                }
            } else if let error = state.error {
                ErrorStateView(error: error) { community.retry() }
            } else {
                ProgressView().padding()
            }
        }
        .background(RoundedRectangle(cornerRadius: 8).fill(theme.lightBackground))
        .padding(12)
        // the feed below gives way first: at large text the description and stats keep their room
        .layoutPriority(1)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("community-details")
    }
}

// MARK: Members and roles

struct RolePick: Identifiable {
    /// nil: the moderator types the account.
    let account: String?
    let current: CommunityRole?
    var id: String { account ?? "-" }
}

struct CommunityMembersScreen: View {
    @EnvironmentObject private var app: AppContainer
    @StateObject private var model: Observed<CommunityMembersState>
    @State private var pick: RolePick?
    @Environment(\.appTheme) private var theme

    init(name: String, title: String, env: AppEnvironment) {
        _model = StateObject(wrappedValue: Observed(CommunityMembersModel(env: env, name: name, title: title), ownsModel: true))
    }

    private var members: CommunityMembersModel { model.model as! CommunityMembersModel }

    var body: some View {
        let state = model.state
        List {
            if state.loading { ProgressView().frame(maxWidth: .infinity).padding(.top, 48) }
            if let empty = state.emptyText { Text(empty).foregroundStyle(theme.secondary).esieAbove(.empty).padding(.top, 48) }
            ForEach(Array(state.members.enumerated()), id: \.element.account) { index, member in
                AdaptiveStack(horizontalAlignment: .leading, spacing: 12) {
                    Button { app.push(AppScreenProfile(author: member.account)) } label: {
                        HStack(spacing: 12) {
                            AvatarView(url: PostCard.companion.avatarUrl(account: member.account, proxyBase: app.env.proxyBase, size: "small"), size: 36)
                            VStack(alignment: .leading) {
                                Text("@\(member.account)").scaledFont(15, weight: .bold).foregroundStyle(theme.text)
                                if !member.title.isEmpty { Text(member.title).scaledFont(13).foregroundStyle(theme.secondary) }
                            }
                        }
                    }
                    .buttonStyle(.plain)
                    Spacer(minLength: 0)
                    if state.saving.contains(member.account) {
                        ProgressView()
                    } else {
                        Button { pick = RolePick(account: member.account, current: member.role) } label: {
                            Text(member.roleLabel).scaledFont(12, weight: .bold)
                                .foregroundStyle(member.editable ? theme.accentText : theme.secondary)
                                .padding(.horizontal, 10).padding(.vertical, 4)
                                .background(RoundedRectangle(cornerRadius: 12).fill(theme.lightBackground))
                                .overlay(RoundedRectangle(cornerRadius: 12).stroke(member.editable ? theme.accent : Color.clear))
                        }
                        .buttonStyle(.plain)
                        .disabled(!member.editable)
                        .accessibilityIdentifier("role-\(member.account)")
                    }
                }
                .listRowBackground(index % 2 != 0 ? theme.lightBackground : theme.background)
                // contain: the role chip keeps its own identifier inside the row
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("member-\(member.account)")
                .onAppear { if index >= state.members.count - 3 { members.loadMore() } }
            }
            if state.loadingMore { ProgressView().frame(maxWidth: .infinity) }
            if let note = state.footerNote { Text(note).scaledFont(13).foregroundStyle(theme.secondary) }
        }
        .listStyle(.plain)
        .refreshable { members.refresh() }
        .navigationTitle(state.headerTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !state.assignableRoles.isEmpty {
                Button { pick = RolePick(account: nil, current: nil) } label: { Image(appIcon: .personAdd) }
                    .accessibilityLabel(CommunityCopy.shared.ASSIGN_ROLE)
                    .accessibilityIdentifier("assign-role")
            }
        }
        .sheet(item: $pick) { p in
            RoleSheet(env: app.env, account: p.account, current: p.current, roles: state.assignableRoles) { account, role in
                pick = nil
                if role != p.current { members.setRole(account: account, role: role) }
            }
            .presentationDetents([.medium, .large])
        }
        .alert(CommunityCopy.shared.FAIL, isPresented: Binding(get: { state.alert != nil }, set: { if !$0 { members.dismissAlert() } })) {
            Button(UiCopy.shared.OK, role: .cancel) { members.dismissAlert() }
        } message: { Text(state.alert ?? "") }
        .onAppear { members.start() }
    }
}

/// RN communityRoleEditSheet: a member's role, or any account (exact `lookup_accounts` match) and a role.
struct RoleSheet: View {
    let account: String?
    let current: CommunityRole?
    let roles: [CommunityRole]
    let onPick: (String, CommunityRole) -> Void
    @StateObject private var lookup: Observed<AccountLookupState>
    @State private var error: String?
    @Environment(\.appTheme) private var theme

    init(env: AppEnvironment, account: String?, current: CommunityRole?, roles: [CommunityRole], onPick: @escaping (String, CommunityRole) -> Void) {
        self.account = account
        self.current = current
        self.roles = roles
        self.onPick = onPick
        _lookup = StateObject(wrappedValue: Observed(AccountLookupModel(env: env, maxSuggestions: 6, debounceMillis: 300, limit: 20), ownsModel: true))
    }

    private var model: AccountLookupModel { lookup.model as! AccountLookupModel }

    var body: some View {
        let state = lookup.state
        ModerationSheet(title: account == nil ? CommunityCopy.shared.ASSIGN_ROLE : CommunityCopy.shared.SET_ROLE,
                        subtitle: account.map { "@\($0)" }, cancelId: "role-cancel") {
            if account == nil {
                HStack {
                    TextField(CommunityCopy.shared.USERNAME_PLACEHOLDER, text: Binding(get: { state.input }, set: { error = nil; model.setInput(text: $0) }))
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .leftToRightField()
                        .accessibilityIdentifier("role-account")
                    if state.searching { ProgressView() } else if state.known { Image(appIcon: .checkCircle, filled: true).foregroundStyle(theme.accentText) }
                }
                .padding(12)
                .background(RoundedRectangle(cornerRadius: 8).fill(theme.lightBackground))
                if let error { Text(error).scaledFont(12).foregroundStyle(theme.dangerText).padding(.top, 6).announces(error) }
                if !state.suggestions.isEmpty && !state.known {
                    VStack(alignment: .leading, spacing: 0) {
                        ForEach(state.suggestions, id: \.self) { name in
                            Button { model.pick(name: name) } label: {
                                HStack(spacing: 10) {
                                    AvatarView(url: PostCard.companion.avatarUrl(account: name, proxyBase: ImageProxy.companion.DEFAULT_BASE, size: "small"), size: 24)
                                    Text(name).foregroundStyle(theme.text)
                                    Spacer()
                                }
                                .padding(.horizontal, 12).padding(.vertical, 10)
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("suggestion-\(name)")
                        }
                    }
                    .background(RoundedRectangle(cornerRadius: 8).fill(theme.lightBackground))
                    .padding(.top, 8)
                }
                Text(CommunityCopy.shared.ASSIGN_ROLE_HINT).scaledFont(12).foregroundStyle(theme.secondary).padding(.vertical, 8)
            }
            ForEach(roles, id: \.self) { role in
                let isCurrent = account != nil && role == current
                let blocked = isCurrent || (account == nil && !state.known)
                ModerationRow(label: role.label, checked: isCurrent) {
                    let target = account ?? state.input
                    if account == nil && (target.isEmpty || !state.known) { error = CommunityCopy.shared.INVALID_USERNAME } else { onPick(target, role) }
                }
                .disabled(blocked)
                .accessibilityIdentifier("role-option-\(role.value)")
            }
        }
    }
}

/// The one layout of the community moderation sheets (manage, set role, mod notes), after RN communityRoleEditSheet:
/// a centred title, an optional subtitle (the account or post it acts on), the sheet's own rows, then Cancel.
struct ModerationSheet<Content: View>: View {
    let title: String
    var subtitle: String?
    let cancelId: String
    @ViewBuilder let content: () -> Content
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appTheme) private var theme

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text(title).scaledFont(20, weight: .bold).foregroundStyle(theme.text).multilineTextAlignment(.center).frame(maxWidth: .infinity)
                    .accessibilityAddTraits(.isHeader)
                if let subtitle {
                    Text(subtitle).scaledFont(14).foregroundStyle(theme.secondary).multilineTextAlignment(.center).frame(maxWidth: .infinity)
                        .padding(.top, 4).padding(.bottom, 16)
                } else {
                    Spacer().frame(height: 12)
                }
                content()
                Button(CommunityCopy.shared.CANCEL) { dismiss() }.frame(maxWidth: .infinity).padding(.vertical, 14)
                    .accessibilityIdentifier(cancelId)
            }
            .padding(20)
        }
        .scrollBounceBehavior(.basedOnSize)
        .background(theme.background)
    }
}

/// A row of a moderation sheet: an optional icon, the label, and a check on the current choice. `.disabled` greys it.
struct ModerationRow: View {
    let label: String
    var icon: AppIcon?
    var checked = false
    var emphasis = false
    let action: () -> Void
    @Environment(\.isEnabled) private var isEnabled
    @Environment(\.appTheme) private var theme

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                if let icon { Image(appIcon: icon).scaledFont(20).foregroundStyle(theme.secondary).frame(width: 24).accessibilityHidden(true) }
                Text(label).scaledFont(16, weight: emphasis && isEnabled ? .bold : .regular)
                    .foregroundStyle(!isEnabled ? theme.secondary : emphasis ? theme.accentText : theme.text)
                Spacer()
                if checked { Image(appIcon: .check).foregroundStyle(theme.accentText).accessibilityHidden(true) }
            }
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(checked ? .isSelected : [])
    }
}

/// RN communityManageSheet: members and roles, settings, activity log.
struct CommunityManageSheet: View {
    let onPick: (CommunityManageAction) -> Void

    var body: some View {
        ModerationSheet(title: CommunityCopy.shared.MANAGE, cancelId: "manage-cancel") {
            ForEach(CommunityManageAction.entries, id: \.self) { action in
                ModerationRow(label: action.label, icon: action.icon) { onPick(action) }
                    .accessibilityIdentifier("manage-\(action.name)")
            }
        }
    }
}

// MARK: Settings

struct CommunitySettingsScreen: View {
    @EnvironmentObject private var app: AppContainer
    @StateObject private var model: Observed<CommunitySettingsState>
    @Environment(\.appTheme) private var theme

    init(name: String, env: AppEnvironment) {
        _model = StateObject(wrappedValue: Observed(CommunitySettingsModel(env: env, name: name), ownsModel: true))
    }

    private var settings: CommunitySettingsModel { model.model as! CommunitySettingsModel }

    private func binding(_ get: @escaping (CommunitySettingsForm) -> String, _ set: @escaping (CommunitySettingsForm, String) -> CommunitySettingsForm) -> Binding<String> {
        Binding(get: { get(model.state.form) }, set: { settings.update(form: set(model.state.form, $0)) })
    }

    var body: some View {
        let state = model.state
        let f = state.form
        Form {
            if let notice = state.notice {
                Text(notice).scaledFont(13).foregroundStyle(theme.secondary).accessibilityIdentifier("settings-notice")
            }
            if state.loading { ProgressView() }
            if let error = state.error { ErrorStateView(error: error, esie: nil) { settings.retry() } }
            Section {
                field(CommunitySettingsState.companion.TITLE_LABEL, binding({ $0.title }, { $0.doCopy(title: $1, about: $0.about, lang: $0.lang, description: $0.description_, rules: $0.rules, isNsfw: $0.isNsfw) }), f.titleError, "title", state.canEdit)
                field(CommunitySettingsState.companion.ABOUT_LABEL, binding({ $0.about }, { $0.doCopy(title: $0.title, about: $1, lang: $0.lang, description: $0.description_, rules: $0.rules, isNsfw: $0.isNsfw) }), f.aboutError, "about", state.canEdit)
                field(CommunitySettingsState.companion.LANG_LABEL, binding({ $0.lang }, { $0.doCopy(title: $0.title, about: $0.about, lang: $1, description: $0.description_, rules: $0.rules, isNsfw: $0.isNsfw) }), f.langError, "lang", state.canEdit)
                field(CommunitySettingsState.companion.DESCRIPTION_LABEL, binding({ $0.description_ }, { $0.doCopy(title: $0.title, about: $0.about, lang: $0.lang, description: $1, rules: $0.rules, isNsfw: $0.isNsfw) }), f.descriptionError, "description", state.canEdit, multiline: true)
                field(CommunitySettingsState.companion.RULES_LABEL, binding({ $0.rules }, { $0.doCopy(title: $0.title, about: $0.about, lang: $0.lang, description: $0.description_, rules: $1, isNsfw: $0.isNsfw) }), f.rulesError, "rules", state.canEdit, multiline: true)
                Toggle(CommunitySettingsState.companion.NSFW_LABEL, isOn: Binding(get: { model.state.form.isNsfw }, set: { v in
                    let cur = model.state.form
                    settings.update(form: cur.doCopy(title: cur.title, about: cur.about, lang: cur.lang, description: cur.description_, rules: cur.rules, isNsfw: v))
                }))
                .disabled(!state.canEdit)
            }
            if state.canEdit {
                Button {
                    settings.save()
                } label: {
                    if state.saving { ProgressView() } else { Text(CommunitySettingsState.companion.SAVE).bold().frame(maxWidth: .infinity) }
                }
                .disabled(!f.isValid || state.saving)
                .accessibilityIdentifier("settings-save")
            }
        }
        .navigationTitle(CommunitySettingsState.companion.HEADER)
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: state.saved) { _, saved in if saved { app.path = Array(app.path.dropLast()) } }
        .alert(CommunityCopy.shared.FAIL, isPresented: Binding(get: { state.alert != nil }, set: { if !$0 { settings.dismissAlert() } })) {
            Button(UiCopy.shared.OK, role: .cancel) { settings.dismissAlert() }
        } message: { Text(state.alert ?? "") }
        .onAppear { settings.start() }
    }

    @ViewBuilder private func field(_ label: String, _ text: Binding<String>, _ error: String?, _ id: String, _ editable: Bool, multiline: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).scaledFont(14, weight: .bold).foregroundStyle(theme.text)
            if multiline {
                TextField(label, text: text, axis: .vertical).lineLimit(4...8).disabled(!editable).accessibilityIdentifier("field-\(id)")
            } else {
                TextField(label, text: text).disabled(!editable).accessibilityIdentifier("field-\(id)")
            }
            if let error { Text(error).scaledFont(12).foregroundStyle(theme.dangerText).accessibilityIdentifier("error-\(id)") }
        }
    }
}

// MARK: Activity log

struct CommunityActivitiesScreen: View {
    @EnvironmentObject private var app: AppContainer
    @StateObject private var model: Observed<CommunityActivitiesState>
    @Environment(\.appTheme) private var theme

    init(name: String, title: String, env: AppEnvironment) {
        _model = StateObject(wrappedValue: Observed(CommunityActivitiesModel(env: env, name: name, title: title), ownsModel: true))
    }

    private var activities: CommunityActivitiesModel { model.model as! CommunityActivitiesModel }

    var body: some View {
        let state = model.state
        List {
            if state.loading { ProgressView().frame(maxWidth: .infinity).padding(.top, 48) }
            if let empty = state.emptyText { Text(empty).foregroundStyle(theme.secondary).esieAbove(.empty).padding(.top, 48) }
            ForEach(Array(state.items.enumerated()), id: \.element.id) { index, row in
                HStack(alignment: .top, spacing: 8) {
                    if let actor = row.actor {
                        AvatarView(url: PostCard.companion.avatarUrl(account: actor, proxyBase: app.env.proxyBase, size: "small"), size: 28)
                    } else {
                        Color.clear.frame(width: 28, height: 28)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(ActivityText.attributed(row, link: theme.accentText)).scaledFont(14).foregroundStyle(theme.text).lineLimit(4)
                        Text(TimeAgo.shared.short(epochMillis: row.epochMillis, nowMillis: Int64(Date().timeIntervalSince1970 * 1000)))
                            .scaledFont(12).foregroundStyle(theme.secondary)
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier("activity-\(row.id)")
                .onAppear { if index >= state.items.count - 3 { activities.loadMore() } }
            }
            if state.loadingMore { ProgressView().frame(maxWidth: .infinity) }
        }
        .listStyle(.plain)
        .refreshable { activities.refresh() }
        .environment(\.openURL, OpenURLAction { url in
            // ecency-activity://account[/permlink]: the mention links of the log
            guard url.scheme == ActivityText.scheme, let account = url.host else { return .discarded }
            let permlink = url.pathComponents.dropFirst().first
            if let permlink { app.push(AppScreenPost(author: account, permlink: permlink)) } else { app.push(AppScreenProfile(author: account)) }
            return .handled
        })
        .navigationTitle(state.headerTitle)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { activities.start() }
    }
}

/// The activity message with its mentions as in-app links. Pure, unit tested.
enum ActivityText {
    static let scheme = "ecency-activity"

    static func attributed(_ row: ActivityRow, link: Color) -> AttributedString {
        var out = AttributedString()
        for part in row.parts {
            var piece = AttributedString(part.text)
            if let account = part.account {
                var components = URLComponents()
                components.scheme = scheme
                components.host = account
                if let permlink = part.permlink { components.path = "/\(permlink)" }
                piece.link = components.url
                piece.foregroundColor = link
            }
            out.append(piece)
        }
        return out
    }
}

// MARK: Mod notes (for the post menu's mute and unmute)

/// RN modNotesSheet: the reason a mute or unmute carries (required, 120 characters). Cancel never broadcasts.
struct ModNotesSheet: View {
    var title = ModNotes.shared.DEFAULT_TITLE
    var subtitle: String?
    var placeholder = ModNotes.shared.DEFAULT_PLACEHOLDER
    var confirmLabel = UiCopy.shared.CONFIRM
    let onConfirm: (String) -> Void
    @State private var value = ""
    @Environment(\.appTheme) private var theme

    var body: some View {
        let notes = ModNotes.shared.notes(input: value)
        // scrolls when the text is large (the sheet opens at half height)
        ModerationSheet(title: title, subtitle: subtitle, cancelId: "mod-notes-cancel") {
            TextField(placeholder, text: Binding(get: { value }, set: { value = ModNotes.shared.limit(input: $0) }))
                .padding(12).background(RoundedRectangle(cornerRadius: 8).fill(theme.lightBackground))
                .accessibilityIdentifier("mod-notes-input")
            Text("\(Fmt.int(ModNotes.shared.count(input: value))) / \(Fmt.int(ModNotes.shared.MAX_LENGTH))").scaledFont(12).foregroundStyle(theme.secondary)
                .frame(maxWidth: .infinity, alignment: .trailing).padding(.vertical, 8)
            ModerationRow(label: confirmLabel, emphasis: true) { if let notes { onConfirm(notes) } }
                .disabled(notes == nil)
                .accessibilityIdentifier("mod-notes-confirm")
        }
    }
}
