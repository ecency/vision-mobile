import EcencyCore
import SwiftUI

/// RN notification screen: the Notifications and Leaderboard tabs. Notifications needs an account ("Login to
/// see"), the leaderboard is public.
struct NotificationsTabView: View {
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var session: Observed<SessionState>
    @Environment(\.appTheme) private var theme
    @State private var page = 0
    /// Bumped when the selected "Notifications" tab is tapped again: the list scrolls to the top (RN).
    @State private var scrollToTop = 0

    var body: some View {
        VStack(spacing: 0) {
            FittingSegments {
                ForEach(Array([ReadingUiCopy.shared.NOTIFICATIONS, ReadingUiCopy.shared.LEADERBOARD].enumerated()), id: \.offset) { index, title in
                    Button {
                        if page == index, index == 0 { scrollToTop += 1 } else { page = index }
                    } label: {
                        VStack(spacing: 0) {
                            Text(title).font(.subheadline.bold()).foregroundStyle(page == index ? theme.accentText : theme.secondary)
                                .fixedSize()
                                .padding(.vertical, 12).padding(.horizontal, 12)
                            Rectangle().fill(page == index ? theme.accent : .clear).frame(height: 2)
                        }
                        .frame(maxWidth: .infinity)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(page == index ? .isSelected : [])
                    .accessibilityIdentifier("notifications-page-\(title)")
                }
            }
            if page == 0 {
                if let username = session.state.username {
                    NotificationListView(model: app.notifications, username: username, scrollToTop: scrollToTop)
                } else {
                    LoginPromptView(text: ReadingUiCopy.shared.LOGIN_TO_SEE) { app.push(AppScreenLogin.shared) }
                }
            } else {
                LeaderboardView(env: app.env)
            }
        }
        .background(theme.background)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("notifications-tab")
    }
}

/// RN FilterBar: the selected option in capitals with a dropdown, and an optional button on the right.
private struct FilterBar<Chips: View, Trailing: View>: View {
    let selected: String
    let options: [String]
    let identifier: String
    let onSelect: (Int) -> Void
    @ViewBuilder let chips: Chips
    @ViewBuilder let trailing: Trailing
    @Environment(\.appTheme) private var theme

    var body: some View {
        HStack {
            // the menu and its quick chips: one line when they fit, else the chips go under the menu (never clipped)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 6) { menu; chips }
                VStack(alignment: .leading, spacing: 0) { menu; HStack(spacing: 6) { chips } }
                VStack(alignment: .leading, spacing: 4) { menu; chips }
            }
            Spacer(minLength: 0)
            trailing
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 44)
        .background(theme.lightBackground)
    }

    private var menu: some View {
        Menu {
            ForEach(Array(options.enumerated()), id: \.offset) { index, label in
                Button(label) { onSelect(index) }.accessibilityIdentifier("\(identifier)-\(index)")
            }
        } label: {
            HStack(spacing: 2) {
                Text(selected).scaledFont(14, weight: .bold)
                Image(appIcon: .dropdown).scaledFont(9)
            }
            .foregroundStyle(theme.secondary)
            .padding(.vertical, 8)
        }
        .accessibilityIdentifier(identifier)
    }
}

extension FilterBar where Chips == EmptyView {
    init(selected: String, options: [String], identifier: String, onSelect: @escaping (Int) -> Void, @ViewBuilder trailing: () -> Trailing) {
        self.init(selected: selected, options: options, identifier: identifier, onSelect: onSelect, chips: { EmptyView() }, trailing: trailing)
    }
}

private struct NotificationListView: View {
    @ObservedObject var model: Observed<NotificationsState>
    let username: String
    let scrollToTop: Int
    @Environment(\.motion) private var motion
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    private var notifications: NotificationsModel { app.env.notifications }

