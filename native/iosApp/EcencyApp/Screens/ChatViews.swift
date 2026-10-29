import EcencyCore
import SwiftUI

// RN src/screens/chats and the chat sheets over the shared chat models (README "Chats"). The list model lives in the
// container (AppContainer.chats) so the tab keeps its rows; a conversation owns its model.

/// RN chat colours that are not theme tokens.
enum ChatColors {
    /// RN `#28a745` / `#dc3545`: the status pill's dot.
    static let connected = Color(hex: 0x28A745)
    static let connecting = Color(hex: 0xDC3545)
    /// RN `#FFB300`: a favourite's star.
    static let star = Color(hex: 0xFFB300)
    /// RN dmWarningContainer.
    static let warningBackground = Color(hex: 0xFFF6DF)
    static let warningBorder = Color(hex: 0xF4C26B)
    static let warningText = Color(hex: 0x8A5B00)
    /// RN `#4CAF50`: online in the members list.
    static let online = Color(hex: 0x4CAF50)
}

/// The message text as SwiftUI draws it: mentions bold, inline markdown, links tappable (shown as RN shows them).
enum ChatAttributed {
    /// Behind a code span.
    static let codeShade = Color.black.opacity(0.12)

    static func make(_ spans: [ChatSpan], linkColor: Color) -> AttributedString {
        var out = AttributedString()
        for span in spans {
            var part = AttributedString(span.text)
            var intent: InlinePresentationIntent = []
            if span.bold { intent.insert(.stronglyEmphasized) }
            if span.italic { intent.insert(.emphasized) }
            if span.code { intent.insert(.code) }
            if !intent.isEmpty { part.inlinePresentationIntent = intent }
            if span.strike { part.strikethroughStyle = .single }
            // a shade darker than the bubble keeps the text's contrast on both bubbles and themes
            if span.code { part.backgroundColor = codeShade }
            if let link = span.url {
                if let url = URL(string: link) { part.link = url }
                part.foregroundColor = linkColor
                part.underlineStyle = .single
            }
            out += part
        }
        return out
    }
}

/// Where a tapped chat link goes (RN handleLink): signing and login links to the auth link flow, web links through
/// the app's link router (posts and profiles open in the app), anything else nowhere.
enum ChatLinkKind: Equatable {
    case appLink
    case web
    case ignored

    static func of(_ url: String) -> ChatLinkKind {
        let lower = url.lowercased()
        if lower.hasPrefix("hive://") || lower.hasPrefix("ecency://") { return .appLink }
        if lower.hasPrefix("https://") || lower.hasPrefix("http://") { return .web }
        return .ignored
    }
}

extension AppContainer {
    /// A link tapped in a message.
    func openChatLink(_ url: String) {
        switch ChatLinkKind.of(url) {
        case .appLink: _ = env.authLinks.handleFromContent(url: url)
        case .web: if let target = SafeTarget.shared.external(url: url) { handle(target) }
        case .ignored: break
        }
    }

    /// RN profile / quick profile "Message": the direct message, or "User has not joined chats".
    func openDirectMessage(_ username: String) {
        Task { @MainActor in
            do {
                let id = try await env.chat.openDirect(username: username)
                push(AppScreenChatThread(channelId: id, type: "D"))
            } catch {
                _ = env.ui.toast(text: ChatService.companion.DM_ERROR)
            }
        }
    }
}

// MARK: - official badge

/// Web OfficialBadge: marks an account Ecency runs (Esie, @ecency) so lookalikes cannot pass as it. A text label, not
/// a seal, so it never reads as the Pro badge: white on the accent fill (AA in both themes), scaled with Dynamic Type,
/// read as "Official Ecency account".
struct OfficialBadge: View {
    @Environment(\.appTheme) private var theme

    /// The fill under the white label (AccessibilityTests checks the pair in every theme).
    static func fill(_ theme: AppTheme) -> Color { theme.accent }

    var body: some View {
        Text(OfficialAccounts.shared.BADGE).scaledFont(11, weight: .semibold).foregroundStyle(.white).lineLimit(1)
            .padding(.horizontal, 4).padding(.vertical, 1)
            .background(RoundedRectangle(cornerRadius: 4).fill(Self.fill(theme)))
            .fixedSize()
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(OfficialAccounts.shared.BADGE_LABEL)
            .accessibilityIdentifier("official-badge")
    }
}

// MARK: - avatars

struct ChatAvatarView: View {
    let avatar: ChatAvatar
    let size: CGFloat
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    var body: some View {
        switch avatar {
        case let a as ChatAvatarAccount:
            AvatarView(url: PostCard.companion.avatarUrl(account: a.name, proxyBase: app.env.proxyBase, size: "small"), size: size)
        case let p as ChatAvatarPair:
            ZStack {
                AvatarView(url: PostCard.companion.avatarUrl(account: p.first, proxyBase: app.env.proxyBase, size: "small"), size: size * 0.7)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                AvatarView(url: PostCard.companion.avatarUrl(account: p.second, proxyBase: app.env.proxyBase, size: "small"), size: size * 0.7)
                    .padding(1.5).background(Circle().fill(theme.background))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
            }
            .frame(width: size, height: size)
        case let i as ChatAvatarInitial:
            Text(i.letter).font(.system(size: size * 0.36, weight: .bold)).foregroundStyle(theme.secondary)
                .frame(width: size, height: size).background(Circle().fill(theme.lightBackground))
                .accessibilityHidden(true)
        default:
            Circle().fill(theme.lightBackground).frame(width: size, height: size)
        }
    }
}

// MARK: - the Chats tab

/// RN chatsContainer: search bar, results, the channel list, the status pill and the channel sheets.
struct ChatsTabView: View {
    @ObservedObject var observed: Observed<ChatsState>
    @EnvironmentObject private var app: AppContainer
    @EnvironmentObject private var session: Observed<SessionState>
    @Environment(\.appTheme) private var theme
    @State private var options: ChatRow?
    @State private var rename: RenameTarget?
    @State private var newGroup = false
    @Environment(\.dynamicTypeSize) private var typeSize
    /// Room under the last row for the floating status pill, growing with the text size.
    @ScaledMetric(relativeTo: .caption) private var pillClearance: CGFloat = 48

    struct RenameTarget: Identifiable {
        let id: String
        let name: String
    }

    private var model: ChatsModel { observed.model as! ChatsModel }

