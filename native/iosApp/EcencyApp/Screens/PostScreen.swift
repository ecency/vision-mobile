import Combine
import EcencyCore
import SwiftUI

/// Observers of the reply drafts this post screen opened. The drafts themselves are app-wide
/// (`env.replyModel` returns one per parent): a draft and its permlink stay until the reply is sent, a
/// send keeps running after the screen is gone, and reopening the post brings the draft back.
@MainActor
final class ReplyDrafts: ObservableObject {
    @MainActor final class Draft {
        let model: ComposeReplyModel
        let observed: Observed<ReplyState>
        init(_ model: ComposeReplyModel) {
            self.model = model
            // the environment owns the model; this screen only watches it
            observed = Observed(model, ownsModel: false)
        }
    }

    private var drafts: [String: Draft] = [:]

    func draft(root: PostKey, parent: Post, env: AppEnvironment) -> Draft {
        let id = "\(parent.author)/\(parent.permlink)"
        if let d = drafts[id] { return d }
        let d = Draft(env.replyModel(root: root, parent: parent))
        drafts[id] = d
        return d
    }

    /// Drops a draft after its reply was sent.
    func finish(_ parent: Post) { drafts["\(parent.author)/\(parent.permlink)"] = nil }
}

/// The reading extras of one post screen (translation, poll, read next), created once the post is in.
@MainActor
final class PostExtras: ObservableObject {
    let translate: Observed<TranslateState>
    let similar: Observed<SimilarState>
    /// RN postReadingMetadata's AI recap (paid, `RecapModel`).
    let recap: Observed<RecapState>
    /// RN NewsletterPostPrompt.
    let digestPrompt: Observed<DigestPromptState>
    private(set) var poll: Observed<PollState>?
    /// RN postDisplayView's view count and postStatsModal, once the post is in.
    private(set) var stats: Observed<PostStatsState>?
    /// RN postDisplayView's tipsQuery: the tip count beside the gift in the bar (read again after a tip from here).
    let tips: Observed<PostTipsState>
    private var pollFor: String?

    private var translateChanges: AnyCancellable?

    init(key: PostKey, env: AppEnvironment, reader: String) {
        translate = Observed(env.translateModel(author: key.author, permlink: key.permlink, readerLanguage: reader), ownsModel: true)
        similar = Observed(env.similarEntriesModel(), ownsModel: true)
        recap = Observed(env.recapModel(author: key.author, permlink: key.permlink, readerLanguage: reader), ownsModel: true)
        digestPrompt = Observed(env.digestPromptModel(), ownsModel: true)
        tips = Observed(env.postTipsModel(author: key.author, permlink: key.permlink), ownsModel: true)
        // the screen swaps the body for the translation: it redraws when the translation changes
        translateChanges = translate.objectWillChange.sink { [weak self] _ in self?.objectWillChange.send() }
    }

    /// Called when the post (or a new version of it) arrives.
    func update(_ post: Post, env: AppEnvironment) {
        (translate.model as! TranslateModel).start(post: post)
        (similar.model as! SimilarEntriesModel).start(post: post)
        (tips.model as! PostTipsModel).start()
        if stats == nil {
            let model = env.postStatsModel(post: post)
            stats = Observed(model, ownsModel: true)
            model.start()
            objectWillChange.send()
        }
        if pollFor != post.updated {
            pollFor = post.updated
            if poll == nil, let model = env.pollModel(post: post) {
                poll = Observed(model, ownsModel: true)
                objectWillChange.send()
            }
        }
    }
}

struct ReplyTarget: Identifiable {
    let parent: Post
    var id: String { "\(parent.author)/\(parent.permlink)" }
}

/// One in-post jump: the top-level body block, and an identity of its own so the same link jumps again.
struct AnchorJump: Equatable {
    let index: Int
    let id = UUID()
}

