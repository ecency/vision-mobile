import EcencyCore
import SafariServices
import SwiftUI
import WebKit

/// Reply composer over a `ComposeReplyModel`. The text lives in the model, so closing the sheet after a
/// failure keeps the draft (and its permlink) for the next attempt; only a success clears it.
struct ReplyComposer: View {
    let parent: Post
    let draft: ReplyDrafts.Draft
    let onSent: () -> Void
    var onExpand: ((EditorArgs) -> Void)?
    @ObservedObject private var reply: Observed<ReplyState>
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appTheme) private var theme
    @Environment(\.motion) private var motion
    @FocusState private var focused: Bool
    /// The editor's own text. Reading it back from `reply.state` lost keystrokes: the watched state arrives a
    /// main-queue turn after `setText`, so the editor was reset to a stale value while the user kept typing.
    @State private var text: String

    init(parent: Post, draft: ReplyDrafts.Draft, onSent: @escaping () -> Void, onExpand: ((EditorArgs) -> Void)? = nil) {
        self.parent = parent
        self.draft = draft
        self.onSent = onSent
        self.onExpand = onExpand
        _reply = ObservedObject(wrappedValue: draft.observed)
        _text = State(initialValue: draft.observed.state.text)
    }

    var body: some View {
        let state = reply.state
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                Text(L.Native.Ios.Reading.replyingTo(username: parent.author)).scaledFont(13, relativeTo: .footnote).foregroundStyle(theme.secondary)
                TextEditor(text: $text)
                    .onChange(of: text) { draft.model.setText(text: text) }
                    .focused($focused)
                    .scrollContentBackground(.hidden)
                    .padding(8)
                    .background(theme.lightBackground, in: RoundedRectangle(cornerRadius: 8))
                    .disabled(state.isSending)
                    .accessibilityLabel(L.Native.Ios.Reading.replyText)
                    .accessibilityIdentifier("reply-text")
                // RN quick post: too short to earn points
                if Quests.shared.showsShortHint(username: "me", isEditing: false, body: text) {
                    Text(Quests.shared.SHORT_CONTENT_HINT).scaledFont(12, relativeTo: .caption).foregroundStyle(theme.secondary).accessibilityIdentifier("short-content-hint")
                }
                if let error = state.error {
                    AdaptiveStack(verticalAlignment: .top) {
                        HStack(alignment: .top) {
                            Image(appIcon: .error).foregroundStyle(theme.dangerText).accessibilityHidden(true)
                            Text(error.message).scaledFont(13, relativeTo: .footnote).foregroundStyle(theme.dangerText)
                        }
                        Spacer(minLength: 0)
                        if error.retryable {
                            Button(UiCopy.shared.RETRY) { draft.model.send() }.accessibilityIdentifier("reply-retry")
                        }
                    }
                    .accessibilityElement(children: .combine)
                    .announces(error.message, attempt: ObjectAttempt(error))
                    .accessibilityIdentifier("reply-error")
                }
            }
            .padding(16)
            .background(theme.background)
            .navigationTitle(ReadingUiCopy.shared.REPLY)
            .navigationBarTitleDisplayMode(.inline)
            // a draft kept on the device arrives a moment after the sheet opened
            .onReceive(reply.$state) { s in if text.isEmpty, !s.text.isEmpty, s.sent == nil { text = s.text } }
            .confirmationDialog(UiCopy.shared.YOUR_REPLY_WAS_ALREADY, isPresented: Binding(get: { reply.state.conflict }, set: { if !$0 { draft.model.dismissConflict() } }), titleVisibility: .visible) {
                // an earlier attempt is on chain and the text changed since: never edited silently
                Button(UiCopy.shared.UPDATE_IT) { draft.model.resolveConflict(update: true) }
                Button(UiCopy.shared.NEW_REPLY) { draft.model.resolveConflict(update: false) }
                Button(UiCopy.shared.CANCEL, role: .cancel) { draft.model.dismissConflict() }
            } message: { Text(UiCopy.shared.UPDATE_IT_OR_POST) }
            // RN: a reply HiveSigner refused for the missing ecency.app authority offers the grant; closing it is a skip
            .postingAuthorityPrompt(isPresented: reply.state.authorityPrompt, onAnswer: { draft.model.answerAuthorityPrompt(grant: $0) }, onDismiss: { draft.model.answerAuthorityPrompt(grant: false) })
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(UiCopy.shared.CLOSE) { dismiss() } }
                // RN quick reply "expand": the same reply continues in the full editor
                ToolbarItem(placement: .primaryAction) {
                    if !state.isSending, let onExpand {
                        Button { draft.model.expand { args in dismiss(); onExpand(args) } } label: { Image(appIcon: .fullscreen) }
                            .accessibilityLabel(ReadingUiCopy.shared.OPEN_EDITOR).largeContent(ReadingUiCopy.shared.OPEN_EDITOR, appIcon: .fullscreen)
                            .accessibilityIdentifier("reply-expand")
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    // Send turns into progress in place; the sheet closes on success, with the success haptic (Motion.swift)
                    ZStack {
                        if state.isSending {
                            ProgressView().transition(motion.swap)
                        } else {
                            Button(UiCopy.shared.SEND) { draft.model.send() }.disabled(!state.canSend).accessibilityIdentifier("reply-send").transition(motion.swap)
                        }
                    }
                    .animation(motion.pop, value: state.isSending)
                }
            }
        }
        .onAppear { focused = true }
        .onChange(of: state.sent) { if state.sent != nil { Haptics.play(.success); onSent() } }
        .interactiveDismissDisabled(state.isSending)
    }
}