    var body: some View {
        let s = observed.state
        let mine = s.account != nil && s.account == session.state.username
        ZStack(alignment: .bottomLeading) {
            VStack(spacing: 0) {
                if mine, let error = s.error, !s.isLoading, !s.rows.isEmpty {
                    Text(error).scaledFont(13).foregroundStyle(theme.dangerText).padding(.horizontal, 16).announces(error).accessibilityIdentifier("chats-error")
                }
                searchBar(s)
                // the first load draws the rows' shape; a search or a reload over rows keeps the spinner
                if mine && s.isLoading && s.rows.isEmpty && !s.searching {
                    RowsSkeleton(count: 6, identifier: "chats-loading")
                } else if mine && (s.isLoading || s.searching) {
                    ProgressView().padding(.top, 12).accessibilityIdentifier("chats-loading")
                }
                List {
                    if mine {
                        if let e = s.searchError { Text(e).scaledFont(13).foregroundStyle(theme.dangerText).announces(e, debounce: true, sticky: true).listRowSeparator(.hidden) }
                        if !s.channelResults.isEmpty {
                            heading(ChatUiCopy.shared.CHANNELS)
                            ForEach(s.channelResults, id: \.channel.id) { r in
                                resultRow(avatar: r.avatar, title: r.title, meta: nil, action: ChatUiCopy.shared.JOIN, id: "join-\(r.channel.id)") { model.join(result: r) }
                            }
                        }
                        if !s.userResults.isEmpty {
                            heading(ChatUiCopy.shared.USERS)
                            ForEach(s.userResults, id: \.user.id) { r in
                                resultRow(avatar: r.avatar, title: r.title, meta: r.handle, action: nil, id: "dm-\(r.user.id)") { model.startDirect(result: r) }
                            }
                        }
                        ForEach(s.rows, id: \.id) { row in
                            channelRow(row)
                        }
                        if s.rows.isEmpty && !s.isLoading {
                            Text(s.error ?? ChatsModel.companion.EMPTY)
                                .scaledFont(14).foregroundStyle(s.error != nil ? theme.dangerText : theme.secondary)
                                .multilineTextAlignment(.center).accessibilityIdentifier("chats-empty")
                                // README "Esie": resting on an empty list, sad when it failed to load
                                .esieAbove(s.error != nil ? .error : .empty).padding(.top, 48)
                                .listRowSeparator(.hidden)
                        }
                    }
                    if !typeSize.isAccessibilitySize {
                        Color.clear.frame(height: pillClearance).listRowSeparator(.hidden).accessibilityHidden(true)
                    }
                }
                .listStyle(.plain)
                .refreshable { try? await model.refreshAndWait() }
                .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chats-list")
                // at accessibility sizes the pill takes its own line under the list instead of floating over the
                // rows (it covered the last row's text while scrolling)
                if typeSize.isAccessibilitySize {
                    statusPill(connected: s.connected && mine).padding(.horizontal, 16).padding(.vertical, 8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            if !typeSize.isAccessibilitySize {
                statusPill(connected: s.connected && mine).padding(16)
            }
        }
        .onAppear {
            model.show()
            #if DEBUG
            // -demoChatNewGroup YES (fake data only): the New group sheet opens by itself (screenshots)
            if app.options.fakeData, UserDefaults.standard.bool(forKey: "demoChatNewGroup") {
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) { newGroup = true }
            }
            #endif
        }
        .onChange(of: s.open) { _, open in
            guard let open else { return }
            model.consumeOpen()
            app.push(open.screen)
        }
        .confirmationDialog(options.map { model.actionsTitle(row: $0) } ?? "", isPresented: Binding(get: { options != nil }, set: { if !$0 { options = nil } }),
                            titleVisibility: .visible, presenting: options) { row in
            ForEach(model.actions(row: row), id: \.self) { action in
                Button(action.label, role: action.destructive ? .destructive : nil) {
                    if action == .rename { rename = RenameTarget(id: row.id, name: row.channel.groupName ?? "") } else { model.perform(row: row, action: action) }
                }
                .accessibilityIdentifier("channel-option-\(action.name)")
            }
        }
        .sheet(item: $rename) { r in RenameGroupSheet(env: app.env, channelId: r.id, name: r.name) }
        .sheet(isPresented: $newGroup) {
            NewGroupSheet(env: app.env) { id in
                newGroup = false
                model.groupCreated(channelId: id)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chats-tab")
    }

    private func searchBar(_ s: ChatsState) -> some View {
        // at accessibility text sizes the buttons go under the field, which keeps the full width (beside them the
        // field shrank its text to fit)
        AdaptiveStack(horizontalAlignment: .trailing, spacing: 8) {
            // the field grows with the text size (a fixed 40 pt height drew the text shrunk at large sizes)
            HStack(spacing: 6) {
                Image(appIcon: .search).foregroundStyle(theme.icon).accessibilityHidden(true)
                TextField(ChatsModel.companion.SEARCH_PLACEHOLDER, text: Binding(get: { s.query }, set: { model.setQuery(text: $0) }))
                    .font(.body)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .accessibilityIdentifier("chats-search")
                if !s.query.isEmpty {
                    Button { model.setQuery(text: "") } label: { Image(appIcon: .clear, filled: true).foregroundStyle(theme.icon).tapTarget() }
                        .accessibilityLabel(UiCopy.shared.CLEAR)
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 6).frame(maxWidth: .infinity, minHeight: minTapTarget)
            .background(Capsule().fill(theme.lightBackground))
            HStack(spacing: 8) { searchButtons(s) }
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
    }

    @ViewBuilder private func searchButtons(_ s: ChatsState) -> some View {
        // icon buttons keep their size; long press shows them enlarged at accessibility text sizes
        Button { model.toggleSort() } label: {
            Image(appIcon: .sortAz).foregroundStyle(s.sortByName ? theme.accent : theme.icon)
                .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                .frame(width: minTapTarget, height: minTapTarget).background(Circle().fill(s.sortByName ? theme.lightBackground : .clear))
        }
        .accessibilityLabel(ChatUiCopy.shared.SORT_BY_NAME)
        .accessibilityAddTraits(s.sortByName ? .isSelected : [])
        .largeContent(ChatUiCopy.shared.SORT_BY_NAME, appIcon: .sortAz)
        .accessibilityIdentifier("chats-sort")
        if s.canStartGroup {
            Button { newGroup = true } label: {
                Image(appIcon: .groupAdd).foregroundStyle(theme.icon).dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                    .frame(width: minTapTarget, height: minTapTarget)
            }
            .accessibilityLabel(ChatNewGroupState.companion.TITLE)
            .largeContent(ChatNewGroupState.companion.TITLE, appIcon: .groupAdd)
            .accessibilityIdentifier("chats-new-group")
        }
    }

    private func heading(_ text: String) -> some View {
        Text(text).scaledFont(13, weight: .bold).foregroundStyle(theme.secondary).accessibilityAddTraits(.isHeader).listRowSeparator(.hidden)
    }

    private func channelRow(_ row: ChatRow) -> some View {
        HStack(spacing: 12) {
            // VoiceOver: one stop for the conversation (name, favourite, muted, time), the unread count and the
            // options button stay their own stops, and the options are also an action on the row
            HStack(spacing: 12) {
                // at accessibility sizes the avatar gives its width to the name, and the favourite and muted marks go
                // under it, so a long name wraps between words instead of breaking inside one
                if !typeSize.isAccessibilitySize { ChatAvatarView(avatar: row.avatar, size: 44) }
                VStack(alignment: .leading, spacing: 2) {
                    AdaptiveStack(spacing: 4) {
                        Text(row.title).scaledFont(15, weight: .semibold).foregroundStyle(theme.text).lineLimit(typeSize.isAccessibilitySize ? 3 : 1)
                        if row.official { OfficialBadge() }
                        if row.favorite { Image(appIcon: .star, filled: true).scaledFont(12).foregroundStyle(ChatColors.star).accessibilityLabel(ChatUiCopy.shared.FAVORITE) }
                        if row.muted { Image(appIcon: .notificationsOff).scaledFont(12).foregroundStyle(theme.icon).accessibilityLabel(UiCopy.shared.MUTED) }
                    }
                    if !row.time.isEmpty { Text(row.time).scaledFont(12).foregroundStyle(theme.secondary).lineLimit(typeSize.isAccessibilitySize ? 2 : 1).accessibilityIdentifier("chat-row-time") }
                }
                Spacer(minLength: 0)
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isButton)
            .accessibilityAction { app.push(model.open(row: row)) }
            .accessibilityAction(named: ChatUiCopy.shared.CHANNEL_OPTIONS) { options = row }
            if row.unread > 0 {
                Text(Fmt.int(row.unread)).scaledFont(12, weight: .bold).foregroundStyle(.white)
                    .padding(.horizontal, 8).padding(.vertical, 4).background(Capsule().fill(theme.accent))
                    .accessibilityLabel(ShellUiCopy.shared.tabUnread(title: row.title, count: Fmt.int(row.unread)))
                    .accessibilityIdentifier("chat-unread-\(row.id)")
            }
            Button { options = row } label: {
                Image(appIcon: .more).foregroundStyle(theme.icon).frame(width: 32, height: 32).tapTarget()
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(ChatUiCopy.shared.CHANNEL_OPTIONS)
            .largeContent(ChatUiCopy.shared.CHANNEL_OPTIONS, appIcon: .more)
            .accessibilityIdentifier("chat-options-\(row.id)")
        }
        .contentShape(Rectangle())
        .onTapGesture { app.push(model.open(row: row)) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chat-row-\(row.id)")
        .listRowSeparator(.hidden)
    }

    private func resultRow(avatar: ChatAvatar, title: String, meta: String?, action: String?, id: String, onAction: @escaping () -> Void) -> some View {
        HStack(spacing: 12) {
            ChatAvatarView(avatar: avatar, size: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).scaledFont(15, weight: .semibold).foregroundStyle(theme.text).lineLimit(typeSize.isAccessibilitySize ? 3 : 1)
                if let meta { Text(meta).scaledFont(12).foregroundStyle(theme.secondary).lineLimit(typeSize.isAccessibilitySize ? 2 : 1) }
            }
            Spacer(minLength: 0)
            Button(action: onAction) {
                if let action {
                    Text(action).scaledFont(13, weight: .bold).foregroundStyle(theme.accentText)
                        .padding(.horizontal, 14).padding(.vertical, 6).overlay(Capsule().stroke(theme.accent)).tapTarget()
                } else {
                    Image(appIcon: .chat, filled: true).foregroundStyle(theme.accentText).frame(width: 32, height: 32).tapTarget()
                }
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(action ?? UiCopy.shared.MESSAGE)
            .accessibilityIdentifier(id)
        }
        .listRowSeparator(.hidden)
    }

    private func statusPill(connected: Bool) -> some View {
        HStack(spacing: 6) {
            Circle().fill(connected ? ChatColors.connected : ChatColors.connecting).frame(width: 8, height: 8).accessibilityHidden(true)
            Text(connected ? ChatsModel.companion.CONNECTED : ChatsModel.companion.CONNECTING).scaledFont(12).foregroundStyle(theme.text)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(Capsule().fill(theme.lightBackground))
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.updatesFrequently)
        .accessibilityIdentifier("chats-status")
    }
}

// MARK: - group sheets

/// RN chatRenameGroupSheet.
struct RenameGroupSheet: View {
    @StateObject private var model: Observed<ChatRenameState>
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appTheme) private var theme

    init(env: AppEnvironment, channelId: String, name: String) {
        _model = StateObject(wrappedValue: Observed(env.chat.renameModel(channelId: channelId, currentName: name), ownsModel: true))
    }

    private var rename: ChatRenameModel { model.model as! ChatRenameModel }

    var body: some View {
        let s = model.state
        ScrollView { VStack(alignment: .leading, spacing: 12) {
            Text(ChatRenameState.companion.TITLE).scaledFont(18, weight: .bold).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
            Text(ChatRenameState.companion.HINT).scaledFont(13).foregroundStyle(theme.secondary)
            TextField(ChatRenameState.companion.PLACEHOLDER, text: Binding(get: { s.value }, set: { rename.setValue(text: $0) }))
                .textFieldStyle(.roundedBorder).submitLabel(.done).onSubmit { rename.save() }
                .accessibilityIdentifier("rename-field")
            if let message = s.message { Text(message).scaledFont(13).foregroundStyle(theme.dangerText).announces(message).accessibilityIdentifier("rename-error") }
            Button { rename.save() } label: {
                ZStack { if s.saving { ProgressView().tint(.white) } else { Text(UiCopy.shared.SAVE).bold() } }.frame(maxWidth: .infinity, minHeight: 32)
            }
            .buttonStyle(.prominentFill).disabled(!s.canSave)
            .accessibilityIdentifier("rename-save")
            Button(UiCopy.shared.CANCEL) { dismiss() }.frame(maxWidth: .infinity).tapTarget()
        }
        .padding(20) }
        .scrollBounceBehavior(.basedOnSize)
        .presentationDetents([.medium, .large])
        .onChange(of: s.saved) { _, saved in if saved != nil { dismiss() } }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("rename-sheet")
    }
}

/// RN chatNewGroupSheet.
struct NewGroupSheet: View {
    @StateObject private var model: Observed<ChatNewGroupState>
    @EnvironmentObject private var app: AppContainer
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appTheme) private var theme
    @Environment(\.dynamicTypeSize) private var typeSize
    let onCreated: (String) -> Void

    init(env: AppEnvironment, onCreated: @escaping (String) -> Void) {
        _model = StateObject(wrappedValue: Observed(env.chat.doNewGroupModel(), ownsModel: true))
        self.onCreated = onCreated
    }

    private var group: ChatNewGroupModel { model.model as! ChatNewGroupModel }

    var body: some View {
        let s = model.state
        // scrolls as a whole when large text makes it taller than the sheet
        ScrollView { VStack(alignment: .leading, spacing: 12) {
            Text(ChatNewGroupState.companion.TITLE).scaledFont(18, weight: .bold).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
            Text(ChatNewGroupState.companion.HINT).scaledFont(13).foregroundStyle(theme.secondary)
            if !s.selected.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(s.selected, id: \.self) { name in
                            Button { group.remove(username: name) } label: {
                                HStack(spacing: 4) {
                                    AvatarView(url: PostCard.companion.avatarUrl(account: name, proxyBase: app.env.proxyBase, size: "small"), size: 20)
                                    Text("@\(name)").scaledFont(13).foregroundStyle(theme.text)
                                    Image(appIcon: .close).scaledFont(11, weight: .semibold).foregroundStyle(theme.secondary)
                                }
                                .padding(.horizontal, 8).padding(.vertical, 4).background(Capsule().fill(theme.lightBackground))
                                .tapTarget()
                            }
                            .accessibilityLabel(ChatUiCopy.shared.removeUser(username: name)).accessibilityIdentifier("chip-\(name)")
                        }
                    }
                }
            }
            TextField(s.placeholder, text: Binding(get: { s.term }, set: { group.setTerm(text: $0) }))
                .textFieldStyle(.roundedBorder).textInputAutocapitalization(.never).autocorrectionDisabled()
                .disabled(s.atLimit).accessibilityIdentifier("group-search")
            if s.showsResults {
                let visible = group.visible
                if s.searching && visible.isEmpty {
                    ProgressView().frame(maxWidth: .infinity)
                } else if visible.isEmpty {
                    Text(s.emptyText).scaledFont(13).foregroundStyle(theme.secondary).accessibilityIdentifier("group-empty")
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) {
                            ForEach(visible, id: \.username) { p in
                                Button { group.add(username: p.username) } label: {
                                    HStack(spacing: 10) {
                                        AvatarView(url: p.account.map { PostCard.companion.avatarUrl(account: $0, proxyBase: app.env.proxyBase, size: "small") } ?? "", size: 28)
                                        Text("@\(p.username)").foregroundStyle(theme.text)
                                        Spacer()
                                    }
                                    .padding(.vertical, 8).frame(minHeight: minTapTarget).contentShape(Rectangle())
                                }
                                .accessibilityIdentifier("person-\(p.username)")
                            }
                        }
                    }
                    .frame(maxHeight: typeSize.isAccessibilitySize ? 420 : 220)
                    .scrollBounceBehavior(.basedOnSize)
                }
            }
            if let error = s.error { Text(error).scaledFont(13).foregroundStyle(theme.dangerText).announces(error).accessibilityIdentifier("group-error") }
            Button { group.create() } label: {
                ZStack { if s.creating { ProgressView().tint(.white) } else { Text(ChatNewGroupState.companion.CREATE).bold() } }.frame(maxWidth: .infinity, minHeight: 32)
            }
            .buttonStyle(.prominentFill).disabled(!s.canCreate)
            .accessibilityIdentifier("group-create")
            Button(UiCopy.shared.CANCEL) { dismiss() }.frame(maxWidth: .infinity).tapTarget()
        }
        .padding(20) }
        .scrollBounceBehavior(.basedOnSize)
        .presentationDetents([.large])
        .onChange(of: s.createdChannelId) { _, id in if let id { onCreated(id) } }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("new-group-sheet")
    }
}

// MARK: - a conversation

/// Sheets of a conversation.
private enum ThreadSheet: Identifiable {
    case options(ChatMessageItem)
    case emoji(ChatMessageItem)
    case reactors(ChatMessageItem, String)
    case pinned
    case members
    case rename
    case translate(ChatMessageItem)
    case picker