/// Post screen: header, body blocks and the comment list in ONE lazy stack, so a long body and a
/// long discussion are laid out only as they scroll in.
struct PostScreen: View {
    let key: PostKey
    @EnvironmentObject private var app: AppContainer
    @StateObject private var post: Observed<PostState>
    @StateObject private var drafts = ReplyDrafts()
    /// The post's Listen bar and player: they live as long as the screen, not as long as the bar's row is on screen,
    /// so the audio goes on while the post scrolls, behind pushed screens and in the background.
    @StateObject private var listen: ListenScreen
    @State private var replyTarget: ReplyTarget?
    @State private var bodyDrawnMarked = false
    @State private var expandedThreads: Set<PostKey> = []
    /// An API-flagged wave opened from a list: its media stays covered until revealed (review L8).
    @State private var waveRevealed = false
    /// #2403: the body block an `#id` link jumps to (a new value per tap, so the same link jumps again).
    @State private var anchorJump: AnchorJump?
    /// Bumped by the bar's comments icon: the list goes to the comments header (with its Reply).
    @State private var commentsJump = 0
    @Environment(\.dynamicTypeSize) private var typeSize
    /// The screen's width (the footer sits outside the content's GeometryReader).
    @State private var screenWidth: CGFloat = 0
    @StateObject private var extras: PostExtras
    @Environment(\.motion) private var motion
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss

    init(author: String, permlink: String, env: AppEnvironment, listenEngine: any ListenEngine = AVListenEngine()) {
        key = PostKey(author: author, permlink: permlink)
        _post = StateObject(wrappedValue: Observed(env.postModel(author: author, permlink: permlink), ownsModel: true))
        _listen = StateObject(wrappedValue: ListenScreen(key: PostKey(author: author, permlink: permlink), env: env,
                                                         uiLanguage: Locale.preferredLanguages.first ?? "en", engine: listenEngine))
        _extras = StateObject(wrappedValue: PostExtras(key: PostKey(author: author, permlink: permlink), env: env,
                                                       reader: env.settings.contentLanguage))
    }

    private var model: PostModel { post.model as! PostModel }

    var body: some View {
        content.overlay(alignment: .bottom) {
            if coveredWave {
                Button {
                    app.env.mutedReveals.reveal(key: key)
                    waveRevealed = true
                } label: {
                    Text(UiCopy.shared.NSFW_TAP_TO_REVEAL).font(.subheadline.bold()).foregroundStyle(.white)
                        .padding(.horizontal, 16).padding(.vertical, 10).background(theme.accent, in: Capsule())
                }
                .padding(16)
                .accessibilityIdentifier("post-nsfw-reveal")
            }
        }
    }