    var body: some View {
        let state = model.state
        // another account's rows never draw, not even for the frame in which the account changes
        let mine = state.account == username
        let items: [NotificationRow] = mine ? state.items : []
        VStack(spacing: 0) {
            filterBar(state)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 0) {
                        Color.clear.frame(height: 0).id("top")
                        placeholder(state, mine: mine, empty: items.isEmpty)
                        rows(items)
                        if state.isLoadingMore { ProgressView().padding(.vertical, 16) }
                        Color.clear.frame(height: 60)
                    }
                }
                .refreshable { try? await notifications.refreshAndWait() }
                .onChange(of: scrollToTop) { _, _ in withAnimation(motion.scroll) { proxy.scrollTo("top", anchor: .top) } }
                .onChange(of: state.filter) { _, _ in proxy.scrollTo("top", anchor: .top) }
            }
        }
        .onAppear { notifications.show() }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("notification-list")
    }

    private func filterBar(_ state: NotificationsState) -> some View {
        FilterBar(selected: state.filter.title, options: NotificationFilter.entries.map(\.title), identifier: "notification-filter",
                  onSelect: { notifications.selectFilter(filter: NotificationFilter.entries[$0]) }) {
            ForEach(NotificationFilter.companion.quick, id: \.self) { filter in
                quickChip(filter, on: filter == state.filter)
            }
        } trailing: {
            Button { notifications.markAllRead() } label: {
                Image(appIcon: .markAllRead).scaledFont(20).foregroundStyle(theme.icon).tapTarget()
            }
            .accessibilityLabel(ReadingUiCopy.shared.MARK_ALL_AS_READ)
            .largeContent(ReadingUiCopy.shared.MARK_ALL_AS_READ, appIcon: .markAllRead)
            .accessibilityIdentifier("mark-all-read")
        }
    }

    /// A quick filter (Replies, Mentions): the same as choosing it in the menu; the selected one again goes back to All.
    private func quickChip(_ filter: NotificationFilter, on: Bool) -> some View {
        PillButton(label: filter.label, filled: on) { notifications.toggleQuickFilter(filter: filter) }
            .fixedSize()
            .accessibilityAddTraits(on ? .isSelected : [])
            .accessibilityIdentifier("notification-chip-\(filter.name)")
    }

    /// RN's placeholder, error with retry (QueryErrorRetry) and "No recent activity".
    @ViewBuilder private func placeholder(_ state: NotificationsState, mine: Bool, empty: Bool) -> some View {
        if !mine || (empty && (state.isLoading || !state.loaded)) {
            RowsSkeleton(count: 8, identifier: "notifications-loading")
        } else if empty, state.error != nil {
            VStack(spacing: 12) {
                EsieView(.error)
                Text(ReadingUiCopy.shared.LOAD_FAILED_RETRY).multilineTextAlignment(.center).foregroundStyle(theme.secondary)
                    .announces(ReadingUiCopy.shared.LOAD_FAILED_RETRY)
                Button(UiCopy.shared.TRY_AGAIN_QUESTION) { notifications.retry() }.accessibilityIdentifier("retry")
            }
            .padding(.top, 48).padding(.horizontal, 24)
        } else if empty {
            Text(ReadingUiCopy.shared.NO_RECENT_ACTIVITY).foregroundStyle(theme.secondary)
                .accessibilityIdentifier("notifications-empty").esieAbove(.empty).padding(.top, 48)
        }
    }

    private func rows(_ items: [NotificationRow]) -> some View {
        let lastId = items.last?.id
        let showImages = model.state.showImages
        return ForEach(items, id: \.id) { row in
            SwipeToRead(enabled: !row.read, onRead: { _ = notifications.markRead(id: row.id) }) {
                NotificationLineView(row: row, showImages: showImages, onTap: { tap(row) }, onAvatar: { app.sheet = .quickProfile(row.source) },
                                     onRead: { _ = notifications.markRead(id: row.id) })
            }
            .onAppear { if row.id == lastId { notifications.loadMore() } }
        }
    }

    private func tap(_ row: NotificationRow) {
        if let entry = notifications.open(id: row.id) { app.open(entry: entry) }
    }
}

/// An unread row swipes from its trailing edge towards the leading edge (right to left, mirrored in RTL) to be marked
/// read; it springs back and stays. A vertical drag stays the scroll's.
struct SwipeToRead<Content: View>: View {
    let enabled: Bool
    let onRead: () -> Void
    @ViewBuilder let content: Content
    @State private var offset: CGFloat = 0
    @Environment(\.appTheme) private var theme
    @Environment(\.motion) private var motion
    @ScaledMetric(relativeTo: .body) private var threshold: CGFloat = 96
    @Environment(\.layoutDirection) private var direction