    var id: String {
        switch self {
        case let .options(i): return "options:\(i.rowId)"
        case let .emoji(i): return "emoji:\(i.rowId)"
        case let .reactors(i, e): return "reactors:\(i.rowId):\(e)"
        case .pinned: return "pinned"
        case .members: return "members"
        case .rename: return "rename"
        case let .translate(i): return "translate:\(i.rowId)"
        case .picker: return "picker"
        }
    }
}

/// RN chatThreadContainer: header, the message list (newest at the bottom), notices, the composer and the sheets.
struct ChatThreadView: View {
    @StateObject private var model: Observed<ChatThreadState>
    @Environment(\.motion) private var motion
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var sheet: ThreadSheet?
    /// "Choose from Library" or "Choose from Files" for an attachment (RN #3561), and the Files importer while open.
    @State private var attachChoice = false
    @State private var files: MediaSurface?
    @State private var removeTarget: ChatMessageItem?
    @State private var groupOptions = false
    @State private var onScreen = false

    init(screen: AppScreenChatThread, env: AppEnvironment) {
        _model = StateObject(wrappedValue: Observed(env.chat.threadModel(channelId: screen.channelId, type: screen.type), ownsModel: true))
    }

    private var thread: ChatThreadModel { model.model as! ChatThreadModel }

