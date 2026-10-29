import AVFoundation
import EcencyCore
import SwiftUI

// MARK: Translation

/// RN postTranslateInline: the banner, then the translated plain text in place of the body.
struct TranslateBanner: View {
    @ObservedObject var model: Observed<TranslateState>
    let post: Post
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    private var translate: TranslateModel { model.model as! TranslateModel }

    var body: some View {
        let s = model.state
        if s.translated != nil {
            AdaptiveStack {
                Text(s.translatedFromText).scaledFont(12).foregroundStyle(theme.secondary)
                Spacer(minLength: 0)
                Button(ReadingUiCopy.shared.SHOW_ORIGINAL) { translate.showOriginal() }.scaledFont(12).accessibilityIdentifier("translate-original")
                Button(ReadingUiCopy.shared.CHANGE_LANGUAGE) { app.sheet = .translation(PostRef(post: post)) }.scaledFont(12)
            }
            .padding(.horizontal, 16).padding(.vertical, 6)
        } else if s.show {
            HStack(alignment: .top, spacing: 8) {
                Image(appIcon: .translate).foregroundStyle(theme.accentText).accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 6) {
                    Text(s.bannerText).scaledFont(13).foregroundStyle(theme.text)
                    AdaptiveStack {
                        Button(s.translateLabel) { translate.translate(post: post, target: s.target) }
                            .scaledFont(13, weight: .semibold).disabled(s.translating)
                            .accessibilityIdentifier("translate-go")
                        if s.translating { ProgressView().controlSize(.small) }
                        Button(ReadingUiCopy.shared.CHANGE_LANGUAGE) { app.sheet = .translation(PostRef(post: post)) }.scaledFont(13)
                    }
                }
                Spacer()
                Button { translate.dismiss() } label: { Image(appIcon: .close).foregroundStyle(theme.icon).tapTarget() }
                    .accessibilityLabel(UiCopy.shared.DISMISS)
                    .largeContent(UiCopy.shared.DISMISS, appIcon: .close)
            }
            .padding(12)
            .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 8))
            .padding(.horizontal, 16)
            .accessibilityIdentifier("translate-banner")
        }
    }
}

/// RN translated body: plain selectable text, 16 / 24, right to left for RTL targets.
struct TranslatedBody: View {
    let text: String
    let rtl: Bool
    @Environment(\.appTheme) private var theme

    var body: some View {
        Text(text)
            .scaledFont(16)
            .lineSpacing(8)
            .foregroundStyle(theme.text)
            .multilineTextAlignment(.leading)
            .frame(maxWidth: .infinity, alignment: .leading)
            // the target language's direction, whatever the app's: its leading edge is the right one for an RTL target
            .environment(\.layoutDirection, rtl ? .rightToLeft : .leftToRight)
            .textSelection(.enabled)
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .accessibilityIdentifier("translated-body")
    }
}

/// RN post-translation-modal: Auto or a source, a target, the summary and its translation.
struct TranslationSheet: View {
    @StateObject private var model: Observed<TranslationSheetState>
    @Environment(\.appTheme) private var theme

    init(post: Post, env: AppEnvironment, reader: String) {
        _model = StateObject(wrappedValue: Observed(env.translationSheetModel(post: post, readerLanguage: reader), ownsModel: true))
    }

    /// Over a model made elsewhere (a chat message's translation).
    init(model: TranslationSheetModel) {
        _model = StateObject(wrappedValue: Observed(model, ownsModel: true))
    }

    private var sheet: TranslationSheetModel { model.model as! TranslationSheetModel }