    /// A drag's width as "towards the end" positive: gestures report screen coordinates, while `.offset(x:)` and the
    /// trailing alignment follow the layout direction, so a right to left layout flips it.
    private func towardsStart(_ width: CGFloat) -> CGFloat { direction == .rightToLeft ? -width : width }

    var body: some View {
        if enabled {
            ZStack(alignment: .trailing) {
                HStack(spacing: 8) {
                    Image(appIcon: .markAllRead).scaledFont(18)
                    Text(ReadingUiCopy.shared.MARK_AS_READ).scaledFont(14, weight: .bold).lineLimit(1)
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 24)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .trailing)
                .background(theme.accent)
                .opacity(offset < 0 ? 1 : 0)
                .accessibilityHidden(true)
                content
                    .offset(x: offset)
            }
            .clipped()
            .simultaneousGesture(
                DragGesture(minimumDistance: 20)
                    .onChanged { v in
                        guard abs(v.translation.width) > abs(v.translation.height) * 1.5 || offset != 0 else { return }
                        offset = min(0, towardsStart(v.translation.width))
                    }
                    .onEnded { v in
                        // only a drag the row followed (a horizontal one) can mark: a diagonal flick of the scroll never does
                        let read = offset != 0 && (offset <= -threshold || towardsStart(v.predictedEndTranslation.width) <= -threshold * 2)
                        withAnimation(motion.settle) { offset = 0 }
                        if read { onRead() }
                    }
            )
        } else {
            content
        }
    }
}

/// RN notificationLineView: avatar, "name text", the post line, a description, the time and a post thumbnail.
private struct NotificationLineView: View {
    let row: NotificationRow
    /// RN "Show Images": off leaves the thumbnail out.
    let showImages: Bool
    let onTap: () -> Void
    let onAvatar: () -> Void
    let onRead: () -> Void
    @Environment(\.appTheme) private var theme
    @Environment(\.colorScheme) private var scheme

    /// RN `$primaryLightBlue`
    private var unread: Color { NotificationRowColors.unread(dark: scheme == .dark) }

    var body: some View {
        HStack(alignment: .top, spacing: 0) {
            Button(action: onAvatar) { AvatarView(url: row.avatarUrl, size: 32) }
                .buttonStyle(.plain)
                .padding(.leading, 24)
                .accessibilityLabel(UiCopy.shared.profileOf(username: row.source))
                .accessibilityIdentifier("notification-avatar-\(row.id)")
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    (Text(row.source).fontWeight(.bold).foregroundColor(theme.text) + Text(" " + row.text).foregroundColor(NotificationRowColors.line(theme)))
                        .scaledFont(14).lineLimit(3)
                        .accessibilityIdentifier("notification-line")
                    ProBadge(username: row.source, size: 13)
                }
                if !row.moreInfo.isEmpty {
                    Text(row.moreInfo).scaledFont(14).foregroundStyle(theme.text).lineLimit(2).padding(.top, 2)
                }
                if !row.description_.isEmpty {
                    Text(row.description_).scaledFont(12, weight: .medium).foregroundStyle(theme.text).lineLimit(2).padding(.top, 2)
                }
                if let time = NotificationClock.text(row.timestampMillis) {
                    Text(time).scaledFont(11, relativeTo: .caption).foregroundStyle(NotificationRowColors.line(theme)).padding(.top, 4)
                }
            }
            .padding(.leading, 16).padding(.trailing, 28)
            .frame(maxWidth: .infinity, minHeight: minTapTarget, alignment: .leading)
            .contentShape(Rectangle())
            // VoiceOver: the text column is the row's one button (name, action, post, time; read or unread)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isButton)
            .accessibilityValue(row.read ? ReadingUiCopy.shared.READ : ReadingUiCopy.shared.UNREAD)
            .accessibilityAction { onTap() }
            // the swipe, for VoiceOver and Switch Control (rotor action), on unread rows only
            .accessibilityActions {
                if !row.read { Button(ReadingUiCopy.shared.MARK_AS_READ, action: onRead) }
            }
            .accessibilityIdentifier("notification-text-\(row.id)")
            if showImages, !row.imageUrl.isEmpty {
                LoadedImageView(src: row.imageUrl, maxWidth: 32) { _ in }
                    .frame(width: 32, height: 32)
                    .background(theme.lightBackground)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .padding(.trailing, 24)
                    .accessibilityHidden(true)
            }
        }
        .padding(.vertical, 12)
        .frame(minHeight: 64, alignment: .top)
        .background(row.read ? theme.background : unread)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("notification-\(row.id)")
    }
}