    var body: some View {
        let s = model.state
        VStack(spacing: 0) {
            header(s)
            if s.showDmWarning { dmWarning }
            messages(s)
            if let typing = s.typing {
                Text("•••  \(typing)").scaledFont(12).foregroundStyle(theme.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).accessibilityIdentifier("chat-typing")
            }
            if let ban = s.banText { notice("⏳", ban).accessibilityIdentifier("chat-ban") }
            ChatComposer(observed: model) { attachChoice = true }
        }
        .mediaSourceChooser(isPresented: $attachChoice, options: MediaSourceMenus.shared.attachment(),
                            identifier: { $0 == .files ? "chat-attach-files" : "chat-attach-library" }) { source in
            if source == .files { files = .chat } else { sheet = .picker }
        }
        .mediaFileImporter(surface: $files) { _, images in if let image = images.first { thread.attach(file: image) } }
        .background(theme.background)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            thread.start()
            onScreen = true
            thread.setActive(on: scenePhase == .active)
            #if DEBUG
            // -demoChatOptions YES (fake data only): the options sheet of the newest message of someone else (screenshots)
            if app.options.fakeData, UserDefaults.standard.bool(forKey: "demoChatOptions") {
                DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
                    if let item = model.state.items.compactMap({ $0 as? ChatMessageItem }).first(where: { !$0.own }) { sheet = .options(item) }
                }
            }
            #endif
        }
        .onDisappear {
            onScreen = false
            thread.setActive(on: false)
        }
        .onChange(of: scenePhase) { _, phase in thread.setActive(on: onScreen && phase == .active) }
        .sheet(item: $sheet) { sheet in sheetView(sheet, s) }
        .alert(ChatThreadModel.companion.REMOVE_TITLE, isPresented: Binding(get: { removeTarget != nil }, set: { if !$0 { removeTarget = nil } }), presenting: removeTarget) { item in
            Button(UiCopy.shared.CANCEL, role: .cancel) {}
            Button(ChatUiCopy.shared.DELETE_MESSAGE, role: .destructive) { thread.remove(item: item) }.accessibilityIdentifier("chat-confirm-remove")
        } message: { _ in Text(ChatThreadModel.companion.REMOVE_BODY) }
        .alert(UiCopy.shared.FAIL, isPresented: Binding(get: { s.alert != nil }, set: { if !$0 { thread.clearAlert() } })) {
            Button(UiCopy.shared.OK, role: .cancel) { thread.clearAlert() }
        } message: { Text(s.alert ?? "") }
        .confirmationDialog(s.title, isPresented: $groupOptions, titleVisibility: .visible) {
            Button(ChatChannelAction.members.label) { sheet = .members }.accessibilityIdentifier("group-option-members")
            if s.groupOwner { Button(ChatChannelAction.rename.label) { sheet = .rename }.accessibilityIdentifier("group-option-rename") }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chat-thread")
    }

    private func header(_ s: ChatThreadState) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Button { dismiss() } label: { Image(appIcon: .back).scaledFont(20).foregroundStyle(theme.icon).tapTarget() }
                    .accessibilityLabel(UiCopy.shared.BACK)
                    .largeContent(UiCopy.shared.BACK, appIcon: .back)
                    .accessibilityIdentifier("chat-back")
                HStack(spacing: 6) {
                    Text(s.title).scaledFont(16, weight: .semibold).foregroundStyle(theme.text).lineLimit(typeSize.isAccessibilitySize ? 2 : 1)
                        .accessibilityAddTraits(.isHeader)
                        .accessibilityIdentifier("chat-title")
                    if s.official { OfficialBadge() }
                }
                .frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 8)
                chip(.members, s.memberCount > 0 ? "\(s.memberCount)" : nil, label: ChatUiCopy.shared.MEMBERS, id: "chat-members") { sheet = .members }
                if s.pinnedCount > 0 { chip(.pin, "\(s.pinnedCount)", label: ChatUiCopy.shared.PINNED_MESSAGES, id: "chat-pinned") { sheet = .pinned } }
                if s.isGroup { chip(.more, nil, label: ChatUiCopy.shared.GROUP_OPTIONS, id: "chat-group-options") { groupOptions = true } }
            }
            .padding(.horizontal, 12).padding(.vertical, 10)
            Rectangle().fill(theme.border.opacity(0.4)).frame(height: 0.5)
        }
    }

    private func chip(_ icon: AppIcon, _ count: String?, label: String, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(appIcon: icon).scaledFont(14).foregroundStyle(theme.icon)
                if let count { Text(count).scaledFont(12, weight: .semibold).foregroundStyle(theme.text) }
            }
            .padding(.horizontal, 6).padding(.vertical, 4).background(RoundedRectangle(cornerRadius: 12).fill(theme.lightBackground))
            .tapTarget()
        }
        .accessibilityLabel(label)
        .accessibilityValue(count ?? "")
        .largeContent(count.map { "\(label) \($0)" } ?? label, appIcon: icon)
        .accessibilityIdentifier(id)
    }

    private var dmWarning: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(appIcon: .warning).foregroundStyle(ChatColors.warningText).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(ChatThreadModel.companion.DM_WARNING_TITLE).scaledFont(14, weight: .bold).accessibilityAddTraits(.isHeader)
                Text(ChatThreadModel.companion.DM_WARNING_BODY).scaledFont(13)
                Button { app.push(AppScreenSettings.shared) } label: {
                    Text(ChatThreadModel.companion.DM_WARNING_SETTINGS).scaledFont(13, weight: .bold).underline()
                }
                .accessibilityIdentifier("dm-warning-settings")
            }
            .foregroundStyle(ChatColors.warningText)
            Spacer(minLength: 0)
            Button { thread.dismissDmWarning() } label: { Image(appIcon: .close).foregroundStyle(ChatColors.warningText).tapTarget() }
                .accessibilityLabel(UiCopy.shared.CLOSE).accessibilityIdentifier("dm-warning-close")
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 10).fill(ChatColors.warningBackground))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(ChatColors.warningBorder))
        .padding(.horizontal, 16).padding(.top, 12)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("dm-warning")
    }

    private func notice(_ icon: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text(icon).accessibilityHidden(true)
            Text(text).scaledFont(13).foregroundStyle(ChatColors.warningText)
            Spacer(minLength: 0)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 10).fill(ChatColors.warningBackground))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(ChatColors.warningBorder))
        .padding(.horizontal, 16).padding(.vertical, 6)
    }

    private func messages(_ s: ChatThreadState) -> some View {
        // RN's list is inverted: the newest message at the bottom, older pages above
        let items = Array(s.items.reversed())
        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 0) {
                    if s.account != nil {
                        Group {
                            if s.isLoading { ProgressView().padding(16) } else if !s.hasMore && !s.items.isEmpty {
                                Text(ChatThreadModel.companion.NO_MORE).scaledFont(12).foregroundStyle(theme.secondary).padding(16)
                                    .accessibilityIdentifier("chat-no-more")
                            }
                        }
                        ForEach(items, id: \.rowId) { item in
                            VStack(spacing: 0) {
                                if item.rowId == s.unreadItemId { unreadMarker }
                                row(item)
                            }
                            .id(item.rowId)
                            .onAppear { if item.rowId == items.first?.rowId { thread.loadMore() } }
                        }
                    }
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
            }
            .defaultScrollAnchor(.bottom)
            .refreshable { try? await thread.refreshAndWait() }
            .overlay {
                if s.account != nil, s.items.isEmpty, s.loaded, !s.isLoading {
                    Text(s.error ?? ChatThreadModel.companion.EMPTY).scaledFont(14).foregroundStyle(s.error != nil ? theme.dangerText : theme.secondary)
                        .multilineTextAlignment(.center).padding(32).announces(s.error).accessibilityIdentifier("chat-empty")
                }
            }
            .onChange(of: s.scrollTo) { _, id in
                guard let id else { return }
                withAnimation(motion.scroll) { proxy.scrollTo(id, anchor: .center) }
                thread.consumeScroll()
            }
            .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chat-messages")
        }
    }

    private var unreadMarker: some View {
        HStack(spacing: 8) {
            Rectangle().fill(theme.danger).frame(height: 1)
            Text(ChatThreadModel.companion.NEW_MESSAGES).scaledFont(12, weight: .bold).foregroundStyle(theme.dangerText).fixedSize()
            Rectangle().fill(theme.danger).frame(height: 1)
        }
        .padding(.vertical, 8)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
        .accessibilityIdentifier("chat-unread-marker")
    }

    @ViewBuilder
    private func row(_ item: ChatItem) -> some View {
        switch item {
        case let m as ChatMessageItem:
            ChatMessageBubble(
                item: m,
                onLongPress: { sheet = .options(m) },
                onReact: { thread.react(item: m, emojiName: $0) },
                onReactors: { sheet = .reactors(m, $0) },
                actions: thread.actions(item: m),
                onAction: { perform($0, m) }
            )
        case let j as ChatJoinItem: joinPill(j)
        case let g as ChatJoinGroupItem: JoinGroupPill(item: g) { joinPill($0) }
        case let n as ChatNoticeItem: pill { Text(n.text).scaledFont(12).foregroundStyle(theme.secondary) }.accessibilityIdentifier("chat-notice-\(n.rowId)")
        default: EmptyView()
        }
    }

    private func joinPill(_ j: ChatJoinItem) -> some View {
        pill {
            HStack(spacing: 4) {
                if let name = j.name {
                    Button { if let a = j.account { app.sheet = .quickProfile(a) } } label: {
                        Text(name).scaledFont(12, weight: .bold).foregroundStyle(theme.accentText)
                    }
                    .disabled(j.account == nil)
                }
                Text(j.text).scaledFont(12).foregroundStyle(theme.secondary)
            }
        }
        .accessibilityIdentifier("chat-join-\(j.rowId)")
    }

    private func pill<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        content()
            .padding(.horizontal, 12).padding(.vertical, 4)
            .background(RoundedRectangle(cornerRadius: 12).fill(theme.lightBackground))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 4)
    }

    @ViewBuilder
    private func sheetView(_ sheet: ThreadSheet, _ s: ChatThreadState) -> some View {
        switch sheet {
        case let .options(item):
            MessageOptionsSheet(item: item, actions: thread.actions(item: item)) { choice in
                self.sheet = nil
                switch choice {
                case let .react(name): thread.react(item: item, emojiName: name)
                case .more: DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { self.sheet = .emoji(item) }
                case let .action(action): perform(action, item)
                }
            }
        case let .emoji(item):
            EmojiPickerSheet { name in
                self.sheet = nil
                thread.react(item: item, emojiName: name)
            }
        case let .reactors(item, emoji):
            ReactorsSheet(model: thread.reactorsModel(item: item, emojiName: emoji)) { name in
                self.sheet = nil
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { app.sheet = .quickProfile(name) }
            }
        case .pinned:
            PinnedSheet(model: thread.pinnedModel(), canUnpin: s.canPin) { id in
                self.sheet = nil
                thread.scrollToMessage(postId: id)
            }
        case .members:
            MembersSheet(thread: thread) { name in
                self.sheet = nil
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { app.sheet = .quickProfile(name) }
            }
        case .rename:
            RenameGroupSheet(env: app.env, channelId: thread.channelId, name: s.groupName)
        case let .translate(item):
            TranslationSheet(model: thread.translationModel(item: item, readerLanguage: app.readerLanguage))
        case .picker:
            PhotoPicker(limit: 1) { images in
                if let image = images.first { thread.attach(file: image) }
            }
            .ignoresSafeArea()
        }
    }

    private func perform(_ action: ChatMessageAction, _ item: ChatMessageItem) {
        switch action {
        case .reply: thread.replyTo(item: item)
        case .theCopy:
            UIPasteboard.general.string = thread.messageText(item: item)
            _ = app.env.ui.toast(text: ChatUiCopy.shared.COPIED_TO_CLIPBOARD)
        case .share:
            let text = thread.messageText(item: item)
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { ChatShare.present(text) }
        case .translate: DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { sheet = .translate(item) }
        case .edit: thread.startEdit(item: item)
        case .pin: thread.setPinned(postId: item.post.id, pinned: true, onDone: nil)
        case .unpin: thread.setPinned(postId: item.post.id, pinned: false, onDone: nil)
        case .remove: removeTarget = item
        default: break
        }
    }
}