/// SFSafariViewController for http(s) links.
struct SafariView: UIViewControllerRepresentable {
    let url: URL

    func makeUIViewController(context: Context) -> SFSafariViewController {
        let vc = SFSafariViewController(url: url)
        vc.dismissButtonStyle = .close
        return vc
    }

    func updateUIViewController(_ vc: SFSafariViewController, context: Context) {}
}

/// Video sheet: a WKWebView on the allowlisted embed URL only. No script message handlers, no new
/// windows, and every navigation outside the embed host is cancelled.
struct VideoPlayerSheet: View {
    let url: URL
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            EmbedWebView(url: url)
                .aspectRatio(16.0 / 9.0, contentMode: .fit)
                .frame(maxHeight: .infinity)
                .background(Color.black)
                .accessibilityIdentifier("video-player")
                .navigationTitle(url.host ?? UiCopy.shared.VIDEO)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button(UiCopy.shared.DONE) { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}

struct EmbedWebView: UIViewRepresentable {
    let url: URL

    func makeCoordinator() -> Coordinator { Coordinator(embed: url) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.websiteDataStore = .nonPersistent()
        let view = WKWebView(frame: .zero, configuration: config)
        view.navigationDelegate = context.coordinator
        view.uiDelegate = context.coordinator
        view.isOpaque = false
        view.backgroundColor = .black
        var request = URLRequest(url: url)
        // Embedded players reject requests without an embedding origin.
        request.setValue("https://ecency.com/", forHTTPHeaderField: "Referer")
        view.load(request)
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        let embed: URL
        init(embed: URL) { self.embed = embed }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            decisionHandler(VideoEmbedPolicy.allows(action.request.url, embed: embed) ? .allow : .cancel)
        }

        /// `target=_blank` and `window.open`: never open anything.
        func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                     for action: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? { nil }
    }
}