    var body: some View {
        let s = model.state
        NavigationStack {
            Form {
                Picker(L.Native.Ios.Reading.translateFrom, selection: Binding(get: { s.source }, set: { sheet.setSource(code: $0) })) {
                    Text(UiCopy.shared.AUTO).tag("auto")
                    ForEach(s.languages, id: \.first) { pair in Text(pair.second as String? ?? "").tag(pair.first as String? ?? "") }
                }
                Picker(L.Native.Ios.Reading.translateTo, selection: Binding(get: { s.target }, set: { sheet.setTarget(code: $0) })) {
                    ForEach(s.languages, id: \.first) { pair in Text(pair.second as String? ?? "").tag(pair.first as String? ?? "") }
                }
                Section {
                    Text(s.original).lineLimit(3).foregroundStyle(theme.secondary)
                }
                Section {
                    if s.isLoading {
                        ProgressView()
                    } else if let error = s.error {
                        Text(error).foregroundStyle(theme.dangerText)
                    } else if let t = s.translated {
                        Text(t).textSelection(.enabled)
                    }
                }
            }
            .navigationTitle(UiCopy.shared.TRANSLATE)
            .navigationBarTitleDisplayMode(.inline)
        }
        .onAppear { sheet.start() }
    }
}

// MARK: Poll

/// RN postPoll: header (question, end chip, notes), choices with bars, Vote, stats toggle, interpretation.
struct PollCard: View {
    @ObservedObject var model: Observed<PollState>
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    private var poll: PollModel { model.model as! PollModel }

    var body: some View {
        let s = model.state
        VStack(alignment: .leading, spacing: 10) {
            AdaptiveStack(verticalAlignment: .top) {
                Text(s.poll?.question ?? "").scaledFont(16, weight: .bold).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
                Spacer(minLength: 0)
                Text(s.endText).scaledFont(11, weight: .semibold).padding(.horizontal, 8).padding(.vertical, 4)
                    .background(theme.lightBackground, in: Capsule()).foregroundStyle(theme.secondary)
            }
            ForEach(s.notes, id: \.self) { Text($0).scaledFont(12).foregroundStyle(theme.secondary) }
            ForEach(s.rows, id: \.num) { row in
                Button { poll.toggle(num: row.num) } label: {
                    ZStack(alignment: .leading) {
                        GeometryReader { geo in
                            RoundedRectangle(cornerRadius: 6)
                                .fill(row.votedByMe ? theme.accent.opacity(0.35) : theme.icon.opacity(0.35))
                                .frame(width: geo.size.width * CGFloat(row.progress))
                        }
                        HStack {
                            if s.showResults && row.votedByMe { Image(appIcon: .checkCircle, filled: true).foregroundStyle(theme.accentText).accessibilityHidden(true) }
                            Text(row.text).scaledFont(14).foregroundStyle(theme.text).lineLimit(4)
                            Spacer()
                            if s.showResults { Text(row.label).scaledFont(12).foregroundStyle(theme.secondary) }
                        }
                        .padding(10)
                    }
                    .frame(minHeight: minTapTarget)
                    .overlay(RoundedRectangle(cornerRadius: 6).stroke(row.selected ? theme.accent : theme.border, lineWidth: row.selected ? 2 : 0.5))
                }
                .buttonStyle(.plain)
                .disabled(!s.canVote)
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(row.selected || (s.showResults && row.votedByMe) ? .isSelected : [])
                .accessibilityIdentifier("poll-choice-\(row.num)")
            }
            AdaptiveStack {
                if s.canVote {
                    Button { poll.vote() } label: { Label(ReadingUiCopy.shared.VOTE, appIcon: .chartBar) }
                        .buttonStyle(.prominentFill)
                        .disabled(s.selection.isEmpty || s.isVoting)
                        .accessibilityIdentifier("poll-vote")
                }
                if s.canToggleStats {
                    Button(s.showResults ? ReadingUiCopy.shared.HIDE_STATS : ReadingUiCopy.shared.VIEW_STATS) { poll.toggleStats() }.scaledFont(13)
                }
                Spacer(minLength: 0)
                if s.canSwitchInterpretation {
                    Button(s.interpretation == PollModel.companion.TOKENS ? ReadingUiCopy.shared.INTERPRET_BY_VOTES : ReadingUiCopy.shared.INTERPRET_BY_TOKENS) {
                        poll.switchInterpretation()
                    }
                    .scaledFont(12)
                }
            }
        }
        .padding(12)
        .background(theme.background)
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(theme.border, lineWidth: 0.5))
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .onAppear { poll.start() }
        .accessibilityIdentifier("poll-card")
    }
}