/// The system share sheet from the top view controller (RN Share.share).
enum ChatShare {
    @MainActor static func present(_ text: String) {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first(where: { $0.activationState == .foregroundActive }),
              var top = scene.windows.first(where: { $0.isKeyWindow })?.rootViewController else { return }
        while let next = top.presentedViewController { top = next }
        top.present(UIActivityViewController(activityItems: [text], applicationActivities: nil), animated: true)
    }
}

/// "N people joined", expandable.
private struct JoinGroupPill<Row: View>: View {
    let item: ChatJoinGroupItem
    let row: (ChatJoinItem) -> Row
    @State private var open = false
    @Environment(\.appTheme) private var theme

    var body: some View {
        VStack(spacing: 0) {
            Button { open.toggle() } label: {
                Text(item.text).scaledFont(12).foregroundStyle(theme.secondary)
                    .padding(.horizontal, 12).padding(.vertical, 4)
                    .background(RoundedRectangle(cornerRadius: 12).fill(theme.lightBackground))
            }
            .frame(maxWidth: .infinity).padding(.vertical, 4)
            .accessibilityIdentifier("chat-joins-\(item.rowId)")
            if open { ForEach(item.entries, id: \.rowId) { row($0) } }
        }
    }
}

/// One message: avatar and author for others, the quote, the text, images, the link card, time, reactions.
/// VoiceOver reads the author, quote, text and time as one stop, with the message options (reply, copy, edit, pin,
/// delete, ...) as its actions; images, the link card and reactions stay their own stops.
struct ChatMessageBubble: View {
    let item: ChatMessageItem
    let onLongPress: () -> Void
    let onReact: (String) -> Void
    let onReactors: (String) -> Void
    var actions: [ChatMessageAction] = []
    var onAction: (ChatMessageAction) -> Void = { _ in }
    @State private var allLinks = false
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let own = item.own
        // at accessibility sizes the side gap shrinks so the text keeps most of the width
        let gap: CGFloat = typeSize.isAccessibilitySize ? 16 : 40
        VStack(alignment: own ? .trailing : .leading, spacing: 4) {
            HStack(alignment: .top, spacing: 8) {
                if own { Spacer(minLength: gap) }
                if !own && !typeSize.isAccessibilitySize {
                    Button { if let a = item.authorAccount { app.sheet = .quickProfile(a) } } label: {
                        AvatarView(url: item.authorAccount.map { PostCard.companion.avatarUrl(account: $0, proxyBase: app.env.proxyBase, size: "small") } ?? "", size: 32)
                    }
                    .disabled(item.authorAccount == nil)
                    .accessibilityLabel(item.authorAccount.map { UiCopy.shared.profileOf(username: $0) } ?? "")
                    .accessibilityHidden(item.authorAccount == nil)
                }
                bubble(own)
                if !own { Spacer(minLength: gap) }
            }
            if !item.reactions.isEmpty { reactions(own) }
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chat-message-\(item.rowId)")
    }