/// RN imageViewer (react-native-image-viewing): the body images in a pager starting at the tapped one, pinch and double
/// tap (1.5x) zoom, and the header: close, "Preview (i/n)", share, copy link and save to the "Ecency" album.
struct GallerySheet: View {
    let urls: [String]
    @State var index: Int
    @State private var zoomed = false
    @State private var saving = false
    @State private var alert: (title: String, message: String)?
    @EnvironmentObject private var app: AppContainer
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            GeometryReader { geo in
                TabView(selection: $index) {
                    ForEach(Array(urls.enumerated()), id: \.offset) { i, url in
                        ZoomableImageView(src: url, size: geo.size) { z in if i == index { zoomed = z } }
                            .tag(i)
                    }
                }
                .tabViewStyle(.page)
                .scrollDisabled(zoomed)
            }
            .background(Color.black)
            .navigationTitle(UiCopy.shared.preview(index: Int32(index + 1), count: Int32(urls.count)))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Image(appIcon: .close) }.accessibilityLabel(UiCopy.shared.CLOSE)
                        .largeContent(UiCopy.shared.CLOSE, appIcon: .close).accessibilityIdentifier("gallery-close")
                }
                ToolbarItemGroup(placement: .primaryAction) {
                    if let url = urls[safe: index], let link = URL(string: url) {
                        ShareLink(item: link) { Image(appIcon: .share) }
                            .accessibilityLabel(UiCopy.shared.SHARE)
                            .largeContent(UiCopy.shared.SHARE, appIcon: .share).accessibilityIdentifier("gallery-share")
                        Button {
                            UIPasteboard.general.string = url
                            Haptics.play(.success)
                            _ = app.env.ui.toast(text: UiCopy.shared.COPIED)
                        } label: { Image(appIcon: .clipboard) }
                            .accessibilityLabel(UiCopy.shared.COPY_LINK)
                            .largeContent(UiCopy.shared.COPY_LINK, appIcon: .clipboard).accessibilityIdentifier("gallery-copy")
                        if saving {
                            ProgressView()
                        } else {
                            Button { save(link) } label: { Image(appIcon: .download) }
                                .accessibilityLabel(UiCopy.shared.SAVE_IMAGE)
                                .largeContent(UiCopy.shared.SAVE_IMAGE, appIcon: .download).accessibilityIdentifier("gallery-save")
                        }
                    }
                }
            }
            .alert(alert?.title ?? "", isPresented: Binding(get: { alert != nil }, set: { if !$0 { alert = nil } })) {
                Button(UiCopy.shared.OK, role: .cancel) {}
            } message: { Text(alert?.message ?? "") }
            .accessibilityIdentifier("gallery")
        }
    }

    private func save(_ url: URL) {
        saving = true
        Task {
            let error = await PhotoSaver.save(url)
            saving = false
            alert = error == nil ? (UiCopy.shared.IMAGE_SAVED, "") : (UiCopy.shared.FAIL, error ?? "")
        }
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}

/// One image of the viewer: pinch (1x to 5x) and pan when zoomed; a double tap toggles 1.5x (RN doubleTapScale).
struct ZoomableImageView: View {
    @Environment(\.motion) private var motion
    let src: String
    let size: CGSize
    let onZoom: (Bool) -> Void
    @State private var scale: CGFloat = 1
    @State private var base: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    var body: some View {
        LoadedImageView(src: src, maxWidth: size.width, contentMode: .scaleAspectFit) { _ in }
            .frame(width: size.width, height: size.height)
            .scaleEffect(scale)
            .offset(offset)
            .clipped()
            .contentShape(Rectangle())
            .gesture(
                MagnifyGesture()
                    .onChanged { v in scale = min(5, max(1, base * v.magnification)) }
                    .onEnded { _ in
                        base = scale
                        if scale <= 1 { reset() }
                        onZoom(scale > 1)
                    }
            )
            .simultaneousGesture(
                DragGesture()
                    .onChanged { v in
                        guard scale > 1 else { return }
                        let maxX = size.width * (scale - 1) / 2, maxY = size.height * (scale - 1) / 2
                        offset = CGSize(width: min(maxX, max(-maxX, lastOffset.width + v.translation.width)),
                                        height: min(maxY, max(-maxY, lastOffset.height + v.translation.height)))
                    }
                    .onEnded { _ in lastOffset = offset },
                including: scale > 1 ? .all : .subviews
            )
            .onTapGesture(count: 2) {
                withAnimation(motion.settle) {
                    if scale > 1 { reset() } else { scale = 1.5; base = 1.5 }
                }
                onZoom(scale > 1)
            }
            .accessibilityAddTraits(.isImage)
            // VoiceOver's zoom (three finger double tap, or the rotor) steps through the same scales as the pinch
            .accessibilityZoomAction { action in
                withAnimation(motion.settle) {
                    switch action.direction {
                    case .zoomIn: scale = min(5, scale + 0.5)
                    case .zoomOut: scale = max(1, scale - 0.5)
                    @unknown default: break
                    }
                    base = scale
                    if scale <= 1 { reset() }
                }
                onZoom(scale > 1)
            }
            .accessibilityIdentifier("gallery-image")
    }

    private func reset() {
        scale = 1; base = 1; offset = .zero; lastOffset = .zero
    }
}