    private var content: some View {
        let state = post.state
        return GeometryReader { geo in
            let contentWidth = geo.size.width - 32
            Color.clear.frame(width: 0, height: 0)
                .onAppear { screenWidth = geo.size.width }
                .onChange(of: geo.size.width) { _, w in screenWidth = w }
            ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    if let card = state.card {
                        header(card)
                    } else if state.notFound {
                        Text(ReadingUiCopy.shared.THIS_POST_DOES_NOT).foregroundStyle(theme.secondary).padding(.top, 80)
                            .frame(maxWidth: .infinity)
                    } else if let error = state.error {
                        ErrorStateView(error: error) { model.retry() }.padding(.top, 80)
                    } else {
                        PostBodySkeleton().padding(.top, 16)
                    }

                    if state.card != nil {
                        if let parent = state.parent { ParentPostBox(parent: parent, summary: state.parentSummary) }
                        if let reading = state.reading {
                            ReadingBar(info: reading, recap: extras.recap)
                                .task(id: "\(state.post?.updated ?? "")|\(app.session.state.username ?? "")") {
                                    if let p = state.post { (extras.recap.model as! RecapModel).start(post: p, owner: app.session.state.username) }
                                }
                        }
                        if state.post != nil {
                            ListenSection(screen: listen, onLogin: { app.sheet = .login }) {
                                app.push(AppScreenCompanion.shared.of(route: .boost, arg: ""))
                            }
                        }
                        if let p = state.post { TranslateBanner(model: extras.translate, post: p) }
                        if let translated = extras.translate.state.translated {
                            TranslatedBody(text: translated, rtl: extras.translate.state.rtl)
                        } else {
                            bodySection(state, width: contentWidth)
                        }
                        // RN postDisplayView footer: "Posted by ... via ..." with the source and AI badges
                        if let p = state.post, state.body != nil, extras.translate.state.translated == nil { PostedByRow(post: p) }
                        // RN NewsletterPostPrompt, once the body is in
                        if let p = state.post, state.body != nil { DigestPostPrompt(model: extras.digestPrompt, post: p) }
                        if let poll = extras.poll { PollCard(model: poll) }
                        SimilarEntriesView(model: extras.similar)
                        commentsHeader(state).id("comments-header")
                        comments(state, width: geo.size.width)
                    }
                }
                .padding(.bottom, 40)
            }
            .refreshable { await refresh() }
            // RN postDisplayView: reading a post, comment or reply is the daily check-in (at most once per 15
            // minutes and account, the recorder's rule); asked again when the account lands or changes
            .task(id: app.session.state.username) { _ = app.env.checkIn.record() }
            .onChange(of: anchorJump) { _, jump in
                guard let jump, let blocks = state.body, jump.index < blocks.count else { return }
                // an anchor draws nothing: land on the block it stands before (the same place)
                var index = jump.index
                while index < blocks.count - 1, blocks[index] is BlockAnchor { index += 1 }
                let target = blockIDs(blocks)[index]
                withAnimation(motion.scroll) { proxy.scrollTo(target, anchor: .top) }
                // lazy rows above are estimated until laid out: settle once more
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { proxy.scrollTo(target, anchor: .top) }
            }
            // RN _scrollToComments: the bar's comments icon; repeated while the lazy rows above settle
            .onChange(of: commentsJump) { _, _ in
                withAnimation(motion.scroll) { proxy.scrollTo("comments-header", anchor: .top) }
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { proxy.scrollTo("comments-header", anchor: .top) }
            }
            .onChange(of: state.comments.count) {
                guard app.options.scrollToComments, !state.comments.isEmpty else { return }
                // Lazy rows above are estimated until laid out: repeat until the jump settles.
                for delay in [0.0, 0.5, 1.5, 3.0] {
                    DispatchQueue.main.asyncAfter(deadline: .now() + delay) { proxy.scrollTo("comments-header", anchor: .top) }
                }
            }
            }
        }
        .speakPlayersScope()
        // RN keeps the post actions in a bar pinned to the bottom of the screen.
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if let card = state.card, let p = state.post {
                footer(card, post: p)
                    .background(theme.background)
                    .overlay(alignment: .top) { Rectangle().fill(theme.lightBackground).frame(height: 1) }
            }
        }
        .background(theme.background)
        .navigationTitle(state.card.map { "@\($0.author)" } ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let p = state.post {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    // RN header edit icon: own posts and comments, into the editor (editor wave)
                    if p.author == app.session.state.username {
                        Button { app.push(AppScreenCompanion.shared.of(route: .editor, arg: "\(p.author)/\(p.permlink)")) } label: {
                            Image(appIcon: .compose)
                        }
                        .accessibilityLabel(UiCopy.shared.EDIT)
                        .largeContent(UiCopy.shared.EDIT, appIcon: .compose)
                        .accessibilityIdentifier("post-edit")
                    }
                    MenuButton {
                        // RN offers Translate in the menu only for sub-posts (comments opened on their own)
                        let isComment = p.depth > 0
                        app.openMenu(p, context: PostMenuContext(translate: isComment, openThread: false, ownProfile: false))
                    }
                }
            }
        }
        .onAppear {
            model.start()
            if let p = post.state.post {
                listen.model.start(post: p)
                if extras.stats == nil { extras.update(p, env: app.env) }
            }
        }
        .onChange(of: state.post?.updated) { _, _ in
            if let p = post.state.post {
                extras.update(p, env: app.env)
                listen.model.start(post: p)
            }
        }
        .onReceive(app.postActions.$state) { s in
            // RN popScreenOnDelete: the post this screen shows was deleted from the menu
            if s.deleted.contains(key) { dismiss() }
        }
        .sheet(item: $replyTarget) { target in
            let draft = drafts.draft(root: key, parent: target.parent, env: app.env)
            ReplyComposer(parent: target.parent, draft: draft, onSent: {
                drafts.finish(target.parent)
                replyTarget = nil
            }, onExpand: { args in
                replyTarget = nil
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { app.push(AppScreenEditor(args: args)) }
            })
        }
    }

    // MARK: Sections

    private func header(_ card: PostCard) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(card.title)
                .scaledFont(24, weight: .semibold, relativeTo: .title2)
                .foregroundStyle(theme.text)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("post-title")
            HStack(alignment: .top) {
                AuthorLine(card: card, avatarSize: 36, nameSize: 15,
                           onAuthor: { app.openQuickProfile(card.author) },
                           onCategory: { app.open(.feed(tag: card.category, sort: RankedSort.trending.apiValue, title: CardText.category(card))) })
                // RN postDisplayView headerWithStats: the view count, tap for the stats modal
                if let stats = extras.stats { PostViewCountView(model: stats) }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 8)
    }

    @ViewBuilder
    private func bodySection(_ state: PostState, width: CGFloat) -> some View {
        if let blocks = state.body {
            let images = GalleryImages(blocks)
            let direction = bodyDirection(blocks)
            if blocks.isEmpty {
                Color.clear.frame(height: 1).onAppear(perform: markBodyDrawn)
            }
            ForEach(blockIDs(blocks), id: \.self) { id in
                // the body reads in its own direction (bodyDirection), not the app's
                BlockView(block: blocks[id.index], maxWidth: width, actions: bodyActions, images: images)
                    .environment(\.layoutDirection, direction)
                    .padding(.horizontal, 16)
                    .onAppear { if id.index == 0 { markBodyDrawn() } }
            }
        } else {
            BodyPlaceholder().padding(.horizontal, 16)
        }
    }

    /// RN postDisplayView's action bar: vote and payout, voters, reblogs, the comments icon (to the comments and their
    /// Reply; no Reply in the bar) and the tip with the post's tip count. Wraps onto lines at large text sizes.
    private func footer(_ card: PostCard, post p: Post) -> some View {
        // before the first layout the width is unknown: no wrapping by width (the text size rule still applies)
        let wraps = AccessibleFooter.wraps(typeSize, width: screenWidth > 0 ? screenWidth : .infinity)
        return (wraps ? AnyLayout(ChipFlow(spacing: 16)) : AnyLayout(HStackLayout(spacing: 16))) {
            VoteButton(key: card.key, votedByMe: card.votedByMe, downvotedByMe: card.downvotedByMe,
                       payoutText: card.payoutText, payoutDeclined: card.isPayoutDeclined, payoutPost: { p })
            Button { app.push(AppScreenVoters(author: card.key.author, permlink: card.key.permlink)) } label: {
                CountLabel(appIcon: .heart, count: card.votes)
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(L.Post.a11yVotes(count: Int(card.votes)))
            .accessibilityHint(L.Post.a11yVotersHint)
            .accessibilityAddTraits(.isButton)
            .accessibilityIdentifier("post-voters")
            if !wraps { Spacer(minLength: 0) }
            Button { app.push(AppScreenReblogs(author: card.key.author, permlink: card.key.permlink)) } label: {
                CountLabel(appIcon: .reblog, count: card.reblogs)
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(L.Post.a11yReblogs(count: Int(card.reblogs)))
            .accessibilityHint(L.Post.a11yReblogsHint)
            .accessibilityAddTraits(.isButton)
            .accessibilityIdentifier("post-reblogs")
            Button { commentsJump += 1 } label: { CountLabel(appIcon: .comment, count: card.comments) }
                .buttonStyle(.plain)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(L.Post.a11yComments(count: Int(card.comments)))
                .accessibilityHint(L.Post.a11yCommentsHint)
                .accessibilityAddTraits(.isButton)
                .accessibilityIdentifier("post-comments")
            TipButton(key: card.key, tips: extras.tips)
        }
        .padding(16)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("post-footer")
    }

    private func commentsHeader(_ state: PostState) -> some View {
        AdaptiveStack {
            HStack {
                Text(state.card.map { L.Post.a11yComments(count: Int($0.comments)) } ?? ReadingUiCopy.shared.COMMENTS)
                    .scaledFont(16, weight: .bold, relativeTo: .headline)
                    .foregroundStyle(theme.text)
                    .accessibilityAddTraits(.isHeader)
                BotCommentsPreview(items: state.botComments, key: key)
            }
            Spacer(minLength: 0)
            // the Reply to the post: the bar's comments icon brings the reader here
            if let p = state.post {
                Button {
                    if app.requireSignIn(to: .reply) { replyTarget = ReplyTarget(parent: p) }
                } label: {
                    Label(ReadingUiCopy.shared.REPLY, appIcon: .reply).scaledFont(14, weight: .semibold).frame(minHeight: minTapTarget)
                }
                .accessibilityLabel(ReadingUiCopy.shared.REPLY)
                .largeContent(ReadingUiCopy.shared.REPLY, appIcon: .reply)
                .accessibilityIdentifier("comments-reply")
            }
            Menu {
                Picker(L.Native.Ios.Reading.sortComments, selection: Binding(get: { state.commentSort }, set: { model.setCommentSort(sort: $0) })) {
                    ForEach(CommentSortLabel.all, id: \.0) { sort, title in Text(title).tag(sort) }
                }
            } label: {
                Label(CommentSortLabel.title(state.commentSort), appIcon: .sort).scaledFont(14).frame(minHeight: minTapTarget)
            }
            .accessibilityLabel(L.Native.Ios.Reading.sortCommentsValue(sort: CommentSortLabel.title(state.commentSort)))
            .accessibilityIdentifier("comment-sort")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .overlay(alignment: .top) { Rectangle().fill(theme.lightBackground).frame(height: 1) }
    }

    @ViewBuilder
    private func comments(_ state: PostState, width: CGFloat) -> some View {
        if state.comments.isEmpty {
            if let error = state.commentsError {
                InlineErrorRow(error: error) { model.retry() }
            } else if state.commentsLoading {
                ProgressView().frame(maxWidth: .infinity).padding(24)
            } else {
                Text(L.Native.Ios.Reading.noComments).foregroundStyle(theme.secondary).padding(16)
            }
        }
        // Identity is the comment key (see FeedListView): a vote updates the row, never replaces it.
        // RN commentsSection: replies under a top-level comment start collapsed; the chevron pill opens them.
        let threads = CommentThreads.shared
        ForEach(threads.visible(comments: state.comments, expanded: expandedThreads), id: \.key) { item in
            CommentRow(item: item, width: width, actions: bodyActions, root: key,
                       thread: threads.hasToggle(item: item) ? ThreadToggleState(open: threads.isOpen(comments: state.comments, key: item.key, expanded: expandedThreads)) {
                           if threads.loadedReplies(comments: state.comments, key: item.key) == 0 {
                               app.open(.post(author: item.post.author, permlink: item.post.permlink))
                           } else {
                               expandedThreads = threads.toggle(expanded: expandedThreads, key: item.key)
                           }
                       } : nil) {
                if app.requireSignIn(to: .reply) { replyTarget = ReplyTarget(parent: item.post) }
            }
        }
        if !state.comments.isEmpty, let error = state.commentsError {
            InlineErrorRow(error: error) { model.retry() }
        }
    }

    // MARK: Actions

    private var bodyActions: PostActions {
        PostActions(
            onLink: { target in
                // an `#id` jump scrolls the body (no screen is pushed, so back still leaves the post)
                if let anchor = target as? LinkTargetAnchor {
                    let here = LinkTargetPost(author: key.author, permlink: key.permlink)
                    if let blocks = post.state.body, let index = BlockAnchors.shared.jump(blocks: blocks, target: anchor, current: here) {
                        anchorJump = AnchorJump(index: index.intValue)
                        return
                    }
                    if anchor.post == nil { return }
                }
                app.handleBodyLink(target)
            },
            onImage: { url, all in app.sheet = .gallery(all.isEmpty ? [url] : all, all.firstIndex(of: url) ?? 0) },
            // RN "Show Images": body and comment images wait for a tap, the viewer shows the revealed ones
            showImages: app.notifications.state.showImages && !coveredWave
        )
    }

    private var coveredWave: Bool {
        !waveRevealed && app.env.mutedReveals.coversWave(key: key, mode: NsfwMode.companion.of(prefs: app.env.prefs))
    }

    private func markBodyDrawn() {
        guard !bodyDrawnMarked else { return }
        bodyDrawnMarked = true
        _ = PerfMarks.shared.mark(event: PerfEvent.shared.POST_BODY_DRAWN)
    }

    /// Pull to refresh: keeps the spinner until the post and comments are in (or the screen closed the model).
    @MainActor private func refresh() async {
        try? await model.refreshAndWait()
    }
}

/// Labels for the comment sort menu (the RN comment filter's names).
enum CommentSortLabel {
    static var all: [(CommentSort, String)] { [(.trending, ReadingUiCopy.shared.TOP), (.reputation, UiCopy.shared.REPUTATION), (.votes, ReadingUiCopy.shared.VOTES), (.age, ReadingUiCopy.shared.AGE)] }
    static func title(_ sort: CommentSort) -> String { all.first { $0.0 == sort }?.1 ?? ReadingUiCopy.shared.TOP }
}

/// Gray lines in place of the body while it renders off the main thread.
struct BodyPlaceholder: View {
    @Environment(\.appTheme) private var theme

    var body: some View {
        // the shimmer skeleton (Motion.swift)
        Skeleton(identifier: "body-placeholder", label: L.Native.Ios.Reading.loadingPost) {
            VStack(alignment: .leading, spacing: 10) {
                ForEach(0..<6, id: \.self) { i in Bone(width: i == 5 ? 180 : nil, height: 14) }
            }
            .padding(.vertical, 12)
        }
    }
}

/// One comment: author line, body, actions; indented by level (1...3), "more replies" below level 3.
struct CommentRow: View {
    let item: CommentItem
    let width: CGFloat
    let actions: PostActions
    /// The discussion's root (the menu's reply composer belongs to it).
    let root: PostKey
    /// The collapse toggle of a top-level comment's replies (RN commentView's chevron pill), or nil.
    var thread: ThreadToggleState? = nil
    let onReply: () -> Void
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    /// Indent per level past the first, as in the RN comment list.
    static func indent(level: Int32) -> CGFloat { CGFloat(max(0, min(3, Int(level)) - 1)) * 22 }
    static let bodyInset: CGFloat = 38

    var body: some View {
        let indent = Self.indent(level: item.level)
        // Body and actions line up with the name, as in the RN comment list (avatar 28 + gap 10).
        let bodyWidth = width - 32 - indent - Self.bodyInset
        let card = item.card
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                AuthorLine(card: card, avatarSize: 28, nameSize: 14, showCategory: false,
                           onAuthor: { app.openQuickProfile(card.author) })
                    // the comment's controls as actions on its author line, so they are one swipe away
                    .accessibilityAction(named: L.Native.Ios.Reading.replyTo(username: card.author), onReply)
                    .accessibilityAction(named: L.Post.a11yVotersHint) { app.push(AppScreenVoters(author: card.key.author, permlink: card.key.permlink)) }
                    .accessibilityAction(named: L.Post.a11yTip) { app.sheet = app.session.state.isLoggedIn ? .tip(card.key) : .login }
                    .accessibilityAction(named: L.Post.a11yPostOptions) {
                        app.openMenu(item.post, context: PostMenuContext(translate: true, openThread: true, ownProfile: false), root: root)
                    }
                if item.isLocal {
                    Text(L.Schedules.pending).scaledFont(11, weight: .semibold, relativeTo: .caption)
                        .foregroundStyle(theme.secondary)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(theme.lightBackground, in: Capsule())
                }
                Spacer(minLength: 0)
                // RN commentView: the dots on the author line (PostHeaderDescription showDotMenuButton), with the
                // comment menu's translate and open thread
                MenuButton {
                    app.openMenu(item.post, context: PostMenuContext(translate: true, openThread: true, ownProfile: false), root: root)
                }
            }
            PostBodyView(blocks: item.blocks, contentWidth: bodyWidth, actions: actions)
                .padding(.leading, Self.bodyInset)
            // one row; at large text sizes the vote and count over the other controls, and at the largest the
            // replies (count and caret, kept together) on a line of their own
            ViewThatFits(in: .horizontal) {
                // tight spacing: the 44 pt targets already space the icons apart
                HStack(spacing: 6) {
                    commentVote(card)
                    Spacer(minLength: 4)
                    commentTools(card)
                    commentReplies(card)
                }
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 14) { commentVote(card) }
                    HStack(spacing: 14) { commentTools(card); commentReplies(card) }
                }
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 14) { commentVote(card) }
                    HStack(spacing: 14) { commentTools(card) }
                    HStack(spacing: 14) { commentReplies(card) }
                }
            }
            .padding(.leading, Self.bodyInset - 4)
            if item.hasHiddenReplies {
                Button {
                    app.open(.post(author: item.post.author, permlink: item.post.permlink))
                } label: {
                    Label(ReadingUiCopy.shared.MORE_REPLIES, appIcon: .chevronRight).scaledFont(13, relativeTo: .footnote)
                }
                .accessibilityIdentifier("more-replies")
                .padding(.leading, Self.bodyInset)
            }
        }
        .padding(.leading, 16 + indent)
        .padding(.trailing, 16)
        .padding(.vertical, 10)
        .overlay(alignment: .leading) {
            if item.level > 1 {
                Rectangle().fill(theme.lightBackground).frame(width: 2).padding(.leading, 16 + indent - 10)
            }
        }
    }

    @ViewBuilder private func commentVote(_ card: PostCard) -> some View {
        VoteButton(key: card.key, votedByMe: card.votedByMe, downvotedByMe: card.downvotedByMe,
                   payoutText: card.payoutText, payoutDeclined: card.isPayoutDeclined, iconSize: 18, payoutPost: { item.post })
        CountLabel(appIcon: .heart, count: card.votes)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(L.Post.a11yVotes(count: Int(card.votes)))
    }

    @ViewBuilder private func commentTools(_ card: PostCard) -> some View {
        TipButton(key: card.key)
    }

    /// The comments icon with the reply count is the reply button (no "Reply" text, so the caret stays on the line).
    @ViewBuilder private func commentReplies(_ card: PostCard) -> some View {
        HStack(spacing: 6) {
            Button(action: onReply) { CountLabel(appIcon: .comment, count: card.comments) }
                .buttonStyle(.plain)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(L.Native.Ios.Reading.replyTo(username: card.author))
                .accessibilityValue(L.Post.a11yComments(count: Int(card.comments)))
                .accessibilityAddTraits(.isButton)
                .accessibilityIdentifier("reply-comment")
            if let thread { ThreadTogglePill(toggle: thread) }
        }
    }
}