    private func bubble(_ own: Bool) -> some View {
        let text = own ? Color.white : theme.text
        return VStack(alignment: .leading, spacing: 4) {
            VStack(alignment: .leading, spacing: 4) {
                if !own {
                    // the author's name opens the quick profile too: at accessibility sizes it is the only way there
                    // (the avatar gives the text its width)
                    HStack(spacing: 4) {
                        Text(item.author).scaledFont(13, weight: .bold).foregroundStyle(theme.accentText)
                            .contentShape(Rectangle())
                            // a long press opens the message options as anywhere on the bubble, and only a plain tap
                            // opens the profile (the long press wins exclusively, so its release never also taps)
                            .gesture(LongPressGesture(minimumDuration: 0.35).onEnded { _ in onLongPress() }
                                .exclusively(before: TapGesture().onEnded { if let a = item.authorAccount { app.sheet = .quickProfile(a) } }))
                        // the message label says it ([ChatBubbleText.label])
                        if item.authorOfficial { OfficialBadge() }
                    }
                    // VoiceOver reads the name in the message label and opens the profile through its action
                    .accessibilityHidden(true)
                }
                if let quote = item.quote { ChatQuoteView(quote: quote, own: own, onClose: nil) }
                if !item.spans.isEmpty {
                    Text(ChatAttributed.make(item.spans, linkColor: own ? .white : theme.accentText))
                        .scaledFont(15).foregroundStyle(text)
                        .environment(\.openURL, OpenURLAction { url in
                            app.openChatLink(url.absoluteString)
                            return .handled
                        })
                        .accessibilityIdentifier("chat-text-\(item.rowId)")
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(ChatBubbleText.label(item))
            .confirmationDialog(ChatUiCopy.shared.MORE_MENU, isPresented: $allLinks) {
                ForEach(Array(ChatBubbleText.links(item).enumerated()), id: \.offset) { _, link in
                    Button(link.name) { app.openChatLink(link.url) }
                }
            }
            .accessibilityActions {
                // the links inside the text: a combined element cannot reach them by touch, so each is an action (the
                // first few; "More" lists them all), opened the way a tap opens them
                let links = ChatBubbleText.links(item)
                ForEach(Array(links.prefix(ChatBubbleText.linkActions).enumerated()), id: \.offset) { _, link in
                    Button(link.name) { app.openChatLink(link.url) }
                }
                if links.count > ChatBubbleText.linkActions {
                    Button(ChatUiCopy.shared.MORE_MENU) { allLinks = true }
                }
                if !item.own, let author = item.authorAccount {
                    Button(UiCopy.shared.profileOf(username: author)) { app.sheet = .quickProfile(author) }
                }
                ForEach(actions, id: \.self) { action in
                    Button(action.label) { onAction(action) }
                }
                Button(ChatUiCopy.shared.MESSAGE_OPTIONS, action: onLongPress)
            }
            ForEach(item.images, id: \.self) { url in
                Button { app.sheet = .gallery(item.images, item.images.firstIndex(of: url) ?? 0) } label: {
                    LoadedImageView(src: url, maxWidth: 220) { _ in }
                        .frame(width: 220, height: 160).clipShape(RoundedRectangle(cornerRadius: 10))
                }
                .accessibilityLabel(UiCopy.shared.IMAGE)
            }
            if let card = item.card { ChatLinkCardView(card: card, own: own) { app.openChatLink(card.url) } }
            HStack(spacing: 6) {
                // read as part of the message above
                Text(item.edited ? ChatUiCopy.shared.editedAt(time: item.time) : item.time).scaledFont(11).foregroundStyle(own ? Color.white.opacity(0.85) : theme.secondary)
                    .accessibilityHidden(true)
                if own {
                    Button(action: onLongPress) { Image(appIcon: .more).scaledFont(12).foregroundStyle(.white).frame(minWidth: 28, minHeight: 20) }
                        .accessibilityLabel(ChatUiCopy.shared.MESSAGE_OPTIONS)
                        .largeContent(ChatUiCopy.shared.MESSAGE_OPTIONS, appIcon: .more)
                        .accessibilityIdentifier("chat-message-actions-\(item.rowId)")
                }
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(UnevenRoundedRectangle(topLeadingRadius: own ? 16 : 4, bottomLeadingRadius: 16, bottomTrailingRadius: 16, topTrailingRadius: own ? 4 : 16)
            .fill(own ? theme.accent : theme.lightBackground))
        .contentShape(Rectangle())
        .onLongPressGesture(minimumDuration: 0.35, perform: onLongPress)
    }

    private func reactions(_ own: Bool) -> some View {
        // reactions wrap onto more lines instead of running off screen at large text
        ChipFlow(spacing: 4) {
            ForEach(item.reactions, id: \.emojiName) { pill in
                ReactionPillView(pill: pill, id: "chat-reaction-\(item.rowId)-\(pill.emojiName)", onReact: onReact, onReactors: onReactors)
            }
        }
        .padding(own ? .trailing : .leading, own || typeSize.isAccessibilitySize ? 0 : 40)
        .frame(maxWidth: .infinity, alignment: own ? .trailing : .leading)
    }
}

/// The VoiceOver text of a message: author (for others), the quoted message, the text, edited and time.
enum ChatBubbleText {
    /// How many links of a message are actions of their own.
    static let linkActions = 5

    /// The message's links: shown text (for a link without text, a web link's host or the link itself) and the target
    /// as a tap opens it.
    static func links(_ item: ChatMessageItem) -> [(name: String, url: String)] { links(item.spans) }

    static func links(_ spans: [ChatSpan]) -> [(name: String, url: String)] {
        spans.compactMap { span in
            guard let raw = span.url else { return nil }
            let parsed = URL(string: raw)
            let name = span.text.trimmingCharacters(in: .whitespacesAndNewlines)
            // a web link without text is named by its host; any other (hive://sign/...) by the link as written
            let web = parsed?.scheme.map { ["http", "https"].contains($0.lowercased()) } ?? false
            return (name.isEmpty ? (web ? parsed?.host ?? raw : raw) : name, parsed?.absoluteString ?? raw)
        }
    }

    static func label(_ item: ChatMessageItem) -> String {
        var parts: [String] = []
        if !item.own { parts.append(item.author) }
        if !item.own && item.authorOfficial { parts.append(OfficialAccounts.shared.BADGE_LABEL) }
        if let quote = item.quote { parts.append(L.Native.Ios.A11yChat.quote(author: quote.author, text: quote.text)) }
        let text = item.spans.map(\.text).joined()
        if !text.isEmpty { parts.append(text) }
        if !item.images.isEmpty { parts.append(UiCopy.shared.IMAGE) }
        parts.append(item.edited ? ChatUiCopy.shared.editedAt(time: item.time) : item.time)
        return parts.joined(separator: ", ")
    }
}

struct ChatQuoteView: View {
    let quote: ChatQuote
    let own: Bool
    let onClose: (() -> Void)?
    @Environment(\.appTheme) private var theme

    var body: some View {
        HStack(spacing: 8) {
            Rectangle().fill(own ? Color.white : theme.accent).frame(width: 3).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(quote.author).scaledFont(12, weight: .bold).foregroundStyle(own ? .white : theme.accentText)
                Text(quote.text).scaledFont(12).lineLimit(2).foregroundStyle(own ? Color.white.opacity(0.9) : theme.secondary)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(L.Native.Ios.A11yChat.quote(author: quote.author, text: quote.text))
            Spacer(minLength: 0)
            if let onClose {
                Button(action: onClose) { Image(appIcon: .close).foregroundStyle(theme.icon).tapTarget() }
                    .accessibilityLabel(ChatUiCopy.shared.CANCEL_REPLY).accessibilityIdentifier("chat-cancel-reply")
            }
        }
        .padding(8)
        .background(RoundedRectangle(cornerRadius: 8).fill(own ? Color.white.opacity(0.15) : theme.background.opacity(0.6)))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chat-quote")
    }
}

struct ChatLinkCardView: View {
    let card: ChatLinkCard
    let own: Bool
    let onOpen: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        Button(action: onOpen) {
            VStack(alignment: .leading, spacing: 0) {
                if let image = card.image, !image.isEmpty {
                    LoadedImageView(src: image, maxWidth: 240) { _ in }.frame(maxWidth: .infinity).frame(height: 120).clipped()
                        .accessibilityHidden(true)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(card.title).scaledFont(13, weight: .bold).foregroundStyle(own ? .white : theme.text).lineLimit(2)
                    if !card.summary.isEmpty { Text(card.summary).scaledFont(12).foregroundStyle(own ? Color.white.opacity(0.85) : theme.secondary).lineLimit(2) }
                }
                .padding(8)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 10).fill(own ? Color.white.opacity(0.15) : theme.background))
            .clipShape(RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isLink)
        .accessibilityIdentifier("chat-link-card")
    }
}

/// The composer: mentions, editing banner, reply quote, link card, the field, attach and send.
struct ChatComposer: View {
    @ObservedObject var observed: Observed<ChatThreadState>
    let onAttach: () -> Void
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @Environment(\.motion) private var motion
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var text = ""
    @State private var version: Int32 = -1

    private var thread: ChatThreadModel { observed.model as! ChatThreadModel }

    var body: some View {
        let s = observed.state
        VStack(spacing: 6) {
            if !s.mentions.isEmpty {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(s.mentions, id: \.name) { m in
                        Button { thread.selectMention(m: m) } label: {
                            HStack(spacing: 8) {
                                AvatarView(url: PostCard.companion.avatarUrl(account: m.name, proxyBase: app.env.proxyBase, size: "small"), size: 24)
                                Text("@\(m.name)").scaledFont(13, weight: .bold).foregroundStyle(theme.text)
                                if let d = m.displayName { Text(d).scaledFont(12).foregroundStyle(theme.secondary).lineLimit(1) }
                                Spacer()
                            }
                            .padding(8).frame(minHeight: minTapTarget).contentShape(Rectangle())
                        }
                        .accessibilityIdentifier("chat-mention-\(m.name)")
                    }
                }
                .background(RoundedRectangle(cornerRadius: 10).fill(theme.lightBackground))
                .padding(.horizontal, 12)
            }
            HStack(alignment: .bottom, spacing: 8) {
                Button(action: onAttach) {
                    ZStack {
                        if s.uploading { ProgressView() } else { Image(appIcon: .add).scaledFont(22).foregroundStyle(theme.icon) }
                    }
                    .frame(width: 44, height: 44)
                }
                .disabled(s.uploading || s.sending)
                .accessibilityLabel(UiCopy.shared.ATTACH_IMAGE)
                .largeContent(UiCopy.shared.ATTACH_IMAGE, appIcon: .add)
                .accessibilityIdentifier("chat-attach")
                VStack(alignment: .leading, spacing: 6) {
                    if s.editingId != nil {
                        HStack {
                            Text(ChatThreadModel.companion.EDITING).scaledFont(12, weight: .bold).foregroundStyle(theme.accentText)
                            Spacer()
                            Button { thread.cancelEdit() } label: { Image(appIcon: .close).foregroundStyle(theme.icon).tapTarget() }
                                .accessibilityLabel(ChatUiCopy.shared.CANCEL_EDIT).accessibilityIdentifier("chat-cancel-edit")
                        }
                        .accessibilityIdentifier("chat-editing")
                    }
                    if let reply = s.replyTo { ChatQuoteView(quote: reply, own: false) { thread.cancelReply() } }
                    if let preview = s.preview { ChatLinkCardView(card: preview, own: false) {} }
                    TextField(ChatThreadModel.companion.PLACEHOLDER, text: $text, axis: .vertical)
                        .lineLimit(1...(typeSize.isAccessibilitySize ? 3 : 6))
                        .onChange(of: text) { _, value in if value != thread.current.text { thread.setText(text: value) } }
                        .accessibilityIdentifier("chat-composer")
                }
                .padding(.horizontal, 12).padding(.vertical, 10)
                .background(RoundedRectangle(cornerRadius: 16).fill(theme.lightBackground))
                // the arrow turns into progress, then a tick while the next message can already be typed (Motion.swift)
                Button { thread.send() } label: {
                    SendContent(sending: s.sending, sent: AnyHashable(Int(s.sentVersion))) {
                        Image(appIcon: .send, filled: true).foregroundStyle(.white)
                            // the paper plane has no forward symbol: it points along the reading direction, as Android's
                            .flipsForRightToLeftLayoutDirection(true)
                    }
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(s.canSend || s.sending ? theme.accent : theme.accent.opacity(0.4)))
                    .animation(motion.fade(), value: s.canSend)
                }
                .disabled(!s.canSend)
                .accessibilityLabel(UiCopy.shared.SEND)
                .largeContent(UiCopy.shared.SEND, appIcon: .send)
                .accessibilityIdentifier("chat-send")
            }
            .padding(.horizontal, 12).padding(.bottom, 8)
        }
        .onAppear { sync(s) }
        .onChange(of: s.textVersion) { _, _ in sync(observed.state) }
    }

    /// The field follows the model only when the model replaced the text (a mention, a sent message, an edit).
    private func sync(_ s: ChatThreadState) {
        guard version != s.textVersion else { return }
        version = s.textVersion
        if text != s.text { text = s.text }
    }
}

/// What the message options sheet picked.
enum MessageOptionChoice {
    case react(String)
    case more
    case action(ChatMessageAction)
}

/// RN chatOptionsSheet: quick reactions, More, then the options.
struct MessageOptionsSheet: View {
    let item: ChatMessageItem
    let actions: [ChatMessageAction]
    let onPick: (MessageOptionChoice) -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        ScrollView { VStack(spacing: 0) {
            // the quick reactions wrap at large text instead of pushing More off screen
            ChipFlow(spacing: 12) {
                ForEach(ChatEmoji.shared.QUICK, id: \.first) { pair in
                    let name = pair.first as String? ?? ""
                    Button { onPick(.react(name)) } label: { Text(pair.second as String? ?? "").scaledFont(28, relativeTo: .title).tapTarget() }
                        .accessibilityIdentifier("chat-quick-\(name)")
                }
                Button { onPick(.more) } label: {
                    Text("\u{22EF} " + ChatUiCopy.shared.MORE_MENU).scaledFont(15).foregroundStyle(theme.text).padding(.horizontal, 12).padding(.vertical, 8)
                        .background(Capsule().fill(theme.lightBackground)).frame(minHeight: minTapTarget)
                }
                .accessibilityLabel(ChatUiCopy.shared.MORE_MENU)
                .accessibilityIdentifier("chat-quick-more")
            }
            .padding(.vertical, 16).padding(.horizontal, 16)
            ForEach(actions, id: \.self) { action in
                Button { onPick(.action(action)) } label: {
                    Text(action.label.uppercased()).scaledFont(15).foregroundStyle(action.destructive ? theme.dangerText : theme.text)
                        .frame(maxWidth: .infinity).padding(.vertical, 12)
                }
                .accessibilityLabel(action.label)
                .accessibilityIdentifier("chat-option-\(action.name)")
            }
        } }
        .scrollBounceBehavior(.basedOnSize)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("chat-options-sheet")
    }
}

