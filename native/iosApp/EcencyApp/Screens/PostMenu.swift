import EcencyCore
import SwiftUI
import UIKit

/// A Kotlin post handed to a sheet (sheets need an identity).
struct PostRef {
    let post: Post
}

/// The options menu for [post], opened from a card, the post screen or a comment.
struct MenuRequest {
    let post: Post
    let context: PostMenuContext
    /// The discussion root, for the reply composer (the post itself when nil).
    var root: PostKey?
}

/// RN MOD_NOTES sheet: the notes a mute or unmute carries.
struct NotesRequest {
    let item: PostMenuItem
    let post: Post
    let title: String
    let description: String
    let placeholder: String
    let confirmLabel: String
    /// The account that opened the sheet; a confirmation as anyone else is refused by the model.
    let user: String
}

struct ReplyRequest {
    let root: PostKey
    let parent: Post
}

/// Menu items that flip a two-state choice on the post: a light haptic as they are picked.
let MenuToggles: Set<PostMenuItem> = [.bookmarks, .reblog, .undoReblog]

/// What the UI does for a [PostMenuEffect]. Pure mapping, unit tested.
enum MenuEffectAction: Equatable {
    case copy(text: String, toast: String)
    case share(String)
    case push(String)
    case reply
    case crossPost
    case translate
    case confirm(title: String, body: String?, confirm: String)
    case notes(title: String)
    case login
    case nothing

    static func of(_ effect: PostMenuEffect) -> MenuEffectAction {
        switch effect {
        case let e as PostMenuEffectCopy: return .copy(text: e.text, toast: e.toast)
        case let e as PostMenuEffectShare: return .share(e.message)
        case let e as PostMenuEffectOpen: return .push(NavState.companion.encodeScreen(screen: e.screen))
        case is PostMenuEffectReply: return .reply
        case is PostMenuEffectCrossPost: return .crossPost
        case is PostMenuEffectTranslate: return .translate
        case let e as PostMenuEffectConfirm: return .confirm(title: e.title, body: e.body, confirm: e.confirmLabel)
        case let e as PostMenuEffectAskNotes: return .notes(title: e.title)
        case is PostMenuEffectLogin: return .login
        default: return .nothing
        }
    }
}

extension AppContainer {
    /// The dots button: the RN post options sheet.
    func openMenu(_ post: Post, context: PostMenuContext = PostMenuContext(translate: false, openThread: false, ownProfile: false),
                  root: PostKey? = nil) {
        sheet = .postMenu(MenuRequest(post: post, context: context, root: root))
    }

    /// The menu for a card: the post comes from the feed's cache (the bridge entry the card was built from).
    func openMenu(key: PostKey, context: PostMenuContext = PostMenuContext(translate: false, openThread: false, ownProfile: false)) {
        Task { @MainActor in
            guard let post = try? await env.api.posts.getPost(author: key.author, permlink: key.permlink,
                                                              observer: session.state.username, refresh: false) else { return }
            openMenu(post, context: context)
        }
    }

    /// RN: tapping an avatar or name opens the quick profile sheet.
    func openQuickProfile(_ username: String) { sheet = .quickProfile(username) }

    /// A sheet after the current one is gone (a sheet cannot present over itself).
    func showAfterSheet(_ next: AppSheet) {
        if sheet == nil {
            sheet = next
        } else {
            nextSheet = next
            sheet = nil
        }
    }

    /// Runs a menu tap through the shared model and performs the effect it returns.
    func select(_ item: PostMenuItem, request: MenuRequest) {
        // bookmark and reblog confirm with a light tap (Motion.swift)
        if MenuToggles.contains(item) { Haptics.play(.toggle) }
        Task { @MainActor in
            guard let effect = try? await env.postActions.select(item: item, shown: request.post) else { return }
            perform(effect, request: request)
        }
    }

    func perform(_ effect: PostMenuEffect, request: MenuRequest) {
        switch effect {
        case let e as PostMenuEffectCopy:
            UIPasteboard.general.string = e.text
            Haptics.play(.success)
            _ = env.ui.toast(text: e.toast)
        case let e as PostMenuEffectShare:
            SystemShare.present(e.message)
        case let e as PostMenuEffectOpen:
            push(e.screen)
        case let e as PostMenuEffectReply:
            // RN's menu "reply" opens the editor (the action bar and comment reply buttons open the quick reply)
            let root = request.root ?? e.post.key
            push(AppScreenEditor(args: EditorArgs(kind: .reply, author: e.post.author, permlink: e.post.permlink, draftId: "", templateId: "", community: "", tags: [], rootAuthor: root.author, rootPermlink: root.permlink)))
        case let e as PostMenuEffectCrossPost:
            showAfterSheet(.crossPost(PostRef(post: e.post)))
        case let e as PostMenuEffectTranslate:
            showAfterSheet(.translation(PostRef(post: e.post)))
        case let e as PostMenuEffectConfirm:
            confirm = e
        case let e as PostMenuEffectAskNotes:
            showAfterSheet(.modNotes(NotesRequest(item: e.item, post: e.post, title: e.title, description: e.description_,
                                                  placeholder: e.placeholder, confirmLabel: e.confirmLabel, user: e.user)))
        case is PostMenuEffectLogin:
            showAfterSheet(.login)
        default:
            break
        }
    }