// MARK: Similar entries, parent post, bot comments

/// RN similarEntries: "Read next", two cards.
struct SimilarEntriesView: View {
    @ObservedObject var model: Observed<SimilarState>
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    var body: some View {
        if model.state.visible {
            VStack(alignment: .leading, spacing: 8) {
                Text(ReadingUiCopy.shared.READ_NEXT).scaledFont(16, weight: .bold).foregroundStyle(theme.text).accessibilityAddTraits(.isHeader)
                AdaptiveStack(verticalAlignment: .top, spacing: 10) {
                    ForEach(model.state.entries, id: \.permlink) { e in
                        Button { app.open(.post(author: e.author, permlink: e.permlink)) } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                ZStack {
                                    theme.lightBackground
                                    if let img = e.imageUrl, !img.isEmpty {
                                        if app.notifications.state.showImages || RevealedImages.shared.isRevealed(img) {
                                            LoadedImageView(src: img, maxWidth: 200, contentMode: .scaleAspectFill) { _ in }
                                        } else {
                                            // RN similarEntryItem useImageReveal: the placeholder until tapped, nothing requested before
                                            GeometryReader { geo in
                                                HiddenImageView(width: geo.size.width, height: 90) { RevealedImages.shared.reveal(img) }
                                            }
                                        }
                                    } else {
                                        Image(appIcon: .image).foregroundStyle(theme.icon).accessibilityHidden(true)
                                    }
                                }
                                .frame(height: 90).clipShape(RoundedRectangle(cornerRadius: 6))
                                Text(e.title).scaledFont(13, weight: .semibold).foregroundStyle(theme.text).lineLimit(2)
                                Text("@\(e.author)").scaledFont(11).foregroundStyle(theme.secondary)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .buttonStyle(.plain)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel([e.title, L.Native.Ios.Card.byAuthor(username: e.author)].joined(separator: ", "))
                        .accessibilityAddTraits(.isButton)
                    }
                }
            }
            .padding(16)
            .accessibilityIdentifier("similar-entries")
        }
    }
}

/// RN parentPost: a bordered box with the parent's title and summary; tap opens it.
struct ParentPostBox: View {
    let parent: Post
    let summary: String
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    var body: some View {
        Button { app.open(.post(author: parent.author, permlink: parent.permlink)) } label: {
            VStack(alignment: .leading, spacing: 4) {
                if !parent.title.isEmpty { Text(parent.title).scaledFont(14, weight: .bold).foregroundStyle(theme.text) }
                Text(summary).scaledFont(11).foregroundStyle(theme.secondary).lineLimit(3)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(theme.border, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .accessibilityIdentifier("parent-post")
    }
}

/// RN BotCommentsPreview: the bots' avatars after a double chevron; opens the bot comments.
struct BotCommentsPreview: View {
    let items: [CommentItem]
    let key: PostKey
    @EnvironmentObject private var app: AppContainer
    @Environment(\.appTheme) private var theme

    var body: some View {
        if !items.isEmpty {
            Button { app.push(AppScreenBotComments(author: key.author, permlink: key.permlink)) } label: {
                HStack(spacing: 4) {
                    Image(appIcon: .chevronDoubleRight).foregroundStyle(theme.icon)
                    ForEach(items.prefix(6), id: \.key) { AvatarView(url: $0.card.avatarUrl, size: 22) }
                }
                .frame(minHeight: minTapTarget).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L.Native.Navigation.botComments)
            .accessibilityIdentifier("bot-preview")
        }
    }
}