/// RN emojiPickerSheet.
struct EmojiPickerSheet: View {
    let onPick: (String) -> Void
    @State private var query = ""
    /// One emoji's cell, growing with the text size (at least the 44 pt touch target).
    @ScaledMetric(relativeTo: .title) private var emojiCell: CGFloat = 44
    @Environment(\.appTheme) private var theme

    var body: some View {
        let list = ChatEmoji.shared.search(query: query)
        VStack(alignment: .leading, spacing: 8) {
            Text(ChatUiCopy.shared.SELECT_EMOJI).scaledFont(18, weight: .bold).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
            TextField(ChatUiCopy.shared.SEARCH_EMOJIS, text: $query).textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityIdentifier("emoji-search")
            if list.isEmpty { Text(ChatUiCopy.shared.NO_EMOJI_FOUND).foregroundStyle(theme.secondary) }
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: emojiCell))]) {
                    ForEach(list, id: \.name) { e in
                        Button { onPick(e.name) } label: { Text(e.emoji).scaledFont(28, relativeTo: .title).frame(width: emojiCell, height: emojiCell) }
                            .accessibilityLabel(e.name).accessibilityIdentifier("emoji-\(e.name)")
                    }
                }
            }
        }
        .padding(16)
        .presentationDetents([.large])
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("emoji-picker")
    }
}

/// RN chatReactorsSheet.
struct ReactorsSheet: View {
    @StateObject private var model: Observed<ChatReactorsState>
    let onProfile: (String) -> Void
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    init(model: ChatReactorsModel, onProfile: @escaping (String) -> Void) {
        _model = StateObject(wrappedValue: Observed(model, ownsModel: true))
        self.onProfile = onProfile
    }

    private var reactors: ChatReactorsModel { model.model as! ChatReactorsModel }