    /// The reader's language for translation (RN: the app language, then the device locale).
    var readerLanguage: String { env.settings.contentLanguage }
}

/// RN postOptionsModal: a list of upper-cased items, Delete in red. Items load when the sheet opens.
struct PostMenuSheet: View {
    let request: MenuRequest
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var items: [PostMenuItem]?

    var body: some View {
        Group {
            if let items {
                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(items, id: \.self) { item in
                            Button {
                                dismiss()
                                app.select(item, request: request)
                            } label: {
                                Text(item.title)
                                    .scaledFont(14, weight: .semibold)
                                    .foregroundStyle(item.destructive ? theme.danger : theme.text)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 14)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("menu-item-\(item.id)")
                        }
                    }
                    .padding(.vertical, 8)
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, minHeight: 160)
            }
        }
        .background(theme.background)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .accessibilityIdentifier("post-menu")
        .task {
            items = (try? await app.env.postActions.menu(shown: request.post, context: request.context)) ?? []
        }
    }
}

/// RN crossPostModal: subscribed community picker, message, PUBLISH / CLOSE.
struct CrossPostSheet: View {
    @EnvironmentObject private var app: AppContainer
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appTheme) private var theme
    @StateObject private var model: Observed<CrossPostState>

    init(post: Post, env: AppEnvironment) {
        let version = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String) ?? EcencyCore.shared.VERSION
        _model = StateObject(wrappedValue: Observed(env.crossPostModel(post: post, appVersion: version), ownsModel: true))
    }

    private var cross: CrossPostModel { model.model as! CrossPostModel }

    var body: some View {
        let s = model.state
        NavigationStack {
            // scrolls at large text sizes
            ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Menu {
                    ForEach(s.filtered, id: \.name) { c in
                        Button(c.title) { cross.select(community: c) }
                    }
                } label: {
                    HStack {
                        Text(s.selected?.title ?? ReadingUiCopy.shared.SELECT_COMMUNITY).foregroundStyle(s.selected == nil ? theme.secondary : theme.text)
                        Spacer()
                        if s.isLoading { ProgressView() } else { Image(appIcon: .expand).accessibilityHidden(true) }
                    }
                    .padding(12)
                    .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 8))
                }
                .accessibilityIdentifier("crosspost-community")
                TextField(ReadingUiCopy.shared.SHARE_YOUR_THOUGHTS_HERE, text: Binding(get: { model.state.message }, set: { cross.setMessage(text: $0) }), axis: .vertical)
                    .lineLimit(3...8)
                    .padding(10)
                    .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 8))
                Text(ReadingUiCopy.shared.CROSS_POST_DESCRIPTION)
                    .scaledFont(12).foregroundStyle(theme.secondary)
                if let error = s.error { Text(error.message).scaledFont(12).foregroundStyle(theme.dangerText).announces(error.message, attempt: ObjectAttempt(error)) }
            }
            .padding(16)
            }
            .navigationTitle(ReadingUiCopy.shared.CROSS_POST)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(UiCopy.shared.CLOSE_CAPS) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if s.isPublishing { ProgressView() } else {
                        Button(ReadingUiCopy.shared.PUBLISH) { cross.publish() }.disabled(!s.canPublish).accessibilityIdentifier("crosspost-publish")
                    }
                }
            }
        }
        .onAppear { cross.start() }
        .onChange(of: s.published) { _, community in
            guard let community else { return }
            dismiss()
            app.push(AppScreenFeed(source: FeedSourceRanked(sort: .created, tag: community), title: s.selected?.title ?? community))
        }
    }
}

/// The reply composer opened from a menu: the app-wide draft of that parent.
struct MenuReplySheet: View {
    let request: ReplyRequest
    @EnvironmentObject private var app: AppContainer

    var body: some View {
        let draft = app.menuReplies.draft(root: request.root, parent: request.parent, env: app.env)
        ReplyComposer(parent: request.parent, draft: draft) {
            app.menuReplies.finish(request.parent)
            app.sheet = nil
        }
    }
}

/// The system share sheet over the top-most view controller.
enum SystemShare {
    @MainActor static func present(_ text: String) {
        guard let root = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene })
            .flatMap(\.windows).first(where: \.isKeyWindow)?.rootViewController else { return }
        var top = root
        while let presented = top.presentedViewController { top = presented }
        let controller = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        controller.popoverPresentationController?.sourceView = top.view
        top.present(controller, animated: true)
    }
}

/// The dots button RN puts on cards, the post header and comments.
struct MenuButton: View {
    let action: () -> Void
    @Environment(\.appTheme) private var theme

    var body: some View {
        Button(action: action) {
            Image(appIcon: .more).scaledFont(18, weight: .semibold).foregroundStyle(theme.icon)
                .frame(width: 32, height: 32).tapTarget()
        }
        .buttonStyle(.plain)
        .accessibilityLabel(ReadingUiCopy.shared.POST_OPTIONS)
        .largeContent(ReadingUiCopy.shared.POST_OPTIONS, appIcon: .more)
        .accessibilityIdentifier("post-options")
    }
}