/// RN formatNotificationTimestamp: relative for a week, then the date and time, both in the app language.
enum NotificationClock {
    static func text(_ epochMillis: Int64, now: Date = Date()) -> String? {
        guard epochMillis > 0 else { return nil }
        if let relative = NotificationTime.shared.relative(epochMillis: epochMillis, nowMillis: Int64(now.timeIntervalSince1970 * 1000)) {
            return relative
        }
        return LocaleFormat.shared.dateTime(epochMillis: epochMillis)
    }
}

/// RN leaderboard: the top users by points for a day, week or month.
private struct LeaderboardView: View {
    @StateObject private var model: Observed<LeaderboardState>
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @Environment(\.dynamicTypeSize) private var typeSize

    /// Accessibility text sizes: the activity count and reward go under the name, without the column headers.
    private var stacked: Bool { typeSize >= .stacksRows }

    init(env: AppEnvironment) {
        _model = StateObject(wrappedValue: Observed(env.leaderboardModel(), ownsModel: true))
    }

    private var board: LeaderboardModel { model.model as! LeaderboardModel }

    /// RN: a tap on the reward shows the "Earn points" tooltip.
    private func reward(_ user: LeaderboardEntry) -> some View {
        Text(user.points).scaledFont(14, weight: .bold).foregroundStyle(theme.accentText)
            .contentShape(Rectangle())
            .onTapGesture { board.showEarnHint() }
            .accessibilityIdentifier("leader-reward-\(user.username)")
    }

    var body: some View {
        let state = model.state
        VStack(spacing: 0) {
            FilterBar(selected: state.duration.label, options: LeaderboardDuration.entries.map(\.label), identifier: "leaderboard-filter",
                      onSelect: { board.select(duration: LeaderboardDuration.entries[$0]) }) { EmptyView() }
            HStack(spacing: 0) {
                Text(ReadingUiCopy.shared.TOP_USERS).scaledFont(14, weight: .bold).frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 20)
                if !stacked {
                    Text(ReadingUiCopy.shared.ACTIVITIES).scaledFont(12, weight: .bold).frame(width: 80, alignment: .leading)
                    Text(ReadingUiCopy.shared.LEADERBOARD_REWARD).scaledFont(12, weight: .bold).frame(width: 88, alignment: .leading)
                }
            }
            .foregroundStyle(theme.secondary)
            .padding(.vertical, 10)
            .accessibilityAddTraits(.isHeader)
            ScrollView {
                LazyVStack(spacing: 0) {
                    if state.users.isEmpty, state.isLoading || !state.loaded {
                        ProgressView().padding(.top, 48)
                    } else if state.users.isEmpty {
                        Text(UiCopy.shared.NOTHING_HERE).foregroundStyle(theme.secondary).esieAbove(.empty).padding(.top, 48)
                    }
                    ForEach(Array(state.users.enumerated()), id: \.element.username) { index, user in
                        Button { app.sheet = .quickProfile(user.username) } label: {
                            HStack(spacing: 0) {
                                Group {
                                    if state.showsQuestCheck, user.questsDone {
                                        Image(appIcon: .checkCircle, filled: true).scaledFont(16).foregroundStyle(Color(hex: 0x4FD688))
                                            .accessibilityLabel(ReadingUiCopy.shared.QUESTS_DONE)
                                    } else {
                                        Text(Fmt.int(index + 1)).scaledFont(11, relativeTo: .caption).foregroundStyle(theme.secondary)
                                    }
                                }
                                .frame(width: 28)
                                .padding(.trailing, 13)
                                AvatarView(url: PostCard.companion.avatarUrl(account: user.username, proxyBase: app.env.proxyBase, size: "small"), size: 32)
                                if stacked {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(user.username).scaledFont(14, weight: .bold).foregroundStyle(theme.text)
                                        Text(user.count).scaledFont(14).foregroundStyle(theme.secondary)
                                        reward(user)
                                    }
                                    .padding(.leading, 12).frame(maxWidth: .infinity, alignment: .leading)
                                } else {
                                    Text(user.username).scaledFont(14, weight: .bold).foregroundStyle(theme.text).lineLimit(1)
                                        .padding(.leading, 12).frame(maxWidth: .infinity, alignment: .leading)
                                    Text(user.count).scaledFont(14).foregroundStyle(theme.secondary).frame(width: 80, alignment: .leading)
                                    reward(user).frame(width: 72, alignment: .leading)
                                }
                            }
                            .padding(.horizontal, 16).padding(.vertical, 10)
                            .background(index % 2 == 1 ? theme.lightBackground : theme.background)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        // one stop per user: name, then each value under its column's name
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel([user.username, "\(ReadingUiCopy.shared.ACTIVITIES): \(user.count)",
                                             "\(ReadingUiCopy.shared.LEADERBOARD_REWARD): \(user.points)"].joined(separator: ", "))
                        .accessibilityValue(state.showsQuestCheck && user.questsDone ? ReadingUiCopy.shared.QUESTS_DONE : "")
                        .accessibilityAddTraits(.isButton)
                        .accessibilityAction(named: Text(UiCopy.shared.profileOf(username: user.username))) { app.sheet = .quickProfile(user.username) }
                        // the reward's "Earn points" tip (a toast, which VoiceOver reads)
                        .accessibilityAction(named: Text(ReadingUiCopy.shared.LEADERBOARD_REWARD)) { board.showEarnHint() }
                        .accessibilityIdentifier("leader-\(user.username)")
                    }
                    Color.clear.frame(height: 60)
                }
            }
            .refreshable { try? await board.refreshAndWait() }
        }
        .onAppear { board.start() }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("leaderboard")
    }
}