    var body: some View {
        let s = model.state
        VStack(alignment: .leading, spacing: 8) {
            Text(ChatReactorsModel.companion.TITLE).scaledFont(18, weight: .bold).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(s.tabs, id: \.emojiName) { tab in
                        let active = tab.emojiName == s.selected
                        Button { reactors.select(emojiName: tab.emojiName) } label: {
                            HStack(spacing: 4) {
                                Text(ChatEmoji.shared.display(name: tab.emojiName))
                                Text(Fmt.int(tab.userIds.count)).scaledFont(12).foregroundStyle(active ? theme.accentText : theme.secondary)
                            }
                            .padding(.horizontal, 10).padding(.vertical, 6)
                            .background(Capsule().fill(active ? theme.accent.opacity(0.15) : theme.lightBackground))
                            .frame(minHeight: minTapTarget)
                        }
                        .accessibilityAddTraits(active ? .isSelected : [])
                        .accessibilityIdentifier("reactors-tab-\(tab.emojiName)")
                    }
                }
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(s.rows, id: \.userId) { r in
                        Button { if let a = r.account { onProfile(a) } } label: {
                            HStack(spacing: 10) {
                                AvatarView(url: r.account.map { PostCard.companion.avatarUrl(account: $0, proxyBase: app.env.proxyBase, size: "small") } ?? "", size: 32)
                                Text(r.label).foregroundStyle(theme.text)
                                Spacer()
                            }
                            .padding(.vertical, 8).contentShape(Rectangle())
                        }
                        .disabled(r.account == nil)
                        .accessibilityIdentifier("reactor-\(r.userId)")
                    }
                }
            }
        }
        .padding(16)
        .presentationDetents([.medium, .large])
        .onAppear { reactors.start() }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("reactors-sheet")
    }
}

/// RN PinnedMessagesModal.
struct PinnedSheet: View {
    @StateObject private var model: Observed<ChatPinnedState>
    let canUnpin: Bool
    let onOpen: (String) -> Void
    @Environment(\.appTheme) private var theme

    init(model: ChatPinnedModel, canUnpin: Bool, onOpen: @escaping (String) -> Void) {
        _model = StateObject(wrappedValue: Observed(model, ownsModel: true))
        self.canUnpin = canUnpin
        self.onOpen = onOpen
    }

    private var pinned: ChatPinnedModel { model.model as! ChatPinnedModel }

    var body: some View {
        let s = model.state
        VStack(alignment: .leading, spacing: 8) {
            Label(ChatPinnedModel.companion.TITLE, appIcon: .pin).scaledFont(18, weight: .bold).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
            if s.isLoading {
                ProgressView().frame(maxWidth: .infinity).padding(32)
            } else if let error = s.error {
                Text(error).foregroundStyle(theme.dangerText).padding(32).announces(error)
            } else if s.items.isEmpty {
                Text(ChatPinnedModel.companion.EMPTY).foregroundStyle(theme.secondary).padding(32)
            } else {
                ScrollView {
                    VStack(spacing: 8) {
                        ForEach(s.items, id: \.post.id) { row in
                            VStack(alignment: .leading, spacing: 6) {
                                HStack {
                                    Text(row.author).scaledFont(14, weight: .semibold).foregroundStyle(theme.accentText)
                                    Spacer()
                                    Text(row.time).scaledFont(12).foregroundStyle(theme.secondary)
                                    if canUnpin {
                                        Button { pinned.unpin(row: row) } label: { Image(appIcon: .close).foregroundStyle(theme.icon).tapTarget() }
                                            .accessibilityLabel(ChatUiCopy.shared.UNPIN).accessibilityIdentifier("unpin-\(row.post.id)")
                                    }
                                }
                                Text(row.text).scaledFont(14).foregroundStyle(theme.text).lineLimit(3)
                            }
                            .padding(12)
                            .background(RoundedRectangle(cornerRadius: 8).fill(theme.lightBackground))
                            .onTapGesture { onOpen(row.post.id) }
                            .accessibilityElement(children: .combine)
                            .accessibilityAddTraits(.isButton)
                            .accessibilityAction { onOpen(row.post.id) }
                            .accessibilityIdentifier("pinned-\(row.post.id)")
                        }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(16)
        .presentationDetents([.medium, .large])
        .onAppear { pinned.start() }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("pinned-sheet")
    }
}

/// RN OnlineUsersModal.
struct MembersSheet: View {
    let thread: ChatThreadModel
    let onProfile: (String) -> Void
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    var body: some View {
        let rows = thread.members()
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Image(appIcon: .members).foregroundStyle(theme.accentText).accessibilityHidden(true)
                VStack(alignment: .leading) {
                    Text(ChatUiCopy.shared.CHANNEL_MEMBERS).scaledFont(18, weight: .bold).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
                    Text(thread.membersSubtitle(rows: rows)).scaledFont(13).foregroundStyle(theme.secondary).accessibilityIdentifier("members-count")
                }
            }
            if rows.isEmpty { Text(ChatUiCopy.shared.NO_MEMBERS_FOUND).foregroundStyle(theme.secondary).padding(32) }
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(rows, id: \.userId) { m in
                        Button { if let a = m.account { onProfile(a) } } label: {
                            HStack(spacing: 12) {
                                AvatarView(url: m.account.map { PostCard.companion.avatarUrl(account: $0, proxyBase: app.env.proxyBase, size: "small") } ?? "", size: 40)
                                    .overlay(alignment: .bottomTrailing) {
                                        if m.online { Circle().fill(ChatColors.online).frame(width: 12, height: 12).overlay(Circle().stroke(theme.background, lineWidth: 2)) }
                                    }
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(m.displayName).scaledFont(15, weight: .medium).foregroundStyle(theme.text)
                                    if m.username != m.displayName { Text("@\(m.username)").scaledFont(13).foregroundStyle(theme.secondary) }
                                }
                                Spacer()
                            }
                            .padding(.vertical, 8).contentShape(Rectangle())
                        }
                        .disabled(m.account == nil)
                        .accessibilityIdentifier("member-\(m.userId)")
                    }
                }
            }
        }
        .padding(16)
        .presentationDetents([.medium, .large])
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("members-sheet")
    }
}

// MARK: - settings

/// RN settings "Chat Privacy" (signed in): who may send direct messages.
struct ChatPrivacyRow: View {
    @StateObject private var model: Observed<ChatPrivacyState>

    init(env: AppEnvironment) {
        _model = StateObject(wrappedValue: Observed(env.chat.privacyModel(), ownsModel: true))
    }

    private var privacy: ChatPrivacyModel { model.model as! ChatPrivacyModel }

    var body: some View {
        let s = model.state
        Picker(ChatPrivacyModel.companion.TITLE, selection: Binding(get: { s.value.apiValue }, set: { value in
            if value != s.value.apiValue, let choice = DmPrivacy.entries.first(where: { $0.apiValue == value }) { privacy.select(privacy: choice) }
        })) {
            ForEach(DmPrivacy.entries, id: \.apiValue) { Text($0.label).tag($0.apiValue) }
        }
        .pickerStyle(.menu)
        .disabled(s.saving)
        .onAppear { privacy.start() }
        .accessibilityIdentifier("settings-dm-privacy")
    }
}

/// One reaction under a message: tap toggles it, long press lists who reacted (RN MessageReactions).
struct ReactionPillView: View {
    let pill: ReactionPill
    let id: String
    let onReact: (String) -> Void
    let onReactors: (String) -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        let name = pill.emojiName
        let border: Color = pill.reacted ? theme.accent : theme.background
        let countColor: Color = pill.reacted ? theme.accentText : theme.secondary
        return HStack(spacing: 4) {
            Text(pill.emoji).scaledFont(14)
            if pill.showsCount { Text(Fmt.int(pill.count)).scaledFont(12).foregroundStyle(countColor) }
        }
        .padding(.horizontal, 8).padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: 12).fill(theme.lightBackground))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(border))
        // the pill stays small; its touch area is the full 44 points
        .tapTarget()
        .onTapGesture { onReact(name) }
        .onLongPressGesture(minimumDuration: 0.35) { onReactors(name) }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(pill.emoji) \(Fmt.int(pill.count))")
        .accessibilityHint(L.Native.Ios.Chat.reactorsHint)
        .accessibilityAddTraits(pill.reacted ? [.isButton, .isSelected] : .isButton)
        .accessibilityAction { onReact(name) }
        .accessibilityAction(named: L.Native.Ios.Chat.seeReactors) { onReactors(name) }
        .accessibilityIdentifier(id)
    }
}