/// RN settings "Show Images": off hides post thumbnails (wired to the notification rows so far).
struct ShowImagesToggle: View {
    @ObservedObject var notifications: Observed<NotificationsState>
    @EnvironmentObject private var app: AppContainer

    var body: some View {
        Toggle(L.Settings.showImgs, isOn: Binding(get: { notifications.state.showImages }, set: { app.env.notifications.setShowImages(show: $0) }))
            .accessibilityIdentifier("settings-show-images")
    }
}

/// RN settings "Push Notification": the master switch, the 14 per-type switches under it, and the notice shown
/// where push cannot work.
struct PushSettingsSection: View {
    @ObservedObject var push: Observed<PushState>
    @EnvironmentObject private var app: AppContainer

    var body: some View {
        let settings = push.state.settings
        Section {
            Toggle(ReadingUiCopy.shared.PUSH_NOTIFICATION, isOn: Binding(get: { settings.enabled }, set: { app.env.push.setEnabled(enabled: $0) }))
                .fontWeight(.bold)
                .accessibilityIdentifier("push-all")
            if settings.enabled {
                ForEach(PushType.entries, id: \.self) { type in
                    Toggle(type.label, isOn: Binding(get: { settings.isOn(type: type) }, set: { app.env.push.setType(type: type, on: $0) }))
                        .accessibilityIdentifier("push-\(type.key)")
                }
            }
            if let notice = push.state.unavailableNotice {
                Text(notice).font(.footnote).foregroundStyle(Color(hex: 0x856404))
                    .padding(12)
                    .background(RoundedRectangle(cornerRadius: 8).fill(Color(hex: 0xFFF3CD)))
                    .accessibilityIdentifier("push-unavailable")
            }
        }
    }
}

/// The notification row's colors (AccessibilityTests checks the line and times on the unread tint).
enum NotificationRowColors {
    /// RN's unread tint, light lightened from 0xEAF2FC so the gray text on it reaches AA (4.6:1, was 4.4).
    static func unread(dark: Bool) -> Color { dark ? Color(hex: 0x2E3D51) : Color(hex: ContrastPalette.lightUnreadRow) }
    /// The action text after the name, and the time.
    static func line(_ theme: AppTheme) -> Color { theme.secondary }
}
